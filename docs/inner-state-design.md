# Inner state: how characters are inside

Status: **all three phases built** (data and the work judge, the conversation judge, then
the nightly steward), **mock-tested only**: no real run yet. The next check is a short real
Claudeworth run, with the user's go-ahead. The user's decisions of 2026-10-22 are in section
4; the defaults at its end were taken as written. Follow-ups go to the roadmap (B13).

Where phase 1's build differs in detail, or fills a gap:

- The checks get the scenario's `:inner` at call time (`item-judge/judgement`'s fourth
  argument, from the run's scenario), not from the world: the reducer needs no configuration,
  so replay is unaffected. `:open` defaults to 2 when the entry leaves it out.
- World files are checked for shape only (keys and values), since `worldfile/problems` has no
  scenario; the engine checks the open keys against the scenario's `:inner` when the run
  starts, and refuses a starting `:inner` in a scenario without one.
- Starting entries count as set in the round the agent is spawned (0 at the start), so the
  judge shows them as set at the clock's start.
- The judge sees each person's `inner` as `{key: {value, set_at}}` (an empty fixed key as
  `{value: ""}`); `set_at` is a short time ("18:30", or "Sun 07:00" on another day). The
  extra `inner`, `temperament`, `persona` and `situation` entries, the instruction section and
  the tool's `inner` field are all left out when the scenario has no `:inner`, so other
  scenarios' judge requests are unchanged.
- The narration is GM-only: `Lady Ashby, inside (job job-12): mood "pleased with Dawson's
  work"; body cleared.` `map.edn` is now also rewritten after `person/*` events, so the later
  phases' changes export too.
- Claudeworth's rules already have the whole 5.5 paragraph, including "what's said to you and
  how you sleep", though until phase 3 only work and conversations change it.

Where phase 2's build (`manor.mechanics.conversation.judge`, called from
`manor.engine.talk/run-conversation!`) differs in detail, or fills a gap:

- Besides an ending at the cost limit or on an error, the call is also skipped when the run's
  cost limit has been reached by the time the conversation ends, so it never goes over the cap.
- `:conversation/judged {:about id :summary}` and, when the answer fails (two bad answers or a
  failed call), an operator-only `:conversation/judge-failed {:about id :reason}` hold the
  conversation's id as `:about`, not `:conversation`: only the conversation's own events, which
  everyone present saw, carry `:conversation` (`conversation/of`). Neither has a reducer.
- Each line is `{person, says}` or `{person, does}` ("says nothing", "leaves the conversation,
  staying within hearing"), with `heard_by` (member ids) only when not every member heard it.
  The request has no setting brief: the work judge's `:judge-brief` is about work.
- The instructions also say to change only what the conversation would and leave the rest
  (as the work judge's do), and that the summary is for the record, read by nobody in the
  simulation. They reuse `inner/key-instructions` for the keys and the open-key cap.
- The mock hook is the scenario's `:mock-conversation-judge` (context `:conversation` and
  `:lines`); the default, `conversation-judge/mock-judge`, changes nothing. The dressing
  fixture's leaves each member who heard someone else's line turning over what they said (the
  open key `mind`), and its mock day has Lady Ashby ask Dawson about her silk at 20:00.
- `cache-report` counts the calls as `:adjudicator`, with the steward's.

Where phase 3's build (`manor.mechanics.items.steward`, called from
`manor.engine.work/run-steward!`) differs in detail, or fills a gap:

- Without the scenario's `:inner` the sleepers are as before (only those with aspects), and
  the request, tool and instructions are unchanged.
- The world doesn't record when someone fell asleep (`:busy` has only `:until`), so
  `went_to_sleep` is the round of the person's last `:agent/occupied` asleep in the run's
  events (`item-steward/asleep-since`), left out when there's none; no world-shape change.
  `wakes` is their `:busy :until`. Both are short times ("Sat 22:30", "07:30").
- The tool's `inner` list comes from the work judge's answer schema (`item-judge/answer-schema`,
  now public, without `made`), and is checked with `item-judge/with-inner` (now public), so
  `inner/checked-changes` runs with the place's sleepers as the judged people.
- The instructions name `went_to_sleep` and `wakes`, ask it to update inner state to take the
  night's passing into account (some things change over a night and some don't, as it
  judges), say an empty entry and an unchanged person are normal, and say nothing about
  fading or rest. (Revised after the first real run, `old-test-runs/claudeworth-inner-state-evening`:
  asked to judge "how each will wake", the steward filled every empty key, mostly with waking
  stiffness that would last all day.)
- People hibernating (Claudeworth's villagers on their routines, who are marked asleep without
  `:asleep?`) are seen too, with `doing` and `since` instead of `went_to_sleep` and `wakes`
  (`item-steward/occupied-since` replaced `asleep-since`). Added after the same run, where the
  steward missed every villager.
- The inner changes follow each place's `:items/stewarded`, so they're emitted place by place.
- In a 48-round mock Claudeworth day the steward made 5 calls (it made none before: no
  sleeper had aspects and nothing was idle), one per occupied bedroom. Claudeworth starts
  with 8 characters, so it's at most 8 a night until someone is hired, not about 10; Mrs
  Finch and Daisy never sleep in the mock day.

## 1. The want

Every character should have an inner state that only they can see: their spirits, how their
body is, what's on their mind, and anything else about them that doesn't show. It changes
with what happens to them: half a day at the copper leaves the laundress aching; a rebuke from
her ladyship at breakfast leaves Nellie stung and anxious; a night's sleep may ease the body
and leave the worry. The character reads it every turn and lets it colour what they do. Nobody
else sees it unless the character shows it or says it.

What it holds is deliberately left open, to see how the judges use it: moods and feelings,
but also physical things that don't show (a headache, a cough coming on, an old knee that's
playing up). It replaces the roadmap's "Private states" follow-up (B12). The older plans for
hunger and fatigue as meters (`claudeworth-design.md` phase 3, `stores-and-aspects-design.md`
D9) came from when the simulation centred on provisions and other concrete things. They are
put aside: agents already sleep and eat without being pushed to, and incentives aren't wanted
now.

## 2. What exists today

- **Person aspects** (`person-aspects-design.md`, roadmap B12): up to 4 keyed phrases under
  `[:agents id :aspects]`, outward only ("Never moods or feelings" in the judge's
  instructions), seen by everyone present. They're changed by the work judge (the worker and
  the people named in `spend-time`'s `on`) and removed overnight by the steward from sleepers
  who have any.
- **Fixed traits:** temperament (Big Five, `:low :average :high`) and faculties, private to the
  person, shown in their system prompt under "Your nature". `:appearance` and `:situation`
  never change.
- **No inner state anywhere** except the village example's private hunger counter.
- **Conversations** run inside the initiator's turn (`manor.engine.talk/run-conversation!`),
  with `:conversation/started`, `:agent/said` lines and `:conversation/ended`, all visible to
  everyone at the place. Nothing judges them afterwards.

### What the judges cost now

From every real run's `llm.edn` (about 32,000 calls), with latency from OpenRouter's
generation records for 120 recent judge calls, 18 steward calls and 60 agent turns:

| | per call | latency (median / p90) | share of a run |
|---|---|---|---|
| Work judge | $0.00047 (about 2.9k prompt tokens, half cached; about 330 out) | 1.6 s / 2.1 s | 8–10% (about 5 calls a round) |
| Nightly steward | $0.00019 | 1.2 s / 1.7 s | negligible |
| Agent turn, for comparison | $0.00050 | 1.6 s / 2.3 s | most of the run |

A Claudeworth round now costs about $0.027 and takes 55–60 s. Independent jobs are judged
concurrently as a round starts, so the judges add about one call's latency to a round. Rounds
average about 2 conversations of about 4.6 speaking calls each. Each extra call a round adds
about 2% to cost.

## 3. Options considered

Who changes inner state:

- **A. The existing judges only:** the work judge after `spend-time`, and the nightly steward,
  which now sets sleepers' inner state and doesn't only remove. Leaves conversations, the most
  frequent cause, unjudged until the person next works or sleeps.
- **B. A, plus a judge at the end of every conversation:** one call judges every member from
  what was said. Everyone in it heard it, so it opens no side channel. **Chosen.**
- **C. A, plus a periodic reflection per agent** over their own visible events, a few times a
  day. Catches what A and B miss (overheard remarks, news, waiting, people who never sleep) but
  lags. **Roadmap only**, in case B isn't enough; it would share a call with B2's rolling
  memory.

Also considered and rejected: meters or enumerated levels (hunger 0–3, mood "low"), which code
could read but which flatten a person into a scale; and letting characters set their own
inner state, which would make it theirs instead of the engine's.

## 4. Decisions (the user, 2026-10-22)

1. **No meters or levels.** Inner state is short phrases, judged by a model. Code checks only
   their structure (keys, lengths, counts, ids); whether the wording fits is the model's job,
   never a phrase or pattern check.
2. **Generic, not "feelings".** It's the person's inner state, whatever doesn't show: moods,
   preoccupations, and physical things nobody else can see. Its scope stays open until the
   judges have been seen using it.
3. **Fixed keys plus a few open ones.** The scenario names keys every character always has;
   the judges may add up to 2 open keys of their own, as for aspects.
4. **Private.** Only the person sees their inner state, and the GM, the operator and the
   judges.
5. **Changed by the judges,** in three phases: the work judge, a new conversation judge, then
   the nightly steward. Never by the person.
6. **No automatic fading.** When each entry was set is recorded and shown to the judges, but
   no rule in code or the instructions makes anything wear off. Whether it does is the judge's
   or the steward's call.
7. **No "improve my state" action.** Characters act on their inner state through the actions
   they already have, or don't. Whether they do (resting, seeking company, keeping away from
   someone) is to be investigated (roadmap B13), and is the hoped-for result.
8. **How people regard each other is separate and later.** An entry's text may mention a
   person ("stung by her ladyship's rebuke"), but there's no per-person structure now.
9. **The judges see everything** about the people in their scene for now: inner state,
   aspects, temperament, persona and situation.
10. **Periodic reflection (option C) is on the roadmap,** for when A and B leave gaps.
11. **No special guidance for serious harm.** If a judge decides something grave happened, it
    stands; it's revisited only if it causes problems in the simulation (roadmap B13).

Defaults proposed where nothing was decided, to confirm when the build starts:

- **The name is `inner`:** `[:agents id :inner]`, a map of keyed phrases like aspects, called
  the person's inner state in prose. Each entry's round is kept in `[:agents id :inner-at]`.
- **Three fixed keys,** set by the scenario with a gloss each. For Claudeworth:
  - `mood`: their spirits and emotions now ("low, stung by her ladyship's rebuke");
  - `body`: how their body is, as only they know it ("footsore after the walk to the
    abbey", "a dull headache since noon");
  - `mind`: what they're preoccupied with, anticipating or dreading ("turning over Mr Pugh's
    estimate").
- **At most 2 open keys** besides the fixed ones, with values like aspects': advised 80
  characters, capped at 200. A fixed key may be empty, and the judges may empty it again; an
  open key is removed when emptied. The judges are always shown every fixed key, empty or
  not, so they consider each; the person's view shows only the entries with a value.
- **Changes are GM-only events.** The person sees their current inner state in their view
  every turn, but gets no line in their event window, so the window isn't crowded and nobody
  else's narration is touched.
- **The work judge changes only the people it already judges** (the worker, and the people
  named who are there at the end), though it sees everyone's inner state.
- **The conversation judge changes only the members** (including those who left part-way),
  not people who overheard.
- **The steward sees every sleeper**, not only those with aspects, without recent lines.
- **Starting values are empty.** There are no defaults: world files and `:init` specs may give
  `:inner` (a character who starts the run unsettled, say), and anything left out starts
  empty. People made during a run start empty.

## 5. The build

### 5.1 Data, views and events

- A new mechanics namespace, `manor.mechanics.inner`: the schema and checks (open-key count,
  key format, value lengths), accessors, the event, its reducer and its narration. Opt-in
  with a scenario `:inner` entry:

  ```clojure
  :inner {:keys {:mood "their spirits and emotions now"
                 :body "how their body is, as only they know it"
                 :mind "what they're preoccupied with"}
          :open 2}
  ```

- World files and `:init` agents may give `:inner`; `worldfile/problems` checks it and
  `map.edn` exports it. Nothing is filled in at the start: a fixed key with no value is
  simply empty.
- The event: `{:type :person/inner-changed :visible-to :gm :data {:person id :source src
  :changes [{:key k :from v :to v'}]}}`, where `src` is `{:job id}`, `{:conversation id}` or
  `{:steward day}`. Its reducer sets `:inner` and `:inner-at`, and is pure. One event per
  person per verdict, so the transcript reads by person.
- **Views:** `prompt/agent-view` adds `:you :inner`. It's in the volatile end of the request,
  so the cached prefix is unchanged. Inner state never goes in `space/person`, `people-here`,
  `look`, or any other agent's request. The default GM view's agents include it; each person's
  panel page (`/agent/<id>`) and the transcript show it.
- **Privacy is enforced by where inner state is put,** not by checking text. The one leak path
  is a judge's *public* summary (the work judge's summary is read by everyone present), which
  the instructions address (5.2) and transcript review checks.

### 5.2 The work judge

- The scene's people entries, for everyone present, gain `inner` (with when each entry was
  set), and the judged people gain `temperament`, `persona` and `situation`. The prompt grows
  by a few hundred tokens.
- The `judge` tool gets a separate `inner: [{person, key, value}]` list beside `changes`.
  Keeping them apart lets the engine route them to separate events (`changes` stay in the
  public `:item/work-done`, inner state goes to the private `:person/inner-changed`).
- Checks (`checked-verdict`): the person is a judged person, the key is fixed or a well-formed
  open key within the count, and the value's length fits (empty clears it). A bad answer is
  sent back once, as now.
- New lines in the instructions, in this sense:
  > A person's `inner` state is how they are inside, which only they know: their spirits, how
  > their body is, what's on their mind, and anything else about them that doesn't show. Each
  > entry says when it was set. Change it only where the activity would, and leave the rest.
  > Their temperament says how they're inclined to take things; it informs your judgement but
  > doesn't decide it. The summary is read by everyone present: say only what they could see
  > or hear, never anyone's inner state.
- The mock judge leaves inner state alone, so existing mock days don't change; the fixture's
  mock judge sets an entry.

### 5.3 The conversation judge

- **When:** in `run-conversation!`, after `:conversation/ended` and before the initiator's
  summary is returned, so the initiator's next step and everyone's later turns see the result.
  It runs on the initiator's seat thread, adding about 1.6 s to that thread. Skipped when the
  conversation ended at the cost limit or on an error; a silent one is still judged (nobody
  answering can sting).
- **The request** (a narrow call through `judges/ask-structured!`, recorded as agent
  `"conversation-judge"`): the place and time; each member's name, persona, situation,
  temperament, appearance, aspects and inner state (with when each entry was set); and the
  lines in order (`:agent/said`, `:conversation/left` and `:conversation/silent`), each with
  who heard it when not everyone did, so a later aside or whisper only changes those who heard
  it.
- **The tool** `react {inner [{person, key, value}], summary}`: the summary is at most 300
  characters, for the GM only. The checks are the work judge's inner-state checks, with the
  members as the judged people.
- **Instructions,** in this sense: an empty answer is normal. Judge each member from what they
  said and heard, given their temperament and how they were before.
- **Result:** one `:person/inner-changed` per changed member, with `{:conversation id}` as
  source, and the summary on a GM-only `:conversation/judged` event for the transcript. No
  member is told.
- **Concurrency:** a member is in one conversation at a time, and work judges and the steward
  run as the round starts, outside seats, so nothing else changes a member's inner state while
  this call runs. A change is "set this key to this value"; a stale `from` is harmless.
- **The default mock** changes nothing; the fixture's mock sets an entry.
- **Cost:** a prompt of about 3k tokens (the instructions cached), about $0.0005 a call; at
  about 2 conversations a round, about $0.001 a round, **4–5% of a run.**

### 5.4 The nightly steward

- The sleepers become every active agent asleep at the steward's time, not only those with
  aspects. A place with sleepers gets a call, as now: in Claudeworth one per occupied
  bedroom, at most 8 a night with its starting characters (5 in a mock day), so about
  $0.001–0.002 a night.
- Each sleeper also shows their inner state (with when each entry was set), temperament, and
  when they went to sleep and will wake.
- The steward may now set, change or clear a sleeper's inner state, judging how they'll
  wake. Outward aspects stay remove-only.
- Inner changes go out as `:person/inner-changed` with `{:steward day}` as source;
  `:items/stewarded` keeps the outward changes.
- Known gaps, as for aspects: someone who sleeps after the steward's time isn't seen that
  night, and someone who never sleeps isn't seen at all (C would cover it).

### 5.5 What agents are told

In the scenario's rules (Claudeworth's `rules`) and in `scenario-authoring.md`'s guidance for
briefs, in this sense:

> Under `you`, `inner` says how you are inside now: your spirits, how your body is, what's on
> your mind. Nobody else can see it unless you show or say so. It changes with what you do,
> what's said to you and how you sleep. Let it colour what you do and say, as anyone's would.

Nothing invites them to manage or improve their inner state: decision 7 wants to see whether
they do it unprompted.

### 5.6 Fixture and tests

Reuse the person-aspects fixture (`test/fixtures/dressing.edn`, `dressing.clj`): give its
agents inner state (one starting with an open key), the scenario an `:inner` entry, and its
mock judge, mock steward and mock conversation judge inner changes. Tests:

- world-file and `:init` checks; people start empty unless given values; the export round
  trip;
- views: `:you :inner` for the person; no other agent's request contains it (built for every
  agent in a mock day and compared with the values, which is a test, not a check on model
  text);
- the work judge: the worker's and a named person's inner state change, a bystander's can't, a
  fixed key can be emptied and an emptied open key is removed, too many open keys are refused,
  and `:item/work-done` holds no inner state;
- the conversation judge: members change, a non-member present can't, a line heard by some
  members is marked so, and it's skipped at the cost limit;
- the steward: every sleeper is seen, inner state may be set, outward aspects stay
  remove-only, people awake are untouched;
- replay equals the live world.

## 6. Phases

1. **Data and the work judge:** 5.1, 5.2, the agent wording (5.5), and Claudeworth's `:inner`
   entry and rules.
2. **The conversation judge:** 5.3.
3. **The steward:** 5.4.

Each phase passes `clojure -M:test`, adds its fixture tests, and updates
`docs/scenario-authoring.md`. After phase 2, a short real Claudeworth run (with the user's
go-ahead) shows how real judges write inner state, what they put in it beyond moods, whether
anything leaks into public summaries, and what it costs. Its transcript also gives the first
evidence for the investigation in decision 7.
