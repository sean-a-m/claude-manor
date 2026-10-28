# Settings: one ruleset, many settings

Status: **in progress.** See section 8 for what's done and what's next; update it as each phase
is finished, so another agent can pick the work up. Decisions are in section 7.

## 1. Goal

Run completely different settings (a Qing palace, say) on the same mechanics as Claudeworth,
with no 1904 England showing through. Every full scenario gets **all** the mechanics; a mechanic
that doesn't fit a world just never comes up (no architect's office, so no plans are drawn), and
what differs between settings (currency, names, calendar, a little wording) is **data and prompt
templates**, not code. No per-scenario list of mechanics and no dependency resolution between
them.

Done when a skeleton palace scenario runs a mock day with no England in what it sends models
(best effort, then a short real run), and Claudeworth, Lodge and the medium household behave as
before.

## 2. What's hard-coded today

The survey, by kind. "Setting text" means model-facing English-1904 wording inside code.

**Structure: Claudeworth is the ruleset.** `scenarios/claudeworth.clj` holds the shared rules:
walk/call/travel/sleep/rouse, views, hibernation, the rules prompt, `init`, `problems`, the
scripted mock policy. `claudeworth_medium` and `lodge` reuse it by `assoc`ing over
`cw/scenario`. Building, shop, deliveries, money and holidays live under
`scenarios/claudeworth/`. Lodge drops the architect with a hand-written `:exclude-actions` list.
The meeting place is a string argument (`"carriage-sweep"`, `"front-gate"`) to `hiring/install`
and `deliveries/install`.

**Money: sterling is in the code.**
- `scenarios/claudeworth/money.clj`: 240 pence a pound, a `£ s d` formatter.
- `shop.clj`: the appraiser's tool field is `unit-price-pence`; its instructions say "Price
  realistically in integer old pence: 12d=1 shilling; 240d=£1", give English measures ("a stone
  of sugar is qty 14 of unit 1 lb") and "a village general store".
- `building.clj`: `:starting-funds` and the architect's price lists are "whole pounds";
  `:money-unit :old-pence`; tool docs say "whole pounds (£)".
- The rules prompt: "Prices are old sterling: £1 = 20 shillings = 240 pence (12d = 1s)".
- There is one account, `[:state :household :funds]`; purses exist only for the architect's fees.
- `tools/scene_claudeworth.clj` and `scene_inspect.clj` format money as sterling.

**Setting text in shared mechanics.** Section 4 sorts every model-facing prompt.

**Vocabulary in data structures.**
- `positions.clj`: `standings` is a fixed set (`:family :upper-servant :servant :outdoor
  :tradesman :professional :villager :visitor`), and `household-standings` decides who belongs to
  the household. `honorifics` (Mr, Mrs, Lord, Lady …) are stripped when filling address
  templates, and `{first-name}` is the first word of a name and `{surname}` the last (wrong for
  Chinese names, family name first).
- The people server (`mcp-servers/people`): one `names.edn` (rural England: first names,
  surnames, origins, dwellings), `settings #{"england-1900"}`, its own honorifics list, and the
  reputation relation `"fellow-servant"` ("was in service with").
- The house is `:domain :house`, hard-coded in `building.clj` (where the house's rooms are, new
  rooms' domain), `claudeworth.clj` (`house-bedrooms`) and `scene_claudeworth.clj`.
- `household` is the string `"household"` (posts, owners of bought goods).

**Time.** `clock.clj` labels are "Saturday 15 October 1904, 09:30" (weekday names; `Locale/ENGLISH`
is right, since the prompts are English). The holiday calendar `calendars/england-1904-1905.edn`
is added by Claudeworth's `init` (`holidays/add-to`). Example dates in tool docs ("1904-10-21")
are literals. Times are `HH:mm` everywhere.

## 3. The design

### 3.1 One ruleset

Move the generic body of `claudeworth.clj` into **`manor.ruleset`**, with building, shop,
deliveries and money moved from `scenarios/claudeworth/` to `mechanics/`. It exposes:

```clojure
(ruleset/scenario {:name :palace
                   :doc "..."
                   :defaults {:world-file "worlds/palace.edn" :max-cost 0.20}
                   :mock-day "worlds/palace-day.edn"      ; or :mock-policy / :mock-conversation
                   :mock-conversation f})
```

which returns the full scenario: every action, judge, bridge, system, hiring, deliveries and
visits installed, in the order they're installed now. Scenarios differ only by world file and
mock script. `claudeworth`, `claudeworth-medium`, `lodge` and the palace each become a few lines.
(`village`, `treasure` and `quiz` are left alone: they're small examples on their own rules.)

**Mechanics switch themselves off by the world, not by lists.** An action that needs a place or
a person that the world lacks isn't offered, using the existing `:available?` hook:
- the architect's five actions are available only when some location has the `:architect`
  service (Lodge's `:exclude-actions` list goes);
- the rules prompt and the judge brief include a mechanic's paragraph only when it applies
  (building with an office, deliveries with a shop that delivers, post with a post office),
  chosen in code as now;
- `invite` offers `post` only when there's a post office (the doc follows; the enum can stay,
  with the existing reason).

**The meeting place** moves to the world file (`:meta :meeting-place`), checked by `problems`.

**The home area** (`:domain :house` today) becomes the meeting place's domain, or
`:meta :home-domain` if a world needs to say otherwise. `"household"` stays as an internal id;
only its display name comes from the setting.

### 3.2 Setting packs: a directory each

A world names its pack in `:meta :setting-pack` (`"settings/england-1904"`), a classpath
directory shared by the worlds of one setting (Claudeworth, the medium household and Lodge all
use England's). One file per subject, so each stays short:

```
resources/settings/england-1904/
  setting.edn     era, how home is named, the few prompt parameters (below), clock label
  money.edn       the currency (3.4)
  people.edn      people-server data set id, honorifics, name order, standings
  positions.edn   the positions catalogue (moved from resources/positions/england-1904.edn)
  calendar.edn    starting holidays (moved from resources/calendars/england-1904-1905.edn)
  prompts/        setting fragments and overrides (3.3)
    money.md                 how the currency works, for agents and the appraiser
    invitation-examples.md   the invitation judge's "possible, if unlikely" examples
```

`setting.edn`, for England:

```clojure
{:id "england-1904"
 :era "England in 1904"                  ; "a simulation set in {{era}}"
 :home "the house"                       ; "invite someone to {{home}}"
 :household-name "the household"
 :everyday-things "cloths, paper, pans, salt, matches and the like"
 :measures "a stone of sugar is qty 14 of unit 1 lb, or qty 1 of unit 1 stone; half a dozen eggs is qty 6 of unit 1 egg"
 :invite-example "my father, Sir Henry Vane"
 :known-as-example "a curate from Ely"
 :clock {:label "EEEE d MMMM yyyy, HH:mm"}}
```

`people.edn`: `{:dataset "england-1900" :honorifics [...] :name-order :given-first
:standings {:family {:household? true} ... :visitor {}}}`. A world's `:meta :positions` still
overrides the pack's catalogue; architect price lists stay per world.

`init` reads the pack into `[:state :setting-pack]` (prompt fragments included, as text), so a
run doesn't depend on the files staying the same, and replay and `--load` keep working. A run
whose state has no pack (every run made before this change) is read as England, so old runs in
`runs/` still load.

### 3.3 Prompts as parameterised markdown

A small renderer, **`manor.prompts`**: `(render "rules/money.md" params)` reads a classpath
template once, replaces `{{key}}` (and `{{money.base-name}}`-style paths) from `params`, and
throws on a missing key, so a template can't silently show `{{home}}`. No conditionals in
templates: code picks which fragments to join, as `AGENTS.md` asks.

- `resources/prompts/` holds the shared templates.
- **Room to swap:** a pack may hold a file at the same relative path under its `prompts/`
  (`settings/qing-palace/prompts/judges/find-person.md`), which replaces the shared template for
  runs in that setting. So any moved prompt can be made setting-specific later without code,
  while the shared one stays neutral. (Lookup: pack first, then shared; added in phase 2.)
- A fragment that *is* the setting (section 4, group C) lives only in the pack and is passed to
  a shared template as a parameter.

### 3.4 Money

`manor.mechanics.money` (from `scenarios/claudeworth/money.clj`): amounts are integers in the
setting's smallest unit, always. `(display spec n)` formats from `:units` (prefix/suffix, or
singular/plural names); `(authored->units spec n)` converts world files' and price lists'
amounts. Every caller passes the run's spec (`(money/spec world)`). The appraiser's field becomes
`unit-price`, priced in `{{money.base-name}}` with the pack's `money.md`; reducers keep accepting
`:unit-price-pence` in old events.

```clojure
;; settings/england-1904/money.edn
{:units [{:value 240 :prefix "£"} {:value 12 :suffix "s"} {:value 1 :suffix "d"}]
 :zero "0d" :base-name "old pence" :authored 240 :authored-name "whole pounds"}

;; settings/qing-palace/money.edn
{:units [{:value 1000 :singular "tael" :plural "taels"} {:value 1 :singular "cash" :plural "cash"}]
 :zero "0 cash" :base-name "cash (copper coins)" :authored 1000 :authored-name "whole taels of silver"}
```

**Does a smallest-unit integer work for older currencies?** Yes, as long as the ratios between
units are fixed, which is what sterling's were (240d to the pound). The Qing case: silver was
weighed, not counted: 1 tael (liang) = 10 qian = 100 fen = 1,000 li. Everyday money was copper
coins, *wen*, called "cash" in English (a real period term, not shorthand). The official rate was
1,000 cash to the tael; the market rate floated (very roughly 700–1,100 in the 18th century).
Fixing the rate at 1,000 makes 1 cash = 1 li of silver, so one integer counts both, displayed as
"3 taels 250 cash". What one integer can't model is a floating rate, or someone holding silver and
copper separately; both belong with bartering (later), as goods rather than money. Roman, medieval
and most other "ancient" systems had fixed nominal ratios too, so the same spec covers them.

**Private accounts (phase 3b).** Small: about 15 call sites (shop's buy, deliveries' orders,
the commission, the architect's purse, views, the two scene tools) plus one argument. Accounts
become `[:state :accounts {owner amount}]` with `"household"` as today's account; a world file
can give an agent a starting `:purse`; `buy` and `commission` take an optional `from`
(`"household"` or `"own"`, default household); only the owner spends their own purse; bought
goods' owner follows the account. The architect's fee purse becomes an ordinary account.
Reducers read old `[:state :household :funds]` events unchanged. No `pay` action yet.

### 3.5 People

- The people server gets a data set per setting: `mcp-servers/people/settings/<dataset>/names.edn`
  (first names by sex, surnames, origins, dwellings, candidate homes), selected by the `setting`
  argument the engine already sends, now from the pack. `temperament.edn` and `faculties.edn`
  stay shared (traits, not setting).
- Name order: the server builds "Family Given" for `:family-first`; `positions/fill` reads
  `{surname}` and `{first-name}` by the pack's `:name-order`, and strips the pack's honorifics.
- Reputation relations get neutral wording ("fellow-servant" → "worked alongside").
- Standings come from the pack: any keyword it lists is valid, and `:household? true` replaces
  `household-standings`. Positions validation reads them from the run's pack.

## 4. The prompt pass

Every model-facing prompt, sorted. Only groups B and C move now; group A stays in code until
it's touched for another reason (it can then move with no wording change).

**A. Already neutral: no change.**
- Items: the scene judge's instructions (its examples, "the pantry", "about 17 lb left", are
  illustrations, not assumptions), the steward, the finder (`find`), item tool docs.
- Conversation: the conversation judge, the speaking-turn closings, `converse`'s docs.
- Inner state, physical status (except one phrase, in B), following, space, builtins' tool docs.
- The turn closing, the GM preamble, the Claudeworth GM brief.

**B. Neutral after small rewording: one shared template, no setting fragment.** Parameters are
from `setting.edn` (`{{home}}`, `{{era}}`, `{{everyday-things}}` …) or from the run (example
dates from the clock's start, the meeting place).
- **Agent preamble** (`prompt.clj`): "the simulation of an Edwardian manor house" → "a
  simulation"; the role brief already carries the setting paragraph. Fix its typos here.
- **Rules prompt** (`claudeworth.clj` `rules`): mechanics throughout; reword "the whole house" →
  `{{home}}`, "a shop or the public house" → "a shop or other place of trade", "household account"
  → `{{household-name}}`'s account, "tradesmen's bills" → "bills", "errand boy or carrier" → "the
  shop's courier", everyday things → `{{everyday-things}}`. The currency sentence comes from
  `money.md` (C).
- **Judge brief** (`claudeworth.clj` `judge-brief`): `{{home}}`, `{{everyday-things}}`; the
  architect and delivery paragraphs only when those mechanics exist.
- **Physical rules:** "a few steps within the house" → "a few steps from where you are".
- **Find-person** judge and doc: examples become kinds ("a nickname, a family term, a form of
  address or a position"); "a house or a village is small" → "the area is small"; drop "the
  servants' quarters".
- **Hiring** rules and `recommend` doc: "a servant, a shopkeeper, a neighbour" → "someone who
  works nearby, a shopkeeper, a neighbour"; "your village" / "around the village" → "the area";
  "wait at the house, in the village or at the pub" → "anywhere in the mapped area"; "Outdoor
  servants' lodging" → "lodging for staff who live out"; example dates from the clock.
- **Visits** rules, `invite` and `schedule-visit` docs: `{{home}}`, `{{invite-example}}`,
  `{{known-as-example}}`, example dates from the clock, the post paragraph only with a post office.
- **Persona writer** (`people.clj` polish): "an English village circa 1900" → "set in {{era}}"
  (the setting paragraph is already in its data). **Appearance writer:** the same.
- **Shop appraiser and shop docs:** "a village general store" → "a small general shop"; English
  measures → `{{measures}}`; "the customer's house" → `{{home}}`; pence → `money.md` (C).
- **Deliveries** docs: "up to the house" → `{{home}}`; the default courier position comes from
  the pack's catalogue (`:errand-boy` stays England's).
- **Architect** docs: "whole pounds (£)" → `{{money.authored-name}}`; shown only with an office.

**C. Setting fragments: in the pack only.**
- `money.md`: how the currency works and how to price in its base unit (agents' rules and the
  appraiser).
- `invitation-examples.md`: the invitation judge's examples of the possible-but-unlikely (the
  Queen calling on the Earl, a cousin in India, "Your father died in 1898"). Neutral examples
  would teach the judge less.

Test fixtures and offline stubs ("a cottage in the next parish") aren't prompts to a real model;
they get neutral text when their file is touched.

## 5. The palace skeleton

Deliberately small: enough to touch every mechanic once, not a designed scenario. Real era (the
1780s), fictional people and palace names.

- **Setting pack** `settings/qing-palace/`: era "the Qing imperial palace in the 1780s", home
  "the inner court", money in taels and cash (3.4), names family name first, standings
  `:imperial :consort :eunuch :palace-maid :official :merchant :visitor` (the first four
  `:household? true`), a clock label without weekdays, a near-empty calendar.
- **Positions** (`positions.edn`): consort (fei), noble lady (guiren), chief eunuch, eunuch,
  palace maid, a merchant who keeps a stall (`:shop-clerk`), an errand eunuch (the stall's
  courier), guest.
- **World** `worlds/palace.edn`: domain `:inner-court` (one consort's palace: hall, bedchamber,
  side rooms, eunuchs' quarters; a noble lady's smaller pavilion; a garden; a long covered
  street) and domain `:outside` (a palace gate as meeting place, a silk and sundries stall beyond
  it, with hours). Five agents: the consort, the noble lady, the consort's chief eunuch, the
  noble lady's maid, the stall keeper. The Emperor is off stage, in the setting paragraph. A few
  items (a jade hairpin, tea things, a lacquer chest), a store or two, and starting purses if
  phase 3b is done. No architect's office and no post office, so neither mechanic appears.
- **Personas:** by hand as placeholders, or `roll-cast!` once the people server has the palace
  data set (a few cents of real calls; ask first).
- **Mock day** `worlds/palace-day.edn`, scripted like Lodge's: someone walks, calls, converses,
  buys at the stall, takes and gives something, spends time, sleeps.
- **Scenario** `scenarios/palace.clj`: `ruleset/scenario` with the world and the mock day.
  Added to `bundled` in `scenarios_test.clj`.

## 6. Checks

- **Prompt diff, the refactor's safety net.** Before phase 1, record every request (messages
  and full tool definitions) of a mock day of Claudeworth, Lodge and the medium household, and
  render each prompt that's going to move (the judges' and writers' requests too, which a mock
  day may not build) against a fixed world. After each phase, record again and diff. Only the
  phase's intended changes may appear; review the diff by eye. Phase 1 must diff empty.
- **England in the palace:** best effort. Dump the palace mock day's prompts and read them, then
  a short real run (8–12 rounds, `:max-cost` 0.20, kept under `old-test-runs/`) and read its
  transcript for anything clearly missed. No automated word-list test.
- Replay equality, the existing suite, and the mock smoke for all four household scenarios.

## 7. Decisions

From the review of the first draft (2026-10-28):

1. Private accounts are small (3.4), so they're done, as phase 3b. No `pay` action yet.
2. One household for now. What a "household" is (each consort's palace its own?) is a later
   refactor.
3. Money is counted in the smallest unit under the hood; for the palace, cash (3.4).
4. Gregorian dates without weekdays and `HH:mm`. Lunar dates and double-hours are later.
5. Real era, fictional people and palace names.
6. Hiring keeps its sequence with neutral wording for now. Later it should lose the negotiation
   for settings where people are assigned, perhaps sharing code with a general
   assign-a-person mechanism.
7. No automated leak test: best effort, then a real run (section 6).
8. The shared ruleset is `manor.ruleset`, and `scenarios/claudeworth/*` move to `mechanics/`.
9. Phase 1 moves prompts verbatim (typos included) so its diff is empty; the agent preamble's
   typos are fixed in phase 2, where it's reworded anyway.
10. The old stashed proposal is not part of this work; don't read or touch it.

**Later, outside this work:** dispatching messages without a post office (messengers, notes
carried by hand); several households; hiring rework (6); lunar calendar; bartering and holding
goods as money.

## 8. Phases and progress

Each phase leaves `clojure -M:test` green and the prompt diff explained, and is committed on its
own branch (worktree under `~/development/git-worktrees/claude-manor/`), merged into master when
reviewed. **When you finish a phase, mark it here with the date, the commit, and anything the
next phase must know.**

| Phase | What | Status |
|---|---|---|
| 0 | Baseline prompt capture | done 2026-10-28 (`57fc5ea`) |
| 1 | Renderer, templates moved verbatim | done 2026-10-28 (`d2425e4`, `2926604`); diff empty |
| 2 | Setting packs, neutral rewording, overrides | not started |
| 3 | Money specs | not started |
| 3b | Private accounts | not started |
| 4 | One ruleset | not started |
| 5 | People and positions from the pack | not started |
| 6 | Palace skeleton | not started |
| 7 | Docs | not started |
| 8 | Short real palace run (8–12 rounds, $0.20) | not started |

0. **Baseline.** A REPL helper in `dev/user.clj` (or a small `dev/` namespace) that runs a mock
   day of `claudeworth`, `lodge` and `claudeworth-medium` (`{:test? true}`, fixed seed, no port)
   and writes every request, messages and full tool definitions, to a gitignored directory
   (`prompt-baseline/`), plus the rendered text of every prompt that phase 1 moves, against a
   fixed world. Re-running it writes a second capture to diff against.
1. **Renderer and templates, no wording change.** `manor.prompts` (`render`, `{{key}}` and dotted
   paths, missing key throws, classpath cache); move the prompts of section 4 groups B and C into
   `resources/prompts/` verbatim, with code supplying today's values as parameters where text was
   computed. Diff: empty.

**Phase 0, done (2026-10-28, `57fc5ea`).** `dev/prompt_capture.clj`:

```sh
clojure -M:dev -e "(require 'prompt-capture) (prompt-capture/capture! \"before\") (shutdown-agents) (System/exit 0)"
# change the code, then the same with "after", and
diff -r prompt-baseline/before prompt-baseline/after
```

`capture!` replaces `prompt-baseline/<label>/` (gitignored, about 53 MB, under a minute). For
`claudeworth`, `lodge` and `claudeworth-medium` it runs a 48-round mock day (seed 1, offline,
under `test-runs/`, no port) and writes `<scenario>/round-NN.md`: every request of the round, its
messages and full tool definitions (a wrapper around the mock LLM keeps the definitions by digest,
joined to llm.edn's calls), sorted by agent and then text, because agents in one schedule group
and jobs judged together call concurrently. Calls: 352, 179 and 593. `rendered/` holds what a
mock day may not build, rendered against the worlds at their start: the rules (Claudeworth; with
`find` refused at shops; without visits; Lodge's, without an architect), the judge brief, the
physical, hiring and visits rules, the find-person, invitation, persona, appearance and shop
requests in full, the moved tool docs, and every tool of Claudeworth and Lodge. Two captures of
unchanged code diff empty (checked).

**Phase 1, done (2026-10-28, `d2425e4` renderer, `2926604` templates).** `manor.prompts/render`
(`src/manor/prompts.clj`, tests in `test/manor/prompts_test.clj` with fixture templates under
`test/prompts/test/`): reads `prompts/<path>` from the classpath once, drops the file's one final
newline, fills `{{key}}` and dotted `{{a.b}}` (keyword or string keys), throws ex-info with
`{:template :key}` for a missing or nil value; values go in with `str` and aren't filled again.
`diff -r prompt-baseline/before prompt-baseline/after` was empty, and `clojure -M:test` passed
(466 tests, 9184 assertions). The templates, and who renders them:

| Template (under `resources/prompts/`) | Rendered by | Parameters |
|---|---|---|
| `agent/preamble.md` | `prompt/agent-preamble` | |
| `rules/how-things-work.md` | `claudeworth/rules` | `sections`, the sections below joined by a blank line |
| `rules/sections/turns.md` | ″ | `turn`, `max-queries`, `counted` (`turns-counted-architect.md` or `turns-counted.md`) |
| `rules/sections/getting-about.md`, `sleep.md` | ″ | |
| `rules/sections/speaking.md` | ″ | `speech-limit`, `max-others`, `passes` |
| `rules/sections/work.md` | ″ | `max-work-hours` |
| `rules/sections/things.md` | ″ | `find-for-sale` (`things-find-everywhere.md` or `things-find-not-at-trade.md`) |
| `rules/sections/people.md` | ″ | `physical` (`rules/physical.md`) |
| `rules/sections/shopping.md` | ″ | `paid` (`shopping-paid-commissions.md` or `shopping-paid.md`) |
| `rules/sections/building.md` | ″, only with an architect | `sizes` (joined), `fee-percent` |
| `rules/sections/hiring.md` | ″ | `hiring` (hiring's rules) |
| `rules/sections/invitations.md` | ″, only with visits | `visits` (`rules/visits.md`) |
| `rules/physical.md` | `physical/rules` | |
| `rules/hiring.md` | `hiring/rules`, `hiring/rules-beside-visits` | `arrivals` (`rules/hiring-arrivals.md` or `rules/hiring-arrivals-beside-visits.md`) |
| `rules/visits.md` | `visits/rules` | |
| `judges/brief.md` | `claudeworth/judge-brief` | `setting` (`""` when the world has none, as before) |
| `judges/find-person.md` | `find-person/instructions` | |
| `judges/invitation.md` | `visits.judge/instructions` | |
| `judges/shop-appraiser.md` | `shop/instructions` | `max-lead-hours`, `max-quantity` |
| `writers/persona.md` | `people/instructions` (the polish) | `max-circumstance`, `advised-persona`, `max-persona`, `max-manner`, `advised-appearance`, `max-appearance` |
| `writers/appearance.md` | `people/appearance-request` | `advised-appearance`, `max-appearance` |
| `docs/find-person.md` | `find-person`'s `:doc` | `max-who` |
| `docs/hiring/recommend.md` | `recommend`'s `:doc` | |
| `docs/visits/invite.md`, `schedule-visit.md` | `invite` (`max-person`), `schedule-visit` | |
| `docs/shop/quote-goods.md`, `buy.md`, `send-up.md` | the shop's actions | `max-lines`; `send-up` also `send-up-max-hours` |
| `docs/architect/*.md` | the five architect actions | |

What phase 2 must know:
- **Everything renders once, at namespace load**: the instructions, the rules of physical, hiring
  and visits, and every tool `:doc` are `def`s, and `actions/->tool` reads `:doc` as a fixed
  string. Pack-first lookup needs the run's pack, so these become functions of the world (or the
  scenario's actions are built per run). Only `claudeworth/rules`, the judge brief and the
  appearance writer render per call today. The cache is keyed by path alone; a pack override
  needs the pack in the key.
- **Not moved, though phase 2 touches them:** `hiring/take-leave-doc` (the example date
  "1904-10-21"; `return-reason`'s message has it too), the judges' and writers' own tool
  descriptions (`seek`, `invitee`, `polish`, `appraise`, `appearance`). Deliveries has no
  model-facing docs of its own: its tool docs are the shop's `quote-goods` and `send-up`.
- **Group C is still inside shared templates:** the sterling sentence ends
  `rules/sections/shopping.md` and is in `judges/shop-appraiser.md` ("Price realistically in
  integer old pence ..."); the invitation examples are in `judges/invitation.md` (the "Pugh the
  architect" and "a curate from Ely" examples, "Your father died in 1898.", the Queen line).
- **Whitespace that matters:** `writers/appearance.md` ends with a space before its final newline
  (the old string did); an editor that strips trailing spaces changes the prompt. The space
  before `{{find-for-sale}}`'s fragment is in `things.md`, so the fragments have none.
- The typos are verbatim, as decision 9 says ("You are a an agent ... a the simulation ... with
  other agent", and the persona writer's run-together sentences: "circumstances.Use",
  "interpretation.circumstances", "area.Present", "concrete.Period", "welcome.manner").
- Group A prompts and the GM brief are still in code, as planned.
2. **Setting packs.** The directory, loader, `[:state :setting-pack]`, England as default for old
   runs, `settings/england-1904/`; pack-first template lookup; group B rewording and group C
   fragments; holidays and positions catalogue from the pack; clock label from the pack; example
   dates from the clock. Diff: the rewording, reviewed.
3. **Money specs.** `mechanics/money.clj` with specs; shop, building, deliveries, views and scene
   tools use the run's spec; `unit-price`. Diff: the appraiser's field and docs.
3b. **Private accounts** (3.4).
4. **One ruleset.** `manor.ruleset`; move building, shop, deliveries; meeting place and home
   domain from the world file; architect actions by `:available?`; conditional judge-brief
   paragraphs; Claudeworth, medium and Lodge become thin. Diff: Lodge's judge brief loses the
   architect paragraph it never needed; otherwise unchanged.
5. **People and positions.** Standings, honorifics and name order from the pack; people-server
   data sets; reputation wording. Diff: none for England.
6. **Palace skeleton** (section 5), and the best-effort read of its prompts.
7. **Docs.** `scenario-authoring.md` (setting packs, `:meta :meeting-place`, money specs,
   templates and overrides), roadmap A3 (partly done by this), `AGENTS.md`'s table and layout.
8. **Real run**, then read the transcript.
