# Load Current Account by (id, company_id) Implementation Plan

## Overview

The authenticated principal `CompanyUser` will carry the account's id, set at login. Every current-account lookup outside login will then load the account by `(accountId, companyId)` from the principal. The cross-company native `findByCanonicalEmail` keeps one caller, the login lookup, which is the only exception AGENTS.md documents. This implements follow-up F3 (Fix B) from `context/changes/company-scoped-picking-completion/follow-ups/review-fixes.md:5-11`.

## Current State Analysis

- `AccountRepository.findByCanonicalEmail` is a native query with no company filter (`account/AccountRepository.java:14-15`). In `src/main` it has 6 callers. One is the login lookup (`security/AccountUserDetailsService.java:28`). The other 5 resolve "the current account" from `authentication.getName()`:
  - `security/AccountActivityFilter.java:32`: per-request active check
  - `orders/PickingController.java:142`: technician's own id for the picking list
  - `orders/PickingController.java:289`: completion reporter
  - `orders/OrderController.java:389`: canceller
  - `parts/StockCorrectionController.java:237`: correction author
- `CompanyUser` has `serialVersionUID = 1L` and carries only `companyId` (`security/CompanyUser.java:14-27`). It is built in one place (`AccountUserDetailsService.java:31-32`), where `account.getId()` is already loaded.
- A grep of `src/test` finds no test that builds its own principal (0 hits for `new CompanyUser`, `WithMockUser`, `with(user(` and `authentication(`). The inspected integration tests sign in through `formLogin()`, for example `orders/PickingIntegrationTests.java:132-133`, so they get the new field automatically.
- `StockCorrectionController` passes `Authentication` through `set` (:107), `adjust` (:150), `apply` (:194) and `applyCorrection` (:225). It never takes `CompanyUser`. Its part lookup is left unscoped on purpose for S-05 (`roadmap.md:156`).
- `findByIdAndCompanyId(Long, Long)` already exists (`AccountRepository.java:29`).

## Desired End State

- `CompanyUser.accountId()` returns the logged-in account's id. `serialVersionUID` is `2L`.
- `grep -rn findByCanonicalEmail src/main/java` lists only the repository declaration and `AccountUserDetailsService`.
- A deactivated account is still logged out on its next request with a redirect to `/login?deactivated`. The filter now checks by `(accountId, companyId)`.
- The completion reporter, canceller and correction author are loaded with `findByIdAndCompanyId(principal.accountId(), principal.companyId())`. A missing row still returns 403. The picking list uses `principal.accountId()` directly.
- A convention test fails the build if `findByCanonicalEmail` shows up anywhere in `src/main/java` outside `AccountRepository` and `AccountUserDetailsService`.
- `./mvnw verify` passes.

### Key Discoveries:

- The only principal construction is `AccountUserDetailsService.java:31-32`, so one edit covers every login path, including all formLogin-based integration tests.
- `AccountUserDetailsServiceTest.java:69-84` asserts `companyId()`. Copy it for `accountId()`. Its `Account` fixtures don't set an id (`Account.setId`, `Account.java:49`).
- `AccountActivityFilter` reads the principal from `SecurityContextHolder` (`AccountActivityFilter.java:29`). It is registered before `AuthorizationFilter` (`SecurityConfig.java:35`). Its deactivated-redirect behaviour is covered by `TechnicianAccountIntegrationTests.java:340-342, 345-348, 386-388`.
- `orders.cancelled_by` and `stock_corrections.account_id` have no composite company FK (F4), so the company check in `findByIdAndCompanyId` is the only company guard for those two writes. `completion_reported_by` is also backed by the V18 composite FK (`V18__company_scoped_completion_reporter.sql:8`).
- `TemplateConventionTests.java` scans source files under a fixed `Path` with `Files`. Copy that pattern for the new guard test.

## What We're NOT Doing

- Scoping `StockCorrectionController`'s part lookups (`findByIdInForUpdate(Set.of(id))`, `findById`) or switching its `reallocateForParts` company argument. Both belong to S-05 (`company-scoped-stock-movements`).
- Adding composite FKs on `cancelled_by` / `stock_corrections.account_id` (F4, handed to S-05).
- Adding Spring Session or any other session persistence.
- Changing the login lookup itself, or changing the 403-on-missing-account responses.
- Handling an operator principal with no company. No operator account exists yet (`Account.java:42-43`, `nullable = false`).

## Implementation Approach

Phase 1 adds the id to the principal and moves the activity filter onto it. No controller changes yet, so the existing suite proves that login and deactivation still work. Phase 2 moves the four controller call sites, then locks the result in with a convention test and an AGENTS.md pointer.

## Critical Implementation Details

- **Stale sessions**: raising `serialVersionUID` to `2L` makes a session serialized before the change fail to deserialize, so the user signs in again. Without the bump, the restored principal would carry `accountId == null`. Production sessions are in memory and are dropped on every deploy anyway (`infrastructure.md:98`).
- **Filter principal type**: the filter must only cast after checking `authentication.getPrincipal() instanceof CompanyUser`. Every other authenticated principal is treated like an inactive account: clear the context, invalidate the session and redirect to `/login?deactivated`. The filter must not throw.

## Phase 1: Principal carries the account id

### Overview

The principal gains `accountId()`, filled at login, and the per-request active check uses it instead of the email.

### Changes Required:

#### 1. Principal

**File**: `src/main/java/pl/regavio/stockahead/security/CompanyUser.java`

**Intent**: Carry the account id next to the company id, so controllers and the filter never need to look the account up by email. Raise the serial version because the serialized shape changes.

**Contract**: The constructor gains a `Long accountId` parameter. New accessor `public Long accountId()`. `serialVersionUID = 2L`. Update the class Javadoc to say that the current account also comes only from here.

#### 2. Login

**File**: `src/main/java/pl/regavio/stockahead/security/AccountUserDetailsService.java`

**Intent**: Pass `account.getId()` into the principal. The email lookup at :28 stays. It is the documented login exception.

**Contract**: The `new CompanyUser(...)` call at :31-32 gains `account.getId()`.

#### 3. Activity filter

**File**: `src/main/java/pl/regavio/stockahead/security/AccountActivityFilter.java`

**Intent**: Check that the current account is still active by `(accountId, companyId)` from the principal, not by email. A principal that is not a `CompanyUser` takes the existing logout-and-redirect path.

**Contract**: The condition at :30-33 becomes: the principal is a `CompanyUser` and `accountRepository.findByIdAndCompanyId(user.accountId(), user.companyId())` holds an active account. Otherwise run the existing branch (:34-40), which clears the context, invalidates the session and redirects to `/login?deactivated`. Drop the `Emails` import.

#### 4. Unit test

**File**: `src/test/java/pl/regavio/stockahead/security/AccountUserDetailsServiceTest.java`

**Intent**: Prove the principal carries the account's id.

**Contract**: New test `principalCarriesTheAccountsId`, modelled on `principalCarriesTheAccountsCompanyId` (:69-84). It sets an id with `setId` on the fixture and asserts `((CompanyUser) userDetails).accountId()`.

### Success Criteria:

#### Automated Verification:

- `AccountUserDetailsServiceTest` passes, including `principalCarriesTheAccountsId`: `./mvnw test -Dtest=AccountUserDetailsServiceTest`
- Deactivation tests pass: `./mvnw test -Dtest=TechnicianAccountIntegrationTests`
- Full build passes: `./mvnw verify`

#### Manual Verification:

- In `./mvnw spring-boot:test-run`, sign in as a technician, have the manager deactivate that account from another browser, then reload as the technician. It redirects to `/login?deactivated`.

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful before proceeding to the next phase.

---

## Phase 2: Controllers resolve the current account from the principal

### Overview

The four controller call sites stop reading the account by email. A convention test keeps `findByCanonicalEmail` limited to login.

### Changes Required:

#### 1. Picking list and completion reporter

**File**: `src/main/java/pl/regavio/stockahead/orders/PickingController.java`

**Intent**: The technician branch of `GET /picking` already has the principal, so it uses `principal.accountId()` with no read. The report-completion action loads the reporter with a company-scoped lookup.

**Contract**:
- At :142-144, drop the `self` lookup and pass `principal.accountId()` to `findByStatusAndAssigneeWithProject`.
- At :289-290, use `accountRepository.findByIdAndCompanyId(principal.accountId(), companyId).orElseThrow(FORBIDDEN)`. The report handler (:254) takes `@AuthenticationPrincipal CompanyUser principal` if it does not already.
- Remove `Authentication` parameters and the `Emails` import only where they become unused. `list` still uses `authentication.getAuthorities()` at :120.

#### 2. Canceller

**File**: `src/main/java/pl/regavio/stockahead/orders/OrderController.java`

**Intent**: Load the canceller by `(accountId, companyId)` from the principal the handler already receives (:338).

**Contract**: At :388-390, use `findByIdAndCompanyId(principal.accountId(), companyId).orElseThrow(FORBIDDEN)`. Drop `Authentication` from the cancel handler and the `Emails` import if they become unused.

#### 3. Correction author

**File**: `src/main/java/pl/regavio/stockahead/parts/StockCorrectionController.java`

**Intent**: Pass the principal through to `applyCorrection` and load the author by `(accountId, companyId)`. Leave part lookups and the `reallocateForParts(part.getCompany().getId(), ...)` call (:253-254) as they are for S-05.

**Contract**:
- `set` (:107) and `adjust` (:150) take `@AuthenticationPrincipal CompanyUser principal` instead of `Authentication authentication`.
- `apply` (:194) and `applyCorrection` (:225) take `CompanyUser principal`.
- At :237, use `findByIdAndCompanyId(principal.accountId(), principal.companyId()).orElseThrow(FORBIDDEN)`.

#### 4. Convention test

**File**: `src/test/java/pl/regavio/stockahead/security/AccountLookupConventionTests.java` (new)

**Intent**: Fail the build if a new call site resolves an account by email outside login. This makes AGENTS.md's single exception enforceable, the same way `TemplateConventionTests` enforces template rules.

**Contract**: One test that walks `src/main/java` and asserts that the only files whose text contains `findByCanonicalEmail` are `account/AccountRepository.java` and `security/AccountUserDetailsService.java`. The assertion message names any offending file.

#### 5. AGENTS.md pointer

**File**: `AGENTS.md`

**Intent**: Tell future agents where the current account comes from, so nobody re-adds an email lookup.

**Contract**: In § Company isolation → "Shared pieces", say that the current account id comes from `security/CompanyUser.accountId()`, loaded with `findByIdAndCompanyId(accountId, companyId)`. The exception sentence at `AGENTS.md:36` stays unchanged. It is accurate again after this change.

### Success Criteria:

#### Automated Verification:

- `grep -rln findByCanonicalEmail src/main/java` lists exactly `AccountRepository.java` and `AccountUserDetailsService.java`
- `AccountLookupConventionTests` passes: `./mvnw test -Dtest=AccountLookupConventionTests`
- Picking, cancel and correction suites pass: `./mvnw test -Dtest='Picking*Tests,OrderCancel*Tests,StockCorrection*Tests'`
- Full build passes: `./mvnw verify`

#### Manual Verification:

- In `./mvnw spring-boot:test-run`, as a technician, `/picking` lists only orders assigned to you. Report completion on a taken order and confirm the order detail shows you as the reporter.
- As a manager, cancel an order and apply one stock correction. The cancellation and the correction both record your account.

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful.

---

## Testing Strategy

### Unit Tests:

- `AccountUserDetailsServiceTest.principalCarriesTheAccountsId`: the principal's `accountId()` equals the account's id.

### Integration Tests:

- Existing suites prove the behaviour stays the same:
  - `TechnicianAccountIntegrationTests`: deactivation redirect through the filter
  - `PickingVisibilityIntegrationTests` / `PickingListAndDetailIntegrationTests`: the technician list filtered by own id
  - `OrderCompletionIntegrationTests` / `PickingIntegrationTests`: reporter
  - `OrderCancelIntegrationTests`: canceller
  - `StockCorrectionIntegrationTests`: author
  - the `*CompanyIsolationIntegrationTests`: cross-company 404s
- `AccountLookupConventionTests`: a source-level guard against new email lookups.

### Manual Testing Steps:

1. Deactivate a signed-in technician. Their next request redirects to `/login?deactivated`.
2. As a technician, open `/picking` and report completion on a taken order. You are recorded as the reporter.
3. As a manager, cancel an order and correct a part's stock. You are recorded on both.

## Performance Considerations

The number of queries per request stays the same. Each email read becomes a primary-key read, and the picking-list technician branch drops its read entirely.

## Migration Notes

There is no schema change. After deploying, every user signs in again. That already happens on every production deploy (in-memory sessions), and the `serialVersionUID` bump makes it certain for any restored dev session.

## References

- Related research: `context/changes/load-current-account/research.md`
- Source follow-up: `context/changes/company-scoped-picking-completion/follow-ups/review-fixes.md:5-11`
- Review finding: `context/changes/company-scoped-picking-completion/reviews/impl-review.md:48-65`
- Pattern for scoped account lookup: `src/main/java/pl/regavio/stockahead/account/TechnicianAccountController.java:125`
- Pattern for source-convention test: `src/test/java/pl/regavio/stockahead/TemplateConventionTests.java`

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles. See `references/progress-format.md`.

### Phase 1: Principal carries the account id

#### Automated

- [x] 1.1 `AccountUserDetailsServiceTest` passes, including `principalCarriesTheAccountsId`: `./mvnw test -Dtest=AccountUserDetailsServiceTest` — d50ef94
- [x] 1.2 Deactivation tests pass: `./mvnw test -Dtest=TechnicianAccountIntegrationTests` — d50ef94
- [x] 1.3 Full build passes: `./mvnw verify` — d50ef94

#### Manual

- [x] 1.4 In `./mvnw spring-boot:test-run`, sign in as a technician, have the manager deactivate that account from another browser, then reload as the technician. It redirects to `/login?deactivated`. — d50ef94

### Phase 2: Controllers resolve the current account from the principal

#### Automated

- [x] 2.1 `grep -rln findByCanonicalEmail src/main/java` lists exactly `AccountRepository.java` and `AccountUserDetailsService.java`
- [x] 2.2 `AccountLookupConventionTests` passes: `./mvnw test -Dtest=AccountLookupConventionTests`
- [x] 2.3 Picking, cancel and correction suites pass: `./mvnw test -Dtest='Picking*Tests,OrderCancel*Tests,StockCorrection*Tests'`
- [x] 2.4 Full build passes: `./mvnw verify`

#### Manual

- [x] 2.5 In `./mvnw spring-boot:test-run`, as a technician, `/picking` lists only orders assigned to you. Report completion on a taken order and confirm the order detail shows you as the reporter.
- [x] 2.6 As a manager, cancel an order and apply one stock correction. The cancellation and the correction both record your account.
