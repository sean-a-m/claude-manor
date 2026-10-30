# Relations: how one person feels toward another

Status: **designed**, not started. The user settled the open questions on 2026-10-29
(section 7); sections 3–6 are written to those decisions. It takes up the follow-up "How people regard
each other" in roadmap B13 and decision 8 of `inner-state-design.md`.

## 1. The want

Each person keeps a private record of how they've felt toward each other person, and why:
Nellie is stung by Mrs Thorne because she scolded her for spilling the sugar; later she's
grateful because Mrs Thorne covered for her with her ladyship. The judges write it at the
points where they already judge inner state. The person reads it and lets it colour how they
treat the other. Nobody else sees it.

Three things set it apart from the other per-agent fields:

1. **It grows during the run.** It holds an entry for anyone the person has had a reason to
   feel something about, and visitors, new hires and candidates appear mid-run, so entries for
   new people are added whenever they're first needed. Nothing is set up in advance.
2. **Where it goes in the prompt matters for the cache and for tokens.** It could sit with the
   person's own data or on the other people in their view.
3. **The direction must be clear.** `{feeling "sad", reason "She lost your eyeglasses"}` has to
   read as the viewer's feeling toward the other person, never the other way round.

## 2. What exists that this builds on

- **Inner state** (`manor.mechanics.inner`): private keyed phrases, changed only by judges,
  through GM-only `:person/inner-changed` events. Relations should follow the same pattern:
  the same judges, the same privacy, its own event.
- **The judges that see inner state:**
  - the work judge, for `spend-time` (its `on` people) and the immediate `act-on`. It judges
    the worker and the people named;
  - the conversation judge, which judges the members once the conversation ends;
  - the nightly steward. It sees sleepers alone, with nobody to feel anything toward, so it
    adds no relations and doesn't touch them (decision 9).
  The first two already see each judged person's temperament, persona, situation and inner
  state.
- **The request layout** (`manor.prompt`): the system message (preamble, role, who you are,
  situation, reference), then tools, the calendar and the recent-events window are the cached
  prefix. The time, **the view**, the notes and the closing come last, are rebuilt on every
  call and are never cached.
- **Agent ids are stable.** Spawned, hired and visiting people get ids that stay theirs when
  they go away, come back, retire or die. That makes the id the natural key.

## 3. Data

On the holder's agent:

```clojure
:relations {"mrs-thorne" [{:feeling "stung"
                           :reason "Mrs Thorne scolded you for spilling the sugar"
                           :round 14
                           :source {:conversation "conv-7"}}
                          ...]          ; oldest first, at most :keep (20) entries
            "daisy"      [...]}
```

- A map from the other person's id to a vector of entries, oldest first. A vector with the
  oldest dropped is the FIFO queue, and the reducer stays pure and simple.
- **Growth:** the reducer creates a person's vector the first time an entry names them. Nobody
  is pre-filled. World files and `:init` agents may give starting entries (decision 6), each
  with `:round 0` and a source of `{:start true}`.
- **Valid targets:** any agent in the world except the holder and the GM, whatever their status
  (away, retired, dead). Grief and old grudges toward people who aren't there are legitimate.
  People who aren't agents (an unnamed vicar mentioned in passing) can't be targets: there's no
  id to key them by.
- **Append only.** A judge can't edit or remove an entry. A change of heart is a new entry
  ("relieved: Mrs Thorne found your eyeglasses after all"), and old entries go only when the
  queue is full. The latest entry is the present feeling, and the earlier ones are the history
  behind it.
- **Opt-in** with a scenario entry, as for `:inner`:

  ```clojure
  :relations {:keep 20}    ; entries kept per person toward each other person
  ```

  How many entries the view and the judges are shown are constants in
  `manor.mechanics.relations` (`view-entries` 3, `judge-entries` 5), so they can be tried at
  other values without touching the scenarios (decisions 2 and 5).

  It doesn't require `:inner`, but the build assumes the scenarios that use one use both.
- **The event:** `{:type :person/relations-changed :visible-to :gm :data {:person holder
  :source src :entries [{:toward id :feeling f :reason r}]}}`, one per holder per verdict. The
  reducer appends each entry with the event's round and source and trims to `:keep`. The
  reducer needs `:keep`, but reducers don't see the scenario, so the event carries it
  (`:keep 20`) and replay stays pure (decision 8).
- **Exports:** `map.edn`, the GM view (the latest entry per pair, to keep it short), each
  person's panel page and the transcript (narration: `Nellie, toward Mrs Thorne (conversation
  conv-7): stung, "Mrs Thorne scolded you for spilling the sugar".`).

### Size

20 entries of about 25 tokens is about 500 tokens per pair. In a household of 10, a
well-connected person could hold 4,000–5,000 tokens. That's fine in the world, and it's why
neither the agent's view nor the judges get the whole queue (sections 5 and 6).

## 4. Where it goes in the prompt: the cache

**The cache isn't the problem, provided relations go in the view and not in the system
message.**

- **In the system message** ("Who you are"), every new entry would change the start of the
  request, and the system message, tools and events window would all miss the cache on that
  person's next call. With about 2 conversations a round and several members each, someone's
  relations would change most rounds. That's a full uncached prompt for each person affected,
  about 3–4× the price of a mostly cached one. Not ruinous, but pointless.
- **In the view** it costs nothing in cache hits. The view already changes every call (the
  time, `doing`, aspects, carried things, inner state), so it's never cached. Adding
  relations there adds only its own tokens.
- **The real cost is tokens.** Everything in the view is paid at the uncached price on every
  call: every turn and every speaking turn in a conversation. That's why the view shows only
  a few recent entries, and only for the people present (section 5). At 3 entries a person
  it's about 75 tokens per person present, so usually 0–300 tokens a call. Against a prompt of several thousand tokens, that's
  a few percent more per agent call, which is the only cost relations add to agent calls.

So: relations go in the volatile end of the request with the other data that changes, and
what matters is how much of it to show, not whether it breaks the cache.

## 5. How the person sees it: direction

Two placements, both in the view:

**A. On each person in `people-here`**, as the user suggested:

```json
"people-here": [{"id": "mrs-thorne", "name": "Mrs Thorne", "doing": "...",
                 "your_feelings_toward_her": [...]}]
```

It's natural to read beside the person, but:
- `space/person` is shared code (the scene judge's `people`, `look`, anything that lists people
  here). The entry would have to be added in `prompt/agent-view` after the fact, never inside
  `space/person`, or it leaks into other people's requests. That's doable, but it's the kind
  of path that leaks later.
- Someone who can't see has no `people-here` (`physical/view-by-sight`), but can still be in a
  conversation and still feel things toward the people in it.
- People who aren't present (where to go, whom to avoid) would need a second place
  (`last-seen` entries), so it ends up in two places anyway.
- A key that gets the direction right has to pick a pronoun ("toward her") or turn generic
  ("toward_them").

**B. Under `you`, keyed by person (chosen, decision 1):**

```json
"you": {"name": "Nellie Briggs",
        "inner": {"mood": "..."},
        "feelings_toward": {
          "Mrs Thorne (mrs-thorne)": [
            {"at": "Sat 09:30", "feeling": "stung", "because": "Mrs Thorne scolded you for spilling the sugar"},
            {"at": "11:00", "feeling": "grateful", "because": "Mrs Thorne covered for you with her ladyship"}]}}
```

- Everything under `you` is already about the viewer (inner state, aspects, physical
  state), so the direction comes from where it sits. `feelings_toward` plus the person's name
  as the key leaves little room to read it backwards.
- It's built in one place, from the holder's own record only, so the privacy rule is
  "relations are read only in `agent-view` under `:you`", as for `:inner`.
- It works for people who can't see, and would work for people who aren't present if
  they're ever shown.

**Which people:** only the people present (and in a conversation, every member, which comes
to the same), each with their latest `view-entries` (3) entries (decisions 2 and 3). People
the person has no entries for aren't listed. Feelings toward people who aren't there aren't
shown; their recent events and notes say if something happened lately, and a later `recall`
tool (roadmap) will let them look up more.

**How a reason is written** (this is the other half of direction, and the judges write it):

- **Second person for the holder, the other person by name:** "Mrs Thorne scolded you for
  spilling the sugar", not "She scolded me" or "I was scolded". It matches the rest of the
  view, which calls the viewer "you". Naming the other person avoids a pronoun that might
  point at the wrong person or the wrong gender. The judge has their name.
- **`feeling` is the holder's feeling**, one word or a short phrase ("stung", "warmly
  grateful", "jealous of her new post"), never a description of the other person ("cruel").
- **The JSON key is `because`, not `reason`.** "Felt stung because Mrs Thorne scolded you"
  reads in one direction only.
- **Rules text** (Claudeworth's `rules/sections/people.md`, in this sense): "Under `you`,
  `feelings_toward` lists how *you* have felt lately toward people who are with you, and why,
  oldest first; the last is how you feel now. They're your feelings, not theirs, and nobody else
  knows them unless you show it."

## 6. The judges

No new calls. The conversation judge and the work judge each get one more answer field:

```
relations: [{person, toward, feeling, because}]
```

- **Who may get entries** (`person`): the people the judge already judges: the conversation's
  members, and the worker and the people named in `spend-time`/`act-on`.
- **Toward whom** (`toward`): only the other judged people in the same scene. People who were
  only talked about get no entries (decision 4).
- **What the judge sees** (an addition to each judged person's entry in the request):

  ```json
  "feelings_toward": {"mrs-thorne": [{"at": "Sat 09:30", "feeling": "stung",
                                      "because": "Mrs Thorne scolded you for spilling the sugar"}]}
  ```

  Only toward the other judged people in the scene, the latest `judge-entries` (5) per pair
  (decision 5). The judge
  needs the history to tell a new feeling from the same one again, and to see how the feeling
  has been going.
- **Checks** (structure only): `person` and `toward` are judged ids and differ; `feeling` is at
  most 40 characters, `because` at most 160; at most one entry per (person, toward) pair per
  verdict; a bad answer is sent back once, as now. Nothing is checked about what the words say.
- **Instructions** (`resources/prompts/judges/relations.md`, shared by both judges, in this
  sense):

  > Each judged person's `feelings_toward` is how they have felt toward the other people here,
  > oldest first; the last is how they feel now. Add an entry only when this changed how
  > someone feels toward someone else, or gave a fresh reason for a feeling they already have.
  > An empty `relations` list is a normal
  > answer. Judge it from what that person experienced, given their temperament and how
  > they felt before.
  > `feeling` is the person's own feeling, in a word or a short phrase. `because` says why,
  > to that person: call them "you" and the other person by name ("Mrs Thorne scolded you
  > for spilling the sugar"), and only from what they experienced, never from another's inner
  > state or hidden intentions.

  The last clause matters for privacy: the judge sees everyone's inner state, and a `because`
  like "Mrs Thorne secretly resents you" would hand Nellie a fact she can't know.
- **Inner state and relations together:** a rebuke might set Nellie's `mood` ("stung, near
  tears") and add an entry toward Mrs Thorne. That overlap is right: one is how she is, the
  other is toward whom. The instructions say so in a line, so the judge neither duplicates
  everything nor puts feelings about people only in `mood`.
- **Mocks:** the default mock judges add none, so existing mock days don't change. The
  dressing fixture's mock conversation judge adds one.

**Cost:** the judge prompts grow by perhaps 200–600 tokens (the instructions, which are cached,
plus the histories), and answers by a few dozen tokens. With the judges about 10–15% of a run,
that's roughly 1–2% more per run, plus the view tokens in section 4.

## 7. Decisions (the user, 2026-10-29)

1. **Placement:** B, under `you` as `feelings_toward`, keyed by person.
2. **The agent sees** the latest 3 entries per person present. A `recall` tool to look up
   more about someone (at first all the entries toward them, more later) goes on the roadmap.
3. **People not present** aren't shown. Other logs say if something happened lately, and
   `recall` will help later.
4. **Targets are the members only** (and in work, the judged people). Things that involve
   third parties aren't relevant yet.
5. **The judges see** the latest 5 entries per pair. The view's 3 and the judges' 5 are
   constants in one place, so they can be experimented with.
6. **Starting relations** are allowed in world files and `:init`. A later pass may clean up
   feelings now written into other descriptions (personas, situations) and move them here.
7. **`feeling` is free:** a word or a short phrase, at most 40 characters. No list.
8. **`:keep`** is a scenario setting, carried on each event so replay stays pure.
9. **The steward** doesn't touch relations.
10. **Overheard and witnessed events** are out of scope.
11. **The judges see others' relations** among the judged people in the scene, as section 6
    describes.
12. **No symmetry nudges.** One person's feeling never implies the other's.

## 8. Build

One phase:

- `manor.mechanics.relations`: schema, checks, judge view, answer entry, event, reducer,
  narration; scenario `:relations`; world-file shape check and export.
- `prompt/agent-view` adds `:you :feelings-toward` for the people present;
  `default-gm-view` and the panel show it.
- The conversation judge and the work judge get the `relations` field, the history in each
  judged person's entry and the shared instructions; `judged-events` emit
  `:person/relations-changed`.
- Claudeworth's `:relations` entry and the rules sentence; `docs/prompts.md` and
  `docs/scenario-authoring.md` updated; world-file and `:init` starting entries.
- Tests (dressing fixture): entries for a new person are created on first use; the queue
  trims at `:keep`; ids that aren't judged, self-entries and the GM are refused; a holder's
  relations appear only in their own request (built for every agent in a mock day); only the
  people present are shown, at most 3 entries each; the steward leaves relations alone; starting
  entries load and export; replay equals the live world.
- Then a short real Claudeworth morning run (with the user's go-ahead, `:max-cost` 0.20) to
  read how the judges write `because`, whether direction is ever confused, how often entries
  are added, and what it costs.
