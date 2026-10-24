# Physical status: bound, gagged, knocked out, dead

Status: **phases 1–2 built and mock/offline-tested; phase 3 awaits the user's go-ahead.**
Written 2026-10-24 from the user's brief and their answers the same day (sections 11–12).
The core, senses, work judge, immediate `act-on` with transfers, and nightly recovery are built.
The burglary mock night proves binding and gagging, silver theft, seizure and recovery of the
jewels, coming to, an eased ankle with ropes left alone, stored-audience privacy and replay
equality. No real-model check has been run for this feature.

Independent-review fixes: deferred final actions recheck the live actor's eligibility before
applying or judging; bound hiring candidates and couriers stay put instead of departing;
completed-work results are sight-filtered, retaining the actor and only people with public
person-aspect or physical changes (not named context or private inner changes). Unconscious
non-actors still perceive nothing. Focused regressions check stored work audiences, next-prompt
privacy and exact replay. All three fixes passed the full mock/offline suite: 385 tests,
7629 assertions, 0 failures, 0 errors.

Where the build differs in detail: the event-making half (`change-events`, `interrupts`,
`comings-to`) is in `manor.mechanics.physical.changes`, because `space` and `builtins` need the
accessors and `physical` can't require them back; the impediments' shape is `items/Impediments`,
beside `Aspects`. Impediments change by their own `:person/impediments-changed`. `w/done-to`
(beside `perceived-by`) names who an event was done to, never dropped for lack of sight: so far
`:item/given`'s recipient; completed work also names its publicly changed people in `:on`.
The actor is never dropped, even unconscious (so someone coming to
sees it). `take` from the helpless also reaches into what they carry. Coming to lets the person
take a turn before a routine claims them, as a release does. A world file's dead agent takes
`:status :dead` with an optional `:died {:cause}`; consciousness may take `:minutes`. The
operator's `afflict!` interrupts on behalf of `:by`, by default the person themselves. The
shared rules paragraph leaves `act-on` to the scenario's rules (Claudeworth offers it) and
adds that the dead are marked `dead` and that their things can be taken, and a sentence on
what not seeing and not hearing stop; `walk`'s limit of 3 is told only in the rules and in its
rejection.

Speech, hearing and sight: the speaker isn't in their muffled noise's audience; their own words
carry `:muffled` and are narrated "(only muffled noises come out)", so they know. A muffled call
naming someone busy in the room still interrupts them, as a clear call would; a call never
interrupts someone deaf. Deaf people are refused `converse` (it stays offered), while the
speech-disabled aren't offered it. `conversation/speaking-actions` takes the world. Someone who
can't see keeps the exits and `last-seen` in their view, reasons don't list the items or people
here for them, and `parted` doesn't record a sighting by or of them in `last-seen` (nor for the
unconscious). `w/done-to` also names an `:item/taken`'s `:from-person`.

The immediate act uses a final action's `:judge-job` hook, sharing the work judge rather
than registering a separate query adjudicator. It spends the final action even if judgement
fails. In simultaneous groups it follows existing final-action semantics: proposals run
concurrently, then acts are checked and judged in schedule order; their result is in the
events rather than the already-returned proposal reply. Judged physical changes follow the
work or act event as the existing physical events, with their own stored audiences and
interrupts; inner changes remain GM-only. Answer-list bounds allow all faculties of the
worker and six named people, and transfers have no arbitrary item-count limit.
`act-on` is opt-in through `:items {:act-on? true}`, enabled in Claudeworth and its fixtures.
The steward's recovery events are GM-only and do not interrupt sleepers; only existing
impediment keys may be rephrased or cleared, with whether a phrase is milder left to its
judgement (no prose classifier). Judge/steward request projections include faculty `level`,
optional `cause` and `set_at` from `:since` using the existing short clock labels; integration
filled this missing duration context without changing agent-facing views or stored state.
The original mid-crime fixture stays unchanged: the integration test clears the butler's
initial bonds, hinders the footman's ankle and places the blind witness in the pantry through
engine events before running the deterministic burglary mock hooks.

## 1. What this adds

A first, mostly hard-coded layer of physical state on people, so the simulation can hold
murders, a cat burglar who ties up the butler and loots the silver, and a footman who stops
the burglar getting away with the jewels. It is not a combat system. What happens in a
struggle is the judge's decision, as now; this layer records the result in a form that code
enforces.

- **Fixed faculties with levels:** movement, grasp and speech (impeded or disabled), sight and
  hearing (disabled only, for now), and consciousness (disabled = unconscious). Each one is
  either normal (no entry) or set, with an optional short cause ("bound hand and foot with
  curtain cord").
- **Death:** a third agent status, `:dead`, beside `:active` and `:retired`. A dead person gets
  no turns and stays where they died, marked dead in everyone's view.
- **Impediments:** a new subcategory of person aspects for open, visible physical hindrances
  that only the judges interpret (a cut finger, a sprained wrist, a split lip).
- **An immediate act on another person,** judged at once inside the actor's turn, so a seized
  burglar is held before he can walk away.
- **The judges set and clear it:** the work judge (for `spend-time` and the new immediate act)
  may set any of it, including death; the nightly steward may clear or ease what a night would.
- **Code enforces the levels:** fewer walks and no travel, no picking things up, muffled or no
  speech, events not seen or heard, no turns.

## 2. What exists today

- **`spend-time` takes effect only when it ends.** The work judge runs at the start of the round
  the job finishes (at least half an hour later). The tool's doc says "What you do to someone
  takes effect only if they're still with you when you finish: they may walk away". That suits
  dressing someone's hair, but a tackle on a fleeing burglar can never land.
- **Adjudicated actions already run within a turn.** `find` is a `:query` with `:adjudicate`:
  `resolve/run-query!` calls the judge, emits `:on-result` events, and replies to the caller.
- **Movement in Claudeworth:** `walk` (within an area, `:kind :free`) can be used many times a
  turn; `travel` (to another area) ends the turn. The generic `move` is one per turn.
- **Agents have `:status :active | :retired`.** `w/active-agents` drives turns, `space/present`,
  conversations, whisper targets and hibernation, so anyone not `:active` drops out of all of them.
- **Items are held through `:holder`**, with no hands or slots. "Carrying" means anything on
  the person. There is no way to take something another person holds: only `give`.
- **Person aspects** (`person-aspects-design.md`): at most 4 keyed outward phrases, seen by
  everyone present, set by the work judge on the worker and the people named in `on`, and
  removed overnight by the steward from sleepers. The judge's instructions currently put
  "a visible injury" among them.
- **Inner state** (`inner-state-design.md`): private phrases including `body`, for physical
  things that don't show. Unchanged by this proposal.
- **Every event's audience is fixed when it's emitted** (`run/emit!` → `w/stamp`), mostly
  `space/here`: everyone at the place. Nothing considers whether they could see or hear it.
- **Code moves people without their choosing:** hibernation routines (`builtins/hibernations`
  emits `:agent/moved` to take a villager home), and later visits and hiring departures.
- **The GM is never used** in practice, so this design gives it nothing (section 13).

## 3. Data

```clojure
;; on an agent, beside :aspects and :inner
:physical {:movement      {:level :disabled :cause "bound hand and foot" :since 31}
           :speech        {:level :impeded  :cause "gagged with a napkin" :since 31}
           :consciousness {:level :disabled :cause "struck on the head" :since 31 :until 35}}
:impediments {:hand "a cut finger, bound in a rag"}   ; keyed phrases, aspect rules
:status :dead                                          ; with :died {:round 40 :cause "..."}
```

| Faculty | Levels | Enforced by code |
|---|---|---|
| `movement` | impeded, disabled | section 4.1 |
| `grasp` | impeded, disabled | section 4.2 |
| `speech` | impeded, disabled | section 4.3 |
| `sight` | disabled | section 4.4 |
| `hearing` | disabled | section 4.4 |
| `consciousness` | disabled (unconscious) | section 4.5 |

- No entry means normal. Setting a faculty to normal removes its entry.
- `:cause` is optional, at most 80 characters advised (200 allowed), as for aspects. The judge
  writes it; code never reads it.
- `:since` is the round it was set, so the steward can see how long it has lasted (shown as a
  short time, as inner state's `set_at` is).
- `:until` exists only for consciousness: the round they come to (section 4.5).
- Impediments follow the aspect rules (keys `[a-z-]`, phrases, removed by `""`), with their own
  cap of 3.
- World files may give an agent a starting `:physical`, `:impediments` or `:status :dead`, so
  test fixtures can start mid-crime. `worldfile/problems` checks their shape.

## 4. What each faculty does

### 4.1 Movement

- **Impeded:** at most 3 walks a turn, and no `travel` (Claudeworth) or `move` along a route
  with travel above 0 (generic). So they can get about the house but not down to the village.
  Engine-driven moves (routines) still apply.
- **Disabled:** no `walk`, `travel` or `move`. Code that moves people (hibernation, and later
  hiring and visit departures) leaves them where they are. Hibernation leaves them out entirely,
  so a bound villager isn't put "asleep at home" in the cellar.
- The walk limit needs `:per-turn` to accept a function of world and actor (now it's a
  number), or a check inside `walk` against the turn's uses. That's a small change in
  `manor.engine.turn`.

### 4.2 Grasp

- **Impeded:** nothing in code. The judge sees it and takes it into account.
- **Disabled:** no `take`, `give`, `put-in` or `open`. `put-down` stays (letting something
  fall). Nothing they hold is dropped. The actions' reasons say why ("your hands are bound").
- **Taking from others:** `take` also accepts an item held by someone here who is dead,
  unconscious or grasp-disabled. The event records whom it came from, and everyone who can see
  it sees it. From anyone else, it's the immediate act (section 5) and the judge decides.

### 4.3 Speech

- **Impeded:** `say`, `whisper`, `call` and conversation lines still take text, but only the
  speaker (and the GM, who sees every event) gets it. Everyone who would have heard it gets a
  separate muffled event instead ("Hale makes muffled noises", "... a muffled cry"). A muffled
  `call` is heard only in the room, not the whole house. A whisper's muffled noise reaches only
  its target.
- **Disabled:** `say`, `whisper`, `call` and `converse` are unavailable. They can still be drawn
  into someone else's conversation, but their speaking turns offer only `stay-silent` and
  `leave`.
- **Conversations with someone gagged:** the engine's loop (`manor.engine.talk`) emits their
  `reply` as muffled for the others. The conversation judge sees the muffled line, not the text.

### 4.4 Sight and hearing

Both are disabled only, for now (blindfolded, deafened by a blast). They filter what reaches the
person, through one hook in `run/emit!` (section 7).

- **Hearing disabled:** they don't get speech or muffled events. They can't start a
  conversation or be drawn into one, and can't be whispered to ("Hale can't hear you"). They
  can still speak.
- **Sight disabled:** they don't get events that are only seen (someone arriving, taking,
  giving, starting work, an act on someone else). Their view shows the place's name and what
  they carry, with "You can't see" in place of the people and items here. `look` and `open`
  are refused; `find` is allowed and its judge is told they can't see (section 12), and `take`
  is allowed for an id they know. They can still move as their movement allows, and still name
  people in an act or `spend-time` from memory; the judge decides how that goes.

### 4.5 Consciousness

Unconscious is its own state, not a kind of sleep.

- **No turns.** They are skipped as sleepers are, though nothing is shown as their occupation.
  They perceive nothing: the emit hook drops them from every audience.
- **Coming to:** the judge may give how long (`minutes`), which code turns into `:until`. At
  the start of that round the engine emits `:person/came-to`, as it releases occupations now.
  Without a time, it lasts until someone revives them with an act (the judge decides) or the
  steward judges they'd have come to by morning.
- **`rouse` doesn't work on them.** Reviving is an act ("splashing water on his face").
- **Implies the rest:** while unconscious, code treats movement, grasp and speech as disabled
  without changing their entries. Others may `take` from them.
- **When it starts mid-job,** their job ends early and is judged on the time worked, as when a
  worker is roused and stops.

### 4.6 Death

- **`:person/died {:person :cause}`** sets `:status :dead` and `:died`, clears any occupation,
  and ends a job early as unconsciousness does. Since they're no longer active, they drop out of
  turns, `present`, conversations, hibernation and the steward with no other change.
- **Others see a dead marker:** the people-here list includes dead people at the place with
  `dead: true` (their name, appearance and aspects as before, no `doing`). `look` shows the
  same. There's no separate "you find a body" event.
- **What they held stays held,** and others may `take` it (section 4.2).
- **Never reversed** in play. The operator can branch the run.
- **Later:** a body as an item that can be carried, hidden and found (section 13).

### 4.7 Visibility of statuses

Everyone present who can see sees every status and impediment, beside aspects in
`people-here` and `look` (e.g. `physical: {movement: "disabled: bound hand and foot"}`,
`impediments: {...}`). The person sees their own under `:you`, with a line in the rules saying
what each level stops them doing. The rejection reasons repeat it when they try.

### 4.8 Interrupts

When a status, impediment or consciousness change lands on someone who is busy (asleep,
working, hibernating), they are interrupted (`builtins/interrupt`) and get a turn to decide
whether to stop, as with `rouse`. The exceptions are a change that makes them unconscious or
dead (section 4.5, 4.6). It reuses existing code, so it's cheap.

## 5. The immediate act: `act-on`

A separate tool from `spend-time`, so models choose it by meaning rather than code guessing
from what they wrote:

> **act-on** — Do something to one or more people here, at once: take hold of them, stop
> them leaving, bind or gag them, strike them, search them, help them up, untie them, revive
> them. What happens is decided at once, before anyone else acts. Name the people with `on`
> (their ids) and anything you use with `with` (items here or carried). For longer care of
> someone (dressing her hair, nursing him), use `spend-time`.

- **Args:** `on` (1–3 ids of other people here, alive or dead), `with` (optional, up to 2
  items here or carried), `doing` (as `spend-time`'s).
- **Kind:** ends the turn (`:final`), with no occupation: a few minutes of the half-hour round.
  `spend-time`'s doc gets a pointer to it.
- **Checks are structural only:** the ids exist and are here, the actor is conscious and
  alive. Anyone may act with bound hands or a gag: the judge decides what they manage.
- **Judged at once,** like `find` (an `:adjudicate` action, which `resolve` already supports for
  queries; it needs to work for a turn-ending action too). The judge request is the work judge's,
  built from a job-like map: the actor, `on` as the judged people, `with` as focus, the place.
  The user message says "An immediate act, a few minutes", with no time worked. The luck roll is
  the same.
- **Transfers:** the act's answer may also move items held by the actor or the named people:
  `moved: [{item, to}]` with `to` a judged person's id or `"place"` (dropped here). This is
  how the jewels are wrenched from the burglar's hand. Only the act has it; `spend-time` still
  can't move things.
- **Events:** one `:person/acted-on {:on :with :doing :summary}`, seen by everyone here who
  can see, and always by the people acted on unless unconscious. It's followed by the same
  change data as work (aspects, impediments, physical, died, moved) and any interrupts. The
  actor's reply is the summary.
- **Cost:** about the price of one work judge call ($0.0005), only when someone uses it.

## 6. The judges

### 6.1 Work judge (for `spend-time` and `act-on`)

Its answer schema gains three optional lists, applied by the same reducer as the other changes
(`:item/work-done`, and `:person/acted-on` for the act):

- `physical: [{person, faculty, level, cause?, minutes?}]`: `level` is `normal`, `impeded` or
  `disabled`, checked against the faculty's allowed levels. `minutes` applies only to
  consciousness.
- `impediments: [{person, key, value}]`: as aspect changes, `""` to remove.
- `died: [{person, cause}]`.

The persons must be judged people (the worker or actor and those named), as for aspects. The
judge sees every person's statuses and impediments in `people`, so it knows a bound worker
can't polish the silver. Its instructions say what each level means, and that most work changes
none of it. A mishap may leave an impediment (a cut finger) on low luck. An act may set or
clear any faculty or kill, as the scene warrants.

"A visible injury" moves from the aspect guidance to impediments. Physical hindrances go
there, and how someone looks stays in aspects.

### 6.2 The nightly steward

Its candidates grow to include anyone at the place with statuses or impediments, asleep,
hibernating or awake. For them it may clear statuses and impediments or ease disabled to
impeded, as a night's passing would. It never sets or worsens anything, and never touches the
dead. Its instructions give the idea in its own terms: a twisted ankle may ease overnight, a
cut finger mends a little, ropes don't untie themselves, the unconscious may well come to. The
LLM decides; code checks only that each change clears or eases.

### 6.3 Conversation judge

Unchanged. Talk doesn't tie anyone up.

## 7. Perception: one hook at emit

Each event type may declare how it's perceived, with a multimethod beside `apply-event`:
`(defmethod w/perceived-by :agent/said [_] :hearing)`. The values are `:sight`, `:hearing`,
or nil (the default: no filtering). `run/emit!` then trims a set-valued `:visible-to` before
stamping:

- **Who is dropped:** recipients lacking the sense, and every unconscious recipient. The actor
  and the people an act was done to are never dropped for lack of sight.
- **Who is never trimmed:** `:all`, `:gm` and `:system` audiences.
- **Tagged in the first build:** speech and muffled events (`:hearing`); moves, departures and
  arrivals, item events, work starts and acts (`:sight`). Anything untagged reaches everyone
  as now. A missed tag means a blind person learns too much, which is harmless enough to fix
  when noticed.
- **Replay is unaffected,** because the trimmed audience is what's stored in `events.edn`.
  Invariant 3 still holds: prompts read only events visible to the agent.

## 8. What needs touching

| Where | Change |
|---|---|
| `world.clj` | `perceived-by` multimethod; `:dead` status helpers |
| new `mechanics/physical.clj` | faculties and levels, accessors (`can? world id :grasp`), events and reducers (`:person/physical-changed`, `:person/came-to`, `:person/died`), narration, the emit filter, the rules paragraph |
| `engine/run.clj` | apply the filter in `emit!` |
| `engine/round.clj` | coming to at round start, beside releases |
| `engine/turn.clj` | per-turn limits that depend on the actor |
| `engine/talk.clj`, `mechanics/conversation.clj` | muffled lines, speaking tools for disabled speech, hearing in `reason-not-drawn-in` |
| `mechanics/builtins.clj` | `say`/`whisper` muffling and checks; hibernation skips |
| `mechanics/space.clj`, `scenarios/claudeworth.clj` | `move`, `walk`, `travel`, `call` checks; dead people in `people-here` |
| `mechanics/items/actions.clj`, `views.clj` | grasp checks, `take` from the helpless, blind views, `act-on` |
| `mechanics/items/judge.clj`, `work.clj`, `steward.clj` | answer fields, checks, scene, steward easing |
| `io/worldfile.clj` | starting statuses |
| `dev/user.clj` | `afflict!` helper for the operator |
| `docs/scenario-authoring.md` | the contract: faculties, `act-on`, `perceived-by` |

## 9. Rules text (Claudeworth, draft)

> Your body can be hindered. Under `physical` you may see that your movement, grasp or speech
> is impeded or disabled, or that you can't see or hear. Impeded movement keeps you to a few
> steps within the house; disabled, you can't move from where you are. With your grasp
> disabled you can't pick up, hand over or put things in; your speech impeded, others hear
> only muffled noises; disabled, you can't speak at all. `impediments` are smaller hurts that
> make work harder. Others see all of these on you, and you on them. To do something to
> someone at once (stop them, take hold of them, free them), use `act-on`.

## 10. Edge cases

- **Act on someone asleep:** allowed. They're interrupted unless killed or knocked out.
- **Act on the dead:** allowed (searching, laying out). Only aspects, impediments and transfers
  apply.
- **Unconscious with a job:** the job ends early and is judged on the time worked.
- **Death mid-conversation:** the conversation loop drops them, as when someone leaves.
- **Bound and alone overnight:** the steward sees them. It may let them doze off or come to,
  but won't untie them.
- **A bound person's routine:** with movement disabled they're out of hibernation and get
  turns where they are. With it impeded, their routine moves them as usual.
- **A gagged person `pray`s:** unchanged. Prayer is silent.
- **Positions of the dead:** the post stays held. Vacancies, wages and mourning are deferred.

## 11. The user's decisions (2026-10-24)

1. A separate immediate tool for doing things to another person, which models pick by its
   description, not code guessing from what they wrote (section 5).
2. Death is `:status :dead` for now. A body as an item is a roadmap follow-up.
3. Unconsciousness is added, distinct from sleep.
4. The judges set statuses. GM tools are deferred: the GM isn't used in practice.
5. Recovery is by action or by the steward, which judges what can clear.
6. A status may carry a short cause phrase, written by the judge.
7. Open impediments live under person aspects as a new subcategory.
8. Disabled grasp blocks `take`, `give`, `put-in`, `open`, but drops nothing. Others may `take`
   from someone dead, unconscious or grasp-disabled; otherwise it's an act the judge decides.
9. Impeded speech is heard only as muffled noise, room only for calls, including in
   conversations. Only the GM (and the speaker) see the real words.
10. Disabled movement blocks engine-driven moves; impeded doesn't.
11. Bodies are shown with a dead marker only, with no discovery event.
12. Status changes interrupt busy people.
13. No guard rails or flags on death or harm. This isn't adversarial.
14. The work judge may add impediments from mishaps (cut fingers); that's where they belong.
15. Sight and hearing exist, disabled only. No dragging bodies or combat resolution beyond the
    judge.

## 12. Settled details (the user, 2026-10-24)

1. **Name:** `act-on`.
2. **The act ends the turn,** with no occupation.
3. **Deaf people** can't start a conversation or be drawn into one.
4. **Sight and hearing are shown to others** like every status.
5. **Impediment cap:** 3, separate from the 4 aspects.
6. **Blind people may `find`,** and its judge is told they can't see. `take` of things already
   in their reach is allowed too (they'd know the id from `find` or memory); `look` and `open`
   stay refused.

## 13. Deferred (roadmap follow-ups)

- **Bodies as items:** retire the dead agent and leave a body item that can be carried,
  hidden, found and given aspects.
- **GM tools** to set and clear statuses, kill and revive, once the GM is used.
- **Arrests:** a constable, a lockup, custody.
- **Impeded sight and hearing** (dim, half-deaf), if a scenario needs them.
- **The posts of the dead:** vacancies, wages, mourning, the household's reaction.
- **Dragging or carrying people.**
- **Combat resolution** beyond one judge call.

## 14. Phases and checks

**Phase 1: built and offline-tested.** Statuses and code effects, no judge: data, reducers,
world-file shape, the
`afflict!` helper, views and rules text, each faculty's code effects (sections 4.1–4.8), death,
coming to, `take` from the helpless, the emit filter and tags, hibernation skips, interrupts.

*Check:* a fixture world (`test/fixtures/burglary.edn`, with a small test scenario reusing
Claudeworth mechanics, as `household.clj` does) starting with a bound and gagged butler, a
blindfolded maid and a dead footman. Tests cover each faculty's rejections, muffled lines in
`say`, `call` and a conversation, trimmed audiences, `take` from the dead, a routine not moving
a bound villager, coming to on time, and replay equality.

**Phase 2: built and mock/offline-tested.** The judges and `act-on`: answer fields and checks,
`act-on` with its immediate
judgement and transfers, the steward's easing, the judge's instructions (impediments replace
"a visible injury"), mock hooks (`:mock-judge` returning physical changes), and
`scenario-authoring.md`.

*Check:* the fixture's mock night: the burglar ties up the butler (`act-on`), takes the silver,
the footman seizes the burglar and takes the jewels back (`act-on` with transfers), and someone
knocked out comes to. The steward eases a twisted ankle and leaves ropes alone.

*Outcome:* `test/manor/burglary_test.clj` runs the full mock night, checking both immediate
acts and the transfer, silver in the burglar's sack, the cook's timed return to consciousness,
ankle recovery before the footman's action, unchanged ropes and gag, no forfeits or rejected
replies, no muffled words in other prompts, no blind visual scene/act leaks, and equality of
live and stored-event replay. `physical_test.clj`, `immediate_act_test.clj` and
`physical_steward_test.clj` cover faculties, conversations, answer checks and failures,
simultaneous scheduling, interrupted jobs, private recovery and faculty timestamps. Burglary
is in the bundled scenario smoke tests. All checks are free, mock/offline only.
Validation: `clojure -M:test` — 379 tests, 7452 assertions, 0 failures, 0 errors.

**Phase 3: NOT RUN; requires the user's go-ahead. A short real run.** About 8–10 rounds of the fixture at
night, `:max-cost` 0.20. Read the transcript: does the judge use the fields sensibly, do models
pick `act-on` for acts and `spend-time` for longer care, do bound and gagged characters behave as
bound and gagged, and are there any leaks from the muffled text or blind views. Keep the run in
`old-test-runs/`.
