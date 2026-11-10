Hand money to someone: wages, a tradesman's bill, a day's labour, a tip, money for errands. This is the only way to pay a person: saying you pay, or spending time counting out coin, moves no money.

- `to`: the agent id of the person paid. They needn't be here; if they aren't, it reaches them where they are.
- `amount`: written in money with its units, e.g. `"{{money-example}}"`.
- `for`: what it's for, in a few words (at most {{max-for}} characters), as the record will say it.
- `from` (optional): `"household"` (the default) or `"own"` (your own purse).

The money goes into their purse at once, and both of you see it paid. A payment from the household account goes in the household's order book.

`pay` buys nothing: for goods at a shop, use `buy` with the quotes in `offers`. Purchases, deliveries, goods sent up, works (when accepted) and commissions pay for themselves in full; their confirmation says "Paid:", and nothing more is owed for them, so don't `pay` for them again.
