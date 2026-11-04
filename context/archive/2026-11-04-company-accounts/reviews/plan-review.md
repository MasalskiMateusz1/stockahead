<!-- PLAN-REVIEW-REPORT -->
# Plan Review: Company Accounts Implementation Plan

- **Plan**: context/changes/company-accounts/plan.md
- **Mode**: Deep
- **Date**: 2026-11-04
- **Verdict**: SOUND
- **Findings**: 0 critical, 1 warning, 2 observations

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| End-State Alignment | PASS |
| Lean Execution | PASS |
| Architectural Fitness | PASS |
| Blind Spots | PASS |
| Plan Completeness | WARNING |

## Grounding
7/7 paths ✓, 6/6 symbols ✓ (findByIdAndCompanyId, findByCompanyIdAndRoleOrderByEmailAsc, findByIdInAndCompanyIdForUpdate, isAssignable, existsByRole, AGENTS.md:41 pointer), brief↔plan ✓, Progress↔Phase ✓ (13/13 criteria mapped)

## Findings

### F1 — Phase 2 filter-test location is left conditional

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Completeness
- **Location**: Phase 2 §3 and §4
- **Detail**: The plan said "filter test, if one exists". `security/AccountActivityFilterTest` exists, but it is a Mockito unit test with `verifyNoInteractions(accountRepository)` and can't prove the role comparison. The plan also didn't say which redirect wins when an account is both inactive and role-changed.
- **Fix**: Put the role-mismatch tests in `CompanyAccountIntegrationTests`. The inactive/missing check runs first and goes to `/login?deactivated`.
- **Decision**: FIXED

### F2 — The plan says "FOR UPDATE", but Hibernate emits FOR NO KEY UPDATE

- **Severity**: OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Completeness
- **Location**: Implementation Approach; Phase 1 §1; plan-brief "Concurrency guard"
- **Detail**: In Hibernate 7.4.5, `PostgreSQLDialect.getForUpdateString()` returns `" for no key update"`. A literal native `FOR UPDATE` would block foreign-key inserts into company-scoped tables while the lock is held.
- **Fix**: Change the wording to `@Lock(PESSIMISTIC_WRITE)` (Postgres: `FOR NO KEY UPDATE`) and say not to replace it with a native FOR UPDATE.
- **Decision**: FIXED

### F3 — The acting manager is not re-checked under the lock

- **Severity**: OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Blind Spots
- **Location**: Critical Implementation Details
- **Detail**: The filter checks the caller before the lock is taken. A manager deactivated or demoted while their request waits on the lock finishes that one request with manager rights. The last-active-manager rule still holds.
- **Fix**: Document the accepted one-request window in Critical Implementation Details.
- **Decision**: FIXED
