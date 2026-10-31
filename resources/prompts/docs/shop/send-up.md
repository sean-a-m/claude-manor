Arrange delivery to {{home}} from your shop, if it offers delivery. The courier handles preparation, packing, and transport; you do not need to do these yourself.

Provide `items`, `goods`, or both:
- `items`: up to {{max-lines}} distinct item ids marked `waiting-for` someone at your shop. These goods are already paid for and are sent for the person they are waiting for.
- `within-hours`: delivery delay for `items`, from 1 to {{send-up-max-hours}} hours; default 1. It does not apply to `goods`.
- `goods`: up to {{max-lines}} new goods lines, each with `item` and `amount` as requested.
- `for`: recipient's id, required with `goods`. The recipient need not be present.

New goods are checked for availability and price, with a delivery time assigned to each accepted line. Accepted lines are charged immediately to the household account; if it cannot cover their combined price, none of the new goods are sent. Send only goods requested at the household's expense. No `buy` call is needed.

Check the result for what was sent or refused. Delivery times outside shop opening hours move to the next opening; pending deliveries appear in `orders`.

During a conversation at your counter, you can call `send-up` before speaking on your turn.
