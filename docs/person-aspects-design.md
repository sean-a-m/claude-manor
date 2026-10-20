# Person aspects: state on characters that work changes

Status: **designed, not built.** The user's decisions of 2026-10-20 are in section 4; the
phases are in section 5. Follow-ups are in the roadmap (B12).

## 1. The want

Characters should carry state that changes with what they do and what's done to them, judged
the way `spend-time` already judges items and rooms: Mrs Finch at the range ends with a
grease-spattered apron; a lady's maid spends half an hour on her ladyship's hair and it's
"dressed high for dinner"; someone spends time on themselves (washing, changing, dressing for
dinner), and the result stays on them until something else changes it.

## 2. What exists today

- Items and locations have **aspects**: at most 4 keyed phrases (`items/max-aspects`), keys
  such as `:cleanliness`, values advised to 80 characters and capped at 200. Item conditions
  became aspects in decision 40 of `claudeworth-design.md`, so this is the house vocabulary.
- The **judge** (`items/judge-request`) gets a `scene` with `place`, `focus`, `also_here`,
  `stores`, `people` (`{:name :doing}` only, as context) and `recent`. It returns
  `changes [{thing aspect value}]`, which `checked-verdict` validates against the judged things;
  the `:item/work-done` reducer applies them through `items/changed` (an item or a place).
  People are explicitly "context, not things to change".
- `spend-time {doing hours? on?}`: `on` accepts items here or carried, the place, and stores
  in scope (`items/work-reason`). Not people.
- Agents have a fixed `:appearance` (written at roll-cast or hiring) that shows in
  `people-here` every turn (`space/person`), in `look` (`items/look-person`) and in the
  character's own system prompt ("What people see:").
- Jobs with disjoint `job-scope` are judged concurrently; overlapping scopes run in order.
- The nightly **steward** (`:items :steward`) makes one call per place with idle candidates,
  and may change, use up or absorb only those candidates.
- `sleep` is a separate action with no judgement; it occupies the sleeper with `:asleep? true`.

### Evidence from real runs

- `old-test-runs/claudeworth-stores-phase6`: Lady Claudeworth spent 30 minutes "dressing for
  dinner — evening gown, hair done, gloves and jewels set out". The judge's summary says so
  ("evening gown on, hair done"), but its only changes are to the room ("hearth lit ...,
  window ledge dusted"). The summary is the only record, and it scrolls away; afterwards her
  view and everyone else's still show her `:appearance` in dark wool.
- `runs/claudeworth-20261019-210747`: Mrs Finch wondered in her thoughts whether a cook should
  walk into the drawing room in her apron. Nothing records whether the apron is clean or what
  she has on.
- Brushing out the range flue, raking soot from the chimney throat, swilling plaster dust off
  the kitchen flags: the judge changed the room and left the worker untouched.
- "Dining with her ladyship" and "standing by while her ladyship counts the sheeting" turn up
  as matching jobs by two people. Joint activity already happens informally.

## 3. Options considered

- **A. Aspects on people: chosen.** Agents get `:aspects` like items, and the judge may change
  the worker and the people the worker names. It reuses the judge's checks, `:item/work-done`
  and job scope.
- **B. Worn things as items** (an apron item with its own `:cleanliness`): clothes could be
  laundered, lent or put away, but every character needs a wardrobe of items, and hair and hands
  still aren't items. Not now; it may suit a later lady's-maid or laundry scenario.
- **C. Code-driven needs** (fatigue and hunger meters): deterministic, but not judged state.
  Inner states go to private aspects instead (roadmap B12).

## 4. Decisions (the user, 2026-10-20)

1. **Overnight, the steward removes.** The nightly steward also sees the agents asleep at its
   time, and removes whichever of their aspects don't seem likely to last the night (hair let
   down, evening dress taken off; a grease-stained apron stays stained). Which ones is the
   steward's judgement. It only removes aspects from sleepers and never sets one. Nothing else
   reverts a person's aspects; someone who stays up all night keeps theirs.
2. **Keys are open,** as for items: any well-formed key, with suggested keys and glosses from
   the scenario. A mix of fixed keys that always exist (`:dress`) and open ones is a follow-up
   (roadmap B12).
3. **Everyone present sees them,** every turn: in `people-here` and `look`, next to
   `appearance`, and in the person's own view under `:you :aspects`.
4. **Only outward state.** Person aspects are what shows and lasts beyond the activity: dress,
   hair, hands and face, dirt and wet, a visible injury. Inner states (hunger, tiredness, moods)
   wait for private aspects (roadmap B12, possibly next).
5. **Appearance is the fixed baseline.** `:appearance` never changes; aspects say how the
   person differs from it now. The people writer may need to word changeable things in
   appearance as habits ("usually in a print frock and apron") rather than facts (roadmap B12).
6. **Acting on someone needs only that they're there at the end.** Naming a person in `on`
   asks nothing of them: they needn't be free, awake or willing. When the job ends they are
   judged if they're at the job's place, and not otherwise. They may leave, come back, or
   `rouse` the worker, who then stops early and is judged on the time worked. Agents are told
   how this works (section 5.4). Consent is for the characters to work out among themselves;
   realism is for the judge.
7. **No bystanders.** Only the worker and the people they named can change; everyone else
   present stays read-only context. Whether the judge should reach bystanders (soot over the
   whole kitchen) is a follow-up (roadmap B12).
8. **No quick changes.** Changing your own state takes `spend-time`, at least half an hour.
   A free, quick change ("taking off my apron") is a follow-up (roadmap B12).
9. **Tested with a fixture world:** no test is designed around Claudeworth's content, which
   changes often; a small test scenario with its own world file is used (section 5.5).
   Claudeworth still gets the feature (section 5.4).

Defaults taken where nothing was decided, to confirm when the build starts:

- The name is **aspects**, the same word as for items and rooms.
- The cap is the item cap: 4 aspects per person.
- Only the judge, the steward, the views and the GM see person aspects. Other adjudicators
  (shop quotes, hiring interviews) don't, for now.
- People made during a run (hired candidates, couriers, the GM's spawns) start with no
  aspects; routines don't change them.

## 5. The build

### 5.1 Data and views

- An agent may have `:aspects`, checked as an item's are (`items/aspect-key?`, value lengths,
  `max-aspects`). World files and `:init` agents may give them; `worldfile` checks them, and
  `map.edn` exports them.
- `space/person` (`people-here`) and `items/look-person` add `:aspects` when there are some;
  `prompt/agent-view` adds `:you :aspects`; the GM view's agents include them. The system
  prompt is unchanged, so its cached prefix is too.
- The panel's agent list and the transcript show them.

### 5.2 The judge

- `spend-time`'s `on` accepts the id of a person at your place, yourself included. A person
  elsewhere gets an actionable reason listing who is here.
- The **worker is always a judged thing**, named or not. The people named in `on` are judged
  when the job ends if they're active and at the job's place.
- The scene's `people` entries gain `id`, `appearance` and `aspects`; judged ones are marked
  `person: true`. Others keep only `name` and `doing`, as now.
- `checked-verdict` turns a change to a person into `{:person id :aspect k :from :to}`, and
  `items/changed` sets it under `[:agents id :aspects]`. People can't be used up, absorbed or
  made into anything, and `made`'s `in` can't name one.
- `job-scope` adds `[:person id]` for the worker and the people named, so two jobs on the same
  person (her ladyship dressing, her maid doing her hair) run one after the other, and the
  second judge sees the first's result.
- `empty-activity?` no longer skips the call when nothing is named, because the worker can
  always change. A job with no time worked still ends without a call.
- Narration reads "(Lady Claudeworth: hair dressed high, with combs)", as for items.
- New lines in the judge instructions, in this sense: *A person's aspects say how they are now,
  where it shows and lasts beyond the activity: dress, hair, hands and face, dirt and wet, a
  visible injury. Never moods or feelings. Their `appearance` is how they usually look; aspects
  record departures from it, and an aspect that only restates it should be removed. Most work
  leaves the worker as they were. Change another person only when the activity was done to
  them.* Scenarios can suggest person keys through the existing `:aspect-keys`.
- `items/mock-judge` gives each named person a placeholder aspect, and leaves the worker alone
  unless named, so existing mock scripts and their results don't change.

### 5.3 The steward overnight

- The steward's pass also looks at **sleepers**: active agents whose `:busy` has `:asleep?` and
  who have aspects. A sleeper belongs to their location's call. A place with sleepers but no
  idle candidates now gets a call (in Claudeworth, about one per occupied bedroom per night,
  and only for sleepers with aspects).
- The request lists `sleepers [{id name appearance aspects}]`, with instructions to remove the
  aspects that wouldn't last the night and leave the rest. A change to a sleeper must have the
  value `""`; setting anything is refused and corrected once, as usual.
- Changes come out in `:items/stewarded` (GM-only) as `{:person id :aspect k :from v :to nil}`.
  The sleeper finds them gone from their view on waking; nobody is told.
- The default `items/mock-steward` removes nothing from sleepers, so existing mock days don't
  change; the fixture scenario's mock steward removes something.

### 5.4 What agents are told

The `spend-time` tool description, and the guidance `scenario-authoring.md` gives for briefs,
say:

- `on` may name people here, yourself included. What you do to someone takes effect only if
  they're still with you when you finish. They may walk away, and they may rouse you to stop
  you early, in which case only the time you worked counts.
- What's notable about how someone looks now is in their `aspects`, beside their usual
  `appearance`, and yours are under `you`.
- When someone is spending time on you (doing your hair), it counts only if you're still with
  them when they finish: stay if you want it done.

Person aspects come with the items module (a scenario's `:items` entry), so every scenario
with items gets them, Claudeworth included, and its judge starts writing them in phase 1.
Claudeworth's rules get the second and third points, and its `:aspect-keys` a few person keys,
in phase 2. No test depends on Claudeworth's content, which changes often: the tests use the
fixture (5.5).

### 5.5 Fixture and tests

A test scenario reusing the items mechanics (like `household.clj`), with its own world file in
`test/fixtures/`: a lady's bedroom with a dressing table, a kitchen with a range, a lady, a lady's
maid and a cook, one starting with aspects. Its mock judge and mock steward return person
changes. Tests:

- world-file and `:init` checks on agent aspects; the export round trip;
- views: `you`, `people-here` and `look` show aspects to everyone present, and not to anyone elsewhere;
- self-work changes the worker without `on`; naming a person who stays changes them, and one
  who left before the end doesn't change;
- `on` refuses a person elsewhere, with the reason; people can't be used up, absorbed or used as `in`;
- two jobs on one person run in order (job scope);
- the steward removes sleepers' aspects, refuses a set, and ignores people who are awake;
- replay equals the live world.

The fixture goes in `bundled` in `scenarios_test.clj`. Seeing how a real judge handles people
would need a short real run of the fixture world, only with the user's go-ahead.

## 6. Phases

1. **Self:** 5.1, the worker always judged (5.2 without `on` people), and narration. Docs.
2. **Others:** `on` accepts people, presence at the end, `job-scope`, and the agent wording
   (5.4), including Claudeworth's rules and person keys.
3. **Overnight:** the steward's sleepers (5.3).

Each phase passes `clojure -M:test`, adds its fixture tests, and updates
`docs/scenario-authoring.md` (Items, Work and the judge, The nightly steward, World files).
