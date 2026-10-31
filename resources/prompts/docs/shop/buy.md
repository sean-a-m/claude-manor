Buy goods at firm quoted prices. You must be at the quoting shop with the shopkeeper present.

- `quote-ids`: select up to {{max-lines}} unspent quotes made for you from `offers`. Omit unwanted quotes; ask the shopkeeper for a new quote if the goods or amount are wrong.
- `from`: pay from {{household-name}}'s funds by default, or set `"own"` to use your own account. The goods belong to the paying account.
- `hand-over` (required): `"take"` puts counter goods in your hands; `"leave"` keeps them paid for at the shop for collection or delivery (see `waiting-at-shops`).
- Delivery quotes are paid in full and become `orders`, regardless of `hand-over`. Delivery timing appears in `orders`.

Use `converse` to ask the shopkeeper for quotes. During a conversation, they can call `quote-goods` before speaking, and you can call `buy` before speaking on your turn.
