# `find` context and the find judge: design

**Status:** proposal, not started. Extends `docs/stores-and-aspects-design.md` phases 4 and 7.

Read `AGENTS.md` first (invariants, run directories, cost rules), then "Finding" in
`docs/scenario-authoring.md` and `src/manor/mechanics/items/find.clj`. The work judge
(`manor.mechanics.items.judge`) is the model to copy: this brings `find` closer to it while
keeping `find`'s anti-duplication safety net.

## Why

The finder is an LLM adjudicator (`adjudicator/items/find`) whose prompt never grew when its
job did. It was built in phase 4 as an odds-oracle — inputs `place`, `stores`, `what` — and only
given an *identification* duty in phase 7, when the candidate list was bolted on to stop
duplication. It must now answer "is the requested thing the same object as this candidate
elsewhere?" with a bare id and name and no knowledge of who is asking or what they have been
doing.

The evidence is `runs/claudeworth-20261022-212429`. Hale asked for his day-book:

- The day-book was never an item; the work judge had written it into the study's state aspect
  ("day-book drying flat") and into work summaries.
- The local fact pass found nothing, so the oracle was called (`llm.edn:742`). It saw the study
  (with that aspect), no stores, `what: "day-book"`, and 33 candidates of id and name only.
- It answered `existing: "butchers-day-book"` — a different book in the butcher's shop — because
  that was the only listed name containing "day book".
- `finder-check` accepted it (the id is listed), `find-hit` correctly saw it is not in the study,
  and the engine recorded a plain `:item/not-found` with no roll (`events.edn:2096`): "You don't
  find it."

The roll for that find (`[:find 2096]`, seed 1) is 26/100 — `likely` or `even` would have found
it. One identification choice, made blind, decided the outcome. The same thing happened earlier
in the run (`events.edn:220`, "estate papers for the quarter" -> `existing: estate-ledgers`,
which was in the study).

The work judge next door (`items/judge.clj:95-133`) already gets Worker + persona + Place + Doing
+ Carrying + `recent`, the actor's own view filtered by `prompt/scene-recent`. `find` should get
the same kind of context, and keep its anti-duplication safety net.

## Goals

- The finder judges with the actor's own situation in front of it: who they are, what they
  carry, what they know, what this place really is.
- `find` still never mints a second copy of an item that exists elsewhere. `existing` and the
  candidate list stay, and the outcome for an elsewhere item stays a plain miss.
- No new leak: the oracle learns nothing the asking actor does not know, and its answer never
  leaves this place.
- The model judges the prose (place aspects, notes, recent lines); code checks only structure.
  This is the `AGENTS.md` rule, not an exception to it.

## Non-goals

- No change to the tool's salience: still a `:query`, `:per-turn 1`, counting against
  `:max-queries`.
- No change to the reply or its audience ("here only"; an `existing` miss stays
  indistinguishable from an ordinary miss).
- No candidate locations, holders or aspects (the invariant at `stores-and-aspects-design.md:736`
  stands).
- No deterministic prose parsing anywhere.

## Decisions

- **F1. The finder sees the asker.** Name, persona, what they carry, and their own notes. This
  is the work judge's actor material plus notes. Decision: yes, try notes in the judge prompt
  now; they are the most direct identification evidence and are the asker's own.
- **F2. The finder sees the asker's own recent lines**, bounded, drawn from the actor's view
  (`prompt/visible-events`), never anyone else's private lines.
- **F3. Two questions, two bodies of evidence.** *Identification* ("what does the asker mean?")
  may use the asker's notes and recent lines. *Presence* ("is there such a thing here now?")
  uses only the place, its listed contents, the stores in scope, and what the asker can
  physically know. Belief is never evidence of presence.
- **F4. Candidates stay id+name only.** No ownership either (owner can be as sensitive as
  location). Identification improves from F1/F2 and instructions, not from candidate provenance.
  (Provenance — an extra per-candidate field such as `in_hand`/`elsewhere` or `owner` — was
  considered and dropped as too complicated for now.)
- **F5. An out-of-scope `existing` stays an ordinary miss — no roll and no fall-through.** This
  is load-bearing: falling through to the odds path would duplicate the very item the oracle
  just identified elsewhere. Better identification, not a fallback, is the fix.
- **F6. Presence is judged, not conjured.** `impossible` is a valid answer; the asker expecting
  a thing does not make it be here. A thing stolen or hidden by another is not findable unless it
  is really here, and the odds of finding a concealed thing should be low. The judge is expected
  to reach that from the context (who could know, who was present, what the asker saw), not from
  a rule code enforces.
- **F7. The mock and mock days are unchanged in behaviour.** New fields are additive;
  `mock-finder` ignores them.

## The request, before and after

Today (`find.clj:100-117`):

```json
{ "place":   {"id","name","desc","aspects"},
  "stores":  [{"id","name","keeps","aspects","at"}],
  "what":    "day-book",
  "candidates": [{"id","name"}, ...] }
```

Proposed:

```json
{ "finder":  {"name": "Hale",
              "nature": "the earl's private secretary: exact, closemouthed...",
              "carrying": [{"id","name"}],
              "notes":  ["[r20] Day-book entry ruled and drying on the desk; his lordship asked me to bring the book to him to initial.",
                         "..."]},
  "recent":  ["[r21] Lord Claudeworth says: \"Hale, the day-book then - bring it here...\"",
              "[r21] Hale finishes keeping the study quiet... the day-book laid flat...", "..."],
  "place":   {"id","name","desc","aspects"},
  "here":    [{"id","name"}],
  "stores":  [{"id","name","keeps","aspects","at"}],
  "what":    "day-book",
  "candidates": [{"id","name"}, ...] }
```

Notes come last-15 like the agent prompt (`prompt.clj:186-202`); `recent` is roughly ten narrated
lines; `here` is the `look`-visible items at this place (not other rooms, and not closed-container
surprises, which deterministic searching already handles).

## Instruction changes

Added to `finder-instructions` (`find.clj:28-46`), in the same voice:

- *You are judging one place, for one person. The place entry, the stores in scope, and what that
  person can physically know are your evidence about what is here.*
- *Use the finder's nature, notes and recent lines to understand what they mean by `what` and
  what they could know. Asking is not evidence: a thing they believe is here is not therefore
  here. If their own account says it was taken, hidden, given away, used up or left elsewhere,
  it is not here.*
- *Candidates are ids and names only. Use them to recognise that `what` means a thing that
  already exists, so you never describe a second copy. You do not know where a candidate is;
  never state or imply it, and never let it make something be here.*
- *A candidate is the thing asked for only if it could be the same physical object. A different
  object that merely shares a name is not it: rate the odds and describe a new found item, or
  answer `impossible`.*
- *Work out whether such a thing is really here, not whether someone could have heard of it. A
  thing stolen or hidden by another is not found unless it is really here; the odds of coming on
  a concealed thing are low.*
- *Do not make a thing be here because the finder expects it or the moment wants it. Rate
  honestly, including `impossible`.*

## Engine work

1. **`find.clj/finder-request`** - add `:finder`, `:recent`, `:here`. Needs `actor` (already a
   parameter) and `items/held-by`, `items/by-name`, `space/present`, `w/agent`.
2. **A query-scoped recent window** - add `prompt/recent-for-actor [world events actor n]` (the
   actor's own view via `visible-events`, last roughly ten lines), and have `scene-recent` build
   on it. This needs the event log, which the request fn does not currently receive.
3. **One small contract extension** - `judges/adjudicate` calls `((:request spec) world actor
   args)`; extend to `(... args ctx)` with `ctx` = `{:events (run/events run) :round (:round
   world)}`. Three other request fns exist (`people/appearance`, `people/polish`,
   `shop/appraise`); update them and the `:adjudicators` contract in
   `scenario-authoring.md:225-240`.

Phase 1 below is world-only and needs none of item 3.

## Phasing

**Phase 1 - world-only context (no contract change).** Add `:finder` (name, persona, carrying,
notes) and `:here`, plus the instruction changes. Hale's own note ("Day-book entry ruled and
drying on the desk; his lordship asked me to bring the book to him to initial") is world data,
so this alone should very likely fix the day-book case. Mock-tested; then one short real run to
check both directions: the day-book is found, and an elsewhere item is still not duplicated.

**Phase 2 - the asker's recent lines.** Add items 2 and 3 above and `:recent`. Do this only if
Phase 1's real run shows identification still failing when notes are thin.

## Tests

- The request holds the asker's name, nature, carrying and notes, and the place's visible items.
- Actor A's notes and recent lines never appear in actor B's find request (privacy by
  construction).
- An out-of-scope `existing` still yields `:item/not-found` with `:existing`, no roll, no
  fall-through, and no aspect change.
- An in-place candidate still yields the local hit (deterministic path unchanged).
- `mock-finder`, the two 48-round Claudeworth mock days, replay-equals-live and the full suite
  are unchanged.
- No test asserts on instruction wording (structure only, per `AGENTS.md`).

## Cost

The current call is about 1.9k prompt tokens / $0.0004. Phase 1 adds persona, notes and `here` -
order 500-900 tokens. Phase 2 adds roughly ten narrated lines - order 400-600. Both stay well
under a tenth of a cent, and the system prompt is cacheable. No new calls.

## Invariants and risks

- **Privacy by construction.** The context is the asker's own view, so the oracle cannot know
  what another actor concealed unless that actor said it in front of them. The instruction rules
  are a second line of defence, not the first.
- **Anti-duplication preserved.** `existing`, candidates and the miss-on-existing stay. The new
  risk is the opposite one: with more context the oracle may be more willing to invent, so the
  "check the candidates first" instruction is essential.
- **Belief vs. fact.** Notes and recent lines are beliefs. The prompt says so, but a stubbornly
  deluded actor could still push the odds. This is an accepted LLM judgement, per the project's
  rule that a model judges prose.
- **Salience.** A more capable `find` is a more attractive tool; watch the `find`-per-turn counts
  against `stores-and-aspects-design.md:741` ("Risk: `find` gets too salient").

## Direction: things carried in prose, and promotion/demotion

The system should aspire to be fluid about where a thing lives. A thing may be present only as a
phrase in a room's, item's or person's aspects (as the day-book was: "day-book drying flat"),
and later be promoted to a real item; an item may be demoted back into an aspect or a store.
Promotion and demotion are already the design's words for the store<->item boundary
(`stores-and-aspects-design.md`, "The idea"), and in the long run these moves may be frequent.

Two consequences for this work:

- `find` reading the place's prose as evidence (F3, F6) is the flexible, model-judged half of
  that. It lets the engine treat an aspect phrase as a real, findable thing without code parsing
  it.
- The other half is the writer side. Today the work judge **cannot** promote a named, assumed
  household object like the day-book. Code would accept it: `judge-schema`'s `made` takes any
  well-formed entry, and `made-problem` checks only that `from` names a store in scope, `in`
  names a container among the reach, and aspect keys/values fit. But `judge-instructions` scopes
  `made` to things "really produced" by the activity or "fetched from a store", and separately
  says a particular thing that is neither in the scene nor carried "only such a thing doesn't
  exist" and must not be written as used or set out. So the day-book had nowhere to go but a room
  aspect. Loosening this - letting the judge make a named, plausible household object that the
  work really uses - is a separate decision for the work judge, not this document.

## Follow-ups (not this document)

- **The work judge promoting a named, assumed object.** The user wants this allowed; it is the
  root cause of the phantom day-book. It is a work-judge change (see Direction above). This
  document only makes the finder able to reason about such a phantom without lying about the
  world.
- **Mechanical concealment.** Should `find` be able to miss a thing that is here but concealed
  (locked box, buried)? Today deterministic matching searches closed containers here. Decision
  for now: leave the mechanism alone and let the judge's context keep the odds low for concealed
  things (F6); make it mechanical only if reviews show the judge is too generous.

## What to watch in the next real run

The finder should distinguish a same-named candidate elsewhere from the thing the asker means,
should find things its own notes and the place's prose attest to, should still miss things it
has no business knowing (a thing taken or hidden by another, a concealed thing), and should
produce no roll when it correctly identifies an elsewhere item.
