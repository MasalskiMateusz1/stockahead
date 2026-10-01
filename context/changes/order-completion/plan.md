# Order Completion (Report & Confirm) Implementation Plan

## Overview

Close the order lifecycle (roadmap S-08, PRD FR-014 + FR-020): a technician — or a manager acting in the technician role — reports a **taken** order as finished, which freezes further picking; a manager then either **confirms** the report (order becomes `COMPLETED`, its unpicked reservations are released and immediately reallocated to the next open orders, its shortages leave the shopping list) or **rejects** it (the report is cleared and the order goes back to normal picking). Without this, reservations of finished orders hang forever and falsify the shopping list.

## Current State Analysis

- `OrderStatus.COMPLETED` already exists in the enum (`src/main/java/pl/regavio/stockahead/orders/OrderStatus.java:7`) and in the DB `CHECK` (`V6__create_orders.sql`), but nothing ever sets it. There is no column for a "reported, awaiting confirmation" state.
- Releasing reservations needs no new allocation logic. `ReservationAllocator.reallocateForParts` (`ReservationAllocator.java:64`) rebuilds allocation only from `OPEN` lines (`OrderLineRepository.findOpenLinesForParts`, `OrderLineRepository.java:30-32`), and the shopping list / parts "reserved" column likewise aggregate `OPEN` lines only (`OrderLineRepository.java:13-14,34-37`). Unpicked units are still inside `Part.quantity` (only picking decrements it, `PickingController.java:132`), so flipping an order to `COMPLETED` and reallocating its parts in the same transaction hands those units to the next orders in allocation order.
- A taken order's lines keep their `reservedQuantity` frozen (`ReservationAllocator.java:82-90`); once the order leaves `OPEN` that number would be a phantom on the completed order's own views unless zeroed. Zeroing satisfies `order_lines_quantities_check` (`V7__add_order_picking.sql`).
- Pick action pattern to mirror: `PickingController.pick` (`PickingController.java:94-152`) — scalar pre-lock lookup (to avoid poisoning the open-in-view persistence context), `partRepository.findByIdInForUpdate`, fresh entity re-read, state re-check, write, `LockRetry` + `DataIntegrityViolationException`/`PessimisticLockingFailureException` → friendly re-render.
- `/orders` (`OrderController.java:71-77`, manager-only) and `/picking` (`PickingController.java:79-86`, both roles) list `OPEN` orders only. Order detail (`OrderDetailModel`) and picking detail (`PickingDetailModel`) copy entities into view records.
- Current user is available via an `Authentication` controller argument (`PartController.java:61`) + `AccountRepository.findByEmail` (`AccountRepository.java:12`).

## Desired End State

- A taken, `OPEN`, not-yet-reported order shows a **"Zgłoś zakończenie"** button on `/picking/{id}` to both roles. Submitting it stamps `completion_reported_at` / `completion_reported_by`; the page then shows "Zgłoszone do potwierdzenia" and no pick forms. A pick POST on a reported order is rejected with a friendly error.
- `/orders` shows a **"Do potwierdzenia"** section (reported orders) above the remaining open orders. `/orders/{id}` of a reported order shows who/when reported, a warning listing lines with unmet quantity (picked < required), and **"Potwierdź zakończenie"** / **"Odrzuć zgłoszenie"** buttons (manager only).
- Confirm: status `COMPLETED`, `completed_at` set, all its lines `reserved_quantity = 0`, and the released units reallocated to other open non-taken orders by priority → date → age. The order disappears from `/orders` and `/picking`; its shortages disappear from `/purchasing`.
- Reject: report fields cleared; order is back to normal taken/OPEN picking and can be reported again.
- Verify: `./mvnw verify` green, including new integration + concurrency tests.

### Key Discoveries:

- Release-by-status-flip works because every reservation consumer filters on `OPEN` (`OrderLineRepository.java:13,31,35`).
- `findByIdInForUpdate` is the lock every stock/reservation writer takes (`PartRepository.java:29`); taking the same lock in report/confirm/reject serializes them against picks and order creation without a new lock type.
- Lessons in force: test the wrong-role 403 for each `@PreAuthorize` route; catch `DataIntegrityViolationException` at write boundaries; run repeatable mutations at least twice on the same row (`context/foundation/lessons.md`).

## What We're NOT Doing

- No direct manager "confirm without report" shortcut — confirmation always requires a pending report (manager may file it themselves).
- No reporting of untaken orders, and no requirement that every line be fully picked.
- No rejection reason/comment field, no notification to the technician.
- No completed-orders history list (FR-006 history is a Non-Goal); completed orders are reachable only by direct URL.
- No cancellation and no return of picked parts to stock — that is S-09 (`order-change-and-cancel`). S-09 must treat a reported order explicitly (noted in Open Risks of the brief).
- No priority/date edits, no realtime push to other sessions.

## Implementation Approach

One new nullable state on `orders` (reported) plus `COMPLETED` for confirmed. Every transition (report, confirm, reject) runs in one `TransactionTemplate` inside `LockRetry`, locking the order's part rows first (same lock as picking), then re-reading the order fresh and re-checking its state, so double submits and pick-vs-report races resolve to a friendly error rather than an inconsistent state. Confirm reuses `ReservationAllocator` unchanged.

## Critical Implementation Details

- **State sequencing on confirm:** set `status = COMPLETED` and zero the lines' `reservedQuantity`, `flush`, and only then call `reservationAllocator.reallocateForParts(partIds)` in the same transaction. If the allocator ran first, the still-`OPEN` order's frozen (taken) reservation would be subtracted from the pool and the released units would not reach other orders.
- **Pre-lock lookup must be scalar:** fetch the order's part ids with a scalar JPQL projection (new `OrderLineRepository.findPartIdsForOrder`), never via `Order.getLines()`, for the same open-in-view stale-cache reason documented on `PickingController` (`PickingController.java:34-48`).

## Phase 1: Schema & completion state

### Overview

Add the reported/completed columns with DB invariants, map them on `Order`, and make the pick action refuse reported orders.

### Changes Required:

#### 1. Migration

**File**: `src/main/resources/db/migration/V8__add_order_completion.sql`

**Intent**: Persist the report and the completion moment, and let the DB reject impossible states.

**Contract**: `orders` gains `completion_reported_at TIMESTAMPTZ NULL`, `completion_reported_by BIGINT NULL REFERENCES accounts(id) ON DELETE RESTRICT`, `completed_at TIMESTAMPTZ NULL`; `CHECK (completion_reported_at IS NULL OR taken_at IS NOT NULL)`; `CHECK ((completion_reported_at IS NULL) = (completion_reported_by IS NULL))`; `CHECK (status <> 'COMPLETED' OR (completed_at IS NOT NULL AND completion_reported_at IS NOT NULL))`.

#### 2. Order entity

**File**: `src/main/java/pl/regavio/stockahead/orders/Order.java`

**Intent**: Map the new columns and expose the state predicates controllers and views use.

**Contract**: fields `completionReportedAt` (`Instant`), `completionReportedBy` (`@ManyToOne Account`, lazy), `completedAt` (`Instant`); `isCompletionReported()` = `completionReportedAt != null`; `canReportCompletion()` = `status == OPEN && isTaken() && !isCompletionReported()`.

#### 3. Part-id lookup for an order

**File**: `src/main/java/pl/regavio/stockahead/orders/OrderLineRepository.java`

**Intent**: Scalar pre-lock lookup of every part an order touches, used by all three transitions.

**Contract**: `List<Long> findPartIdsForOrder(Long orderId)` — `SELECT ol.part.id FROM OrderLine ol WHERE ol.order.id = :orderId`.

#### 4. Pick guard

**File**: `src/main/java/pl/regavio/stockahead/orders/PickingController.java`, `src/main/resources/messages.properties`

**Intent**: Picking is frozen once completion is reported.

**Contract**: inside the locked transaction, after the `OPEN` check, `order.isCompletionReported()` → `picking.error.completionReported` ("Zlecenie zostało zgłoszone jako zakończone — pobieranie jest wstrzymane.").

### Success Criteria:

#### Automated Verification:

- `./mvnw verify` passes (Flyway applies V8 under `ddl-auto=validate`)
- New schema test asserts the three `CHECK`s reject: report on an untaken order, reporter without timestamp, `COMPLETED` without `completed_at`
- `PickingIntegrationTests` gains: pick on a reported order is rejected, stock and line quantities unchanged

#### Manual Verification:

- App starts via `./mvnw spring-boot:test-run` and existing picking flow still works

**Implementation Note**: After automated verification passes, pause for manual confirmation before Phase 2.

---

## Phase 2: Technician report

### Overview

Both roles can report a taken order as finished from the picking detail page.

### Changes Required:

#### 1. Report action

**File**: `src/main/java/pl/regavio/stockahead/orders/PickingController.java`

**Intent**: Stamp the report under the same part locks a pick takes, so a concurrent pick either lands before the report or is rejected after it.

**Contract**: `POST /picking/{orderId}/report-completion`, `@PreAuthorize("hasAnyRole('MANAGER','TECHNICIAN')")`. Unknown order → 404. Shape: `findPartIdsForOrder` → `lockRetry.executeWithLockRetry(transactionTemplate.execute(findByIdInForUpdate(partIds) → orderRepository.findById → re-check))`. Errors (friendly re-render of `picking-detail`): not `OPEN` → `picking.error.orderNotOpen`; not taken → `picking.error.reportNotTaken`; already reported → `picking.error.alreadyReported`; `DataIntegrityViolationException`/`PessimisticLockingFailureException` → `picking.error.reportFailed`. Success sets `completionReportedAt = now`, `completionReportedBy` = account resolved from `Authentication.getName()`, redirects to `/picking/{orderId}`.

#### 2. Picking views

**File**: `src/main/java/pl/regavio/stockahead/orders/PickingDetailModel.java`, `src/main/resources/templates/picking-detail.html`, `src/main/resources/templates/picking-list.html`, `src/main/resources/messages.properties`

**Intent**: Show the button only when reporting is allowed, show the pending state otherwise, and hide pick forms on a reported order.

**Contract**: `OrderView` gains `canReportCompletion`, `completionReported`. Pick form condition adds `and !order.completionReported`. `picking-list` shows a "Zgłoszone" marker on reported rows.

### Success Criteria:

#### Automated Verification:

- `./mvnw verify` passes
- New `OrderCompletionIntegrationTests`: technician reports a taken order (fields stamped, reporter = that account); report on an untaken order rejected; second report rejected; report on a non-`OPEN` order rejected; unknown order 404; unauthenticated → login redirect
- New `OrderCompletionConcurrencyTests`: concurrent pick and report on the same order — afterwards either the pick committed before the report or it was rejected; never a pick recorded after `completion_reported_at`

#### Manual Verification:

- As technician: pick part of an order, click "Zgłoś zakończenie", see pending notice and no pick forms; button absent on an untaken order

**Implementation Note**: After automated verification passes, pause for manual confirmation before Phase 3.

---

## Phase 3: Manager confirm/reject & visibility

### Overview

Managers see pending reports on `/orders`, review them on the detail page, and confirm (release + reallocate) or reject.

### Changes Required:

#### 1. Confirm & reject actions

**File**: `src/main/java/pl/regavio/stockahead/orders/OrderController.java`, `src/main/resources/messages.properties`

**Intent**: Close the order and hand its unpicked reservations to the next orders, or send it back to picking.

**Contract**: `POST /orders/{id}/confirm-completion` and `POST /orders/{id}/reject-completion`, both `@PreAuthorize("hasRole('MANAGER')")`, same lock/re-check shape as the report. Both require `status == OPEN && isCompletionReported()`, otherwise friendly error `orders.error.notReported` re-rendered on `orders-detail`; DB/lock failures → `orders.error.completionFailed`. Confirm: `status = COMPLETED`, `completedAt = now`, every line `reservedQuantity = 0`, flush, `reservationAllocator.reallocateForParts(partIds)`. Reject: clear `completionReportedAt` / `completionReportedBy`. Both redirect to `/orders` on success.

#### 2. Orders list & detail

**File**: `OrderController.list`, `src/main/java/pl/regavio/stockahead/orders/OrderDetailModel.java`, `src/main/resources/templates/orders-list.html`, `src/main/resources/templates/orders-detail.html`

**Intent**: Make pending reports impossible to miss and give the manager what they need to decide.

**Contract**: `list` puts `pendingOrders` (reported, in allocation order) and `orders` (open, not reported) in the model; template renders a "Do potwierdzenia" section above "Zlecenia" (section hidden when empty). `OrderDetailModel.OrderView` gains `status`, `completionReported`, `reportedAt`, `reportedByEmail`, `completedAt`; `LineView` gains `pickedQuantity`. Detail shows reporter/time, a warning listing lines where picked < required, and the confirm/reject forms only when reported and `OPEN`; a `COMPLETED` order shows "Zakończone <completedAt>". `render` gets an error overload like `PickingDetailModel`.

### Success Criteria:

#### Automated Verification:

- `./mvnw verify` passes
- `OrderCompletionIntegrationTests` adds: confirm sets `COMPLETED` + `completed_at` and zeroes reserved; a lower-priority non-taken order sharing a part receives the released units (e.g. order A 10 needed / 6 reserved / 2 picked, order B short 4 → B gets 4 after confirm); confirmed order's shortage disappears from `/purchasing`; confirm/reject without a report rejected; reject clears fields and pick works again; report → reject → pick → report → confirm cycle succeeds (repeat-mutation lesson); technician gets 403 on both confirm and reject; `/orders` lists the reported order under "Do potwierdzenia" and not among open; completed order absent from `/orders` and `/picking`
- `MessagesBundleTests` still passes with the new keys

#### Manual Verification:

- As manager: see the pending section, open the order, read the shortage warning, confirm — the next order's reservation grows and the shopping list shrinks; reject a second report and verify the technician can pick again

**Implementation Note**: After automated verification passes, pause for manual confirmation.

---

## Testing Strategy

### Unit Tests:

- None separate — allocator is unchanged; behavior is covered through real-Postgres integration tests per AGENTS.md.

### Integration Tests:

- `OrderCompletionIntegrationTests` (MockMvc + Testcontainers, seeding via `JdbcTemplate` as in `PickingIntegrationTests`): all state transitions, wrong-role 403s, release/reallocation, shopping list effect, repeat cycle.
- `OrderCompletionConcurrencyTests` (pattern from `PickingConcurrencyTests`): pick vs report race.
- Schema test for V8 `CHECK`s.

### Manual Testing Steps:

1. Technician picks part of an order and reports it; pick forms vanish.
2. Manager sees it under "Do potwierdzenia", rejects; technician picks again and re-reports.
3. Manager confirms; a competing order's reservation grows, `/purchasing` updates, order disappears from both lists.

## Performance Considerations

Confirm locks only the confirmed order's parts and reallocates only them — same cost as an order creation. No new hot paths.

## Migration Notes

V8 only adds nullable columns; no existing row is `COMPLETED`, so the new `CHECK`s hold for current data.

## References

- Roadmap item: `context/foundation/roadmap.md` (S-08)
- PRD: `context/foundation/prd.md` FR-014, FR-020, §Business Logic, §Access Control
- Pattern: `src/main/java/pl/regavio/stockahead/orders/PickingController.java:94-152`
- Allocator: `src/main/java/pl/regavio/stockahead/orders/ReservationAllocator.java:64-106`
- Prior change: `context/archive/2026-10-01-picking-list-and-pick/plan.md`

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles. See `references/progress-format.md`.

### Phase 1: Schema & completion state

#### Automated

- [x] 1.1 `./mvnw verify` passes (Flyway applies V8 under `ddl-auto=validate`)
- [x] 1.2 New schema test asserts the three `CHECK`s reject: report on an untaken order, reporter without timestamp, `COMPLETED` without `completed_at`
- [x] 1.3 `PickingIntegrationTests` gains: pick on a reported order is rejected, stock and line quantities unchanged

#### Manual

- [x] 1.4 App starts via `./mvnw spring-boot:test-run` and existing picking flow still works

### Phase 2: Technician report

#### Automated

- [ ] 2.1 `./mvnw verify` passes
- [ ] 2.2 New `OrderCompletionIntegrationTests`: report happy path, untaken/second/non-OPEN report rejected, unknown order 404, unauthenticated redirect
- [ ] 2.3 New `OrderCompletionConcurrencyTests`: pick vs report race never records a pick after the report

#### Manual

- [ ] 2.4 As technician: report a picked order, see pending notice and no pick forms; button absent on an untaken order

### Phase 3: Manager confirm/reject & visibility

#### Automated

- [ ] 3.1 `./mvnw verify` passes
- [ ] 3.2 `OrderCompletionIntegrationTests` covers confirm/release/reallocation, shopping list effect, reject, repeat cycle, technician 403s, list sections
- [ ] 3.3 `MessagesBundleTests` still passes with the new keys

#### Manual

- [ ] 3.4 As manager: confirm from the pending section (next order's reservation grows, shopping list shrinks); reject a second report and verify picking resumes
