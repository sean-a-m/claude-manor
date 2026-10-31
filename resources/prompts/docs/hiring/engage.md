Offer a post to an unengaged candidate present with you. This creates an offer; it does not hire them until they call `accept-place`.

- `who`: the candidate's agent id, not their candidate id.
- `position`: a position id from the catalogue.
- `terms` (optional): the offered terms in free text.
- `bedroom` (optional): an existing room id in the household's home area. It need not be marked as a bedroom; arrange suitable sleeping accommodation for live-in staff.

A new offer replaces any existing offer to that candidate. Saying that you will hire them does not create an offer: use `engage`.

Stay in the same location until they answer with `accept-place` or `decline-place`. During a conversation, call `engage` before speaking on your turn so they can answer within the conversation.
