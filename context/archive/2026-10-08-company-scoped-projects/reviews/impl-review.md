<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: Company-Scoped Projects Implementation Plan

- **Plan**: context/changes/company-scoped-projects/plan.md
- **Scope**: Full plan
- **Reviewed phases**: 1, 2
- **Date**: 2026-10-17
- **Verdict**: APPROVED
- **Findings**: 0 critical, 0 warnings, 0 observations

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

- Diff `c00210b^..HEAD`: 43 files. Every main-code file is named in the plan: V16, `Project`, `BomLine`, `ProjectRepository`, the three project controllers, `ProjectDetailModel` and `OrderController`. The test changes are the planned fixture churn plus `ProjectSchemaTests` and `ProjectsCompanyIsolationIntegrationTests`. No unplanned files.
- V16 matches the contract: the column, constraint and FK names and the `ON DELETE` actions all match. `project_links` is untouched.
- All ten project lookups, the BOM part lookup, and the order picker and order lookup use `(id, companyId)` / `list(companyId, …)`. The plan's grep finds no bare lookups.
- One documented adaptation: `ProjectController.setActive(id, companyId, active)`.
- Out-of-scope items stayed out: order by-id routes, assignee scoping, `orders.company_id`, UI changes.
- Lessons: the existing `DataIntegrityViolationException` handling stays at the write boundaries. No new role-gated routes, so no new 403 tests are needed.
- Success criteria: `./mvnw verify` ran green on `f427393` (547 tests, 0 failures, 6/6 isolation tests). Only `plan.md` and `change.md` changed after that. A break-check made the isolation tests go red (4 of 6) when the lookups were unscoped. The grep was re-run during the review: no matches. The user confirmed all manual items (1.4, 2.6, 2.7).

## Notes (not findings)

- An unknown id shows Spring's whitelabel error page, because the app has no `error.html`. The user decided on 2026-10-17 to handle this in a separate change.
- A composite-FK violation in `ProjectBomController.addLine` would show as "duplicate part". The code can't reach that today, because the part lookup is scoped first. This is the same accepted shape as S-01's impl review F4, and the plan-brief lists it as an accepted risk.

## Findings

None.
