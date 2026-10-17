# Company-Scoped Projects Implementation Plan

## Overview

This is roadmap item S-02 of milestone M-2. Projects, their BOMs and their documentation links stay within one company. `projects` and `bom_lines` get a `company_id` column, and project names become unique per company. Database composite FKs tie each BOM line's project and part to the same company. Every project route, plus the order form's project picker and its project lookup, is scoped to the logged-in user's company. A user of company B gets 404 for company A's project ids and cannot add A's part to a BOM. This implements FR-028 for projects and the guessed-id guardrail from prd-v2 § Success Criteria › Guardrails.

## Current State Analysis

S-01 (`company-scoped-parts`, archived 2026-10-06) scoped the parts catalog. Projects have no company concept yet:

- `projects.name` is globally `UNIQUE` (`V5__create_projects.sql:3`, `@Column(unique = true)` at `projects/Project.java:24`). `bom_lines` (`V5:8-14`) and `project_links` (`V5:16-21`) reference only `project_id`, and `bom_lines.part_id` references only `parts(id)`.
- `ProjectRepository.list(showInactive)` (`projects/ProjectRepository.java:11-16`) is global.
- Ten bare project lookups never check the company:
  - `ProjectController.java:120` (`existsById`), `:132` and `:162`
  - `ProjectBomController.java:63` (`existsById`), `:83` and `:163` (`findLine`)
  - `ProjectLinkController.java:63` (`existsById`), `:84` and `:119` (`findLink`)
  - `ProjectDetailModel.java:74`

  `GET /projects` and `GET /projects/{id}` are open to technicians too.
- **The cross-company reference hole:** `ProjectBomController.java:85` calls `partRepository.findById(parsedPartId)`, which accepts another company's part id. A missing or inactive part re-renders with `projects.bom.error.partUnavailable`.
- `ProjectController.create`, `deactivate`/`reactivate`, `ProjectBomController.removeLine` and `ProjectLinkController.removeLink` don't take the principal.
- Callers outside the package:
  - `OrderController.populateFormModel` (`orders/OrderController.java:725-726`) lists `projectRepository.list(false)` for the new-order picker. `newForm` (`:498`) and the create-error path (`:732`) call it.
  - `OrderController.create` (`:544`) loads the project with a bare `findById(parsedProjectId)`, so B could order A's project and reserve A's parts. A missing or inactive project returns `orders.error.projectUnavailable`.
- About 28 test files insert projects or BOM lines by JDBC (`INSERT INTO projects …`, `INSERT INTO bom_lines …`). All of them break once the columns are `NOT NULL`. Tests delete `bom_lines`/`projects` themselves before `companyFixtures.cleanUp()` (e.g. `parts/PartsCatalogIntegrationTests.java:107-108`).
- The V14 guard (`V14__create_companies.sql:3-13`) requires `projects`, `bom_lines` and `project_links` to be empty. Multi-company work stays on its branch, so V16 always runs against empty tables and no backfill is needed.

## Desired End State

- `projects` and `bom_lines` carry a non-null `company_id`. Project names are `UNIQUE (company_id, name)`.
- The database rejects a BOM line whose project and part belong to different companies.
- `project_links` gets no company column. Links are reached only through their company-scoped project.
- `GET /projects` lists only the user's own company's projects. `GET /projects/{id}`, `POST /projects/{id}`, `/deactivate`, `/reactivate`, `/bom`, `/bom/{lineId}`, `/bom/{lineId}/delete`, `/links` and `/links/{linkId}/delete` answer 404 for another company's project id and change nothing.
- Adding another company's part id to a BOM gets the same `partUnavailable` re-render as a missing part, and no line is written.
- The new-order picker lists only the user's own company's active projects. Posting another company's project id to `POST /orders` gets the same `projectUnavailable` error as a missing project, and no order is created.
- `./mvnw verify` is green.

To verify, run the Phase 2 two-company tests and a manual `spring-boot:test-run` with two companies.

### Key Discoveries:

- Patterns to copy from S-01: `PartRepository.findByIdAndCompanyId` with `@AuthenticationPrincipal CompanyUser principal`, the composite FK `part_locations (part_id, company_id) → parts (id, company_id)` in `V15__company_scoped_parts.sql:16-19`, and the test class `parts/PartsCompanyIsolationIntegrationTests.java`.
- V15 already added `parts_id_company_key UNIQUE (id, company_id)` (`V15:13`). That's the target of the new `bom_lines (part_id, company_id)` FK.
- The V5 constraints have Postgres default names: `projects_name_key`, `bom_lines_project_id_fkey`, `bom_lines_part_id_fkey`. V16 drops them by those names.
- `ProjectController` maps any `DataIntegrityViolationException` to `projects.error.duplicateName`, and `ProjectBomController.addLine` maps any to `projects.bom.error.duplicatePart`. Neither looks at constraint names, so renaming the unique constraint is safe.
- `CompanyFixtures.cleanUp()` (`CompanyFixtures.java:126-141`) deletes parts, accounts and companies. With `projects.company_id … ON DELETE RESTRICT`, it must delete the company's projects first. `bom_lines` and `project_links` cascade from projects.

## What We're NOT Doing

- No Hibernate `@TenantId` or `@Filter`. Scoping uses an explicit `companyId` parameter, as in F-01 and S-01.
- No `company_id` on `project_links`. It is reached only through its project, and AGENTS.md doesn't list it.
- No `company_id` on `orders` or `order_lines`, and no composite FK from `orders.project_id`. That's S-03.
- No scoping of order routes beyond the project picker and the project lookup in `OrderController.create`. The assignee picker and lookup (`accountRepository.findAssignable()`/`findById` at `:548`, `:727`), order by-id routes and allocation stay for S-03/S-04.
- No new project delete. Projects are deactivated, as they are today.
- No UI change.
- No new 403 tests beyond the existing wrong-role tests for project routes.

## Implementation Approach

The company flows from `CompanyUser` to the controller and is passed as an explicit argument. `ProjectRepository` methods take `companyId`. A miss on `(id, companyId)` is treated exactly like a missing id: 404 on project routes, `partUnavailable` for a BOM part, `projectUnavailable` for an order's project.

Phase 1 is the mechanical fallout of the `NOT NULL` columns: schema, entities, setting the company on project creation, and every test insert. All queries stay global. Phase 2 does the semantic scoping and adds the two-company tests that prove it.

## Phase 1: Schema, entities and test fixtures

### Overview

Add the company columns, per-company name uniqueness and the composite FKs. Set the company on project creation, and give every test that seeds projects or BOM lines a company. Queries stay global and behavior is unchanged. The build is green.

### Changes Required:

#### 1. V16 migration

**File**: `src/main/resources/db/migration/V16__company_scoped_projects.sql`

**Intent**: Give projects and BOM lines a company. Make project names unique per company, and make a BOM line linking two companies impossible at the database level. The tables are guaranteed empty (V14 guard), so the columns can be `NOT NULL` with no backfill.

**Contract**:
- `projects.company_id BIGINT NOT NULL REFERENCES companies (id) ON DELETE RESTRICT`.
- Drop `projects_name_key` and add `projects_company_name_key UNIQUE (company_id, name)`. Its leading column also serves lookups by company, so there's no separate company index.
- Add `projects_id_company_key UNIQUE (id, company_id)` as the target for composite FKs. S-03 can reuse it for `orders`.
- `bom_lines.company_id BIGINT NOT NULL`. Drop `bom_lines_project_id_fkey` and `bom_lines_part_id_fkey`, then add:
  - `bom_lines_project_company_fkey FOREIGN KEY (project_id, company_id) REFERENCES projects (id, company_id) ON DELETE CASCADE`
  - `bom_lines_part_company_fkey FOREIGN KEY (part_id, company_id) REFERENCES parts (id, company_id) ON DELETE RESTRICT`

  The `ON DELETE` actions match V5.
- Keep `UNIQUE (project_id, part_id)` on `bom_lines` and the whole of `project_links` unchanged.

#### 2. Entities

**File**: `src/main/java/pl/regavio/stockahead/projects/Project.java`, `BomLine.java`

**Intent**: Map the new columns. A BOM line always takes its project's company.

**Contract**:
- Remove `unique = true` from `Project.name`.
- Add `@ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "company_id", nullable = false) Company company` to `Project`, with a getter and setter, exactly as on `Part` (`Part.java:31-33`).
- Update the `ProjectController` javadoc: project names are unique per company (`projects_company_name_key`), not globally.
- Map `BomLine`'s `company_id` as a plain `Long companyId` column, set from the project in `Project.addBomLine`, the same way `PartLocation` does it.
- Keep the existing `project` and `part` associations as the only writers of `project_id`/`part_id`. Mapping `company_id` as a plain column doesn't create a second writer.

#### 3. Project creation sets the company

**File**: `projects/ProjectController.java` (`create`)

**Intent**: Every new project belongs to its creator's company. Without this, the `NOT NULL` column fails every create.

**Contract**: `create` takes `@AuthenticationPrincipal CompanyUser principal` and calls `project.setCompany(companyRepository.getReferenceById(principal.companyId()))` before saving. The company comes only from the principal.

#### 4. Test fixtures

**File**: `src/test/java/pl/regavio/stockahead/CompanyFixtures.java`, plus every test that seeds projects or BOM lines (about 28 files under `projects/`, `orders/`, `parts/`, `purchasing/`)

**Intent**: Seeded projects and BOM lines belong to the test's existing company. New tests get helpers, and cleanup keeps working under the `RESTRICT` FK.

**Contract**:
- Add `CompanyFixtures.project(Company company, String name, boolean active)`. It commits and returns the `Project`.
- Add `CompanyFixtures.bomLine(Project project, Part part, int quantityPerUnit)`. It commits.
- `cleanUp()` deletes `projects WHERE company_id = ?` before the company's parts. `bom_lines` and `project_links` cascade. `orders.project_id` is `ON DELETE RESTRICT` (`V6__create_orders.sql:3`), so tests still delete their orders before calling `cleanUp()`. Update the `CompanyFixtures` class javadoc to list projects among what it removes and orders among what the test deletes first.
- Put the Phase 1 schema tests (1.2, 1.3) in `projects/ProjectSchemaTests.java`, following `parts/PartCompanySchemaTests.java`. Its existing `duplicateProjectNameIsRejected` keeps both projects in one company.
- In existing tests, add `company_id` to the JDBC inserts into `projects` and `bom_lines`, using the company the test already creates. Each BOM line's `company_id` equals its project's and its part's.
- `V10MigrationTests` and `V14MigrationTests` insert at their own migration version, where the columns don't exist yet. Leave them unchanged unless they migrate past V16.

### Success Criteria:

#### Automated Verification:

- `./mvnw verify` passes with V16 applied
- A test asserts that two companies can each hold a project named "Board X", while a second "Board X" in the same company violates the unique constraint
- A test asserts that a `bom_lines` row linking company A's project to company B's part is rejected by the database

#### Manual Verification:

- `./mvnw spring-boot:test-run`: the manager creates a project, adds a BOM line and a link, and creates an order for it, and all four succeed

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful before proceeding to the next phase.

---

## Phase 2: Scoped routes and the two-company tests

### Overview

Scope every project read and by-id lookup, the BOM part lookup, and the order form's project picker and lookup to the principal's company. Prove the isolation with two-company tests.

### Changes Required:

#### 1. ProjectRepository

**File**: `src/main/java/pl/regavio/stockahead/projects/ProjectRepository.java`

**Intent**: Every query that answers "which projects exist" sees only one company.

**Contract**:
- Add `findByIdAndCompanyId(Long id, Long companyId)` and `existsByIdAndCompanyId(Long id, Long companyId)`.
- `list(showInactive)` becomes `list(companyId, showInactive)` and filters `p.company.id = :companyId`.

#### 2. Project controllers and detail model

**File**: `projects/ProjectController.java`, `ProjectBomController.java`, `ProjectLinkController.java`, `ProjectDetailModel.java`

**Intent**: Project routes act only on the user's own company. A foreign project id is a 404, and a foreign part id in a BOM post is treated as unavailable.

**Contract**:
- Every handler takes `@AuthenticationPrincipal CompanyUser principal`, including `list`, `deactivate`, `reactivate`, `removeLine` and `removeLink`.
- All ten lookups listed in Current State Analysis become `findByIdAndCompanyId`/`existsByIdAndCompanyId(id, principal.companyId())`, with the existing `NOT_FOUND`. `findLine` and `findLink` take `companyId`.
- `ProjectDetailModel.load` uses `findByIdAndCompanyId(projectId, companyId)`. It already receives `companyId`.
- `ProjectBomController.addLine` loads the part with `partRepository.findByIdAndCompanyId(parsedPartId, principal.companyId()).filter(Part::isActive)`. A miss goes down the existing `partUnavailable` path, which returns 200 and re-renders the page.

#### 3. Order form's project picker and lookup

**File**: `src/main/java/pl/regavio/stockahead/orders/OrderController.java`

**Intent**: A manager can order only their own company's projects. The order form offers only those, and a foreign project id posted to `POST /orders` is rejected like a missing one. This closes the only path where a project leak becomes cross-company stock reservation.

**Contract**:
- `populateFormModel(model, companyId)` lists `projectRepository.list(companyId, false)`. `newForm` and `create` take `@AuthenticationPrincipal CompanyUser principal` (`create` has none today). `renderNewOrderError(model, error, form)` becomes `renderNewOrderError(model, companyId, error, form)`, and all six of its call sites in `create` pass `principal.companyId()`.
- `create` (`:544`) loads with `findByIdAndCompanyId(parsedProjectId, principal.companyId()).filter(Project::isActive)`. A miss returns the existing `orders.error.projectUnavailable` error.
- The assignee picker and lookup are unchanged (S-03).

#### 4. Two-company tests

**File**: `src/test/java/pl/regavio/stockahead/projects/ProjectsCompanyIsolationIntegrationTests.java` (new), following `parts/PartsCompanyIsolationIntegrationTests.java`

**Intent**: Prove the S-02 guardrail on every project route, on the cross-company BOM part reference and on the order's project.

**Contract**: Use `@Import({ TestcontainersConfiguration.class, CompanyFixtures.class })` with a real Postgres. Cases:
- Company B's manager gets 404 on each of these for A's project id: `GET /projects/{A}`, `POST /projects/{A}` (rename), `/deactivate`, `/reactivate`, `POST /projects/{A}/bom`, `POST /projects/{A}/bom/{A-line}`, `/bom/{A-line}/delete`, `POST /projects/{A}/links` and `/links/{A-link}/delete`. A's project name, active flag, BOM lines and links are unchanged in the DB.
- B's technician gets 404 on `GET /projects/{A}`.
- `GET /projects` and `?showInactive=true` for B's manager, and `GET /projects` for B's technician, never list A's projects.
- B can create a project named like A's. Renaming B's project to A's name succeeds, and renaming it to another B project's name still shows the duplicate-name error.
- B's manager posts A's active part id to `POST /projects/{B}/bom`. The response is 200 with the `partUnavailable` message, and no BOM line is written.
- `GET /orders/new` for B's manager doesn't list A's projects. Posting A's project id to `POST /orders` shows `projectUnavailable`, no order is created, and A's parts' reservations are unchanged.

#### 5. Docs

**File**: `context/foundation/roadmap.md`

**Intent**: Leave the S-03 hand-off explicit for its planner.

**Contract**: Add a sentence to the S-03 Risk entry: `projects_id_company_key (id, company_id)` exists, so `orders.project_id` can become a composite FK once `orders` carries `company_id`, and S-02 already scoped the project lookup and picker in `OrderController.create`.

### Success Criteria:

#### Automated Verification:

- `./mvnw verify` passes
- `ProjectsCompanyIsolationIntegrationTests` passes: B gets 404 on all nine `/projects/{id}…` routes with A's data unchanged, the list shows only the user's own company, and project names are reused across companies
- The cross-company BOM part test passes: A's part id posted to B's BOM shows `partUnavailable` and writes no line
- The order isolation test passes: the new-order picker lists only the user's own company's projects, and A's project id posted to `POST /orders` shows `projectUnavailable` and creates no order
- `grep -nE "projectRepository\.(findById|existsById)\(|partRepository\.findById\(" src/main/java/pl/regavio/stockahead/projects src/main/java/pl/regavio/stockahead/orders/OrderController.java` returns nothing

#### Manual Verification:

- With `spring-boot:test-run`, two companies each create a project "Board X". Each manager sees only their own project, and pasting the other company's project id into `/projects/{id}` shows the 404 page
- The new-order form lists only the user's own company's projects, and the BOM add-line picker lists only the user's own company's parts

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful before proceeding.

---

## Testing Strategy

### Unit Tests:

- No new pure unit tests. The behavior lives in SQL constraints and the security principal, and AGENTS.md requires real Postgres for these invariants.

### Integration Tests:

- Phase 1: per-company unique project name, and the composite FK rejecting a cross-company BOM line.
- Phase 2: `ProjectsCompanyIsolationIntegrationTests`, with the cases listed in Phase 2 § 4.
- Every existing projects, orders, parts and purchasing test keeps passing on single-company data.

### Manual Testing Steps:

1. `./mvnw spring-boot:test-run`, set up company A, log in as A's manager, and create project "Board X" with a BOM line and a link.
2. Create company B and its manager in the DB. Log in as B's manager, create "Board X", and confirm it succeeds.
3. As B, open `/projects/<A's id>` and confirm the 404.
4. As B, open `/orders/new` and confirm only B's projects appear. On B's project page, confirm the BOM picker lists only B's parts.

## Migration Notes

V16 assumes `projects`, `bom_lines` and `project_links` are empty. That holds because V14's guard required an empty database, and V14–V16 ship together from the multi-company branch. If V16 meets rows, `ADD COLUMN … NOT NULL` without a default fails and Flyway rolls back. The V14 wipe procedure in deployment.md applies.

## References

- Roadmap: `context/foundation/roadmap.md` § S-02
- PRD: `context/foundation/prd-v2.md` FR-028, Success Criteria › Guardrails
- Frame: `context/changes/multi-company-support/frame.md`
- S-01 plan: `context/archive/2026-10-06-company-scoped-parts/plan.md` (and its impl review F4: a composite-FK violation that the current code can't reach is mapped to a generic message)
- Pattern: `parts/PartRepository.findByIdAndCompanyId`, `V15__company_scoped_parts.sql:16-19`, `parts/PartsCompanyIsolationIntegrationTests.java`
- Lessons: `context/foundation/lessons.md` (DB constraint violations at write boundaries; wrong-role tests)

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles. See `references/progress-format.md`.

### Phase 1: Schema, entities and test fixtures

#### Automated

- [x] 1.1 `./mvnw verify` passes with V16 applied
- [x] 1.2 A test asserts that two companies can each hold a project named "Board X", while a second "Board X" in the same company violates the unique constraint
- [x] 1.3 A test asserts that a `bom_lines` row linking company A's project to company B's part is rejected by the database

#### Manual

- [x] 1.4 `./mvnw spring-boot:test-run`: the manager creates a project, adds a BOM line and a link, and creates an order for it, and all four succeed

### Phase 2: Scoped routes and the two-company tests

#### Automated

- [ ] 2.1 `./mvnw verify` passes
- [ ] 2.2 `ProjectsCompanyIsolationIntegrationTests` passes: B gets 404 on all nine `/projects/{id}…` routes with A's data unchanged, the list shows only the user's own company, and project names are reused across companies
- [ ] 2.3 The cross-company BOM part test passes: A's part id posted to B's BOM shows `partUnavailable` and writes no line
- [ ] 2.4 The order isolation test passes: the new-order picker lists only the user's own company's projects, and A's project id posted to `POST /orders` shows `projectUnavailable` and creates no order
- [ ] 2.5 `grep -nE "projectRepository\.(findById|existsById)\(|partRepository\.findById\(" src/main/java/pl/regavio/stockahead/projects src/main/java/pl/regavio/stockahead/orders/OrderController.java` returns nothing

#### Manual

- [ ] 2.6 With `spring-boot:test-run`, two companies each create a project "Board X". Each manager sees only their own project, and pasting the other company's project id into `/projects/{id}` shows the 404 page
- [ ] 2.7 The new-order form lists only the user's own company's projects, and the BOM add-line picker lists only the user's own company's parts
