# Operator Setup and Company List Implementation Plan

## Overview

Roadmap slice S-08 (FR-033, FR-025, FR-029, Success Criteria › Guardrails). The token-gated `/setup` stops creating "company + first manager" and instead creates the single **platform operator**: an account with role `OPERATOR` that belongs to no company. Setup closes once the operator exists. The operator logs in, lands on a read-only list of companies (name, manager's email, registration date, status), and every warehouse route refuses them with 403. A URL-level deny-by-default rule enforces the refusal, and each warehouse controller gets its own test.

## Current State Analysis

- `SetupController` (`account/SetupController.java:71-131`) creates a `Company` (ACTIVE) and a MANAGER inside one transaction. It serialises concurrent submissions with `pg_advisory_xact_lock(0x53455455, 0)`, re-checks under the lock, and catches `DataIntegrityViolationException`. It closes on `existsByRole(MANAGER)` (`:65`, `:93`, `:102`). It validates only the token, the company name and password confirmation: no email format or length check and no password minimum.
- The schema forbids an account without a company. V1 has `role CHECK (role IN ('MANAGER','TECHNICIAN'))` (`V1__create_accounts.sql:5`), V14 has `accounts.company_id BIGINT NOT NULL` (`V14__create_companies.sql:24`), and `Account.company` is `optional = false, nullable = false` (`account/Account.java:40-42`).
- `companies` already has `status` (PENDING/ACTIVE/REJECTED/BLOCKED) and `created_at` (`V14__create_companies.sql:17-22`, `account/CompanyStatus.java`), so the list needs no company schema change.
- Every principal is assumed to have a company:
  - `AccountUserDetailsService.loadUserByUsername` dereferences `account.getCompany().getId()`, and its `authoritiesFor` switch is exhaustive over `Role` (`security/AccountUserDetailsService.java:30-40`).
  - `AccountActivityFilter.accountState` treats `companyId == null` as DEACTIVATED and checks the manager flag only (`security/AccountActivityFilter.java:62-77`). An operator would therefore be logged out on every request.
- `SecurityConfig` only requires authentication (`security/SecurityConfig.java:27-30`). Four routes have no `@PreAuthorize`: `GET /` (`HomeController:19`), `GET /parts` (`PartController:68`), `GET /projects` and `GET /projects/{id}` (`ProjectController:61,76`). A user without MANAGER/TECHNICIAN would get an empty catalog, not a 403 (roadmap S-08 risk, company-scoped-parts impl review F3).
- The topbar shows warehouse links to everyone, and its badge treats `!hasRole('MANAGER')` as "Technik" (`templates/fragments.html:14-26`). `dashboard.html` shows the warehouse tiles unconditionally.
- `CompanyAccountController` holds the account form validation: email canonical/≤255/pattern, password ≥12, confirmation (`account/CompanyAccountController.java:37-41,86-107`). Its `parseRole` accepts only MANAGER/TECHNICIAN (`:247-251`), so a manager can never create or promote an operator.
- Tests: `AuthenticationIntegrationTests` covers setup (company + manager, closing, race, wrong token) and guards against a leaked MANAGER (`:84-88`). `CompanyFixtures` seeds companies and accounts. `AccountLookupConventionTests` allows `findByCanonicalEmail` only in login.

## Desired End State

- On an empty database, `GET /setup` shows a form with email, password, password confirmation and token, and no company name. A valid submission creates exactly one active `OPERATOR` account with `company_id IS NULL` and redirects to `/login?setup`. Setup then redirects to `/login` and creates nothing. Companies and managers existing in the database do not close setup; only an operator does.
- The operator logs in, `/` redirects them to `/operator/companies`, and the session survives subsequent requests.
- `/operator/companies` lists every company with its name, the email of its first-created MANAGER (active or not; earliest `created_at`, then lowest id), its registration date (`companies.created_at`) and its Polish status label. Rows are ordered newest first (`created_at DESC, id DESC`). With no companies, an empty-state message is shown.
- The operator gets 403 on every warehouse route, GET and POST, and changes nothing. Managers and technicians get 403 on `/operator/**`. The operator's topbar shows only the company-list link and an "Operator" badge.
- Verify with `./mvnw verify`, then a manual run of `./mvnw spring-boot:test-run` (setup → login → list → try `/parts`).

### Key Discoveries:

- The setup lock pattern (`SetupController.java:32-38,99-120`) already gives the "exactly one" guarantee. The plan backs it with a DB partial unique index, as V2 once did for managers.
- `CompanyRepository.findByIdForUpdate` and `findByIdAndCompanyId` are company-scoped by design. The operator needs its own id-based lookup (`findByIdAndCompanyIdIsNull`), and `AccountLookupConventionTests` stays satisfied because it only polices `findByCanonicalEmail`.
- `CompanyAccountIntegrationTests:122` shows the session-login test pattern (`formLogin().user(..)` → `MockHttpSession`) to reuse for operator tests.

## What We're NOT Doing

- No company registration (S-09), no approve/reject (S-11), no block/unblock (S-12). The list is read-only.
- No operator access to any company's warehouse data, accounts list or account management (prd-v2 § Non-Goals).
- No operator password change, deactivation or second operator. Exactly one operator exists.
- No extra setup hardening beyond the token. PRD Open Question 1 is resolved as "the token is enough": constant-time compare, advisory lock, self-closing setup.
- No dev seeder for a demo company. Until S-09/S-11 land, a fresh `spring-boot:test-run` has no company. Warehouse screens are exercised through tests and `CompanyFixtures`, or an SQL insert for manual checks. This branch is not merged to main until the milestone is done.
- No pagination or search on the company list.

## Implementation Approach

Phase 1 introduces OPERATOR at the schema and authentication layers and switches `SecurityConfig` to deny by default. Setup still creates a manager at this point, so the suite stays green. Phase 2 makes setup create the operator and moves the credential checks into one shared validator, which S-09 will reuse. Phase 3 adds the operator's only screen, routes `/` by role, makes the topbar role-aware, and adds the per-controller operator→403 guardrail tests and the documentation updates.

## Critical Implementation Details

- **Error dispatch under deny-by-default.** When `anyRequest()` requires MANAGER/TECHNICIAN, Spring Security also authorizes the `ERROR` dispatch. Keep `/error` reachable to any authenticated user, either `.requestMatchers("/error").authenticated()` or a `dispatcherTypeMatchers(DispatcherType.ERROR)` rule. Otherwise an operator's 403 re-enters authorization and is denied again. `/` must also stay `authenticated()` so `HomeController` can route the operator.
- **Filter role comparison.** `AccountActivityFilter` must compare the account's current role to the authority set in the session, not just "is manager", so that an OPERATOR session is CURRENT and never `roleChanged`. Derive the expected authorities in one place shared with `AccountUserDetailsService`.

## Phase 1: Operator role in the schema and authentication

### Overview

The database, entity, principal, activity filter and URL rules all know about an OPERATOR with no company. Behaviour for managers and technicians is unchanged.

### Changes Required:

#### 1. Migration V20

**File**: `src/main/resources/db/migration/V20__operator_role.sql`

**Intent**: Allow exactly one operator account, which has no company, while every other account keeps a mandatory company.

**Contract**:
- Replace the V1 role CHECK so it allows `'OPERATOR'`. Look up the generated constraint name, `accounts_role_check`, before dropping it.
- `ALTER COLUMN company_id DROP NOT NULL`.
- Add `CHECK ((role = 'OPERATOR') = (company_id IS NULL))`.
- Add `CREATE UNIQUE INDEX accounts_single_operator_idx ON accounts (role) WHERE role = 'OPERATOR'`.

#### 2. Role and Account

**File**: `src/main/java/pl/regavio/stockahead/account/Role.java`, `account/Account.java`

**Intent**: Model the operator. Its company is absent.

**Contract**: `Role.OPERATOR` is added. The `Account.company` mapping becomes `optional = true` and the column nullable. `CompanyAccountController.parseRole` stays unchanged: OPERATOR remains unassignable from the accounts page.

#### 3. Account lookup for the operator

**File**: `src/main/java/pl/regavio/stockahead/account/AccountRepository.java`

**Intent**: Let the activity filter reload an operator by id without a company.

**Contract**: `Optional<Account> findByIdAndCompanyIdIsNull(Long id)`. The Javadoc says it is the operator's counterpart of `findByIdAndCompanyId`.

#### 4. Principal and authorities

**File**: `src/main/java/pl/regavio/stockahead/security/AccountUserDetailsService.java`, `security/CompanyUser.java`

**Intent**: An operator principal carries `ROLE_OPERATOR` only and `companyId == null`. Managers keep MANAGER+TECHNICIAN, and technicians keep TECHNICIAN.

**Contract**: The company id is null-safe for OPERATOR. The role→authorities mapping is exposed as one static helper that the filter can reuse. The `CompanyUser` Javadoc notes that `companyId()` is null only for the operator.

#### 5. Activity filter

**File**: `src/main/java/pl/regavio/stockahead/security/AccountActivityFilter.java`

**Intent**: An active operator's session stays CURRENT. A missing or inactive operator account is logged out as `deactivated`. Company accounts behave exactly as today.

**Contract**: If the principal has `companyId == null`, load it with `findByIdAndCompanyIdIsNull` and require `role == OPERATOR`. Otherwise use `findByIdAndCompanyId`. The role-change check compares the account role's expected authorities with the session authorities. The Javadoc is updated.

#### 6. URL authorization

**File**: `src/main/java/pl/regavio/stockahead/security/SecurityConfig.java`

**Intent**: Deny by default, so a future warehouse route that forgets `@PreAuthorize` still refuses the operator.

**Contract**:
- Public: `/login`, `/setup/**`, `/actuator/health/**` and static resources.
- `/` and `/error` (including the error dispatch): `authenticated()`.
- `/operator/**`: `hasRole("OPERATOR")`.
- `anyRequest()`: `hasAnyRole("MANAGER", "TECHNICIAN")`.

#### 7. Tests

**File**: `src/test/java/pl/regavio/stockahead/security/AccountActivityFilterTest.java`, `AccountUserDetailsServiceTest.java`, new `account/V20MigrationTests.java`, `CompanyFixtures.java`

**Intent**: Prove the schema and authentication invariants.

**Contract**:
- Migration: a second OPERATOR insert fails on the unique index. An OPERATOR with a company fails the CHECK. A MANAGER or TECHNICIAN with a null company fails the CHECK.
- User details: OPERATOR → `[ROLE_OPERATOR]` with a null company id.
- Filter: an active operator passes, and an inactive or missing one goes to `/login?deactivated`. The existing `companyId == null` logout case changes to "a principal whose id is not an operator is logged out".
- `CompanyFixtures` gains `operator(email, rawPassword, active)`, and `cleanUp()` deletes company-less accounts too.

### Success Criteria:

#### Automated Verification:

- `./mvnw verify` passes, including the existing setup, role-gate and company-isolation suites
- V20 migration tests: second operator rejected, operator-with-company rejected, manager-without-company rejected
- AccountUserDetailsService and AccountActivityFilter tests cover the operator principal (current, deactivated, missing)

#### Manual Verification:

- `./mvnw spring-boot:test-run`: existing setup → manager login → warehouse pages still work as before

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful before proceeding to the next phase.

---

## Phase 2: Setup creates the operator

### Overview

`/setup` creates the single operator with the same credential rules as the accounts page, then closes.

### Changes Required:

#### 1. Shared credential validation

**File**: new `src/main/java/pl/regavio/stockahead/account/AccountCredentials.java`, `account/CompanyAccountController.java`

**Intent**: Use one definition of acceptable email and password for every account-creating form: the accounts page now, setup in this change, registration in S-09.

**Contract**: A small final helper that validates (raw email, password, confirmation) and returns either the canonical email or the first failing message key with arguments. It keeps the existing order and keys: `accounts.error.emailRequired`, `emailTooLong` {255}, `emailInvalid`, `passwordTooShort` {12}, `passwordMismatch`. `CompanyAccountController.create` delegates to it without changing behaviour, and its constants move into the helper.

#### 2. Setup controller

**File**: `src/main/java/pl/regavio/stockahead/account/SetupController.java`

**Intent**: Create the operator instead of company + manager. Setup is closed exactly when an operator exists.

**Contract**:
- `POST /setup` takes `email, password, confirmPassword, token` and no `companyName`. It checks the token first, then the `AccountCredentials` checks.
- The re-check, advisory lock, re-check under the lock and `DataIntegrityViolationException` catch all stay, keyed on `existsByRole(Role.OPERATOR)`.
- It saves an active OPERATOR with `company = null` and redirects to `/login?setup`. `GET /setup` redirects to `/login` once an operator exists.
- The company-name constant and messages are removed, and the class Javadoc is updated.

#### 3. Views and messages

**File**: `src/main/resources/templates/setup.html`, `templates/login.html`, `src/main/resources/messages.properties`

**Intent**: The setup form talks about creating the operator account and drops the company field. The login confirmation says the operator account was created.

**Contract**:
- Remove the `setup.error.companyNameRequired` and `setup.error.companyNameTooLong` keys.
- Rename `login.status.managerCreated` to `login.status.operatorCreated`, with Polish text.
- The template keeps `fragments :: head` and no topbar (lessons.md: shared head).

#### 4. Tests

**File**: `src/test/java/pl/regavio/stockahead/account/AuthenticationIntegrationTests.java`, `CompanyAccountIntegrationTests.java` (unchanged expectations), `MessagesBundleTests`/`TemplateConventionTests` (must stay green)

**Intent**: Rewrite the setup coverage for the operator.

**Contract**: The guard in `setUp()` checks for a leaked OPERATOR instead of a MANAGER. The new tests cover:
- Valid setup creates one active OPERATOR with no company and no new company row.
- Setup stays open while companies and managers exist but no operator.
- Setup is closed for both GET and POST once an operator exists.
- Two concurrent setups → exactly one operator.
- Wrong token, invalid email, an 11-character password and a mismatched confirmation each create nothing, re-render the form and keep the email.
- The operator can log in after setup.

### Success Criteria:

#### Automated Verification:

- `./mvnw verify` passes
- Setup tests: operator created without company, concurrent race yields exactly one operator, validation failures create nothing
- `CompanyAccountIntegrationTests` passes unchanged after the validation extraction

#### Manual Verification:

- Fresh `./mvnw spring-boot:test-run`: `/setup` shows email/password/token only, creates the operator, then `/setup` redirects to login

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful before proceeding to the next phase.

---

## Phase 3: Operator company list, home routing and guardrail tests

### Overview

The operator's only screen, role-based landing, a role-aware topbar, the per-controller refusal tests and the documentation updates.

### Changes Required:

#### 1. Company list query

**File**: `src/main/java/pl/regavio/stockahead/account/CompanyRepository.java` (plus a row record, e.g. `account/CompanyListRow.java`)

**Intent**: One query returns every company with its first-created manager's email, newest first. The operator list is intentionally cross-company.

**Contract**: The row has `name, managerEmail (nullable), createdAt, status`. `managerEmail` is the email of the company's MANAGER account with the smallest `(created_at, id)`, active or not. Rows are ordered by `companies.created_at DESC, companies.id DESC`. JPQL with a correlated subquery is preferred. A native query is acceptable, but its Javadoc must state that it is the intentional cross-company read for the operator.

#### 2. Operator controller and view

**File**: new `src/main/java/pl/regavio/stockahead/account/OperatorCompanyController.java`, new `src/main/resources/templates/operator-companies.html`, `messages.properties` (if status labels go through the bundle)

**Intent**: The operator sees the company list and nothing else.

**Contract**:
- `GET /operator/companies` with `@PreAuthorize("hasRole('OPERATOR')")`. The model holds the rows.
- The table columns are: Nazwa, E-mail kierownika, Data rejestracji, Status.
- Status labels: PENDING "Oczekuje", ACTIVE "Aktywna", REJECTED "Odrzucona", BLOCKED "Zablokowana".
- The empty state is "Brak zarejestrowanych firm."
- The template uses `fragments :: head` and `fragments :: topbar`, with no inline styles (lessons.md).

#### 3. Home routing

**File**: `src/main/java/pl/regavio/stockahead/account/HomeController.java`

**Intent**: The operator never sees the warehouse dashboard.

**Contract**: `GET /` redirects to `/operator/companies` for a principal with `ROLE_OPERATOR`. Everyone else gets the dashboard as today. The login success URL stays `/`.

#### 4. Role-aware topbar

**File**: `src/main/resources/templates/fragments.html`

**Intent**: The operator sees only the company-list link and an "Operator" badge. Managers and technicians see exactly what they see today.

**Contract**:
- Wrap the existing warehouse links in `sec:authorize="hasAnyRole('MANAGER','TECHNICIAN')"`.
- Add a "Firmy" link (`/operator/companies`) under `hasRole('OPERATOR')`.
- The badges are Kierownik `hasRole('MANAGER')`, Technik `hasRole('TECHNICIAN') and !hasRole('MANAGER')`, and Operator `hasRole('OPERATOR')`.
- Leave alone the markup that existing tests match exactly.

#### 5. Guardrail tests

**File**: new `src/test/java/pl/regavio/stockahead/account/OperatorAccessIntegrationTests.java`

**Intent**: Prove that the operator sees only the list and has no path to company data, with one refusal test per warehouse controller (roadmap S-08 risk).

**Contract**:
- List content with two companies and fixtures:
  - Company A has two managers; the first-created one is deactivated, and its email is shown.
  - Company B has only a technician, so the manager email is empty.
  - Rows are ordered newest first, with status labels and the empty state.
- `/` redirects the operator to `/operator/companies`.
- The operator's session survives several requests.
- Manager and technician get 403 on `/operator/companies`. An anonymous user is redirected to login.
- The operator gets 403 on at least one route of each controller: `PartController` (`GET /parts`, `POST /parts`), `PartImportController`, `DeliveryController`, `StockCorrectionController`, `ProjectController` (`GET /projects`, `GET /projects/{id}` of a fixture project), `ProjectBomController`, `ProjectLinkController`, `OrderController`, `PickingController`, `ShoppingListController`, `CompanyAccountController` and `ManagerPingController`.
- Each POST case asserts the fixture row is unchanged.

#### 6. Documentation

**File**: `AGENTS.md`, `context/foundation/prd-v2.md`, `context/foundation/roadmap.md`

**Intent**: Record the new conventions and the decisions resolved here.

**Contract**:
- AGENTS.md § Company isolation:
  - URL deny-by-default: `/operator/**` requires OPERATOR, and every other non-public route requires MANAGER/TECHNICIAN.
  - The operator is loaded with `findByIdAndCompanyIdIsNull`.
  - The company-list query is an intentional cross-company read.
- prd-v2 § Open Questions 1 is marked resolved: the token is enough, decided 2026-11-04.
- roadmap S-08 unknowns: both answered (the token is enough; setup uses the shared validation). Leave Status for the roadmap workflow.
- roadmap S-09 unknown on reusing the validation: note that `AccountCredentials` exists.

### Success Criteria:

#### Automated Verification:

- `./mvnw verify` passes
- `OperatorAccessIntegrationTests`: list content/ordering/first-created manager, operator→403 on every warehouse controller with no data changed, manager/technician→403 on `/operator/companies`
- `TemplateConventionTests` passes for `operator-companies.html`

#### Manual Verification:

- Fresh `./mvnw spring-boot:test-run`: setup → operator login lands on the company list with only the "Firmy" link and "Operator" badge; empty state shown
- After inserting a company + manager via SQL, the row shows name, manager email, date and "Aktywna"; typing `/parts` or `/manager/accounts` as the operator gives 403
- Manager login still shows the full topbar and dashboard

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful before proceeding to the next phase.

---

## Testing Strategy

### Unit Tests:

- `AccountUserDetailsServiceTest`: authorities and company id per role, including OPERATOR.
- `AccountActivityFilterTest`: operator current, deactivated and missing; manager/technician cases unchanged.

### Integration Tests:

- `V20MigrationTests`: the DB invariants (one operator; operator ⇔ no company).
- `AuthenticationIntegrationTests`: operator setup, closing, race and validation.
- `OperatorAccessIntegrationTests`: the list and the guardrail (one operator→403 per warehouse controller, with no mutation).

### Manual Testing Steps:

1. Start `./mvnw spring-boot:test-run` on a fresh container and complete `/setup` with a 12+ character password.
2. Log in as the operator: you land on `/operator/companies` with the empty state.
3. Insert a company and a manager by SQL, reload, and check the row.
4. Open `/parts`, `/orders` and `/manager/accounts` as the operator: each returns 403.
5. Log in as that manager: the dashboard and topbar are unchanged, and `/operator/companies` returns 403.

## Performance Considerations

The list runs one query with a correlated subquery per company row. That is fine at the expected tens of companies, and `accounts_company_id_idx` (V14) supports the subquery.

## Migration Notes

V20 is additive except for the replaced role CHECK. Existing manager/technician rows already satisfy `(role = 'OPERATOR') = (company_id IS NULL)`. On a database where the old setup created a manager, `/setup` reopens until an operator is created. This is intended.

## References

- Roadmap slice: `context/foundation/roadmap.md` § S-08
- PRD: `context/foundation/prd-v2.md` FR-025, FR-029, FR-033, Open Questions 1
- Setup lock pattern: `src/main/java/pl/regavio/stockahead/account/SetupController.java:32-38,99-120`
- Credential validation source: `src/main/java/pl/regavio/stockahead/account/CompanyAccountController.java:37-41,86-107`
- Session-login test pattern: `src/test/java/pl/regavio/stockahead/account/CompanyAccountIntegrationTests.java:122`
- Lessons: `context/foundation/lessons.md` (constraint violations at write boundaries; wrong-role 403 per route; shared head/stylesheet)

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles. See `references/progress-format.md`.

### Phase 1: Operator role in the schema and authentication

#### Automated

- [x] 1.1 `./mvnw verify` passes, including the existing setup, role-gate and company-isolation suites — 0e49818
- [x] 1.2 V20 migration tests: second operator rejected, operator-with-company rejected, manager-without-company rejected — 0e49818
- [x] 1.3 AccountUserDetailsService and AccountActivityFilter tests cover the operator principal (current, deactivated, missing) — 0e49818

#### Manual

- [x] 1.4 `./mvnw spring-boot:test-run`: existing setup → manager login → warehouse pages still work as before — 0e49818

### Phase 2: Setup creates the operator

#### Automated

- [x] 2.1 `./mvnw verify` passes — aad6a22
- [x] 2.2 Setup tests: operator created without company, concurrent race yields exactly one operator, validation failures create nothing — aad6a22
- [x] 2.3 `CompanyAccountIntegrationTests` passes unchanged after the validation extraction — aad6a22

#### Manual

- [x] 2.4 Fresh `./mvnw spring-boot:test-run`: `/setup` shows email/password/token only, creates the operator, then `/setup` redirects to login — aad6a22

### Phase 3: Operator company list, home routing and guardrail tests

#### Automated

- [x] 3.1 `./mvnw verify` passes
- [x] 3.2 `OperatorAccessIntegrationTests`: list content/ordering/first-created manager, operator→403 on every warehouse controller with no data changed, manager/technician→403 on `/operator/companies`
- [x] 3.3 `TemplateConventionTests` passes for `operator-companies.html`

#### Manual

- [x] 3.4 Fresh `./mvnw spring-boot:test-run`: setup → operator login lands on the company list with only the "Firmy" link and "Operator" badge; empty state shown
- [x] 3.5 After inserting a company + manager via SQL, the row shows name, manager email, date and "Aktywna"; typing `/parts` or `/manager/accounts` as the operator gives 403
- [x] 3.6 Manager login still shows the full topbar and dashboard
