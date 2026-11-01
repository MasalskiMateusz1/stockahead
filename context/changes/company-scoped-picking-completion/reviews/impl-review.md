<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: Company-Scoped Picking and Completion

- **Plan**: context/changes/company-scoped-picking-completion/plan.md
- **Scope**: Full plan
- **Reviewed phases**: 1, 2
- **Date**: 2026-10-29
- **Verdict**: APPROVED
- **Findings**: 0 critical, 0 warnings, 4 observations

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| Plan Adherence | WARNING |
| Scope Discipline | PASS |
| Safety & Quality | PASS |
| Architecture | WARNING |
| Pattern Consistency | PASS |
| Success Criteria | PASS |

Evidence: `./mvnw verify` passed (569 tests, 0 failures, re-run during the review). The plan's grep check returns nothing. All planned files were changed, and the only extra paths are this change's own context docs and the roadmap status flip. The user confirmed manual checks 1.4, 2.5 and 2.6 in session. "What We're NOT Doing" was respected: confirm/reject, the delivery, correction and import part locks, the allocator, templates and existing tests were not touched.

## Findings

### F1: Javadoc overstates where the report-completion 404 comes from

- **Severity**: 💡 OBSERVATION
- **Impact**: 🏃 LOW (quick decision; fix is obvious and narrowly scoped)
- **Dimension**: Plan Adherence
- **Location**: src/main/java/pl/regavio/stockahead/orders/PickingController.java:62-65, 240-242
- **Detail**: The class Javadoc says a foreign id's 404 "comes from the first scoped lookup, before any validation output, lock or write". That holds for detail and pick. For report-completion, the first scoped lookup (`findPartIdsForOrder`) returns an empty set rather than throwing, and the `builtUnits` parsing runs next. The 404 then comes from the in-transaction `findByIdAndCompanyId` (valid input) or from `renderReportError` → `PickingDetailModel.load` (invalid input). The effect is still correct: no lock, no write and no rendered output. The method Javadoc's "404 from the in-transaction findByIdAndCompanyId" is likewise only true for valid input. The plan's own Intent sentence has the same imprecision.
- **Fix**: Reword both Javadoc sentences to describe the real mechanism. For report-completion, a foreign order yields no part ids, so nothing is locked, and it is 404 from the scoped in-transaction load or from the scoped error re-render, before any output or write.
- **Decision**: FIXED

### F2: No foreign-order test with a non-integer builtUnits

- **Severity**: 💡 OBSERVATION
- **Impact**: 🏃 LOW (quick decision; fix is obvious and narrowly scoped)
- **Dimension**: Plan Adherence
- **Location**: src/test/java/pl/regavio/stockahead/orders/PickingCompanyIsolationIntegrationTests.java:230-247
- **Detail**: `otherCompanysUsersGet404OnReportCompletionAndChangeNothing` posts only `builtUnits=1`. The invalid-input path described in F1 (where the 404 comes from the scoped error re-render) is untested. The pick test covers its equivalent with `quantity=abc`. The plan doesn't require this case, but it is the one path where the 404 depends on the re-render being scoped.
- **Fix**: In the same test, also post `builtUnits=abc` as B's manager and technician, and assert 404 and an unchanged snapshot.
- **Decision**: FIXED

### F3: Account lookup by email is cross-company but not on AGENTS.md's exception list

- **Severity**: 💡 OBSERVATION
- **Impact**: 🔎 MEDIUM (real tradeoff; pause to reason through it)
- **Dimension**: Architecture
- **Location**: src/main/java/pl/regavio/stockahead/orders/PickingController.java:285 (also :139, OrderController:389, StockCorrectionController:237)
- **Detail**: The completion reporter is resolved with `accountRepository.findByCanonicalEmail(authentication.getName())`, a cross-company native query. It is safe: it resolves the logged-in user's own account, email is globally unique (FR-029), and V18's composite FK backstops any mismatch, which would surface as the caught `picking.error.reportFailed`. But AGENTS.md lists only "the login email lookup in AccountRepository" as an intentional cross-company exception. This usage predates the change (S-03) and is repeated at several call sites.
- **Fix A ⭐ Recommended**: Extend the AGENTS.md exception to "resolving the authenticated account by its own email (login and current-user lookups)".
  - Strength: Documents a pattern that is already safe and repeated in four places, with no code churn.
  - Tradeoff: The exception list grows; a future misuse with a non-principal email would read as "allowed".
  - Confidence: HIGH: email is globally unique and the value always comes from the authenticated principal.
  - Blind spot: None significant.
- **Fix B**: Add `accountId()` to `CompanyUser` and load the current account by `(id, company_id)`.
  - Strength: Removes the cross-company read entirely, and the rule needs no new exception.
  - Tradeoff: Touches security code and four controllers, outside this change's scope; it needs its own small change.
  - Confidence: MED: straightforward, but `CompanyUser` construction and its tests haven't been inspected.
  - Blind spot: The session-serialized principal shape changes, so existing sessions may need re-login.
- **Decision**: DEFERRED (Fix B) — queued in follow-ups/review-fixes.md as its own change

### F4: Remaining single-column account FKs (cancelled_by, stock_corrections.account_id)

- **Severity**: 💡 OBSERVATION
- **Impact**: 🏃 LOW (quick decision; fix is obvious and narrowly scoped)
- **Dimension**: Architecture
- **Location**: src/main/resources/db/migration/V9__add_order_cancellation.sql, V11__create_stock_corrections.sql
- **Detail**: `orders.cancelled_by` and `stock_corrections.account_id` still reference `accounts(id)` alone, so the DB does not guarantee the actor belongs to the row's company. The plan explicitly defers `cancelled_by`, and stock corrections belong to S-05. This is not drift, just a hand-off to keep visible.
- **Fix**: Carry both composite FKs into S-05's plan (stock corrections) and note `cancelled_by` there or in a follow-up.
- **Decision**: FIXED (handed off) — queued in follow-ups/review-fixes.md for S-05
