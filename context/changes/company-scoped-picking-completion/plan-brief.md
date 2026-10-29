# Company-Scoped Picking and Completion — Plan Brief

> Full plan: `context/changes/company-scoped-picking-completion/plan.md`

## What & Why

This is roadmap item S-04 of milestone M-2. Technicians and managers open, pick and report completion only for their own company's orders. Another company's order id is a 404 that locks and changes nothing. This closes FR-028 for picking and completion and the prd-v2 guardrail "never see or change company B's data, not even by guessing a record id".

## Starting Point

S-03 scoped orders, the allocator, the picking list and the manager's confirm/reject completion. On purpose, it left the picking by-id routes bare for S-04: `PickingDetailModel`'s `findByIdWithDetails(id)`, `pick`'s `findPartIdForLine`, part lock and `findById`, `reportCompletion`'s `findPartIdsForOrder(orderId)` and `findById`, and the list's `reservedQuantitiesByOpenOrder()`. `completion_reported_by` still has V8's plain FK.

## Desired End State

B's manager and technician get 404 on `/picking/{A}`, on A's pick and on A's report-completion, and A's order, lines and stock stay unchanged. A line of another company's order posted under B's own order id is also 404. The DB refuses a reporter from another company. The "to pick" totals read only the user's company. Single-company behavior is unchanged.

## Key Decisions Made

| Decision | Choice | Why (1 sentence) | Source |
| --- | --- | --- | --- |
| `completion_reported_by` | Composite FK `(completion_reported_by, company_id) → accounts (id, company_id)` in V18 | The DB refuses a foreign reporter even if a future path sets it from somewhere other than the principal, like S-03's assignee FK | Plan (user) |
| Technician scope inside a company | Company boundary only; a technician may still open or pick any own-company order by id | Matches the roadmap Outcome and prd-v2's preserved capability matrix; an assignee rule would be a new product rule | Plan (user) |
| Scoping mechanism | Explicit `companyId` from `CompanyUser` on every lookup and lock | Same as F-01 to S-03; AGENTS.md § Company isolation | AGENTS.md |
| Response to a foreign id | 404 from the first scoped lookup, before validation output, locks or writes | AGENTS.md: "exactly like a missing id" | AGENTS.md |
| Bare repository variants | Delete `findByIdWithDetails(Long)`, `findPartIdsForOrder(Long)`, the 2-arg `findPartIdForLine` and the no-arg `reservedQuantitiesByOpenOrder`; keep `findByIdInForUpdate` for S-05 | No test uses them; deleting them stops drift back to unscoped calls | Plan |
| Phase split | Schema first, then routes and tests | Proven in S-01 to S-03 | Plan |

## Scope

**In scope:**
- V18: reporter composite FK, plus two `OrderSchemaTests` cases
- Scoped `PickingDetailModel`, `PickingController` detail, pick and report-completion, and the list's "to pick" totals
- Removal of the bare repository variants
- New `PickingCompanyIsolationIntegrationTests` (6 tests, both roles)

**Out of scope:**
- Restricting technicians to their assigned orders
- Confirm/reject completion (already scoped in S-03)
- Delivery, correction and import part lookups (S-05); the shopping list (S-06)
- `cancelled_by` FK; the allocator or lock strategy; UI changes; operator 403 tests (S-08)

## Architecture / Approach

`@AuthenticationPrincipal CompanyUser` → `companyId` flows into every picking lookup in `OrderController.transitionOrder`'s shape: a scoped scalar pre-lock lookup, a scoped part lock (`findByIdInAndCompanyIdForUpdate`), then a scoped re-read (`findByIdAndCompanyId`). A foreign id yields nothing at the first step, which is a 404. Pre-lock lookups stay scalar projections because of the open-in-view stale-cache rule.

## Phases at a Glance

| Phase | What it delivers | Key risk |
| --- | --- | --- |
| 1. Reporter composite FK | V18 and 2 schema tests; no behavior change | Wrong name for V8's auto-named FK; an existing seed with a cross-company reporter |
| 2. Scoped picking routes and two-company tests | B→404 on all picking by-id routes, scoped "to pick", bare variants deleted | Over-scoping breaks own-company picking; the own-company test and existing suites guard it |

**Prerequisites:** S-03 archived (done); Docker running for Testcontainers.
**Estimated effort:** ~1–2 sessions; smaller than S-03 (no entity or fixture churn).

## Open Risks & Assumptions

- V18 assumes no existing row has a cross-company reporter. That holds because the reporter is always the authenticated user, and the V14 empty-table guard covers the branch.
- Inside a company, the picking list's assignee filter stays a view filter: a technician can still pick a colleague's order by URL. This was decided in planning.

## Success Criteria (Summary)

- Logged in as company B, pasting A's picking URL or posting A's pick or report form shows 404, and A's stock and order are untouched.
- Company A's technician picks and reports completion exactly as before.
- The DB rejects a completion reporter from another company.
