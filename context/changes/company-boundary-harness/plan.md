# Company Boundary and Cross-Company Test Pattern Implementation Plan

## Overview

Roadmap M-2 F-01. Introduce the company as a first-class record: a `companies` table, a non-null `accounts.company_id`, a logged-in principal that carries its company id, and a `/setup` that creates a company together with its first manager. Replace 28 hand-rolled account fixtures with one shared `CompanyFixtures` test bean, and prove the "company B's user gets 404 for company A's record id" pattern on the technician-account routes. Every Stream A slice (S-01…S-06) and S-07/S-08/S-09 builds on this.

## Current State Analysis

- No company concept exists anywhere in `src/main` or `src/test`; migrations stop at V13.
- `accounts` (V1) has no owner column; `accounts_single_manager_idx` (`V2__enforce_single_manager.sql:1`) allows exactly one MANAGER per database. Email is globally unique via `accounts_email_canonical_idx` (V4:21) — that stays (FR-029).
- `AccountUserDetailsService.loadUserByUsername` (`security/AccountUserDetailsService.java:28-37`) returns a plain Spring `User` (email + authorities). Controllers that need the account reload it by email (`parts/StockCorrectionController.java:237`).
- `SetupController` (`account/SetupController.java:42-82`) creates the single MANAGER; it closes via `existsByRole(MANAGER)` (lines 44, 67), not only via the V2 index.
- `TechnicianAccountController` lists technicians globally (`findByRoleOrderByEmailAsc`, line 51) and deactivates/reactivates by bare `findById` (line 113) — a cross-company leak once two companies exist.
- 28 test classes each build accounts with their own `seedAccount` (`new Account()`, e.g. `parts/PartsCatalogIntegrationTests.java:123-133`); two classes insert accounts with raw SQL (`account/V4MigrationTests.java:89`, `orders/OrderCompletionSchemaTests.java:47`). `AccountUserDetailsServiceTest` builds `Account`s with Mockito.
- `V4MigrationTests` inserts accounts at V3 and then migrates to *latest* — it would hit V14's non-empty guard.
- Four tables reference `accounts` (orders ×3 in V8/V9/V13, `stock_corrections` in V11); none need a company in this change.

## Desired End State

- `companies(id, name, status, created_at)` exists; status is one of `PENDING/ACTIVE/REJECTED/BLOCKED`, default `ACTIVE`. Every account row has a non-null `company_id`. More than one MANAGER may exist (one or more per company).
- The authenticated principal exposes `companyId`; controllers obtain the company only from it.
- `/setup` asks for a company name and creates company + manager atomically; it still closes once any manager exists.
- `/manager/technicians` (list, create, deactivate, reactivate) is scoped to the principal's company; a company-B manager gets 404 on company A's technician id and cannot change it.
- All tests use `CompanyFixtures`; `./mvnw verify` is green.

Verify with `./mvnw verify` plus the manual setup/login walkthrough in Phase 1 and Phase 2.

### Key Discoveries:

- `SetupController.java:44,67` — setup closure is application logic, so dropping the V2 index does not reopen `/setup`.
- `V4__normalize_account_emails.sql:1-17` — precedent for a guard `DO $$ … RAISE EXCEPTION 'V4: …'` migration; V14's non-empty guard follows the same shape.
- `PartsCatalogIntegrationTests.java:99-111` — tests clean up by deleting accounts by email; fixture cleanup must also delete the companies it created, after the accounts (FK order).
- `TechnicianAccountController.java:110-120` — already maps "not a technician" to 404; the company check joins that same lookup.
- lessons.md "Handle DB constraint violations at write boundaries" and "Test the wrong-role case for every role-gated route" apply to setup and the technician routes.

## What We're NOT Doing

- No company scoping of warehouse tables (parts, projects, orders, reservations, …) — each Stream A slice adds its own column and migration.
- No operator role and no nullable `company_id` — S-08 relaxes the column when it introduces the operator.
- No public registration, pending/approval flow, or block/unblock — S-09/S-11/S-12. The `status` column exists but nothing reads it; F-01 writes only `ACTIVE`.
- No manager-account management or "last active manager" rule — S-07 (F-01 scopes only the existing technician routes).
- No scoping of `AccountRepository.findAssignable()` (order assignee picker) — S-03. Safe meanwhile because production has one company until S-09 ships.
- No data backfill: V14 refuses to run on a non-empty `accounts` table; production is wiped by hand before deploy (prd-v2 § Constraints allows it).
- No company name in the topbar or anywhere else in the UI beyond the setup form.
- No uniqueness on company name (prd-v2 does not require it).

## Implementation Approach

Phase 1 changes the schema, puts the company on the principal, gives both production account-creating paths (`/setup` and technician `create`) a company, and makes the whole test suite company-aware — all in one green step. The NOT NULL column breaks every account-creating path at once, in tests and in `src/main` alike, so none of them can be split out. Phase 2 uses the principal to scope the technician list/deactivate/reactivate routes and lands the reference two-company tests and the docs that point later slices at them.

## Critical Implementation Details

- **V14 ordering**: the guard must run before `ALTER TABLE accounts ADD COLUMN company_id … NOT NULL`, so a non-empty database fails with the named V14 message rather than Postgres' generic not-null error. Flyway runs the Postgres migration in one transaction, so a failed guard leaves the schema at V13.
- **Fixture discovery**: annotate `CompanyFixtures` with `@TestComponent` and `@Import` it explicitly in each test class. A plain `@Component` under `src/test` would be picked up by component scanning from `TestStockaheadApplication` as well.

## Phase 1: Schema, principal, setup and shared company fixture

### Overview

Add the `companies` table and `accounts.company_id`, map them in JPA, carry the company on the principal, make `/setup` and technician `create` save accounts with a company, and migrate every test to a shared fixture so the suite stays green.

### Changes Required:

#### 1. Migration V14

**File**: `src/main/resources/db/migration/V14__create_companies.sql`

**Intent**: Create the company record, attach every account to one, and lift the one-manager-per-database rule. Refuse to run against existing account data (manual wipe decision).

**Contract**:
- Guard: `DO $$ … IF EXISTS (SELECT 1 FROM accounts) THEN RAISE EXCEPTION 'V14: tabela accounts nie jest pusta — wyczyść bazę przed wdrożeniem'` (wording may vary, but it must start with `V14:`).
- `companies`: `id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY`, `name VARCHAR(255) NOT NULL CHECK (btrim(name) <> '')`, `status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('PENDING','ACTIVE','REJECTED','BLOCKED'))`, `created_at TIMESTAMPTZ NOT NULL DEFAULT now()`.
- `accounts.company_id BIGINT NOT NULL REFERENCES companies (id) ON DELETE RESTRICT`, plus an index on `accounts (company_id)`.
- `DROP INDEX accounts_single_manager_idx`.

#### 2. Company entity and repository

**File**: `src/main/java/pl/regavio/stockahead/account/Company.java`, `CompanyStatus.java`, `CompanyRepository.java`

**Intent**: Map the new table in the `account` package next to `Account`.

**Contract**: `Company` with id, name, `CompanyStatus status` (enum `PENDING, ACTIVE, REJECTED, BLOCKED`, stored as string, Java default `ACTIVE`), createdAt. `CompanyRepository extends JpaRepository<Company, Long>`. Imports are `jakarta.persistence.*`.

#### 3. Account → Company

**File**: `src/main/java/pl/regavio/stockahead/account/Account.java`

**Intent**: Every account belongs to a company.

**Contract**: `@ManyToOne(fetch = LAZY, optional = false) @JoinColumn(name = "company_id", nullable = false) Company company` with a getter and setter. `ddl-auto=validate` must pass.

#### 4. Company-aware principal

**File**: `src/main/java/pl/regavio/stockahead/security/CompanyUser.java`, `security/AccountUserDetailsService.java`

**Intent**: Make the company available to every controller from the authenticated principal, which is the only allowed source (AGENTS.md § Company isolation).

**Contract**: `CompanyUser extends org.springframework.security.core.userdetails.User` and adds `Long companyId()`. `loadUserByUsername` returns it, built from `account.getCompany().getId()`, with authorities and the disabled flag unchanged. Controllers receive it via `@AuthenticationPrincipal CompanyUser`. `AccountActivityFilter` is unchanged.

#### 5. Setup creates company + manager

**File**: `src/main/java/pl/regavio/stockahead/account/SetupController.java`, `src/main/resources/templates/setup.html`, `src/main/resources/messages.properties`

**Intent**: Setup asks for the company name and creates the company and its first manager in one transaction. This is the "create company + manager" step S-09 registration will reuse.

**Contract**:
- `POST /setup` takes a new `companyName` param. It is trimmed; empty is an error (`setup.error.companyNameRequired`), and so is longer than 255 characters (`setup.error.companyNameTooLong`). Both re-render the form, keeping email and company name.
- Company (`ACTIVE`) and account are saved in one `TransactionTemplate`. A `DataIntegrityViolationException` re-renders with `setup.error.accountCreationFailed`, per lessons.md.
- The `existsByRole(MANAGER)` closure checks stay. Dropping `accounts_single_manager_idx` removes the DB backstop that today makes the second of two concurrent setup POSTs fail. So inside the same `TransactionTemplate`, first take a transaction-scoped advisory lock (`pg_advisory_xact_lock`, same shape as `PartRepository.java:62`, with a setup-specific key). Then re-check `existsByRole(MANAGER)` and redirect to `/login` if a manager now exists. Only then save the company and the manager. This lock belongs to setup only; S-09 registration must not reuse it, because many companies are allowed there.
- `setup.html` gets a "Nazwa firmy" field built on the existing `form-field` markup (no inline styles, per `TemplateConventionTests`).

#### 6. Technician create sets the company

**File**: `src/main/java/pl/regavio/stockahead/account/TechnicianAccountController.java`

**Intent**: The other production path that creates accounts must supply a company as soon as the column is NOT NULL.

**Contract**: `create` takes `@AuthenticationPrincipal CompanyUser` and sets the new technician's company to `companyRepository.getReferenceById(principal.companyId())`. `list` and `setActive` are untouched until Phase 2.

#### 7. Shared test fixture

**File**: `src/test/java/pl/regavio/stockahead/CompanyFixtures.java`

**Intent**: One place to create companies and accounts in companies, so every test, and every later slice's "company B → 404" test, uses the same setup.

**Contract**: `@TestComponent`; methods roughly `Company company(String name)`, `Account account(Company company, String email, String rawPassword, Role role, boolean active)` (encodes the password, commits in its own transaction like the existing `seedAccount`s), and a cleanup that deletes accounts by email and then the companies the fixture created (FK order). Names are the implementer's choice; these behaviours are the contract.

#### 8. Migrate existing tests

**File**: all 28 classes that call `new Account()` (see `grep -rl "new Account()" src/test`), plus `orders/OrderCompletionSchemaTests.java`, `account/V4MigrationTests.java`, `security/AccountUserDetailsServiceTest.java`

**Intent**: Keep each test's behaviour and helper names; only route account creation through `CompanyFixtures`, with a single default company per class unless the test needs two.

**Contract**:
- Each `seedAccount` delegates to `CompanyFixtures.account(...)`, and each `cleanUp` calls the fixture cleanup after its own deletes.
- `OrderCompletionSchemaTests` raw `INSERT INTO accounts` supplies a `company_id` (insert a company first).
- `V4MigrationTests` migrates to target `"13"` instead of latest, because it tests V4 behaviour on pre-company schema.
- `AccountUserDetailsServiceTest` sets a `Company` on its accounts.

#### 9. V14 migration tests

**File**: `src/test/java/pl/regavio/stockahead/account/V14MigrationTests.java`

**Intent**: Pin the migration's guard and constraints, following the `V4MigrationTests` pattern (own container, Flyway with `target`).

**Contract**: tests for:
- migrating from V13 with an existing account fails with a `V14:` message and leaves `accounts` unchanged
- an empty database migrates cleanly
- a new company gets `ACTIVE` by default
- an unknown status is rejected
- an account without `company_id` is rejected
- two MANAGER accounts in two different companies are both accepted

#### 10. Setup and principal tests

**File**: `src/test/java/pl/regavio/stockahead/account/AuthenticationIntegrationTests.java`, `security/AccountUserDetailsServiceTest.java`

**Intent**: Cover the principal's company and the new setup behaviour.

**Contract**: covers:
- setup with a company name creates one `ACTIVE` company and an active MANAGER in it
- a blank company name creates nothing and re-renders the form
- setup stays closed once a manager exists
- two concurrent setup POSTs (two threads, as in the existing `*ConcurrencyTests`) create exactly one company and one manager
- after login, the principal is a `CompanyUser` whose `companyId` equals the account's company
- the unit test asserts `companyId` is carried over
- Setup tests create their company through `POST /setup`, which `CompanyFixtures` does not track. So they assert through the created accounts (`account.getCompany()`), never through a global `companyRepository.count()`. Company and manager share one transaction, so a losing concurrent POST leaves no orphan company. The class's cleanup deletes those accounts and then their companies, in FK order.

### Success Criteria:

#### Automated Verification:

- `./mvnw verify` passes (all existing tests green on V14 schema)
- `V14MigrationTests` covers guard, default status, status check, NOT NULL company and multiple managers
- `grep -rn "new Account()" src/test` matches only `CompanyFixtures.java` and `AccountUserDetailsServiceTest.java`
- Setup tests cover the happy path, blank company name, the closed-after-manager case and two concurrent POSTs creating exactly one manager
- The principal test asserts `companyId` after a real form login

#### Manual Verification:

- On `./mvnw spring-boot:test-run`, `/setup` shows the company-name field. Submitting creates the manager, who can log in and reach the dashboard.
- Opening `/setup` again redirects to `/login`.
- The manager creates a technician, which works as before.

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful before proceeding to the next phase.

---

## Phase 2: Two-company pattern on technician routes

### Overview

Scope the existing technician-account routes by the principal's company and land the reference cross-company tests every later slice copies.

### Changes Required:

#### 1. Company-scoped account queries

**File**: `src/main/java/pl/regavio/stockahead/account/AccountRepository.java`

**Intent**: Give controllers company-scoped lookups instead of global ones.

**Contract**: `findByCompanyIdAndRoleOrderByEmailAsc(Long companyId, Role role)` and `Optional<Account> findByIdAndCompanyId(Long id, Long companyId)`. Leave `findByCanonicalEmail`, which is intentionally cross-company for login, and `findAssignable` (S-03).

#### 2. Technician routes scoped

**File**: `src/main/java/pl/regavio/stockahead/account/TechnicianAccountController.java`

**Intent**: A manager sees, deactivates and reactivates only their own company's technicians. Another company's id answers 404 exactly like a missing id.

**Contract**:
- `list` uses the company-scoped query.
- `create` keeps setting the company from the principal (Phase 1).
- `setActive` loads via `findByIdAndCompanyId(id, principal.companyId())` and keeps the existing `role == TECHNICIAN` filter, so a miss returns 404.
- The company comes only from `@AuthenticationPrincipal CompanyUser`, never from a request param.

#### 3. Reference cross-company tests

**File**: `src/test/java/pl/regavio/stockahead/account/TechnicianAccountIntegrationTests.java`

**Intent**: The canonical example of AGENTS.md's "company B → 404" rule, next to the existing wrong-role 403 tests.

**Contract**: with companies A and B from `CompanyFixtures`:
- B's manager does not see A's technicians in the list
- `POST …/{A-technician-id}/deactivate` and `…/reactivate` by B's manager return 404, and A's technician's `active` flag stays unchanged (re-read in a fresh transaction)
- a technician created by B's manager belongs to B
- the existing technician→403 tests still pass

#### 4. Docs for later slices

**File**: `AGENTS.md` (§ Company isolation), `context/foundation/deployment.md`

**Intent**: Point every later slice at the shared pieces, and record the one-time wipe the V14 deploy needs.

**Contract**:
- AGENTS.md gets one line naming `CompanyUser` (the principal's company) and `CompanyFixtures` plus `TechnicianAccountIntegrationTests` (the two-company test pattern to copy).
- deployment.md gets a short "V14 one-time wipe" note as an ordered sequence, because `ci.yml` deploys on every push to `main`:
  1. Click Backup now.
  2. `TRUNCATE` every application table (not `flyway_schema_history`) `RESTART IDENTITY CASCADE`.
  3. Merge the PR immediately.
  4. Watch the deploy (`gh run watch`).
  5. Run `/setup` with a company name.
  If anyone uses the old `/setup` between steps 2 and 3, the V14 guard trips and the deploy fails without changing anything (V14 is transactional). In that case, wipe again and redeploy the tag.

### Success Criteria:

#### Automated Verification:

- `./mvnw verify` passes
- `TechnicianAccountIntegrationTests` has the cross-company list, deactivate-404, reactivate-404 and create-in-own-company tests
- `grep -n "findById(" src/main/java/pl/regavio/stockahead/account/TechnicianAccountController.java` returns nothing

#### Manual Verification:

- On `./mvnw spring-boot:test-run`, the manager lists, deactivates and reactivates a technician, which works as before.
- AGENTS.md and deployment.md additions read correctly and name the right classes.

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful before proceeding to the next phase.

---

## Testing Strategy

### Unit Tests:

- `AccountUserDetailsServiceTest`: the `CompanyUser` carries `companyId`, and the authorities are unchanged.

### Integration Tests:

- `V14MigrationTests`: guard on a non-empty database, status default and check, NOT NULL company, several managers.
- `AuthenticationIntegrationTests`: setup with a company name, blank name rejected, setup closed after a manager exists, principal's company after a real login.
- `TechnicianAccountIntegrationTests`: two-company list, deactivate/reactivate 404, create in own company, plus the existing 403s.
- The full existing suite runs unchanged in behaviour on `CompanyFixtures`.

### Manual Testing Steps:

1. `./mvnw spring-boot:test-run`, open `/setup`, and enter company name, email, password and token. You land on `/login?setup`.
2. Log in, open `/manager/technicians`, and create, deactivate and reactivate a technician.
3. Revisit `/setup` and confirm it redirects to `/login`.

## Migration Notes

V14 is deliberately not expand/contract (it deviates from deployment.md S5): it refuses to run while `accounts` has rows. Before deploying the change to production, click Backup now and wipe all application tables (see the Phase 2 deployment.md note). Then the deploy runs V14 and `/setup` is used again with a company name. Per the project memory, this branch does not land on `main` automatically. The wipe happens right before the user merges, because CI deploys on the push to `main`; follow the ordered sequence in that note.

## References

- Frame brief: `context/changes/multi-company-support/frame.md`
- PRD: `context/foundation/prd-v2.md` (FR-028, FR-029, § Constraints, § Access Control Changes)
- Roadmap: `context/foundation/roadmap.md` F-01
- Guard-migration precedent: `src/main/resources/db/migration/V4__normalize_account_emails.sql`
- Migration-test precedent: `src/test/java/pl/regavio/stockahead/account/V4MigrationTests.java`
- 404-on-miss precedent: `src/main/java/pl/regavio/stockahead/account/TechnicianAccountController.java:110-120`

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles. See `references/progress-format.md`.

### Phase 1: Schema, principal, setup and shared company fixture

#### Automated

- [x] 1.1 `./mvnw verify` passes (all existing tests green on V14 schema) — b843c42
- [x] 1.2 `V14MigrationTests` covers guard, default status, status check, NOT NULL company and multiple managers — b843c42
- [x] 1.3 `grep -rn "new Account()" src/test` matches only `CompanyFixtures.java` and `AccountUserDetailsServiceTest.java` — b843c42
- [x] 1.4 Setup tests cover the happy path, blank company name, the closed-after-manager case and two concurrent POSTs creating exactly one manager — b843c42
- [x] 1.5 The principal test asserts `companyId` after a real form login — b843c42

#### Manual

- [x] 1.6 On `./mvnw spring-boot:test-run`, `/setup` shows the company-name field. Submitting creates the manager, who can log in and reach the dashboard. — b843c42
- [x] 1.7 Opening `/setup` again redirects to `/login`. — b843c42
- [x] 1.8 The manager creates a technician, which works as before. — b843c42

### Phase 2: Two-company pattern on technician routes

#### Automated

- [x] 2.1 `./mvnw verify` passes
- [x] 2.2 `TechnicianAccountIntegrationTests` has the cross-company list, deactivate-404, reactivate-404 and create-in-own-company tests
- [x] 2.3 `grep -n "findById(" src/main/java/pl/regavio/stockahead/account/TechnicianAccountController.java` returns nothing

#### Manual

- [x] 2.4 On `./mvnw spring-boot:test-run`, the manager lists, deactivates and reactivates a technician, which works as before.
- [x] 2.5 AGENTS.md and deployment.md additions read correctly and name the right classes.
