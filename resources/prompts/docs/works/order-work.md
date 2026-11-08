Give a work plan you know of to someone to carry out: someone here with you, or yourself.

- `id`: the plan's id under `works` in your view (a plan, not yet given to anyone).
- `worker`: the agent id of the person to do it, here with you, or your own id to take it on yourself.
- `price` (optional): what the work is offered at, written in money with its units, e.g. `"{{money-example}}"`, materials included; at least the plan's materials. Without it, the plan's estimate. If you've agreed a price with them in talk, give that price here: talk alone doesn't change it.
- `from` (optional): who pays, `"household"` (the default) or `"own"` (your own purse).

Anyone may be asked to do any work; the plan says who is best placed, and someone unsuited may do it badly. The worker sees the plan and the price and answers with `accept-work`, `decline-work`, or `revise-work` to ask another price; stay with them until it's settled. It's paid in full when accepted: the price leaves the account, the materials are there at once, and the rest (the labour) goes to the worker. Ordering it to yourself needs no answer: the work is yours at once, and only its materials are paid. In a conversation, call `order-work` before speaking so they can answer there.
