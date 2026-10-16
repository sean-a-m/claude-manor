# Claudeworth phase 2: build brief

Self-contained instructions for implementing phase 2 of `docs/claudeworth-design.md`: money, the
store, plans, the architect MCP server and construction — the rest of the manor-building slice —
plus one general engine change, interruptions. Read `AGENTS.md` first (invariants, run
directories, cost rules), then sections 1, 6, 7, 9, 10 and 12 of the design for intent. This brief
is the authority on scope and details for phase 2; the design describes the whole slice.

Phase 1 is done and committed (`docs/claudeworth-phase1.md`, `docs/claudeworth-phase1-fixes.md`,
`docs/next-steps.md`). Mr Pugh is already in the scenario and can live in the village, talk and
pass the time; he has no building role yet.

Work in this order: **(1) interruptions** (engine), **(2) the architect server**, **(3) the
scenario** (money, store, plans, construction, views, briefs), **(4) the mock day and tests**,
**(5) one short real run**, **(6) docs**. Run `clojure -M:test` after each step.

## Scope

**In:**
- interruptions: `rouse` interrupts a busy agent, who gets a turn and decides whether to stop
  (a small, general engine change; see "Interruptions")
- money: the household account, the architect's private purse and his 10% fee
- the store: `buy`
- plans: `consult-references`, `price-list`, `draw-plans`, `show-plans`, `commission`
- the architect MCP server in babashka (`mcp-servers/architect/`), with its price list in a data
  file the offline stub also reads
- construction after the build delay, and the run option `:build-rounds`
- `:observe` additions (`:household`, `:building`, `:plans`, the shop's prices, the architect's
  purse)
- brief updates, an extended mock day, tests, one short real run, doc updates

**Out (phase 3):** an inn, hunger and fatigue, rents, hiring servants, abbey bells, the access
matrix, spending rules, architect hibernation, the reference cleanup. Don't build them, don't
design them.

## Decisions already made (don't revisit)

- **Money:** one household account; the **earl, countess and secretary** all spend from it
  freely. The architect has a **private purse** and earns **10% of each commission on a plan he
  drew**. Integer pounds throughout. Plans the clerk drafts (no architect drew them) earn no fee.
- **Store:** `buy` at a location whose `:shop` list has the item, during its `:hours`, paid from
  the household account and into the household stores. Eating provisions is phase 3.
- **Plans, then commission:** drawing is a *query* (calls the server; the drafted plan is stored
  as an event). `commission` is a *final* action with no bridge.
- **One commission at a time per plan:** a plan whose works are in progress can't be commissioned
  again. Once it is built it can be (a second room, with a fresh id: `library-2`).
- **Architecture is free-form.** The design of the room is the agents' business; code enforces
  only what keeps the world consistent (the list under `draw-plans`). An "odd" room (a bathroom
  joined to the dining room and the gallery) must go through.
- **The architect's office is marked in the world file** (`:services #{:architect}` on the
  location), never found by id in code.
- **The price list lives in one data file**, `mcp-servers/architect/prices.edn`. The server and
  the offline stub both read it, and `draw-plans`' size enum is derived from it. Contents:
  `small` £200, `medium` £500, `large` £1200, `grand` £3000; build days 1 (2 for `grand`). The
  world file's `:meta :build-days` goes: build days come from the plan.
- **Build delay:** `rounds-per-day × the plan's build days` (one day by default). The run option
  `:build-rounds` overrides it for tests and cheap checks. `:start` already lets a run begin near
  a time of day.
- **Busy people are interrupted, not woken.** One general mechanism instead of a rule per case:
  `rouse` interrupts anyone asleep or busy in the same room; they get a turn and choose to
  `carry-on` or stop. So a client who finds Mr Pugh "at his drawing board" rouses him, and the
  plan rules need nothing special for it.
- **Queries may span turns.** `:max-queries` stays 2 per turn. An agent that wants three
  (references, prices, a draft) does two this turn and the rest next turn. What a reference or
  price query found is recorded privately for the caller, so it is still in their prompt next
  turn (see "Plans").
- **The server** is a stateless babashka project started as `bb -m architect.server`, consulted
  over stdio. It refuses only structural impossibilities. The reference library is the Ashcombe
  documents; reference cleanup is deferred (design decision 9), so the server returns only the
  architectural fields/sections.
- **Offline:** an offline stub inside the scenario with the **same three tools and result
  shapes**. Tests also use the **real** server (deterministic, free, starts in milliseconds).
- **`price-list` is an action too.** The design's §7 table lists only `consult-references`,
  `draw-plans`, `show-plans`, `commission`; this brief exposes the server's `price_list` as a query
  action so it is reachable from play.
- **Engine changes:** interruptions, as specified below, and optionally the query-limit wording
  (below). If anything else seems necessary, keep it small, general and tested, and report it.
  Either way, check that village prompts stay byte-identical (load `runs/smoke-llm`, compare
  `prompt/agent-messages` and `prompt/gm-messages` before and after, e.g. with `git stash`; never
  write under `runs/`).

## What phases 0 and 1 give you (use it, don't rebuild it)

- **`manor.worldfile/load-world`** → `{:space :clock :agents :world-file}`; `:init` adds `:state`.
  The scenario is `src/manor/scenarios/claudeworth.clj` (split out a `building.clj` if it passes
  ~600 lines), the world file is `resources/worlds/claudeworth.edn`.
- **Clock:** `clock/label`, `clock/short-label`, `clock/round-at`, `clock/rounds-for`,
  `clock/rounds-per-day`, `clock/hh-mm?`.
- **Space:** `space/location`, `space/location-of`, `space/present`, `space/here`,
  `space/together?`, `space/area`, `space/exits`, `space/route`, `space/path`,
  `space/distance`. Areas are the locations' `:domain`s.
- **Occupation:** `b/occupy`, `b/free`, `b/releases`, `w/busy` (sleep, pastimes, rouse).
- **Actions:** phase 1's `walk`, `travel`, `sleep`, `spend-time`, `rouse`, the overridden `pass`,
  and the rule/brief functions (`rules`, `agent-brief`, `gm-brief`, `about`, `setting`).
- **Bridges and MCP:** `manor.bridge`, `manor.mcp`; the contract is in
  `docs/scenario-authoring.md` → "`:bridges`", "MCP bridges", "`:systems`". Copy the shape from
  `village.clj` (weather system, `:on-result`) and `quiz.clj` (MCP bridge, `:mcp-servers`, and an
  `:on-result` that records a private event so the caller remembers the result). Run options
  `:bridge-stubs {bridge (fn [args ctx])}` replace a bridge, which is how a test feeds a bad draft.
- **What a bridge returns:** `manor.mcp/call-tool` gives the tool's `structuredContent` if it has
  any (a map with keyword keys, decoded verbatim, so `"connects-to"` → `:connects-to`), otherwise
  its text. A tool result with `isError: true` becomes `{:error "<the tool's text>"}`, and the model
  is told `error: <text>`; `:on-result` isn't called. A result longer than the bridge's
  `:max-chars` (measured with `pr-str`) is replaced by a truncated **string**.
- **Within a turn the prompt isn't rebuilt:** the view and events are fixed when the turn starts;
  later calls only add the engine's replies. What an agent learns mid-turn must be in a reply.
- **`map.edn`:** the engine already rewrites `<run-dir>/map.edn` whenever a `:space/...` event is
  emitted, so a built room needs no export code. The space event shapes are:
  - `{:type :space/location-added :data {:location {:id .. :name .. :desc .. ...}}}`
  - `{:type :space/route-opened :data {:from .. :to .. :travel 0 :one-way? false}}`
- **Commands:** `clojure -M:dev:run claudeworth --mock --offline --test`, `clojure -M:test`,
  `clj -M:dev`. Babashka v1.13.226 is installed, with `clj-yaml` and `cheshire` built in.

## Files to create or change

```
src/manor/builtins.clj                        b/interrupt; the carry-on action
src/manor/world.clj                           :agent/interrupted, :agent/resumed reducers
src/manor/engine.clj                          interrupted agents take turns; ending the occupation
src/manor/narrate.clj                         narration for the two events
src/manor/prompt.clj                          :you :busy for an interrupted agent
test/manor/engine_test.clj                    interruption tests (hamlet)
test/manor/scenarios/hamlet.clj               its rouse uses b/interrupt
resources/worlds/claudeworth.edn              :shop, :meta :starting-stores, :services on the office; drop :meta :build-days
src/manor/scenarios/claudeworth.clj           rouse; money, store, plans, construction, views, brief, mock
src/manor/scenarios/building.clj              new, only if claudeworth.clj outgrows ~600 lines
mcp-servers/architect/bb.edn                  new
mcp-servers/architect/prices.edn              new: the price list (server and stub)
mcp-servers/architect/src/architect/server.clj       MCP stdio loop and tool dispatch
mcp-servers/architect/src/architect/references.clj   reference library and search
mcp-servers/architect/src/architect/drafting.clj     price list and drafting
test/manor/claudeworth_test.clj               the slice, the refusals, the odd room, the clerk path
test/manor/scenarios_test.clj                 :claudeworth is already in `bundled`; no change expected
README.md                                     say what the scenario can now do
AGENTS.md                                     layout: mcp-servers/architect/ and the new files
DESIGN.md                                     occupation row and round steps: interruptions
docs/scenario-authoring.md                    occupation section: b/interrupt and carry-on
docs/claudeworth-design.md                    status line; phase 2 marked done, with deviations
```

## Interruptions (engine, do this first)

Today `rouse` frees its target at once (`b/free`). Replace that with an interruption the target
answers. General, for any scenario with occupation:

- **`(b/interrupt world id by)`** → `{:type :agent/interrupted :actor id :visible-to (conj
  (space/here world id) by) :data {:activity .. :by by}}`. The reducer sets `[:agents id :busy
  :interrupted-by]` to `by`. Narration: "Hale interrupts Lord Claudeworth (asleep)."
- **Turns:** `engine/schedule` and the filter in `run-group!` skip busy agents *unless* they have
  `:interrupted-by`. An interruption made during a round is answered next round (the schedule is
  fixed at round start), as rousing is today. `b/releases` still frees an agent whose time is up,
  interrupted or not.
- **The interrupted turn:** the agent's view shows `:you :busy {:activity "asleep" :until "Sun
  07:30" :interrupted-by "Hale"}` (names and clock labels, not ids and rounds). Only interrupted
  agents are ever prompted while busy, so no other prompt changes.
- **`carry-on`** (built-in, `:final`, `:all`, `:available?` only when the actor is busy and
  interrupted): "Ignore the interruption and carry on with what you were doing until it is
  over." Effect: `:agent/resumed`, visible to `space/here`; the reducer removes `:interrupted-by`
  and keeps `:until`. Narration: "Lord Claudeworth carries on (asleep, until Sun 07:30)."
- **Stopping:** the interrupted agent's first *accepted* action other than `carry-on` — free,
  query or final — ends the occupation: the engine emits `(b/free world id by)` just before that
  action's own events (and before a query's bridge call), so the agent is no longer busy when the
  action applies. A rejected or undecodable call doesn't end it. So `say`, `walk` or `pass` all
  mean "I stop", and `carry-on` means "I don't".
- **No decision:** if the turn ends with no accepted action (a forfeit), the engine emits
  `:agent/resumed`, so the agent carries on and isn't prompted every round.
- Scenario `rouse` actions call `b/interrupt`; `b/free` stays as the primitive for freeing
  someone outright. Claudeworth's `rouse` and the test scenario `hamlet`'s both switch, and
  `rouse` rejects a target who is already interrupted ("Lord Claudeworth has already been
  interrupted"). Claudeworth's `rouse` doc: "Interrupt someone here who is asleep or busy (use
  their id). They get a turn and decide whether to stop."
- **Mock policies** see interrupted agents too: Claudeworth's should `carry-on` unless the script
  has a line for that time. Hale's 06:30 rouse then still has the earl up at 07:00: his scripted
  07:00 `say` ends his sleep.
- **Tests** (`engine_test`, with hamlet): an interrupted agent gets a turn next round and no
  other busy agent does; `carry-on` keeps `:until` and clears the flag; any other accepted action
  frees them first (the `:agent/freed` event names the interrupter) and a rejected one doesn't; a
  forfeit resumes; occupation still ends on time; replay equals the live world. Update the
  existing rousing tests in `engine_test` and `claudeworth_test`.
- Village prompts must stay byte-identical (it has no occupation; `carry-on` is never available).

Optional, also engine: the query-limit reply could say when to try again, e.g. "error: query
limit reached for this turn (2); take your action now, and query again next turn".

## The architect server

`mcp-servers/architect/`: `bb.edn` with `:paths ["src"]`; `src/architect/{server,references,
drafting}.clj`; `prices.edn`. Newline-delimited JSON-RPC on stdio: `initialize`, `tools/list`,
`tools/call`, about 80 lines for the loop with `cheshire`. Stateless: everything comes from the
arguments, `prices.edn` and the reference directory (`ARCHITECT_REFERENCE_DIR`, default
`../../external-resources/` relative to the server's directory). Reading the reference files once
at start-up is fine.

**Every tool returns its result as `structuredContent` (a JSON object) and the same JSON as a
text block.** Payload keys are kebab-case (`"connects-to"`, `"build-days"`), matching the
project's Clojure; this deviates from the design's snake_case and should be noted there.
Refusals are `isError: true` with a text that says how to fix the request.

- **`consult_references {query}`** → `{"matches": [{"source": .., "title": .., "text": ..}]}`, the
  best few by keyword score, capped at about 2500 characters in all. The library: the locations in
  `ashcombe_world.yaml` (only name, floor, zone, connects-to and description; parsed with
  `clj-yaml`) and the sections of `ashcombe_spec.md` about the buildings (§6, §10.2, §10.4), split
  by heading. Nothing about staff counts, precedence or etiquette.
- **`price_list {}`** → `{"sizes": [{"size": "small", "price": 200, "build-days": 1}, ...]}`,
  straight from `prices.edn`.
- **`draft_plans {house, taken, request: {name, floor, desc, connects-to, size}}`** →
  `{"plan": {"id", "name", "floor", "desc", "connects-to"}, "price", "build-days"}`. It tidies the
  strings, derives the id from the name (kebab-case) and makes it unique against `taken`
  (`library`, `library-2`), and prices it from `prices.edn`. It refuses only structural problems:
  an empty name, an unknown size, a `connects-to` room not in `house`.

## The world file

One home per fact: prices of goods, item names, starting stores and the office marker live here,
not in code.

```clojure
:meta {... :starting-funds 3000 :starting-stores {"provisions" 2} ...}   ; no :build-days

{:id "architects-office" ... :services #{:architect} :hours ["09:00" "18:00"] ...}

:shop {"general-store" [{:item "provisions" :price 1 :desc "a week's groceries for the household"}
                        {:item "candles"    :price 1}
                        {:item "coal"       :price 2 :desc "a ton"}]}
```

`claudeworth/problems` should also check: every `:shop` key is a location with `:hours`; every item
is a non-empty string with a positive integer `:price`; every `:starting-stores` item is sold
somewhere; `:services` is a set of keywords; and at least one location with `:services
:architect` has `:hours`. Keep the existing checks.

## State, events and reducers

Read the world file's money and shop into `:state` at `:init`:

```clojure
:state {:household {:funds  (get-in wf [:meta :starting-funds])
                    :stores (or (get-in wf [:meta :starting-stores]) {})}
        :shop    (:shop wf)                       ; location id -> [{:item :price :desc}]
        :purses  (into {} (for [a agents :when (= :architect (:role a))] [(b/slug (:name a)) 0]))
        :plans   {}                               ; plan-id -> plan
        :works   []                               ; commissions in progress
        :build-rounds (:build-rounds opts)        ; override, or nil
        ...phase 1 keys...}
```

New events, all namespaced `:claudeworth/`, each with a reducer (`w/apply-event`) and a
`narrate/describe` that reads correctly for everyone who can see it:

| Event | Data | Visible to | Reducer |
|---|---|---|---|
| `:claudeworth/bought` | `{:location :item :qty :price}` | `space/here` | `:household :funds` − price; `:household :stores` item + qty |
| `:claudeworth/consulted` | `{:query :matches}` | the caller | none (a record, so the caller remembers it) |
| `:claudeworth/prices-quoted` | `{:sizes}` | the caller | none |
| `:claudeworth/plans-drawn` | `{:plan ..}` | drawer and client | assoc the plan at `[:state :plans (:id plan)]` |
| `:claudeworth/draft-refused` | `{:reason}` | the caller | none |
| `:claudeworth/plans-shown` | `{:plan-id :to [ids]}` | `space/here` | add `:to` to the plan's `:holders` set |
| `:claudeworth/commissioned` | `{:plan-id :room :name :by :price :due :fee :payee :connects-to}` | `:all` | funds − price; payee's purse + fee; conj the work onto `:works` |
| `:claudeworth/works-completed` | `{:plan-id :room}` | `:all` | remove the work whose `:room` this is; mark the plan `:built? true` |

The narration of `consulted` and `prices-quoted` shows what was found, clipped to a length kept in
one `def` (about 600 characters), so the caller still has it next turn. The narration of
`commissioned` names who commissioned what and beside which rooms, but no fee and no purse (those
are private); the GM and operator see the data anyway.

A plan is plain data (floors are keywords, as in the world file):

```clojure
{:id "library" :name "Library" :floor :ground :size "large" :desc "..."
 :connects-to ["study"] :price 1200 :build-days 1
 :drawn-by "pugh" :for "lord-claudeworth" :holders #{"pugh" "lord-claudeworth"}}
```

A work is `{:plan-id .. :room .. :name .. :due <round> :connects-to [..] :by <id>}`. Works are
identified by `:room`.

## The store: `buy`

| Field | Value |
|---|---|
| name / kind / roles | `buy` / `:final` / `#{:earl :countess :secretary}` |
| args | `[:map [:item :string] [:qty [:int {:min 1 :max 20}]]]` |
| `:valid?` | at a location with a `:shop` entry; open now; the item is sold here; funds ≥ `price × qty` |
| `:effect` | `:claudeworth/bought`, `:visible-to (space/here world actor)` |

Reasons are actionable and name things from the world: `"the General store sells: provisions
(£1), candles (£1), coal (£2)"`, `"the General store is closed; it is open 08:00-18:00"`,
`"3 coal costs £6; the household has £4"`. The `:doc` says only mechanics: at a shop, open hours,
paid from the household, goods to the stores.

## Plans

"At an office" below means: at a location whose `:services` include `:architect`, during its
`:hours`. "The architect" is an active agent whose role is `:architect` and who is at that
location, **busy or not**: someone who finds him busy rouses him (see "Interruptions").

### Bridges

```clojure
:mcp-servers {:architect {:command ["bb" "-m" "architect.server"] :cwd "mcp-servers/architect"}}

:bridges {:architect/references {:doc "Search the architect's reference books"
                                 :mcp {:server :architect :tool "consult_references"}
                                 :timeout-ms 20000 :max-chars 4000}
          :architect/prices     {:doc "The firm's price list for new rooms"
                                 :mcp {:server :architect :tool "price_list"}
                                 :timeout-ms 20000 :max-chars 2000}
          :architect/draft      {:doc "Draft a room and price it"
                                 :mcp {:server :architect :tool "draft_plans"}
                                 :timeout-ms 20000 :max-chars 6000}}
```

`:offline-stubs` provide the same three bridges with the same result maps (keyword keys, as the
MCP client would decode them): a canned match or two, the price list read from
`mcp-servers/architect/prices.edn` (a path relative to the working directory, like the server's
`:cwd`), and a straightforward draft that tidies the strings, derives an id unique against
`taken`, prices it from the same file and throws (→ `{:error ..}`) on the same structural
problems. `{:offline? true}` runs must not start the server. The `draw-plans` size enum is read
from the same file when the namespace loads.

### `consult-references {query}`

| Field | Value |
|---|---|
| kind / roles | `:query` / `:all` |
| args | `[:map [:query [:string {:min 2 :max 200}]]]` |
| `:valid?` | at an office, open now |
| bridge | `:architect/references`, args `{:query query}` |
| `:on-result` | `:claudeworth/consulted`, visible to the caller |

### `price-list {}`

`:query`, `:all`, at an office and open now, bridge `:architect/prices`, no args. `:on-result`:
`:claudeworth/prices-quoted`, visible to the caller.

### `draw-plans {for name floor desc connects-to size}`

| Field | Value |
|---|---|
| kind / roles | `:query` / `:all` (role logic in `:valid?`) |
| args | `[:map [:for {:optional true} :string] [:name [:string {:min 1 :max 60}]] [:floor :string] [:desc [:string {:min 1 :max 600}]] [:connects-to [:vector {:min 1 :max 4} :string]] [:size (into [:enum] sizes)]]` |
| `:valid?` | see below |
| bridge | `:architect/draft` |

`:valid?`, in this order, every reason actionable:
- at an office, open now
- who draws: if the actor is the architect, `for` may name a client **present** in the office
  (default: the actor). If the actor isn't the architect and an architect is in the office:
  `"Mr Pugh draws the plans here; ask him"` (his name from the world). Otherwise (the clerk path)
  `for` must be the actor or absent.
- `floor` is a floor of a house room other than `:outside` (`"floor must be one of: ground,
  first"`, from the map); the action converts it to a keyword
- every `connects-to` id is a location with `:domain :house` (`"conservatory is not a room of the
  house; use: carriage-sweep, dining-room, ..."`)

So the usual mistakes are refused before the server is called, and the model hears why in the same
turn. The server's own refusals are a second line.

`:bridge-args` builds all of `{:house [..] :taken [..] :request {:name .. :floor .. :desc ..
:connects-to .. :size ..}}` from the world, never from the model:

```clojure
:house (for [[id loc] (get-in world [:space :locations])
             :when (= :house (:domain loc))]
         {:id id :name (:name loc) :floor (:floor loc)
          :connects-to (vec (for [e (space/exits world id) :when (zero? (:travel e))] (:to e)))})
:taken (distinct (concat (keys (get-in world [:state :plans]))
                         (keys (get-in world [:space :locations]))
                         (map :room (get-in world [:state :works]))))
```

(The `:for` key shadows `clojure.core/for` in a destructuring; read it with `(get args :for)` if
the body uses `for`.)

Because `taken` goes to the server, the id the model sees in the draft is the id that is stored:
there is no renaming afterwards.

`:on-result` treats the draft as **untrusted input**: it must be a map; the id must be a fresh
kebab-case id not in `taken`; name and desc within their lengths; the size one of the price list's;
every `connects-to` an existing house room. On a bad draft, store nothing and emit
`:claudeworth/draft-refused` to the caller (they read it next turn; it can only happen if the
server misbehaves). On a good draft, store the plan with `:floor` as a keyword, `:drawn-by` the
actor, `:for`, `:holders #{drawn-by for}`, and the price and build days from the draft, as
`:claudeworth/plans-drawn` `:visible-to #{drawn-by for}`.

`show-plans`, `commission` and the view all read the stored plan, so the server is called once.

### `show-plans {plan-id}`

`:free`, `:all`, anywhere. `:valid?`: the plan exists and the actor is one of its `:holders`
(`"you have not seen those plans"`), and someone else is present. Effect:
`:claudeworth/plans-shown` with `:to` = the ids present, `:visible-to (space/here world actor)`.
Narration reads as spreading the plans out for the room to see.

### `commission {plan-id}`

| Field | Value |
|---|---|
| kind / roles | `:final` / `#{:earl :countess :secretary}` |
| args | `[:map [:plan-id :string]]` |
| `:valid?` | at an office, open now; the plan exists; the actor holds it; no works of this plan are in progress (`"the Library is already being built; it will be finished Sunday 10:00"`); `funds ≥ price` (`"the Library would cost £1200; the household has £900"`) |
| `:effect` | `:claudeworth/commissioned`, `:visible-to :all` |

Compute at commission time:
- `delay = (or (:build-rounds state) (* (clock/rounds-per-day clock) (:build-days plan)))`
- `due = (:round world) + delay`
- `room` = the plan id, made unique against existing locations and the works' rooms (it differs
  only when a built plan is commissioned again)
- `fee` = if the plan's `:drawn-by` is an agent whose role is `:architect`,
  `(quot (* price fee-percent) 100)`, else 0; `payee` is that id (nil when there is no fee).
  `fee-percent` is one `def` (10).

## Construction

A scenario `:system` runs at the start of every round, before agents act:

```clojure
{:name :construction
 :tick (fn [world _]
         (mapcat (fn [{:keys [plan-id room name due connects-to]}]
                   (when (<= due (:round world))
                     (let [plan (get-in world [:state :plans plan-id])]
                       (concat
                        [{:type :claudeworth/works-completed :visible-to :all
                          :data {:plan-id plan-id :room room}}
                         {:type :space/location-added
                          :data {:location {:id room :name name :desc (:desc plan)
                                            :domain :house :floor (:floor plan) :kind :room}}}]
                        (for [to connects-to]
                          {:type :space/route-opened
                           :data {:from room :to to :travel 0 :one-way? false}})))))
                 (get-in world [:state :works])))}
```

- The reducer removes the work, so it fires once.
- The location has no `:connects-to`: the routes are the record of what joins what, and the
  export derives `:connects-to` from them.
- The space events keep the engine's default visibility (`:gm`). Agents learn of the room from
  the `works-completed` announcement ("Builders finish the Library, beside the Study.") and find
  it in `places` and `walkable`, which are computed from the map. Showing the space events too
  would put "Route open: ... (next door)" lines in every feed.
- `map.edn` follows automatically.

## Views

Engine sections (`:time`, `:you`, `:location`) are added automatically. Add to `observe`:

- **`:building`** (everyone, only when there are works): `[{:name "Library" :beside ["Study"]
  :finish "Sunday 10:00"}]` — names from `space/location`, the finish time from
  `clock/short-label` of the due round. This is the design's "builders at work beside the
  entrance hall, finishing Sunday at 10:00". (The design put works under `:household`; they are
  public here because a commission is village news. Note it as a deviation.)
- **`:household`** for `#{:earl :countess :secretary}` only: `{:funds 2880 :stores {"provisions" 3
  "coal" 1}}`. The architect never sees it.
- **`:you :purse`** for a role `:architect`: his own purse from `:state :purses`.
- **`:plans`** for the plans where the agent is a `:holder`: compact maps
  `{:id :name :size :floor :price :connects-to :status}` where `:status` is `"drawn"`,
  `"being built"` (a work exists) or `"built"`.
- **`:here`** for a shop: add `:sells [{:item :price} ...]` (keep the existing `:open` /
  `:open-now?`). For the office, nothing new is required.

Keep the whole view compact; it is sent on every model call.

## Briefs

Keep the one-home-per-fact rule. The setting, role flavour and personas stay in the world file
(`:meta :setting`, `:roles`, `:agents`); the rules stay in code with their numbers.

- **Drop "Nothing can be built yet"** from `rules`, and add a building paragraph:
  - the family and secretary spend from the household account (`:household` in the view) and can
    `buy` at a shop while it's open
  - building is arranged **at the architect's office, while it's open**, not at the house: there
    you may search the reference books (`consult-references`), ask for the price list
    (`price-list`), have plans drawn (`draw-plans`), show plans you hold to the room
    (`show-plans`, anywhere) and commission a plan you hold (`commission`). Talk at the house is
    for deciding what you want. (In the real runs so far the family kept asking Mr Pugh to bring
    drawings to the Manor; without this line nothing gets commissioned.)
  - the cost comes out of the household account, and the room appears after the build time shown
    under `building`; the architect earns `fee-percent`% of a commission on plans he drew
  - queries are limited per turn; carry on with them next turn
- **Update the occupation sentence** for interruptions: others can `rouse` you while you're asleep
  or busy; you then get a turn, and `carry-on` ignores them while anything else means you stop.
- Update the world file's role paragraphs where phase 1's text is now false or thin: the architect
  can now consult, draw and earn. Keep them flavour, not rules.
- **Tool `:doc`s stay mechanics-only**: no place names, hours or prices in them; point at
  `:household`, `:plans`, `:here`, `:building` and `places`.

## The mock day

Extend the scripted day so the whole slice runs free and offline, and keep the phase 1 beats
(breakfast, the earl's visit, the countess at the abbey, Hale's letters, Pugh's drawing board,
bed, the 06:30 rouse). The mock policy may read `world` (it already does for `way-to-bed`), so it
can find the plan drawn for the earl by looking at `:state :plans`.

Required beats:
- the earl (or Hale) travels to the office during office hours
- Mr Pugh consults the reference books (and the price list) in one turn, then draws a plan for the
  earl in a **later** turn, so the cross-turn record (`:claudeworth/consulted`) is exercised
- the plan is shown; the earl commissions it
- Hale buys something at the general store
- the day continues and the room is found where it was built
- With `:architect? false` the same script must work through the clerk path. Branch in the policy
  on whether an architect agent is present rather than writing a second script.
- Interrupted agents `carry-on` unless the script has a line for them at that time.

Rules the phase 1 script already follows, and which now matter more: use the time of day (not
round numbers), return a turn-ending action after a free or query call (`used` lists this turn's
tools), and only choose valid actions — the smoke test fails on forfeits. The round-robin order is
the earl, the countess, Hale, Mr Pugh: in a round, Mr Pugh acts after the earl.

## Tests (`test/manor/claudeworth_test.clj`)

Keep the phase 1 tests (world, walking, sleep, privacy, briefs from the world file, move the
abbey, `:architect? false`), update the mock-day and rousing expectations, and add:

- **The store:** `buy` moves money and stores; rejects an unknown item, a closed store, and too
  little money (assert on the `:valid?` reason text where it matters).
- **Plans and refusals** (pure or small runs): `draw-plans` outside the office and outside hours;
  a client refused while the architect is present (also while he is busy); the clerk path with
  `:architect? false`; an unknown `connects-to` and a bad floor refused in `:valid?`; a bad draft
  from a stub (`:bridge-stubs`) is not stored and the caller gets `draft-refused`; `commission`
  with too little money; `commission` again while the works are in progress; `commission` again
  after it is built (a second room, `library-2`); `show-plans` by a non-holder; `commission` by a
  non-family role.
- **Queries span turns:** after `consult-references`, the caller's next prompt contains what was
  found; nobody else's does.
- **The full slice**, with `{:llm :mock :offline? true :build-rounds 6}`: run until after the due
  round and assert
  - a `:claudeworth/commissioned` event with the price and the due round,
  - the household funds fell by the price and the architect's purse rose by exactly 10%,
  - the room did **not** exist in `(space/location world "library")` before the due round and does
    after, with a keyword `:floor`,
  - `:space/route-opened` with `:travel 0` joins it to the rooms it was built onto, so it is in
    `(space/area world "study")` and can be `walk`ed into,
  - `map.edn` in the run directory contains the room.
- **The same slice against the real server** (`:offline? false`): the plan is drafted by
  `mcp-servers/architect/`. Guard with a `bb-available?` helper (e.g. `clojure.java.shell` running
  `bb -e ""`, catching the exception if `bb` is missing) and skip with a printed note if `bb` is
  absent. Also drive the three tools directly through `manor.mcp` in one test so the server's
  contracts are covered (references text without staff or precedence fields, the price list equal
  to `prices.edn`, a draft with a derived id that avoids `taken`, a refusal as `isError`), and check
  that the stub and the server give the same price list and the same draft for one request.
- **The odd room:** a bathroom joined to the dining room and the gallery (two rooms, different
  floors) is drawn, commissioned and built.
- **`:architect? false`:** the clerk path completes the slice with no fee and no forfeits.
- **Replay:** replaying the run's events equals the live world (phase 1 already checks this; make
  sure the new events replay).

`clojure -M:test` must pass in full and stay free and offline (`bb` is fine: deterministic and
local). Do not let a missing `bb` fail the suite.

## The real run

After the mock slice passes, do **one** short real run, in the foreground, per "Real runs are
short by default" in `AGENTS.md`:

```clojure
(e/start-run :claudeworth {:test? true :build-rounds 4 :max-rounds 12 :max-cost 0.10})
;; then (e/auto! run 12), (e/wait-idle run), and read test-runs/<id>/transcript.md
```

`:build-rounds 4` (two hours) gives a commission made by about 12:00 the chance to be built
within the 12 rounds. It must be under `test-runs/`. Read the transcript (and `llm.edn`): count
`error:` / `rejected:` tool replies and `:action/forfeited` events, watch for wrong ids, refusals
that read badly, rouses and how they're answered, models that never find the office, or a
commission that never happens. Fix briefs, docs and `:valid?` reasons where they stumble. If a
fix needs checking, one more run capped at **$0.05** is allowed. **Total real spend for this phase
must stay under $0.20.** Copy the run directory to `old-test-runs/` before the next
`clojure -M:test` deletes it. Only the architect server calls no model.

Then update `docs/claudeworth-design.md` (phase 2 done, with deviations) and `docs/next-steps.md`
(what's left: phase 3, hibernation and the reference cleanup, both deferred by the user to after
phase 2).

## Don't

- write to `runs/` or delete anything there; tests and checks use `:test? true`
- commit; the parent session reviews and commits
- start phase 3 work
- change the Ashcombe documents in `external-resources/` (read-only; reference cleanup is
  deferred), or let the server return staff counts, precedence or etiquette
- plan a whole-day real run, or raise a sub-agent timeout to fit one
- print the OpenRouter key

The read tool can't open images in this environment. Don't rely on viewing PNGs.

## Final report (return this)

1. Files created and changed, one line each.
2. Test result: the `clojure -M:test` summary line.
3. The village prompt comparison (byte-identical, or what differs and why).
4. Real run: cost, calls per agent, errors/rejections/forfeits, whether the room was built, how
   rouses were answered, a short account of the day, and what you changed in response.
5. Any deviation from this brief and why, any engine change beyond interruptions, and open
   questions.
