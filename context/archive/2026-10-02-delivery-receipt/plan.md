# Delivery Receipt (S-10) Implementation Plan

## Overview

A technician or manager records a delivery. A receipt has several lines, and each line carries a part, a quantity, and optionally the location where the units were shelved. The receipt raises each part's stock atomically under the part-row locks. Reservations are then recomputed in the same transaction, so the new units fill shortages in allocation order (priority → required date → older order), taken orders get topped up, and the shopping list shrinks without anyone refreshing it. This covers roadmap S-10 / FR-004.

## Current State Analysis

- `parts.quantity` is only changed by order picks (down) and cancel returns (up). No screen raises stock for a new delivery. The parts-catalog plan deliberately left this to S-10 (`context/archive/2026-09-28-parts-catalog/plan.md:30`).
- `ReservationAllocator.reallocateForParts(Set<Long>)` (`src/main/java/pl/regavio/stockahead/orders/ReservationAllocator.java:91`) already does the full recompute for the given parts: it locks them, seeds the pool, protects taken orders, tops up unreported taken orders, and rebuilds non-taken lines. Its Javadoc explicitly requires every stock-raising write, delivery included, to call it under the part-row lock. The method is package-private.
- `LockRetry` (`src/main/java/pl/regavio/stockahead/orders/LockRetry.java:17`) is the shared retry-on-`PessimisticLockingFailureException` wrapper. Class and method are both package-private.
- The closest existing write is cancel's return-to-stock (`src/main/java/pl/regavio/stockahead/orders/OrderController.java:259-330`): lock the parts, `part.setQuantity(+n)`, `saveAndFlush`, then `reallocateForParts`.
- `/purchasing` (`purchasing/ShoppingListModel.java`) and the reserved/available columns on `/parts` (`parts/PartController.java:68-81`) read live from `order_lines`. Neither needs a cache invalidation.
- `parts.quantity` has `CHECK (quantity >= 0)` and `part_locations` has `UNIQUE (part_id, location)` (`src/main/resources/db/migration/V3__create_parts_catalog.sql`). No migration is needed.
- `spring.jpa.open-in-view` is at its default (enabled). A `Part` loaded before the lock would be served stale from the request's persistence context, which is the trap documented at length in `PickingController`'s Javadoc.
- The app has no JavaScript. Every form is server-rendered Thymeleaf (`project-detail.html:65` is the existing part `<select>` pattern).

## Desired End State

- `/deliveries/new` is open to MANAGER and TECHNICIAN. It shows a receipt form with 5 blank rows, each with a part dropdown (every part; inactive ones are labelled), a quantity, and an optional location. A "Dodaj wiersze" button re-renders the form with 5 more rows and keeps everything already typed.
- Submitting a valid receipt does all of the following in one transaction:
  - adds each part's summed quantity to `parts.quantity`
  - adds each given location the part doesn't already have
  - recomputes reservations for exactly those parts
  - redirects to `/parts` with a one-line success summary.
- Any invalid row rejects the whole receipt. Nothing is written, and the form re-renders with an error and all inputs kept.
- The dashboard and `/parts` link to the receipt form.
- Verification: `./mvnw verify` is green, including new integration and concurrency tests. A manual run shows a delivery fills an order's shortage and shrinks `/purchasing`.

### Key Discoveries:

- Allocator contract: "every write that raises a part's stock (delivery, correction, import) must call `reallocateForParts` for that part under its row lock" (`orders/ReservationAllocator.java:44-48`).
- The allocator must run after the stock change is flushed. Otherwise `findCurrentQuantities` reads the old stock (`OrderController.java:252-258`, `:322-328`).
- Locking the ids from the form and then reading the entities from `findByIdInForUpdate` is safe only if no `Part` was hydrated earlier in the same request (`PickingController.java:34-52`, `PartRepository.java:31-44`).
- The lessons that apply here:
  - catch `DataIntegrityViolationException` at the write boundary and re-render the form
  - put an explicit upper bound on user quantities instead of relying on the DB CHECK
  - mutate the same row at least twice in tests
  - add inline `td, th { text-align: center; }` to every template that has a table (`context/foundation/lessons.md`).

## What We're NOT Doing

- No delivery record, deliveries table or movement history. FR-006 is a PRD Non-Goal for MVP, and the stock increment is the only persisted effect. Who received what is not traceable until FR-006 is built.
- No JavaScript add-row. Rows are added by a server round-trip.
- No removing or renaming locations from a receipt. A receipt can only add a location the part is missing.
- No per-location stock. Stock stays a single total, per the PRD Non-Goals.
- No supplier, price or purchase-order link. A receipt doesn't "close" shopping-list rows; the list is simply recomputed from live shortages (FR-004 Socrates resolution).
- No negative or corrective quantities. Downward changes belong to S-11 stock-correction.
- No narrowing of the allocator's lock scope.
- No fix for the pre-existing lost-update race between a receipt and `PartController` edit/deactivate/reactivate. Those handlers write `Part` without a row lock or `@Version`, so they can overwrite delivered stock. This is queued as a separate change in `follow-ups/review-fixes.md` F1 (found by impl-review).

## Implementation Approach

Follow the cancel/pick shape: validate first without touching entities, then do a single `lockRetry.executeWithLockRetry(transactionTemplate.execute(...))` that locks the part rows, mutates them, flushes, and calls the allocator. Rows naming the same part are merged into one entry before locking: quantities are summed and locations become a set. Each part is then locked and incremented exactly once. The controller lives in the `parts` package as `DeliveryController`, so `ReservationAllocator.reallocateForParts` and `LockRetry` become public. The `parts` → `orders` dependency already exists through `PartController`'s use of `OrderLineRepository`.

Agreed product decisions:

| Decision | Choice |
|---|---|
| Form shape | Multi-line receipt; one submission can cover many parts |
| Record keeping | Stock only; no deliveries table |
| Inactive parts | Allowed. Inactive parts appear in the dropdown for both roles, labelled "(nieaktywna)" |
| Location | Optional per row; added to the part if missing, existing ones untouched |
| Rows | 5 blank rows; "Dodaj wiersze" adds 5 more and keeps values; blank rows ignored |
| Same part on several rows | Quantities summed; each row's location added if missing |

## Critical Implementation Details

**State sequencing.** Inside the transaction the order is: `findByIdInForUpdate(partIds)`, then check that every requested id was returned, then set quantities and add locations on those locked entities, then `partRepository.saveAndFlush`/`flush()`, then `reservationAllocator.reallocateForParts(partIds)`. If you call the allocator before the flush, it seeds its pool with the old stock and the delivered units reach no order.

**Timing & lifecycle (open-in-view).** On the POST path, do not load any `Part` entity (for example to build the dropdown or check that a part is active) before acquiring the lock. Validation must work only on the raw form strings. The dropdown is loaded only on the GET path and on error re-renders, which never go on to write anything.

## Phase 1: Receipt form and row validation

### Overview

Add the receipt screen and its validation. The screen supports adding rows, keeps values across re-renders, and rejects every invalid shape with a localized error. In this phase a valid submission does not write anything yet. Links to the screen are added in Phase 2, so a half-built screen isn't exposed.

### Changes Required:

#### 1. Delivery controller (form + validation)

**File**: `src/main/java/pl/regavio/stockahead/parts/DeliveryController.java` (new)

**Intent**: Render the receipt form and turn the posted rows into a validated, merged receipt, or produce a localized error that re-renders the form with every row's raw input.

**Contract**:
- `GET /deliveries/new` and `POST /deliveries`, both `@PreAuthorize("hasAnyRole('MANAGER','TECHNICIAN')")`.
- Row fields are `partId<i>`, `quantity<i>`, `location<i>` for `i = 0..rows-1`, plus a hidden `rows` count. A default `rows` of 5 is used when it is missing or invalid, and it is clamped to at most 100.
- A POST with `action=addRows` re-renders with `rows + 5` and kept values. It runs no validation.
- Any other POST validates in this order, stopping at the first error:
  - A row with all three fields blank is ignored.
  - A row with any field filled must have both a part and a quantity. If not, the error is `deliveries.error.rowIncomplete` with the 1-based row number.
  - `partId` must parse as a `Long`.
  - Quantity must be an integer from 1 to 1 000 000. The errors are `deliveries.error.quantityNotInteger`, `quantityNotPositive` and `quantityTooLarge`, each with the row number.
  - The location is normalized with `PartLocation.normalize` and must be at most 255 characters (`deliveries.error.locationTooLong`). Normalizing turns non-breaking spaces (U+00A0/U+2007/U+202F) into plain spaces and then strips the ends. `trim()`/`strip()` alone would keep a trailing non-breaking space pasted from a spreadsheet, so "A1" would become a second shelf. *Addendum (plan-review F3):* `PartController.parseLocations` uses the same normalizer. `sameLocation` compares normalized values, so legacy rows that hold such spaces also match. Covered by `DeliveryIntegrationTests.locationsPastedWithNonBreakingSpacesMatchTypedOnes` and two `PartsCatalogIntegrationTests` cases.
  - At least one non-blank row is required (`deliveries.error.empty`).
- Valid rows are merged per `partId` into a summed quantity and a set of locations, ready for Phase 2's write.
- The dropdown source is every part ordered by name (`partRepository.search("", true)`). It is loaded only on GET and on error/addRows re-renders.
- *Addendum (impl-review F3):* the implementation also adds location autocomplete. `PartRepository.findAllLocationNames()` is a scalar query that loads no `Part`, and it feeds a `<datalist id="known-locations">`. It is loaded at the same points as the dropdown. A partId that does not parse uses the key `deliveries.error.partInvalid`.

#### 2. Receipt template

**File**: `src/main/resources/templates/deliveries-new.html` (new)

**Intent**: A table of N rows: a part `<select>` (with an empty option, the part name, and a "(nieaktywna)" suffix for inactive parts), a quantity input, and a location input. Each re-rendered value is pre-selected or pre-filled. The form has the hidden `rows`, a "Dodaj wiersze" submit (`name=action value=addRows`), a "Przyjmij dostawę" submit, an error paragraph, and a link back to the dashboard.

**Contract**: Inline `<style>td, th { text-align: center; }</style>` in `<head>`, per the lessons. The CSRF token comes from Thymeleaf's `th:action`.

#### 3. Messages

**File**: `src/main/resources/messages.properties`

**Intent**: Polish texts for every `deliveries.error.*` key above. Row-scoped messages take the row number as `{0}`.

**Contract**: The new keys live under the `deliveries.` prefix.

#### 4. Tests

**File**: `src/test/java/pl/regavio/stockahead/parts/DeliveryIntegrationTests.java` (new)

**Intent**: MockMvc + Testcontainers tests:
- GET renders 5 rows for both TECHNICIAN and MANAGER, and the dropdown lists an inactive part with its label.
- `addRows` renders 10 rows with the typed values kept.
- Each validation error re-renders with its message and keeps the inputs.
- An anonymous GET/POST redirects to login.
- Every rejected POST leaves `parts.quantity` and `part_locations` unchanged.

**Contract**: Follow `@Import(TestcontainersConfiguration.class)` + `@SpringBootTest` + `@AutoConfigureMockMvc`, as in `PickingIntegrationTests`.

### Success Criteria:

#### Automated Verification:

- `./mvnw verify` passes
- `DeliveryIntegrationTests` covers the 5-row GET for both roles, `addRows` value retention, every validation error with inputs kept, anonymous redirect, and no DB change on rejection

#### Manual Verification:

- `/deliveries/new` (typed URL) renders the form; "Dodaj wiersze" adds rows without losing typed values; an invalid row shows a readable Polish error

**Implementation Note**: After automated verification passes, pause for manual confirmation before Phase 2.

---

## Phase 2: Atomic receipt with reallocation

### Overview

Wire up the valid-receipt path. Under the part locks it raises stock, adds missing locations, flushes, and reallocates. It then redirects to `/parts` with a success summary. Links to the form are added from the dashboard and the parts list.

### Changes Required:

#### 1. Expose the allocator and lock retry

**File**: `src/main/java/pl/regavio/stockahead/orders/ReservationAllocator.java`, `src/main/java/pl/regavio/stockahead/orders/LockRetry.java`

**Intent**: Let `parts.DeliveryController` call them. No behavior change.

**Contract**: `public void reallocateForParts(Set<Long>)`; `public class LockRetry` with `public <T> T executeWithLockRetry(Supplier<T>)`. Update the `LockRetry` Javadoc to mention the delivery caller.

#### 2. Receipt write path

**File**: `src/main/java/pl/regavio/stockahead/parts/DeliveryController.java`

**Intent**: Commit a merged receipt atomically, following the order set out in Critical Implementation Details.

**Contract**:
- Inside `lockRetry.executeWithLockRetry(() -> transactionTemplate.execute(...))`:
  - Lock the merged part ids via `findByIdInForUpdate`.
  - If an id is missing, return `deliveries.error.partNotFound`.
  - Compute the new quantity with `Math.addExact`. On overflow, return `deliveries.error.stockOverflow` with the part name.
  - Set the quantity on each locked part.
  - For each location in the row set, add a `PartLocation` if the part doesn't already have one that matches under `PartLocation.sameLocation`. The match ignores case, and `PartController.parseLocations` uses the same rule.
  - *Addendum (impl-review F6):* the plan originally called for exact, case-sensitive matching. The implementation switched both controllers to the case-insensitive `PartLocation.sameLocation`. Rows of one part that differ only in case are merged, and the first spelling wins. The DB `UNIQUE (part_id, location)` stays case-sensitive as a backstop, so a race against a part edit that uses a different case is not caught by `saveFailed`. Covered by `DeliveryIntegrationTests.locationsAreMatchedIgnoringCase`.
  - Flush, then call `reservationAllocator.reallocateForParts(partIds)`.
- Inactive parts are accepted with no active check.
- If the transaction throws `DataIntegrityViolationException` or `PessimisticLockingFailureException`, re-render with `deliveries.error.saveFailed`. This covers, for example, a concurrent manager edit that inserts the exact same location string first. It does not protect stock from a concurrent part edit (see What We're NOT Doing).
- On success, redirect to `/parts` with a flash attribute `deliveryReceived`, rendered as "Przyjęto dostawę: {n} poz., {m} szt." via the message key `deliveries.received`.

#### 3. Entry points and flash display

**File**: `src/main/resources/templates/dashboard.html`, `src/main/resources/templates/parts-list.html`, `src/main/resources/messages.properties`

**Intent**: Add a "Przyjmij dostawę" link on the dashboard and on `/parts`, visible to both roles. `parts-list.html` shows the `deliveryReceived` flash message when it is present.

**Contract**: The link target is `@{/deliveries/new}`. There is no `sec:authorize`, since both roles may receive.

#### 4. Tests

**File**: `src/test/java/pl/regavio/stockahead/parts/DeliveryIntegrationTests.java`

**Intent**: Prove the business rule end to end through the real endpoint. Create orders and reach "taken" through the real order/pick endpoints or helpers, as the sibling tests do. The test cases:
- **US-01 shape.** A part has stock 6 and an order needs 10. A delivery of 4 makes the order fully reserved, and the part drops off `/purchasing`.
- **Allocation order.** Two non-taken orders (HIGH and LOW) are both short. A delivery that covers only part of the shortage fills the HIGH order first.
- **Taken top-up.** A taken, unreported order that is short receives the delivered units. A completion-reported taken order receives nothing.
- **Two receipts on the same part (lessons).** Two successive deliveries both land, and the second one's reallocation sees the first one's stock.
- **Duplicates summed.** Two rows of the same part (50 + 30) raise stock by 80, and their two distinct locations are both added.
- **Location handling.** An existing location is not duplicated, and a part without a location given keeps its locations unchanged.
- **Inactive part.** A delivery to an inactive part is accepted by a TECHNICIAN.
- **Overflow.** A delivery that would overflow `int` is rejected and nothing is written.
- **Unknown part.** An unknown `partId` is rejected and nothing is written, including the other rows of the same receipt.
- **Redirect.** A successful receipt redirects to `/parts` with the summary.

**Contract**: Assertions read `parts.quantity`, `part_locations` and `order_lines.reserved_quantity` via `JdbcTemplate`.

### Success Criteria:

#### Automated Verification:

- `./mvnw verify` passes
- `DeliveryIntegrationTests` covers: US-01 fill, priority-ordered fill, taken top-up vs reported-taken exclusion, two successive receipts on one part, summed duplicate rows with both locations added, existing location not duplicated, inactive part accepted, overflow rejected atomically, unknown part rejected atomically, redirect + summary

#### Manual Verification:

- As a technician: receive a delivery covering an order's shortage from the dashboard link; `/parts` shows the summary and updated stock/reserved/available; as manager `/purchasing` no longer lists (or lists a smaller) shortage for that part
- A row with a new location shows that location on `/parts` afterwards

**Implementation Note**: After automated verification passes, pause for manual confirmation before Phase 3.

---

## Phase 3: Concurrency guarantees

### Overview

Prove with real concurrent requests on Postgres that a receipt can't lose an update and can't break the stock/reservation invariants when it runs alongside picks and order creation.

### Changes Required:

#### 1. Concurrency tests

**File**: `src/test/java/pl/regavio/stockahead/parts/DeliveryConcurrencyTests.java` (new)

**Intent**: Follow the `OrderCancelConcurrencyTests` / `PickingConcurrencyTests` shape: an `ExecutorService` with a `CountDownLatch` start gate, separate logged-in `MockHttpSession`s, and throwaway `pg_sleep` triggers to widen the windows where needed. The scenarios:
- **No lost update.** Two receipts of +10 on the same part run concurrently, and final stock is the start value + 20.
- **Receipt vs pick.** A receipt races a pick on the same part of a taken order. Afterwards stock ≥ 0, the sum of reserved per part ≤ stock, and `picked + reserved ≤ required` per line.
- **Receipt vs order creation.** A receipt races the creation of a new order on the same part. The same per-part invariant holds, and the new order's reservation equals what the allocation order grants given both commits.

**Contract**: Invariant assertions are written as SQL over `parts` / `order_lines`. Any triggers are dropped in `@AfterEach`.

### Success Criteria:

#### Automated Verification:

- `./mvnw verify` passes
- `DeliveryConcurrencyTests` proves no lost update across two concurrent receipts on one part
- `DeliveryConcurrencyTests` proves receipt-vs-pick and receipt-vs-order-creation keep stock ≥ 0 and reserved ≤ stock per part

---

## Testing Strategy

### Unit Tests:

- None beyond integration. Row parsing is tested through the endpoint, so the re-render behavior is covered at the same time.

### Integration Tests:

- `DeliveryIntegrationTests` (Phases 1–2) and `DeliveryConcurrencyTests` (Phase 3), all against real Postgres via Testcontainers. No H2 or mocked DB, per AGENTS.md.
- The existing `ReservationAllocatorTests`, `OrderCancel*` and `Picking*` suites must stay green after the visibility change.

### Manual Testing Steps:

1. `./mvnw spring-boot:test-run`. Create parts, a project with a BOM, and an order that is short on one part.
2. As a technician, open "Przyjmij dostawę" from the dashboard. Add rows, enter two rows for the short part (one with a new location), and submit.
3. Check `/parts` for the summary, the new stock, the reservation and the new location. As the manager, check that `/purchasing` shrank.
4. Submit a receipt with one invalid row. Check that the error shows, the inputs are kept, and the stock is unchanged.

## Performance Considerations

A receipt locks only the parts it names, then the allocator re-reads the open lines for those parts. That cost scales with the receipt size and the open orders on those parts, which is fine at the PRD's small scale. The dropdown lists every part. That's acceptable for a small plant's catalog, and a search-based picker can come later if the catalog grows.

## Migration Notes

No schema change. The existing `CHECK (quantity >= 0)` and `UNIQUE (part_id, location)` constraints cover the write.

## References

- Roadmap item: `context/foundation/roadmap.md` S-10
- PRD: FR-004, § Business Logic, § Non-Goals (FR-006)
- Similar implementation: `src/main/java/pl/regavio/stockahead/orders/OrderController.java:259-330` (cancel return-to-stock + reallocate)
- Allocator contract: `src/main/java/pl/regavio/stockahead/orders/ReservationAllocator.java:20-48`
- Open-in-view lock trap: `src/main/java/pl/regavio/stockahead/orders/PickingController.java:34-52`
- Concurrency test pattern: `src/test/java/pl/regavio/stockahead/orders/OrderCancelConcurrencyTests.java`
- Lessons: `context/foundation/lessons.md`

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles. See `references/progress-format.md`.

### Phase 1: Receipt form and row validation

#### Automated

- [x] 1.1 `./mvnw verify` passes — 707d6f5
- [x] 1.2 `DeliveryIntegrationTests` covers the 5-row GET for both roles, `addRows` value retention, every validation error with inputs kept, anonymous redirect, and no DB change on rejection — 707d6f5

#### Manual

- [x] 1.3 `/deliveries/new` (typed URL) renders the form; "Dodaj wiersze" adds rows without losing typed values; an invalid row shows a readable Polish error — 707d6f5

### Phase 2: Atomic receipt with reallocation

#### Automated

- [x] 2.1 `./mvnw verify` passes — 7cf4cab
- [x] 2.2 `DeliveryIntegrationTests` covers: US-01 fill, priority-ordered fill, taken top-up vs reported-taken exclusion, two successive receipts on one part, summed duplicate rows with both locations added, existing location not duplicated, inactive part accepted, overflow rejected atomically, unknown part rejected atomically, redirect + summary — 7cf4cab

#### Manual

- [x] 2.3 As a technician: receive a delivery covering an order's shortage from the dashboard link; `/parts` shows the summary and updated stock/reserved/available; as manager `/purchasing` no longer lists (or lists a smaller) shortage for that part — 7cf4cab
- [x] 2.4 A row with a new location shows that location on `/parts` afterwards — 7cf4cab

### Phase 3: Concurrency guarantees

#### Automated

- [x] 3.1 `./mvnw verify` passes — f5944a4
- [x] 3.2 `DeliveryConcurrencyTests` proves no lost update across two concurrent receipts on one part — f5944a4
- [x] 3.3 `DeliveryConcurrencyTests` proves receipt-vs-pick and receipt-vs-order-creation keep stock ≥ 0 and reserved ≤ stock per part — f5944a4
