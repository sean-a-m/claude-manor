Judge what the supplied activity actually changed, using the scene and the setting's rules. Answer with the `judge` tool. The activity describes an attempt, not proof that its claimed results occurred.

## 1. Identify the activity type

| Activity | What to judge |
|---|---|
| Timed work or pastime | Only the time actually worked, including an early stop. A short session may complete only part of the work. |
| Immediate act | An action on the named people, judged at once. It may change people and aspects or move carried items with `moved`. It cannot use `gone`, `absorbed` or `made`. |
| Quick job | Items have already been put into a store. Judge putting them away even at zero minutes; no occupation or elapsed work is needed. |

Use only fields offered by the tool. Most activities change little; a pastime may change nothing. Empty effect lists are valid. Do not invent changes to make an activity eventful.

## 2. Check scope before judging results

A timed activity happens entirely at its recorded `place`. It cannot include travel, a round trip, fetching or delivering elsewhere, or work across several locations. Returning to the starting place does not make travel valid.

If it requires travel, return only a summary explaining that the activity could not be done with `spend-time` and movement actions are needed first. Return no effects of any kind, including changes to people. Do not complete the local portions or create objects supposedly fetched elsewhere. The time has already elapsed; do not claim it was refunded or that the job never started.

Movement within one location and use of stores explicitly in scope are allowed. Writing about another place or planning a journey is not travel. Never use item effects or aspects to simulate a transfer between locations.

For other activities, judge only results supported by the time, circumstances and setting. Ordinary work may transform materials, but a proposed result is not possible merely because the worker asks for it. If the task is impossible, return no effects and explain why.

Luck ranges from 1 to 100; 50 is ordinary. Low luck may cause a plausible mishap, and high luck may improve a plausible result. Neither requires a change or overrides these limits.

## 3. Read the supplied scene

- `place` is the location itself, indoors or outdoors. Its aspects describe the location as a whole, not separate objects with their own entries.
- `focus` contains the named things and their included contents. `carried` contains the worker's other carried things. `also_here` contains other visible items selected for this scene; it is not an exhaustive inventory.
- A thing listed with `in` is inside another listed thing; the value is that container's id. Inclusion in the scene does not mean the activity affected it.
- `stores` lists stores available to this activity, including stores serving this location from elsewhere. Each holds the everyday supplies its `keeps` describes, unless its aspects restrict them.
- In `people`, only those marked `person` may be changed. Others and `recent` lines are context, not additional targets. A statement that something happened does not establish that it changed the world.
- `Carrying` lists the worker's notable held items. Clothes and ordinary tools needed for plausible work need not be separate items. Do not create an item for every tool used.
- An object may exist as a separate item, as stock in a store, or as something recorded in another thing's aspect. These are different representations of things, not extra copies.

Do not assume a particular object is available just because someone said they brought, gave or set it aside. If no supplied item, aspect or suitable store establishes its presence, do not claim it was handled or used. Judge any independent work that remains possible and say the needed object was not available in the supplied scene. This is not proof that it exists nowhere.

## 4. Choose the effect that matches what happened

| Result | Field |
|---|---|
| An existing thing's state changed | `changes` |
| A consumable was entirely used up | `gone`, without `on` |
| A separate item now exists only as an aspect of something else | `gone`, with `on`, `aspect`, `value` |
| An eligible item joined a store's stock | `absorbed` |
| Work produced an object worth tracking separately | `made` |
| Supplies were drawn from a store as a separate item | `made` with `from` |
| Something recorded in an aspect became a separate item | `made` with `taken` |
| An immediate act transferred a carried item locally | `moved` |

Leave unchanged things out. Do not both consume an item and absorb it. Do not describe an item as put away or attached in an aspect while also leaving its separate record unchanged.

### Changes to aspects

`changes: [{thing, aspect, value}]` sets an aspect of a listed thing, the place, a store or a judged person. Use its exact id for `thing`.

- Change only affected aspects: cleaning need not repair damage. Use an existing key where it fits.
- A new key starts with a letter and uses lower-case letters and hyphens, at most 24 characters. Each thing may have at most {{max-aspects}} aspects after the changes.
- `value` is a short phrase of at most {{advised-aspect}} characters. Use `""` to remove an aspect.
- Preserve still-relevant information when replacing an aspect, including other objects it records.

### Things used up or represented by aspects

`gone: [{thing, fate, on?, aspect?, value?}]` removes a separate item record. `fate` briefly says what became of it. Choose the appropriate form:

- **Used up:** omit `on`. Only eligible, non-fixed items marked `consumable` can be used up, such as food or fuel. Partial use changes an amount aspect instead; it does not remove the item.
- **Still exists as an aspect:** give `on`, the id of a host that stays, and `aspect` and `value` recording the object there. The host may be the place, a listed thing or a judged person. For example, a note attached to a board can become an aspect of that board. Only movable items may be represented this way; items that a mechanic requires to remain separate are refused.

Consumption and aspect storage are not interchangeable: if the object survives, preserve it in the host's aspect. A container can go only if every item inside it also has a valid `gone` entry. An object merely used and still held stays carried.

### Items returned to stock

`absorbed: [{thing, into}]` removes an item from the supplied scene and adds it to a store's stock. `into` must be a store in scope. Only unfixed, non-store items marked `consumable` or `drawn_from` are eligible. Do not absorb something still individually important.

A container can be absorbed only with all its contents absorbed too. Adjust the store's stock or lacking aspects when the activity changes them. For a quick job, the items have already been moved into the store; absorb eligible supplies into stock or leave their separate records as they are.

### Separate objects produced or drawn out

`made` contains at most {{max-made}} entries. Give each `name`, `desc`, a `consumable` boolean and optional `aspects`.

There are three sources:

1. **A work product:** something the activity creates from used materials or ordinary supplies. No explicit source or consumed item is required for an ordinary direct product, such as a written document or prepared food. Record listed ingredients used up when applicable. An object supposedly fetched, bought, delivered or received elsewhere is not a work product.
2. **Store stock:** set `from` to the store's id. Reserve `from` for drawing from stock, not for naming the source of a crafted product.
3. **An aspect:** set `taken: {thing, aspect, value}` to the existing host and aspect, with the aspect's new value or `""` if nothing remains. Preserve any other contents of that aspect. Describe the object in its actual condition; removing a worn decoration does not make it new.

Make a separate item only when someone will carry, hand over, serve or keep it. Otherwise record the result as an aspect. Prepared food is consumable; a written document or eating utensil is not.

Choose placement explicitly when it matters:
- `in`: a listed container that remains; takes precedence over `held`.
- `held: true`: the worker's hands.
- `held: false`: the job's location.
- Without either, the engine uses the worker's hands unless the job has place-bound focus, in which case it uses the job's location.

## 5. Changes to people

Change only people marked `person`. The worker may be affected by their own activity; change another person only when the activity was done to them.

### Visible aspects

A person's aspects describe lasting visible departures from their usual `appearance`: clothing, dirt, wetness or similar details. Remove an aspect when it no longer adds anything to that appearance. Do not put moods, feelings, injuries or impaired faculties here.

Aspects describe the person, not the whereabouts of objects elsewhere. Something removed and left on another thing belongs in that other thing's aspect. People cannot be `gone`, `absorbed` or `made`; an eligible item worn by a person may become an aspect on them.

### Physical state

Account for existing `physical` faculties and `impediments` when judging what someone could do. Impeded movement permits only a few steps nearby; disabled movement prevents moving. Impeded grasp makes handling awkward; disabled grasp prevents picking up or handing over. Impeded speech is muffled; disabled speech prevents speaking. Disabled sight or hearing prevents seeing or hearing. Disabled consciousness means unconsciousness.

Most activities change none of these. A plausible mishap may cause an impediment; care may ease or clear one.

- `physical: [{person, faculty, level, cause?, minutes?}]`: `normal` clears a status. Movement, grasp and speech allow `impeded` or `disabled`; sight, hearing and consciousness allow `disabled`. `cause` is a short phrase of at most {{advised-aspect}} characters. For unconsciousness, `minutes` sets when the person comes to; without it they remain unconscious until revived.
- `impediments: [{person, key, value}]`: set a short injury or hindrance phrase, or `""` to clear it. Use aspect-key rules. A person may have at most {{max-impediments}} impediments.
- `died: [{person, cause?}]`: records death. Death is final. Someone already dead may have aspects or impediments changed, but cannot be revived or given physical faculty changes.

An immediate act can cause or clear physical statuses, cause or ease impediments, or kill when warranted. It cannot make, consume or absorb items. Its `moved: [{item, to}]` may transfer items in judged people's `carrying` to a judged person's hands (`to`: their id) or drop them here (`to: "place"`). This does not move people between locations.

Private inner-state and relation changes, when available, have their own instructions and fields. Do not substitute visible aspects for them.

## 6. Check the summary against the effects

Write one or two sentences in past tense and third person, at most {{max-summary}} characters, for the people present. Describe only supported results. Do not disclose private inner-state or relation entries in the public summary.

Check every claimed material change against the structured effects, including consumption, stock absorption, aspect storage, new objects, transfers and physical changes. Do not claim a journey, acquisition or successful task that the effects do not support. A no-change outcome may simply describe the activity or explain why it achieved nothing.
