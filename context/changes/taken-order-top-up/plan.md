# Taken Order Top-Up Implementation Plan

## Overview

A taken order (first pick done) is protected from takeover, but today it is also excluded from allocation, so a taken order that is short never receives units freed later (order-completion review F6, Roadmap Open Question 4). This plan makes a short, unreported taken order take part in normal allocation order (priority → required date → age) for its unmet quantity on every reallocation event. Its current reservation is never reduced. Reject-completion catches a previously reported order up, and the picking list shows technicians which orders have parts waiting.

## Current State Analysis

- `ReservationAllocator.reallocateForParts` (`ReservationAllocator.java:64-106`) splits affected OPEN lines into taken and non-taken. It subtracts every taken line's `reservedQuantity` from the per-part pool, then rebuilds only non-taken lines from scratch in `ALLOCATION_ORDER`. Taken lines are never written (frame: hypothesis 1, STRONG).
- This "frozen" behaviour was a deliberate MVP simplification (`context/archive/2026-10-01-picking-list-and-pick/plan-brief.md:22,38,61`). The PRD only forbids *takeover* of taken reservations; its delivery and release rules ("uzupełniają rezerwacje wg kolejności", "trafiają do kolejnych zleceń") do not exempt taken orders (frame: hypothesis 2, STRONG).
- Callers today: confirm-completion (`OrderController.java:128`) and order creation (`OrderController.java:252`). S-09 to S-12 (cancel, delivery, correction, import) will call the same allocator and inherit the rule.
- Reject-completion (`OrderController.java:139-145`) clears the report but does not reallocate.
- A completion-reported order is still `OPEN` and taken but cannot be picked (`PickingController.java:131`).
- Picking already handles a growing reservation: `remainingToPick == reservedQuantity` (`PickingDetailModel.java:91-98`), so pick forms reappear once a taken line receives units. Shortage is `OrderLine.getMissingQuantity()` = required − reserved − picked, which already holds for a topped-up line.
- `/picking` (`PickingController.java:89-95`) lists OPEN orders in allocation order with no per-order quantity.
- No existing test asserts that a taken order stays short; `ReservationAllocatorTests.java:259,282,305` only assert that a taken reservation survives competitors.

## Desired End State

- On any reallocation for a set of parts, each taken, unreported OPEN order line with unmet quantity (required − picked − reserved > 0) receives extra units in `ALLOCATION_ORDER` alongside non-taken orders. It never ends below its previous reservation and never above required − picked.
- A completion-reported order keeps its reservation and receives nothing extra. When its report is rejected, its parts are reallocated in the same transaction, so it catches up immediately.
- `/picking` shows a "Do pobrania" count per order (sum of `reservedQuantity`), so a waiting order that just received parts stands out.
- PRD § Business Logic states the rule; Roadmap Open Question 4 is closed with the decision.
- Verify: `./mvnw verify` green, including new allocator, integration and concurrency tests.

### Key Discoveries:

- Lock scope does not change: the allocator still locks exactly `findByIdInForUpdate(partIds)` (`ReservationAllocator.java:75`). Only the set of lines written grows, so the lessons.md rule on narrowing allocator locks does not apply.
- `transitionReportedOrder` already hands confirm/reject the pre-lock `partIds` under the part locks (`OrderController.java`, BiConsumer since review F8), so reject can reallocate without new locking code.
- Lessons in force: run repeatable mutations at least twice on the same row (pick a topped-up line twice); reach "taken" and "reported" only through real endpoints, never raw-SQL seeds (order-completion review F3); every new write path catches `DataIntegrityViolationException`/`PessimisticLockingFailureException` (reject already does).

## What We're NOT Doing

- No "taken orders first" bonus: extra units follow normal allocation order, so a taken LOW order does not beat a non-taken HIGH one.
- No partial completion / built-units count. That is roadmap S-13 (`order-partial-completion`).
- No new stock-freeing events (cancel, delivery, correction, import). Those are S-09 to S-12; they inherit this rule by calling the allocator.
- No schema change and no new lock type.
- No notification or push to technicians beyond the picking-list column.
- No change to how much a technician may pick (still at most the current `reservedQuantity`).

## Implementation Approach

Keep the allocator one pass in `ALLOCATION_ORDER`, but over a wider set of orders. First, the pool for each part is seeded with current stock minus the current reservation of **every** taken line (reported or not); this is the protection floor. Then non-taken orders and taken, unreported orders are processed together in allocation order. A non-taken line is rebuilt from zero as today. A taken, unreported line only gains `min(pool, required − picked − reserved)`. Reported lines are never written. Reject reuses the existing locked transition and calls the allocator after clearing the report.

## Critical Implementation Details

- **State sequencing on reject:** clear `completionReportedAt`/`completionReportedBy` and `saveAndFlush` *before* `reallocateForParts(partIds)`. Otherwise the order is still reported when the allocator reads it, and it is skipped (same flush-then-reallocate pattern as confirm).
- **Floor before top-up:** taken lines' current reservations must leave the pool before any order is processed (including taken ones), or a higher-priority non-taken order could absorb units that belong to a taken order's existing reservation. That would be takeover, which the PRD forbids.

## Phase 1: Allocator top-up

### Overview

Change the allocation rule so taken, unreported lines can grow in allocation order, and prove it with allocator-level tests.

### Changes Required:

#### 1. Allocation rule

**File**: `src/main/java/pl/regavio/stockahead/orders/ReservationAllocator.java`

**Intent**: Treat "taken" as "protected from takeover" instead of "excluded from allocation": taken, unreported lines join the allocation pass but can only grow.

**Contract**: `reallocateForParts(Set<Long>)` signature and locking unchanged. Per affected part:
- pool = stock − Σ reserved of all taken lines (clamped at 0, as today);
- for each order in `ALLOCATION_ORDER` over (non-taken OPEN) ∪ (taken, `!isCompletionReported()` OPEN):
  - non-taken line: `reserved = min(pool, required)`;
  - taken line: `reserved += min(pool, required − picked − reserved)`;
  - in both cases `pool` decreases by what was granted;
- reported taken lines are untouched.

Update the class and method javadoc to describe protection versus top-up and the reported exclusion.

#### 2. Allocator tests

**File**: `src/test/java/pl/regavio/stockahead/orders/ReservationAllocatorTests.java`

**Intent**: Pin the new rule and keep the existing protection tests green. Reach "taken" via the existing real `pick` helper. Raise stock with a direct `parts.quantity` update to stand in for a future delivery or correction (a reachable state once S-10/S-11 exist).

**Contract**: new tests:
- `takenShortOrderReceivesNewlyAvailableUnits`;
- `takenOrderTopUpIsCappedAtRequiredMinusPicked`: two real picks, then extra stock; reserved + picked never exceeds required;
- `nonTakenHigherPriorityOrderGetsExtraUnitsBeforeTakenLowerPriorityOrder`;
- `takenHigherPriorityOrderTopsUpBeforeNonTakenLowerPriorityOrder`: the non-taken lower order shrinks, as normal preemption among non-taken reservations;
- `reportedTakenOrderReceivesNoExtraUnits`: report via the real endpoint;
- `takenOrderReservationIsNeverReducedByTopUpPass`.

Existing `:259,282,305` tests stay unchanged and green.

### Success Criteria:

#### Automated Verification:

- `./mvnw -q test -Dtest=ReservationAllocatorTests` passes, including the six new tests
- `./mvnw verify` passes

#### Manual Verification:

- None (allocator-only phase; behaviour is visible in Phase 2's flows)

**Implementation Note**: After automated verification passes, continue to Phase 2 (no manual gate).

---

## Phase 2: Event paths: confirm, create, reject

### Overview

Make reject-completion reallocate, and prove the rule end to end through real endpoints and under concurrency.

### Changes Required:

#### 1. Reject reallocates

**File**: `src/main/java/pl/regavio/stockahead/orders/OrderController.java`

**Intent**: A rejected report returns the order to picking; it must immediately receive any units it missed while it was reported.

**Contract**: the reject lambda in `transitionReportedOrder` clears both report fields, calls `saveAndFlush`, then calls `reservationAllocator.reallocateForParts(partIds)`. Error handling is unchanged (`orders.error.completionFailed` on DB/lock failure). Update the confirm javadoc ("open non-taken orders") to "open orders in allocation order, including short taken ones".

#### 2. End-to-end tests

**File**: `src/test/java/pl/regavio/stockahead/orders/OrderCompletionIntegrationTests.java`

**Intent**: Prove the rule on real flows with states reached only through endpoints.

**Contract**: new tests:
- `confirmHandsReleasedUnitsToAShortTakenOrder`: A and B share a part. B is taken (real pick) and short. Confirming A gives B the freed units. The technician then picks them in two real picks, the second taking the remainder.
- `reportedShortOrderIsSkippedUntilRejectCatchesItUp`: B is reported. Confirming A sends nothing to B; the units go to the next eligible order or stay free. Rejecting B's report hands B its share in allocation order.
- `newLowPriorityOrderDoesNotTakeUnitsAShortTakenHigherOrderNeeds`: a newly created order after a confirm does not take units ahead of the short taken order.

#### 3. Concurrency

**File**: `src/test/java/pl/regavio/stockahead/orders/OrderCompletionConcurrencyTests.java`

**Intent**: The top-up path writes taken lines that a technician may be picking at the same moment.

**Contract**: new test `concurrentConfirmAndPickOnTheTakenOrderBeingToppedUp`. Race confirm of A against a pick on short taken order B sharing the part, alternating which goes first as in the existing confirm-vs-create test. After every iteration: no 500; stock ≥ 0; for every line, reserved + picked ≤ required; Σ reserved over OPEN lines ≤ stock.

### Success Criteria:

#### Automated Verification:

- `./mvnw -q test -Dtest='OrderCompletionIntegrationTests,OrderCompletionConcurrencyTests'` passes, including the four new tests
- `./mvnw verify` passes

#### Manual Verification:

- As technician: pick part of order B (short), report order A complete; as manager confirm A, and check that B's picking page offers the freed parts
- Report B, confirm another order sharing the part, check B gets nothing; reject B's report, check B's pick forms show the units

**Implementation Note**: After automated verification passes, pause for manual confirmation before Phase 3.

---

## Phase 3: Technician signal and docs

### Overview

Show which orders have parts waiting to be picked, and record the rule in the PRD and roadmap.

### Changes Required:

#### 1. "Do pobrania" on the picking list

**File**: `src/main/java/pl/regavio/stockahead/orders/OrderLineRepository.java`, `src/main/java/pl/regavio/stockahead/orders/PickingController.java`, `src/main/resources/templates/picking-list.html`

**Intent**: A waiting taken order that just received parts must stand out without opening every order.

**Contract**:
- new scalar query `List<Object[]> reservedQuantitiesByOpenOrder()`: `SELECT ol.order.id, SUM(ol.reservedQuantity)` over OPEN orders, grouped by order;
- `PickingController.list` adds a `Map<Long, Long> toPickByOrderId` model attribute;
- `picking-list.html` gains a "Do pobrania" column showing the count (0 when absent).

#### 2. Picking list test

**File**: `src/test/java/pl/regavio/stockahead/orders/PickingListAndDetailIntegrationTests.java`

**Intent**: Pin the new column.

**Contract**: new test `pickingListShowsUnitsToPickPerOrderIncludingToppedUpTakenOrder`. A taken short order shows its reserved count before and after a confirm tops it up. Reached via real pick, report and confirm endpoints.

#### 3. PRD and roadmap

**File**: `context/foundation/prd.md`, `context/foundation/roadmap.md`

**Intent**: Make the decided rule the documented rule and close the open question.

**Contract**:
- PRD § Business Logic gains one sentence: a taken order keeps its unpicked reservation, and its remaining shortfall is still filled in allocation order whenever units become free, except while its completion report awaits confirmation.
- Roadmap Open Roadmap Question 4 is marked resolved with that decision and a link to this change.

### Success Criteria:

#### Automated Verification:

- `./mvnw -q test -Dtest=PickingListAndDetailIntegrationTests` passes, including the new test
- `./mvnw verify` passes

#### Manual Verification:

- As technician: `/picking` shows "Do pobrania" per order, and the count rises on a waiting order after a manager confirms another order sharing its part

**Implementation Note**: After automated verification passes, pause for manual confirmation.

---

## Testing Strategy

### Unit Tests:

- None separate. The allocator is tested against real Postgres in `ReservationAllocatorTests`, per AGENTS.md.

### Integration Tests:

- Allocator rule matrix (Phase 1), real-endpoint flows for confirm, create and reject (Phase 2), picking-list column (Phase 3). All taken and reported states are reached through endpoints.

### Manual Testing Steps:

1. Technician picks part of a short order B; manager confirms another order A sharing the part; B's picking page and `/picking` count show the freed units.
2. B reported → confirm another order → B gets nothing → reject B → B catches up.
3. A new LOW order created afterwards does not take B's units.

## Performance Considerations

Same locks and the same single pass over the affected parts' OPEN lines as today; the extra work is a few comparisons per taken line. The picking-list query is one grouped aggregate.

## Migration Notes

None. No schema change. Existing taken orders start receiving units on the next reallocation that touches their parts.

## References

- Frame brief: `context/changes/taken-order-top-up/frame.md`
- Allocator: `src/main/java/pl/regavio/stockahead/orders/ReservationAllocator.java:64-106`
- Callers: `src/main/java/pl/regavio/stockahead/orders/OrderController.java:113-145, 252`
- Picking guard: `src/main/java/pl/regavio/stockahead/orders/PickingController.java:131`
- Prior decision: `context/archive/2026-10-01-picking-list-and-pick/plan-brief.md:22,38,61`
- Review: `context/archive/2026-10-01-order-completion/reviews/impl-review.md` F6
- Roadmap: `context/foundation/roadmap.md` Open Roadmap Question 4, S-13

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles. See `references/progress-format.md`.

### Phase 1: Allocator top-up

#### Automated

- [x] 1.1 `./mvnw -q test -Dtest=ReservationAllocatorTests` passes, including the six new tests — 0e2c0f4
- [x] 1.2 `./mvnw verify` passes — 0e2c0f4

### Phase 2: Event paths: confirm, create, reject

#### Automated

- [x] 2.1 `./mvnw -q test -Dtest='OrderCompletionIntegrationTests,OrderCompletionConcurrencyTests'` passes, including the four new tests — af2c285
- [x] 2.2 `./mvnw verify` passes — af2c285

#### Manual

- [x] 2.3 As technician: pick part of order B (short), report order A complete; as manager confirm A, and check that B's picking page offers the freed parts — af2c285
- [x] 2.4 Report B, confirm another order sharing the part, check B gets nothing; reject B's report, check B's pick forms show the units — af2c285

### Phase 3: Technician signal and docs

#### Automated

- [x] 3.1 `./mvnw -q test -Dtest=PickingListAndDetailIntegrationTests` passes, including the new test — a0feb4d
- [x] 3.2 `./mvnw verify` passes — a0feb4d

#### Manual

- [x] 3.3 As technician: `/picking` shows "Do pobrania" per order, and the count rises on a waiting order after a manager confirms another order sharing its part — a0feb4d
