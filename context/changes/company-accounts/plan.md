# Company Accounts Implementation Plan

## Overview

Roadmap M-2 **S-07** (FR-030, FR-032, FR-029, FR-028). Replace the technicians-only account page with one per-company **accounts** page where a manager creates, deactivates, reactivates and changes the role of technician and manager accounts in their own company. The rule "a company always keeps at least one active manager" holds under concurrency: every transition that could remove the last active manager takes a row lock on the company first.

## Current State Analysis

- `account/TechnicianAccountController.java` serves `/manager/technicians` (list, new form, create, deactivate, reactivate). It already scopes everything to `principal.companyId()`, returns 404 for another company's id via `findByIdAndCompanyId`, and filters to `Role.TECHNICIAN` only: a manager's id returns 404 today (`TechnicianAccountController.java:121-123`, asserted by `TechnicianAccountIntegrationTests.managerIdAndMissingIdBothReturn404WithoutChangingAccounts`).
- Create validation (canonical email, max 255, email pattern, password ≥ 12, confirmation) and the `DataIntegrityViolationException` → re-rendered form handling for duplicate email are already in place (`TechnicianAccountController.java:67-111`). Email is globally unique (`V1__create_accounts.sql`, `accounts.email UNIQUE`).
- V14 dropped `accounts_single_manager_idx` (`V14__create_companies.sql`, last line), so the schema already allows several managers per company. No code creates a second one.
- `security/AccountActivityFilter.java:42-48` ends a session on the next request when its account is inactive or missing, redirecting to `/login?deactivated`. It does not compare roles. `AccountUserDetailsService.authoritiesFor` gives a manager `ROLE_MANAGER` + `ROLE_TECHNICIAN`. Authorities are fixed at login and stored in the session.
- Order assignment already accepts any active manager or technician of the company (`AccountRepository.findAssignable`, `OrderController.isAssignable` at `OrderController.java:622-624`). Extra managers become assignable with no change.
- `SetupController` still uses a global `existsByRole(Role.MANAGER)` to close setup. That belongs to S-08 and isn't affected by extra managers.
- Navigation: topbar link "Konta techników" (`fragments.html:22`), dashboard tile (`dashboard.html:42`), icon case `'technicians'` (`fragments.html:50`). Messages use `technicians.*` keys (`messages.properties:38-43`).
- `AGENTS.md:41` names `account/TechnicianAccountIntegrationTests` as the two-company test pattern to copy.

## Desired End State

A manager opens **Konta** (`/manager/accounts`) and sees every account of their company (email, role, status), never another company's. They can:
- create a technician or a manager account (same validation as today, with a role select);
- deactivate or reactivate any account of their company, including their own;
- promote a technician to manager or demote a manager to technician.

A deactivation or demotion that would leave the company with **zero active managers** is refused. The list re-renders with a clear error and nothing changes. This holds when two managers act at the same moment: exactly one of two mutually-exclusive requests succeeds. An account whose role was changed is logged out on its next request (`/login?roleChanged`), the same way a deactivated account is today. Another company's account id returns 404 on every route, and a technician gets 403 on every route.

Verification: `./mvnw verify` green, including the new concurrency tests. Manual walk-through below.

### Key Discoveries:

- `TechnicianAccountController.java:121-130`: `setActive` runs inside a `TransactionTemplate`; the guarded transition belongs in that same transaction.
- `parts/PartRepository` `findByIdInAndCompanyIdForUpdate` is the established pessimistic-lock query style (`@Lock(PESSIMISTIC_WRITE)` style repository method). Mirror it for the company row.
- `orders/*ConcurrencyTests` and `parts/DeliveryConcurrencyTests` show the latch-and-threads pattern against Testcontainers Postgres.
- `CompanyFixtures.account(company, email, password, role, active)` already seeds managers and technicians per company.
- lessons.md "Handle DB constraint violations at write boundaries" (duplicate email → re-render) and "Test the wrong-role case for every role-gated route" (403 test per route) both apply.

## What We're NOT Doing

- No account deletion: deactivate, never delete (prd-v2 § Preserved).
- No change to registration, setup or the operator role (S-08, S-09). `SetupController`'s global `existsByRole(MANAGER)` stays as is.
- No neutral "email already exists" message to prevent account enumeration. The form is manager-only, and the decision is owned by S-09's open question.
- No password reset, email change or email notifications.
- No live authority refresh: a role change ends the session instead of rewriting authorities in place.
- No schema migration. The rule is enforced in the application under a company-row lock, not by a DB constraint.
- No redirect from the old `/manager/technicians` URLs. The routes are internal and only linked from our own templates.

## Implementation Approach

Generalise the existing controller rather than add a parallel one: same validation, same company scoping, same transaction shape. Wrap every transition that can reduce the number of active managers (deactivating a manager, demoting a manager) in one transaction that first locks the company row (`CompanyRepository.findByIdForUpdate(principal.companyId())`, which takes `FOR NO KEY UPDATE` on Postgres), then loads the target with `findByIdAndCompanyId`, counts the company's other active managers, and refuses when the count is zero. Locking the company row instead of using an advisory key gives one lock per company with no int-key collisions, and it's the same row S-11/S-12 status transitions will lock. Transitions that can only add managers (create, reactivate, promote) don't need the rule. They still run in a transaction, but a manager count can't drop through them.

A refused transition redirects back to `/manager/accounts` with a flash error (`RedirectAttributes.addFlashAttribute("error", …)`, already used in `parts/DeliveryController` and others) shown through `fragments :: alerts`.

Role change reuses the activity filter: the filter compares the session's `ROLE_MANAGER` authority with the account's current DB role and ends the session on a mismatch, redirecting to `/login?roleChanged`.

## Critical Implementation Details

- **Timing & lifecycle**: the lock must be taken **before** the target account and the manager count are read, in the same transaction, or two concurrent deactivations can each see "one other active manager" and both commit. Counting "other active managers" must exclude the target account itself, which also covers self-deactivation and self-demotion.
- **Timing & lifecycle**: `AccountActivityFilter` already runs a `findByIdAndCompanyId` per authenticated request. The role check must reuse that same lookup, not add a second query.
- **Accepted window**: the filter checks the caller before the controller takes the lock, so a manager deactivated or demoted while their request waits on the lock still finishes that one request with manager rights. The last-active-manager rule is unaffected because the count reads committed state under the lock. The caller is not re-checked inside the transaction. Their session ends on the next request.

## Phase 1: Company accounts page with managers

### Overview

Rename the technicians page to a company accounts page covering both roles. Add manager creation, and enforce the last-active-manager rule on deactivation under a company-row lock, with sequential, cross-company and concurrency tests.

### Changes Required:

#### 1. Company row lock

**File**: `src/main/java/pl/regavio/stockahead/account/CompanyRepository.java`

**Intent**: Give account transitions a per-company mutex so that concurrent "last manager" checks serialise.

**Contract**: `Optional<Company> findByIdForUpdate(Long id)`, a `@Lock(PESSIMISTIC_WRITE)` JPQL lookup of one company row, in the same style as `PartRepository.findByIdInAndCompanyIdForUpdate`. On Postgres, Hibernate 7 emits `FOR NO KEY UPDATE`, which doesn't block foreign-key inserts into company-scoped tables. Don't replace it with a native `SELECT … FOR UPDATE`, which would block every insert that references the company row while the lock is held.

#### 2. Active-manager count

**File**: `src/main/java/pl/regavio/stockahead/account/AccountRepository.java`

**Intent**: Let the controller ask "how many other active managers does this company have" in one query, and list all of a company's accounts.

**Contract**: `long countByCompanyIdAndRoleAndActiveTrueAndIdNot(Long companyId, Role role, Long excludedId)` (or an equivalent JPQL count). `List<Account> findByCompanyIdOrderByEmailAsc(Long companyId)` replaces the technician-only list call. Keep `findByCompanyIdAndRoleOrderByEmailAsc` only if something else still uses it.

#### 3. Accounts controller

**File**: `src/main/java/pl/regavio/stockahead/account/TechnicianAccountController.java` → rename to `CompanyAccountController.java`

**Intent**: Serve the company accounts page for both roles. Create takes a role. Deactivate and reactivate work on any account of the company. Deactivating the last active manager is refused.

**Contract**: all routes are `@PreAuthorize("hasRole('MANAGER')")` and take the company only from `@AuthenticationPrincipal CompanyUser`:
- `GET /manager/accounts` → `accounts-list` (all company accounts, by email).
- `GET /manager/accounts/new` → `accounts-new`.
- `POST /manager/accounts` with `email`, `password`, `confirmPassword`, `role`. `role` must parse to `MANAGER` or `TECHNICIAN`; anything else re-renders with `accounts.error.roleInvalid`. Existing validation and duplicate-email handling are unchanged. The form echoes the role back on error.
- `POST /manager/accounts/{id}/deactivate`: in one transaction, lock the company row, load the target with `findByIdAndCompanyId` (404 if absent), and if the target is an active `MANAGER` with zero *other* active managers, change nothing and redirect with flash error `accounts.error.lastActiveManager`. Otherwise deactivate (idempotent).
- `POST /manager/accounts/{id}/reactivate`: load scoped (404 if absent), reactivate (idempotent). No lock-guarded rule needed.
- All successful actions redirect to `/manager/accounts`.

#### 4. Templates and navigation

**Files**: `src/main/resources/templates/technicians-list.html` → `accounts-list.html`; `technicians-new.html` → `accounts-new.html`; `fragments.html`; `dashboard.html`

**Intent**: Show both roles and offer the role choice, built on the shared head/topbar/alerts per lessons.md "Build every template on the shared head and stylesheet".

**Contract**: The list has columns E-mail / Rola / Status / Akcja and renders `fragments :: alerts` for the flash error. Role labels: "Kierownik" / "Technik". The create form adds a required `<select name="role">` with options `TECHNICIAN` (default) and `MANAGER`. Page title, h1 and the topbar/dashboard link become "Konta" pointing at `/manager/accounts`. The icon case may keep its key or be renamed; it must stay consistent between `fragments.html` and `dashboard.html`. No inline styles.

#### 5. Messages

**File**: `src/main/resources/messages.properties` (and any locale variant present)

**Intent**: Move account messages under one namespace and add the new errors.

**Contract**: rename `technicians.error.*` → `accounts.error.*` (same texts). Add `accounts.error.roleInvalid` ("Wybierz rolę konta.") and `accounts.error.lastActiveManager` ("Nie można dezaktywować ani zmienić roli ostatniego aktywnego kierownika firmy."). Update every Java/template reference. `MessagesBundleTests` stays green.

#### 6. Tests

**Files**: `src/test/java/pl/regavio/stockahead/account/TechnicianAccountIntegrationTests.java` → `CompanyAccountIntegrationTests.java`; new `src/test/java/pl/regavio/stockahead/account/LastManagerConcurrencyTests.java`

**Intent**: Carry over every existing technician test against the new routes, adapt the one whose expected behaviour changes, and cover the new rule.

**Contract**:
- Existing tests are ported to `/manager/accounts`. `managerIdAndMissingIdBothReturn404…` becomes: another manager's id **is** manageable (200/redirect), a missing id is still 404.
- New: manager creates a **manager** account that can log in and reach a manager-only route. An invalid `role` value is rejected without creating an account.
- New: with one active manager, deactivating them (self) is refused with the error shown and the account stays active. With two active managers, one deactivates the other, then the remaining one is refused. Self-deactivation with another active manager present succeeds and ends the caller's session on the next request.
- New: an inactive manager doesn't count. With managers A (active) and B (inactive), deactivating A is refused.
- Two-company: company B's manager gets 404 on deactivate/reactivate of company A's manager id, the list never shows A's managers, and company A having a single manager never blocks company B.
- Wrong role: a technician gets 403 on every `/manager/accounts` route (GET list, GET new, POST create, POST deactivate, POST reactivate).
- Concurrency (`LastManagerConcurrencyTests`): two active managers M1, M2 of one company; two threads released by a latch, M1 deactivates M2 while M2 deactivates M1. Exactly one succeeds and the company ends with exactly one active manager. Repeat several iterations. A second test: a company-A deactivation holding the lock does not delay a company-B deactivation (B completes while A's transaction is held open, in the style of `orders/CrossCompanyAllocationLockTests`).

#### 7. Agent docs

**File**: `AGENTS.md`

**Intent**: Keep the pointer to the two-company pattern valid.

**Contract**: `AGENTS.md:41` names `account/CompanyAccountIntegrationTests` instead of `account/TechnicianAccountIntegrationTests`.

### Success Criteria:

#### Automated Verification:

- Full build and test suite passes: `./mvnw verify`
- `CompanyAccountIntegrationTests` covers manager creation, last-active-manager refusal (including self and inactive-manager cases), two-company 404s and technician 403 on every route
- `LastManagerConcurrencyTests` passes: concurrent mutual deactivation leaves exactly one active manager, and one company's lock does not block another company
- No references to `/manager/technicians` or `technicians.error.` remain: `grep -rn "manager/technicians\|technicians\.error" src AGENTS.md` returns nothing
- `TemplateConventionTests` and `MessagesBundleTests` pass

#### Manual Verification:

- As the setup manager, open "Konta", create a second manager, log in as them in another browser and confirm the full manager menu
- Deactivate the original manager from the second manager's session. Then try to deactivate yourself as the only remaining active manager and see the error with the account still active
- Reactivate the original manager and confirm they can log in again
- The page layout matches the other list pages (shared topbar, table style, alert style)

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful before proceeding to the next phase.

---

## Phase 2: Role change

### Overview

Allow promoting a technician to manager and demoting a manager to technician, guarded by the same lock and rule. An account whose role changed is logged out on its next request.

### Changes Required:

#### 1. Role-change route

**File**: `src/main/java/pl/regavio/stockahead/account/CompanyAccountController.java`

**Intent**: Add the second transition that can remove a manager, sharing the phase 1 guard so that deactivate and demote serialise against each other.

**Contract**: `POST /manager/accounts/{id}/role` with `role` (`MANAGER` | `TECHNICIAN`; anything else → flash `accounts.error.roleInvalid`), `@PreAuthorize("hasRole('MANAGER')")`. In one transaction, lock the company row, load the target scoped (404 if absent), and if the target is an active `MANAGER`, the new role is `TECHNICIAN`, and there are zero other active managers, refuse with flash `accounts.error.lastActiveManager`. Otherwise set the role (idempotent when unchanged). Allowed on inactive accounts too, since an inactive manager never counts toward the rule. Extract the "lock company → load scoped → check last active manager" step into one private helper used by both deactivate and demote, so there is a single place for the invariant.

#### 2. Role control in the list

**File**: `src/main/resources/templates/accounts-list.html`

**Intent**: Let the manager trigger promote or demote from the row.

**Contract**: each row gets one form posting to `/manager/accounts/{id}/role` with a hidden `role` set to the opposite role. Button labels: "Zmień na kierownika" / "Zmień na technika". Shared button classes only.

#### 3. Session invalidation on role change

**Files**: `src/main/java/pl/regavio/stockahead/security/AccountActivityFilter.java`; `src/main/resources/templates/login.html`; `messages.properties`

**Intent**: A demoted manager must not keep `ROLE_MANAGER` in their session, and a promoted technician gets correct menus after logging in again.

**Contract**: the filter's existing scoped lookup also compares the account's DB role with whether the principal holds `ROLE_MANAGER`. On a mismatch it clears the context, invalidates the session and redirects to `/login?roleChanged`. The inactive/missing check runs first and keeps `/login?deactivated`, so an account that is both inactive and role-changed goes to `/login?deactivated`. `login.html` shows `login.status.roleChanged` ("Twoja rola została zmieniona — zaloguj się ponownie.") under `param.roleChanged`, built like the existing `param.deactivated` line.

#### 4. Tests

**Files**: `CompanyAccountIntegrationTests.java` (including the stale-session / role-mismatch tests, against real accounts); `LastManagerConcurrencyTests.java`. `security/AccountActivityFilterTest` (Mockito, unresolvable-principal cases) stays unchanged.

**Intent**: Prove the second removal path keeps the invariant and that stale sessions end.

**Contract**:
- Promote a technician: their next request with the old session goes to `/login?roleChanged`. After logging in again they reach a manager-only route.
- Demote one of two managers: they lose manager routes (session ended, then 403 on a manager route after logging in again). Demoting the only active manager, including self-demotion, is refused with the role unchanged.
- An invalid `role` value is refused. Company B's manager gets 404 on company A's account id. A technician gets 403 on the route.
- An order assigned to a demoted manager stays assigned and visible to them as a technician (the assignee rule accepts technicians).
- Concurrency: with two active managers, M1 demotes M2 while M2 deactivates M1 (latch-released threads, several iterations). Exactly one succeeds, and the company ends with ≥ 1 active manager.

### Success Criteria:

#### Automated Verification:

- Full build and test suite passes: `./mvnw verify`
- Role-change tests pass: promote, demote, refused last-manager demotion (including self), invalid role, cross-company 404, technician 403
- Stale-session tests pass: a changed-role account's old session redirects to `/login?roleChanged`
- Concurrency test passes: concurrent demote + deactivate never leaves zero active managers
- Assigned-order-survives-demotion test passes

#### Manual Verification:

- Promote a technician to manager. Their open browser session is sent to login with the "role changed" message, and after logging in again they see the manager menu
- Demote the second manager. Their session ends, and after logging in again they see only technician features
- As the only active manager, try to demote yourself and see the error with the role unchanged

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful.

---

## Testing Strategy

### Unit Tests:

- None required beyond integration tests. The rule's correctness depends on real Postgres row locks (AGENTS.md: don't mock the database for invariants).

### Integration Tests:

- `CompanyAccountIntegrationTests`: every route × {happy path, technician 403, company-B 404}, plus the last-active-manager matrix (self, other, inactive-doesn't-count, two companies independent).
- `LastManagerConcurrencyTests`: mutual deactivation, demote vs deactivate, cross-company lock independence.

### Manual Testing Steps:

1. Start with `./mvnw spring-boot:test-run`, run setup, and log in as the manager.
2. Konta → create a manager and a technician. Log in as each in a separate browser.
3. Deactivate, reactivate, promote and demote across the two manager sessions, and confirm the last-manager error appears exactly when only one active manager would remain.
4. Confirm that changed or deactivated sessions are sent to login with the right message.

## Performance Considerations

The company-row lock is held only for the length of one small transaction and only on manager-removing transitions. Contention is per company and negligible at expected volumes. The filter's role check adds no query.

## Migration Notes

No schema change. Existing data is unaffected.

## References

- Roadmap item: `context/foundation/roadmap.md` § S-07
- PRD: `context/foundation/prd-v2.md` FR-029, FR-030, FR-032
- Pattern: `src/main/java/pl/regavio/stockahead/account/TechnicianAccountController.java:113-131`
- Lock style: `src/main/java/pl/regavio/stockahead/parts/PartRepository.java` (`findByIdInAndCompanyIdForUpdate`)
- Session end: `src/main/java/pl/regavio/stockahead/security/AccountActivityFilter.java:28-48`
- Cross-company lock test style: `src/test/java/pl/regavio/stockahead/orders/CrossCompanyAllocationLockTests.java`

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles. See `references/progress-format.md`.

### Phase 1: Company accounts page with managers

#### Automated

- [x] 1.1 Full build and test suite passes: `./mvnw verify` — 9ebe59a
- [x] 1.2 `CompanyAccountIntegrationTests` covers manager creation, last-active-manager refusal (including self and inactive-manager cases), two-company 404s and technician 403 on every route — 9ebe59a
- [x] 1.3 `LastManagerConcurrencyTests` passes: concurrent mutual deactivation leaves exactly one active manager, and one company's lock does not block another company — 9ebe59a
- [x] 1.4 No references to `/manager/technicians` or `technicians.error.` remain: `grep -rn "manager/technicians\|technicians\.error" src AGENTS.md` returns nothing — 9ebe59a
- [x] 1.5 `TemplateConventionTests` and `MessagesBundleTests` pass — 9ebe59a

#### Manual

- [x] 1.6 As the setup manager, open "Konta", create a second manager, log in as them in another browser and confirm the full manager menu — 9ebe59a
- [x] 1.7 Deactivate the original manager from the second manager's session. Then try to deactivate yourself as the only remaining active manager and see the error with the account still active — 9ebe59a
- [x] 1.8 Reactivate the original manager and confirm they can log in again — 9ebe59a
- [x] 1.9 The page layout matches the other list pages (shared topbar, table style, alert style) — 9ebe59a

### Phase 2: Role change

#### Automated

- [x] 2.1 Full build and test suite passes: `./mvnw verify`
- [x] 2.2 Role-change tests pass: promote, demote, refused last-manager demotion (including self), invalid role, cross-company 404, technician 403
- [x] 2.3 Stale-session tests pass: a changed-role account's old session redirects to `/login?roleChanged`
- [x] 2.4 Concurrency test passes: concurrent demote + deactivate never leaves zero active managers
- [x] 2.5 Assigned-order-survives-demotion test passes

#### Manual

- [x] 2.6 Promote a technician to manager. Their open browser session is sent to login with the "role changed" message, and after logging in again they see the manager menu
- [x] 2.7 Demote the second manager. Their session ends, and after logging in again they see only technician features
- [x] 2.8 As the only active manager, try to demote yourself and see the error with the role unchanged
