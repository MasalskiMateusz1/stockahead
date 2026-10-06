# Company-Scoped Parts Catalog Implementation Plan

## Overview

Roadmap M-2 S-01 (north star). Each company gets its own parts catalog. Parts and shelf locations carry `company_id`. Part names and shelf spellings are unique per company. Every catalog query and advisory lock is scoped to the logged-in user's company, and a user of company B gets 404 on company A's part ids. Implements FR-028 for the parts catalog and the guessed-id guardrail from prd-v2 § Success Criteria › Guardrails.

## Current State Analysis

F-01 (`company-boundary-harness`, archived 2026-10-06) added `companies`, `accounts.company_id`, the `CompanyUser` principal with `companyId()`, and the `CompanyFixtures` test bean. The parts catalog has no company concept yet:

- `parts.name` is globally `UNIQUE` (`V3__create_parts_catalog.sql:3`, `@Column(unique = true)` at `parts/Part.java:24`).
- Every read in `parts/PartRepository.java` is global: `findByName` (:17), `search` (:19-26), `findAllLocationNames` (:33), `findSpellingsOf` (:41), `findWithLocationsByIdIn` (:63), `findAllIdsAndNames` (:89). The bulk update `respellOnOtherParts` (:50-55) re-spells a shelf on **every** part in the database.
- The advisory locks in `parts/LocationSpellings.java:45-71` are keyed by the shelf or name hash only. Company A writing shelf "A1" would block, and be blocked by, company B writing "A1".
- `CatalogNameIndex` (`parts/CatalogNameIndex.java:30`) is built from the global `findAllIdsAndNames`. A case variant in another company would be read as a duplicate name.
- `parts/PartController.java` loads parts with a bare `findById` at :203, :220, :282, :311 and :323, and never reads the principal.
- The parts list adds reserved quantities from `OrderLineRepository.reservedQuantitiesByPart()` (`orders/OrderLineRepository.java:13`), summed over every company's order lines.
- Callers outside `PartController` that use the helpers whose signatures change:
  - `PartImportController.java:129, :221-255, :361` (via `ImportPreview.build`).
  - `DeliveryController.java:156, :297-298`.
  - `projects/ProjectDetailModel.java:89`: the BOM part picker, `search("", false)`, reached through `render(...)` from `ProjectController`, `ProjectBomController` and `ProjectLinkController`.
- About 27 test files insert parts, by JDBC (`INSERT INTO parts …`) or `new Part()`. All of them break once `parts.company_id` is `NOT NULL`.
- Production has not run V14 yet: multi-company work stays on its branch, and V14 requires an empty database. V15 therefore always runs against an empty `parts` table, so no backfill is needed.

## Desired End State

- `parts` and `part_locations` carry a non-null `company_id`. A location's company always equals its part's company (enforced by an FK).
- Part names are `UNIQUE (company_id, name)`. Case-insensitive name and shelf matching applies only within one company.
- Every catalog read, the shelf re-spelling update and both advisory locks are scoped to the principal's company.
- `/parts`, `/parts/{id}/edit`, `POST /parts/{id}`, `/parts/{id}/deactivate` and `/parts/{id}/reactivate` serve only the user's own company's parts. Another company's id answers 404 and changes nothing.
- The import, the delivery form and the BOM part picker show and match only the user's own company's parts.
- `./mvnw verify` is green.

Verify with the Phase 2 two-company tests and a manual run of `spring-boot:test-run` with two companies.

### Key Discoveries:

- F-01 pattern to copy: `AccountRepository.findByIdAndCompanyId` (`account/AccountRepository.java:27`) with `@AuthenticationPrincipal CompanyUser principal` (`account/TechnicianAccountController.java:56-118`).
- Two-company test pattern to copy: `account/TechnicianAccountIntegrationTests.java`, using `company()`/`otherCompany()` from `CompanyFixtures`.
- Parts tests delete `part_locations`/`parts` before `companyFixtures.cleanUp()` (`parts/PartsCatalogIntegrationTests.java:62-74`), so an `ON DELETE RESTRICT` FK from parts to companies keeps cleanup working.
- `PartController.constraintErrorKey` matches the location index by name (`PartController.java:42`), and a test asserts it (`PartsCatalogIntegrationTests.java:742`). V15 must keep the index name `part_locations_part_location_ci_idx`, or the constant and the test must change with it.
- `pg_advisory_xact_lock(int, int)` has only two int keys (`PartRepository.java:62`). The company has to be folded into the second key.

## What We're NOT Doing

- No Hibernate `@TenantId` or `@Filter`. Scoping uses an explicit `companyId` parameter, as in F-01.
- No scoping of the by-id lookups in delivery, stock correction and import routes, and no B→404 tests for them. That's S-05. `DeliveryController`'s `findByIdInForUpdate(partIds)` with posted ids and `StockCorrectionController.java:226, :300` stay as they are.
- No scoping of `ProjectBomController.java:82` (`findById(partId)` when adding a BOM line). Cross-company BOM references are S-02.
- No scoping of `ReservationAllocator`, `PickingController`, `OrderController` or `OrderDetailModel` part locks (S-03/S-04). Their part ids come from order lines, which are not company-scoped yet.
- No `company_id` on `stock_corrections`, `projects`, `bom_lines`, `orders` or `order_lines`.
- No UI change. The company name is not shown.
- No new 403 tests beyond the existing wrong-role tests for parts routes.

## Implementation Approach

The company flows from the database to `CompanyUser` and then to the controller, which passes it as an explicit argument. Repository methods take `companyId`. The non-public helpers (`LocationSpellings`, `CatalogNameIndex`, `ImportPreview`) take it from their caller. A miss on `(id, companyId)` is a 404, exactly like a missing id.

Phase 1 is the mechanical fallout of the `NOT NULL` column: schema, entity, setting the company on every create path, and every test insert. It leaves all queries global. Phase 2 does the semantic scoping and adds the two-company tests that prove it.

## Critical Implementation Details

- **Lock order**: shelves, then names, then rows. Keep `LocationSpellings`' sorted-key order unchanged. Only the key derivation changes: fold the company into the int key, for example as a hash of `companyId` and the shelf key. A collision between two companies' keys only over-serializes, never deadlocks, because the ordering stays global and sorted.
- **Locations under re-spelling**: `respellOnOtherParts` is a bulk JPQL update that bypasses the persistence context. Scope it by `l.companyId = :companyId`. Bulk-update JPQL cannot join to `parts`, which is one reason `part_locations` carries its own `company_id`.

## Phase 1: Schema, entity and test fixtures

### Overview

Add the company columns and per-company uniqueness, set the company on every path that creates a part, and give every test that seeds parts a company. Queries stay global and behavior is unchanged. The build is green.

### Changes Required:

#### 1. V15 migration

**File**: `src/main/resources/db/migration/V15__company_scoped_parts.sql`

**Intent**: Give the parts catalog a company, and make name uniqueness and location ownership per company. The table is guaranteed empty (V14 guard), so the columns can be `NOT NULL` with no backfill.

**Contract**:
- `parts.company_id BIGINT NOT NULL REFERENCES companies (id) ON DELETE RESTRICT`.
- Drop `parts_name_key` and add `UNIQUE (company_id, name)`.
- Add `UNIQUE (id, company_id)` on `parts`, the target for the composite FK.
- `part_locations.company_id BIGINT NOT NULL`, with `FOREIGN KEY (part_id, company_id) REFERENCES parts (id, company_id) ON DELETE CASCADE`. This replaces the existing `part_id` FK.
- Keep `part_locations_part_location_ci_idx (part_id, lower(location))` unchanged.
- Add `CREATE INDEX part_locations_company_location_idx ON part_locations (company_id, lower(location))` for spelling lookups.
- Add `parts_company_id_idx` only if `UNIQUE (company_id, name)` doesn't already serve it. It does, as the leading column.

#### 2. Entities

**File**: `src/main/java/pl/regavio/stockahead/parts/Part.java`, `PartLocation.java`

**Intent**: Map the new columns. A location always takes its part's company.

**Contract**:
- Remove `unique = true` from `Part.name`.
- Add `@ManyToOne(optional = false) @JoinColumn(name = "company_id") Company company` to `Part`, with a getter and setter.
- Map `PartLocation`'s `company_id` as a plain `Long companyId` column, set from the part in `Part.addLocation`.
- Keep the existing `part` association as the writer of `part_id`, so mapping `company_id` doesn't create a second writer of the composite FK.

#### 3. Create paths set the company

**File**: `parts/PartController.java` (`create`), `parts/PartImportController.java` (`confirm`, new parts at :324)

**Intent**: Every new part belongs to the creator's company. Without this, the `NOT NULL` column fails every create.

**Contract**: Both handlers take `@AuthenticationPrincipal CompanyUser principal` and set `part.setCompany(companyRepository.getReferenceById(principal.companyId()))` before saving. The company comes only from the principal.

#### 4. Test fixtures

**File**: `src/test/java/pl/regavio/stockahead/CompanyFixtures.java`, plus every test that seeds parts (about 27 files under `parts/`, `projects/`, `orders/`, `purchasing/`)

**Intent**: Seeded parts belong to the test's existing company, and new tests get one helper for seeding parts.

**Contract**:
- Add `CompanyFixtures.part(Company company, String name, int quantity, String... locations)`. It commits and returns the `Part`.
- In existing tests, add `company_id` to JDBC inserts (with the location rows' `company_id`) or call `setCompany` on `new Part()`, using the company the test already creates.
- `V10MigrationTests` and `V14MigrationTests` insert at their own migration version, where the column doesn't exist yet. Leave them unchanged unless they migrate past V15.

### Success Criteria:

#### Automated Verification:

- `./mvnw verify` passes with V15 applied
- A test asserts that two companies can each hold a part named "R 10k", while a second "R 10k" in the same company violates the unique constraint
- A test asserts that a `part_locations` row whose `company_id` differs from its part's company is rejected by the database

#### Manual Verification:

- `./mvnw spring-boot:test-run`: the manager creates a part and imports a CSV, and both succeed

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful before proceeding to the next phase.

---

## Phase 2: Scoped catalog and the two-company tests

### Overview

Scope every catalog read, the shelf re-spelling update, the advisory locks and the by-id lookups to the principal's company. Pass the company through the callers whose signatures change. Prove the isolation with two-company tests.

### Changes Required:

#### 1. PartRepository

**File**: `src/main/java/pl/regavio/stockahead/parts/PartRepository.java`

**Intent**: Every query that answers "what's in the catalog" sees only one company.

**Contract**:
- Add `findByIdAndCompanyId(Long id, Long companyId)`.
- `findByName` becomes `findByCompanyIdAndName`.
- `search`, `findAllLocationNames`, `findSpellingsOf` and `findAllIdsAndNames` gain a `companyId` parameter and filter on it.
- `respellOnOtherParts` gains a `companyId` parameter and filters `part_locations.company_id` on it.
- `findByIdInForUpdate`, `findWithLocationsByIdIn` and `findCurrentQuantities` stay unscoped: they take ids already resolved within a company, and the S-03…S-05 callers own them.

#### 2. LocationSpellings and CatalogNameIndex

**File**: `parts/LocationSpellings.java`, `parts/CatalogNameIndex.java`, `parts/ImportPreview.java`

**Intent**: Shelf and name locks and spelling resolution apply within one company, so two companies never block each other or share a spelling.

**Contract**:
- `lockShelves(partRepository, companyId, locations)` and `lockPartNames(partRepository, companyId, names)` fold `companyId` into the int key.
- `resolve` uses `findSpellingsOf(companyId, …)`.
- `ImportPreview.build(lines, partRepository, companyId)` builds the index from `findAllIdsAndNames(companyId)`.

#### 3. PartController

**File**: `src/main/java/pl/regavio/stockahead/parts/PartController.java`

**Intent**: The catalog routes act only on the user's company, and a foreign id is a 404.

**Contract**:
- Every handler takes `@AuthenticationPrincipal CompanyUser principal`.
- All five `findById` calls become `findByIdAndCompanyId(id, principal.companyId())` with the existing `NOT_FOUND`.
- The duplicate-name checks, the `CatalogNameIndex` reads, the locks and `respellOnOtherParts` pass the company.
- The list's reserved-quantity map comes from a company-scoped query.

#### 4. Reserved quantity per company

**File**: `src/main/java/pl/regavio/stockahead/orders/OrderLineRepository.java`

**Intent**: The parts list's reserved and available numbers never read another company's order lines.

**Contract**: `reservedQuantitiesByPart(Long companyId)` adds `AND ol.part.company.id = :companyId`. `PartController.list` is the only caller to update.

#### 5. Forced callers

**File**: `parts/PartImportController.java`, `parts/DeliveryController.java`, `projects/ProjectDetailModel.java` and its callers `ProjectController`, `ProjectBomController`, `ProjectLinkController`

**Intent**: The callers of the changed helpers pass the principal's company. Import matching, the delivery form's part list and known shelves, and the BOM part picker then show only the user's own company. Their own by-id lookups stay for S-02/S-05.

**Contract**:
- Import `upload`/`confirm` and the delivery handlers take `@AuthenticationPrincipal CompanyUser` and pass `companyId` to `ImportPreview.build`, `lockShelves`, `lockPartNames`, `findAllIdsAndNames`, `search` and `findAllLocationNames`.
- `ProjectDetailModel.render(...)` gains a `companyId` argument, used only for the picker's `search(companyId, "", false)`.

#### 6. Two-company tests

**File**: `src/test/java/pl/regavio/stockahead/parts/PartsCompanyIsolationIntegrationTests.java` (new), following `account/TechnicianAccountIntegrationTests.java`

**Intent**: Prove the S-01 guardrail on every catalog route and on the shared name and shelf pieces.

**Contract**: Use `CompanyFixtures`, `@Import({ TestcontainersConfiguration.class, CompanyFixtures.class })` and a real Postgres. Cases:
- Company B's manager gets 404 for each of `GET /parts/{A-id}/edit`, `POST /parts/{A-id}`, `POST /parts/{A-id}/deactivate` and `POST /parts/{A-id}/reactivate`, and A's part is unchanged in the DB (name, locations, active).
- `GET /parts` (and `?q=`, and `showInactive=true`) for B's manager and B's technician never lists A's parts. A search for A's shelf name returns nothing.
- B can create "R 10k" at shelf "A1" while A has "R 10k" at "a1". Each keeps its own spelling.
- Re-spelling "a1" to "A1" on A's part doesn't change B's location rows.
- The BOM part picker for B's project page doesn't list A's parts.
- An import preview in B classifies A's part name as new, not as an update.
- Concurrency: A and B create the same part name and the same shelf at the same moment. Both succeed, neither waits on the other's lock. Copy the latch pattern from `parts/LocationSpellingConcurrencyTests.java`.

#### 7. Docs

**File**: `AGENTS.md` (§ Company isolation), `context/foundation/roadmap.md`

**Intent**: Record that the parts locks now include the company, and leave the S-05 and S-02 leftovers explicit for their planners.

**Contract**:
- Change the AGENTS.md sentence "once names are unique per company, include the company in the lock key" to state that `LocationSpellings` keys include the company.
- In the S-05 and S-02 roadmap entries, add the leftover bare part lookups as a Risk note: delivery `findByIdInForUpdate` with posted ids, `StockCorrectionController`, and `ProjectBomController.java:82`.

### Success Criteria:

#### Automated Verification:

- `./mvnw verify` passes
- `PartsCompanyIsolationIntegrationTests` passes: B gets 404 on all four `/parts/{id}` routes with A's data unchanged, the list and search show only the user's own company, names and shelves are reused across companies, and re-spelling stays within one company
- The BOM picker and import-preview isolation tests pass
- The cross-company concurrency test shows two companies creating the same name and shelf at the same time without blocking each other
- `grep -n "findById(" src/main/java/pl/regavio/stockahead/parts/PartController.java` returns nothing

#### Manual Verification:

- With `spring-boot:test-run`, two companies each create "R 10k" at "A1". Each manager sees only their own part, and pasting the other company's part id into `/parts/{id}/edit` shows the 404 page
- The BOM add-line picker and the delivery form list only the user's own company's parts

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful before proceeding.

---

## Testing Strategy

### Unit Tests:

- No new pure unit tests. The behavior lives in SQL, locks and the security principal, and AGENTS.md requires real Postgres for these invariants.

### Integration Tests:

- Phase 1: per-company unique name; composite FK rejects a location with a mismatched company.
- Phase 2: `PartsCompanyIsolationIntegrationTests`, with the cases listed in Phase 2 § 6.
- Every existing parts, projects, orders and purchasing test keeps passing on single-company data.

### Manual Testing Steps:

1. `./mvnw spring-boot:test-run`, set up company A, log in as A's manager, create "R 10k" at "A1".
2. Create company B and its manager in the DB, or with a second `CompanyFixtures`-like seed. Log in as B's manager, create "R 10k" at "a1", and confirm it succeeds and shows "a1".
3. As B, open `/parts/<A's id>/edit` and confirm the 404.
4. As B, open a project's BOM picker and the delivery form and confirm only B's parts appear.

## Performance Considerations

`findAllIdsAndNames` was a full-table read and now reads a single company's rows. The new `(company_id, lower(location))` index keeps spelling lookups indexed. No other change.

## Migration Notes

V15 assumes `parts` and `part_locations` are empty. That holds because V14's guard required an empty database and the two ship in the same deploy from the multi-company branch. If V15 meets rows, `ADD COLUMN … NOT NULL` without a default fails and Flyway rolls back; deployment.md's V14 wipe procedure applies.

## References

- Roadmap: `context/foundation/roadmap.md` § S-01
- PRD: `context/foundation/prd-v2.md` FR-028, Success Criteria › Guardrails
- Frame: `context/changes/multi-company-support/frame.md`
- F-01 plan: `context/archive/2026-10-05-company-boundary-harness/plan.md`
- Pattern: `account/AccountRepository.java:27`, `account/TechnicianAccountController.java:56-118`, `account/TechnicianAccountIntegrationTests.java`
- Lessons: `context/foundation/lessons.md` (DB constraint violations at write boundaries; wrong-role tests)

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles. See `references/progress-format.md`.

### Phase 1: Schema, entity and test fixtures

#### Automated

- [x] 1.1 `./mvnw verify` passes with V15 applied
- [x] 1.2 A test asserts that two companies can each hold a part named "R 10k", while a second "R 10k" in the same company violates the unique constraint
- [x] 1.3 A test asserts that a `part_locations` row whose `company_id` differs from its part's company is rejected by the database

#### Manual

- [x] 1.4 `./mvnw spring-boot:test-run`: the manager creates a part and imports a CSV, and both succeed

### Phase 2: Scoped catalog and the two-company tests

#### Automated

- [ ] 2.1 `./mvnw verify` passes
- [ ] 2.2 `PartsCompanyIsolationIntegrationTests` passes: B gets 404 on all four `/parts/{id}` routes with A's data unchanged, the list and search show only the user's own company, names and shelves are reused across companies, and re-spelling stays within one company
- [ ] 2.3 The BOM picker and import-preview isolation tests pass
- [ ] 2.4 The cross-company concurrency test shows two companies creating the same name and shelf at the same time without blocking each other
- [ ] 2.5 `grep -n "findById(" src/main/java/pl/regavio/stockahead/parts/PartController.java` returns nothing

#### Manual

- [ ] 2.6 With `spring-boot:test-run`, two companies each create "R 10k" at "A1". Each manager sees only their own part, and pasting the other company's part id into `/parts/{id}/edit` shows the 404 page
- [ ] 2.7 The BOM add-line picker and the delivery form list only the user's own company's parts
