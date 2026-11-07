# Changing posts: reassignment and dismissal

Status: **built** (the user's answers are in section 9), mock- and offline-tested
(`test/manor/appointments_test.clj`). Phase 4, the short real run, waits for the user's go-ahead.

As built, the differences from the text below are small:

- `take-leave` for the dismissed is `visits/take-leave`'s third case (`visits/dismissed?`).
  Its departure is the guest's (`visits/leave-events`, with `:off-map? true`): towards the
  agent's `:lives`, a hired candidate's `:candidate-home`, the record's `:lives`, else
  "elsewhere", and any visit of theirs that is `:here` ends. A hired candidate's pool record
  goes back to `:away` (it was `:engaged`), so `send-for` can bring them back as section 4
  expects.
- `appoint` and `dismiss` refuse visitors (`:guest`, `:tradesman`) and candidates who haven't
  been engaged: their visit or candidacy decides when they go, and `engage` takes a candidate
  on. Otherwise an appointed candidate's record stayed `:here` and hiring's timeout sent them
  away. The GM's `change-post` refuses `:gm`.
- The position line's sentence and the post narration are in code beside the lines they join
  (`ruleset.rules/position-line`, `positions` describe), as the text around them is.

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
- Everyone knows the mechanism exists and that it lies with the people who direct others, so
  someone who wants a post changed knows to ask (section 6).
- Not now: resignation, a hierarchy, agents creating positions, several posts per person,
  appointers outside the household (the publican and his barmaid; section 9, answer 11).

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
  not `:noble-lady`. The user accepted this list.
- A world file adds or removes it per position by overriding `:capabilities` in `:roles`, as
  for anything else. Note that `merge` replaces the whole set, so a world adding `:appoints` to
  a shop position has to restate `:shop-clerk` (scenario-authoring should say so).
- Code doesn't check *whom* an appointer moves beyond structure. The judge does the rest.

## 4. Actions

### `appoint` (appointers) — reassignment, immediate

`appoint {who position reason? terms?}`, kind `:final`, `:roles :all`, `:needs :appoints`,
`:admission :people/post-check`.

Code checks (structure only):

- `who` is an active participant, not the GM, **not the actor** ("you cannot appoint or
  dismiss yourself"; this also keeps resignation out),
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

Effect, all visible to both:

- `post-changed` to `:none`, household nil, `:employer actor`;
- `bedroom-assigned who nil`: the bed is no longer theirs (section 7);
- the `:situation` line: "Mr Merrick has dismissed you from your post as housemaid. You no
  longer belong to the household and have no bed here. Stay as long as you must; use
  `take-leave` when you go."

They stay, as an ordinary active agent with no post, until they choose to go with `take-leave`
(section 4, "Leaving").

### The GM's `change-post` (for debugging and story events)

`change-post {who position household? terms?}`, `:roles #{:gm}`, no judge: the operator's
`change-post!` as a tool, and `:none` allowed. Visible to `who` (and nobody else, as the
operator's).

### No `give-notice`

Self-resignation is out for now. The GM or operator can do it.

### Leaving: `take-leave` for the dismissed

Today `take-leave` (one shared action, `visits/take-leave`) is offered to unengaged candidates
and guests from elsewhere. It gets a third case: **someone dismissed**, i.e. whose post is
`:none` with an `:employer` (so a person who was always `:none`, a candidate, isn't caught by
it). For them:

- `:valid?`: movement not disabled (`physical/disabled-reason`, as for guests). From
  anywhere: no need to be at the meeting place or in the house area.
- `:effect`: `:people/departed` with `:towards` the home their record has (`:lives`, or
  `:candidate-home` for a hired candidate), else "elsewhere", `:off-map? true`. They become
  `:away` (no turns, no calls, notes and memory kept). No return time: the optional
  `return-date`/`return-time` is refused for them, so there's no calendar entry and nothing
  to clean up.
- Being away is revivable, so they could come back later through `send-for` if a candidate
  record exists for them (a hired candidate), or a GM visit. Authored cast have no record, so
  only the GM could bring them back. That's fine for now.

The doc text for `take-leave` gets one line for this case.

Dismissal should be rare, so this supports it simply and handles none of the edge cases (a
dismissed person with nowhere to sleep who never leaves, a dismissed villager whose shop is
left untended, coming back). The roadmap notes them for later (B11).

### `assign-bedroom` (appointers)

A separate tool for moving someone's bed, so that `appoint` isn't used as a workaround to
change bedrooms (appointing someone to the post they already hold is refused anyway, and
`appoint` takes no `bedroom`).

`assign-bedroom {who bedroom}`, kind `:final`, `:roles :all`, `:needs :appoints`. **No
judge**: moving a bed is low stakes, and agents already complain about odd living situations,
so an inappropriate assignment is handled socially.

Code checks (structure only, as `engage`'s and `invite`'s bedroom):

- `who` is an active participant, not the GM, in the household or a guest from elsewhere
  staying with it (`visits/from-elsewhere?`): the people whose beds are the household's to
  give. **Self is allowed** (the countess moving to the blue room); it's not a post change.
- `bedroom` is a location in the household's home area (`space/home-location?`); any room there
  may be assigned, as with `engage`. It isn't checked for being free: sharing is allowed, as now.
- `who` doesn't already sleep there.

Effect: `bedroom-assigned who bedroom`, visible to both, and a one-line narration ("Mrs Thorne
has given Bessie the maids' room to sleep in"). Nothing else changes.

## 5. The post check (judge)

A new adjudicator `:people/post-check`, built like `activity-check`: one narrow call on the
run's `:judge-model` (the same as every judge and adjudicator; see section 9, answer 6), one
tool `check-post` answering `{:allowed bool :reason?}`, a reason
required when refused, checked by `adjudication/answer`. Mock: always allowed (tests supply
refusals). Failure: a neutral reply, nothing changed.

**What it sees** (no private state: no persona, inner state, relations, history):

```json
{"action": "dismiss" | "appoint",
 "by": {"name": "...", "post": "Butler", "about": "...", "household": "household"},
 "who": {"name": "...", "post": "Earl", "about": "...", "household": "household"},
 "to": {"post": "Head housemaid", "about": "..."},
 "reason": "the appointer's own words, if any",
 "setting": "the world file's :meta :setting paragraph ([:state :setting]), as the shop appraiser gets it"}
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

## 6. Everyone knows the mechanism exists

The user wants every agent, in any scenario, to know that posts are changed by appointment and
that this lies with the people who direct others, so that someone who wants a post (or wants
someone moved) asks one of them. Two parts:

**A rules section for everyone.** A new section in the shared rules (`ruleset/rules.clj`
`rules`, template `resources/prompts/rules/sections/posts.md`), shown whenever the run has at
least one position with `:appoints`. It's in the system prompt, so it's cached and costs
nothing per turn. The text is generic, setting-neutral, and names nobody, so it fits any
scenario and any pack:

> ### Posts
> A post someone already holds changes only by appointment; newcomers are still taken on by
> hiring. Those who direct others in a household can appoint someone to a different post, dismiss them or give them a different bedroom, and the change
> takes effect at once, wherever they are; agreeing to it in conversation changes nothing
> until they do. If you want a different post or room, or want someone else moved or
> dismissed, and can't do it yourself, ask someone who can. Someone dismissed stays until
> they choose to go, with `take-leave`.

A pack can override the template, as with any prompt (`resources/settings/<id>/prompts/`), if
its wording should be in period, but nothing in it is setting-specific.

**Who can, in their own prompt.** Saying "those who direct others" leaves it to the agent to
work out who that is, which is the period way and needs no hierarchy: the countess's `:about`
says she gives the servants their work, the head housemaid's says she leads the housemaids.
Appointers additionally see:

- the `appoint`, `dismiss` and `assign-bedroom` tools (by `:needs`), each with a short doc;
- one line in their position line: "Your position: Housekeeper. You live in. You can appoint
  people to posts, dismiss them and assign their bedrooms." (`ruleset/rules.clj` `position-line`, from the
  capability, so it follows the post and the user doesn't have to write it into every
  `:about`).

The generic rules text is enough (user's answer); no list of appointers.

## 7. Getting a change into others' contexts

- Both parties see the `post-changed` event among their recent events at their next turn,
  wherever they are, narrated "X now holds the post of …, on these terms: …". A line
  "appointed by Y" is added to the narration when `:employer` is set.
- The appointee's `:situation` is replaced, so the change stays in their prompt after the event
  scrolls out of their recent history.
- The appointee's view shows their post (`:hiring :posts`, already there). The appointer's view
  shows posts where they're the `:employer` (already there), so they remember whom they've
  appointed.
- Nobody else is told (user's answer 9). Others learn by talk.
- A sleeping appointee sees it when they next take a turn. Only people with routines hibernate
  (villagers), so a hibernated appointee would only come up if someone appointed or dismissed a
  villager; they'd see it when they wake. Nothing extra is needed.

## 8. What else changes

- **Routine:** left alone. Only village people (shopkeepers, the constable and so on) have
  routines, and none of them hold `:appoints` or would be appointed in practice.
- **Bedroom:** unchanged on `appoint` (moving it is `assign-bedroom`'s job).
  **Cleared on `dismiss`:** `[:state :bedrooms id]` is removed (the existing
  `bedroom-assigned id nil`). Its effects:
  - the household's bedroom list in everyone's view no longer shows them as sleeping in that
    room, so the bed shows as free for a new hire;
  - `sleep`'s "go to a bedroom first; yours is …" no longer names a room for them. They can
    still sleep in any room of kind `:bedroom`, as anyone can: code doesn't evict them, and
    whether they're let stay the night is a social matter;
  - dismissal also ends household membership (household nil), so they no longer see the
    household's bedroom list, its books or its account entries.
- **Hiring:** unchanged. `engage` stays open to anyone.
- **Mock policy:** a scripted `appoint` and `dismiss` in a test fixture, not in the mock day.
- **Docs:** `docs/scenario-authoring.md` (the `:appoints` capability, the `merge` note), the tool
  docs in `resources/prompts/docs/people/`, the judge prompt, `docs/prompts.md`.

Phases, as one build:

1. The capability in both catalogues and the medium world; `appoint`, `dismiss`,
   `assign-bedroom`, the post check, the GM's `change-post`; bedroom clearing; `take-leave` for
   the dismissed; the roadmap note on dismissal's edge cases.
2. The rules section and the position line; tool docs; the judge prompt; `docs/prompts.md`,
   `docs/scenario-authoring.md`, the roadmap.
3. Tests: mock and replay; the judge's refusal through a test verdict; a dismissed person's
   `take-leave`; the rules section shown only with appointers.
4. With your go-ahead: a short real run (about 8 rounds, `:max-cost` 0.20) that starts with
   an appointment to make, and a few deliberately odd ones put to the judge directly, to see
   where its line falls.

## 9. The user's answers

First round (2026-11-07):

1–2. No hierarchy: designated appointers, including lower roles that direct someone.
Inappropriate appointments are a social problem, not a constraint.
4. Changes take effect at once.
5. No notice period.
6. No self-resignation for now. Dismissals and appointments go through a light judge.
7. Anywhere, for now; the change must reach the other agent's context.
8. Routines: ignore.
9. Only the two people hear of it.
10. A GM tool, yes.
11. Not now. Why the first draft tied appointment to households: with no hierarchy and no
    judge, the household was the only boundary code could enforce, or a publican with
    `:appoints` could have reassigned the manor's housemaids. With the judge that check isn't
    needed, and giving `:publican` `:appoints` would be all it took.
12. Appointers may change each other, subject to the judge: slightly inappropriate goes ahead,
    absurd is refused.

Second round:

1. The appointer list stands. Everyone should know the mechanism exists and lies with the
   appropriate people, in a way that works in any scenario; appointers see the tools.
2. Self-appointment refused in the tool.
3. Clear the bedroom on dismissal.
4. The dismissed stay until they choose to go, and get `take-leave`.
5. Hiring stays as it is.
6. The judge model. Every narrow call, not only the light ones, uses the run's
   `:judge-model` (falling back to `:model`): the work judge that decides `spend-time`'s
   outcome, the conversation judge, the steward, the find judge, the invitation and trade
   judges, and the activity check. Only the character writers (hiring's appearance and
   polish, `:writer? true`) use the run's `:model`. Its default is nil, so in practice all
   of them run on the run's model unless a run sets `:judge-model`. The post check does the
   same; it adds no new option.

Third round:

1. The dismissed take leave from anywhere. Dismissal should be rare: support it simply, and
   note the edge cases (homeless agents and the like) in the roadmap for later.
2. The generic rules text is enough.
3. A separate bedroom command for appointers, so `appoint` isn't abused to move beds.

Fourth round:

1. `assign-bedroom` has no judge: odd living situations are left to the agents, who already
   complain about them.
2. Appointers may assign their own bedroom.

All questions are answered; details left can be settled while building.
