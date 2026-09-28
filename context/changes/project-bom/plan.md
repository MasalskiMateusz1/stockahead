# Projekty urządzeń i BOM (S-03) Implementation Plan

## Overview

Add device projects with a bill of materials (BOM) and documentation links (FR-007, FR-019). A manager creates, renames, deactivates and reactivates projects, manages BOM lines (active part × quantity per unit) and adds/removes documentation links; every logged-in user can browse projects and read their BOM and links. This is the data S-04 (`production-order-allocation`) will read to reserve parts.

## Current State Analysis

`main` has three feature areas: `account` (login, roles, `/setup`), `security`, and `parts` (catalog with locations, deactivate/reactivate, `CHECK (quantity >= 0)`). Migrations `V1`–`V3` exist. No project, BOM or link table or code exists. S-02 (`technician-accounts`) is being implemented by Codex **in this same working tree**, uncommitted: it adds `V4__normalize_account_emails.sql`, `Emails.java`, `AccountActivityFilter`, and edits `dashboard.html` and `messages.properties`.

## Desired End State

A manager can create a project, rename it, deactivate/reactivate it, add BOM lines choosing from active parts with a positive quantity per unit, change a line's quantity, remove a line, and add/remove documentation links (http/https URL + optional label). Any logged-in user sees the list of active projects and each project's detail page with its BOM and links; lines whose part was deactivated are visibly marked. Nothing is ever hard-deleted except individual BOM lines and links.

Verify by: `./mvnw verify` passes in the `project-bom` worktree (and again after rebasing onto a `main` that contains S-02); logging in as each role and exercising `/projects` confirms the role split.

### Key Discoveries:

- Pattern to copy: `src/main/java/pl/regavio/stockahead/parts/PartController.java` — one controller, no service layer, `TransactionTemplate` for writes, `DataIntegrityViolationException` caught outside the transaction and turned into a re-rendered form, `@PreAuthorize("hasRole('MANAGER')")` on every write, `ResponseStatusException(NOT_FOUND)` for unknown ids, `showInactive` honored only for managers (`PartController.java:53-66`).
- Parts are never deleted, only deactivated (`Part.active`), so `bom_lines.part_id` can use `ON DELETE RESTRICT` without blocking any existing operation.
- `context/foundation/lessons.md`: (1) every write to a uniqueness-guarded table catches `DataIntegrityViolationException` and re-renders the form; (2) every `@PreAuthorize` route needs a wrong-role 403 test.
- S-02 plan review established two conventions reused here: the DB constraint is the only duplicate check (no application pre-check, so the constraint path is testable deterministically), and each phase ships its own tests with a `./mvnw verify` gate.
- `th:href` does not filter `javascript:` URLs — link schemes must be whitelisted server-side.
- Views are plain Thymeleaf with no JavaScript or CSS framework (`parts-list.html`); messages come from `messages.properties` via `MessageSource`.
- Integration tests that need committed data are not `@Transactional` and clean up before/after each test (`src/test/java/pl/regavio/stockahead/parts/PartsCatalogIntegrationTests.java:100`); `accounts_single_manager_idx` allows only one manager row at a time across all test classes.

## What We're NOT Doing

- Hard delete of projects — replaced by deactivate/reactivate (decided in planning; S-04 orders will reference projects).
- Editing a link in place — delete and re-add instead.
- Any project field beyond `name`, `active`, `created_at` (no description, version, customer).
- Search or pagination on the project list — `data_volume: small`.
- File upload / PDF preview (FR-008, FR-009) — PRD Non-Goals for MVP.
- Blocking part deactivation when the part is used in a BOM — lines stay and are marked instead; no change to `PartController`.
- Deciding whether an inactive project or a BOM with an inactive part can be ordered — that belongs to S-04.
- Paste-in / bulk BOM entry — per-line forms only.
- Row locking — BOM edits never change stock or reservations, so the `AGENTS.md` locking rule does not apply here.

## Implementation Approach

New feature package `pl.regavio.stockahead.projects`: three entities (`Project` owning `BomLine` and `ProjectLink` collections), one repository, three controllers split by concern (`ProjectController`, `ProjectBomController`, `ProjectLinkController`) sharing one package-private helper that fills the model for the detail page, and four templates. One Flyway migration (`V5`) creates all three tables. Each phase ships its own integration tests.

## Critical Implementation Details

**Parallel work with Codex (S-02).** Codex is editing this working tree. Implement S-03 in a separate worktree and branch: `git worktree add ../stockahead-project-bom -b project-bom main`, then copy `context/changes/project-bom/` into it (the folder is untracked here) and run `/10x-implement` from the worktree. Never stage or commit S-03 work from the main checkout.

**Migration number and merge order.** S-02 owns `V4`; this slice uses `V5__create_projects.sql`. Flyway runs with `outOfOrder=false`, so a `V5` applied in production before `V4` would make S-02's deploy fail validation. Merge `project-bom` into `main` only after S-02 is on `main`: rebase onto it, re-run `./mvnw verify`, then merge. If S-03 must ship before S-02, rename this migration to `V4__create_projects.sql` before merging and tell Codex to renumber its migration to `V5`.

**Shared files.** `dashboard.html` and `messages.properties` are edited by both slices. Changes here are append-only (one link paragraph, new `projects.*` keys), so the rebase conflict is resolved by keeping both sides.

**Test fixtures compatible with S-02.** After S-02 merges, logins match emails in canonical form and an activity filter re-checks every request. Seed accounts with lowercase, trimmed, class-unique emails and `active = true` so the tests pass both before and after the rebase.

**Detail page errors across controllers.** BOM and link validation errors re-render `project-detail` (not a separate form page). A package-private `ProjectDetailModel` component fills the page model (project, lines with part name/active flag, links, active parts for the select, `error`, and submitted values) so all three controllers render it the same way; load it in a fresh read after any rolled-back write, never from the failed managed entity.

## Phase 1: Schema & domain model

### Overview

Create the three tables and the JPA layer that maps them, with DB constraints that guard every invariant the controllers rely on.

### Changes Required:

#### 1. Migration

**File**: `src/main/resources/db/migration/V5__create_projects.sql`

**Intent**: Create projects, BOM lines and documentation links together; enforce name uniqueness, one line per part per project, positive quantities, no dangling part references, and http/https-only URLs at the DB level.

**Contract**:
```sql
CREATE TABLE projects (
	id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
	name VARCHAR(255) NOT NULL UNIQUE,
	active BOOLEAN NOT NULL DEFAULT TRUE,
	created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE bom_lines (
	id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
	project_id BIGINT NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
	part_id BIGINT NOT NULL REFERENCES parts (id) ON DELETE RESTRICT,
	quantity_per_unit INT NOT NULL CHECK (quantity_per_unit > 0),
	UNIQUE (project_id, part_id)
);

CREATE TABLE project_links (
	id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
	project_id BIGINT NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
	url VARCHAR(2048) NOT NULL CHECK (url ~* '^https?://'),
	label VARCHAR(255)
);
```

#### 2. Entities and repository

**Files**: `src/main/java/pl/regavio/stockahead/projects/Project.java`, `BomLine.java`, `ProjectLink.java`, `ProjectRepository.java`

**Intent**: `Project` owns `bomLines` and `links` (`@OneToMany(mappedBy = "project", cascade = CascadeType.ALL, orphanRemoval = true)`) with `add…`/`remove…` helpers that maintain both sides, following `Part`/`PartLocation`. Plain accessors, no Lombok. `BomLine.part` is a `@ManyToOne` to `pl.regavio.stockahead.parts.Part`.

**Contract**: `Project` — `id`, `name`, `active` (default `true`), `createdAt`, `bomLines` (`List<BomLine>`), `links` (`List<ProjectLink>`). `BomLine` — `id`, `project`, `part`, `quantityPerUnit` (int). `ProjectLink` — `id`, `project`, `url`, `label` (nullable). `ProjectRepository` adds a list query ordered by name: `showInactive = false` returns active projects only, `showInactive = true` returns active and inactive together (same union semantics as `PartRepository.search`). `bom_lines` (`project_id`, `part_id`, `quantity_per_unit`) is the surface S-04 will read — keep these names.

#### 3. Schema tests

**File**: `src/test/java/pl/regavio/stockahead/projects/ProjectSchemaTests.java`

**Intent**: Prove the DB constraints exist independently of controller validation.

**Contract**: `@Import(TestcontainersConfiguration.class) @SpringBootTest`, not `@Transactional`; direct `JdbcTemplate` inserts, each in its own statement, with cleanup of `project_links`, `bom_lines`, `projects`, `part_locations`, `parts` before and after each test. Cases: `quantity_per_unit = 0` rejected; second line with the same `(project_id, part_id)` rejected; deleting a part referenced by a BOM line rejected; `url = 'javascript:alert(1)'` rejected; `url = 'HTTPS://example.com'` accepted; duplicate project name rejected.

### Success Criteria:

#### Automated Verification:

- `ProjectSchemaTests` passes: zero/negative quantity, duplicate BOM line, deleting a referenced part, non-http(s) URL and duplicate project name are rejected by PostgreSQL.
- `./mvnw verify` passes — `V5` applies cleanly on top of `V1`–`V3` and Hibernate `validate` accepts the entities.

---

## Phase 2: Projects — list, create, rename, deactivate, detail

### Overview

Project lifecycle screens: everyone browses, the manager creates, renames, deactivates and reactivates. The detail page shows the (still empty) BOM and links read-only.

### Changes Required:

#### 1. Controller and detail model

**Files**: `src/main/java/pl/regavio/stockahead/projects/ProjectController.java`, `src/main/java/pl/regavio/stockahead/projects/ProjectDetailModel.java`

**Intent**: Follow `PartController`: manual validation of the trimmed name (non-blank, ≤ 255), writes inside `TransactionTemplate`, the DB `UNIQUE` constraint as the only duplicate-name check (catch `DataIntegrityViolationException` after rollback, re-render the form with a friendly error). `ProjectDetailModel` builds the detail page model described in "Critical Implementation Details".

**Contract**:
- `GET /projects?showInactive=` — any authenticated user; renders `projects-list`. `showInactive` honored only for managers.
- `GET /projects/{id}` — any authenticated user; renders `project-detail`. An inactive project is 404 for a technician and visible for a manager. Unknown id → 404.
- `GET /projects/new`, `POST /projects` (`name`) — manager; success redirects to `/projects/{newId}`.
- `GET /projects/{id}/edit`, `POST /projects/{id}` (`name`) — manager; rename only; success redirects to `/projects/{id}`.
- `POST /projects/{id}/deactivate` → redirect `/projects`; `POST /projects/{id}/reactivate` → redirect `/projects?showInactive=true` — manager.
- Every manager route has `@PreAuthorize("hasRole('MANAGER')")`.

#### 2. Templates, dashboard and messages

**Files**: `src/main/resources/templates/projects-list.html`, `projects-new.html`, `projects-edit.html`, `project-detail.html`, `src/main/resources/templates/dashboard.html`, `src/main/resources/messages.properties`

**Intent**: Plain forms in the style of the `parts-*` templates. The list shows name, status and (manager only, `sec:authorize`) "Dodaj projekt", "pokaż nieaktywne" and per-row edit/deactivate/reactivate. The detail page shows name, status, a BOM table (Część / Ilość na sztukę) and a links list, both empty-state friendly. Dashboard gains one `<p><a th:href="@{/projects}">Projekty</a></p>` visible to both roles, next to the parts link; `messages.properties` gains `projects.*` keys only.

**Contract**: POST forms include CSRF (Thymeleaf `th:action` adds it). Error paragraph via `th:if="${error}"`, submitted name re-populated.

#### 3. Tests

**File**: `src/test/java/pl/regavio/stockahead/projects/ProjectIntegrationTests.java`

**Intent**: HTTP-level coverage of the lifecycle and role split.

**Contract**: `@Import(TestcontainersConfiguration.class) @SpringBootTest @AutoConfigureMockMvc`, not `@Transactional`; `formLogin()`/`csrf()` like `PartsCatalogIntegrationTests`; fixtures committed via `TransactionTemplate`; cleanup before/after each test deletes `project_links`, `bom_lines`, `projects`, `part_locations`, `parts`, then this class's accounts (lowercase, class-unique emails); persisted state re-read after each request.

### Success Criteria:

#### Automated Verification:

- Manager creates a project → redirect to its detail page, row persisted with `active = true`; blank or > 255-character name re-renders the form with an error and no row.
- Duplicate project name on create and on rename returns the form with a friendly error (DB constraint path, no 500) and leaves the original rows unchanged.
- A deactivated project is excluded from the default list, listed together with active projects for a manager with `showInactive=true`, still excluded for a technician with `showInactive=true`, 404 on its detail page for a technician, and back in the default list after reactivation.
- Both roles get 200 on `GET /projects` and on an active project's `GET /projects/{id}`; a technician gets 403 on `GET /projects/new`, `POST /projects`, `GET /projects/{id}/edit`, `POST /projects/{id}`, `POST /projects/{id}/deactivate`, `POST /projects/{id}/reactivate`.
- `./mvnw verify` passes.

#### Manual Verification:

- As a manager: the dashboard link opens `/projects`; create, rename, deactivate and reactivate a project; "Dodaj projekt" and "pokaż nieaktywne" are visible. As a technician: the list and detail page load, and no manager controls are shown.

---

## Phase 3: BOM lines

### Overview

The manager builds a project's BOM on its detail page with per-line forms; lines on deactivated parts stay and are marked.

### Changes Required:

#### 1. Controller

**File**: `src/main/java/pl/regavio/stockahead/projects/ProjectBomController.java`

**Intent**: Add a line (active part + quantity), change a line's quantity, remove a line. Validation errors and constraint violations re-render `project-detail` through `ProjectDetailModel` with the error and the submitted values; success redirects to `/projects/{id}`. The `UNIQUE (project_id, part_id)` constraint is the only duplicate check.

**Contract** (all `@PreAuthorize("hasRole('MANAGER')")`):
- `POST /projects/{id}/bom` (`partId`, `quantityPerUnit`) — `quantityPerUnit` must parse to an integer ≥ 1; `partId` must name an existing **active** part, otherwise the error "część nieaktywna lub nie istnieje" and no row; duplicate part → error telling the manager to change the existing line's quantity.
- `POST /projects/{id}/bom/{lineId}` (`quantityPerUnit`) — same quantity rule; allowed even when the line's part is inactive.
- `POST /projects/{id}/bom/{lineId}/delete` — removes the line (orphan removal).
- Unknown project, unknown line, or a line belonging to another project → 404. Edits are allowed on inactive projects too.

#### 2. Detail template

**File**: `src/main/resources/templates/project-detail.html`, `src/main/resources/messages.properties`

**Intent**: For managers (`sec:authorize`), an "add line" form with a `<select>` of active parts ordered by name and a quantity input, and per-row quantity-change and remove forms. For everyone, each line shows part name and quantity per unit; a line whose part is inactive shows a "nieaktywna" marker. New keys go under `projects.bom.*`.

**Contract**: The select lists only active parts (the server check still enforces it for tampered requests).

#### 3. Tests

**File**: `src/test/java/pl/regavio/stockahead/projects/ProjectBomIntegrationTests.java`

**Intent**: Same shape and isolation as `ProjectIntegrationTests`.

**Contract**: Fixtures: one project, active parts, one inactive part.

### Success Criteria:

#### Automated Verification:

- Adding a line with an active part and quantity 3 persists one `bom_lines` row and the detail page shows it.
- Adding the same part a second time returns the detail page with a friendly error (DB constraint path, no 500) and exactly one row remains.
- Quantity `0`, `-1` and `abc` on add and on change are rejected with an error and no change in the database.
- Adding an inactive or nonexistent part is rejected with an error and no row; the select offers only active parts.
- A line whose part was deactivated after it was added stays in the BOM, is rendered with the "nieaktywna" marker, and its quantity can still be changed.
- Changing a quantity and removing a line persist; a `lineId` belonging to another project returns 404 and changes nothing.
- A technician gets 403 on `POST /projects/{id}/bom`, `POST /projects/{id}/bom/{lineId}` and `POST /projects/{id}/bom/{lineId}/delete`.
- `./mvnw verify` passes.

#### Manual Verification:

- As a manager: build a BOM of three parts, change one quantity, remove one line; deactivate one of the parts in `/parts` and confirm the project page marks its line. As a technician: the BOM is visible without any edit forms.

---

## Phase 4: Documentation links

### Overview

The manager adds and removes documentation links; everyone can open them.

### Changes Required:

#### 1. Controller

**File**: `src/main/java/pl/regavio/stockahead/projects/ProjectLinkController.java`

**Intent**: Add and delete links with server-side URL validation so a stored link can never execute script when rendered.

**Contract** (all `@PreAuthorize("hasRole('MANAGER')")`):
- `POST /projects/{id}/links` (`url`, `label`) — `url` trimmed, required, ≤ 2048 characters, parses as `java.net.URI` with scheme `http` or `https` (case-insensitive) and a non-empty host; `label` trimmed, blank stored as `null`, ≤ 255 characters. Errors re-render `project-detail` with the submitted values.
- `POST /projects/{id}/links/{linkId}/delete` — removes the link.
- Unknown project, unknown link, or a link belonging to another project → 404.

#### 2. Detail template

**File**: `src/main/resources/templates/project-detail.html`, `src/main/resources/messages.properties`

**Intent**: Each link renders as `<a th:href="${link.url}" target="_blank" rel="noopener noreferrer">` with the label, or the URL itself when the label is empty. Managers get an add form (URL + optional label) and a delete button per link. New keys go under `projects.links.*`.

**Contract**: No other rendering path for `url`.

#### 3. Tests

**File**: `src/test/java/pl/regavio/stockahead/projects/ProjectLinkIntegrationTests.java`

**Intent**: Same shape and isolation as the other project test classes.

### Success Criteria:

#### Automated Verification:

- Adding a link with a label persists it and the detail page renders an anchor with that label, `target="_blank"` and `rel="noopener noreferrer"`; a link without a label renders the URL as its text.
- `javascript:alert(1)`, `ftp://example.com/x`, `http://` (no host), a blank URL and a URL over 2048 characters are rejected with an error and no row.
- Deleting a link removes it; a `linkId` belonging to another project returns 404 and changes nothing.
- A technician sees the links on the detail page and gets 403 on `POST /projects/{id}/links` and `POST /projects/{id}/links/{linkId}/delete`.
- `./mvnw verify` passes.

#### Manual Verification:

- As a manager: add a schematic link with a label and one without, open both in a new tab, delete one. As a technician: the links open, and no add/delete controls are shown.
- After S-02 is on `main`: rebase `project-bom`, resolve `dashboard.html`/`messages.properties` by keeping both sides, confirm the migration is still `V5` with S-02's `V4` present, and `./mvnw verify` passes before merging.

---

## Testing Strategy

### Unit Tests:

- None — no logic is isolated from the database and HTTP layer, matching the `parts` precedent.

### Integration Tests:

- `ProjectSchemaTests` (Phase 1): DB constraints via `JdbcTemplate`.
- `ProjectIntegrationTests` (Phase 2), `ProjectBomIntegrationTests` (Phase 3), `ProjectLinkIntegrationTests` (Phase 4): MockMvc against Testcontainers PostgreSQL, not `@Transactional`, cleanup before/after each test, a 403 test for every manager route.
- Regression: the existing `account`, `security` and `parts` suites keep passing, before and after the rebase onto S-02.

### Manual Testing Steps:

1. As a manager, create a project, add three BOM lines and two links, rename the project.
2. Deactivate one of the BOM's parts in `/parts`; confirm the project page marks that line and the add-line select no longer offers the part.
3. As a technician, open the project list and detail page; confirm the BOM and links are visible and no edit controls exist.
4. As a manager, deactivate the project; confirm the technician no longer sees it and the manager does with "pokaż nieaktywne"; reactivate it.

## Performance Considerations

Detail and list pages lazy-load BOM lines, parts and links per project (N+1), accepted for `data_volume: small` as in the parts catalog.

## Migration Notes

New tables only; no existing data changes. `V5` must land on `main` after S-02's `V4` (see "Critical Implementation Details").

## References

- PRD: `context/foundation/prd.md` — FR-007, FR-019, Access Control, Non-Goals.
- Roadmap: `context/foundation/roadmap.md` — S-03 `project-bom`, prerequisites F-01, S-01.
- Pattern: `src/main/java/pl/regavio/stockahead/parts/PartController.java`, `Part.java`, `PartRepository.java`, `src/main/resources/templates/parts-list.html`.
- Test pattern: `src/test/java/pl/regavio/stockahead/parts/PartsCatalogIntegrationTests.java`.
- Parallel slice: `context/changes/technician-accounts/plan.md` (Codex; owns `V4`).
- Lessons: `context/foundation/lessons.md`.

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles. See `references/progress-format.md`.

### Phase 1: Schema & domain model

#### Automated

- [x] 1.1 `ProjectSchemaTests` passes: zero/negative quantity, duplicate BOM line, deleting a referenced part, non-http(s) URL and duplicate project name are rejected by PostgreSQL.
- [x] 1.2 `./mvnw verify` passes — `V5` applies cleanly on top of `V1`–`V3` and Hibernate `validate` accepts the entities.

### Phase 2: Projects — list, create, rename, deactivate, detail

#### Automated

- [ ] 2.1 Manager creates a project → redirect to its detail page, row persisted with `active = true`; blank or > 255-character name re-renders the form with an error and no row.
- [ ] 2.2 Duplicate project name on create and on rename returns the form with a friendly error (DB constraint path, no 500) and leaves the original rows unchanged.
- [ ] 2.3 A deactivated project is excluded from the default list, listed together with active projects for a manager with `showInactive=true`, still excluded for a technician with `showInactive=true`, 404 on its detail page for a technician, and back in the default list after reactivation.
- [ ] 2.4 Both roles get 200 on `GET /projects` and on an active project's `GET /projects/{id}`; a technician gets 403 on every manager route.
- [ ] 2.5 `./mvnw verify` passes.

#### Manual

- [ ] 2.6 Manager can create, rename, deactivate and reactivate from the UI; technician sees list and detail with no manager controls.

### Phase 3: BOM lines

#### Automated

- [ ] 3.1 Adding a line with an active part and quantity 3 persists one `bom_lines` row and the detail page shows it.
- [ ] 3.2 Adding the same part a second time returns the detail page with a friendly error (DB constraint path, no 500) and exactly one row remains.
- [ ] 3.3 Quantity `0`, `-1` and `abc` on add and on change are rejected with an error and no change in the database.
- [ ] 3.4 Adding an inactive or nonexistent part is rejected with an error and no row; the select offers only active parts.
- [ ] 3.5 A line whose part was deactivated stays, is marked "nieaktywna", and its quantity can still be changed.
- [ ] 3.6 Changing a quantity and removing a line persist; a `lineId` of another project returns 404 and changes nothing.
- [ ] 3.7 A technician gets 403 on all three BOM routes.
- [ ] 3.8 `./mvnw verify` passes.

#### Manual

- [ ] 3.9 Manager builds, edits and trims a BOM; a deactivated part's line is marked; technician sees the BOM without edit forms.

### Phase 4: Documentation links

#### Automated

- [ ] 4.1 A labelled link renders with its label, `target="_blank"` and `rel="noopener noreferrer"`; an unlabelled link renders its URL.
- [ ] 4.2 `javascript:`, `ftp:`, host-less, blank and over-2048-character URLs are rejected with no row.
- [ ] 4.3 Deleting a link removes it; a `linkId` of another project returns 404 and changes nothing.
- [ ] 4.4 A technician sees links and gets 403 on both link routes.
- [ ] 4.5 `./mvnw verify` passes.

#### Manual

- [ ] 4.6 Manager adds, opens and deletes links; technician opens links with no add/delete controls.
- [ ] 4.7 After S-02 is on `main`: rebase, keep both sides in shared files, confirm `V4` + `V5`, `./mvnw verify` passes before merging.
