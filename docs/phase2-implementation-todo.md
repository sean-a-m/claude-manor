# Phase 2 implementation handoff (closed)

This checklist was written at the WIP checkpoint `4889a33`, when phase 2 stopped at the user's
usage limit. **Phase 2 is finished (2026-10-16).** The file is kept only as a pointer: the
authoritative record is `docs/claudeworth-phase2.md` (the brief), `docs/claudeworth-design.md`
section 10 (what was built, the deviations and the real-run results), and `docs/next-steps.md`
(the current handoff).

Finished after the checkpoint, in `97771ca` and this follow-up:

- the one-room-at-a-time rule (decision 18): `commission` refuses while any work is in progress,
  and the tool doc, the scenario rules and the refusal message all say so; see
  `building.clj`, `claudeworth.clj` and the test in `claudeworth_test.clj`
- the remaining wording from the real run: the `draw-plans` doc (if an architect is present, only
  that agent can draw), the shared query allowance in the scenario rules (read from
  `[:state :max-queries]`, not a literal), who may `commission`, and the GM's no-income guard
- `floor` normalisation for `draw-plans`: "Ground floor" and "ground" are the same
- the architect's reference extracts drop whole sentences that state a social rule, with a
  regression test in `architect-server-contract`
- step 6 documentation (README, AGENTS, DESIGN, scenario-authoring, Claudeworth design,
  next-steps) and the implementation review, with extra test assertions
- two short real runs, archived under `old-test-runs/`; the final suite is 63 tests, 767
  assertions, 0 failures, 0 errors

Not done, by the user's decision: architect hibernation (design decision 11) and the curated
reference-library cleanup (decision 9). See `docs/next-steps.md`.
