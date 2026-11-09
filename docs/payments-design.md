# Paying people: an informal `pay`, and automatic payments confirmed the same way

**Status:** decided (the user, 2026-11-09; section 8). Phases 1 and 2 done, mock-tested; phase 5 (a short real run) next. Phases 3 and 4 dropped. Source: item B1 of
[sending-for-run-review-2026-11-09.md](sending-for-run-review-2026-11-09.md); roadmap B21 "Next".

## 1. The problem

Characters keep making agreements to pay each other outside the formal mechanisms: a quotation
from an upholsterer, a sweep's two flues, a labourer "by the day", wages agreed on `engage`. This
will keep happening, because these agreements are the ordinary business of a household. Today
money moves only in four places, and each one is a reducer of its own event:

| Event | From | To |
|---|---|---|
| `:shop/bought`, `:deliveries/ordered`, `:deliveries/sent-out` | the buyer's account | nobody (it vanishes) |
| `:building/commissioned` | the commissioner's account | the architect's purse gets a 10% fee; the rest vanishes |
| `:works/accepted` | the orderer's account | the worker's purse gets the labour; the materials vanish |

Nothing else can move money. What happened in the run:

- **Lady Claudeworth** spent about six turns trying to pay Quince £8 2s. Her thoughts were
  "there's no pay tool" and "the coin isn't a findable item". She tried `spend-time` ("counting
  £8 2s into his hand"), and the scene judge rightly moved nothing. The conversation judge then
  wrote relations saying he *had* been paid ("You counted the £8 2s into your own hand").
- **Rudge** ("his lordship's credit is good enough for me") and **Carter** (refused payment)
  worked around the gap in character, and **Tidy**'s day wage can't be paid at all. Rudge's
  thoughts show he had worked out from the rules that payment isn't supported: "service payments
  aren't supported here; I shouldn't invent charges."
- **Wages** agreed at `engage` (£28 and £18 a year) are free text in the post's terms. No
  wages are paid automatically, so a `pay` wouldn't double anything here.

**The risk the user named:** with `pay` added, someone might pay again for what already paid
itself (a work paid on acceptance, a purchase paid at `buy`), or think they must. The reverse
risk is someone using `pay` at a shop counter instead of `buy`: they lose the money and get no
goods.

## 2. Proposal in brief

1. **A `pay {to amount for from?}` action:** free, also offered on speaking turns, to anyone
   in the world, here or not (decision 6). It moves money from the household account or the payer's own purse to the
   payee's purse (opening one if they have none) and emits one new event, `:money/paid`.
2. **One form of confirmation:** every payment, made with `pay` or automatically, ends its
   narration with the same `Paid:` line, built by one function. The existing events keep their
   types, data and reducers. Only their narration gains the line (the user's idea, done in
   narration only; see 4.1).
3. **Payments go in the books:** a payment from the household account becomes a `:payment`
   entry in the household order book, next to purchases and works. So household members and the
   conversation judge see what really was paid, whatever was said.
4. **Words where the confusion arises:** the tool doc, the rules, the works view ("paid when
   accepted") and the scene judge's activity check all say that money changes hands only through
   `pay` or the mechanisms that pay themselves, and which those are.

Existing systems keep their events, reducers and payment timing. The changes to them are
narration, one view field and the book.

## 3. The `pay` action

```
pay {to, amount, for, from?}
```

| Argument | Check (structural only) |
|---|---|
| `to` | any non-GM person, active or away, not dead or retired, not yourself; here or not (decision 6) |
| `amount` | money text read by `money/parse` ("£8 2s"), positive; `money/parse-reason` otherwise |
| `for` | required free text, 1–200 characters: what it's for, as the record will say it |
| `from` | `"household"` (default) or `"own"`, as `buy`, `commission` and `order-work` take it; `money/spend-reason` checks the balance |

- **Kind:** `:free`, like `give`, so it can come before or after talk in the same turn. It's also
  offered on speaking turns (`ruleset/speaking-actions`), to someone in the conversation, as
  `buy` and `order-work` are. That way "here's your money" and the paying happen on one line.
- **Event:**
  `{:type :money/paid :visible-to (space/here payer) + payee :data {:payer :payee :account :amount :for}}`,
  with `:sent? true` when the payee wasn't there (the narration then says it reaches them).
  Coin handed over is seen by everyone present, as with `give`, and the payee always learns of it.
- **Reducer:** `[:state :accounts account]` minus `amount`, `[:state :accounts payee]` `fnil +`
  `amount`, and, when `account` is `"household"`, a `:payment` record in the household book. It
  is pure, and replay reproduces it.
- **Reply:** "Paid: £8 2s from the household account to Levi Quince, for the carpet, chairs and
  table. The household has £29,980 left."
- **Payee's view:** `you.purse` appears once they have an account, as it does for a worker paid
  labour today (`building/observe`). They can then `pay` or `buy` with `from: "own"`.
- **Not in it:** asking for or demanding
  money (that's talk), taking money from someone, credit and settling later. **Who may spend the
  household's money is left open** (the user, 2026-11-09).

Uses this covers: a tradesman's quotation (Quince), a job done with `spend-time` (Rudge), a day's
wage (Tidy), quarter wages, a tip, a vail to a guest's servant, petty cash given to a butler for
errands (household account to his purse), and a stake at cards.

## 4. One recognisable kind of payment

### 4.1 Shared event or shared narration?

| Option | What it means | For | Against |
|---|---|---|---|
| **A. Shared narration** (recommended) | Existing events keep moving money; their narration ends with the `Paid:` line from `money/paid-line`, the same function `:money/paid` uses | No change to the four mechanisms' events, reducers or tests; little overlap with other branches; replay unaffected | Two code paths move money (but one shape of words) |
| B. Shared event | Every money movement becomes a `:money/paid` event emitted alongside the mechanism's event; the mechanisms' reducers stop touching accounts | One code path for money; one event type to query | Rewrites the shop, deliveries, works and building reducers and their tests; conflicts with parallel work on works and deliveries; two events per purchase in the record |
| C. Companion record | Mechanisms keep moving money and also emit a no-op `:money/paid` | One event type in the record | Two events for one movement: easy to double-count in code later |

What a character reads is the narration, not the event type, so A gives characters everything B
would. B can come later if code ever needs one event for all money.

### 4.2 The line

`money/paid-line names {:amount :account :payer :to :for :parts}` gives one form:

- `pay`: "**Paid:** £8 2s from the household account, by Lady Claudeworth to Levi Quince, for
  the carpet, chairs and table."
- `buy` (taken away): "… Lady Claudeworth buys linen bed sheets … and takes the goods away.
  **Paid:** £1 7s from the household account to the Draper's shop. Nothing more is owed."
- `buy` with a delivery, and `send-up`: the same line, plus "Nothing more is owed on delivery."
- `works/accepted`: "**Paid:** £3 10s from the household account for work-2: £1 10s to Mr Carter
  for the labour, £2 for the materials, which are there. Nothing more is owed for this work."
- `commission`: "**Paid:** £650 from the household account to the architect's firm. Nothing more
  is owed for this room." (Everyone sees a commission, so the architect's fee, which is private,
  isn't in the line.)

"Nothing more is owed" is the part that heads off paying twice. It's said where the payment
happens, to both sides, in the same words a `pay` would use.

### 4.3 Where the money goes

- **`pay`:** the payee's purse.
- **Works:** unchanged. The labour goes to the worker's purse and the materials are spent.
- **The architect:** unchanged. The fee goes to the architect and the rest to the firm, off the map.
- **Shops (question for the user):** today the money vanishes. Options:
  1. **A till per shop**, `[:state :tills shop-id]`. Its keeper sees it in their view
     (`shop-takings`). Nobody can spend it yet. It's kept apart from `:accounts` so
     `spend-reason`, `from` and the household checks never meet it. *Recommended*, as a small
     later phase.
  2. The keeper's own purse: simple, but wrong for a shop assistant who isn't the owner, and it
     mixes a keeper's wages with the takings.
  3. Leave it vanishing, but name the shop as payee in the line (4.2). Costs nothing now.

  The `Paid:` line names the shop whichever option is chosen, so options 1 and 2 can come later
  without changing the wording.

## 5. Guarding against paying twice

These go in order of strength. 1 and 2 are the proposal, 3 is optional.

1. **The confirmation line** (4.2), seen by payer and payee where the payment happens.
2. **The records show it:**
   - A work's entry under `works` gains `paid: "£3 10s from the household account, when
     accepted"` once it's accepted. Today it shows `price` and `paid-from`, but not that the money
     has already gone.
   - The household book already says "agreed and paid" for a work. A `pay` from the household
     account adds a `:payment` entry ("Paid Levi Quince £8 2s for …, by Lady Claudeworth"), so
     the next person to wonder whether Quince was paid can look.
   - Judges that see the books (conversation, scene) see the same entries. That would have stopped
     the conversation judge writing that Quince "counted the £8 2s into your own hand".
3. **Optional, a structural guard in `pay`:** refuse a payment to someone who, in the last day,
   accepted a paid work ordered by the payer, unless the call adds `extra: true`. The reason
   would say: "work-2 between you was paid in full when accepted (£3 10s, £1 10s of it to his
   purse); nothing more is owed for it. If this is for something else (a tip, another job), pay
   again with extra: true." This checks only ids, roles and rounds, never the `for` text. It
   costs one rejected call in the rare legitimate case. *Recommended only if a run shows
   double payment*: 1 and 2 may be enough, and the guard is machinery.

**`pay` instead of `buy` at a counter:** the tool doc says so plainly: "`pay` buys nothing: for
goods at a shop, use `buy` with the quotes in `offers`." A code guard (refusing `pay` to a shop
clerk at their counter while there are quotes for the payer) would be possible, but isn't
proposed.

## 6. Words

- **Tool doc** `resources/prompts/docs/money/pay.md` (with `{{money-example}}`): what it does;
  that money changes hands only this way or through the mechanisms that pay themselves (purchases,
  deliveries, `send-up`, works on acceptance, commissions); that it doesn't buy goods; that saying
  you pay, or `spend-time` counting coin, moves no money.
- **Rules:** a short `### Paying people` paragraph in `rules/sections/shopping.md` (or a section
  of its own) next to "Everything bought or sent up is paid in full… no bills". Wages and
  agreements made in talk are paid with `pay`, when the payer chooses; nothing pays them by itself.
- **`order-work` and `accept-work` docs:** add "Nothing more is owed for it afterwards; don't
  `pay` for it again."
- **The scene judge's activity check:** an activity that is only handing over money is refused,
  with a reason pointing to `pay`. This is the pattern the review proposes for fixed things and
  `plan-work` (review B3). It's a judge's call, not a code check.
- **The arrival situation for someone sent for** (`situations/sent-for.md`): one sentence saying
  that what they're paid is paid with `pay` by whoever hired them; asking for it is talk.
- **`docs/prompts.md`, `docs/scenario-authoring.md`:** the new template, event and view field.

## 7. Phases

1. **`pay` itself.** `manor.mechanics.payments` (the action, `:money/paid`, its reducer and
   narration, `paid-line`), installed in `ruleset/scenario` and `ruleset/speaking-actions`; the
   tool doc and the rules paragraph; the `:payment` book record. Tests: validation reasons, replay,
   a purse opened for the payee, the speaking-turn variant, palace money. A mock day where someone
   pays a caller.
2. **Automatic payments confirmed the same way.** `paid-line` in the narration of `:shop/bought`,
   `:deliveries/ordered`, `:deliveries/sent-out`, `:works/accepted` and `:building/commissioned`;
   the `paid` field on accepted works; the `order-work`/`accept-work` doc sentences; the scene
   judge's activity-check sentence.
3. ~~Shop tills~~ (dropped: decision 2).
4. ~~The `extra` guard~~ (dropped: decision 4).
5. **A short real run** (about 8 rounds, `:max-cost` 0.20) of `claudeworth-small`, starting where
   a tradesperson has been sent for. Check that `pay` is used, that nobody pays twice for a work,
   and that nobody `pay`s at a counter.

Phases 1 and 2 are each small. Phase 2 edits narration in `shop.clj`, `deliveries.clj`,
`works.clj` and `building.clj`. Other branches (moving things, goods off the map) may touch those
files, so phase 2 should be merged promptly or rebased onto theirs.

## 8. The user's decisions (2026-11-09)

1. **Shared narration** (4.1 A), not one shared event: agreed.
2. **Shop money keeps disappearing** for now, until a more sophisticated system is developed. The
   `Paid:` line names the shop as payee all the same (4.3.3). Phase 3 (tills) is dropped for now.
3. **A household `pay` goes in the household book** as a `:payment` entry: yes.
4. **No double-payment guard** (5.3): too many edge cases. Phase 4 is dropped.
5. **`pay` on speaking turns:** yes.
6. **The payee needn't be present:** payment can be made whenever, to anyone in the world (here,
   elsewhere or away), but not the GM, the dead or the retired. If that causes problems it can be
   changed. So the check on `to` in section 3 is "a non-GM person in the world, not yourself",
   and the event is seen by those with the payer and by the payee.
