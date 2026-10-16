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

- **Sub-agent time limit:** sub-agents default to a 30-minute limit. Pass
  `options: {timeoutMs: ...}` for longer tasks. The first phase 1 worker timed out.
- **Real runs:** they take about 27 seconds per half-hour round, so a full day is about 20
  minutes and about $0.34. Run them in the foreground with `:max-rounds` and `:max-cost`. Write
  the transcript afterwards (`(transcript! id {:test? true})`). The user wants to read
  transcripts.
- **`clojure -M:test` deletes `test-runs/`.** Back up anything there you want to keep.
- **The read tool can't open images** in this environment.

## Later (recorded, not scheduled)

- **The architect shouldn't stay a full-time agent.** He made the most model calls while nobody
  visited him. Make him hibernate when nobody else is in the village (design decision 11).
- **Reference cleanup:** replace the Ashcombe documents with a curated architecture reference
  (design decision 9).
- Test agents without the fixed setting facts (the village and abbey exist) in their briefs.
