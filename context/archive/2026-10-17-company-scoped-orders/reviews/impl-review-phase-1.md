<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: Company-Scoped Orders Implementation Plan

- **Plan**: context/changes/company-scoped-orders/plan.md
- **Scope**: Phase 1 of 2
- **Reviewed phases**: 1
- **Date**: 2026-10-29
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

- Commit `cf9cfaa`: every file listed under Phase 1 › Changes Required is in the diff. The only change outside the plan is the roadmap S-03 status flip that `/10x-implement` makes.
- V17 matches the plan's contract: it adds `accounts_id_company_key`, `orders.company_id`, composite project and assignee FKs (`MATCH SIMPLE`, `RESTRICT`), `orders_id_company_key` and `orders_company_status_idx`, plus `order_lines.company_id` with composite order (`CASCADE`) and part (`RESTRICT`) FKs. It follows V16's style.
- `Order.setProject` sets `companyId` and copies it to lines already added. `Order.addLine` copies it to the new line. `OrderController.create` is the only place that constructs an `Order` or `OrderLine`.
- `CompanyFixtures.cleanUp()` deletes the companies' orders before the account loop, as the plan specifies.
- `./mvnw verify` exits 0 (553 tests, 0 failures, 0 errors, 0 skipped).
- `OrderSchemaTests` covers the four rejections the plan requires, plus an unassigned order and an order assigned to an account of its own company, both accepted.
- The grep in criterion 1.3 returns nothing. `new OrderLine`, `new Order()` and every raw insert were checked.
- Manual check 1.4 is backed by DB evidence: order 1 and both of its lines have `company_id = 1`, the parts belong to company 1, and the lines are fully reserved (48/48 and 30/30).

## Findings

### F1 — CompanyFixtures class Javadoc lists the deletion steps in a different order than cleanUp()

- **Severity**: OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Pattern Consistency
- **Location**: src/test/java/pl/regavio/stockahead/CompanyFixtures.java:33-36
- **Detail**: The class Javadoc says cleanup removes "orders, projects, parts, accounts and then companies (FK order)". The method deletes in a different order: orders, then the email accounts, then projects, parts, any remaining accounts and companies. The method's own Javadoc gives the correct order. The mismatch existed before this change, and the edit carried it forward.
- **Fix**: In the class Javadoc, drop "(FK order)" or point to `cleanUp()`'s Javadoc for the exact order.
- **Decision**: FIXED — class Javadoc now defers to cleanUp() for the exact order
