<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: Order Partial Completion

- **Plan**: context/changes/order-partial-completion/plan.md
- **Scope**: Full plan
- **Reviewed phases**: 1, 2, 3
- **Date**: 2026-10-03
- **Verdict**: APPROVED
- **Findings**: 0 critical, 1 warning, 2 observations

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| Plan Adherence | PASS |
| Scope Discipline | PASS |
| Safety & Quality | PASS |
| Architecture | PASS |
| Pattern Consistency | WARNING |
| Success Criteria | PASS |

Automated: `./mvnw verify` passed (exit 0, 485 tests, 0 failures/errors/skipped), and V12 was applied by Testcontainers. Manual items 1.3, 2.3, 3.2 and 3.3 are checked off, and integration tests assert the same strings (`zbudowano 7 z 10`, `7 / 10`, the partial/full/legacy texts).

## Findings

### F1 — `novalidate` on the report form switches off the planned client-side checks

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Pattern Consistency
- **Location**: src/main/resources/templates/picking-detail.html:23
- **Detail**: The plan specifies `min="0"`, `th:max`, `required` on the built-units input, but the form adds `novalidate`, so the browser never enforces them and every bad value round-trips to the server. It is the only form in the templates with `novalidate` (the pick form on the same page has none). This is not a safety issue, because server validation covers every case and is tested, but it is an unplanned UX difference.
- **Fix**: Remove `novalidate` (server-side validation stays as the authoritative guard), or keep it and add a one-line plan addendum explaining why the server's Polish message is preferred.
- **Decision**: FIXED — removed `novalidate` (not needed: server validates every case; matches the pick form) and the test assertion that pinned it (PickingListAndDetailIntegrationTests)

### F2 — `Order.isPartiallyCompleted()` is dead code duplicated by `OrderView.partiallyCompleted()`

- **Severity**: 🔍 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Architecture
- **Location**: src/main/java/pl/regavio/stockahead/orders/Order.java:177, src/main/java/pl/regavio/stockahead/orders/OrderDetailModel.java:200
- **Detail**: Nothing calls the entity method. The view helper re-implements the comparison without the `COMPLETED` status check and is also used for the pending-report note (orders-detail.html:54), so the two "partially completed" definitions already differ.
- **Fix**: Delete `Order.isPartiallyCompleted()`, or rename the view helper (e.g. `builtFewerThanOrdered()`) so the two meanings don't share a name.
- **Decision**: FIXED — deleted unused `Order.isPartiallyCompleted()`; plan addendum added

### F3 — Minor unplanned deviations (message format, incidental test edits)

- **Severity**: 🔍 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Scope Discipline
- **Location**: src/main/resources/messages.properties:74, 5 test files
- **Detail**: `picking.error.builtUnitsTooMany` uses `{0,number,#}` where the plan had `{0}`. This is an improvement, since it avoids a grouping separator such as "1 000". `OrderCancelIntegrationTests`, `PickingIntegrationTests`, `ReservationAllocatorTests`, `DeliveryIntegrationTests` and `ShoppingListIntegrationTests` were updated to send `builtUnits=1`, a required consequence of the new mandatory parameter that the plan did not list. Both are benign.
- **Fix**: No code change. Optionally note both in the plan for traceability.
- **Decision**: FIXED — plan addendum records both deviations
