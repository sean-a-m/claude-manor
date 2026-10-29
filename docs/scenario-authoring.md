# Scenario authoring guide

This is the complete contract between a scenario and the engine as it stands today. Bundled
examples include:

- `src/manor/scenarios/treasure.clj`: small, about 100 lines. Start here and copy it.
- `src/manor/scenarios/village.clj`: full-featured. Economy, bridges, an act bridge, systems, GM
  triggers, locations, mock policy.
- `src/manor/scenarios/quiz.clj`: an MCP bridge. Each contestant messages a private,
  persistent pi assistant through the pi MCP server.

`src/manor/scenarios/lodge.clj` is a small production household built by associating over
`claudeworth/scenario`, with its own world (`resources/worlds/lodge.edn`), mock day
(`lodge-day.edn`), defaults and excluded architecture tools. It reuses the shop, stores, work
judge, positions and posts without copying mechanics. Its missing maid is each character's
`:situation`, not starting notes. The shared catalogue includes `:mistress` (of a small house)
and `:cook-general` for this sort of household.

`test/manor/scenarios/prize.clj` is a 22-line minimal scenario used by the tests (no locations,
simultaneous turns). `test/manor/scenarios/hamlet.clj` is a small test scenario loaded from a
world file (`test/fixtures/hamlet.edn`), with a clock and a `sleep` action.

## Mental model

- The engine owns **all state**. The world is one map, and it is changed **only by applying
  events**. Your scenario provides:
  - functions that *propose* events (action effects, system ticks, spawn hooks)
  - reducers that say how each event changes the world (`manor.world/apply-event`)
  - descriptions of events as text (`manor.narrate/describe`)
  - a projection of what each agent can see (`:observe`)
- Agents (LLMs, humans, or the mock policy) only ever *call actions*. The engine decodes the
  arguments, checks `:valid?`, and applies the events returned by `:effect`.
- Narrative never feeds back into state. If something matters, it must be in an event and a
  reducer.
- Privacy is per event (`:visible-to`). Agents see only events visible to them plus their
  `:observe` view. The full world never enters an agent prompt.

## File layout and loading

- File: `src/manor/scenarios/<name>.clj`
- Namespace: `manor.scenarios.<name>`
- It must define a var named `scenario` holding the scenario map.
- Start it with `(e/start-run :<name> opts)`, `(start! :<name> opts)` at the REPL, or
  `clojure -M:run <name>`.
- Loading uses `require`, so reloading after edits needs `(require 'manor.scenarios.<name> :reload)`.
  A running run keeps the scenario map it started with, so reload the run too: `(load! id)`. Old
  event logs replay through your *current* reducers. Changing a reducer can make old runs diverge.
- Add the scenario keyword to `bundled` in `test/manor/scenarios_test.clj` so it is smoke-tested.

## The scenario map

```clojure
(def scenario
  {:name          :treasure                ; must match the file/namespace name
   :doc           "..."                    ; optional, informational
   :init          (fn [opts] {...})        ; required, see below
   :roles         {...}                    ; required, see below
   :agent-brief   (fn [world agent-id] "...")  ; optional: the brief of a role :roles doesn't list
   :on-spawn      (fn [world id spec] [events])  ; optional: after every spawn (posts, bedrooms)
   :actions       [...]                    ; scenario actions, see below
   :bridges       {...}                    ; optional, real-world calls
   :adjudicators  {...}                    ; optional, narrow internal model queries (see below)
   :mcp-servers   {...}                    ; optional, MCP servers that bridges call
   :systems       [...]                    ; optional, per-round world processes
   :items         {:judge-brief (fn [world] "...")}   ; optional, turns on items (see Items)
   :calendar      {:lookahead-days 30}     ; optional, in-world schedule (requires a clock)
   :inner         {:keys {:mood "gloss" ...} :open 2}  ; optional, turns on inner state (see Inner state)
   :hibernate     (fn [world agent-id] {:activity .. :location ..} or nil)  ; optional, see Hibernation
   :reference     (fn [world agent-id] {...})   ; optional: facts that rarely change, in the system prompt
   :observe       (fn [world agent-id] {...})   ; strongly recommended
   :gm-view       (fn [world] {...})       ; optional, what the GM sees
   :scene         {...}                    ; optional, operator presentation only (below)
   :schedule      :round-robin             ; or :simultaneous, or (fn [world] [[ids] ...])
   :gm-triggers   [(fn [world] reason-or-nil) ...]
   :pause-when    (fn [world] reason-or-nil)
   :limits        {:max-agents 8}          ; GM spawn limit (default 50 active agents)
   :exclude-actions #{:move}               ; actions to leave out (built-in or scenario)
   :defaults      {:model "..." ...}       ; overrides engine default options for this scenario
   :mock-policy   (fn [ctx] {:tool :dig :args {}})
   :mock-judge    (fn [ctx] {:tool :judge :args {..}})  ; optional, the judge in mock runs (see Items)
   :mock-steward  (fn [ctx] {:tool :steward :args {..}})  ; optional, the nightly steward in mock runs
   :speaking-actions (fn [world speaker conv] actions)    ; optional free/query tools in conversations
   :mock-conversation (fn [ctx] {:tool :reply :args {..}})  ; optional, speaking turns in mock runs (see Conversations)
   :mock-conversation-judge (fn [ctx] {:tool :react :args {..}})  ; optional, the conversation judge in mock runs (see Inner state)
   :offline-stubs {:bridge/name (fn [args ctx] result)}})
```

The engine reads no other keys. The web scene, not the simulation, consumes `:scene`.

### `:scene` (operator presentation only)

The scene at `/` is an **omniscient operator surface**, not an agent view. All callbacks are
optional and pure: no events, world mutation, model/bridge calls, I/O or persistence changes.
Removing `:scene` must not change simulation behaviour or what agents see. Return ordinary
Hiccup/text (escaped by rendering), never raw HTML. Generic scenarios need no hooks.

```clojure
:scene {:toolbar (fn [world] [:div "Household funds: ..."])
        :badges  (fn [world location-id] ["extra room badge"])
        :inspect (fn [world kind id] [:section ...]) ; kind is "agent", "place" or "item"
        :history (fn [events] {commission-index {:reservation room-record}
                              location-index {:location {:size :large}}})}
```

`:history` receives the matching ordered, stamped event prefix. Return a map keyed by **zero-based
index in that vector**, not event id or round. A `:reservation` is a display-only normalized record
with stable `:id` (the future room id), `:name`, `:domain`, `:floor`, `:size` and `:connects-to`.
Size may be `:small`, `:medium`, `:large`, `:grand` or `[positive-width positive-height]`; missing
size uses kind/default sizing. Optional `:due` labels completion time. Reservation connections
are explicitly planned display links, never real routes. A later location event completes the
reservation with that id; `:location` annotations enrich that event's display record only. Do not
change ids or annotate unrelated events. Keep annotations' historical metadata immutable: use
only evidence known **at that boundary**, never current state or future plan values.

The generic projection uses full pre-round-1 topology for initial placement, then freezes local
room geometry at each location/reservation boundary with only then-known routes. Every boundary
is processed even when no browser observed it. Later route changes update styles/connectors only.
Outer region/panel packing may shift as the picture grows. The UI cache is disposable, not a
source of geometry. There are no cancellation/deletion events in this adapter contract.

Claudeworth's `manor.tools.scene-claudeworth/hooks` captures existing `plans-drawn` evidence when
`commissioned` arrives, using the commissioned **room id**, not the plan id. On location creation
it carries that size forward. Existing `works-completed` plus prior plan evidence can size a room
when commissioning evidence is absent; it cannot fabricate a historical ghost. Unknown custom
size names use generic sizing. No size is added to simulation locations/events/state. Current
funds, held plans, purse amounts and adjoining-works badges are world-only overlays. The original
world-only `:ghosts` sketch is deliberately replaced by `:history`, because current works cannot
recover completed reservations on reload.

Generic closed-hours shading uses the existing same-day inclusive-open/exclusive-close hours and
clock; unknown timing makes no closure claim. Busy arcs use existing job `:started`/`:due`, or the
current occupation's recorded start event and `:until`. They show scheduled elapsed time, not a
claim that work effects have succeeded. Missing timing and indefinite hibernation get status
marks, never invented percentages. None of these displays gates actions or changes scheduling.

### `:init`

`(fn [opts] {:state map, :space map, :agents [spec ...], :clock map, :items [item ...], :calendar [entry ...], :world-file map})`
(only `:state` is required), where `opts` are the run
options (engine defaults, merged with your `:defaults`, merged with the caller's options). Custom
option keys pass through, e.g. the village reads `:start-date`.

- `:state`: your scenario's state. It lives at `(:state world)` and is entirely yours.
- `:space`: optional locations graph:
  ```clojure
  {:locations [{:id "camp" :name "Camp" :desc "Tents and a fire."} ...]
   :routes    [["camp" "forest" 1]               ; [from to travel-rounds], two-way
               ["forest" "cave" 1 :one-way]]}     ; one-way edge
  ```
- `:agents`: starting agents, as spawn specs:
  `{:name "Iris" :role :explorer :persona "..." :location "camp" :model "..." :controller :human}`.
  Only `:name` and `:role` are required. The id is derived from the name (`"Iris"` → `"iris"`,
  with a `-2` suffix on collisions). `:location` defaults to the role's `:start-location`.
  A spec may also carry a person's hidden traits, `:sex :manner :temperament :faculties
  :circumstances`, a public `:appearance` (see "People and traits" under World files), starting
  `:aspects` (see People's aspects, under Items; checked, so a bad one stops the run) and a
  `:situation` (see "Positions, posts and situations"). Other keys stay off the agent, but the scenario's `:on-spawn` sees
  them all (Claudeworth's reads `:bedroom`, `:routine` and `:household`).

- `:clock`: optional game time, `{:start "1904-10-15T08:00" :minutes-per-round 30}` (see Time).
- `:items`: optional starting items, used only when the scenario has the `:items` key (see Items).
  Holders and agent owners are agent ids here.
- `:calendar`: optional starting entries, only used with scenario `:calendar` (see Calendar).
- `:world-file`: the world file the scenario loaded, if any (see World files). The engine saves a
  copy as `<run-dir>/world-start.edn`.

The engine always adds a GM seat (id `"gm"`, role `:gm`) before your agents. Who fills it comes
from the `:gm` run option (`:llm`, `:human`, or `:off`).

### `:roles`

```clojure
{:explorer {:brief "Role description and rules, shown in the agent's system prompt."
            :spawnable? true                     ; GM may spawn this role
            :start-location "camp"               ; default location for new agents
            :on-spawn (fn [world id] [events])}  ; starting holdings etc.
 :gm {:brief "What this world is and how lightly to intervene."}}
```

- `:brief` is the main way to explain the rules to models. Say what the role can do, where, and
  what matters. Restate rules the engine enforces so models don't waste turns.
- `:brief` is a string, or a function that builds one from the current world:
  `(fn [world agent-id] string)` for agents, `(fn [world] string)` for the GM. It's called for
  every model call, so it can fill in facts from the world (minutes per turn from the clock, text
  your `:init` put in `:state` from a world file) instead of restating them. See One home per
  fact below; `claudeworth.clj` does this.
- `:on-spawn` receives a world that already contains the agent. Return events, e.g. one that sets
  starting holdings privately: `{:type :my/settled :actor id :data {...} :visible-to #{id}}`.
- A role without `:spawnable? true` can only come from `:init` or an operator `spawn!`.
- A role `:roles` doesn't list gets the scenario's `:agent-brief`, `(fn [world agent-id] string)`.
  With positions (see "Positions, posts and situations"), that's how every non-GM role gets its
  brief: Claudeworth's `:roles` lists only `:gm`, and `agent-brief` reads the agent's position
  from the world, so a new position needs no code.
- The scenario's own `:on-spawn`, `(fn [world id spec] [events])`, runs after every spawn (from
  `:init`, an operator or the GM), after the role's, and gets the whole spawn spec. Claudeworth's
  is `manor.mechanics.positions/starting-events`: the agent's post, bedroom and routine.

### `:actions`

Each action is a map. Each role's actions become that role's LLM tools.

```clojure
{:name    :dig                       ; tool name: plain keyword, [a-z0-9-], unique
 :doc     "Dig for the treasure where you are."
 :args    [:map [:depth [:int {:min 1 :max 3}]]]   ; malli schema, default [:map]
 :roles   #{:explorer}               ; or :all (every non-GM role, the default)
 :needs   :shop-clerk                ; optional: a capability the agent's position must give
 :kind    :final                     ; :final (default) | :free | :query | :act (| :converse, built in)
 :kind-for (fn [args] kind)          ; optional: the kind of a call with these args, for an action free
                                     ; with some and a query with others (visits' invite); give it
                                     ; both kinds' keys (:effect, :query-fn ...) and a :hint
 :available? (fn [world actor] bool) ; optional: leave the tool out of this model step
 :valid?  (fn [world actor args] nil-or-reason-string)
 :effect  (fn [world actor args] [events])
 :per-turn  1                        ; optional: at most this many accepted uses per turn, or
                                     ; (fn [world actor]) => n or nil (no limit)
 :limit-key :speech                  ; optional: actions with the same key share :per-turn
 :hint    "..."                      ; optional: replaces the kind's hint ("Ends your turn.") in the tool description
 :reply   (fn [world actor args] data)}   ; :free only, optional: the tool result instead of "ok"
```

**Roles and capabilities:** an agent gets an action as a tool when its `:roles` admits their role
and, if it has `:needs`, their position gives that capability (`:capabilities` in
`[:state :positions]`, see "Positions, posts and situations"). Use `:needs` for what a holder of
several positions might do: the shop's `quote-goods` is `:roles :all :needs :shop-clerk`, so a
butcher and a shopkeeper can both quote and an earl can't. Code that treats someone as something
should ask the same way, `(manor.mechanics.positions/capable? world id :architect)`, never compare roles.
`:valid?` should still check it, for a call that reaches the action another way.

**Availability within a turn:** normal agent and GM turns start with the snapshot's tools,
then refresh availability and capabilities from the live world between model steps. A free
`walk`, a query result or a completed `converse` can therefore add or remove tools for the
next call. Calls in one response still use the tools that response was offered, with current
`:valid?` checks before effects. Refreshing never resets query/free limits, the step budget or
turn completion. Reminders name the refreshed turn-ending tools. Speaking turns keep their
separate, restricted line tools; Claudeworth's include `engage`, `accept-place` and `decline-place`
(see Hiring), so a bargain can be struck on the lines where it's agreed.
Tool ordering remains stable, with conditional tools last so the unconditional prefix stays
cacheable; a change in availability still changes the conditional tail.

**Kinds:**

| Kind | Ends turn? | When it applies | Notes |
|---|---|---|---|
| `:final` | yes | immediately (sequential turns) or after the group (simultaneous) | the normal action |
| `:free` | no | immediately | notes, declining, small side moves |
| `:query` | no | immediately; bridge or internal adjudication result comes back within the turn | either `:bridge` or `:adjudicate`; limited by `:max-queries` per turn; no approval |
| `:act` | yes | creates a pending request; runs after operator approval at the next round start | needs `:bridge` |
| `:converse` | no | immediately; the engine runs a conversation within the turn | only the built-in `converse` (see Conversations) |

**Per-turn limits:** `:per-turn n` caps how often an action can be used in one turn; it suits
free and query actions (a turn has only one final action anyway). Only accepted uses count: a call
rejected by `:valid?` or with bad arguments leaves the limit untouched. Actions with the same
`:limit-key` (by default each action's own `:name`) share one count. Once it's spent, a further
call gets `rejected: you can use say or whisper only once per turn; take your turn-ending action
now, and use them again next turn`. The tool description ends with "Once per turn." or "At most n
times per turn."; say in the `:doc` which other actions share the limit. The count is turn state,
not world state, so it leaves no events and replay is unaffected. A `:per-turn` function of the
world and actor gives a limit that depends on who is acting now (Claudeworth's `walk` uses
`physical/walks-per-turn`: 3 with movement impeded, else none); it isn't told in the tool
description, so say it in the rules.

Every model call in a turn counts towards `:max-steps` (default 10): free and query actions, and
retries after an error or rejection. A turn with no turn-ending action by then is forfeited. To
avoid that, the engine reminds an agent with no turn-ending action yet, after its second call and
again before its last, naming the turn-ending actions it has.

Query and act actions also take:
```clojure
 :bridge      :open-meteo/daily                          ; key in :bridges
 :bridge-args (fn [world actor args] bridge-args)        ; default: the action args
 :on-result   (fn [world actor args result] [events])    ; optional
```
For query actions the bridge result is returned to the model as JSON. `:effect` is not used.
For act actions, set `:actor` explicitly on `:on-result` events. `:valid?` runs when the request is
made, not when it is executed.

#### Internal adjudication queries

A query can use `:adjudicate :your/task` **instead of** `:bridge`. Register its adapter in the
scenario:

```clojure
:adjudicators
{:your/task
 {:request (fn [world actor args] {:messages [...] :tools [...] :ctx {...}})
  :check   (fn [response] {:verdict checked-data :replies [...]}) ; or {:error reason :replies [...]}
  :mock    (fn [ctx] {:tool :answer :args {...}})}}
```

These are narrow engine-run LLM calls, not the GM and not real-world bridges. Build only the
context needed for that judgement; never dump the world or unrelated private state. Treat
agent-authored strings as untrusted data. `:ctx` is local mock context, not a model message.
The engine adds `:role :adjudicator`, `:task` and `:step` (attempt 0 or 1) to that context.
A request may return its own `:check` closure (overriding the adapter's checker) to check
against its narrow scene. It may instead return `{:result checked-data}` for a deterministic
answer: no model call is made, and that result goes straight to `:on-result`. With `:items`
enabled the engine registers `item-find/adjudicators` automatically; scenario adapters override
entries with the same key.

The engine uses `:judge-model` (falling back to `:model`), with the run's reasoning, token and
tool-choice options. It asks once more after an invalid answer, using the checker's replies.
`manor.mechanics.adjudication/answer` implements first-tool-call selection and replies; domain code
supplies the tool name and raw-argument checker. The work judge uses the same runner/parser,
but keeps its own prompts and domain checks (round-start work and immediate `act-on`), as does the
conversation judge (see Inner state).

Only a checked `:verdict` (or the request's direct result) reaches the action's `:on-result`
and the caller's JSON tool reply.
The callback returns events with `:actor` filled in automatically. Record everything that must
persist as events here; replay never calls the model. A valid negative answer is a verdict too.
Repeated invalid output or provider failure runs no callback: the caller gets a neutral error,
and an operator-only `:adjudication/failed` event records the task/caller/reason. There is no
arbitrary model-to-world patch. An adapter may set `:failure-reply` to neutral in-world text
instead of the default enquiry error. An invocation returning this neutral reply counts as an
accepted use for `:per-turn`.

A query action may supply `:reply (fn [world actor args result] data)`, called after its
`:on-result` events. `world` is the resulting world; `result` is the checked/direct result with
`:events` added: the stamped `:on-result` events actually emitted (not usage or bridge events).
Return a string as is, or other data as JSON. This lets a tool report an allocated item id
without exposing internal odds. With no `:reply`, queries still return their result as JSON.

Every returned model response and its usage are logged under `adjudicator/<namespace>/<task-name>` and
count toward the normal run cost cap. One action invocation uses one query slot even if the
adjudicator needs a correction. In mock runs the registered `:mock` handles these calls,
separately from character `:mock-policy`; supply a responder for every task used offline.

Claudeworth's `manor.scenarios.claudeworth.shop` is an example. Every appraisal receives the actual
location's `:store-profile` (required, non-blank, at most 2000 characters), name/description,
setting/date and goods/amount. It refuses unsuitable stock before pricing. The checked
response creates a buyer-bound quote for each line of a request (`quote-goods {for goods}` takes
up to 10 lines `{item amount deliver?}` in one appraisal and one query; its `:reply` tells the clerk each quote id or
refusal). `amount` is free text, at most 40 characters, the amount as the customer put it ("a stone",
"2 lb", "3"); the appraiser sees `{item amount}` and gives back, for an available line, `qty` (a
whole number, 1 to 20) and `unit` that express it, a stone of sugar as 14 × `1 lb` or 1 × `1 stone`,
counted goods as one named piece (`1 knife`), with `unit-price-pence` for one unit. A missing or
out-of-range `qty` gets the checked-answer correction. The quote's price is `qty` × the unit price,
and the quote, purchase, order and `:amount` aspect all read as `qty` × `unit`, so the clerk can
compare what was quoted with what was asked. At an open counter, Claudeworth offers `quote-goods` in the clerk's speaking turn:
a customer can `converse`, the clerk checks the list then replies with prices, and the customer
gets the quote ids and can `buy` on their next line, before speaking (a speaking-turn tool for a
customer with unspent quotes at that shop), or later in that same turn. Anyone's speaking
turn also offers `recommend`, to someone in that conversation, so whoever is asked for a candidate
can answer with a real one on their line instead of making up a name.
`buy {quote-ids hand-over}` is free (it doesn't end the turn) and separately debits money for up to 10 quotes at once, checked as a
whole, and creates for each counter quote one portable item owned by the household, a parcel of the whole quantity, with two aspects: `:amount`, the amount
bought ("20 × 1 lb"), where work can later say what's left, and `:state`, the appraiser's
`condition` ("fresh, dry"). It is `:consumable?` when the appraiser says the goods are used up in
use (food, fuel, soap; not tools or crockery). The appraiser's `unit` (at most 30 characters) is
short so the amount fits an aspect; its `condition` is at most 80. `hand-over` is required:
"take" puts the parcels in the buyer's hands ("Hale buys … at the General store, and takes the
goods away."); "leave" puts them at the shop, paid for, with a `:waiting` marker for the buyer kept
by the clerk (see Items), "… and leaves the goods there to be collected or sent up." Leaving works
at every shop: someone collects the goods, or the clerk hands them over with `give`, or at a
delivering shop sends them up (below). Only the buyer's view lists them, under `waiting-at-shops
[{item amount at}]` (left out when empty); at the shop `location.here.items` marks each
`waiting-for` the buyer, and `here.service` says such goods are paid for and kept until collected
(or sent up). `buy`'s reply says where the goods are now. No fixed inventory, bulk counters or bargaining; delivery is below. Amounts are integer
old pence; world-file starting funds and architect bridge prices remain authored in pounds.
All Claudeworth characters can buy and commission from the shared household account and
see its balance. There is no spender-role allowlist: authority to spend is a social decision
for the agents, not an engine permission. Buyer-bound quotes, affordability, plan access,
co-location and opening-hours checks still apply. The GM is not a character and does not
receive these actions.
Old Claudeworth runs are intentionally unsupported after this contract change.

Claudeworth also reuses this counter service for its draper, publican and postmistress (and the
medium world for its baker, poulterer and fishmonger, whose fish stall is a `:stall`): each
position has `:shop-clerk`, and each location uses `:services #{:general-store}` (the post
office also has `:post-office`) with its own `:store-profile` and `:hours`. Despite the service
name, the profile determines the trade. It covers physical purchases only: cloth, refreshments,
stamps and stationery, not repairs, haircuts, postal orders, telegrams or room hire. Cottage
trades and the forge use their own service markers, so with `:find-everywhere? false`
Claudeworth's `find-allowed?` refuses `find` there too. Their repairs, sweeping, hairdressing and washing use ordinary
`spend-time` on actual items, places or people; no paid-service or mail-transport contract is
implied. Each resident has a bedroom and routine just like the existing shopkeepers.

**Deliveries** (`manor.scenarios.claudeworth.deliveries`, design in `docs/deliveries-design.md`). A shop
sends goods out when its location has a `:delivery-profile`: at most 400 characters, describing
its delivery scope in general terms, never a list of goods (so it doesn't suggest purchases).
A `quote-goods` line may set `deliver true`; at a shop without a profile such a call is refused
before any model call. A delivery quote is for goods too large or too many to carry away (smaller goods bought at the
counter can be left and sent up, below). The appraiser sees the delivery profile and which lines are for delivery,
judges those against it (larger quantities and goods too big to carry are fine when it covers
them) and gives each available one `lead-hours` (1–120); a delivery line without it gets the
usual correction. The quote records `:deliver {:lead-hours :to}`, where `:to` is the delivery
place, hiring's `:meeting-place`. `buy` takes counter and delivery quotes together: a delivery
quote, whatever `hand-over` says, emits `:deliveries/ordered`, which pays at once and keeps the order under
`[:state :orders]`, due at `deliveries/due-round` (at least next round, moved to the shop's next
opening time when it falls outside its `:hours`). The buyer and the clerk see it under `orders`
in their view. The order's id is the engine's alone: no tool takes it, so neither the view nor
the `:deliveries/ordered` narration shows it (characters shown "order-312" took it up as a
reference number in their talk).

**`send-up {items within-hours}`** is the clerk's free tool, at an open shop with a delivery
profile, for goods bought and left there when the customer wants them sent up rather than
collected. `items` (up to 10, each once) must be shown as waiting at this shop, with nothing in
them; `within-hours` is 1 to `deliveries/send-up-max-hours` (3, kept low for now), fitted to
opening hours by `due-round`. There's no appraisal and no payment: the goods are paid for. Its
`:deliveries/sent-up` event, seen by everyone at the shop, removes the items
(`items/remove-items`, fate "sent up by the shop's courier") and adds an order for each, id
`order-<item-id>`, for the person it waited for, carrying the item's name, description, aspects,
owner and consumability: "Briggs sends up tin of silver polish (one) for Hale from the General
store, to be delivered at the Carriage sweep by 13:30." The clerk's reply says the
courier has the goods, when they'll arrive, and that there is nothing to pack or carry. At a
shop without a profile it's refused ("… doesn't send goods out; hand them over when they're
collected"). Claudeworth's `speaking-actions` offers `send-up` on a line to the clerk of an open
delivering shop with goods waiting there, as it offers `buy` to a customer with unspent quotes
at the shop, so "he'll have it up within the hour" can be done as it's said.

The `deliveries/arrivals` system, at round start, adds each due order's parcel
at the delivery place (owner household, the quote's `:amount` and `:state` aspects, or for goods
sent up the item's own owner and aspects under a new id, as the old one is never given out again; `:consumable?`, and
`:order-id`) and emits `:deliveries/arrived`, heard by everyone within walking distance of that
place. Install it with `deliveries/install`, after `hiring/install` (it uses the meeting place,
the people bridges and adjudicators, and the agent cap). Claudeworth's general store,
butcher and draper deliver; the general store uses `:courier :carrier`, while the butcher and
draper use the default errand boy.

Each delivering shop has one **courier**, under `[:state :couriers shop-id]` (`{:person
:position :agent-id :arrived}`; not in hiring's pool), using the location's `:courier` position
(default `:errand-boy`; the scenario checks it's in the positions table). The system's
`:compose` names every delivering shop's courier in round 1 with `hiring/person-record` (the
people server only, no model call), so the shop can say who sends orders up: each entry in
`orders` has `sent-up-by`, "Stephen Dobbs, the General store's carrier" (the shop's own view
doesn't name him, so he isn't taken to be at hand). That is the courier's only presence in prompts
until they first deliver. The first time a shop's order is due, compose writes the person
(`hiring/write-person`: appearance and polish) and the courier is spawned at the delivery place
with `hiring/spawn-spec`, its spawn `:system` because it holds a private persona, a post naming
the order's clerk as employer ("carries for the General store (general-store)"), and a
one-entry routine, waiting by the delivery there. With the scenario's `:hibernate`
(Claudeworth's routine rule) that keeps them out of play, with no model calls, whenever nobody
else is at the delivery place: they are put out of play as they arrive if nobody's there, and
wake when someone comes. Later deliveries bring the same agent back (`:people/returned`), or keep one
who is still here; each arrival resets `:arrived` and rewrites their situation with what they
brought. A courier goes home (`:people/departed`) once `courier-leave-after` (2) rounds pass
since they arrived or last conversed (`hiring/idle-rounds`, hiring's own rule), except while
they're a guest here (`visits/visiting?`), who decides for himself. With no room
under the agent cap, or a retired courier, the goods come without one; a person made but not
yet spawned is kept for the next delivery.

#### Composed queries

A query needing several dependent bridge/adjudicator calls may supply
`:query-fn (fn [world actor args services] result)` instead of `:bridge` or `:adjudicate`.
`services` contains three engine-owned callbacks: `(bridge bridge-id args)` returns bridge data;
`(adjudicate task-id args)` builds the registered request and returns only a checked verdict;
`(recent)` returns the caller's own last ten narrated lines (`prompt/recent-lines`, their own
view), for a judge that reads what they mean (the invitation judge).
They throw on failure, check the run budget before each component, and record all calls and
usage. Intermediate bridge events are **operator-only**, not visible to the calling character.
Composed adjudicators use the run's `:model` (candidate generation must not inherit a work
judge override). One composed action uses one query slot; each model may have one correction.

The function must not mutate world state or do I/O directly. Its result goes to the usual
`:on-result`, and its `:reply` should project only what the caller may know. An exception
emits an operator-only `:adjudication/failed` and returns a neutral error without running
`:on-result`. `manor.mechanics.hiring/recommend!` is the example: generate a record, write a public
appearance from visible fields, polish privately, and obtain a private sampled reputation.

#### Replies and arguments

An accepted free action replies `"ok"`, or, if it has a `:reply`, whatever that returns: a string
as is, anything else as JSON. `:reply` is called with the world after the action's effect (if it
has one), so it can report what changed or simply show something (`look`). A rejected action
still replies `rejected: <reason>`. The reply goes only to the caller and is kept in `llm.edn`;
it isn't an event, so anything that must be remembered or replayed belongs in `:effect`.

**Arguments:**
- Use plain malli types that convert to JSON Schema: `:string`, `:int`, `:double`, `:boolean`,
  `[:enum "a" "b"]`, `[:vector :string]`, nested `[:map ...]`, `{:optional true}` entries, and
  `{:min :max}` properties. Avoid arbitrary predicate functions; they don't convert to JSON Schema.
- Prefer string enums and string ids. JSON has no keywords.
- Models often send `snake_case` or `camelCase` keys. The decoder normalizes them to kebab-case and
  silently drops keys the schema doesn't declare. On a validation failure, the model is told the
  error, the expected parameter names, and any ignored keys.
- A string or collection outside its `:min`/`:max` gets a reason that says how to fix it: `text is
  1043 characters; the limit is 1000 - shorten it by at least 43`. To leave slack, set
  `:advised-max` below `:max`, state the advised limit in the `:doc`, and oversize values are
  advised down to it: `say` is `[:string {:min 1 :max 1500 :advised-max 1000}]`. A string with
  an `:advised-max` has no `maxLength` in the tool schema, so the `:doc`'s number is the only one
  the model sees; `:max` is still enforced.
- Keep each `:doc` short and concrete. Say what `give-*` and `want-*` style fields mean.

**Validation:**
- `:valid?` returns `nil` (allowed) or a human-readable reason. The reason is shown to the model,
  which can retry within its step budget, so make it actionable:
  `"you must be at Fields (fields) to plant"`, not `"invalid"`.
- Check everything the effect relies on: holdings, location, existence of the target, and that the
  target is still active.
- Gate by location in `:valid?` (discoverable: the model learns where to go). Use `:available?`
  only when a tool truly makes no sense (e.g. `move` while travelling).

**Effects:**
- Pure: read the world, return a vector of events. Never mutate anything or do I/O.
- `:actor` is filled in automatically if you omit it.
- Randomness: `(manor.rng/roll world [(:round world) actor :purpose] n)` gives an int in `[0, n)`
  determined by the run seed and the key. **Record the outcome in the event data** (e.g.
  `{:yield 15}`). Reducers must never roll.
- Effects may return engine events too: `(manor.mechanics.builtins/spawn-events scenario world spec)`,
  `{:type :agent/retired :data {:id .. :reason ..} :visible-to :all}`,
  `{:type :run/pause-requested :data {:reason ".."} :visible-to :gm}`.
- Ids for new things should be deterministic, e.g. `(str "offer-" (inc (:seq world)))`.

**Built-in actions** (merged before yours; define one with the same `:name` to override it):

| Action | Roles | Kind | Behaviour |
|---|---|---|---|
| `pass` | all | final | does nothing |
| `carry-on` | all | final | only offered to an interrupted agent: keep the occupation and ignore the interruption |
| `note` | all | free | private note, shown to the agent every round (last 15); told 400 characters, accepts 500 |
| `say` | all | free | heard by everyone in the same place (everyone, without locations); told 1000 characters, accepts 1500; once per turn, shared with `whisper` (`:limit-key :speech`) |
| `whisper` | all | free | private to one agent; requires being together if both have locations; same limits, and shares `say`'s once per turn |
| `converse` | all | converse (free) | draws up to 3 people here into a conversation within the turn (see Conversations); once per turn and per round; not offered in a simultaneous group |
| `pray` | all | free | a private, diegetic way to reach the GM: its `:gm/requested` event fills the GM's inbox and triggers the GM this round |
| `move` | all | final | follow a route (see Locations); only offered when the agent has a location |
| `look`, `take`, `put-down`, `give` | all | free | only when the scenario has `:items` (see Items) |
| `spend-time`, `work-on` (alias) | all | final | only when the scenario has `:items` and the world a clock (see Items) |
| `spawn-agent` | gm | final | spawn a `:spawnable?` role, optional location; respects `:limits` (default 50 active agents; retired ones don't count) |
| `retire-agent` | gm | final | remove an agent |
| `announce` | gm | final | message to everyone or a list of ids |
| `ask-human` | gm | final | pause after this round with a question for the operator |
| `add-location`, `open-route`, `close-route` | gm | final | change the graph (only when it exists) |
| `done` | gm | final | end the GM's intervention |

Override a built-in by defining an action with the same `:name`, or leave it out with
`:exclude-actions #{:move :whisper}`. Exclusion applies to scenario actions too, so a world
reusing a larger scenario can omit tools that make no sense there. Don't exclude `done`: the GM needs it.

### One home per fact

Every fact about the world (places, distances, opening hours, bedrooms, names, the setting) should
live in exactly one place, and everything agents read should be derived from it. With a world
file, that place is the world file: if the abbey moved farther away, editing its route should be
enough. So:

- Put the setting and each role's flavour in the world file too, read them into `:state` at
  `:init`, and build the brief with a function (see `:roles`). Keep the rules of play in code,
  with their numbers taken from the world and the actions.
- Show places, journeys and opening hours in `:observe`, computed from the map
  (`space/distance`, `space/area`, the locations' own keys), rather than in briefs.
- Build `:valid?` reasons from the current world: look names up (`(:name (space/location world
  id))`), list ids that would work, and never write a place name into a reason.

Mock policies and their tests are exempt: they're scripts for one particular world.

### Tool descriptions are mechanics

A tool's `:doc` should be true for every role that has it and on any map: say how it works, not
where things are. No place names, area names, role names or distances. Point at the view
instead: "Walk to any place listed under `walkable` in your view", "`journeys` in your view shows
how long each takes". The engine appends the kind hint ("Ends your turn." or "Free: does not end
your turn.").

### Events and reducers

Event shape (returned by effects, ticks and hooks; the engine stamps `:id` and `:round`):

```clojure
{:type :treasure/dug                 ; namespace it with your scenario name
 :actor "iris"                       ; optional (filled in for action effects)
 :data {:at "cave" :found? true}     ; plain EDN data only: no functions or objects
 :visible-to #{"iris" "omar"}}       ; :all | #{agent-ids} | :gm | :system
```

- **Visibility:**
  - `:all`: everyone.
  - A set of ids: those agents, plus the GM and the operator.
  - `:gm`: GM and operator only.
  - `:system`: operator only, never in any prompt.

  **The default if you omit it is `:gm`**, which hides the event from agents. Always set it
  deliberately. For things that happen at a place use `(manor.mechanics.space/here world actor)`. For a
  private outcome use `#{actor}`.
- **Reducers:** one per event type that changes state.
  ```clojure
  (defmethod manor.world/apply-event :treasure/dug [world {:keys [actor data]}]
    (assoc-in world [:state :found-by] actor))
  ```
  Reducers must be pure and depend only on the world and the event: no clock, no rng, no I/O.
  Event types without a reducer are informational (e.g. speech).
- Engine-owned world keys: `:round :seq :agents :space :gm :pending-bridges :pause-requests :usage`,
  `:clock` when the scenario has one, and `:items` (and `:item-jobs`) once a world has items.
  Scenario reducers should only touch `:state`, unless deliberately using engine semantics via
  engine events.
- **Perception:** an event type may say which sense it's perceived by, with
  `(defmethod manor.world/perceived-by :treasure/dug [_] :sight)` (`:sight`, `:hearing`, or nil,
  the default). Before stamping, `run/emit!` drops from a set audience everyone unconscious, and
  those who lack the sense (see Physical status); the actor is always kept, and
  `manor.world/done-to` names others never dropped for lack of sight (`:item/given`'s recipient,
  the `:from-person` of an `:item/taken`, and `:person/acted-on`'s `:on` people).
  `:all`, `:gm` and `:system` are never trimmed, and the stored audience is the trimmed one, so
  replay is unaffected. Tagged so far: speech (`:agent/said`, `:agent/whispered`, Claudeworth's
  `:claudeworth/called`) and muffled noises (`:person/muffled`) are heard; moves, departures, arrivals and placings, occupations (work
  starts, falling asleep), items taken, put down, put in, given, found or not found, and status
  changes and immediate acts are seen. Untagged events reach everyone in the audience who is conscious.
- Multimethods are global. Namespace your event types (`:treasure/...`) so scenarios can't collide.

### Narration

Every event type an agent can see should have a description. That text *is* what agents read in
"Recent events you witnessed".

```clojure
(defmethod manor.narrate/describe :treasure/dug [{:keys [actor data]} names]
  (str (manor.narrate/who names actor) " digs at " (manor.narrate/place names (:at data)) "."))
```

`names` resolves agent ids (`narrate/who`), location ids (`narrate/place`) and rounds
(`(narrate/at names round)`: a time like "Sun 07:00" with a clock, else "round 7"). Without a method
you get a generic `"Iris: dug {:at ...}"`. Write descriptions so they read correctly for everyone
who can see the event.

### `:observe`

`(fn [world agent-id] map)` returns the agent's private view, sent as JSON every turn. The engine
adds:
- `:round`, or with a clock `:time` ("Saturday 15 October 1904, 09:30")
- `:you {:id :name :role}`, merged with whatever you put under `:you`
- `:location {:here .. :exits [{:to :name :travel-rounds}] :people-here [..]}` when the world
  has locations, or `{:travelling {..}}` while in transit. With a clock, exits give
  `:travel-minutes` instead. People who are occupied have `:doing` ("asleep").
  It also has `:last-seen [{:id :name :place :at :went}]` once the agent has parted from
  someone: for each active person not with them now, where and when they last saw them (`:round`
  instead of `:at` without a clock), most recent first, and `:went` (a place name) if they saw
  them go. See Space.

Rules: include only what this agent may know: its own holdings, public facts, and things at its
location. This is the fog of war. Never return another agent's private data. Keep it compact; it
is sent on every model call.

### `:reference` and how a request is laid out

The provider caches prompts: a request that starts the same way as an earlier one pays about 2% of
the input price for the shared start. So requests are laid out with what rarely changes first:

1. the system message: one preamble for turns and conversations alike, the role's `:brief`, who
   they are, their situation, and `## Reference`, the agent's `:reference` as JSON;
2. the tools, in declaration order, with actions that have an `:available?` check last;
3. the user message: the recent events (oldest first), then `# Now:` with the time, the
   `:observe` view, the notes, and last what is asked now (`## Your turn`, or `## The
   conversation` with how to answer).

The event window shows at least `:event-window` events and fewer than twice that: its start
jumps forward only every `:event-window` events, so between jumps it just grows at the end.

`:reference` is `(fn [world agent-id] map)`: what the agent may know that rarely changes, such as
the places by area or a catalogue of positions. Anything in the system message that changes
makes the provider re-read everything after it (tools, events and view), so keep what changes
often in `:observe`, and don't put it in a `:brief` function either. Claudeworth's `reference`
has its places and hiring's meeting place and positions. The same privacy rule holds as for
`:observe`: only what this agent may know.

`:gm-view` defaults to `{:state .. :agents [..] :space .. :items [..] :item-jobs [..]}` (the GM is
omniscient; `:space` and `:items` only when the world has them, `:item-jobs` while work is going on).
Override it to
trim tokens. The default GM view leaves out `:setting` and `:roles :gm` in `:state` (and `:roles`
if nothing else is in it), which are brief material: put them in the GM's `:brief`. Other roles'
entries in `:roles` stay. Of `:positions` it keeps only those an active agent holds, and its
agents carry their `:situation`.

### `:systems`

World processes that run at the start of every round, in order, before any agent acts. Each sees
the world as changed by the previous systems.

```clojure
{:name :weather
 :bridge :open-meteo/daily                  ; optional
 :bridge-args (fn [world] args)             ; required if :bridge
 :tick (fn [world bridge-response] [events])}
```

`bridge-response` is `{:result r}`, `{:error msg}`, or `nil` (no bridge). Handle the error case
with a sensible fallback (the village uses neutral weather). Bridge calls made by systems are
recorded as `:bridge/called` events, visible to the GM.

A system needing several dependent calls out, or model calls, gives `:compose` instead of
`:bridge`:

```clojure
{:name :deliveries/arrivals
 :compose (fn [world {:keys [bridge adjudicate]}] result) ; may call out; changes nothing
 :tick (fn [world compose-response] [events])}           ; pure, as always
```

`services` are those of a composed query ("Composed queries"): `bridge` and `adjudicate`,
with the run's budget checked before each call and every call recorded, as agent `"system"`
(adjudicators use the run's `:model`). `compose-response` is `{:result r}`, or `{:error msg}`
if compose threw, which also records an operator-only `:adjudication/failed`. Compose runs
every round, so it should return nil without calling anything when there's nothing to do.
Everything it produced must reach the world through tick's events: replay never composes.

Travellers still exist while in transit; systems that iterate agents (upkeep, wages) include them
unless you filter on `(:travel agent)`.

### `:bridges`

The only way to touch the real world.

```clojure
{:open-meteo/daily {:doc "Historical daily weather"
                    :call (fn [args ctx] result)   ; may throw; may block (timeout applies)
                    :timeout-ms 20000              ; default 15000
                    :max-chars 4000}}              ; results over this are truncated to a string
```

- `ctx` is `{:run-id :run-dir :opts :mcp}`. Write files only under `run-dir`.
- Results must be plain data (they are stored in the event log and shown to models as JSON).
  Exceptions and timeouts become `{:error msg}`.
- Use `manor.io.bridge/get-json` (JDK HTTP client) for simple GET APIs. Don't use http-kit's client
  (it broke on gzip responses).
- Provide `:offline-stubs` with the same signature so `{:offline? true}` runs need no network.
- Treat results as untrusted input: keep them small and structured.

#### MCP bridges

A bridge can be a tool on an MCP server instead of a function. The bridge args become the tool's
arguments; the result is the tool's text content (or its structured content, if any). A tool
error becomes `{:error msg}`.

```clojure
:bridges {:pi/send-message {:doc "Message the caller's own pi session"
                            :mcp {:server :pi :tool "send_message"}
                            :timeout-ms 300000
                            :max-chars 3000}}
:mcp-servers {:pi {:command ["node" "mcp-servers/pi/server.mjs"]   ; relative to the repo
                   :env {"PI_MCP_SYSTEM_PROMPT" "..."}}}
```

- Only the stdio transport is supported. Each server is one process per run, started on the
  first call and closed by `stop-run`. Its stderr goes to `<run-dir>/mcp-<name>.log`, and it gets
  `MANOR_RUN_ID` and `MANOR_RUN_DIR` in its environment.
- Server settings merge per server: the scenario's `:mcp-servers`, then `config/mcp.edn`
  (machine-specific, gitignored; see `config/mcp.example.edn`), then the run option
  `:mcp-servers`. `:env` maps are merged.
- **Safety belongs in the server.** The engine only decides *which* tool an action may call (only
  tools named in `:bridges` are reachable) and with what arguments.
- **Choose identity-bearing arguments in `:bridge-args`, never from the model.** For per-agent
  state such as a pi session, pass the actor id: `(fn [_ actor args] {:session actor ...})`.
  Otherwise one agent could address another's session.
- Query results reach only the caller's current turn. The `:bridge/called` event the agent sees
  later doesn't contain the result. If the agent should remember it, emit a private event from
  `:on-result` with a `describe` that includes it (the quiz does this).
- If the server reports `_meta {"manor/cost" usd}` in a tool result, the engine adds it to the
  caller's usage, so it counts towards `:max-cost`.
- Replays and loads read the recorded results; they never start servers. Servers with their own
  memory (pi sessions) keep it across loads; a branch gets a new run directory and therefore
  fresh pi sessions.

#### The pi MCP server

`mcp-servers/pi/server.mjs` (Node, no dependencies) has one tool, `send_message {session,
message}`. Each session is a long-lived `pi --mode rpc` process with its own workspace
(`<run-dir>/pi/<session>/`, also its working directory) and a persisted conversation. Its
settings are environment variables (documented at the top of the file): `PI_MCP_COMMAND` (for
example a sandbox wrapper), `PI_MCP_MODEL` (default `deepseek/deepseek-v4.1-flash` via
OpenRouter), `PI_MCP_TOOLS` (pi tools allowed; default none), `PI_MCP_ISOLATED` (default on: no
extensions, skills, context files or MCP inside pi), `PI_MCP_SYSTEM_PROMPT`, timeouts and limits.

### Locations (`manor.mechanics.space`)

When `:init` returns `:space`, the engine provides:
- agent positions (`:location` on the agent; `nil` while travelling)
- the `move` action. A route with `:travel 1` is immediate (the move is the agent's action for the
  round). `:travel n` > 1 puts the agent in transit: it skips its turns and arrives at the start
  of round current+n. A route with `:travel 0` joins places next door to each other (the rooms of
  a house); `move` along it is immediate too. Places joined by `:travel 0` routes form an
  *area*, and a scenario can offer a free action to walk anywhere in it (see `space/area`).
- location-scoped `say`/`whisper`, departure and arrival visibility, and the `:location` view section
- GM graph tools, operator `place!`, and spawning at a location
- **last seen:** when two people part (one of them moves, sets off on a journey or is placed
  elsewhere), the movement reducers record it under `(:seen world)`: `{observer {other {:place
  :round :went}}}`, `:went` only for the one who stayed. Arriving records nothing, since the
  people there are under `:people-here`. `(space/last-seen world id)` is what the view shows.
  Leaving for elsewhere (`:people/departed`, in `manor.world`) records the parting the same way
  for everyone in the place they leave, so a guest who never left the drawing room before
  `take-leave` is still remembered there. `(space/away-acquaintances world id)` reads the same
  data for the people who are away now (Visits shows it). Every list is shown in full; with many
  agents it may need a cap later.

Helpers for your actions and views:

| Function | Returns |
|---|---|
| `(space/location-of world id)` | location id or nil |
| `(space/present world loc)` | set of active agent ids there |
| `(space/here world actor)` | visibility set for "where actor is" (or `:all` without locations) |
| `(space/together? world a b)` | same location? |
| `(space/in-transit? world id)` | travelling? |
| `(space/exits world loc)` | `[{:to id :travel n} ...]` over open routes |
| `(space/route world from to)` | `{:travel n :open? bool}` or nil |
| `(space/path world from to)` | quickest route by travel time, as locations after `from` |
| `(space/distance world from to)` | total travel (rounds) of that route, or nil |
| `(space/area world loc)` | every location reachable from `loc` over `:travel 0` routes, itself included |
| `(space/location world loc)` | `{:id :name :desc}` |
| `(space/last-seen world id)` | where `id` last saw each active person not with them, most recent first |
| `(space/away-acquaintances world id)` | the same, for each person `id` has met who is away now |

**Finding people** (`manor.mechanics.find-person`, used by Claudeworth): add
`find-person/action` to your actions and `find-person/adjudicators` to your `:adjudicators`.
`find-person {who}` is a `:query` (counts against `:max-queries`, once per turn), offered
wherever the agent has a place. `who` (1-80 characters) describes the person in the seeker's own
words. The `:people/find-person` judge sees the seeker's name, position, nature, situation,
place, last 15 notes and last ten narrated lines, and `people`: everyone in the seeker's
`space/area` but them, the dead included, with only what anyone about could see (id, name,
position, form of address, appearance, place and what they're doing). It answers `person` (a
listed id) with a likelihood (certain, likely, even, unlikely, impossible), or `nobody`; code
checks only that shape and that the id is listed. The engine rolls `[:find-person seq]` against
the odds (as `find` does) and records `:people/sought`, visible only to the seeker, with the
outcome, the roll and, when found, the place `:at`. It has no reducer and moves no one. Found,
the reply names the person, their place id and what they're doing ("walk to gallery"); missed,
and no one meant, both reply "You look about for <who> but don't find them.", so a miss gives
nothing away. The mock finds someone listed whose id, name or surname `who` contains.

**Following** (`manor.mechanics.following`, used by Claudeworth): add `following/actions` to your
actions and, so people can follow from a conversation, to your `:speaking-actions`; wrap the
events of each action that moves its actor with `(following/along world actor travel events)`
(`travel` the journey's rounds, 0 for a walk); and call `(following/add-to-view view world id)`
from `:observe`. `follow {who}` (free, once a turn) needs `who` active and in the same place, not
already followed by the actor, not following the actor directly or through others, and the actor
able to move; it records `:agent/following {:leader id}` (seen by everyone here; the reducer sets
`:following` on the agent), after a `:agent/stopped-following` if they followed someone else.
`follow-stop` (free, offered only while following) records `:agent/stopped-following {:leader
:reason :chose}`. `along` puts `:agent/stopped-following` (`:own-way`) before the move when the
mover was following someone, and after their `:agent/moved` or `:agent/departed` adds, for each
follower and their followers in turn, the same event with the follower as actor and `:with` the
person they went with (narrated "Hale goes with Lord Claudeworth from ..."), preceded by
`:agent/freed` (`:by` the leader) for one who was busy: their job stops and is judged on the
time done. A follower who isn't in the place the mover leaves gets `:stopped-following`
(`:apart`, seen only by them); one asleep, hibernating, travelling or whose movement can't make
the journey (`physical/movement-reason`) is left behind (`:left-behind`, seen by everyone there),
and so are their followers. The view gets `you.following {:id :name}` while they're together and
`you.followed-by [{:id :name}]` for followers here. Following counts only while both are active:
once either dies, retires or goes away, `following/leader` is nil (the record stays on the agent),
so the dead are never taken along and nobody follows a corpse. Moves made by the engine (hibernation, GM or
operator placing, arrivals) take nobody along.

**Aspects of a place** (with items, see the next section): a location may have `:aspects`, the
same keyed phrases as an item's (`{:cleanliness "swept" :hearth "a fire laid"}`, at most 4,
values 1-200 characters, 80 advised), about the room as a whole: its floor, hearth and air, never the things in
it. A world file may give them (checked like an item's); afterwards only work changes them (see
Work and the judge). The per-turn view shows them under `:location :here :aspects`, and `look`
under `:place`; a place with none shows none.

State that belongs to a location (prices, opening rules) goes in your `:state`, keyed by location
id. Objects people can pick up and carry are items (next section).

### Items (`manor.mechanics.items`)

The core namespace holds the accessors, checks, every item event's reducer and narration; its
parts are in `manor.mechanics.items.*`: `views`, `actions`, `work`, `judge`, `steward` and `find`.

Items are things with a description and a few aspects that lie in a place, are carried by
someone, or are inside another item: a piano, a pocket watch, an umbrella someone bought, the
silver in the sideboard. They need locations. A scenario
turns them on with the key `:items`, a map, possibly empty; its optional configuration includes
`:judge-brief`, `:aspect-keys`, `:find-allowed?`, `:act-on?` and `:steward` (see Finding, Work and the judge,
Immediate acts, and The nightly steward). Scenarios without it are unchanged.

An item:

```clojure
{:id "china-cabinet" :name "China cabinet"
 :desc "A glass-fronted walnut cabinet of Meissen figures."   ; 1-400 characters, never changes
 :aspects {:cleanliness "dusty" :repair "the glass cracked"}  ; optional (see Aspects)
 :place "drawing-room"      ; exactly one of :place (a location id)
 ;; :holder "hale"          ;   or :holder (an agent id)
 ;; :in "sideboard"         ;   or :in (the id of a container item)
 :owner "household"         ; optional: an agent id or any label; shown, not enforced
 :fixed? true               ; optional: furniture, can't be taken
 :consumable? true          ; optional: food, fuel, soap: work may use it up (see Work and the judge)
 ;; :waiting {:for "hale" :keeper "briggs" :at "general-store" :since 12}  ; optional: goods kept for someone
 :container :open}          ; optional: it holds things (see Containers)
```

Ids are kebab-case and unique; at most 60 characters of name. No other keys are allowed (the
engine adds `:touched` and may add `:drawn-from`, below). A carried item goes wherever its holder goes, and things in a container go wherever it goes, with
no events of their own. Work and the nightly steward can remove an item by using it up or absorbing it into stock.
The world retains `:items-gone {id {:name .. :place .. :round .. :fate ..}}`: where it was
(the holder's place if carried), when it went and what happened to it. `items/names` still
names old lines, and `fresh-id` never gives an old id out again. Old runs are not migrated.

- **Aspects** say what's notable about a thing's state: a map of at most 4 (`items/max-aspects`)
  keys to phrases of 1-200 characters (`items/max-aspect`). The judge, steward and finder are
  told at most 80 (`items/advised-aspect`, also the JSON schemas' `maxLength`), so a model that
  runs a little over isn't refused; over 200 is. A world file may use up to 200. Keys are plain keywords of lower-case
  letters and hyphens, at most 24 characters, starting with a letter (`items/aspect-key?`;
  `:cleanliness`, `:glass-eye`). The engine checks only the form and the cap; the keys are the
  scenario's choice. Claudeworth uses `:cleanliness`, `:repair`, `:state` (its present condition
  where no other key fits), `:amount` (how much there is of a single thing, such as a parcel's
  "20 × 1 lb"), and for stores `:stock` (the general level) and `:lacking` (what it has run
  out of). A thing with nothing notable has no
  `:aspects`. Work (and the optional steward on its candidates) changes them, one aspect at a time, so unrelated facts survive (see Work
  and the judge). Locations have them too (see Locations), and so do people (see People's aspects).
- **`:touched`** is the round an item was last taken, put down, put in a container, given,
  changed, worked on as named focus (including container contents), added or made. The reducers set it from `(:round world)`; it isn't part of a world
  file, and the exported map leaves it out.
- **`:drawn-from`** is the store id on a non-consumable thing promoted from stock. It lets
  the judge or steward put the thing back; the exported map retains it. **Absorbable** means
  not fixed, not a store, and either `:consumable?` or `:drawn-from` (`items/absorbable?`).
  A watch or the family's silver cannot be absorbed or used up.
- **`:order-id`** marks goods delivered for an order (`manor.scenarios.claudeworth.deliveries`). The nightly
  steward never draws them as candidates, wherever they've been taken; work can still use
  them up. The exported map retains it.
- **`:waiting {:for :keeper :at :since}`** marks goods kept for someone: whom they're for and
  who keeps them (agent ids), the location where they wait and the round they were left
  (Claudeworth's `buy` with hand-over "leave"). They're **shown as waiting** (`items/waiting?`)
  only while they're there: the outermost thing lies at `:at`, or the keeper holds it while at
  `:at`. Only then does the view mark them `waiting-for` and the steward see `waiting`; away
  from the shop the marker means nothing. The `:item/taken`, `:item/given`, `:item/put-down` and
  `:item/put-in` reducers, and work's `moved`, drop it from the item and everything in it once it
  no longer shows as waiting: taken by or given to someone other than the keeper, put down or put in
  something away from `:at`. Picked up, packed or put down by the keeper at the shop, it stays
  waiting. A world file gives `:for` and `:keeper` as names; the exported map leaves the marker
  out, as it does `:touched`.
- **Starting items** come from `:init` (`:items`, a vector, holders and owners as ids), or from a
  world file's `:items` (see World files). The engine checks them and adds each with an
  `:item/added` event, visible to the GM, after the agents are spawned.
- **Reading items:** never read `(:items world)` yourself; use the accessors, so the storage can
  change later inside the one namespace:

  | Function | Returns |
  |---|---|
  | `(items/item world id)` | the item, or nil |
  | `(items/all world)` | every item, by id |
  | `(items/items-at world loc)` | items lying at a location (not those carried there, or in containers) |
  | `(items/held-by world agent-id)` | what an agent carries |
  | `(items/contents world id)` | the items directly in a container, by name |
  | `(items/within world id)` | everything in a container at any depth, each followed by its own contents |
  | `(items/outermost world item)` | the item it's ultimately inside (itself if in nothing) |
  | `(items/place-of world item)` | where it lies, directly or in containers; nil if carried |
  | `(items/reachable? world agent-id item)` | does it (or what it's in) lie where they are, or do they hold it? |
  | `(items/visible-at world loc)` | what someone there can see: items lying there and what's in open containers |
  | `(items/items-where world pred)` | items matching a predicate |
  | `(items/waiting? world item)` | is it shown as waiting for someone (see `:waiting`)? |
  | `(items/waiting-for world agent-id)` | the items shown as waiting for them, in id order |
  | `(items/enabled? world)` | does the world have items? |
  | `(items/jobs world)`, `(items/job world id)` | work in progress, by `:due` then id; one job |

- **New items** (a purchase, say): `(items/added world fields visible-to)` is an `:item/added`
  event for an item with a fresh id from its name (`umbrella`, `umbrella-2`); `fields` are the
  item's keys without `:id`, and invalid ones throw. `(items/added world fields visible-to
  :amount "20 × 1 lb")` adds the amount to the event, read after the name. `(items/added-events
  world [fields ...] visible-to)` makes several, each with its own id. Don't build item maps or
  events by hand. A scenario reducer that takes items out of the world (goods sent up) uses
  `(items/remove-items world ids fate)`: each is recorded under `:items-gone` with `fate`; they
  must have nothing in them.
- **Events** (reducers in `manor.mechanics.items`; narration looks up the item's name):

  | Event | Data | Visible to | Reads |
  |---|---|---|---|
  | `:item/added` | `{:item {...}}`, plus `:amount` if given | the GM at the start; the room when bought | "Hale now has an umbrella." / "Mrs Finch now has plain household flour (20 × 1 lb)." |
  | `:item/taken` | `{:item :from-place}`, plus `:from-item` out of a container | everyone here | "Lady Claudeworth picks up the silver candlesticks." / "Nellie takes the kettle from the kitchen dresser." |
  | `:item/put-down` | `{:item :place}` | everyone here | "Hale puts down the feather duster." |
  | `:item/put-in` | `{:item :into}` | everyone here | "Nellie puts the silver candlesticks in the sideboard." |
  | `:item/given` | `{:item :to}` | everyone here | "Hale gives the pocket watch to Lord Claudeworth." |

  The last four also set the item's `:touched`.

- **Actions** (not turn-ending, every role but the GM; merged before your own actions, so you can
  override or exclude them):
  - `look {}`: replies (see `:reply` under `:actions`) with the place (its name, aspects and
    detail), the items lying here in detail and the other people here with what they carry, and
    records nothing. Empty parts are left out, as are `:aspects`, `:detail`, `:owner`, `:fixed?`
    and `:doing` when absent; agent owners show as names. Refused while travelling. The aspects,
    descriptions and owners of items here come **only** from `look`: the view lists items here by
    name (the place's and stores' aspects are in the view too). Say in the brief that it is for examining
    something closely, not a routine arrival action (Claudeworth's `rules` does).

    ```clojure
    {:place {:name "Drawing room" :aspects {:cleanliness "dusty"} :detail "..."}
     :items [{:id "piano" :name "Piano" :desc "..." :aspects {:repair "a little out of tune"}
              :owner "household" :fixed? true}]
     :people [{:name "Hale" :doing "dusting the china cabinet"
               :carrying [{:id "pocket-notebook" :name "Pocket notebook" :desc "..." :aspects {:amount "half full"}}]}]}
    ```
  - `find {what}`: once per turn, counts as a query; see Finding below.
  - `take {item}`: the item lies where you are, directly or in a container (at any depth, open
    or closed, without opening it first), and isn't fixed. Taking something you don't own is
    allowed; everyone here sees it.
  - `open {container}` and `put-in {item container}`: see Containers.
  - `put-down {item}`: you carry it; it's left where you are.
  - `give {item to}`: you carry it, and `to` is someone else here.
- **Views:** the engine adds to every agent's view `:you :carrying [{:id :name :aspects}]`,
  `:location :here :items [{:id :name}]` (names only; containers as in Containers; goods shown
  as waiting add `waiting-for`, the person's name) and
  `:carrying ["Umbrella" ...]` on each of `:people-here`. The agent's own `:carrying` is always
  there, `[]` when they hold nothing, so a character told "here, take these" can see whether
  anything was handed over; the others are left out when empty. The default GM view gets
  `:items`.
- **`:detail`** is an optional location key: a paragraph about the room that only `look` shows,
  never the view (which has `:id :name :desc`, and `:aspects` if the place has any). Keep the `:desc` short and put what someone
  would notice on looking round in `:detail`; in a world file it goes last, after `:desc`. Things
  people could use or carry belong in items, not in either text.
- Say in a brief that things are under `items` in the view and what you carry under `carrying`,
  that `look` shows their descriptions, aspects and owners (and the room's `:detail`), so it
  is for examining something closely rather than looking routinely on arrival, that some things
  hold others (open ones list `contents`, closed ones show them only to `open`; `take` works on
  either, `put-in` puts things in), that `look`, `open`, `take`, `put-in`, `put-down` and `give`
  don't end the turn, and that `spend-time` spends time on work or pastimes, with optional
  focus ids under `on` and results when it's over. Stores in scope appear every turn; they hold
  everything they keep unless their aspects say otherwise. Say too that talking changes nothing:
  things change hands only with the item actions and change only by `spend-time`, so a character
  holds only what's under `carrying`, whatever anyone says; and that characters never speak of
  views, tools or ids. Claudeworth's `rules` has the wording that was tested against
  `runs/claudeworth-20261019-233942` (roadmap C16).
- Any character can be left out of a run: Claudeworth's `:init` drops the world-file agents
  whose ids are in the run option `:leave-out` (`{:leave-out ["pugh" "nellie"]}`), with the items
  they hold. Unknown ids are ignored. Nothing in the world file marks a character as optional.

#### People's aspects

People have aspects too, under `[:agents id :aspects]`, checked as an item's are (at most 4,
well-formed keys, values of 1-200 characters). They say how a person differs now from their
fixed `:appearance`, where it shows and lasts beyond the activity: dress, hair, hands and face,
dirt and wet, a visible injury. Never inner states (hunger, tiredness, moods): those are the
person's private inner state (see Inner state). `:appearance` never changes; word changeable
things in it as habits ("usually in a print frock and apron").

- **Where they come from:** a world-file agent or an `:init` agent spec may give `:aspects`
  (`worldfile/problems` and the engine check them), and the exported `map.edn` writes the live
  ones back to the file's agents. People made during a run (hired candidates, couriers, the GM's
  spawns) start with none, and routines don't change them. Only work changes them (see Work and
  the judge).
- **Who sees them:** everyone present, every turn: `:aspects` beside `:appearance` on each of
  `:location :people-here` and on `look`'s people, and the person's own under `:you :aspects`;
  each is left out when there are none. The default GM view's agents have them, and each person's
  panel page (`/agent/<id>`) shows them. The system prompt is unchanged ("What people see:" stays the appearance).
  Other adjudicators (shop quotes, hiring interviews) don't see them.
- **Overnight:** the nightly steward, when the scenario has one, removes from people asleep the
  aspects that wouldn't last the night, and never sets one (see The nightly steward), even in a
  scenario with inner state, where it may set that. Nothing else reverts a person's aspects.
- **What the judge does:** the worker is always judged; work changes them only where the activity
  would ("Most work leaves the worker as they were"), and a pastime usually not at all.
  Scenarios can suggest person keys in the same `:aspect-keys` as items' (Claudeworth suggests
  `:dress`, `:hair` and `:grime`).
- **Acting on someone:** `spend-time`'s `on` may name people here, yourself included. Naming
  someone asks nothing of them: they needn't be free, awake or willing. When the job ends they
  are judged if they're active and at the job's place, and not otherwise; they may leave, come
  back, or `rouse` the worker, who then stops early and is judged on the time worked. Consent is
  for the characters to work out, realism for the judge. Only the worker and the people named
  can change: everyone else present stays context. `job-scope` includes `[:person id]` for the
  worker and each person named, so two jobs on one person (her ladyship dressing, her maid doing
  her hair) are judged one after the other, and the second judge sees the first's result.
  People can't be used up, absorbed or made, and a `made` entry's `in` can't name one.
- **What to tell agents:** the `spend-time` description says that `on` may name people here,
  that what you do to someone counts only if they're still with you when you finish, and that
  they may walk away or rouse you, so that only the time worked counts. Say in the brief that how
  someone looks now is in their `aspects`, beside their usual `appearance`, with yours under
  `you`, and that when someone is spending time on you (doing your hair), it counts only if you
  stay with them until they finish. Claudeworth's `rules` has the wording. There is no quick
  change: changing yourself takes `spend-time`, at least half an hour.

#### Containers

An item with `:container` holds other items, whose `:in` names it. Furniture is the use so far
(shelves, a sideboard, a glass-fronted cabinet); carried containers aren't forbidden, but nothing
supports or tests them (a basket's contents don't show in `carrying`).

- `:container :open`: what's inside can be seen from the room (shelves, glass). `:closed`: only
  `open` shows it (a cupboard). It's a fixed fact: there are no open or closed states, no locks,
  and no events for opening.
- **Checks** (world files, `:init` and `items/added`): exactly one of `:place`, `:holder` and
  `:in`; `:in` names an existing item (in the same list or already in the world) that has
  `:container`; and no loops ("its containers go round in a loop"). Contents may be listed
  before their container. Nesting is allowed; there are no sizes or capacities.
- **The view** shows an open container as `{:id :name :contents [{:id :name} ...]}`, nested the
  same way for open containers inside it and with `:contents []` when it's empty, and a closed one
  as `{:id :name :closed true}`. `look` does the same with each item in detail. Every open
  container's contents are listed, however many.
- **`open {container}`** (free, reply-only, records nothing, so nobody else learns anything):
  the container is here, in a container here, or carried, and has `:container`. The reply is
  `{:name :aspects :contents [...]}` (`:aspects` only if it has some), each item as `look` shows it, inner closed containers
  with `:closed true` and not their contents. Open containers can be opened too.
- **`put-in {item container}`** (free): the item is carried by you or could be taken here (so not
  fixed); the container is in reach and is one; the item isn't the container, around it, or
  already directly in it. Reasons say which. The item moves at once. Into an ordinary container
  (such as the sideboard), this is deterministic and makes no judge call. Into a store, it also
  starts a **quick put-away job**, judged at the next round start (see Work and the judge).
  `put-down` still puts what you carry on the floor.
- **Hidden contents stay hidden:** reasons that list "items here" list only what can be seen
  (`items/visible-at`), never what's in closed containers. Something inside what another person
  holds is just "not here". But `take` accepts any id in reach, so a guessed id inside a closed
  container works; that's by design for now.
- **Work on a container reaches its contents:** `spend-time` accepts things inside containers here,
  and the items in reach for the judge are each job item plus everything `within` it, at any
  depth, open or closed. The judge sees `in` (the container's id) on an item whose container is
  also listed, and may change the aspects of any of them. A container counts as one of the six items.

#### Stores

A store is a fixed item standing for untracked supplies, not an exhaustive inventory:

```clojure
{:id "pantry-shelves" :name "Pantry shelves" :place "pantry" :fixed? true
 :desc "Stout shelves of jars, packets and crocks."
 :store {:keeps "dry goods, preserves, condiments, butter and cheese"
         :serves ["kitchen"]}
 :aspects {:stock "well stocked with the household's staples"}}
```

`:keeps` is 1–200 characters; `:serves` is optional and names known locations. A store must
be `:fixed?` and cannot be `:consumable?`. It can also be a container of tracked things; all
container actions still work. `(items/store? it)` identifies stores; `(items/stores-for world
place)` returns those at the place or serving it, in id order.

Every turn, `:location :here :stores` lists `{:id :name :aspects}`, with `:at` naming the
location id for stores elsewhere. Stores here are omitted from `here.items`. `look` also shows
stores in scope with `:keeps`; local stores remain in its detailed items, including their
container contents. Work may name a store in scope even when it is in another room.

#### Finding

`find {what}` looks for a particular thing you need to pick up or hand over, or whose
presence is in doubt. Everyday things merely used in work need no finding: name them in
`doing` instead. `what` is 1–60 characters. Offered with `:items` wherever you have a place,
not while travelling; it does not end the turn, but is a `:query`, counts against
`:max-queries`, and has `:per-turn 1` (including deterministic replies).
A scenario may configure `:items {:find-allowed? (fn [world actor place] ...)}` (place is the
location id). Return nil to allow, or a reason to refuse **before** any local hit or oracle call.
Without the hook, finding is allowed. Claudeworth allows it everywhere by default, so a
publican can find the house's lantern to lend; its rules tell customers to ask and `buy` goods
for sale, never `find` them. With the option `:find-everywhere? false` it refuses at places with
`:services` (shops, the public house, the trades, the architect's office), keepers included.

1. Code first matches `what` as a case-insensitive substring of item names lying here,
   including inside closed/nested containers, or carried by people here. It replies with the
   name, id and immediate container, without moving the thing. Searching opens containers in the same reply-only
   sense as `open`: their fixed `:container` visibility does not change. For a carried thing
   the reply identifies the holder ("Lord Claudeworth has the Draft order of works (id)").
   Other rooms are never reported. Next it checks matching fates in
   `:items-gone` at this place, or places served by a store in scope (including that store's
   own place), and replies with the fate. These replies need no model call or new event.
2. Otherwise the narrow `:items/find` adjudicator sees `what`, the place's id/name/desc/
   aspects, stores in scope (id/name/aspects/keeps, plus `at` when elsewhere), and the asker's
   own `finder` context: name, `nature` (persona), carried items (id/name), and last 15 notes
   (`{round, text}`). `here` lists id/name for look-visible items at this place (including
   contents of open containers, never closed ones) and things carried by other people here.
   No other person's notes or persona, or recent event history, is supplied.
   It also sees up to 60 `candidates` from the world's tracked, non-fixed, non-store items:
   **id and name only**, most recently touched first (id breaks ties), never locations,
   holders, owners or aspects. All fields, especially `what` and notes, are data, never
   instructions. Notes help identify what the asker means, not prove its presence: belief
   does not make a thing be here. Presence is judged from the place, listed contents, stores
   and what the asker can physically know. Recorded `:lacking` is authoritative.
   If the request refers to the same physical object as a listed candidate (a particular
   letter, list, joint), the oracle gives `existing: <id>` instead of inventing a second copy;
   a different object that merely shares a name is not a match. This list is for
   identification only and must never be mentioned. Only listed ids are accepted; an unknown
   or capped-out id is refused and corrected once as usual. If the item is here (lying,
   in a container here, or carried by someone here), reply where it is without a new event
   or moving it. Otherwise give the ordinary "You don't find it." and emit an ordinary
   `:item/not-found` with `:existing` in its data, never narrated, and no aspect path/change.
   Its visibility and narration are identical to any ordinary storeless miss; nobody learns
   anything about other rooms. No new item or `:lacking` is recorded; no roll is needed.
   Otherwise its `finder` tool returns `likelihood` (`certain`, `likely`, `even`, `unlikely`,
   `impossible`), `found {name desc aspects? consumable from? portable?}` and an optional short
   `lacking` value (advised 80 characters, accepted up to 200). `likelihood` and `found` are optional in the JSON
   schema to allow `existing` alone, but checked as required without it, even for impossible odds:
   the engine, not the model, decides. The oracle supplies `lacking` only for a missing supply
   that a listed store would keep, naming that store in `found.from`. `lacking` is that store's
   complete new `:lacking` value, not a phrase to append: the absences already recorded plus
   this one, `; `-separated. If the store's `:lacking` already records this absence in any
   wording, the oracle omits `lacking`. Deciding what is already listed is the oracle's
   judgement; code doesn't parse or merge the list. For a particular thing
   (a coat, a letter, a joint bought for dinner), omit `lacking` and rate whether it plausibly
   exists here. No listed store does not mean no particular thing can exist here.
   `from`, when present, must name a store in scope; `lacking` without it is refused.
   Found names/descriptions/aspects
   have the normal item bounds; `consumable` is required, `portable` defaults true.
3. On the live world the engine rolls `rng/roll world [:find next-event-seq] 100` (0–99)
   against thresholds 100/75/50/25/0. Success emits `:item/found`: a fresh item in the
   finder's hands, or lying here for `portable: false`; `from` becomes `:drawn-from`.
   Failure always emits `:item/not-found`. Only when the oracle supplied both `lacking` and
   a store source, set that store's `:lacking` to it. Never evict an unrelated aspect: a store
   with four non-lacking aspects records no aspect change. Without `lacking` or a store,
   record no aspect change either; never fall back to the place. A later search may disagree
   when an absence could not be recorded.

Both rolled outcome events record `what`, place, likelihood, roll key, roll and outcome,
visible to finder and people here. An existing-item miss records `what`, place, `:existing`
and outcome only. Success records the complete item and source; failure records its
chosen aspect path and resulting aspects. Pure reducers apply them without calling or
rolling; replay equals live state. Replies give the found id and whether carried/lying here,
or "You don't find it." Narration is short: "Daisy looks for mustard: there's none in the
pantry shelves." A bad answer is checked and corrected once; repeated failure emits only
system-visible `:adjudication/failed` (`:task :items/find`), plus usage, and replies neutrally
"you don't find it" without changing items/aspects. Calls log as
`adjudicator/items/find`, counted against cost. The default mock is certain, a plain item
from the first store in scope (or lying here without a store), unless a candidate name
matches: then it gives `existing`. Ordinary mock scripts need not call `find`.

#### Work and the judge

`spend-time {doing, hours?, on?}` (turn-ending, every role but the GM, offered only with
`:items` and a clock) spends time on work or a pastime:

- `doing`: advertised as 3–200 characters (`items/advised-doing`), with a soft limit:
  decoding accepts up to 400 (`items/max-doing`); 401 is refused with the usual length reason.
  The tool description says at most 200, and Malli's `:json-schema/maxLength` overrides the
  model-facing JSON schema to 200 while validation keeps `:max 400`. The alias uses the same
  limits. It reads after "is" ("dusting the china cabinet", "resting in the garden").
  `hours`: optional, 0.5–4, default 0.5; rounds as `clock/rounds-for` gives them,
  at least one. `on`: optional list of up to 6 ids: items here (in a container or not) or carried
  by you, the place you're in, stores in scope (even in another room), or people here, yourself
  included (see People's aspects). Fixed items are fine; duplicates are dropped. A rejected id
  gets an actionable reason; a person elsewhere gets "Mrs Pell is not here; people here: you
  (dawson), Lady Ashby (lady-ashby)". No `on` is normal for a pastime, and most pastimes change
  nothing.
- `work-on {items, doing, hours?}` remains an alias for now: `items` (1–6 ids) means `on`, with
  the same effect. New scripts and briefs should use `spend-time` and `doing`, not `activity`.
  `sleep` in Claudeworth stays separate and unjudged.
- Its effect is a job, `:item/work-begun` (visible to the GM; data: the job), and an occupation
  (`b/occupy`, seen by the room: "Hale is dusting the china cabinet until 15:30") whose `:busy`
  carries the job id as `:job`. The job table is `(:item-jobs world)`, keyed by id (`"job-<seq>"`):
  `{:id :actor :items [..] :doing :place :started round :due round}`, with `:people [..]` when
  `on` named people (an id that is an item's or the place's is a thing, never a person). Read it
  with `items/jobs`.
- **Quick put-away jobs** come from free `put-in` into a store. The move happens immediately;
  the job has `:quick? true`, `:store` (the destination id), focus on the item and store,
  doing "putting the <item> away in the <store>", and `:due` the next round. It creates no
  occupation: the actor keeps their turn, may leave or start other work, and a missing or
  different `:busy :job` does not stop it. Further put-ins by the same actor into that store
  in the same round merge focus and doing in the same job via `:item/work-extended`
  (`{:job :items :doing}`, GM-only, pure reducer). Different workers or stores stay separate.
  Quick jobs join ending jobs' usual due/id ordering and parallel batches, with zero minutes
  and `:stopped-early? false`; unlike ordinary no-time jobs they are judged. The judge can
  absorb things into stock, clear/change store `:lacking`/`:stock`, change affected aspects,
  or leave things untouched. Retired workers' jobs are still dropped.
- **Judged effects wait until the work is over** (a quick put-away job's move already happened). At the start of each round, after arrivals,
  releases and hibernation and before approved bridges and systems, the engine takes the jobs that
  end, in order of `:due` then id (decided on the world as the round starts, before releases):

  | Situation | What happens |
  |---|---|
  | the worker has been retired | dropped: `:item/work-ended`, visible to the GM, no call |
  | a quick job's `:due` has come | finished at zero minutes, regardless of occupation |
  | an ordinary job's worker's `:busy :job` is no longer this job (they stopped after `rouse`, or something replaced the occupation) | stopped early; minutes worked = (this round − 1 − started) × minutes per round |
  | an ordinary job's `:due` has come | finished; minutes worked = (due − started) × minutes per round |
  | otherwise | still going on |

  `carry-on` keeps the occupation and its job, so the work runs to its end.
- For a stopped or finished job, **focus** (`item-work/focus-things`) is the named things still
  in reach: the room if named, and items at the job's place or carried by the worker, or stores
  in scope, each followed by everything inside it. `items/in-reach` stays items-only.
  The **scene** (`item-work/scene`) always includes the place, focus, up to 12 other visible items
  (not inside closed containers), stores in scope, and people: those present, and the judged
  people (`item-work/judged-people`: the worker, wherever they are, then the people named in `on` who
  are still at the job's place, alive or dead). Dead people lying at the place are included too.
  **Judged things** (`item-work/judged-things`) are the place, focus,
  other visible items, stores and the judged people, once each (a person whose id is also a
  judged thing's isn't judged). With no time worked (except quick put-away jobs), the job ends
  with `:item/work-ended`, seen by the worker and people at the place, and no call. Every other
  activity is judged, even one naming nothing at an empty place, because the worker can always
  change. The engine
  asks the scene judge and emits `:item/work-done`, seen by the worker and people at the place:
  `{:job :actor :place :doing :minutes :stopped-early? :luck :summary :changes [..]}`, plus
  `:used-up [{:item :name :fate}]`, `:absorbed [{:item :name :into}]`, `:made [item ...]`
  (complete new items), and `:fetched [id ...]` (which made entries were drawn from stores)
  when there are some.
  Each change is `{:item id :aspect k :from s :to s}`, `{:place id ...}` for the room or
  `{:person id ...}` for a person; `:from` is nil for a new aspect and `:to` nil for one removed.
  Its reducer sets or removes each aspect (on the item, on the location under `[:space :locations
  id :aspects]`, or on the agent under `[:agents id :aspects]`), touches the changed
  items and named focus, removes used-up and absorbed things (recording their fates), adds the
  made ones (touched too) and removes the job. Jobs with disjoint scopes (place, stores in scope,
  focus items’ places, the worker and the people named) are judged
  concurrently on the pre-batch world; overlapping jobs remain sequential, so a later judge
  sees an earlier one’s result. Events, usage and call logs stay in due/id order; on the same
  aspect the last applied wins. It reads "Nellie
  finishes sweeping the drawing room and dusting the piano. (Drawing room: swept, the hearth clean;
  Piano: dusted)." or "(Lady Claudeworth: hair dressed high, with combs)" (each thing's changes by their new values, which are whole phrases, or "no
  longer lacking" for an aspect `:lacking` removed, hyphens in the key read as spaces;
  "leaves off" when stopped early; the summary is left out when it only repeats the opening),
  followed by "Used up: Rib of beef. Made: Beef stock in the saucepan; Beef bones." when
  something was. Absorption and drawing read "Put away: Loaf sugar (in the Pantry shelves).
  Fetched: Dripping tin."
- **Promotion, absorption and fates.** Work may use up unfixed items marked `:consumable?`
  (food eaten or cooked into something else, fuel burnt, an ingredient used up), and make at
  most two things (`items/max-made`). A `made` entry without `from` may be the activity's
  direct product from everyday supplies, with no tracked source or use-up needed (a letter,
  a list, plans, a dish). Keep `from` for drawing from a store in scope (including one serving
  this place), not for making a work product. Each entry gives `name`, `desc`, optional
  `aspects`, a required boolean `consumable`, optional `from`, `held` and `in`. Writing/drawing
  products are not consumable; dishes are. Make a thing only if someone will carry, hand over,
  serve or keep it; otherwise describe the result as an aspect (the room's table "laid for
  dinner"). Do not promote every utensil used. A made thing gets a fresh id, the consumable flag, and
  the first used-up thing's owner if any. A non-consumable drawn thing gets `:drawn-from`.
  Placement: `in` a named container in reach takes precedence; otherwise explicit `held: true`
  puts it in the worker's hands and `held: false` at the job's place. The judge is instructed
  that things naturally kept about the worker (a letter, a list, a note) are held; dishes and
  large things aren't. Without either option, keep the deterministic default: in the worker's
  hands when no place-bound focus was named, else at the job's place. A place-bound focus is the named
  room or an item lying at a place (including inside a container), rather than carried.
  `absorbed: [{thing, into}]` returns an absorbable thing in focus or `also_here` to a store
  in scope. It is removed, with fate "put away in the <store name>". All real contents of a
  container must be absorbable and absorbed too, even hidden contents or those beyond the
  scene cap; otherwise the answer is refused generically. A thing cannot be both absorbed and
  used up. Store aspects may change in the same answer, but need not.
  `fates` maps used-up ids to nonblank fate phrases; omissions default to "used up while
  <doing>". Something partly used is not used up: change its `:amount` ("about 17 lb left").
  An activity happens in one place and the judge cannot move things: carrying something
  elsewhere is not using it up. Leave it where it is; characters carry things with `take`
  and movement actions, and dishes are used up where eaten. When leftovers or supplies are
  put away into a store in scope (leftovers set in the pantry, groceries shelved), absorb
  them into that store: they join its stock and get a "put away" fate. Adjust the store's
  aspects if its stock or lacking changes. Never describe a thing as moved or put away in
  its aspects while leaving it where it is. If something isn't put away or consumed, leave
  it as it is. This is judge guidance, not a new effect or semantic validator. Leaving any
  thing untouched is always a valid answer. An activity may assume a particular thing someone
  said they brought, handed over or set aside that is neither in the scene nor carried (seven
  packets "counted into my apron" in talk): the judge treats it as not existing, writes nothing
  as if it were counted or set out, and says in the summary that it wasn't there. Clothes,
  everyday things and a store's supplies are still to hand.
- **The judge** is a narrow model call, not the GM and not a bridge; characters never hear of it.
  `item-judge/judge-request` builds it from the optional `:items :judge-brief` (`(fn [world] text)`),
  optional suggested aspect keys with short glosses (`:items {:aspect-keys {:cleanliness "how
  clean or dirty it is" :repair "damage, wear or needed repairs" ...}}`), time, worker's name and persona, doing,
  what the worker carries (`- Carrying: Pipe (pipe)`, or `nothing`), minutes worked, whether stopped early, and luck (1–100, 50 ordinary, `rng` key `[:judge job-id]`).
  Claudeworth suggests cleanliness, repair, state, amount, stock and lacking. These aren't a
  closed vocabulary: other well-formed keys are allowed. The user message's scene is:

  ```clojure
  {:place {:id "kitchen" :name "Kitchen" :desc "..." :aspects {...}}
   :focus [{:id "kitchen-range" :name "Kitchen range" :desc "..." :aspects {...}}]
   :also_here [...]
   :stores [{:id "pantry-shelves" :name "Pantry shelves" :keeps "dry goods, preserves, ..."
             :aspects {:stock "well stocked"} :at "pantry"}]
   :people [{:id "mrs-finch" :name "Mrs Finch" :appearance "..." :aspects {:apron "floury"}
             :doing "cooking dinner" :person true}
            {:name "Nellie" :doing "blacking the range"}]
   :recent ["[17:00] Mrs Finch says: ..."]}
  ```

  `people` lists everyone present and the judged people: those the judge may change are marked
  `person: true` and have their id, `appearance` and `aspects`; the others are context, by
  name and what they're doing. All may show `physical` (each faculty as `{level, cause?, set_at}`,
  with `set_at` a short time or, without a clock, `"round N"`), `impediments`, and `dead: true`.
  When the scenario has `:inner` (see Inner state), everyone's
  entry also has `inner` (every fixed key, an empty one as `{"value": ""}`, then the open
  ones, each `{"value": "...", "set_at": "18:30"}`), and the judged people's also have their
  `temperament`, `persona` and `situation`.

  Focus includes container contents as above, with `in` only when that container is also in
  focus; entries mark `consumable: true`, `container: true`, or `room: true` when appropriate,
  and `drawn_from` names a drawn thing’s store.
  `also_here` has the same item shape, at most 12; stores are excluded from it. No other rooms,
  other people's holdings (except the judged people's `carrying` for an act), or closed-container
  contents are added to the scene. A focused container still includes its contents, open or closed, as before.
  `recent` is at most 10 narrated lines the worker saw since this job began, excluding events
  only they saw (notes and private replies); it uses `prompt/visible-events` and the same
  narration as agent prompts, not a separate visibility rule. Shared whispers may be included.
  Room detail is not sent.
  Instructions say most activities change little and a pastime often nothing: empty answers
  are normal, and the judge must not invent events to make it eventful. A store holds everything
  it keeps unless its aspects say otherwise. Change only aspects affected by this activity;
  the place's aspects describe the room itself, not its contents.
  It has one required tool, `judge {changes: [{thing, aspect, value}],
  used-up: [id], fates: {id: phrase}, absorbed: [{thing, into}],
  made: [{name, desc, aspects?, consumable, from?, held?, in?}], summary}`: each change sets one aspect of one
  thing, and `value: ""` removes it. With `:inner`, the tool also has `inner: [{person, key,
  value}]` beside `changes`, and the instructions an "Inner state" section (see Inner state).
  Both work and acts also accept `physical: [{person, faculty, level, cause?, minutes?}]`,
  `impediments: [{person, key, value}]` and `died: [{person, cause?}]` (see Physical status).
  These may change only judged people; faculty levels must fit the faculty, and `minutes`
  applies only to disabled consciousness. Death cannot be reversed: the dead may have only
  aspects and impediments changed (and held things transferred by an act). Impediments have
  their own cap of 3; their phrases and causes are never interpreted by code. Most work
  changes none of these; physical hurts belong in impediments, not outward aspects.
  Checked physical changes follow the work/act result as separate physical events with their
  own stored audiences and interrupts, never as arbitrary patches. The engine sends it through
  the run's LLM with the model `:judge-model` (a run option; nil means `:model`) and the run's
  `:reasoning` and `:max-tokens`, without holding the world lock. Each call is in `llm.edn` as
  agent `"judge"` and its usage is an `:llm/usage` event, so it counts towards `:max-cost`;
  replay uses the recorded events and never calls it.
- **Checks on the answer:** a change names a judged thing ("you may change only these things:
  hall, clock; not umbrella"); its aspect's name is normalised as argument names are
  (`glass_eye` and `glassEye` are `:glass-eye`) and must then be a key (`items/aspect-key?`); a
  value is at most 200 characters (80 advised); no thing may end up with more than 4 aspects, counting those
  it has less those removed ("the Grandfather clock (clock) would have 5 aspects (...); a thing
  has at most 4: change one it has instead, or remove one with the value \"\""); a summary of 1-300
  characters. When the same aspect of a thing comes twice, the last counts. Other keys (a `desc`,
  a `name`) are dropped, so existing things keep their identity and descriptions, and unchanged
  values (or removing an aspect a thing hasn't got) are left out, as are changes to removed
  items. `used-up` names only consumable, unfixed items in focus or `also_here`, and a container
  only with all its real descendants used up. The check reads the world, not just the scene:
  hidden contents or contents beyond the 12-item cap cannot be orphaned. The rejection names
  no unseen things and suggests naming the container with `on` next time; `made` has at most 2 entries (name 1-60, desc 1-400 characters, optional
  aspects as above, required consumable flag, optional boolean `held`); any `from` must name
  a store in scope. Source-free products need no use-up; whether they are the activity's
  direct product is the judge's decision, not a code classifier. An `in` must be a container in reach that is neither used up nor absorbed.
  With `:inner`, the `inner` list is checked too (see Inner state). Without it, an `inner` list
  is dropped like any unknown key.
  A bad answer is sent back with the reason and asked once more. A second bad answer, or an
  exception, gives `:item/work-done` with no changes and a
  neutral summary ("Hale finishes dusting the china cabinet."), plus an `:item/judge-failed`
  event (`:visible-to :system`) with the reason. The run isn't paused.
- **Mock runs:** a call with role `:judge` goes to the scenario's `:mock-judge` if it has one,
  else to `item-judge/mock-judge` (each focused thing gets cleanliness "just seen to", and each person
  named in `on` who is still here a state "just seen to"; it leaves the worker alone unless named,
  so without `on` it changes nothing and makes nothing, and never changes inner or physical state;
  an immediate act changes nothing by default). For quick put-away jobs the default mock instead
  absorbs deposited consumables (only with absorbable consumable contents) and clears matching
  semicolon-separated shortages using whole-word matches against their names ("sugar" matches
  "Loaf sugar", but "tea" does not match "meat"). Other shortages survive. Thus
  `:mock-policy` never sees the judge. Its context has
  `:world :job :things` (all judged things), `:focus` (named things still in reach),
  `:minutes :stopped-early? :luck :step`, and it returns `{:tool :judge :args {..}}`.
  `item-judge/judgement` takes the response, world and judged things, so every check includes real
  container contents.

#### Immediate acts: `act-on`

Opt in with `:items {:act-on? true}` (Claudeworth does). `act-on {on, with?, doing}`
is a final action, not a query or an occupation. `on` names 1–3 other people here, alive or
dead; `with` names up to 2 items here or carried by the actor; `doing` has the same bounds
as `spend-time`. The actor must be alive, conscious and at a place. Bound hands, a gag or
blindness do not forbid an act: the judge sees the statuses and decides what is possible.
Use it for immediate acts on people (seizing, binding, freeing, striking, searching, reviving);
use `spend-time` for longer care. Work's effects still wait until it ends.

The final action's `:judge-job` hook builds a deterministic `"act-<seq>"` job-like map with
`:act? true`, people from `on`, focus from `with`, and the actor's place. The work judge is
called inside the turn, before the next actor, with "An immediate act, a few minutes", no
time-worked line, and the usual luck roll. The judged people's `carrying` lists item ids and
names. The answer may change aspects, inner state when enabled, statuses, impediments and
deaths, but cannot make, use up or absorb things. Only an act accepts
`moved: [{item, to}]`: an item directly held by the actor or a named person, each item once,
to a judged person's id or `"place"` (dropped here). Container contents stay inside it.
Other people's or loose items cannot be transferred this way; known reachable items still
use `take`. Duplicate transfers or destinations outside the judged people are rejected;
no-op transfers are omitted.

`:person/acted-on` records `{:act :place :on :with? :doing :luck :summary? :changes :moved?}`,
with the actor as `:actor`. Its reducer applies aspects and transfers (`:moved` entries
have `:item :from` and either `:holder` or `:place`); physical and private inner changes follow
as their existing events. The act is seen by people here who can see, and always by the
people acted on unless unconscious. It interrupts named busy people even if no status
changed, except those made unconscious or dead. Its tool reply is the summary.
A failed judgement still spends the final action and records an act with no changes, a
neutral reply, and a system-only `:item/judge-failed`; there is no occupation.
In a simultaneous group, proposals run concurrently and acts are rechecked and judged in
schedule order after the group, like other final actions. Their results are in events, not
the already-returned proposal replies. A later actor made unconscious by an earlier act is
rejected before judging. `:mock-judge` handles acts as well as work; inspect `:job :act?`.
The burglary fixture demonstrates both binding and a seizure with a transfer, without
classifying `doing` prose.

#### The nightly steward

Opt in with `:items {:steward {:at "02:00" :idle-hours 6 :max-per-call 8}}`; absent means
no pass. Claudeworth currently uses `:idle-hours 4` for testing (the other values above
are unchanged); it may be raised again later. At round start **after ending jobs**, before approved
bridges and systems, the steward sees leftovers after work and before anyone creates new work.
It runs when the game clock reaches or crosses `:at`: the most recent scheduled time must be
at or after the run's clock start and not already recorded. A run starting at 20:00 skips that
day's already-past 02:00 and runs at the next 02:00; starting exactly at 02:00 runs immediately.
`:items/steward-ran {:day "1904-10-16"}` records the day under `:items-steward-day` in the
world, even with no candidates; replay reproduces the once-per-day decision.

Code selects absorbable items not carried or inside closed containers, without an `:order-id`, untouched for at least
`idle-hours` of game time. Starting items age from the clock start. Candidates are grouped by
place (place-id order), oldest `:touched` first, id breaking ties, at most `max-per-call` per
place; excess waits for another night. Goods with a `:waiting` marker stay candidates like any
other: no code keeps them out. While shown as waiting, a candidate's JSON has `waiting: "for
Hale since Wed 12:30"`, and one instruction line says such goods are paid for, to be collected
or sent up, and are left unless plainly forgotten for days. The steward also sees **sleepers**
(`item-steward/steward-sleepers`): active people whose `:busy` has `:asleep?` or
`:hibernating?` (a routine keeping them out of play, such as Claudeworth's villagers), and who
haven't been roused, each at their location. Without inner state only those who have aspects,
physical statuses or impediments are sleepers; with the scenario's `:inner` (see Inner state)
everyone asleep or hibernating is, aspects or not. **Hindered** people
(`item-steward/steward-hindered`) are other active people here with statuses or impediments,
including the awake, roused and unconscious. The dead are never candidates. A place with
candidates, sleepers or hindered people gets a call; empty places cost nothing. Someone who
stays awake keeps their outward aspects: the steward may change only their physical problems,
not their aspects or inner state.

One narrow `ask-structured!` call per place sees only its place, stores in scope, candidates
and `sleepers [{id name appearance aspects physical? impediments?}]`, plus
`hindered [{id name appearance physical? impediments?}]`. Faculties have `level`, optional
`cause`, and `set_at` (a short time, or a round without a clock). Hindered people have no
private context or inner state in this request. With inner state each sleeper also has their
`temperament`, their `inner` state (as the work judge sees it: every fixed key, with when each
entry was set), and how they spend the night: someone asleep has `went_to_sleep` (the round
of their last `:agent/occupied` in the run's events, `item-steward/occupied-since`; left out
when there's none, e.g. after a load that started them asleep) and `wakes` (their `:busy
:until`); someone hibernating has `doing` (their routine's activity) and `since`, from the same
event. Times are short ("Sat 22:30", "07:30"). No recent lines.
The `steward` tool accepts `changes`, `used-up`, `fates`, `absorbed`, `physical`, `impediments`
and required `summary`, never `made`, `moved` or `died`. It may absorb a candidate into a store
in scope, use up a candidate with a fate,
change its aspects or leave it. Even non-consumable drawn candidates may be used up (discarded).
Only candidates can change; never room or store aspects. A sleeper's aspects may only be
removed: the steward is told to remove those that wouldn't last the night (hair let down,
evening dress taken off) and leave the rest (a grease-stained apron stays stained), which is its
judgement; a change that sets one is refused ("a sleeper's aspects can only be removed, with the
value \"\"; ...") and corrected once, as usual. Container checks use all world
contents as for work. Unknown fields are dropped; they cannot create anything.

With inner state the tool also has `inner: [{person, key, value}]`, and the instructions add
lines in this sense: sleepers have an inner state, which only they know, and each entry says
when it was set; the night passes for them between then and the morning, so update their
inner state to take that time into account, since some things change over a night and some
don't, which is the steward's judgement; an empty entry is a normal state and leaving someone
as they are is a normal answer; their temperament informs the judgement but doesn't decide it; then the keys and the
open-key cap (`inner/key-instructions`). Nothing in them says that anything fades or that rest
eases it: whether it does is the steward's call. The list is checked with
`inner/checked-changes` with the place's sleepers as the judged people ("you may change the
inner state only of these people: mrs-pell; not dawson"), and a bad answer is sent back once.
Outward aspects stay remove-only.

For sleepers and hindered people, `physical: [{person, faculty, level, cause?}]` may clear an
existing faculty (`normal`) or ease `disabled` to `impeded` where that faculty permits it.
`impediments: [{person, key, value}]` may clear or rephrase an existing impediment, never add
a new key. The judge decides whether the new phrase is milder; code does not rank prose.
Instructions say a twisted ankle may ease and someone unconscious come to, but ropes and
gags do not untie themselves. New problems, worsening levels and out-of-scope people are
refused through the usual checked-answer retry.

Calls are logged as agent `"steward"`, use `:judge-model` and count toward cost. The shared
one-correction lifecycle applies; a final failure leaves things alone and records
`:items/steward-failed` (`:system`). Every checked result is `:items/stewarded`, GM-only,
with `{:place :summary :changes}`, plus named `:used-up` and `:absorbed` when nonempty. Its
reducer records removals and fates, and touches changed candidates; a sleeper's removal is a
change `{:person id :aspect k :from v :to nil}`. It never holds inner state: the sleepers'
inner changes follow it as GM-only `:person/inner-changed` events with `:source {:steward
"1904-10-16"}` (see Inner state). Its short narration is "Steward at Kitchen:
Idle things were put away.", with its changes after the summary as for work ("(Lady Ashby: no
longer dressed high, with combs)": a person's removed aspect reads by its old value).
Physical recovery follows as GM-only `:person/physical-changed` or
`:person/impediments-changed` events (`item-steward/eased-events`), without interrupting or
waking sleepers. Characters see no steward event: they discover the changes in their views.
A call with role `:steward` goes to the scenario's `:mock-steward` if it has one, else to the
default mock (`item-steward/mock-steward`, context
`:world :place :candidates :sleepers :hindered :step`), which absorbs everything it safely can
into the first store in scope, otherwise leaves it, and removes nothing from sleepers and
changes no inner or physical state. A scenario's `:mock-steward` may return `:inner`,
`:physical` or `:impediments` in its args (the dressing fixture updates inner state; the
burglary fixture eases an ankle but leaves the ropes).

Stores of provisions can be consumable items, a parcel each, whose `:amount` the judge rewrites as
they are used (Claudeworth's purchases).

### Inner state (`manor.mechanics.inner`)

A person's inner state is how they are inside, which only they know: their spirits, how their
body is, what's on their mind, and anything else about them that doesn't show (a headache, an
old knee playing up). It's short keyed phrases under `[:agents id :inner]`, like aspects, with
the round each entry was set under `[:agents id :inner-at]`. Judges change it; the person only
reads it. Opt in with a scenario `:inner` entry:

```clojure
:inner {:keys {:mood "their spirits and emotions now"           ; fixed keys, each with a gloss
               :body "how their body is, as only they know it"
               :mind "what they're preoccupied with, anticipating or dreading"}
        :open 2}                                                ; open keys besides them (default 2)
```

- **Keys and values:** keys are formed as aspects' (`items/aspect-key?`), values 1–200
  characters (80 advised). The fixed keys only mean that judges are always shown every one,
  an empty one as `""`, and that they don't count against `:open`. Nothing is filled in:
  an empty entry is simply absent, and emptying any key removes it (with its `:inner-at`); an
  agent with no entries has no `:inner`. There are no meters or levels and **no automatic
  fading**: an entry stays until a judge changes it, and when it was set is only shown to the
  judges. Code checks only structure, never the wording.
- **Where it comes from:** a world-file agent or an `:init` agent spec may give `:inner` (a
  character who starts the run unsettled). `worldfile/problems` checks keys and values; the
  engine also checks the open keys against the scenario's `:inner`, and refuses `:inner` in a
  scenario without one. Starting entries count as set in the round the agent was spawned.
  Anything left out starts empty, and so do people made during a run. The exported `map.edn`
  writes the live `:inner` back to the file's agents.
- **Who sees it:** the person, under `:you :inner` in their view (only entries with a value),
  and the GM (the default GM view's agents), the operator (each person's panel page, `/agent/<id>`) and the judges.
  Never `people-here`, `look`, or any other agent's request. Privacy comes from where it's put,
  not from checking text.
- **What changes it:** the work judge, the conversation judge and the nightly steward (below,
  and The nightly steward, which updates sleepers' inner state for the night that passes). The work judge sees
  everyone present's inner state and may change the judged people's (the worker, and the
  people named in `on` who are still there), never a bystander's. Its answer's `inner: [{person, key, value}]` list is checked in
  `item-judge/judgement` (given the scenario's `:inner`): the person is judged ("you may change
  the inner state only of these people: dawson, lady-ashby; not mrs-pell"), the key is
  well formed (normalised like argument names), the value fits, and nobody ends up with more
  open keys than `:open` ("Mrs Pell (mrs-pell) would have 3 keys besides mood, body (...); at
  most 2: change one they have instead, or clear one with the value \"\""). `""` clears a key;
  the last of a key counts; unchanged ones are left out. A bad answer is sent back once, as for
  the rest of the judge's answer. The judge is told, in this sense: change inner state only
  where the activity would and leave the rest; temperament informs the judgement but doesn't
  decide it; the summary is read by everyone present, so it says only what they could see or
  hear, never anyone's inner state.
- **The conversation judge** (`manor.mechanics.conversation.judge`): when a conversation ends
  in a scenario with `:inner`, one narrow call (`judges/ask-conversation-judge`, recorded in
  `llm.edn` as agent `"conversation-judge"`, model `:judge-model` or else `:model`) judges how
  it left its members, before the initiator's summary is returned, so their next step and
  everyone's later turns see the result. It's skipped when the conversation was cut short (at
  the cost limit or by an error) or the run's cost limit is reached; one where nobody answered
  is still judged. Its request has the place and time; each member, including those who left
  part-way, with id, name, persona, situation, temperament, appearance, aspects and inner state
  (every fixed key, with when each entry was set); and the conversation's lines in order
  (`:agent/said`, `:conversation/silent`, `:conversation/left`), each as `{person, says}` or
  `{person, does}`, with `heard_by` (the members who heard it, from the event's `:visible-to`)
  when not every member did. Nothing about anyone else: no other rooms, no bystander. Its tool
  is `react {inner: [{person, key, value}], summary}`, the summary at most 300 characters; the
  `inner` list is checked as the work judge's, with the members as the judged people (a
  bystander who overheard can't be changed), and a bad answer is sent back once. It's told, in
  this sense: an empty answer is normal; judge each member from what they said and heard, given
  their temperament and how they were before; change only what the conversation would, and
  only the members'; the summary is for the record, read by nobody in the simulation. Its
  verdict is a GM-only `:conversation/judged {:about id :summary}` (narrated `The conversation
  judge on conv-12: ...`), then the members' changes; a failure leaves everything as it was,
  with an operator-only `:conversation/judge-failed {:about id :reason}`. These hold the id
  as `:about`, not `:conversation`, so they aren't among the conversation's own events. No
  member is told.
- **The event:** the changes go in GM-only `:person/inner-changed` events, one per changed
  person, emitted right after the job's `:item/work-done` (which holds no inner state), the
  `:conversation/judged`, or the place's `:items/stewarded` (which holds none either):
  `{:person id :source {:job id} :changes [{:key k :from v :to v'}]}`, or `:source
  {:conversation id}` or `{:steward "1904-10-16"}` (`:to` nil when cleared). Its reducer sets `:inner` and `:inner-at` (the event's round). The person gets no
  line in their event window; the transcript reads `Lady Ashby, inside (job job-12): mood
  "pleased with Dawson's work"; body cleared.`
- **Mock runs:** `item-judge/mock-judge` leaves inner state alone. A scenario's `:mock-judge`
  may return `:inner` in its args (the dressing fixture's does). The conversation judge
  (`role :conversation-judge`, with `:conversation {:id :initiator :members}` and `:lines`, the
  line events, in its context) goes to the scenario's `:mock-conversation-judge`, or else
  `conversation-judge/mock-judge`, which changes nothing; `conversation-judge/heard-by` gives
  the members who heard a line. The default mock steward changes no inner state either (see
  The nightly steward).
- **What to tell agents,** in the brief or rules, in this sense (Claudeworth's `rules` has
  it): *Under `you`, `inner` says how you are inside now: your spirits, how your body is, what's
  on your mind. Nobody else can see it unless you show or say so. It changes with what you do,
  what's said to you and how you sleep. Let it colour what you do and say, as anyone's would.*
  Don't invite them to manage or improve it.

### Physical status (`manor.mechanics.physical`)

People's bodies can be hindered, and people can die (docs/physical-status-design.md). Every
scenario has it; nothing is set unless a world file, the operator or a judge sets it.

```clojure
;; on an agent
:physical {:movement      {:level :disabled :cause "bound hand and foot" :since 31}
           :speech        {:level :impeded  :cause "gagged with a napkin" :since 31}
           :consciousness {:level :disabled :cause "struck on the head" :since 31 :until 35}}
:impediments {:hand "a cut finger, bound in a rag"}
:status :dead :died {:round 40 :cause "..."}
```

- **Faculties and levels** (`physical/faculties`): movement, grasp and speech may be `:impeded`
  or `:disabled`; sight, hearing and consciousness only `:disabled` (unconscious). No entry is
  normal. `:cause` is a short phrase shown beside the level, never read by code; `:since` is the
  round it was set; `:until` (consciousness only) the round they come to. While unconscious,
  movement, grasp and speech count as disabled (`physical/level`, `physical/can?`) without
  their entries changing.
- **Impediments** are a subcategory of a person's aspects: keyed phrases, keys and values as
  aspects', at most 3 (`items/Impediments`), separate from the 4 aspects.
- **Death** is `:status :dead` (`manor.world/dead?`). The dead aren't active, so they drop out of
  turns, `present`, conversations, whispers, hibernation and the steward; they stay at their
  `:location`, keep what they hold, and others see them.
- **What code enforces now:** movement disabled: no `walk`, `travel` or `move`, and hibernation
  leaves them alone (routines don't move them); impeded: at most 3 walks a turn, no `travel`, no
  `move` along a route with travel above 0 (`physical/movement-reason`). Grasp disabled: no
  `take`, `give`, `put-in` or `open`; `put-down` is allowed and nothing is dropped. `take` also
  takes what's held by someone here who is dead, unconscious or grasp-disabled
  (`physical/helpless?`), even out of something they carry; the `:item/taken` then has
  `:from-person`. Unconscious: no turns, `rouse` and conversations refuse them, any occupation
  ends, and at the start of the `:until` round the engine emits `:person/came-to`, beside
  releases (they then get a turn before any routine). Death ends any occupation too. Either way
  a job in progress ends early and is judged on the time worked, as when a worker stops.
  The reasons repeat the status: "your grasp is disabled (wrists tied), so you can't open things".
- **Speech:** impeded, `say`, `whisper`, `call` and conversation lines still take text, but
  `physical/spoken` gives the words (marked `:muffled`, narrated "(only muffled noises come
  out)") only to the speaker (the GM sees every event), and the rest of the audience a
  `:person/muffled {:sound :at? :to? :conversation?}` instead (`:sound` `:speech`, `:whisper` or
  `:call`: "Crane makes muffled noises, as if trying to speak"). A muffled whisper's noise reaches
  only its target, and a muffled `call` is heard only in the room. In a conversation the
  conversation judge sees the muffled line, not the words. Use `spoken` for any new speech
  action. Disabled: `say`, `whisper`, `call` and `converse` aren't offered (`:available?`) and
  refuse ("your speech is disabled (gagged), so you can't speak"); they can still be drawn into
  a conversation, but their speaking turns offer only `stay-silent` and `leave`, and the
  prompt says they can't speak.
- **Hearing** disabled: the perception hook keeps speech and muffled noises from them; they
  can't start a conversation, be drawn into one or be whispered to ("Hale can't hear you
  (deafened)", `physical/hearing-reason`); a named `call` doesn't interrupt them. They can
  still speak.
- **Sight** disabled: seen events pass them by unless they're the actor or it was done to them.
  Their view (and the `walk` reply) keeps only the place's id and name, what they carry, the
  exits and `last-seen`, with `:cant-see` in place of the people and things here
  (`physical/view-by-sight`, applied by `prompt/agent-view`), and they don't record anyone in
  `last-seen` as they part. `look` and `open` refuse; reasons don't list the items or people
  here. `find` is allowed and its judge gets `:sight "can't see (...): searching by touch, only
  within reach"`; `take` works for an id they name.
- **Who sees it:** everyone present who can see, beside aspects in `people-here` and `look`:
  `physical: {"movement": "disabled: bound hand and foot", "consciousness": "unconscious: struck
  on the head"}`, `impediments: {...}`, and `dead: true` for the dead (with their name,
  appearance, aspects and what they carry). The person sees their own under `:you :physical`
  and `:you :impediments`. The GM's default view has them too.
- **Events:** `:person/physical-changed {:person :changes [{:faculty :level :cause? :until?}]}`
  (`:level :normal` clears; knocking out ends the occupation), `:person/impediments-changed
  {:person :changes [{:key :from :to}]}` (`:to` nil removes), `:person/died {:person :cause?}`
  and `:person/came-to {:person}`, each with the person as `:actor`, seen by everyone at their
  place who can see. Build them with `manor.mechanics.physical.changes`: `change-events` (world,
  id, `{:physical [{:faculty :level :cause :minutes}] :impediments [{:key :value}] :died cause}`,
  by) also interrupts the person if they're busy (asleep, working, hibernating), so they get a
  turn to decide whether to stop, except when the change knocks them out or kills them;
  `interrupts` does only that, for changes made by other events; `problem` checks a change.
  `:minutes` (consciousness) become `:until` through the clock (at least one round).
- **World files and `:init` agents** may start someone with `:physical` (`{:movement {:level
  :disabled :cause "..."}}`, and for consciousness optionally `:minutes` until they come to),
  `:impediments`, or `:status :dead` with an optional `:died {:cause "..."}`.
  `worldfile/problems` (and the engine, for `:init` agents) checks their shape. The exported
  `map.edn` writes the live levels and causes, impediments and deaths back.
- **The operator** sets and clears them with `manor.engine/afflict!` (and `afflict!` at the
  REPL): `{:faculty :movement :level :disabled :cause "..."}`, `{:faculty :consciousness :level
  :disabled :minutes 60}`, `{:impediment :hand :value "a cut finger"}` (`""` or no value removes),
  `{:died "a fall"}`, with an optional `:by` for whoever interrupts them (default: themselves).
- **Judges:** work and immediate `act-on` may set or clear faculties and impediments, or kill
  judged people. The steward may clear or ease living people's existing problems overnight,
  never set or worsen them. `physical/judge-view` gives each faculty's `level`, optional
  `cause`, and `set_at` from `:since`, as a short time (or round without a clock); agent views
  keep the phrases above. `:mock-judge` and `:mock-steward` may return the same physical fields
  as their real counterparts. Neither default mock changes bodies.
- **What to tell agents:** `physical/rules`, plus guidance on `act-on` in scenarios offering it
  (as in Claudeworth's rules).

### World files (`manor.io.worldfile`)

A scenario can keep its starting world in an EDN file instead of code: locations, routes, the
clock, starting agents, and any sections of its own. Put it under `resources/worlds/` and load it
in `:init`:

```clojure
:init (fn [opts]
        (assoc (manor.io.worldfile/load-world (or (:world-file opts) "worlds/my-world.edn"))
               :state {...}))
```

```clojure
{:meta {:name "Hamlet" :start "1904-10-15T21:00" :minutes-per-round 30}   ; :start is optional
 :locations [{:id "hall" :name "Hall" :desc "A small hall."
              :detail "Coats on pegs and a worn rug."  ; optional, shown only by look (see Items)
              :aspects {:cleanliness "muddy"}         ; optional (see Locations)
              :connects-to ["bedroom" "porch"]      ; next door (:travel 0); either side is enough
              :domain :house :kind :hall}           ; other keys are kept on the location
             ...]
 :routes [["porch" "green" 1]]                     ; journeys, as in :space
 :agents [{:name "Ada" :role :resident :location "hall"
           :aspects {:hands "inky"}                 ; optional (see People's aspects)
           :inner {:mind "the unanswered letter"}   ; optional (see Inner state)
           :away? true                            ; optional: away from the start (below)
           :physical {:grasp {:level :impeded :cause "a sprained wrist"}}}  ; optional (see Physical status),
          {:name "Cal" :role :resident :status :dead :died {:cause "a fall"}}] ; as are :impediments, :status :dead
 :items [{:id "pipe" :name "Pipe" :holder "Ben" :owner "Ben"   ; optional, see Items
          :aspects {:state "still warm"} :desc "A briar pipe."}]
 :visits [{:who "ada" :date "1904-10-20" :time "14:00" :stay "a week"}]   ; optional (below)
 :shop {...}}                                       ; your own sections, from :world-file
```

- `load-world` takes a file path or a classpath resource name. It checks the file and throws a
  list of readable problems (unknown locations, duplicate ids, bad routes, bad clock, bad items,
  bad agent aspects or inner state). The file must hold exactly one form, the world map: a
  second form after it is refused rather than ignored, because `edn/read-string` would otherwise
  silently drop whole sections (a missing `]` once left `:items` and `:agents` out of a file
  that appeared to load).
  It returns `{:space :clock :agents :items :calendar :visits :world-file}` (each of the middle
  ones only if the file has it). Read your own sections from `:world-file`.
- An agent with `:away? true` (a boolean, checked) is in the run but **away from the start**: the
  engine spawns them like everyone else at initialization (post, bedroom, situation, held items
  and all), then emits `:people/departed` at once, all of it `:visible-to :system`, so nobody sees
  them come or go. They have no place and no turns until something brings them back with
  `:people/returned` (a visit, see Visits). Unlike `:leave-out`, which keeps a character out of
  the run altogether, an away character is known and can be invited home (the medium
  household's son, at school).
- `:visits` (optional) are **authored visits**, made when a fresh run starts (see Visits): each
  names its guest in `:who` and its `:known-to` by **character id**, as calendar audiences do.
- `:items` (optional) lists items as in Items, one per line, with `:holder`, an agent `:owner`
  and a `:waiting` marker's `:for` and `:keeper` given by agent **name**, as `:agents` does; loading turns those into ids (other owners, such as
  `"household"`, are kept). Each item is checked: its fields and lengths, a unique id, exactly one
  of `:place` (a location of the file) and `:holder` (an agent of the file). A scenario that leaves
  out one of the file's agents should leave out the items that agent holds (Claudeworth's
  `:leave-out` does). A location's `:aspects` are checked the same way
  ("location \"hall\": {:aspects {:hearth [\"should be at least 1 character\"]}}").
- `:domain` groups locations into regions on the operator scene at `/` (house, village, ...).
  The existing control panel and its `?as=` feed filter remain at `/panel`; `/agent/<id>` still
  uses the unchanged agent-view projection. The scene is omniscient, presentation-only, and works
  without space. It derives generic frozen placement from existing location/route history, and
  shows current directed routes without relocating rooms. No scenario fields or events are needed.
  Linked person/place/item inspectors are also omniscient: they include current private inner state,
  closed-container contents and recorded model reasoning, never additional model calls. Recently
  reads existing `llm.edn` and event evidence on live or loaded runs, distinguishing requested tools
  from eventual outcomes. These surfaces are not safe agent-view endpoints. No authoring fields
  are needed; [scene-web-api.md](scene-web-api.md) documents APIs, limits and client checks.
  Optional pure `:scene` callbacks supply scenario-specific overlays and historical construction
  reservations; see the [`:scene` contract](#scene-operator-presentation-only). Do not add simulation
  fields, events or prompt content for the operator UI. Base-comparison and read-only regression
  checks are documented in [scene-web-api.md](scene-web-api.md#integration-and-simulation-boundary-checks-phase-5).
- For an offline automatic room-plan preview, use `(scene-preview! "resources/worlds/my-world.edn"
  "/tmp/my-world.svg")` in the dev REPL. It writes an SVG without a run, server or model call.
  This is presentation-only: it needs no new simulation fields or events. The pure layout contract
  and its limits are in [scene-layout-api.md](scene-layout-api.md). This file-only preview does not
  run scenario hooks or reconstruct construction history; the live scene uses existing run records.
- The file is a **template**: no run writes to it (only the REPL's `roll-cast!` does, see below).
  The engine saves a copy as
  `<run-dir>/world-start.edn`. Whenever the map changes, it writes the live map back in the same
  format to `<run-dir>/map.edn` (output only), and the live items with it (holders and owners as
  names again) whenever an item changes hands, and the file's agents with their live `:aspects`
  and `:inner` (people who came during the run aren't added). A run can start from an exported map
  (`{:world-file "runs/<id>/map.edn"}`), and `(export-map! path)` at the REPL writes one anywhere.

A Claudeworth-derived world with an architect's office names the architect's price list in
`:meta :architect-prices`: a file or classpath resource such as `"architect/prices-1904.edn"`
(`resources/architect/`), `{:sizes [{:size "small" :price 200 :build-days 1} ...]}` with prices in
whole pounds. `:init` reads it into `:state :architect-prices`; `price-list` answers from it, the
rules list its sizes, `draw-plans` accepts only those sizes, and each draft sends it to the
architect server. A world without an office needs none.

Claudeworth-derived worlds may omit architect services altogether (as Lodge does). If an
architect service exists, the existing check still requires at least one with opening hours;
all supplied hours are checked. With no architect service, the shared brief omits building-only
instructions. Exclude the five architecture actions in that scenario as well. Only an agent whose position
has the `:architect` capability is offered `draw-plans`, and draws only for a client with them
at the open office. Each starting
character still needs a bedroom, including living-out shopkeepers; the empty maid's room must
remain unassigned if it is to show as free in the household view.

#### People and traits

An agent in a world file may carry a person's traits. They're hidden from everyone but that
person; no other agent's view or prompt contains them. The optional `:appearance` is public:
one or two third-person sentences describing what a stranger sees at a glance (age, build, face,
dress, bearing, spectacles, a squint, red hands or a limp). It is shown wherever the person
is: do not place them at a location or describe a passing action. It must not include the person's name,
trait labels, character judgements or secrets: no temperament or faculty labels, "drinks",
"honest" or "owes money". Aim for about 400 characters; up to 600 are accepted.

Other agents see it in `location.people-here`, the `walk` reply and `look`'s people. The GM's
view lists it with the persona; hidden traits and manner remain excluded as before. The person's
own prompt's "Who you are" is laid out like this (absent optional fields add nothing):

```
## Who you are
You are Daisy (id: daisy), the scullery maid. People address you as Daisy.

<persona, in the second person>

How strangers see you: <manner, in the second person>

What people see: <appearance, in the third person>

### Your nature (known only to you)
Temperament:
- Openness: average (curiosity, imagination, willingness to entertain new ideas)
- ... all five
Faculties:
- Alimentiveness: large (appetite for food)
- ... all seven
Circumstances:
- a child put out to nurse

## Your situation
...
```

The position comes from `[:state :positions]`, and the address line only when the run shows forms
of address (see "Positions, posts and situations"). Each term's meaning is read from the people
server's own data files (`:measures` in `temperament.edn`, `:means` in `faculties.edn`), as static
data, so there's one source and no call.

```clojure
{:name "Hale" :sex :male :role :secretary :location "secretarys-room"
 :persona "You are twenty-six, neat to the point of fussiness ..."   ; polished: the traits made concrete
 :manner "You seem eager and tidy ..."                   ; private guidance, at most 400
  :appearance "A young man in a neat dark suit and spectacles, standing very straight." ; public, at most 600
 :temperament {:openness :high :conscientiousness :average :extraversion :low
               :agreeableness :high :neuroticism :average}   ; the Big Five: :low, :average or :high
 :faculties {"Secretiveness" :large "Cautiousness" :small "Veneration" :large "Hearing" :small
             "Form" :large "Number" :moderate "Causality" :small}  ; one per group: :small, :moderate, :large
 :circumstances ["Your widowed mother lives in another parish and expects your letters."]} ; one or two written facts
```

- `load-world` checks these keys (the five axes and their levels, the sizes, `:sex` `:male` or
  `:female`, and appearance's nonempty string and 600-character limit). Loading never calls the
  people server or a model: the file holds the result.
- There is no `:sketch` input. Old descriptions are never used to regenerate a cast;
  a successful re-roll also removes a legacy `:sketch` if one is present.
- The basic traits come from the **people server** (`mcp-servers/people/`, babashka,
  stateless, no model, no key). `generate_people {position count area setting seed taken-names sex ages candidate}`
  rolls identity and readings from `temperament.edn`, `faculties.edn` and `names.edn`:
  a name sharing no word with `taken-names`, requested sex (else either), age within `ages`
  (else 18–55), origin, home, five temperament levels and one faculty from each of seven groups.
  Readings carry only names, values and short definitions (`:measures`/`:means`).
  There are **no behavioural descriptors or circumstance templates**. Levels, sizes and
  faculty-selection distributions are unchanged; removing descriptor draws changes exact
  readings for old seeds. The engine passes age/sex defaults from the position catalogue.
  The optional JSON/MCP `candidate` boolean opts potential hires into a separate
  `:candidate-home` string **instead of** `:lives`: a dwelling and nearby off-map neighbourhood
  from the server's data, never the internal `area` id. `:origin` retains its existing meaning
  and generation. Other visitors, couriers and cast rolls do not opt in; their home handling
  is unchanged. The candidate's home is generated once, retained in their base/pool record,
  and passed to the private writer for consistency. It is background, not a new map location.
- The **character writer**, still called the **polish** (`manor.mechanics.people/polish!`), returns
  `{circumstances persona manner appearance}` in one checked model call. It receives only
  setting, position, basic identity/readings and optional authored notes; legacy descriptors,
  circumstances and prose are stripped from its record and mock context.
  Circumstances are **one or two external personal facts**, inspired by the whole record
  including role, not dispositions or enduring habits (which belong in persona/manner).
  Ordinary lives are allowed. Off-stage relatives and past employment may be invented;
  connections to existing named characters, present jobs/authority/capabilities, money,
  items, property and actionable assets may not. Background prose grants no engine state.
- Persona/manner address the person in the second person; public appearance uses the third.
  The prompt treats basic readings as actual characteristics, not theories to discount.
  Raw levels/sizes and definitions remain in the private prompt under **Faculties** alongside
  generated prose. Clearer period-appropriate wording of readings is deferred, not a reason
  to replace the underlying data with stock habits.
- Validation checks structure only: one/two nonblank circumstance strings (at most 480
  characters each), persona advised 900/accepted 1200, manner at most 400, appearance advised
  400/accepted 600, all nonblank. One correction is allowed. No phrase/regex checks judge
  prose. Basic readings stay from the server; written circumstances are stored with them.
  `mock-polish` is a free deterministic fixture, not another production generator.
- `reputation {person knower relation seed}` takes the completed record and selects known
  basic readings and some **already-written** circumstances according to relation. No new
  behavioural descriptions are supplied: the recommending agent interprets the readings.
  The acquaintance line still comes from the deterministic relationship service.
  Generation/reputation selection are seeded; model prose need not repeat for the same seed.
- `(roll-cast! "worlds/claudeworth.edn")` at the REPL (`clj -M:dev`;
  `{:llm :mock}` for free) writes the file in place, comments/layout kept. Names, sex, roles,
  situations, bedrooms, routines and other setup stay. Persona, manner, appearance,
  temperament, faculties and circumstances are replaced together on success; failed/unselected
  characters stay unchanged. Age/origin are newly generated; generated home is omitted because
  housing is authored. Old persona, manner, appearance, traits and circumstances never feed
  back; the agent's `:situation` does, as the polish's notes, so the person written fits what
  the scenario says about them (the polish's instructions say to keep the authored identity
  and directions and take character from the new traits). Successful rolls remove legacy
  sketches. Since the age comes from the position's `:ages`, set that range where the
  character's age matters; narrowing it also narrows who may be hired into the position. A fresh seed is returned and saved in
  `roll-cast.edn`; an explicit `:seed` reproduces basic readings, not necessarily model prose.
  The default model/settings are the runs' (`deepseek/deepseek-v4.1-flash`, manor key).
  `{:only ["Hobbs"]}` selects characters. The REPL helper defaults to `:max-cost 0.20`,
  checked before each model call across the cast, including corrections. An in-flight call
  may cross the threshold, as in engine runs. Logs and all calls live under
  `test-runs/roll-cast-<time>/`; preserve paid records under `old-test-runs/`.
- Loading, branching and replay never read world files: the starting map is recorded as events.

#### Runtime candidates and hiring

Claudeworth and Lodge include `manor.mechanics.hiring`. A derivative scenario with another entrance
should call `(hiring/install scenario "entrance-id")`: it sets `:meeting-place`, initial
state's meeting place/cap, the visit system and the writing-job extension. A new scenario
must also include `hiring/actions`, `hiring/adjudicators`, `hiring/bridges`,
`hiring/offline-stubs`, `people/mcp-servers`, `positions/starting-events` as `:on-spawn`,
and project `hiring/observe` into its own observation (Claudeworth uses `:hiring`).
Its positions must include `:none`; its meeting place must exist. Include `hiring/rules` in
character briefs (Claudeworth and Lodge already do, for turns and conversations). These rules
exclude recruiting established characters already living or working in the same village until
profession changes and filling their vacated village jobs are supported. This is prompt guidance,
not a prose validator. A new candidate may still live locally: characters may describe local
acquaintances, but `recommend` supplies the candidate's name and details before anyone is named
or promised. It does not select an existing villager.

- `recommend {position for}`: one query, one candidate. Both parties must be present
  and distinct; self-recommendation is rejected with directions to ask someone else.
  Claudeworth also offers it on a speaking turn, where `for` must be in the conversation.
  Reuse a pooled/away person seeking that position in the same walking area, else generate
  one through the people server. The raw record is kept in `[:state :pool]`, never in a
  character's observation. The pool limit is 40, FIFO among pooled/away records not owned
  by active agents; sent-for/here/engaged records cannot be evicted. A fully protected pool
  refuses new recommendations. Known names, candidate ids, positions and `send-for`
  instructions go only to the two parties. The sampled reputation belongs only to the
  recommender, who then chooses what to say. `:none`, `:gm` and `:guest` (a visitor, see Visits)
  aren't posts: `recommend` and `engage` refuse them ("guest is not a post"), and
  `hiring/reference` doesn't list them.
- Runtime appearance uses `people/appearance-request`: **only** age, sex, position and small
  sense names reach it, including mock context. It never sees private readings, circumstances or prose.
  The private polish sees the approved appearance for consistency; its own appearance output
  is discarded. These calls use the run's default model and normal cost budget, with checked
  lengths/blanks and one correction, never lexical prose checks. Offline bridge fixtures and
  mock adjudicators keep `--mock --offline` entirely free.
- `send-for {candidate}` is final. Only someone who knows the current pool record may call it.
  Arrival is next round at the meeting place, before turns, if the active-agent cap has room
  (otherwise it waits). The visitor is spawned through the scenario hook with role `:none`,
  own traits and a private situation naming their summoner. Their model inherits `:model`.
  Private spawn data is operator-only; a separate arrival event goes to those at the entrance
  and the summoner. No GM-spawnable positions are added. Their situation tells them to remain
  locally available while a decision is pending, agree a waiting place, and distinguish
  walking to the village/pub from abandoning the application with `take-leave`.
  `hiring/observe` includes `:candidate-home` only for known candidates; the applicant's own
  situation names their home and explicitly says it is off-map, not a walking destination.
- `engage {who position terms? bedroom?}` is free (it doesn't end the turn) and replaces any previous offer. Anyone
  may offer a present candidate any position that is a post; no authority, sex or vacancy restriction.
  `who` is the arrived agent id, not the pool id. Walking to the candidate and interviewing
  with `converse` makes `engage` available on the next model step of the same turn.
  `accept-place {conditions?}` and `decline-place {reason?}` are free too. Discuss a new offer to haggle.
  Claudeworth also offers all three on speaking turns (`engage` only to someone in the
  conversation), so the offer and the answer can be made as the bargain is agreed; the hiring
  rules tell characters that saying they'll take someone on engages nobody. Acceptance changes role/post,
  marks the pool entry engaged, records employer, terms and conditions, and optionally assigns
  the named bedroom. A bedroom must exist and be a bedroom, but occupancy and the need for a
  bed are not enforced. No wages or conditions are mechanically enforced. Both parties can
  see the agreed post later; a candidate's temporary situation becomes their appointment.
- `send-away {who}` and the candidate's `take-leave` are final and end an unengaged visit.
  `take-leave {return-date? return-time?}` may say when the candidate will be back, both or
  neither, later than now and within `hiring/max-return-days` (7), and only in a world with a
  clock. They go home off-map as usual, and the pool entry becomes `:returning` with its
  `:arrival` round and `:return-at`; the tick brings them to the meeting place then, through the
  same arrival as `send-for` (their situation says they've come back as they said), and the
  24-hour wait starts again. The departure narration says when they mean to be back. With a
  `:calendar`, hiring adds managed entries (owner `:hiring`, ids `hiring-<candidate>-return-…`):
  one for the candidate and one for everyone who knows them and their summoner. `send-for` still
  works on a returning candidate, bringing them next round and cancelling those entries.
  Otherwise, automatic departure is after **24 in-world hours** since arrival or the last
  conversation the candidate participated in, whichever is later. `candidate-wait-rounds`
  rounds this up using the world's clock, so it never cuts the day short. Clockless worlds
  use 24 rounds. Crossing midnight does not itself cause departure. Walking around, waiting
  or working locally does not change their pool status from `:here`; they can be interviewed
  and hired wherever they are present. Engaged staff are not timed out.
  A candidate departs towards their `:candidate-home`, with `:off-map? true` making the narration
  explicit about leaving the map. For legacy records without that field, it says "home"
  off-map, not the old ungrounded `:lives` address. Recorded events are not rewritten.
  `:people/departed {id reason towards off-map?}` makes them `:away`,
  clears location/travel/occupation and stops turns/calls; their notes and history survive,
  and everyone in the place they left remembers parting there (`:seen`).
  The offer closes and the pool entry becomes `:away`. Another `send-for` uses
  `:people/returned {id at}` to restore the same agent, not a duplicate. Retired agents never
  return. Engaged staff have no visitor timeout; dismissal and outdoor lodging are deferred.
- **Shared with deliveries and visits.** `hiring/arrival scenario world at {:id | :spawn,
  :situation?, :also?, :visible-to?}` is the one arrival at the meeting place: an away agent
  returns there (`:people/returned`, seen by those there, themselves and the ids in `:also`, or by
  `:visible-to`), with their situation replaced if given; with no `:id`, a new agent is spawned
  there from `:spawn`, its events `:system`. It returns `{:id :events}`; `hiring/room-for-arrival?`
  is the agent cap it waits on. The people writer takes options for people made to order:
  `person-record`'s `:sex` and `:ages` replace the position's, and `(write-person services p
  notes)` passes `notes` to the polish (visits' new guests). `send-for` refuses someone who is
  about already (a former candidate visiting as a guest).
- A written reference is ordinary work: `spend-time {doing hours? candidate?}` may name a known
  candidate. Omit `candidate` for other activities; an empty string is treated as absent.
  Its job snapshots only the writer's known identity/reputation into `:reference`,
  which reaches the scene judge. All items made by that job carry `:candidate-id`, shown in
  detailed item views and preserved in export/replay. Give the paper with the ordinary `give`
  action. The link does not grant its reader a recommendation or verify the prose.

#### Setting packs and prompt templates

The household scenarios name a classpath directory in `:meta :setting-pack`, currently
`"settings/england-1904"`. Omitting it defaults to England, including old runs without a stored
pack. A pack contains:

- `setting.edn`: prompt parameters (`:era`, `:home`, `:household-name`, `:everyday-things`,
  `:measures`, `:invite-example`, `:known-as-example`), `:clock {:label "EEEE d MMMM yyyy, HH:mm"}`
  and `:courier-position` (England `:errand-boy`, overridable by a shop's `:courier`).
  `:authored-money` temporarily supplies architect docs' “whole pounds (£)”; currency mechanics
  themselves are still sterling, not configurable yet.
- `positions.edn`: the default positions catalogue; `calendar.edn`: explicitly dated holidays.
- `prompts/money.md` and `prompts/invitation-examples.md`: setting-specific prose used by the
  shopping rules/appraiser and invitation judge. Any other markdown file under `prompts/` may
  override the shared template with the same relative path, e.g. `prompts/judges/find-person.md`.

Claudeworth's base `init` loads all this through `manor.settings/for-worldfile` and records it in
`[:state :setting-pack]`. Fragment/override text is snapshotted, so editing pack files does not
change an existing run. `settings/pack` reads that snapshot (England for a pre-pack state), and
`(settings/parameter world :home)` or `(settings/parameter world :clock :label)` reads a parameter.
The loaded pack's `:positions`, `:calendar`, `:source` and `:prompts` are not rendering parameters.

`(manor.prompts/render world "path.md" params)` looks in the pack first, then
`resources/prompts/path.md`, and fills `{{key}}` or dotted paths from the setting parameters with
explicit `params` winning. Missing values throw; no conditionals or loops in templates. Code picks
fragments. Only shared files are cached. Instructions and rules are rendered for each request.
An action's `:doc` may be a fixed string or a function of the world; `(prompts/doc "docs/x.md"
params)` constructs one, `(actions/description action world)` resolves it, and `(actions/->tool
action world)` builds its model-facing tool, including kind/limit hints. Pass the world in model
requests and human-seat panel forms. Shorter renderer/tool arities use the default setting for
old callers. `clock/world-label` applies the pack's clock label. Dated tool docs use
`prompts/example-date`: the run's start date, or `1970-01-01` without a clock, solely as a format
example. Judge briefs include architect/delivery paragraphs only with the corresponding places;
visits' post option is described only with a post office.

#### Positions, posts and situations

Who someone is (their persona and traits) is kept apart from the job they hold (their position)
and from their circumstances in this world (their situation). The code is `manor.mechanics.positions`.

**Positions** come from a catalogue shared between worlds, `resources/settings/england-1904/positions.edn`
(country-house and village positions in 1904, plus `:none`). By default the catalogue comes from
the world's setting pack. An explicit `:meta :positions` path overrides it. A world may add or
override entries in its own `:roles`, merged key by key over the chosen catalogue:

```clojure
{:meta {:positions "settings/england-1904/positions.edn" ...}
 :roles {:secretary {:workplace ["study"]}                ; adds a key to the catalogue's entry
         :ferryman {:title "Ferryman" :standing :villager :sleeps :out
                    :about "You row people across the river."}   ; a new position
         :gm {:about "..."}}}                             ; the GM's own text, as before
```

An entry:

```clojure
:housemaid {:title "Housemaid"
            :about "You are in service to the family. Your day is housework: ..."
            :standing :servant      ; :family :upper-servant :servant :outdoor :tradesman :professional :villager :visitor
            :sleeps :in-house       ; or :out
            :workplace ["kitchen"]  ; optional location ids; best given in the world file
            :capabilities #{:shop-clerk}   ; optional: what code treats the holder as
            :addressed-as "{first-name}"   ; optional; or {:male "Mr {surname}" :female "Mrs {surname}"}
            :ages [15 30] :sex :female}    ; what the people server rolls for a newcomer
```

- `:about` is the duties only: no names, personality, situation or forms of address. Write it in
  the second person, as a brief. Standing directions (what a butcher sells, whom the cook consults)
  belong here, because the polish rewrites personas and would lose them.
- The scenario keeps the merged table in `[:state :positions]`. Claudeworth's `problems` checks
  every entry (`positions/entry-problems`), that every agent's role is a position with an
  `:about`, and that workplaces are locations.
- Claudeworth's `agent-brief` begins "Your position: Private secretary. You live in. You work at:
  Study (study)." and then gives the `:about`, the setting and the rules. `:standing` decides
  who starts in the household (`positions/household-standings`). `:visitor` is the one standing
  outside every household: the catalogue's `:guest` (someone from elsewhere, see Visits), which
  has no `:sleeps` because they live elsewhere.
- `:capabilities` are what code and tools go by (see `:needs` under `:actions`): `:shop-clerk`
  (quote goods at a shop) and `:architect` (draw plans for clients, earn commission fees). The
  butcher, shopkeeper, draper, publican and postmistress are separate positions with the same
  counter capability.

**Forms of address** are behind the run option `:addressed-as?` (default true; CLI
`--no-address` turns it off), so runs with and without them can be compared. When it's on, an
agent's own prompt says "People address you as my lord." and the view of the people present gives
each one's `:address-as`. `{first-name}` and `{surname}` are the first and last words of their
name, titles aside. When it's off, `positions/table` drops every `:addressed-as`, so no form of
address appears anywhere; keep them out of `:about` texts and situations for the same reason (name
people as the world names them: "Pugh", not "Mr Pugh").

**Posts.** An agent's `:role` is the id of the position they hold. `[:state :posts]` says more:
`{"nellie" {:position :housemaid :household "household" :since 0 :terms nil}}`. One event,
`:people/post-changed`, sets both, so the brief and the tools follow the post; nothing checks who
holds what (two cooks, or none, are fine). `(change-post! "nellie" :cook {:terms "..."})` at the
REPL (`manor.engine/change-post!`) changes a post between rounds; `:household` defaults to their
present post's, and they see the change. Hiring's `accept-place` also changes a post and
records optional `:employer` and `:conditions`; there is no general in-game post-changing action.

**Bedrooms and routines** are kept by person in `[:state :bedrooms]` and `[:state :routines]`,
set by `:people/bedroom-assigned` and `:people/routine-set`. Claudeworth's `:on-spawn` sets an
agent's post, bedroom and routine from their spawn spec, so `(spawn! {:role :footman :name "James
Ward" :location "entrance-hall" :bedroom "maids-room"})` gets the footman's brief, a post in the
household and a bed. Members of the household see the house's bedrooms (`:kind :bedroom`, `:domain
:house`) and who sleeps in each under `household.bedrooms` in their view. The sleepers shown are
all active assigned sleepers, guests included: bedroom lists may reveal even secret visitors.
Invitation and calendar privacy is otherwise unchanged. The rules say someone who
lives in needs a bed; nothing enforces it, and sharing is the agents' call.

**Situations.** An agent's `:situation` in the world file is what is neither their job nor their
personality: the earl means to enlarge the house, and Pugh is the man to see; the cook has just
joined. Their own prompt shows it as "## Your situation", after "Who you are"; nobody else's does.
Neither the polish nor `roll-cast!` rewrites it.

### Time (`manor.clock`)

If `:init` returns a `:clock`, rounds have times. Round 1 starts at `:start`, and each round is
`:minutes-per-round` later.

- Prompts show the time instead of the round: the header ("# Saturday 15 October 1904, 09:30"),
  event and note lines (`[09:30]`, or `[Sun 07:00]` on another day), and the view's `:time`.
  Round start and end markers are left out of agent prompts; the GM still sees them.
- Helpers: `(clock/label c round)`, `(clock/short-label c round today)`,
  `(clock/round-at c round "07:00")` (the round when someone waiting until 07:00 acts again),
  `(clock/rounds-for c minutes)`, `(clock/rounds-per-day c)`, `(clock/hh-mm? s)`.
- The clock is in the world (`(:clock world)`), so actions and views can use it.
- The run option `:max-rounds` (CLI: `--max-rounds N`) pauses at that round, independently of
  the clock; use it with `:max-cost` for short real checks.
- The run option `:start` (CLI: `--start 1904-10-15T21:00`) replaces the clock's `:start` for one
  run and keeps its `:minutes-per-round`, so you can begin near what you want to test (bedtime,
  say). It's checked like the clock, and a scenario without a clock refuses it. Only the time
  changes: people start where the world file puts them (at 21:00 still in their 08:00 places), so
  a late start may need a mock script or world file that makes sense then.

### Calendar (`manor.mechanics.calendar`)

Opt in with scenario `:calendar {}` (default `:lookahead-days 30`, a non-negative integer).
It requires a simulation clock. Return a vector of entries in `:init`'s `:calendar`, or pass
through `worldfile/load-world`'s `:calendar` from a world file:

```clojure
[{:id "holiday-1904" :date "1904-11-11"
  :description "A holiday." :visible-to :all}
 {:id "visit-1904" :at "1904-10-21T14:00"
  :description "Mr Smith is expected."
  :visible-to #{"ada"} :gm-notes "Optional private guidance."}]
```

- IDs are unique lowercase letters/digits/underscore/hyphen identifiers, starting with a letter
  or digit. Cancelled IDs remain reserved. Exactly one of `:date` (ISO local date) or `:at`
  (ISO local datetime) is required. No recurrence, ranges, approximate dates or event payloads.
- `:description` is 1–600 characters; optional `:gm-notes` is at most 1200. Only structure is
  checked, not the meaning of prose. `:visible-to` defaults to `:gm`; other choices are `:all`
  or an explicit set of existing **character IDs**, not names or positions. Unlike item holders,
  calendar audiences are not converted to/from names in world files. Initial validation happens
  after cast creation, so the IDs must match those actually spawned (including collision suffixes).
- Public entries are known by later arrivals too. Restricted entries do not follow a post to its
  replacement. Conversations and notes may convey knowledge but never automatically edit calendars.
- Both turns and conversation requests put `## Your calendar` at the beginning of the user
  message, before recent events, below the stable system/tool prefix. It shows only permitted
  descriptions and absolute dates, with times only for `:at`. It is a schedule of known arrangements
  and expectations, **not confirmation they happened**. Never put raw calendar/export data in a
  character `:reference` or `:observe` projection: it includes private notes and audiences.
- The visible window is today through `:lookahead-days` after today, inclusive. Sorting is by
  date, date-only before timed entries, time and ID. With no edits its bytes stay unchanged all
  day. Today's entries remain until midnight even after their appointed time or GM review.
- After the existing pre-turn world systems, the engine queues pending entries whose review time
  has been reached or crossed and gives the GM **one batch before characters act**. Date-only
  entries use 05:00 internally; this is not a character-facing start time. Existing requests,
  periodic checks and scenario GM triggers still run after turns, independently of calendar review.
- Fresh-run initialization uses the final `:start` override and queues today's date-only entries
  at/after 05:00. Before then they wait. Earlier dates and already-past timed starting entries
  are historical, not a backlog. No model is called inside initialization. Load/branch uses saved
  lifecycle, not this startup exception.
- The GM has `add-calendar-entry`, `update-calendar-entry`, `cancel-calendar-entry`, all subject
  to normal argument decoding and GM action limits. Operators use `engine/add-calendar-entry!`,
  `engine/update-calendar-entry!` and `engine/cancel-calendar-entry!` (also REPL helpers), queued
  between rounds. Updates take an ID and changed authored fields; setting `:date` replaces `:at`
  and vice versa. Live creation/rescheduling rejects dates before today and past timed appointments.
  A date-only entry added today after 05:00 waits until the next pre-turn boundary.
- Description/note/audience edits preserve review status. Rescheduling to a future date/time makes
  an entry pending again and clears an old queue record. Cancellation hides it and removes due
  work. Revision-aware acknowledgements cannot overwrite edits/cancellation/rescheduling during
  review. New due work never recursively invokes the GM in that round.
- A completed intervention (including immediate `done` or reaching the GM action limit) reviews
  the batch once. Failed/forfeited review stays due and requests a pause. Disabled or cost-blocked
  GM review stays due. Due records are supplied directly, not through the recent-event window.
- All calendar mutation/review events are GM-only, even for public entries. They never announce
  anything to characters. Reducers are pure; calendar data lives under engine-owned `:calendar`
  (`:entries` by ID and revision-aware `:due`). Entries store `:lifecycle {:status ... :revision ...}`.
  Authors normally omit it; world-file export retains it to preserve reviewed/historical/cancelled
  and due state when imported. Calendar changes also refresh the run's live `map.edn`.
- Map export retains exact calendar audience IDs, but existing map export does **not** include
  newly spawned cast members. If an entry names one, supply that cast record before starting
  a new run from the map; otherwise initial validation rejects the missing ID. Nothing is silently
  dropped or made public. Saved **load/branch** is the full-fidelity continuation path.
- **Managed entries.** A mechanic may own entries: `calendar/managed-command-events world op
  args owner` adds, updates or cancels them, marking them `:managed-by owner` (engine-owned:
  `command-events` refuses it as an argument). They're **never queued for GM review**, and the
  GM's and operator's update/cancel refuse them ("that entry is managed by visits", followed by
  the owner's hint). Characters see them like any entry; the GM sees the marker. World-file export
  leaves them out, as their owner's records aren't exported. Visits (below) and hiring (a
  candidate's return, see Hiring) are the owners.
- **Owners name themselves** in the scenario's `:calendar` config, under `:managed`:
  `{:visits {:id-prefix "visit-" :hint "use cancel-visit"}}` (`visits/install` adds its own, and
  `hiring/install` adds `:hiring` with the prefix `hiring-`). It's
  recorded in `:calendar/initialized`. Nobody but the owner may add an entry whose id starts with
  its `:id-prefix` ("calendar IDs starting "visit-" are kept for visits; choose another ID"), so an
  owner's ids never clash with a GM's or operator's entry; the `:hint` ends the refusal of an edit.
  The calendar itself names no owner.
- Calendar review never credits money, spawns a visitor or guarantees an outcome. Those need
  separate supported actions. No scheduling schemas are added to work/conversation judges or
  the steward. Scenarios without `:calendar` retain their existing prompts and tools.

The bundled `claudeworth` and `claudeworth-medium` scenarios opt in and compose their base init
with `manor.scenarios.claudeworth.holidays/add-to`. It adds the loaded setting pack's explicitly dated
calendar entries (England: `resources/settings/england-1904/calendar.edn`) to the init result's
`:calendar`. A world-file
entry with a matching ID replaces the default completely (including audience and lifecycle);
other entries are retained and normal engine validation still applies. The base `claudeworth/init`
loads only the supplied world: worlds reusing the rules without the holiday seed, such as Lodge,
use that base init and omit scenario `:calendar`. The catalogue is an illustrative period list,
not an exhaustive almanac; it has no recurrence and must be extended by hand beyond 1905.

### Visits (`manor.mechanics.visits`)

[visits-design.md](visits-design.md), phases 1 to 3: guests **in the world**
(villagers, a candidate still about) invited to the house for a date and time, who make their own
way, and guests **from elsewhere** (away agents), who arrive at the meeting place at the time and
go with `take-leave`. Which kind a guest is, is decided when the visit falls due: are they active
or away? An inviter who describes someone (`invite`'s `person`) has them settled by the
invitation judge, which matches someone in the world or writes someone new.

- **Install** with `(visits/install scenario)` after `hiring/install` (it needs the meeting
  place). It adds `invite`, one `take-leave` for candidates and guests from elsewhere (in place of
  hiring's), the GM's `schedule-visit` and `cancel-visit`, the invitation judge (`:visits/invitee`
  in `:adjudicators`, with its mock), the `:visits/tick` system (after hiring's and deliveries'),
  and the config in `[:state :visit-config]`. It's idempotent:
  `claudeworth-medium` re-installs on `cw/scenario`. The scenario needs a clock, a `:guest`
  position for people made new (the catalogue has one), and `:calendar` for the guests' and hosts'
  entries (without it, visits still run but nobody's calendar shows them). Its `:observe` should
  call `(visits/add-to-view view world id)`, and its `:hibernate` should leave out anyone
  `visits/keeps-awake?`. Both Claudeworth scenarios install it, and Claudeworth's `rules` add the
  invitation paragraph (`visits/rules`) when the config is there.
- **Config:** `visits/default-config`, then `{:house <world file :meta :name>}`, then the
  scenario's `:visits`, then the run option `:visits`, merged in that order (`:lead-hours` key by
  key):

  ```clojure
  :visits {:lead-hours {:in-person 0 :messenger 1 :post 3}  ; shortest gap from sending to expected
           :from-away-hours 24  ; added to an invitation's lead time when the guest is away
           :wake-from "06:00"   ; a guest is kept in play from this time on the visit's day
           :lapse-hours 3       ; ... and is no longer expected this long after the time
           :house "Claudeworth Manor"}
  ```
- **The record** in `[:state :visits]`, by id (`"visit-1"`, …): `:guest` (an agent id), `:host`
  (nil when arranged by the GM or operator, or authored), `:by` (`:in-person`, `:post`, `:messenger`, `:gm`,
  `:operator`, `:authored`), `:via`, `:at` (the ISO time given), `:arrive` (its round), `:deliver` (the round the
  guest is told), `:wake` (the round from which they're kept in play), `:stay`, `:reason`,
  `:bedroom`, `:known-to` (arranged visits), `:told?`, `:reminded?`, `:arrived` (the round it
  became `:here`), `:from-away?` (they arrived at the meeting place) and `:status`: `:scheduled` →
  `:here` → `:ended`, or `:lapsed` / `:cancelled`. The GM sees it in the world state.
- **`invite {by, who | person, date time stay reason? via? bedroom?}`** is once a turn. With `who`
  (an id) it's free and calls nothing; with `person` (at most 300 characters, who they are in the
  inviter's words) it's a query, counted towards the turn's `:max-queries` (`:kind-for`; see
  Actions). Exactly one of the two. Its tool description and `visits/rules` tell inviters to give
  the person's name whenever they know it, with how they stand to them ("my father, Sir Henry
  Vane", not just "my father"); a description without a name is still accepted. Checks are
  structural, with reasons that say what to do: the guest exists, isn't you, the GM, dead, retired, or
  someone of the household who is at home (`positions/household-member?` and active: "they live
  here"; the son away at school can be invited home); `in-person` needs them here with you; `post`
  needs a place with the `:post-office` service (no hours check); `messenger` needs `via` here
  with you, anyone but yourself, the invitee included; `date`/`time` parse and are at least the
  channel's lead time from now, plus `:from-away-hours` when the guest is away ("by post, the
  earliest they can be here is Saturday 15 October, 23:00"); `bedroom`, if given, is a
  `:bedroom`. `who` isn't checked against whom the inviter knows. With `person`, the checks that
  need the guest (in person, the lead time from away, "they live here") wait for the judge. Its
  reply says who is expected when, or for someone described why nothing is arranged. Events: `:visits/scheduled` (GM-only, the record), the host's calendar entry at once, and
  one per channel: `:visits/invited` (in person, seen by both), `:visits/posted` ("posts a
  letter", seen by those present, no contents) or `:visits/entrusted` (the message, seen by the
  inviter and the messenger).
  In person needs speech and a conscious guest who can hear. With speech impeded, only muffled
  noise is emitted: no visit or calendar entries are made, and the reply says no invitation was
  arranged (with `person`, no judge is called). Disabled speech is refused before the query.
  Post and messenger retain their existing abstract message handling. In-person invitations are
  heard; posting and entrusting are sight-filtered for their existing audiences.
- **The invitation judge** (`manor.mechanics.visits.judge`, task `:visits/invitee`, tool
  `invitee`) is one checked narrow call, built like the find judge. Its request
  (`invitation-request`) holds the inviter's own context (name; age when known, their `:age` or
  their position's when its `:ages` are fixed; sex; position title; persona as `nature`;
  situation; last 15 notes; and their last ten narrated lines from the `recent` service), the
  description and `reason`, and `people` (`judge/people`), each person once: `your-guests` (the
  inviter's own guests, with situation and `known-as`), `known` (everyone active, the inviter's
  household away members, the away people they've met and the candidates recommended to them who
  have come: id, name, position title, where they live) and `others` (everyone else not retired:
  only id, name, `from` and public `known-as` (plus `dead: true` when applicable), so another inviter's guest is matched without
  anything of who invited them, when or why). It answers `{existing id}` (a listed id), `{new
  {name sex age from known-as situation}}` (`judge/new-person-fields`' limits; a listed person's
  name is refused, "answer existing") or `{impossible reason}` (≤ 300). A bad answer is sent back
  once; a second fails the query neutrally ("The enquiry failed; try again later.", an
  operator-only `:adjudication/failed`, no other events). Its instructions say to decide who is
  meant, never willingness, and to answer impossible only for physical impossibility (dead, or
  the inviter), never for unlikelihood, rank, distance, inconvenience, propriety or
  unwillingness; that's prompt guidance, and code checks only the answer's shape. `existing`
  invites that person (all of `invite`'s checks, on the world when the query's done); `new` is
  checked first (not in person, and the lead time from away), then written in the same query
  (`write-new-person`) and made an away agent (`away-guest-events`) before the visit is
  scheduled, all from the recorded result; `impossible` replies with the reason and schedules
  nothing. `judge/mock-judge` stands in under `--mock`: the inviter's own name is impossible, a
  listed person whose surname or `known-as` the description contains is them, anyone else is new.
- **Telling** is for guests in the world. In person, or when the invitee carries their own
  message, the guest knows at once. Otherwise at `:deliver` (now plus the lead time) the tick sends
  `:visits/delivered` to the guest alone, if they're about ("A letter comes from Lord Claudeworth:
  …", or by messenger "A message comes from Lord Claudeworth (sent with Hale): …", which doesn't
  say the messenger came) with their calendar entry. An away guest is told nothing.
- **Calendar entries** (`<visit-id>-host`, `<visit-id>-guest`) are written from the record and
  managed by visits, which keeps every id starting `visit-` for itself (see Calendar): the host
  side goes to the inviter only, or an arranged visit's `known-to` (default nobody); the guest
  side goes to a guest in the world from when they're told, saying where to come (the meeting
  place's id). Nobody else is told. The GM never reviews them.
- **The tick**, before turns, takes visits in id order, each step on the world the previous left:
  tell at `:deliver`; the first time a visit keeps a guest with a routine in play, free them if
  hibernating and send a private `:visits/reminded` ("You have an engagement: Claudeworth Manor at
  10:00 (Lord Claudeworth's invitation)."); a told guest in the meeting place's walking area
  (`space/area`) from an hour before the time is `:here`; an **away guest** at the time arrives at
  the meeting place (below); a `:here` guest from the village no longer in, or heading to, that
  area has `:ended` (a guest from elsewhere only once they're no longer active); a guest who is
  about and hasn't come `:lapse-hours` after the time, or is dead or retired, has `:lapsed`. An away guest
  never lapses: they wait to arrive. Status events are GM-only.
- **Arriving from elsewhere.** When an away guest's visit is due and the run is under its agent
  cap (`hiring/room-for-arrival?`; else they wait), and movement and consciousness permit
  (`hiring/can-arrive?`; disabled movement or unconsciousness waits, impeded movement does not),
  `hiring/arrival` brings the same agent back to
  the meeting place (`:people/returned`, `:system`) and the tick emits `:visits/arrived`, seen by
  those at the meeting place, the host wherever they are, and the guest: "A visitor arrives at the
  Carriage sweep: Mr Arden (mr-arden), a curate from Ely." (with their public `:known-as`), or for
  someone of the household "Master Claudeworth (master-claudeworth) arrives at the Carriage
  sweep." There's no house-wide bell. A named `bedroom` is theirs (`positions/bedroom-assigned`). A
  former candidate or courier (known by `hiring/for-agent` or `[:state :couriers]`) gets a short
  situation from their record in place of hiring's or deliveries' ("You are Pat Ellis, of a
  cottage in Ely. You came to Claudeworth Manor once before, about the cook place." or "…; you
  carry for the General store."). A former candidate whose pool record was evicted (role `:none`,
  no record) gets "You are Pat Ellis. You have been to Claudeworth Manor before."; anyone else
  keeps theirs. A courier staying as a guest isn't given deliveries' courier situation when a
  delivery comes. A guest in the world who goes away
  before the time arrives this way too. Another visit of theirs falling due while they're here
  from elsewhere (two due in the same round, say) is `:here` at once, with no arrival and no
  letter, and its named bedroom is theirs; they aren't told of it while they stay.
  Death ends a stay and lapses scheduled visits; it never permits another arrival. Arrival and
  departure audiences are sight-filtered (the arriving/leaving person still knows), and departure
  sightings never add memories for blind or unconscious witnesses. `take-leave` also refuses
  disabled movement or unconsciousness. Hiring and couriers share these movement constraints;
  hiring keeps its full-day grace and off-map `candidate-home`. Starting dead overrides `:away?`.
- **Views** (`add-to-view`): while a guest's visit is `:here`, `you.visit` shows their newest
  one, to them alone: `{:host "Lord Claudeworth" :invited-for "a week" :reason … :arrived "Fri
  19:00" :bedroom "blue-room"}` (no `:host` for an arranged visit). A guest from elsewhere who is
  here (`from-elsewhere?`) sees the house's bedrooms under `household.bedrooms`, as the household
  does; all active assigned sleepers are listed, including guests. These lists may reveal even
  secret visitors; invitation and calendar privacy is otherwise unchanged. `away-acquaintances`
  lists the people the viewer has met who are away now, with where they parted (`space/away-acquaintances`), so they can be invited by `who`.
- **`take-leave`** (final) is shared: for a candidate here it is hiring's, unchanged; for a guest
  from elsewhere, anywhere in the house's area, it emits `:people/departed` towards where they
  live (their `:lives`) and ends their `:here` visits. They're away again and can be invited
  again, coming back as the same agent with their memories. With `return-date` and
  `return-time` (checked by `hiring/return-reason`), a new visit is scheduled from their newest
  one here: same host, `:by :returning`, `:returning? true`, told at once, with calendar entries
  for both sides; they arrive at the meeting place then like any away guest. There's no
  `send-away` for guests; hiring's stays candidates-only.
- **Away agents** come from two places, and only pool records are ever evicted, so neither is:
  world-file characters with `:away? true` (see World files), and **someone new**, made from the
  fields `visits/new-person-keys` (`name sex age from known-as situation`). `(write-new-person world
  services fields)` is the I/O, for a `:query-fn` or a system's `:compose`: the people server's
  record for that sex and exact age, with the name kept and `:lives` set to `from`, then
  `hiring/write-person` with `situation` as the polish's notes. `(away-guest-events scenario world
  p fields)` turns its result into events, all `:system`: the spawn as a `:guest` (no household),
  `:agent/updated` with `:lives`, the public `:known-as` and `:age`, and `:people/departed`. Returns
  `{:id :events}`. Everything is recorded from the query's result, so replay never calls out.
- **GM and operator.** The GM's `schedule-visit {who | name sex age from known-as situation, date
  time stay reason? bedroom? known-to?}` is a **query** (it may write someone new; it counts
  towards the GM's queries), and `cancel-visit {id}` is final. `who` is anyone in the world:
  someone about is told at once (their calendar entry: "You mean to call at …"), except a guest
  already staying from elsewhere, who gets neither `:told?` nor a guest entry. Someone away
  arrives at the time told nothing before. In-person invitations and carrying their own message
  still tell a staying guest at once. Or the new person's fields: they're written in the
  query, spawned away when it's done (the checks are run again on the world then), and arrive at
  the time; nobody is told unless named in `known-to`. A new name already in the world is refused
  ("use who"). Operators use `engine/schedule-visit!` (with `:who` only) and `engine/cancel-visit!`
  (REPL: `schedule-visit!`, `cancel-visit!`), queued between rounds; they throw with the reason,
  including the tool's limits on `stay` (1–200 characters) and `reason` (at most 300), which the
  operator's spec doesn't get from a schema. Their only lead time is the next round. Only a
  `:scheduled` visit can be cancelled; its entries are cancelled with it.
- **Authored visits** are a world file's top-level `:visits`, passed through `load-world` to
  `:init` like `:calendar`:

  ```clojure
  :visits [{:who "master-claudeworth" :date "1904-12-20" :time "14:00" :stay "the Christmas holidays"
            :reason "Home from Eton at the end of the Michaelmas half."   ; optional
            :bedroom "sons-room" :known-to ["lord-claudeworth" "lady-claudeworth"]}]   ; optional
  ```

  At a fresh run's start, after the cast (away characters included), the final `:start` clock and
  the calendar, the engine makes each in order (`visits/authored-events`) with the operator's
  checks and events, `:host` nil and `:by :authored`: someone about is told at once in their
  calendar, someone away arrives at the time told nothing before, and `:known-to` (character ids,
  default nobody) get the host's entry. `:who` is a character id, never a name or someone new; the
  keys are only those above. Valid visits strictly before the final start time (after a `:start`
  override) are skipped; equality is allowed. There is no catch-up or reconstruction of a stay,
  even if it would still have continued. All entries are validated before skipping: malformed
  dates, unknown ids or keys, and other invalid fields still fail. Claudeworth's `:leave-out`
  drops visits for left-out characters and removes their ids from `:known-to`; genuinely unknown
  ids and malformed audiences are still errors. Any problem stops
  initialization with every bad visit listed by its place in the vector ("bad :visits in the world
  file:\n- visit 2 (Mrs Bell): no one has the id Mrs Bell"). Load and branch replay the recorded
  events and never make them again. Map export (`map.edn`) leaves `:visits` out, as it leaves out
  `[:state :visits]` and the managed entries: a run started from an exported map has no authored
  visits unless they're added back by hand. The medium household's son comes home this way.
- **Hibernation guard.** `(visits/keeps-awake? world id)` is true from `:wake` (`:wake-from` on the
  visit's day, or the day before when the visit is earlier than `:wake-from` plus their journey to
  the meeting place and an hour, as for a 01:00 visit), once told, until the visit arrives, and
  all the while it's `:here`. Claudeworth's `hibernate` returns nil while it's true, so the
  ordinary `b/free` wakes them. Once the visit ends or lapses, hibernation resumes as usual.

### Occupation: sleep and other long activities

An agent can be busy until a round. Like a traveller, they skip their turns (no model calls)
and are freed at the start of that round. This makes nights and long activities cheap.

```clojure
:effect (fn [world actor {:keys [until]}]
          [(manor.mechanics.builtins/occupy world actor "asleep" (clock/round-at (:clock world) (:round world) until))])
```

- `(b/occupy world id activity until)` is an `:agent/occupied` event, visible to everyone present.
  Its `:busy` keeps `:activity :until :asleep? :hibernating? :job` from the event data (`spend-time`
  adds the `:job`; see Items). For sleep, pass `{:asleep? true}` as a fifth argument:
  `(b/asleep? world id)` is then true until they're roused, and nobody can draw them into a
  conversation. The activity text alone ("asleep") means nothing to the engine.
  Write `activity` to read after "is": "asleep", "reading in the library". The agent's next turn
  is in round `until`.
- `(b/free world id by)` frees someone early (e.g. a `rouse` action). `by` is named in the
  narration and also sees the event.
- `(b/interrupt world id by)` is the event a `rouse` action returns: it marks the occupation
  interrupted (`:interrupted-by`), visible to everyone present plus `by`. The agent gets a turn
  this round if they have not acted yet (with default schedules), even though busy. In that turn, the built-in `carry-on` returns `(b/resume world id)`,
  which keeps the occupation and its original deadline; any other accepted action frees them first
  (the engine does this, so each action needs no rule of its own). A rejected or undecodable call
  does not free them; a forfeit or a failed turn calls `(b/resume world id)`, so the occupation
  still ends at its own time.
- An agent can't be interrupted twice: a `rouse` should refuse when `(:interrupted-by (w/busy world
  who))` is set (Claudeworth's and hamlet's do). The interrupted agent's view has `[:you :busy]`
  with the activity, when it ends, and who interrupted them.
- `(manor.world/busy world id)` is `{:activity .. :until ..}` or nil. Occupied agents still
  count as present, so they see what happens in the room, in their window when they wake.

### Hibernation: agents nobody needs

An agent nobody is near (the village architect on a day without clients) can be left out of play
with no end time, and woken when someone comes near. Set `:hibernate` to
`(fn [world agent-id] {:activity "at his drawing board" :location "architects-office"} or nil)`.
At the start of every round, after travellers arrive and occupations end, the engine asks it about
each agent who is free or already hibernating, except anyone whose own occupation just ended:
they get one turn before returning to their routine next round.

- not nil, for an agent who is free: the engine moves them to `:location` (optional; an
  `:agent/moved` that people at both ends see) and occupies them with `(b/hibernate world id
  activity)`: an `:agent/occupied` event with `:hibernating? true` and no `:until`. They skip their
  turns like anyone busy, and people present see what they're doing.
- not nil, for an agent already hibernating: if the activity or the location differs, they're moved
  and occupied again (the day goes on: the office in the morning, home in the evening); otherwise
  nothing happens.
- nil, for an agent who is hibernating: `(b/free world id)`. They act this round.

Agents busy on their own account (`sleep`, `spend-time`), travellers, human seats, interrupted
agents, and anyone whose movement is disabled or who is unconscious (see Physical status) are
left alone. `rouse` works on a hibernating agent as on anyone busy; the agent's view and
the narration leave out the end time. `(b/hibernating? world id)` tells you whether someone is
hibernating. Agents are decided one at a time, each on the world as the previous one left it.

After an action emits `:agent/moved`, the engine also checks `(b/wakings hibernate-fn world)`:
uninterrupted hibernators whose function now returns nil are freed immediately. Nobody is put
out of play mid-round. A customer can therefore walk in and talk to a clerk at once.

Decide in the function who counts as company, and don't count other hibernating agents, or two
idle agents keep each other awake. Claudeworth also doesn't count anyone exactly where their own
routine puts them, so two people who share a workplace (a publican and his barmaid) don't keep
each other in play. Keep it narrow (Claudeworth counts the agent's place and the
place their routine puts them, not the whole village), or one visitor wakes everyone in the area. Placing the agent where they'd be (their office in office hours)
matters: someone woken in the wrong place doesn't know why they were woken. Moving an agent between
areas skips the journey, so only do it when nobody is there to see (Claudeworth checks both areas).
Waking usually means someone has come near, but it may also come from the agent's own plans: a
visit keeps an invited guest in play all that day (Claudeworth's guard, see Visits), with a private
reminder of the engagement. The wake narration names nobody either way; keep that in mind for
scenarios where it matters.

### Conversations (`manor.mechanics.conversation`)

Every scenario has the built-in `converse {with?, text}`, so back-and-forth talk doesn't take a
round per answer. It's free: the conversation runs inside the initiator's turn, who then carries
on with it. It uses nobody else's turn either.

- **Who:** up to 3 others in the initiator's place (`with`, a list of ids; a single id string is
  accepted), or, with `with` left out, everyone here who can talk, if that's at most 3. Not
  people asleep (`b/asleep?`), hibernating, travelling or elsewhere. Someone
  busy but awake can talk, and the conversation doesn't touch their occupation: no interrupt, and
  their work ends when it was going to. An interrupted member stays interrupted and decides on
  their own turn. `manor.mechanics.conversation/check` gives the reasons.
- **Order:** the initiator opens with `text`, then everyone speaks in turn, the initiator first
  (then `with`'s order, or spawn order), for up to 2 passes, then the initiator has a last word.
  Each speaking turn is one short model call (at most 3 with retries) with its own prompt (the
  agent's usual view, notes and recent events, plus who is in the conversation and where it has
  got to) and its own tools: members get `reply {text}`, `stay-silent` and `leave`; the
  initiator gets `reply` and `end-conversation`; everyone also has `note`. Someone whose speech
  is disabled has no `reply`, and a gagged member's line is muffled for the others (see Physical
  status). Nobody deaf can start or join one. A speaking turn that
  answers with plain text and no tool call has the text taken as its `reply`, if it has one (normal turns are
  nudged to call a tool instead). These tools exist only
  in speaking turns and can't be overridden. Lines are told 400 characters and accept 1500.
  The prompt also says that a line agrees to things but doesn't do them: nothing is handed over,
  made, mended or moved by talking, only by someone acting on their turn (and, in a world with
  items, that what you hold is under `carrying`).
- **Extra tools:** optional `:speaking-actions (fn [world speaker conv] actions)` adds tools after
  the speaking tools and `note`; `conv` has `:id`, `:initiator` and `:members`. The scenario decides
  who gets them. The engine accepts only `:free` and `:query`, applies their `:available?` checks,
  and drops names reserved for the speaking tools or `note`. Normal argument decoding and
  `:valid?` still apply. Turn-ending actions (including `buy`) and nested `converse` aren't allowed.
  With a query offered, that speaking turn has one query slot and at most 4 model calls instead
  of 3. Query usage and appraisal cost are recorded normally; queries preserve occupations too.
  Each speaking turn has fresh limits, separate from the speaker's own turn. Its prompt names
  the extra tools actually offered: use them first if needed, then finish with a speaking tool.
  Speakers without extras keep their usual prompt.
- **Ending:** the initiator ends it, everyone else leaves, nobody else speaks in a pass, the
  passes and the last word are done, the run reaches `:max-cost` (checked between speaking turns), or
  a call fails (the run is asked to pause). It never carries over into another round.
- **Events,** each with `:conversation` (the id, `"conv-<n>"`) in its data and visible to everyone
  present: `:conversation/started {:members :at}`, each line an ordinary `:agent/said`, then
  `:conversation/silent`, `:conversation/left` and `:conversation/ended {:reason :handed}`.
  In a world with items, `:handed` counts the things that came into someone's hands during it
  (`:item/given`, `:item/taken`, `:item/found`); when it's 0 the ending's narration adds that
  nothing changed hands and that anything offered, lent or promised is still where it was. Bystanders hear
  every line. The started event records the round in the initiator's `:conversed-round`, which
  limits them to one conversation a round. Nothing else is world state.
- The initiator's tool reply says how it ended and narrates every event visible to them between
  its start and end: lines, silences, departures, and extra-tool effects such as offers (with quote
  ids) and refusals. Start/end markers, usage and system events are omitted; private events stay
  private. When nothing changed hands, it ends by saying so and that a thing changes hands only
  through `give`, after `find` if nobody carries it. Extra-tool events need no `:conversation` tag. Speaking turns are
  logged in `llm.edn` with `:conversation {:id :pass}` (`:pass :last-word` for the last word),
  which the transcript shows next to the agent.
- **Judged afterwards** in a scenario with inner state: once it has ended (and wasn't cut
  short), the conversation judge may change its members' inner state, before the initiator's
  summary is returned. Nobody is told. See Inner state.
- **Human seats** take part like anyone else. A human's speaking turn waits for panel input
  (`act!`) with the same tools, its form headed by which line it is, who else is in it and what
  they've heard of it so far; it has no call limit and ends only with a speaking tool. A human
  initiator's `converse` runs inside their own turn, which carries on when it ends. A model's
  conversation that draws a human in waits at each of their lines, as a human turn does.
- It isn't offered in a simultaneous group, where the others are in their own turns. Leave it
  out with `:exclude-actions #{:converse}`.

### Turn order: `:schedule`

- `:round-robin` (default): one agent at a time in spawn order; each sees the previous agents'
  results.
- `:simultaneous`: all agents act on the same snapshot. Final actions are re-checked and resolved
  in spawn order afterwards; losers get a private `:action/rejected` event. Free and query actions
  still apply immediately.
- `(fn [world] [[ids ...] ...])`: custom groups. Use `(manor.world/active-agents world)` (it
  excludes the GM). Inactive, travelling and occupied agents are filtered out when each group runs.

The defaults schedule all active agents, checking travel and occupation at each group
(not just at round start). After those groups, one trailing pass gives a turn in spawn order
to agents now free or interrupted, not travelling, who have not acted this round. Its candidates
are fixed before the pass: someone woken during it waits until next round. Custom schedule
functions keep control of their groups and get no trailing pass.

### GM triggers and pausing

Calendar due review is a separate pre-turn activation (see Calendar); the following stay after turns.

- `:gm-triggers`: functions run after agents act each round. Any non-nil string calls the GM with
  that reason. The GM is also called when its inbox has requests, or every
  `:gm-check-in-every` rounds if set. **Triggers fire every round while true**, and each firing
  costs a GM call. Prefer edge conditions (e.g. `(= hunger 2)` rather than `(>= hunger 2)`).
- `:pause-when`: checked after each round. A non-nil reason pauses auto mode. Use it for end
  conditions.

### `:mock-policy`

Lets the scenario run for free with `{:llm :mock}`. It's essential for testing.

```clojure
(fn [{:keys [world agent-id role step used reasons]}]
  {:tool :dig :args {} :say "optional text"})   ; nil means pass
```

- `step` is the model-call index within this turn. `used` is the tool names already called this
  turn: after a free or query call, return a turn-ending action next. `say` and `whisper` are
  free and once per turn, so guard them with `(not (used "say"))` and pass afterwards.
- For the GM (`role :gm`) return `{:tool :done}` unless testing GM behaviour.
- The judge of work on items (`role :judge`) never reaches the policy: the engine sends it to the
  scenario's `:mock-judge`, or `manor.mechanics.items.judge/mock-judge` (see Items). Nor does the nightly steward
  (`role :steward`): it goes to `:mock-steward`, or `manor.mechanics.items.steward/mock-steward`.
- Internal adjudication calls (`role :adjudicator`) go to the task's registered `:mock`,
  not this policy (see Internal adjudication queries).
- Nor do speaking turns in a conversation (the context has `:conversation {:id :initiator
  :members :pass :final?}`): they go to the scenario's `:mock-conversation`, and when that is
  missing or returns nil, to `manor.mechanics.conversation/mock-default` (the initiator ends it, the others
  stay silent). The conversation judge (`role :conversation-judge`) goes to
  `:mock-conversation-judge` (see Inner state).
- Only choose valid actions. The scenario smoke test fails on forfeited turns.

## Stable test conditions

Keep mechanics tests independent of the bundled scenario's current cast, map and mock script.
Use a small test world or fixed test-owned fixtures, with explicit defaults and choreography.
For example, test-only `manor.scenarios.household` reuses Claudeworth's mechanics with
`test/fixtures/household.edn` and `household-day.edn`; do not sync those fixtures when adding
characters or changing the bundled world. Its fixed day leaves the maid out explicitly;
work/judge tests enable her explicitly. Engine and transcript mechanics tests use it too.

Select actors by identity, not position in a vector. Validation tests should start from valid
fixtures and check the intended diagnostic, not merely that some error occurred. Exact cast,
audience, balance and timing assertions belong in controlled fixtures. Assertions about all
current agents should enumerate the applicable agents rather than a historical cast list.

Keep separate bundled-scenario smoke tests (`test/manor/scenarios_test.clj`) for current content:
valid initialization, valid mock actions and exact replay. A deliberate default change may
update its configuration test, but should not require changing unrelated mechanics tests.

## Checklist for a new scenario

1. Copy `treasure.clj` to `src/manor/scenarios/<name>.clj` and rename the namespace and `:name`.
2. Define `:state` and (optionally) `:space` in `:init`, plus the starting agents.
3. Write role briefs that explain the rules and goals (functions of the world, if they would
   otherwise restate facts from it).
4. For each action: schema, `:valid?` with actionable reasons, `:effect` returning namespaced events
   with deliberate `:visible-to`.
5. A reducer for every event type that changes state, and a `describe` for every visible one.
6. `:observe` that shows only what the agent may know. Check it by eye with `(view "id")`.
7. A mock policy. Add the scenario to `test/manor/scenarios_test.clj` and run `clojure -M:test`.
8. Run offline at the REPL: `(start! :<name> {:llm :mock :offline? true :test? true}) (step! 5) (feed)`.
   Check `(feed 40 "some-agent")` shows nothing it shouldn't. (`:test? true` keeps experiments
   under `test-runs/`, which can always be deleted.)
9. Try real models cheaply and briefly: `(start! :<name> {:max-cost 0.20 :max-rounds 10 :test? true})`
   (a few rounds expose most problems; see "Real runs are short by default" in `AGENTS.md`). To
   check something late in the day, start near it with `:start` (see Time) instead of playing up
   to it. Then read
   `transcript.md` in the run directory (written whenever the run pauses or stops), which shows each
   model call with the character's private thoughts when the model returned any (`thoughts:` lines),
   `test-runs/<id>/llm.edn`, or the agent pages in the panel. Look for rejections, forfeits, and confused tool calls, and fix
   docs, briefs and reasons accordingly.

## Common mistakes

- Forgetting `:visible-to`. The event is then hidden from agents (`:gm`), so nobody learns what
  happened.
- Putting randomness or the clock in a reducer: replay diverges.
- Returning functions or Java objects in event `:data`: the log can't be written or read back.
- Tool names with namespaces (`:my/dig`): only the name part is used, so names can collide.
- Hiding a tool with `:available?` when a `:valid?` reason would teach the model where to go.
- Long `:observe` maps: they are sent, at the full input price, on every model call. Move what
  rarely changes to `:reference`, where the provider's cache makes it cheap.
- Something that changes often in a `:brief` function or `:reference`: everything after it is read
  at the full price again.
- A GM trigger that stays true: a paid GM call every round.
- `:on-result` events for act bridges without `:actor`.
- Letting the model choose a session or account id for an MCP tool instead of setting it in
  `:bridge-args`.
