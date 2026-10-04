# Frame Brief: Assigning an order to a specific technician

> Framing step before /10x-plan. This document captures what is *actually*
> at issue, separated from what was initially assumed.

## Reported Observation

Every OPEN order appears for every technician on the picking list, and the manager has no way to say which technician an order is for.

## Initial Framing (preserved)

- **User's stated cause or approach**: Orders lack an "assigned technician"; the technician view is not filtered by it.
- **User's proposed direction**: "manager should be able to assign order to specific technician, technician sees only his orders"
- **Pre-dispatch narrowing**: The pain is "Manager can't record intent" (who builds what is decided verbally today). The scope is "Picking list only": the order list is filtered, and opening, picking from or reporting on another technician's order is out of scope for now. Ownership: orders are "Sometimes handed over".

## Dimension Map

The observation could originate at any of these dimensions:

1. **Spec ↔ implementation gap**: the PRD already requires an assignee, but the delivered slices dropped it.
2. **Data model**: `orders` has no column for who builds the order. ← initial framing
3. **Visibility vs. authorization**: "sees only his orders" could be a list filter or an access-control boundary.
4. **Ownership lifecycle**: handover (including after the first pick), orders that already exist without an assignee, and a deactivated assignee.
5. **Manager's view**: the manager is a superset of technician. Do they see their own queue or everything?

## Hypothesis Investigation

| Hypothesis | Evidence | Verdict |
| --- | --- | --- |
| 1. PRD already requires assignment; slices dropped it | PRD US-01 "zleca **technikowi** produkcję… zlecenie pojawia się na liście **technika**"; FR-010 "zlecić **technikowi**"; shape-notes flow step 4–5. `context/archive/2026-10-01-picking-list-and-pick/plan-brief.md:7,65` chose "see **every** OPEN order" with no recorded decision about the assignee. S-04's plan (`2026-09-30-order-reserves-parts/plan.md`) never mentions an assignee. | STRONG |
| 2. No assignee in the data model | `V6__create_orders.sql` has no technician column. `Order.java` has only `completionReportedBy` and `cancelledBy` as `Account` refs. | STRONG |
| 3. Visibility needs an access-control boundary | User scoped it to the picking list only. `/picking/{id}` and the pick/report routes stay `hasAnyRole('MANAGER','TECHNICIAN')` (`PickingController.java:105-112,193`). PRD § Access Control has no per-assignee row. | WEAK (excluded by scope) |
| 4. Ownership is not fixed for an order's life | User: handover happens "also mid-build". FR-002 deactivation keeps an account's data, so a deactivated assignee's open orders would vanish from every technician's list. Existing OPEN orders have no assignee. | STRONG (must be planned) |
| 5. Manager needs a personal queue | User: the manager sees everything and filters by technician. | NONE for a personal queue. The manager needs an "all + filter" view. |

## Narrowing Signals

- The pain is recording the manager's intent, not stopping wrong-technician picking. That makes this an assignment field plus a list filter, not a security boundary. The lesson "Test the wrong-role case for every role-gated route" still applies to any new manager-only assign/reassign route.
- Handover happens mid-build. Reassignment must work after `takenAt` is set. Unlike priority and deadline (FR-021), the assignee is **not** frozen when the order is taken.
- Manager on `/picking`: sees all open orders and can filter by technician.
- Assignment does not touch reservations. `ReservationAllocator`'s ordering (priority → deadline → age) and the hard rules in AGENTS.md are unaffected. The assignee is metadata, not an allocation input, so assigning or reassigning is not a recompute event under PRD § Business Logic.

## Cross-System Convention

The codebase already records who acted on an order as `Account` references on `Order` (`completionReportedBy`, `cancelledBy`), with accounts deactivated and never deleted (FR-002). An assignee reference fits that convention. The project's convention for changing the schema is a new Flyway migration (next would be V13), never `ddl-auto`.

## Reframed (or Confirmed) Problem Statement

> **The actual problem to plan around is**: the PRD's "kierownik zleca **technikowi**" was never implemented. Orders need an assignee that the manager sets at creation and can change at any time, including mid-build, and `/picking` should show a technician only their assigned orders while the manager sees all orders with a per-technician filter.

The initial framing was correct. The refinement is that this closes a spec gap rather than adding a new feature. The lifecycle cases are part of the problem, not edge polish: mid-build handover, open orders that predate assignment, and a deactivated assignee. Get any of them wrong and orders silently disappear from every technician's list.

## Confidence

**HIGH**: there is direct PRD and code evidence, the convention matches, and the user's answers settled scope, handover and the manager's view.

## What Changes for /10x-plan

Plan an assignee on orders (migration and entity), set at order creation and changeable by the manager at any status-appropriate time, including after the order is taken. Filter `/picking` per technician, and give the manager all orders plus a technician filter. The plan must decide:

- what existing OPEN orders get (the migration has to backfill an assignee or allow none)
- whether the assignee may be the manager
- what happens to the open orders of a deactivated technician
- whether the assignee is required on new orders
- whether the PRD needs a note under FR-010/FR-012 (§ Access Control is unchanged, because this is a view filter)

## References

- Source files: `src/main/java/pl/regavio/stockahead/orders/PickingController.java:91-103`, `src/main/java/pl/regavio/stockahead/orders/Order.java:62,75`, `src/main/resources/db/migration/V6__create_orders.sql`, `src/main/java/pl/regavio/stockahead/orders/OrderController.java:444-452`, `src/main/java/pl/regavio/stockahead/account/TechnicianAccountController.java:100`
- PRD: US-01, FR-002, FR-010, FR-012, § Access Control
- Prior changes: `context/archive/2026-10-01-picking-list-and-pick/plan-brief.md`, `context/archive/2026-09-30-order-reserves-parts/plan.md`
- Investigation tasks: none spawned. The surface was small and was read directly.
