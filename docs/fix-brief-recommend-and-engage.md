# Fix brief: `recommend`-to-self crash, and `engage` unavailable during an interview

Two defects found in run `runs/claudeworth-20261018-230543`. They are independent; fix and
test them separately. Do not delete that run — it is the user's real-model evidence.

Read `AGENTS.md` first. This brief is the task; when both fixes are committed, move this file to
`docs/archived/`.

---

## Evidence

- The run ends at `:run/paused` (event 913) with
  `error during lady-claudeworth's turn: Duplicate key: lady-claudeworth`.
- The pause-requesting turn: events 879 (usage for the `recommend` call),
  880 `:action/forfeited` (`the turn failed`), 881 `:run/pause-requested`, all round 9.
- Two kitchen maids were recruited that morning:
  `candidate-251` Priscilla Stubbs (properly engaged, events 419/435/450–454) and
  `candidate-142` Lizzie Ward (never offered a post; departed in round 9,
  events 853–855 `:people/departed` "the visit has ended").

---

## Issue 1 — `recommend` with `for` = the caller crashes the turn

### Symptom
`IllegalArgumentException: Duplicate key: lady-claudeworth`, thrown while handling a `recommend`
query, forfeits the caller's whole turn and pauses the run.

### Root cause
The model called (llm.edn line 314):

```
recommend {"position": "kitchen-maid", "for": "lady-claudeworth"}
```

i.e. it recommended a candidate **to itself**, so `actor == for`.

1. `recommend-reason` (`src/manor/hiring.clj:84-89`) only requires `for` to be active, non-GM and
   `space/together?` with the caller. `(space/together? world actor actor)` is true, so
   self-recommendation validates and the query runs.
2. `recommend!` found an existing reusable candidate (`reusable`, `hiring.clj:57-63`: Lizzie Ward,
   `:status :away`, `:area "carriage-sweep"` matches `(area world "lady-claudeworth")`), so it took
   the `old` branch and made no bridge call. That is why there are no `:bridge/called` events
   between event 879 and 880.
3. `recommendation-events` (`hiring.clj:140-144`) then builds two eager set literals with both
   variables:
   ```clojure
   {:type :people/recommended :visible-to #{actor for}
    :data (... :knowers #{actor for})}
   ```
   Clojure compiles set literals to `PersistentHashSet/createWithCheck`, which throws
   `IllegalArgumentException("Duplicate key: " + x)` on a repeated element. `actor == for` is
   exactly that.
4. The exception escapes `run-query!` because only the *composed query* is inside the try
   (`src/manor/engine.clj:299-301`); the `:on-result` call at `engine.clj:327-329` is outside it.
   It reaches `activate`'s catch (`engine.clj:786-789`), which forfeits the turn and requests a
   pause.

The only other game-facing set literals over two ids are guarded: `engage` uses
`#{actor (:who args)}` but `engage-reason` forbids `actor = who` (`hiring.clj:225-230`), and
`accept`'s `#{actor employer}` is safe because `engage` forbids self-offers. `recommend` is the
unguarded one.

### Suggested fixes (do both)
1. **Reject self-recommendation** in `recommend-reason` with an actionable reason, e.g.
   `(when (= actor for) "ask someone else here to recommend to you, not yourself")`. Make sure the
   wording tells the model what to do instead (that pattern gets models to self-correct; see the
   lessons section of `AGENTS.md`).
2. **Make the event construction non-throwing regardless**, so no future caller can trip it:
   replace `#{actor for}` with something that cannot throw on a duplicate (`(conj #{} actor for)` /
   `(into #{} [actor for])`), in both places at `hiring.clj:143-144`. `#{}` for `known-to` and
   one-element sets elsewhere are fine.

Consider (optional, separate judgement) whether `:on-result`/`:reply` errors should also be caught
by `run-query!` and returned as a query failure rather than killing the turn. If you do that, add a
test that a throwing `:on-result` yields an error reply and does **not** pause the run.

### Tests to add
- A unit test that `recommend-reason` returns a reason when `for` equals the actor.
- A reducer/event test (or a mock scenario run) that `recommendation-events` with
  `actor = for` either is never reached (fix 1) or produces one `:people/recommended` and one
  `:people/reputation-known` with a single-element `:visible-to`/`:knowers`, and does not throw.

---

## Issue 2 — the hire was made in narrative but never in hard state; the visitor then left

### Symptom
Lizzie Ward was interviewed, "taken on" verbally, moved into the maid's room, and did kitchen-maid
work for several rounds, but the engine still held `candidate-142` as an unengaged `:here` visitor.
At the round-9 tick she was removed as an idle visitor (`:people/departed`, "the visit has ended",
event 853). Both she and lady-claudeworth wrote notes saying she was hired.

### Root cause
The engagement was never recorded because the employer never could call `engage` at the moment she
needed it:

- `activate-llm` computes the turn's available actions **once, from the turn-start snapshot**:
  `actions (turn-actions run snapshot agent-id turn)` (`src/manor/engine.clj:612-615`), and
  `run-calls` reuses that same list for every step of the turn. A free `walk` does not refresh it.
- `engage`'s `:available?` (`hiring.clj:286`) requires a candidate already present
  (`present-candidate-reason`, `hiring.clj:222-224`: active, `:status :here`, `together?`).
- In round 2 lady-claudeworth began the turn in the kitchen, then used free `walk` to reach the
  carriage-sweep and interviewed Lizzie there. So `engage` was absent from her tool list for that
  entire turn (her own thinking notes this), even though she was standing with the candidate when
  she said "I'll take you on".
- With no `people/offered` event, Lizzie's `accept-place` (gated on an existing `:place-offers`
  entry, `hiring.clj:295`) could never become available either. Hard state stayed `:status :here`.

She then left by the ordinary rule, because it counts only conversations: the `tick`
(`hiring.clj:216-229`) departs a `:here` candidate when
`(- round (max arrived last-conversation-round)) >= leave-after` (3). Lizzie's last conversation was
round 6 (conv-620), so at round 9 she was removed — despite working and living in the house. (The
tick itself behaved as written; the missing engagement is the bug.)

### Suggested fixes (pick one primary, coordinate the rest)
The design intent is: arrive at the meeting place, interview by `converse`, then `engage`; the
candidate `accept-place`s; an unengaged visitor eventually leaves. The failure is that the tool set
is stale after a free move.

- **Preferred: refresh tool availability within a turn.** After a free action that can change what
  the actor can do (`walk` at minimum, possibly any free action), re-run
  `turn-actions`/`a/available` against the current world for subsequent model steps. Keep free/query
  budgets and `:done?` state as they are. Watch cost: recomputing tool specs each step must not
  change prompt caching in a way that inflates real-run cost materially.
- **Alternative: loosen `engage`'s `:available?`** so it is offered whenever any candidate the actor
  knows is in play (or whenever the actor is anywhere in the meeting-place's area), and let
  `present-candidate-reason` do the location enforcement through `:valid?`, with an actionable
  reason ("you must be at <meeting-place> to make the offer"). This keeps prompts stable and matches
  the existing pattern of actionable `:valid?` reasons.
- Whichever you choose, consider whether a candidate who has been given a bed / is working should
  still be evictable by the idle tick. A cheap guard (e.g. don't depart a candidate who has an
  assigned bedroom, or count any in-person interaction, not just `converse`) reduces the chance of a
  narrative-vs-state divergence producing a silent disappearance. This is secondary; the primary fix
  is making `engage` reachable.

### Tests to add
- Extend the hiring tests so that an interviewer who walks to the meeting place and interviews a
  candidate in the same turn can then `engage`, and the run records `:people/offered` →
  `accept-place` → `:people/post-changed` → `:people/candidate-updated {:status :engaged}`.
- A test that a candidate who is still `:here` and unengaged after `leave-after` conversation-free
  rounds departs (lock in the existing rule), and that an **engaged** candidate does not.
- If you change the idle tick, cover the new guard.

---

## Verification (follow the repo rules)

- `clojure -M:test` first and last.
- Reproduce cheaply, free and offline, before any real run:
  `clojure -M:run claudeworth --mock --offline --test`.
- Do **not** start a real-model run without the user's go-ahead. If one is warranted, cap it
  (`:max-rounds`, `:max-cost`) and run it in the foreground via `--test`; keep the run directory and
  report cost.
- Re-read `runs/claudeworth-20261018-230543/transcript.md` around round 9 while working; do not
  modify or delete anything under `runs/`.

## Guardrails

- Reducers stay pure; world changes only through `engine/emit!` → `world/apply-stamped` →
  `apply-event`.
- Action arguments are still decoded and validated before any effect.
- Don't put private candidate data in events visible to other agents. The `:people/recommended`
  and `:people/reputation-known` events are the checked projections; keep them that way.