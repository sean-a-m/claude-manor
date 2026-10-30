# Prompts: the catalogue

Every prompt template under `resources/prompts/`: what it's for, who renders it, and what it's
filled with. Keep this file current: when you add, rename, split or remove a template, change its
row here in the same commit. The model-facing text that is still in code is listed at the end;
`docs/prompt-extraction-design.md` is the plan for it.

## How templates work

- A template is markdown text with `{{key}}` placeholders (dotted paths like
  `{{money.base-name}}` reach into maps). No conditionals or loops: code chooses which templates
  and fragments to render and join. A placeholder with no value throws. A file's one final newline
  isn't part of the template. See `manor.prompts` and `docs/settings-design.md` 3.3.
- `(prompts/render world "path.md" params)` looks in the run's setting pack first
  (`resources/settings/<id>/prompts/path.md`), then in `resources/prompts/path.md`. The setting
  pack's parameters from `setting.edn` (`{{home}}`, `{{era}}`, `{{household-name}}`,
  `{{everyday-things}}`, `{{money...}}` and the rest; see `docs/scenario-authoring.md`, "Setting
  packs and prompt templates") are always available. Explicit `params` win.
- An action's `:doc` (and its `:hint`) may be a string or a function of the world;
  `(prompts/doc "docs/x.md" params)` makes one.
- **Fragments** are templates rendered into another template's parameter. They are listed with
  their parent. Mind the whitespace a fragment starts or ends with: several begin with ", " and
  `writers/appearance.md` ends with a space before its newline, which an editor that strips
  trailing spaces would remove.
- **Checking a change:** `dev/prompt_capture.clj` records every request of four mock days, plus
  prompts that a mock day doesn't build (judges, writers, the GM, reminders, every tool), under
  `prompt-baseline/<label>/`. Capture `before`, make the change, capture `after`, and
  `diff -r prompt-baseline/before prompt-baseline/after`. A move with no change in wording must
  diff empty.

Parameters below are the ones code passes. The setting's parameters are noted only where a
template uses them.

## Agents: framing and turn mechanics (`agent/`)

| Template | Used for | Rendered by | Parameters |
|---|---|---|---|
| `agent/preamble.md` | The start of every agent's system message, turns and conversations alike (so they share the prompt cache) | `prompt/agent-preamble` | |
| `agent/turn-closing.md` | The end of a turn request: how to act, and to act now | `prompt/turn-closing` | `goals-review` (the review request, or empty) |
| `agent/goals-review.md` | Asks for a review of the agent's goals with `set-goals`, every `:goals-every` rounds; goes into the turn closing | `goals/review-request` | `goals-max` |
| `agent/reminder-early.md` | Reminder after two calls without a turn-ending action | `engine.turn/turn-reminder` | `actions` (an or-list), `wait` (`" "` + `reminder-pass.md`, or `""`) |
| `agent/reminder-last.md` | Reminder before the last call of a turn | ″ | ″ |
| `agent/reminder-pass.md` | Fragment: `pass` is a fine way to wait (only when `pass` is offered) | ″ | |
| `agent/reminder-speaking-last.md` | Reminder before the last call of a speaking turn | `engine.talk/speaking-reminder` | `actions` |
| `agent/nudge-length.md` | Reply to a response cut off by `:max-tokens` with no tool call | `engine.turn/nudge` | |
| `agent/nudge-no-tool.md` | Reply to a response with no tool call | ″ | |

## The rules prompt (`rules/`)

The household ruleset's "How things work" section of each role brief (`ruleset.rules/rules`), and
the rules of individual mechanics that it joins in.

| Template | Used for | Rendered by | Parameters |
|---|---|---|---|
| `rules/how-things-work.md` | The section around the parts below | `ruleset.rules/rules` | `sections` (joined by a blank line) |
| `rules/sections/turns.md` | Turns, time, queries | ″ | `turn`, `max-queries`, `counted` (`turns-counted-architect.md` or `turns-counted.md`) |
| `rules/sections/getting-about.md` | `walk`, `travel`, `call` | ″ | |
| `rules/sections/sleep.md` | Sleep and being busy | ″ | setting |
| `rules/sections/speaking.md` | `say`, `whisper`, `converse` | ″ | `speech-limit`, `max-others`, `passes` |
| `rules/sections/work.md` | `spend-time` | ″ | `max-work-hours` |
| `rules/sections/things.md` | Items and `find` | ″ | `find-for-sale` (`things-find-everywhere.md` or `things-find-not-at-trade.md`) |
| `rules/sections/people.md` | Aspects of people, the body | ″ | `physical` (`rules/physical.md`) |
| `rules/sections/shopping.md` | Money and shopping | ″ | `paid` (`shopping-paid[-commissions].md`), `spending-tools` (`shopping-spending-tools[-commissions].md`), `money` (the pack's `money.md`) |
| `rules/sections/building.md` | Building, only with an architect | ″ | `sizes`, `fee-percent` |
| `rules/sections/hiring.md` | Hiring | ″ | `hiring` (`rules/hiring.md`) |
| `rules/sections/invitations.md` | Invitations, only with visits | ″ | `visits` (`rules/visits.md`) |
| `rules/physical.md` | Physical status: hindered faculties, impediments | `physical/rules` | |
| `rules/hiring.md` | How hiring works | `hiring/rules`, `hiring/rules-beside-visits` | `arrivals` (`rules/hiring-arrivals.md` or `rules/hiring-arrivals-beside-visits.md`) |
| `rules/visits.md` | How invitations and guests work | `visits/rules` | `post` (`rules/visits-post.md` or `""`), `calendar` (`rules/visits-calendar.md` or `""`) |

## Tool descriptions (`docs/`)

The `:doc` of each action: the tool description a model reads. `actions/->tool` appends a hint
for the action's kind and its per-turn limit (`docs/hints/`).

| Template | Tool | Defined in | Parameters |
|---|---|---|---|
| `docs/hints/final.md`, `free.md`, `query.md`, `act.md`, `converse.md` | The hint for each action kind | `actions/->tool` | |
| `docs/hints/once-per-turn.md`, `times-per-turn.md` | The per-turn limit | ″ | `n` |
| `docs/hints/speaking-turn.md` | The hint of the speaking-turn tools | `conversation` | |
| `docs/hints/invite.md` | `invite`'s own hint (free, or a query with `person`) | `visits` | |
| `docs/builtins/carry-on.md`, `pass.md`, `note.md`, `say.md`, `whisper.md`, `pray.md` | Every scenario's agent actions | `builtins/agent-actions` | `note-limit` (note), `speech-limit` (say, whisper) |
| `docs/builtins/set-goals.md` | The `set-goals` action | `goals/actions` | `goals-max`, `goal-limit` |
| `docs/gm/spawn-agent.md`, `retire-agent.md`, `announce.md`, `ask-human.md`, `done.md` | The GM's actions | `builtins/gm-actions` | |
| `docs/gm/add-location.md`, `open-route.md`, `close-route.md` | The GM's map actions | `space/actions` | |
| `docs/gm/add-calendar-entry.md`, `update-calendar-entry.md`, `cancel-calendar-entry.md` | The GM's calendar actions | `calendar/actions` | |
| `docs/space/move.md` | `move` (scenarios without the ruleset's `walk`/`travel`) | `space/actions` | |
| `docs/pass.md`, `walk.md`, `travel.md`, `sleep.md`, `rouse.md`, `call.md` | The household ruleset's actions (its `pass` replaces the builtin) | `ruleset.actions` | `max-sleep-hours` (sleep), `max-call` (call) |
| `docs/following/follow.md`, `follow-stop.md` | Following | `following/actions` | |
| `docs/conversation/converse.md` | Starting a conversation | `conversation/converse` | `max-others`, `passes`, `line-limit` |
| `docs/conversation/reply.md`, `end-conversation.md`, `stay-silent.md`, `leave.md` | The speaking-turn tools | `conversation/speaking-actions` | `line-limit` (reply) |
| `docs/items/spend-time.md` | `spend-time` and its old alias `work-on` | `items.actions/timed-activity` | `alias` (`work-on-alias.md` + `" "`, or `""`), `on`, `act-on` (`spend-time-act-on.md` + `" "`, or `""`), `advised-doing`, `max-work-hours`, `max-work-items` |
| `docs/items/act-on.md` | Doing something to people at once | `items.actions/act-on` | `max-act-on`, `max-act-with`, `advised-doing` |
| `docs/items/find.md`, `look.md`, `take.md`, `open.md`, `put-in.md`, `put-down.md`, `give.md` | The item actions | `items.actions/actions` | |
| `docs/find-person.md` | `find-person` | `find-person` | `max-who` |
| `docs/hiring/recommend.md`, `send-for.md`, `engage.md`, `accept-place.md`, `decline-place.md`, `send-away.md`, `take-leave.md` | Hiring (and `take-leave`, shared with visits) | `hiring` | `take-leave`: `max-return-days`, `example-date` |
| `docs/hiring/return-time-format.md` | Fragment: the date format for returns | `hiring` | `example-date` |
| `docs/hiring/spend-time-candidate.md` | Appended to `spend-time` when a reference can be written for a candidate | `hiring/reference-activity` | |
| `docs/visits/invite.md`, `schedule-visit.md`, `cancel-visit.md` | Invitations; the GM's visits | `visits` | `invite`: `max-person`, `example-date`, `post` (`docs/visits/post.md` or `""`) |
| `docs/visits/time-format.md` | Fragment: the date format for visits | `visits` | `example-date` |
| `docs/shop/quote-goods.md`, `buy.md`, `send-up.md` | The shop | `shop` | `max-lines`; send-up also `send-up-max-hours` |
| `docs/architect/consult-references.md`, `price-list.md`, `draw-plans.md`, `show-plans.md`, `commission.md` | The architect, only with an office | `building` | setting money names |

## Judges, the steward and adjudicators (`judges/`)

The system messages of the narrow, checked model calls, and the descriptions of the one tool each
must call.

| Template | Used for | Rendered by | Parameters |
|---|---|---|---|
| `judges/scene.md` | The scene judge: what a job or an immediate act changed | `items.judge/judge-instructions` | `advised-aspect`, `max-aspects`, `max-made`, `max-impediments`, `max-summary` |
| `judges/brief.md` | The scene judge's "## The setting": the household's brief | `ruleset.rules/judge-brief` | `setting`, `mechanics` (`brief-architect.md`, `brief-deliveries.md` when those exist) |
| `judges/brief-architect.md`, `brief-deliveries.md` | Fragments of the brief | ″ | |
| `judges/inner.md` | The scene judge's lines on inner state | `inner/instructions` | `advised-value`, `keys` (`inner-keys.md`) |
| `judges/inner-keys.md` | Fragment: the inner-state keys every person has, and how many more | `inner/key-instructions` | `open`, `keys` (the scenario's keys and glosses, one line each) |
| `judges/steward.md` | The nightly steward | `items.steward/steward-instructions` | `advised-aspect` |
| `judges/steward-inner.md` | The steward's lines on sleepers' inner state | `items.steward` | `advised-value`, `keys` |
| `judges/finder.md` | The finder: the odds of finding something with `find` | `items.find/finder-instructions` | `advised-aspect` |
| `judges/conversation.md` | The conversation judge: how a conversation left its members' inner state | `conversation.judge/instructions` | `advised-value`, `max-summary`, `keys` |
| `judges/find-person.md` | Whom a `find-person` seeker means | `find-person/instructions` | |
| `judges/invitation.md` | Whom an `invite` means | `visits.judge/instructions` | `invitation-examples` (the pack's fragment) |
| `judges/shop-appraiser.md` | The shop's appraisal of requested goods | `shop/instructions` | `max-lead-hours`, `max-quantity`; the pack's `money-pricing.md` |
| `judges/tools/judge.md` | The scene judge's `judge` tool | `items.judge/judge-tool` | `activity` (act or work), `advised-aspect`, `max-summary`, `inner` (`judge-inner.md` + `" "`, or `""`), `effects` (`judge-act.md` or `judge-work.md`, which takes `max-made`) |
| `judges/tools/steward.md` | The `steward` tool | `items.steward/steward-tool` | `inner` (`steward-inner.md` or `""`) |
| `judges/tools/react.md` | The conversation judge's `react` tool | `conversation.judge/react-tool` | `advised-value`, `max-summary` |
| `judges/tools/finder.md`, `seek.md`, `invitee.md`, `appraise.md` | The finder's, find-person's, invitation judge's and shop's tools | their request functions | |
| `judges/tools/polish.md`, `appearance.md` | The persona writers' tools | `people/polish-request`, `people/appearance-request` | |

## Writers (`writers/`)

| Template | Used for | Rendered by | Parameters |
|---|---|---|---|
| `writers/persona.md` | The polish: circumstances, persona, manner and appearance from a person's traits | `people/instructions` | `max-circumstance`, `advised-persona`, `max-persona`, `max-manner`, `advised-appearance`, `max-appearance` |
| `writers/appearance.md` | A runtime candidate's appearance | `people/appearance-request` | `advised-appearance`, `max-appearance` |

## The GM (`gm/`)

| Template | Used for | Rendered by | Parameters |
|---|---|---|---|
| `gm/preamble.md` | The start of the GM's system message, before the scenario's GM brief | `prompt/gm-preamble` | |

## Setting packs' own fragments

Under `resources/settings/<id>/prompts/`. Each pack must have all three.

| Fragment | Used in | Says |
|---|---|---|
| `money.md` | `rules/sections/shopping.md` | How the currency works, for agents |
| `money-pricing.md` | `judges/shop-appraiser.md` | How to price in the base unit, for the shop |
| `invitation-examples.md` | `judges/invitation.md` | Examples of the impossible and the possible-but-unlikely |

A pack may also override any shared template with a file at the same relative path. Neither pack
does so today.

## Still in code

Model-facing text that isn't in a template yet. `docs/prompt-extraction-design.md` sorts it and
proposes how to move it.

- **Request framing:** the section headings and the prose between the data in every request: the
  agent request (`prompt/agent-request`: "## Your role", "## Recent events you witnessed",
  "# Now", "## Your notes" ...), the calendar section (`calendar/character-section`), the
  conversation closing (`prompt/conversation-closing`, `how-to-answer`, `conversation-lines`), the
  GM's user message (`prompt/gm-messages`, with the calendar review sentence), and the judges'
  and steward's user messages ("# The activity", "## Suggested aspect keys", "Call `judge` now.").
- **Replies to tool calls:** each action's `:reply`, and the engine's `error:`/`rejected:`
  protocol text (`engine.turn/handle-call`).
- **Rejection reasons:** the strings returned by `:valid?` and the reason helpers in the
  mechanics (`physical/sight-reason` and the like).
- **Narration:** the `describe` methods (`manor.narrate` and each mechanic), which make the event
  lines every agent reads.
- **View text:** phrases inside an agent's view (what someone who can't see is told, following,
  pending orders).
- **Schema field descriptions:** `:description` in a few judges' and the calendar's argument schemas.
- **Not prompts, staying in code:** the toy scenarios' own docs and rules (`village`, `treasure`,
  `quiz`), mock policies and offline stubs, and operator-facing text (panel, transcript, errors).
