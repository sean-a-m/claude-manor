# Sending for people: the first real run (2026-11-09)

**Status:** observations and the user's first thoughts, for planning the next work. Nothing here is
decided unless it says so. This is the starting point for the next session on this subject.

Design: `docs/sending-for-design.md` (phases 0–3 done and mock-tested; this run is the first of
phase 4). Roadmap: B21. Earlier review: `docs/works-run-review-2026-11-08.md`.

Sources:

- `runs/claudeworth-small-20261109-064910`: the user's run of `claudeworth-small` on the
  `sending-for-design` branch, reviewed at round 21 (18:00 on the first day), $0.65, deepseek-v4.1-flash
  with reasoning `low`. Read `transcript.md` first.
- Also there, not yet reviewed: `runs/claudeworth-small-20261109-061056` (22 rounds, on the same
  branch) and two one-round runs, `-060840` and `-061022`.

## A. What worked

The new path worked end to end with a real model:

- **Four recommendations, all sensible judge answers, all someone new:**
  - Miss Ellis, asked for an upholsterer: Levi Quince, "upholsterer".
  - Mrs Bell, asked for a cook: Louisa Dibble, "plain cook seeking a place".
  - Mrs Bell, asked for a housemaid: Olive Parsons, "housemaid seeking a place…".
  - Briggs, asked for help with wood: Robert Tidy, "woodman and jobbing labourer".
- **`recommend` and `send-for` on speaking turns**, with no errors or rejections from either.
- **Arrival two hours after sending.** Each arrival event said who sent for them and what about,
  and their `you.visit` said the same.
- **Belongings to suit.** Quince brought a canvas tool bag, a bundle of patterns and a trade card.
  Dibble and Parsons each brought a written character carrying their `:contact-id`.
- **Hiring.** Dibble and Parsons came about places and took them. Fenwick offered first, then Lord
  Claudeworth offered with wages; the later offer replaced the earlier one. Both accepted his
  (cook £28, housemaid £18), with conditions, inside a conversation.
- **Leaving.** Quince used `take-leave` by himself once he was done.

## B. Problems

### 1. No way to pay someone informally (the biggest)

Money moves only through shop purchases, deliveries, the architect and works (paid when a work is
accepted: the price from the account, the labour to the worker's purse). Nobody can hand money to a
person.

- **Quince.** Lady Claudeworth spent about six turns trying to pay him his £8 2s quotation. Her
  thoughts: "The tool set here doesn't include a 'pay' tool", "the coin isn't a findable item". Quince
  in the end "counted the £8 2s into my own hand" with `spend-time`. No money moved.
- **Rudge.** Fenwick walked down to him in person (no `recommend` needed), and Rudge swept two flues
  with `spend-time`: "his lordship's credit is good enough for me". Nothing was paid.
- **Carter.** He hung a flue door and refused payment, though Lord Claudeworth meant to pay him.
- **Tidy.** He was sent for to split wood "by the day". A day's wage can't be paid either.

**The user's view (2026-11-09):**
- They want a `pay` action: an informal payment, because this will keep coming up.
- Their concern is confusion with the payments that happen by themselves. A work pays on
  acceptance, so someone might also `pay` the worker for it, or expect to have to.
- Their idea: partly combine the two. When a payment happens automatically (a work accepted, a
  purchase, a delivery, the architect's fee), both sides see it confirmed exactly as if someone had
  used `pay`, so there is one recognisable kind of payment in the record and in the narration.

Questions for the design:
- **Arguments:** `pay {to amount from for}`? `from` would be "household" or "own", as `order-work`
  takes it; `for` is free text.
- **One event or one narration:** should automatic payments emit the same event as `pay`, or only
  narrate the same way?
- **Where shop money goes:** a shop purchase debits the account but credits nobody. Should the
  shopkeeper's till, or the keeper's purse, receive it? The user thinks money simply vanishing is
  "probably incorrect".
- **Paying for a work twice:** how does a work's own payment show, so nobody pays twice? Should a
  work's text say it's paid on acceptance?
- **Not a concern for now (the user):** who may spend from the household account. Visitors can,
  and Quince's work-1 was "paid from the household account", at 0d.

### 2. Goods worked on or made off the map

Quince's quotation was to take two chairs to Ely, reupholster them, and come back in a fortnight
with them, a carpet, a table and cushions. Nothing supports that:

- **The chairs.** They're marked `:fixed? true` in the world file, so moving them needed a works
  plan (see 3). Quince's `find` "found" his cart, so the finder made a cart item in the front yard.
  He then loaded the chairs with `spend-time` and left. The chairs are still in the front yard, with
  the aspect "two… loaded on the cart", beside a cart whose owner has gone home.
- **What's missing.** There is no way for a visitor to take things off the map, nor for goods made
  elsewhere to be ordered from a contact and delivered later. Trades-design phase 3 ("Merchants":
  orders from a contact through deliveries) is the nearest planned piece.
- The user will discuss this with the next session.

### 3. Moving a thing across rooms was very hard

The user isn't concerned that furniture is marked fixed. They are concerned that it was so hard to
set up the work of moving something through several rooms. What happened depended on which tool
was tried:

1. **`spend-time` to carry the chairs out** (Fenwick, Quince, several times): the activity check
   refused it as work across several rooms. Its reason said to "move to each place with movement
   actions, then… collect goods with item actions".
2. **`take`** (Quince three times, Fenwick once): refused, "the Kitchen chairs is fixed in place; to
   move it, plan a work with plan-work". This contradicts the activity check's advice to use item
   actions.
3. **`plan-work`.** Three plans were made to carry the same chairs from the parlour to the front
   yard:
   - **work-1:** Quince offered it to Lord Claudeworth at 0d, then cancelled it.
   - **work-2:** Fenwick took it on himself at 14:00 and carried it out at 16:00. The chairs moved.
   - **work-3:** Quince planned it again at 15:00, before work-2 was done, and offered it to
     Fenwick, who declined.
4. **Ownership.** The visitor planning and ordering work on the household's things, to the
   householders, is odd but did no harm.

Possible directions, not decided:
- The activity check's refusal for a fixed thing could point to `plan-work` instead of item
  actions.
- A move of an ordinary piece of furniture could be lighter than a work: two people carrying it
  through the rooms.
- `plan-work` could show open plans for the same thing, so nobody plans it twice.

**Done (2026-11-09, branch `moving-across-rooms`; decision 13 of `docs/fixtures-design.md`).**
The user kept furniture fixed and moving it a work, and chose to make that path plain: fixed
things are marked `fixed` in the view; `take`'s refusal gives the steps, or names the open work
on the thing; the rules and tool docs agree; open works in the home are shown to everyone there
(visitors too) and anyone may `order-work` one nobody has taken; only the planner cancels; one
open work per thing; the judge sets a work's length, 30 to 120 minutes. Mock-tested
(`works_test/an-open-plan-is-found-and-taken-on-by-someone-else` replays this case with the
china cabinet). The activity check's strictness and its correction are being worked on
separately (branch `activity-check-prompt`). Not yet seen in a real run.

### 4. Not yet seen in a run

- **Lodging (phase 1b).** Tidy is expected at 18:30 and may stay the night: letting this run go on
  into the evening would test the Crown's back chamber.
- **The judge reading place against job** for someone on the map (design 9.2). All four answers
  were someone new.
- **`appoint` tried on a caller.** Nobody did.
- **A caller leaving and coming back** with a return time.

### 5. Unrelated or already known

- **Judge answers over their length limits** (the run's 12 errors) are works-review item 16,
  unchanged.
- **Carter's forfeit** in round 16 was an OpenRouter timeout after three attempts, which paused the
  run. It isn't from the code.
- **31 rejections**, mostly the activity check refusing work across rooms (3 above) and the
  once-per-turn limits on `converse`, `say` and `find-person`.

## C. Where the sending-for design stands

- **Phases 0–3 are done** and merged to master with this review.
- **Phase 4 (the real run):** this run is the first. It found no fault in the new path itself. The
  checks in B4 are still to be seen.
- **Next, by the user's priorities:**
  1. A design for `pay` and for confirming automatic payments the same way (B1).
  2. Moving things across rooms (B3).
  3. Goods taken or made off the map (B2), to discuss with the user first.
