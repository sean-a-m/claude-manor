# Containers: build brief

Self-contained instructions for adding **containers** to `manor.items` and to Claudeworth: items
that hold other items, such as shelves, cabinets and a sideboard. Read `AGENTS.md` first
(invariants, run directories, cost rules). For how items work now, read the Items section of
`docs/scenario-authoring.md` and `src/manor/items.clj`; the finished items briefs are in
`docs/archived/`. This brief is the authority on scope. The user agreed every decision below on
2026-10-16.

## Status

- [x] C1 engine: data, checks, events, actions, views, judge reach, world files, tests
  (`test/manor/containers_test.clj`). Accessor names as built: `contents`, `within`,
  `outermost`, `place-of`, `reachable?`, `visible-at` (the flattened visible list), and `opened`
  (the `open` reply). A loop reads "its containers go round in a loop". Reasons list visible
  items sorted by name, as before.
- [ ] C2 Claudeworth: containers and contents, rules text, mock day, tests, docs
- [ ] C3 one short real run, and the report

Tick each phase here as it's committed, so a paused build can restart from this file.

## Why

Agents should be able to keep things in the right places: NPCs stocking kitchen shelves with
tools, the silver put away in the sideboard. A first step is containers they can see into, open,
take from and put things in.

## Decisions (don't revisit)

1. **Where an item is.** An item has exactly one of `:place` (a location id), `:holder` (an agent
   id) or `:in` (the id of the container item it's in). Contents follow their container without
   events, as carried items follow their holder.
2. **What makes a container.** The optional item key `:container`, either `:open` or `:closed`.
   It's a fixed fact about the item; there are no open or closed *states* and no events for
   opening.
   - `:open`: the contents can be seen from the room, as on shelves or behind glass. The view and
     `look` show them.
   - `:closed`: the contents are seen only with `open`, as in a sideboard or a cupboard.
3. **`open {container}`.** A free action that only replies (like `look`) with the container and
   its contents in detail. It records no event, so nobody else learns anything. It works on open
   containers too, and is kept as a separate tool so that locks can be added to it later.
4. **No opening to take.** `take` works on any item inside a container in reach, at any depth,
   whether or not the agent has opened it. If an agent guesses an id inside a closed container,
   it gets the item. (Later, maybe: locked chests, and needing to open before taking. See the
   roadmap note in C2.)
5. **Nesting is allowed**, with a cycle check: nothing can end up inside itself. There's no
   check on size or sense (a cabinet in a matchbox is allowed) and no capacity limit.
6. **Carryable containers** (a basket) aren't forbidden, but they aren't supported or tested
   either. The containers actually made and tested are furniture: shelves, cabinets, a sideboard.
   Don't add code for carried containers beyond what falls out naturally.
7. **No "belongs in" tags.** Agents decide where things go. Items get no home or proper-place key.
8. **Views list everything visible.** An open container's contents are listed in full, however
   many there are. If views grow too large, deal with it later (a count instead of names, say).
9. **Work and the judge.** Work on a container reaches its contents: the judge sees every item
   inside it, at any depth, open or closed, and decides what the work did to each. Naming a
   container counts as one of `work-on`'s six items. If the judge behaves badly with this, adjust
   later.
10. **Claudeworth content:**
    - the **china cabinet** becomes `:open` (it's glass-fronted), holding the Meissen figures as
      separate items, which come out of its description;
    - a **sideboard** in the dining room, `:closed`, holding the silver candlesticks and a few
      other things;
    - **kitchen shelves** (a kitchen dresser), `:open`, holding a few working things: pans, a
      kettle, blacking brushes for the range.

    Nellie's brief gets **no hint** about where the candlesticks are. The real run checks whether
    she finds them by opening the sideboard; if she doesn't, consider a hint afterwards.

## C1: the engine (`src/manor/items.clj`, plus `worldfile.clj`)

### Data and accessors

- `Item` schema: add `[:in {:optional true} :string]` and
  `[:container {:optional true} [:enum :open :closed]]`.
- New accessors (the rest of the code must still never read `(:items world)` directly):
  - `(contents world id)`: the items directly in a container, by name.
  - `(outermost world it)`: follow `:in` up to the item that lies at a place or is held.
  - `(place-of world it)` / `(holder-of world it)`: where the outermost item lies or who holds it.
  - `(within world id)`: every item inside a container, at any depth.
  - `(visible-contents world id)`: the contents as seen from the room. These are the direct
    contents, each `:open` container among them with its own visible contents nested, and closed
    ones without theirs.
- `items-at` keeps its meaning: items lying directly at a place, not those in containers.
- **In reach** for an agent: the outermost item lies where the agent is, or the agent holds it.
  This one rule is used by `take`, `put-in`, `open` and `work-on`.

### Checks (`problems`)

- Exactly one of `:place`, `:holder` and `:in`. The message is "give exactly one of :place,
  :holder and :in"; update the existing tests' expected text.
- `:in` must name an item that exists, either in the list or among the items already in the world
  (pass those to `problems`, so that `added` can check new items against the live world). That
  item must have `:container`.
- No cycles: following `:in` from any item must end at a place or a holder.
- Order doesn't matter: contents may be listed before their container (the reducers just assoc).

### Events

| Event | Data | Visible to | Reads |
|---|---|---|---|
| `:item/taken` (changed) | `{:item :from-place}`, plus `:from-item` when it came out of a container | everyone here | "Nellie takes the silver candlesticks from the sideboard." |
| `:item/put-in` (new) | `{:item :into}` | everyone here | "Nellie puts the silver candlesticks in the sideboard." |

- The `:item/taken` reducer also dissocs `:in`. `:item/put-in` dissocs `:place` and `:holder`
  and sets `:in`. `:item/put-down` dissocs `:in` too, for safety.
- `:item/added` narration for an item with `:in` reads "Item added to the sideboard: ...".
- Old runs replay unchanged: their events never carry `:in`.

### Actions

- **`take {item}`**: as now, but an item inside a container in reach can be taken too. Refused
  if it's fixed, or if its outermost container is held by someone else. In that case say "X is
  not here" unless the item itself is the held one, so nothing hidden leaks.
- **`put-in {item container}`** (free, new): `item` is one you carry, or one you could `take`
  here. `container` is a container in reach. Refused if the item is the container itself or the
  container is inside the item ("you can't put the X inside itself"), or if the item is already
  directly in that container. Give actionable reasons, as the other item actions do.
- **`open {container}`** (free, new): reply-only. Refused while travelling, if the item isn't in
  reach ("X is not here; items here: ..."), or if it isn't a container ("the X is not something
  you can open"). The reply is
  `{:name :condition :contents [{:id :name :desc :condition :owner? :fixed? ...}]}`. Inner open
  containers have their own `:contents` nested, and inner closed ones get `:closed true` without
  their contents. An empty container replies `:contents []`.
- **`put-down`** and **`give`** are unchanged: they work on what you carry directly.
- **Reasons that list items** (`here-list` in `take`, `open` and `work-on`) list the visible
  items: those lying here plus the contents of open containers, flattened. **Never** the contents
  of closed containers.

### Views and `look`

- The view's `:location :here :items` shows each container with what can be seen of it:
  - an open container: `{:id :name :contents [{:id :name} ...]}`, nested for inner open
    containers, with `:contents []` when empty, so agents know they can put things there;
  - a closed container: `{:id :name :closed true}`.
- `look` does the same in detail: each item as now, open containers with `:contents` in detail,
  closed ones with `:closed true`.
- `:you :carrying` and the people-here `:carrying` lists are unchanged (decision 6).
- The GM view already has every item, now with `:in` and `:container`.

### Work and the judge

- `work-reason`: an item is workable if it's in reach (decision 9 allows any depth).
- `in-reach` (the judge's items when a job ends): each job item still in reach of the job's place
  or its worker, plus everything `within` it, without duplicates. Use the job's place, not the
  worker's current place, as now.
- The judge's item JSON gains `:in` (the container's id) for items inside a container. Add one
  line to `judge-instructions`: things listed with `in` are inside another of the things worked
  on, and the work may or may not touch them; leave them out if it didn't.
- `verdict` already allows changes to any item in reach; with the expanded reach that covers the
  contents.

### World files

- `item-key-order`: `[:id :name :place :in :holder :owner :fixed? :container :condition :desc]`.
- `worldfile/problems` and `engine/check-items!` work through `items/problems` and need no change
  of their own, beyond passing what's needed.
- The export round-trips `:in` and `:container`.

### Tests (new `test/manor/containers_test.clj`)

Don't change `test/fixtures/hamlet.edn`: many existing assertions count its items. Instead, write
a variant of it to `test-runs/` (as the Pugh test in `claudeworth_test.clj` does) with, for
example, an open shelf and a closed cupboard in the hall, a box inside the cupboard, and things in
each. Cover:

- the world-file checks: three-way exclusivity, unknown container, not a container, a cycle;
- the accessors: `contents`, `within`, `outermost`, `visible-contents`;
- the view and `look`: open contents shown and nested, closed ones marked and hidden;
- `open`'s reply, its refusals, and that it records no event;
- `take` from an open and from a closed container, at depth, with the narration and the event
  data; refused when fixed;
- `put-in` from hand and from the room, into a nested container, its cycle refusal and reasons,
  and the narration;
- privacy: reasons and views never show closed contents; another room sees no events;
- `work-on` a container: the judge request lists the contents with `:in`, and the judge may
  change them;
- a short mock run with containers: no forfeits, replay equal, export round-trip.

`clojure -M:test` must pass. Village, treasure and quiz must be unaffected (they have no
`:items`).

## C2: Claudeworth (`resources/worlds/claudeworth.edn`, `src/manor/scenarios/claudeworth.clj`)

- **The world file.**
  - The china cabinet becomes `:container :open`, with the description "A glass-fronted walnut
    cabinet." or similar. Its figures become items `:in "china-cabinet"`: a shepherdess, a
    harlequin, a pug.
  - Add the sideboard (dining room, fixed, `:closed`). The silver candlesticks move into it, along
    with, for example, a canteen of cutlery and table linen. Take "Breakfast is laid on the
    sideboard" out of the dining room's description, or reword it so the sideboard isn't
    described twice.
  - Add the kitchen dresser (kitchen, fixed, `:open`) with a few working things: a kettle,
    saucepans, blacking brushes.
  - Keep descriptions within the limits, with the household as owner.
- **The rules text** (`claudeworth/rules`) says, in brief:
  - some things hold others: open ones show their contents under `contents`, and closed ones
    (`closed`) show them only when you `open` them;
  - `take` works on things in containers here;
  - `put-in` puts something into a container;
  - `open` and `put-in` don't end the turn, alongside `look`, `take`, `put-down` and `give`.

  Keep it short.
- **Nellie's `:about`**: no hint about the candlesticks (decision 10).
- **The mock day** (`claudeworth/mock-day`): the countess `open`s the sideboard before taking the
  candlesticks. After polishing them, Nellie takes them back to the dining room and `put-in`s them
  in the sideboard. Turns that aren't listed still pass.
- **Tests:**
  - a bundled-content test (in `scenarios_test.clj` next to `claudeworth-default-cast`, or in
    `claudeworth_test.clj` against the bundled scenario) that the containers and contents exist
    as described, and that a mock day with the housemaid has no forfeits and no `error:` or
    `rejected:` replies, with replay equal;
  - leave the household fixture alone unless a mechanics test needs a container.
- **Docs:**
  - `docs/scenario-authoring.md`, Items section: the `:in` and `:container` keys, the new
    accessors, the events table, `open` and `put-in`, the view shapes, the judge's reach, and
    what to tell agents in a brief;
  - `docs/roadmap.md` B7: containers are done. Add follow-ups: locked containers, possibly
    needing to open before taking, carried containers (unsupported), view size with many
    contents, and the "in" wording for shelves in narration;
  - `docs/claudeworth-design.md` section 12: a decision for containers;
  - `AGENTS.md` only if the layout changes;
  - `README.md` if it lists actions.

## C3: the real run

There is one authorized run, after C1 and C2 are committed, in the foreground:
`(start! :claudeworth {:test? true :housemaid? true :start "1904-10-15T09:00" :max-rounds 10
:max-cost 0.20})` (see `dev/user.clj` for the exact helper). Read the transcript, then report:

- the cost and the number of calls;
- forfeits and `error:`/`rejected:` replies, and what caused them;
- `open` and `put-in` calls, and how often `open` is used compared with `look`;
- whether Nellie finds the candlesticks (opens the sideboard, or takes them by guessing), and
  whether anyone puts things away;
- any judge call on a container, and whether it handled the contents sensibly.

Copy the key lines into this brief's status section before `test-runs/` is cleaned. Ask the user
before any second run.

## When you're done

Record the outcomes (the roadmap, the design decisions, the authoring guide), make sure
`clojure -M:test` passes, and move this brief to `docs/archived/`.
