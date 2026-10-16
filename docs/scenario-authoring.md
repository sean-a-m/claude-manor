# Scenario authoring guide

This is the complete contract between a scenario and the engine as it stands today. Three working
scenarios ship with the engine:

- `src/manor/scenarios/treasure.clj`: small, about 100 lines. Start here and copy it.
- `src/manor/scenarios/village.clj`: full-featured. Economy, bridges, an act bridge, systems, GM
  triggers, locations, mock policy.
- `src/manor/scenarios/quiz.clj`: an MCP bridge. Each contestant messages a private,
  persistent pi assistant through the pi MCP server.

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
   :actions       [...]                    ; scenario actions, see below
   :bridges       {...}                    ; optional, real-world calls
   :mcp-servers   {...}                    ; optional, MCP servers that bridges call
   :systems       [...]                    ; optional, per-round world processes
   :hibernate     (fn [world agent-id] {:activity .. :location ..} or nil)  ; optional, see Hibernation
   :observe       (fn [world agent-id] {...})   ; strongly recommended
   :gm-view       (fn [world] {...})       ; optional, what the GM sees
   :schedule      :round-robin             ; or :simultaneous, or (fn [world] [[ids] ...])
   :gm-triggers   [(fn [world] reason-or-nil) ...]
   :pause-when    (fn [world] reason-or-nil)
   :limits        {:max-agents 8}          ; GM spawn limit (default 10)
   :exclude-actions #{:move}               ; built-in actions to leave out
   :defaults      {:model "..." ...}       ; overrides engine default options for this scenario
   :mock-policy   (fn [ctx] {:tool :dig :args {}})
   :offline-stubs {:bridge/name (fn [args ctx] result)}})
```

The engine reads no other keys.

### `:init`

`(fn [opts] {:state map, :space map, :agents [spec ...], :clock map, :world-file map})` (only
`:state` is required), where `opts` are the run
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

- `:clock`: optional game time, `{:start "1904-10-15T08:00" :minutes-per-round 30}` (see Time).
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

### `:actions`

Each action is a map. Each role's actions become that role's LLM tools.

```clojure
{:name    :dig                       ; tool name: plain keyword, [a-z0-9-], unique
 :doc     "Dig for the treasure where you are."
 :args    [:map [:depth [:int {:min 1 :max 3}]]]   ; malli schema, default [:map]
 :roles   #{:explorer}               ; or :all (every non-GM role, the default)
 :kind    :final                     ; :final (default) | :free | :query | :act
 :available? (fn [world actor] bool) ; optional: leave the tool out of this turn
 :valid?  (fn [world actor args] nil-or-reason-string)
 :effect  (fn [world actor args] [events])}
```

**Kinds:**

| Kind | Ends turn? | When it applies | Notes |
|---|---|---|---|
| `:final` | yes | immediately (sequential turns) or after the group (simultaneous) | the normal action |
| `:free` | no | immediately | notes, declining, small side moves |
| `:query` | no | immediately; calls a bridge, and the result comes back within the turn | needs `:bridge`; limited by `:max-queries` per turn; no approval |
| `:act` | yes | creates a pending request; runs after operator approval at the next round start | needs `:bridge` |

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
- Effects may return engine events too: `(manor.builtins/spawn-events scenario world spec)`,
  `{:type :agent/retired :data {:id .. :reason ..} :visible-to :all}`,
  `{:type :run/pause-requested :data {:reason ".."} :visible-to :gm}`.
- Ids for new things should be deterministic, e.g. `(str "offer-" (inc (:seq world)))`.

**Built-in actions** (merged before yours; define one with the same `:name` to override it):

| Action | Roles | Kind | Behaviour |
|---|---|---|---|
| `pass` | all | final | does nothing |
| `carry-on` | all | final | only offered to an interrupted agent: keep the occupation and ignore the interruption |
| `note` | all | free | private note, shown to the agent every round (last 15); told 400 characters, accepts 500 |
| `say` | all | final | heard by everyone in the same place (everyone, without locations); told 1000 characters, accepts 1500 |
| `whisper` | all | final | private to one agent; requires being together if both have locations; same limits |
| `pray` | all | free | a private, diegetic way to reach the GM: its `:gm/requested` event fills the GM's inbox and triggers the GM this round |
| `move` | all | final | follow a route (see Locations); only offered when the agent has a location |
| `spawn-agent` | gm | final | spawn a `:spawnable?` role, optional location; respects `:limits` |
| `retire-agent` | gm | final | remove an agent |
| `announce` | gm | final | message to everyone or a list of ids |
| `ask-human` | gm | final | pause after this round with a question for the operator |
| `add-location`, `open-route`, `close-route` | gm | final | change the graph (only when it exists) |
| `done` | gm | final | end the GM's intervention |

Override a built-in by defining an action with the same `:name`, or leave it out with
`:exclude-actions #{:move :whisper}`. Don't exclude `done`: the GM needs it.

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
  deliberately. For things that happen at a place use `(manor.space/here world actor)`. For a
  private outcome use `#{actor}`.
- **Reducers:** one per event type that changes state.
  ```clojure
  (defmethod manor.world/apply-event :treasure/dug [world {:keys [actor data]}]
    (assoc-in world [:state :found-by] actor))
  ```
  Reducers must be pure and depend only on the world and the event: no clock, no rng, no I/O.
  Event types without a reducer are informational (e.g. speech).
- Engine-owned world keys: `:round :seq :agents :space :gm :pending-bridges :pause-requests :usage`,
  and `:clock` when the scenario has one.
  Scenario reducers should only touch `:state`, unless deliberately using engine semantics via
  engine events.
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

Rules: include only what this agent may know: its own holdings, public facts, and things at its
location. This is the fog of war. Never return another agent's private data. Keep it compact; it
is sent on every model call.

`:gm-view` defaults to `{:state .. :agents [..] :space ..}` (the GM is omniscient). Override it to
trim tokens. The default GM view leaves out `:setting` and `:roles :gm` in `:state` (and `:roles`
if nothing else is in it), which are brief material: put them in the GM's `:brief`. Other roles'
entries in `:roles` stay.

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
- Use `manor.bridge/get-json` (JDK HTTP client) for simple GET APIs. Don't use http-kit's client
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

### Locations (`manor.space`)

When `:init` returns `:space`, the engine provides:
- agent positions (`:location` on the agent; `nil` while travelling)
- the `move` action. A route with `:travel 1` is immediate (the move is the agent's action for the
  round). `:travel n` > 1 puts the agent in transit: it skips its turns and arrives at the start
  of round current+n. A route with `:travel 0` joins places next door to each other (the rooms of
  a house); `move` along it is immediate too. Places joined by `:travel 0` routes form an
  *area*, and a scenario can offer a free action to walk anywhere in it (see `space/area`).
- location-scoped `say`/`whisper`, departure and arrival visibility, and the `:location` view section
- GM graph tools, operator `place!`, and spawning at a location

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

State that belongs to a location (items, prices) goes in your `:state`, keyed by location id.

### World files (`manor.worldfile`)

A scenario can keep its starting world in an EDN file instead of code: locations, routes, the
clock, starting agents, and any sections of its own. Put it under `resources/worlds/` and load it
in `:init`:

```clojure
:init (fn [opts]
        (assoc (manor.worldfile/load-world (or (:world-file opts) "worlds/my-world.edn"))
               :state {...}))
```

```clojure
{:meta {:name "Hamlet" :start "1904-10-15T21:00" :minutes-per-round 30}   ; :start is optional
 :locations [{:id "hall" :name "Hall" :desc "A small hall."
              :connects-to ["bedroom" "porch"]      ; next door (:travel 0); either side is enough
              :domain :house :kind :hall}           ; other keys are kept on the location
             ...]
 :routes [["porch" "green" 1]]                     ; journeys, as in :space
 :agents [{:name "Ada" :role :resident :location "hall"}]
 :shop {...}}                                       ; your own sections, from :world-file
```

- `load-world` takes a file path or a classpath resource name. It checks the file and throws a
  list of readable problems (unknown locations, duplicate ids, bad routes, bad clock). It returns
  `{:space :clock :agents :world-file}`. Read your own sections from `:world-file`.
- `:domain` groups locations into areas on the control panel's map (house, village, ...).
- The file is a **template**: nothing writes to it. The engine saves a copy as
  `<run-dir>/world-start.edn`. Whenever the map changes, it writes the live map back in the same
  format to `<run-dir>/map.edn` (output only). A run can start from an exported map
  (`{:world-file "runs/<id>/map.edn"}`), and `(export-map! path)` at the REPL writes one anywhere.
- Loading, branching and replay never read world files: the starting map is recorded as events.

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
- The run option `:start` (CLI: `--start 1904-10-15T21:00`) replaces the clock's `:start` for one
  run and keeps its `:minutes-per-round`, so you can begin near what you want to test (bedtime,
  say). It's checked like the clock, and a scenario without a clock refuses it. Only the time
  changes: people start where the world file puts them (at 21:00 still in their 08:00 places), so
  a late start may need a mock script or world file that makes sense then.

### Occupation: sleep and other long activities

An agent can be busy until a round. Like a traveller, they skip their turns (no model calls)
and are freed at the start of that round. This makes nights and long activities cheap.

```clojure
:effect (fn [world actor {:keys [until]}]
          [(manor.builtins/occupy world actor "asleep" (clock/round-at (:clock world) (:round world) until))])
```

- `(b/occupy world id activity until)` is an `:agent/occupied` event, visible to everyone present.
  Write `activity` to read after "is": "asleep", "reading in the library". The agent's next turn
  is in round `until`.
- `(b/free world id by)` frees someone early (e.g. a `rouse` action). `by` is named in the
  narration and also sees the event.
- `(b/interrupt world id by)` is the event a `rouse` action returns: it marks the occupation
  interrupted (`:interrupted-by`), visible to everyone present plus `by`. The agent gets a turn
  next round even though busy. In that turn, the built-in `carry-on` returns `(b/resume world id)`,
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
each agent who is free or already hibernating:

- not nil, for an agent who is free: the engine moves them to `:location` (optional; an
  `:agent/moved` that people at both ends see) and occupies them with `(b/hibernate world id
  activity)`: an `:agent/occupied` event with `:hibernating? true` and no `:until`. They skip their
  turns like anyone busy, and people present see what they're doing.
- not nil, for an agent already hibernating: if the activity or the location differs, they're moved
  and occupied again (the day goes on: the office in the morning, home in the evening); otherwise
  nothing happens.
- nil, for an agent who is hibernating: `(b/free world id)`. They act this round.

Agents busy on their own account (`sleep`, `spend-time`), travellers, human seats and interrupted
agents are left alone. `rouse` works on a hibernating agent as on anyone busy; the agent's view and
the narration leave out the end time. `(b/hibernating? world id)` tells you whether someone is
hibernating. Agents are decided one at a time, each on the world as the previous one left it.

Decide in the function who counts as company, and don't count other hibernating agents, or two
idle agents keep each other awake. Keep it narrow (Claudeworth counts the agent's place and the
place their routine puts them, not the whole village), or one visitor wakes everyone in the area. Placing the agent where they'd be (their office in office hours)
matters: someone woken in the wrong place doesn't know why they were woken. Moving an agent between
areas skips the journey, so only do it when nobody is there to see (Claudeworth checks both areas).
Waking tells the agent that someone has come near; keep that in mind for scenarios where it matters.

### Turn order: `:schedule`

- `:round-robin` (default): one agent at a time in spawn order; each sees the previous agents'
  results.
- `:simultaneous`: all agents act on the same snapshot. Final actions are re-checked and resolved
  in spawn order afterwards; losers get a private `:action/rejected` event. Free and query actions
  still apply immediately.
- `(fn [world] [[ids ...] ...])`: custom groups. Use `(manor.world/active-agents world)` (it
  excludes the GM). Inactive, travelling and occupied agents are filtered out automatically.

### GM triggers and pausing

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
  turn: after a free or query call, return a turn-ending action next.
- For the GM (`role :gm`) return `{:tool :done}` unless testing GM behaviour.
- Only choose valid actions. The scenario smoke test fails on forfeited turns.

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
9. Try real models cheaply and briefly: `(start! :<name> {:max-cost 0.10 :max-rounds 10 :test? true})`
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
- Long `:observe` maps: they are sent on every model call.
- A GM trigger that stays true: a paid GM call every round.
- `:on-result` events for act bridges without `:actor`.
- Letting the model choose a session or account id for an MCP tool instead of setting it in
  `:bridge-args`.
