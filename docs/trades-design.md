# Tradespeople and merchants: design

> **Superseded in part** by [sending-for-design.md](sending-for-design.md) (2026-11-08/09):
> `recommend` takes one free-text `wanted` (no `trade` argument, no `position` alternative); the
> trade judge became the recommendation judge (`manor.mechanics.contacts.judge`); `trades.clj` was
> folded into `manor.mechanics.contacts`, one contact record for staff and tradespeople alike; the
> `:tradesman` role became `:caller`; the lead time is 2 hours; there is no idle departure (the
> visitor leaves with `take-leave`); the Tradespeople rules section merged into
> `rules/sections/sending-for.md`. What follows is the history; the current contract is
> `docs/scenario-authoring.md`, "Sending for people".

**Status:** phases 1 and 2 implemented and mock/offline-tested (`test/manor/trades_test.clj`);
no real-model check yet. Phases 3 and 4 not started. Written 2026-10-31 from the user's brief and
their answers the same day (section 2); section 7 records how the build differs from section 4.
The contract is in `docs/scenario-authoring.md`, "Tradespeople".

Read `AGENTS.md` first, then `docs/people-and-hiring-design.md` (section 6.6, hiring),
`docs/visits-design.md` (the invitation judge, guests from elsewhere), `docs/deliveries-design.md`
(couriers, arrival at the meeting place), `src/manor/mechanics/hiring.clj` and
`src/manor/mechanics/visits/judge.clj`.

## 1. Why

A 1904 household depends on people it doesn't employ: the sweep, the coalman, the smith, the
laundress collecting the washing, a piano tuner from town. Today:

- **There's no way to have them come.** The village trades are resident agents with
  `:services` tags (`:chimney-sweep`, `:laundry`, ...), but code acts only on `:general-store`,
  `:post-office` and `:architect`. The roadmap's "Village trades content" says it outright:
  "discussing a job does not schedule or perform it." Someone has to walk to Rudge, talk him
  round, and hope he decides on his own to walk up and do it.
- **There's no way to find a trade that isn't on the map.** There are far more trades and
  merchants than a world file could list. When characters need one they make one up (Hale's
  letter to a coal merchant who doesn't exist, deliveries design), and nobody can meet them.
- **Hiring already solves the same problem for servants.** `recommend` makes a real person
  from the people server, both sides learn an id, and `send-for` brings them to the meeting
  place. It needs a catalogue position, though, so it can't name an open-ended trade.

The people server already takes any position string (`generate.clj`, `position-key`), and the
invitation judge already turns a description into someone new from elsewhere. What's missing
is a record for "a tradesperson you've been told of" and a way to send for them.

## 2. Decisions (the user's, 2026-10-31)

1. **Part of `recommend`.** `recommend` takes `trade` (free text) as an alternative to
   `position`. Either way the result is an id to `send-for`.
2. **Anyone may recommend any trade.** No judgement of whether this recommender would know of one.
3. **People on the map may be recommended.** If Rudge is recommended for a sweep and then
   won't do the job, the characters can ask for someone else; nothing enforces that he agrees.
4. **A fixed lead time of 3 hours** for sending for a tradesperson. It can grow later.
5. **Arrival:** send-for brings an off-map tradesperson to the meeting place; a tradesperson
   on the map is sent word and comes themselves, like a guest in the world. No payment for
   services yet.
6. **No appointments: as for hiring.** `send-for` takes no date or time. The tradesperson is
   expected 3 hours after it (decision 4), as a candidate is expected next round.

## 3. For the characters

1. **Asking.** Mrs Hale asks Mrs Bell at the post office whether she knows anyone who tunes
   pianos. Mrs Bell calls `recommend` with `trade: "piano tuner"` and `for: "mrs-hale"`.
   It's a query, offered on speaking turns as now, and the person told must be present.
2. **The answer.** Both learn a contact: "Arthur Brand (contact-7), a piano tuner from Ely. To
   have him come, use send-for with id "contact-7"; he can be at the Carriage sweep in 3
   hours." Mrs Bell alone gets what she knows of him (the existing reputation bridge). For
   someone on the map: "Mr Rudge (contact-8), the chimney sweep, at the Chimney sweep's
   cottage."
3. **Sending for them.** `send-for` with `id: "contact-7"` and `job: "tune the drawing-room
   piano"`. They are expected 3 hours later. The sender and the tradesperson get calendar
   entries. Arthur Brand is spawned at the Carriage sweep at that
   time; Rudge is told and kept out of hibernation that day, and walks up if he's willing.
4. **The job.** Ordinary `spend-time` on real items and places, judged by the work judge as now.
5. **Going home.** An off-map tradesperson leaves with `take-leave`, or by themselves once
   they've been idle (no conversation, no activity) for a couple of rounds, as couriers do. The
   same agent comes back when sent for again.

## 4. The engine

### 4.1 Contacts

A contact is one engine-owned record in `[:state :contacts]`, beside the candidate pool:

```clojure
{:id "contact-7" :trade "piano tuner" :based "Ely" :situation "..."  ; from the trade judge
 :record {...}            ; people-server record, nil for someone on the map
 :agent-id nil            ; set at first arrival, or from the start for someone on the map
 :known-to #{"mrs-bell" "mrs-hale"}
 :area "village"          ; the recommender's area, for reuse as with candidates
 :status :off-map}        ; :off-map | :sent-for | :here | :local
```

- Every trade recommendation makes or reuses a contact, **including one for someone on the
  map** (`:status :local`, `:agent-id` set, no record). So `send-for` always takes a contact or
  candidate id, and what a character knows stays in `:known-to`, as for candidates.
- The record is the free half of a person (`hiring/person-record`, position = the trade).
  The persona is written only at first arrival (`hiring/write-person`, with the judge's
  `situation` as notes), as couriers are. Recommending costs one judge call, one people-server
  call and one reputation call; nobody is written until they come.
- A cap (40, like `pool-limit`) with the same eviction rule: only contacts nobody has in play.
- Candidates and contacts stay separate records, with separate id prefixes, so models don't mix
  up a man who wants a post with one who offers a trade. They share the person-making code.

### 4.2 The trade judge

One checked narrow call before anyone is made, built like the invitation judge
(`visits/judge.clj`): instructions in a prompt template, a tool with a schema, and code that
checks only the answer's shape. It sees:

- the recommender's own context (who and where they are);
- the trade, in the recommender's words;
- tradespeople on the map within walking distance of the recommender: name, position title,
  workplace, public description;
- contacts already in the area: name, trade, based. Not who knows them.

It answers one of:

| Answer | Meaning |
|---|---|
| `existing-agent` | someone on the map (Rudge for "a sweep"); makes or reuses their `:local` contact |
| `existing-contact` | a contact already made in the area, so there aren't three piano tuners in Ely |
| `new` | `{trade based situation}`: someone new from elsewhere; the people server rolls the name |
| `impossible` | a reason, only when the trade plainly doesn't exist in the setting |

A judge does the matching, never string comparison of trades. A match reveals nothing about who
else knows the contact, as with invitations.

### 4.3 `recommend` with `trade`

- `:args [:map [:position {:optional true} :string] [:trade {:optional true} [:string {:min 1 :max 80}]] [:for :string]]`;
  exactly one of `position` and `trade`.
- `recommend-reason` keeps its presence checks; with `trade` it checks the contacts cap instead
  of the pool's.
- The query: the trade judge, then (for `new`) `person-record` and the reputation bridge, then
  events recalculated on the current world as `recommendation-events` does now.
- Events: `:contacts/made` (`:system`), `:people/reputation-known` (the recommender only) and
  `:contacts/recommended` (both), whose narration gives the id and how to send for them.
- The doc (`docs/hiring/recommend.md`) gains one paragraph on `trade`.

### 4.4 `send-for`

- The argument becomes `id`, a candidate or contact id (no backwards compatibility kept).
  Candidates behave exactly as now.
- For a contact: `job` (required, free text, at most 300 characters). No date or time, as for
  candidates (decision 6): they are expected at the first round 3 hours on (`lead-hours 3`).
- **Off-map contact:** status `:sent-for` with the arrival round; the tick brings them to the
  meeting place through `hiring/arrival`, spawning them the first time with a generic position
  (4.5) and a situation naming the job, who sent for them and their home off the map.
- **Contact on the map:** word reaches them at once (an event visible to them and the sender),
  with calendar entries for both, managed by the contact record like visits' entries, and
  `keeps-awake?` keeps them in play that day. Nobody moves them; they may refuse.
- Departure for off-map tradespeople: `take-leave`, or idle for `courier-leave-after`-like
  rounds (no conversation, no activity), shared with couriers where possible.

### 4.5 A generic position

A catalogue entry `:tradesman` in each setting pack's `positions.edn` (england-1904, and a
counterpart for qing-palace), not listed for hiring (added to `not-posts`). Its `:about` speaks
of "your trade" generally; the specific trade, home and job live in the agent's situation.

### 4.6 What else changes

- The views: `contacts` beside `candidates` in `hiring/observe`, known contacts only, with id,
  name, trade, based, status.
- The medium world's GM `:about` ("never invent or spawn new people") allows contacts made
  through `recommend`.
- The mock policy and an offline mock adjudicator for the trade judge; tests for privacy,
  reuse, the cap, replay, arrival and departure, and a mapped contact being told.
- `docs/scenario-authoring.md` and `docs/prompts.md` for the new template and arguments.

## 5. Phases

1. **Contacts.** `recommend` with `trade`, the trade judge, contact records, the view,
   narration. Mock and offline testable.
2. **Sending for contacts.** `send-for` with `who`, `job` and a time; arrival, spawning, the
   generic position, calendar entries, departure, word to people on the map.
3. **Merchants.** The trade judge's `new` answer may add `supplies` (a short profile). Ordering
   by letter from a known contact with supplies reuses the shop's appraisal with `supplies` as
   the store profile and no clerk at a counter, and accepted orders go through deliveries (their
   courier, arrival at the meeting place). Optionally a delivery quote names a store to fill
   (the coal bunker). This reverses deliveries decisions 1 and 2, but only for contacts someone
   was told of. A data-only coal merchant in the medium world is possible before any of it.
4. **Paying.** Service charges and tradesmen's bills, with roadmap "Bills and tradesmen's
   accounts".

## 6. Open questions

- Asking with nobody to ask (an advertisement, a directory at the post office): later, as
  another way to make the same records, if characters get stuck.

## 7. Implementation notes (2026-10-31)

Where the build differs from section 4:

- **Written at recommendation, not at first arrival.** Someone new from elsewhere is written
  (appearance and polish) and made an away agent in the `recommend` query, as `invite` makes
  someone new. Then `send-for` is an ordinary effect with no I/O, and arrival, calendar entries,
  waking, lapsing and `take-leave` are all visits' own. A new contact costs the judge, one
  people-server roll, the reputation and the two writer calls.
- **`send-for {id, job?}`**: one field for a candidate's or a contact's id (the user, 2026-10-31:
  separate fields for the same thing are confusing), named `id` rather than `who`, which means an
  agent id elsewhere. Hiring's own `send-for` takes `id` too. `job` is needed for a contact and
  ignored for a candidate, whose post was set when they were recommended.
- **One `existing` answer** for both lists: contact ids and agent ids can't collide.
- **The judge's `local` list is everyone on the map who works**, not only those within walking
  distance: the worlds are small enough.
- **No reputation for someone on the map**: they have no people-server record, and the
  recommender knows them in the world already.
- **The tradesman's position** is `:tradesman` with `:sleeps :out`; in the palace pack its
  standing is `:merchant`.
- **`send-for` is offered to everyone** (the user, 2026-10-31). It was offered only once someone
  had been recommended anyone, so in `runs/claudeworth-medium-20261031-223736` the head gardener,
  told of `send-for` by the rules, called it and got "there is no tool called send-for". Now an
  unknown id is refused with a reason pointing to `recommend`.

