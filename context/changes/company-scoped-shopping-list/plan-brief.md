# Company-Scoped Shopping List — Plan Brief

> Full plan: `context/changes/company-scoped-shopping-list/plan.md`

## What & Why

Roadmap M-2 S-06: the shopping list page and its CSV export must aggregate only the logged-in manager's company's shortages and blocked orders. Today they read every company's open order lines. The roadmap calls this "the easiest leak in the system because it's a read-only page nobody suspects."

## Starting Point

`OrderLineRepository.findOpenLinesWithShortage()` has no company filter and feeds both `/purchasing` and `/purchasing/export` through `ShoppingListModel.buildRows()`. `order_lines.company_id` with composite FKs already exists (V17), so the data needed for scoping is there.

## Desired End State

Each manager's page and CSV show only their own company's parts, blocked orders and missing quantities, even when another company has a part with the same name. A company with no shortages sees the empty state and a header-only CSV, whatever other companies have. The CSV format is unchanged.

## Key Decisions Made

| Decision          | Choice                                                   | Why (1 sentence)                                                              |
| ----------------- | -------------------------------------------------------- | ----------------------------------------------------------------------------- |
| Scoping mechanism | Explicit `companyId` parameter, controller → model → query | Same style as every earlier M-2 slice and its sibling queries.                |
| Old query         | Replace the no-argument variant, don't keep it           | Leaves no unscoped path for a future caller.                                  |
| Schema            | No migration                                             | `order_lines.company_id` and its composite FKs already exist.                 |
| Guardrail test    | New `PurchasingCompanyIsolationIntegrationTests`          | No ids in these routes, so the test asserts "only own rows" rather than a 404. |
| Phasing           | One phase                                                | One read path; the two-company test proves it end to end.                     |

## Scope

**In scope:**
- Company filter on the shortage query
- Company parameter on `ShoppingListModel.render`/`rowsForExport`
- Principal's company passed in by `ShoppingListController`
- Two-company integration tests on the page and the CSV

**Out of scope:**
- CSV format changes
- Schema changes
- `reservedQuantityForPart` (already safe after S-05)
- Operator 403 (S-08)
- Performance tuning

## Architecture / Approach

`ShoppingListController` reads `principal.companyId()` and passes it to `ShoppingListModel`. The model's single `buildRows(companyId)` calls `findOpenLinesWithShortage(companyId)`, which filters `ol.companyId = :companyId`. Grouping, sorting and CSV writing are untouched.

## Phases at a Glance

| Phase                                          | What it delivers                                       | Key risk                                                     |
| ---------------------------------------------- | ------------------------------------------------------ | ------------------------------------------------------------ |
| 1. Scoped shortage query and two-company tests | Per-company page and CSV, proven by two-company tests  | Same-name parts across companies hide a leak if not tested    |

**Prerequisites:** S-03 done (orders carry `company_id`), which it is.
**Estimated effort:** ~1 short session.

## Open Risks & Assumptions

- Assumes the V17 composite FKs keep each line's company equal to its part's and order's company, so filtering on the line alone is enough.

## Success Criteria (Summary)

- Manager A never sees B's shortages, blocked orders or quantities on the page or in the CSV, and the other way round.
- `./mvnw verify` passes, including the new isolation tests and the unchanged `ShoppingListIntegrationTests`.
