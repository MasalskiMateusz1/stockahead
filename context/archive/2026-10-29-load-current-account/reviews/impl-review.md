<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: Load Current Account by (id, company_id)

- **Plan**: context/changes/load-current-account/plan.md
- **Scope**: Full plan
- **Reviewed phases**: 1, 2
- **Date**: 2026-11-01
- **Verdict**: APPROVED
- **Findings**: 0 critical, 0 warnings, 1 observation

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| Plan Adherence | PASS |
| Scope Discipline | PASS |
| Safety & Quality | PASS |
| Architecture | PASS |
| Pattern Consistency | PASS |
| Success Criteria | PASS |

## Evidence

- Commits: d50ef94 (p1), 2ead9cb (p2). 6d93490 and 9e7b762 in the same range belong to `company-scoped-picking-completion` and were not attributed to this plan.
- All planned files changed as described (MATCH): `CompanyUser` (accountId, serialVersionUID 2L, Javadoc), `AccountUserDetailsService`, `AccountActivityFilter`, `AccountUserDetailsServiceTest.principalCarriesTheAccountsId`, `PickingController`, `OrderController`, `StockCorrectionController`, `AccountLookupConventionTests`, `AGENTS.md`.
- `StockCorrectionController` part lookups and `reallocateForParts` left untouched, as the "NOT doing" list requires.
- `grep -rln findByCanonicalEmail src/main/java` → exactly `AccountRepository.java`, `AccountUserDetailsService.java`.
- `./mvnw verify` → exit 0; 571 tests, 0 failures, 0 errors, 0 skipped (includes `AccountLookupConventionTests`, `AccountUserDetailsServiceTest`, `TechnicianAccountIntegrationTests`, picking/cancel/correction suites).
- Manual items 1.4, 2.5, 2.6 are checked with commit SHAs; manual testing leaves no diff evidence, accepted as reported.

## Findings

### F1 — Activity filter's fallback branch has no direct test

- **Severity**: 💡 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: src/main/java/pl/regavio/stockahead/security/AccountActivityFilter.java:43-49
- **Detail**: The plan's Critical Implementation Details require that a principal which is not a `CompanyUser` is logged out and redirected without throwing. The implementation does that and also adds a `null` accountId/companyId guard (not in the plan, but benign: it covers a stale principal restored without the new field). No test exercises either branch; `TechnicianAccountIntegrationTests` only covers the deactivated-`CompanyUser` path, and nothing in `src/test` references `AccountActivityFilter`. Only formLogin creates principals today, so the branch is currently unreachable.
- **Fix**: Add a small unit test for `AccountActivityFilter` with a `UsernamePasswordAuthenticationToken` whose principal is a plain `User` (and one `CompanyUser` with a null accountId), asserting the context is cleared, the session invalidated and the response redirects to `/login?deactivated`.
- **Decision**: FIXED — added `src/test/java/pl/regavio/stockahead/security/AccountActivityFilterTest.java` (2 tests, passing)
