# Next steps (handoff, 2026-10-15)

Where the Claudeworth Manor work stands, and what to do next, in order. For a new session or a
sub-agent. Read `AGENTS.md` first.

## Where things are

**All of phase 1 and the fixes brief (`docs/claudeworth-phase1-fixes.md`, tasks 1–6) is done and
committed, but not yet reviewed** by the parent session. It's the commit after `9db0298`; see
`git log`. In that commit:

- **The scenario:**
  - `resources/worlds/claudeworth.edn` (world, setting, role paragraphs)
  - `src/manor/scenarios/claudeworth.clj` (`walk`/`travel` with area names, `sleep`,
    `spend-time`, `rouse`, `journeys` in the view, brief functions)
  - `test/manor/claudeworth_test.clj` (including "move the abbey")
- **Engine:**
  - rejected arguments report their length and how much to cut (`actions.clj`, with a
    `:advised-max` schema property)
  - `say`/`whisper` accept 1500 characters but are documented as 1000 (`builtins.clj`)
  - a role's `:brief` may be a function of the world (`prompt.clj`)
  - transcripts: `src/manor/transcript.clj`, `(transcript! id)` at the REPL
- **Docs:** the design doc (phase 1 done; decisions 13 and 14), the authoring guide ("one home
  per fact", "tool descriptions are mechanics"), README, AGENTS.md.
- **The worker's own checks:**
  - `clojure -M:test`: 44 tests, 0 failures
  - village prompts against `runs/smoke-llm` are byte-identical, apart from the `say`/`whisper`
    tool descriptions and `maxLength` (800 to 1500)

**Real-model spend on Claudeworth:** $0.112 (the stopped run) + $0.092 (the 12-round check) =
$0.204. Hamlet added $0.008. Phase 1's approved budget was $0.30.

**Real runs, for reading** (`/tmp` may not survive a reboot; `clojure -M:test` deletes
`test-runs/`):

- **`/tmp/claudeworth-real-1-backup/`:** the stopped run before the fixes. 16 rounds, 25 speeches
  rejected for length, 3 forfeits.
- **`/tmp/claudeworth-real-2-backup/`:** after the fixes. 12 rounds, 82 calls, 1.71 calls per
  turn, 0 length rejections except 2 speeches over 1500 (fixed on the next call), 0 bad ids,
  2 forfeits. Each has `transcript.md`.

## Step 1: review the committed work

1. `git show` on the commit; check it against both briefs.
2. Run `clojure -M:test`.
3. Repeat the village prompt comparison with `git stash` or the parent commit; never write
   under `runs/`.
4. Try `clojure -M:dev:run claudeworth --mock --test` and read
   `/tmp/claudeworth-real-2-backup/transcript.md`.
5. Fix anything wrong in a follow-up commit.

## Step 2: decisions for the user, then small fixes

1. **Forfeits from walking only (new).** In the real check, Hale and Pugh each spent all 4 calls
   of a turn on free `walk`s from room to room and never took a turn-ending action. Options:
   - a line in the brief or tool description ("after walking, take your action")
   - after N walks in a turn, reply "you have arrived; now take your action"
   - treat a turn that ends with free actions only as a `pass` rather than a forfeit (an engine
     change)
2. **Two numbers for the speech limit.** The JSON schema the model sees says `maxLength 1500`,
   and the description says 1000. That's the slack the user asked for, but models see both. A
   possible fix is to hide `maxLength` from the tool schema while still enforcing it.
3. **Replies to a turn's last call aren't recorded.** `llm.edn` doesn't record the engine's reply
   to a turn's last call, so transcripts can't show it. That would be a small engine change in
   `engine/activate-llm` or `record-call!`.
4. **The GM sees the setting twice** (its brief and the state). It's cheap, so low priority.
5. **Earlier finding, now probably fixed:** `note`'s 500 limit was hit once. It now reports the
   length.

## Background: what the consistency work (task 4) was

This is done; kept for reference. The user approved it as a follow-up after the first worker:

1. A small engine change: a role's `:brief` may be a function of the world.
2. Setting, role flavour and every map fact move into the world file; agent-facing text derives
   from it.
3. Tool descriptions describe mechanics only.
4. A `journeys` list in the view.
5. A test that moves the abbey farther away and checks that no agent-facing text still says
   "half an hour" for that journey.
6. Both rules ("tool descriptions are mechanics only" and "one home per fact") in
   `docs/scenario-authoring.md` and the design doc.
7. One real check, with a small cap.

User decisions recorded in the design (13 and 14): speech is advised at 1000 and accepted up to
1500. Area names in `travel` go to the nearest entrance, found by pathfinding.

## Step 3: phase 2 (building)

Write `docs/claudeworth-phase2.md` as a build brief in the style of `docs/claudeworth-phase1.md`,
from sections 6.2–6.5, 7 and 10 of `docs/claudeworth-design.md`:

- money (earl, countess and secretary all spend)
- the shop's `buy`
- the architect MCP server in babashka (`mcp-servers/architect/`): `consult_references`,
  `price_list`, `draft_plans`
- `draw-plans`, `show-plans`, `commission`
- construction after one day (`:build-rounds` override)
- the plan-to-room test

Then dispatch it. Follow the rules from step 2 (one home per fact; mechanics-only tool
descriptions).

## Process notes (learned the hard way)

- **Sub-agent time limit:** sub-agents default to a 30-minute limit. The first phase 1 worker
  timed out. Don't fix that by raising timeouts. (`clojure -M:test` itself is fast: about 12
  seconds of wall time, measured 2026-10-16.)
- **Real runs are short** (user, 2026-10-16; the policy is in `AGENTS.md`, "Real runs are short
  by default"): about 8–12 rounds, `:max-cost` 0.10, in the foreground, then write the
  transcript (`(transcript! id {:test? true})`); the user wants to read transcripts. Rounds take
  about 27 seconds each. Don't plan whole-day runs (48 rounds, about 20 minutes) or raise
  timeouts to fit them, and sub-agents don't start real runs unless their task allows it.
- **`clojure -M:test` deletes `test-runs/`.** Back up anything there you want to keep.
- **The read tool can't open images** in this environment.

## Later (recorded, not scheduled)

- **The architect shouldn't stay a full-time agent.** He made the most model calls while nobody
  visited him. Make him hibernate when nobody else is in the village (design decision 11).
- **Reference cleanup:** replace the Ashcombe documents with a curated architecture reference
  (design decision 9).
- Test agents without the fixed setting facts (the village and abbey exist) in their briefs.

---

## Addendum: review outcomes, decisions and the fix list (2026-10-15)

Appended after the parent session reviewed the committed work; the text above is unchanged.

### The review of step 1

The work in commit `08b790e` was read against both briefs and checked against the archived 12-round
real run (now in `old-test-runs/`, moved out of `/tmp`). `clojure -M:test` is 44 tests, 0 failures.
Scope matches the briefs. The run corroborates the reported numbers: 12 rounds, 82 calls, $0.0918,
0 bad ids, no `finish: length`, no text-only calls. Two corrections:

- the run's 3 errors are 2 speeches over 1500 **and one `note` over its 500 limit** (801 chars), not
  just the speeches; "now probably fixed" in step 2.5 above overstates it (only the message improved)
- step 1.3 (the village prompt byte-for-byte comparison) was deliberately **not** run: the diff
  supports the claim and the parent session judged it not needed for now

### Decisions (these settle the "decisions for the user" list in step 2)

1. **Forfeits from walking: bump `:max-steps` to 10, globally, and add a nudge.** `:max-steps` is a
   runaway/cost guard only; it has no game-time meaning (a turn is 30 minutes whatever the call
   count). What consumes a step: free actions (`walk`, `note`, `rouse`), query actions
   (`:max-queries` 2), and every retry after an error/rejection. Walking a long way in a growing
   house is a *separate, later* simulation question; for now, 10 plus the nudge.
   - Add the nudge in `engine/activate-llm` (the loop already appends a user message for the
     text-only case): when a step leaves no final action, remind the model that free actions don't
     end the turn but do use calls, and to finish with a turn-ending action (`say`, `whisper`,
     `travel`, `sleep`, `spend-time`, `pass`). Trigger early (once ~2 calls have passed with no
     final action) and again on the last call; exact thresholds in implementation.
   - A separate "failed attempts" budget (stop early after N consecutive error/rejection replies)
     was discussed but is **not** part of this change. Revisit only if a malfunctioning model still
     wastes calls at 10.
2. **Speech limit: hide the schema's `maxLength`.** `:max` stays 1500 (rejected above it, advised
   down to 1000), but `actions/->tool` omits `:maxLength` for any schema carrying `:advised-max`, so
   the description's number is the only one the model sees. Slack stays; the contradiction goes.
3. **`note` gets the same treatment as `say`.** Give it an `:advised-max` below its real 500 and
   state that number in the doc (proposed: `[:string {:min 1 :max 500 :advised-max 400}]`, "at most
   400 characters"; confirm the numbers when implementing), so the model stays safely under 500.
4. **Record a turn's last replies.** Persist the engine's replies to the final call of a turn
   (small change in `engine/activate-llm` / `record-call!`), so transcripts show them and the
   error/rejection counts are complete (they currently miss the last call of every turn).
5. **GM sees the setting twice.** Trim the keys the GM brief already carries (`:setting`, probably
   `:roles`) from the default `gm-view` dump, rather than rewriting the dump, so the other
   scenarios' GMs lose nothing.

### Fix list, in order

1. `src/manor/engine.clj`: `:max-steps` default 4 → 10; the nudge; record last-call replies. Tests
   in `engine_test.clj` (including a policy that walks more than once then acts).
2. `src/manor/actions.clj`: omit `:maxLength` when `:advised-max` is set (`->tool`). Test.
3. `src/manor/builtins.clj`: `note` doc + `:advised-max`. Test.
4. `src/manor/prompt.clj`: trim the default `gm-view` dump. Test.
5. `README.md`: options table, `:max-steps` 4 → 10.
6. Docs: reflect the above in `docs/claudeworth-design.md` (decisions 13/14 wording) and
   `docs/scenario-authoring.md` if it states the length limits.
7. Write `docs/claudeworth-phase2.md` from design §6.2–6.5, §7 and §10 — including the full
   done-criteria (offline stub with the same signatures, the architect's private purse and 10% fee,
   `:observe` `:household`/`:plans`, `:build-rounds`, the "odd room", the structural refusals, the
   `:architect? false` clerk path, `map.edn`), not just the bullet list in step 3 above. (Not a
   reason for a long sub-agent timeout; see the correction under process notes.)

### Other updates

- `old-test-runs/` is a temporary parking spot for the archived runs; it is not gitignored and must
  not be committed. Delete it (or ignore it) once the review is signed off. `transcript!` only looks
  in `runs/` and `test-runs/`, so use `(manor.transcript/write! "old-test-runs")` directly; the
  header path baked into the transcript is stale.
- Test runs live under `test-runs/` and `clojure -M:test` clearing them is expected; that is not a
  problem to fix.
- Open question, not decided: **the architect's hibernation** (design decision 11) before or during
  phase 2. In the archived run Pugh made 26 of 82 calls with nobody in the village, so it is a real
  cost lever for phase 2's longer runs.

---

## Fix list items 1–6 done (2026-10-16)

Uncommitted, in the working tree, for the parent session to review. No real runs were made.

1. **Engine** (`engine.clj`): `:max-steps` default 10. A participant with no turn-ending action
   yet gets a user message after its 2nd call ("Your turn isn't over: free tools, queries and
   rejected calls don't end it, but each uses one of your 10 calls this turn (2 used). When
   you're ready, finish with one turn-ending action: `say`, ... or `whisper`.") and before its
   last ("This is your last call this turn. Finish now with one turn-ending action (...), or you
   lose your turn."). The list is the turn-ending actions that agent has this turn. The GM gets
   no reminder (its turn has no forfeit). Every `llm.edn` entry now has `:replies`: the messages
   the engine sent back after that call, so a turn's last call has them too. The `:llm/usage`
   event is still emitted right after the response, so the event order and replay are unchanged.
   `manor.transcript` shows `:replies`, and falls back to the next call for older runs (its
   summary then says that run didn't record last-call replies).
2. **`->tool`** (`actions.clj`) leaves out `maxLength` for a string schema with `:advised-max`.
3. **`note`**: `[:string {:min 1 :max 500 :advised-max 400}]`, doc "at most 400 characters".
4. **GM view** (`prompt/default-gm-view`): `:state` without `:setting` and `:roles :gm`
   (and without `:roles` if nothing else is left). Village and other scenarios: unchanged.
5. README options table; 6. `claudeworth-design.md` (decision 13 wording, new decisions 15 and
   16, a line under phase 1), `scenario-authoring.md` (steps and reminders, `maxLength`, `note`,
   the GM view), and one line in `DESIGN.md`'s turn description.

Checks: `clojure -M:test` 49 tests, 0 failures, about 12 s. A 47-round mock Claudeworth run
(`test-runs/cw-mock-nudge/transcript.md`, deleted by the next `clojure -M:test`): no forfeits; at
06:30 Hale walks, rouses the earl, gets the reminder, then speaks; every turn's last call shows
`engine: done`.

Still open: item 7 (the phase 2 brief); a real check of the reminder with a paid model when the
user approves one; the architect's hibernation question; deleting `old-test-runs/` once the
review is signed off.

## Parent review and the short-run policy (2026-10-16)

- **Review:** the diff for items 1–6 was read and matches the decisions. The parent's own checks:
  `clojure -M:test` 49 tests, 0 failures, 13 s; a 47-round mock Claudeworth run shows Hale's
  reminder after `walk` + `rouse` and `engine: done` on the last call of each turn, with 0
  errors, rejections or forfeits. Not committed yet.
- **Short real runs are now policy** (`AGENTS.md`). The whole-day real run in
  `claudeworth-phase1.md` and the full-length run in the design's phase 2 criteria were changed
  to match; the design's cost section now says its whole-day numbers aren't test plans.
- **Open, small:** there's no run option to start the clock later. Today it takes a copy of the
  world file with a different `:meta :start`, passed as `:world-file`. A `:start` run option
  (overriding the scenario's clock) would make "start near what you're testing" easy; worth
  adding with phase 2, so the phase 2 brief should mention it.
- The phase 2 brief (item 7) must follow the policy: short real runs only, no full-length run
  in its done-criteria.

## The `:start` run option (2026-10-16)

The small open item above is done: `(start-run :claudeworth {:start "1904-10-15T21:00"})` (CLI
`--start 1904-10-15T21:00`) starts the scenario's clock at that time and keeps its
`:minutes-per-round`. `engine/init!` replaces the clock's `:start` before `:world/initialized`, so
replay, `load-run` and branching keep it. A bad time fails at start ("bad :start option: ..."),
and so does `:start` with a scenario that has no clock. Only the clock changes:
`world-start.edn` stays a copy of the world file (08:00), and people start where it puts them.
From 21:00, Claudeworth's mock script picks up at 22:00 (Hale, Mr Pugh) and 22:30 (the family);
everyone but Mr Pugh is asleep by 22:30 and Hale rouses the earl at 06:30. Mr Pugh's 22:00
`sleep` is rejected because he is still in his office (his walk home is at 18:00), so he
forfeits that turn and stays up. Tests: `the-start-option` (engine) and `a-late-start`
(Claudeworth).
