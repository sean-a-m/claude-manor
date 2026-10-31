Check availability and quote firm prices for goods to a customer at your shop. This does not charge them. The shop must be open.

- `for`: the customer's id; they must be present.
- `goods`: up to {{max-lines}} lines. Each line describes one kind of goods with `item` and `amount`, using the amount the customer requested ({{amount-examples}}).
- `deliver`: set to `true` within a goods line to quote delivery to {{home}} instead of collection, if your shop offers delivery.

Each line produces a quote with a `quote-id` or a refusal, announced at the counter. Quote the shopping list in one call, before promising goods or stating prices. There is no fixed catalogue, bargaining, or credit.

The customer accepts quotes with `buy`: counter goods can be taken or left paid for at the shop; delivery goods are paid in full and sent by the courier, with nothing for you to prepare, pack, or carry. Use `send-up` to send paid goods left at the shop or new goods charged directly to the household. Your shop's sales and what it still has to send are in `shop-orders` in your view: nothing is sold or sent that isn't there.

During a conversation at your counter, call `quote-goods` before speaking on your turn, then tell the customer the prices.
