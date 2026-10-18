# Stores, aspects and the scene judge: design

**Status:** Phases 0–5 done, including the phase 5 real run; phase 6 fixes done (mock-tested). Each phase below has its own status line; update it as work lands.

Read `AGENTS.md` first (invariants, run directories, cost rules), then "Items (`manor.items`)" and
"Work and the judge" in `docs/scenario-authoring.md`, and `src/manor/items.clj`. This document
replaces `docs/archived/room-conditions-brief.md`: its R1/R2 are folded into phases 1 and 5, and
its R3 (rooms getting dirty again) is answered by phase 2 (judged pastimes: use makes mess).

## Why

Item tracking in Claudeworth has turned into Dwarf-Fortress-style item management. The
evidence is `runs/claudeworth-20261017-172705` (48 rounds, $0.47). It predates the consumables
and one-parcel commits, which already fixed the twenty separate flour items and gave the judge a
way to use things up. What's left is structural:

- **Listed items read as everything there is.** The world file starts the pantry as
  `pantry-shelves: "clean, empty"`. After the countess's stocktake the pantry held only what was
  bought that day. When Daisy looked she saw "butter, mutton, salt, tea, oatmeal, flour and
  sugar", and the household concluded there was no mustard, no pickles, no roots and no dripping
  tin. By dinner that had become a request to her ladyship for a standing order.
- **Utensils as items cause errands.** The kettle and saucepans exist as items, so the
  buttered paper, the cloths and the dripping tin "don't exist". Daisy carried the kettle and
  saucepans round the house.
- **Story contradicts state, and nobody can check.** Hobbs said the beef and mutton were "set
  by for your maid to call for", but `buy` had already handed them to Lady Claudeworth. Hobbs
  noted his version, and from 17:00 Hale was chasing a parcel "still wrapped by his door".
- **Salient things linger.** The rib of beef, a single item from purchase through roasting,
  carving and the stock pot, was handled by eight jobs and several `take`/`put-in` calls, and
  stayed in the world after dinner.
- **`household.stores {"provisions" 2}` means nothing.** It was in every agent prompt (1,070
  times in that run), and no code reads it.
- **Judging is cheap; latency isn't.** 41 judge calls cost $0.017 of $0.47. Jobs are judged one
  after another at the start of each round.

## The idea

Stuff in the world comes in two tiers:

- **Things**: tracked items that matter individually (the silver, the china, the range, tonight's
  joint, a parcel someone carries).
- **Stocks**: everything ambient, described by the aspects of a **store**, e.g. the pantry
  shelves "well stocked with the usual staples", lacking "mustard". Anything a store keeps is
  assumed present unless its aspects say otherwise; only notable exceptions are recorded.

Stuff moves between the tiers by **promotion** (drawn from a store, found, bought, made) and
**demotion** (absorbed into a store, used up). A narrow LLM judge decides; engine code checks
every change. The characters are told that everyday things can be assumed to be to hand.

## Decisions (the user's, 2026-10-17)

- **D1. Items stay.** Containers stay too, mainly for hiding or shutting things away.
- **D2. Conditions become aspects:** a small map of keyed facets with free-text values
  (`{:cleanliness "dusted" :repair "one glass eye loose"}`), not one phrase and not fixed tags.
  The judge changes one aspect at a time, so unrelated facts survive.
- **D3. `spend-time` and `work-on` merge** into one judged timed activity. If the judge proves
  too eager (making things because someone was "resting in the garden"), adjust.
- **D4. Stores are fixed items with a `:store` field.** A store stands for untracked stuff. A
  container holds tracked things; an item may be both.
- **D5. Store and room aspects show in the per-turn view**, without `look`.
- **D6. A nightly steward pass at 02:00** tidies up loose consumables. Code chooses what it
  sees (the user's concern was a pass over every object).
- **D7. `find` is a separate tool**, not an argument to `look`.
- **D8. One parcel per purchase**, as now, with its description saying roughly what it holds.
- **D9. The provisions counter is removed.** If provisions become a real mechanic later
  (hunger, phase 3 incentives in `claudeworth-design.md`), design it then, probably as a level
  code can read on the pantry's stock aspect.
- **D10. Old runs aren't migrated** (already the project's policy: `roadmap.md` B7). Old
  `events.edn` files need not replay after phase 1.

## Data model (target)

### Aspects

On items and on locations, replacing an item's `:condition`:

```clojure
:aspects {:cleanliness "dusty" :repair "one glass eye loose"}
```

- Keys are keywords matching `^[a-z][a-z-]{0,23}$`; values are strings of 1–80 characters
  (`items/max-condition` becomes `max-aspect`). At most 4 aspects per thing (`max-aspects`).
  `:aspects` is optional; a thing with nothing notable has none.
- In the judge's JSON, keys are strings; the decoder normalises snake_case to kebab-case, as
  `actions/decode-args` does for arguments.
- A scenario may suggest keys: `:items {:aspect-keys {:cleanliness "how clean" ...}}`. The
  suggestions go into the judge's prompt. The judge may coin other keys; the engine checks only
  the format and the cap.
- Claudeworth's suggested keys: `:cleanliness`, `:repair`, `:state` (lit, hot, cooking,
  carved), `:amount` (a parcel's "20 × 1 lb", which today leads the condition string),
  `:stock`, `:lacking`.
- Locations get `:aspects` too (the room itself: floor, hearth, air; never supplies, which
  belong to stores).

### Stores

A store is a fixed item with a `:store` map:

```clojure
{:id "pantry-shelves" :name "Pantry shelves" :place "pantry" :fixed? true :owner "household"
 :desc "Stout shelves of jars, packets and crocks."
 :store {:keeps "dry goods, preserves, condiments, butter and cheese"   ; 1-200 chars, never changes
         :serves ["kitchen"]}                                         ; optional: other places it serves
 :aspects {:stock "well stocked with the usual staples"}}
```

- `:keeps` says what kinds of thing the store holds. With the aspects, it is what the judge
  and `find` reason from.
- A store serves its own place, plus any places in `:serves`. The stores **in scope** at a
  place are those at it or serving it (`items/stores-for world place`).
- A store must be `:fixed?`. It may also be a `:container`, e.g. a closed linen press that hides
  a letter. Stores aren't consumable and are never used up or absorbed.
- Store aspects are conventionally `:stock` (the general level) and `:lacking` (notable
  absences), but they are ordinary aspects.

### Item fates and touches

- `:items-gone` changes from `id -> name` to
  `id -> {:name .. :place .. :round .. :fate "eaten at dinner" | "put away in the pantry shelves"}`.
  `:place` is where it was when it went (the holder's place if carried). `items/names` keeps
  working from `:name`.
- Every item gets `:touched`, the round it was last taken, moved, given, worked on or changed.
  The reducers set it from `(:round world)`, which keeps them pure. The steward uses it.
- Promoted things that aren't consumable (a dripping tin from the dresser) carry `:drawn-from`
  (the store id), so they can be put back.
- **Absorbable** means: not fixed, not a store, and either `:consumable?` or `:drawn-from`.
  The family's silver or the earl's watch can never be absorbed or used up.

## Phases

Each phase leaves `clojure -M:test` passing and is committed on its own. Real runs need the
user's go-ahead (`AGENTS.md`). Every phase updates `docs/scenario-authoring.md` for contract
changes and this document's status lines.

**Before phase 0:** `resources/worlds/claudeworth.edn` has uncommitted edits by the user (cook
`:about`, the countess's persona, the dining-room desc). Never discard them: ask the user to
commit them first, or edit around them and say so.

### Phase 0: content and wording, no engine change

**Status: done (2026-10-17), with its real run.** The judge brief is now
`claudeworth/judge-brief` (the setting plus the everyday-things sentence), and a purchase reads
"... at the General store, and takes the goods away."

Real run, `old-test-runs/claudeworth-phase0-everyday-things`: 10 rounds from 16:00 Saturday,
366 calls, $0.134, 6 errors, 13 rejections, 4 forfeits.

- The everyday-things rule was taken up. The cook's thoughts, after looking at the pantry: the
  condition "says well stocked ... the notable items aren't listed. Fine", and on writing her list,
  "I need paper. Assume to hand." Nobody searched for cloths, paper or condiments, and `look` was
  15 of 366 calls.
- The one authored absence that remains, the `sugar-jar` item ("Not a grain of sugar remains
  inside"), drove the whole afternoon: the countess demanded a full sugar jar, and the cook,
  the countess, Daisy and Hale all ended up at Briggs's counter. A single recorded shortage
  makes a good errand; phase 5 moves it from an item to a store's `:lacking`.
- The judge wrote conditions over 80 characters twice, and corrected them on the retry. Aspects
  (phase 1) split such phrases.
- **New issue, not addressed by this design:** dinner was "served" at 19:00 although nobody
  cooked it (the cook was at the shop until 18:30). Meals are narrative only. Phase 2's judged
  pastimes don't fix this by themselves; consider it when phase 5 tunes the cook.

1. **Remove the provisions counter** (D9): `:stores` in `building/initial-state`, the
   `:starting-stores` check in `building/problems`, `:starting-stores` in
   `resources/worlds/claudeworth.edn` and the `test/fixtures/household*.edn` files, the test at `claudeworth_test.clj:287`, the `:stores` in `shop_test.clj:49,98`, and
   the mentions in `claudeworth-design.md` 6.2 and `scenario-authoring.md` ("Stores of
   provisions can be ... counters in your `:state`").
2. **Stock the pantry for now:** `pantry-shelves` condition "well stocked with the household's
   staples" instead of "clean, empty". (Phase 2 turns it into a store.)
3. **The everyday-things rule** in `claudeworth/rules`: the house has the ordinary things a
   house like it keeps; everyday things (cloths, paper, pans, salt, matches) can be assumed to be
   to hand unless you've been told otherwise; the things listed in your view are the notable
   ones, not everything there is. Say the same in the judge brief (`:items :judge-brief`).
4. **Goods are handed over at once:** the `:shopkeeper` role text in the world file and the narration of
   `shop/buy-events` say the buyer takes the goods away now; nothing is "set by" or kept
   for collection.
5. Tests pass; the mock day still has no `error:`/`rejected:` replies.
6. **Optional, with the user's go-ahead:** a short real run (10 rounds, `:max-cost` 0.20)
   starting at about 16:00, to see how much the wording alone fixes. Count turns spent searching
   for everyday things.

### Phase 1: aspects on items and places

**Status: done (2026-10-17), mock-tested only (no real run).** The engine part of the old
room-conditions R1, with aspects (D2). Built as written, with these small deviations and
additions:

- Narration shows each change by its new value, as in the example below ("Piano: dusted"; the
  worker first wrote "aspect value", changed at review because values are whole phrases). A
  removal reads "no longer <aspect>", which suits `:lacking` but reads oddly for others ("no
  longer hearth").
- Items **made** by work get `:touched` too (they are new; the steward would otherwise see them
  as never handled). The exported map drops `:touched`: it's the run's, not the map's.
- The judge's and the mock judge's context key is `:things` (the judged things, room
  included), not `:items`. The room entry has id, name, desc and aspects, not its `:detail`.
- The place's aspects are added in `space/view`, so Claudeworth's `walk` reply shows them too.
- A location's aspects are checked by `worldfile/problems` with a message naming the location
  (`Location` itself takes any value there), rather than by the `Location` schema.
- Made items' aspect values are length-checked in code (`made-problem`), not by the decode
  schema: malli's strip-extra-keys transformer silently drops `map-of` entries that fail it.
- `actions/kebab-key` is public now; the judge uses it for aspect names.
- The suggested `:items {:aspect-keys ...}` (Data model) isn't built; it isn't in this phase's
  list. The judge's instructions name cleanliness, repair, state, amount and stock as examples.
- Claudeworth's rooms start with no aspects (phase 5); the hamlet fixture's hall has two, for
  the tests.

1. **Data and checks.** `items/Item` swaps `:condition` for optional `:aspects`
   (`Aspects` schema: map, key regex, value 1–80, at most `max-aspects`). `worldfile/Location`
   gets optional `:aspects`, checked in `worldfile/problems`. `worldfile/export` keeps them
   (`item-key-order`: `:aspects` where `:condition` was). Round-trip test.
2. **Events.** `:item/work-done` `:changes` become
   `[{:item id :aspect k :from s-or-nil :to s-or-nil}]`, or `{:place id ...}` for the room.
   `nil` `:to` removes the aspect. The reducer writes `[:items id :aspects k]` or
   `[:space :locations id :aspects k]`. It also sets `:touched` on changed items, and so do
   `:item/taken`, `:item/put-down`, `:item/put-in` and `:item/given`.
3. **The judge's tool.** `changes: [{thing, aspect, value}]`; `value: ""` clears. The
   checked verdict rejects unknown things, bad keys and a fifth aspect on a thing (counting what
   it already has), with an actionable reason, as now. Unchanged values are dropped.
4. **The room goes to the judge** whenever the job names its place: an entry with
   `"room": true`, its id, name, `desc` and aspects. The room-only shortcut in
   `engine/finish-job!` goes (a room-only job is judged). `in-reach` stays items-only (container
   code depends on it); add `items/judged-things` for reach plus room, used by
   `judge-request`, `verdict` and the mock judge's ctx.
5. **Narration** (`n/describe :item/work-done`): "(Drawing room: swept, the hearth clean;
   Piano: dusted)". Show the new value, or "no longer <aspect>" for a removal.
6. **Views.** `look` shows aspects for items and for the place (in `:place`). The per-turn
   `here` view shows the place's aspects (D5). `carrying` shows aspects where it showed the
   condition.
7. **Shop parcels:** `buy` creates `:aspects {:amount "20 × 1 lb" :state <appraised condition>}`.
   The appraiser's `condition` stays one phrase; `shop/max-goods-condition` can go back to
   `max-aspect`.
8. **Mock judge:** every judged thing gets `{:cleanliness "just seen to"}`.
9. **Content:** convert every `:condition` in `resources/worlds/claudeworth.edn`,
   `test/fixtures/*.edn` and `hamlet.edn` to aspects. Keys in Claudeworth: mostly `:cleanliness`
   and `:repair`, `:state` for the range ("cold and unlit").
10. **Tests:** update the `:condition` assertions (about 70 across `items_test`,
    `containers_test`, `consumables_test`, `shop_test`, `claudeworth_test`, `scenarios_test`).
    New: an aspect change leaves the others alone; clearing; the cap; a bad key refused and
    retried; a room aspect changed by work and replayed; narration; `look`; the view shows place
    aspects.
11. **Docs:** "Items" and "Work and the judge" in `scenario-authoring.md`; the Locations section
    for `:aspects`; decision 24 in `claudeworth-design.md` superseded by decision 40 (below).

### Phase 2: stores, the scene judge, one timed activity

**Status: 2a–2d done, mock-tested only (no real run).** Stores, the scene
judge, suggested aspect keys and the shared `spend-time` are built. The pantry shelves serve
the kitchen; the rest of phase 5's content is untouched. Small deviations and safeguards:

- Stores are explicitly checked not to be consumable, as required by the data model. Local
  stores remain in `look.items` for container contents as well as `look.stores` for the in-scope
  supply list; per-turn `here.items` omits them.
- The internal job still calls its focus ids `:items`, including jobs from `spend-time :on`.
  `items/focus-things` is the old named-things scope; `items/judged-things` is now the whole
  changeable scene. Mock ctx includes both `:focus` and `:things`.
- `prompt/scene-recent` builds the shared recent lines in the engine (items cannot require
  prompt, which already requires items). It uses `visible-events` and the same narrated lines
  as agent prompts, starting after the job's actual begun event, not earlier in that round.
- Container use-up checks now require the world, not just the judged things: otherwise a
  consumable container in `also_here` could orphan hidden contents or contents beyond its cap.
  Rejections are generic about unseen contents and suggest naming the container with `on`;
  no hidden ids or names are sent to the judge.
- A job whose named focus has gone still has its local scene judged; the old nothing-in-reach
  shortcut no longer applies. Only an unnamed activity in an empty, aspectless, storeless
  place skips the judge, with a neutral work-done summary (or no-time/retired as before).
- At review: Claudeworth excludes the `work-on` alias (`:exclude-actions`), so characters see
  one timed action, not two; the alias stays in the engine for other scenarios and its tests.
  The tool doc no longer says "Most pastimes change nothing" (that's for the judge, and might
  put characters off housework). The rules gained a sentence saying what `stores` in the view
  means.

2d uses consecutive disjoint batches (place, serving stores and focus places). Calls run on
the pre-batch snapshot; usage events and call logs are deferred and flushed in job order too.
New item ids are allocated when applying results on the live world, preserving sequential ids.
Tests compare all ending events with sequential judging, check overlapping changes and shared
stores, and use a two-call latch to prove independent calls overlap.

**2a. Stores (engine).**

1. `Item` gets optional `:store {:keeps :serves}`. Checks: a store must be `:fixed?`; `:serves`
   names known locations. Accessors: `stores-for world place` (in id order) and `store? it`.
2. **View:** `here` gains `stores: [{id name aspects}]`, holding the stores in scope at your
   place, with `at` naming the place when it's elsewhere ("pantry"). Stores at your place are
   left out of `here.items` so they don't appear twice. `look` adds `keeps`.
3. Work may name a store in scope even when it's in another room (`work-reason`), e.g. the
   cook in the kitchen naming `pantry-shelves`.

**2b. The scene judge.** The judge sees the scene, not only the named things:

```json
{"place":  {"id": "kitchen", "name": "Kitchen", "desc": "...", "aspects": {...}},
 "focus":  [the things the job named, in reach, with contents as now],
 "also_here": [other notable items at the place, not in closed containers, at most 12],
 "stores": [{"id", "name", "keeps", "aspects", "at"}],
 "people": [{"name", "doing"}],
 "recent": ["Mrs Finch says: 'the range has run far too hot'", ...]}
```

- `recent`: at most 10 narrated lines the worker saw since the job began, leaving out lines
  only they saw (notes, replies). It adds continuity without leaking private matters. Use
  `prompt/visible-events` and the narration it feeds, not a new filter.
- The judge may change aspects of anything in `place`, `focus`, `also_here` and `stores`.
  It may use up consumables in `focus` or `also_here` (dining eats the joint on the table).
- New instructions: most activities change little and a pastime often nothing; don't invent
  events to make the activity eventful; a store holds everything it keeps unless its aspects say
  otherwise; change only the aspects the activity affected.

**2c. One timed activity (D3).** Claudeworth's `spend-time` and the engine's `work-on` become one
engine action in `manor.items`:

- `spend-time {doing, hours, on?}`: `doing` 3–120 characters (reads after "is"), `hours`
  0.5–4, default 0.5, `on` an optional list of up to 6 ids (items in reach, the place, stores in
  scope). It's the action's turn-ending kind and needs a clock, as `work-on` does now.
  `work-on` stays as an alias for one release, so prompts and mock scripts can move over.
  Then remove it.
- Ending: judged like work. If the place has no aspects, no items and no stores in scope, and
  nothing was named, the activity ends with a neutral summary and no judge call.
- Claudeworth: remove its `spend-time` from `claudeworth/actions`; `sleep` stays separate and
  unjudged. Update `rules` and the mock day (`claudeworth.clj` `mock-day`: `spend-time` keeps its
  name; `work-on` calls become `spend-time ... :on`).
- Event names stay `:item/work-begun`, `:item/work-done` and `:item/work-ended` (no rename
  churn); their narration already reads naturally for pastimes.

**2d. Parallel judging.** In `engine/run-round!`, the ending jobs whose places and stores in
scope don't overlap are judged concurrently, on the world as it was before the batch. Events
are emitted in the existing order (due, then id), so replay and determinism are unchanged.
Jobs that overlap stay sequential, so a later one sees the earlier one's result.

**Tests:** stores in the view and `look`; a store serving another place; the judge request
holding place, focus, also-here, stores and recent; a pastime changing a room aspect; a dining
activity using up a joint lying at the place; a garden rest with nothing to judge making no
call; parallel batch events in the same order as sequential.

### Phase 3: promotion and demotion

**Status: done, mock-tested only (no real run).** Drawing, absorption, fates and the
opt-in nightly steward are built. Claudeworth enables the steward with the values below.
Tests cover checks, placement, container safety, fates and old names, selection/caps,
nightly scheduling/replay, mock effects, correction and cost. Both 48-round Claudeworth
mock days (normal start and 20:00 start) have no bad replies or judge/steward failures and
replay equals live. The evening-start day records one steward pass, round 13 (02:00).

Small details and deviations:

- Clarified with the supervisor: skip a partial first day's already-past scheduled time.
  Use the most recent `:at` at or before now, only if at or after the clock start and not
  recorded. Starting exactly at `:at` runs immediately. This differs from a literal
  "first round at or after" on a late initial day.
- A made entry's `consumable` flag is required. Placement follows this phase, replacing the
  old rule based on the first used-up thing: a named room or focus lying at a place is
  place-bound; otherwise new things go to the worker's hands.
- Work-done data retains all new things in `:made` and adds `:fetched [id ...]` to distinguish
  drawing in narration, including consumables (which have no `:drawn-from`).
- Items are touched on addition as well as handling, changes and creation; named work focus
  and its contents are touched on completion even when aspects were unchanged. Starting
  round-zero items age from clock start, not an extra round earlier. Export retains
  `:drawn-from` but drops `:touched`; `:items/stewarded` also refreshes the live exported map.
- Steward places run sequentially; optional parallelization was unnecessary. Its checked
  tool shares effect checks with the judge but excludes `made` and restricts aspect changes
  to candidates. It can discard any absorbable candidate, including a worn drawn utensil.
  Failures leave things alone and add a system-only `:items/steward-failed` event.
- The late mock-day check found pre-existing redundant walks to bedrooms on late starts.
  Only mock sleep scripts now omit such walks when already there; no character tool or
  ordinary-day choreography changed. The mock judge needs no new behavior and stays quiet.
- The steward runs immediately after finishing jobs, before bridges and systems: newly
  handled work is protected, and it sees leftovers before new work begins.

1. **Drawing from a store.** A `made` entry may give `from: <store id>` (a store in scope).
   Without `from`, it needs something used up, as now. Made entries carry `aspects` (instead of
   `condition`) and `consumable`. Made items drawn from a store and not consumable get
   `:drawn-from`. Where they go: `in` a container in reach, else the worker's hands if they named
   no place-bound focus, else the job's place. `max-made` stays 2.
2. **Absorbing.** A new judge list, `absorbed: [{thing, into}]`: the thing must be absorbable
   and in `focus` or `also_here`, and `into` a store in scope. The event data gets
   `:absorbed [{:item :name :into}]`; the reducer removes the item and records its fate
   ("put away in the Pantry shelves"). The judge may also change the store's aspects in the same
   answer; it isn't required to.
3. **Fates.** Used-up items record their fate: the judge gives one per used-up thing in a
   `fates` map, defaulting to "used up while <doing>". Absorbed items record the store.
   `:items-gone` takes the new shape (above).
4. **The steward** (D6), an engine pass:
   - Scenario config: `:items {:steward {:at "02:00" :idle-hours 6 :max-per-call 8}}`; off when
     absent.
   - At the first round start at or after `:at` each game day (record the day it last ran in the
     world with a `:items/steward-ran` event, so replay agrees), code chooses candidates: items
     that are absorbable, not carried, not inside a closed container, and with `:touched` at
     least `:idle-hours` ago.
   - Candidates are grouped by place; places without any cost nothing. For each place, one
     narrow call (agent `"steward"` in `llm.edn`) sees the place, its stores in scope and the
     candidates (oldest first, at most `:max-per-call`; any left wait for the next night). For
     each candidate it may: absorb it into a store in scope, use it up with a fate (eaten by the
     staff, thrown out, gone off), change its aspects (going stale), or leave it. It never makes
     anything.
   - The checked answer is emitted as `:items/stewarded` (visible to the GM only: nobody sees
     it happen). It goes through `ask-structured!` with a mock steward that absorbs everything
     it can.
5. **Tests:** draw from a store (in scope, out of scope refused); absorb (absorbable only;
   into a store in scope); fates recorded and still named in old lines; steward candidate
   selection (carried, fixed, closed-container, recently touched and non-absorbable items all
   excluded); the steward running once a night and replaying; the steward's mock.

### Phase 4: `find`

**Status: done, mock-tested only (no real run).** Local name/container hits and scoped fates
short-circuit the oracle. Checked finder answers, seeded outcomes, bounded absences,
visibility, per-turn limits, failure/correction, usage and replay are covered by
`test/manor/find_test.clj`. The full suite's Claudeworth mock days remain unchanged.

Approved deviations and details:

- `find` is a non-turn-ending `:query`, not `:free`: it uses the existing adjudication
  machinery and counts against the query budget as well as `:per-turn 1`. This intentionally
  reduces its salience. Calls log as `adjudicator/items/find`, not `finder`; failures use
  system-only `:adjudication/failed` with task `:items/find`, not a new failure event type.
- Item adapters register automatically when items are enabled. Adjudicator requests may
  return a direct `:result` or their own scope-closing `:check`; query actions may return a
  custom `:reply` using the stamped on-result events (for exact allocated ids). Shop defaults
  are unchanged. Finder failures reply neutrally and consume the accepted per-turn use.
- Container searches, like `open`, reveal matching contents only in their reply; no persistent
  open/closed state was introduced. Deterministic hits/fates emit nothing and do not move items.
- Originally oracle `found` and `lacking` were both required because code decides the outcome
  (phase 6 makes `lacking` optional and restricts it to store supplies). Optional
  `portable` defaults true; false leaves the thing here (no new size mechanics). Mock items
  lie here when no store is in scope. A found source becomes `:drawn-from`.
- The roll key uses the live world's next event sequence at on-result time, after usage is
  recorded. Key, roll, likelihood and outcome are recorded, so replay never re-rolls.
- **Historical gap (fixed in phase 6):** four unrelated aspects are never evicted to make room for `:lacking`.
  A full selected store falls back to the place; if that is full too, the negative event
  records `what` without changing aspects. A later search may disagree in that rare case.
- No phase 5 content or mock-day search choreography was added; only the requested short
  Claudeworth rules sentence and query-budget wording changed.
- At review: matching ignores a leading article ("a dripping tin" finds "Dripping tin"), and
  a find reads "... looks for mustard and finds it in the pantry shelves" ("finds one" read
  oddly for mass nouns).

- `find {what}`: `what` is 1–60 characters. A non-turn-ending query, once per turn (`:per-turn 1`), not
  while travelling. Its doc: "Look for a particular thing you need to pick up or hand over, or
  whose presence is in doubt. Everyday things you only use in your work need no finding: just
  name them in what you're doing."
- **Here only.** `find` searches your place, its open and closed containers, and the stores in
  scope. It never reports anything elsewhere: telling the finder where a thing is in another
  room would leak private information.
- **Step 1, deterministic:** a case-insensitive match of `what` against the names of items
  here (including inside containers, which searching opens) replies "the X is here (id)". A
  match against `:items-gone` whose `:place` is here, or a place this store serves, replies with
  its fate ("the rib of beef was carved at dinner; its bones went into the stock pot").
- **Step 2, the oracle:** otherwise one narrow call (agent `"adjudicator/items/find"`) gets the place, its
  aspects, the stores in scope and `what`. It returns a `likelihood` (`certain`, `likely`,
  `even`, `unlikely`, `impossible`), a `found` item `{name desc aspects consumable from}` for a
  yes, and a short `lacking` phrase for a no. The engine rolls `rng/roll world [:find seq] 100`
  against 100/75/50/25/0. The LLM rates the odds and the engine decides the outcome (the
  user's todo about not trusting LLM randomness).
  - **Yes:** the thing is promoted into the finder's hands (`:drawn-from` the store, or lying
    here if it's furniture-sized and `from` is absent: the judge says which).
  - **No:** `what` is appended to the store's `:lacking` aspect, or the place's if there's no
    store in scope, so the next search agrees. Appending a fifth aspect, or running past 80
    characters, keeps the most recent absences that fit.
- Events: `:item/found` and `:item/not-found`, seen by the finder and the people here.
- Mock: certain for anything, found as a plain item drawn from the first store in scope.
- Tests: deterministic hit, fate reply, a yes and a no from fixed rolls, `:lacking` recorded
  and consulted, not reporting other rooms, the per-turn limit.
### Phase 5: Claudeworth content and a real run

**Status: done, with its real run; its findings are phase 6.**
Four household stores, the pantry's sugar shortage, aspects on all sixteen indoor house places,
new-room aspects, and tidied rules/role wording are built. Nellie's range-blacking names the
range and her cupboard in scope. Mock days (08:00 and 20:00 starts, 48 rounds each) have no
errors, rejections, forfeits or judge/steward failures, and replay equals live.

Placement and test notes: the housemaid's cupboard is in her room off the kitchen, serving the
kitchen, dining room, drawing room and entrance hall; the closed linen press is in the gallery.
There is no wine cellar or coal-hole on the map, so neither was added. The dresser/sugar-jar
assertions now belong to fixed household-container fixtures and remain unchanged; the bundled
store smoke check is generic. No mechanics or fixtures changed; no real runs were started.

1. **Stores:** the pantry shelves (serving the kitchen); the kitchen dresser (pans, tins, cloths,
   crockery, kitchen tools); a housemaid's cupboard (brushes, dusters, polish, blacklead); the
   linen press (closed container too); the wine cellar and the coal-hole if they're on the map.
   Starting `:stock` aspects are period-plausible and mostly "well stocked", with at most one
   shortage worth a story.
2. **Remove** `kettle`, `saucepans`, `blacking-brushes` and `sugar-jar`. Keep the range, the
   china, the silver, the portrait, the piano, the stag's head and personal things. Update
   `scenarios_test.clj:59-78` and the mock day (Nellie's range-blacking draws on the cupboard).
3. **Room aspects** on every room: short and period-appropriate (the drawing room dusty, the
   kitchen range unlit). Rooms built by `building.clj` start with
   `{:cleanliness "new, smelling of plaster and paint"}`.
4. `rules`: stores, `find`, `spend-time ... on`. The cook's `:about`: the pantry is a store,
   so shop for what's lacking or running low, not for every staple.
5. **Real run** (with the user's go-ahead): 10–12 rounds from about 16:00 (cooking and dinner), a
   second short one from 01:30 to see the steward. `:max-cost` 0.20 each. Look for: searches for
   everyday things, `find` calls per turn (too many means its salience is too high), judge-made
   items during pastimes (D3's risk), the beef's life ending in a fate, store aspects that read
   sensibly, and `error:`/`rejected:` counts. Move the runs to `old-test-runs/`.

Real run, `old-test-runs/claudeworth-stores-phase5`: 10 rounds from 16:00 Saturday, then
continued to 02:30 (22 rounds) for the steward; 363 calls, $0.137, 30 errors, 16 rejections,
1 forfeit. What worked:

- The sugar shortage drove a short errand: the cook reported it, bought loaf sugar at Briggs's
  (he was out of lump sugar), and shelved it. No hunts for cloths, pans or condiments.
- The judge held back: 13 of 28 judged jobs changed nothing; pastimes stayed quiet; room and
  range aspects changed sensibly ("Kitchen range: lit and drawing well").
- Fates read well ("Clear soup: carried to the drawing room and served at the family's supper").
- The steward ran once, at round 21 (02:00), with replay intact. It had no candidates: the one
  loose consumable (the loaf sugar, shelved at round 11) was idle 5 hours, under `:idle-hours` 6.
  So the steward's model call is still untried against a real model.

What didn't, all fixed in phase 6:

- **Work products can't exist.** The judge tried to make Pugh's working drawings, the
  countess's shopping list, a laid table, soup and pudding, a cold collation and a sealed note;
  5+4 answers were refused ("things are made only from consumable items the work used up"),
  since everyday supplies (paper, ink, ingredients) are neither consumables in reach nor a store
  in scope. The judge then made nothing, and characters went looking for the papers they had
  just written: 4 of the 7 `find` calls were for the household list (three times) and a sealed
  note. This is the "missing things" drama the design set out to remove.
- **`find` in a shop** ("lump sugar on the shelves" at Briggs's): a yes would have handed over
  the shop's goods unpaid.
- **`:lacking` collected non-supplies:** "no coat here", "household list on the writing table",
  "joint of meat or roast" (on the pantry room, not its shelves), "lump sugar" on the shop.
- **Carrying used things up:** taking the soup and pudding to the drawing room was judged as
  using them up (the judge can't move things), so they were gone before supper.
- **`doing` over 120 characters** 15 times: the rules now ask characters to describe their
  tools and supplies in it.
- **Lacking not cleared on restocking:** the pantry kept `:lacking "sugar"` with the sugar on it,
  because `put-in` isn't judged and the steward hadn't absorbed it yet.

### Phase 6: fixes from the phase 5 run

**Status: done (mock-tested; no real-model run).** All six fixes are built. The user's final
choices supersede the original proposal: no `from: "everyday"` sentinel, no place-level
absence fallback, and immediate moves with deferred quick judging for store put-ins, rather
than waiting for another activity/the steward. Claudeworth's steward stays at `:idle-hours 6`.

1. **Work products.** Source-free `made` entries need nothing used up. The judge decides whether
   a thing is the activity's direct product from everyday supplies; code still checks the
   two-item cap, fields, store sources and containers. Instructions distinguish carryable,
   keepable or servable products from room aspects (a table "laid for dinner"), writing/drawing
   products (not consumable) from dishes (consumable), and making from drawing from a store.
2. **Finding permissions.** Generic `:items :find-allowed?` receives world, actor and place id,
   returning nil or a reason before deterministic lookup or oracle. Claudeworth refuses at
   any location with nonempty `:services`: "this isn't your house to search; ask for it here
   instead". Shops and the architect's office both exercise this guard in tests.
3. **Supply absences only.** The finder supplies optional `lacking` only for a supply a listed
   store keeps, naming it in `found.from`. Particular coats, letters and purchased joints do
   not create shortages. A miss without a phrase/source, or on a full store, still records
   its event but no aspect; there is no room fallback or unrelated-aspect eviction.
4. **Carrying isn't consumption.** Judge instructions confine activities to one place and say
   carrying isn't using up: leave the thing where it is, and characters carry it with `take`.
   Leaving any thing untouched is always valid. Claudeworth says carry dishes with `take`
   and `walk`, and use them up where eaten. No additional semantic use-up validator was added.
5. **Longer activities.** `doing` is 3–200 characters for both `spend-time` and `work-on`;
   boundary tests also check occupations and work-done narration retain the complete phrase.
6. **Judged restocking.** Free `put-in` moves at once; for stores it also creates a zero-minute
   `:quick?` job due next round. There is no occupation and it cannot be stopped merely because
   the actor is not busy with it (nor does it stop another occupation). It uses the existing
   checked judge, due/id order and disjoint parallel batches. The judge may absorb, change
   store stock/shortages or other affected aspects, or leave things alone. Ordinary containers
   remain deterministic. Default mock quick jobs absorb deposited consumables and clear matching
   whole-word shortage phrases; unrelated shortages survive.

Details and approved deviations:

- Placement needed an explicit choice without a writing classifier: on supervisor approval,
  a made entry may give optional boolean `held` (true = worker's hands, false = job's place).
  `in` still wins, and omitting both preserves the previous place-bound-focus default.
  Instructions say letters/lists/notes are naturally held, dishes and large things aren't.
- Several put-ins merge per **actor + store + started round**, retaining the first job id and
  extending focus and doing with a GM-only `:item/work-extended` event. Different workers or
  stores remain separate jobs. Retired workers still drop their jobs, as before. Quick jobs
  also work without a clock (the request labels the round rather than a time).
- Dedicated integration tests exercise restocking rather than changing bundled mock scripts
  or the all-durable mock shop appraisal (approved: mechanics tests stay independent of live
  scenario content). Both existing bundled 48-round days, 08:00 and 20:00 starts, remain quiet:
  no error/rejected replies, forfeits or judge/steward/finder failures; replay equals live.
  The evening-start steward still runs at round 13 (02:00).
- Tests cover source-free products, placement/flags, carrying then eating, shop/office refusal,
  optional and storeless misses, full-store no-fallback, 200/201 boundaries, immediate moves,
  no occupation, merging, another occupation surviving, ordinary sideboard no-call, and replay.

**What to watch in the next real run:** written products should actually exist and be held;
laid tables should be aspects, not new furniture; dishes should remain until physically carried
and eaten, not disappear while "serving elsewhere". If contradictions persist, the user plans
to loosen the using-up rule further; keep it simple until there is evidence. Check that the
finder omits non-supply shortages, shop refusals lead to asking rather than repeated queries,
quick restocking absorbs/clears only appropriate stock (without erasing unrelated lacks), and
merged put-ins get one call. The real steward model path remains untried: the phase 5 pass had
no candidates, and its six-hour idle threshold has not changed. No new real run was started.

### Phase 6 real run

`old-test-runs/claudeworth-stores-phase6`: 16:00 Saturday to 02:30 (22 rounds), 374 calls,
$0.149, 13 errors, 18 rejections, 0 forfeits (phase 5: 30, 16, 1).

- **Work products exist now.** No "made only from" refusals. Made: two lists of matters for
  Mr Pugh, a note to Mr Pugh, a draft order of works and a fair copy, a memorandum of queries
  (all held by their writers), a pan of prepared vegetables, a batter pudding and gravy. The cook
  bought a rib of beef at Hobbs's and roasted it; after dinner it reads "about 2 lb cold beef
  left". Nobody hunted for a paper they had written.
- **`find` refused in the shop** once ("this isn't your house to search"); no new `:lacking`
  entries except the pantry's sugar, which stayed correct (the sugar was quoted but not bought
  before closing, and the story carried on: Hale is to fetch it at eight).
- **No carrying contradiction** this time; dishes weren't carried, so the rule wasn't tested
  hard. `put-in` into a store wasn't used (7 `put-down`s instead), so quick jobs are untested.
- **The steward ran at 02:00 with no candidates again:** the evening's leftovers were last
  touched at round 14 (21:30 is 4.5 hours before), under `:idle-hours` 6.

Problems:

- **`find` duplicates things that exist elsewhere.** Lord Claudeworth held "Draft order of
  works for Pugh & Sons" and "Memorandum of household account queries"; Hale searched the study
  for each and the oracle made a second copy of both. The local match only covers items lying
  here, so things carried by people here or lying elsewhere go to the oracle.
- **`doing` over 200 characters** 10 times (up to 397).
- **Aspects describe moves the judge can't make:** leftovers lying in the kitchen read "covered
  and set in the pantry" (the judge could have absorbed them into the pantry shelves, which
  serve the kitchen).

### Phase 7: fixes from the phase 6 run

**Status: proposed (2026-10-18).**

1. **`find` never duplicates.** The local match also covers things carried by the people here
   ("Lord Claudeworth has the Draft order of works"). If a tracked item anywhere matches the
   name, a miss here is a plain "you don't find it here", with no oracle call and nothing
   recorded: the reply is the same as any miss, so nothing about other rooms leaks.
2. **`doing` up to 400 characters.**
3. **The judge absorbs instead of describing a move:** "to put leftovers or supplies away in a
   store in scope, absorb them; don't describe them as moved while they stay where they are."
4. **Steward `:idle-hours` 4 in Claudeworth**, so the evening's leftovers are tidied at 02:00.

### Later, by need

- GM tools to change aspects and stores (roadmap B7's "GM tools to create or alter items"), so
  the GM can inject "the mustard's run out" drama.
- Shops as stores: a store whose `:keeps` is the store profile, so the appraiser and `find`
  reason alike.
- Gathering a trip's purchases into one basket parcel (D8 chose the simpler option for now).
- A code-readable stock level, if provisions become a mechanic (D9).
- Panel: aspects and stores on the map page.

## Invariants and risks

- Every change is an event with a pure reducer (invariant 1). The judge, the steward and the
  oracle are engine-run model calls; their checked answers are recorded as events, and replay
  never calls them (invariant 4). Rolls come from `manor.rng`, keyed by event sequence.
- Judge, steward and finder prompts never hold the whole world. Their output goes only to the
  people at the place (the steward's only to the GM).
- **Risk: the judge does too much** (D3). The phase 2 instructions say most activities change
  little; phase 5's run checks how many pastimes make items or change aspects.
- **Risk: `find` gets too salient.** One per turn, a doc that discourages it, and the
  everyday-things rule. If real runs show agents finding everything, drop the tool from the
  per-turn view's hints first, then consider costing it a turn.
- **Risk: aspects sprawl.** Four per thing and a suggested key list. If keys drift
  ("clean", "cleanliness", "tidiness"), tighten the judge's prompt to the suggested keys first.
- **Risk: round-start latency** grows with judged pastimes. Phase 2d batches independent jobs.

## Open questions (don't block any phase)

- The thresholds for the oracle's likelihoods (100/75/50/25/0) are a guess.
- The steward's `:idle-hours` (6) and whether it should also rewrite room aspects overnight
  (e.g. ash in grates). Use makes mess (D3) may be enough.
- Whether `find`'s deterministic match should be fuzzier than substring matching.
