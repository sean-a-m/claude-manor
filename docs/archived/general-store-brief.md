# Claudeworth general store: completed build brief

Status: implemented on `claudeworth-general-store`; archived with the feature commit.
Offline tests, bundled mock-day coverage and a bounded live purchase check pass.
The final suite passes 111 tests and 1602 assertions. Scope: unchanged opening hours, portable purchases only, no bargaining,
integer old pence, no old-run compatibility.

Verification: `clojure -M:test` passes. A separate 47-round mock day at
`test-runs/general-store-offline-check/transcript.md` was read: two quoted/accepted purchases,
no errors, rejections or forfeits, exact replay equality, $0 cost. The clerk hibernates while
unneeded and serves both customers. The adjudicator receives the store profile in recorded
requests. A later review reran all 110 tests (1519 assertions) successfully and a separate 47-round
bundled mock day (`test-runs/general-store-review-mock/transcript.md`): two purchases,
zero errors/rejections/forfeits, exact replay equality.

Live review: `old-test-runs/general-store-live-1792264704764/transcript.md` preserves an
eight-round run using the production scenario/store profile with only Hale and Briggs,
initially at the counter, the default DeepSeek model, GM off, bridges offline, and a $0.20
cap. Cost: $0.011718168 across 40 calls, including four appraisals. Hale accepted four
quotes: two tea packets (16d), four pounds of sugar (12d), seven pounds of flour (14d),
and six candles (6d). Funds fell by exactly 48d (4s), to 719952d (£2999 16s), the 19
purchased items were carried home and stored, and replay matched the live world. Zero
engine errors, rejections, forfeits or adjudication failures. Briggs hibernated again
once Hale left. The run includes `verification.edn`, `world-start.edn` and `live-check.clj`.

Live coverage limitation: the review harness attempted to give Hale an errands list via
`:goals`, but agent spawning does not retain that field. Hale therefore improvised the
ordinary shopping errand above. Needles/soap and the planned smartphone, telephone
exchange and bulk-delivery refusal checks were NOT exercised. The existing mock tests
check refusal mechanics, not live historical judgement. No second paid run was started.

Live follow-ups (not fixed by this review):

- Preserve unit/package meaning on purchased items (`shop/buy-events`). It copies the name,
  description and condition but drops the quote's `:unit`. Sugar items thus lose their
  explicit one-pound measure. The flour appraisal also described the complete seven-pound
  order as one bag, but the engine repeated that description across seven items. Ask for a
  description of ONE unit, and retain its measure in the resulting item's supported fields.
- Return the recorded quote ID and total to the clerk with the immediate query result.
  Currently `quote-goods` returns only the appraisal, not the created offer. In round 2,
  Briggs wondered whether the offers had actually been recorded. They were present for the
  next turn, so this caused no failure, but it is avoidable confusion.
- Each separate quote needs a final `buy`, hence a separate half-hour turn: this four-line
  shopping basket took until noon to buy. That follows the current contract, but consider
  whether multi-line checkout is wanted later.

## Goal

Add a male shopkeeper agent. Replace the fixed catalogue with requests to him: he asks a
narrow LLM adjudicator whether the requested goods are plausible stock for an Edwardian
village general store, and what they cost. Engine code validates the answer and owns all
money and goods changes. Customers must authorize their own purchases.

## Findings at the start of the work

- `resources/worlds/claudeworth.edn` has fixed `:shop` goods but no shopkeeper. There is no
  literal closed-sign item or text. The store has hours 08:00–18:00; `claudeworth/observe`
  supplies `:open-now?`, and `building/actions` refuses purchases outside those hours.
- The architect offers the right interaction pattern: customer asks an agent, that agent
  obtains a structured result, the engine records it, and the customer separately commissions
  it. Its MCP server uses a fixed price list, not an LLM.
- `engine/ask-judge` already supplies the model-call machinery needed here: the run's LLM,
  `:judge-model` fallback, structured response checking, one correction attempt, usage and
  transcript recording, and graceful failure. Its request and verdict rules currently belong
  to `manor.items` and must remain distinct from purchasing rules.
- Mechanics tests use `test/fixtures/household.edn` and `household-day.edn` through a test-only
  scenario. Do not reconnect them to the production world or its cast. Roadmap C13 also
  tracks broader cast safety and removing the old optional-character machinery; this proposal
  does not mark that whole item done.

## Proposed interaction

1. Customer visits and uses `say` to ask the shopkeeper for goods and a quantity.
2. Shopkeeper uses `quote-goods {for, item, qty}` (query). `for` must name a present, active
   character; spending is not restricted by role. Only a shopkeeper at a general-store service can use it.
3. The engine asks the adjudicator during the shopkeeper's turn. A valid answer is either:
   - unavailable, with a short diegetic reason; or
   - available, with a normalized portable item name, explicit unit/package, positive integer
     unit price in pence, description and condition. Code multiplies unit price by the requested
     quantity; the model cannot change quantity or supply a conflicting total.
4. The engine stores a quote bound to this buyer, shop, clerk, goods, quantity and total price.
   The result goes to the clerk. A checked offer/refusal event informs the customer and people
   present, without requiring the clerk to repeat the model call's answer in speech.
5. Customer uses `buy {quote-id}` (final). Code checks the buyer, co-location with the clerk,
   service location, quote unused, existing opening hours, and current funds. Then it deducts
   the recorded price and creates the goods through existing reducers/item-added events.
   The quote is consumed exactly once.

Availability does not reserve finite stock: there is no stock ledger or replacement fixed
inventory. A quote fixes that offer's price; accepting it never asks the model again. For the
first version, quotes need no clock expiry; they remain usable until bought, provided the
buyer and clerk are back together at the shop. A later request can obtain a new offer.

The shopkeeper cannot spend another agent's money. The model cannot choose the buyer, charge
an account, set IDs/ownership/holders, change unrelated state, or create arbitrary item fields.
Code supplies these. Operator follow-up: all characters may now spend from the household
account, both on goods and on building commissions. The spender-role allowlist is removed;
all characters see the account. Social station and instructions determine who attempts to
spend. Quotes remain buyer-bound, and funds, plan access, location and hours are still checked.
The GM does not receive character spending tools. Offline tests exercise purchases by every
bundled role and an unrelated bystander role.

The bundled world also has an empty, open sugar jar in the kitchen and a morning concern in
the countess's persona: send Nellie for one pound of sugar, then have her return and report.
Nellie's existing social brief is unchanged. These motivations have not yet been live-tested.

## Adjudication and code sharing

- Add a small structured-adjudication helper, factoring only the common call/check/retry loop
  out of `engine/ask-judge`. Preserve the existing work judge's prompts and checked outcomes.
- Allow an immediate query action to request engine-run adjudication rather than an external
  bridge. Its narrow request builder and result checker are pure functions; checked outcomes
  feed `:on-result` event construction. Document the new query contract.
- Keep the API small: request builder, response checker, mock responder and logging identity.
  Do not build a general workflow framework or add a new MCP process for an internal model call.
- Use the existing run LLM and `:judge-model` default, with separate logged identity/task for
  store appraisal. Every attempt counts in run usage. Respect normal query limits. Tests/mock
  runs must route appraisal calls to a deterministic responder, never to OpenRouter.
- The appraisal prompt contains only the setting/date, shop description/policy and requested
  goods/quantity. No full world, private notes, household balance or unrelated agent context.
  Treat the free-text request as data, not instructions.
- Reuse the architect's prepare/validate/store/accept pattern, not its room-price arithmetic,
  structural rules or MCP transport. Avoid a generic quotes/plans abstraction for two small,
  different domain records unless duplication actually warrants one.
- Invalid output gets one correction attempt; repeated invalid output or transport failure
  creates no quote, goods or debit. Distinguish technical failure (operator log, neutral retry
  message to clerk) from a valid in-world refusal.

## What the store adjudicator receives

Every appraisal includes an engine-authored store profile, not just the item request. Keep
this profile as world-file data associated with the store, separate from the clerk's dialogue
and from decorative room descriptions. The engine selects it from the clerk's actual service
location; the clerk cannot supply a different store type or override the policy.

The request includes:

- Setting and current date: rural England, Claudeworth village, 1904.
- Store name, type, ordinary scope and limitations. Proposed profile: a modest village general
  store selling everyday household necessities, groceries, dry goods, haberdashery, stationery
  and simple domestic tools; not a department store, specialist dealer or magical source of
  anything requested. Examples describe its scope, not an exhaustive inventory.
- The location's description/detail, consistent with that profile. Revise any text promising
  bulk goods to match the portable-only scope; decorative shelves are not a stock ledger.
- Engine policy: only period-appropriate, ordinary portable goods plausibly available over
  this counter now. No bulk delivery, special orders, services, property, people or invented
  functional capabilities. Something existing in 1904 does not alone make it plausible stock.
- The clerk's requested item text and quantity, delimited as untrusted data.
- Currency and output contract: integer old pence, £1 = 240d, 1s = 12d; available item details
  and unit price, or an unavailable decision with a brief in-world reason.

Judge suitability before pricing. A request outside this kind of shop must be refused, not
made available at an enormous price. Do not silently substitute another requested object:
refuse, optionally mentioning an alternative in the reason so the customer can request it.
Allow mundane named packages (a tin of polish, a packet of needles) as portable items, but
not abstract counters such as a household's weekly supplies. Be conservative about unusual
requests and quantities rather than inventing procurement or delivery.

No full world, customer balance, private conversation history, other agents' notes, or raw
clerk assertions that an item is available. The profile gives semantic grounding; checked
schemas restrict what state can change. Neither guarantees a real model will always classify
an edge case correctly. Add prompt-contract tests that verify the profile actually reaches
the model, plus a short bounded real-model check if requested during implementation.

## Scenario changes

- Add a named shopkeeper (suggestion: Mr Briggs), a `:shopkeeper` role and a bedroom above the
  shop. Supply a data-driven routine using the existing hibernation mechanism, so an empty
  shop does not cost a model turn every round. A customer arriving must wake him. Do not add
  another optional-character flag.
- Mark the shop with `:services #{:general-store}`. Store policy/description replaces `:shop`
  stock and the `:here :sells` list. Views and action docs say to ask the shopkeeper.
- Remove fixed-catalogue validation, including the requirement that initial household stores
  must appear in goods for sale. Validate initial counters directly instead.
- New purchases create portable items only, handed to the buyer and owned by the household.
  A tin of polish, loaf, or small packet is an item; a ton of coal or an abstract week's
  provisions is not a supported purchase. No fulfillment-kind field, bulk counter changes,
  delivery, credit, wage or personal-purse economy. Existing counters can remain historical
  state, but the new shop never writes them. The adjudicator must refuse goods that are not
  plausibly portable stock or require unsupported mechanics; code restricts the allowed item
  fields, while semantic plausibility necessarily remains model judgement.
- Update the scripted demonstration to ask, quote and accept in the engine's existing turn
  order. This is not a same-round conversation redesign; ordinary replies may take another
  round, as they do with the architect.
- Old runs may be invalidated, as explicitly approved by the user. Do not retain legacy
  purchase paths, money-unit compatibility branches or migration machinery. Existing run
  directories remain untouched; this is permission to break compatibility, not delete data.

## Tests: mechanics must not depend on the production cast

- New store unit tests use a minimal test-owned world: customer, clerk and service location,
  with canned adjudications. Names, IDs and ordering should be unrelated to production names.
- Test valid/invalid purchases, bounded quantities/text/prices, rejected anachronistic goods,
  refusal vs technical failure, insufficient funds, wrong buyer, absent/retired clerk,
  wrong place, consumed quote, no double charge, and engine-controlled item identity/ownership.
- Cover portable item creation, unchanged household counters, refused bulk/delivery requests,
  and replay equality after accepted/refused offers and purchases. Check logging/cost accounting
  and no private-context leakage. Semantic refusal tests use controlled model responses; they
  cannot prove that a real model will always make the right historical judgement.
- Parameterize mechanics over extra/reordered/removed unrelated agents. Exercise shopkeeper
  hibernation with customers, passers-by elsewhere and multiple routine-driven village agents.
- Update the fixed household fixture's purchase choreography to the new contract where needed;
  do not retain a legacy fixed-stock production path solely to satisfy old assertions.
- Bundled-content checks validate the configured agents/roles/services, not a full cast snapshot
  or an exact number of agents, turns, calls or unrelated events. A focused shopkeeper-content
  check is fine; it must not make all household mechanics depend on him.
- Keep/extend the fixture-boundary regression that substitutes a different production world
  and mock day while the fixed mechanics fixture still runs unchanged.

## Implementation order

1. Implement/test money conversion and formatting as a distinct change before connecting
   purchases; do not spend effort on old-run compatibility.
2. Extract/test the shared adjudication loop without changing work-judge behavior.
3. Add the appraisal query, checked quote/refusal events and quote-based purchase action, with
   isolated unit/integration tests.
4. Replace bundled store content, add clerk/routine, update prompts and deterministic mock day;
   adapt the test-owned fixture only to the changed mechanics contract.
5. Run the full offline suite and mock demonstrations; update the design decisions, roadmap
   and scenario-authoring contract. No real-model call is needed for this design phase. If
   implementation gets a real-model check, keep it short, test-only and capped at $0.20, and
   retain/read its transcript per AGENTS.md.

## Comparison: work judge and architect

Both are relevant, at different boundaries:

| Concern | Work-on judge | Architect | Store choice |
|---|---|---|---|
| Semantic judgement | Narrow hidden LLM evaluates a task | MCP server performs deterministic drafting/pricing | Reuse work judge's bounded LLM approach |
| Checked answer | Allowed condition changes, no arbitrary world edits | Validated plan, price and delay | Validated offer or refusal, no arbitrary world edits |
| Model lifecycle | Work finishes at a later round start | Bridge query during the architect's turn | Immediate adjudication query during the clerk's turn |
| Applying effects | Engine immediately records checked work outcome | Plan stored; client separately commissions | Quote stored; buyer separately accepts |
| Failure | One correction attempt; log failure | Bad draft refused | One correction attempt; no quote or debit on failure |

The current work judge is a separate narrow LLM call, not the GM seat. Keep it that way:
no full-world GM prompt and no character-visible adjudicator. Share its execution/checking
machinery, not its elapsed-work/luck/job scheduling. Store appraisal should not wait for a
work job to finish, and should not have a random success roll. Likewise, don't force it into
the architect's external MCP transport just because it also produces a price.

This yields one shared structured-call runner and separate pure domain adapters for work and
store. The query-action contract needs a small extension for internal adjudication. Only
checked offers reach event construction; acceptance remains deterministic code.

## Money precision: assessment and recommendation

**Recommendation: integer pre-decimal pence internally.** In 1904, £1 = 20 shillings = 240
pence. For example, 18 pence displays as `1s 6d`, and 258 as `£1 1s 6d`. This supports cheap
purchases without floating-point errors or a general currency framework. Whole old pence
are sufficient initially; halfpennies/farthings can be deferred explicitly.

The arithmetic is easy. The change is moderate rather than trivial because pounds currently
appear at several boundaries, mostly confined to `src/manor/scenarios/building.clj`:

- household funds, architect purses and affordability checks;
- stored plan prices, commissions and the architect's percentage fee;
- world-file starting funds and the architect server's whole-pound prices;
- purchase/plan narration, action refusals and agent-view monetary fields;
- test fixtures and assertions.

Proposed boundaries:

- Small pure money helpers for pounds-to-pence and human-readable formatting, with unit tests.
  Do not change the unrelated USD model-cost accounting or village scenario's economy.
- Keep world-file `:starting-funds 3000` authored in pounds, convert to 720000 pence at fresh
  initialization, and record an explicit money-unit/version marker in the initialized state.
- Keep the architect MCP contract and `prices.edn` in pounds. Convert exactly once when the
  engine accepts a draft; stored new-run balances and plan prices then use the same unit.
  Format bridge price-list text with its unit so agents never mistake pounds for pence.
- The store adjudicator returns a positive bounded integer `unit-price-pence`. Explicitly
  teach the conversion and examples in its prompt. Quotes bind that value, unit/package and
  quantity; code computes the total. Customer tools accept quote IDs, not typed money.
- Present funds, purses, prices and narration as formatted money, rather than expose a bare
  integer that an agent could misread. Use formatter tests, not duplicated string arithmetic.
- Compute architect percentage fees in pence, rounding down to a penny if necessary. Existing
  whole-pound architectural prices produce the same economic results as before.

**Old-run compatibility is explicitly out of scope.** The user accepts invalidation and does
not want preservation work. No legacy-unit reducers, event-format compatibility layers,
migration events or old-run continuation tests. New runs must still replay exactly under the
new contract. Do not delete or rewrite existing run files.

This is a focused scenario-economy change, not a rewrite of the simulation engine. It is
worth doing alongside the store: otherwise the adjudicator will have to price small everyday
objects in implausible whole pounds. Currency helpers/conversion should be a separate tested
step so failures don't get confused with appraisal or cast changes.

## Confirmed scope

- Leave opening hours and their display unchanged; no literal sign needs removing.
- Portable purchases only. No new bulk counters or delivery mechanics.
- Firm price and explicit buyer acceptance. No bargaining system.
- Preserve production/test-fixture separation and cast-independent mechanics tests.
- No old-run preservation or migration; existing files remain untouched.
- Recommended money representation: integer old pence, with formatted pounds/shillings/pence.
- Every appraisal must include the engine-selected store type/profile and supported scope,
  not merely the requested object or a generic setting paragraph.
