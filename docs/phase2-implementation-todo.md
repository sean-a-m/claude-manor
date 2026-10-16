# Phase 2 implementation handoff / remaining work

Work is in progress in the main checkout. The user subsequently authorized a **WIP checkpoint
commit**, including this handoff; phase 2 is NOT finished. No subagents have been used.
The authority remains `docs/claudeworth-phase2.md`; read `AGENTS.md` and the brief before continuing.
This checklist was written at the user's request, immediately after inspecting the first real run.
Implementation then stopped at the user's request because of usage limits. The checkpoint adds
only handoff notes; it does not complete any of the remaining tasks below.

## Completed

- Read the requested documents in order.
- Step 1: interruptions in engine/builtins/world/prompt/narration; Claudeworth and hamlet rouse
  now interrupt. Carry-on, stopping before accepted free/query/final actions, rejection handling,
  forfeits, scheduling and replay have tests.
- Step 2: stateless babashka architect server, three MCP tools, architectural reference extraction,
  price data file, deterministic drafting with fresh ids.
- Step 3: household funds/stores, architect purse/fee, shop, private plans, sharing, commissions,
  delayed construction, views, world-file checks and initial brief changes. Building code is split
  into `src/manor/scenarios/building.clj`.
- Step 4: extended mock day (including clerk mode), direct MCP contract/parity tests, full offline
  and real-server mock slices, malformed-draft/refusal/privacy/odd-room/repeat-commission tests.
- Full `clojure -M:test` was run after each of steps 1–4.
  Latest result, before the real run: **63 tests containing 754 assertions; 0 failures, 0 errors.**
- Saved village prompts were captured BEFORE changes, using a read-only `e/load-run "smoke-llm"`.
  They were byte-identical after step 1. Need repeat comparison after final changes.
- Step 5: the first real run has completed and been archived. Its entire transcript has been read.
  No second real run has been made.

## Real run: preserve this information

- Original: `test-runs/phase2-real-1/`.
- **Already copied in full to `old-test-runs/phase2-real-1/`.** It is therefore safe to run the
  suite again (which deletes `test-runs/`). Never delete the archive.
- Options: `:test? true :build-rounds 4 :max-rounds 12 :max-cost 0.10`.
- Stopped at round 10, Saturday 12:30, because the engine checks cost at round boundaries.
  Actual cost **$0.109590879** (the configured $0.10 cap overshot by one round; do not hide this).
- Calls/cost:
  - lord-claudeworth: 17 / $0.020671474
  - lady-claudeworth: 17 / $0.027603872
  - hale: 22 / $0.031111617
  - pugh: 21 / $0.027298164
  - gm: 2 / $0.002905752
  - Total: 79 calls.
- Transcript summary: **17 error replies, 0 rejection replies, 0 forfeits**.
  Inspection suggests 15 query-limit errors and 2 "Pugh draws the plans here; ask him" errors;
  verify from raw `llm.edn` using the summary script below.
- No rouses occurred in this daytime real run. Interruption behavior was tested by mocks/tests,
  not exercised by the real models.
- The family discussed the enlargement at breakfast and all reached the office. Pugh drew and
  showed plans. Hale commissioned the Saloon at 10:30; the earl commissioned the Library and
  the countess a Guest bedchamber at 11:00. Household funds became £100; Pugh earned £290.
- **Saloon completed at 12:30 and was walked into.** Library and Guest bedchamber were due at
  13:00, after the cost stop, so they remained in progress. Do not claim all three were built.
- The countess had one length-finished response with no tool call, then recovered after a nudge.
- Late in the run the earl asked the GM for rents. The GM invented a future remittance promise,
  but no money changed. Rents are phase 3; do not implement them.

## Remaining TODO, in order

### 1. Finish step 5: inspect raw log and tune confusing wording

- [ ] Run `/tmp/manor-real-summary.clj` (already written, NOT yet run), or equivalent, to verify
  counts from archived `llm.edn` and events, costs, commissions and completions.
- [ ] Tighten `draw-plans` tool documentation: current wording "The architect draws for a present
  client (for); without one, the clerk draws for you" confused clients into thinking they could
  invoke it while Pugh was present. Say explicitly: if an architect is here, only that agent can
  draw; clients must ask him using say. Without an architect, the clerk draws for the caller.
- [x] Clarify `commission`: only one room is built at a time, so **any** work in progress blocks
  a new commission (the same plan or a different one). The tool doc, the scenario rules and the
  refusal message all say so, and the architect is told the firm builds one room at a time. This
  settles the ambiguity of "A plan already being built must finish first".
- [ ] Clarify the shared query allowance in the scenario rules: reference lookup, price list and
  each individual draft all count toward the same allowance, even if calls are batched. After
  querying references and prices, draft next turn. Keep `:max-queries` at 2; avoid scattering
  another hard-coded setting in unrelated code.
- [ ] Rules currently tell every role "you ... commission a plan you hold", confusing the architect
  whose tools correctly exclude commission. State that only family/secretary commission.
- [ ] Consider a concise GM brief guard: the current slice has no income/rent mechanic, so narration
  must not promise automatic future deposits. No phase 3 mechanics or broader GM redesign.
- [ ] Review reference output against the brief's "no etiquette" requirement. Current server
  selects ONLY allowed YAML fields and ONLY Markdown §§6, 10.2, 10.4, but YAML descriptions can
  themselves contain etiquette (e.g. "Servants do not use it except ..."). The live transcript
  exposed such sentences. Decide on a small conservative extraction/filter in the server if
  needed, with a regression test. **Do not edit Ashcombe source files or start the deferred
  reference-library cleanup project.** No fix for this has been implemented yet.
- [ ] Run the full suite after these changes. Archive is already safe.
- [ ] A second real check is optional, not necessary just to chase complete construction. Only
  make it if a fix genuinely needs checking: user permits at most ONE further run with
  `:max-cost 0.05`, and phase total must remain under $0.20. Use `:test? true`, foreground,
  short round cap; archive before the next suite. Budget already spent: $0.109590879.

### 2. Review implementation and fill small test gaps, if found

- [ ] Review diff for correctness/readability and scope; do not change phase 3 behavior.
- [ ] Consider explicit assertions for freeing-before-action event order, on-time release of an
  interrupted occupation, and repeated rouse refusal. Existing tests cover most of this via
  separate tests but some new assertions could be stronger.
- [ ] Check robustness of untrusted draft handling. Currently rejects non-map/truncated results,
  used/invalid ids, bad/empty lengths, unknown floors/rooms, missing size in request, and
  non-positive/non-integer price/build-days. Server payload deliberately has no size inside plan;
  stored size comes from validated request. Prices/days come from the result per the brief.
- [ ] In `building.clj`, completion event includes `:name` and `:connects-to` in addition to the
  brief's `{:plan-id :room}` so narration can announce the room and its neighbours without hidden
  state or depending on subsequent space events. Retain/report this small deviation unless
  there is a better simple approach.
- [ ] Note general engine additions: interruption behavior plus the OPTIONAL improved query-limit
  wording. `b/resume` is a helper for carry-on/forfeit. No other feature changes intended.

### 3. Step 6: documentation (NOT done yet)

- [ ] `README.md`: scenario can shop, consult, draft/share/commission plans and build; architect
  server requirements, offline mode, useful `:build-rounds` example if appropriate.
- [ ] `AGENTS.md`: update layout for architect server and `scenarios/building.clj`, phase status.
- [ ] `DESIGN.md`: occupation row/round steps explain interruptions and carry-on.
- [ ] `docs/scenario-authoring.md`: occupation contract, `b/interrupt`, carry-on, first accepted
  action stopping occupation, forfeit resumes, labels/private view.
- [ ] `docs/claudeworth-design.md`: phase 2 done; record deviations (kebab-case payloads, public
  top-level `:building`, completion event detail, actual real-run result/cap stop).
- [ ] `docs/next-steps.md`: completion, tests, real spend/archive, remaining phase 3; architect
  hibernation and reference cleanup still deferred by user. Mention any limitations not fixed.
- [ ] Update/remove this temporary checklist as appropriate, so it doesn't remain falsely open.
- [ ] Run `clojure -M:test` after docs (required step-6 check).

### 4. Final verification and report

- [ ] Repeat village prompt capture and `cmp` using files below. Never write under `runs/`.
- [ ] Confirm `git diff --check`, final `git status --short`, no accidental data/key output, no
  Ashcombe edits, no writes under `runs/`. The user authorized this WIP checkpoint commit;
  further commits need their authorization.
- [ ] Final report required by brief:
  1. Files created/changed, one line each.
  2. Final full-suite summary.
  3. Village byte-identical comparison result.
  4. Real run cost, calls per agent, errors/rejections/forfeits, what built, rouse behavior,
     short account of the day, and fixes made in response.
  5. Deviations, any engine changes beyond interruptions, open questions.

## Useful temporary scripts / outputs

These are outside Git and may need recreation if moved to another machine:

- `/tmp/manor-village-prompts.clj`: takes output filename as its argument; loads saved village,
  serializes all active agent messages and GM messages, shuts down executor without stopping or
  writing the run. Before: `/tmp/manor-village-before.edn`. Step-1 comparison output:
  `/tmp/manor-village-after.edn`.
  ```sh
  clojure -M /tmp/manor-village-prompts.clj /tmp/manor-village-final.edn
  cmp /tmp/manor-village-before.edn /tmp/manor-village-final.edn
  ```
- `/tmp/manor-phase2-real.clj`: script used for real run #1. Do NOT rerun it (same id exists,
  and doing so would be a second real run without applying fixes).
- `/tmp/manor-real-summary.clj`: reads the ARCHIVED real run and prints bounded stats. Not yet run.
- `/tmp/manor-step{1,2,3,4}.log`: suite results; step 4 is latest passing result.

## Files already changed/created

Changed: `resources/worlds/claudeworth.edn`; `src/manor/{builtins,engine,narrate,prompt,world}.clj`;
`src/manor/scenarios/claudeworth.clj`; `test/manor/{claudeworth_test,engine_test}.clj`;
`test/manor/scenarios/hamlet.clj`.

New source files included in the WIP checkpoint: `src/manor/scenarios/building.clj`;
`mcp-servers/architect/{bb.edn,prices.edn,src/architect/server.clj,src/architect/references.clj,src/architect/drafting.clj}`;
this checklist. `old-test-runs/phase2-real-1/` is preserved run data, not source to commit.
