# Visits and guests

Status: **phases 1 and 2 implemented** (guests in the world; guests from elsewhere; `person` and
the invitation judge; section 16 records the implementation choices); phase 3 (authored visits)
not started. Written 2026-10-23 from the user's brief and revised the
same day with their answers (section 13).

## 1. What this adds

People come to the manor as guests: to dine, to call, to stay a week, or to slip in at night.
It applies to both Claudeworth scenarios (`:claudeworth-medium` reuses `cw/scenario`, so it
gets this automatically).

- **Anyone can invite anyone.** There's no check on authority and no refusals: if the earl
  invites the King, the King comes.
- **Three ways to send an invitation:** in person, by post from the post office, or by
  messenger (anyone present who carries it).
- **The GM can arrange visits on its own.** Nobody needs to know about them before the
  guest arrives.
- **Two kinds of guest, decided when the visit falls due:**
  - **A guest in the world** (an active villager, the guestmaster) gets the invitation in
    their calendar, is kept out of hibernation all that day, and walks or travels up
    themselves. Nobody moves them.
  - **A guest from elsewhere** (an `:away` agent) appears at the meeting place (the carriage
    sweep) at the appointed time.
- **Away agents can be made in advance.** A world file can mark a character as away from the
  start (the medium household's son), and a new person someone invites is written and spawned
  away when they're invited. Eviction only removes candidate-pool records, so away agents are
  never evicted.
- **Who is being invited is settled by a judge** when the inviter describes someone rather than
  giving an id ("my father, Baron von Visitsworth"), as `find` settles what's being looked for.
  It matches an existing person or writes a new one, before anyone is created or told.
- **Guests decide when to leave.** The guest's view says who invited them, why and for how
  long, in the inviter's words ("to dine", "a week"). Nothing is enforced. A guest from
  elsewhere uses `take-leave`; a guest from the village just goes home. There's no
  `send-away` for guests: anyone who wants a guest gone can ask them.
- **Former guests can be invited again.** It's the same agent coming back, with their
  memories.
- **Only the inviter knows**, apart from the guest. Arrivals are seen only by those at the
  meeting place and by the host, so a midnight visitor isn't announced to the house.

Not included: replies and refusals; cancelling or answering an invitation in-world; guests
arriving by train or their own carriage (they simply appear); invitations anywhere but the
manor; postage costs; a general postal or remote-messaging system. Section 14 and the roadmap
record these as deferred.

## 2. What already exists

Most of the work is reuse.

| Need | Existing piece |
|---|---|
| Someone from elsewhere appearing at the entrance | `hiring/arrival-events`: `:people/returned` for an away agent; waits if the agent cap is full |
| Leaving and coming back as the same agent | `:people/departed` / `:people/returned` in `world.clj`: status `:away`, memories kept |
| Making a new person | `hiring/person-record` (free people-server record) and `hiring/write-person` (appearance and polish, two checked calls), already shared with couriers |
| A query that may make a person, inside a turn | `recommend`: `:query-fn` does the I/O, `:on-result` turns it into events |
| A judge that resolves what an agent means, with the agent's own context | the find judge (`docs/find-context-design.md`) and the shared checked-answer parser (`mechanics/adjudication.clj`) |
| A schedule people can see | `manor.mechanics.calendar`: per-audience entries, a rolling "Your calendar" section |
| Keeping villagers cheap | Claudeworth's `hibernate`: villagers stay out of play until someone comes near |
| Who you've met, by id | `[:seen viewer other]`, kept for everyone (`last-seen` shows only active people) |
| The GM seeing it all | `prompt/default-gm-view` shows all of `:state`, so `[:state :visits]` reaches the GM untouched |

There's one finding that shapes the design. **A hibernating villager never gets a turn**
unless someone comes near, so an invitation they hold in their calendar would never be acted
on. Keeping invited villagers awake on the day is the one real addition to an existing
mechanic (section 7).

## 3. The idea in one paragraph

A **visit** is one engine-owned record in `[:state :visits]`: which agent is coming, who
invited them, when, for how long and why, and how the word was sent. A visit always names an
**existing agent**. If the invitee didn't exist yet, the invitation first makes them as an
`:away` agent (section 6.1). Every source of visits (the `invite` action, the GM's
`schedule-visit`, the operator, and later the world file) makes the same record. A system tick
moves visits through their states. Whether the guest comes by themselves or appears is decided
when the visit falls due: **is the guest active, or away?** That's a code check, and it stays
right if a candidate leaves or a former guest is still in the house.

## 4. Where visits come from

### 4.1 The `invite` action (any character)

```
invite {by, who? | person?, date, time, stay, reason?, via?, bedroom?}
```

| Argument | Meaning and checks |
|---|---|
| `by` | `"in-person"`, `"post"` or `"messenger"` |
| `who` | the id of someone you know (anyone in your view, `away-acquaintances` or calendar) |
| `person` | instead of `who`: who they are, in your words (≤ 300): "my father, Baron von Visitsworth", "Pugh the architect", "a lady novelist I met in Bath, Mrs Arden" |

`person`'s tool description says the same as the rules (section 9): give the person's actual
name whenever you know it, with how they stand to you. A name makes the judge's match certain
and keeps a description like "my father" from being guessed at. A description without a name
is still accepted; the judge then writes or matches the person as best it can.
| `date`, `time` | `"1904-10-21"` and `"19:00"`: when they're expected at the house |
| `stay` | how long, in the inviter's words (≤ 200): "to dine", "until Monday", "a week" |
| `reason` | optional, ≤ 300: "to meet Mr Pugh about the new wing" |
| `via` | for `messenger`: the id of anyone here, the invitee included |
| `bedroom` | optional: an existing bedroom id for a guest who stays the night |

The checks are structural, with actionable reasons, as `sleep` and `travel` give:

- `in-person`: the guest is active and here with you.
- `post`: you're at a place with the `:post-office` service. There's no hours check (the
  pillar box stands by the door).
- `messenger`: `via` is here with you. Anyone counts, even the invitee.
- With `who`: the guest exists and isn't you, the GM, retired, or someone who lives in the
  house and is at home (`positions/household-member?` and active).
- `date`/`time` parse, and the time is at least the lead time from now (4.3). Too early is
  refused with the earliest time: "by post, the earliest they can be here is Saturday 22
  October, 09:00".
- `bedroom`, if given, is a `:bedroom`.

**The action is free.** It doesn't end the turn. With `person` it's a query (the judge, and
perhaps the people writer), so like `find` and `recommend` it counts towards the turn's
queries. With `who` it's an ordinary free action with no model call. Either way it's once a
turn.

Its reply confirms what was arranged: "Your father, Baron von Visitsworth, is expected at the
house on Friday 21 October at 19:00." Events:

- `:visits/scheduled` (GM-only), with the full record.
- One visible event per channel:
  - **In person:** the invitation, seen by the inviter and the guest.
  - **Post:** "Lord Claudeworth posts a letter", seen by everyone present, without its
    contents.
  - **Messenger:** "Lord Claudeworth gives Turner a message for Mrs Vane: …", seen by the
    inviter and the messenger.

### 4.2 Who is invited: the invitation judge

`who` is resolved by code. A `person` description goes to the **invitation judge**: one checked
narrow call, built like the find judge, before anyone is created or told.

The judge is given:

- **The inviter:** name, age, sex, position, persona, situation, notes, and their recent
  visible events. These are the same inputs the find judge gets (`prompt/scene-recent`), so
  "my father" is read against what the inviter is.
- **The description**, and the visit's `reason`.
- **The people the inviter knows of**, by id, name, position title and where they live:
  - everyone active outside the inviter's household (a small village's tradespeople are
    public figures);
  - the inviter's own household, away members included (the son at school);
  - the `:away` people the inviter has met (`:seen`);
  - candidates in the pool known to the inviter;
  - guests the inviter has already invited;
  - **guests anyone else has invited**, so two inviters describing "the new curate from Ely"
    get the same person. These are shown only by name, `from` and their public `known-as`
    (below), never their situation, host or visit, so a match tells the inviter nothing about
    anyone else's invitation or private relations.

  Private detail (situation, persona) is shown only for people the inviter already knows of,
  so a description can't fish out a secret ("my husband's secret friend").

It answers with one of:

| Answer | Meaning | Checked by code |
|---|---|---|
| `{:existing "id"}` | the description is someone listed | the id was in the list |
| `{:new {:name :sex :age :from :known-as :situation}}` | someone not in the world | name ≤ 40; sex `male`/`female`; age 1–110; `from` ≤ 80; `known-as` ≤ 120, a public description with nothing private ("a curate from Ely"); `situation` ≤ 600, second person, who they are and how they stand with the inviter |
| `{:impossible "reason"}` | the person physically can't come (below) | reason ≤ 300 |

A malformed answer gets the usual single correction, then the action fails with a neutral
reply and no events. The judge decides who is meant, never whether they'll come: willingness
is not its business.

**`impossible` is for physical impossibility only**, and the judge's instructions say so with
examples on both sides:

- **Impossible:** the person is dead, according to the inviter's persona or situation or the
  world; or they're the inviter themselves.
- **Never impossible:** unlikelihood, rank, distance, inconvenience, propriety or
  unwillingness. "The Queen wouldn't call on the Earl of Claudeworth" is not a reason; she
  comes.

The reply gives the reason ("Your father died in 1898."), and nothing is scheduled. This is
prompt guidance, as `AGENTS.md` asks; code checks only the answer's shape.

`known-as` is also how others first see a new guest: the arrival reads "A visitor arrives at
the Carriage sweep: Mr Arden (mr-arden), a curate from Ely."

**Why a judge here and not for scheduling.** The time is structured, and code checks it
(question 1). But who "my father" or "the young doctor from Ely" is depends on prose: the
inviter's persona and situation, and the names and positions of people who may already exist.
That's exactly the kind of judgement `AGENTS.md` gives to a model. Without the judge, an
inviter who has no exact name couldn't invite at all, and one who misspelled a villager's name
would make a second one.

### 4.3 Lead times

A lead time is the **shortest gap allowed between sending an invitation and the guest being
expected**: a letter can't bring someone in ten minutes. They're data, set in the scenario's
`:visits` config and overridable per run with the run option `:visits` (merged over the
scenario's, as `:max-queries` is):

```clojure
:visits {:lead-hours {:in-person 0 :messenger 1 :post 3}
         :from-away-hours 24     ; added when the guest is away (not in the world)
         :wake-from "06:00"      ; a guest in the world is kept in play from this time on the visit's day
         :lapse-hours 3}         ; ... and is no longer expected this long after the time
```

`from-away-hours` covers both the letter and the journey for someone from elsewhere, without
modelling where they live. Section 14 defers anything finer.

### 4.4 GM, operator and authored visits

- **GM tool `schedule-visit`:** `who` (any agent id) or the judge's `:new` fields given
  directly (the GM knows the world, so it needs no judge), plus `date`, `time`, `stay`,
  `reason`, `bedroom` and `known-to` (ids, default none). Its only lead time is the next round,
  so the visit can stay secret until the guest arrives. The GM might use it from a GM-only
  calendar entry ("consider whether a caller should come today", which already works), on a
  prayer, or on its own check-in. A guest in the world must know, or they won't come, so their
  calendar entry is made at once ("You mean to call at Claudeworth Manor at 15:00: …").
- **GM tool `cancel-visit {id}`:** for a visit not yet arrived.
- **Operator:** `engine/schedule-visit!` and `cancel-visit!` (and REPL helpers), queued
  between rounds like the calendar commands.
- **Authored visits (phase 3):** visits written into the world file before the run starts, such
  as `{:who "master-claudeworth" :date "1904-12-20" :time "14:00" :stay "the Christmas holidays"}`.
  They're the same records with no inviter, made at initialization.

## 5. The visit record and its states

```clojure
{:id "visit-17"
 :guest "pugh"                       ; always an agent id
 :host "lord-claudeworth"            ; nil for a GM, operator or authored visit
 :by :post :via nil
 :arrive 123 :at "1904-10-21T19:00"  ; round, and the time as given
 :deliver 117                        ; when a guest in the world learns of it
 :stay "to dine" :reason "..." :bedroom nil
 :status :scheduled}                 ; :scheduled -> :here -> :ended, or :lapsed / :cancelled
```

The tick runs before turns, after hiring's and deliveries'. It takes visits in id order, each
on the world the previous one left:

| Status | When | Then |
|---|---|---|
| `:scheduled` | round ≥ `:deliver`, guest active, not yet told | guest learns of it: an event to them, and their calendar entry (7.1) |
| `:scheduled` | round ≥ `:arrive`, guest `:away`, room under the agent cap | arrival at the meeting place (6.2); `:here` |
| `:scheduled` | guest active and in the house's area, round ≥ `:arrive` − 1 hour | `:here` (they came) |
| `:scheduled` | guest active, round ≥ `:arrive` + `lapse-hours` | `:lapsed` (GM-only event) |
| `:scheduled` | guest retired | `:lapsed` |
| `:here` | a guest from the village is no longer in, or heading to, the house's area | `:ended` |
| `:here` | a guest from elsewhere uses `take-leave` | `:ended` (done by the action) |

"The house's area" is the meeting place's walking area (`space/area`), so the carriage sweep
and every room count. The one-hour lower bound stops someone already at the house for other
reasons, such as the sweep at work in the afternoon, from "arriving" for an evening visit and
then "leaving" when he goes home to wash.

## 6. Guests from elsewhere

### 6.1 Away agents made in advance

An away agent is an ordinary agent with status `:away`: spawned with a persona, traits,
situation and appearance, then immediately `:people/departed`, with both events
`:visible-to :system`. There are two sources:

- **The invitation judge's `:new` answer.** In the same query, the people writer makes them:
  - `person-record` with the judge's sex and an exact age range `[age age]`, keeping the
    judge's name rather than the server's;
  - then `write-person`, with the judge's `situation` passed as the polish's notes.

  They're spawned with the new catalogue position `:guest` (section 10), their situation set
  to the judge's, `:lives` set to `from`, and no household. All of it is recorded from the
  query's result, so replay never calls out. This replaces any pending-person record or
  name-reservation scheme: the name is taken because the agent exists.
- **World-file characters marked `:away? true`.** They're spawned like everyone else at
  initialization (post, bedroom, situation and all), then made away. This is how the medium
  household's son is in the run but at school, replacing his `:leave-out`. A `:leave-out`
  character isn't in the run at all; an away one is known and can be brought home.

**Eviction.** The candidate pool's FIFO eviction (`hiring/eviction`) only removes pool records,
never agents. Away agents of either source have no pool record, so nothing can evict them, and
no special flag is needed. A test pins this down: a pool filled to its limit by
recommendations leaves every away agent, and their visits, intact. If a cleanup of away agents
is ever added, it must leave these alone. That's when a flag would be worth adding.

### 6.2 Arriving

When a visit falls due and the guest is away, they return with `:people/returned` at the meeting
place, as with `send-for`. This is the only arrival path for guests from elsewhere, because they
already exist. The named bedroom, if any, is assigned with `positions/bedroom-assigned`. If the
agent cap is full, the arrival waits.

The arrival event is seen by **those at the meeting place and the host**, wherever they are.
Hiring's arrivals work the same way. There's no house-wide bell, unlike deliveries, so a
midnight visitor isn't announced to everyone: "A visitor arrives at the Carriage sweep: Baron
von Visitsworth (baron-von-visitsworth)."

### 6.3 Who they are and why they're here

- **Situation (identity):** the judge's or the world file's, set at spawn, and never
  rewritten. The exception is former candidates and couriers. Hiring and deliveries overwrite
  their situation with visit-specific text ("You have come about the cook place… You leave
  after three rounds without a conversation"), which would be wrong for a guest. When such a
  person arrives as a guest (code knows them by `hiring/for-agent` or the couriers map), one
  `:agent/updated` replaces it with a template from their record: "You are Pat Ellis, of
  Ely. You came to Claudeworth once before, about the cook's place." or "…; you carry for the
  General store." If they later come back as a candidate or courier, hiring or deliveries set
  their own text again, as now.
- **The visit (what's happening now, in the view while it's `:here`):**

  ```clojure
  :you {:visit {:host "Lord Claudeworth" :invited-for "a week" :reason "..."
                :arrived "Fri 19:00" :bedroom "guest-bedroom"}}
  ```

  This is where "for dinner, or a week" lives, so the guest always knows what they're visiting
  for. Only the guest sees it. There's no overstay handling.
- **Brief:** the `:guest` position's `:about` says they don't live here, points to `you.visit`,
  and says that when the visit is over, or it seems right to go, they `take-leave`. Returning
  people keep the role they had; `you.visit` and the rules give them the same directions.

### 6.4 Leaving

- **`take-leave`** (final) works for a guest from elsewhere anywhere in the house's area. It
  emits `:people/departed` towards `:lives`, so they become `:away`, and ends their `:here`
  visits. They can be invited again.
- There's no `send-away` and no timeout for guests. If someone wants a guest gone, they say
  so, and the guest decides. Hiring's `send-away` stays as it is, for candidates only.

Hiring already has a `take-leave` for candidates. `visits/install` replaces it with one that
also accepts guests, keeping hiring's candidate branch as it is, so models see one
`take-leave` whatever brought them.

## 7. Guests in the world (the village, the abbey)

### 7.1 Knowing

- **In person:** the guest knows at once.
- **Post or messenger:** at the `:deliver` round (now + lead time), the guest gets an event, "A
  letter comes from Lord Claudeworth: you are invited to Claudeworth Manor on Friday 21
  October at 19:00, to dine.", and their calendar entry.
- The calendar entry says what to do: "You are expected at Claudeworth Manor at 19:00, invited
  by Lord Claudeworth: to dine." That, the rules (section 9) and `travel` are all a villager
  needs.

### 7.2 Awake all that day: the one change to hibernation

Claudeworth's `hibernate` gains one guard: **don't hibernate someone a visit is keeping in
play.** `visits/keeps-awake?` is true:

- from `wake-from` (06:00) on the visit's day, or on the day before if that's too late to get
  there (the visit is earlier than `wake-from` plus their journey and an hour, as with a
  01:00 tryst), but never before they've been told;
- through the visit while it's `:here`;
- until it ends or lapses.

An awake villager sleeps as anyone does, with `sleep` in their own bedroom. Being kept awake
all day costs about 25 extra turns per invitation (about $0.05). A villager at the house is
kept in play anyway, because the household, having no routines, counts as company in
Claudeworth's `company?`. The guard matters mainly before they arrive. Once they leave and the
visit has ended, hibernation resumes as usual.

**The wake narration.** When hibernation ends, the event is already generic, "Mrs Bell is no
longer attending to the post office counter.", with no mention of anyone. But until now a wake
always meant someone had come near, and the authoring guide says so ("Waking tells the
agent that someone has come near"). The changes:

- That sentence in the guide changes to say a wake may also come from the agent's own plans.
- When the guard is what wakes someone, the tick adds a private `:visits/reminded` event: "You
  have an engagement today: Claudeworth Manor at 19:00 (Lord Claudeworth's invitation)."

The waking itself stays the ordinary `b/free`.

### 7.3 Coming and going

They `travel` up (one round from the village or the abbey). The tick marks the visit `:here`
when they're in the house's area. A villager who stays the night sleeps with `sleep` in a
bedroom, like anyone. They end the visit by going home. They don't need `take-leave`, and
their situation is never touched.

If they don't come, that's their choice. The visit lapses after `lapse-hours`, which the GM can
see, and nothing else happens.

## 8. What people know

### 8.1 Calendar entries owned by visits

Each visit makes up to two calendar entries, written by code from the record:

- **Host side:** the inviter only, or the GM's `known-to` (question 7). "Your father, Baron von
  Visitsworth, is expected at the house at 19:00: a week." The messenger sees the message
  they carried but gets no entry.
- **Guest side:** only a guest in the world, from when they're told. See 7.1.

These entries need one small calendar change: an engine-owned **`:managed-by :visits`**
marker. Managed entries are **not queued for GM review** (the engine performs the arrival; a
review at every arrival time would be a GM call per visit for nothing). The GM's and operator's
calendar edit/cancel commands refuse them with "use cancel-visit". `visits` updates or
cancels them itself through the shared `calendar/command-events`. Everything else about the
calendar is unchanged.

Nobody else is told. A guest who comes openly is seen by whoever is there, as anyone is; a
lover let in at the sweep at midnight is seen by nobody but the host and whoever happens to be
outside.

### 8.2 Beds

A guest from elsewhere who is staying sees the house's bedrooms and who sleeps in each (today
only household members do), so they, or whoever shows them up, can choose a bed when the
invitation named none. Sharing or making do is their decision, as the rules already say.

### 8.3 Inviting someone again

No new register is needed. Spawned people stay in `:agents` as `:away`, and `[:seen viewer
id]` records where people parted. A view list, **`away-acquaintances`**, shows the `:away`
people the viewer has met, with where they last saw them: the same `:seen` data that
`last-seen` filters down to active people. Anyone on it can be invited by `who`. Former
candidates known only by recommendation stay in `hiring.candidates`. An inviter who has lost an
id can describe the person, and the judge will match them from the same lists (4.2).

One fix is needed. `:people/departed` (in `world.clj`) drops the location without recording a
parting, so a guest who never left the drawing room before `take-leave` wouldn't be in the
host's `:seen`. The departure should record the parting for everyone present, as the movement
reducers in `space.clj` do. Candidates and couriers benefit too.

### 8.4 Privacy

- `:visits/scheduled`, the judge's call, the spawn of away agents and lapses are GM-only or
  `:system`.
- The posting event shows that a letter went, not to whom.
- Arrivals go only to the meeting place and the host (6.2).
- A guest's `:visit` and calendar entries go only to their audience, never into `:reference`.
- The judge shows private detail only for people the inviter knows of. Everyone else's guests
  appear only by name, `from` and public `known-as`, so a match reveals a name and nothing about
  who invited them, when, or why.

## 9. Rules text

This is shared, and cached with the rest of the rules. Here's a draft for `rules`:

> Anyone may `invite` someone to the house for a date and time: in person (they're here with
> you), by post (at the post office) or by messenger (anyone here who will carry it). Name
> them with `who` if you know their id; otherwise say who they are in `person`, in your own
> words. If you know their name, always give it in `person`, with how they stand to you ("my
> father, Sir Henry Vane", not "my father"): the more exactly you say who they are, the surer it
> is that the right person comes. Someone who lives nearby makes their own way at that time; someone from further off
> arrives at {meeting place}. A guest from elsewhere stays as long as seems right and goes
> with `take-leave`. If you're invited, your calendar says when to be there.

The `:guest` position's `:about`:

> You don't live here: you are a guest, and `visit` under `you` in your view says who asked
> you, why and for how long. When your visit is over, or it seems right to go, use
> `take-leave`.

## 10. The two Claudeworth scenarios

- **Both:**
  - `visits/install` goes after `hiring/install` (it needs the meeting place). It adds the
    actions, the judge, the system, the GM tools, the hibernation guard, the views and the
    rules paragraph.
  - The install is idempotent like hiring's and deliveries', because `claudeworth-medium`
    re-installs on `cw/scenario`.
  - Add `:guest` to `resources/positions/england-1904.edn`, with a new standing `:visitor`.
    Standings are used only for household membership and scene colour, so a guest must not
    have a household standing.
  - Worldfile support for `:away? true` on characters, checked like the other agent fields.
- **`claudeworth`:** the house has no spare bedroom. A guest who stays the night shares or
  makes do, or the earl builds one, which gives commissioning a reason. No world-file change is
  proposed.
- **`claudeworth-medium`:** it has one guest bedroom. The son becomes `:away? true` instead of
  `:leave-out`, so his family can invite him home, or the GM or an authored visit (phase 3) can
  bring him. The Dowager needs a suite and is still left for later.

## 11. Should this merge with hiring?

The brief asks whether one `send-for {hiring | invitation: reason}` should cover both.

**Shared:** arriving at the meeting place, the agent cap, `:people/departed` and `:away`,
`take-leave`, and making people.

**Different:**

| | Hiring | Visits |
|---|---|---|
| Who | a recommended candidate | anyone |
| When | next round | a chosen date and time |
| Why | always "about a place" | free text |
| Length | three idle rounds, then gone | the guest decides |
| Knowing | the pool's `known-to` | calendar entries |
| Afterwards | `engage` and `accept-place` | nothing |

**Recommendation: share the back end, keep two tools.**

- Narrow tools whose arguments always apply suit models better than one tool with modes.
- `send-for`'s precondition (a recommendation) and its timeout stay where they are.
- Hiring's tests carry over unchanged.

The sharing is:

- one `take-leave` (6.4);
- the people writer;
- arrival at the meeting place, extracted once. Hiring and deliveries each have a copy
  (`arrival-events`, `courier-events`), and visits would be the third user.

Guests don't go into the candidate pool. An away guest needs no pool record to be invited again
(8.3), and keeping them out is also what keeps them safe from eviction (6.1).

## 12. Edge cases

| Case | Handling |
|---|---|
| Agent cap full when a guest from elsewhere is due | Arrival waits; the visit stays `:scheduled` |
| Villager asleep, busy or travelling at the time | Their choice; the visit lapses after `lapse-hours` |
| Guest already at the house when a visit falls due | `:here` at once, no arrival event; their `:visit` shows the newest |
| Candidate invited for later, then leaves | Away when due, so they arrive at the meeting place |
| Invitee retired before the time | The visit lapses (GM-only) |
| Inviting someone who lives in the house and is at home | Refused: "they live here" |
| Inviting yourself or the GM | Refused |
| Invitee as their own messenger | Allowed (the messenger lead time applies) |
| "My father", with no name | The judge writes him, keeping consistent with the inviter's age, persona and situation |
| A description that matches a villager ("Pugh the architect") | The judge answers `:existing "pugh"`; no second Pugh |
| Two people describe the same new person | The judge matches the first's guest by name, home and `known-as`; the second inviter learns only the name |
| "Invite the Queen" | She's written and comes; rank and likelihood are never `impossible` |
| "Invite my late father" | `impossible`, with the reason; nothing scheduled |
| Guest from elsewhere wanders into the village | Allowed; `take-leave` works anywhere in the house's area, or they come back |
| Host leaves, sleeps or is retired | The visit carries on; hosts have no duties in code |
| "Do stay till Sunday" | Conversation; the guest decides. `stay` records the invitation, not the plan |
| A villager invited for a week | Stays in play at the house; their shop is untended, which is their choice |
| Judge or people server fails mid-query | Neutral error reply, no events, nobody made, as with `recommend` |
| Branch, load, replay | Every step is an event; query results are recorded, so replay never calls out |
| Map export | `[:state :visits]` and spawned guests aren't in `map.edn`, as with candidates; load/branch is the full-fidelity path |

## 13. Decisions and open questions

### 13.1 The user's decisions (2026-10-23)

1. **Scheduling by code**, with structured date and time and no judge or GM step for the time.
   Kept as proposed.
2. **Lead times** are as proposed and configurable: scenario `:visits` config, overridable
   by the run option `:visits` (4.3).
3. **Anyone present can be a messenger**, even the invitee.
4. **Invited villagers are kept awake the whole day** (`wake-from` 06:00; 7.2).
5. **No overstay handling and no `send-away` for guests.** The `:visit` field tells them what
   they're visiting for; anyone can ask a guest to go, and the guest uses `take-leave`.
6. **Who is invited is settled by a judge, not a name check.** The exact-name check is
   dropped. A description goes to the invitation judge (4.2), modelled on `find`, before anyone
   is created or told, because a name may not exist yet ("my father, Baron von Visitsworth").
7. **Only the inviter knows.** The host-side entry goes to the inviter alone, arrivals aren't
   heard house-wide, and there's no household guest list, so a lover can be let in at night.
8. **"Come about a place"** was explained; settled as B below.
9. **Away characters are needed now**, for the son and as the way new guests exist (6.1).
   Authored visits (world-file `:visits`) are left for phase 3.
10. **`invite` is free.**

From the follow-up:

- **Wake narration:** the existing text is already generic. The authoring guide's "a wake means
  company" changes, and visit wakes get a private reminder (7.2).
- **Pre-made away agents must not be evicted.** Eviction never touches agents, and a test
  checks this; no flag is needed unless away agents are ever cleaned up (6.1).

From the second follow-up:

- **A. The judge may answer `impossible`**, but only for physical impossibility (the person is
  dead, or is the inviter), never for unlikelihood, rank or unwillingness. The instructions
  say so with examples (4.2).
- **B. Returning candidates' and couriers' situations are replaced** with a short template when
  they come back as guests. It's one `:agent/updated` at arrival (6.3).
- **C. The judge sees everyone's guests**, others' only by name, home and a public `known-as`,
  so the same person is reused without revealing anything private. No "unavailable" reply is
  needed: nothing about the first invitation reaches the second inviter (4.2).

### 13.2 Still open

None. Phase 1 can start when the user says so.

## 14. Deferred

Also recorded in the roadmap (B14):

- **Coming and going coherently:** a railway station, a hired fly or the guest's own carriage,
  arrival times set by the timetable, guests' luggage and servants. For now, guests from
  elsewhere appear at the meeting place and vanish when they leave.
- **Replies and refusals:** an invitee who declines, proposes another day or never answers;
  answering letters.
- **A wider postal or remote system:** letters as items, posts and deliveries, telegrams,
  correspondence with people elsewhere, and invitations as one kind of message.
- Cancelling or postponing an invitation in-world (the GM and operator can already cancel).
- Invitations to places other than the manor (a villager inviting someone to their cottage).
- A `recommend`-like "do you know someone who…?" for guests.
- Postage paid from the household account.
- Distances for people from elsewhere (London against Bath), instead of one
  `from-away-hours`.

## 15. Phases and checks

All checks use mock/offline runs and a small test-owned fixture world. The fixture has a
house, a post office, a hibernating villager with a routine, a meeting place, and an away
character. The fixture and `test/manor/visits_test.clj` own their cast, and both Claudeworth
scenarios stay in the bundled smoke test. Mock adjudicators stand in for the judge. A short
real run (8–12 rounds, `:max-cost` 0.20) only with the user's go-ahead, as usual.

**Phase 1 — visits for people in the world.**
- Build: the record, `invite` with `who` (all three channels), lead-time config and the run
  option, the GM's `schedule-visit`/`cancel-visit` for existing agents, operator helpers,
  managed calendar entries, delivery, the all-day hibernation guard with its reminder and the
  authoring-guide change, `:here`/`:ended`/`:lapsed`, the rules paragraph, and the install in
  both scenarios.
- Done when:
  - a villager invited by post is told at the right round, kept awake from 06:00, scripted to
    travel, marked `:here`, then `:ended` when they go home, and hibernates again;
  - a 01:00 visit wakes them the day before;
  - a villager who doesn't come lapses;
  - managed entries are never GM-reviewed and refuse GM edits;
  - invitations and calendar entries reach only the inviter and the guest;
  - replay matches.

**Phase 2 — guests from elsewhere and away agents.**
- Build:
  - world-file `:away? true`, and the son switched from `:leave-out`;
  - `invite` with `person` and the invitation judge (with its mock and its checked answer);
  - new people written and spawned away in the query;
  - the GM's `:new` fields;
  - arrival at the meeting place (seen only there and by the host);
  - the `:guest` position, `you.visit` and beds for staying guests;
  - the shared `take-leave`, `away-acquaintances`, and the `:seen` fix on departure;
  - the extracted arrival helper used by hiring, deliveries and visits.
- Done when:
  - "my father, Baron von Visitsworth" makes one away agent consistent with the inviter, who
    arrives at the time;
  - "Pugh the architect" resolves to `pugh`, and a second inviter's "the curate from Ely"
    resolves to the first inviter's guest without seeing their situation;
  - the mock judge's `impossible` reply schedules nothing;
  - a returning former candidate's situation is replaced by the template;
  - a GM-secret visit by a new person arrives with nobody told in advance;
  - the guest leaves, is invited again by `who`, and returns as the same agent with their
    memories;
  - the son can be invited home;
  - a pool filled by recommendations evicts no away agent;
  - a full agent cap delays arrival;
  - hiring's tests pass unchanged;
  - nothing calls out offline.
  - Review one real roll of a judge-made guest by eye (no prose checks).

**Phase 3 — authored visits.**
- Build: world-file `:visits`, made at initialization (for example the son home on 20 December).

Suggested homes:

- `src/manor/mechanics/visits.clj`: record, action, system, GM tools, views, rules;
- `src/manor/mechanics/visits/judge.clj`: the invitation judge, beside the find judge's pattern;
- a `:managed-by` branch in `mechanics/calendar.clj`;
- the guard in Claudeworth's `hibernate`;
- `:away?` in `io/worldfile.clj` and the scenario's init;
- `engine.clj` and `dev/user.clj` for operator helpers.

Update the scenario-authoring contract when it's built.

## 16. Implementation notes

Phase 1 is in `src/manor/mechanics/visits.clj`, with the `:managed-by` branch in
`mechanics/calendar.clj`, the guard in Claudeworth's `hibernate`, the operator commands in
`engine.clj` and `dev/user.clj`, and the contract in `docs/scenario-authoring.md` (Visits).
Tests: `test/manor/visits_test.clj` on the test-owned world `test/fixtures/visits.edn`. Where
the design left a detail open, the simplest choice consistent with it was taken:

- **Away guests were refused in phase 1**, and a visit whose guest left before the time lapsed.
  Part A of phase 2 replaced both with arrival at the meeting place (below).
- **`who` isn't checked against who the inviter knows.** Section 4.1's checks don't list it, so
  any existing id is accepted.
- **Visit ids** are `visit-N`, N one more than the number of visits so far (visits are never
  removed). The calendar entries are `visit-N-host` and `visit-N-guest`.
- **`:by` for arranged visits** is `:gm` or `:operator`, with `:host` nil, so the GM can see
  where a visit came from.
- **The invitee as their own messenger** knows at once (they hold the message); the messenger's
  lead time still sets the earliest time.
- **Delivery** is the round the lead time ends: `:deliver` is the first round starting at or after
  now plus the lead time, and `:arrive` the first round starting at or after the given time.
- **The wake round** is computed once, when the visit is made, from the guest's place then (or
  where they're travelling to): the journey is the travel to the meeting place.
- **The reminder** is sent the first time a visit keeps a guest with a routine (a hibernator) in
  play, whether or not they were hibernating at that moment; if they are, the tick frees them
  (`b/free`) with it. A guest without a routine never hibernates and has no reminder.
- **Arriving (`:here`)** also needs the guest to have been told, so someone at the house for other
  reasons before their letter comes isn't counted as having come.
- **Lapses and calendar entries.** A lapsed or ended visit's entries are left as they are (they
  drop out of view after their day); a cancelled visit's are cancelled. A guest entry is never
  added for a time already past.
- **Calendar text** is clipped to the calendar's 600 characters (a long `stay` and `reason` can
  exceed it with the names); the house is named by `:house` in the config, from the world file's
  `:meta :name`.
- **Managed entries** are refused by `calendar/command-events` for any change and can't be passed
  `:managed-by` by anyone; only `calendar/managed-command-events` with the owner changes them.
  World-file export leaves them out, as it leaves out `[:state :visits]`.
- **Visit entry ids are reserved** so a GM's or operator's entry can't take `visit-N-host` first and
  make a later invitation throw: an owner names itself in the scenario's `:calendar :managed`
  (`{:visits {:id-prefix "visit-" :hint "use cancel-visit"}}`, added by `visits/install` and recorded
  in `:calendar/initialized`), and nobody else may add an id with that prefix. The refusal of an
  edit ends with the owner's hint, so the calendar names no owner itself (review fix).
- **The operator's `schedule-visit!`** is checked for `stay` (1–200) and `reason` (≤ 300) in
  `arranged-reason`, as the GM tool's schema checks them (review fix).
- **Messenger delivery** reads "A message comes from Lord Claudeworth (sent with Hale): …": the
  messenger needn't have gone to the guest himself (review fix).
- **The rules paragraph** (phase-1 wording, only `who`) is added by Claudeworth's `rules` when
  `[:state :visit-config]` is present, so worlds reusing those rules without visits (Lodge) don't
  get it.
- **`:bedroom`** is checked and recorded; since phase 2 part A it's assigned to a guest from
  elsewhere when they arrive.
- **Without `:calendar`** (the test household scenario drops it), visits still run but no entries
  are written.

### Phase 2, part A

Guests from elsewhere, without the invitation judge or `person`. Code in `visits.clj` (arrival,
`take-leave`, the new person, the view), `hiring.clj` (`arrival`, `room-for-arrival?`, the people
writer's options), `world.clj` (`:seen` on departure), `space.clj` (`away-acquaintances`),
`engine.clj` (`:away?` at init), `worldfile.clj`, `positions.clj` (`:visitor`) and the catalogue's
`:guest`; tests in `visits_test.clj`. Choices where the design left a detail open:

- **The GM's new person is given as flat fields** of `schedule-visit` (`name sex age from known-as
  situation` beside `who`), not a nested `:new` map: the decoder normalises only top-level keys,
  and models write `known_as`. Internally they're the same map (`visits/new-person-keys`), which
  part B's judge answer will reuse.
- **`schedule-visit` is a query** for the GM, as writing someone new is I/O (the people server and
  two checked calls); with `who` it calls nothing. Its result is checked again on the world when
  it lands, and a name already in the world is refused with "use who".
- **The operator's `schedule-visit!` takes `who` only**; making someone new is the GM's (4.4 names
  only the GM's `:new`).
- **World-file away characters** are handled in `engine/init!` (`spawn-at-start`), as `:away?` is a
  world-file field like any other: everything spawned is `:system`, then `:people/departed`
  (towards their `:lives`, else "elsewhere"). The fixture's away friend and the son keep their
  bedrooms from the world file.
- **Who has come from elsewhere** is recorded on the visit (`:from-away?`, set by the arrival), so
  `take-leave`, the bedrooms and the end of a visit go by it. Such a visit ends only through
  `take-leave` or the guest no longer being active (a GM retire, say), not by walking to the village.
- **`you.visit` is shown for any guest whose visit is `:here`**, villagers too, as it's theirs
  alone and says what they came for; the bedrooms only for guests from elsewhere. "Staying" isn't
  read from the prose `stay`, so every guest from elsewhere who is here sees them.
- **Arrival narration** is one `:visits/arrived` event for those at the meeting place, the host and
  the guest; the `:people/returned` beneath it is `:system`, so a first-time visitor isn't said
  to "return". Someone of the household arriving home reads "… arrives at the Carriage sweep."
  rather than "A visitor arrives".
- **An away guest is never told** (no delivery, no guest calendar entry): someone away hears of it
  only if they're about before it's due. A GM-arranged visit's delivery, if it ever comes, reads
  "You mean to call at …".
- **Lapsing** is only for guests who are about; an away guest whose arrival waits on the agent
  cap stays `:scheduled` (12: "arrival waits").
- **`take-leave` towards** the agent's `:lives`, else a former candidate's pool `:lives`, else
  "elsewhere". Its guest branch is refused outside the house's area ("go back there first").
- **Former couriers as guests:** deliveries' idle-courier departure skips anyone `visiting?`, or a
  courier invited as a guest would be sent home by deliveries two rounds after arriving. Hiring's
  `send-for` refuses a candidate who is about already (a guest), which would otherwise spawn a
  second agent.
- **The catalogue's `:guest`** has `:standing :visitor` and no `:sleeps` (the catalogue test allows
  that for visitors); its `:ages` are only a default, as new guests are rolled at their given age.
- **The arrival helper** is `hiring/arrival`, beside `departure`, since deliveries already uses
  hiring's people writer; `hiring/room-for-arrival?` replaces the agent-cap checks hiring and
  deliveries each had.

Part B can reuse: `visits/new-person-keys`, `visits/write-new-person`, `visits/away-guest-events`,
`visits/arranged-reason` (and `guest-reason` through `invite-reason`), `visits/invite-events`,
`space/away-acquaintances`, and the agent's public `:known-as` and `:lives`.

### Phase 2, part B

`invite`'s `person` and the invitation judge. Code in `visits/judge.clj` (the request, `people`,
the checked answer, the mock), `visits.clj` (`invitee!`, `described-invite-events`,
`resolved-reason`, the rules paragraph), `actions.clj` and `engine/turn.clj` (`:kind-for`),
`engine/resolve.clj` (the `recent` service) and `prompt.clj` (`recent-lines`); tests in
`visits_test.clj`. Choices where the design left a detail open:

- **One tool, two kinds.** `invite` is free with `who` and a query with `person` (4.1), so an
  action may now give `:kind-for (fn [args] kind)`; the turn dispatches on the kind of each call.
  `invite` has both kinds' keys (`:effect` for `who`, `:query-fn` and `:on-result` for `person`)
  and a `:hint` saying so. No other action uses it.
- **Recent lines** come from a new composed-query service, `(recent)`: the caller's own last ten
  narrated lines (`prompt/recent-lines`, their own view, private lines included since they're the
  inviter's), since request functions don't get the event log. `prompt/scene-recent` is the work
  judge's and stays as it is; the find judge still has none.
- **Age.** Agents carry no age, so the inviter's is their `:age` if they have one (guests written
  by the judge or the GM now get the judge's `:age` in their `:agent/updated`), else their
  position's when its `:ages` are fixed (`[52 52]`), else it's left out.
- **The lists** are three, each person once and in this order: `your-guests` (the inviter's own
  guests: id, name, position, where they live, `known-as`, situation), `known` (id, name, position
  title, where they live) and `others`. **`others` is everyone else in the world not retired**, by
  id, name, `from` and `known-as` only: everyone else's guests as 4.2 asks, and also away people
  the inviter has never met (the fixture's Captain Hart, a former courier). Without them, "my old
  friend Captain Hart" would write a second Captain Hart. They show only what 4.2 allows for
  others' guests, and the list never says who is a guest. Visits themselves (host, time, stay,
  reason) never reach the judge, not even the inviter's own, beyond their guests' situations.
- **Known candidates** are those recommended to the inviter who have an agent (they've come
  before); one only in the pool isn't an agent yet and is reached by `send-for`.
- **Where someone lives** is their `:lives`, the house for its household, a candidate's home, or
  their bedroom's name, whichever comes first.
- **A new name already listed** is refused to the judge ("answer existing with that id if they're
  meant"), using the one correction; a retired namesake doesn't count, as spawning gives a fresh
  id. The `new` map's keys are normalised like top-level ones (`known_as`), as models write them.
- **Order of checks.** Before the query: the channel (except in person), the lead time without the
  journey from away, and the bedroom. After the judge: `existing` gets all of `invite`'s checks;
  `new` is refused in person ("not here to be invited in person") and gets the lead time from away
  *before* anyone is written, so no call is spent on someone who can't come. `resolved-reason` runs
  again on the world when the query's done.
- **Replies.** "Baron von Visitsworth (baron-von-visitsworth) is expected at Claudeworth Manor on
  …", as with `who`; an impossible answer gives the judge's reason and "Nothing is arranged.";
  anything else refused says why. A judge that fails twice gives the composed query's neutral
  "The enquiry failed; try again later." with an operator-only `:adjudication/failed`.
- **The mock judge** (`--mock` runs): the inviter's own name means impossible, a listed person
  whose surname or `known-as` the description contains is them, anyone else is someone new named
  by the description's last capitalised part ("my father, Baron von Visitsworth"), aged 50, from
  London. Tests replace it where they need a particular answer.
- **Not checked by eye yet:** section 15 asks for one real roll of a judge-made guest; that needs
  a real run, which wasn't part of this work.
