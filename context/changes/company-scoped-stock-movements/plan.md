# Company-Scoped Stock Movements Implementation Plan

## Overview

Roadmap item S-05 of milestone M-2. Deliveries, stock corrections and CSV import touch only the logged-in user's company's parts, and reservations recompute only for that company's orders. A part id of another company behaves exactly like a missing one and writes nothing. The same slice gives `stock_corrections` its `company_id` (AGENTS.md § Company isolation) and closes load-current-account's F4: the last single-column account FKs (`stock_corrections.account_id`, `orders.cancelled_by`) become composite company FKs.

## Current State Analysis

S-01 scoped the parts catalog, shelf locks and spellings, import name matching and the delivery form's lists. S-03 made the allocator take a company. On purpose, both left these part lookups bare for S-05:

- `DeliveryController.applyReceipt` locks posted part ids with the unscoped `partRepository.findByIdInForUpdate(partIds)` (`DeliveryController.java:163`). A foreign id passes the `size()` check, its stock is raised, a location is added in the *receiver's* company shelf spelling, and `reallocateForParts(companyId, …)` (`:204`) ignores it, so the owner's short orders miss the units until the next event on that part (roadmap S-05 Risk).
- `StockCorrectionController.applyCorrection` locks by bare id (`StockCorrectionController.java:227`) and reallocates with the locked part's company (`:255`, comment "until S-05"). `renderForm` reads the part with bare `findById(id)` (`:302`), then `reservedQuantityForPart(id)` and the correction history by part id (`:305`, `:312`). Another company's manager can open, set and adjust A's part.
- `PartImportController.applyImport` locks matched ids with the bare `findByIdInForUpdate(matchedIds)` (`PartImportController.java:260`). Those ids come from `CatalogNameIndex.of(findAllIdsAndNames(companyId))` (`:237`), so they are always the company's own ids. The lock is safe in practice but unscoped in form. After S-05 it is the last production caller of the bare method.
- `stock_corrections` (V11) has no `company_id`; `part_id` and `account_id` are single-column FKs (`V11__create_stock_corrections.sql:3-4`). `orders.cancelled_by` is V9's single-column FK (`V9__add_order_cancellation.sql:2`). The correction author and the canceller are already loaded with `findByIdAndCompanyId` (load-current-account), which is their only company guard today.
- Target keys for composite FKs exist: `parts_id_company_key` (`V15__company_scoped_parts.sql:12`) and `accounts_id_company_key` (`V17__company_scoped_orders.sql:5`). V18 is the template for an FK swap (`V18__company_scoped_completion_reporter.sql`).
- `spring.jpa.hibernate.ddl-auto=validate` (`application.properties:3`): a new NOT NULL column must be mapped on `StockCorrection` in the same phase as the migration.

## Desired End State

- B's manager gets 404 on `GET /parts/{A}/correction`, `POST …/correction/set` and `POST …/correction/adjust`. A's stock, corrections and reservations stay unchanged, and no row of A is locked.
- B's user posting a delivery that names A's part id gets the existing "part doesn't exist" form error (`deliveries.error.partNotFound`), exactly like a missing id. Nothing is written, and A's part is not locked.
- Correction and delivery reallocate with the principal's company, and both stay correct for own-company parts.
- CSV import confirm locks with the scoped variant. Its behavior is unchanged.
- `stock_corrections.company_id` is NOT NULL, and the DB refuses a correction whose part or author belongs to another company. The DB refuses an order cancelled by another company's account.
- The bare `PartRepository.findByIdInForUpdate` no longer exists.

Verify with `./mvnw verify` (all existing suites plus the new isolation and schema tests) and the manual checks in each phase.

### Key Discoveries:

- `PartRepository.findByIdInAndCompanyIdForUpdate` (`PartRepository.java:44-47`) already exists and does not lock rows of other companies. A `FOR UPDATE` only locks rows that pass its `WHERE`.
- Precedent for a foreign id posted inside a form: S-02's BOM add-line answers the same form error as a missing or inactive part (`ProjectBomController.java:85-90`), not 404. A foreign id in the URL path is 404 (AGENTS.md:35).
- `PartLocation.companyId` is a plain column, set from its part in `Part#addLocation` (`Part.java:107-111`) and backed by a `(part_id, company_id)` FK. This is the pattern for `StockCorrection.companyId`.
- `CrossCompanyAllocationLockTests.java:176` holds A's part lock via the bare `findByIdInForUpdate`. It must switch to the scoped variant before the bare method is deleted.
- `StockCorrectionIntegrationTests.seedCorrection` (`StockCorrectionIntegrationTests.java:179-185`) inserts into `stock_corrections` without `company_id` and must add it.

## What We're NOT Doing

- Operator (`companyId == null`) 403s on these routes. S-08 owns them.
- The shopping list (S-06) and account management (S-07).
- Any change to the CSV file format, import preview or snapshot logic (prd-v2 § Constraints). Only the confirm's row lock changes.
- Turning the delivery's foreign-id case into a 404. It stays the same form error as a missing id, following S-02's precedent.
- Changing `reservedQuantityForPart(partId)` or `findByPartIdNewestFirst(partId)`. They run only after the scoped part check, so they never see a foreign id.
- The allocator, lock order or `LockRetry` behavior; templates and messages.

## Implementation Approach

Two phases, the same schema-first split as S-01 to S-04. Phase 1 changes only the schema and the entity mapping, so behavior is unchanged and a migration problem stays isolated. Phase 2 swaps every remaining part lookup in the three controllers for its `(id, companyId)` variant, using the principal's company. It deletes the bare lock so it can't come back and adds the two-company test suite.

## Phase 1: Company on corrections and composite account FKs

### Overview

V19 gives `stock_corrections` a NOT NULL `company_id` and composite FKs to its part and author. It also swaps `orders.cancelled_by` to a composite FK. The entity maps the column. No behavior change.

### Changes Required:

#### 1. Migration V19

**File**: `src/main/resources/db/migration/V19__company_scoped_stock_corrections.sql`

**Intent**: Add `stock_corrections.company_id`, backfilled from the part's company, so any rows written since V14 keep working. Then make the correction's part and author, and an order's canceller, belong to one company at the DB level. Comment header in V18's style.

**Contract**:
- `stock_corrections.company_id BIGINT`, backfilled with `UPDATE … SET company_id = parts.company_id FROM parts WHERE parts.id = stock_corrections.part_id`. After the backfill: `NOT NULL`, `REFERENCES companies (id) ON DELETE RESTRICT`.
- Drop `stock_corrections_part_id_fkey` and add `stock_corrections_part_company_fkey (part_id, company_id) → parts (id, company_id)`.
- Drop `stock_corrections_account_id_fkey` and add `stock_corrections_author_company_fkey (account_id, company_id) → accounts (id, company_id) ON DELETE RESTRICT`.
- Drop `orders_cancelled_by_fkey` and add `orders_canceller_company_fkey (cancelled_by, company_id) → accounts (id, company_id) ON DELETE RESTRICT`. Default MATCH SIMPLE, so an uncancelled order (NULL) passes, as in V18.
- Keep V11's ON DELETE behavior on the part FK. The existing `stock_corrections_part_created_idx` stays.
- Confirm the auto-generated FK names against the live schema (`\d stock_corrections`, `\d orders`) before writing the DROPs.

#### 2. StockCorrection entity

**File**: `src/main/java/pl/regavio/stockahead/parts/StockCorrection.java`

**Intent**: Map the new column so `ddl-auto=validate` passes and every new correction carries its part's company.

**Contract**: A plain `@Column(name = "company_id", nullable = false) Long companyId`, set from the part's company whenever the part is set (`setPart`), mirroring `PartLocation`/`Part#addLocation`. No public setter for the company on its own.

#### 3. Test seeds and schema tests

**Files**: `src/test/java/pl/regavio/stockahead/parts/StockCorrectionIntegrationTests.java`, `src/test/java/pl/regavio/stockahead/parts/PartCompanySchemaTests.java`, `src/test/java/pl/regavio/stockahead/orders/OrderSchemaTests.java`

**Intent**: Keep the raw-SQL seed valid, and prove that each new FK refuses a cross-company row and accepts an own-company one.

**Contract**:
- `seedCorrection` (`StockCorrectionIntegrationTests.java:179-185`, the only `INSERT INTO stock_corrections` in `src/test`) inserts the part's `company_id`. This keeps the CHECK-constraint tests (`:747-767`) failing on their own CHECK, not on a NOT NULL `company_id`, which would raise the same `DataIntegrityViolationException` and hide a broken CHECK.
- Raw-SQL `cancelled_by` writes keep the canceller in the order's company: `StockCorrectionIntegrationTests.seedCancelledOrder` (`:166`) and `OrderCancelIntegrationTests.java:721`, `:725`. The `:721` case must still fail on its V9 CHECK (`orders_cancelled_status_check`), not on the new FK.
- `PartCompanySchemaTests` gets new cases: a correction whose part belongs to another company is rejected, a correction whose author belongs to another company is rejected, and an own-company correction is accepted. All three assert `DataIntegrityViolationException`/success like the existing cases.
- `OrderSchemaTests` gets two new cases: an order cancelled by another company's account is rejected, and an order cancelled by its own company's account is accepted. These mirror `completionReportedBy…` (`OrderSchemaTests.java:163-180`).

### Success Criteria:

#### Automated Verification:

- Migration applies and Hibernate validates the schema: `./mvnw test -Dtest=StockaheadApplicationTests`
- New and existing schema tests pass: `./mvnw test -Dtest='PartCompanySchemaTests,OrderSchemaTests'`
- Correction and cancel suites pass unchanged in behavior: `./mvnw test -Dtest='StockCorrection*Tests,OrderCancel*Tests'`

#### Manual Verification:

- On a local database with existing data, the app starts after V19 and `stock_corrections.company_id` equals each row's part's company (`SELECT count(*) FROM stock_corrections sc JOIN parts p ON p.id = sc.part_id WHERE sc.company_id <> p.company_id` returns 0).

**Implementation Note**: After automated verification passes, pause for the human's manual confirmation before Phase 2.

---

## Phase 2: Scoped stock routes and two-company tests

### Overview

Every part lookup and lock in delivery, correction and import takes the principal's company. The bare lock is deleted. A new suite proves B's users can't read or change A's stock.

### Changes Required:

#### 1. Delivery receipt

**File**: `src/main/java/pl/regavio/stockahead/parts/DeliveryController.java`

**Intent**: Lock only the receiver's company's parts, so a foreign posted id falls into the existing `lockedParts.size() != partIds.size()` branch. It gets `deliveries.error.partNotFound`, rolls back, and A's row is never locked or raised. Shelf resolution was already company-scoped; this closes S-01 impl review F1's "second spelling of one shelf" case.

**Contract**: `applyReceipt` calls `findByIdInAndCompanyIdForUpdate(partIds, companyId)` in place of `findByIdInForUpdate(partIds)` (`:163`). The shelf-then-part lock order is unchanged. Update the class javadoc reference (`:46`).

#### 2. Stock correction

**File**: `src/main/java/pl/regavio/stockahead/parts/StockCorrectionController.java`

**Intent**: Answer 404 for another company's part on the page and on both writes, before validation output, locks or writes. Reallocate with the principal's company.

**Contract**:
- `form` takes `@AuthenticationPrincipal CompanyUser principal`. `renderForm` takes the company id and loads the part with `findByIdAndCompanyId(id, companyId)` → 404 (`:302`).
- `set`/`adjust` validation-error re-renders go through the scoped `renderForm`, so a foreign id with invalid input is still 404, never a rendered page.
- `applyCorrection` locks with `findByIdInAndCompanyIdForUpdate(Set.of(id), principal.companyId())` → empty is 404 (`:227`). `reallocateForParts(principal.companyId(), Set.of(id))` replaces `part.getCompany().getId()`, and the "until S-05" comment goes (`:254-255`).
- Update the class javadoc reference (`:44`).

#### 3. CSV import confirm

**File**: `src/main/java/pl/regavio/stockahead/parts/PartImportController.java`

**Intent**: Use the scoped lock for consistency with AGENTS.md:37. The matched ids are already own-company, so behavior is unchanged.

**Contract**: `applyImport` calls `findByIdInAndCompanyIdForUpdate(matchedIds, companyId)` (`:260`). `findWithLocationsByIdIn(matchedIds)` stays, because it only initializes locations of rows already locked.

#### 4. Remove the bare lock

**Files**: `src/main/java/pl/regavio/stockahead/parts/PartRepository.java`, `src/main/java/pl/regavio/stockahead/orders/LockRetry.java`, `src/test/java/pl/regavio/stockahead/orders/CrossCompanyAllocationLockTests.java`

**Intent**: Delete `findByIdInForUpdate(Collection<Long>)` so no future caller can lock across companies. This follows S-04's removal of bare variants.

**Contract**:
- Remove the method (`PartRepository.java:33-35`). Repoint the `findCurrentQuantities` javadoc (`:99`) and `LockRetry`'s javadoc (`LockRetry.java:14`) to `findByIdInAndCompanyIdForUpdate`.
- `CrossCompanyAllocationLockTests` holds A's lock with `findByIdInAndCompanyIdForUpdate(Set.of(partA.getId()), companyA.getId())` (`:176`).
- `grep -rn "findByIdInForUpdate(" src/` returns nothing.

#### 5. Two-company isolation tests

**File**: `src/test/java/pl/regavio/stockahead/parts/StockMovementsCompanyIsolationIntegrationTests.java` (new)

**Intent**: Add the AGENTS.md company-B→404 guardrail for every stock-movement route, using `CompanyFixtures` and the shape of `PickingCompanyIsolationIntegrationTests`. Two companies each have a part, and A has an open order short on its part.

**Contract**: The test cases:
- B's manager `GET /parts/{A}/correction` → 404.
- B's manager `POST …/correction/set` (valid input, A's current quantity as `expectedQuantity`) → 404, and A's stock, `stock_corrections` count and A's reservations are unchanged.
- B's manager `POST …/correction/adjust` (valid delta) → 404, nothing changed.
- B's manager `POST …/correction/set` with *invalid* input on A's id → 404, not a rendered page.
- B's technician `POST /deliveries` naming A's part id → 200 with the `partNotFound` error, and A's stock and locations are unchanged.
- A delivery mixing B's own part and A's part → the error, and **neither** part changes (all-or-nothing).
- Delivery of B's own part to a shelf spelled like one of A's shelves stores B's own spelling (no cross-company respelling).
- B's manager imports a CSV naming a part that exists only in A → it previews and confirms as a *new* part in B. A's part is unchanged.
- An own-company correction and delivery for A still work and reallocate A's short order. A correction adjusting A's stock up tops up A's reservation.
- B's foreign-id delivery and correction attempts don't wait on A's held part lock (hold A's lock in a separate transaction as `CrossCompanyAllocationLockTests` does; B's request returns within the timeout).

### Success Criteria:

#### Automated Verification:

- New isolation suite passes: `./mvnw test -Dtest=StockMovementsCompanyIsolationIntegrationTests`
- Stock-movement and allocation suites pass: `./mvnw test -Dtest='Delivery*Tests,StockCorrection*Tests,PartImport*Tests,CrossCompanyAllocationLockTests,Reservation*Tests'`
- No bare part lock remains: `grep -rn "findByIdInForUpdate(" src/` returns nothing
- Full build passes: `./mvnw verify`

#### Manual Verification:

- With two companies seeded, B's manager opening `/parts/{A's id}/correction` in the browser gets a 404 page.
- As A's technician, receive a delivery for a part an open order is short on. The order's reservation rises at once, and the parts list shows the new stock.
- As A's manager, correct a part's stock up and down. Both succeed, the history lists both with your account, and the reservations follow.

**Implementation Note**: After automated verification passes, pause for the human's manual confirmation.

---

## Testing Strategy

### Unit Tests:

- None new. The changed code is repository wiring, covered by integration tests against real Postgres (AGENTS.md § Testing).

### Integration Tests:

- `PartCompanySchemaTests` and `OrderSchemaTests`: the three new composite FKs, cross-company rejected and own-company accepted.
- `StockMovementsCompanyIsolationIntegrationTests`: the 404 and form-error guardrail per route, no-mutation assertions, all-or-nothing mixed delivery, per-company shelf spelling, import name matching, own-company regression, no cross-company lock wait.
- Existing `Delivery*`, `StockCorrection*`, `PartImport*`, `CrossCompanyAllocationLockTests` and `Reservation*` suites must pass unchanged apart from the seed/lock-call edits named above.

### Manual Testing Steps:

1. Start the app on a database with existing corrections. V19 applies, and the backfill check query returns 0.
2. Log in as B's manager and open `/parts/{A}/correction`: 404.
3. Log in as A's technician, receive a delivery for a short part: the order's reservation rises.
4. Log in as A's manager, set and adjust stock: the history and reservations are correct.

## Migration Notes

V19 backfills `stock_corrections.company_id` from `parts.company_id` before setting NOT NULL. Every existing correction's part already carries a company (V15), and its author was loaded within the same company (load-current-account), so the composite FKs validate against existing rows. If an older row somehow has an author from another company, V19 fails on deploy rather than silently keeping it. In that case, inspect the row instead of loosening the FK. `orders.cancelled_by` rows were written with `findByIdAndCompanyId` since load-current-account, and with the principal's own account before that, so the swap validates too. Rollback is the CI's image rollback plus a forward-fix migration. Flyway does not undo V19 automatically. During an image rollback the previous image starts, because `ddl-auto=validate` tolerates the extra column. Its `StockCorrection` never sets `company_id`, though, so every stock correction fails with `corrections.error.saveFailed` until roll-forward. Deliveries, imports and cancellations are not affected. V15 and V17 accepted the same posture.

## References

- Roadmap item: `context/foundation/roadmap.md` § S-05
- Pattern for scoped routes and isolation tests: `context/archive/2026-10-29-company-scoped-picking-completion/plan.md`, `src/test/java/pl/regavio/stockahead/orders/PickingCompanyIsolationIntegrationTests.java`
- FK swap template: `src/main/resources/db/migration/V18__company_scoped_completion_reporter.sql`
- F4 hand-off: `context/archive/2026-10-29-load-current-account/plan.md:34`, `:40`
- Foreign-id-in-form precedent: `src/main/java/pl/regavio/stockahead/projects/ProjectBomController.java:85-90`
- Rules: `AGENTS.md` § Company isolation; `context/foundation/lessons.md` (wrong-role tests, constraint violations at write boundaries)

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles. See `references/progress-format.md`.

### Phase 1: Company on corrections and composite account FKs

#### Automated

- [x] 1.1 Migration applies and Hibernate validates the schema: `./mvnw test -Dtest=StockaheadApplicationTests`
- [x] 1.2 New and existing schema tests pass: `./mvnw test -Dtest='PartCompanySchemaTests,OrderSchemaTests'`
- [x] 1.3 Correction and cancel suites pass unchanged in behavior: `./mvnw test -Dtest='StockCorrection*Tests,OrderCancel*Tests'`

#### Manual

- [x] 1.4 On a local database with existing data, the app starts after V19 and `stock_corrections.company_id` equals each row's part's company

### Phase 2: Scoped stock routes and two-company tests

#### Automated

- [ ] 2.1 New isolation suite passes: `./mvnw test -Dtest=StockMovementsCompanyIsolationIntegrationTests`
- [ ] 2.2 Stock-movement and allocation suites pass: `./mvnw test -Dtest='Delivery*Tests,StockCorrection*Tests,PartImport*Tests,CrossCompanyAllocationLockTests,Reservation*Tests'`
- [ ] 2.3 No bare part lock remains: `grep -rn "findByIdInForUpdate(" src/` returns nothing
- [ ] 2.4 Full build passes: `./mvnw verify`

#### Manual

- [ ] 2.5 With two companies seeded, B's manager opening `/parts/{A's id}/correction` in the browser gets a 404 page
- [ ] 2.6 As A's technician, receive a delivery for a part an open order is short on; the order's reservation rises at once
- [ ] 2.7 As A's manager, correct a part's stock up and down; both succeed, the history lists both with your account, and the reservations follow
