# Operator Approves or Rejects Companies Implementation Plan

## Overview

Roadmap slice S-11. The operator approves a pending company (PENDING → ACTIVE), after which its manager reaches the full warehouse, or rejects it (PENDING → REJECTED). A rejected registration stays on record, its users fail login with the generic bad-credentials message, and its email can register again (US-01, FR-024, FR-025, FR-026, prd-v2 Business Logic Changes › company lifecycle).

## Current State Analysis

- `companies.status` already allows PENDING/ACTIVE/REJECTED/BLOCKED (`V14__create_companies.sql`, `account/CompanyStatus.java`). Registration creates a PENDING company plus its active MANAGER in one transaction (`account/RegistrationController.java:125-146`).
- The operator's only screen is the read-only list `GET /operator/companies` (`account/OperatorCompanyController.java`, `templates/operator-companies.html`). `CompanyListRow` carries no company id, so no row can be targeted.
- `AccountActivityFilter` already redirects users of a non-ACTIVE company to `/awaiting-approval` and keeps the session, so an approved company's existing session works on its next request (`security/AccountActivityFilter.java:28-37`). REJECTED is currently treated like PENDING (fail-closed placeholder from S-09).
- Email is globally unique twice over: `accounts_email_key` from `V1__create_accounts.sql` (`email … UNIQUE`, never dropped) and `accounts_email_canonical_idx` on `lower(btrim(email))` from `V4__normalize_account_emails.sql`. Login resolves an account with the native `AccountRepository.findByCanonicalEmail` (`account/AccountRepository.java:14-15`), which assumes at most one row per email. `AccountLookupConventionTests` restricts that query to login.
- `CompanyRepository.findByIdForUpdate` (`account/CompanyRepository.java:22-24`) already locks a company row with `FOR NO KEY UPDATE`.

## Desired End State

- On `/operator/companies`, each PENDING row has an "Akcje" column with "Zatwierdź" (posts directly) and "Odrzuć" (opens a confirmation page naming the company and manager email, then posts). Non-PENDING rows show no actions.
- Approve: the company becomes ACTIVE, and its manager reaches the dashboard on the next request, in the same session or a new login. Then US-01 runs end to end.
- Reject: the company becomes REJECTED. Every account of that company is deactivated and its email is released. A login with the correct password shows the generic `login.error.badCredentials`. An open session ends on its next request, redirecting to `/login?error`. The operator list still shows the company as "Odrzucona" with its email. The same email can register a new company, which appears as a separate PENDING row.
- A stale action (the company is no longer PENDING) changes nothing and returns to the list with the info message "Status firmy zmienił się — odświeżono listę." An unknown company id answers 404.
- Verify with `./mvnw verify` plus the manual steps below.

### Key Discoveries:

- `accounts_email_key` (V1) still exists, so a partial canonical index alone would not free the email. V21 must drop the constraint.
- In `AccountActivityFilter` the inactive check runs before the company gate (`security/AccountActivityFilter.java:110-118`). A rejected account is also inactive, so the released check must come first, or the user gets `?deactivated` instead of the generic message.
- Flash plus PRG back to the list is the existing pattern (`account/CompanyAccountController.java:125-161`, `redirectAttributes.addFlashAttribute`). `.btn-danger` exists in `static/css/app.css:371`.
- `/operator/**` already requires OPERATOR at the URL level (`security/SecurityConfig.java:53`). New routes still get `@PreAuthorize("hasRole('OPERATOR')")` and wrong-role 403 tests (lessons.md, "Test the wrong-role case").
- `findByEmail` is only used by tests. After V21 it could match two rows (a released one and a live one), but no test re-registers a released email through it.

## What We're NOT Doing

- Block and unblock (S-12). BLOCKED keeps today's `/awaiting-approval` behaviour.
- Approving a REJECTED company, or un-rejecting. Only PENDING → ACTIVE and PENDING → REJECTED exist. A rejected registrant registers again.
- Email notifications of approval or rejection (prd-v2 Open Question 2), and a reject reason or note field.
- A "rejected" login message. The failure is the generic bad-credentials text (FR-024 Socrates resolution).
- An audit table of who approved or rejected when. The company status plus `accounts.email_released_at` is the record.
- JavaScript `confirm()` dialogs. Reject is confirmed on a server-rendered page.

## Implementation Approach

Phase 1 makes a "released" account a first-class state in the schema, the login lookup and the activity filter. Nothing sets the flag yet, so this phase is safe to land alone and is fully testable with fixtures. Phase 2 adds a small transactional lifecycle service that locks the company row, acts only if the company is PENDING, and on reject releases and deactivates the company's accounts in the same transaction. Operator routes, the confirmation page and the list column sit on top of it.

## Critical Implementation Details

- **State sequencing (filter):** evaluate "released" before "inactive". A released account is also `active = false` (enforced by a V21 CHECK), so the existing deactivated branch would otherwise win and show `login.status.deactivated`.
- **Timing (reject transaction):** lock the company row with `findByIdForUpdate` before re-reading its status, and run the account release (`email_released_at = now()`, `active = false` for all accounts of that company) in the same transaction as the status change. Two concurrent operator clicks then serialize on the row lock, and the loser sees a non-PENDING status and becomes the stale no-op.

## Phase 1: Email release and generic lockout

### Overview

Introduce `accounts.email_released_at`. Released accounts are invisible to login and to email uniqueness, and an open session of a released account ends with the generic login failure.

### Changes Required:

#### 1. Migration V21

**File**: `src/main/resources/db/migration/V21__account_email_release.sql`

**Intent**: Let a released account keep its real email while freeing it for a new account, and enforce the invariants in the database.

**Contract**: add nullable `email_released_at TIMESTAMPTZ` to `accounts`. Drop constraint `accounts_email_key`. Drop and re-create `accounts_email_canonical_idx` as `UNIQUE (lower(btrim(email))) WHERE email_released_at IS NULL`. Add CHECK `accounts_released_inactive_check` (`email_released_at IS NULL OR NOT active`) and CHECK `accounts_operator_not_released_check` (`email_released_at IS NULL OR role <> 'OPERATOR'`).

#### 2. Account entity

**File**: `src/main/java/pl/regavio/stockahead/account/Account.java`

**Intent**: Map the new column, and drop the now-false `unique = true` on `email`.

**Contract**: `Instant emailReleasedAt` with getter/setter and an `isEmailReleased()` convenience. Javadoc: a released account belongs to a rejected registration and never logs in.

#### 3. Login lookup

**File**: `src/main/java/pl/regavio/stockahead/account/AccountRepository.java`

**Intent**: Login ignores released accounts, so a rejected registrant's correct password fails generically and a re-registered email resolves to the live account.

**Contract**: `findByCanonicalEmail` native SQL gains `AND email_released_at IS NULL`. Javadoc on `findByEmail` notes it can match a released row and is test-only.

#### 4. Activity filter

**File**: `src/main/java/pl/regavio/stockahead/security/AccountActivityFilter.java`

**Intent**: An open session of a released account ends on its next request with the generic login failure.

**Contract**: new state `RELEASED`, evaluated first for company accounts. It invalidates the session and redirects to `contextPath + "/login?error"`. The DEACTIVATED, ROLE_CHANGED and COMPANY_NOT_ACTIVE ordering is otherwise unchanged. Update the class javadoc. Users of a PENDING or BLOCKED company still go to `/awaiting-approval`.

#### 5. Tests

**Files**: `src/test/java/pl/regavio/stockahead/account/V21MigrationTests.java` (new, following `V20MigrationTests`), `security/AccountActivityFilterTest.java`, `security/AccountUserDetailsServiceTest.java` or `account/AuthenticationIntegrationTests.java`, `CompanyFixtures`

**Intent**: Pin the schema invariants and the lockout behaviour.

**Contract**: `CompanyFixtures` gains a way to release an account (or a parameter on `account(...)`). Cases are listed under Success Criteria.

### Success Criteria:

#### Automated Verification:

- `./mvnw verify` passes
- V21MigrationTests: a second live account with a released account's email (any case or whitespace) inserts; two live accounts with the same canonical email still violate the index; a released active account and a released operator are rejected by CHECKs
- Integration: a released account's login with the correct password redirects to `/login?error` and creates no session
- Integration: a live account sharing a released account's email logs in as itself (its own company id in the principal)
- AccountActivityFilterTest: released account → session invalidated, redirect `/login?error`; the released check wins over inactive; pending, blocked and deactivated paths are unchanged

#### Manual Verification:

- With a manager of a PENDING company logged in, run `UPDATE accounts SET active = false, email_released_at = now() WHERE email = '<email>'`. The next click lands on the login page with "Nieprawidłowy e-mail lub hasło." and logging in again fails the same way

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful before proceeding to the next phase.

---

## Phase 2: Approve and reject from the operator list

### Overview

Add the lifecycle transitions and the operator UI for them, with locking, stale-click handling, the confirmation page and end-to-end tests of US-01.

### Changes Required:

#### 1. Lifecycle service

**File**: `src/main/java/pl/regavio/stockahead/account/CompanyLifecycle.java` (new `@Service`)

**Intent**: The single place that changes a company's status, so S-12 can add block and unblock next to it.

**Contract**: `@Transactional approve(Long companyId)` and `reject(Long companyId)` return an outcome (`DONE` / `NOT_PENDING`) and throw a not-found exception (mapped to 404) for an unknown id. Each locks via `CompanyRepository.findByIdForUpdate`, acts only if the status is PENDING, and `reject` also releases every account of the company (`email_released_at = now`, `active = false`), either through a bulk `@Modifying` update on `AccountRepository` scoped by `company_id` or by loading the company's accounts. Javadoc marks this as an operator-only cross-company write.

#### 2. Operator list row

**Files**: `account/CompanyListRow.java`, `account/CompanyRepository.java`

**Intent**: Rows need the company id to target actions.

**Contract**: `CompanyListRow` gains a leading `Long id`, and `findAllForOperatorList` selects `c.id`. The manager-email subquery is unchanged, so a rejected company still shows its released manager's email.

#### 3. Operator routes

**File**: `src/main/java/pl/regavio/stockahead/account/OperatorCompanyController.java`

**Intent**: Expose approve, the reject confirmation and reject, all operator-only.

**Contract**: `POST /operator/companies/{id}/approve`; `GET /operator/companies/{id}/reject` renders `operator-companies-reject` with name and manager email when PENDING (it redirects to the list with the stale message when not PENDING, and answers 404 for an unknown id); `POST /operator/companies/{id}/reject`. All carry `@PreAuthorize("hasRole('OPERATOR')")`. The POSTs redirect to `/operator/companies` with a flash message: `operator.companies.approved` / `operator.companies.rejected` (naming the company) on success, `operator.companies.statusChanged` when not PENDING. The confirm page loads the company through a read that returns name and first-manager email for one id (a by-id variant of the list projection).

#### 4. Templates and messages

**Files**: `templates/operator-companies.html`, `templates/operator-companies-reject.html` (new), `src/main/resources/messages.properties`

**Intent**: Show the actions and feedback using the shared head, topbar and stylesheet (lessons.md, "Build every template on the shared head").

**Contract**: the list gains an "Akcje" column with two small POST forms (or a link to the confirm page) on PENDING rows only, plus a flash success/info alert above the table. The confirm page names the company and email, posts "Odrzuć firmę" (`btn-danger`) and links back with "Anuluj". New keys: `operator.companies.approved=Firma {0} została zatwierdzona.`, `operator.companies.rejected=Firma {0} została odrzucona.`, `operator.companies.statusChanged=Status firmy zmienił się — odświeżono listę.`, plus confirm-page labels. No inline styles. `TemplateConventionTests` must keep passing.

#### 5. Tests

**Files**: `account/OperatorAccessIntegrationTests.java` or a new `account/OperatorCompanyLifecycleIntegrationTests.java`, plus a concurrency test following `LastManagerConcurrencyTests`

**Intent**: Cover US-01 and every transition, guard and boundary.

**Contract**: cases are listed under Success Criteria.

#### 6. Agent rules

**File**: `AGENTS.md` (Company isolation section)

**Intent**: Record the new load-bearing rules for future slices.

**Contract**: one bullet saying that an account with `email_released_at` set belongs to a rejected registration, is excluded from login and email uniqueness, and that any new email lookup must filter `email_released_at IS NULL`. One bullet saying that company status changes go through `CompanyLifecycle` under the company row lock.

### Success Criteria:

#### Automated Verification:

- `./mvnw verify` passes
- US-01 path: register → manager session sits on `/awaiting-approval` → operator approves → the same manager session reaches the dashboard and the parts list on its next request
- Reject path: the operator rejects via the confirm page → the company is REJECTED and its account is inactive with `email_released_at` set → the manager's correct-password login redirects to `/login?error` → the operator list still shows the row as "Odrzucona" with its email → the same email registers again and the list shows two rows
- An open manager session of the rejected company is redirected to `/login?error` on its next request
- Stale clicks: approve or reject on an ACTIVE or REJECTED company changes nothing and redirects to the list with the statusChanged flash; GET of the reject page for a non-PENDING company redirects the same way
- Unknown company id → 404 on approve, the reject page and reject
- Manager and technician get 403 on all three new routes and change nothing; anonymous is redirected to login; POST without a CSRF token is rejected
- Concurrency: simultaneous approve and reject on one PENDING company leave exactly one outcome (ACTIVE with live accounts, or REJECTED with released accounts), never a mix
- The list shows action buttons only on PENDING rows

#### Manual Verification:

- In `./mvnw spring-boot:test-run`, register a company, log in as the operator, approve it, and confirm the manager reaches the warehouse
- Register a second company, reject it via the confirm page, confirm the generic login failure, and register again with the same email
- Open two operator tabs, approve in one, then click reject in the other, and see the "Status firmy zmienił się" message
- The list and confirm page look consistent with the rest of the app

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful before proceeding to the next phase.

---

## Testing Strategy

### Unit Tests:

- `AccountActivityFilterTest`: the RELEASED state and its precedence.
- `AccountUserDetailsServiceTest`: unchanged behaviour (released filtering lives in SQL, so it is covered by integration).

### Integration Tests:

- V21 migration invariants against real Postgres.
- Login and session lockout of released accounts.
- The full approve and reject flows, stale clicks, 404, 403, CSRF.
- Concurrent approve versus reject on real Postgres (two threads, one latch).

### Manual Testing Steps:

1. Register company A, approve it as the operator, and work as A's manager.
2. Register company B, reject it, check the generic login failure, and re-register B's email.
3. Do a stale click from a second operator tab.

## Performance Considerations

None. These are single-row locks on operator actions at a handful of companies.

## Migration Notes

V21 is additive (a nullable column) and rebuilds one index plus drops one constraint. Existing rows all have `email_released_at IS NULL`, so the rebuilt partial index accepts current data. Rollback would mean a new migration; no data is rewritten.

## References

- Roadmap: `context/foundation/roadmap.md` S-11 (Outcome, Unknowns, Risk)
- PRD: `context/foundation/prd-v2.md` FR-024, FR-025, FR-026, Business Logic Changes › company lifecycle
- Prior slices: `context/archive/2026-11-05-company-registration/plan.md`, `context/archive/2026-11-04-operator-setup-and-company-list/plan.md`
- Patterns: `account/CompanyAccountController.java:125-161` (flash + PRG), `account/CompanyRepository.java:22-24` (row lock), `account/LastManagerConcurrencyTests.java` (concurrency test), `account/V20MigrationTests.java` (migration test)

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles. See `references/progress-format.md`.

### Phase 1: Email release and generic lockout

#### Automated

- [x] 1.1 `./mvnw verify` passes — 4fe8cc1
- [x] 1.2 V21MigrationTests: a second live account with a released account's email (any case or whitespace) inserts; two live accounts with the same canonical email still violate the index; a released active account and a released operator are rejected by CHECKs — 4fe8cc1
- [x] 1.3 Integration: a released account's login with the correct password redirects to `/login?error` and creates no session — 4fe8cc1
- [x] 1.4 Integration: a live account sharing a released account's email logs in as itself (its own company id in the principal) — 4fe8cc1
- [x] 1.5 AccountActivityFilterTest: released account → session invalidated, redirect `/login?error`; the released check wins over inactive; pending, blocked and deactivated paths are unchanged — 4fe8cc1

#### Manual

- [x] 1.6 With a manager of a PENDING company logged in, run `UPDATE accounts SET active = false, email_released_at = now() WHERE email = '<email>'`. The next click lands on the login page with "Nieprawidłowy e-mail lub hasło." and logging in again fails the same way — 4fe8cc1

### Phase 2: Approve and reject from the operator list

#### Automated

- [x] 2.1 `./mvnw verify` passes — b052608
- [x] 2.2 US-01 path: register → manager session sits on `/awaiting-approval` → operator approves → the same manager session reaches the dashboard and the parts list on its next request — b052608
- [x] 2.3 Reject path: the operator rejects via the confirm page → the company is REJECTED and its account is inactive with `email_released_at` set → the manager's correct-password login redirects to `/login?error` → the operator list still shows the row as "Odrzucona" with its email → the same email registers again and the list shows two rows — b052608
- [x] 2.4 An open manager session of the rejected company is redirected to `/login?error` on its next request — b052608
- [x] 2.5 Stale clicks: approve or reject on an ACTIVE or REJECTED company changes nothing and redirects to the list with the statusChanged flash; GET of the reject page for a non-PENDING company redirects the same way — b052608
- [x] 2.6 Unknown company id → 404 on approve, the reject page and reject — b052608
- [x] 2.7 Manager and technician get 403 on all three new routes and change nothing; anonymous is redirected to login; POST without a CSRF token is rejected — b052608
- [x] 2.8 Concurrency: simultaneous approve and reject on one PENDING company leave exactly one outcome (ACTIVE with live accounts, or REJECTED with released accounts), never a mix — b052608
- [x] 2.9 The list shows action buttons only on PENDING rows — b052608

#### Manual

- [x] 2.10 In `./mvnw spring-boot:test-run`, register a company, log in as the operator, approve it, and confirm the manager reaches the warehouse — b052608
- [x] 2.11 Register a second company, reject it via the confirm page, confirm the generic login failure, and register again with the same email — b052608
- [x] 2.12 Open two operator tabs, approve in one, then click reject in the other, and see the "Status firmy zmienił się" message — b052608
- [x] 2.13 The list and confirm page look consistent with the rest of the app — b052608
