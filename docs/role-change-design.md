# Changing posts: promotion, reassignment, dismissal and resignation

Status: **draft for discussion**, nothing built. Open questions are in section 7.

## 1. What exists now

- A person's job is their `:role` (a position id) plus their post in `[:state :posts id]`
  (`{:position :household :since :terms :conditions :employer}`). The one event that changes
  both is `:people/post-changed` (`manor.mechanics.positions/post-changed`).
- Three things emit it today:
  - starting a run (`starting-events`),
  - hiring: `engage` (an offer) and then `accept-place` (the candidate accepts). Anyone may
    offer, but only to an unengaged candidate who is present (people-and-hiring decision 5:
    "Anyone may hire. The agents decide whether that's proper"),
  - the operator's `change-post!` (engine.clj, `dev/user.clj`), and deliveries, for a
    courier's post.
- **No agent can change an existing person's post.** People-and-hiring decision 14 says each
  person keeps their position "for now", and that holding or moving between positions needs "a
  way to take up and give up official positions". Dismissal is deferred (section 6.5 of that
  doc sketches it as `post-changed` to `:none` plus `:people/departed`).
- **There is no hierarchy in the data.** Who answers to whom is only in the prose of each
  `:about`:
  - england-1904: footman → butler (or the master); head housemaid → housekeeper (or the lady);
    butler → master; housekeeper, governess → lady; tutor → master; constable → his sergeant
    (off-map). Village tradespeople answer to no one in the house.
  - qing-palace: eunuch → chief eunuch; chief eunuch → his consort; consort → the Empress and
    Emperor (off-map); a noble lady owes the consort deference.
  - Standings (`:family`, `:upper-servant`, `:servant`, `:outdoor`, `:tradesman`, … and in the
    palace `:consort`, `:eunuch`, `:palace-maid`, `:merchant`) give a coarse ladder but no
    "who directs whom".
- What follows a role automatically once it changes: the brief's `:about`, the title and form of
  address, capabilities (and so tools with `:needs`, e.g. `:shop-clerk`, `:architect`), the
  trades judge's workplaces, and the "positions someone holds" list in the GM's view. What
  **doesn't** follow: bedroom, routine, `:situation` text, the persona and inner state, the
  mock policy's per-role script, and any scenario `:roles` brief keyed by role.

## 2. Goals

1. A character with the authority can move someone in the household to another post (promotion,
   demotion, reassignment), and can dismiss them.
2. A character can give notice (resign) on their own account.
3. By default, people with no authority, such as a village shopkeeper, can't change anyone's
   post, their own included, apart from resigning, which is open question Q6.
4. The change is engine-owned (one `post-changed`), validated in code by **structure only**:
   ids, positions in the catalogue, the authority relation. Whether a change is wise or proper
   is left to the agents, as with hiring.

## 3. Authority: two models

### 3a. Designated appointers (recommended to start)

A position may carry the capability `:appoints` (capabilities already exist and drive tool
availability through `:needs`). Only holders of it get the tools. An appointer may change the
post of anyone **in their own household** (`positions/household-member?`), except themselves
and except other appointers. The new position must be one of the pack's household standings
(`household-standing?`).

- england-1904: `:earl`, `:countess`, `:mistress`; in claudeworth-medium `:baron`, `:baroness`
  (world `:roles` overrides). Possibly `:butler` and `:housekeeper` (Q2).
- qing-palace: `:consort`, possibly `:chief-eunuch` (Q2).
- Shopkeepers, publicans, villagers, guests, candidates and children don't have it, so they
  never see the tools.

It's cheap: one capability, one membership check, no graph. It also matches how the household
actually works in 1904: the master and mistress engage and dismiss, and the upper servants
recommend.

### 3b. Hierarchy (later, or as well)

Positions get an optional `:answers-to`, an **ordered** vector of position ids, the first one
held by someone being the effective superior. That is the same fallback the prose already uses:

```clojure
:footman {... :answers-to [:butler :earl]}
:head-housemaid {... :answers-to [:housekeeper :countess]}
:butler {... :answers-to [:earl]}
:eunuch {... :answers-to [:chief-eunuch :consort]}
```

"Above" is the transitive closure over the posts actually held in the run. Anyone above someone
may change that person's post, to a position that is also below them, so no one can appoint a
peer or a superior. Positions with no `:answers-to` and no one below them (shopkeepers) get
nothing. A world file can override it per entry like any other field. Checks at load:
the ids exist and there's no cycle.

Costs: authoring `:answers-to` for about 60 england-1904 positions, which then duplicates the
`:about` prose (it could drift; the `:about` text could be rendered from it, Q3), and more
cases to explain to models ("you may move Rose but not Mrs Thorne").

Possible middle ground: 3a decides **who may appoint at all**, and 3b, if present, narrows
**whom** (a butler with `:appoints` could only move people below him).

## 4. Actions (sketch)

All are `:roles :all` with `:needs :appoints` unless noted, structure-checked in `:valid?` with
actionable reasons.

| Action | Args | Kind | Effect |
|---|---|---|---|
| `reassign` | `who position terms? bedroom?` | final | An **offer**, as `engage` is: `:people/offered` with `:employer actor`. The person answers with the existing `accept-place` / `decline-place`. |
| `dismiss` | `who reason?` | final | `post-changed` to `:none`, household nil. Leaving the house is a separate question (Q5). |
| `give-notice` | `reason?` | final | Anyone holding a household post; no `:needs`. `post-changed` to `:none`, or a notice period (Q6). |

Why an offer for `reassign`: it reuses hiring's offer/accept machinery and decision 6
(conditions, haggling), and a demotion the person refuses becomes a story (they give notice, or
they're dismissed) instead of an engine fiat. The alternative is an immediate change (Q4).

Validation (code, structure only):

- `who` is active, not the GM, not the actor, in the actor's household, and doesn't hold
  `:appoints` (or, under 3b, is below the actor),
- `position` exists, isn't `:gm`, `:guest`, `:tradesman` or `:none` (dismissal is its own
  tool), and has a household standing (under 3b, is below the actor),
- the two are together, as for `engage` (or Q7: by letter or message),
- `bedroom`, if given, is a home-area location (as `engage-reason`).

`accept-place` today assumes a candidate (`for-agent`, `:status :engaged`, the situation text
"You have taken the … place"). It gets a second branch for an existing member: no pool record
change, and a situation line that says the post changed.

## 5. What else changes with the post

- **Routine:** an old routine describes the old job, and hibernation leans on routines. Clear it
  on a change (`routine-set id []`) and let the person or the GM set a new one, or keep it until
  replaced (Q8).
- **Bedroom:** unchanged unless `bedroom` is given. Dismissal: keep, or clear (Q5).
- **Situation:** replaced by one engine line naming the new post and who appointed them, as
  `accept` does now.
- **Persona and inner state:** untouched; the work and conversation judges may react.
- **Scenario `:roles` briefs** keyed by role, and the mock policy keyed by role: these follow the
  role automatically; the mock gets a scripted `reassign` for one test.
- **Tools:** refreshed between steps already, so a new capability (e.g. made shop clerk) shows
  at once.

Visibility: the `post-changed` and the offer are seen by the two parties (as hiring now). Others
learn by conversation, or (Q9) by an event visible to everyone present, or to the household.

GM: a GM tool `change-post` mirroring the operator's, for story events (a will, a royal
decree), is cheap and fits "GM hands-off", but it's optional (Q10).

## 6. Phases

1. `:appoints` capability on the family positions in both packs and the medium world; `reassign`
   (offer) with `accept-place` extended; `dismiss` to `:none`; tests (mock, replay); rules and
   tool docs in `resources/prompts/`; `docs/prompts.md` and `docs/scenario-authoring.md`.
2. `give-notice`; routine/bedroom handling decided in Q5 and Q8.
3. Optional: `:answers-to` hierarchy (3b), with load checks.
4. Later, from decision 14: several posts per person.

## 7. Open questions

- **Q1. Model:** designated appointers only (3a), a hierarchy (3b), or 3a now with 3b
  narrowing later? The draft recommends 3a.
- **Q2. Who is designated:** only the family (earl/countess/baron/baroness/mistress; consort),
  or also the butler, housekeeper and chief eunuch over "their" servants? If yes, that is
  effectively 3b and needs `:answers-to`.
- **Q3.** Under 3b, should `:about` prose stay hand-written (could contradict `:answers-to`) or
  be rendered from it?
- **Q4. Offer or decree:** does `reassign` need the person's acceptance (draft), or does it
  take effect at once? Promotion by offer is natural; demotion by offer means a refused demotion
  leaves them in place.
- **Q5. Dismissal's consequences:** only the post (`:none`, still in the house and the bed)?
  Or also `:people/departed` (away, as people-and-hiring 6.5 sketches), at once or after a
  notice period, with their bedroom cleared?
- **Q6. Resignation:** may anyone give notice, including shopkeepers leaving their own shop
  (which would remove `:shop-clerk` and break their shop)? Draft: only household members; the
  village has no tools at all.
- **Q7. Presence:** must the appointer and the person be together, as for hiring, or may it be
  done by letter or message (the butler told to pass it on)?
- **Q8. Routine:** clear it on a change, or keep the old one until someone sets a new one?
  Should agents get a way to set routines at all (none today, beyond the world file and
  the operator)?
- **Q9. Who hears of it:** only the two parties, everyone present, or the whole household
  (servants' gossip as an engine event)?
- **Q10.** A GM tool for post changes, or leave that to the operator?
- **Q11. Outside the household:** should a publican be able to take on or dismiss his barmaid?
  That would need per-shop households (barmaids hold `household nil` today). Draft: no, for now.
- **Q12.** Should appointers be able to change each other (the earl moving the countess)? Draft:
  no; the family's own positions are out of scope.
