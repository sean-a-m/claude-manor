# Converse: conversations within a turn

**Status: built (V1-V7); the real run (V8) is below.** Read `AGENTS.md` first.

The user agreed the decisions below on 2026-10-17. They follow an investigation of the last real
Claudeworth run (`runs/claudeworth-20261017-120559`). With one utterance per agent per round, the
office talks about the drawing room took four rounds (09:00-10:30). There were 71 `pass` actions,
many of them waiting for a reply; 10 second utterances were rejected by the speech limit; and
about 49 thoughts reasoned around that limit ("I already used my say, so I must pass"). The
stage-2 research (`docs/turn-resolution-research.md`, worry 3) sketched a conversation occupation
for simultaneous rounds. This brief is simpler: it works within today's rounds, where agents act
one after another.

## Status

- [x] V1 eligibility and the `:asleep?` occupation flag
- [x] V2 `manor.conversation`: the actions of a speaking turn, events, narration, reducer
- [x] V3 engine: the `:converse` kind, the conversation loop, the speaking turn
- [x] V4 prompt for a speaking turn
- [x] V5 mock, transcript and llm.edn labels
- [x] V6 tests
- [x] V7 docs and Claudeworth's rules
- [ ] V8 one short real run, measured against the run above

## Decisions

1. **`converse {with?, text}` is free.** It doesn't end the initiator's turn: when the
   conversation is over, the initiator carries on with the turn and takes a turn-ending action,
   e.g. commission what was agreed or travel. `text` is the initiator's opening line.
2. **It uses nobody's turn.** Members still take their own turns this round, before or after it.
   Watch for "turn hoarding" (agents preferring talk to action); nothing limits it yet beyond
   decision 9.
3. **Who can be drawn in:** active agents in the initiator's place, at most 3 of them (4 members
   including the initiator). Not:
   - travellers (they aren't in the place anyway);
   - anyone asleep;
   - hibernating agents (out of play);
   - human seats (each line would block the conversation until the human answers). Humans can't
     start one either, for now.

   Busy agents who are awake (working, `spend-time`) can be drawn in, and **talking doesn't touch
   their occupation**: no interrupt, so their work goes on and ends when it was going to.
   (Being interrupted by `rouse` or `call` doesn't make work fail either: it gives the busy agent
   a turn where `carry-on` keeps going and any other action stops the work early, judged on the
   time worked. Converse avoids the question by not interrupting.)
4. **Who:** `with` is a list of ids. Leaving it out means everyone here who can be drawn in, if
   there are no more than 3; with more, the action is rejected and asks for up to 3 names.
5. **Turn order:** round-robin, the initiator first, then the members in the order given (or the
   spawn order, when `with` is left out). Three full passes; the initiator's opening line is
   their first turn of pass 1. After pass 3 the initiator has a **final word**.
6. **A speaking turn offers one tool to end it, plus `note`:**
   - members: `reply {text}`, `stay-silent`, `leave`;
   - the initiator: `reply {text}`, `end-conversation`; for the final word, `reply` or
     `end-conversation`.

   No other actions (decision 7 of the questions: speech and notes only).
7. **It ends** when the initiator ends it, when every other member has left, when every
   remaining member stays silent for a whole pass, after the final word, if the run's cost cap is
   reached (checked before each call), or if a call fails (the run is then asked to pause, as for
   a failed turn). It never carries over into the next round.
8. **Lines are told 400 characters.** The schema is `[:string {:min 1 :max 1500 :advised-max 400}]`:
   models are told 400, the hard limit stays 1500 as for `say`, and anything over 1500 is
   rejected with advice to shorten it to 400.
9. **One conversation per agent per round.** `converse` has `:per-turn 1` under its own limit
   key, so it's separate from `say`/`whisper`/`call` (an agent can say hello, then converse). As
   an agent takes at most one turn a round, that is already once a round; a reducer also records
   the round of the agent's last conversation and `:valid?` checks it, so the rule survives a
   custom `:schedule` or stage 2.
10. **Every scenario gets it** as a built-in; `:exclude-actions [:converse]` removes it. It isn't
    offered in a simultaneous group (`run-group!` with more than one agent), where the other
    members are in the middle of their own turns; that waits for stage 2.
11. **Nothing is replaced.** `say`, `whisper` and `call` stay as they are.
12. **Who hears:** each line is an ordinary `:agent/said`, heard by everyone in the place, as
    `say` is. Bystanders hear everything but can't speak in it. Private conversations are later
    work.

## Design

### Events (`manor.conversation`)

| Event | Visible to | Data | Reducer |
|---|---|---|---|
| `:conversation/started` | `space/here` | `{:id :members :at}` | records `[:agents actor :conversed-round]` |
| `:agent/said` | `space/here` | `{:text :at :conversation id}` | none (existing narration) |
| `:conversation/silent` | `space/here` | `{:id}` | none |
| `:conversation/left` | `space/here` | `{:id}` | none |
| `:conversation/ended` | `space/here` | `{:id :reason}` | none |

The id is `(str "conv-" (inc (:seq world)))`, like bridge request ids. Narration: "Lady
Claudeworth draws Lord Claudeworth and Hale into conversation.", "Hale says nothing.", "Hale
leaves the conversation.", "The conversation ends." Lines read as `say` does. Conversation state
(members, pass) lives only in the engine's loop; the events are the record. Replay calls no model.

### The speaking-turn actions

Engine-internal specs in `manor.conversation`, not in the run's action list, so they never appear
in a normal turn and scenarios can't override them. `reply` and `end-conversation` emit the line
(if any) as `:agent/said`; `stay-silent` and `leave` emit their events.

### Engine (`manor.engine`)

- **A new action kind, `:converse`**, with the hint "Free: does not end your turn; the others
  answer before it returns." `handle-call` sends it to `run-conversation!`, as it sends `:query`
  to `run-query!`. Its tool reply is the conversation's lines and how it ended, then "The
  conversation is over; carry on with your turn."
- **`run-conversation!`** emits `:conversation/started` and the opening line, then loops over the
  passes, calling `speaking-turn!` for each member in order, and applies decision 7.
- **`speaking-turn!`** reuses the turn machinery: `activate-llm` is split so its call loop takes
  the messages, the actions, the step cap and a reminder function. A speaking turn has at most 3
  calls and `:max-finals 1`; with no accepted action it counts as silent. It is not wrapped in
  `activate` (no `:action/forfeited`, no resume). Its commit applies the action **without
  `stop-interrupted!`**, and `note` goes through the same path, so an interrupted member who
  talks stays interrupted and still decides on their own turn.
- **Not offered** in a simultaneous group or to a human seat: `turn-actions` drops `:converse`
  actions from the tools when the turn says it is simultaneous or the seat is human.
- **Eligibility** is one function, `manor.conversation/reason-not-drawn-in`, used by `:valid?`
  and by the "everyone here" default. Asleep comes from a new occupation flag:
  `b/occupy` takes an optional `{:asleep? true}`, the reducer keeps it, and Claudeworth's `sleep`
  sets it (as does the test hamlet's). Claudeworth's `answers-calls?` keeps its own check on the
  activity text. Runs recorded before this have no flag, so a loaded old run would treat a
  sleeper as awake; acceptable.

### Prompt (`manor.prompt`)

`conversation-messages` builds a speaking turn's request: the role brief and persona, a
conversation preamble instead of "Each round you take exactly ONE turn-ending action" ("You're in
a conversation, outside your own turn; answer by calling exactly one of `reply`, `stay-silent` or
`leave`; talking doesn't use up your turn or stop what you're busy with"), the same view, notes
and recent events as a turn (the lines so far are among the events), and a closing block: who is
in it, who started it, pass n of 3 (or the final word), and, for a busy member, "you are busy:
polishing the silver until 11:00; talking doesn't stop it".

### Mock, transcript, llm.edn

- The mock LLM's context gets `:conversation {:id :initiator :members :pass :final?}`. Such
  calls go to the scenario's `:mock-conversation` if it has one; otherwise the initiator ends the
  conversation and members stay silent. (Mock policies written for turns would otherwise answer
  with `walk` and the like.)
- `record-call!` adds `:conversation {:id :pass}` to the llm.edn entry; the transcript labels
  those calls ("conversation conv-41, pass 2"). Usage counts under the speaking agent, as now.
- Claudeworth's mock day (and the test household's) gets one scripted conversation through
  `:mock-conversation`: at breakfast the countess draws the earl in, he answers once and she ends
  it, so the smoke tests exercise it.

## Tests

In `engine_test` (a test scenario with a small map) unless noted:

- a three-member conversation runs 3 passes and the final word, in order; the initiator then
  takes a turn-ending action in the same turn;
- the initiator ends it early; every member leaves; a silent pass ends it;
- eligibility: not here, asleep, hibernating, human seat, yourself, more than 3, a duplicate;
  "everyone here" with 0, 3 and 4 eligible;
- a busy member stays busy with the same `:until`; an interrupted member stays interrupted;
- bystanders see the lines, people elsewhere don't; `:conversation/*` events never reach anyone
  outside the place;
- a second conversation by the same agent in the round is rejected;
- `converse` isn't offered in a simultaneous group (the `prize` scenario);
- a line over 400 and under 1500 characters is accepted; over 1500 it is rejected with the 400
  advice;
- the cost cap ends a conversation; a failing call ends it and requests a pause;
- replay of the events reproduces the world; the Claudeworth mock day still replays.

## Docs

- `docs/scenario-authoring.md`: the `:converse` kind, the events, `:asleep?` on occupations,
  `:mock-conversation`, and that conversation actions can't be overridden.
- `DESIGN.md`: a row for conversations.
- `docs/claudeworth-design.md`: decision 36. Claudeworth's `rules` paragraph: one sentence on
  `converse`, next to the speech sentence.
- `docs/roadmap.md` B10: a note that within-turn conversations are done, separately from stage 2.

## Real run (V8)

One short run once the mock tests pass: Claudeworth from breakfast, at most 8 rounds,
`:max-cost` 0.20, kept in `old-test-runs/converse-real-1`. Measure, against the run above:
conversations started per round and by whom, lines per conversation and how they ended, `pass`
count, speech-limit rejections, rejected and error tool replies, cost, and wall time per round.
Read the lines for whether members answer each other or talk past each other, and for turn
hoarding.
