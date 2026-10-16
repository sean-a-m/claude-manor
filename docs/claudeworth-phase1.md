# Claudeworth phase 1: build brief

Self-contained instructions for implementing phase 1 of `docs/claudeworth-design.md`: living at
Claudeworth, before any building. Read `AGENTS.md` first (invariants, run directories, cost
rules), then sections 1–7 of the design for intent. This brief is the authority on scope and
details for phase 1; the design describes the whole slice.

## Scope

**In:**
- the world file
- the scenario: roles, briefs, `walk`, `travel`, `sleep`, `spend-time`, `rouse`, `:observe`
- a scripted mock day
- tests
- one capped real run
- doc updates

**Out (phase 2):**
- money
- the shop's `buy`
- `consult-references`, `draw-plans`, `show-plans`, `commission`
- construction
- the architect MCP server

Opening hours are data and shown to agents in phase 1, but nothing is gated by them yet.

The architect, Mr Pugh, is in the scenario from the start. In phase 1 he can only live in the
village, talk, and pass the time. He gets his building role in phase 2.

## Decisions already made (don't revisit)

- Scenario `:claudeworth`, Claudeworth Manor, beside Claudeworth village and Hollin Abbey.
- Agents:
  - Lord Claudeworth (earl)
  - Lady Claudeworth (countess)
  - Hale (private secretary)
  - Pugh (architect, optional: run option `:architect? false` leaves him out; default true)
- Start: **08:00, Saturday 15 October 1904**. One round = 30 minutes.
- Model: the default `deepseek/deepseek-v4.1-flash`.
- World file in EDN, ids kebab-case strings, as already supported by `manor.worldfile`.
- Anyone in the family, plus the secretary, may spend money. This only matters in phase 2.

## What phase 0 gives you (use it, don't rebuild it)

- **`manor.worldfile/load-world`.** Returns `{:space :clock :agents :world-file}`; `:init` adds
  `:state`.
- **Pattern to copy:** `test/manor/scenarios/hamlet.clj` and `test/fixtures/hamlet.edn`. They
  show a world file, a clock, `sleep` with `clock/round-at`, and `rouse` with `b/free`.
- **`manor.builtins`:** `occupy`, `free`.
- **`manor.world`:** `busy`.
- **`manor.space`:** `area` (everything next door), `distance`, `path`, `here`, `present`,
  `together?`, `location`, `location-of`.
- **`manor.clock`:** `label`, `short-label`, `round-at`, `rounds-for`, `hh-mm?`, `time-of`.
- **`narrate/at`.**
- **`:exclude-actions #{:move}`.**
- **Free with a clock:** the engine already puts `:time` in every view, shows exits in
  `:travel-minutes`, shows `:doing` for busy people, labels events with times, and hides round
  markers from agents.
- **Engine events with reducers and narration:** `:agent/moved` (immediate move; narration "X goes
  from A to B."), and `:agent/departed` with `:arrives`. The engine emits `:agent/arrived` itself.
  Use these for `walk` and `travel`, with the same visibility as the built-in `move` in
  `space.clj`.
- **Commands:**
  - `clojure -M:dev:run claudeworth --mock --test` (panel on :8080)
  - `clojure -M:test`
  - REPL `clj -M:dev`

## Files to create or change

```
resources/worlds/claudeworth.edn          new: the starting world
src/manor/scenarios/claudeworth.clj       new: the scenario
test/manor/claudeworth_test.clj           new
test/manor/scenarios_test.clj             add :claudeworth to `bundled`
README.md                                 scenario list and a run example
AGENTS.md                                 layout: the scenario and resources/worlds/
docs/claudeworth-design.md                status line; phase 1 marked done, with notes on deviations
```

`resources/` is already on the classpath (`deps.edn` `:paths`), so the scenario loads
`"worlds/claudeworth.edn"` as a resource. Accept a `:world-file` run option to override it, as
hamlet does.

Engine changes are **not expected**. If one seems necessary, keep it small, general and tested,
and report it. Village prompts must stay byte-identical. To check, load `runs/smoke-llm` and
compare `prompt/agent-messages` and `prompt/gm-messages` output before and after (with `git
stash`); don't write anything under `runs/`.

## The world file

`:meta`: `{:name "Claudeworth Manor" :start "1904-10-15T08:00" :minutes-per-round 30
:starting-funds 3000 :build-days 1}`. The last two are unused until phase 2 but belong here.

Locations. Every location has `:domain`, a `:desc` of one or two period-flavoured sentences, and
`:floor` for house rooms. `:kind` is used by rules (`:bedroom`) and is descriptive otherwise.

| id | name | domain | floor | kind | connects-to / notes |
|---|---|---|---|---|---|
| carriage-sweep | Carriage sweep | house | outside | approach | entrance-hall; the motor car stands here |
| entrance-hall | Entrance hall | house | ground | hall | drawing-room, study, dining-room, kitchen, staircase |
| drawing-room | Drawing room | house | ground | sitting-room | |
| study | Study | house | ground | study | the earl's business room |
| dining-room | Dining room | house | ground | dining-room | |
| kitchen | Kitchen | house | ground | kitchen | "A cold kitchen. There is no cook." |
| staircase | Staircase | house | ground | stair | gallery |
| gallery | First-floor gallery | house | first | landing | earls-bedroom, countess-bedroom, secretarys-room |
| earls-bedroom | Earl's bedroom | house | first | bedroom | |
| countess-bedroom | Countess's bedroom | house | first | bedroom | |
| secretarys-room | Secretary's room | house | first | bedroom | small |
| village-green | Village green | village | | green | general-store, architects-office |
| general-store | General store | village | | shop | `:hours ["08:00" "18:00"]` |
| architects-office | Architect's office | village | | office | architects-lodgings; `:hours ["09:00" "18:00"]`; "Pugh & Sons, Architects" |
| architects-lodgings | Architect's lodgings | village | | bedroom | rooms above the office |
| abbey-gatehouse | Abbey gatehouse | abbey | | gatehouse | abbey-church |
| abbey-church | Abbey church | abbey | | church | Hollin Abbey's church; the monks keep to themselves |

Routes (journeys, 1 round = 30 minutes): `["carriage-sweep" "village-green" 1]`,
`["carriage-sweep" "abbey-gatehouse" 1]`, `["abbey-gatehouse" "village-green" 1]`.

`:agents`, each with `:name :role :location :bedroom :persona` (and Pugh `:optional? true`):

- Lord Claudeworth, `:earl`, starts in `earls-bedroom`, bedroom `earls-bedroom`. About fifty; old
  title, new money. Finds the house mortifyingly small for his station and means to make it a
  proper seat.
- Lady Claudeworth, `:countess`, `countess-bedroom`. Sharp and practical, with firm views on
  taste; regards the house as hers to run.
- Hale, `:secretary`, `secretarys-room`. The earl's private secretary: young, meticulous,
  discreet, and a little anxious to please.
- Pugh, `:architect`, `architects-office`, bedroom `architects-lodgings`. Village architect of
  the firm Pugh & Sons: proud of his craft, keen for a commission from the manor, prone to
  holding forth on proportion.

Everyone except Pugh starts in their bedroom at 08:00, as if just risen. Write the personas as
prose in the file; the above is the gist.

**Scenario-level checks** at `:init` (throw with a readable message): every agent's `:bedroom`
exists and has `:kind :bedroom`; every `:hours` is two valid `"HH:MM"` strings.

**Bedrooms:** `spawn-events` ignores unknown keys, so keep `:bedrooms {agent-id loc-id}` in
`:state`. Agent ids come from `manor.builtins/slug` of the name: `"lord-claudeworth"`,
`"lady-claudeworth"`, `"hale"`, `"pugh"`.

## Actions

Exclude the built-in `move`. Keep `pass`, `note`, `say`, `whisper`, `request-gm`. All new
actions are for every non-GM role.

| Action | Kind | Args | Valid when (reason text matters: models self-correct from it) | Effect |
|---|---|---|---|---|
| `walk` | free | `to` | `to` exists; isn't here; is in `(space/area world here)`. Otherwise e.g. "Village green is not within walking distance of here; use travel" | `:agent/moved` from here to `to`, visible to `(into (space/here ..) (space/present .. to))` |
| `travel` | final | `to` | `to` exists; is **not** in the current area (else "X is close by; use walk, which doesn't use up your turn"); reachable (`space/distance`) | distance ≤ 1: `:agent/moved` straight to `to` (the half hour is spent travelling); > 1: `:agent/departed` with `:arrives (+ round d)` (unused by this map, but keep it correct) |
| `sleep` | final | `until` ("HH:MM") | in a location with `:kind :bedroom` (else "go to a bedroom first; yours is Earl's bedroom (earls-bedroom)"); `hh-mm?`; at most 12 hours away | `(b/occupy world actor "asleep" (clock/round-at ...))` |
| `spend-time` | final | `activity` (string 3–80, reads after "is", e.g. "reading the newspapers"), `hours` (number 0.5–4) | none beyond the schema | `(b/occupy world actor activity (+ round (clock/rounds-for clock (* 60 hours))))` |
| `rouse` | free | `who` | `who` is here and busy | `(b/free world who actor)` |

Tool docs are short and concrete. Say in `walk`'s doc that it doesn't end the turn, and in
`travel`'s that it takes the turn (half an hour). The engine appends "Ends your turn." or "Free:
does not end your turn."

## `:observe`

Engine sections (`:time`, `:you`, `:location`) are added automatically. The scenario adds:

```clojure
{:you {:bedroom "earls-bedroom"}            ; merged into the engine's :you
 :here {:open "09:00-18:00" :open-now? true} ; only where the location has :hours
 :places {:house [{:id .. :name ..} ...]     ; every location by domain, compact
          :village [...] :abbey [...]}
 :walkable ["study" "drawing-room" ...]}     ; ids in (space/area world here), minus here
```

Keep it compact; it's sent on every model call. Nothing private to others goes in: in phase 1
there's no private state anyway, but keep the habit.

## Briefs

One shared rules paragraph, plus a paragraph per role. The rules paragraph must say:

- It's October 1904. Each turn is half an hour, and you take one turn-ending action per turn.
- `walk` moves you anywhere within the house, or within the village, and doesn't end your turn.
  `travel` to the village or the abbey takes your turn (half an hour).
- You're only heard by people in the same room (`say`); `whisper` is private.
- Sleep at night in a bedroom (`sleep` until a time, e.g. "07:00"). You get no turns while
  asleep, and others can `rouse` you.
- `spend-time` for longer pastimes (half an hour to 4 hours). `note` keeps private notes.
- Opening hours of village premises are shown in your view.

Role paragraphs:

- **Earl:** master of Claudeworth; the house is too small for his station. He intends to enlarge
  it: Mr Pugh, the architect in the village, is the man to see. Building itself isn't possible
  yet: say plans can be discussed.
- **Countess:** runs the household; has firm views on what the house needs and how it should look;
  expects to be consulted.
- **Secretary:** serves the earl; handles correspondence, errands and arrangements, often in the
  village; reports back.
- **Architect:** Pugh & Sons in Claudeworth village; office hours 09:00–18:00; lives above the
  office. Wants the manor's custom. For now he can advise and discuss; formal plans come later.
- **GM:** a quiet period household; intervene only when asked.

Use period-appropriate forms of address in the briefs (my lord, my lady, Mr Hale, Mr Pugh).

## The scripted mock day (`:mock-policy`)

Base the script on clock time (hours and minutes of the current round), not round numbers, so
it's easy to read. It must only choose valid actions: the smoke test fails on forfeits. After a
free action (`walk`, `rouse`), return a turn-ending one; `used` in the context lists the tools
already used this turn. The GM always returns `{:tool :done}`.

Required beats over one day (08:00 Saturday to 07:00 Sunday):

1. **Breakfast** (about 08:00–09:30): the earl, countess and Hale walk to the dining room. Each
   says something there (the earl announces his wish to enlarge the house), then they spend time
   breakfasting.
2. **The earl's visit** (between 09:30 and 13:00, within office hours): the earl travels to the
   architect's office and says something to Pugh, who replies with `say`. The earl then travels
   home and walks to the study.
3. **The countess** travels to the abbey church at some point in the afternoon and back home by
   18:00.
4. **Hale** spends time working in the study (letters), in blocks of 1–2 hours.
5. **Pugh** spends time at his drawing board in the office during office hours, and walks to his
   lodgings in the evening.
6. **Bedtime:** everyone is asleep in their own bedroom by 23:00. Hale sleeps until 06:30;
   the others until 07:30.
7. **Rouse:** at 06:30 Sunday (round 46) Hale wakes, walks to the earl's bedroom, rouses him
   (both free), and says good morning (his turn-ending action). The earl is freed early and takes
   his turn in round 47 (07:00).

Pass or `spend-time` in between. Spending time in multi-hour blocks keeps the run cheap and
fast.

## Tests (`test/manor/claudeworth_test.clj`)

Use `{:llm :mock :offline? true :test? true :run-id (str (gensym ...))}`, a
`store/delete-test-runs!` fixture, and `e/stop-run` at the end (see `engine_test.clj`).

- **World:** the world file loads with no problems; bedrooms are bedrooms; `(space/area world
  "study")` is the 11 house locations; the village area is green, store, office and lodgings.
- **The mock day:** run 47 rounds (to 07:00 Sunday). Check:
  - no `:action/forfeited`
  - replay equals the live world
  - the earl was at `architects-office` during office hours, and a `:agent/said` event there is
    visible to `pugh`
  - the countess visited `abbey-church`
  - each agent has an `"asleep"` `:agent/occupied` event by 23:00, and made no model call
    (`:llm/usage` by that actor) between then and their wake-up
  - Hale roused the earl at 06:30 (`:agent/freed` with `:by "hale"`), and the earl acted in
    round 47
  - `map.edn` and `world-start.edn` exist in the run directory
- **Privacy:** the earl's words at the architect's office are not visible to the countess or Hale,
  and don't appear in the countess's prompt (`prompt/agent-messages`).
- **Action unit tests** (pure, as in `space_test.clj`'s `perform`):
  - `walk` within the house works and doesn't end the turn (`:kind :free`); it rejects a village
    target with the "use travel" reason
  - `travel` rejects a house target ("use walk") and moves to `architects-office` in one turn
  - `sleep` rejects outside a bedroom (the reason names their own bedroom) and more than 12 hours
  - `spend-time` occupies the right number of rounds (1.5 hours = 3 rounds)
  - `rouse` frees someone busy in the same room and rejects otherwise
- **`:architect? false`** leaves Pugh out and the mock day still runs with no forfeits.

`clojure -M:test` must pass in full.

## The real run

> **Changed 2026-10-16:** this section used to ask for a real run of a whole simulated day (48
> rounds, about 20 minutes). Real runs are now short by default (see "Real runs are short by
> default" in `AGENTS.md`); the whole day is the mock day's job.

After the mock day passes, do **one** short real run:

```clojure
(e/start-run :claudeworth {:test? true :max-cost 0.10 :max-rounds 12}) ; then (e/auto! run 12), wait-idle
```

It must be under `test-runs/`. Then read `test-runs/<id>/llm.edn`. Count tool replies starting
`error:` or `rejected:`, and `:action/forfeited` events. Look for confusion: wrong ids, walking
to the village, sleeping outside bedrooms, never sleeping. Note `:finish-reason "length"` too.

Fix briefs, docs and `:valid?` reasons where models stumble. If a fix needs checking, one more
real run capped at **$0.05** is allowed. **Total real spend must stay under $0.30.**

Report:
- cost and calls per agent
- counts of errors, rejections and forfeits
- what the agents actually did over the day, a few lines
- what you changed in response

## Don't

- write to `runs/` or delete anything there; tests and checks use `:test? true`
- commit; the parent session reviews and commits
- start phase 2 work
- read or depend on `external-resources/` (that's phase 2)
- print the OpenRouter key

The read tool can't open images in this environment. Don't rely on viewing PNGs.

## Final report (return this)

1. Files created and changed, one line each.
2. Test result: the `clojure -M:test` summary line.
3. Real run: cost, calls per agent, errors/rejections/forfeits, a short account of the day, and
   changes made in response.
4. Any deviation from this brief and why, any engine change, and open questions.
