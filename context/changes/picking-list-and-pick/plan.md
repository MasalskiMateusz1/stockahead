# Picking List and Pick Implementation Plan

## Overview

Technicians (and managers) get a picking screen for OPEN orders: a list of orders to assemble, and a per-order detail showing each required part, its quantity, its locations, how much is reserved vs. already picked, and a form to pick more — partially or fully. Picking immediately reduces physical stock and introduces an order-level "taken" moment (first pick) that freezes the order's current reservation against future preemption by higher-priority orders, per PRD §Business Logic and AGENTS.md's hard rule.

## Current State Analysis

- `Order`/`OrderLine` (`src/main/java/pl/regavio/stockahead/orders/Order.java`, `OrderLine.java`) exist with `requiredQuantity`/`reservedQuantity` per line, but there is no "picked" concept anywhere and no "order taken" flag.
- `ReservationAllocator.reallocateForParts(Set<Long>)` (`src/main/java/pl/regavio/stockahead/orders/ReservationAllocator.java:58-82`) fully recomputes `reservedQuantity` for every OPEN order line touching a part, from scratch, in strict `ALLOCATION_ORDER` (priority desc → required date asc → created-at/id asc). It has no notion of protecting an order's existing reservation from being overwritten by a higher-priority newcomer.
- `/orders` and `/orders/{id}` (`OrderController.java:71-84`) are `@PreAuthorize("hasRole('MANAGER')")`-only. There is no technician-visible order/picking view, even though PRD §Access Control gives both roles "Lista projektów do zmontowania" and "Pobranie części ze stanu".
- `Part`/`PartLocation` (`src/main/java/pl/regavio/stockahead/parts/`) carry free-text locations with no per-location stock (PRD §Non-Goals), so the picking screen just lists all of a part's locations as text — same rendering as `parts-list.html:47`.
- `OrderController` already has the exact locking/retry/validation shape this plan reuses: `executeWithLockRetry` (`OrderController.java:171-182`), transactional validate-then-lock-then-write (`OrderController.java:124-154`), and friendly re-render on `DataIntegrityViolationException`/`PessimisticLockingFailureException`.
- `PartRepository.findByIdInForUpdate(Collection<Long>)` (`PartRepository.java:27-29`) is the only row-locking primitive in the codebase, used by `ReservationAllocator` and (indirectly, via it) `OrderController`.

### Key Discoveries:

- PRD §Business Logic's list of events that trigger a full reservation recompute (order creation, priority/date change, delivery, import, stock correction, cancel, close) does **not** include picking. A pick action shifts quantity from `reservedQuantity` to a new `pickedQuantity` counter on the same line while decrementing `Part.quantity` by the same amount — `reservedQuantity + pickedQuantity` and `stock - reservedQuantity` (availability for everyone else) are both invariant across a pick, so a pick never needs to call `ReservationAllocator`.
- The PRD's "podjęcie" (taken) concept is **order-level**, not line-level: "podjęcie = pierwsze pobranie części przez technika" applies to the whole order the instant any one of its lines is first picked, and from then on *all* of its lines' unpicked reservations are protected from preemption (PRD §Business Logic; AGENTS.md hard rule).
- `ReservationAllocator`'s "recompute everything affected from a clean stock pool" design (flagged in `context/foundation/lessons.md` as the riskiest code in the system) means the exclusion rule must subtract a taken order's *current* `reservedQuantity` from the pool before running the existing algorithm — not just skip writing to it — or the freed-up pool would silently let a competitor double-claim stock already earmarked for the taken order.
- `ProjectBomController`/`ProjectDetailModel` (`src/main/java/pl/regavio/stockahead/projects/`) are the closest existing precedent for "a parent detail page with per-child-row POST actions, each re-rendering the full detail page with an inline error on failure" — this plan's `PickingController`/`PickingDetailModel` mirror that shape exactly.

## Desired End State

A technician or manager opens `/picking`, sees every OPEN order sorted the same way the manager's order list is sorted (priority → date → age), opens one, and sees a row per BOM part with its required/reserved/picked/remaining-to-pick quantities and its locations as free text. Picking a quantity (≤ what's currently reserved-but-unpicked) reduces that part's physical stock, updates the row, and — on the order's first ever pick — marks the order "taken", visible on the page. A later, unrelated order (even one with higher priority) created or recomputed afterward can never shrink a taken order's existing reservation.

Verification: `./mvnw verify` passes, including new algorithmic tests proving the no-preemption guarantee and new concurrency tests proving it holds under real contention.

## What We're NOT Doing

- Blocking priority/required-date edits or cancellation on a taken order (FR-021, FR-011) — that's S-09 (`order-change-and-cancel`); this slice only introduces the `taken_at` flag and the allocator's respect for it.
- Letting a taken order's protected reservation *grow* later as new stock arrives (e.g. via a future delivery) — it stays frozen exactly as it was at the moment of taking. A future slice can revisit this if the business needs it.
- Order completion reporting/confirmation (FR-014, FR-020) — that's S-08 (`order-completion`).
- Batch/multi-line pick submission — each part line is picked with its own quantity and its own submit.
- Any change to how parts, locations, or projects are managed, or to the shopping list.

## Implementation Approach

Four phases, each one a prerequisite for the next: (1) the schema/entity plumbing the rest depends on, (2) the one change to the system's riskiest shared component — proven algorithmically before anything calls it from a controller, (3) the picking domain logic and controller with real-concurrency proof, (4) the UI and full-stack integration tests. This keeps the highest-risk work isolated and verified early, matching the codebase's existing `ReservationAllocatorTests`/`ReservationConcurrencyTests` → `OrderController` → integration-tests layering for `order-reserves-parts`.

## Critical Implementation Details

**Timing & lifecycle**: A pick never calls `ReservationAllocator.reallocateForParts`. The invariant `reservedQuantity + pickedQuantity ≤ requiredQuantity` and `stock - sum(reservedQuantity across all open lines) = availability` both hold before and after a pick without any recompute — only subsequent *other* events (new order, future delivery/correction/priority-change slices) call the allocator, and from then on they must treat taken-order lines as frozen (see Phase 2).

**State sequencing**: Order-taken detection happens inside the same locked transaction as the stock/quantity mutation: lock the part first (`findByIdInForUpdate`), then re-read the order line fresh, validate, mutate `Part.quantity`/`OrderLine.reservedQuantity`/`OrderLine.pickedQuantity`, and only then check `order.getTakenAt() == null` to stamp it — never stamp `taken_at` before the pick itself is known to be valid, or a rejected pick could wrongly freeze an untouched order.

## Phase 1: Schema & entities

### Overview

Add the two columns picking needs — `orders.taken_at` and `order_lines.picked_quantity` — and tighten `order_lines`' quantity invariant so the database itself can never end up with a line that has committed more than it requires.

### Changes Required:

#### 1. Migration

**File**: `src/main/resources/db/migration/V7__add_order_picking.sql`

**Intent**: Add the columns the picking feature needs, and replace the old `reserved_quantity <= required_quantity` check with one that also bounds `picked_quantity` and their sum, so the DB enforces the same invariant the allocator and picking controller rely on.

**Contract**:
```sql
ALTER TABLE orders ADD COLUMN taken_at TIMESTAMPTZ;

ALTER TABLE order_lines ADD COLUMN picked_quantity INT NOT NULL DEFAULT 0;
ALTER TABLE order_lines DROP CONSTRAINT order_lines_reserved_quantity_check;
ALTER TABLE order_lines ADD CONSTRAINT order_lines_quantities_check
	CHECK (picked_quantity >= 0 AND reserved_quantity >= 0 AND reserved_quantity + picked_quantity <= required_quantity);
```
`order_lines_reserved_quantity_check` is Postgres's default auto-generated name for V6's unnamed inline `CHECK` on `reserved_quantity`. If the `DROP CONSTRAINT` fails, confirm the real name first (`\d order_lines` or `SELECT conname FROM pg_constraint WHERE conrelid = 'order_lines'::regclass`) rather than guessing again.

#### 2. Order entity

**File**: `src/main/java/pl/regavio/stockahead/orders/Order.java`

**Intent**: Track the moment the order became "taken" (first pick of any line), `null` until then.

**Contract**: Add `@Column(name = "taken_at") private Instant takenAt;` with getter/setter, plus a convenience `public boolean isTaken() { return takenAt != null; }`.

#### 3. OrderLine entity

**File**: `src/main/java/pl/regavio/stockahead/orders/OrderLine.java`

**Intent**: Track how much of this line's reservation has actually left the warehouse so far.

**Contract**: Add `@Column(name = "picked_quantity", nullable = false) private int pickedQuantity = 0;` with getter/setter.

### Success Criteria:

#### Automated Verification:

- `./mvnw verify` passes (migration applies cleanly against Testcontainers Postgres; every existing test is unaffected since both new columns default to "untouched").

#### Manual Verification:

- `\d order_lines` / `\d orders` against a running dev DB shows the new columns and the replaced constraint.

---

## Phase 2: Allocator protection logic

### Overview

Change `ReservationAllocator` so a taken order's current reservation is excluded from every future recompute and protected from the stock pool, while non-taken orders keep competing exactly as before.

### Changes Required:

#### 1. ReservationAllocator

**File**: `src/main/java/pl/regavio/stockahead/orders/ReservationAllocator.java`

**Intent**: A taken order must never lose reservation it already has, even to a newly-created higher-priority order. Partition the lines touching the affected parts into taken (frozen) and reallocatable, remove the taken lines' current reservation from the stock pool up front, then run the existing priority-ordered algorithm only over the reallocatable lines.

**Contract**: `reallocateForParts(Set<Long> partIds)` changes its line-processing step:

```java
List<OrderLine> affectedLines = orderLineRepository.findOpenLinesForParts(partIds);
List<OrderLine> takenLines = affectedLines.stream().filter(line -> line.getOrder().isTaken()).toList();
List<OrderLine> reallocatableLines = affectedLines.stream().filter(line -> !line.getOrder().isTaken()).toList();

for (OrderLine takenLine : takenLines) {
	remainingStockByPartId.merge(takenLine.getPart().getId(), -takenLine.getReservedQuantity(), Integer::sum);
}
remainingStockByPartId.replaceAll((partId, remaining) -> Math.max(0, remaining));

// existing grouping-by-order / ALLOCATION_ORDER sort / assign loop now runs over reallocatableLines only
```
Taken lines are never written to (not even re-saved with the same value) — they are simply absent from the write set. The `Math.max(0, remaining)` floor is a defensive guard; it should never actually trigger if every other write path upholds `reservedQuantity ≤ stock`.

### Success Criteria:

#### Automated Verification:

- `./mvnw test -Dtest=ReservationAllocatorTests` passes, including new cases:
  - `takenOrdersReservationSurvivesANewHigherPriorityCompetitor` — a taken LOW-priority order's reservation is untouched when a new HIGH-priority order for the same scarce part is reallocated.
  - `takenOrderWithPartialPickKeepsItsRemainingReservationProtected` — a taken order with some already picked and some still reserved keeps both numbers unchanged when a new competing order is reallocated.
  - `nonTakenOrdersStillCompeteNormallyAmongThemselves` — with a taken order present and untouched, two non-taken orders still resolve priority/date/age ties exactly as the existing tests prove.
- `./mvnw verify` passes (full suite, including the untouched `ReservationConcurrencyTests` and `OrderCreationIntegrationTests`).

#### Manual Verification:

- None beyond the automated proof — this phase has no user-facing surface yet.

---

## Phase 3: Picking domain & controller

### Overview

Add the controller, view-model, and locking/validation logic that lets a technician or manager pick a quantity for one order line, decrementing stock and marking the order taken on its first successful pick.

### Changes Required:

#### 1. Shared lock-retry helper

**File**: `src/main/java/pl/regavio/stockahead/orders/LockRetry.java` (new)

**Intent**: `PickingController` needs the same "retry up to 3 times on lock contention" behavior `OrderController.executeWithLockRetry` already has. Extract it once instead of duplicating the loop a second time.

**Contract**: A small package-private component/utility exposing `<T> T executeWithLockRetry(Supplier<T> action)` with the same `MAX_LOCK_RETRY_ATTEMPTS = 3`, no-backoff, re-throw-last-failure behavior currently in `OrderController.java:171-182`. `OrderController` delegates to it instead of keeping its own copy; the existing `OrderControllerTests` keeps its name but its two existing cases retarget to call `LockRetry` directly (same two test cases, same assertions).

#### 2. PickingDetailModel

**File**: `src/main/java/pl/regavio/stockahead/orders/PickingDetailModel.java` (new `@Component`, mirrors `OrderDetailModel`/`ProjectDetailModel`)

**Intent**: Render the picking detail page's data from one place, reusable from the `GET` route and from a failed-pick `POST`'s re-render.

**Contract**: `static final String VIEW = "picking-detail";`
- `String render(Model model, Long orderId)` and an overload `String render(Model model, Long orderId, String error, Long errorLineId)` (mirrors `ProjectDetailModel.render(Model, Long, boolean, String)`).
- Reuses `OrderRepository.findByIdWithDetails` — no new query; per-line `part.getLocations()` lazy-loads (accepted N+1 at this shop's scale, same precedent as `lessons.md`'s JOIN FETCH note).
- `OrderView` record: `projectName, quantityUnits, priority, requiredDate, status, taken` (boolean, from `order.isTaken()`).
- `LineView` record: `lineId, partName, locations` (joined text, same as `parts-list.html:47`), `requiredQuantity, reservedQuantity, pickedQuantity, remainingToPick` (`reservedQuantity - pickedQuantity`).

#### 3. PickingController

**File**: `src/main/java/pl/regavio/stockahead/orders/PickingController.java` (new)

**Intent**: Expose the picking list, detail, and pick-one-line routes to both roles, following `OrderController`'s/`ProjectBomController`'s validate-then-lock-then-write shape.

**Contract**:
- `GET /picking` — `@PreAuthorize("hasAnyRole('MANAGER','TECHNICIAN')")`, same `orderRepository.findByStatusWithProject(OPEN)` + `.sorted(ReservationAllocator.ALLOCATION_ORDER)` as `OrderController.list`, view `picking-list`.
- `GET /picking/{id}` — same role check, delegates to `pickingDetailModel.render(model, id)`.
- `POST /picking/{orderId}/lines/{lineId}/pick` — same role check, `@RequestParam(required = false) String quantity`. Validate it parses to an integer ≥ 1 (new `picking.error.quantityNotInteger`/`quantityNotPositive` messages, same style as `OrderController.validateQuantityUnits`). On success, inside `executeWithLockRetry(() -> transactionTemplate.execute(...))`: lock the line's part via `partRepository.findByIdInForUpdate(Set.of(partId))`, re-fetch the order line fresh via a `findLine(orderId, lineId)` helper (mirrors `ProjectBomController.findLine`, 404 on unknown/mismatched ids), reject with `picking.error.orderNotOpen` if `order.getStatus() != OPEN`, reject with `picking.error.quantityExceedsAvailable` (parameterized with the max pickable amount) if the requested quantity exceeds `reservedQuantity - pickedQuantity`, otherwise decrement `Part.quantity`, shift the requested amount from `reservedQuantity` to `pickedQuantity`, and stamp `order.setTakenAt(Instant.now())` only if it was still `null`. Catch `DataIntegrityViolationException`/`PessimisticLockingFailureException` the same way `OrderController.create` does, mapping to `picking.error.saveFailed`.
- Any validation/business-rule failure re-renders via `pickingDetailModel.render(model, orderId, error, lineId)` (HTTP 200, submitted raw quantity preserved on that row); success is `redirect:/picking/{orderId}`.

#### 4. Messages

**File**: `src/main/resources/messages.properties`

**Intent**: Add the picking feature's error vocabulary, namespaced like every other feature.

**Contract**: `picking.error.quantityNotInteger`, `picking.error.quantityNotPositive`, `picking.error.quantityExceedsAvailable` (takes `{0}` = max pickable amount), `picking.error.orderNotOpen`, `picking.error.saveFailed`.

#### 5. Concurrency tests

**File**: `src/test/java/pl/regavio/stockahead/orders/PickingConcurrencyTests.java` (new, mirrors `ReservationConcurrencyTests`)

**Intent**: Prove the hard rule (AGENTS.md: never drop stock below zero, never double-reserve a unit) holds under real Postgres contention for picking specifically.

**Contract**:
- `twoConcurrentPicksOnTheSameLineNeverTogetherExceedReservedQuantity` — two threads race to pick from the same line, each requesting more than half of a small reserved amount; assert total `picked_quantity` for the line never exceeds the original `reserved_quantity`, and `parts.quantity` never goes negative.
- `pickRacingANewCompetingOrderNeverLetsTheCompetitorStealAnAlreadyTakenReservation` — one thread performs a pick (which marks its order taken) while another concurrently creates and reallocates a higher-priority competing order for the same part; assert the taken order's protected reservation survives and the competitor gets only what was genuinely left.
- `concurrentFirstPicksOnDifferentLinesOfTheSameOrderBothSucceedAndLeaveItTaken` — an order with two untaken lines for two different parts; two threads each pick one line concurrently (each thread locks a different `Part` row, so nothing serializes the two transactions' reads of `Order.takenAt`). Assert both picks succeed, both lines show the correct `reservedQuantity`/`pickedQuantity`, and the order ends up with `takenAt` non-null — the exact timestamp (whichever transaction's `UPDATE` committed last) is not asserted, since `Order` has no `@Version` column and this is the first place in the codebase where two transactions can concurrently `UPDATE` the same `orders` row.

### Success Criteria:

#### Automated Verification:

- `./mvnw test -Dtest=OrderControllerTests,PickingConcurrencyTests` passes — `OrderControllerTests` keeps its name but its two existing cases retarget to call `LockRetry` directly instead of `OrderController#executeWithLockRetry`.
- `./mvnw verify` passes (full suite).

#### Manual Verification:

- None yet — no template exists until Phase 4; this phase is verified entirely through tests and, if desired, manual `curl`/Postman calls against the new routes.

---

## Phase 4: UI & integration tests

### Overview

Add the picking-list and picking-detail templates, a dashboard link both roles can see, and full-stack integration tests covering the real user flows.

### Changes Required:

#### 1. Picking list template

**File**: `src/main/resources/templates/picking-list.html` (new)

**Intent**: List OPEN orders for both roles, same shape as `orders-list.html`.

**Contract**: Same columns as `orders-list.html:14-33` (project, quantity units, priority, required date, link to `/picking/{id}`); no inline `sec:authorize` needed since the route itself is open to both roles.

#### 2. Picking detail template

**File**: `src/main/resources/templates/picking-detail.html` (new)

**Intent**: Show the picking checklist with locations and a per-line pick form.

**Contract**: Header shows project/quantity/priority/required date, plus a "Zlecenie podjęte" indicator when `order.taken`. Table columns: part, locations (`#{strings.listJoin}`, same as `parts-list.html:47`), required, reserved, picked, remaining-to-pick. Each row with `remainingToPick > 0` and `order.status == 'OPEN'` has its own `<form method="post" th:action="@{/picking/{orderId}/lines/{lineId}/pick(...)}">` with a number input (prefilled to `remainingToPick`) and its own submit button. An error banner at the top shows `error` when present, with the failed row's attempted value preserved via `errorLineId`.

#### 3. Dashboard link

**File**: `src/main/resources/templates/dashboard.html`

**Intent**: Both roles can reach the picking screen from the dashboard.

**Contract**: Add `<p><a th:href="@{/picking}">Lista kompletacyjna</a></p>` unguarded (same pattern as the existing `/parts`/`/projects` links at `dashboard.html:12-13`), near the manager-only `/orders` link.

#### 4. List/detail integration tests

**File**: `src/test/java/pl/regavio/stockahead/orders/PickingListAndDetailIntegrationTests.java` (new, mirrors `OrderListAndDetailIntegrationTests`)

**Contract**: Both a manager session and a technician session get `200` on `GET /picking` and `GET /picking/{id}` (no 403 for either role); unknown order id is `404`; the detail page's HTML contains the part name, its locations, and its required/reserved/picked numbers.

#### 5. Pick-action integration tests

**File**: `src/test/java/pl/regavio/stockahead/orders/PickingIntegrationTests.java` (new, mirrors `OrderCreationIntegrationTests`)

**Contract**:
- A valid partial pick decrements `parts.quantity`, shifts `reserved_quantity` → `picked_quantity` by the requested amount, sets `orders.taken_at`, and redirects to `/picking/{orderId}`.
- An overpick request (quantity greater than currently reserved-but-unpicked) is rejected with `picking.error.quantityExceedsAvailable` and leaves `parts.quantity`/`order_lines` rows unchanged.
- A pick on a `CANCELLED`/`COMPLETED` order is rejected with `picking.error.orderNotOpen` and leaves DB state unchanged.
- A second, later partial pick on an already-taken order succeeds and does not overwrite the original `taken_at` timestamp.

### Success Criteria:

#### Automated Verification:

- `./mvnw verify` passes (full suite, including every test from Phases 1-4).

#### Manual Verification:

- As a technician, open `/picking`, open an order, pick a partial quantity, and confirm the row updates (reserved ↓, picked ↑, remaining-to-pick ↓) and the physical stock shown on `/parts` for that part dropped by the same amount.
- Confirm the order now shows the "taken" indicator on `/picking/{id}`, and that creating a new higher-priority order for the same part (as manager, via `/orders/new`) does not shrink the taken order's reservation — visible as the unchanged `reservedQuantity` number on `/orders/{id}` (which has no taken badge, only `/picking/{id}` does) or `/picking/{id}`.
- Confirm a manager can also pick from `/picking` (role parity), and that attempting to pick more than the remaining-to-pick amount shows the friendly error instead of a 500.

---

## Testing Strategy

### Unit Tests:

- `ReservationAllocatorTests` — taken-order exclusion/protection (Phase 2).
- `OrderControllerTests`/relocated lock-retry tests — retry-count and re-throw behavior (Phase 3).

### Integration Tests:

- `PickingConcurrencyTests` — real-Postgres contention proof for double-pick and pick-vs-reallocation races (Phase 3).
- `PickingListAndDetailIntegrationTests`, `PickingIntegrationTests` — full HTTP-level flows including role access, validation, and the taken-order lifecycle (Phase 4).

### Manual Testing Steps:

1. Pick a partial quantity as a technician; confirm stock, reserved, and picked numbers all move correctly.
2. Attempt to overpick; confirm a friendly error, not a 500 or silent clamp.
3. Confirm a taken order survives a newly created higher-priority competitor for the same part.

## Performance Considerations

None beyond the existing per-part `PESSIMISTIC_WRITE` locking already used by `ReservationAllocator`/`OrderController` — this shop's `target_scale` (small, low qps) doesn't warrant anything further, and `lessons.md` already accepts per-line-location N+1 queries at this scale.

## Migration Notes

No data backfill: `taken_at` defaults to `NULL` (no existing order has been picked) and `picked_quantity` defaults to `0` for all existing rows, which is the historically accurate value since no picking mechanism existed before this change.

## References

- `src/main/java/pl/regavio/stockahead/orders/ReservationAllocator.java:58-82` — recompute algorithm this plan extends.
- `src/main/java/pl/regavio/stockahead/orders/OrderController.java:93-182` — lock-retry/validate/transactional-write pattern this plan reuses.
- `src/main/java/pl/regavio/stockahead/projects/ProjectBomController.java`, `ProjectDetailModel.java` — per-row POST action + detail-render-with-error pattern this plan mirrors for picking.
- `context/foundation/prd.md` §Business Logic, §Access Control, FR-012, FR-013, FR-021 — the business rules this plan implements.
- `context/foundation/lessons.md` — DB-constraint error handling, wrong-role testing, JOIN FETCH threshold, and the narrowed-locking deadlock-retry warning this plan follows.

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles. See `references/progress-format.md`.

### Phase 1: Schema & entities

#### Automated

- [ ] 1.1 `./mvnw verify` passes with the new migration and entity fields

#### Manual

- [ ] 1.2 `\d order_lines` / `\d orders` shows the new columns and replaced constraint

### Phase 2: Allocator protection logic

#### Automated

- [ ] 2.1 `takenOrdersReservationSurvivesANewHigherPriorityCompetitor` passes
- [ ] 2.2 `takenOrderWithPartialPickKeepsItsRemainingReservationProtected` passes
- [ ] 2.3 `nonTakenOrdersStillCompeteNormallyAmongThemselves` passes
- [ ] 2.4 `./mvnw verify` passes (full suite)

### Phase 3: Picking domain & controller

#### Automated

- [ ] 3.1 Lock-retry tests pass against the extracted shared helper
- [ ] 3.2 `twoConcurrentPicksOnTheSameLineNeverTogetherExceedReservedQuantity` passes
- [ ] 3.3 `pickRacingANewCompetingOrderNeverLetsTheCompetitorStealAnAlreadyTakenReservation` passes
- [ ] 3.4 `concurrentFirstPicksOnDifferentLinesOfTheSameOrderBothSucceedAndLeaveItTaken` passes
- [ ] 3.5 `./mvnw verify` passes (full suite)

### Phase 4: UI & integration tests

#### Automated

- [ ] 4.1 `PickingListAndDetailIntegrationTests` passes (role access, 404, rendered content)
- [ ] 4.2 `PickingIntegrationTests` passes (valid pick, overpick rejection, non-OPEN rejection, repeat pick on taken order)
- [ ] 4.3 `./mvnw verify` passes (full suite)

#### Manual

- [ ] 4.4 Manual technician pick flow confirmed (stock/reserved/picked numbers move correctly)
- [ ] 4.5 Manual overpick error confirmed (friendly error, not 500)
- [ ] 4.6 Manual taken-order-survives-new-competitor flow confirmed
- [ ] 4.7 Manual manager-can-also-pick role-parity confirmed
