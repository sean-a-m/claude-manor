Recommend one candidate for a post to another person here.

- `position`: a position id from the catalogue.
- `for`: the id of the person receiving the recommendation. They must be present and cannot be you.

Call `recommend` before naming a candidate. It finds or creates a hiring-pool candidate; it does not select an established local resident.

You receive private knowledge of the candidate to describe in your own words. Both you and the recipient learn the candidate's id and can use `send-for` to summon them. The candidate does not arrive until summoned.

During a conversation, call `recommend` before speaking on your turn, then give your recommendation.
