<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: Konta techników (S-02) Implementation Plan

- **Plan**: context/changes/technician-accounts/plan.md
- **Scope**: Full plan (4 of 4 phases)
- **Reviewed phases**: 1, 2, 3, 4
- **Date**: 2026-09-28
- **Verdict**: APPROVED
- **Findings**: 0 critical, 1 warning, 2 observations

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| Plan Adherence | PASS |
| Scope Discipline | PASS |
| Safety & Quality | PASS |
| Architecture | PASS |
| Pattern Consistency | PASS |
| Success Criteria | WARNING |

## Findings

### F1 — POST test does not exercise a technician-authorized write

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Success Criteria
- **Location**: src/test/java/pl/regavio/stockahead/account/TechnicianAccountIntegrationTests.java:257
- **Detail**: Phase 3 requires proving that a deactivated technician's next POST cannot perform a business action. The test posts to `/manager/technicians`, which is manager-only even while the technician is active (`TechnicianAccountController.java:60-61`). It proves that the activity filter redirects and invalidates a POST session, but cannot prove prevention of a write the technician was otherwise permitted to make. No technician-authorized write route exists in the current app.
- **Fix**: Record this test limit in the plan and add an integration test against the first technician-authorized write route when that route is implemented.
- **Decision**: SKIPPED — user chose not to change the plan or test for this finding.

### F2 — Java and PostgreSQL trim different legacy email whitespace

- **Severity**: 🔎 OBSERVATION
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Safety & Quality
- **Location**: src/main/java/pl/regavio/stockahead/account/Emails.java:11
- **Detail**: `Emails.canonical` uses Java `strip()`, while `V4__normalize_account_emails.sql:8,19,21` uses PostgreSQL `btrim(email)`, which defaults to ASCII space. A preexisting email with leading or trailing Unicode whitespace could remain stored with that whitespace after migration, while login removes it before lookup. The account could then be unfindable by its apparent address. The plan prescribes these expressions, so this is also a plan edge case.
- **Fix**: Detect legacy emails containing whitespace that Java `strip()` removes but PostgreSQL `btrim()` does not, and resolve those rows before relying on the migrated lookup.
  - Strength: Keeps the database index and Java contract aligned for the affected legacy rows.
  - Tradeoff: Requires a targeted data audit and migration decision for any matches.
  - Confidence: MED — the normalization mismatch is clear, but affected legacy data has not been observed.
  - Blind spot: Production account data was not available to inspect.
- **Decision**: SKIPPED — user chose not to change the migration or lookup contract for this finding.

### F3 — Browser-only manual checks have no durable evidence

- **Severity**: 🔎 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Success Criteria
- **Location**: context/changes/technician-accounts/plan.md:297
- **Detail**: Progress marks the two-browser deactivation check (3.5) and full manual flow (4.3, line 308) complete with commit references, but the reviewed diff contains no browser run notes or screenshots. HTTP integration tests cover the behavior; the review cannot independently verify the stated browser observations.
- **Fix**: Attach a concise manual run note to the change when browser verification is repeated, or distinguish HTTP integration coverage from browser verification in Progress.
- **Decision**: FIXED — recorded the user-reported browser confirmations below.

## Verification

- `./mvnw verify` — PASS on 2026-09-28; 58 tests, 0 failures, 0 errors, 0 skipped; `BUILD SUCCESS`. The run used Testcontainers with local Docker and real PostgreSQL. This satisfies the repeated automated command in phases 1–4.
- Phase 1 migration, canonical login, and uniqueness checks are exercised by `V4MigrationTests` and `AuthenticationIntegrationTests` in the passing suite.
- Phase 2 account creation, validation, duplicate handling, role restrictions on all five routes, 404 behavior, and identity preservation are exercised by `TechnicianAccountIntegrationTests` in the passing suite.
- Phase 3 session invalidation, active account access, public endpoints, and new login after reactivation are exercised by `TechnicianAccountIntegrationTests` in the passing suite, subject to F1.
- Phase 4 regression includes the existing F-01 and S-01 integration tests in the passing suite.
- Manual Progress items 1.5, 2.7, 3.5, and 4.3 are marked `[x]`; browser-specific evidence for 3.5 and 4.3 was not available in the diff.

### Manual confirmations

The user reported these results during implementation; no screenshots or independent browser recording were captured:

- A manager created and deactivated a technician account, and the deactivated account could not log in.
- Reactivation restored login, and the technician did not see the “Konta techników” dashboard link.
- With the technician already signed in, deactivation caused the next `/parts` request to go to the login page.

## Scope

Compared commits `c6c2040` through `3f1390d` with the plan. All planned production areas are represented. No change to parts inventory, reservations, account deletion, password reset, or public registration was found. Unrelated untracked files were left untouched.
