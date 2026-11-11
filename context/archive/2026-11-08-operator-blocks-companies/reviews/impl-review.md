<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: Operator Blocks and Unblocks Companies

- **Plan**: context/changes/operator-blocks-companies/plan.md
- **Scope**: Full plan
- **Reviewed phases**: 1, 2
- **Date**: 2026-11-08
- **Verdict**: APPROVED
- **Findings**: 0 critical, 0 warnings, 5 observations

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| Plan Adherence | PASS |
| Scope Discipline | PASS |
| Safety & Quality | PASS |
| Architecture | PASS |
| Pattern Consistency | PASS |
| Success Criteria | PASS |

Evidence: `./mvnw verify` re-run at f2b2d70 exits 0 (781 tests). All 10 Progress rows are `[x]`. The user confirmed manual checks 1.4, 2.5 and 2.6 in session, and 2.5 was checked against the blocked wording on the gate page. Every changed file is named in the plan or belongs to the change folder.

## Findings

### F1 — Concurrency test name contradicts its assertion

- **Severity**: 💡 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Adherence
- **Location**: src/test/java/pl/regavio/stockahead/account/CompanyLifecycleConcurrencyTests.java:105
- **Detail**: The plan's "exactly one DONE" for simultaneous block and unblock was wrong. If block commits first, unblock then sees BLOCKED and also applies, and the company ends ACTIVE. The test correctly accepts both serial orders, but it is still named `...ApplyExactlyOneTransition`, and the plan text was never corrected.
- **Fix**: Rename to `simultaneousBlockAndUnblockOfActiveCompanyEndInAConsistentSerialOrder`, and add a one-line note to the plan's Phase 2 test contract.
- **Decision**: FIXED — test renamed; correction note added under the plan's Phase 2 Concurrency contract

### F2 — End-to-end blocked-login checks are weaker than Phase 1's

- **Severity**: 💡 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Success Criteria
- **Location**: src/test/java/pl/regavio/stockahead/account/OperatorCompanyLifecycleIntegrationTests.java (blockGatesOpenSessionAndLoginThenUnblockRestoresEveryoneAndKeepsData)
- **Detail**: After the block, the new manager and technician logins assert only the `/login?blocked` Location, not `unauthenticated()`. The gate check asserts the blocked message but not the title. `BlockedCompanyLoginIntegrationTests` covers both, so this is redundancy, not a gap.
- **Fix**: Add `.andExpect(unauthenticated())` to the two post-block login requests in the end-to-end test.
- **Decision**: FIXED — `.andExpect(unauthenticated())` added to both post-block logins

### F3 — Block page reuses the reject page's message keys

- **Severity**: 💡 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Pattern Consistency
- **Location**: src/main/resources/templates/operator-companies-block.html:15-17,24
- **Detail**: `operator.companies.reject.name`, `.managerEmail` and `.cancel` are used on the block page. The plan allows this and the text is identical, but rewording the reject page would silently change the block page too.
- **Fix**: Leave it. If the reject wording ever changes, add `operator.companies.block.*` copies.
- **Decision**: FIXED — `operator.companies.block.name`/`.managerEmail`/`.cancel` added and used by the block page

### F4 — Post-authentication checker hand-copies the framework default

- **Severity**: 💡 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: src/main/java/pl/regavio/stockahead/security/SecurityConfig.java:101-110
- **Detail**: `setPostAuthenticationChecks` replaces Spring's default checker, so the credentials-expired check is copied by hand. If a later Spring Security version adds another default post-check, it would be silently skipped. This is planned and documented, and the default protected checker cannot be delegated to from a bean.
- **Fix**: Accept. Re-check `DaoAuthenticationProvider`'s default post-checks on the next Spring Security major upgrade.
- **Decision**: FIXED — risk accepted; upgrade re-check noted in the `postAuthenticationChecks` Javadoc

### F5 — No unit case for the companyBlocked flag

- **Severity**: 💡 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Success Criteria
- **Location**: src/test/java/pl/regavio/stockahead/security/AccountUserDetailsServiceTest.java
- **Detail**: No unit test asserts that `companyBlocked()` is true for a BLOCKED company and false for an ACTIVE company or the operator. The integration tests cover the behaviour end to end.
- **Fix**: Add two cases to `AccountUserDetailsServiceTest`, one BLOCKED → true and one operator (no company) → false.
- **Decision**: FIXED — BLOCKED → true and ACTIVE → false cases added; operator case now asserts false
