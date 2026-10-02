<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: Order Change and Cancel

- **Plan**: context/changes/order-change-and-cancel/plan.md
- **Scope**: Full plan
- **Reviewed phases**: 1, 2, 3
- **Date**: 2026-10-02
- **Verdict**: APPROVED
- **Findings**: 0 critical, 0 warnings, 5 observations

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| Plan Adherence | PASS |
| Scope Discipline | PASS |
| Safety & Quality | PASS |
| Architecture | PASS |
| Pattern Consistency | PASS |
| Success Criteria | PASS |

Notes:
- **Plan Adherence**: every planned item matches the plan. The lock and re-check sequence lives in one helper, `transitionOrder`, shared by change, cancel, confirm and reject.
- **Scope Discipline**: three test files changed outside the plan: `OrderCompletionIntegrationTests`, `PickingIntegrationTests` and `ShoppingListIntegrationTests`. Each now reaches `CANCELLED` through the real cancel endpoint instead of a raw-SQL seed, because the V9 CHECK rejects the old seed. No assertion is weakened.
- **Success Criteria**: `./mvnw verify` passes on HEAD: exit 0, 236 tests. All manual items (1.4, 1.5, 2.5–2.7, 3.4) were confirmed by the user in the implementation sessions. 3.4 was confirmed after a retry on a fresh order. The first attempt hit an order whose completion had already been reported.

## Findings

### F1 — Order with no lines is changed and cancelled without any lock

- **Severity**: 💡 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: src/main/java/pl/regavio/stockahead/orders/OrderController.java:406
- **Detail**: `transitionOrder` locks only the order's parts and skips the lock when `partIds` is empty. An order can be created from a project with an empty BOM, because `create` does not reject one. Two concurrent transitions on such an order can then overwrite each other, since Hibernate writes every column and the entity has no `@Version`. For example, a change can write `status='OPEN', cancelled_at=NULL` over a cancel that has just committed. This has no stock or reservation impact, because the order has no lines. It is practically unreachable, since there is only one manager account.
- **Fix**: When `partIds` is empty, lock the order row with a `PESSIMISTIC_WRITE` `findById`. Alternatively, reject empty-BOM orders at creation.
- **Decision**: FIXED — `OrderRepository.findByIdForUpdate` locks the order row in `transitionOrder` when it has no parts

### F2 — Params for a line of another order are untested

- **Severity**: 💡 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Success Criteria
- **Location**: src/test/java/pl/regavio/stockahead/orders/OrderCancelIntegrationTests.java
- **Detail**: Cancel processes only the re-read order's own lines, so `returned_<id>` for a line of another order, or for an unknown line id, is ignored. That is correct, but no test covers it. A `seenPicked_<id>` for a foreign line without its `returned_<id>` is rejected with `returnNotInteger` (`OrderController.java:278`) instead of being ignored. That is safe, but it does not match the `paramsByLineId` Javadoc, which says such params are ignored.
- **Fix**: Add one test that posts a foreign line's params and asserts that the other order's line and part are unchanged.
- **Decision**: FIXED — added `OrderCancelIntegrationTests.returnParamsForAnotherOrdersLineAreIgnored`

### F3 — V9 fails on any hand-made CANCELLED row

- **Severity**: 💡 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: src/main/resources/db/migration/V9__add_order_cancellation.sql:4
- **Detail**: `CHECK ((status = 'CANCELLED') = (cancelled_at IS NOT NULL))` fails if any existing row is `CANCELLED`, because its `cancelled_at` would be NULL. No code could set that status before this change, so only a hand-edited database is affected. The migration fails loudly; no data is corrupted.
- **Fix**: None needed unless an environment holds hand-made CANCELLED rows. In that case, backfill `cancelled_at` and `cancelled_by` before the CHECK.
- **Decision**: ACCEPTED — V9 already applied cleanly on the dev DB; a hand-edited CANCELLED row would make the migration fail loudly, which is acceptable

### F4 — Cancel page loads part locations one query per line

- **Severity**: 💡 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: src/main/java/pl/regavio/stockahead/orders/OrderDetailModel.java:122
- **Detail**: `findByIdWithDetails` fetches lines and parts but not `Part.locations`, so the cancel page runs one query per picked line. `PickingDetailModel` has the same shape, and lessons.md already covers it ("Consider JOIN FETCH…").
- **Fix**: None needed at current row counts. Fetch locations once line counts grow.
- **Decision**: FIXED — `PartRepository.findWithLocationsByIdIn` batch-loads the shown parts' locations in `loadCancel`

### F5 — Cleanup after a timed-out race can hang

- **Severity**: 💡 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Pattern Consistency
- **Location**: src/test/java/pl/regavio/stockahead/orders/OrderCancelConcurrencyTests.java:306
- **Detail**: If a race's `future.get(20s)` times out, `shutdownNow()` cannot interrupt a request blocked in JDBC or `pg_sleep`. The `DROP TRIGGER` in `@AfterEach` can then wait on that transaction. The result of `bothReady.await(...)` is also ignored. This only matters after a test has already failed, and `OrderCompletionConcurrencyTests` has the same shape.
- **Fix**: Optionally set `lock_timeout` on the cleanup connection.
- **Decision**: SKIPPED — matches OrderCompletionConcurrencyTests; only affects an already-failed run
