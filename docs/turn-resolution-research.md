# Research: simultaneous plans, interrupts and conversations in manor ("stage 2")

## Summary

Every system studied that keeps simultaneous moves deterministic does three things: it collects
orders on one snapshot, executes them in a fixed published order (priority, phases, registers), and
re-checks each order against the current state at the moment it executes. Under those rules,
"failed" and "interrupted" can be decided by code. A step **fails** when its precondition is false
when it is resolved. An agent is **interrupted** when an event *addressed to it*, or matching an
attention mask, lands before its remaining steps. Everything else the agent can see only
**affects** it. A judge is needed only where it already is: outcomes in free text (`work-on`).

For manor I recommend **optimistic planning on a private fork of the snapshot**. Each agent plans
against its own copy of the immutable world, so `walk` followed by `look` still works. A pure
resolver then replays all plans step by step, interleaved like Robo Rally registers, in a seeded
order. After that come at most two "react" passes, like Diplomacy's retreat phase, for agents
whose plans failed or who were addressed. Conversations start out as chains of react passes, and
in a second version become an engine-owned conversation occupation.

At 200 agents with most of them busy, keeping 30-minute rounds is the right choice. Manor already
has timers (`busy` until a round) and interrupts (`rouse`). What it lacks is an interrupt *mask*,
and a way to skip rounds in which nothing happens. A fully event-driven engine only pays off if
you need timing finer than 30 minutes.

---

## Findings

### How manor works today (read from the code)

1. **Claim:** Sequential groups apply free actions immediately (`resolve!`). Simultaneous groups
   (`run-group!` with more than one id) also apply *free* actions immediately to the live world
   while the turns run in parallel. Only turn-ending actions are deferred: they are checked during
   the turn with `a/check` on the live world, then re-checked and applied in schedule order after
   all futures finish. **Sources:** `src/manor/engine.clj` (`handle-call`, `run-group!`). **Support:**
   direct evidence. **Confidence:** high. This is the leak the user described: in a simultaneous
   group, one agent's free `take` or `say` can change the world mid-round, in an order that depends
   on thread timing. This is *researcher inference* from the code: it is safe for replay, because
   events are recorded, but it isn't reproducible run to run.
2. **Claim:** Occupations (`busy` until a round) and `rouse` → `:interrupted-by` → a `carry-on`
   choice are already a timer-plus-interrupt mechanism. `:hibernate` is an "out of play until
   someone comes near" mask. **Sources:** `DESIGN.md` (Occupation row). **Support:** direct evidence.
   **Confidence:** high.

### Board and war games

3. **Claim:** Diplomacy adjudication gives each order the state SUCCEEDS, FAILS or UNRESOLVED and
   resolves orders recursively through their dependencies. The hard cases are *cyclic*
   dependencies (circular moves, convoy paradoxes), which need guess-and-check plus a backup rule.
   Older adjudicators instead process orders "in a specific sequence", where "a move order succeeds
   until a reason is found for it to fail". **Sources:**
   [Kruijswijk, The Math of Adjudication ch. 3](https://diplom.org/Zine/S2009M/Kruijswijk/DipMath_Chp3.htm).
   **Support:** direct evidence. **Confidence:** high.
   *Inference for manor:* the cycles come entirely from orders whose success depends on other orders
   succeeding (support, convoy). If manor has no such orders (no "help Bob carry" that only works if
   Bob also acts), a single sequential pass with precondition re-checks is complete, and its cost
   grows linearly with the number of orders. Keep it that way; add dependent actions only knowingly.
4. **Claim:** In Diplomacy's retreat phase, only dislodged units order, and their options are
   restricted: no retreat to the attacker's origin or to a province left empty by a standoff, and a
   unit with no legal retreat is disbanded. **Sources:**
   [Diplomacy rulebook (via MIT ESP PDF)](https://esp.mit.edu/download/7123a4ae0bb98d30807251afea836f57/S1763_diplomacy_rulebook.pdf);
   only the search summary was read, not the PDF itself. **Support:** interpretation of the search
   summary, consistent with my own knowledge. **Confidence:** medium-high. *Inference:* this is the
   model for manor's react pass. Only affected agents act, with a narrow menu, and there is a default
   when they can't.
5. **Claim:** Robo Rally programs five registers simultaneously and secretly, then resolves them
   **one register at a time**. Within a register, robots act in priority order (distance to the
   priority antenna, with a clockwise tie-break), and after each register the board elements fire
   "in the order shown". Pushing and walls are resolved mechanically. A robot that reboots has "your
   programming … canceled for the round, and you may not complete remaining registers". A player
   who runs out of programming time gets random cards. **Sources:**
   [Robo Rally rules (WotC 2017 PDF)](https://media.wizards.com/2017/rules/roborally_rules.pdf).
   **Support:** direct evidence. **Confidence:** high. *Inference:* interleaving steps by register
   stops the first agent in the order from finishing its whole plan before anyone else moves. It
   also gives a natural "time within the round" for narration, and a clear rule for what happens to
   the rest of a plan after a failure (cancel it).
6. **Claim:** Star Fleet Battles: each player secretly allocates energy for the turn, then ships
   move during a 32-impulse chart in which speed decides which impulses a ship moves on.
   **Sources:** [Star Fleet Games](https://www.starfleetgames.com/starfleetbattles.shtml),
   [32-impulse chart](https://www.starfleetgames.com/sfb/sfin/32_Impulse_Chart.pdf); only the search
   summary was read. **Support:** interpretation. **Confidence:** medium. *Inference:* this is the
   register idea with different speeds, which matters only if manor actions get durations shorter
   than a round.
7. **Claim:** WeGo design advice: plot orders, give each order a limited fallback ("if blocked,
   stop"), compute simultaneous effects from one snapshot, and avoid "letting the first unit
   processed claim a space" unfairly. **Sources:** a search-provider synthesis citing
   [Matrix Games dev diary](https://www.matrixgames.com/news/wego-world-war-ii-overlord-dev-diary-1).
   **Support:** interpretation (a synthesized summary, not fetched). **Confidence:** low-medium.
8. **Claim:** D&D 5e "Ready": on your turn you name a *perceivable trigger* and a response. If the
   trigger happens before your next turn, you may use your reaction right after it finishes, or
   ignore it. You get one reaction per round. **Sources:**
   [5e SRD 5.1 PDF](https://media.wizards.com/2016/downloads/DND/SRD-OGL_V5.1.pdf); only the search
   summary was read. **Support:** interpretation of the summary of an official source, consistent with
   my own knowledge. **Confidence:** medium-high. *Inference:* the reaction comes *after* the trigger
   resolves and is capped per round. That matches the react pass, with a cap of one or two per agent.

### LLM agent simulations

9. **Claim:** Concordia has a Sequential engine ("one agent acts, then the GM resolves"; for
   conversations) and a Simultaneous engine ("all selected agents generate their actions in
   parallel… passes them *together* to the GM for batched resolution"). It also has Scenes, which
   switch engine and GM per phase (for example, a sequential meeting, then a simultaneous work day).
   **Sources:** [Concordia environment README](https://github.com/google-deepmind/concordia/blob/main/concordia/environment/README.md).
   **Support:** direct evidence. **Confidence:** high.
10. **Claim:** In Concordia's Simultaneous engine, the actions are joined into one string
    (`'\n'.join(actions.values())`) and resolved by **one GM LLM call** ("Because of all that came
    before, what happens next?"). Conflict resolution is delegated to the LLM. **Sources:**
    [simultaneous.py](https://github.com/google-deepmind/concordia/blob/main/concordia/environment/engines/simultaneous.py).
    **Support:** direct evidence. **Confidence:** high. *Inference:* this is the opposite of manor's
    principle that the engine owns state. Copy the batching, not the LLM resolver.
11. **Claim:** Concordia's interrupt-driven game master: "Each entity always has exactly one pending
    timer. Polling an entity (for any reason) clears its mask AND timer. Every entity response must
    provide a new mask and a new timer. Timer events are private… Non-timer events poll all entities
    whose mask matches." Masks are prefix lists over event tags (`[""]` matches everything, `[]`
    matches nothing). Entities can add tags (for example `food.ready`) to their actions, and the
    simulation "advances in simulated time, skipping dead air". The scripted default response of an
    empty mask and a one-hour timer is explained as "breaking the action-event cascade and allowing
    time to advance". **Sources:**
    [interrupt_driven.py](https://github.com/google-deepmind/concordia/blob/main/concordia/prefabs/game_master/interrupt_driven.py),
    [scheduling_examples.py](https://github.com/google-deepmind/concordia/blob/main/examples/interrupt_driven/scheduling_examples.py).
    **Support:** direct evidence. **Confidence:** high. *Inference:* the cascade risk is real. A
    reaction is an event, and that event can poll others, so a cap or a "match nothing" default is
    required.
12. **Claim:** In Stanford Generative Agents, at each time step the agent perceives and the model
    decides "whether the agent should continue with their existing plan, or react". If it reacts,
    "we regenerate the agent's existing plan starting from the time when the reaction takes place".
    Dialogue is generated utterance by utterance, each conditioned on memory and the dialogue so far,
    "until one of the two agents decides to end the dialogue". Plans are decomposed just in time,
    from hour-long chunks to 5–15-minute chunks. 25 agents for two days cost "thousands of dollars".
    The appendix suggests batching dialogue as a joint prompt, and invalidating only the parts of a
    plan that need it. **Sources:** [Park et al. 2023](https://arxiv.org/html/2304.03442).
    **Support:** direct evidence. **Confidence:** high. *Inference:* their "should I react?" call is
    an LLM call made on *every* observation, which is what makes it expensive. Manor should decide
    who reacts in code.
13. **Claim:** In AI Town, conversation membership states are `invited` → `walkingOver` →
    `participating`, driven by the inputs `startConversation`, `acceptInvite`, `rejectInvite` and
    `leaveConversation`. Conversations have exactly two members, and a player is in at most one
    conversation at a time. Turn-taking uses a typing "lock" (`setIsTyping`), an
    `AWKWARD_CONVERSATION_TIMEOUT` (speak if the other side is silent for too long), a
    `MESSAGE_COOLDOWN`, and a leave rule
    `tooLongDeadline < now || numMessages > MAX_CONVERSATION_MESSAGES`, where the maximum is 8.
    Agents accept invites from other agents with probability 0.8, there are per-partner cooldowns,
    an `INVITE_TIMEOUT` applies, and one LLM operation runs per agent at a time. The engine is
    single-threaded per world, and all changes are "inputs" that the engine validates. **Sources:**
    [ARCHITECTURE.md](https://github.com/a16z-infra/ai-town/blob/main/ARCHITECTURE.md),
    [constants.ts](https://github.com/a16z-infra/ai-town/blob/main/convex/constants.ts),
    [agent.ts](https://github.com/a16z-infra/ai-town/blob/main/convex/aiTown/agent.ts) (read through
    an extraction summary). **Support:** direct evidence. **Confidence:** high.
14. **Claim:** In Sotopia, agents "take turns (in a round-robin fashion)". The actions are speak,
    non-verbal, physical, `none` and `leave`, and "an episode ends either because one of the agents
    chooses to leave, or it reaches the limit of turns", which is set to 20. Only N=2 was tested.
    **Sources:** [Sotopia](https://arxiv.org/html/2310.11667v2). **Support:** direct evidence.
    **Confidence:** high.
15. **Claim:** AutoGen GroupChat selects the next speaker in one of four ways: `auto` (an LLM
    picks), `round_robin`, `random` or `manual`, with a separate `max_round` cap. **Sources:**
    [AutoGen groupchat reference](https://autogenhub.github.io/autogen/docs/reference/agentchat/groupchat/);
    only the search summary was read. **Support:** interpretation. **Confidence:** medium.
16. **Claim:** Project Sid's PIANO runs cognitive modules concurrently behind a coordinating
    controller. No turn or conversation mechanics specific enough to reuse were found. AgentSociety
    steps are increments of simulation time (for example `tick=3600`) and are not conversation turns.
    **Sources:** [Project Sid](https://arxiv.org/abs/2411.00114),
    [AgentSociety docs](https://agentsociety2.readthedocs.io/en/latest/interaction.html), both from
    search summaries only. **Support:** interpretation. **Confidence:** low.

### Discrete-event simulation, roguelikes, MUDs

17. **Claim:** OMNeT++ orders events with the same timestamp by "scheduling priority and then,
    insertion order". Other deterministic event queues (Agents.jl-style) break ties by a monotonic
    sequence number. **Sources:**
    [OMNeT++ cEvent](https://doc.omnetpp.org/omnetpp/api/classomnetpp_1_1cEvent.html),
    [rustsim event_queue](https://docs.rs/rustsim-core/latest/src/rustsim_core/event_queue.rs.html);
    only the search snippets were read. **Support:** direct evidence (quoted snippets).
    **Confidence:** medium-high.
18. **Claim:** In roguelike energy scheduling, actors gain energy at a rate set by their speed, act
    when they reach a threshold, and pay the action's cost. A priority queue is the common
    alternative. **Sources:** [RogueBasin Scheduling](https://www.roguebasin.com/index.php/Category%3AScheduling);
    only the search summary was read. The Cogmind time-systems article returned HTTP 403.
    **Support:** interpretation. **Confidence:** medium.
19. **Claim:** In MUDs, a "wait state" (lag) blocks some commands for a while but leaves others
    (who, chat, score) available. Delayed commands ("casting times") are stored with the wait state,
    so cancelling one cancels the other. **Sources:** [CircleMUD list](https://www.circlemud.org/maillist/1999-02/0700.html),
    [End of Time MUD](http://eotmud.com/gameplay.html); only the search snippets were read.
    **Support:** interpretation. **Confidence:** low-medium. *Inference:* this is the same idea as
    manor's split between free and turn-ending actions: being busy restricts which actions you can
    take, not whether you can perceive.

---

## Recommended design for manor

### Core idea: plan on a fork, resolve in code

This is optimistic concurrency control, a database technique and background knowledge here, not a
fetched source: execute on a snapshot, then validate at commit.

1. **Snapshot and seeded order.** At the start of the agent phase, take `snapshot` and emit
   `:round/order` (visible to the GM) with a permutation of the free agents drawn by `manor.rng`
   from the seed and the round. A fixed schedule order systematically favours whoever comes first.
   Seeded order is fair over time and reproducible.
2. **Planning call, run in parallel.** Each agent's existing tool loop runs against **its own
   fork**, an atom holding `snapshot`. Free actions apply to the fork, so `walk` then `look` returns
   what the destination looked like at the snapshot, and the turn-ending action is checked on the
   fork. Nothing touches the live world. The accepted calls become the plan: up to about 4 effect
   steps plus one final step. Read-only tools (`look`, query bridges) are not steps. Query-bridge
   events are recorded as today, and they are private to the caller. Tool replies for effect tools
   say "planned: happens when the round resolves" where honesty matters, for example for `take`.
   *This needs one new flag per action, `:read-only?` (or an `:effects` classification), and a fork
   to replace the live-world calls in `handle-call`.*
3. **Pure resolver.** `(resolve-plans world plans order) → {:events [...] :outcomes {id …}}`, a pure
   function that is easy to unit test. It interleaves the plans by register: step 1 of every agent
   in order, then step 2, and so on, with every final step in the last register. Within a register
   it sorts by a fixed **phase** and then by the seeded order. The phases are: speech (say/whisper,
   invitations), then hand-overs and item and money actions, then movement, then turn-ending
   actions. Speech before movement means "I tell Bob, then I leave" works in the same register,
   and so does Bob hearing it before he leaves. Each step is re-checked with `a/check` against the
   current world (the `:valid?` re-check in Robo Rally and Diplomacy style) and applied through the
   normal `action-events`. The engine emits the events in resolution order, so replay is exact as it
   is today. `:plan/submitted` (visible to the GM) records each plan in order, for the transcript.

### Worry 1: failed, interrupted, affected

All three can be decided mechanically:

| Outcome | Rule (code) | What happens |
|---|---|---|
| **Done** | precondition true, effect applied | nothing |
| **Failed** | `a/check` returns a reason at resolution time: the item is gone, too little money, the target isn't here, the route is closed | the step and **all later steps, including the final, are cancelled**, as in a Robo Rally reboot. Emit `:plan/step-failed` (visible to the actor) with the reason and *who caused it* (see worry 2). The agent is queued for a react call. |
| **Interrupted** | an event earlier in the resolution is **addressed to the agent** (it has `:to` containing the id: say-to, whisper, give, invite, rouse) **and** the agent's *attention* matches (below) | the agent's remaining steps are **suspended**, not cancelled. In the react call it can `carry-on` (resume the suspended steps, re-checked) or replace them. `:plan/interrupted` (visible to the actor). |
| **Affected** | anything else visible to the agent (someone arrived, room speech not addressed to it) | no call. It appears in the next prompt's events, or in the react prompt if the agent gets one anyway. |

**Attention without an LLM-chosen mask.** Concordia lets the model pick masks (finding 11). For a
cheap model, derive the mask from what the agent is doing:

- the final step is `pass` or a wait: attentive to everything addressed to it *and* to room speech
- the final step is work, walk or `spend-time`: addressed events only
- occupied or asleep: `rouse` only (today's rule)
- hibernating: proximity only (today's rule)

A scenario can override this with an `:attention` function. This keeps manor's present `rouse`
semantics and generalises them.

**A judge** is needed only where an outcome can't be written as a precondition: "did my speech
persuade him", "what did the cleaning do". Manor already routes those through `work-on` and the
judge. Don't add a judge to the resolver.

### Worry 2: detecting conflicts cheaply and deterministically

- **Correctness needs no conflict detection.** Sequential re-checking in a deterministic order
  catches everything. The first claimant wins, and the others fail their precondition (finding 3,
  the sequential approach; finding 5).
- **Explanations and fairness need claims.** Add an optional per-action
  `:claims (fn [world actor args] #{[:item id] [:money actor] [:agent target] [:stock shop item]})`.
  The built-ins can provide them (`take` → `[:item id]`, `buy` → money and stock, `say`/`whisper`
  with a `to` → `[:agent target]`, `travel` → `[:position actor]`). The resolver keeps a map
  `claim → first agent that changed it this round`. When a step fails, it looks up the failed
  step's claims and reports "Ann took the brass key first". That costs one map lookup and needs no
  LLM.
- **Cases:**
  - *Same item:* the first in phase and seeded order wins; the other fails, naming the winner.
  - *Same money:* money is the actor's own, so it only conflicts with the actor's own earlier steps
    and with shop stock, which works like an item.
  - *Same person:* two people address Bob in one register. Both utterances apply, and Bob is
    interrupted once, by both.
  - *Speech to someone who left:* the phase order puts speech before movement in the same register.
    If Bob left in an *earlier* register, `say-to Bob` fails ("Bob isn't here; he left for the
    kitchen"), which is visible because the departure was a visible event.
  - *Crossing moves:* no conflict, because the locations have no capacity. Add capacity
    preconditions only if a scenario needs them.
- **Dependent orders** ("help Bob lift the trunk") are the only source of Diplomacy-style cycles.
  Don't add them as plan steps. Model them as a joint `work-on` job that the second agent joins in
  a later register or round, so the resolver stays a single linear pass.

### Worry 3: conversations

**Version 1: conversations as react chains**, with no new state.

- `say` takes an optional `to` (a list of ids). Addressed speech interrupts the addressees (by the
  attention rule).
- **React pass:** after resolution, every agent that failed or was interrupted (in seeded order)
  gets **one short call**. The prompt is its normal view plus a block saying what it planned, what
  was done, what failed and why, and who said what to it. It returns a new short plan, at most 2
  steps plus a final, or `carry-on`. The plans are resolved by the same pure resolver. Utterances in
  pass 2 can interrupt others, who react in pass 3.
- **Cap:** 2 react passes per round (3 at most), and at most 2 react calls per agent per round. When
  the cap is reached, interrupted agents keep their suspended steps (an implicit `carry-on`), and
  agents whose plan failed get `pass` (or `carry-on` if they were busy).
- A ↔ B can manage A-says (pass 0), B-replies (pass 1), A-replies (pass 2) in one round: three
  utterances. A conversation that keeps going simply carries on in the next round, with the recent
  speech in the event window.
- **Who hears:** unchanged. `say` goes to `space/here` and `whisper` to its targets. Membership only
  decides who is *prompted*, never who *hears*, so private information stays per event.

**Version 2: a conversation occupation**, once version 1 shows the need. It follows AI Town and
Sotopia (findings 13 and 14).

- `talk-to {who [ids] topic}` is a turn-ending action. It creates `:conversation/started` with
  members `{initiator :participating, others :invited}` in one location. The invitees are
  interrupted. In the react call, `join-conversation` or `decline` replaces `acceptInvite` and
  `rejectInvite`; no `walkingOver` state is needed because they must be in the same place.
  `join-conversation` with an id lets a third person present join an existing conversation.
- **Within the round:** the members take turns in **round-robin, starting with the initiator**
  (Sotopia, AutoGen `round_robin`). Each call returns one utterance, plus `leave` or `stay`, and
  can't take any other effect action. Stop when everyone has spoken `k` times (say 3), when all but
  one have left, or when the round's budget is spent. The cost is `k × members` calls. Members are
  then occupied, with their final action `converse`, for the rest of the round. The conversation
  persists into the next round (`:conversation/continued`) until everyone leaves or a cap of about
  8–12 messages is reached (AI Town uses 8, Sotopia 20 turns), after which `:conversation/ended`.
- **Leaving** is an action. Walking away emits `:conversation/left` before the move, in the speech
  phase.
- *Cheap mode (optional, off by default):* Generative Agents suggests writing a dialogue in one joint
  prompt (finding 12). That would put two characters' private context in one prompt, so it breaks
  manor's information rule. Reject it, except perhaps for background NPCs who have no secrets.

**Cost per round for a pair of talkers:** version 1 is 2 planning calls plus up to 3 react calls,
about $0.01 at $0.002 per call. Version 2 with k=3 is 2 plus 6 calls.

### Worry 4: scale to about 200 agents; rounds or events

- **Cost is set by the number of free agents, not by how many agents exist.** Busy agents cost
  nothing today. If about 15–25% are free in a typical round (30–50 agents), a round costs 30–50
  planning calls plus react calls capped at about 2× the number of affected agents. That is roughly
  $0.10–0.20 per round, or $5–10 for a 48-round day. *This is a researcher estimate, not measured.*
  The resolver is pure code and costs nothing in comparison.
- **The bottleneck is concurrency limits on the provider.** Run the planning calls with a bounded
  pool (say 16–32). Collect every plan *before* resolving, and order them by `:round/order`, never
  by arrival, so a mock run is reproducible.
- **Locality:** conflicts and interrupts are almost always within one location. The resolver can
  stay global, because it is cheap. Locality matters for *prompts*: keep `observe` limited to what
  is here.
- **Skip dead air:** when nobody is free, nobody is due and no interrupt is pending, jump straight to
  the next due round with the clock events, and make no LLM calls. This is Concordia's "skipping dead
  air" (finding 11), inside the round model.
- **When to go fully event-driven** (a priority queue of timers and masks, polls with no rounds;
  findings 11 and 17): when the activities you care about are much shorter than 30 minutes (a
  10-second argument, a duel), or when durations differ so much that rounds waste calls even with
  skipping. Event-driven processing serialises events, so simultaneous conflicts mostly go away. The
  costs are a cascade problem (finding 11), tie-breaking rules (timestamp, then priority, then
  sequence number), and less natural parallelism: Concordia batches the entities polled at the same
  timestamp, which is a round again. Manor is already "event-driven quantised to 30 minutes", with
  occupations as timers and `rouse` as an interrupt. **Recommendation:** keep rounds, add the
  attention rule and dead-air skipping, and revisit only if conversations need finer time than
  "three exchanges per half hour".

### Smallest first version worth building

1. Add `:read-only?` on actions, and an agent fork for planning (simultaneous mode only; keep the
   sequential mode as it is).
2. `manor.resolve/resolve-plans`, pure: registers, phases, seeded order, re-check, outcomes
   (done, failed with the cause, interrupted), with `:claims` on 4–5 built-ins for cause messages.
3. Add an optional `to` on `say`. Implement the attention rule derived from the final action.
4. One react pass (cap 1 per agent), with an outcome block in the prompt. Then raise it to 2 passes.
5. Events: `:round/order`, `:plan/submitted`, `:plan/step-failed`, `:plan/interrupted`,
   `:plan/resumed`, each with `describe`.
6. Tests: the resolver on fixtures (two agents take one item; speech then leave; interrupt then
   `carry-on`), replay equality, and a mock run that gives the same events twice.

Version 2 conversations, scenario `:attention` overrides and dead-air skipping come after.

## Open questions for the user

1. Interleave plans by register (fairer, more "simultaneous") or run each plan whole in seeded order
   (simpler, closer to today)?
2. Seeded random order per round, or a scenario-defined initiative (status, speed)?
3. When a step fails, should the final still be tried? (Recommended: no; react or default instead.)
4. React caps: passes per round (2 or 3)? Calls per agent per round?
5. Should *room* speech (not addressed to anyone) ever interrupt, for example agents who are
   `pass`-waiting?
6. Should conversations have more than 2 people in version 2? AI Town and Sotopia only tested 2.
7. Should times within a round ("09:10") be shown to agents per register, or only the round time?

## Risks

- **Stale plans make failures pile up, and failures cost react calls.** Mitigate with short plans
  (at most 4 steps), caps, and cause messages so that the models self-correct.
- **The fork shows the snapshot, not the live world.** `look` after `walk` shows who was at the
  destination at the start of the round. That is acceptable and explainable, but say so in the
  prompt.
- **Parallel planning loses within-round reactivity** that sequential mode has today (B sees what A
  just did). React passes recover only part of it. Keep the sequential schedule available per
  scenario.
- **Interrupt storms** in crowded rooms (a dinner party): the caps and the attention rule limit them,
  but expect long react prompts there.
- **Prompt clarity:** the cheap model must understand "planned, not done yet". Real-run checks
  (short, capped) are needed before trusting it.
- Several game-rule sources (Star Fleet Battles, WeGo, roguelike, MUD, AutoGen, Project Sid) were
  read only through search summaries. They were used as analogies, not for decisions.

## Contradictions

- Concordia resolves simultaneous actions with an LLM GM (finding 10), while manor and AI Town
  validate inputs in code (finding 13). This is a design difference, not a factual conflict; this
  brief follows manor's principle.
- Generative Agents decides whether to react with an LLM call per observation (finding 12), while
  Concordia's interrupt GM decides by tag masks (finding 11). The brief recommends masks derived in
  code.

## Missing evidence

- No measured numbers on how often snapshot plans fail in LLM sims, so the react-call cost estimate
  is unvalidated.
- Robo Rally's numbered card priorities (older editions) weren't verified; the 2016/2017 rules use
  distance to the antenna.
- The Cogmind/Grid Sage time-systems article was unavailable (HTTP 403).
- No concrete multi-party (3+) turn-taking rules found in an LLM sim beyond AutoGen's speaker modes.

## Sources

- Kept: Concordia environment README, `simultaneous.py`, `interrupt_driven.py`,
  `scheduling_examples.py` (github.com/google-deepmind/concordia): primary code for both engines and
  the masks and timers.
- Kept: AI Town `ARCHITECTURE.md`, `constants.ts`, `agent.ts` (github.com/a16z-infra/ai-town):
  primary code for the conversation state machine and caps.
- Kept: Generative Agents (arxiv.org/html/2304.03442): primary on reaction, replanning and dialogue.
- Kept: Sotopia (arxiv.org/html/2310.11667v2): primary on round-robin turns, leave and a 20-turn cap.
- Kept: Kruijswijk, Math of Adjudication ch. 3 (diplom.org): primary on order states, sequential
  resolution and cycles.
- Kept: Robo Rally rules 2017 (media.wizards.com): primary on registers, priority and cancellation.
- Kept, read only as summaries: D&D 5e SRD (Ready), Diplomacy rulebook (retreats), OMNeT++ tie-break
  docs.
- Deprioritized: the WeGo synthesis, Star Fleet Battles, RogueBasin, MUD forums, AutoGen and Project
  Sid/AgentSociety. These were search summaries or analogies only.
- Rejected: Grid Sage blog (HTTP 403); d20srd page (HTTP 404).

## Next steps

- Prototype `resolve-plans` on fixtures with the mock LLM. It is free and settles open questions 1
  and 3 quickly.
- One short real run (at most 8 rounds, `:max-cost` 0.20) in a crowded location to measure the
  failure and react-call rate, if the user approves.

---

**Budget used:** 15 search queries, 1 of them wasted on a mis-aimed query about the local code.
About 14 URL fetches, 2 of which failed. No `source_check` calls.
