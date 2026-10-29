<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: Company-Scoped Orders

- **Plan**: context/changes/company-scoped-orders/plan.md
- **Scope**: Full plan
- **Reviewed phases**: 1, 2
- **Date**: 2026-10-29
- **Verdict**: APPROVED
- **Findings**: 0 critical, 0 warnings, 3 observations

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| Plan Adherence | PASS |
| Scope Discipline | PASS |
| Safety & Quality | PASS |
| Architecture | PASS |
| Pattern Consistency | WARNING |
| Success Criteria | PASS |

This is a re-review. It covers commits d68b233 and befb82b, plus the uncommitted working-tree fixes from the earlier same-day review (listed under "Previous review" below). Those fixes are still uncommitted.

Success criteria re-run on 2026-10-29:
- `./mvnw verify` passes: 563 tests, 0 failures, 0 errors, 0 skipped. That includes `OrderSchemaTests`, `OrdersCompanyIsolationIntegrationTests` and `CrossCompanyAllocationLockTests`.
- Both grep criteria (Phase 1 inserts, Phase 2 bare lookups) return nothing.
- Manual checks 1.4 and 2.6–2.8 were confirmed by the user during implementation.

Plan drift: every planned item matches, and nothing is missing.
- The only change beyond the plan is the uncommitted `findByIdInAndCompanyIdForUpdate` in `transitionOrder` (the earlier F1 fix). It locks the same rows, because the part ids come from the scoped lookup and the composite FK ties every line's part to its order's company.
- None of the work listed under "What We're NOT Doing" was done.

Safety: nothing was found.
- The company comes only from the principal.
- A foreign order id answers 404 before anything is locked or written.
- The 4 dropped FK names are the real Postgres default names.
- ON DELETE behaviour is unchanged.
- An unassigned order passes the assignee FK (MATCH SIMPLE).
- The new composite FKs take `FOR KEY SHARE` locks, which don't conflict with Hibernate's `FOR NO KEY UPDATE`, so they add no deadlock risk.

Accepted risks, not findings (recorded in roadmap S-05):
- **Delivery with a foreign part id.** It raises that part's stock without reallocating the owner's orders (`DeliveryController.java:204`). It never over-reserves.
- **Delivery and stock correction pass different companies to the allocator.** Delivery passes the principal's company. Stock correction passes the locked part's company. S-05 brings them in line.
- **V17 needs empty tables.** It assumes `orders` and `order_lines` are empty, and it fails loudly and rolls back if they are not.

## Findings

Fixes for this review's F1–F3 were applied in triage. F2 raised both the `createB` and `reallocateB` bounds to 15 s. The four edited test classes were re-run and pass.

### F1 — Missing space in technician picking assignment

- **Severity**: 🔍 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Pattern Consistency
- **Location**: src/test/java/pl/regavio/stockahead/orders/OrdersCompanyIsolationIntegrationTests.java:295
- **Detail**: The uncommitted fix for the previous F3 introduced `ModelAndView technicianPicking =getModel(...)`, with no space after `=`.
- **Fix**: Change it to `= getModel(`.
- **Decision**: FIXED

### F2 — 5-second bound on a full MockMvc order creation may flake on slow CI

- **Severity**: 🔍 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Success Criteria
- **Location**: src/test/java/pl/regavio/stockahead/orders/CrossCompanyAllocationLockTests.java:198
- **Detail**: `createB.get(5, SECONDS)` has to cover a whole `POST /orders`: security filters, the transaction and the allocator. A slow runner could time out even with correct scoping. The lock holder waits up to 30 s, so a larger bound would still catch a lock that has gone global again.
- **Fix**: Raise the bound to about 15 s if the test ever flakes. Leave it as is for now.
- **Decision**: FIXED

### F3 — Edited raw-insert calls are no longer wrapped like their neighbours

- **Severity**: 🔍 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Pattern Consistency
- **Location**: src/test/java/pl/regavio/stockahead/orders/ReservationConcurrencyTests.java:107,156,159; src/test/java/pl/regavio/stockahead/orders/ReservationAllocatorTests.java:107
- **Detail**: Adding `company_id` made these `jdbc.update(...)` calls 123–126 characters long on one line. The surrounding calls wrap their arguments. This is cosmetic only, and no formatter is configured.
- **Fix**: Wrap the arguments the way the neighbouring calls do.
- **Decision**: FIXED

## Previous review (2026-10-29, superseded)

The earlier same-day review was APPROVED with 3 observations, all marked FIXED. These fixes are in the working tree, still uncommitted:
- **F1 (`transitionOrder` used the bare part lock).** It now calls `findByIdInAndCompanyIdForUpdate(partIds, companyId)`. Fixed.
- **F2 (lock test: the create half can't fail, and the executor wasn't awaited).** Added a Javadoc note and `executor.awaitTermination(10, SECONDS)`. Fixed.
- **F3 (the B-technician `/picking` check can't discriminate).** Added a smoke-check comment. Fixed, but the new comment introduced this review's F1.
