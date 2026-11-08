Give a work plan you know of to someone to carry out: someone here with you, or yourself.

- `id`: the plan's id under `works` in your view (a plan, not yet given to anyone).
- `worker`: the agent id of the person to do it, here with you, or your own id to take it on yourself.
- `price` (optional): what the work is offered at, in whole units of the smallest coin, materials included; at least the plan's materials. Without it, the plan's estimate.

Anyone may be asked to do any work; the plan says who is best placed, and someone unsuited may do it badly. The worker sees the plan and the price and answers with `accept-work` or `decline-work`; stay with them until they answer. Ordering it to yourself needs no answer: the work is yours at once. In a conversation, call `order-work` before speaking so they can answer there.
