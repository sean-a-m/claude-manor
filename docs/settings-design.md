# Settings: one ruleset, many settings

Status: **phases 0–7 done; phase 8 (a short real palace run) not started.** Section 8 records
each phase; section 9 is the handoff for a review of the whole refactor. Decisions are in
section 7.

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

The original survey, before this refactor, by kind (current progress is in section 8).
"Setting text" means model-facing English-1904 wording inside code.

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
is right, since the prompts are English). The holiday calendar (now `settings/england-1904/calendar.edn`)
is added by the ruleset's `init` (`manor.ruleset.init/add-holidays`, from the loaded pack). Example dates in tool docs ("1904-10-21")
are literals. Times are `HH:mm` everywhere.

## 3. The design

### 3.1 One ruleset

The generic body of `claudeworth.clj` is now **`manor.ruleset`** (phase 4), with building, shop,
deliveries and money moved from `scenarios/claudeworth/` to `mechanics/`. It exposes:

```clojure
(ruleset/scenario {:name :palace
                   :doc "..."
                   :defaults {:world-file "worlds/palace.edn" :max-cost 0.20}
                   :mock-day "worlds/palace-day.edn"      ; or :mock-policy / :mock-conversation
                   :mock-conversation f})
```

which returns the full scenario: every action, judge, bridge, system, hiring, deliveries and
visits installed, in the order they were installed before. Scenarios differ only by world file and
mock script. `claudeworth`, `claudeworth-medium`, `lodge` and the palace each become a few lines.
(`village`, `treasure` and `quiz` are left alone: they're small examples on their own rules.)
One option is not data from the world: `:calendar? false` leaves out the calendar and with it the
pack's holidays (Lodge's content choice; visits work without a calendar, their rules then saying
nothing of one).

**Mechanics switch themselves off by the world, not by lists.** An action that needs a place or
a person that the world lacks isn't offered, using the existing `:available?` hook:
- the architect's five actions are available only when some location has the `:architect`
  service (`building/office?`; Lodge's `:exclude-actions` list is gone). As tools with an
  `:available?` check, they now come at the end of the tool list;
- the rules prompt and the judge brief include a mechanic's paragraph only when it applies
  (building with an office, deliveries with a shop that delivers, post with a post office),
  chosen in code as now;
- `invite` offers `post` only when there's a post office (the doc follows; the enum can stay,
  with the existing reason).

**The meeting place** is in the world file (`:meta :meeting-place`), checked by `problems`, and
kept at `[:state :meeting-place]` (`space/meeting-place`).

**The home area** (Claudeworth's `:domain :house`) is the meeting place's domain, or
`:meta :home-domain` if a world needs to say otherwise (`space/home-domain`). `"household"` stays as an internal id;
only its display name comes from the setting.

### 3.2 Setting packs: a directory each

A world names its pack in `:meta :setting-pack` (`"settings/england-1904"`), a classpath
directory shared by the worlds of one setting (Claudeworth, the medium household and Lodge all
use England's). One file per subject, so each stays short:

```
resources/settings/england-1904/
  setting.edn     era, how home is named, the few prompt parameters (below), clock label
  money.edn       the currency (3.4)
  people.edn      people-server data set id, honorifics, name order, standings (phase 5)
  positions.edn   the positions catalogue
  calendar.edn    starting holidays
  prompts/        setting fragments and overrides (3.3)
    money.md                 how the currency works, for agents' shopping rules
    money-pricing.md         how the shop appraiser prices in it
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
 :unit-examples "1 lb, 1 loaf, 1 tin or 1 dozen"           ; the appraiser's units (phase 6)
 :amount-examples "\"a stone\", \"2 lb\", \"half a dozen\", \"3\""  ; quote-goods' amounts (phase 6)
 :invite-example "my father, Sir Henry Vane"
 :known-as-example "a curate from Ely"
 :courier-position :errand-boy            ; a position in the pack's catalogue
 :clock {:label "EEEE d MMMM yyyy, HH:mm"}}   ; the palace adds :day-label and :date-label (phase 6)
```

`people.edn`: `{:dataset "england-1900" :honorifics [...] :name-order :given-first
:standings {:family {:household? true} ... :visitor {}}}`. A world's `:meta :positions` still
overrides the pack's catalogue; architect price lists stay per world.

`manor.settings/load-pack` reads `setting.edn`, `money.edn`, `people.edn` (as `:people`), `positions.edn`, `calendar.edn` and every markdown
file recursively under `prompts/`. The ruleset's `init` stores the resulting immutable map in
`[:state :setting-pack]`: setting parameters at the top level, `:source` (classpath directory),
`:money`, `:positions`, `:calendar` and `:prompts` (relative template path → text without one final newline).
The initialized event therefore contains the pack snapshot, including fragment and override text;
replay does not read pack files. `settings/pack` reads a state without one as England for pack-less
test fixtures, not as old-run compatibility (decision 11). A world file without
`:meta :setting-pack` also defaults to England. `settings/parameter` takes a world and keyword path,
for example `(settings/parameter world :home)` or `(settings/parameter world :clock :label)`.
`settings/parameters` excludes `:source`, `:positions`, `:calendar`, `:people` and `:prompts`.

Positions use an explicit `:meta :positions` catalogue first, otherwise the pack's catalogue, with
`:roles` merged over either. `ruleset-init/add-holidays` reads the snapshot's calendar; Lodge
(`:calendar? false`) has no calendar. A delivering shop uses its own `:courier`, otherwise the pack's
`:courier-position`; that position must exist in its merged catalogue. Currency formatting
uses `:money`, and standings, honorifics, name order and the people server's data set come from
`:people` (phase 5).

### 3.3 Prompts as parameterised markdown

A small renderer, **`manor.prompts`**: `(render world "rules/sections/shopping.md" params)`
uses the world's snapshotted pack override, otherwise a shared classpath template. It replaces
`{{key}}` (and `{{money.base-name}}`-style paths) from setting parameters merged under explicit
`params`, and throws on a missing key, so a template can't silently show `{{home}}`. The one- and
two-argument arities use England for callers without a world. No conditionals in
templates: code picks which fragments to join, as `AGENTS.md` asks.

- `resources/prompts/` holds the shared templates.
- **Room to swap:** a pack may hold a file at the same relative path under its `prompts/`
  (`settings/qing-palace/prompts/judges/find-person.md`), which replaces the shared template for
  runs in that setting. So any moved prompt can be made setting-specific later without code,
  while the shared one stays neutral. Only shared templates are cached by path: overrides are
  already text in the run's state, so the cache cannot mix packs.
- A fragment that *is* the setting (section 4, group C) lives only in the pack and is passed to
  a shared template as a parameter (`money.md` and `invitation-examples.md`).
- Moved instructions and rules render when a request is built. An action's `:doc` may be a string
  or `(fn [world] text)`; `prompts/doc` constructs the latter. `(actions/description action world)`
  resolves it, and `(actions/->tool action world)` adds the kind/limit hints and schema. Both model
  requests and human-seat panel forms pass the live world; tool digests hash that resolved tool.
  The one-argument `->tool` remains for default-setting callers. Writer requests accept a world
  before their input, with old one-input arities defaulting to England.
- `clock/world-label` uses `:clock :label`, falling back to the existing English pattern.
  `prompts/example-date` uses the run clock's start date (stable over the run), or `1970-01-01`
  without a clock. This is a format example, not a promised future appointment.

### 3.4 Money

Implemented in **`manor.mechanics.money`** (moved there in phase 4): amounts are integers in the setting's smallest unit, always.
`(display spec n)` formats from descending positive-integer `:units`, ending at value 1
(prefix/suffix joined directly, or singular/plural names separated by a space), omitting zero
components, using `:zero` for zero and a leading minus sign for negatives.
`(authored->units spec n)` multiplies by `:authored` at world-file/architect boundaries.
Every caller passes the run's spec (`(money/spec world)`). The spec is part of the pack snapshot
at `[:state :setting-pack :money]`, and a rendering parameter `:money` (including dotted paths).
Narration's `names` map carries it at `[:money]`; describe methods format with that snapshot,
not the current pack on disk. The appraiser returns `unit-price` in `{{money.base-name}}`,
explained by the pack's `money-pricing.md` (separate from agents' `money.md`).
`:unit-price-pence`, the old formatter API, `:money-unit` and `:authored-money` are removed;
old events/state need not keep working (decision 11).

```clojure
;; settings/england-1904/money.edn
{:units [{:value 240 :prefix "£"} {:value 12 :suffix "s"} {:value 1 :suffix "d"}]
 :zero "0d" :base-name "old pence" :authored 240 :authored-name "whole pounds (£)"}

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

**Private accounts (phase 3b, implemented).** Accounts are
`[:state :accounts {owner amount}]`, with `"household"` as the shared account and agent ids as
private owners. A world file agent may have a starting `:purse`, a non-negative integer in the
spec's authored unit (`:authored-name` names it in validation). Explicit zero creates an account;
omission gives none, except architects start at zero for their fees. `household` is a reserved
initial-cast slug. Starting funds and architect price lists remain authored units too; runtime
balances, stored plan prices and fees are base-unit integers, with percentage fees rounded down.

`buy` and `commission` take optional `from` (`"household"` default or `"own"`), never an arbitrary
owner id. Only the actor's own account can be selected as `own`; missing accounts and insufficient
balances are refused with actionable reasons. Household-spending permissions remain unchanged.
Paid events record `:account`; bought goods belong to that account's owner. Direct delivery orders
record that `:owner`, while sent-up goods retain their existing owner. Architect fees credit the
architect's ordinary account (creating it if necessary). Views still expose shared funds under
`household.funds` and expose only your own balance at `you.purse`, when you have an account.
GM/operator views may see the small accounts map. No `pay` action or old `:household :funds` /
`:purses` compatibility remains.

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

*Later (2026-10-29):* group A's instructions and tool docs moved to templates with no wording
change (the conversation rules and request framing did not); see `docs/prompts.md` and
`docs/prompt-extraction-design.md`.

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
- `money.md`: how the currency works (agents' shopping rules); `money-pricing.md`: how to price
  in its base unit (the shop appraiser only).
- `invitation-examples.md`: the invitation judge's examples of the possible-but-unlikely (the
  King calling on the Earl, a cousin in India, "Your father died in 1898"). Neutral examples
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
11. **No backwards compatibility with old runs** (the user, 2026-10-28, now in `AGENTS.md`):
    loading old runs is rarely used, so later phases needn't keep old event shapes, state keys or
    pack-less states working, and shouldn't add complexity for them. Phase 2's England default for
    a state without a pack may stay (it's what pack-less test fixtures use) but isn't a promise.

**Later, outside this work:** dispatching messages without a post office (messengers, notes
carried by hand); several households; hiring rework (6); lunar calendar; bartering and holding
goods as money.

## 8. Phases and progress

Each phase leaves `clojure -M:test` green and the prompt diff explained, and is committed on its
own branch (in its own git worktree), merged into master when
reviewed. **When you finish a phase, mark it here with the date, the commit, and anything the
next phase must know.**

| Phase | What | Status |
|---|---|---|
| 0 | Baseline prompt capture | done 2026-10-28 (`57fc5ea`) |
| 1 | Renderer, templates moved verbatim | done 2026-10-28 (`d2425e4`, `2926604`); diff empty |
| 2 | Setting packs, neutral rewording, overrides | done 2026-10-28 (`d768180`, `d255456`, `114bac8`; reviewed and documented below) |
| 3 | Money specs | done 2026-10-28 (`a2a1cb1`) |
| 3b | Private accounts | done 2026-10-28 (`979f74a`) |
| 4 | One ruleset | done 2026-10-29 (`58f1351`, `d3c13f1`, `69e5a89`, `1c2393d`; notes below) |
| 5 | People and positions from the pack | done 2026-10-29 (`b97441e`, `51bc850`; notes below) |
| 6 | Palace skeleton | done 2026-10-29 (`850733f`, `7e6a45f`, `ef7eac2`, `1766904`; notes below) |
| 7 | Docs | done 2026-10-29 (AGENTS.md `1573575`, `3e3c6d4`; roadmap A3; this section and section 9) |
| 8 | Short real palace run (8–12 rounds, $0.20) | not started: left to the user and a reviewing agent |

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
  architect" and "a curate from Ely" examples, "Your father died in 1898.", the King line).
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

**Phase 2, done (2026-10-28).** Commits: `d768180` (pack directory, snapshot, paths, positions and
calendar), `d255456` (per-world instructions/tool docs, pack-first lookup, clock labels), `114bac8`
(neutral wording, fragments, conditional judge brief and post docs). The completion commit records
this review and removes a redundant parenthesis in the invitation judge.

Validation: a new baseline was captured at master `6012d5e` in a detached temporary worktree
(`master` itself was already checked out), using the same capture coverage, including Lodge's
judge brief. The temporary worktree was removed. Step 1 and step 2 each diffed **empty** against
that baseline. The final `before`/`after` diff was reviewed in full by deduplicating repeated edit
spans across the captured requests; `prompt-baseline/review.txt` keeps that local review. No new or
missing capture files, changed schemas, altered request data, or unexplained changes were found.
Only the changes listed below remain. The three offline mock days each reached 48 rounds, with
352 / 179 / 593 calls (Claudeworth / Lodge / medium), no exceptions and no pauses. The helper now
checks the round count, error/pause status and pause events explicitly. Mock capture runs were
removed from `test-runs/`. No real models or ports were used. The full suite passed before each
implementation commit; the final implementation suite passed **472 tests, 9224 assertions**.

Every distinct prompt change (the three captured scenarios unless qualified):

- **Agent preamble (also used by the small example scenarios):** “a an agent ... a the simulation
  of an Edwardian manor house ... other agent” → “an agent ... a simulation ... other agents”.
- **House/home rules:** sleeping “a bed in the house ... the house's bedrooms” → “a bed at
  {{home}} ... the bedrooms”; things “The house has the ordinary things a house like it keeps”
  → “At {{home}}, everyday things”. Speaking, visits and building use `{{home}}`; these substitutions
  otherwise preserve England's wording. The `call` doc's “the whole house, say” → “throughout
  {{home}}, say”. Everyday supplies use `{{everyday-things}}`, unchanged for England.
- **Finding goods:** “a shop or the public house” → “a shop or other place of trade”; “the house's
  own things” → “the things at {{home}}”. The optional no-find-at-shops rule drops “the public house”.
- **Shopping/spending:** “the household account” → “{{household-name}}'s account”; architect
  commissions and `buy` likewise use its account/funds. “up to the house” → “to {{home}}”, “errand
  boy or carrier” → “the shop's courier”, “tradesmen's bills” → “bills”. The currency text is two
  pack fragments (changed in review, below): agents' rules get `money.md`, the old sterling
  sentence unchanged; the appraiser gets `money-pricing.md`, its old pricing sentence unchanged
  apart from “Whole pennies only.” now ending its own sentence.
- **Physical rules:** “a few steps within the house” → “a few steps from where you are”.
- **Find-person doc/judge:** named examples (“the new housemaid”, “her ladyship”, “Mamma”, etc.)
  → kinds (name/nickname, family term, form of address, position); the doc drops the house example;
  “a house or a village is small” → “the area is small”; “the servants' quarters” is removed.
- **Hiring rules/recommend doc:** “a servant” → “someone who works nearby”; “your village” /
  “around the village” / “in the village” → “the area”; waiting at house/village/pub → anywhere
  in the mapped area; “Outdoor servants' lodging” → “Lodging for staff who live out”.
- **Invitation/leave docs:** example dates “1904-10-21” → clock start date: **1904-12-21** for
  Claudeworth, **1904-12-19** for medium, **1904-10-15** for Lodge (and the frozen test worlds).
  Invalid invitation/return-time format replies use the same rule. Visits use `{{home}}`,
  `{{invite-example}}` and `{{known-as-example}}` (the latter two render unchanged for England).
  GM schedule-visit's “stand with the house” → “stand with {{household-name}}”. Without a post
  office, the post option is omitted from the invite doc and visits rules; in the captured days,
  this removes it from Lodge's invite tool only.
- **Invitation judge:** home/era/known-as are parameters (unchanged in England). The matching
  examples (“Pugh the architect”, “the curate from Ely”) and deceased-father example move out of
  their instruction bullets into the pack's examples alongside the unchanged King/duke/India
  examples. The now-unneeded matching parenthesis is removed; the matching rule remains.
- **Persona/appearance writers:** “an English village circa 1900” / “England circa 1900” →
  “a simulation set in {{era}}” (England in 1904). Persona's “behavioral a” → “behavioral and”,
  and the five run-together sentence boundaries gain spaces. Appearance's final space is retained.
- **Shop appraiser/docs:** “a village general store” → “a small general shop”; “the customer's
  house” / “their house” → `{{home}}`; amount examples come from `{{measures}}` (unchanged in
  England). `send-up` says “to {{home}}” instead of “up to the customer's house”.
- **Architect docs (Claudeworth/medium):** “house room ids” → “room ids”; “whole pounds (£)”
  comes from `{{authored-money}}` (same text); price-list drops “not pence”, and draw-plans'
  “formatted sterling” → “formatted amounts”. No currency schema or formatter changes yet.
- **Judge brief:** ordinary-things preface → “At {{home}}, assume the everyday things ...”, with
  the same supplies. Architect and delivery paragraphs are selected by location services/delivering
  shops. **Lodge loses only the architect paragraph**, retaining deliveries because its shop delivers.
  A world with neither gets neither (covered by an exact template-output test).

**Supervisor review of phase 2** (2026-10-28, the commit after `fe66194`). Two fixes:
- The GM's default view dumped `[:state :setting-pack]` (the whole catalogue, calendar and
  prompts: a GM prompt of about 110k characters). `prompt/gm-state` now leaves it out, with a test.
  The mock days never call the GM, so the prompt capture can't see the GM prompt: check it by hand
  when changing what the state holds.
- One `money.md` had merged the agents' currency sentence with the appraiser's pricing rule, so
  agents were told to "price realistically in integer old pence". Split into `money.md` (agents)
  and `money-pricing.md` (appraiser), each rendering today's text. Capture diff against the
  delegate's `after`: only those two sentences.

Handoff to phases 3+: the pack/API/state layout is in 3.2/3.3 above. `:authored-money` in
`setting.edn` is deliberately temporary: replace its two architect template placeholders with the
money spec's authored name in phase 3. The appraiser still returns `unit-price-pence`; all reducers,
sterling formatting and account state are unchanged. No `money.edn`, accounts, ruleset move,
`people.edn`, standings/honorific/name-order changes or people-server data sets were started.
The courier position now comes from the pack (England `:errand-boy`) unless the shop overrides it.
The five judge/writer answer tools (`seek`, `invitee`, `polish`, `appraise`, `appearance`) were
reviewed: their own descriptions already contain no setting text, so they remain in code under
Group A rather than moving unchanged text unnecessarily. Group A prose and the GM brief are
otherwise untouched. Phase 4 must **not** repeat the conditional judge-brief work; its remaining
availability/ruleset/meeting-place/home-domain changes are still outstanding. Pack-less fixtures
now inherit England's catalogue: tests that wanted a missing entry must override it explicitly,
and inherited forms of address are real, expected state rather than a prompt leak.

3. **Money specs.** `scenarios/claudeworth/money.clj` with specs (move in phase 4); shop, building, deliveries, views and scene
   tools use the run's spec; `unit-price`. Diff: the appraiser's field and docs.
**Phase 3, done (2026-10-28, `a2a1cb1`).** Money specs live in each pack's `money.edn`; the API is
`money/spec`, `(money/display spec n)` and `(money/authored->units spec n)`, still in
`scenarios/claudeworth/money.clj` until phase 4 moves it. No sterling constants remain in code;
`:money-unit` and `:authored-money` are removed. Narration carries the spec in `names` at
`[:money]`. England's authored name is `whole pounds (£)` to preserve the architect docs.
The renderer now lets explicit string keys override keyword setting parameters too (adding
`:money` exposed that latent collision). Full suite: **474 tests, 9239 assertions**, green.
The three 48-round offline mock days completed with 352 / 179 / 593 calls, no pauses/exceptions.
Reviewed capture diff: only appraiser `unit-price-pence` → `unit-price` (instructions/schema,
Claudeworth and Lodge calls plus rendered request), and price-list narration `prices in pounds`
→ `prices in whole pounds (£)` (Claudeworth's event memories). All other rendered docs and
captured prompts unchanged. GM system/user character counts, before → phase 3:
Claudeworth 1381/80163 → 1381/80138; Lodge 1480/10102 → 1480/10077;
medium 1795/186087 → 1795/186062. The pack remains excluded; removing the money-unit marker
accounts for the 25-character decrease in each user message. No real models or ports used.

3b. **Private accounts** (3.4).

**Phase 3b, done (2026-10-28, `979f74a`).** Runtime balances are `[:state :accounts {owner amount}]`;
no `:household :funds` or `:purses` state remains. The household's view is still
`household.funds`; any account owner now sees `you.purse`, privately. Agents may author a
non-negative integer `:purse` in the pack's authored unit; an architect without one starts at
zero. `buy` and `commission` accept optional `from` (`household` default, or `own`), never
another person's id. Their events carry `:account`; bought items and direct delivery orders
carry the paying owner, and sent-up goods retain theirs. Architect fees credit the ordinary
account (creating it if necessary). `household` is reserved as an account owner, so the initial
cast may not use it as a slug. No `pay`, old-state/event compatibility, or change to who can
spend household funds. All files stay in their current namespaces until phase 4.

Full suite: **478 tests, 9289 assertions**, green. New tests cover two currencies through
initialization/validation/pricing/narration, authored purses (including zero and an architect's
nonzero purse), private purchase success/insufficient/missing account, private commission,
item ownership for taken/left/direct-delivery/sent-up goods, and recorded-run replay equality.
One first full-suite attempt found that Lodge's new shopping wording advertised `commission`;
fixed by choosing the spending-tools fragment with the existing architect-service condition.
The subsequent full suite passed. The suite prints transcript-writing warnings (including steward cleanup warnings);
no test failures/errors remain.

The three 48-round mock days again completed with **352 / 179 / 593 calls**, no exceptions or
pauses. The phase-3 → phase-3b capture diff was reviewed by grouping every changed span; only
five distinct spans (the two rules variants, the two tool docs, and the schema addition):
- **Shopping rules, all three scenarios and rendered rules:** household account → household
  account **by default**; add `from: "own"`, `you.purse` and owner-only spending/ownership;
  `buy {quote-ids, hand-over}` → `buy {quote-ids, hand-over, from?}`; paid from household account
  there and then → paid from **chosen** account. Claudeworth and medium name `buy` or
  `commission`; Lodge names only `buy`.
- **`buy` docs, all three scenarios and rendered tools:** household funds → household funds
  **by default**; add `from`/`own`, owner-only spending and goods' paying-account ownership.
- **`commission` docs, Claudeworth/medium and rendered tools:** household funds → household
  funds **by default**; add `from`/`own` and owner-only spending.
- **Both schemas (commission only with an architect):** add optional string `from`, enum
  `household` / `own`. Existing required fields are unchanged.
- No captured view lines change: the bundled worlds author no new purses, and architects
  already saw theirs. New tests check `you.purse` for non-architects and absence for others.

GM system/user character counts, phase 3 → phase 3b: Claudeworth **1381/80138 → 1381/80130**;
Lodge **1480/10077 → 1480/10068**; medium **1795/186062 → 1795/186054**. Parsed GM state diff
against the unchanged baseline is only removing `:money-unit`, `:household` and `:purses`,
and adding the small `:accounts` map. The pack remains excluded. No real models or ports used;
capture mock runs were removed from `test-runs/`.

**Handoff to phase 4+:** move the existing money namespace with the other Claudeworth mechanics;
its API is above, plus `money/account` and `money/spend-reason` for the shared payer selection.
Update both scene tools' imports too. `:accounts` is the only runtime balance map, deliberately
not the old household/purse shapes; the views keep their stable public keys. `money.edn` is now
required in every pack (including test packs), but no people data or ruleset/file moves were
started. Both shopping rules and judge brief already condition architect prose by services;
don't regress Lodge by advertising commission in a shared new fragment. Currency tests use a
tael/cash spec; an actual second setting pack still belongs to the palace phase. Keep the pack
out of the GM view. This work used only this worktree and did not touch any real runs or ports.

4. **One ruleset.** `manor.ruleset`; move building, shop, deliveries; meeting place and home
   domain from the world file; architect actions by `:available?`; Claudeworth, medium and Lodge
   become thin. Conditional judge-brief paragraphs were completed in phase 2. Diff: otherwise
   unchanged.

**Phase 4, done (2026-10-29).** Commits:
- `58f1351`: building, shop, deliveries and money move to `src/manor/mechanics/`
  (`manor.mechanics.building` etc.); `tools/scene_claudeworth.clj` becomes
  `tools/scene_ruleset.clj` (`manor.tools.scene-ruleset`). Event types renamed to their mechanic:
  `:claudeworth/{consulted,prices-quoted,draft-refused,plans-drawn,plans-shown,commissioned,works-completed}`
  → `:building/…`, `:claudeworth/{bought,goods-quoted,goods-refused}` → `:shop/…`,
  `:claudeworth/called` → `:agent/called`. Capture diff: **empty**.
- `d3c13f1`: `manor.ruleset` and `src/manor/ruleset/`; holidays.clj folded into
  `ruleset.init/add-holidays`; Claudeworth's mock day moved to `resources/worlds/claudeworth-day.edn`.
  Capture diff: **empty**.
- `69e5a89`: Lodge gets a real visit config (below). Capture diff: Lodge only.
- `1c2393d`: meeting place and home area from the world file; the architect's actions by
  `:available?`. Capture diff: only the tool reordering (below).

**The API.** `(ruleset/scenario {:name :doc :defaults :mock-day :mock-policy :mock-conversation
:calendar?})`. `:defaults` must give the `:world-file` (the ruleset's `init` no longer falls back
to Claudeworth's). `:mock-day` is a map or a classpath EDN file, run by
`manor.ruleset.mock/scripted-policy`; `:mock-policy` replaces it (Lodge wraps the scripted policy
to fill in its candidate). Extra keys (a test scenario's `:mock-judge`, `:mock-steward`) are
`assoc`ed onto the result. The scenario is: `:scene` hooks, `:init` (with the pack's holidays
unless `:calendar? false`), `:roles` (GM only) and `:agent-brief`, `:on-spawn`
`positions/starting-events`, the actions (ruleset's, find-person, following, building, shop,
hiring), adjudicators, bridges, offline stubs, MCP servers, `:systems [building/construction]`,
`ruleset/items`, `ruleset/inner`, `:exclude-actions #{:move :work-on}`, speaking actions,
`:hibernate`, `:reference`, `:observe`, then `hiring/install`, `deliveries/install`,
`visits/install` in that order. Its parts, under `src/manor/ruleset/`: `places` (areas, bedrooms,
the time of day), `actions` (pass, walk, call, travel, sleep, rouse), `views` (`reference`,
`observe`), `hibernation`, `rules` (the rules prompt, judge brief, agent and GM briefs, roles,
`find-allowed?`), `init` (`problems`, `init`, `add-holidays`), `mock`. Claudeworth, the medium
household and Lodge are 26, 26 and 38 lines; the test scenarios `household`, `household-containers`
and `burglary` use it too.

**World data.** `:meta :meeting-place` (required, a location id; checked by `problems`) goes to
`[:state :meeting-place]` in the ruleset's `init`; `hiring/install` takes no meeting place now.
`:meta :home-domain` (optional, a keyword that must be some location's `:domain`) goes to
`[:state :home-domain]` only when set, so the GM state of the bundled worlds is unchanged.
`space/home-domain` answers "the home area": that, else the meeting place's domain. It decides the
household's bedrooms in views, the rooms plans build onto, and the domain of rooms built:
`:building/commissioned` and `:building/works-completed` now carry `:domain` (recorded at
commissioning), and the scene hook reads it. All bundled worlds and the ruleset's fixture worlds
(`household`, `household-containers`, `burglary`, `visits`) name their meeting place.

**The mock's plans.** The old scripted policy hard-coded the earl: commission/show-plans used
"lord-claudeworth"'s plan, and at 18:00 he walked into it once built. Now `commission` and
`show-plans` use the first plan the scripted agent holds (dropped while they hold none), and a
scripted walk to `"@plan"` goes into the room built from it (dropped until built): the 18:00 walk
is data in `claudeworth-day.edn` and the two household fixture days. No Claudeworth id remains in
the ruleset or mechanics outside docstrings.

**Lodge (decided with the supervisor).** Lodge was `(hiring/install (assoc (dissoc cw/scenario
:calendar) :init cw/init …))`, which threw away Claudeworth's init chain: it offered `invite`,
`take-leave`, `schedule-visit` and `cancel-visit` but had no `[:state :visit-config]`, so its
rules had no invitations section. Step 2 reproduced that exactly (empty diff); `69e5a89` gives it
a real config. Lodge stays without a calendar (dropping the holidays is a content choice), so the
visits rules' calendar sentence is now a fragment (`rules/visits-calendar.md`) rendered only with
a calendar. A new test (`lodge-test/invitations-without-a-calendar`) invites Ward in person and
checks he arrives, with replay. Lodge's prompt diff, in all 159 of its rules-carrying requests
and `rendered/rules-lodge.md`: hiring's "Nobody new arrives except through send-for" → "No
candidate arrives except through send-for" (the beside-visits wording), and a new "### Invitations
and guests" section (Claudeworth's, with "the house", no post, no calendar sentence). Claudeworth
and the medium household unchanged. Lodge's GM state gains only the visit config (+148
characters).

**Tool order (`1c2393d`).** `actions/available` puts tools with an `:available?` check last, so
in Claudeworth and the medium household `consult-references`, `price-list`, (`draw-plans` for
Pugh), `show-plans` and `commission` move from after `follow` to the end of the tool list; the
turn-ending reminder lists `commission` after `spend-time` ("`pass`, `act-on`, `travel`, `sleep`,
`spend-time` or `commission`"). Checked by script: every request's tools are the same set, the
others' order unchanged, and no other text differs. A separate world-level key would have kept
the order, but the plan asked for the existing hook. `rendered/tools-lodge.md` (which renders
every run action, not those offered) now lists the five architect tools; Lodge's requests never
include them (its rounds are unchanged).

**Checks.** Full suite after each commit; final **481 tests, 9320 assertions**, green (new:
`ruleset-test`, the Lodge invitation test, a domain check in the building slice). The three
48-round mock days: 352 / 179 / 593 calls, no exceptions or pauses (the capture checks). Replay
equality is tested for all three (`scenarios-test` smoke, `lodge-test`, `claudeworth-test`). GM
prompt at the start (system/user characters): Claudeworth 1381/89371 and medium 1795/197612,
identical text before and after; Lodge 1480/11699 → 1480/11847 (the visit config). No real
models, no ports.

**For phases 5–6.** A new scenario is a namespace with `(def scenario (ruleset/scenario {...}))`,
a world file with `:meta :meeting-place` and `:meta :setting-pack`, and a mock day EDN (see
`claudeworth-day.edn`; `request-goods`/`accept-offer`/`ask-send-up` are mock-only markers the shop
translates, and `"@plan"` is the walk into your built plan). Add it to `bundled`. The palace's
home is the meeting place's domain unless the gate stands in an outer domain: then set
`:meta :home-domain :inner-court`. Surprises: the old Lodge composition (above); the medium and
Lodge scenarios' `:init`, called without their `:defaults` (as `scene-layout-test` did), silently
loaded Claudeworth's world (fixed: the test merges `:defaults`, and init now requires a world
file); the shop's scripted customer still says "sent up to the house" (mock speech, not a prompt
template). `test/manor/scene_boundary_check.sh` (a historical check against `0f7eae9`) still
names the old files and wasn't updated. `AGENTS.md` (uncommitted user edits in the main checkout)
was left alone: its layout section needs the same change as `CLAUDE.md`'s.
5. **People and positions.** Standings, honorifics and name order from the pack; people-server
   data sets; reputation wording. Diff: none for England.

**Phase 5, done (2026-10-29).** Commits:
- `b97441e`: `settings/england-1904/people.edn` (`:dataset "england-1900"`, England's eleven
  honorifics, `:given-first`, the eight standings with `:household? true` on the four household
  ones), loaded as the pack's `:people` (not a rendering parameter). `positions/standings` and
  `positions/household-standing?` take a pack and replace the fixed `standings` and
  `household-standings`; `entry-problems` takes the standings, and the ruleset's `problems`
  passes the world file's pack's; `starting-events` reads the run's pack. `positions/fill` is
  public, takes the pack's people data, strips its honorifics and reads `{surname}` and
  `{first-name}` by `:name-order` (`:family-first`: the first word, then the rest, or the one
  word). `people/dataset` gives a pack's data set; `generate-args` takes `:dataset` (hiring passes
  the run's, `roll-cast!` the world file's). The people server: `names.edn` moved to
  `settings/england-1900/` with its own `:name-order` and `:honorifics`; `settings` is now every
  directory under `settings/` with a `names.edn` (default `"england-1900"` for a request without
  one); names are built in the data set's order. A second data set, `settings/qing-1780/`
  (pinyin given names, Han family names and Manchu clan names, origins and dwellings of the
  capital), came with this commit for the data-set test. Reputation's `"fellow-servant"` is now
  `"worked-alongside"` ("%k worked alongside %p for %y years."), renamed since only a test used
  the id. Tests: name order for both orders and a run's own pack, standings and household
  standings from a test pack (`test/settings/test/people.edn`), position validation against the
  pack's standings (an entry and a world file), the people server's data-set selection, name
  order and origins/homes. Capture diff against the phase-4 baseline: **empty**.
- `51bc850`: neutral text in the offline people stub (origin "a place not far off", homes
  "lodgings nearby" / "lodgings outside the mapped area", from "the neighbouring village", "a
  cottage in the village", "a cottage in the next parish") and the mock polish's circumstance
  ("Has a cousin living some way off."). Capture diff: exactly these strings, in Claudeworth's,
  Lodge's and the medium household's mock prompts (checked by substituting them back: identical).
  The stub's name, "Pat Ellis N", is left.

GM prompt: the pack stays out of the GM view (`:people` is inside it), so England's GM state is
unchanged. Full suite after each commit: **485 tests, 9416 assertions**, green.

6. **Palace skeleton** (section 5), and the best-effort read of its prompts.

**Phase 6, done (2026-10-29).** Commits:
- `850733f`: the pack `resources/settings/qing-palace/`: `setting.edn` (era "the Qing imperial
  palace in Beijing in the 1780s", home "the inner court", "the palace household", palace
  everyday things, measures in jin and liang, courier `:errand-eunuch`), `money.edn` (taels and
  cash, base unit cash, 1 tael = 1,000 cash, authored in whole taels), `people.edn` (data set
  `"qing-1780"`, `:family-first`, standings `:imperial :consort :eunuch :palace-maid` in the
  household and `:official :merchant :visitor`), `positions.edn` (consort (fei), noble lady
  (guiren), chief eunuch, eunuch, errand eunuch, palace maid, stall keeper (`:shop-clerk`),
  `:guest`, `:none`), `calendar.edn` (one entry: the winter solstice, 21 December 1784, a date not checked against an
  almanac), and the
  three fragments written for the palace.
- `7e6a45f`: `resources/worlds/palace.edn` (below), the mock day `palace-day.edn`, the scenario
  `src/manor/scenarios/palace.clj` (`ruleset/scenario` with `:max-cost 0.20` and a one-line
  mock conversation), `test/manor/palace_test.clj`, `:palace` in `bundled`. The shop's scripted
  customer passes `:from` through to `buy`, and asks for goods "sent up to {home}" (unchanged
  for England).
- `ef7eac2`: the capture helper runs the palace's mock day and renders its rules, judge brief,
  find-person, hiring, visits, invitation, polish, appearance, appraiser and GM requests and
  every tool under `rendered/palace/`.
- `1766904`: the fixes of the England review (below).

**The world.** The Palace of Gathered Fragrance (fictional), 20 October 1784. Domain
`:inner-court`: the long covered street, the front hall, the consort's bedchamber, east and west
side rooms (tea room; store room), the eunuchs' quarters, the noble lady's Pavilion of Pure Dew
with her bedchamber and a maid's room, and a garden. Domain `:outside`: the palace gate (the
meeting place, walkable from the covered street, so the house area is the inner court and the
gate) and, a one-round journey away, a silk and sundries stall (08:00–17:00, store and delivery
profiles; it delivers by the pack's errand eunuch) with the stall keeper's room. `:meta
:home-domain :inner-court`. Starting funds 200 taels; purses: the consort 30 taels, the noble lady
8, the chief eunuch 5. Five agents with short hand-written personas, no traits: Sumuru Wanxia
(consort), Tan Qiaoyun (noble lady), Zhao Deshou (chief eunuch), Liu Cuiping (the noble lady's
maid), Han Baoshan (stall keeper). The Emperor is in the setting paragraph only, and the GM's
brief says never to spawn or play him. Items: a lacquer chest holding a jade hairpin, tea things,
a tea cabinet and supply shelves (stores), an embroidery frame, the stall's counter. No
architect's office, no post office.

**The mock day** (48 rounds, 193 calls, no forfeits, no rejected or error replies, replay
equal): the consort calls Zhao, sends Han an invitation by messenger (Zhao carrying it) for
16:00, takes the hairpin from the chest and gives it to the noble lady at tea, and invites Han in
person at 16:30 to come again on the 22nd; the noble lady converses with the consort; Zhao travels
to the stall, buys candles and a basket of charcoal (delivered) from the household and a snuff
bottle with `from "own"`, puts the candles away; the maid fetches the charcoal from the gate and
puts it away; everyone spends time and sleeps; Han comes in at the message. The test checks the
accounts (household 200 taels − 36 cash, Zhao's purse "4 taels 982 cash"), the forms of address
("Noble Lady Tan", "Chief Eunuch Zhao", "Cuiping"), clock labels without weekdays, that the
architect's tools are never offered, and replay. Inviting the noble lady in person was refused
("lives here": a household member can't be a guest), so the in-person invitation went to Han.

**The England review** (best effort: every distinct prompt of the palace's mock day and the
rendered requests, read by eye after grepping for English words). Found → done:
- **Weekday names**: "[Wed 12:30]" stamps (`clock/short-label`'s fixed `"EEE HH:mm"`), and
  "on Wednesday 20 October at 16:00" in invitations, returns and visit replies (fixed
  `"EEEE d MMMM"` in visits and hiring). Now the pack's `:clock :day-label` goes into the run's
  clock at `init` (only when a pack gives one, so England's clock and GM state are unchanged)
  and `clock/when-label` / `clock/date-pattern` read `:clock :date-label`. The palace: "20 Oct
  21:30", "20 October at 16:00".
- **English measures in shared templates**: quote-goods' `("a stone", "2 lb", "half a dozen",
  "3")` and the appraiser's "such as 1 lb, 1 loaf, 1 tin or 1 dozen" → pack parameters
  `{{amount-examples}}` and `{{unit-examples}}` (England's text unchanged).
- **Scene judge's** `"about 17 lb left"` → `"about a third left"` (shared; England changes).
- **GM's open-route doc** "(e.g. rooms of one house)" → "rooms of one building" (shared).
- **Mock appraisal's** desc "An ordinary X from the village shop." → "An ordinary X." (mock only).
- **Hiring candidate's situation**: "wait at the house, in the village or at the pub" → "wait
  anywhere in the mapped area" (as the hiring rules already say).
- **Money refusal** "the household has …" → the pack's household name ("the palace household
  has …"; England unchanged).
- **`find` refused at a shop**: "this isn't your house to search" → "these aren't your things to
  search" (shared; only with `:find-everywhere? false`).
- **The invite example** "my mother, Lady Wanggiya" didn't fit the shared "not just \"my father\""
  → the palace's example is "my father, Wanggiya Fulu".
- **The palace's setting paragraph** said "No architect works here and there is no post office";
  removed (it put an architect in a Qing palace).
England's capture diff for `1766904`: only the scene judge's amount example, the open-route doc
(rendered tools), the mock appraisal's desc and Lodge's candidate situation.

Left, with reasons: the scene judge's other illustrations ("the pantry", "polish the silver",
"curtain cord", "(floor, hearth, air)") and the appraiser's "1 knife, 1 tongue", consumables
"(… polish, matches)" (examples of kinds, group A); the position enum value `sleeps: "in-house"`
in views (an identifier); `"post"` in invite's enum (refused with a reason without a post office,
as section 3.1 allows); hiring's sequence and words like "staff", "candidate", "a character/
reference" (decision 6); the offline stub's "Pat Ellis N" courier and the mock appearance's
"spectacles" (mock only); the invitation judge gives no name order for a new person's name (the
era is in its prompt); `building.clj`'s "not in the house" errors (only with an architect's
office); the errand eunuch is employed by the stall keeper (deliveries make the shop's clerk the
courier's employer, as the plan's courier position implies). Hiring's sequence (negotiated
terms, "New staff") doesn't fit a palace where people are assigned (decision 6, later).

**GM prompt** (rendered by hand into `rendered/palace/gm-request.md`): 22.9k characters; no pack,
no data set or honorifics; the brief is the setting paragraph and the palace's GM `:about`; the
state shows `"house":"the Palace of Gathered Fragrance"`, cash amounts in the accounts map and
neutral building keys (`plans`, `works`, `architect-prices: null`). England's GM state is
unchanged by phase 6 (no `:day-label` in its pack).

Full suite after `1766904`: **488 tests, 9585 assertions**, green.

**For phase 7.** `scenario-authoring.md` now has "People in a setting pack" and "A new setting:
the palace as the example", and the setting-pack section lists the new parameters. Still to do:
roadmap A3, and `AGENTS.md`'s table and layout (the palace scenario, `resources/settings/
qing-palace/`, the people server's `settings/<dataset>/`, `people.edn`; not edited here because
the main checkout has the user's uncommitted edits to it).

**For phase 8 (the real run).** `clojure -M:run palace --test --port 809x --max-rounds 10` (the
scenario's `:max-cost` is 0.20). Watch for: names and forms of address the models write
(family name first?), prices in cash and taels from the appraiser, the stall's goods and
delivery, whether anyone tries to hire (the hiring rules are England's sequence), the Emperor
staying off stage, any English idiom (Mr/Mrs, pounds, "the house") in the models' own lines, and
whether the consort's messenger invitations and the noble lady's deference come across.
7. **Docs.** `scenario-authoring.md` (setting packs, `:meta :meeting-place`, money specs,
   templates and overrides), roadmap A3 (partly done by this), `AGENTS.md`'s table and layout.
8. **Real run**, then read the transcript.

**Phase 7, done (2026-10-29).** `AGENTS.md`'s documents table and layout (`1573575`, `3e3c6d4`),
roadmap A3 (the agent preamble is a template a pack can override). `scenario-authoring.md` was
updated by each phase. The palace's personas were then rolled for real (`7be434c`, see 9).

## 9. Handoff: reviewing the refactor

The user means to have a fresh agent review the whole refactor for remaining problems. Where to
start, and what's known.

**How to look.**
- `clojure -M:run palace --mock --offline --test --port 809x` shows the palace's scripted day in
  the panel; `clojure -M:run claudeworth-medium --mock --offline --test --port 809x` the medium
  household. (Port 8080 is the user's.)
- `dev/prompt_capture.clj` records every request of the four ruleset scenarios' mock days, plus
  `rendered/` judge, writer and GM requests (`capture!`, then `diff -r` two labels). The mock
  days never call the GM; the capture renders the palace's GM request by hand.
- Phase 8, a real palace run, needs the user's go-ahead: the command and what to watch for are in
  phase 6's notes above.

**The palace's personas** were rolled with `roll-cast!` (seed `ef5d0ac7-be75-4efa-a465-324b7f784e5a`,
$0.0023, record in `old-test-runs/palace-roll-cast/roll-cast.edn`), replacing the hand-written
placeholders; situations, names, sex and positions are as authored. Worth a look:
- several circumstances name another character ("Master Han's stall"), which the polish is told
  not to do for existing characters' present lives;
- the consort's rolled background (a silk-shop family by the Qianmen gate) sits awkwardly with her
  rank and her situation; the noble lady rolled 35, the top of her position's age range.
Re-roll one person with `(roll-cast! "worlds/palace.edn" {:only ["Sumuru Wanxia"]})`, or edit by hand.

**Known and left on purpose** (details in phases 2–6 above): the scene judge's English
illustrations ("the pantry", "polish the silver"); `sleeps: "in-house"` in views; `post` stays in
`invite`'s enum (refused with a reason without a post office); hiring's negotiated sequence
(decision 6); Lodge has no calendar (`:calendar? false`, a content choice); one household per
world (decision 2); Gregorian dates and `HH:mm` (decision 4).

**Loose ends noticed, not fixed.**
- `test/manor/scene_boundary_check.sh` still names pre-phase-4 files (an old check against
  commit `0f7eae9`).
- The test suite prints transcript-writing warnings ("transcript of steward-… not written") from
  passing tests; seen before this work too.
- The full suite takes about 5 minutes (`scene-integration-test`), not the
  15 seconds `AGENTS.md` says.
- The GM prompt was already large before this work (about 186k characters for the medium
  household at the start of a run: agents, map and items); unrelated to settings.
- The setting pack is snapshotted into `[:state :setting-pack]` and kept out of the GM view
  (`prompt/gm-state`); anything else added to `[:state]` reaches the GM's JSON unless left out.

**Later, outside this work** (section 7): dispatching messages without a post office; several
households; hiring reworked for assigned posts; lunar dates and double-hours; bartering.

