# Kartoteka części z lokalizacjami (S-02) Implementation Plan

## Overview

Add the parts catalog: a `parts` + `part_locations` schema guarded by a `CHECK (quantity >= 0)` constraint, a search/browse screen open to every authenticated user (matches name or location text), and manager-only create/edit/deactivate/reactivate actions. Quantity is set once at creation and is not editable through this slice — later slices (delivery, correction, import, reservations) own subsequent quantity changes.

## Current State Analysis

The codebase has exactly one feature package so far: `pl.regavio.stockahead.account` (login, roles, `/setup`) plus `pl.regavio.stockahead.security` (Spring Security config). Two migrations exist — `V1__create_accounts.sql`, `V2__enforce_single_manager.sql` — no `parts`/`location` table or code exists yet. `context/foundation/roadmap.md` tracks this as slice `S-02`, prerequisite `S-01` (done, delivered as `auth-and-roles`).

### Key Discoveries:

- Feature-first package convention, established by `auth-and-roles` and confirmed in its plan brief: `pl.regavio.stockahead.account`, `.security` — this slice adds `pl.regavio.stockahead.parts`.
- Role enforcement is method-level only (`@PreAuthorize`), never URL-based — `src/main/java/pl/regavio/stockahead/security/SecurityConfig.java:21-23` only gates `authenticated()` at the URL level; `src/main/java/pl/regavio/stockahead/account/ManagerPingController.java:16` is the reference pattern (`@PreAuthorize("hasRole('MANAGER')")`).
- No service layer exists anywhere yet — `SetupController.java` validates and inserts a flat account, catching `DataIntegrityViolationException` (`src/main/java/pl/regavio/stockahead/account/SetupController.java:45-82`). Keep controller-based orchestration, but use an explicit `TransactionTemplate` boundary for the new aggregate writes; account insertion is not an aggregate-edit precedent.
- `context/foundation/lessons.md` records two rules that apply directly: (1) any write hitting a DB uniqueness constraint must catch `DataIntegrityViolationException` and re-render the form with a friendly error, never a raw 500; (2) every `@PreAuthorize`-gated route needs a test asserting 403 for the wrong role, not just a happy-path 200.
- `AGENTS.md`'s hard rule requires a DB `CHECK (quantity >= 0)` constraint in the migration, and row locking "before changing stock or reservations." This slice is the first to introduce the `quantity` column, so the `CHECK` constraint lands here per the roadmap's own risk note (`context/foundation/roadmap.md:120`) — but see "Critical Implementation Details" for why the locking half of that rule does not apply yet.
- FR-005's "zarezerwowane" / "dostępne" columns are intentionally 0 / `= quantity` until `S-04` introduces reservations — already settled by the roadmap (`context/foundation/roadmap.md:146`), not a decision made in this plan.
- No CSS framework or JavaScript exists in any template (`dashboard.html`, `login.html`, `setup.html` are plain HTML forms) — this plan does not introduce either.
- Existing test convention: `@Import(TestcontainersConfiguration.class) @SpringBootTest @AutoConfigureMockMvc @Transactional`, MockMvc + `formLogin()`/`csrf()`, a private `seedAccount(...)` helper — see `src/test/java/pl/regavio/stockahead/account/AuthenticationIntegrationTests.java`. The catalog tests deliberately omit the enclosing test transaction so request commits, rollbacks, and subsequent database reads use production-like boundaries.

## Desired End State

A manager can add a part (name, initial quantity, one or more locations), edit its name/locations, deactivate it, and reactivate it. Any logged-in user (manager or technician) can search the catalog by name or location text and see each part's quantity, reserved (stub `0`), available, and locations. Deactivated parts are hidden from the default view; a manager can filter to see and reactivate them.

Verify by: `./mvnw verify` passes (migration applies cleanly, all tests green); logging in as each role and exercising `/parts` confirms the role split above.

## What We're NOT Doing

- Editing a part's quantity after creation through this catalog — quantity changes exclusively through `S-04` (reservations), `S-10` (delivery), `S-11` (correction, with reason), `S-12` (import) once they exist.
- Establishing the F-01 concurrent-transaction test proving the `CHECK` constraint holds under a race — deferred to the first slice with a real concurrent quantity-mutation path (see "Critical Implementation Details").
- Hard delete of parts — replaced by deactivate/reactivate, matching the FR-002 account pattern.
- Any field not named in PRD (description, SKU, unit of measure, etc.) — only `name`, `quantity`, `locations`, `active`.
- Blocking delete/deactivate on a part referenced by a project BOM — `S-03` (projects-and-bom) doesn't exist yet; that slice will need its own guard when it lands.
- Pagination on the parts list — `tech-stack.md` declares `target_scale.data_volume: small`; a flat filtered list is enough for MVP.
- Eager-fetching locations in the search query — the `LEFT JOIN` in `PartRepository.search` filters but doesn't `JOIN FETCH`, so rendering the locations column lazy-loads per part (N+1). Accepted tradeoff given the same small-data-volume assumption above; revisit if `S-12`'s CSV import meaningfully grows the table.
- File uploads / PDF preview (FR-008/FR-009) — explicit PRD Non-Goals, unrelated to this slice.
- Any change to `SecurityConfig.java` — `/parts/**` needs only "authenticated," already covered by `anyRequest().authenticated()`; manager-only actions are `@PreAuthorize` only, matching the established pattern.

## Implementation Approach

New package `pl.regavio.stockahead.parts` holding two entities (`Part`, `PartLocation`), one repository with a JPQL search query joining both, one controller handling both the public search screen and the manager-only CRUD actions (no service layer; explicit `TransactionTemplate` for aggregate writes), and three Thymeleaf templates. One Flyway migration creates both tables together since they're introduced as a single feature.

## Critical Implementation Details

**Quantity-mutation guard deferred, not skipped.** `AGENTS.md` requires locking the part row "before changing stock or reservations." This slice has no operation that changes an existing part's `quantity` — it's set once at INSERT time (no concurrent-update race to guard) and the edit/deactivate/reactivate actions never touch it. So no `@Lock(PESSIMISTIC_WRITE)` code and no concurrent-transaction test belong in this slice; adding them now would guard against an update path that doesn't exist yet. The `CHECK (quantity >= 0)` constraint still lands in this migration regardless (defense-in-depth, and to avoid a corrective migration later per the roadmap's own note) — Phase 4 includes a direct-insert test proving the constraint exists, but not a concurrency test. The locking code and its concurrent-transaction test belong in whichever slice introduces the first real quantity-mutating update (most likely `S-10` delivery-receipt or `S-11` stock-correction).

**Multi-location input without JavaScript.** No template in this codebase uses client-side scripting. Rather than a fixed number of location inputs or introducing JS for a dynamic add/remove list, the create/edit forms use a single `<textarea>` with one location per line; the controller splits on newline, trims each line, drops blanks, and rejects the submission if any two trimmed lines are identical (case-sensitive, matching the DB's `UNIQUE (part_id, location)` byte comparison).

## Phase 1: Schema & domain model

### Overview

Create the `parts` and `part_locations` tables and the JPA layer that maps them, including the search query used by Phase 2.

### Changes Required:

#### 1. Migration

**File**: `src/main/resources/db/migration/V3__create_parts_catalog.sql`

**Intent**: Create both tables together since they're introduced as one feature; guard stock non-negativity at the DB level per `AGENTS.md`, and part-name / part+location uniqueness so the app-level duplicate checks in Phase 3 have a DB-level backstop.

**Contract**:
```sql
CREATE TABLE parts (
	id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
	name VARCHAR(255) NOT NULL UNIQUE,
	quantity INT NOT NULL CHECK (quantity >= 0),
	active BOOLEAN NOT NULL DEFAULT TRUE,
	created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE part_locations (
	id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
	part_id BIGINT NOT NULL REFERENCES parts (id) ON DELETE CASCADE,
	location VARCHAR(255) NOT NULL,
	UNIQUE (part_id, location)
);
```

#### 2. Entities and repository

**Files**: `src/main/java/pl/regavio/stockahead/parts/Part.java`, `PartLocation.java`, `PartRepository.java`

**Intent**: `Part` owns its `locations` (`@OneToMany(mappedBy = "part", cascade = CascadeType.ALL, orphanRemoval = true)`). Initialize the collection and reconcile it in place: retain matching child rows and IDs, remove missing rows with orphan removal, and add only new locations. Provide `addLocation`/`removeLocation` helpers that maintain both sides of the relationship; do not replace the managed collection or clear and recreate retained rows. Keep plain accessors (no Lombok, no builder). `PartRepository` supplies name lookup and the search query used below.

**Contract**: `Part` fields — `id`, `name` (unique), `quantity` (int), `active` (boolean, default `true`), `createdAt`, `locations` (`List<PartLocation>`). `PartLocation` fields — `id`, `part` (`@ManyToOne`), `location` (String). `PartRepository` adds `Optional<Part> findByName(String name)` and a search query joining `part_locations`. `showInactive = false` returns only active parts; `showInactive = true` is a union, not a replacement — it adds inactive parts to the results alongside active ones, and never hides active parts:

```java
@Query("""
	SELECT DISTINCT p FROM Part p LEFT JOIN p.locations l
	WHERE (:showInactive = true OR p.active = true)
	AND (:q = '' OR LOWER(p.name) LIKE LOWER(CONCAT('%', :q, '%'))
		OR LOWER(l.location) LIKE LOWER(CONCAT('%', :q, '%')))
	ORDER BY p.name
	""")
List<Part> search(@Param("q") String q, @Param("showInactive") boolean showInactive);
```

### Success Criteria:

#### Automated Verification:
- `./mvnw verify` — Flyway migration `V3` applies cleanly against the Testcontainers Postgres and the app context loads.

#### Manual Verification:
- (none — schema-only phase, covered by Phase 4's tests)

---

## Phase 2: Search & browse

### Overview

A `/parts` screen any authenticated user can reach and search, reachable from the dashboard.

### Changes Required:

#### 1. Controller — search endpoint

**File**: `src/main/java/pl/regavio/stockahead/parts/PartController.java`

**Intent**: List/search endpoint open to any authenticated user (no `@PreAuthorize` needed — `SecurityConfig`'s `anyRequest().authenticated()` already covers it, matching `HomeController.dashboard()`). `showInactive` is honored only when the caller is a manager; otherwise it's silently ignored so a technician always sees the active catalog. When honored, `showInactive=true` adds inactive parts to the results alongside the active ones — it is a reveal, not a filter swap, so a manager never loses sight of the active catalog while reviewing deactivated parts. This is why `POST /parts/{id}/reactivate` can safely redirect to `/parts?showInactive=true`: the just-reactivated part (now active) still appears there.

**Contract**: `GET /parts?q=&showInactive=` → renders `parts-list` with `parts` (search results), `q`, `showInactive` (echoed for the toggle), `isManager`. Reserved is a static `0`; available is `quantity` (no reservations exist yet, per roadmap note).

#### 2. Template

**File**: `src/main/resources/templates/parts-list.html`

**Intent**: Table of Nazwa / Stan / Zarezerwowane / Dostępne / Lokalizacje, a search box, and (manager only, via `sec:authorize="hasRole('MANAGER')"`) a "Dodaj część" link, a "pokaż nieaktywne" toggle, and per-row edit/deactivate/reactivate actions.

**Contract**: Follows the plain-HTML, no-CSS-framework style of `dashboard.html`/`login.html`.

#### 3. Dashboard link

**File**: `src/main/resources/templates/dashboard.html`

**Intent**: Give every logged-in user a way to reach the new screen — without this, `/parts` is unreachable through the UI.

**Contract**: Add one `<a th:href="@{/parts}">Kartoteka części</a>` link, visible to both roles (no `sec:authorize` wrapper).

### Success Criteria:

#### Automated Verification:
- `./mvnw verify` passes (covered fully by Phase 4's tests for this endpoint).

#### Manual Verification:
- Logged in as a technician, `/parts` loads, shows seeded active parts, and searching by a location string returns the matching part.
- Logged in as a manager, the "pokaż nieaktywne" toggle and "Dodaj część" link are visible; as a technician, they are not.

---

## Phase 3: Manager CRUD

### Overview

Create, edit (name + locations only), deactivate, and reactivate — all manager-only.

### Changes Required:

#### 1. Controller — write actions

**File**: `src/main/java/pl/regavio/stockahead/parts/PartController.java`

**Intent**: Protect all six GET/POST handlers listed below with `@PreAuthorize("hasRole('MANAGER')")`. Create and edit share manual validation and a private location-parsing/dedup helper (split textarea on newline, trim, drop blanks, reject in-request duplicates). No new service class.

**Write transaction contract**:
- Construct a `TransactionTemplate` from the injected `PlatformTransactionManager`. Do not annotate the controller with `@Transactional`. Parse and validate submitted scalar values before mutating any managed entity.
- Wrap each write in `TransactionTemplate.execute(...)`. For create, persist the new part and its locations together. For edit, load the part and its locations inside the callback, change only the name, and reconcile the normalized case-sensitive location set in place: retain matching rows, remove absent rows, and add new rows using the relationship helpers. Deactivate/reactivate load and change only `active` inside their callbacks. Flush within the callback; transaction completion must precede redirect or rendering.
- Keep the database unique constraint authoritative for name conflicts, including conflicts introduced between requests. Catch `DataIntegrityViolationException` outside `execute(...)`, after rollback, and re-render the appropriate form with a friendly error. Never swallow the exception within the transaction or render a failed managed aggregate. Populate errors from submitted scalar form values; fetch any required persisted display values in a fresh transaction and copy them into the model. Failed edits must preserve the previous name, quantity, active flag, and complete location set.

**Contract**:
- `GET /parts/new`, `POST /parts` — name (non-blank, unique), quantity (int, `>= 0`), locations (≥ 1 after parsing). On success: create with `active = true`, redirect to `/parts`.
- `GET /parts/{id}/edit`, `POST /parts/{id}` — name (non-blank, unique excluding self) and locations (≥ 1 after parsing) only; no quantity field exists on this form at all.
- `POST /parts/{id}/deactivate` — sets `active = false` unconditionally (no quantity gate, per the accepted decision), redirect to `/parts`.
- `POST /parts/{id}/reactivate` — sets `active = true`, redirect to `/parts?showInactive=true`.

#### 2. Templates

**Files**: `src/main/resources/templates/parts-new.html`, `parts-edit.html`

**Intent**: `parts-new.html` has name, quantity, and a locations textarea (one per line). `parts-edit.html` has name and the same textarea, plus a read-only line showing current quantity with a short note that it's changed through delivery/correction, not here.

**Contract**: Same plain-form style as `setup.html` (error paragraph at top via `th:if="${error}"`, values re-populated on validation failure).

### Success Criteria:

#### Automated Verification:
- `./mvnw verify` passes (business-rule tests land in Phase 4).

#### Manual Verification:
- As manager: add a part with two locations, see it in the list; edit its name, see the change; deactivate it, confirm it disappears from the default list and reappears under "pokaż nieaktywne"; reactivate it, confirm it's back in the default list.
- As technician: none of `/parts/new`, edit, deactivate, reactivate links or forms are reachable; direct `POST` to any of them is rejected.

---

## Phase 4: Tests

### Overview

Integration coverage for the schema guard, the role split, and every business rule decided in this plan.

### Changes Required:

#### 1. Integration tests

**File**: `src/test/java/pl/regavio/stockahead/parts/PartsCatalogIntegrationTests.java`

**Intent**: Reuse `@Import(TestcontainersConfiguration.class) @SpringBootTest @AutoConfigureMockMvc`, `formLogin()`/`csrf()`, and seed helpers, but omit class-level and method-level test `@Transactional`. Each MockMvc request must complete its own application transaction.

**Fixture and assertion boundaries**:
- Commit account/part fixtures in separate `TransactionTemplate` callbacks before issuing requests. After each request, verify persisted state in a fresh read transaction or via `JdbcTemplate`; do not assert against cached fixture entities.
- Use explicit cleanup before and after each test in the throwaway Testcontainers database, deleting `part_locations`, then `parts`, then test accounts. Run this class without parallel execution against shared fixtures. Account deletion here is test-fixture cleanup only, not an application operation.
- Run the direct invalid SQL insert in its own transaction and catch the constraint exception outside it. Perform any later assertions in a fresh transaction.
- The duplicate-name cases must exercise the real database constraint and controller exception handler, not just an application pre-check. Assert the friendly rendered error and then independently read committed state, after rollback. Do not mock the database.

**Contract** — cases required:
- Direct SQL insert of a part with `quantity = -1` (via `JdbcTemplate`, bypassing app validation) fails with a constraint violation — proves the migration's `CHECK` actually exists.
- Manager creates a part with valid name/quantity/2 locations → redirect, row persisted, `active = true`.
- Duplicate part name on create → 200 with a friendly error, no duplicate row (`DataIntegrityViolationException` path).
- Create with zero locations (blank textarea) → validation error, no row created.
- Create with two identical location lines → validation error, no row created.
- Technician gets 403 on `GET /parts/new`, `POST /parts`, `GET /parts/{id}/edit`, `POST /parts/{id}`, `POST /parts/{id}/deactivate`, `POST /parts/{id}/reactivate`.
- Both roles get 200 on `GET /parts`.
- Search matches a part by name substring (case-insensitive) and separately by a location substring.
- A deactivated part is excluded from `GET /parts` (default) and appears together with still-active parts when `showInactive=true` as manager (both states present in one response — the flag never hides active parts); a technician's `showInactive=true` request is ignored (still excluded).
- Reactivating a part makes it reappear in the default `GET /parts` results.
- Posting an edit never changes `quantity`, even if a `quantity` request param is injected — the endpoint has no such field to bind.
- Edit with unchanged locations succeeds and preserves their row IDs.
- Edit locations from A/B to B/C retains B's row ID, deletes A, creates C once, and persists the name/location change together.
- Edit to another part's existing name while submitting changed locations returns a friendly error and preserves the original name, quantity, active flag, and all location rows/IDs after rollback, verified in a fresh transaction.

### Success Criteria:

#### Automated Verification:
- `./mvnw verify` — full suite green, including the new `PartsCatalogIntegrationTests`.
- Unchanged-location edits preserve location row IDs.
- Overlapping location edits retain matching rows and persist the intended final set.
- Duplicate-name edits roll back all aggregate changes and render a friendly error.

#### Manual Verification:
- (none — this phase is verification itself)

---

## Testing Strategy

### Integration Tests:
- All coverage lives in `PartsCatalogIntegrationTests` (Phase 4) — no unit-test layer, matching the account package's precedent (no services to unit-test in isolation).

### Manual Testing Steps:
1. Log in as manager, add a part with 2 locations, confirm it appears searchable by both name and either location string.
2. Edit the part's name, confirm the search index picks up the new name and the old name no longer matches.
3. Deactivate the part, confirm a technician's search no longer finds it; reactivate as manager, confirm it's findable again.
4. As technician, confirm `/parts/new` and edit/deactivate/reactivate actions are all inaccessible.

## Migration Notes

None — this is a new schema, no existing data to migrate.

## References

- Feature-first package precedent: `context/archive/2026-09-26-auth-and-roles/plan-brief.md`
- `@PreAuthorize` pattern: `src/main/java/pl/regavio/stockahead/account/ManagerPingController.java:16`
- Manual-validation + `DataIntegrityViolationException` pattern: `src/main/java/pl/regavio/stockahead/account/SetupController.java:45-82`
- Test shape: `src/test/java/pl/regavio/stockahead/account/AuthenticationIntegrationTests.java`
- Hard rules: `AGENTS.md` (stock/reservation invariants, account deactivation precedent)
- Roadmap slice definition and risk note: `context/foundation/roadmap.md:111-121`
- Lessons applied: `context/foundation/lessons.md`

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles.

### Phase 1: Schema & domain model

#### Automated

- [x] 1.1 `./mvnw verify` — migration V3 applies cleanly, app context loads — bb2bda9

### Phase 2: Search & browse

#### Manual

- [x] 2.1 Technician can load `/parts` and search by name or location — bb2bda9
- [x] 2.2 Manager-only controls (toggle, add link) hidden from technician — bb2bda9

#### Automated

- [x] 2.3 `./mvnw verify` — full suite passes after search/browse changes — bb2bda9

### Phase 3: Manager CRUD

#### Manual

- [x] 3.1 Manager can add, edit, deactivate, and reactivate a part end-to-end — bb2bda9
- [x] 3.2 Technician cannot reach any write action — bb2bda9

#### Automated

- [x] 3.3 `./mvnw verify` — full suite passes after manager CRUD changes — bb2bda9

### Phase 4: Tests

#### Automated

- [x] 4.1 CHECK constraint rejects a direct negative-quantity insert — bb2bda9
- [x] 4.2 Manager create with valid data persists an active part — bb2bda9
- [x] 4.3 Duplicate name on create re-renders with a friendly error, no duplicate row — bb2bda9
- [x] 4.4 Zero locations on create is rejected — bb2bda9
- [x] 4.5 Duplicate location lines on create are rejected — bb2bda9
- [x] 4.6 Technician gets 403 on every write route — bb2bda9
- [x] 4.7 Both roles get 200 on `GET /parts` — bb2bda9
- [x] 4.8 Search matches by name substring and by location substring — bb2bda9
- [x] 4.9 Deactivated part excluded/included correctly across roles and `showInactive` — bb2bda9
- [x] 4.10 Reactivate restores default-list visibility — bb2bda9
- [x] 4.11 Edit never changes quantity, even with an injected `quantity` param — bb2bda9
- [x] 4.12 `./mvnw verify` — full suite passes, including PartsCatalogIntegrationTests — bb2bda9
- [x] 4.13 Unchanged-location edits preserve location row IDs — bb2bda9
- [x] 4.14 Overlapping location edits retain matching rows and persist the intended final set — bb2bda9
- [x] 4.15 Duplicate-name edits roll back all aggregate changes and render a friendly error — bb2bda9
