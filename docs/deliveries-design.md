# Deliveries: design and implementation plan

**Status:** done; a follow-up, "Hand-over", is at the end. Phases 1–4 are built and mock-tested, and the real-run check is below
("Real run"). As built, the shared functions are
`hiring/new-person`, `hiring/spawn-spec` and `hiring/idle-rounds` (in `manor.mechanics.hiring`, which owns
the people bridges and adjudicators), not `people/...` as sketched below.

Read `AGENTS.md` first, then "Internal adjudication queries", "Composed queries", `:systems`,
"The nightly steward" and "Runtime candidates and hiring" in `docs/scenario-authoring.md`,
`src/manor/mechanics/shop.clj` and `src/manor/mechanics/hiring.clj`.

## Why

Village shops sell only what a customer can carry away. In real runs Briggs refused "a dozen
stout heavy dust sheets" and "brandy by the case", and in another run Hale wrote to a coal
merchant who doesn't exist. Three things refuse such orders: the store profiles in the world
files ("no bulk deliveries or special orders"), the appraiser's instructions (`shop.clj`,
"Refuse ... bulk goods, deliveries, special orders") and the shopkeeper and butcher positions'
`:about` ("nothing is set by, kept for collection or delivered").

A 1904 household had large orders sent up from the shop. Deliveries let a customer order at
the counter goods that are too large or too many to carry, pay for them at once, and have
them left at the front of the house later, brought by the shop's courier.

## Decisions (the user's, 2026-10-19)

1. **Only shops on the map.** No off-map suppliers (a coal merchant, a wine merchant, a town
   draper) for now.
2. **Orders are placed in person at the counter**, as shopping is now. Ordering by letter, or
   the tradesman's boy calling at the house for orders, comes later if at all.
3. **Paid when ordering**, through `buy`. Agents often talk of putting things "on the account";
   that stays talk, and `buy` debits the household account either way.
4. **The courier is an agent, one per shop, made once and reused** for every later delivery from
   that shop.
5. **Who hears an arrival:** everyone within walking distance of the delivery place, i.e. the
   whole house and its approach, as if hearing the bell.
6. **The nightly steward leaves delivered goods alone**, for now.
7. **The appraiser chooses the lead time**, within bounds; the engine does the clock
   arithmetic.
8. **It must scale to 20+ shops.** No position or code per shop. A shop opts in with world-file
   data, and couriers share generic positions from the catalogue.
9. **Delivery profiles are vague.** They describe a shop's delivery scope in general terms,
   never a list of goods, so they don't put ideas (paraffin, say) in agents' heads.

## Design

### The flow

```
customer converses at the counter ── clerk: quote-goods {for, goods: [{item qty deliver?} ...]}
   └─ appraiser: counter lines as today; delivery lines against the shop's :delivery-profile,
      each with lead-hours
customer: buy {quote-ids}       one buy, one funds check on the total
   └─ counter quote  → :shop/bought + a carried parcel (unchanged)
   └─ delivery quote → :claudeworth/ordered: money paid, order kept until due
deliveries system, at round start
   └─ compose (I/O): if a due order's shop has no courier yet, generate one (people server + polish)
   └─ tick (pure): for each shop with due orders, parcels at the delivery place, the courier
      spawned or returned there, the arrival announced; last round's couriers go home
```

### World data

A shop opts in with a `:delivery-profile` on its location, beside `:store-profile`. It is
1–400 characters, and `shop/problems` checks it. Where it's absent, the shop doesn't deliver. An
optional `:courier` names a catalogue position (default `:errand-boy`). The delivery place is
hiring's `:meeting-place` (Claudeworth: `carriage-sweep`; Lodge: `front-gate`).

Profiles describe scope in general terms, never a list of goods. Current profiles include:

- **Butcher's shop:** "Larger orders, more than a customer can carry, are sent up to houses in
  the neighbourhood."
- **General store:** "Orders too large to carry away, and goods in the shop's line that can be
  got in from town, are sent up to houses in the neighbourhood." `:courier :carrier`.
- **Draper's shop:** "Larger parcels in the drapery trade, too much for a customer to carry
  away, are sent up to houses in the neighbourhood." Like the butcher, it uses the default
  `:errand-boy` courier; the draper's duties explain quoting delivery lines and leaving them
  to the courier. This is content using the existing system, with no new delivery mechanics.
- **Lodge's village shop:** "Larger orders are carried across to customers' houses."

Store profiles lose "no bulk deliveries or special orders" and keep "only portable goods sold
over the counter": the counter's scope is unchanged, and the delivery profile covers the rest.
The butcher's "no ... whole carcasses" stays in his store profile. The appraiser decides
whether a whole carcass, or half of one, fits his delivery profile.

### Positions

One new catalogue entry, shared by every shop:

```clojure
:errand-boy
{:title "Errand boy" :standing :villager :sleeps :out :addressed-as "{first-name}" :ages [13 18] :sex :male
 :about "You work for a village tradesman, carrying goods out to customers' houses and running his errands."}
```

`:carrier` already exists, for shops whose goods come by cart. A shop names any position with
`:courier`; the courier's post records which shop they work for, so 20 shops need no new
positions.

### Quoting

- `quote-goods` goods lines take an optional `deliver` boolean. A delivery line at a shop with no
  `:delivery-profile` is rejected by `quote-reason` before any model call, with "the Butcher's
  shop doesn't send goods out; ask without deliver".
- The appraiser's request adds the shop's `:delivery-profile` when it has one, and marks the
  lines asking for delivery. Its schema gains `lead-hours`, an integer from 1 to 120, which a
  delivery line must have when it's available.
- Instructions, delivery lines: judge against the delivery profile and the setting; larger
  quantities and goods too big to carry are fine when the profile covers them; refuse what this
  shop wouldn't plausibly supply, and services, people, property and unsupported mechanics as
  now; give a lead time in hours, a realistic one for the order.
- Instructions, counter lines: unchanged, plus: when a refused line is too large to carry but
  the delivery profile would cover it, the reason may say it could be sent up if ordered for
  delivery.
- The quote stores `:deliver {:lead-hours n}`. Its narration says "to be sent up to the Carriage
  sweep within 3 hours of ordering".

### Ordering

- `buy` takes counter and delivery quotes together. A delivery quote emits `:claudeworth/ordered`
  instead of `:shop/bought` plus a parcel, and it is visible to the counter audience. Its
  reducer debits the money, spends the quote and adds `[:state :orders id]`, holding buyer, clerk,
  shop, item, unit, qty, price, desc, condition, consumable, `:due` and `:to`.
- **Due round:** `round + rounds-for(lead-hours)`, then moved forward into the shop's `:hours` if
  it falls outside them, so a 10-hour order placed at 15:00 comes at 08:00 next day. Always at
  least next round. Clock helpers do this, never the model.
- The narration names the time: "Hale orders a sack of flour (1 × 1 sack) for 3s 6d, to be
  delivered at the Carriage sweep by Saturday 14:00."
- **View:** `orders` lists the viewer's pending orders, for the buyer and the clerk only:
  id, item, amount, shop and due time. At a delivering shop, `here` also shows its
  `:delivery-profile`.

### Arrival

`manor.mechanics.deliveries` adds a system, `:deliveries/arrivals`, after hiring's visits. Its tick
is pure. For each shop with orders due by this round, in shop-id order:

1. Each order's parcel is added at the delivery place with `items/added`: owner household,
   aspects `{:amount ... :state condition}`, `:consumable?` from the appraisal, and an
   `:order-id`.
2. The courier:
   - if the shop's courier is away, they return (`:people/returned`, with a new situation);
   - if they're still here from an earlier delivery, their idle timer restarts;
   - if the shop has none yet and this round's compose made one, they are recorded and
     spawned;
   - otherwise there is no courier. That happens when there's no room under `:max-agents`, when
     the courier is neither away nor here (retired by the GM, say), or when compose made
     nobody (the cost cap stopped it, or the polish gave two bad answers). This is one branch:
     the goods come without a courier, and compose tries again at the next delivery if the
     shop has none. Server outages get no handling of their own (`AGENTS.md`).
3. `:deliveries/arrived` announces it to everyone within walking distance of the delivery place.
   With a courier: "Tom Ridding (tom-ridding), from the Butcher's shop, leaves a side of mutton
   (1 × side) at the Carriage sweep." Without one: "A delivery from the Butcher's shop is left at
   the Carriage sweep: ...". Its reducer removes the orders.

The courier's situation: "You have brought goods up from the Butcher's shop for the household;
they are left at the Carriage sweep. Speak to whoever comes if you like; you go back once nobody
needs you." They take turns as usual, and anyone at the house can `converse` with them.

They go home after an idle timer, the rule hiring already uses for an unengaged visitor. They
leave at the start of a round once `courier-leave-after` (2) rounds have passed since they
arrived or last conversed, whichever is later. So a courier nobody talks to has the arrival
round and one more. A conversation in any later round gives them one round more; one in the
arrival round changes nothing, since they'd still have the next round anyway. A second delivery from
the same shop resets `:arrived`, which restarts the timer with no special case. Leaving uses
the world's existing `:people/departed`, which sets them `:away`, `:towards` the shop's name.

### Couriers and the people server

How people are generated is changing (the `character-generation` branch). Among other things,
circumstances now come from the polish, the record is stripped by `people/base-record`, and
`recommend!` puts the steps together in a new order. So deliveries must not reproduce those
steps. It depends on two shared functions instead, which are taken out of hiring's code once
that branch is merged:

- **Making a person:** `(people/new-person services {:position :area :seed :taken-names})`
  returns whatever a fresh candidate is made of today: the record, persona, manner and
  appearance. It is the generation half of `recommend!`, which then calls it too.
- **Spawning a person:** `(people/spawn-spec person)`, the traits and public and private prose
  that hiring's `arrival-events` merges into a spawn spec. Hiring's arrivals call it too.
- **The idle timer:** `(hiring/idle-rounds world agent-id arrived)`, the number of rounds since
  arrival or the last conversation. Hiring's `tick` now compares it with
  `candidate-wait-rounds` (a full 24 game hours; 24 rounds without a clock), while deliveries
  still uses `courier-leave-after` (2). The longer hiring grace does not extend courier visits.

Deliveries treat the person as opaque: they store it, pass it to `spawn-spec` and read only
`:name`. A later change to how people are written then reaches candidates and couriers alike,
with nothing to change here.

Couriers live in `[:state :couriers shop-id]`: `{:person :agent-id :status :position}`. They are
not in hiring's pool, so nobody can `recommend`, `send-for` or `engage` them through it. The
seed comes from the run seed and the shop id; taken names include pool and courier names.

The spawn event is `:system`, as with candidates, since it holds a private persona and traits.
The courier holds the courier position. Their post is outside any household, its `:employer` is
the order's clerk, and its terms are "carries for the Butcher's shop (butchers-shop)", so the
shopkeeper sees their own courier under `posts`.

### Engine change: systems that compose

Systems can call one bridge today but no models, and making a courier needs a bridge and two
checked model calls. A system may now give `:compose (fn [world services] result)`. Its
`services` are the same `bridge` and `adjudicate` callbacks a composed query gets, with the same
budget checks and recording. They are logged as agent `"system"`; adjudicators use the run's
`:model`. Its result, `{:result r}` or `{:error msg}` when it threw, goes to `:tick` like a bridge
response. Compose must cost nothing when there's nothing to do: the deliveries compose only calls
out when a due order's shop has no courier. Replay never calls it: what it produced is in the
tick's events.

### The steward

`item-steward/steward-candidates` skips items with an `:order-id`, wherever they've been taken. The item
schema gains `[:order-id {:optional true} :string]`, as it has `:candidate-id` for references.
Work can still use delivered food up: only the nightly pass leaves it alone.

### Cost

Once per shop: one people-server call (free) and two cheap model calls (about $0.001). Each
delivery: at least two courier turns (about $0.004), and another for each round in which
someone talks to them. An order adds nothing to the appraisal.

### Not in scope

Off-map suppliers, ordering by letter or by the courier calling for orders, tradesmen's accounts,
cancellation and refunds, delivery to another door or room, goods too heavy to carry (carrying
is unlimited), spoilage of goods left out, and the courier carrying goods indoors.

## Real run (2026-10-19)

`old-test-runs/deliveries-first-check`: Claudeworth from 10:00, 12 rounds (8, then continued to
12 so the orders could arrive), $0.199, 0 forfeits, replay equal. Mrs Finch's situation asked for
meat, flour and a side of bacon, "far more than anyone could carry home"; delivery wasn't
mentioned.

- **Ordering worked unprompted.** At the butcher's, Hobbs quoted the meat over the counter;
  Mrs Finch asked for it "sent up", and he re-quoted it with `deliver` in the same
  conversation. At the general store the appraiser refused a sack of flour over the counter
  and said it "could be sent up to the house if ordered for delivery"; Briggs then quoted it
  for delivery. A side of bacon was refused at the store as the butcher's trade, and ordered
  from Hobbs. Lead times: 3 hours (fresh meat), 4 (flour), 24 (bacon).
- **Arrival and couriers worked.** The meat came at 14:00 with Hobbs's errand boy, Richard
  Dunn, and the flour at 15:30 with Briggs's carrier, Stephen Dobbs; everyone in the house
  heard. Nellie, Daisy and Mrs Finch carried it all to the pantry. Dunn said a word to the
  family and went home after two rounds; Dobbs talked with Nellie while she checked the sack.
  Couriers cost about $0.002 in turns each, plus about $0.002 to make each one.
- **Money was right** (£2 7s 2d spent, exactly the orders and some sugar), but Hale, who sees
  the balance and not the orders, spent several turns tracing a "missing pound": the bacon.
  This is how private purchases already work; a household account book would answer it.

Observations, at first not changed:

- **The work judge absorbs delivered meat into the pantry's stock** when someone puts it away,
  so the saddle of mutton is no longer a tracked thing for tomorrow's dinner. Counter
  purchases already behave this way; only the nightly steward leaves delivered goods alone.
- **Prices differ between appraisals.** The delivery re-quote priced the legs of mutton at 5s
  against 2s over the counter. Each appraisal is a separate call, and nothing ties the two.

## Fixes after `runs/claudeworth-20261019-210747` (the user's run, 2026-10-19)

No order came due in that run, so no courier was ever made, and the shopkeepers did the
deliveries themselves. Briggs spent half an hour "making up her ladyship's parcel" (the work
judge made it a real item, which the carrier's delivery would later have doubled) and carried
it up after closing; his thoughts said "there's no carrier in the village". Hobbs made out "a
reckoning for the Manor" and walked it up: the characters kept asking for things "put on the
household account" and "the figures written plain". Fixes (branch `courier-reference`):

- **Couriers are named at the start,** by a free people-server record for each delivering shop
  in round 1, and written (appearance and polish, two model calls) only when the shop first has
  an order due. The name is shown only at the shop (`here.orders-sent-up-by`) and in `orders`
  (`sent-up-by`), so the shopkeeper sees all day who takes his orders up, at the cost of one
  line. A shop that never delivers costs nothing more. (The hand-over below took the name off
  the shop's view again: it read as if the courier were at hand.)
- **Couriers hibernate while nobody else is at the delivery place,** through a one-entry
  routine and Claudeworth's existing hibernation, including the round they arrive. They wake
  when someone comes out. In the mock day Briggs's carrier made one model call.
- **Wording:** the shopkeeper and butcher duties and the `quote-goods` description say the
  courier takes orders up at the time given and the shopkeeper doesn't make up, pack or carry
  them, and there is no bill to send; Claudeworth's rules say everything bought is paid in full
  at once, with no bills, reckonings or accounts to settle later; the judge's brief says making
  up, packing or carrying an order makes nothing.
- **Bills and settling accounts are deferred** to a more realistic treatment later (roadmap
  B7), at the user's request.

## Implementation plan

Each phase ends with `clojure -M:test` passing and a commit.

### Phase 1: systems that compose (engine)

- `engine/run-systems!`: when a system has `:compose`, call it with `query-services` for agent
  `"system"`, catch exceptions as `{:error (ex-message e)}`, emit an operator-only
  `:adjudication/failed` on error, then pass the result to `:tick`.
- Docs: `:compose` under `:systems` in `docs/scenario-authoring.md`.
- Tests (`engine_test.clj`): a test scenario system whose compose calls a stub bridge and a mock
  adjudicator, whose tick records the result. Usage counts towards cost; a throwing compose
  reaches tick as `{:error ...}` and leaves no partial events; a compose that returns nil makes
  no calls; replay reproduces the world without calling compose.

### Phase 2: ordering and arrival without couriers

- `resources/worlds/claudeworth.edn`, `lodge.edn`: delivery profiles and `:courier`; trim the
  store profiles. `shop/problems` checks `:delivery-profile` and `:courier`, the latter against
  the positions table (via `claudeworth/problems`, which has it).
- `shop.clj`:
  - the `deliver` flag on goods lines, and the deliver check in `quote-reason`;
  - in the appraisal: the request data, instructions, `lead-hours` in the schema, and the check
    that a delivery line which is available has it;
  - `offer-events` stores `:deliver`, and the narration says so;
  - `buy-events` branches to `:claudeworth/ordered`;
  - `observe` adds `orders` and the delivery profile;
  - `mock-appraisal` gives delivery lines `lead-hours 2`;
  - `scripted-clerk` passes `deliver` through.
- New `src/manor/mechanics/deliveries.clj`: due-round arithmetic, order reducers and narration,
  and the system with tick only (no couriers yet). Install it from `claudeworth/scenario`, so
  Lodge gets it too.
- `items.clj`: the `:order-id` key, and the steward skips such items.
- Wording:
  - Claudeworth `rules`: replace "no bargaining, bulk counters or delivery" with one sentence:
    "A shop that sends goods out can quote a line with `deliver` for goods too large or too many
    to carry away; they're paid for when you buy and left at the delivery place when due
    (`orders` in your view)." Also fix the stale "ask the shopkeeper with `say`" to `converse`.
  - Shopkeeper and butcher `:about` in the catalogue: "If your shop sends goods out, quote lines
    a customer can't carry away with deliver; they're paid for now and sent up later."
- Tests (new `test/manor/deliveries_test.clj`, on a test-owned world in the style of
  `shop_test.clj`):
  - Quoting:
    - a delivery line gets a quote with lead hours;
    - a deliver line at a shop with no profile is rejected before any model call;
    - an available delivery line without `lead-hours` gets up to three corrections, then fails neutrally;
    - lead hours out of bounds are refused.
  - Buying and orders:
    - one `buy` of a counter quote and a delivery quote: a parcel carried, an order kept, funds
      debited by the total, both quotes spent;
    - an order is visible to the counter audience only;
    - `orders` shows in the buyer's and clerk's views only.
  - Due times:
    - an order due at night arrives at opening time;
    - an order arrives at its due round, never the same round.
  - Arrival:
    - parcels arrive at the delivery place with `:order-id` and consumability;
    - the announcement is heard house-wide, not in the village.
  - Steward: delivered parcels are skipped, even after being moved indoors.
  - Replay reproduces the world.

### Phase 3: couriers

Start after `character-generation` is merged; phases 1 and 2 don't touch people code.

- Refactor first, in its own commit: take `people/new-person`, `people/spawn-spec` and
  `hiring/idle-rounds` out of `recommend!`, hiring's `arrival-events` and its `tick`, with
  hiring calling them. Hiring's tests must pass unchanged.
- Catalogue: `:errand-boy`.
- `deliveries.clj`:
  - compose: `new-person` for each due shop without a courier;
  - tick: record the courier, spawn or return them with the arrival, set their situation and
    post, and send home couriers whose idle timer has run out;
  - the single no-courier branch.
- Tests:
  - Making and reusing couriers:
    - the first delivery from a shop makes and spawns its courier at the delivery place;
    - a later delivery brings the same agent back from `:away`;
    - two shops get two couriers.
  - Leaving:
    - a courier nobody talks to leaves at the start of the second round after arriving;
    - a conversation in their second round keeps them a third;
    - a second delivery from the same shop restarts the timer.
  - No courier: a full agent cap still delivers the goods, without a courier, and a courier is
    made at the next delivery once there's room.
  - Privacy:
    - the courier's persona and record are never in another agent's prompt;
    - the courier's post is visible to the shopkeeper.
  - Replay reproduces the world without calling the people server.

### Phase 4: mock day, docs, check

- Claudeworth `mock-day`: at 12:00 Hale asks for a sack of flour, to be delivered, beside the
  silver polish. At 14:30 Nellie takes it from the carriage sweep to the pantry and puts it on
  the shelves. The bundled smoke test covers it.
- Docs:
  - `docs/scenario-authoring.md`: the shop section (delivery profile, `deliver`, orders,
    couriers), the steward's skip and the item key;
  - `docs/claudeworth-design.md` §6.3 and a new decision;
  - `docs/roadmap.md` B7: deliveries done;
  - the `shop.clj` ns doc.
- A mock Claudeworth day, then its transcript: order, payment, arrival announcement, courier
  turn and departure, Nellie's fetch, and no steward touch overnight.
- **One short real run, with the user's go-ahead.** Claudeworth from `--start
  "1904-10-15T10:00"`, at most 8 rounds, `:max-cost 0.20`, with a situation set at the REPL for
  Mrs Finch: a dinner wants more meat than can be carried. Read the transcript for:
  - whether the clerk uses `deliver` unprompted, or the appraiser's refusal points to it;
  - the lead times the appraiser chooses;
  - the courier's turns and cost;
  - whether anyone fetches the goods.

  Move the run to `old-test-runs/deliveries-first-check`.

## Hand-over: goods left at the shop and `send-up` (2026-10-26)

**Status:** built, phases 1–4, on branch `shop-hand-over`; mock-checked, no real run yet. The
user agreed the design on 2026-10-26. The contract is in `docs/scenario-authoring.md` (the shop
and deliveries part of Internal adjudication queries, Items' `:waiting`, The nightly steward);
what differs from the design below is under "As built" at the end.

### Why

In no real run has a delivery yet arrived, but shopkeepers keep promising them. In
`runs/claudeworth-medium-20261025-203523` there were no orders, four counter sales, and:

- Mrs Garland quoted a goose, fowls and rabbits for the counter, then said "I'll have Reuben up
  the hill with the goose… inside the hour". Mrs Thorne bought them, so they were in her hands.
  Garland called for Reuben, spent an hour "packing up the Manor order for Reuben" (the work
  judge agreed: "the shop stands ready for the boy's knock"), and failed to `find-person`
  "Reuben, my errand boy", who is a people-server record, not an agent.
- Briggs said "I'll have it all into your basket directly. And I'll tell Stephen Dobbs myself",
  then watched the door for Dobbs and asked after him at the Crown; Hollis later asked Carter
  about "Dobbs's cart". Hobbs noted "check the errand boy got the Manor order up"; Pocock
  offered to "send the boy up".
- In `…145921` the general store's carrier became a village carrier for letters and town orders.
  The two real orders in these runs (`…145921`, `…180411`) were due after their runs ended.

The cause is that the conversation and the exchange are decoupled:

- `quote-goods` happens in the conversation, but `buy` is a `:final` action on the buyer's own
  turn afterwards, when both have already agreed in words how the goods travel.
- The clerk decides `deliver` when quoting, before their line. The wish to send goods up comes
  later in the talk (a big order, a full basket, being obliging), when the counter quote
  already exists, and nothing can turn it into a delivery.
- A counter purchase leaves no state the clerk can see, so their own spoken promise is the most
  concrete thing they have. Garland saw "takes the goods away" and packed the order anyway.
- `here.orders-sent-up-by` names the courier at the counter all day, as if he were at hand.

### Design

The exchange happens inside the conversation, and every purchase leaves visible state until
it's settled. The seller decides what is sent up; the buyer decides whether to take the goods.

1. **`buy` takes a hand-over and can be used on a line.** `buy {quote-ids, hand-over: "take" |
   "leave"}`; `hand-over` is required. `take` is today's behaviour (the goods go into the
   buyer's hands). `leave` puts the goods at the shop, paid for and owned by the household,
   marked as waiting for the buyer. Delivery quotes are ordered as now whatever `hand-over`
   says. `buy` becomes `:free` and is a speaking-turn tool for a buyer with unspent quotes at
   that shop, so its result is announced in the conversation before the clerk's next line.
   Narration for `leave`: "Mrs Thorne buys … at the Poulterer's shop, and leaves the goods
   there to be collected or sent up."
2. **Waiting goods are ordinary items with a marker,** owned by the generic items mechanic:
   `:waiting {:for buyer :keeper clerk :at shop :since round}`. `look`, `take`, `give`,
   `put-in` and work work on them as on any item.
   - **Shown as waiting** only while the goods are at the shop: the outermost thing lies at
     `:at`, or is held by the keeper while the keeper is there. In `location.here.items` such an item gets
     `"waiting-for": "Mrs Thorne"`; the shop's rules say goods waiting for someone are paid for,
     to be sent up or handed over.
   - **Cleared** by the reducers when someone other than the keeper takes the item, or it's
     given to someone other than the keeper, or it's put down or put in something away from
     `:at`. `send-up` and the steward remove the item. Picked up, packed or put down by the
     keeper in the shop, it stays waiting.
   - **The buyer's view** gets `waiting-at-shops: [{item, amount, at}]` (left out when empty),
     like `orders`. Only the buyer sees it: if they send someone, they say so, and at the shop
     the marker shows whose the goods are.
3. **`send-up {items, within-hours}`, the clerk's tool.** At a shop with a `:delivery-profile`,
   open, the clerk turns waiting goods there into orders, due within `within-hours` (1 to
   `send-up-max-hours`, 3 for now), fitted to opening hours by `due-round`. It's `:free`, on
   the clerk's turn and as a speaking-turn tool, so "Reuben will have it up within the hour"
   can be done as it's said. No appraisal: the goods are appraised and paid for. The items are
   removed and each becomes an order carrying the item's name, description, aspects and
   consumability; arrival makes the parcel from those, so nothing is duplicated. Orders appear
   in `orders` for the buyer and clerk, with the courier's name, as now.
4. **Held for collection at shops that don't deliver.** The same `leave` works at every shop:
   someone collects the goods or the clerk hands them over. This replaces "nothing is set aside
   for later collection".
5. **The courier is named only on orders.** `here.orders-sent-up-by` goes from the shop's view;
   `sent-up-by` on `orders` and the arrival stay. Couriers are still named at the start (a free
   record), so the name is stable.
6. **The steward.** Waiting goods stay steward candidates like any loose consumable, but the
   steward sees the marker: `"waiting": "for Mrs Thorne since Mon 11:00"` in the candidate's
   JSON, and one instruction line: goods waiting for someone are normally left until they have
   plainly been forgotten for days. No new exclusion code.
7. **`deliver` on quotes stays,** for goods only plausible if sent up (a ton of coal), which the
   appraiser must judge against the delivery profile.

### Decisions (the user's, 2026-10-26)

1. Goods may be held for collection; forgotten ones are left to the steward, given the marker,
   rather than protected by special cases.
2. The clerk sets `send-up`'s lead time, capped low (3 hours) for now.
3. `waiting-at-shops` goes to the buyer only; at the shop the items list shows the marker.

### Not addressed

- A clerk can still say the wrong thing before the purchase, or ignore waiting goods. Measure
  first: count waiting goods never sent or collected in a short real run. If needed, the
  conversation judge could later reconcile agreements with state.
- Invented services (a village carrier for letters). Dropping the courier's name from the
  counter should remove most of the pull.

### Phases

Each ends with `clojure -M:test` passing and a commit, on branch `shop-hand-over`.

1. **Items: the waiting marker** (`manor.mechanics.items`, `items/views`, `items/steward`).
   The `:waiting` key in the item schema; `items/waiting?` (shown-as-waiting test) and
   `items/waiting-for` (a person's waiting items); clearing in the `:item/taken`, `:item/given`,
   `:item/put-down` and `:item/put-in` reducers; the `waiting-for` annotation in
   `add-to-view`; the steward's JSON field and instruction line. Tests: each clearing rule,
   the keeper exception, the annotation only at `:at`, the steward request shows the marker,
   replay.
2. **Shop: `hand-over`** (`shop.clj`, `claudeworth.clj`). `buy` gets `hand-over`, becomes
   `:free`, and joins `speaking-actions` for a buyer with unspent quotes here; `leave` adds the
   waiting items at the shop; narration; `waiting-at-shops` in `observe`; drop
   `here.orders-sent-up-by`; rules, `quote-goods` and `buy` docs, the Claudeworth shopping rules
   and the judge brief; `scripted-customer` (`:accept-offer` takes an optional hand-over,
   default take). Tests: take and leave, buying on a line, the buyer's list, the delivery quote
   ignoring hand-over, the shop view without the courier.
3. **Deliveries: `send-up`** (`deliveries.clj`, `shop.clj`, `claudeworth.clj`). The action, its
   checks (clerk, open, delivering shop, waiting items at this shop, 1–3 hours), the
   `:deliveries/sent-up` event and reducer (items removed, orders added), arrival from
   item-based orders, the speaking-turn tool. Tests: send-up on a line and on a turn, refusals,
   due round within opening hours, arrival with the items' aspects, `orders` for the buyer,
   replay. Extend the Claudeworth mock day: a counter purchase left and sent up.
4. **Docs and check.** `docs/scenario-authoring.md` (shop section, item marker, steward),
   `docs/roadmap.md`, this section's status; a mock day and its transcript.

### As built

As designed, except:

- **The keeper-held rule applies only at the shop:** goods held by the keeper show as waiting
  only while the keeper is at `:at` (`items/waiting?`), so a clerk who walks off with them
  doesn't take the marker round the village. The marker isn't dropped then; it shows again if
  they come back holding the goods, and goes once they're put down or given away elsewhere.
- **Clearing** also happens when work moves a thing (`moved`), and drops the marker from
  everything inside a moved container too.
- **Order ids** of goods sent up are `order-<item-id>` (`deliveries/sent-up-id`), not fresh
  numbers; no tool takes them, as before. The parcel that arrives gets a new item id
  (`tin-of-silver-polish-2`), since a gone item's id is never given out again.
- **Removal** uses a new `items/remove-items` (fate "sent up by the shop's courier"), and
  `send-up` refuses goods with something in them ("take what's in … out first").
- **Orders from goods sent up** keep the item's owner, and have no `:unit`/`:qty`: views and
  narration take the amount from the `:amount` aspect (`deliveries/order-amount`).
- **World files** may give `:waiting` with names for `:for` and `:keeper`; the exported map
  leaves it out.
- **The mock day:** Mr Hale's 12:30 buys with `:hand-over "leave"`, and a new mock marker,
  `:ask-send-up {:item}`, says the line asking for the goods to be sent up; the scripted clerk
  sends up, within an hour, any waiting goods whose buyer's day asks for that at any time. In
  the mock Hale's request is said just before the purchase, in the same turn.

The mock check (a whole small-world day): Hale buys at 12:30 and leaves the polish ("Hale buys
tin of silver polish (one) for 1s 6d at the General store, and leaves the goods there to be
collected or sent up."), Briggs sees it `waiting-for` Hale and sends it up on his turn ("Briggs
sends up tin of silver polish (one) for Hale from the General store, to be delivered at the
Carriage sweep by 13:30."), both see it under `orders`, it arrives at 13:30 with the aspects it
had at the shop, the flour ordered for delivery still arrives at 14:30 and Nellie puts it away,
and the steward at 02:00 leaves everything. Measure in a short real run, with the user's
go-ahead, whether clerks use `send-up` and how many waiting goods are never collected or sent
(see Not addressed).
