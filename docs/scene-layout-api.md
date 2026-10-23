# Scene layout API (phase 1 handoff)

`manor.scene-layout` is pure presentation code. It does not read or change a world, run, event,
agent view or cache. `manor.tools.scene-preview` provides only an offline SVG writer. The scene
page now consumes this API (see [scene-web-api.md](scene-web-api.md)); inspectors and
Claudeworth history extraction are implemented in the presentation layer.

## Input and history

```clojure
(require '[manor.scene-layout :as scene])

(def initial
  {:locations [{:id "hall" :name "Hall" :domain :house :floor :ground :kind :hall}
               {:id "study" :name "Study" :domain :house :floor :ground}]
   :routes [{:from "hall" :to "study" :travel 0 :open? true}
            {:from "study" :to "hall" :travel 0 :open? false}]})

(def reservation-boundary
  {:reservations [{:id "morning-room" :name "Morning room" :domain :house
                   :floor :ground :size :large :connects-to ["hall"]}]
   :routes (:routes initial)})

(def completion-boundary
  {:locations [{:id "morning-room" :name "Morning room" :domain :house
                :floor :ground :size :large}]
   :routes (conj (:routes initial)
                 {:from "hall" :to "morning-room" :travel 0 :open? true})})

(def history {:initial initial :steps [reservation-boundary completion-boundary]})
(def result (scene/layout (assoc history :current completion-boundary)))
```

Every topology uses ordered vectors. Location/reservation ids must be non-nil and unique within
one boundary; completed locations replace reservations at a **later** boundary with the same id.
Stable ids are typically strings, but keywords are supported. Size is optional: one of
`:small`, `:medium`, `:large`, `:grand`, or `[positive-integer-width positive-integer-height]`.
Do not copy a future completed room's size back into an earlier generic placement. The temporal adapter must
supply size evidence at the historical reservation boundary where it was actually known.

Routes are **directed records**, never unordered endpoint pairs. `:travel` defaults to 0 and
`:open?` to true. All other keys, asymmetric values, duplicates, closed routes and loops are
preserved. There is no implied reverse route. Only reservations' optional `:connects-to` expands
to two directed display-only zero-travel routes, without replacing supplied directions.
For sizes/floors/grouping, adjacency is undirected zero-travel regardless of openness.

`:initial` contains all pre-round-1 rooms and the complete initial topology. Each `:steps` entry
is a historical placement boundary: records introduced or completed there and the full set of
routes **known then**, including any still-relevant reservation connections. Do not project old
steps with the current graph. A generic room introduced before its routes can use a free spot;
subsequent routes are connector updates, never relocation hints. Process all boundaries even if
no browser was open. `manor.tools.scene-history` and the optional scenario history adapter extract these boundaries from existing records; no new
simulation events, location fields or persistence formats are needed or allowed.

`:current` is optional and affects only metadata and connection rendering. Its records override
historical display metadata (not rectangles or effective floors). With no `:current`, rendering
uses the last boundary's routes. Route-only boundaries do not move rooms. Completion updates
stored metadata, so later route-only boundaries do not resurrect a ghost. The current adapter has no cancellation/deletion event contract; reservations complete at the
matching location event. See the temporal seam in `scene-web-api.md`.

## Geometry and projection

- `normalize-topology` validates input and normalizes reservation connections; nil/empty is valid.
- `place-history history` cold-recomputes immutable `:rooms/:records/:order/:floor-orders` plus an
  evaluation count. `extend-placement placement boundary` produces the same result incrementally.
- `present placement current-topology` packs outer boxes and renders current connections, without
  search. `layout history` combines placement and presentation. No memoization is required.
- Output `:rooms` is keyed by id. `:x/:y/:w/:h` are **panel-local integer grid cells**;
  `:panel` is `[region-id effective-floor]`, `:region` is `[:domain domain]` or `[:area root-id]`.
  `:floor` is the effective panel floor, and `:outside?` preserves outside styling. `:global` is
  the rectangle after outer packing. Unknown metadata survives. Reservations have `:reservation?`.
- `:panels` and `:regions` are keyed maps with global `:x/:y/:w/:h`, ids and panel local bounds.
  A domainless region's label uses its first room's name. Domain labels are the domain value.
- `:bounds` encloses regions. Empty input has no boxes and zero-sized bounds.
- `:connections` contains one entry per normalized directed route, in input order, with `:route`
  unchanged, endpoints, `:kind`, `:path` (wall-endpoint grid polyline), and Euclidean `:length`.
  Kinds: `:door`, `:connector`, `:stair`, `:road`, `:loop`, `:unresolved`. Positive travel always
  renders as a road, even within a region/panel. Cross-region zero travel is also a road.
  Missing endpoints remain explicit `:unresolved` entries (no fabricated geometry).
- `:quality` reports directed connection/door counts, door share among same-panel zero-travel
  door/connector candidates, total non-door line length, unresolved count, bounding area and
  candidate evaluations. It measures real results; it does not promise all tree edges are doors.

Frozen means **local** geometry. Panels pack side by side; regions choose free neighbouring
positions near their shortest already-placed journey partner, with a separated fallback. Outer
boxes and global room coordinates may shift as panels/regions grow or journeys change. Callers
must not mistake outer packing for local relocation, or use SVG/global coordinates as history.

Explicit domains are authoritative, even if disconnected. Domainless connected components use
only domainless zero-travel edges: they cannot absorb explicit domains. At a later boundary a
new domainless component uses its earliest frozen region, if any; previously separate regions
never merge. Floors are basement, ground, first, second, third, attic, then unknown floors in
historical record order. Outside shares ground. A missing floor uses the most common assigned/
explicit same-region neighbour floor, ties by floor order; with no such neighbour it uses ground
if its region has any floors, otherwise nil. Missing-floor inference is one pass, not recursive.
These assignments freeze at placement. Panels are solved ground first, then known/unknown floor
order; stair alignment is a bonus, not a guarantee.

Search uses at most 8 beams × 100 candidates per room; panels over 80 rooms use 2 × 24 and over
200 use 1 × 16. Wall positions are considered in stable neighbour order. There is always a free
fallback one cell beyond the existing bounding box, not touching any old rectangle. Disconnected
clusters have separated positions too. Large graphs intentionally trade door quality for bounded
work. Connectors try two obstacle-free orthogonal bends, then use a straight line; complex line
crossings and overlap between lines are not eliminated.

## Preview and checks

```clojure
;; clojure -M:dev (also exposed as user/scene-preview!)
(require '[manor.tools.scene-preview :as preview])
(preview/scene-preview! "resources/worlds/claudeworth.edn" "/tmp/manor.svg")
;; => {:path "/tmp/manor.svg" :quality {...}}
(preview/svg result) ; escaped SVG string; no server, run or model calls
```

The one-argument helper writes `<source>.svg`; use an explicit writable output for classpath
resources. `world-file-topology` is a preview-only converter using the existing world-file space
conversion. It is not a loaded-run adapter. Preview road labels use rounds, since normalized
input intentionally has no simulation clock. Phase 2 owns richer labels and all interactive UI.

Focused validation: `clojure -M:test -n manor.scene-layout-test`. Tests cover every bundled
scenario, household/world-file fixtures, empty input, stars/cliques/rings/chains/disconnected
rooms, mixed domains/unknown floors/ties, closed/asymmetric/one-way routes, frozen incremental
placement, reservation completion and cache-independent recomputation, escaped/parseable SVG,
and a deterministic 320-room graph. The performance test has an 8-second shared-CI ceiling;
normal local measurements are around one second, with an explicit evaluation-count bound.
