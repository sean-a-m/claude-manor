# Next steps (handoff, 2026-10-16)

Where the Claudeworth Manor work stands, and what to do next, in order. For a new session or a
sub-agent. Read `AGENTS.md` first.

## Where things are

**Phases 0, 1 and 2 are done and committed.** The Claudeworth Manor vertical slice is complete:
the world file, clock, `walk`/`travel`, talk, `sleep`/`spend-time`/`rouse`, money, the shop, plans
(`consult-references`, `price-list`, `draw-plans`, `show-plans`, `commission`), the babashka
architect MCP server, delayed construction, `map.edn`, and the optional architect
(`:architect? false`). The engine gained interruptions (`b/interrupt`, the `carry-on` action,
`b/resume`).

`clojure -M:test` passes: **63 tests, 767 assertions, 0 failures, 0 errors**, free and offline, in
about 15 s. The full building slice is checked against both the offline stub and the real babashka
server, including `:architect? false`, the odd room, malformed drafts, privacy and replay.

- The build brief is `docs/claudeworth-phase2.md`.
- The design and the decisions are in `docs/claudeworth-design.md` (section 10 marks phase 2 done,
  with the deviations; section 12 has decisions 17-21).
- The phase-2 implementation handoff (`docs/phase2-implementation-todo.md`) is closed: its wording
  fixes and the review are done.

## Real spend and archived runs

Real Claudeworth spend, all runs: about **$0.56**. Before phase 2 it was about $0.37 (five runs,
listed in the history below). Phase 2 added two short checks: `$0.109590879` (`phase2-real-1`) and
`$0.0828` (`one-build-real-1`), **$0.192 in all** - just under the phase-2 budget of $0.20.
(Note: the second check's `:max-cost` was 0.10 and it cost $0.0828, above the $0.05 guidance
written before it; the total still fits.)

Archived under `old-test-runs/` (gitignored; **never commit it**, delete it when the user is done):

- `phase2-real-1`: first phase-2 check. 10 rounds, 79 calls, $0.109590879, 17 error replies (15
  query-limit, 2 "Pugh draws the plans here; ask him"), 0 rejections, 0 forfeits. The Saloon was
  built at 12:30 and walked into; the Library and a guest bedchamber were still building when the
  $0.10 cost cap stopped the run one round late.
- `one-build-real-1`: after the one-room-at-a-time change. 10 rounds, 76 calls, $0.0828, 6 errors
  (3 floor wording, 3 query limit), 1 rejection, 0 forfeits. No commission confusion.
- the three phase-1 runs: `claudeworth-real-3`, `claudeworth-reasoning-1` and the 12-round check
  at the top level.

## Not fixed (deliberate, or open)

- **Architect hibernation** (design decision 11) and **reference-library cleanup** (decision 9)
  are still deferred by the user. The server filters the Ashcombe extracts to architectural fields
  and now drops whole sentences that state social rules ("Servants do not use it except ..."), but
  the curated reference document is not written.
- **The cost cap overshoots by one round**: cost is checked at round boundaries, so `phase2-real-1`
  spent $0.109590879 against a $0.10 cap. Report this; don't hide it.
- **Rents and income, hunger, hiring and the inn** are phase 3. The GM brief now tells the GM not to
  invent money arriving.
- The floor and query-limit wording was fixed and covered by tests, but only short real runs have
  checked it. Chasing complete construction with a longer run is not needed.
- `old-test-runs/` and `/tmp` backups are not durable; anything worth keeping should be read before
  a cleanup.

## Phase 3: incentives

From design section 10: an inn, hunger and fatigue, quarterly rents as income, hiring servants as
spawnable roles (which feeds on building), gongs and abbey bells, the access matrix and the green
baize door, and spending rules. Nothing is scheduled; the user asked for phase 2 first.

## Process notes (still true)

- `clojure -M:test` is free, offline and fast; it deletes `test-runs/`.
- Real runs are short by default (`AGENTS.md`, "Real runs are short by default"). Start near what
  you are testing with `:start` or `:build-rounds` rather than playing up to it.
- `runs/` is the user's data: never delete it and never write under it. Test and ad-hoc runs go to
  `test-runs/` (`{:test? true}` / `--test`).
- Sub-agents have a 30-minute default limit and must not start real runs unless their task says so.

---

# History

The dated sections below were written during phase 1 and the phase 2 build. They are kept for
reference; they are not a current task list.

## Next steps (handoff, 2026-10-15)

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

(Since committed in `16b747d`; the line below was true when written.) Uncommitted, in the
working tree, for the parent session to review. No real runs were made.

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
everyone is asleep by 22:30 and Hale rouses the earl at 06:30. Mr Pugh is still in his office
at 22:00 (his walk home is at 18:00); the mock policy walks anyone whose scripted `sleep` finds
them away from their bedroom there first, so he sleeps in his lodgings with no forfeits. Tests: `the-start-option` (engine) and `a-late-start`
(Claudeworth).

## Automatic transcripts; walking to bed in the mock script (2026-10-16)

- The engine writes `<run-dir>/transcript.md` whenever a run stops running rounds: at the end of
  `do-round!` when the run isn't carrying on in auto mode (a pause for any reason, the end of
  `auto!`'s `n` rounds, a manual step, `pause!` during a round), from `pause!` itself (queued, so
  a pause between auto rounds and `stop-run` are covered), and after a round that throws. All of
  these run on the run's executor, after the round's events and llm.edn entries are on disk. It is
  skipped when no events were added since it was last written (or since the run was started or
  loaded, so loading an old run and stopping it leaves its files alone), and a failure is printed
  to stderr, never thrown. No option: the test suite's wall time didn't change measurably (about
  14–16 s either way). `(transcript! ...)` stays for old runs. Test:
  `transcripts-are-written-when-a-run-stops`.
- Claudeworth's mock policy: a scripted `sleep` that finds someone away from their bedroom walks
  them there first (or travels, if it's in another area; that takes the turn, so they then sleep
  only if a later scripted turn says so). From a 21:00 start, Mr Pugh walks from his office to his
  lodgings at 22:00 and sleeps there, with no forfeits; the 08:00 mock day's events and model calls
  are unchanged. Script progress within a turn now skips calls off the script (`scripted-done`).

## `pass` as waiting, and the private-thoughts rename (2026-10-16)

- **`pass` now reads as a legitimate wait.** Its doc says it's a fine choice when you want to see
  what others do, and the engine's reminder adds "If you're unsure what to do, `pass` is a fine way
  to wait and see." when the agent has `pass`. Claudeworth's brief says the same. This is a first
  response to the pacing seen in real runs (agents used free `walk`s to hedge instead of ending a
  turn); the other causes recorded earlier (movement is public, no within-turn question/reply) are
  untouched by decision.
- **The captured deliberation is now called the character's `:thoughts`** everywhere in our own
  code: the llm.edn key, the response key, the usage key (`:thought-tokens`) and the transcript
  line (`thoughts:`). The wire field stays `reasoning`, and the run option `:reasoning` (passed to
  OpenRouter) is unchanged. Reason: the project's vocabulary is the world's (`note`, `say`, `brief`,
  `persona`), and the value is the character's cognition in the run record. Nothing about what is
  captured or sent changed: `:message` is still the only thing returned to the model, and the
  transcript still shows the text.
- A 12-round real run with the thoughts recorded cost $0.077 (`old-test-runs/claudeworth-reasoning-1`).

## Tidy-up and phase 2 kickoff (2026-10-16, later session)

Housekeeping before phase 2, by the user's instruction:

- **`old-test-runs/` is now gitignored** (it held the archived real runs). The files stay on disk;
  delete the directory whenever the user is done with them. It holds three real runs: the files at
  its top level are the 12-round check after the phase 1 fixes (82 calls, $0.0918, `:max-steps`
  4; the one the review above read), and two subdirectories:
  - `claudeworth-real-3` (12 rounds, 92 calls, $0.0802) is the **paid check of the nudge**
    (`:max-steps` 10 + reminder): the reminder appears after 2 free `walk`s, `engine: done` on the
    last call of each turn, 3 errors, 1 rejection and 1 forfeit. It was not recorded in the
    sections above.
  - `claudeworth-reasoning-1` (12 rounds, 80 calls, $0.077) is the run after the `:thoughts`
    rename, with `thoughts:` lines in the transcript.
- **Test count:** `clojure -M:test` is now **56 tests, 540 assertions, 0 failures** (the sections
  above still say 49; several tests were added with the reminder, `:start` and transcript work).
- **Architect hibernation (design decision 11) is deferred to after phase 2.** Not scheduled; the
  user wants to revisit once the simulation is larger.
- **Reference cleanup (design decision 9) is also deferred to after phase 2**, together with
  hibernation. The architect server keeps returning only the architectural fields of the Ashcombe
  documents until then.
- **Phase 2 is being kicked off.** The build brief is `docs/claudeworth-phase2.md` (item 7 above),
  written this session from design §6.2–6.5, §7 and §10. Dispatch it to a worker once the parent
  has read it; follow the short-real-run policy.

## Phase 2 brief validated and revised (2026-10-16, later)

The brief was checked against the code (`engine`, `bridge`, `mcp`, `space`, `worldfile`,
`prompt`, the scenario and the world file); `clojure -M:test` was 56 tests, 540 assertions,
0 failures. Real Claudeworth spend so far, all five real runs: about $0.37.

Fixed in the brief:
- the construction system read `(:works (get-in world [:state :works]))`, always nil, so nothing
  would have been built
- plan floors were strings while the world file's are keywords
- the stored plan id could differ from the one the model saw (the engine renamed it after the
  server replied, and a turn's prompt isn't rebuilt): the server now gets the `taken` ids
- refusals in `:on-result` were invisible within the turn: the structural checks (rooms, floor)
  move into `draw-plans`' `:valid?`
- the result shape of the server and the stub is specified (`structuredContent`, keyword keys)
- smaller: no `:connects-to` on built locations, space events left at `:gm`, no fee in the
  public commission line, the rules say building happens at the office, `:build-rounds 4` for
  the real run

The user's decisions (design decisions 17–21): `rouse` becomes a general interruption with
`carry-on` (an engine change, step 1 of the brief); one commission at a time per plan; the
office is marked with `:services #{:architect}`; the price list is `mcp-servers/architect/prices.edn`,
read by the server and the stub; queries may span turns, with reference and price results
recorded privately so the caller still has them next turn.

Next: the user reads the revised brief, then it is dispatched to a worker.
