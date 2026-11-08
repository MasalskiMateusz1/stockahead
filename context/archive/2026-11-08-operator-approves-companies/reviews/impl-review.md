<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: Operator Approves or Rejects Companies

- **Plan**: context/changes/operator-approves-companies/plan.md
- **Scope**: Full plan
- **Reviewed phases**: 1, 2
- **Date**: 2026-11-08
- **Verdict**: NEEDS ATTENTION
- **Findings**: 0 critical, 2 warnings, 5 observations

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| Plan Adherence | PASS |
| Scope Discipline | WARNING |
| Safety & Quality | WARNING |
| Architecture | PASS |
| Pattern Consistency | WARNING |
| Success Criteria | WARNING |

Automated: `./mvnw verify` passed (re-run during review, exit 0; 765 tests, 0 failures in the phase run). Manual: 1.6 and 2.10–2.13 marked done after the user confirmed them in session.

## Findings

### F1 — `findByEmail` can now match two rows and nothing keeps it out of production code

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: src/main/java/pl/regavio/stockahead/account/AccountRepository.java:18
- **Detail**: Since V21, one email can have a released row and a live row, so `Optional<Account> findByEmail(email)` throws `IncorrectResultSizeDataAccessException` on a re-registered email. Its javadoc says "tests only", but `security/AccountLookupConventionTests` only checks `findByCanonicalEmail`. Nothing stops a future `src/main` caller, which would also break the new AGENTS.md rule ("any new email lookup must filter `email_released_at IS NULL`"). Today there are no `src/main` callers; tests use it 75 times.
- **Fix**: Extend `AccountLookupConventionTests` so it fails when `findByEmail(` appears in any `src/main/java` file except `AccountRepository.java`.
- **Decision**: FIXED — AccountLookupConventionTests.productionCodeNeverCallsTheTestOnlyFindByEmail (break-checked)

### F2 — Released-login test passes without the new filter, and its session check can be skipped

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Success Criteria
- **Location**: src/test/java/pl/regavio/stockahead/account/AuthenticationIntegrationTests.java:321-339
- **Detail**: `releasedAccountCannotLogInWithTheCorrectPasswordAndGetsNoSession` (criterion 1.3) seeds an account with `active=false`. Spring already rejects a disabled account with `/login?error`, so the test passes even without the `email_released_at IS NULL` filter. Its session assertions are wrapped in `if (session != null)`, so they can be skipped silently. The next test, `liveAccountSharingAReleasedAccountsEmailLogsInAsItself`, does exercise the filter, so the behaviour is covered elsewhere.
- **Fix**: Replace the `if (session != null)` block with an unconditional assertion that there is no session or no security context. In the test's comment, say that the filter itself is pinned by the shared-email test.
- **Decision**: FIXED — session assertions unconditional; comment points to the shared-email test for filter coverage

### F3 — Unplanned `DataIntegrityViolationException` → `actionFailed` path with no test

- **Severity**: 💬 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Scope Discipline
- **Location**: src/main/java/pl/regavio/stockahead/account/OperatorCompanyController.java:58, 89
- **Detail**: The subagent added this from lessons.md ("Handle DB constraint violations at write boundaries"), with a new key `operator.companies.actionFailed`. Neither transition can realistically break a constraint: approve changes only the status, and release removes rows from the partial index. So the path is effectively dead, and no controller test covers it. It is harmless and in line with the lesson.
- **Fix**: Add a one-line note to the plan (or change.md) that this is a deliberate defensive addition.
- **Decision**: FIXED — note added to change.md

### F4 — V21 is forward-only once one rejection has been re-registered

- **Severity**: 💬 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: src/main/resources/db/migration/V21__account_email_release.sql:3-6
- **Detail**: Once a rejected email has registered again, `accounts_email_key` cannot be restored, and pre-V21 application code would fail login for that email: the unfiltered `findByCanonicalEmail` would match two rows. Flyway community has no down migrations, so this is expected, but it is not written down anywhere.
- **Fix**: Note in `context/deployment/deploy-plan.md` (or the plan's Migration Notes) that rolling back the app past V21 is unsafe once a reject plus re-registration has happened.
- **Decision**: FIXED — forward-only note added to plan.md Migration Notes (no deploy-plan.md exists yet)

### F5 — No upgrade-path test for V21 on existing data

- **Severity**: 💬 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Success Criteria
- **Location**: src/test/java/pl/regavio/stockahead/account/V21MigrationTests.java
- **Detail**: Every case migrates an empty database straight to the latest version. None seeds accounts at V20 and then runs V21, as `V14MigrationTests:134-138` does. The migration is low risk on existing data (a nullable column, and CHECKs that trivially hold).
- **Fix**: Optionally add one test: migrate to `target("20")`, seed accounts, migrate, and assert `email_released_at IS NULL` and that canonical uniqueness still holds.
- **Decision**: FIXED — V21MigrationTests.upgradeFromV20KeepsExistingAccountsLiveAndCanonicallyUnique

### F6 — Success-flash name is re-read after the transaction

- **Severity**: 💬 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Pattern Consistency
- **Location**: src/main/java/pl/regavio/stockahead/account/OperatorCompanyController.java:99
- **Detail**: `afterTransition` calls `findOperatorRowById` again after commit just to get the company name. This is safe because there is no rename or delete route, but it costs an extra query and sits outside the lock.
- **Fix**: Accept as is, or have `CompanyLifecycle` return the name with the outcome.
- **Decision**: FIXED — CompanyLifecycle returns Result(outcome, companyName) read under the row lock; controller re-read removed

### F7 — Minor style splits: 404 exception style and hard-coded button labels

- **Severity**: 💬 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Pattern Consistency
- **Location**: src/main/java/pl/regavio/stockahead/account/CompanyLifecycle.java:33-40; src/main/resources/templates/operator-companies.html:38, 40
- **Detail**: The service throws a custom `@ResponseStatus(NOT_FOUND)` exception, while the rest of the codebase (including `rejectForm` in the same controller) uses `ResponseStatusException(NOT_FOUND)`. The list buttons hard-code "Zatwierdź" and "Odrzuć", while the confirm page uses message keys; the existing list headers are hard-coded too. Both are cosmetic, and the 404 tests pass.
- **Fix**: Accept as is (the custom exception keeps web types out of the service).
- **Decision**: SKIPPED
