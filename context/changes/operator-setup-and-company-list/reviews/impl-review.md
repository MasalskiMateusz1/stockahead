<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: Operator Setup and Company List

- **Plan**: context/changes/operator-setup-and-company-list/plan.md
- **Scope**: Full plan
- **Reviewed phases**: 1, 2, 3
- **Date**: 2026-11-05
- **Verdict**: APPROVED
- **Findings**: 0 critical, 2 warnings, 3 observations

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| Plan Adherence | PASS |
| Scope Discipline | PASS |
| Safety & Quality | WARNING |
| Architecture | PASS |
| Pattern Consistency | WARNING |
| Success Criteria | PASS |

## Findings

### F1 — Setup with an already-used email shows a misleading "try again" error

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: src/main/java/pl/regavio/stockahead/account/SetupController.java:106-108
- **Detail**: Setup intentionally stays open while managers exist, so the operator email can collide with an existing manager or technician email. Under the advisory lock, when no operator exists yet, a `DataIntegrityViolationException` can only come from `accounts_email_canonical_idx`. The catch block renders `setup.error.accountCreationFailed` ("Nie udało się założyć konta. Spróbuj ponownie."), but retrying can never succeed. No test covers this catch. The accounts page handles the same collision with `accounts.error.duplicateEmail` (CompanyAccountController.java:104).
- **Fix**: In the catch block, render `accounts.error.duplicateEmail` (the email is kept). Add an AuthenticationIntegrationTests case: seed a manager with email X, POST /setup with X, and expect the form to re-render with that error and no operator created. Drop `setup.error.accountCreationFailed` if nothing else uses it.
- **Decision**: FIXED — catch renders accounts.error.duplicateEmail; new AuthenticationIntegrationTests case; removed setup.error.accountCreationFailed

### F2 — Setup form lacks the client-side limits of the accounts form

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Pattern Consistency
- **Location**: src/main/resources/templates/setup.html:17-26
- **Detail**: `AccountCredentials` now enforces a password of at least 12 characters and an email of at most 255 on both forms, but only accounts-new.html has `maxlength="255"` on the email field and `minlength="12"` on the password fields.
- **Fix**: Add `maxlength="255"` to the email input and `minlength="12"` to both password inputs, as in accounts-new.html.
- **Decision**: FIXED — maxlength="255" / minlength="12" added to setup.html

### F3 — Orphaned message key setup.error.passwordMismatch

- **Severity**: 💡 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Pattern Consistency
- **Location**: src/main/resources/messages.properties:2
- **Detail**: Setup now uses `accounts.error.passwordMismatch` through AccountCredentials. The old key is referenced only by MessagesBundleTests.java:36.
- **Fix**: Delete the key and its MessagesBundleTests assertion, as was done for the companyName* keys.
- **Decision**: FIXED — key and its MessagesBundleTests case removed

### F4 — Plant time zone duplicated in CompanyListRow

- **Severity**: 💡 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Pattern Consistency
- **Location**: src/main/java/pl/regavio/stockahead/account/CompanyListRow.java:14
- **Detail**: A second `ZoneId.of("Europe/Warsaw")` is hard-coded here; the existing shared formatter is `orders/OrderMoments` (`@orderMoments` in templates).
- **Fix**: Reuse the zone constant from OrderMoments (or add a date-only method there and use it from the template).
- **Decision**: SKIPPED

### F5 — Status th:switch has no fallback case

- **Severity**: 💡 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Pattern Consistency
- **Location**: src/main/resources/templates/operator-companies.html:24-29
- **Detail**: A future CompanyStatus value would render an empty cell without any error. All four current values are handled and tested.
- **Fix**: Add `<span th:case="*" th:text="${company.status}"></span>`.
- **Decision**: SKIPPED

## Notes (not findings)

- The roadmap S-08 `Status: in-progress` edit was made by /10x-implement's roadmap sync, which is the roadmap workflow, so it is not drift.
- The company list has no pagination, which the plan explicitly scoped out. Revisit after S-09.
- Automated: `./mvnw verify` passed at f1bab7f (661 tests, 0 failures); the epilogue commit touched only plan.md and change.md. Manual 1.4, 2.4, 3.4–3.6 were confirmed by the user.
