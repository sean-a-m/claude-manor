Plan a work: a lasting change to the things where you are, done by someone's labour. The plan is private to you; it says what will be done, what it would cost, and who is best placed to do it.

- `kind`: `install` (something new fixed here: a range, a gasolier), `move` (a thing here to another place in the home) or `remove` (a thing here taken away for good).
- `request`: what you want, in your words ("a three-light gas chandelier over the dining table").
- `target`: for move and remove, the id of a thing here (not carried).
- `to`: for move, the id of a place in the home.

Mending, cleaning or tuning a thing is not a work: use `spend-time`. You can have at most {{max-open-plans}} plans not yet given to anyone. The plan comes back with an id under `works` in your view; use `order-work` with it to give it to someone to carry out (or to yourself), and `cancel-work` to let it go. If the plan wants a tradesperson, it names the trade: ask someone who might know to `recommend` with that `trade`, `send-for` the contact with the work's id in the job, and `order-work` it to them when they come. That is advice only: you may give the plan to anyone.
