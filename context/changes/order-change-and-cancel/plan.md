# Order Change and Cancel Implementation Plan

## Overview

The manager changes the priority and required date of an order until it has been taken (FR-021), and cancels an open order (FR-011). On cancel, the manager enters for each picked line how many picked units go back into stock. The rest count as used. Both events recompute reservations and the shopping list through `ReservationAllocator.reallocateForParts`, under the same part-row locks every other order event uses (roadmap S-09).

## Current State Analysis

- `OrderStatus.CANCELLED` exists (`OrderStatus.java:6`) and the `V6` status CHECK allows it (`V6__create_orders.sql:7`), but no code sets it. Nothing records when or by whom an order was cancelled, or how many units came back.
- `OrderController.confirmCompletion` (`OrderController.java:120-132`) is the model for a cancel: lock the parts → re-read the order → zero `reservedQuantity` → `saveAndFlush` the status change → `reallocateForParts(partIds)`. The flush must come before the reallocation, or the still-`OPEN` taken order's reservation is subtracted from the pool. `transitionReportedOrder` (`OrderController.java:160-194`) is the shared lock/re-check shape: a scalar pre-lock `findPartIdsForOrder`, then `LockRetry` + `TransactionTemplate`, `findByIdInForUpdate`, a fresh `findById`, a business re-check, and catching `DataIntegrityViolationException`/`PessimisticLockingFailureException` into a friendly re-render.
- `ReservationAllocator` (`ReservationAllocator.java:46-52, 83-130`) rebuilds non-taken `OPEN` lines from scratch in `ALLOCATION_ORDER` (priority desc → required date → created at → id). A priority or date change therefore needs only a flush followed by `reallocateForParts`, and preemption works in both directions with no new allocator logic. Taken orders keep their reservation and only grow, so changing the schedule of an untaken order can never take units from a taken one.
- "Taken" = `Order.takenAt != null`, set on the first pick (`PickingController.java:155-157`). A completion report requires taken (`V8` CHECK `orders_completion_reported_requires_taken_check`), so "not taken" implies "not reported".
- `OrderLine.pickedQuantity` is cumulative. `reservedQuantity` is live (each pick moves units out of it), per `OrderLine.java:83-91` and lessons.md "Don't re-subtract a counter that's already net of what you're subtracting". The returnable amount is `pickedQuantity` alone.
- Every downstream view already filters to `OPEN`: order list and picking list (`findByStatusWithProject(OPEN)`), shopping list (`findOpenLinesWithShortage`), reserved totals (`reservedQuantitiesByPart`). Pick and report-completion already reject a non-`OPEN` order (`PickingController.java:135, 205`). A cancelled order drops out of all of them with no change there.
- `orders-detail.html` shows status only for `COMPLETED`. Creation validates the date as ISO, not before today (`OrderController.java:296-311`), and falls back to `NORMAL` on an unknown priority (`OrderController.java:325-335`).

## Desired End State

- On `/orders/{id}` an `OPEN`, untaken order shows a form to change priority and required date. Saving it reorders the allocation at once: raising the priority can take unpicked reservations from lower, untaken orders, and lowering it can give them back. A taken order shows no form, and the server rejects the change.
- An `OPEN` order whose completion is not reported shows an "Anuluj zlecenie" link to `/orders/{id}/cancel`. That page lists each line with `pickedQuantity > 0`, with a return field prefilled with the picked amount. Confirming sets the order to `CANCELLED`, records `cancelled_at`/`cancelled_by` and each line's `returned_quantity`, adds the returned units to `parts.quantity`, zeroes the reservations and reallocates the freed and returned stock to the remaining open orders.
- A cancelled order no longer appears in the order list, picking list or shopping list. Its detail page shows "Anulowane <moment>, <email>" and the returned quantity for each line.
- Stock never drops below zero, and no unit is picked after a cancel commits. A cancel submitted from a stale form (picked quantities changed since the page loaded) is rejected and nothing changes.
- Verify: `./mvnw verify` green, including the new integration and concurrency tests.

### Key Discoveries:

- `transitionReportedOrder`'s `BiConsumer<Order, Set<Long>>` shape (`OrderController.java:160`) can be generalized to take the precondition as a parameter, or copied into a sibling helper. Either way, change and cancel need the exact scalar-lookup → lock → re-read → re-check sequence for the open-in-view stale-cache reason in `OrderLineRepository.java:30-37`.
- The returned units must enter `parts.quantity` before `reallocateForParts` runs. The allocator reads stock through the scalar `findCurrentQuantities` (`PartRepository.java:36`), so the `Part` updates have to be flushed together with the order flush.
- Lessons in force: every new `@PreAuthorize` route ships a 403 test for the technician; reach "taken" and "reported" states through real endpoints (pick/report), not raw-SQL seeds; pick the same line twice before cancelling, so a test catches a double-subtracted return bound; every new `<table>` template carries the centered-cell `<style>` block; catch DB constraint violations at write boundaries.

## What We're NOT Doing

- Cancelling an order whose completion is reported. The manager must reject the report first (decision: "No, reject first").
- A two-step return (manager cancels, technician records returns later) and any "awaiting return" state. The manager enters returns in the same request that cancels.
- Changing the unit quantity or project of an existing order, or changing priority/date of a taken order.
- Lists or archives of cancelled or completed orders. They stay reachable only by `/orders/{id}`.
- Undoing a cancel, editing returned quantities afterward, or a stock-movement history (FR-006, a PRD Non-Goal).
- Any change to `ReservationAllocator`'s algorithm or lock scope.

## Implementation Approach

Both actions follow the existing manager-transition pattern in `OrderController`. Pre-lock scalar part ids, then `LockRetry` around a `TransactionTemplate` that locks the parts, re-reads the order, re-checks its state, mutates and flushes, then calls `reallocateForParts(partIds)`. The precondition differs per action: change requires `OPEN && !taken`, and cancel requires `OPEN && !completionReported`. Input format is validated before the transaction. Bounds that depend on live data (return ≤ picked, stale-form check) are validated inside it, after the lock.

## Critical Implementation Details

- **State sequencing (cancel):** inside the locked transaction, in this order: add each line's `returned_quantity` to its part's `quantity` → zero every line's `reservedQuantity` → set `CANCELLED` + `cancelledAt`/`cancelledBy` → `saveAndFlush` (order and parts) → `reallocateForParts(partIds)`. Flushing after the reallocation would let the allocator still see an `OPEN` taken order and subtract its reservation from the pool, and the returned units would not reach other orders.
- **Stale cancel form:** the cancel form posts, for each line it showed, both the return amount and the `pickedQuantity` it displayed (`seenPicked_<lineId>`). Inside the lock, the cancel is rejected without changes ("Stan pobrań zlecenia zmienił się — sprawdź ilości do zwrotu.") when any line's current `pickedQuantity` differs from the seen value, including a line that had 0 picked when the form loaded. Without this check, a pick committed between page load and submit would silently become "used", with no return asked for.

## Phase 1: Change priority and required date

### Overview

The manager changes the priority and required date of an untaken order from its detail page, and reservations are recomputed in the same transaction.

### Changes Required:

#### 1. Change endpoint

**File**: `src/main/java/pl/regavio/stockahead/orders/OrderController.java`

**Intent**: Add a manager-only POST that validates the new priority and date, then under the part locks re-checks that the order is still `OPEN` and not taken, applies the values, flushes and reallocates the order's parts. A business rejection or DB/lock failure re-renders the detail page with an error, and success redirects to `/orders/{id}`.

**Contract**: `POST /orders/{id}/change` with params `priority`, `requiredDate`; `@PreAuthorize("hasRole('MANAGER')")`. Validation: priority must be one of `LOW|NORMAL|HIGH` (an unknown value is an error, unlike create's fallback). The date must be a valid ISO date, not before today, **unless it equals the order's current `requiredDate`**. Compare against the value re-read inside the transaction. Unknown order → 404. Not `OPEN` or taken → `orders.error.notChangeable`. Failure → `orders.error.changeFailed`. Extract the lock/re-check sequence shared with `transitionReportedOrder` instead of duplicating it.

#### 2. Change form on the detail page

**File**: `src/main/resources/templates/orders-detail.html`, `src/main/java/pl/regavio/stockahead/orders/OrderDetailModel.java`

**Intent**: Show a priority select (prefilled with the current value) and a date input (prefilled with the current date) only when the order is `OPEN` and not taken. On a validation error, re-render with the submitted values kept.

**Contract**: `OrderView` gains `taken` (and `changeable` = `OPEN && !taken`). `OrderDetailModel.render` accepts optional submitted priority/date for the re-render.

#### 3. Messages

**File**: `src/main/resources/messages.properties`

**Intent**: Add the new error texts in Polish, matching the existing `orders.error.*` style.

**Contract**: `orders.error.notChangeable`, `orders.error.changeFailed`, `orders.error.priorityInvalid`. Reuse `orders.error.dateRequired`/`orders.error.datePast`.

#### 4. Integration tests

**File**: `src/test/java/pl/regavio/stockahead/orders/OrderChangeIntegrationTests.java`

**Intent**: Cover FR-021 end to end against real Postgres, in the style of `OrderCompletionIntegrationTests`.

**Contract**: Tests cover the following:
- Raising a LOW order to HIGH moves the unpicked reservation from an older NORMAL untaken order to it.
- Lowering the priority gives it back.
- Moving the date earlier at equal priority reorders the allocation.
- A taken order (taken through the real pick endpoint) is rejected, and the form is absent.
- A past date different from the current one is rejected.
- Resubmitting an order's current past date with a new priority is accepted.
- An unknown priority is rejected.
- An unknown order returns 404.
- A technician gets 403 and nothing changes.
- An unauthenticated request redirects to login.

### Success Criteria:

#### Automated Verification:

- `./mvnw verify` passes, including `OrderChangeIntegrationTests`
- Raising or lowering priority reallocates reservations between untaken orders as asserted in `OrderChangeIntegrationTests`
- Change on a taken order, a past non-current date, an unknown priority and a technician request are each rejected with no DB change

#### Manual Verification:

- On `/orders/{id}` of an untaken order, changing priority to Wysoki moves reserved quantities from a lower order (visible on both detail pages and on `/purchasing`)
- The change form is absent on a taken order's detail page

**Implementation Note**: After automated verification passes, pause for manual confirmation before Phase 2.

---

## Phase 2: Cancel with per-line returns

### Overview

The manager cancels an open, unreported order. Unpicked reservations are released, the picked units the manager enters return to stock, and freed stock is reallocated.

### Changes Required:

#### 1. Migration

**File**: `src/main/resources/db/migration/V9__add_order_cancellation.sql`

**Intent**: Record who cancelled, when, and how many picked units came back for each line, with DB guards so a return can never exceed what was picked.

**Contract**: `orders.cancelled_at TIMESTAMPTZ`, `orders.cancelled_by BIGINT REFERENCES accounts (id) ON DELETE RESTRICT`. CHECK `(status = 'CANCELLED') = (cancelled_at IS NOT NULL)` and CHECK `(cancelled_at IS NULL) = (cancelled_by IS NULL)`. `order_lines.returned_quantity INT NOT NULL DEFAULT 0` with CHECK `returned_quantity >= 0 AND returned_quantity <= picked_quantity`. `parts.quantity >= 0` already exists.

#### 2. Entities

**File**: `src/main/java/pl/regavio/stockahead/orders/Order.java`, `OrderLine.java`

**Intent**: Map the new columns. Add `canCancel()` = `OPEN && !completionReported`, next to `canReportCompletion()`.

**Contract**: `Order.cancelledAt`, `Order.cancelledBy` (lazy `@ManyToOne Account`), `OrderLine.returnedQuantity`.

#### 3. Cancel page and endpoint

**File**: `src/main/java/pl/regavio/stockahead/orders/OrderController.java`, new `src/main/resources/templates/orders-cancel.html`

**Intent**: GET renders a confirmation page. It lists the lines with `pickedQuantity > 0` (part name, location, picked, return field prefilled with picked, hidden seen-picked) or says that no parts were picked, and offers a "Potwierdź anulowanie" button. POST validates the format of each return field (an integer ≥ 0), then inside the locked transaction runs the following steps in order:
- Re-check `canCancel()`.
- Run the stale-form check (see Critical Implementation Details).
- Check each return against the line's current `pickedQuantity`.
- Apply the state sequence from Critical Implementation Details.
- Redirect to `/orders/{id}`.

On a GET for an order that cannot be cancelled, redirect to the detail page. On a POST rejection, re-render the cancel page with the submitted values and the error.

**Contract**: `GET /orders/{id}/cancel`, `POST /orders/{id}/cancel` with params `returned_<lineId>`, `seenPicked_<lineId>` for each shown line; both `@PreAuthorize("hasRole('MANAGER')")`. The cancelling account is resolved like `PickingController.reportCompletion`'s reporter. Errors: `orders.error.notCancellable` (not `OPEN`, or completion reported: "odrzuć zgłoszenie zakończenia, aby anulować"), `orders.error.pickedChanged`, `orders.error.returnNotInteger`, `orders.error.returnExceedsPicked` (with the line's picked amount), `orders.error.cancelFailed`. A missing `returned_` param for a shown line is an error, not 0. The template carries the centered-cell `<style>` block (lessons.md).

#### 4. Detail page

**File**: `src/main/resources/templates/orders-detail.html`, `OrderDetailModel.java`

**Intent**: Show the "Anuluj zlecenie" link when `canCancel()`. For a `CANCELLED` order, show "Anulowane <moment>, <email>" and a "Zwrócona ilość" column. Hide the change form for it, and drop the missing column as it already is for non-`OPEN` orders.

**Contract**: `OrderView` gains `cancelledAt` (formatted through `OrderMoments`), `cancelledByEmail`, `canCancel`. `LineView` gains `returnedQuantity`.

#### 5. Integration tests

**File**: `src/test/java/pl/regavio/stockahead/orders/OrderCancelIntegrationTests.java`

**Intent**: Cover FR-011 end to end, in the style of `OrderCompletionIntegrationTests`.

**Contract**: Tests cover the following:
- Cancelling an untaken order releases its reservation to the next order in allocation order, and stock is unchanged.
- Cancelling a taken order with returns (some lines fully, some partially, one with 0) raises stock by exactly the returned amounts, records `returned_quantity`, `cancelled_at`/`by`, and reallocates the returned and freed units to a short order.
- A line picked twice (the first pick more than half of the reservation), then cancelled with the full picked amount returned, is accepted and stock is restored exactly (lessons.md).
- A return above the line's picked amount is rejected with no change.
- A pending completion report blocks the cancel. After a reject, the cancel succeeds.
- Cancelling a `CANCELLED` or `COMPLETED` order is rejected.
- After a cancel, pick and report-completion on the order are rejected, and the order is absent from `/orders`, `/picking` and `/purchasing`.
- An unknown order returns 404.
- A technician gets 403 on GET and POST.
- An unauthenticated request redirects to login.
- A migration test (style of `OrderCompletionSchemaTests`) shows the DB rejects `returned_quantity > picked_quantity` and a `CANCELLED` status without `cancelled_at`.

### Success Criteria:

#### Automated Verification:

- `./mvnw verify` passes, including `OrderCancelIntegrationTests` and the V9 schema tests
- Cancelling a taken order raises part stock by exactly the entered returned quantities and hands freed units to the next open order
- A pick-twice-then-return-all cancel restores stock exactly
- Over-return, cancel of a reported, cancelled or completed order, and a technician request are each rejected with no DB change

#### Manual Verification:

- Cancelling a partly picked order through `/orders/{id}/cancel` with one line partially returned shows the expected stock on `/parts` and the expected reservations on another order
- The cancelled order disappears from `/orders`, `/picking` and `/purchasing`, and its detail page shows the cancel moment, the person who cancelled and the returned quantities
- An order awaiting completion confirmation shows no cancel link

**Implementation Note**: After automated verification passes, pause for manual confirmation before Phase 3.

---

## Phase 3: Concurrency guarantees

### Overview

Prove under real concurrent transactions that a cancel and a pick never interleave unsafely, that a schedule change never applies to an order taken in the meantime, and that a stale cancel form changes nothing.

### Changes Required:

#### 1. Concurrency tests

**File**: `src/test/java/pl/regavio/stockahead/orders/OrderCancelConcurrencyTests.java`

**Intent**: Use the two-thread pattern of `OrderCompletionConcurrencyTests` (including its DB-trigger detector idea) to race the real endpoints.

**Contract**: Tests cover the following:
- Racing a pick against a cancel never records a pick on a `CANCELLED` order. Either the pick committed first, so the cancel sees it and is rejected as stale (the form showed the old picked amount), or the pick is rejected as `orderNotOpen`. `parts.quantity` stays ≥ 0 and equals the initial stock − kept picks + returns.
- Racing a pick against a priority change never leaves a taken order with a changed priority/date. Either the change commits first and the pick follows, or the change is rejected as `notChangeable`.
- A cancel submitted with a seen-picked value lower than the current one (a sequential stale form) is rejected and changes no row.

### Success Criteria:

#### Automated Verification:

- `./mvnw verify` passes, including `OrderCancelConcurrencyTests`
- No pick is ever recorded on a `CANCELLED` order and stock stays ≥ 0 across the pick/cancel race
- No taken order ends up with a changed priority or date across the pick/change race

#### Manual Verification:

- With two browser sessions (manager on the cancel page, technician picking the same order), the cancel submitted after the technician's pick shows the "stan pobrań zmienił się" error and the refreshed page shows the new picked amount

---

## Testing Strategy

### Unit Tests:

- None new. `ReservationAllocator` is unchanged, so `ReservationAllocatorTests` stays as the algorithm guard.

### Integration Tests:

- `OrderChangeIntegrationTests` (Phase 1), `OrderCancelIntegrationTests` + V9 schema assertions (Phase 2), `OrderCancelConcurrencyTests` (Phase 3). All use `@Import(TestcontainersConfiguration.class)` against real Postgres. Taken and reported states come from the real pick/report endpoints.

### Manual Testing Steps:

1. `./mvnw spring-boot:test-run`, create two orders on a shared, scarce part, raise the later one's priority, and check that the reservations swap.
2. Pick part of one order twice, cancel it returning less than picked on one line, and check the stock on `/parts` and the other order's reservation.
3. Report completion on an order, check that it has no cancel link, reject the report, then cancel.

## Performance Considerations

Each event locks only the order's own parts (`findByIdInForUpdate`) and reallocates those parts. The cost is the same as confirm-completion.

## Migration Notes

`V9` only adds nullable or defaulted columns and CHECKs that hold for existing rows: no existing order is `CANCELLED`, and `returned_quantity` defaults to 0 ≤ `picked_quantity`. No backfill is needed.

## References

- PRD: `context/foundation/prd.md` FR-021, FR-011, § Business Logic, § Access Control
- Roadmap: `context/foundation/roadmap.md` S-09
- Pattern: `OrderController.java:120-194` (confirm/reject + `transitionReportedOrder`), `PickingController.java:189-233` (reporter resolution)
- Prior change: `context/archive/2026-10-01-order-completion/`, `context/archive/2026-10-01-taken-order-top-up/`
- Lessons: `context/foundation/lessons.md` (403 tests, double-subtract, table centering, constraint handling)

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles. See `references/progress-format.md`.

### Phase 1: Change priority and required date

#### Automated

- [x] 1.1 `./mvnw verify` passes, including `OrderChangeIntegrationTests` — ab221e2
- [x] 1.2 Raising or lowering priority reallocates reservations between untaken orders as asserted in `OrderChangeIntegrationTests` — ab221e2
- [x] 1.3 Change on a taken order, a past non-current date, an unknown priority and a technician request are each rejected with no DB change — ab221e2

#### Manual

- [x] 1.4 On `/orders/{id}` of an untaken order, changing priority to Wysoki moves reserved quantities from a lower order (visible on both detail pages and on `/purchasing`) — ab221e2
- [x] 1.5 The change form is absent on a taken order's detail page — ab221e2

### Phase 2: Cancel with per-line returns

#### Automated

- [x] 2.1 `./mvnw verify` passes, including `OrderCancelIntegrationTests` and the V9 schema tests — 6237135
- [x] 2.2 Cancelling a taken order raises part stock by exactly the entered returned quantities and hands freed units to the next open order — 6237135
- [x] 2.3 A pick-twice-then-return-all cancel restores stock exactly — 6237135
- [x] 2.4 Over-return, cancel of a reported, cancelled or completed order, and a technician request are each rejected with no DB change — 6237135

#### Manual

- [x] 2.5 Cancelling a partly picked order through `/orders/{id}/cancel` with one line partially returned shows the expected stock on `/parts` and the expected reservations on another order — 6237135
- [x] 2.6 The cancelled order disappears from `/orders`, `/picking` and `/purchasing`, and its detail page shows the cancel moment, the person who cancelled and the returned quantities — 6237135
- [x] 2.7 An order awaiting completion confirmation shows no cancel link — 6237135

### Phase 3: Concurrency guarantees

#### Automated

- [x] 3.1 `./mvnw verify` passes, including `OrderCancelConcurrencyTests`
- [x] 3.2 No pick is ever recorded on a `CANCELLED` order and stock stays ≥ 0 across the pick/cancel race
- [x] 3.3 No taken order ends up with a changed priority or date across the pick/change race

#### Manual

- [x] 3.4 With two browser sessions (manager on the cancel page, technician picking the same order), the cancel submitted after the technician's pick shows the "stan pobrań zmienił się" error and the refreshed page shows the new picked amount
