# Calendar events and GM review

Status: **implemented; parent validation and independent review passed (mock/offline)**. Based on the user's decisions in the
events-system conversation. This implements roadmap B4, but is not a general replacement for
every timed mechanic in the engine. The scenario-authoring guide records the runtime contract.

## 1. Purpose and scope

Give the world a calendar of things people know are scheduled: holidays, expected visitors,
rent dates, and private appointments. The GM can also have entries nobody else knows about.
When an entry comes due, the engine calls the GM before characters act, to decide whether
anything needs doing. Doing nothing is a successful review.

This is an **in-world schedule**, not the engine's event log and not a list of guaranteed
outcomes. An expected payment does not credit the household account. A scheduled visit does
not spawn or move a person. An entry may describe something whose mechanics do not exist yet.
The GM can use the available tools, leave it alone, or ask the operator if necessary; prose
cannot change hard state.

First version:

- One-off entries with a specific date, or a specific date and time.
- Public knowledge, explicit selected-agent knowledge, or GM-only knowledge.
- An optional separate GM-only note on any entry. Ordinary entries need only a description.
- A rolling calendar section in character context, refreshed by date, defaulting to about a month.
- GM review before character turns, with date-only entries reviewed at 05:00 internally.
- Creation, editing, cancellation and audience changes by scenario authors, the operator and GM.

Not included: recurrence, approximate dates, multi-day spans, reminders, character appointment
tools, automatic extraction from conversations, or migrating travel, deliveries and work jobs
onto a universal scheduler. The work judge does not gain scheduling in this version (section 8).

## 2. Existing pieces to reuse

- `manor.clock` maps rounds to local simulation dates and times. Use this clock, not wall time.
- `manor.world/visible-to?` already distinguishes `:all`, a set of agent IDs, and `:gm`.
- `manor.engine/init!` builds the world, after applying the run's `:start` clock override.
- `manor.engine.round` already has pre-turn world processing and an after-turn GM activation.
- The GM has a `done` action; its prompt already permits doing nothing.
- `manor.prompt` puts stable instructions/reference first, recent events next, and volatile
  time/view/notes last. Turns and conversations share the same request builder.
- World changes, operator commands, persistence and branching already go through emitted events.

The earlier B4 sketch queued arbitrary engine events by round. Keep this feature narrower:
calendar records plus a due-for-review queue, with actual effects still owned by existing
validated actions. A calendar entry must not contain an arbitrary engine-event payload to emit.

## 3. Entries and configuration

Scenario configuration:

```clojure
:calendar {:lookahead-days 30}
```

The scenario's `:init` can return `:calendar`, a vector of starting entries. World files can
also have a top-level `:calendar` vector, passed through `worldfile/load-world` to `:init`.
This is opt-in and requires a simulation clock. Scenarios without it keep their existing
prompts and tools. The internal date-only review time is 05:00; it is not a character-facing
start time or a field authors need to repeat on each holiday.

Illustrative entries (not proposed additions to the bundled world yet):

```clojure
{:id "martinmas-1904"
 :date "1904-11-11"
 :description "Martinmas."
 :visible-to :all}

{:id "michaelmas-rent-audit-1904"
 :date "1904-10-28"
 :description "The Michaelmas rent audit is due; the estate expects the half-yearly rents."
 :visible-to #{"lord-claudeworth" "hale"}}

{:id "smith-visit-1904-10-21"
 :at "1904-10-21T14:00"
 :description "Mr Smith is expected at the house."
 :visible-to #{"lord-claudeworth" "hale"}
 :gm-notes ""}

{:id "private-review-1904-10-22"
 :at "1904-10-22T10:00"
 :description "Consider whether a visitor should arrive today."
 :visible-to :gm}
```

Fields:

| Field | Meaning |
|---|---|
| `:id` | Stable, unique entry ID; distinct from the engine log's numeric event ID |
| `:date` or `:at` | Exactly one: ISO local date, or ISO local datetime |
| `:description` | Short description, shown only to the entry's audience and GM |
| `:visible-to` | `:all`, an explicit set of character IDs, or `:gm`; default `:gm` |
| `:gm-notes` | Optional short text, never included in character projections |

Validate structure, date/time formats, unique IDs, audience IDs and bounded text lengths.
Do not inspect the meaning of the prose with word lists or regexes. Validate starting audience
IDs after the initial cast has been created. Use one shared entry validator for initialization,
operator commands and GM tools. Model tool arguments still use the normal action decoder.

Store records under an engine-owned world key such as `:calendar`, keyed by entry ID, with
engine-managed lifecycle metadata separate from the authored fields. Persist only EDN data;
parse dates for comparisons, rather than storing Java time objects in the world.

## 4. Who knows what

Public entries appear to every character, including characters who join later. Restricted
entries appear only to the explicitly listed IDs; a new butler does not inherit the previous
butler's calendar. GM-only entries never appear in character context. The operator can see all
entries, as with existing private state.

The GM sees the description, audience and any optional GM note. Keep the instruction brief:
"Review these due calendar entries and decide what, if anything, to do. Calling done is fine."
No elaborate GM-note protocol or prescribed scenarios are needed.

A character can tell someone about an entry in ordinary conversation. That listener can remember
it through the existing conversation and note mechanisms, but their calendar is not automatically
updated. There is no knowledge inference or sharing subsystem in this version.

The operator and GM may explicitly change an entry's audience. Removing someone from it stops
future calendar projection; it cannot erase facts already heard or written in their notes.

Calendar mutation and review-log events are GM-only by default, **even for public entries**:
mutation payloads may contain GM notes, audience metadata or old private text. Characters get
only a deliberately constructed projection of permitted fields, not the raw calendar records.
A GM announcement is a separate action with its own audience; reviewing an entry does not
broadcast it or its private note.

## 5. Character context and caching

Add a `## Your calendar` section at the beginning of the user message, before recent witnessed
events. This leaves the stable system message and tool definitions unchanged, while keeping the
calendar ahead of fast-changing material. Use it in both ordinary turns and conversations.

A short framing sentence is enough:

> Your in-world calendar: dates and arrangements known to you. These are scheduled or expected
> occasions, not a record that they have happened.

Show absolute dates and explicit times only where the entry has one:

```text
11 November 1904 — Martinmas.
21 November 1904, 14:00 — Mr Smith is expected at the house.
```

Do not show 05:00 on date-only entries, review status, engine event IDs, scheduling metadata,
GM notes, relative countdowns or a changing "last updated" timestamp. The heading should not
contain the current time either. Deterministic sorting is by date/time, then entry ID, with
date-only entries before timed ones on the same date.

Default horizon: **today through 30 days after today**, inclusive, using simulation dates.
This is an approximately month-long rolling view, not a calendar-month boundary. Make the
lookahead configurable in days. Recompute its date boundaries once per simulation day (a pure
projection from today's date is sufficient; no persisted daily-refresh event is needed).

Keep all of today's non-cancelled entries visible through midnight, including timed entries
whose appointed hour has passed and entries the GM has reviewed. Their wording describes the
schedule, not confirmation that it occurred. On the next date they leave the displayed window.
The normal event log and notes hold what actually happened.

With no edits, the section is byte-identical across calls on the same day. Adding, editing,
changing visibility or cancelling an entry updates it immediately; cache stability must not
hide a correction. No daily per-character LLM call or calendar summary is needed.

## 6. Due processing and the GM

### Ordinary rounds

At the pre-turn boundary, after existing arrivals, releases, finished work, steward and world
systems, collect entries due for review. Run one calendar GM intervention for the batch before
any character turn. Existing requests/prayers, periodic check-ins and scenario GM triggers
remain after character turns. Two interventions in one round are possible if there is also a
later request; do not move the calendar back after turns merely to combine them.

- A timed entry is due at its stated datetime.
- A date-only entry is due for GM review at 05:00 on its date. This does not define when the
  occasion starts: the character's calendar shows the date, including before 05:00.
- If round boundaries skip the exact time, use the first round at or after it.
- Use persisted pending/due/reviewed state so an entry is reviewed once, not every round that
  a condition remains true. Sort due entries deterministically before presenting the batch.
- A normal completed intervention, including an immediate `done`, marks the batch reviewed.
  Review is not a claim that the payment, visit or other occasion actually happened.
- A failed/forfeited intervention must not be marked reviewed. Preserve due work and use the
  existing error/pause handling, without adding a retry loop or degraded-mode mechanism.
- If the GM is disabled, retain due entries without pretending they were reviewed. Characters
  still see their permitted calendar. Review can happen when the GM is enabled later.

Provide the due records directly in the GM request, independently of the recent-event window.
Refactor the GM activation helper as needed so the pre-turn call and after-turn call can reuse
activation/action handling, without feeding calendar triggers into both stages.

Do not recursively run the GM when it creates another immediately due entry. New due work
created during an intervention waits for the next pre-turn boundary. Likewise, a due entry
added between rounds is processed at the next boundary. Use the existing GM action limit and
cost controls; no unbounded same-round scheduling loop.

### Fresh-run initialization exception

Keep the startup exception next to `engine/init!`, after the final clock override and cast
creation, not as a "maybe this is startup" branch scattered through round processing.

For a **fresh** run starting at or after 05:00, queue any still-unreviewed date-only entries for
that same date as due. The first pre-turn boundary reviews them before any character acts.
Starting before 05:00 leaves them pending until that day's normal review time. This uses the
same due queue and review path as ordinary rounds; `start-run` itself need not make a model call.

Earlier dates are historical, not a backlog to replay. Initially supplied timed entries whose
time is already past are also historical; the special catch-up is only for today's date-only
entries. Historical entries do not produce retroactive effects. A past timed entry on today's
date can still appear in today's calendar as an arrangement, not a completed occurrence.

Loading or branching a run uses recorded calendar lifecycle state. It does not run fresh-world
initialization or schedule a second startup review.

## 7. Mutation, lifecycle and persistence

GM tools: `add-calendar-entry`, `update-calendar-entry`, and `cancel-calendar-entry`.
Keep their descriptions short and neutral; they are capabilities, not encouragement to invent
occasions. Corresponding operator helpers go through the existing single-thread executor.
No character receives these tools.

Use shared command-to-event functions, with pure reducers, for example:

- `:calendar/added` — store a validated entry, initially pending or historical as appropriate.
- `:calendar/updated` — apply a checked edit to an existing non-cancelled entry.
- `:calendar/cancelled` — retain its identity, exclude it from future display/review.
- `:calendar/due` — add an entry to the persisted review queue.
- `:calendar/reviewed` — acknowledge the batch after a completed intervention.

These are implementation names, not character-facing vocabulary. Keep retired/cancelled IDs
reserved to avoid confusing references. Review status need not change character-facing text.

Edits to description, GM notes or audience do not by themselves cause another review. Moving
a pending/due entry to a future date/time clears its old due state; explicitly rescheduling a
reviewed entry to a future date/time makes it pending again. Editing a due entry keeps the
queued record current. Cancellation removes it from the due queue as well as the calendar.
If the GM cancels/reschedules a batched entry during review, the final acknowledgement must
not overwrite that newer state; acknowledge only the still-due revisions actually reviewed.

For live commands, reject past timed appointments and dates before today. A date-only entry
for today added after 05:00 becomes due at the next pre-turn boundary. This is ordinary handling
of a newly added entry, not another startup exception.

All mutations and acknowledgements use `emit!`; reducers do not read clocks or call models.
Replay reproduces records, cancellations and the review queue without processing due work or
calling out. Load/branch continue from that state, including a due but unreviewed batch.

World-file loading/export should round-trip the live calendar, including stable IDs and
lifecycle markers needed not to resurrect cancelled or reviewed entries. Refresh `map.edn`
on calendar changes too. Export is operator data and may contain private entries/GM notes;
it must never be used wholesale as character context. An exported map is still not a substitute
for loading a saved run and its full history.

## 8. Judge scheduling: leave it out for now

The work judge currently returns one checked verdict about a bounded scene, not arbitrary GM
actions. Adding calendar output would require a new verdict field, date/audience validation,
privacy scope rules and result-to-calendar event translation. The answer schema is also shared
with the steward. This is more than exposing an existing tool, so defer it.

If that later becomes a small, well-scoped extension, reuse the calendar validator and mutation
functions. A single neutral instruction could then permit recording an occasion already
established by the activity. Do not encourage judges to generate new occasions, and do not
add scheduling to the conversation judge or steward as part of this proposal.

## 9. Money and other unsupported effects

The household has starting funds and spending reducers, but no supported credit action.
The roadmap separately records income/account credits: an engine-validated credit with amount,
reason and a decision about who can invoke it. It is not a prerequisite for the calendar.

Until then, a rent-date entry may be listed and reviewed without changing the account. Avoid
saying a payment has arrived when the engine cannot record it. Claudeworth's GM brief currently
forbids both income and promises of future payments: the calendar implementation should allow
scheduled expectations while keeping the no-unrecorded-income rule. Revise it again when an
actual credit action exists.

Visitors and holidays likewise do not acquire new mechanics here. A holiday can simply be
known, with no GM action. A visitor may need existing validated actions or later mechanics.

## 10. Build outline and acceptance

1. **Data and commands.** Shared validation, pure reducers, initialization/world-file support,
   operator commands and GM tools. Update the scenario-authoring contract when implemented.
2. **Prompts.** Safe character projection before recent events; due records and optional notes
   in the GM request. Minimal wording updates, including the no-income brief.
3. **Review path.** Pre-turn batching, acknowledgements, initialization catch-up and replay/load/
   branch behavior. Leave the existing after-turn GM path intact.
4. **Fixtures and documentation.** Use small test-owned worlds and mock GM policies. Do not add
   historical dates or an annual calendar to the bundled scenario without reviewing that content
   (the later user-approved holiday seed is recorded below).

Suggested homes: a small `manor.mechanics.calendar` module for entry rules, projections, commands
and reducers; `engine/round.clj` for activation timing; `engine.clj` for initialization/operator
API; `prompt.clj` for presentation; `io/worldfile.clj` and `engine/run.clj` for world-file support.
Keep it opt-in and avoid building a generic job scheduler.

Acceptance checks (free, mock/offline):

- Public, selected-agent and GM-only entries project correctly; no GM notes or raw mutation
  payloads leak through any turn, conversation, recent-event or reference projection.
- The same-day calendar prefix stays identical as time/log/view/notes change, but changes after
  an edit or on the next date. The horizon is 30 days ahead by default and configurable.
- Date-only entries display without a time and remain through the whole date, including before
  05:00 and after review. Timed entries retain their explicit time and remain through that date.
- Exact and crossed due times run the GM before character turns; multiple due entries make one
  intervention; `done` acknowledges them once; ordinary prayers still run after character turns.
- Fresh starts before, at and after 05:00 cover the startup exception with the overridden clock;
  earlier dates and past timed entries are not replayed. Load/branch do not repeat the exception.
- Creation, edits, audience changes, cancellation and rescheduling validate properly; mutation
  during a GM batch is not overwritten by its acknowledgement. New due entries do not recurse.
- A failed review remains due; a disabled GM does not silently consume it. Cost/action controls
  remain in force.
- Replay matches the live world; load, branch and world-file round-trips preserve calendar state.
- A rent-date review changes no money and an expected visitor changes no physical presence unless
  a separate supported action does so. Scenarios without a calendar remain unchanged.

No real-model run is needed to review this proposal. Implementation should use mock/offline
checks first and run the full suite before any commit, following the project's normal rules.

## 11. Implementation outcome and validation

- `manor.mechanics.calendar` owns authored schemas, shared validation/command-to-event translation,
  pure reducers, revision-aware due lifecycle, safe character projection and calendar export.
  Runtime state is `:calendar {:lookahead-days n :entries {id record} :due {id revision}}`;
  each record has engine-managed `:lifecycle {:status ... :revision ...}`. Only init/world-file
  import may supply lifecycle; live commands cannot set it. Text limits are 600 characters for
  description and 1200 for optional GM notes.
- Engine initialization runs calendar validation after final clock override and cast creation.
  Rounds collect/review once after existing pre-turn systems. The seat's actual completion
  result is checked: caught errors return no finals, and no-action/nudged exhaustion is not a
  successful review. Immediate `done` or a valid action-limit completion acknowledges the batch.
  Errors preserve due work and use pause requests, without a retry loop.
- Pre-turn calendar interventions do not consume the ordinary GM inbox or reset its periodic
  check-in boundary. The after-turn activation path remains separate, with its original behavior
  in opt-out scenarios. Disabled or already cost-blocked calendar review remains queued.
- GM tools and executor-queued operator/REPL helpers expose add/update/cancel; updates include
  audience changes. Character tools, judge/steward schemas, money and physical presence are not
  extended. Claudeworth's brief permits scheduled expectations but still forbids unrecorded income.
- World-file `:calendar` is an entry vector passed unchanged through loading; engine validation
  uses actual spawned IDs. Export preserves exact IDs and lifecycle and calendar mutations refresh
  `map.edn`. **Qualified export limitation (approved):** existing export intentionally excludes
  spawned cast members. An exported audience referring to one remains intact, and a new run
  rejects it until that cast record is supplied. Saved load/branch fully preserves those people
  and their calendars. Do not drop recipients, omit entries or broaden visibility to bypass this.
- Test-owned `calendar-fixture` and `calendar-test` cover privacy in turns/conversations and logs,
  prefix placement/stability/rollover/horizon, date-only/explicit-time rendering, exact/crossed
  boundaries, startup override/history, batch ordering and `done`, disabled/failed/cost guards,
  in-review revisions/rescheduling/cancellation, action limits/no recursion, replay/load/branch,
  live map refresh and lifecycle round-trip, retained/missing audience IDs, and no income/presence
  effects. Bundled scenario dates and annual content were deliberately not added.
- Independent review found that `:done?` also covers twice-nudged exhaustion after partial
  progress. Parent reproduced and fixed it: acknowledgement now also requires an accepted `done`
  or the GM action-limit count. Regression tests use genuine tool-less responses (the normal mock
  substitutes `pass` for nil), and cover both failed review and successful recovery after a nudge.
  Targeted independent re-review passed with no remaining findings.
- Parent validation passed: focused calendar tests (14 tests, 165 assertions) and the full suite
  (292 tests, 5,003 assertions), with zero failures/errors. No real-model run was made. The full
  suite ran from a temporary symlinked checkout view with its own `test-runs`, because existing
  suite fixtures delete that directory; this preserved the worktree's test runs and user data.
  Existing asynchronous transcript-write warnings from suite cleanup were non-failing.

No scope expansion: recurrence, generic scheduling, character/judge scheduling, credits and
migration of existing timing remain deferred. Household income/account credits remain roadmap B7.

### Bundled holiday seed

The user subsequently approved a proof-of-concept holiday calendar for both Claudeworth worlds.
`resources/calendars/england-1904-1905.edn` holds 58 public date-only entries: familiar English
holidays, the four quarter days, church feasts and seasonal observances. The two scenarios share
one initializer wrapper; Lodge and the fixed test household do not enable the calendar.
World-file entries override catalogue entries by ID, without dropping authored duplicates or
bypassing normal validation. Storage is independent of the 30-day character projection.

The list is intentionally illustrative rather than an exhaustive historical almanac. Easter
is 3 April 1904 / 23 April 1905; August Bank Holiday uses the period's **first** Monday, not the
modern last Monday. New Year's Day and May Day are observances, not English bank holidays.
Quarter days do not assert that rents arrived, nor do holidays grant leave or close shops.
No annual generation, special holiday mechanics or existing-run migration was added.

Useful references for the content: [the 1871 Act's holiday schedule](https://www.legislation.gov.uk/ukpga/Vict/34-35/17/pdfs/ukpga_18710017_en.pdf),
[Western Easter dates](https://www.census.gov/data/software/x13as/genhol/easter-dates.html), and
[English quarter days](https://www.legalchoices.org.uk/dictionary/quarter-days).
Focused mock/offline tests cover both seeds, public prompt projection, the year boundary,
once-only GM review without money/presence effects, overrides and replay. Validation:
`clojure -M:test` passed (301 tests, 5,652 assertions, no failures/errors); no real-model run.
The suite's existing non-failing asynchronous transcript-write warning was also present.
