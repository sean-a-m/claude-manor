# Claudeworth phase 1: finishing and fixes (build brief)

The continuation of `docs/claudeworth-phase1.md`. Read `AGENTS.md`, then that brief (scope and
decisions still apply), then this one. Where they differ, this one wins.

## State at handoff

- **Uncommitted phase 1 work exists:**
  - `resources/worlds/claudeworth.edn`
  - `src/manor/scenarios/claudeworth.clj`
  - `test/manor/claudeworth_test.clj`
  - edits to `README.md`, `AGENTS.md` and `test/manor/scenarios_test.clj`

  The scripted mock day was checked and works: 47 rounds, no forfeits, replay equal, the earl
  roused by Hale at 06:30. **The full test suite has not been run on it.**
- **The real run `test-runs/claudeworth-real-1` was stopped by the user** at round 16 (15:30):
  $0.112, 133 calls, 3 forfeits. A backup is in `/tmp/claudeworth-real-1-backup/`, with
  `transcript.md` and the throwaway script that made it (`transcript-script.clj`).

  **`clojure -M:test` deletes everything under `test-runs/`**, including that run. That's
  acceptable because of the backup, but don't rely on it being there afterwards.
- **What the real run showed:**
  - **25 rejected `say` calls over the 800-character limit.** The error gave the limit but not the
    text's length, so models trimmed a little and retried. That cost about 2 calls per turn and
    all 3 forfeits (max-steps 4).
  - 4 rejected `spend-time` activities over 80 characters.
  - 2 rejected `travel`s to `"house"` (an area name: the view groups `places` under
    `house`/`village`/`abbey`).
  - Otherwise the agents played well and on theme.

## Tasks, in order

### 1. Finish phase 1

Run `clojure -M:test`; fix any failures. Update `docs/claudeworth-design.md`: mark phase 1 done,
with a short note of what was built and any deviations from the phase 1 brief.

### 2. Rejections must say how to fix the call (engine)

- **Lengths:** in `manor.actions/decode-args`, when a string is too long or too short, say how long
  it was and what to do: `text is 1043 characters; the limit is 1500 - shorten it by at least
  243`. Do the same for collection sizes if it's easy. Keep the existing parts of the message
  (expected parameters, ignored keys). Keep this generic: driven by the malli schema's
  `:min`/`:max`, not by the action.
- **`say` and `whisper`:** raise the limit from 800 to **1500** characters. Their tool
  descriptions must state the limit and the norm, e.g. "Say something out loud to everyone in the
  same place: a few sentences, at most 1500 characters." The engine appends the kind hint.
- **`spend-time`:** keep the 80-character activity limit, but state it in the description ("a
  short phrase, at most 80 characters").
- **Village prompts change on purpose.** The `say` and `whisper` descriptions and limits are
  built-ins. Check that the village prompts change **only** in those tool descriptions: compare
  `runs/smoke-llm` agent and GM messages plus `(e/all-actions sc)` before and after with
  `git stash`, as in phase 1. Don't write to `runs/`.
- Unit tests: a too-long `say` gets the length message, and 1500 characters is accepted.

### 3. Areas in `walk` and `travel`

An *area* is a set of places joined by `:travel 0` routes (`space/area`). In this world the areas
are the `:domain`s: house, village, abbey. Agents naturally say "travel to the village" or
"travel to house".

- `travel` accepts an area name (a `:domain` name such as `"village"` or `"house"`) as well as a
  place id. It goes to the nearest place of that area (the first place in that domain on the
  quickest path, by `space/distance`), e.g. the village green.
  - Travelling to an area name when you're already in it: reject with "you are already in the
    house; use walk to go to a particular place, e.g. ...".
  - Derive everything from the map: no new world-file data.
- Unknown ids in `walk` and `travel` get a helpful reason: "house is not a place. Use a place
  id: <up to 6 ids from walkable or places>". For `travel`, mention that area names work too.
- Say in `travel`'s description that it accepts a place id or an area name.
- Tests for each case.

### 4. One home per fact (the consistency task the user approved)

**Principle:** every fact about the world (places, distances, opening hours, bedrooms, names,
setting) lives in exactly one place: the world file. Everything agents see is derived from it.
If the abbey moved farther away, only the world file's route should need editing. Mock-day
scripts and their tests are exempt: they're scripts for one particular world.

- **Briefs as functions (engine):** a role's `:brief` may be a string, as now, or `(fn [world
  agent-id] string)`. Change only `manor.prompt` (agent messages, and the GM brief if it's a
  function: `(fn [world] string)`). Village prompts unchanged. Test it. Document it in
  `docs/scenario-authoring.md` (roles section).
- **Split the Claudeworth briefs:**
  - Setting and role flavour move into the world file: `:meta :setting` (a paragraph about
    Claudeworth Manor, the village and the abbey; fixed facts like "there is a village and an
    abbey nearby" are fine there), and a per-role `:roles {:earl {:about "..."} ...}` section.
  - The rules about how actions work stay in code, as a brief function that fills in numbers from
    the world: minutes per turn from the clock, the 1500 limit from the action's schema if easy,
    and so on.
  - The scenario puts what it needs from the world file into `:state` at `:init`, so the brief
    function can read it from the world. Briefs must not depend on the scenario namespace's
    static data for world facts.
- **Tool descriptions are mechanics only:** true for every role and any map. No place names, area
  names, role names or distances.
  - `walk`: "to any place listed under `walkable` in your view".
  - `travel`: takes your turn; shorter journeys arrive within the half hour, longer ones leave you
    travelling; see `journeys` in your view. Get the "half hour" from the clock if you mention a
    duration at all.
  - Fix the current `travel` description ("you arrive straight away"), which is false for
    journeys longer than one round.
- **`journeys` in the view:** for each other area, the nearest place, the minutes to get there
  (`space/distance` times minutes per round), and the arrival time. Keep it compact.
- **Opening hours:** shown from the world file's `:hours` only. The architect's office hours must
  not appear in any brief text.
- **Rejection reasons:** built from the current world (look names up), never literal place names.
- **Test, "move the abbey":** load the world file, change the abbey route to 3 rounds in a copy
  (write it under `test-runs/`, or pass the data via a temp file), start a run with `:world-file`,
  and assert:
  - the agent-facing text (system prompt, user message, tool descriptions from `a/->tool`)
    contains no claim that the abbey is half an hour away
  - `journeys` shows 90 minutes to the abbey
  - travelling there puts the agent in transit
- **Docs:** add a short "One home per fact" and "Tool descriptions are mechanics" section to
  `docs/scenario-authoring.md`. Update `docs/claudeworth-design.md` decision 10 or add one: rules
  live in code, setting and flavour in the world file. Revise the "briefs live in Clojure" wording
  in sections 3 and 7.

### 5. A transcript tool

Turn `/tmp/claudeworth-real-1-backup/transcript-script.clj` into `src/manor/transcript.clj`:
`(write! run-dir)` writes `<run-dir>/transcript.md`, and returns the path.

- For each round: what happened (narrated, with who could see it), then every model call (the
  tools called, their arguments, any text, and the engine's reply to each tool call).
- Keep it readable and dependency-free (only what's already in `deps.edn`).
- Add `(transcript!)` (current run) and `(transcript! id)` / `(transcript! id {:test? true})` to
  `dev/user.clj`.
- Mention it in `README.md` and `AGENTS.md` ("To inspect a real run" in AGENTS.md).
- A small test that it runs on a mock run.

### 6. One real check

After everything passes, start one real run:

```clojure
(e/start-run :claudeworth {:test? true :max-cost 0.10 :max-rounds 12})  ; auto! 12, wait-idle
```

**Run it in the foreground (synchronously) and wait for it.** It takes about 6 minutes. Then
write its transcript with the new tool and count:
- rejections and errors, by kind
- forfeits
- calls per agent and per turn
- cost

Compare with the stopped run: 133 calls in 16 rounds, 25 length rejections, 3 forfeits. If
something new goes wrong, fix it if it's small; **don't do another real run**. Total spend for
this task: at most $0.10.

## Rules

- **Commands:**
  - `clojure -M:test` (full suite)
  - `clojure -M:dev:run claudeworth --mock --test`
  - `clj -M:dev`
- Never write to or delete anything in `runs/`. All runs use `:test? true`.
- Don't commit; the parent session reviews and commits.
- Don't start phase 2: no money, shop actions, architect server or `external-resources/`.
- Keep engine changes small, general and tested.
- Never print the OpenRouter key.
- The read tool can't open images here.

## Final report (return this)

1. Files created and changed, one line each.
2. The `clojure -M:test` summary line.
3. Village prompt comparison: what changed (it should be only the `say`/`whisper` tool
   descriptions).
4. The real check: cost, calls per agent, calls per turn, rejections and errors by kind,
   forfeits, a few lines on what the agents did, and the transcript path.
5. Deviations from this brief, and open questions.
