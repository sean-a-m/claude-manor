# Concealment and knowledge: investigation

**Status: discussion proposal, not an approved design or implementation.** Investigated from
`c9a0a09` in the fresh `investigation/concealment-and-knowledge` branch. No material from the
previous hidden-aspects worktree was used. No code, prompts or scenario contracts changed.

## 1. What this should make possible

A second set of aspects is a good starting point: real facts about a thing which the GM and
relevant judges see, but ordinary characters do not automatically see. Each fact records who
has established it. Hidden items need a related rule for discovering their presence or location.

The purpose is broader than adversarial intrigue. Privacy, overlooked defects, expertise,
surprises, evidence, and ordinary incomplete knowledge all need this distinction. A manor
house can use it without becoming a murder mystery; the palace can use the same mechanics
with different people, motives and opportunities.

Four questions must remain separate:

1. **What is actually true?** Owned by the engine, changed through checked events.
2. **What can this person perceive now?** Depends on the scene, access, senses and activity.
3. **What have they learned?** Can persist after they leave, and can become out of date.
4. **What do they believe or claim?** May be mistaken or deliberately false; not hard state.

A fifth question matters for items: **can they physically act on it?** Knowledge of something
inside a locked drawer is not access to it. Conversely, someone can carry a sealed parcel
without knowing its contents or authenticity.

This need not become a general-purpose belief simulator. Start with hidden facts and explicit
confirmed discoveries; leave suspicions and lies in dialogue, notes, inner state and relations.
But do not make those discoveries an omniscient live feed.

## 2. Use cases that exercise different parts of the problem

| Situation | Hidden fact or object | Discovery and consequences |
|---|---|---|
| A servant conceals broken porcelain with a careful repair | Hairline damage under a painted join; the vase remains visible | Dusting might reveal a loose fragment; a close examination establishes the repair; rough handling can break it without anyone first discovering it. |
| A stolen hairpin is sewn into a lining | The actual hairpin, concealed on a person or in clothing | A casual look sees clothes; a search may find a hard object, then the hairpin. Theft, concealment, possession and guilt are different facts. |
| A private letter is in a chest | Its presence, its contents, and possibly its author | Finding an envelope is not reading it. Reading text is not proving the author's identity or the truth of its allegations. |
| Palace accounts conceal a short delivery of silk | An altered entry or false total in a visible ledger | Comparison with a receipt or stock inspection can expose a discrepancy. A claim about accounts must not silently alter the actual money ledger. |
| A petition bears a forged seal | The document is visible; its provenance is not obvious | Most readers accept the apparent seal; a knowledgeable clerk may detect a mismatch. Detection need not identify the forger. |
| An attendant substitutes an imitation ornament | The ornament's material/authenticity; perhaps the missing original elsewhere | Appraisal establishes the substitution. Search must not generate another genuine original because someone expects to find one. |
| A damaged latch has been made to look sound | Concealed damage or tampering | Routine cleaning, testing the latch, or its later failure can reveal different amounts. Knowing it failed is not knowing who interfered with it. |
| A hollow screen or loose floorboard holds a cache | A concealed compartment plus items within it | A knock may reveal a hollow space; opening it reveals contents; removing one item need not reveal everything or inform absent accomplices. |
| A maid hides a gift, savings or personal keepsake | A private item or hiding place, not wrongdoing | Discovery can be socially intrusive without authorizing seizure. This is useful in otherwise peaceful household life. |
| A visitor carries an unannounced message | A folded paper in a sleeve, then its contents | Giving the paper reveals a transfer; it need not reveal its message, sender, or the rest of the visitor's possessions. |
| A stored supply has spoiled or been adulterated | A property of one batch, not necessarily all stock | Ordinary use can have consequences before detection. Smell, inspection or subsequent symptoms may provide different evidence. |
| A person hides an injury beneath clothing | A concealed bodily condition | It can impair work; treatment or changing clothes may reveal it. The person knows pain, not necessarily the medical explanation. |
| A room has an unnoticed listening opening | A feature of the room or screen | Finding the opening does not itself implement eavesdropping through it; hearing across rooms needs an actual mechanic. |
| Someone arranges a misleading scene | Real planted evidence, such as a stolen object in another person's drawer | Discovery establishes where the object was found, not who put it there or who is guilty. |

Poisoned refreshments fit the spoiled/adulterated-supply case, but should not determine the
whole design. They additionally expose gaps in consumption and delayed bodily consequences
(section 8). Concealed breakage, a forged document and a hidden keepsake are better first
fixtures because they isolate different requirements without needing that extra machinery.

Some useful secrets are **not aspects of things**: a private agreement, a person's intention,
a bribe already paid, or a promise to meet. Existing private speech, goals, inner state,
relations and calendar entries cover parts of these. Do not turn every secret into a hidden
aspect, or store a future plan as if it were an accomplished physical fact.

## 3. What the code currently does

These are observations of this checkout, not proposed behaviour.

| Area | Existing behaviour and implication |
|---|---|
| Aspects | `items/Aspects`: up to four short keyed phrases on items, locations and people. Views show them openly; person aspects describe outward state, not thoughts. |
| Containers | `visible-at` omits closed-container contents. `open` is a query-like free action returning contents, not a persistent opened-state transition. Closed is not locked. |
| Reach | `reachable?` follows the outer container to its place/holder. `take`, work focus and container access can accept ids inside closed containers without prior discovery. |
| Carrying | `look-person` shows another person's directly held items and their descriptions/aspects; ordinary views show their carried names. No concealed-carry rule exists. |
| Finding | `finder-candidates` includes local items (closed contents and carried items too) plus recent items elsewhere. For `existing`, `find-hit` checks co-location and reports holder/container, without a discovery roll. Odds apply to the other, materializing path. |
| Work | `scene` uses named focus and its contents, other carried items, up to twelve other visible items, stores, and people. The worker and named people still present are changeable, not all bystanders. Work usually takes effect when it ends. |
| Public work | `work-on-events` keeps the job private to the GM but passes `doing` into the public occupation. Nearby views show `busy.activity`. Work-end narration prints `doing`, changes, fates and made-item names as well as the judge's summary. |
| Immediate acts | `act-on` is an immediate judged action on other people. It can change aspects/bodies and move carried items, but not make/use up/absorb items. It is not a general immediate item-use action. |
| Physical state | Faculties and impediments enforce restrictions. `physical/shown` exposes causes as well as levels. `perceivable` trims event audiences by faculties; it keeps the actor and gives special treatment to the people acted on. It does not judge subtle detection. |
| Events | Visibility is per event; `narrate/describe` has no viewer argument. Everyone who receives an event gets its same narration. The GM sees non-system events; the operator sees everything. |
| Item lifecycle | Work can use up an item or demote it into another thing's aspect (`gone` with `on`), and work/find can promote an aspect to an item (`taken`). Absorption merges suitable things into a store. |
| Steward | At night it handles selected old visible loose items, sleepers and physical recovery. It is not an all-world detector or a progression system for hidden hazards. |
| Conversations | The conversation judge changes inner state and relations. It receives who heard each line. It does not currently establish physical discoveries or confirmed knowledge. |
| Deliveries | `send-up` removes an existing item and preserves selected fields in an order; arrival creates a new item id. New hidden data would need explicit preservation. |

The palace already has a closed lacquer chest and a jade hairpin inside it, plus stores and
private rooms. This is a ready setting for access and concealment examples; it does not yet
provide robust hidden possession just by using `:container :closed`.

## 4. The smallest useful data model

### 4.1 A second aspect map

Illustrative only, not a schema commitment:

```clojure
{:id "petition"
 :name "Petition"
 :desc "A folded petition bearing a red seal."
 :aspects {:condition "neatly folded"}
 :hidden-aspects
 {:seal-origin {:value "The seal was copied rather than applied by its purported issuer."
                :known-by #{"copyist"}
                :revision 1}}}
```

Properties of this approach:

- A hidden aspect is **true**, not merely something its listed knowers believe.
- An empty `known-by` is valid: unnoticed decay, an authored secret, or a fact whose last
  witness died. The GM does not need membership in this set.
- Each aspect has its own knowers. Knowing about damage is not knowing about provenance.
- Private views show only facts this character has learned, not the other knowers, hidden
  keys, hidden counts or a generic "there are secrets here" indicator.
- Ownership, rank and being the carrier do not automatically confer knowledge.
- Creating an intended result does not automatically establish successful completion. The
  judge decides what the actor could verify as well as what really happened.
- The GM sees all hidden state. Each relevant judge sees the hidden state needed for its
  task, alongside the actor's actual knowledge. That is not a reason to send every judge
  the full world.

Keep public aspects about what is openly apparent: "seal intact", not "genuine" when that
has not been established. Hidden facts can explain appearances without contradicting them.
A false inscription is a real inscription; the engine need not assert that its text is true.
A contradictory public and hidden statement about the same physical fact is a bad verdict,
not a useful model of deception.

One aspect should contain one discoverable fact at roughly the needed granularity. Do not
combine "counterfeit, made by X, at Y, to incriminate Z" if different people can establish
those parts separately. Nor should every detail require a new aspect: retain small caps,
and settle their values after trying realistic scenes. Do not evict an important secret
merely to fit a cosmetic update.

### 4.2 `known-by` is useful but insufficient for changing facts

Suppose Ada learns that the ornament is in a drawer. Ben later moves it unseen.

- Leaving Ada on the **new location's** knowers list gives her remote knowledge.
- Removing Ada from the old fact, and then dropping it from her view, can tell her that
  something happened even though she saw nothing. It also discards useful memory.

The minimal robust addition is a record of **what was learned, and when**, independent of
the current hidden value. Discovery events already provide that history; a small per-agent
projection of them can retain it beyond the recent-event window:

```clojure
;; Conceptual learned record; exact placement and field names undecided.
{:subject [:item "ornament"]
 :fact :whereabouts
 :revision 1
 :value "In the lower drawer of the cabinet."
 :learned-at 31}
```

The character sees "At 10:00 you established ...", not "this remains true right now".
When the world changes unseen, that record remains. A new observation updates it. Never
label it stale using knowledge the character does not possess. A removed hidden aspect
must not remotely announce its removal either.

Use `known-by` as those who established the **current revision**, if retaining that index;
use learned records for character context. Both must be event-maintained consistently.
Alternatively derive the index from discovery records. Do not build both as independent
sources of truth. A simpler first experiment could use immutable secrets and discovery
events only, but that deliberately does not solve moving caches or repaired hidden damage.

This is a narrow confirmed-observation record, not a database of all beliefs. An overheard
accusation or a forged letter does not enter it as verified truth. "Read a letter claiming X"
can be confirmed; X itself need not be. Whether trusted testimony can establish a hidden
fact is a user decision, not something to leave implicit in a prompt.

### 4.3 Hidden items: conceal presence, not physical existence

Keep a hidden item in `:items` with its real `:place`, `:holder` or `:in`. Concealment must not
be a second location system. An illustrative extra property is:

```clojure
:concealment {:description "Tucked behind the cabinet's loose back panel."
              :known-by #{"ada"}
              :revision 1}
```

This says how its presence/location is concealed. It does not say that everyone who knows
of the item knows its current location, or can see or reach it. Discovery can be private
without removing concealment: Ada can find the cache and put the panel back. Ben may later
expose it by pulling the cabinet apart. Seeing a hidden item need not reveal its hidden
aspects.

A known item moved unseen uses last-known whereabouts, not the current canonical location.
An item handed to somebody is encountered, but its hidden properties are not automatically
revealed. A secret inside a visible container can remain secret while the container is carried.

Start with a shared discovery vocabulary for aspects and item presence, but separate their
checks: hiding a whole item changes listing, targeting and movement narration; hiding an
aspect does not make the item untargetable.

## 5. Discovery: existing judges first, bounded additional calls

### 5.1 What a judge needs to decide

Separate these outputs even if one call supplies all of them:

1. **World effects:** what actually changed, including secret changes.
2. **Observations:** what each eligible person perceived.
3. **Confirmed discoveries:** which current facts those observations establish.

Example: someone sees an attendant leaning over a cabinet; another sees a paper slide into
a sleeve; the attendant knows which paper was taken. These are three different disclosures,
not one event with a larger audience. A witness of a preparation may still not know whether
it succeeded.

Not every clue needs permanent state. A noise can be a private event. Persistent evidence
(a scratch, a stain, a resealed flap) belongs in state and can be examined later. A clue may
be real yet misleading. Judges must not replace "you notice a scratch" with the omniscient
explanation that the scratch was made during a theft.

### 5.2 Where to put it

**Work and `act-on`: extend the existing scene judge.** It already decides outcomes from a
local scene, skill/persona, elapsed time and luck. Add relevant hidden state, actor knowledge
and eligible witnesses. Let it return discoveries and audience-specific observations with
its ordinary changes. Searching someone's clothing already fits an immediate act on a person;
careful inspection or searching a room fits timed work.

**`find`: preserve identification, add discovery for established objects.** An existing id
must continue to prevent duplication, but it must no longer imply detection merely because
it is co-located. Give the oracle relevant concealed local candidates as privileged context,
then adjudicate whether the search discovers them. A miss must not say whether the object
is absent, hidden, held nearby, or elsewhere. Finding something should not necessarily take
it; that is a deliberate change from materialized portable findings today.

**`look`, `open`, entry and handling:** keep ordinary projections cheap. Do not make every
look into a full model call. When an action creates a genuine opportunity to detect a
recorded secret, a bounded observation check may be needed. Options:

- First slice: incidental discoveries during judged work, plus explicit inspection/search;
  ordinary `look` never rolls. This is cheap but misses some discoveries during opening or
  handling. Say so explicitly rather than pretending it is complete.
- Fuller version: use a shared detection component from those action paths only when relevant
  hidden state exists. Cache/record the opportunity so repeated `look` does not reroll it.

A tiny optional structural hint such as "handling / inspection / use" could later help
select relevant secrets. Do not start with a large catalogue of triggers or parse the hidden
prose for words like "poison", "loose" or "forged". Let models interpret meaning; code
checks ids, allowed subjects, revisions, recipients, lengths and scope.

**Conversations:** hearing a statement is not physical discovery. Initially keep ordinary
claims as dialogue and reactions. If we later allow a character to establish a fact by being
shown evidence, the conversation path needs an explicit evidence/knowledge result. Today's
conversation judge runs at the end; that is too late if another speaker must act on newly
verified information in the same conversation. Also cover standalone `say` and `whisper`,
not just `converse`, if testimony is made authoritative.

**GM:** author/alter secrets and grant justified discoveries through validated events; never
make an announcement alone change hard state. The GM should not have to arbitrate every
ordinary search. New GM/operator tools would need bounded permissions; those tools are not
already supplied by the hidden-aspect data model.

### 5.3 Chance, repeated attempts and witnesses

The existing finder pattern is suitable: the judge proposes a likelihood, the engine rolls
with a stable key and records the result. Alternatively the work judge can resolve detection
using supplied deterministic luck, as it does outcomes today. Choose one policy per path;
avoid an accidental second roll for a discovery already decided in the work verdict.

An opportunity should depend on the fact revision, observer, activity/evidence and meaningful
scene change, not each rephrasing of a query. Repeated quick looks should not guarantee eventual
detection. Spending more time, opening a different compartment, obtaining a tool or involving
an expert can justify a new attempt. Costs and time must be explicit, rather than exploiting
the generous budget for free actions.

Same room is only eligibility, not proof of observation. Senses, sleep, attention, obstruction,
access and familiarity matter. The current physical filter can rule people out, but its
actor/done-to exemptions must not grant knowledge of an unknown cause or concealed outcome.

Timed work introduces a special issue: current work audiences are mostly chosen at the end.
Someone who watched the start then left may know something; someone who arrived at the end
must not learn the earlier secret operation. For an initial slice, adjudicate the result at
completion and avoid claiming exact mid-job observation. A fuller version needs bounded
presence/activity evidence across the job, and a policy about when the hidden change occurs.

## 6. Privacy has to hold throughout the action

Adding a `hidden-aspects` key to views would not be sufficient.

### 6.1 Private intent versus public activity

`doing` currently doubles as the character's intended action and the description everyone
sees. "Replacing the petition with a forged copy while pretending to tidy" would reveal the
whole plot before the work ends.

Store the full intention privately. Public occupation text must describe observable activity,
not intent. Options are a neutral engine description for all concealed work, or a checked
public description at its start. Letting the actor supply an unreviewed cover story as fact
would be wrong: a cover story is an attempt to mislead, not control over what witnesses see.
Giving only secret work a distinctive generic label would itself become a metagame signal;
use an ordinary outward-activity convention consistently.

The same applies to interrupts, carry-on prompts, stopped-work narration, immediate acts and
failed actions. A judge failure must not fall back to printing secret `doing` publicly. It
must also not apply an unjudged secret effect.

### 6.2 Events: keep truth and disclosure separate

Prefer the engine's existing per-event visibility rather than a large viewer-aware narration
rewrite:

- GM-only canonical events apply hidden world changes.
- Discovery events update the selected characters' learned records.
- Observation events go only to their intended recipients, grouping recipients who receive
  the same text. They do not apply the physical change a second time.
- Public events contain only genuinely public outcomes and observations.

Link these events to the same action/job. A character receives only their own observations,
not the full verdict with an instruction to ignore parts of it. Resolve a checked outcome on
a defined snapshot, then emit its event batch in a stable order before any character acts.
Remember pre-effect witnesses when a consequence incapacitates someone; post-effect perception
alone may erase what they observed immediately beforehand.

A public view should be constructed from public state and permitted observations, not from a
full secret scene and a request to "avoid spoilers". When a model must write different
observations from privileged context, this is a semantic leak risk: use an explicit
recipient/evidence contract and, if needed, a narrow review at generation. Code cannot prove
that arbitrary prose has not leaked a fact. Do not use keyword/regex screening as a substitute.

### 6.3 Other disclosure paths to audit

- Item names, immutable descriptions, ids, owner labels and container names must not encode
  secrets ("forged-petition" is already a clue). Concealed actor identity needs its own rules;
  generic person ids and roster names currently assume known identity.
- `look`, `open`, walk replies, carrying lists, store views, nearby-person views, action target
  lists, tool replies, error messages and physical causes need actor-aware projections.
- Knowing a guessed id must not bypass discovery. Unknown/hidden and nonexistent targets
  need indistinguishable failures at that boundary; known but inaccessible targets can give
  useful access reasons without revealing unseen changes.
- Work summaries are not the only narration: deterministic `doing`, change, removal, creation
  and movement text currently exposes details too.
- A finder failure currently narrates "there's none". Hidden-aware failure should say "did
  not find", not establish absence or write a store shortage from a mere detection failure.
- Shared recent lines are lines the worker saw and somebody else saw, not necessarily every
  person now present. Judges must not treat these as universal knowledge.
- Private notes, testimony, bridge replies and judge input are evidence with different status.
  None should authorize changing physical truth merely because they assert it.
- GM/operator panels, map exports, event logs and transcripts may intentionally remain
  omniscient. Label that boundary; do not reuse their payloads for character-facing views.
  Replay/branch/load must preserve knowledge and old disclosures, not recompute who knows
  from the current world. World-file export/import also needs a stated knowledge policy.

## 7. Item lifecycle is a major design decision

The current simplification from item to aspect is useful for ordinary household life. It is
hazardous for secrets: a hidden note turned into "papers on the table" could lose provenance,
its knowers and its identity; recreating it later could leak its contents or duplicate it.

**Recommended initial rule:** things with hidden aspects, active concealment, or tracked
knowledge/provenance dependencies stay separate items. Reject lossy demotion, store absorption
and destructive cleanup unless the verdict explicitly resolves those dependencies. Legitimate
destruction is still possible: burning a letter removes the letter, not other people's memories
of reading it. This should be a narrow guard, not a ban on ordinary item simplification.

Later, a transfer operation could preserve fact identity and knowledge when a thing moves
between item and aspect representation. That is significantly more work than adding hidden
aspects, and is not necessary to prove the idea. Keep object identity separate from its visible
label; a new appearance must not create a duplicate original.

Specific consequences:

- A hidden compartment can be a hidden aspect, but its important contents should be real
  items. Finding the compartment grants neither ownership nor all the facts about the contents.
- Stores describe broad ambient supplies. A secret applying to one parcel/batch should stay
  on that parcel, not silently contaminate every thing the store can provide. Conversely,
  taking material from a genuinely affected store must not create an unaffected duplicate.
- Transformation can carry facts forward selectively: a document copied, a batch divided,
  or an ingredient used in a dish. Knowledge does not automatically transfer to the product's
  new holder, and copying text does not copy a physical seal. Initially reject or explicitly
  scope unsupported transformations rather than silently dropping secrets.
- `send-up` and arrival currently replace ids and select fields. Either preserve the secret
  object identity through transit or record a checked successor mapping and transfer its
  secret metadata. The same audit applies to purchase, spawning, export/import and `made`.
- The steward must not erase evidence because it is idle, old, consumable or part of a sleeper's
  clothing. Its narrow scene needs relevant secret facts even if nobody present knows them.
  It may clean evidence away when actual cleaning justifies that, not merely as bookkeeping.
- Destruction, hiding and loss of visibility are distinct. Missing an item in a room does not
  tell an absent knower that it was burned, nor who burned it.

## 8. Hidden facts must matter without being discovered

A concealed defect can break a chair; a forged document can mislead a reader; a hidden sharp
edge can injure somebody. Judges of relevant interactions need the true local state even when
the actor does not know it. Otherwise hidden aspects are only a role-play annotation.

Use existing mechanics where they actually apply:

- Work can change aspects and bodies. Concealed damage can affect a judged repair, cleaning
  task or other use, and the resulting visible breakage can be public without exposing its cause.
- `act-on` can handle an immediate search or assault on another person, subject to its current
  restrictions. It does not presently consume an item or represent every kind of using/eating.
- Physical faculties enforce bodily consequences. Their public phrases should express
  symptoms/effects, not unverified diagnoses or culprit names. The true cause can be hidden.
- Conversation/relations can react to what a person actually witnessed, read or was told, not
  the omniscient scene's explanation. Being falsely accused may change relationships without
  changing the world's record of guilt.

There are also gaps hidden aspects do not solve by themselves:

1. **Consumption:** meals are not generally linked to specific item use-up. No item-linked
   drinking event means no reliable trigger for an effect carried by a drink. Decide on an
   actual use/consume path rather than infer consumption from spoken narrative.
2. **Delayed consequences:** a hidden bodily process or weakening structure needs a due
   event/review hook if it develops later. The steward only eases existing physical conditions;
   it cannot substitute for progression. Calendar review offers a scheduling precedent, not
   a complete condition engine. Do not invent a timer for every hidden aspect.
3. **Locks and access:** concealing a key or knowing of a locked chest does not implement a
   lock. Keep physical access rules separate from knowledge.
4. **Routes and hearing:** a discovered passage or listening hole needs real graph/hearing
   effects to change movement or eavesdropping. A prose aspect cannot override the engine.
5. **Authority and identity:** a forged command does not automatically bypass role/capability
   validation. A disguise does not hide names already printed in views. These are separate
   follow-ups, not implied by adding a secret to a person.
6. **Money and abstract inventories:** "stole coins" in an aspect does not debit an account;
   "short delivery" does not itself reconcile stock or balances. Use authoritative reducers.

The sensible scope is hidden state plus observation first, with a clear list of which kinds
of interaction can already realize consequences. Avoid promising a complete intrigue engine
from one new map.

## 9. Implementation options and recommendation

### Option A: hidden map and current knowers only

Smallest change; good for immutable authored facts and static demonstrations. Insufficient
for unseen changes, private discovery during public actions, hidden-item targeting and lossless
lifecycle. Not a complete implementation of the user's idea in a living world.

### Option B: hidden map, learned observations, existing judges, private events

Recommended foundation. Add:

- Hidden aspects on items and places; decide explicitly whether person aspects join the first
  slice. Keep private motives in their existing systems.
- Per-fact confirmed discovery, with a last-learned value rather than automatic live updates.
- Split canonical changes from character observations and public activity descriptions.
- Scene-judge support for consequences and discoveries; finder support for genuine detection.
- Optional item concealment on existing physical locations, with discovery/access-aware tools.
- Guards for lossy item transformations and metadata copying.

Use shared checks/projectors rather than a new omniscient agent making a call every round.
This is a cross-cutting change, but its world model stays small.

### Option C: full beliefs, testimony, deception and persistent observation simulation

Could represent trust, lies, suspicion levels, testimony chains and exact mid-action witnessing.
That is a different, much larger project. Do not make it a prerequisite. Add only what actual
scenes demonstrate is missing from Option B.

### Suggested order, if approved

1. Agree on the knowledge semantics and scope using the questions below.
2. Authored hidden aspects, learned records, replay, safe views and private observation events;
   GM/operator authoring and an explicit inspection path. No secret-writing characters yet
   unless their intention/occupation/narration paths are also private.
3. Judged creation/modification, actor and witness discoveries, safe public work descriptions;
   incidental detection during those judged activities.
4. Concealed items, find/access/transfer integration and lifecycle guards. Treat these as a
   coherent slice: filtering `look` without fixing `find` and targeting is not concealment.
5. Targeted extra observation triggers and a real consequence path needed by a chosen scenario.
   Do not default to consumption/toxic effects as the first demonstration.

This is a possible sequence, not a commitment to build it.

## 10. Test scenes and acceptance questions

Use fixed mock verdicts to test structure/replay first; later small real-model probes assess
judgement quality and prose leakage. A mock test alone cannot prove that a model will avoid
hinting at a secret in public prose. No real runs were made for this investigation.

1. **Concealed repair:** A knows the vase is repaired, B sees only an intact-looking vase, C
   discovers a join during cleaning. Does the judge preserve unrelated aspects and avoid
   telling B either that it is damaged or that C has learned something?
2. **Moved cache:** A hides a keepsake; B finds and moves it while A is absent. Does A retain
   only the old location? Does repeat searching avoid duplicates and free rerolls?
3. **Forgery:** a clerk detects the seal mismatch but cannot identify its maker. Does the
   confirmed record contain only that discovery? Does reading the forged claim leave truth
   unchanged? Can someone warn another without magically proving it?
4. **Private letter:** discover envelope, read text, establish provenance on separate occasions.
   Can opening a chest reveal the envelope without revealing the text? Can the reader keep
   the discovery private while the letter remains physically in the chest?
5. **Ordinary hazard:** an unknown loose chair joint fails during a supported judged activity.
   Does its hidden reality affect the outcome without revealing the saboteur or requiring the
   victim to know the hazard first?
6. **Transfer/cleanup:** a concealed fact survives a gift, a journey, a delivery and overnight
   processing. Unsupported absorption/demotion is refused rather than stripping metadata.
7. **Witness boundaries:** two co-present observers have different senses or evidence; one
   leaves midway; someone arrives late. No blanket knowledge just from end-of-job presence.
8. **Failure paths:** guessed ids, nonexistent ids, missed searches, interruptions, rejected
   verdicts and exhausted checked-answer attempts do not disclose hidden text. Failed
   detection is not recorded as proof of absence or a shortage.
9. **Persistence:** replay and branch reproduce both hard state and learned observations;
   later disclosure does not make old secret events visible retroactively. Destruction and
   retirement do not erase what surviving witnesses learned.
10. **Privacy comparisons:** two worlds differing only in an unobserved secret should give
    identical ordinary views/replies until a permitted observation or physical consequence
    distinguishes them. Test keys, ids, candidate lists, names and errors, not just prose.

## 11. Decisions worth discussing first

1. **Scope:** items and places first, or people too? Is concealment of whole items required
   in the initial useful version, rather than an optional follow-up?
2. **Meaning of knowing:** only direct, judged confirmation, or also reliable testimony?
   Should learned records retain the established value/time as recommended?
3. **Discovery:** explicit inspection and existing work to start, or must casual opening,
   handling and entering sometimes reveal secrets immediately?
4. **Attempts:** should inspection cost time, a query, or an immediate final action? What
   constitutes enough new evidence for another attempt?
5. **Secrecy of activity:** is a neutral outward occupation adequate initially, or do witnesses
   need a judged visible description when concealed work begins?
6. **Item persistence:** is retaining secret-bearing/evidence items as real items acceptable,
   rather than immediately generalizing promotion/demotion to preserve secret identity?
7. **Publication:** does exposing a hidden property make it an ordinary aspect, or remain a
   private fact with more knowers? Seeing the object later should grant knowledge only if
   the property has actually become observable; "everyone currently knows" is not the same.
8. **Authored world:** can any hidden facts be present with no knowers? How are initial
   character names resolved, and does export preserve last-learned snapshots or start a fresh
   scenario's knowledge? These need separate rules for save/replay versus scenario export.
9. **Consequence scope:** which one or two interactions should prove that hidden reality
   matters? Suggested first choices: concealed damage and document authenticity, plus an
   item cache. These cover different problems without selecting one plot as the architecture.

## Source map

Primary code inspected:

- `src/manor/mechanics/items.clj`: schemas, visibility/reach, lifecycle reducers and narration.
- `src/manor/mechanics/items/{views,actions,work,judge,find,steward}.clj`.
- `src/manor/mechanics/{physical,space}.clj` and `conversation/judge.clj`.
- `src/manor/{world,prompt}.clj` and `src/manor/engine/work.clj`.
- `resources/prompts/judges/{scene,finder}.md`.
- `resources/worlds/palace.edn`.

Related documentation: `docs/stores-and-aspects-design.md`, `docs/find-context-design.md`,
`docs/scenario-authoring.md` (including deliveries), and the current item/physical follow-ups
in `docs/roadmap.md`. Existing docs describe shipped behaviour; this document does not change
those contracts.
