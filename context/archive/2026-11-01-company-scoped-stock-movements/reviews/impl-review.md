<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: Company-Scoped Stock Movements

- **Plan**: context/changes/company-scoped-stock-movements/plan.md
- **Scope**: Full plan
- **Reviewed phases**: 1, 2
- **Date**: 2026-11-01
- **Verdict**: APPROVED
- **Findings**: 0 critical, 1 warning, 4 observations

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| Plan Adherence | PASS |
| Scope Discipline | WARNING |
| Safety & Quality | WARNING |
| Architecture | PASS |
| Pattern Consistency | PASS |
| Success Criteria | PASS |

Success criteria re-run during review: `./mvnw verify` passed 588/588, and `grep -rn "findByIdInForUpdate(" src/` found nothing. Manual checks 1.4 and 2.5–2.7 were confirmed by the user against a seeded two-company dev database.

## Findings

### F1 — V19's author FK can abort the migration on a database that already has cross-company corrections

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Safety & Quality
- **Location**: src/main/resources/db/migration/V19__company_scoped_stock_corrections.sql:8, :18-20
- **Detail**: Before this change, `StockCorrectionController` locked the part by bare id but loaded the author from the principal's own company. B's manager could therefore write a correction with A's part and B's account. This is the bug S-05 fixes. V19 backfills `company_id` from the part, so such a row gets A's company, and `stock_corrections_author_company_fkey (account_id, company_id)` then fails. Postgres DDL is transactional, so the migration rolls back cleanly, but the deploy is blocked. The plan's Migration Notes (plan.md:213) accept "fails on deploy" as the posture, but they justify it with "its author was loaded within the same company". That reasoning misses the unscoped part side, so this row shape is a known reachable path, not a "somehow". `orders.cancelled_by` is not affected, because cancellation already scoped the order.
- **Fix**: Before deploying V19 to any database that ran V14–V18 with more than one company, run `SELECT count(*) FROM stock_corrections sc JOIN parts p ON p.id = sc.part_id JOIN accounts a ON a.id = sc.account_id WHERE a.company_id <> p.company_id;` and expect 0. Correct the plan's Migration Notes reasoning to match.
  - Strength: Keeps the plan's "inspect, don't loosen the FK" posture, and the check surfaces the case before deploy instead of during it.
  - Tradeoff: It is a manual pre-deploy step. The migration itself still aborts if the step is skipped.
  - Confidence: HIGH — the pre-S-05 code path that writes such rows is described in the plan's own Current State Analysis.
  - Blind spot: Whether any database with V14+ and two or more companies exists outside local dev. Per project memory, multi-company work lives only on this branch, so the practical risk is likely nil.
- **Decision**: FIXED — plan.md Migration Notes corrected and the pre-deploy check query recorded

### F2 — Correction half of the lock-wait test has an alternative pass path

- **Severity**: 💡 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: src/test/java/pl/regavio/stockahead/parts/StockMovementsCompanyIsolationIntegrationTests.java:363
- **Detail**: No `lock_timeout` is configured today, so a regressed bare lock would block past the 15 s budget and fail the test. If a `lock_timeout` under ~5 s is ever added, a regressed lock would time out instead. The request would land in the `saveFailed` handler, which re-renders through the scoped `renderForm`, which still returns 404, so the test would stay green. The delivery half has no such gap, because it requires the partNotFound text.
- **Fix**: Also assert that B's correction request completes well under the lock-hold time (e.g. < 5 s elapsed).
- **Decision**: FIXED — correction half now also asserts it finished in < 5 s

### F3 — `PartRepository.findWithLocationsByIdIn` still loads by bare ids

- **Severity**: 💡 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: src/main/java/pl/regavio/stockahead/parts/PartRepository.java:89-90 (callers PartImportController.java:267, ImportPreview.java:81)
- **Detail**: Both callers get their ids only from `findAllIdsAndNames(companyId)`, and `applyImport` has already locked them with the scoped query. It is not exploitable today, but it is the last bare multi-id part read, and it sits on a route that AGENTS.md § Company isolation covers. The plan kept it on purpose (Phase 2 §3).
- **Fix**: Add a `companyId` parameter (`AND p.company.id = :companyId`) and pass the principal's company at both call sites.
- **Decision**: FIXED — renamed to findWithLocationsByIdInAndCompanyId(ids, companyId); all three callers (PartImportController, ImportPreview, OrderDetailModel) pass their company

### F4 — Delivery lazy-loads locations per part while holding part locks

- **Severity**: 💡 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: src/main/java/pl/regavio/stockahead/parts/DeliveryController.java:194
- **Detail**: The N+1 existed before this change. `part.getLocations()` runs one query per locked part, whereas the import path pre-fetches locations. Receipts are small (at most ~5 rows), so the impact is low.
- **Fix**: Leave it as is for now. Pre-fetch with a company-scoped query if receipt sizes grow (see the lessons.md JOIN FETCH rule).
- **Decision**: FIXED — DeliveryController.applyReceipt pre-fetches the locked parts' locations with the scoped query after the partNotFound check

### F5 — Unplanned javadoc edit in ReservationConcurrencyTests

- **Severity**: 💡 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Scope Discipline
- **Location**: src/test/java/pl/regavio/stockahead/orders/ReservationConcurrencyTests.java:37
- **Detail**: A comment-only change. It was required because the plan's `grep -rn "findByIdInForUpdate(" src/` criterion would otherwise have matched the stale javadoc.
- **Fix**: None needed. It is benign and justified.
- **Decision**: ACCEPTED — benign, justified comment-only edit
