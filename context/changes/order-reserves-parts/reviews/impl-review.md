<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: Zlecenie produkcji rezerwuje części

- **Plan**: context/changes/order-reserves-parts/plan.md
- **Scope**: Full plan
- **Reviewed phases**: 1, 2, 3, 4, 5
- **Date**: 2026-09-30
- **Verdict**: APPROVED
- **Findings**: 0 critical, 0 warnings, 4 observations

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| Plan Adherence | PASS |
| Scope Discipline | PASS |
| Safety & Quality | PASS |
| Architecture | PASS |
| Pattern Consistency | PASS |
| Success Criteria | PASS |

## Summary

All 5 phases (Schema & entities, Allocation engine, Order creation, Order list & detail, Parts catalog wiring) were checked file-by-file against the plan's Contracts. Every planned file was found implemented with contract-level fidelity — no MISSING items, no DRIFT, no scope creep beyond the plan's "What We're NOT Doing" boundary. The two load-bearing invariants both hold exactly as specified:

1. `ReservationAllocator.reallocateAll()` locks every `Part` row (`PartRepository.findAllForUpdate()`, `PESSIMISTIC_WRITE`) before loading/sorting orders, and `OrderController.create()` invokes it inside the *same* `TransactionTemplate.execute()` block that saves the `Order`/`OrderLine`s — part locks and order writes commit atomically together, exactly as AGENTS.md's hard rule requires.
2. `GET /orders` reuses `ReservationAllocator.ALLOCATION_ORDER` directly (the same `Comparator` instance) rather than re-deriving a second, hand-synced sort.

`Priority` is declared `LOW, NORMAL, HIGH` (confirmed order for `reverseOrder()` correctness). `OrderLine.requiredQuantity` is a one-time snapshot at creation, never re-derived from `BomLine`. The Phase 1 migration's `CHECK`/`UNIQUE` constraints match the plan's contract exactly.

Every manager-only order route (`GET /orders/new`, `POST /orders`, `GET /orders`, `GET /orders/{id}`) has a corresponding 403-for-technician test, per `lessons.md`'s rule. Every write to a uniquely-constrained table catches `DataIntegrityViolationException`. No CRITICAL or WARNING findings surfaced from either the plan-drift or the safety/pattern-compliance pass.

`./mvnw verify` re-run at review time: **139/139 tests pass, BUILD SUCCESS.** All Manual Progress items (3.5, 4.5, 5.3) were confirmed live by the user during implementation, including one real bug caught and fixed in-session (a stale Docker image showing pre-Phase-5 hardcoded values — resolved by rebuilding `stockahead:local` and force-recreating the container, not a code defect).

## Findings

### F1 — OrderController is package-private, unlike every other controller

- **Severity**: OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Pattern Consistency
- **Location**: src/main/java/pl/regavio/stockahead/orders/OrderController.java:38
- **Detail**: Every other `@Controller` in the codebase (`HomeController`, `ManagerPingController`, `TechnicianAccountController`, `SetupController`, `PartController`, `ProjectLinkController`, `ProjectBomController`, `ProjectController`) is declared `public`. `OrderController` is the sole package-private exception. No functional impact — Spring manages package-private beans in the same package fine.
- **Fix**: Declare `public class OrderController` for consistency with the rest of the codebase.
- **Decision**: PENDING

### F2 — findAllForUpdate() locks the entire parts table on every order creation

- **Severity**: OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Architecture
- **Location**: src/main/java/pl/regavio/stockahead/orders/ReservationAllocator.java:53-72, src/main/java/pl/regavio/stockahead/parts/PartRepository.java:26-28
- **Detail**: `findAllForUpdate()` locks every row of the entire `parts` table (active and inactive) on every single order creation, serializing all concurrent order-creation transactions against the whole table rather than just the parts the triggering order touches. This is deliberate per `plan.md` ("small/low-QPS scale... over an incremental approach, for correctness simplicity") — a scalability ceiling, not a defect.
- **Fix**: None needed now. Worth a `lessons.md` note if a future slice needs to relax full-table locking for throughput.
- **Decision**: PENDING

### F3 — N+1 query pattern in order list/detail rendering (no JOIN FETCH)

- **Severity**: OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: src/main/java/pl/regavio/stockahead/orders/OrderController.java:63-70, src/main/java/pl/regavio/stockahead/orders/OrderDetailModel.java:48-58
- **Detail**: `GET /orders` renders `order.project.name` per row and `GET /orders/{id}` renders `line.getPart().getName()` per line, both via default JPA `@ManyToOne` loading with no `JOIN FETCH` — one extra query per order (list) / per line (detail). Same shape as the pre-existing pattern in `ProjectDetailModel`/`PartController`; not egregious for this tool's expected row counts.
- **Fix**: No action needed at current scale; consider a `JOIN FETCH` if order/line counts grow significantly.
- **Decision**: PENDING

### F4 — No upper-bound validation on quantityUnits before multiplying into required_quantity

- **Severity**: OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: src/main/java/pl/regavio/stockahead/orders/OrderController.java:131
- **Detail**: `line.setRequiredQuantity(bomLine.getQuantityPerUnit() * parsedQuantityUnits)` has no overflow guard on the user-supplied `quantityUnits`. An extreme value could overflow `int` to a negative `required_quantity`, but this is caught by the DB `CHECK (required_quantity > 0)` constraint and surfaces through the existing `DataIntegrityViolationException` → `orders.error.saveFailed` path. Not exploitable — just a confusing generic error rather than a targeted validation message.
- **Fix**: Optional — add an explicit upper-bound check on `quantityUnits` with a dedicated error message, if it comes up in practice.
- **Decision**: PENDING
