# Order Partial Completion Implementation Plan

## Overview

Roadmap S-13 (FR-014, FR-020 extension): when reporting an order as finished, the technician enters how many device units were actually built (0–N). The manager sees that number and confirms or rejects the report. A confirmed order with fewer than N built closes as **partially completed** and keeps showing "zbudowano X z N". The unbuilt remainder is dropped: its reservations are released and its shortage leaves the shopping list, exactly as a full confirmation does today. The manager re-orders the rest by hand if needed.

## Current State Analysis

- Completion is report → confirm/reject (S-08). The report (`PickingController.java:191-233`) takes no input, and confirm (`OrderController.java:140-152`) sets `COMPLETED`, zeroes reservations, flushes and reallocates. A short order confirmed today looks identical to a full one. Its shortfall disappears from `/purchasing` and from every view except a pre-confirm warning (`orders-detail.html:47-53`).
- The schema stores no built count. V6 sets `status IN ('OPEN','CANCELLED','COMPLETED')`, and the V8 CHECKs tie `COMPLETED` to `completed_at` + report (`V8__add_order_completion.sql`).
- Completed orders appear in no list. `/orders` shows only `OPEN` orders, split into pending reports and open orders (`OrderController.java:113-122`), and `/orders/{id}` still renders completed ones.
- The PRD's FR-014/FR-020 are binary. The roadmap S-13 entry has a blocking unknown ("co dzieje się z niezbudowaną resztą"), which this plan resolves.

## Desired End State

- The technician's "Zgłoś zakończenie" form has a required "Zbudowano sztuk" field, pre-filled with N, accepting whole numbers 0…N. The picking page of a reported order shows "Zgłoszone do potwierdzenia — zbudowano X z N".
- The manager's "Do potwierdzenia" table on `/orders` has a "Zbudowano" column (`X / N`). The report section of the order detail page shows the count next to the existing unmet-lines warning.
- Confirming keeps today's mechanics (release + reallocate). Afterwards the detail page reads "Zakończone" (X = N), "Zakończone częściowo: zbudowano X z N" (X < N), or "Zakończone — liczba zbudowanych sztuk nie została zapisana" (legacy NULL).
- Rejecting clears the report **and** the count, and the technician re-reports with a new number.
- The PRD FR-014/FR-020 and the roadmap S-13 entry describe the decided rule.

### Key Discoveries:

- `PickingController.reportCompletion` already re-reads the order under the part-row lock (`PickingController.java:198-221`), so `quantityUnits` can be validated there with no new lock.
- Confirm/reject share `transitionReportedOrder` (`OrderController.java:375-385`). Reject only clears the two report columns (`:166-171`), so the count must be cleared there too.
- `OrderDetailModel.OrderView` (`OrderDetailModel.java:185-186`) and `PickingDetailModel.OrderView` (`PickingDetailModel.java:86-87`) are plain records copied in a read-only transaction. Each one needs a `builtUnits` field.
- The pending table on `orders-list.html:34-41` renders `Order` entities directly, so `order.builtUnits` is available once it is mapped.
- Tests: `OrderCompletionSchemaTests`, `OrderCompletionIntegrationTests`, `OrderCompletionConcurrencyTests`, `PickingListAndDetailIntegrationTests`, `OrderListAndDetailIntegrationTests` already cover this flow and are the files to extend.

## What We're NOT Doing

- No automatic follow-up order for the unbuilt remainder, and no "close + re-order" option at confirm. The manager creates a new order manually.
- No manager edit of the built count. A wrong number is fixed by rejecting, and the technician reports again.
- No comparison of the count with picked parts (no "more than the picks can cover" warning). The technician's number is authoritative, so substitute parts work.
- No new `OrderStatus` value. "Partial" is derived from `built_units < quantity_units` on a `COMPLETED` order.
- No completed/partial-orders history list on `/orders` (as in S-08; FR-006 stays nice-to-have).
- No backfill of existing completed orders. They keep `built_units` NULL.
- No change to `ReservationAllocator`, the lock strategy, the shopping list or cancellation.

## Implementation Approach

Add one nullable column, `orders.built_units`, guarded by DB CHECKs. Write it in the report transaction (validated against `quantity_units` re-read under the existing lock), clear it on reject, and leave confirm unchanged apart from what it displays. The PRD is amended first so the code follows a written rule.

## Critical Implementation Details

- **Constraint vs. in-flight reports:** reports already pending when V12 runs have no count. The "report requires count" rule is therefore enforced in the application, and the DB only enforces `built_units IS NULL OR completion_reported_at IS NOT NULL` (no count without a report) plus the range. Such a legacy pending report can still be confirmed and then shows the "nie została zapisana" state.
- **Reject ordering:** clear `built_units` together with `completion_reported_at`/`by` *before* the existing `saveAndFlush`. Otherwise the new CHECK rejects the flush (count without a report) and reject fails with `orders.error.completionFailed`.

## Phase 1: PRD rule & schema

### Overview

Write the decided rule into the PRD and roadmap, then add the column, constraints and entity mapping.

### Changes Required:

#### 1. PRD

**File**: `context/foundation/prd.md`

**Intent**: Make the partial-completion rule part of the spec so the implementation and later changes follow it.

**Contract**: FR-014 says the technician reports completion with the number of built units (0…N, the technician's own number, not derived from picks). FR-020 says confirmation with X < N closes the order as partially completed showing "zbudowano X z N", the remainder is dropped (reservations released, shortage leaves the shopping list), and the manager creates a new order for it if needed. A wrong count is rejected and re-reported, and the manager does not edit it. Each FR gets a short `> Socrates:` resolution line in the existing style.

#### 2. Roadmap S-13

**File**: `context/foundation/roadmap.md`

**Intent**: Close the S-13 unknowns with the decisions above.

**Contract**: In the S-13 block, mark both `Unknowns` as resolved (remainder dropped, manager re-orders manually; count 0…N, no derived-from-picks check, manager cannot edit — rejects instead). Update the PRD refs note to drop "wymaga uzupełnienia PRD przed planowaniem" and the Backlog Handoff row's "Wymaga uzupełnienia PRD…" note. Status flips are handled by the skill workflow, not this phase.

#### 3. Migration V12

**File**: `src/main/resources/db/migration/V12__add_order_built_units.sql`

**Intent**: Store the technician's built count.

**Contract**: `orders.built_units INT NULL`; CHECK `built_units IS NULL OR (built_units >= 0 AND built_units <= quantity_units)`; CHECK `built_units IS NULL OR completion_reported_at IS NOT NULL`. No backfill. Use named constraints (`orders_built_units_range_check`, `orders_built_units_requires_report_check`).

#### 4. Order entity

**File**: `src/main/java/pl/regavio/stockahead/orders/Order.java`

**Intent**: Map the column and expose the derived state.

**Contract**: `Integer builtUnits` with getter/setter. `isPartiallyCompleted()` is true when status is `COMPLETED`, `builtUnits != null` and `builtUnits < quantityUnits`.

#### 5. Schema tests

**File**: `src/test/java/pl/regavio/stockahead/orders/OrderCompletionSchemaTests.java`

**Intent**: Pin the two CHECKs.

**Contract**: Raw-SQL tests: count −1 and N+1 rejected; count without a report rejected; NULL count with a report accepted; 0 and N with a report accepted.

### Success Criteria:

#### Automated Verification:

- `./mvnw verify` passes, including new `OrderCompletionSchemaTests` cases for range and report-required CHECKs
- Flyway applies V12 on a fresh database (covered by the Testcontainers run)

#### Manual Verification:

- PRD FR-014/FR-020 and roadmap S-13 read consistently with the decisions in `plan-brief.md`

**Implementation Note**: After automated verification passes, pause for manual confirmation before Phase 2.

---

## Phase 2: Technician report with built count

### Overview

The report form collects the count, the server validates it under the lock, and the picking page shows it.

### Changes Required:

#### 1. Report action

**File**: `src/main/java/pl/regavio/stockahead/orders/PickingController.java`

**Intent**: Accept and validate the count, and store it with the report.

**Contract**: `POST /picking/{orderId}/report-completion` takes `@RequestParam(required = false) String builtUnits`. Before locking, parse it to an integer ≥ 0, or re-render with `picking.error.builtUnitsNotInteger`. Inside the locked transaction, after the existing state checks, reject `> order.getQuantityUnits()` with `picking.error.builtUnitsTooMany` (message takes N as `{0}`). Then set `builtUnits` alongside `completionReportedAt`/`By`. Validation errors go through the existing `renderReportError`.

#### 2. Picking detail model & template

**Files**: `src/main/java/pl/regavio/stockahead/orders/PickingDetailModel.java`, `src/main/resources/templates/picking-detail.html`

**Intent**: Show the input and the reported count.

**Contract**: `OrderView` gains `Integer builtUnits`. The form gets `<input type="number" name="builtUnits" min="0" th:max="${order.quantityUnits}" required>` labelled "Zbudowano sztuk", pre-filled with `quantityUnits`. The reported-state line becomes "Zgłoszone do potwierdzenia — zbudowano X z N" (count shown only when non-null).

#### 3. Messages

**File**: `src/main/resources/messages.properties`

**Contract**: `picking.error.builtUnitsNotInteger` ("Podaj liczbę zbudowanych sztuk (liczba całkowita, co najmniej 0).") and `picking.error.builtUnitsTooMany` ("Liczba zbudowanych sztuk nie może przekroczyć liczby sztuk w zleceniu ({0}).").

#### 4. Tests

**Files**: `src/test/java/pl/regavio/stockahead/orders/OrderCompletionIntegrationTests.java`, `PickingListAndDetailIntegrationTests.java`, `OrderCompletionConcurrencyTests.java`

**Intent**: Prove range validation, persistence and the existing guarantees with the new parameter.

**Contract**: Report with 0, with N and with 1 < X < N persists the value. Missing, non-integer, negative and N+1 values are rejected with the right message and leave the order unreported. A technician (not only a manager) can report with a count. The picking page shows the pre-filled field and, once reported, "zbudowano X z N". Existing report tests and the report-vs-pick concurrency test are updated to send `builtUnits`.

### Success Criteria:

#### Automated Verification:

- `./mvnw verify` passes with new report tests for 0, N, partial, missing, non-integer, negative and N+1 counts
- Existing completion and concurrency tests pass with `builtUnits` sent

#### Manual Verification:

- As a technician on a taken order, the report form is pre-filled with N, entering N+1 shows the error, and entering 7 of 10 shows "zbudowano 7 z 10" after reporting

**Implementation Note**: After automated verification passes, pause for manual confirmation before Phase 3.

---

## Phase 3: Manager review, confirm/reject & display

### Overview

The manager sees the count before deciding. Reject clears it, and completed orders show full, partial or unknown.

### Changes Required:

#### 1. Reject clears the count

**File**: `src/main/java/pl/regavio/stockahead/orders/OrderController.java`

**Intent**: A rejected report leaves no stale count, and the next report starts fresh.

**Contract**: `rejectCompletion` also sets `builtUnits` to null before the existing `saveAndFlush`. `confirmCompletion` is unchanged (the count stays as recorded). Update the Javadoc of both.

#### 2. Pending list column

**File**: `src/main/resources/templates/orders-list.html`

**Contract**: The "Do potwierdzenia" table gains a "Zbudowano" column rendering `builtUnits / quantityUnits`, or "—" when null.

#### 3. Order detail

**Files**: `src/main/java/pl/regavio/stockahead/orders/OrderDetailModel.java`, `src/main/resources/templates/orders-detail.html`

**Intent**: Show the count at review time and the outcome after confirmation.

**Contract**: `OrderView` gains `Integer builtUnits` (and a `partiallyCompleted()` helper or equivalent). The report section adds "Zbudowano: X z N" above the unmet-lines warning, plus a note when X < N that the remaining units will not be built and their shortage leaves the shopping list. The completed line shows three states: "Zakończone <date>" (X = N), "Zakończone częściowo: zbudowano X z N, <date>" (X < N), and "Zakończone <date> — liczba zbudowanych sztuk nie została zapisana" (null).

#### 4. Tests

**Files**: `src/test/java/pl/regavio/stockahead/orders/OrderCompletionIntegrationTests.java`, `OrderListAndDetailIntegrationTests.java`

**Intent**: Prove the outcome and the display states.

**Contract**:
- Confirming a 7-of-10 report sets `COMPLETED` with `built_units = 7`, releases reservations to the next order and removes the order's shortage from `/purchasing`.
- Rejecting clears `built_units`. Re-reporting with a different count stores the new value (two-cycle test).
- Pending list shows `7 / 10`. The detail page shows the partial, full and legacy-NULL texts, with NULL seeded by raw SQL as a pre-V12 report.
- A legacy pending report with NULL count can be confirmed.
- Wrong-role: a technician POSTing confirm/reject still gets 403.

### Success Criteria:

#### Automated Verification:

- `./mvnw verify` passes with new confirm-partial, reject-clears-count, two-cycle, legacy-NULL and display tests

#### Manual Verification:

- As a manager, a 7-of-10 report shows "7 / 10" on `/orders` and "Zbudowano: 7 z 10" on the detail page, and after confirming the detail reads "Zakończone częściowo: zbudowano 7 z 10"
- Rejecting and re-reporting with 10 shows "10 / 10" and confirms as "Zakończone"

---

## Testing Strategy

### Unit Tests:

- None separate. The logic is a range check plus display, and it is covered through integration tests against real Postgres (AGENTS.md: no H2/mocks for order invariants).

### Integration Tests:

- Schema CHECKs (range, count requires report).
- Report: boundary values 0, N, N+1, −1, non-integer, missing, for both roles.
- Confirm partial → reservations released, `/purchasing` drops the shortage, next order receives units.
- Reject → count cleared, then re-report → new count (exercise the action twice, per lessons.md).
- Display: pending column, three completed states including legacy NULL.
- Concurrency: existing report-vs-pick test still serializes with the new parameter.

### Manual Testing Steps:

1. Create a 10-unit order whose parts are partly short, pick some, then report with 7 as a technician.
2. As the manager, check `/orders` and the detail page, then confirm, and check that `/purchasing` no longer lists the order and the detail reads partial.
3. Repeat with a reject → re-report with 10 → confirm cycle.

## Performance Considerations

None. It adds one column read on existing pages.

## Migration Notes

V12 adds a nullable column with CHECKs and no backfill, so it is safe on the deployed database. Completed orders and pending reports that already exist keep NULL and show "nie została zapisana". Rollback means reverting the code. The column can stay.

## References

- Prior change: `context/archive/2026-10-01-order-completion/plan.md` (report/confirm/reject mechanics)
- Origin of the requirement: `context/archive/2026-10-01-taken-order-top-up/frame.md:14,20-21,57,66`
- Roadmap: `context/foundation/roadmap.md` S-13
- Report action: `src/main/java/pl/regavio/stockahead/orders/PickingController.java:191-233`
- Confirm/reject: `src/main/java/pl/regavio/stockahead/orders/OrderController.java:140-172, 375-385`
- Views: `src/main/resources/templates/orders-detail.html:20,43-61`, `orders-list.html:34-41`, `picking-detail.html:21-24`

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles. See `references/progress-format.md`.

### Phase 1: PRD rule & schema

#### Automated

- [x] 1.1 `./mvnw verify` passes, including new `OrderCompletionSchemaTests` cases for range and report-required CHECKs — c89246f
- [x] 1.2 Flyway applies V12 on a fresh database (covered by the Testcontainers run) — c89246f

#### Manual

- [x] 1.3 PRD FR-014/FR-020 and roadmap S-13 read consistently with the decisions in `plan-brief.md` — c89246f

### Phase 2: Technician report with built count

#### Automated

- [x] 2.1 `./mvnw verify` passes with new report tests for 0, N, partial, missing, non-integer, negative and N+1 counts — 9c2a444
- [x] 2.2 Existing completion and concurrency tests pass with `builtUnits` sent — 9c2a444

#### Manual

- [x] 2.3 As a technician on a taken order, the report form is pre-filled with N, entering N+1 shows the error, and entering 7 of 10 shows "zbudowano 7 z 10" after reporting — 9c2a444

### Phase 3: Manager review, confirm/reject & display

#### Automated

- [x] 3.1 `./mvnw verify` passes with new confirm-partial, reject-clears-count, two-cycle, legacy-NULL and display tests — cfdd5a6

#### Manual

- [x] 3.2 As a manager, a 7-of-10 report shows "7 / 10" on `/orders` and "Zbudowano: 7 z 10" on the detail page, and after confirming the detail reads "Zakończone częściowo: zbudowano 7 z 10" — cfdd5a6
- [x] 3.3 Rejecting and re-reporting with 10 shows "10 / 10" and confirms as "Zakończone" — cfdd5a6
