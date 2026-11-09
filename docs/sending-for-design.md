# Sending for people: one path for staff and tradespeople

**Status:** draft design; phases 0 (the speaking-turn tools) and 1 (contacts, settling at arrival, the pool gone) done and mock-tested, the rest not started. Written 2026-11-08 from the user's brief and two rounds of
answers the same day (section 2). Section 9 lists what is still open.

Read `AGENTS.md` first, then `docs/works-run-review-2026-11-08.md` (items 11, 12 and 14),
`docs/trades-design.md`, `docs/people-and-hiring-design.md` (section 6.6), `docs/visits-design.md`
and `docs/arrival-belongings-design.md`. Code: `src/manor/mechanics/hiring.clj`, `trades.clj`,
`trades/judge.clj`, `visits.clj`, `people.clj`, `items/belongings.clj`, and `speaking-actions` in
`src/manor/ruleset.clj`.

This replaces trades-design decision 1 ("part of `recommend`": `trade` as an alternative to
`position`) and the separate candidate and contact records (trades-design 4.1, "so models don't mix
up a man who wants a post with one who offers a trade").

## 1. Why

There are two ways to find someone and have them come, and one choice made early decides which way
everything after it goes:

| | Candidate (`recommend {position}`) | Contact (`recommend {trade}`) |
|---|---|---|
| Record | `[:state :pool]`, `candidate-N` | `[:state :contacts]`, `contact-N` |
| Who is meant | code: the oldest unplaced candidate in the recommender's walking area seeking exactly that position id, else someone new; never anyone on the map | the trade judge: someone on the map, a contact made before, or someone new |
| Belongings | a first-arrival set "coming for interview", usually with a written character | none |
| Arrives | next round, role `:none`, "you have come about the X place" | 3 hours later, role `:tradesman`, with the job on their visit |
| `engage` | yes | no: `engage` needs a candidate record |
| Leaves | after 24 hours without a conversation; hiring's own `take-leave` | after 2 hours idle unless a work is open; visits' `take-leave` |

In `runs/claudeworth-small-20261108-121046` someone was asked three times for a joiner to mend a
stair tread and recommended an estate-carpenter candidate. The three arrived "to talk about a
place", carrying written characters, and were hired (review item 11). Once that choice is made,
nobody can turn it into the job they wanted: a candidate can be given work with `order-work` but
arrives as a job-seeker, and a tradesperson can't be hired at all.

All three wrong recommendations came on speaking turns. `ruleset/speaking-actions` took hiring's
own `recommend`, which takes only `position`, not the trades version installed in the scenario. The
recommenders' thoughts show it ("The tool only supports position id from the catalogue —
estate-carpenter is a position"). Fixing that alone would have avoided this run's mistakes, but
the two paths would still split on one choice. The aim is a design where a choice made wrongly in
one conversation can still be put right when the person arrives.

The code's reuse rule for candidates is also brittle: exact position ids only, keyed on the
recommender's walking area (so the house and the village never share candidates), and never
anyone already placed.

## 2. Decisions (the user's, 2026-11-08)

1. **One path.** `recommend` takes a free-text `wanted` ("a cook for the manor", "a carpenter to
   build a new dining table"). `send-for` brings whoever it named. Nothing in the record or the
   arrival fixes whether they come for a place or for a job: that is settled when they're here,
   by `engage` (a place) or `order-work` (a work), or by neither. This reverses the trades design's
   reason for keeping the two apart: a mix-up is still possible, but now it can be put right.
2. **A judge decides who is meant**, from `wanted`: someone already known (a contact made before,
   or someone on the map), or someone new, and if new, what sort of person, so that the writer
   can give them belongings to suit (a box and a written character, the tools of a trade, or
   nothing much). No code matching of positions or areas.
3. **Duplicates aren't prevented.** Two carpenters, or two people engaged as estate carpenter, are
   allowed. The judge may reuse a contact who fits; it never has to.
4. **Lead time 2 hours** for everyone, for now (candidates arrive next round today, tradespeople
   after 3 hours).
5. **The visitor decides when to go.** No idle departures: the arrival tells them to `take-leave`
   when they're done, have nothing more to do, or have been turned away. This brings the path
   closer to the other visitors (invited guests).
6. **People already in the world can't be `engage`d.** Only someone who came by `send-for` and
   holds no post can be offered a place. Recommending someone on the map for a *job* stays (the
   village sweep).
7. **The speaking-turn tools are fixed with it:** `recommend` and `send-for` are the same tools in
   a conversation as on a turn.
8. **The generic role is `:caller`** ("Caller").
9. **Visitors who don't leave are left alone for now.** If runs show they don't know how to go,
   that's looked at then.
10. **A public place to sleep.** A world may mark locations as lodging, where anyone without a
    bedroom of their own may sleep (4.11). The Claudeworth worlds get one at the Crown. A world
    without one still works: a visitor with no bed is refused `sleep` as now, and complains or
    takes leave.
11. **The latest recommendation wins.** If one contact is recommended to the same person for
    different things, the latest `wanted` replaces the earlier one; `send-for`'s `about` overrides
    both.
12. **`send-for` doesn't end the turn**, so it can be offered on speaking turns.

## 3. For the characters

1. **Asking.** Lady Claudeworth asks Caroline whether she knows a joiner. Caroline calls
   `recommend {wanted: "a joiner to mend the split stair tread", for: "lady-claudeworth"}`, on a
   speaking turn or her own. It's a query, offered on speaking turns as now; the person told must
   be present.
2. **The answer.** Both learn a contact: "Horace Barker (contact-7), a joiner of Littleport. To
   have him come, use send-for with id "contact-7"; he is expected at the Front yard 2 hours later."
   Caroline alone gets what she knows of him (the reputation bridge), as now.
3. **Sending for them.** `send-for {id: "contact-7"}`, with an optional `about` ("to mend the stair
   tread and look at the back door"), which defaults to the `wanted` that recommended him to the
   sender. The sender and the person sent for get calendar entries, and the event says what they
   are coming about.
4. **Arrival.** He arrives at the meeting place: "Horace Barker (contact-7) arrives at the Front yard,
   sent for by Lady Claudeworth: to mend the split stair tread." His view's `you.visit` says who sent
   for him and why. The same words reach whoever is there and the sender.
5. **Settling it.** Whoever is dealing with him decides what he's there for, whatever was asked at
   first: a job is given with `order-work` (or simply done with `spend-time`), and a place is offered
   with `engage`. He may accept, haggle or refuse either. If Lady Claudeworth decides she wants an
   estate carpenter after all, she can `engage` him; if a cook sent for "a place" is asked to bake
   for a dinner instead, that's fine too.
6. **Going.** He leaves with `take-leave` when the job is done, nothing is wanted of him, or he's
   been refused, optionally with a return time, as guests can now. Taking a place ends his visit;
   he becomes one of the household.

## 4. The engine

### 4.1 Contacts

One record in `[:state :contacts]` for everyone recommended, replacing `[:state :pool]`:

```clojure
{:id "contact-7" :created 7
 :name "Horace Barker"
 :line "joiner"            ; the judge's: what they do or seek, plainly ("cook", "joiner", "lady's maid wanting a place")
 :based "Littleport"       ; where they live or work from, off the map; nil for someone on the map
 :known-as "a joiner of Littleport"  ; by repute, public
 :record {...}             ; people-server record; nil for someone on the map
 :agent-id "horace-barker" ; an away agent from the moment they're made (trades-design section 7)
 :local? false             ; someone on the map
 :works-at "..."           ; for someone on the map
 :known-to #{"caroline-stone" "lady-claudeworth"}
 :wanted {"lady-claudeworth" "a joiner to mend the split stair tread"}} ; per knower, the latest
```

- No `:status`: whether they're expected, here or gone is their visit's (`visits`) and their agent's
  status, and whether they're placed is their post. Fewer states to keep in step.
- Ids `contact-N`, from a contact counter. The cap (40) and the eviction rule stay as trades has them
  (only contacts nobody has in play).
- Someone new is written at recommendation and made an away agent then, as trades does now
  (trades-design section 7): `send-for` stays an effect with no I/O.

### 4.2 The judge (`recommend`'s, from the trade judge)

`trades/judge.clj` becomes the recommendation judge (say `manor.mechanics.contacts.judge`), built as now: a
prompt template, a tool with a schema, and code that checks only the shape. It sees:

- the recommender's own context (who and where they are, their recent lines);
- `wanted`, in the recommender's words;
- `local`: people on the map who work at something, outside the household, by position and
  workplace (as now);
- `contacts`: the contacts made so far who can still come and hold no post, by name, line, based
  and known-as, and whether they're here now. Not who knows them.

It answers exactly one of:

| Answer | Meaning |
|---|---|
| `existing` | the id of someone listed; reuse when they plainly fit, never required |
| `new` | `{line sex age based known-as situation}`: someone new from elsewhere; the people server rolls the name |
| `impossible` | a reason, only when no such person could exist in the setting |

Its instructions say: decide who is meant, never whether the work or the place will happen or what it
costs. When `wanted` is plainly a place in service, it never picks someone on the map, who can't be
engaged (decision 6), but writes someone new or picks an unplaced contact; for a job, someone on the map
is fine (the village sweep). The judge, not code, reads which `wanted` is. If `wanted` is a place in service, `new` is someone seeking a place (`line` "cook seeking a
place"); if it's a job, someone who does that work. If it's unclear, it writes whoever a
recommender would most likely know of for it. Someone on the map is never written again. The
judge has no say over whether two people end up doing the same thing.

`line` replaces both `:seeks` and `:trade`. The people server already takes any position string.
The judge gives sex and age, so `generate-args` doesn't need a catalogue position for them.

### 4.3 `recommend`

- `:args [:map [:wanted [:string {:min 1 :max 120}]] [:for :string]]`. No `position`, no `trade`.
- Reasons: the recipient must be someone else who is present (`recipient-reason`), and there must
  be room for another contact.
- Query: the judge; for `new`, the people server's record, the appearance and the polish, with
  first-arrival belongings (4.6), and the reputation; for an existing contact with a record, the
  reputation if the recommender hasn't one yet. Events are recalculated on the current world,
  as now.
- Events: `:contacts/made` (`:system`, a spawn's private parts), `:people/reputation-known`
  (recommender only), `:contacts/recommended` (both), which records `wanted` against the asker.

### 4.4 `send-for`

- `:args [:map [:id :string] [:about {:optional true} [:string {:min 1 :max 300}]]]`. `about`
  defaults to the `wanted` the sender was recommended them for, or the latest one if they've been
  recommended to the sender more than once.
- A **free** action, not one that ends the turn, so it can be offered on speaking turns (decision 7,
  review item 14). It only schedules (decision 12).
- Reasons as `trades/contact-reason` now: known to the sender, not themselves, not already
  expected, not about already ("speak to them"), and a meeting place. For someone on the map,
  `visits/guest-reason`.
- Effect: a visit by `:sent-for` expected `lead-hours` (2) on, its `:stay` the `about`, with calendar
  entries for both, as `visits/sent-for-events` does now. Someone on the map is told at once and
  comes if they're willing. Someone from elsewhere arrives at the meeting place through
  `hiring/arrival`.

### 4.5 Arrival and role

- Everyone from elsewhere arrives in **one generic position**. `:tradesman` becomes `:caller`
  (title "Caller", decision 8). Its `:about` speaks of being sent for in general and
  points at `you.visit`: talk it over with whoever sent for you or whoever is here for them; you may
  be offered work (`order-work`: `accept-work`, `revise-work`, `decline-work`, `carry-out`) or a place
  (`engage`: `accept-place`, `decline-place`), or neither, and may refuse either; use `take-leave` when
  you're done, nothing more is wanted of you, or you've been turned away. Both packs get it, and
  `not-posts` and `appointments/visitor-roles` list it.
- The arrival situation names who sent for them, what about, and their home off the map, as a
  prompt template, not code (`hiring/arrival-events` builds it in code now).
- The arrival event (`:visits/arrived`, for a visit by `:sent-for`) says why they've come, so the sender
  and those present learn it through the event, the calendar and the visitor themselves.
- `:none` stays for someone dismissed or between places in the world itself.

### 4.6 Belongings

- Everyone new from elsewhere is written with first-arrival belongings (`:belongings-mode :first-arrival`)
  where the scenario has items. The arrival context becomes
  `{:kind :sent-for :line line :wanted wanted}`, from the recommendation.
- `writers/belongings.md` stops assuming an interview: from the line and wanted, the writer decides
  what they'd bring. Someone seeking a place in service may bring a box and a written character;
  a tradesperson brings the tools of the trade, perhaps a card; others, little.
- The reference marker `:candidate-reference?` becomes `:reference?`, allowed for anyone written this
  way (the writer decides whether to use it), and the item's `:candidate-id` becomes `:contact-id`.
  `items/identity-bound?`, `items/views`, `items/work` and `made-review` follow, as does `AGENTS.md`'s
  "Item identity" rule. `hiring/reference-activity` (writing someone a character with `spend-time`)
  takes `contact`.
- The mock polish varies with the line, so mock runs don't always give everyone a box and a reference.

### 4.7 Engaging

- `engage {who position terms bedroom}` is offered for, and takes, anyone **here on a visit by
  `:sent-for` who holds no post** and is a contact (decision 6). Everyone already in the world, and
  invited guests, are refused: "only someone sent for, who holds no post, can be offered a place."
- `accept-place` gives the post, as now, and ends their visit by `:sent-for`.
- The same post filled twice is allowed (decision 3).
- `send-away` takes anyone here by `:sent-for` and unplaced, and ends the visit.

### 4.8 Leaving

- One `take-leave`, visits' own: anyone from elsewhere goes home, optionally with a return time,
  which makes a returning visit, as for guests now. Hiring's own `take-leave`, its return calendar
  entries (`hiring-…` calendar owner) and the 24-hour departure go.
- `trades/idle-departure` goes. Nothing sends a visitor home by itself (decisions 5 and 9). A
  visitor staying the night sleeps at the world's lodging, if it has one (4.11).
- Someone dismissed still leaves by `take-leave` from anywhere, as now; their contact remains, so
  they can be sent for again.

### 4.9 Views and rules

- `contacts` in the view, the contacts the viewer has been told of: id, name, line, based or
  works-at, the `wanted` they were recommended to the viewer for, and `expected`/`here` while
  sent for. `hiring.candidates` goes; `reputations`, `place-offers`, `posts` stay.
- `reference` keeps the meeting place and the positions list (`engage` needs a position id).
- `rules/hiring.md` and `rules/sections/trades.md` become one section: finding someone, sending for
  them, settling it when they come, papers and belongings, leaving. `rules/hiring-arrivals*.md`
  merge into it.

### 4.10 Speaking turns

`ruleset/speaking-actions` takes `recommend` and `send-for` from the scenario's actions, not from
`hiring/actions` directly, so the tools in a conversation are the ones installed (decision 7). A
test checks that a speaking turn's `recommend` and the turn's are the same spec.

### 4.11 Lodging

How sleeping works now: `sleep` is allowed in any location of `:kind :bedroom`, or in the sleeper's
assigned bedroom (`[:state :bedrooms]`, set by `engage`'s `bedroom`, `assign-bedroom`, a world file's
`:bedroom`, or a visit's). Thorne had no assignment, and the lumber room isn't a bedroom, so his
`sleep` was refused three times (review item 13).

- A location may have `:lodging true` in its world file: anyone may `sleep` there, with or without a
  bedroom of their own. Nobody is assigned it, so it holds any number of people.
- `sleep`'s refusal adds where lodging is, when the world has any ("…; anyone without a bed may sleep
  at the Crown's back chamber (crown-chamber)"). The `:caller` and `:guest` texts, or the arrival
  situation, say that lodging exists if it does. No lodging, no mention: the refusal stays as now
  (decision 10).
- The rules (rendered per world) name the world's lodging, so nobody needs to have been told.
- No charge for now. A bill for a night's lodging belongs with the roadmap's "Bills and tradesmen's
  accounts".
- World files: `claudeworth.edn`, `claudeworth-medium.edn` and `claudeworth-small.edn` get a room at
  the Crown (say "Crown's back chamber", `:kind :bedroom :lodging true`, off the public house). The
  Crown's `:desc`, `:store-profile` ("no room bookings") and the publican's room text ("there are no
  guest rooms") change to match. Lodge and the palace may come later. World-file checks: `:lodging` is
  a boolean.
- This doesn't touch whether people may sleep in someone else's bedroom (any `:kind :bedroom` is
  allowed today). That's a separate question.

### 4.12 What else changes

- `works.clj`'s plan text ("ask someone who might know to recommend with trade \"…\"") speaks of
  `wanted`; `works/judge.clj`'s trade becomes a short description to pass to `wanted`.
- `visits.clj`: `returning-situation`, `leave-events`, `dismissed-leave-events` and
  `visits/judge.clj` read contacts, not the pool.
- `appointments.clj`: `visitor-reason` reads the new role and the visit.
- Mock days (`lodge-day.edn`, `claudeworth-day.edn`, `palace-day.edn`) and `ruleset/mock.clj` use
  `wanted`; the mock judge matches a listed line in `wanted` by a simple substring rule, as now (a
  mock, not a judge of prose).
- The worlds' GM `:about` texts that mention candidates and tradespeople.
- `docs/scenario-authoring.md`, `docs/prompts.md`, and superseding notes in `docs/trades-design.md`
  and `docs/people-and-hiring-design.md`.

## 5. What goes

`hiring/pool`, `recommend!`, `reusable`, `recommendation-events`, the candidate statuses, hiring's
`arrival-events`, `tick`, `sent-for`, `departure`'s candidate bookkeeping, `leave-with-return` and
its calendar owner, the `:people/pooled`/`:people/recommended`/`:people/candidate-updated` events,
`trades/idle-departure` and its tick, `docs/hiring/recommend.md`, `docs/hiring/send-for.md`,
`docs/trades/recommend-trade.md`, the separate trades rules section. `hiring.clj` keeps
`person-record`, `write-person`, `spawn-spec`, `arrival`, `engage`/`accept-place`/`decline-place`/
`send-away` and the reputation; contacts become their own namespace (`manor.mechanics.contacts`),
with `trades.clj` folded into it.

Old runs won't load (AGENTS.md: no backwards compatibility needed for a change like this).

## 6. Prompts to write or rewrite

| Template | Change |
|---|---|
| `docs/contacts/recommend.md` | new: `wanted` and `for`; call it before naming anyone; until it names someone you know of nobody |
| `docs/contacts/send-for.md` | new: `id`, `about`; 2 hours; doesn't end the turn; meet them and settle it |
| `docs/hiring/engage.md` | anyone sent for who holds no post |
| `docs/hiring/take-leave.md` | the one shared doc, without the candidate-only parts |
| `rules/sections/contacts.md` | the merged section |
| `judges/recommendation.md`, `judges/tools/recommendation.md` | from `judges/trade.md` |
| `writers/belongings.md` | no interview assumed; belongings follow line and wanted |
| `situations/sent-for.md` | new: the arrival situation, out of `hiring.clj` |
| both packs' `positions.edn` | `:caller` in place of `:tradesman` |

## 7. Phases

Each phase ends with `clojure -M:test` passing and a mock run of `claudeworth-small`.

0. **Speaking turns** (4.10), on the existing code: `recommend` with `trade` and `send-for` in
   conversations. Small, and useful even if the rest waits. **Done:** `ruleset/scenario` builds
   its `:speaking-actions` hook last, over the installed `:actions`, so a line's `recommend`,
   `send-for`, `engage`, `accept-place` and `decline-place` are the turn's (`recommend` and
   `engage` still only to a member); hiring's and trades' `send-for` are `:kind :free` and their
   docs no longer say it ends the turn. Tests in `trades_test.clj`.
1. **Contacts.** The one record, the judge, `recommend {wanted for}`, the view; the pool goes.
   `send-for` and arrival for everyone through visits, the `:caller` role, the arrival situation and
   event, `engage` on contacts, one `take-leave`, no idle departures. In three parts, each passing
   the suite and committed on its own; the old candidate path keeps working until 1.3:
   - **1.1 The record and the judge, beside the old path.** `trades.clj` becomes
     `manor.mechanics.contacts` (with `contacts/judge.clj`), the trade judge the recommendation judge
     (4.2: `line` for `trade`, the place-or-job rule for people on the map, placed contacts left out
     of its list). `recommend` takes `wanted` in place of `trade`, and still `position` for the old
     candidate path until 1.3. Contacts keep `:wanted` per knower (4.1, decision 11). `send-for` takes
     `about`, defaulting to `wanted`; the lead time is 2 hours. `:tradesman` becomes `:caller` in both
     packs and everywhere it's named. The view's `contacts` show line and wanted (4.9).
     **Done:** `manor.mechanics.contacts` and `contacts/judge.clj` (adjudicator
     `:contacts/recommendation`, tool `recommendation`, `judges/recommendation.md`); `recommend`
     takes `wanted` (1-120) or `position`, exactly one; `:contacts/recommended` carries `:asker` and
     `:wanted`, kept per knower in the contact's `:wanted`; `send-for {id about?}` for a contact
     (1-300), `:contacts/sent-for` for `:trades/sent-for`, lead 2 hours; `:caller` in both packs;
     the view's `contacts` add `line` and the viewer's `wanted`. Differences from the plan: only the
     asker (`for`) gets `wanted` recorded, not the recommender, since it's what they asked for;
     "holds no post" for the judge's list means a post other than `:none` or `:caller`; the idle
     departure and its rules text (`rules/sections/contacts.md`, still headed "Tradespeople") stay
     until 1.2; `you.visit` still says `job` for a visit by `:sent-for` (1.2's arrival work); the
     doc fragments are `docs/contacts/recommend.md` (appended to hiring's `recommend` doc, for
     now) and `docs/contacts/send-for.md`. Tests in `contacts_test.clj`.
   - **1.2 Settling at arrival**, in two commits (a first attempt at it in one timed out):
     - **1.2a Arrival.** The arrival situation from a template (`situations/sent-for.md`), passed into
       visits by contacts rather than visits requiring contacts (they require it); the arrival event
       and `you.visit` saying what they came about (`about`, not `job`) (4.5); the `:caller` text; the
       contacts idle departure goes, so contacts leave with visits' `take-leave` (4.8).
       **Done:** `contacts/arrival-situation` renders `situations/sent-for.md` (`name`, `home`, `by`,
       `house`, `about`) at `send-for` for a contact from elsewhere; `visits/sent-for-events` keeps it
       on the visit as `:situation`, and `visits/coming` gives it on arrival (else
       `returning-situation`, as before). Someone on the map keeps their own situation. The
       `:visits/arrived` of a visit by `:sent-for` carries `:host` and `:about`, narrated "…, sent for
       by X about this: …"; `you.visit` says `about`. The calendar entries already said the about.
       `:caller`'s `:about` in both packs matches the template. `idle-hours`, `idle-departure`,
       `tick` and the `:contacts/tick` system went, and with them `works/engaged?` (unused
       elsewhere); `works/open-work?` still keeps a worker out of hibernation and `keeps-awake?`
       a visitor here. Differences from the plan: the situation is rendered when they're sent for,
       not at arrival (so it names the sender then); `visits/house` is now public for it. Tests in
       `contacts_test.clj` (the tuner stays hours later, then `take-leave` sends him home) and
       `works_test.clj` (the idle-departure test became one that `take-leave` stays allowed with
       open work).
     - **1.2b Engaging.** `engage`, `accept-place`, `decline-place` and `send-away` on contacts here by
       `:sent-for` with no post, refusing established agents (4.7), and accepting ends the visit.
       `contacts/install` wraps hiring's versions, as it wraps `recommend` and `send-for`, since
       hiring can't require contacts or visits.
       **Done:** `contacts/install` wraps all six (marked, idempotent); a candidate here goes to
       hiring's, anyone else must be `contacts/sent-for-here?` (a contact whose visit by `:sent-for`
       is `:here`, post nil, `:none` or `:caller`). Hiring's checks and effects were split into
       shared parts: `candidate-here?`, `offer-terms-reason`, `answer-reason`, `held-reason` and
       `place-taken` (`accept` adds the pool's `:engaged`); visits gained `ended-events`, which
       `leave-events` now uses. `accept-place` for a contact is `place-taken` plus the visit ended;
       the post-changed event makes the role the post, so `appointments/visitor-reason`, `take-leave`
       (`from-elsewhere?`), `keeps-awake?`, `you.visit` and the contacts view's `sent-for` all follow.
       `send-away` for a contact is `visits/leave-events` with the reason "sent away" and the offer
       closed. Differences from the plan: one refusal text covers `engage` and `send-away` ("only
       someone sent for, who holds no post, can be offered a place or sent away"); `accept-place` and
       `decline-place` for someone neither a candidate nor sent for say "only someone sent for, who
       holds no post, can take a place offered" (after "nobody has offered you a place"); `send-away`
       refuses oneself ("use take-leave"). The `engage` and `send-away` docs say someone sent for.
       Tests in `contacts_test.clj`.
   - **1.3 The pool goes.** `recommend {wanted for}` only. The pool, its events and statuses,
     `recommend!`, `reusable`, hiring's arrival, tick, `sent-for`, its own `take-leave` and return
     calendar entries go (section 5); the view drops `hiring.candidates`; visits, `visits/judge` and
     appointments read contacts. The mock policy and mock days use `wanted`. To keep belongings
     working, new contacts are written with first-arrival belongings using the existing writer
     prompt, and the reference marker points at the contact id (phase 2 does the rest).
     With no candidates left, contacts defines `recommend`, `send-for`, `engage`, `accept-place`,
     `decline-place` and `send-away` outright instead of wrapping hiring's, and hiring keeps only the
     shared parts (making people, `arrival`, the post).
     **Done:** `contacts/actions` defines all six (installed in place of any of the same names);
     `hiring.clj` keeps making people, reputations, arrival, offers and `place-taken`, return times,
     `reference-activity` and `install` (the agent cap, `:hiring-belongings?`, the `spend-time`
     extension); the pool, its events, tick, `:people/visits` system, `:hiring` calendar owner,
     `rules-beside-visits` and `rules/hiring-arrivals*.md`, `docs/hiring/recommend.md` and
     `send-for.md` went. Visits' `take-leave` is one plain spec; `returning-situation`,
     `leave-events`, `visits/judge` and `appointments/visitor-reason` read contacts
     (`hiring/contact-of`, so visits needn't require contacts); a `:caller` refused `appoint` is
     told to use `engage`. Belongings: a new contact from elsewhere in a scenario with items is
     written with `:arrival-context {:kind :sent-for :line :wanted}`, kept as the contact's
     `:pending-belongings`, and `visits/coming` adds `hiring/first-arrival-events` at the first
     arrival (then `:people/belongings-brought` clears them); `belongings/problem` allows
     `:candidate-reference?` for `:sent-for`; items keep `:candidate-id`, now the contact id.
     `spend-time`'s argument is `contact` (doc `docs/hiring/spend-time-contact.md`), checked
     against `:known-to`. The mock judge gives "<title> seeking a place" for a `wanted` naming a
     catalogue position (the longest title it contains), of that position's sex and youngest age,
     reusing a listed contact with that line; Lodge's mock day hires its housemaid through
     `"@contact"`/`"@caller"`, two hours after sending for her. Differences from the plan:
     coming back at a time given to `take-leave` (a visit `:by :returning`) still counts as sent
     for, so they may be engaged then, and they keep their own situation rather than
     `returning-situation`'s; `rules/hiring.md`, the `engage`, `send-away`, `take-leave`, `recommend`
     and `send-for` docs, `writers/belongings.md`, the England belongings fragment and the worlds'
     GM texts were rewritten minimally (no candidates, no pool, no "next round"), the merged
     section staying for phase 3; the people server still takes `candidate` (unused). Tests:
     `hiring_test` and `arrival_belongings_test` rewritten on the visits world; reuse, eviction,
     the 24-hour departure and the return calendar entries' tests went.
1b. **Lodging** (4.11): the `:lodging` flag, `sleep`, the refusal, the three Claudeworth world files.
   Independent of the rest; can go before or after.
   **Done:** `places/lodgings` (sorted ids of `:lodging true` locations); `sleep` allows them, and
   its refusal adds "; anyone without a bed of their own may sleep at <name> (<id>)" (several joined
   with " or "); `rules/sections/sleep.md` takes a `lodging` parameter, the new
   `sleep-lodging.md` or empty; `ruleset.init/problems` checks it's a boolean. The Crown's back
   chamber (`crown-chamber`, `:kind :bedroom`, `:domain :village`, off `public-house`) in the three
   Claudeworth worlds; the Crown's `:desc` says the publican lets it, its `:store-profile` that a bed
   is arranged with the publican, not bought over the bar; "There are no guest rooms" went from the
   publican's room. Differences from the plan: the `:caller`/`:guest` texts and
   `situations/sent-for.md` don't mention lodging (the rules, which every visitor reads, do); the
   refusal stays in code beside the other `sleep` reasons.
   Tests in `claudeworth_test.clj` (`lodging`, `claudeworth-worlds-have-lodging-at-the-crown`, and
   the no-lodging refusal in `sleeping-pastimes-and-rousing`).
2. **Belongings** for everyone written, the reference marker renamed, the mock polish varied.
3. **Prompts and docs:** the merged rules section, the docs of section 6, `works`' plan text, worlds'
   GM texts, `scenario-authoring.md`, `prompts.md`.
4. **One short real run** of `claudeworth-small` (8–12 rounds, `:max-cost` 0.20) starting with a
   need for both a servant and a job, to see whether the judge's choices and the settling at arrival
   hold up. The user's go-ahead first.

## 8. Tests

Rewritten: `hiring_test`, `trades_test` (into a contacts test), `arrival_belongings_test`.
Touched: `visits_test`, `works_test`, `conversation_test`, `engine_test`, `lodge_test`, `shop_test`,
`appointments_test`, `palace_test`, the scenario smoke tests.

New cases: someone recommended for a job is engaged; someone recommended for a place is given a work;
an established agent can't be engaged; someone on the map recommended for a job is told, not spawned;
`about` defaults to `wanted`; two contacts for the same line; `send-for` on a speaking turn; no idle
departure; `take-leave` with a return; a visitor sleeping at lodging, and refused with lodging named or not,
in a world with and without it; replay of all of it; privacy (only the recommender's
reputation, the judge's lists show no knowers).

## 9. Open questions

1. **Placed contacts.** The judge's `contacts` list leaves out anyone who holds a post now (they're
   in the household, and asked directly). I'd keep it that way.
2. **The judge reading place against job** (4.2). It decides from `wanted` alone whether someone on
   the map may be picked. A miss sends word to someone who then can't be engaged; the asker can
   recommend again, and it stops nothing else. Worth watching in phase 4's run, not more machinery.
