# Changing posts: reassignment and dismissal

Status: **draft, revised with the user's first answers** (section 8). Nothing built. Open
questions are in section 9.

## 1. What exists now

- A person's job is their `:role` (a position id) plus their post in `[:state :posts id]`
  (`{:position :household :since :terms :conditions :employer}`). One event changes both:
  `:people/post-changed` (`manor.mechanics.positions/post-changed`).
- It's emitted at the start of a run, by hiring's `accept-place`, by deliveries (a courier's
  post) and by the operator's `change-post!`. **No agent can change an existing person's post.**
- **There's no hierarchy in the data.** Who answers to whom is only in each position's `:about`
  prose ("You answer to the housekeeper, or to the lady of the house where there is none"). It
  stays that way: this design adds none.
- Once a role changes, these follow it automatically: the brief's `:about`, the title and form
  of address, capabilities (and so tools with `:needs`), the trades judge's workplaces. The
  bedroom, routine, `:situation`, persona and inner state don't follow it.
- Actions can already be gated by a model: an action's `:admission` names an adjudicator that
  answers `{:allowed true}` or `{:allowed false :reason ...}` before any effect
  (`engine/resolve.clj` `prepare-action`; the spend-time activity check,
  `mechanics/items/activity_check.clj`, is the one user). The post check reuses that hook.

## 2. Goals and non-goals

- A designated appointer can move anyone to another post, with immediate effect, from
  anywhere, and can dismiss them.
- Who may appoint is a **capability on positions**, not a hierarchy. An appointer who makes an
  odd appointment (the cook made housekeeper by the hall boy's say-so) causes a social problem
  for the agents to deal with, like a servant spending the household account. It isn't a
  constraint.
- **A light judge** stops only the absurd: the butler dismissing the lord, a housemaid
  "appointing" the earl as scullery maid. It works for any positions, including the ones agents
  may define later, without each scenario writing out a hierarchy.
- Not now: resignation, a hierarchy, agents creating positions, several posts per person,
  appointers outside the household (the publican and his barmaid, Q11 in section 8).

## 3. Who may appoint: the `:appoints` capability

A position with `:capabilities #{:appoints}` gets the tools; no one else sees them (the existing
`:needs` mechanism). That includes the "lower" roles that, by their `:about`, direct someone:

| Pack / world | Positions with `:appoints` |
|---|---|
| england-1904 catalogue | `:earl` `:countess` `:mistress` `:butler` `:housekeeper` `:head-housemaid` `:cook` `:head-gardener` `:coachman` |
| claudeworth-medium `:roles` | `:baron` `:baroness` `:butler-valet` (its own entries, which don't inherit the catalogue's) |
| qing-palace catalogue | `:consort` `:chief-eunuch` |

Notes:

- `:cook` because the kitchen and scullery maids assist her, `:coachman` for the grooms under
  him, `:head-gardener` for the garden lad. `:cook-general` (Lodge) has nobody under her, so no.
- Not `:secretary` or `:tutor` (they direct no one), not `:son-` or `:daughter-of-the-house`,
  not `:noble-lady`. These are judgement calls (Q1).
- A world file adds or removes it per position by overriding `:capabilities` in `:roles`, as
  for anything else. Note that `merge` replaces the whole set, so a world adding `:appoints` to
  a shop position has to restate `:shop-clerk` (scenario-authoring should say so).
- Code doesn't check *whom* an appointer moves beyond structure. The judge does the rest.

## 4. Actions

### `appoint` (appointers) — reassignment, immediate

`appoint {who position reason? terms?}`, kind `:final`, `:roles :all`, `:needs :appoints`,
`:admission :people/post-check`.

Code checks (structure only):

- `who` is an active participant, not the GM, **not the actor** (no self-appointment, which
  keeps resignation out too: Q2),
- `position` is in the run's positions and isn't `:gm`, `:guest`, `:tradesman` or `:none`
  (`hiring/not-a-post-reason` already covers these; dismissal has its own tool),
- `who` doesn't already hold `position`,
- `terms`, `reason` are bounded text.

Effect: one `post-changed` with `:employer actor`, the household of `who`'s present post (or
the actor's if `who` has none), visible to **both** of them. And an `:agent/updated` that
replaces `who`'s `:situation` with one engine line ("Lady Claudeworth has made you head
housemaid: …terms"), as `accept-place` does now. Nobody needs to be present.

### `dismiss` (appointers)

`dismiss {who reason?}`, kind `:final`, `:needs :appoints`, `:admission :people/post-check`.
Same checks on `who`; `who` must hold some post other than `:none`.

Effect: `post-changed` to `:none`, household nil, `:employer actor`, visible to both, plus the
`:situation` line. What else dismissal does is Q3/Q4 below.

### The GM's `change-post` (for debugging and story events)

`change-post {who position household? terms?}`, `:roles #{:gm}`, no judge: the operator's
`change-post!` as a tool, and `:none` allowed. Visible to `who` (and nobody else, as the
operator's).

### No `give-notice`

Self-resignation is out for now (user's answer 6). The GM or operator can do it.

## 5. The post check (judge)

A new adjudicator `:people/post-check`, built like `activity-check`: one narrow call on the
run's `:judge-model`, one tool `check-post` answering `{:allowed bool :reason?}`, a reason
required when refused, checked by `adjudication/answer`. Mock: always allowed (tests supply
refusals). Failure: a neutral reply, nothing changed.

**What it sees** (no private state: no persona, inner state, relations, history):

```json
{"action": "dismiss" | "appoint",
 "by": {"name": "...", "post": "Butler", "about": "...", "household": "household"},
 "who": {"name": "...", "post": "Earl", "about": "...", "household": "household"},
 "to": {"post": "Head housemaid", "about": "..."},
 "reason": "the appointer's own words, if any",
 "setting": "the pack's one-line setting"}
```

**Its standard** (prompt `resources/prompts/judges/post-check.md`): permissive. Allow
anything a person in the appointer's place could plausibly do, even if improper, unwise,
unkind or against custom: those are the characters' business. Refuse only what is absurd or
impossible in the setting: a servant dismissing or reassigning their master or mistress, someone
appointing a person outside any power they could have (the cook dismissing the vicar), or a
post the person plainly can't hold (a child of eight made coachman is borderline; the earl made
scullery maid by the footman is not). Appointers may change each other (the earl may move the
butler, and the countess the housekeeper); the judge decides whether this pair makes sense. The
reason it gives goes back to the appointer as the rejection.

As with the activity check, the proposal is data, not instructions, and code never reads its
prose.

## 6. Getting it into others' contexts

- Both parties see the `post-changed` event among their recent events at their next turn,
  wherever they are, narrated "X now holds the post of …, on these terms: …". A line
  "appointed by Y" is added to the narration when `:employer` is set.
- The appointee's `:situation` is replaced, so the change stays in their prompt after the event
  scrolls out of their recent history.
- The appointee's view shows their post (`:hiring :posts`, already there). The appointer's view
  shows posts where they're the `:employer` (already there), so they remember whom they've
  appointed.
- Nobody else is told (user's answer 9). Others learn by talk.
- A hibernating or sleeping appointee sees it when they next take a turn. If being appointed
  should wake a hibernated person, that's Q6.

## 7. What else changes

- **Routine:** left alone. Only village people (shopkeepers, the constable and so on) have
  routines, and none of them hold `:appoints` or would be appointed in practice.
- **Bedroom:** unchanged on `appoint`. On `dismiss`, see Q3.
- **Hiring:** unchanged. `engage` stays open to anyone, by people-and-hiring decision 5. Should
  it now need `:appoints`? That's Q5.
- **Mock policy:** a scripted `appoint` and `dismiss` in a test fixture, not in the mock day.
- **Docs:** `docs/scenario-authoring.md` (the `:appoints` capability, the `merge` note), the tool
  docs in `resources/prompts/docs/people/`, the judge prompt, `docs/prompts.md`.

Phases: (1) capability, `appoint`, `dismiss`, the judge, the GM tool, tests (mock, replay,
the judge's refusal through a test verdict). (2) Whatever Q3–Q6 decide. A short real run of the
judge on a few deliberately odd appointments, with your go-ahead.

## 8. The user's answers (2026-11-07)

1–2. No hierarchy: designated appointers, including lower roles that direct someone.
Inappropriate appointments are a social problem, not a constraint.
3. Not applicable.
4. Changes take effect at once.
5. No notice period (the bedroom question is Q3 below).
6. No self-resignation for now. Dismissals, and appointments, go through a light judge.
7. Anywhere, for now; the change must reach the other agent's context.
8. Routines: ignore.
9. Only the two people hear of it.
10. A GM tool, yes.
11. Not now (the explanation is below).
12. Appointers may change each other, subject to the judge: slightly inappropriate goes ahead,
    absurd is refused.

**On 11, why the draft tied it to households.** The publican doesn't *need* a household. In the
first draft, code limited an appointer to people in their own household because, with no
hierarchy and no judge, that was the only boundary available. Without it, a publican with
`:appoints` could have reassigned the manor's housemaids. Now that the judge decides whom an
appointer may move, that check isn't needed: giving `:publican` the `:appoints` capability is
all it would take, and the judge would refuse him the manor's servants. What remains is minor.
A dismissed barmaid goes to `:none` with household nil, which she already has. "Their
employer" for the judge comes only from the `:about` prose ("you work for the publican" in the
barmaid's), which is enough for a loose judge. So 11 is a one-line change, left out for now
as you said.

## 9. Open questions

- **Q1. The appointer list** in section 3: right as it stands? In particular `:cook`,
  `:coachman`, `:head-gardener` (each directs one or two people), and whether `:secretary`
  should act for the earl.
- **Q2. Self-appointment** is refused in code (actor ≠ who), which also rules out resigning by
  "appointing" oneself to `:none`. Is that right, or should the judge decide (e.g. the countess
  taking over the housekeeping herself)?
- **Q3. "Clearing the bedroom"**, which you asked about. Each person's bed is in
  `[:state :bedrooms id]`. It decides where `sleep` lets them sleep ("go to a bedroom first;
  yours is …"), and it lists them as that room's sleeper in the household's bedroom view.
  Clearing it means a dismissed maid no longer has the maids' room as hers. She could still
  sleep in any bedroom, as anyone can, but she'd no longer be shown as sleeping there, and the
  bed would show as free for a new hire. So: clear the bedroom on dismissal, or leave it as a
  social matter (she stays in her room until someone makes her go)?
- **Q4. Does a dismissed person leave?** With the post at `:none` they're still an active agent
  in the house, and they lose household membership (the household's books and bedroom view). The
  options:
  - (a) they stay until they choose to go, and get hiring's `take-leave` (today only for
    candidates) so they can;
  - (b) they leave at once, like a candidate sent away (`:people/departed`, away and
    revivable);
  - (c) they leave after the candidates' 24-hour grace, which isn't a notice period, just a
    timeout.

  The draft leans to (a), plus `take-leave`.
- **Q5. Hiring:** should `engage` (offering a candidate a place) also need `:appoints`, now that
  the capability exists? At the moment anyone may hire.
- **Q6. Hibernated appointees:** should a post change wake someone who's hibernating, or wait
  for them?
- **Q7. Judge model:** the run's `:judge-model`, as the other judges use (a flash model)? It's
  one call per appointment or dismissal, so it's cheap either way.
