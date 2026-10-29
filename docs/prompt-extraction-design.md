# Moving the rest of the prompts out of code

Status: proposal, not started. `docs/prompts.md` catalogues the templates that exist.

## 1. Where things stand

`docs/settings-design.md` moved the setting-dependent prompts into `resources/prompts/` (its
groups B and C). A later pass (2026-10-29) moved the rest of the prompts that move verbatim as
whole blocks: the scene judge's, steward's, finder's and conversation judge's instructions, the
inner-state lines, the GM preamble, the turn closing, reminders and nudges, every tool description
of the household mechanics and builtins (with the kind and per-turn hints), and the judges' and
writers' tool descriptions. The prompt capture diffed empty before and after, and the variants
the mock days don't reach (the `work-on` alias, `spend-time` with `act-on`, the `act` hint,
`spawn-agent`) were compared against HEAD directly.

What remains is different in kind. It isn't long fixed text with a few numbers in it. It's short
strings woven into code that computes names, ids, times, plurals and the viewer's perspective, in
large numbers:

| Kind | Where | Roughly how much |
|---|---|---|
| A. Request framing | `prompt/agent-request`, `prompt/conversation-closing` (with `how-to-answer`, `how-to-answer-speechless`, `conversation-lines`), `prompt/gm-messages`, `calendar/character-section`, the judges' and steward's user messages | 6 builders, about 40 strings, several conditional |
| B. Replies to tool calls | each action's `:reply`; the engine's `error:`/`rejected:`/"(Your text was taken as ...)" in `engine.turn` | about 27 replies, about 8 engine strings |
| C. Rejection reasons | `:valid?` and the reason helpers (`physical/*-reason`, `items.actions/*-reason`, hiring, visits, shop) | about 66 validators, a few hundred strings |
| D. Narration | `describe` methods in `manor.narrate` and the mechanics | about 100 methods |
| E. View text | phrases inside an agent's view (sight, following, pending orders, the calendar) | a few dozen |
| F. Schema field descriptions | `:description` in argument schemas (calendar entries, judges' answers) | 17 |

None of it is setting-specific today (phase 2 neutralised the wording), so the setting packs don't
need it moved. The reasons to move it are to read and tune the prompts in one place, and to let a
pack or a scenario reword them without code.

## 2. Why the current mechanism doesn't fit as it is

- **One file per prompt** suits paragraphs. For C and D it would mean hundreds of one-line files,
  each with its own path in code, and reading a mechanic's messages would mean opening dozens of
  files.
- **No conditionals in templates** is right, but for these strings the conditions are the
  substance: "you" or a name, "has" or "have", a time or "round 6", whether the item is named. If
  a template only gets the pieces that are left, it ends up as "{{a}}{{b}}{{c}}" and says nothing.
- **Tests assert wording** in many places (`str/includes?` on reasons and narration). Each move
  changes those tests from literals to lookups or leaves them testing the template.
- **Coverage:** the prompt capture sees narration and framing (they're in every request) but only
  the rejections and replies that happen in the mock days.

## 3. Proposal

### 3.1 A: request framing, as templates (recommended next)

This is the part worth doing. Every request carries it, and it's where tuning happens (the
conversation rules especially).

- `agent/request-system.md` and `agent/request-user.md`: the section skeletons, with
  `{{preamble}}`, `{{role}}`, `{{who}}`, `{{situation}}` (a fragment or `""`), `{{reference}}`,
  `{{calendar}}`, `{{events}}`, `{{now}}`, `{{view}}`, `{{notes}}`, `{{closing}}`. Code still
  builds the JSON and the event lines.
- `agent/conversation/`: the conversation closing as fragments, the way `rules/sections/` does
  it. That means `intro-initiator.md`/`intro-member.md` (today's `conversation-lines`),
  `how-to-answer.md` with `{{extra-actions}}`, `{{choices}}` and `{{changes-hands}}`, the
  speechless variant, and the "Pass n of m. Your turn: ..." lines. The parts that name people
  ("Bert started a conversation here with you and Daisy") stay as code that fills one
  `{{members}}` parameter.
- `gm/request.md` and `gm/calendar-review.md` for the GM's user message.
- `judges/scene-request.md`, `judges/steward-request.md`, `judges/conversation-request.md`: the
  prose around each judge's JSON ("# The activity", the labels, "Call `judge` now."). The labelled
  lines (`- Worker:`, `- Luck:`) become one template with optional lines passed as fragments.
- `calendar/section.md` for "## Your calendar".

Before starting, extend `dev/prompt_capture.clj` so it covers the variants a mock day misses: a
speechless member and initiator, a speaking turn with extra actions, the final word, a GM call
with due calendar entries, and a judge request without inner state. Then move in small commits,
each diffing empty.

### 3.2 B, C, E: message catalogues

For short strings, one EDN file per mechanic under `resources/prompts/messages/`
(`physical.edn`, `items.edn`, `hiring.edn` ...), each mapping a keyword to a `{{key}}` string:

```clojure
{:sight/cant "your sight is disabled ({{cause}}), so you can't {{doing}}"
 :take/not-here "{{item}} isn't here"}
```

- `(prompts/message world :physical/sight-cant {:cause c :doing d})`: the same `fill` and
  missing-key rule as templates. A pack's `prompts/messages/<file>.edn` merges over the shared one
  key by key, so a pack rewords one message without copying the file.
- Keys are namespaced by the file, so `:physical/...` lives in `physical.edn`. A missing key
  throws, like a missing template.
- Code keeps all the choosing (which message, plurals, "you" or a name). A string that needs a
  conditional in the middle becomes two keys.
- Order: replies (B) first, since agents read them at once and there are few; then the reasons
  (C) mechanic by mechanic; view text (E) alongside its mechanic.
- Tests that assert wording should assert the key's text through `prompts/message`, or keep the
  literal where the exact sentence is what's tested.

### 3.3 D: narration — decide first whether to do it at all

Narration is the largest part and the least like a prompt: it's the engine describing what
happened, read as the recent-events lines. Moving it would use the same catalogues
(`narration.edn` per mechanic), with each event's text in two forms, `:self` ("You give ...") and
`:other` ("Hale gives ..."), chosen by `describe`. Replay and the transcript check it for free: the
prompt capture shows every narrated line of the mock days.

Recommendation: leave narration in code unless a second setting or a scenario actually
needs to reword it. Its wording is neutral, it's tested heavily, and a catalogue of about 200
two-form strings is harder to read than the `describe` methods it would replace.

### 3.4 F: schema field descriptions

Leave them. There are 17, they describe the shape of an argument next to its schema, and moving
them would mean resolving templates inside malli schemas when tools are built. If one ever needs a
setting's wording, `->tool` can walk the schema with the world, as it already does for
`:advised-max`.

### 3.5 Out of scope

The toy scenarios' docs and rules (`village`, `treasure`, `quiz`) are scenario code and move only
if those scenarios move onto setting packs. Mock policies, offline stubs, test fixtures and
operator-facing text (the panel, the transcript, operator errors) aren't model prompts.

## 4. Order and size

1. Capture coverage for the framing variants (small).
2. A: request framing, one builder per commit: agent request, conversation closing, GM, judges,
   calendar (medium; the conversation closing is the fiddly one).
3. The `prompts/message` catalogue mechanism with tests, then B (small, then small).
4. C and E per mechanic, by need (medium, mechanical).
5. D only on request.

Each step leaves `clojure -M:test` green and the prompt capture diff empty, and adds its
templates to `docs/prompts.md`.

## 5. Questions for the user

- Should rejection reasons and replies (B, C) move at all, or only the framing (A)? A alone covers
  everything a model reads at the start of every call. B and C are what it reads after a mistake.
- EDN catalogues for short strings, or keep markdown with one file per message? This proposal
  says EDN, because a mechanic's messages can then be read on one page.
- Narration: leave it in code (recommended) or plan it as well?
