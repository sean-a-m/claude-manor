# Deliveries: design and implementation plan

**Status:** done. Phases 1–4 are built and mock-tested, and the real-run check is below
("Real run"). As built, the shared functions are
`hiring/new-person`, `hiring/spawn-spec` and `hiring/idle-rounds` (in `manor.mechanics.hiring`, which owns
the people bridges and adjudicators), not `people/...` as sketched below.

Read `AGENTS.md` first, then "Internal adjudication queries", "Composed queries", `:systems`,
"The nightly steward" and "Runtime candidates and hiring" in `docs/scenario-authoring.md`,
`src/manor/scenarios/claudeworth/shop.clj` and `src/manor/mechanics/hiring.clj`.

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
   └─ counter quote  → :claudeworth/bought + a carried parcel (unchanged)
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
  instead of `:claudeworth/bought` plus a parcel, and it is visible to the counter audience. Its
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

`manor.scenarios.claudeworth.deliveries` adds a system, `:deliveries/arrivals`, after hiring's visits. Its tick
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
  arrival or the last conversation. Hiring's `tick` compares it with `leave-after` (3) and
  deliveries with `courier-leave-after` (2).

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

`items/steward-candidates` skips items with an `:order-id`, wherever they've been taken. The item
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
  line. A shop that never delivers costs nothing more.
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
- New `src/manor/scenarios/claudeworth/deliveries.clj`: due-round arithmetic, order reducers and narration,
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
    - an available delivery line without `lead-hours` gets one correction, then fails neutrally;
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
