# Company-Scoped Stock Movements — Plan Brief

> Full plan: `context/changes/company-scoped-stock-movements/plan.md`

## What & Why

This is roadmap item S-05 of milestone M-2. Deliveries, stock corrections and CSV import affect only the logged-in user's company's parts, and reservations recompute only for that company's orders. A part id of another company behaves like a missing one and writes nothing. This closes FR-028 for stock movements. It also closes load-current-account's F4: the last single-column account FKs become composite company FKs.

## Starting Point

S-01 and S-03 left three part lookups bare on purpose for this slice. The first is the delivery's part lock (`DeliveryController.java:163`). A foreign id raises A's stock with B's shelf spelling and without reallocation. The second is the correction page, its lock and its reallocation company (`StockCorrectionController.java:227`, `:255`, `:302`). B's manager can open and change A's part. The third is the import confirm's lock (`PartImportController.java:260`), which is safe in practice but unscoped in form. `stock_corrections` has no `company_id`, and `stock_corrections.account_id` / `orders.cancelled_by` use plain FKs.

## Desired End State

B's manager gets 404 on A's correction page and on both correction writes. B's delivery naming A's part gets the usual "part doesn't exist" error, and nothing changes. Every stock writer locks and reallocates only within the principal's company. The DB refuses a correction or a cancellation that links two companies. The bare `findByIdInForUpdate` is gone. Single-company behavior is unchanged.

## Key Decisions Made

| Decision | Choice | Why (1 sentence) | Source |
| --- | --- | --- | --- |
| `stock_corrections` schema | NOT NULL `company_id` (backfilled from parts) + composite FKs `(part_id, company_id)` and `(account_id, company_id)` in V19 | AGENTS.md requires `company_id` on corrections, and the DB refuses a correction linking two companies, like S-03/S-04's FKs | Plan (user) |
| `orders.cancelled_by` | Composite FK `(cancelled_by, company_id) → accounts` in the same V19 | Closes F4 completely; a few lines in V18's shape | Plan (user) |
| Foreign id in the URL (correction) | 404 before validation output, locks or writes | AGENTS.md: "exactly like a missing id" | AGENTS.md |
| Foreign part id posted in the delivery form | Existing `deliveries.error.partNotFound` form error, all-or-nothing | Same answer as a missing id; S-02's BOM add-line precedent | Roadmap / S-02 |
| Reallocation company | Always `principal.companyId()` | Roadmap S-05 Risk; correction's interim "part's company" is no longer needed | Roadmap |
| Bare `findByIdInForUpdate` | Delete it; import and the lock test switch to the scoped variant | No production caller remains, so deleting it stops drift back (S-04 precedent) | Plan |
| `StockCorrection.companyId` | Plain column set from the part, like `PartLocation` | Existing pattern; the composite FK keeps it in step | Plan |

## Scope

**In scope:**
- V19: corrections `company_id` + 2 composite FKs, `cancelled_by` composite FK; entity mapping; 5 schema tests
- Scoped delivery lock, correction page/lock/reallocation, import lock
- Removal of the bare lock
- New `StockMovementsCompanyIsolationIntegrationTests` (about 10 cases)

**Out of scope:**
- Operator 403s on these routes (S-08); the shopping list (S-06)
- CSV format or preview changes; the allocator or lock order; templates and messages
- Making the delivery's foreign id a 404

## Architecture / Approach

`@AuthenticationPrincipal CompanyUser` → `companyId` flows into every part lookup: `findByIdAndCompanyId` for the page and `findByIdInAndCompanyIdForUpdate` for every write lock. A foreign id is neither returned nor locked, so B never waits on A's rows. The existing "not found" branches then produce 404 (correction) or the form error (delivery). Reallocation runs with the same company.

## Phases at a Glance

| Phase | What it delivers | Key risk |
| --- | --- | --- |
| 1. Company on corrections and composite account FKs | V19, entity column, 5 schema tests; no behavior change | Wrong auto-generated FK names; an old row with a cross-company author fails the deploy |
| 2. Scoped stock routes and two-company tests | B→404/form error on every stock route, bare lock deleted, isolation suite | Over-scoping breaks own-company delivery, correction or import; existing suites and own-company tests guard it |

**Prerequisites:** S-01 and S-03 archived (done); Docker running for Testcontainers.
**Estimated effort:** ~1–2 sessions, similar to S-04.

## Open Risks & Assumptions

- V19 assumes no existing correction or cancellation links two companies. This holds because authors and cancellers come from the principal, and since load-current-account they are loaded with `findByIdAndCompanyId`. If a row does, the deploy fails loudly instead of silently keeping it.
- The delivery's foreign-id answer is a form error, not 404. It can't be told apart from a deleted part, which is the intended "exactly like a missing id".

## Success Criteria (Summary)

- A user of company B can't read, correct or receive stock into company A's parts, by URL or by form post.
- Deliveries, corrections and imports still work within one company, and reservations follow them.
- The DB itself refuses correction and cancellation rows that link two companies.
