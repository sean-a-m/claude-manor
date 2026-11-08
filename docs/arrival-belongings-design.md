# Generated arrival belongings

**Status: implemented, independently reviewed and full-suite offline-tested.**

## 1. Brief and evidence

The user wants the character generator to generate the probable things a new person would
be carrying. Start here rather than build a delivery system. The user has chosen candidates
first, a strong prompt-level default for appropriate references, and ordinary containers with
usual contents summarised as aspects. Retrieval on return and new leave/absence mechanics
are explicitly outside this build.

In the main checkout's `runs/claudeworth-medium-20261107-132710/transcript.md`:

- Edith Gates's generated circumstances give her off-map lodgings by the quarry, but no box
  or reference paper. During the interview she invents a written character she can fetch
  (around line 6505). Later dialogue puts it in a box under her bed.
- The household repeatedly demands the papers and interprets her failure as dishonesty.
  Emily even follows her to inspect the box, although the lodgings are not on the map.
- At line 8390, `travel` to "lodging house by the quarry" is rejected. Edith then uses
  `take-leave` with a return time successfully, but comes back empty-handed: that mechanism
  schedules her return, not the outcome of fetching something.

In the later `runs/claudeworth-medium-20261107-142822/transcript.md`, Mabel Vine's generated
circumstances say she arrived carrying a bundle and a written character, but her actual
inventory is empty. The writer's current instructions forbid granting items in background
prose, but this instruction alone has not prevented the mismatch.

These runs live in the main checkout, not in this worktree. They are evidence, not fixtures
to modify or replay with paid calls.

## 2. Proposed scope

### Core build: first-arrival belongings

Extend the **existing private people/polish call** with a small structured `:belongings`
answer. The same model writes the person's background and the ordinary possessions they
bring. The engine checks the structure and creates ordinary items through events when the
person actually arrives. No separate inventory-writing call on first arrival.

Initial consumer: **new hiring candidates only**. Keep the data and event helper reusable
for other generated people, with explicit opt-in context. Do not silently give equipment to
all couriers, guests and tradespeople merely because they share `hiring/write-person`.

Do not change the people MCP server's trait/name generator. The prose writer is the part
that knows the person it is inventing and can choose plausible belongings coherently.

### Outside the core build

- Delivery/collection requests, carriers, postage, transport charges and station timetables.
- New map places for generated homes or past employers.
- A stock inventory for every established servant, a clothing simulation or a cash grant.
- Regenerating authored casts or changing `roll-cast!`'s existing world-file contract.
- Verifying former employers, automatic hiring approval, or guaranteed favourable references.
- Retrieval on return, inventory regeneration on re-entry, changes to `take-leave`, and new
  absence/return-to-work mechanics for engaged staff. See section 7.
- General off-map adventures.

## 3. What the writer may supply

Add an explicit input mode, for example `:belongings-mode :first-arrival`, with a short
arrival context: candidate seeking a particular post, coming for interview, setting and
any authored directions. Do not infer this mode from a role name or from prose. Other
callers, including `roll-cast!`, default to no belongings generation.

For this mode the answer must contain `:belongings`, including `[]` when none are appropriate.
This distinguishes a deliberate empty inventory from a missing answer. Existing no-belongings
callers need not supply this field and must not consume unsolicited item proposals.

### Where the recommendations live

**In the character-creation prompt**, not in a hard-coded item catalogue or a separate
inventory-generation call. Extend `resources/prompts/writers/persona.md` with the belongings
instructions and structured answer contract. England-specific recommendations are pack-supplied
prompt text rendered there (a fragment or template override), so the same writer remains
usable in other settings. `judges/tools/polish.md` describes the output field, not a second
independent source of kit recommendations.

The writer chooses plausible things while writing the same person's background. Clojure
validates and materialises that answer; it does not pick a compulsory box/letter kit.

Instructions, in substance:

- Supply a few ordinary, portable personal effects likely to be brought on this occasion.
  This is not everything the person owns, and not an exhaustive list of clothes being worn.
- In the England setting, a modest servant's box containing their usual personal things is
  the recommended default, with a bundle or other suitable luggage where more plausible.
  Summarise ordinary contents as an aspect, using period-appropriate detail; do not enumerate
  every garment, comb or mending tool as an item. A box is not a compulsory invariant.
- For a servant with previous service in the England setting, **strongly prefer a carried
  written character**. It need not be glowing. Do not force one for every age, history,
  position or setting, and do not invent a missing paper at home as a substitute.
- A letter can be part of summarised contents when no separate handling is needed. When it
  is likely to be shown or handed over, as a reference at a hiring interview normally is,
  supply it as a separate ordinary item inside the box. The box's contents aspect describes
  the remaining ordinary things; it must not represent a second copy of the separate letter.
- Write background, appearance and possessions consistently in this one answer. An invented
  former employer may sign a character; this does not spawn that employer as an agent or
  make their house a location. Keep any employment details consistent across the answer.
- Possessions go in `:belongings`, not solely in circumstances or appearance. Prose may refer
  to a supplied thing, but cannot grant an additional item or an off-map cache of equipment.
- No money/account balances, property deeds granting authority, business stock, purchases,
  delivery orders, other people's possessions, engine capabilities or supernatural effects.
  A document's words are not executable authority.
- Modest ordinary lives and few belongings are preferable to an elaborate personal kit.

### Suggested answer shape (names provisional)

```clojure
{:circumstances ["You were in service with Mrs Harcourt near Reading for three years;
                  the household reduced its staff when she moved to a smaller house."]
 :persona "..."
 :manner "..."
 :appearance "..."
 :belongings
 [{:name "Servant's box"
   :desc "A small, worn wooden travelling box with an iron hasp."
   :container :closed
   :aspects {:contents "A change of linen, a spare apron, a comb and mending things."}
   :contents
   [{:name "Written character"
     :desc "Mrs Harcourt's signed character for Edith Gates: three years as under-housemaid,
             steady over grates and linen, plain-spoken, left when the household reduced."
     :candidate-reference? true}]}]}
```

This is illustrative, not a fixed kit or expected wording. Ordinary contents remain an aspect
until something needs to be taken out, using the existing aspect-to-item mechanisms. Here the
reference is already separate because the interview is likely to require handing it over.
There is no special box inventory, contents catalogue or new promotion/demotion mechanic.

Proposed bounds:

- At most four top-level objects, six objects total, at most one level of contents.
- Existing item name/description/aspect limits; existing aspect-key validation.
- Optional `:container` (`:open` or `:closed`), `:contents`, `:aspects` and `:consumable`.
  Contents require a container; child containers are not allowed in the first version.
- Optional boolean `:candidate-reference?`, valid only in candidate generation and at most
  once in the whole answer. The engine translates it to the existing `:candidate-id` marker.
- No model-supplied world item ids, holders, owners, locations, `:waiting`, `:order-id`,
  arbitrary state paths or effects. Unknown keys follow the ordinary decoder policy.

Code checks shape, limits and structural relationships, not whether the text sounds like a
reference, a weapon or an expensive thing. Plausibility is the writer's instructed judgement;
use the existing checked-answer retry for malformed structure, never phrase/regex tests.

## 4. Data and event lifecycle

### Generate once; consume once

1. `people/polish-request` enables the belongings answer for candidate creation. The public
   appearance call stays restricted to its existing visible input; private background and
   reference contents must not reach that call.
2. `hiring/write-person` preserves the checked `:belongings` alongside the prose. It is not
   a trait and must not be copied into `:circumstances` by `people/traits`.
3. `:people/pooled` records the pending descriptions privately. No real item exists yet: the
   candidate has no agent id and has not entered the simulated area. A reused pooled
   candidate keeps the same pending descriptions; recommendations do not roll a new kit.
4. On successful first arrival, spawn the agent, then materialise the pending descriptions
   with engine-allocated ids. Roots get `:holder` and `:owner` set to the new agent id;
   contents get `:in` set to their newly allocated parent's id. Candidate references get the
   candidate pool id through the existing hiring-reference convention.
5. Use `items/added` and sequentially applied temporary worlds to allocate each fresh id
   without collisions. The resulting `:item/added` events, emitted normally, own all changes.
   No model call, randomness or I/O in an item reducer or in the arrival effect.
6. Clear the pending list in a private candidate-update event in that batch. An empty list is
   consumed too. A return must not use the stored generation answer as a refill template.

If an arrival is blocked by the active-person cap or physical status, create no items and
leave the pending list intact. Repeated ticks after a completed arrival create nothing more.
Event recording/loading retains the same guarantees as other multi-event engine operations;
do not add a bespoke transaction or recovery layer for this feature.

Once added, these are **ordinary items**. They may be given, lost, consumed, put in a room,
absorbed where permitted, or carried away with their holder. Do not add an identity-protection
rule for a particular bundle or keepsake. References can use the existing candidate marker,
which is already an allowed identity-bound case.

Replay/load/branch read recorded events and never rerun generation. Returning candidates keep
whatever they actually still carry. A paper given to Emily stays with Emily; leaving and
returning cannot restore it or move a box left in the house into the candidate's hands.

### Implementation boundary

Keep the item-shape checking/materialisation helper in the items/people boundary rather than
embedding item reducers in hiring. `hiring/arrival-events` is the initial caller. Do not enable
all generated people by changing `builtins/spawn-events` globally: role/scenario spawn hooks,
away guests made ahead of a visit, and authored spawns already have distinct lifecycles.

## 5. Privacy and agent behaviour

Pending descriptions and separate reference contents are private to the engine/GM until the
owner arrives. Recommendation and `hiring/observe` continue projecting only their existing
fields; knowing an applicant is not permission to read their inventory or letter.

Use owner-only audiences for materialisation events, including child items. Existing views
show visible carried roots to bystanders and expose child items through the usual open/look
rules; do not announce a closed box's separate reference text to everyone at the meeting place.
An ordinary contents aspect follows existing aspect visibility, not a new secrecy rule: use
it for general possessions, never to hide private letter text. Confirm in implementation that
the owner can discover and open their own generated container using existing views/actions.
Avoid adding a second hidden inventory or a redundant public manifest.

Update candidate/hiring instructions:

- The things they brought are their actual carried things, including contents of containers
  they can open. Use ordinary item actions to show or give a paper.
- If no written character is available, say so rather than inventing one at inaccessible
  lodgings. An interviewer may seek a recommendation, offer a trial or refuse. None of these
  should be automatically selected or forced by code.
- An off-map home remains background, never a destination for `walk`/`travel`. Phase 1 does
  not make taking leave retrieve unrecorded goods.

These are instructions, not a semantic lie detector. A character can still lie, but the
engine must not itself describe an unperformed retrieval as successful.

## 6. Integration and core acceptance checks

Planned implementation changes:

- `src/manor/mechanics/people.clj`: conditional answer schema, normalisation, mock writer.
- `resources/prompts/writers/persona.md`, `judges/tools/polish.md`, pack guidance and
  `docs/prompts.md`: the structured belongings contract and its scope.
- `src/manor/mechanics/hiring.clj`: opt-in context, private pending data and first-arrival
  materialisation/consumption; item helpers in or beside `mechanics/items`.
- Shared hiring/arrival instructions and a small fixed household fixture/mock policy.
- `docs/scenario-authoring.md` and this design's outcome section when implemented. The live
  authoring contract is not changed by this proposal alone.

Mock/offline acceptance before any paid run:

1. A fixed writer answer with a box, a usual-contents aspect and a separate reference produces
   real items on first arrival. The candidate opens the box and gives the reference during
   the interview; the paper retains the correct candidate marker. Ordinary clothes and tools
   stay summarised, with no second copy of the letter. No off-map journey is needed.
2. A deliberate empty kit and a kit without a reference are accepted. Missing required answer,
   excessive count/depth, invalid containers/aspects and invalid reference flags are rejected
   structurally. Tests do not match generated prose.
3. Items are absent while pooled or arrival-blocked; the first successful arrival creates
   them once. Additional recommendations and ticks do not create more.
4. Give away the reference and leave a bundle on-map, then take leave and return: neither
   gets duplicated or teleported. Existing carried items remain carried.
5. Other agents' recommendations, prompts and arrival memories do not leak private contents.
   Normal visible roots/open-container behaviour still works.
6. Replay, load and branch reproduce exact items, containment and consumed pending state,
   with zero generation calls during replay.
7. Disabled/default callers, authored world inventories, `roll-cast!`, couriers, guests and
   tradespeople remain unchanged. No-items scenarios gain no generated inventory.
8. Full offline suite before an eventual implementation commit; no real model run or cast
   refresh is needed to draft or approve this design.

## 7. Re-entry and retrieval: excluded for now

The user chose not to include retrieval on return or new leave mechanics for newly engaged
staff. Do not add a `bring` argument, a return writer, pending return kits or arrival-time
model calls. Existing `take-leave`, return scheduling and item persistence remain unchanged.

The first-arrival helper and schema should be reusable if a future feature needs them, but
there is no requirement to design or build that future feature now. In particular, first
arrival descriptions must never be replayed as an inventory refill on re-entry.

## 8. User decisions

1. **Candidates first; extendable.** Enable generated belongings only for new hiring candidates,
   keeping the data and event helper reusable for explicit opt-ins in other cases.
2. **Strong default, in the character-creation prompt.** Appropriate experienced servants
   should normally bring a written character. The recommendations live in the people writer's
   prompt, with setting-appropriate guidance, not a Clojure kit table or another model call.
3. **Ordinary containers and aspects.** A period-appropriate servant's box is a recommended
   default. Its usual things can be summarised as a contents aspect, like existing containers.
   A letter is a separate item when separate handling is needed; no full wardrobe enumeration.
4. **No retrieval-on-return feature in this build.** Preserve current return behaviour without
   generating new belongings.
5. **No new leave/absence mechanics for engaged servants in this build.**

No user-facing questions remain open. Numerical caps and field names above are implementation
defaults. Record the implementation outcome and any necessary departures here when built.


## 9. Implementation outcome

- The private polish conditionally requires and checks belongings in the existing call;
  default writers, cast rolls and other runtime people remain disabled. England guidance is
  a setting-pack fragment rendered inside the persona prompt, not a Clojure kit table.
- `manor.mechanics.items.belongings` owns the reusable schema, structural checks,
  normalisation and item-event materialisation. The proposed bounds and field names are used.
  Item aspects keep malformed values through decoding so normal aspect checks reject them
  rather than silently losing a bad value.
- Hiring stores checked `:pending-belongings` outside the trait record, privately pools it,
  and consumes it to nil in the successful first-arrival candidate update. Root and child
  additions are owner-only, use engine ids and existing containment/reference mechanics.
  Returns never reuse generation descriptions or move existing possessions.
- `hiring/install` snapshots its scenario's items opt-in as `:hiring-belongings?`, beside its
  existing active-agent cap. This matters for an items-enabled scenario with no starting
  items: `items/enabled?` otherwise becomes true only after an item has actually been added.
  This is a local configuration detail, not a change to global item mechanics.
- `test/manor/arrival_belongings_test.clj` supplies a fixed kit and mock interview policy:
  private pooling, cap blocking, exactly-once arrival, opening/taking/giving the character,
  leaving luggage behind, scheduled return retaining real inventory, exact replay/load/branch,
  structural rejection, empty/no-reference kits, disabled callers, and collision-safe ids.
  Focused writer/hiring/item/visitor/delivery/setting checks are run offline; no paid generation,
  cast refresh or run deletion is part of this build.

No scope departure: retrieval on return and new leave mechanics remain excluded.

### Final review and validation

- Independent correctness and contract/test reviews both reported no findings, with an
  OK-with-notes verdict pending the complete offline suite. Final inspection accepted both
  reviews; no corrective code or regression-test changes were needed. No out-of-scope
  suggestions or material decisions remained.
- Fresh focused validation:
  `clojure -M:test --workers 1 -n manor.arrival-belongings-test -n manor.people-test -n manor.hiring-test -n manor.items-test -n manor.prompts-test`
  passed 81 tests / 1,549 assertions, with zero failures/errors. Evidence is retained under
  `test-runs/tests/bd33e052-88cb-416e-b714-27e0d2c9becd/worker-1/`.
- Complete `clojure -M:test` passed 573 tests / 10,617 assertions across four isolated JVMs,
  with zero failures/errors. Evidence is retained under
  `test-runs/tests/021f2063-9f8d-4841-a9da-b6c81531000c/` (worker logs, summaries and runs).
- The fresh focused `hiring-24781/transcript.md` shows owner-only first additions, ordinary
  open/take/give/put-down, departure and return without refill; it records $0 cost and zero
  errors, rejections or forfeits. All model execution was mock/offline; existing mock
  cast-writer tests use scratch fixtures, not authored cast regeneration.
- `git diff --check` passed. Delegated validation completed in `candidate-belongings-design`,
  based on `b6748db`, before publication. Approved pre-existing edits remained intact;
  no paid calls, main-checkout changes or run deletion occurred during implementation.
  The scenario-authoring contract and prompt catalogue were rechecked and remain current.

No implementation blocker remains. Real-model plausibility has not been assessed and is not
an acceptance requirement for this build; prose quality remains a prompt-level judgement,
never a phrase/regex validator. The user subsequently authorised committing the worktree
and merging into master, preserving unrelated local changes.
