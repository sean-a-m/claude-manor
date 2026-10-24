# A modest household with new money: proposal for a second Claudeworth scenario

Status, 2026-10-23: **the starting world is built** as the `:claudeworth-medium` scenario —
`resources/worlds/claudeworth-medium.edn`, `src/manor/scenarios/claudeworth_medium.clj`,
`resources/architect/prices-1904-medium.edn` and `test/manor/claudeworth_medium_test.clj`.
What was built, and the user's decisions, are in section 9. Income itself is not built (another
piece of work), so sections 4 and 6 are still the design target rather than the state. The
existing `:claudeworth` scenario is untouched and stays as the small test house.

## 0. What this is

A new scenario on Claudeworth's mechanics, with its own house, family, staff and world file. It
keeps **Claudeworth village and Hollin Abbey** (copy their locations, routes, residents and
items from `resources/worlds/claudeworth.edn`); only the household is new. Don't carry over
anything else from the current house or cast: the existing world is a feature test bed, not a
design.

The scenario should suit long runs (weeks or months of game time), not just a day or two.
Income isn't implemented yet but will be soon, so the money in section 4 is the design target for
it; until then it goes into characters' situations and the starting balance (section 8.5).

The figures are period-plausible estimates from general knowledge (Beeton-style tables of servants
for each income, typical 1904 wages, income tax at 1s in the pound, consols at 2½%). They weren't
looked up; spot-check them before they become mechanics.

## 1. The premise

The Earls of Claudeworth have had a title and little else for two generations. The agricultural
depression of the 1880s cut their rents, and a predecessor's debts were paid by selling the great
house and most of the land. The family kept **the Manor**, their older and smaller second house,
and about 3,000 acres let to tenant farmers. They've lived there quietly and a little beyond their
means.

In 1903 an iron company found **ironstone** under part of the estate and took a lease to quarry
it. Quarrying began in the spring of 1904. The first half-year's royalty account came at
Michaelmas: about three times what the family is used to seeing, and the company forecasts that
output will double. For the first time in living memory they're properly well off. They are
also careful people, and the money is still a forecast.

This gives a reason to expand, a reason to expand steadily rather than spend everything at once
(the old scenario's earl kept trying to spend his whole account on one grand room; see
`docs/claudeworth-spending-investigation.md`), and an income that grows over a long run.

An earl in a house this size needs the backstory above. Without it, a baronet ("Sir X and Lady X")
would be the natural rank. The user's preference so far is to keep the earl.

## 2. The family

| Who | Age | Present | What they want |
|---|---|---|---|
| The Earl | ~52 | from the start | A house fit for the title, but a careful man all his life who doesn't trust the forecast |
| The Countess | ~44 | from the start | Comfort, proper staff, a house where she can return hospitality and launch her daughter |
| Lady (daughter) | 17 | from the start | Her governess left this summer. She's to be presented at Court next spring and wants a London Season |
| Viscount (the heir) | 14 | **comes home at Christmas** (~20 December) | At Eton; home only in the holidays. Wants shooting and a horse |
| The Dowager Countess (the earl's mother) | ~74 | **moves in later** | Lives in lodgings in Cheltenham on her jointure, the widow's allowance the estate pays her. Comes once there's a suite for her and a room for her maid, bringing the maid and her opinions |

That's two more family members than the current scenario, one more arriving with the first
holidays and one more once the house has grown. Forms of address: an earl's daughter is "Lady
Firstname" and his heir uses a courtesy title (addressed "my lord"). The catalogue's
`:daughter-of-the-house` ("Miss {first-name}") and `:son-of-the-house` ("Master {first-name}")
are wrong for them; override `:addressed-as` in the world file's `:roles`.

## 3. The house at the start

A Jacobean manor with a Georgian front, small for the family's rank.

- **Ground floor, family:** entrance hall, drawing room, dining room (seats ten), library (the
  earl's business room), morning room (the countess's).
- **Service:** kitchen (the servants eat here; there is no servants' hall), scullery, larder,
  butler's pantry with a silver cupboard. The manservant sleeps in a room off the pantry to guard
  the plate. A back stair. A lean-to in the yard for boots and lamps.
- **First floor:** the earl's bedroom and dressing room, the countess's bedroom, the daughter's
  room, the son's room, and **one** guest room. **No bathroom**: hip baths filled from cans of
  hot water. One WC.
- **Attic:** a room for the cook, a room for the lady's maid, a room the two housemaids share, and
  a slip of a room for the kitchen maid.
- **Outside:** a three-stall stable, a coach house with the coachman's rooms above, a walled
  kitchen garden with one glasshouse, and the gardener's cottage.
- **Utilities:** oil lamps and candles; well water hand-pumped to a roof cistern; the range
  boiler heats water for the kitchen only.

**Every servant's bed is taken.** Any new indoor servant needs a room first, so building and
hiring depend on each other.

## 4. Money

### 4.1 The old income (to 1903)

| | A year |
|---|---|
| Farm rents, gross (~3,000 acres at ~20s an acre, down from 28s before the depression) | £3,000 |
| less farm repairs, tithe, rates, land tax, insurance and the agent's 5% | −£920 |
| less interest on a £16,000 mortgage from the old debts | −£600 |
| less the Dowager's jointure | −£300 |
| Consols and railway debentures (~£12,000 at about 3%) | +£360 |
| **Available to the family** | **≈ £1,540** |

### 4.2 What the house costs to run now

| | A year |
|---|---|
| Indoor wages | £170 |
| Outdoor wages | £140 |
| Servants' board (6 living in, about 11s a week each) | £170 |
| Family table and wine | £220 |
| Coal, lamp oil and candles | £90 |
| Laundry (sent to the village laundress) | £40 |
| Horses and carriage | £100 |
| Garden, liveries, rates and licences | £95 |
| Income tax (1s in the pound) | £75 |
| Eton | £200 |
| Dress and personal spending (the countess's pin money, the daughter's allowance) | £220 |
| Charity, subscriptions, church and abbey, Christmas boxes | £50 |
| Repairs | £50 |
| **Total** | **≈ £1,620** |

They've run about £100 a year behind, kept afloat by putting off repairs and an overdraft at the
market-town bank.

### 4.3 The new income: ironstone royalties

The lease runs 60 years. It sets a minimum rent of £500 a year whatever is quarried, a royalty of
about 5d a ton above that, and a surface rent for the land the quarry takes.

| Year | Output | Royalties | Family's total income |
|---|---|---|---|
| 1903 | none | — | ~£1,540 |
| 1904–05 (the start) | ~60,000 tons | ~£1,250 | ~£2,800 |
| 1906 (forecast) | ~120,000 tons | ~£2,500 | ~£4,000 |
| 1908 on (forecast) | ~180,000 tons | ~£3,700 | ~£5,200 |

### 4.4 When money arrives

- **Rents:** half-yearly. Lady Day (25 March) and Michaelmas (29 September) rents are collected
  some weeks later at a rent audit, with a dinner for the tenants at the Crown.
- **Royalties:** half-yearly accounts, January and July.
- **Dividends:** quarterly (January, April, July, October).

Income arrives in lumps while spending is steady, so the bank balance rises and falls and an
overdraft is a normal tool.

### 4.5 Reasons for caution

- Before 1906 the forecast is only a forecast, and the iron trade goes through slumps.
- The quarry takes farmland, so tenants want their rents reduced.
- Every £1,000 of capital spent costs about £30 a year in lost dividends.
- Paying off the mortgage would add £600 a year but use about two years of royalties.

So the earl has real choices: pay off debt, build, or pay for the London Season (a furnished
London house for May to July costs several hundred pounds).

### 4.6 How the family's money is split

Historically, and possibly later in the simulation: the earl's account takes rents and royalties;
the countess has pin money settled at her marriage (about £300 a year) for her dress and personal
spending; the earl gives her a monthly housekeeping allowance; the daughter has a dress allowance
of about £100. Tradesmen are paid quarterly on account, not in cash over the counter (the shops
in the simulation currently sell only for cash).

### 4.7 An alternative windfall (not chosen)

The countess's father dies and leaves her about £70,000 in trust, yielding about £2,400 a year.
Under the Married Women's Property Act 1882 it's her own property, and trustees control the
capital. That's better for family politics (she holds the new purse; the earl must persuade her
and a cautious solicitor) but worse for long runs: it's flat, doesn't grow, and doesn't change
the village. The ironstone was preferred; the legacy could be a second event later.

## 5. Staff at the start

| Post | Wage a year (living in, board provided) | Sleeps | Notes |
|---|---|---|---|
| Butler-valet | ~£45 | room off the pantry | One man doing two jobs: the door, the wine, the plate and waiting at table, plus valeting the earl. When the house grows he becomes a proper butler or is replaced by one |
| Cook | ~£36 | attic | No housekeeper, so she keeps the stores; the countess keeps the books |
| Lady's maid | ~£28 | attic | Serves both the countess and the daughter, which will become a strain |
| Head housemaid | ~£24 | attic, shared | Also waits at table with the butler |
| Housemaid | ~£18 | attic, shared | |
| Kitchen maid | ~£18 | attic | Does the scullery work too |
| Coachman-groom | 25s a week (~£65) with rooms | over the coach house, married | Two horses, a brougham and a dog-cart |
| Gardener | 22s a week (~£57) with cottage | his cottage | The only gardener |
| Garden boy | 7s a week | at home in the village | Comes in at 6 to do boots, knives and coals, as was common in small houses |

From outside: the laundry goes weekly to the village laundress, the sweep comes twice a year, a
charwoman comes for heavy cleaning, and a land agent (a solicitor in the market town, off the map)
manages the estate for 5% of the rents. He has never dealt with anything like the quarry.

No secretary, governess or footman. Small households preferred maids to menservants partly
because of the 15s-a-year tax on each male servant. A secretary or an estate clerk to handle the
quarry business is a natural early hire.

## 6. Where the household is heading

### 6.1 The target (~£4,000–5,000 a year, around 1907)

| Indoor (~13) | Outdoor (~8) |
|---|---|
| Butler, valet, footman, hall boy | Coachman and groom (or a chauffeur, if they buy a car at £600–900) |
| Housekeeper, head housemaid, two housemaids | Head gardener, two under-gardeners, garden boy |
| Cook, kitchen maid, scullery maid | Gamekeeper, for shooting parties |
| Lady's maid for the countess, one for the daughter (the Dowager brings her own) | Estate clerk or resident agent, for the quarry business |

The laundry could be built and staffed with two laundry maids, or kept going to the village
laundress (which keeps the village involved). A household this size costs about £3,000–3,500 a
year to run, plus about £800 in a year with a London Season, leaving roughly £1,000–1,500 a year
for building and saving.

### 6.2 A natural order of growth (by decision, not date)

1. **First comforts (the first winter):** a bathroom and a hot-water boiler (a few hundred
   pounds); the attic lumber room made into two more beds; a scullery maid (so the kitchen maid
   can cook) and a hall boy. The daughter's coming out soon needs a second housemaid.
2. **Service wing:** a servants' hall, a housekeeper's room, proper larders, a pantry with a plate
   safe, 6–8 servants' bedrooms. Then a housekeeper and a footman, and the butler-valet split
   into two posts.
3. **Family and guest side:** 3–4 guest bedrooms, a second bathroom, a larger drawing room or a
   billiard room, a sitting room for the daughter, the Dowager's suite (after which she moves
   in), and an electric lighting plant (£500–1,500 then).
4. **Outside:** stables or a motor house, more glasshouses, a keeper's cottage.

All of that building totals about £8,000–12,000, paid from several years' surplus, a loan
secured on the royalties, or both. That paces the expansion naturally.

## 7. Ties to the village and abbey, and the first year's calendar

- The rent-audit dinner is held at the Crown.
- The architect in the village is the obvious man for the work.
- The quarry brings carters and labourers into the village: the Crown gets busier, and the quarry
  competes with the house for servants, pushing local wages up.
- The ironstone runs on under the abbey's land, so the monks must decide whether to lease theirs.

| Date | Event |
|---|---|
| 15 October 1904 | Start (just after Michaelmas; pheasant shooting has begun) |
| Late October | Michaelmas rent audit |
| 11 November | Martinmas |
| ~20 December | The son comes home from Eton |
| 25 December | Christmas quarter day: wages and Christmas boxes |
| January | Dividends and the royalty account; the son goes back to school |
| 25 March | Lady Day |
| May–July | The Season: go to London or not? |
| 24 June | Midsummer quarter day |
| July | Royalty account |
| 29 September | Michaelmas |

## 8. Building it with the engine as it is

No new mechanics are needed for the starting household. The steps:

### 8.1 A scenario namespace

`src/manor/scenarios/<name>.clj`, modelled on `src/manor/scenarios/lodge.clj`. The scenario key
maps to the namespace (`:claudeworth-household` → `manor.scenarios.claudeworth-household`, file
`claudeworth_household.clj`); the name is a placeholder until the user picks one.

```clojure
(ns manor.scenarios.claudeworth-household
  "…"
  (:require [manor.mechanics.hiring :as hiring]
            [manor.scenarios.claudeworth :as cw]
            [manor.scenarios.claudeworth.deliveries :as deliveries]))

(def scenario
  (deliveries/install
   (hiring/install (assoc cw/scenario
                          :name :claudeworth-household
                          :doc "…"
                          :defaults {:world-file "worlds/claudeworth-household.edn" :max-cost 0.20}
                          :mock-policy #(cw/scripted-policy {} %)
                          :mock-conversation (constantly nil))
                   "carriage-sweep")))
```

- `hiring/install` is applied again because it bakes in the meeting place (where candidates
  arrive and deliveries are left); use whatever id the new approach has. `deliveries/install` is
  applied again because its system closes over the scenario it was given. Lodge does both.
- **Don't bring over Claudeworth's mock day.** Its script is keyed to Claudeworth's agent and room
  ids. The empty script above makes every character pass under `--mock`, so mock runs and the
  smoke test work. Without it the new scenario would inherit Claudeworth's script.
- Add the scenario to `bundled` in `test/manor/scenarios_test.clj`. That test runs every bundled
  scenario offline for 8 rounds and checks there are no forfeits and that replay gives the same
  world.

### 8.2 The world file

`resources/worlds/<name>.edn`, in the format of `claudeworth.edn` (the contract is in
`docs/scenario-authoring.md`, "World files"). What the checks require:

- `:meta`: `:name`, `:start "1904-10-15T08:00"`, `:minutes-per-round 30`, `:starting-funds`
  (whole pounds), `:positions "positions/england-1904.edn"`, `:setting` (a paragraph), and
  `:architect-prices` (the village keeps its architect's office, so a price list is required).
- Locations: house rooms are `:domain :house` (building attaches new rooms only to those); the
  village and abbey keep their domains; `walk` moves within a domain and `travel` moves between
  them along `:routes`. Every place someone sleeps is `:kind :bedroom`, including the coachman's
  rooms, the gardener's cottage and the villagers' rooms. Indoor rooms get one or two starting
  `:aspects`.
- Every agent: `:name`, `:sex`, `:role`, `:location` and a `:bedroom`. Outdoor staff and villagers
  with a `:routine` hibernate (no model calls) when nobody is near, which keeps costs down.
- Stores: the pantry or larder, the kitchen dresser and a housemaid's cupboard as `:store` items,
  as in `claudeworth.edn`.

### 8.3 Positions

From the catalogue (`resources/positions/england-1904.edn`): `:earl`, `:countess`, `:cook`,
`:ladys-maid`, `:head-housemaid`, `:housemaid`, `:kitchen-maid`, `:coachman`, `:head-gardener`,
and later `:butler`, `:valet`, `:footman`, `:hall-boy`, `:housekeeper`, `:scullery-maid`,
`:groom`, `:under-gardener`, `:gamekeeper`.

To add or override in the world file's `:roles` (an entry needs at least `:about`; new ones also
`:title`, `:standing`, `:sleeps`):

- `:daughter-of-the-house`: `:addressed-as "Lady {first-name}"`.
- `:son-of-the-house`: `:addressed-as "my lord"`.
- `:butler-valet` (new, `:upper-servant`, in-house): both jobs in one `:about`.
- `:dowager-countess` (new, `:family`, in-house, "my lady"), for later.
- `:garden-boy` (new, `:outdoor`, lives out): boots, knives and coals in the morning, then the garden.
- `:coachman` and `:head-gardener`: `:about` overrides for a one-man stable and a one-man garden,
  or say so in their situations.
- `:workplace` entries for the staff and villagers, and the GM's `:about`.

### 8.4 Characters

Write names, roles, situations and bedrooms by hand. The money in section 4 belongs mostly in the
earl's and countess's `:situation` (what they know of the accounts, the forecast, the caution), the
servants' wages and terms in their own situations, and the long-term plans in the family's. Then
fill in personas and traits with `(roll-cast! "resources/worlds/<name>.edn")` at the REPL
(`clj -M:dev`). That calls a real model (default cap $0.20) and writes into the file, so review it
with `git diff`, and move its run record from `test-runs/` to `old-test-runs/` afterwards.

### 8.5 Money until income exists

- `:starting-funds` is the bank balance on 15 October 1904. A balance well under the largest
  price on the architect's list keeps the first winter modest; the user should pick the figure
  (section 9).
- The GM brief in `claudeworth.clj` says "This slice has no rents or other income": true until
  income lands, and it then needs changing.
- The architect's prices come from the world (`:meta :architect-prices`). The current list
  (`resources/architect/prices-1904.edn`: small £200, medium £500, large £1,200, grand £3,000)
  could be kept or replaced with one for this scenario (a bathroom, a wing and so on cost
  differently).

### 8.6 Later arrivals

Timed arrivals are visits (`docs/visits-design.md`). The son is in the world file, away at
school (`:away? true`), and comes home by an authored visit in the file's `:visits`: 20 December
1904 at 14:00, for the Christmas holidays, known to the baron and baroness, to the son's room.
His family can also invite him home earlier, and the GM or the operator can bring him
(`schedule-visit`, `engine/schedule-visit!`). The Dowager isn't in the file yet: when her suite
exists she can be written in as an away character with an authored visit of her own, or added by
the operator with `(spawn! {...})` at the REPL (`dev/user.clj`).

### 8.7 Verifying

1. `clojure -M:test` (the bundled smoke test covers loading, mock running and replay).
2. A mock run with no panel from `clj -M:dev`: `e/start-run` with `{:llm :mock :offline? true
   :test? true}`, `e/auto!`, `e/wait-idle`, `e/stop-run`.
3. A short real run only with the user's go-ahead: about 8–12 rounds with `:max-rounds` and
   `:max-cost` 0.20, on a port from 8090–8099 if the panel is used (never 8080). Read the
   transcript, report the cost, and move the run to `old-test-runs/`.

## 9. What was built, and the user's decisions (2026-10-23)

The user's decisions, in brackets, and what they became:

1. **Name and key** [`claudeworth-medium`, "bigger than the existing one, but bigger ones will
   come later"]: the world file is `worlds/claudeworth-medium.edn`, the scenario
   `:claudeworth-medium`.
2. **Rank** [baron, "if it's smaller"]: `:baron` and `:baroness` are positions in the world
   file's `:roles` ("my lord", "my lady"); a baron's children are The Honourable, so
   `:son-of-the-house` is addressed "Mr {surname}" and `:daughter-of-the-house` "Miss {surname}".
3. **Income** [both]: ironstone royalties are the premise, written into the family's situations,
   and the countess's legacy is designed as a later event (4.7). Neither is implemented:
   income is a separate piece of work. The cast does not state the legacy yet, and the countess's
   origin has since been rolled as the Cotswolds, so whoever implements it places the father and
   the trustees.
4. **Starting balance** [a little below £3,000, because of `docs/claudeworth-spending-investigation.md`]:
   `:starting-funds 2900`, and the new price list's largest work is £4,000, so one commission
   cannot empty the account. `claudeworth_medium_test` asserts both.
5. **Price list** [draft one now if doable]: `resources/architect/prices-1904-medium.edn` —
   "small room" £300, "medium room" £650, "large room" £1,600, "grand wing" £4,000, build days
   1 for the first three and 2 for the grand wing.
6. **The son and the Dowager** [TBD; an event system is being built separately]: the son is in
   the world file (name, persona, room, situation) marked `:away? true`: he's in the run but away
   at school, comes home on 20 December by the world file's authored visit, and his family can
   invite him home sooner (visits; this replaced the scenario's
   `:defaults :leave-out`). The Dowager is not in the file:
   she needs a suite that does not exist yet, and she comes with the arrival work.
7. **Names** [`roll-cast!` cannot randomise them, so fitting ones]: the family is
   Lord/Lady Claudeworth, Miss Claudeworth and Master Claudeworth; the staff are Mr Merrick
   (butler-valet), Mrs Thorne (cook), Miss Nash (lady's maid), Emily and Rose (housemaids),
   Bessie (kitchen maid), Mr Ridley (coachman), Mr Durrant (head gardener) and Alf (garden lad).
   The village and abbey keep the `:claudeworth` cast, names and personas. The medium village
   has since gained more trades of its own: Mr Pocock (baker), Mrs Garland (poulterer and game
   dealer), Mr Jessop (fishmonger, at a stall on the green), Mr Kemp (cobbler) and Constable
   Hollis (the county police constable), each with a workplace, bedroom and routine.

### 9.1 How the cast was written

Roles, layout, bedrooms, situations and names were written by hand; personas, appearances and
traits came from `roll-cast!` (two rolls, $0.006 each). Three things were needed to make the
roll fit the premise, all now recorded in the code and docs:

- **The polish sees the agent's `:situation`** (as its `:notes`, which `polish-request` and the
  instructions always supported; `roll-one` was not passing it). Before that, the roll made the
  seventeen-year-old daughter twelve with the housekeeping on her hands.
- **Positions carry the ages the design wants** (`:ages` in the world's `:roles`: the baron
  [52 52], the baroness [44 44], the daughter [17 17], the son [14 14], and narrower ranges for
  the servants, which also narrow who may be hired into each post later).
- **Hand-edits after the roll** for the few things it still got wrong: a Black Country iron
  family for the baron, a Chancery suit for the son, parlourmaids, an under-butler, a pub called
  the Plough, a fourteen-year-old Alf, and a girl walking twenty miles home on a half-day.

`roll-cast!`'s log and calls for both rolls were lost: they are written under `test-runs/`,
which any test run deletes wholesale. That is now roadmap C15.
