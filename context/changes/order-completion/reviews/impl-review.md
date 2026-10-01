<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: Order Completion (Report & Confirm)

- **Plan**: context/changes/order-completion/plan.md
- **Scope**: Full plan
- **Reviewed phases**: 1, 2, 3
- **Date**: 2026-10-01
- **Verdict**: NEEDS ATTENTION
- **Findings**: 0 critical, 3 warnings, 5 observations

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| Plan Adherence | PASS |
| Scope Discipline | WARNING |
| Safety & Quality | WARNING |
| Architecture | PASS |
| Pattern Consistency | WARNING |
| Success Criteria | PASS |

Automated: `./mvnw verify` — 198 tests, 0 failures (incl. OrderCompletionIntegrationTests 16, OrderCompletionSchemaTests 7, OrderCompletionConcurrencyTests 1, MessagesBundleTests 37). Manual 1.4, 2.4, 3.4 confirmed by the user during implementation.

## Findings

### F1 — Raw UTC timestamp in the "Zgłoszono" column on /orders

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Scope Discipline
- **Location**: src/main/resources/templates/orders-list.html:34
- **Detail**: The "Do potwierdzenia" table adds an unplanned "Zgłoszono" column that renders `${order.completionReportedAt}` as a raw `Instant` (e.g. `2026-10-01T18:12:33.123456Z`). The detail page was already changed at the user's request to `yyyy-MM-dd HH:mm` in Europe/Warsaw (`OrderDetailModel.MOMENT_FORMAT`), so the two pages disagree, and the list shows the over-detailed format the user rejected.
- **Fix**: Format the cell the same way as the detail page: share the formatter (e.g. `#temporals.format` on a Warsaw-zoned value, or a small shared helper), and add a list assertion matching the detail-page pattern.
- **Decision**: FIXED — extracted `OrderMoments` bean (`@orderMoments`), used by OrderDetailModel and orders-list.html; list assertion added (break-checked)

### F2 — No concurrency test for confirm vs order creation / pick on a shared part

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Safety & Quality
- **Location**: src/test/java/pl/regavio/stockahead/orders/OrderCompletionConcurrencyTests.java
- **Detail**: The only concurrency test is pick vs report. Confirm is the first transition that frees reserved stock and calls `reallocateForParts` across several parts, but no real-concurrency test covers it racing `POST /orders` or a pick on another order sharing a part. The lessons entry on allocator locking asks for real-concurrency coverage of reallocation paths. Code reading (same `findByIdInForUpdate` lock ordered by id, LockRetry) suggests it is safe, but this is unproven.
- **Fix**: Add a confirm vs `POST /orders` race on a shared part, modeled on `ReservationConcurrencyTests`. Assert: no 500, sum of reserved ≤ stock for every part, and the new order receives the released units.
  - Strength: Proves the AGENTS.md hard rule (never double-reserved) under the new release path.
  - Tradeoff: One more slow Testcontainers test, and races are probabilistic.
  - Confidence: MED — the existing concurrency test harness can be reused.
  - Blind spot: Deadlocks with confirms on several parts at once are only sampled, not exhausted.
- **Decision**: FIXED — added `concurrentConfirmAndOrderCreationHandReleasedUnitsToTheNewOrderWithoutOverReserving` (both orderings alternated; taken/reported reached via real endpoints); break-checked by removing the confirm's reallocation

### F3 — Tests seed "taken" orders via raw SQL with zero picks

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Safety & Quality
- **Location**: src/test/java/pl/regavio/stockahead/orders/OrderCompletionIntegrationTests.java:198-200 (`markTaken`, used ~369, ~407), ~380-385; PickingIntegrationTests `markCompleted`; ShoppingListIntegrationTests ~221-228
- **Detail**: These helpers set `taken_at` (and in some cases COMPLETED) on orders whose lines have `picked_quantity = 0`. The real flow can't produce this: an order becomes taken only through a pick. The lesson on re-subtracting counters warns that such fixtures can hide bugs. Today they only cover rejection and filtering paths, so nothing is masked yet.
- **Fix**: Reach "taken" through the real `pick(...)` endpoint (or seed `picked_quantity > 0` with stock lowered to match), and confirm via the real endpoint instead of setting COMPLETED by SQL where possible.
  - Strength: Fixtures describe only reachable states; this matches the lesson.
  - Tradeoff: More setup per test; the ShoppingList/Picking helpers also need to change.
  - Confidence: HIGH — the `pick`/`report`/`confirm` helpers already exist in OrderCompletionIntegrationTests.
  - Blind spot: Some rejection tests (e.g. CANCELLED status) still need SQL because cancel doesn't exist yet (S-09).
- **Decision**: FIXED — taken/reported/completed now reached via real pick/report/confirm endpoints in OrderCompletionIntegrationTests, PickingIntegrationTests, ShoppingListIntegrationTests, OrderCompletionConcurrencyTests and ReservationAllocatorTests (same pattern found there); CANCELLED kept as SQL with a comment (no cancel endpoint until S-09). Follow-up noted: ShoppingListIntegrationTests still seeds open-order reservations the allocator couldn't produce (reserved > stock, under-reserved with stock available)

### F4 — Completed order shows full required quantity as "Brakująca ilość"

- **Severity**: 💡 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: src/main/java/pl/regavio/stockahead/orders/OrderDetailModel.java:79, orders-detail.html:57
- **Detail**: `missingQuantity = required - reserved`. Confirm zeroes `reserved`, so a COMPLETED order's detail page shows every unit as missing, including the ones already picked.
- **Fix**: Show 0 or hide the "Brakująca ilość" column when the order isn't OPEN.
- **Decision**: FIXED — 'Brakująca ilość' column shown only for OPEN orders; confirm test asserts it before/after (break-checked)

### F5 — Reporter lookup uses exact `findByEmail` instead of the canonical lookup

- **Severity**: 💡 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Pattern Consistency
- **Location**: src/main/java/pl/regavio/stockahead/orders/PickingController.java:207
- **Detail**: `AccountUserDetailsService` and `AccountActivityFilter` resolve the principal with `findByCanonicalEmail(Emails.canonical(...))`. The report action is the only call site using exact-match `findByEmail(authentication.getName())`. It works today because the principal name is the stored email, but on any mismatch it would return a 403.
- **Fix**: Switch to `findByCanonicalEmail(Emails.canonical(authentication.getName()))`.
- **Decision**: FIXED — report action resolves the reporter via findByCanonicalEmail(Emails.canonical(...))

### F6 — Released units never reach a short taken order

- **Severity**: 💡 OBSERVATION
- **Impact**: 🔬 HIGH — architectural stakes; think carefully before deciding
- **Dimension**: Architecture
- **Location**: src/main/java/pl/regavio/stockahead/orders/ReservationAllocator.java (taken lines are frozen)
- **Detail**: On confirm, freed units go only to non-taken OPEN orders, because the allocator never writes to taken lines. A taken order that is short on the same part never gets them. PRD § Business Logic says taken orders' unpicked reservations can't be taken from them, but it doesn't say whether they can receive new units. This predates the change; confirm is the first event that makes it visible.
- **Fix**: Decide the PRD semantics (can a taken order's shortfall be filled later?). If yes, plan it as its own change via /10x-plan with allocator and concurrency tests. Don't patch it inline.
  - Strength: Keeps the allocator rule change explicit and reviewed.
  - Tradeoff: Taken orders may sit short until a delivery (S-10) and rely on the same rule.
  - Confidence: MED — S-10 (delivery receipt) will hit the same question.
  - Blind spot: How the plant actually works with half-picked orders.
- **Decision**: ACCEPTED (deferred) — recorded as roadmap Open Roadmap Question 4 (affects S-10); no code change

### F7 — Shopping list counts picked units as shortage (pre-existing, S-07)

- **Severity**: 💡 OBSERVATION
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Safety & Quality
- **Location**: src/main/java/pl/regavio/stockahead/orders/OrderLineRepository.java:44-47 (`findOpenLinesWithShortage`); OrderDetailModel `missingQuantity`
- **Detail**: Shortage is `required > reserved`, but `reservedQuantity` is live (each pick moves units out of it into `pickedQuantity`), so picked units show up as missing on `/purchasing` and in "Brakująca ilość". This is the double-subtraction lesson in reverse. This change doesn't make it worse: confirmed orders leave the list.
- **Fix**: Change the shortage to `required - reserved - picked` everywhere it is computed (query, CSV export, detail model), with a test that picks twice. Do it as a separate fix change, since it touches S-05/S-07 behavior.
  - Strength: Fixes a user-visible over-ordering bug at the source.
  - Tradeoff: Touches already-archived slices' behavior; needs its own plan and tests.
  - Confidence: HIGH — the invariant `reserved + picked <= required` makes the formula unambiguous.
  - Blind spot: Haven't checked every consumer (CSV export, parts "reserved" column).
- **Decision**: FIXED (in this change, per user) — new `OrderLine.getMissingQuantity()` = required − reserved − picked used by `findOpenLinesWithShortage`, ShoppingListModel (page + CSV) and OrderDetailModel; `repeatedPicksOnAShortLineLeaveTheShortageUnchanged` picks twice and checks page + CSV (break-checked)

### F8 — Confirm passes part ids from `order.getLines()` instead of pre-lock `partIds`

- **Severity**: 💡 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Adherence
- **Location**: src/main/java/pl/regavio/stockahead/orders/OrderController.java:128-130
- **Detail**: The plan calls `reallocateForParts(partIds)`. The code rebuilds the set from `order.getLines()` after the lock and the fresh re-read. This is harmless (it runs after the lock, and the allocator re-locks anyway) and only a wording drift.
- **Fix**: Leave as is, or pass `partIds` to match the plan.
- **Decision**: FIXED — confirm/reject transition receives the locked pre-lock `partIds` (BiConsumer); confirm passes them to `reallocateForParts`

## Triage summary (2026-10-01)

- Fixed: F1, F2, F3, F4, F5, F7, F8
- Accepted (deferred): F6 — roadmap Open Roadmap Question 4
- Post-triage `./mvnw verify`: 200 tests, 0 failures
