<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: Company Accounts

- **Plan**: context/changes/company-accounts/plan.md
- **Scope**: Full plan
- **Reviewed phases**: 1, 2
- **Date**: 2026-11-04
- **Verdict**: APPROVED
- **Findings**: 0 critical, 1 warning, 5 observations

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| Plan Adherence | PASS |
| Scope Discipline | PASS |
| Safety & Quality | WARNING |
| Architecture | PASS |
| Pattern Consistency | WARNING |
| Success Criteria | PASS |

Notes: no DRIFT or MISSING items; every test bullet in Phase 1 §6 and Phase 2 §4 has a test with real assertions. EXTRA items are benign (two `MessagesBundleTests` assertions, an order clean-up in `CompanyAccountIntegrationTests` for the RESTRICT FK, five tighter tests). `./mvnw verify` passed (627 tests, 0 failures) on `f6441c0`; `src` is unchanged since. The grep criterion returns nothing. All manual items were confirmed by the user in session.

## Findings

### F1 — Caller's role is not re-checked under the lock

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Safety & Quality
- **Location**: src/main/java/pl/regavio/stockahead/account/CompanyAccountController.java:151-218
- **Detail**: The caller is checked only by `AccountActivityFilter` and `@PreAuthorize`, both before the transaction starts. If M1 demotes or deactivates M2 while M2's request is between the filter and the transaction, that one request still runs with manager rights. The plan accepts this window ("Accepted window" in Critical Implementation Details). It does not mention what M2 can do with it: promote themself back, create a new manager account, or reactivate accounts. Create, reactivate and promote don't take the company lock, so they don't wait behind the demotion. The last-active-manager rule itself is never at risk.
- **Fix A ⭐ Recommended**: Accept the window and record it in the plan as an acknowledged risk.
  - Strength: The plan already chose this trade-off, and the window lasts milliseconds. Only an already-trusted manager of the same company can use it.
  - Tradeoff: A demoted manager racing the demotion could, in theory, undo it once.
  - Confidence: HIGH — the plan explicitly accepts this window.
  - Blind spot: The self-re-promotion detail was not weighed when the plan accepted the window.
- **Fix B**: Make every write route take the company lock, and re-load the caller under it. Refuse unless the caller is an active MANAGER.
  - Strength: Closes the window completely, because demotion and caller-check serialise on the same row.
  - Tradeoff: Create and reactivate now take the lock too. That needs more code and a regression test, and the real race can't be tested end to end.
  - Confidence: MED — the change is simple, but it widens the lock to every write route.
  - Blind spot: Lock contention is unmeasured, though at expected volumes it is likely negligible.
- **Decision**: FIXED via Fix B — every account write (create, deactivate, reactivate, role) locks the company row and re-checks the caller is an active manager (403 otherwise); race test `managerDemotedWhileTheirRequestWaitsOnTheLockCannotPromoteThemselfBack` added (break-check red); race tests now accept 403 for the losing caller.

### F2 — Account transactions don't use LockRetry, and no comment explains why

- **Severity**: 💡 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Pattern Consistency
- **Location**: src/main/java/pl/regavio/stockahead/account/CompanyAccountController.java:130,167
- **Detail**: Sibling lock users (`DeliveryController`, `StockCorrectionController`) wrap their transactions in `LockRetry`. Here there is a single lock with a fixed order (company row, then account row), so a deadlock can't happen and leaving out `LockRetry` is defensible. Nothing in the code says so, though.
- **Fix**: Add a one-line comment on `lockAndLoadGuarded` explaining why `LockRetry` isn't needed (single lock, fixed order).
- **Decision**: FIXED — Javadoc on `lockCompanyAsActiveManager` explains why `LockRetry` isn't needed (single lock, always first).

### F3 — No test that the lock is FOR NO KEY UPDATE

- **Severity**: 💡 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: src/main/java/pl/regavio/stockahead/account/CompanyRepository.java:14-22
- **Detail**: The plan relies on Hibernate emitting `FOR NO KEY UPDATE`, which doesn't block foreign-key inserts into tables that reference the company. The Hibernate 7.4.5 dialect does emit it, but no test checks that. `companyAsHeldLockDoesNotDelayCompanyBsDeactivation` only shows that another company isn't blocked.
- **Fix**: Add a test that holds company A's lock and checks that inserting a part or account for company A still completes.
- **Decision**: FIXED — `heldCompanyLockDoesNotBlockInsertsReferencingTheSameCompany` added to `LastManagerConcurrencyTests` (break-check: native `FOR UPDATE` → timeout, red).

### F4 — Stale Javadoc on findAssignable

- **Severity**: 💡 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Pattern Consistency
- **Location**: src/main/java/pl/regavio/stockahead/account/AccountRepository.java:39
- **Detail**: It still says "active technicians and the manager", but a company can now have several managers.
- **Fix**: Change "the manager" to "managers".
- **Decision**: FIXED — Javadoc now says "active technicians and managers".

### F5 — Any integrity violation on create is reported as a duplicate email

- **Severity**: 💡 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: src/main/java/pl/regavio/stockahead/account/CompanyAccountController.java:117-122
- **Detail**: Every `DataIntegrityViolationException` is shown as `duplicateEmail`, including an FK failure if the company row were gone. The only realistic constraint here is the email uniqueness index, and the team rule (catch and re-render) is met.
- **Fix**: Accept as is. Optionally, check the constraint name before showing the duplicate-email message.
- **Decision**: ACCEPTED — the only realistic constraint is the email uniqueness index; the team rule (catch and re-render) is met.

### F6 — An invalid role is reported before the 404 check

- **Severity**: 💡 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: src/main/java/pl/regavio/stockahead/account/CompanyAccountController.java:158-163
- **Detail**: `POST /{id}/role` with an invalid `role` and another company's (or a missing) id returns the `roleInvalid` flash, not a 404. Nothing changes and no data leaks, because the response is the same for any id. The plan doesn't specify the order.
- **Fix**: Accept as is. Move validation after the scoped load only if a strict 404-first contract is wanted.
- **Decision**: ACCEPTED — nothing changes and nothing leaks; the plan doesn't specify the order.

## Triage summary

- Fixed: F1 (Fix B), F2, F3, F4
- Accepted: F5, F6
- `./mvnw verify` after fixes: 629 tests, 0 failures (2026-11-04).
