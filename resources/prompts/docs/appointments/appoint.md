Appoint someone to a different post. It takes effect at once, wherever they are; they are told, and nobody else.

- `who`: their agent id. Not yourself, a visitor or a candidate (use `engage` for a candidate).
- `position`: a position id from the catalogue, other than the one they hold. To end someone's post, use `dismiss`.
- `terms` (optional): the terms of the new post, in free text.
- `reason` (optional): why, in your own words.

Their bedroom doesn't change: use `assign-bedroom` for that. Agreeing to a change in conversation changes nothing until you call `appoint`.
