<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: Company-Scoped Shopping List

- **Plan**: context/changes/company-scoped-shopping-list/plan.md
- **Scope**: Full plan
- **Reviewed phases**: 1
- **Date**: 2026-11-01
- **Verdict**: APPROVED
- **Findings**: 0 critical, 0 warnings, 2 observations

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| Plan Adherence | PASS |
| Scope Discipline | PASS |
| Safety & Quality | PASS |
| Architecture | PASS |
| Pattern Consistency | PASS |
| Success Criteria | PASS |

Evidence: all four planned changes MATCH (no DRIFT/MISSING/EXTRA); every "NOT doing" guardrail held. `./mvnw verify` re-run at 20a42b7: 591 tests, 0 failures, BUILD SUCCESS. `grep -rn "findOpenLinesWithShortage()" src` returns nothing. Manual 1.5/1.6 confirmed by the user against a two-company seed in the running app (curl evidence: A sees only Rezystor 10k / #1001 / 5; B sees Kondensator 100nF 4 and Rezystor 10k 18 / #2001). The only read path is fetch-joined and scoped; no lazy load reaches another company's data. Wrong-role 403 already covered in ShoppingListIntegrationTests.

## Findings

### F1 — Seeded order lines skip the BOM

- **Severity**: OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Pattern Consistency
- **Location**: src/test/java/pl/regavio/stockahead/purchasing/PurchasingCompanyIsolationIntegrationTests.java:117-127
- **Detail**: Order lines are inserted via JDBC without a matching bom_lines row, so required_quantity is not quantity_units × BOM quantity as real order creation would produce. The shopping list never reads the BOM, reserved quantities match what the allocator leaves for a lone short order, and ShoppingListIntegrationTests seeds the same way.
- **Fix**: Leave as is, or add a one-line comment that the BOM is skipped on purpose.
- **Decision**: FIXED (Javadoc note on seedShortOrder)

### F2 — Cell-value assertions are loose

- **Severity**: OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: src/test/java/pl/regavio/stockahead/purchasing/PurchasingCompanyIsolationIntegrationTests.java:167,170,178,181
- **Detail**: `.contains("<td>5</td>")` / `.doesNotContain("<td>3</td>")` would also match an unrelated cell with that value. Reliable today (one row per page) and backed by the stronger `PROJECT (date, qty)` and exact-CSV assertions.
- **Fix**: Leave as is.
- **Decision**: SKIPPED
