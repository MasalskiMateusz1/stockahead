# Stock Correction (S-11) Implementation Plan

## Overview

A manager corrects one part's stock after an inventory count and must give a reason (FR-018). The correction screen offers two separate forms: **"Ustaw stan"**, which sets a counted total and is rejected if stock changed since the form was opened, and **"Zmień stan o"**, which applies a signed change to the current stock and can never go below 0. Each correction is stored with its reason in a new `stock_corrections` table and listed on the part's correction page. Reservations and the shopping list then recompute (PRD § Business Logic).

Correction is the only event that can push stock below what taken orders still hold unpicked. For that case `ReservationAllocator` gains a deficit pass that shrinks taken orders' unpicked reservations in reverse allocation order until reserved ≤ stock. Losing physical units is not the "przejęcie" (takeover) the PRD forbids, and AGENTS.md and the PRD are updated to say so.

## Current State Analysis

- Stock (`parts.quantity`) is the physical count in the warehouse. Picks decrement it (`orders/PickingController.java`), deliveries raise it (`parts/DeliveryController.java:131-188`), and cancel returns picked units. `CHECK (quantity >= 0)` guards it (`V3__create_parts_catalog.sql:4`).
- `parts-edit.html` shows stock read-only with the note "Stan zmienia się przez dostawę lub korektę, nie tutaj". No correction path exists yet.
- `ReservationAllocator.reallocateForParts` (`orders/ReservationAllocator.java:84-133`) seeds a per-part pool from stock and subtracts every taken line's current reservation. It then **clamps the pool to 0** (`:97-100`) and never shrinks a taken reservation. Today that is safe because nothing lowers stock below the taken reservations: a pick shifts reserved → picked. A downward correction breaks it: stock 10, taken order A holds 8 unpicked, count finds 5 → reserved 8 > stock 5. No DB CHECK spans `parts` and `order_lines`, so nothing catches it.
- Picks validate the requested quantity against the line's locked `reservedQuantity` (`PickingController.java:141-147`), so a shrunk reservation is enforced on the next pick automatically.
- Movement history (FR-006) is a PRD Non-Goal, and nothing records who changed stock or why.

## Desired End State

- `/parts` shows a manager-only **"Koryguj stan"** link per row, for active and inactive parts.
- `GET /parts/{id}/correction` (manager) shows the part name, current stock, reserved and available, and two forms, each with a required reason field:
  - **Ustaw stan po spisie**: the counted total (≥ 0), with the shown stock in a hidden `expectedQuantity`.
  - **Zmień stan o**: a non-zero signed integer.
  
  Below the forms is a table of the part's past corrections, newest first: time, author e-mail, before → after, change, reason.
- A valid submit commits atomically: lock the part, set stock, insert the `stock_corrections` row, flush, reallocate. The manager is redirected to `/parts` with a flash such as `Skorygowano stan części „Rezystor 10k”: 12 → 5.`
- After a downward correction, non-taken orders lose reservations first (normal recompute). If stock is still below the taken orders' unpicked reservations, those shrink: first the leftover unpicked reservations of completion-reported orders (they can no longer be picked), then in reverse allocation order: lowest priority, then latest date, then newest order loses first. The missing units reappear as shortages on `/purchasing` and the order detail.
- Rejections re-render the correction page with the inputs kept and nothing written: stale total, delta below zero, empty or too-long reason, non-integer, out-of-range, no-op.
- `stock ≥ 0` and `Σ reserved ≤ stock` hold for every part under concurrent picks, deliveries and order creation.

### Key Discoveries:

- Write-path pattern to copy: `DeliveryController.receive/applyReceipt` (`parts/DeliveryController.java:100-188`). It wraps the work in `LockRetry` + `TransactionTemplate`, validates raw strings before any `Part` is loaded (open-in-view stale-entity trap), runs `findByIdInForUpdate`, mutates, **flushes**, then calls `reallocateForParts`.
- The allocator's class Javadoc (`ReservationAllocator.java:33-41`) already says "every write that raises a part's stock (delivery, correction, import) must call `reallocateForParts`". Correction must call it for decreases too.
- The author lookup pattern is `accountRepository.findByCanonicalEmail(Emails.canonical(authentication.getName()))` (`PickingController.java:215`).
- `MessagesBundleTests` pins every key in `messages.properties` to its exact text, so new keys must be added there.
- Existing allocator tests (`ReservationAllocatorTests`) use raw-SQL fixtures against real Postgres. `takenOrderReservationIsNeverReducedByTopUpPass` must keep passing, since the deficit pass only fires when stock < taken reservations.
- Lessons that apply: a 403 test for the technician on every route; an explicit upper bound plus `Math.addExact` instead of relying on the DB CHECK; centered `td, th` style for the new table; `JOIN FETCH` for the author in the corrections list.

## What We're NOT Doing

- No general movement history (FR-006). Only corrections are recorded, and picks and deliveries still leave no trace.
- No multi-row inventory sheet. Correction covers one part per submit.
- No predefined reason categories. The reason is free text.
- No editing or deleting past corrections, and no undo button. A wrong correction is fixed with another correction.
- No technician access. Correction is manager-only per PRD § Access Control.
- No location changes from the correction screen.
- No narrowing of the allocator's lock scope (see lessons.md).
- No notification to the technician whose taken order lost reservation. They see it on the picking list.

## Implementation Approach

Prove the risky rule first. Phase 1 changes only the allocator and the docs that state the rule, and tests the deficit pass in isolation. Phase 2 adds the schema and the read-only screen. Phase 3 adds the two write paths on top of the delivery pattern. Phase 4 proves the concurrency guarantees against real Postgres.

## Critical Implementation Details

- **Deficit pass placement:** the deficit is per part, computed as `stock − Σ taken reservedQuantity` (completion-reported orders count as taken). It must be resolved **before** the clamp at `ReservationAllocator.java:100`. Walk that part's taken lines completion-reported first, then the rest, each group in reverse `ALLOCATION_ORDER`, and lower each `reservedQuantity` by `min(deficit, reservedQuantity)` until the deficit is 0. The non-taken allocation then runs on a pool of 0. Never touch `pickedQuantity`.
- **Open-in-view:** the POST handlers must not load the `Part` entity before `findByIdInForUpdate`, same as `DeliveryController`. Load the part for re-rendering only on error paths, after the transaction.
- **Stale check is under the lock:** compare `expectedQuantity` with the locked part's quantity inside the transaction, not before it.

## Phase 1: Allocator deficit pass and rule docs

### Overview

Make `reallocateForParts` restore `Σ reserved ≤ stock` when stock has fallen below the taken orders' unpicked reservations, and record the exception to the protection rule in the docs that state it.

### Changes Required:

#### 1. Allocator

**File**: `src/main/java/pl/regavio/stockahead/orders/ReservationAllocator.java`

**Intent**: Before the taken reservations are subtracted and the pool is clamped, detect each part whose stock is below its taken lines' total `reservedQuantity`. Shrink those lines, completion-reported orders first, then in reverse allocation order, until the totals match. Update the class and method Javadoc: a taken reservation is protected from takeover by other orders, but shrinks when the physical units are gone.

**Contract**: `reallocateForParts(Set<Long>)` keeps its signature. New post-condition: for every part in `partIds`, `Σ reservedQuantity over OPEN lines ≤ parts.quantity`. Taken lines are reduced only when `stock < Σ taken reserved`, and only by the deficit. Order of reduction: completion-reported orders first (their unpicked reservation can no longer be picked; confirm would release it anyway), then the rest; within each group `ALLOCATION_ORDER.reversed()` over the orders owning those lines.

#### 2. Allocator tests

**File**: `src/test/java/pl/regavio/stockahead/orders/ReservationAllocatorTests.java`

**Intent**: Cover the deficit pass with raw-SQL fixtures in the file's existing style.

**Contract**: New tests:
- `stockBelowSingleTakenReservationShrinksItToStock`: stock 10 → 5, taken A holds 8 → A reserved 5.
- `deficitShrinksLowestPriorityTakenOrderFirst`: two taken orders, the deficit comes out of the later one in allocation order first.
- `nonTakenReservationsGoBeforeAnyTakenOneShrinks`: a taken order keeps its reservation while the deficit can be covered by freeing non-taken ones.
- `reportedTakenOrderAlsoShrinksOnDeficit`.
- `reportedTakenOrderShrinksBeforeActiveTakenOrder`: a higher-priority reported order loses its unpicked reservation before a lower-priority taken order still being picked.
- `deficitNeverTouchesPickedQuantity`.

#### 3. Rule docs

**Files**: `AGENTS.md`, `context/foundation/prd.md`

**Intent**: State the exception so future work doesn't read the shrink as a violation.

**Contract**: AGENTS.md § Hard rules, second bullet: append "— except when stock drops below those reservations (stock correction); then taken reservations shrink — completion-reported orders first, then in reverse allocation order — never into picked parts." PRD § Business Logic: add one sentence after "Przydzielone w ten sposób sztuki od razu podlegają ochronie przed przejęciem.": "Wyjątek: gdy korekta obniży stan poniżej niepobranych rezerwacji zleceń podjętych, rezerwacje te maleją — najpierw zleceń ze zgłoszonym zakończeniem, potem w odwrotnej kolejności przydziału — brak fizycznych sztuk nie jest przejęciem."

### Success Criteria:

#### Automated Verification:

- New allocator tests pass: `./mvnw test -Dtest=ReservationAllocatorTests`
- Full suite passes, including all existing allocator, picking, completion and delivery tests: `./mvnw verify`

#### Manual Verification:

- AGENTS.md and PRD § Business Logic read consistently with the new allocator Javadoc

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful before proceeding to the next phase.

---

## Phase 2: Corrections schema and correction screen (read-only)

### Overview

Add the `stock_corrections` table and entity, and the manager-only correction page with both forms and the past-corrections list. The forms don't save yet. Link the page from `/parts`.

### Changes Required:

#### 1. Migration

**File**: `src/main/resources/db/migration/V11__create_stock_corrections.sql`

**Intent**: Store each correction with its reason and author.

**Contract**: Table `stock_corrections`:
- `id` BIGINT identity PK
- `part_id` BIGINT NOT NULL REFERENCES `parts(id)`
- `account_id` BIGINT NOT NULL REFERENCES `accounts(id)`
- `quantity_before` INT NOT NULL CHECK ≥ 0
- `quantity_after` INT NOT NULL CHECK ≥ 0
- `reason` VARCHAR(500) NOT NULL CHECK `length(btrim(reason)) > 0`
- `created_at` TIMESTAMPTZ NOT NULL DEFAULT now()
- CHECK `quantity_before <> quantity_after`
- Index on `(part_id, created_at DESC)`

#### 2. Entity and repository

**Files**: `src/main/java/pl/regavio/stockahead/parts/StockCorrection.java`, `src/main/java/pl/regavio/stockahead/parts/StockCorrectionRepository.java`

**Intent**: Map the table, and provide the per-part list with the author fetched in one query.

**Contract**: `StockCorrection` has `@ManyToOne` `part` and `account`, plus the scalar fields above. The repository adds `findByPartIdNewestFirst(Long partId, Limit limit)` using `JOIN FETCH c.account`, ordered by `createdAt DESC, id DESC`; the page lists the newest `HISTORY_LIMIT` (50).

#### 3. Controller (GET) and template

**Files**: `src/main/java/pl/regavio/stockahead/parts/StockCorrectionController.java`, `src/main/resources/templates/parts-correction.html`

**Intent**: Render the correction page for one part, active or inactive.

**Contract**: `GET /parts/{id}/correction` with `@PreAuthorize("hasRole('MANAGER')")`; 404 for an unknown id. The model carries the part name, `quantity`, `reserved` (sum over OPEN lines for this part), `available`, the corrections list, and form re-render fields: `newQuantity`, `delta`, `setReason`, `adjustReason`, `expectedQuantity`, `error`. The template has:
- a summary line;
- form A posting to `/parts/{id}/correction/set` with `newQuantity`, hidden `expectedQuantity` and `reason`;
- form B posting to `/parts/{id}/correction/adjust` with `delta` and `reason`;
- the corrections table (Data, Autor, Przed → Po, Zmiana, Powód), or "Brak korekt";
- a link back to `/parts`;
- the centered `td, th` style per lessons.md.

#### 4. Link from the parts list

**File**: `src/main/resources/templates/parts-list.html`

**Intent**: A manager-only "Koryguj stan" link in the Akcje cell, plus a slot for the correction flash message.

**Contract**: `th:href="@{/parts/{id}/correction(id=${row.part.id})}"`; flash attribute `stockCorrected` rendered like `deliveryReceived`.

#### 5. Messages

**Files**: `src/main/resources/messages.properties`, `src/test/java/pl/regavio/stockahead/MessagesBundleTests.java`

**Intent**: Add any page texts that go through the bundle, and pin them in the bundle test.

**Contract**: `corrections.*` key prefix.

#### 6. Tests

**File**: `src/test/java/pl/regavio/stockahead/parts/StockCorrectionIntegrationTests.java`

**Contract**: The manager gets 200 and sees stock, reserved and available. The technician gets **403**, per lessons.md. An unknown id gets 404. The page lists seeded corrections newest first with the author e-mail. `/parts` shows the link to the manager and not to the technician. The migration rejects a blank reason, an equal before/after, and a negative quantity, checked via `JdbcTemplate`.

### Success Criteria:

#### Automated Verification:

- Migration V11 applies on startup and in tests: `./mvnw verify`
- Screen tests pass: `./mvnw test -Dtest=StockCorrectionIntegrationTests`
- Bundle test passes with the new keys: `./mvnw test -Dtest=MessagesBundleTests`

#### Manual Verification:

- As manager, `/parts` → "Koryguj stan" opens the page with correct stock/reserved/available and both forms
- As technician, the link is absent and the URL returns 403

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful before proceeding to the next phase.

---

## Phase 3: Set-total and adjust write paths

### Overview

Wire both forms to an atomic, locked, reallocating write that records the correction.

### Changes Required:

#### 1. Write handlers

**File**: `src/main/java/pl/regavio/stockahead/parts/StockCorrectionController.java`

**Intent**: Two POST handlers that validate the raw strings first, then share one transactional apply step: lock, compute and check the new stock, set it, insert the correction row, flush, reallocate. Both run inside `LockRetry.executeWithLockRetry` + `TransactionTemplate`, as in `DeliveryController`.

**Contract**:
- `POST /parts/{id}/correction/set` (manager): params `newQuantity`, `expectedQuantity`, `reason`.
  - Raw validation: `newQuantity` is an integer with `0 ≤ n ≤ Integer.MAX_VALUE`, parsed via `BigInteger`.
  - Under lock: unknown part → 404 / error. If the locked quantity ≠ `expectedQuantity`, roll back and re-render with `corrections.error.stale`; the page shows the fresh stock, the hidden `expectedQuantity` takes the new value, and the inputs are kept. If `newQuantity` equals the current stock, reject with `corrections.error.noChange`.
- `POST /parts/{id}/correction/adjust` (manager): params `delta`, `reason`.
  - Raw validation: `delta` is a non-zero integer with `|delta| ≤ 1 000 000` (an explicit bound, per lessons.md).
  - Under lock: new stock = `Math.addExact(current, delta)`. Overflow gives `corrections.error.stockOverflow`; a result below 0 gives `corrections.error.belowZero`, naming the current stock.
- Reason (both): trimmed, required (`corrections.error.reasonRequired`), at most 500 characters (`corrections.error.reasonTooLong`).
- Author: the current account via `findByCanonicalEmail(Emails.canonical(auth.getName()))`.
- On success: insert `StockCorrection(before, after, reason, account)`, `flush()`, `reservationAllocator.reallocateForParts(Set.of(id))`, then redirect to `/parts` with flash `stockCorrected` = `corrections.applied` ("Skorygowano stan części „{0}”: {1,number,#} → {2,number,#}.").
- `DataIntegrityViolationException` or `PessimisticLockingFailureException` re-render with `corrections.error.saveFailed`.
- Every rejection re-renders `parts-correction` with the inputs kept and nothing written.

#### 2. Messages

**Files**: `src/main/resources/messages.properties`, `src/test/java/pl/regavio/stockahead/MessagesBundleTests.java`

**Contract**: The `corrections.error.*` keys listed above, plus `corrections.applied`.

#### 3. Integration tests

**File**: `src/test/java/pl/regavio/stockahead/parts/StockCorrectionIntegrationTests.java`

**Contract**: New cases:
- Set down and set up both update stock, write one row with the right before/after, author and trimmed reason, and redirect with the flash message.
- Adjust +N / −N does the same.
- **Downward set covers a shortage:** order with BOM 10, stock 10 fully reserved; set to 6 → order reserved 6, `/purchasing` shows the part with shortage 4.
- **Upward correction tops up:** a short taken order gains units, as with a delivery.
- **Taken deficit end-to-end:** taken order holds 8 unpicked, set to 5 → reserved 5, shortage visible. A subsequent pick of 6 is rejected.
- **Stale total:** submit with `expectedQuantity` ≠ current → nothing written, page shows the fresh stock.
- Each rejection writes nothing: adjust below zero; delta 0; set equal to current; blank reason; 501-character reason; non-integer; delta > 1 000 000.
- An inactive part can be corrected.
- The technician gets **403** on both POST routes.

### Success Criteria:

#### Automated Verification:

- Correction integration tests pass: `./mvnw test -Dtest=StockCorrectionIntegrationTests`
- Full suite passes: `./mvnw verify`

#### Manual Verification:

- Count down a fully reserved part on `/parts/{id}/correction`; `/purchasing` and the order detail show the new shortage without manual steps
- Open the form in two tabs, pick a unit in between, submit "Ustaw stan": the page refuses and shows the fresh stock
- The past-corrections table shows the new row with your e-mail and reason

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful before proceeding to the next phase.

---

## Phase 4: Concurrency guarantees

### Overview

Prove against real Postgres that corrections racing picks, deliveries and order creation never lose an update and never break `stock ≥ 0` or `Σ reserved ≤ stock`.

### Changes Required:

#### 1. Concurrency tests

**File**: `src/test/java/pl/regavio/stockahead/parts/StockCorrectionConcurrencyTests.java`

**Intent**: Mirror `DeliveryConcurrencyTests`: raw-SQL fixtures, the real HTTP routes through `MockMvc` with logged-in sessions, a `CountDownLatch` start, `pg_sleep` triggers that widen the windows, and alternating start offsets over several iterations.

**Contract**: Scenarios:
- **Adjust vs delivery** on the same part: the final stock is exactly start + delivery + delta.
- **Adjust (down) vs pick** on the same part: no lost update; the final stock is start − pick + delta, or the adjust is rejected below zero. Invariants hold.
- **Set vs pick**: either the set wins and the pick then runs against the new reservations, or the pick wins and the set is rejected as stale. Never a silent overwrite of the pick.
- **Set (down, taken deficit) vs pick on the taken order**: the pick never exceeds the post-correction reservation, and `stock ≥ 0` holds.
- **Adjust (down) vs order creation**: the invariants hold.

Every scenario asserts `stock ≥ 0` and `Σ reserved ≤ stock` as SQL over `parts`/`order_lines`, plus exactly one `stock_corrections` row per successful correction.

### Success Criteria:

#### Automated Verification:

- Concurrency tests pass reliably over several runs: `./mvnw test -Dtest=StockCorrectionConcurrencyTests`
- Full suite passes: `./mvnw verify`

**Implementation Note**: After completing this phase and all automated verification passes, the change is ready for `/10x-impl-review`.

---

## Testing Strategy

### Unit Tests:

- None standalone. Allocator rules are tested against real Postgres in `ReservationAllocatorTests` (AGENTS.md: no mocked DB for stock or reservation invariants).

### Integration Tests:

- Allocator deficit pass: single taken order, multiple taken orders in reverse order, non-taken first, reported orders (and before active taken ones), picked untouched.
- Correction screen: access per role, listing, link visibility.
- Write paths: both modes, every rejection, the stale form, shortage on `/purchasing`, top-up on an upward correction, the taken deficit followed by a pick.
- Concurrency: corrections vs pick, delivery, order creation.

### Manual Testing Steps:

1. As manager, create an order that reserves all 10 units of a part, then correct the part to 6 with a reason. `/purchasing` shows 4 missing.
2. As technician, take that order (first pick of 2), then as manager set the stock to 3. The order's reservation shrinks, and the technician can't pick more than what's reserved.
3. Use "Zmień stan o" with −100 on a part with stock 5: rejected, nothing changes.
4. Check the correction page lists every correction with author, before → after and reason.

## Performance Considerations

None beyond the existing pattern: one part lock per correction, and the allocator's per-part recompute as for deliveries.

## Migration Notes

V11 only adds a table, so existing data is untouched. No backfill: there were no corrections before this change.

## References

- Roadmap item: `context/foundation/roadmap.md` § S-11
- PRD: FR-018, § Business Logic, § Access Control, § Non-Goals (FR-006)
- Pattern: `src/main/java/pl/regavio/stockahead/parts/DeliveryController.java:100-188`
- Allocator: `src/main/java/pl/regavio/stockahead/orders/ReservationAllocator.java:84-133`
- Pick guard: `src/main/java/pl/regavio/stockahead/orders/PickingController.java:141-147`
- Sibling concurrency tests: `src/test/java/pl/regavio/stockahead/parts/DeliveryConcurrencyTests.java`
- Prior plan: `context/archive/2026-10-02-delivery-receipt/plan.md`

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles. See `references/progress-format.md`.

### Phase 1: Allocator deficit pass and rule docs

#### Automated

- [x] 1.1 New allocator tests pass: `./mvnw test -Dtest=ReservationAllocatorTests` — cb7a411
- [x] 1.2 Full suite passes, including all existing allocator, picking, completion and delivery tests: `./mvnw verify` — cb7a411

#### Manual

- [x] 1.3 AGENTS.md and PRD § Business Logic read consistently with the new allocator Javadoc — cb7a411

### Phase 2: Corrections schema and correction screen (read-only)

#### Automated

- [x] 2.1 Migration V11 applies on startup and in tests: `./mvnw verify` — 7506059
- [x] 2.2 Screen tests pass: `./mvnw test -Dtest=StockCorrectionIntegrationTests` — 7506059
- [x] 2.3 Bundle test passes with the new keys: `./mvnw test -Dtest=MessagesBundleTests` — 7506059

#### Manual

- [x] 2.4 As manager, `/parts` → "Koryguj stan" opens the page with correct stock/reserved/available and both forms — 7506059
- [x] 2.5 As technician, the link is absent and the URL returns 403 — 7506059

### Phase 3: Set-total and adjust write paths

#### Automated

- [x] 3.1 Correction integration tests pass: `./mvnw test -Dtest=StockCorrectionIntegrationTests` — 98907b3
- [x] 3.2 Full suite passes: `./mvnw verify` — 98907b3

#### Manual

- [x] 3.3 Count down a fully reserved part on `/parts/{id}/correction`; `/purchasing` and the order detail show the new shortage without manual steps — 98907b3
- [x] 3.4 Open the form in two tabs, pick a unit in between, submit "Ustaw stan": the page refuses and shows the fresh stock — 98907b3
- [x] 3.5 The past-corrections table shows the new row with your e-mail and reason — 98907b3

### Phase 4: Concurrency guarantees

#### Automated

- [x] 4.1 Concurrency tests pass reliably over several runs: `./mvnw test -Dtest=StockCorrectionConcurrencyTests` — 7936415
- [x] 4.2 Full suite passes: `./mvnw verify` — 7936415
