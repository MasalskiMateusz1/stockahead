# Order Assignee — Plan Brief

> Full plan: `context/changes/order-set-to-specific-technician/plan.md`
> Frame brief: `context/changes/order-set-to-specific-technician/frame.md`

## What & Why

The PRD's "kierownik zleca **technikowi**" (US-01, FR-010) was never implemented. Orders need an assignee that the manager sets at creation and can change at any time, including mid-build. `/picking` should show a technician only their assigned orders, while the manager sees all orders with a per-technician filter.

## Starting Point

`orders` has no assignee column, and `GET /picking` lists every `OPEN` order to everyone. `Order` already references `Account` for `completionReportedBy`/`cancelledBy`, and accounts are deactivated, never deleted.

## Desired End State

The manager picks a technician (or themselves, or nobody) when creating an order, and can change or clear it from the order detail page while the order is open, including after it's taken. A technician's `/picking` shows only their own orders. The manager's `/picking` shows everything, with a "Technik" column and a filter: Wszystkie / Nieprzypisane / each technician. "Nieprzypisane" also catches orders whose technician was deactivated.

## Key Decisions Made

| Decision | Choice | Why (1 sentence) | Source |
| --- | --- | --- | --- |
| Problem shape | Assignment field plus a list filter, not an access-control boundary | The pain is recording the manager's intent; detail, pick and report routes stay open | Frame |
| Handover | Reassignable while `OPEN`, including taken and reported orders | Handover happens mid-build; unlike priority and date, the assignee is not frozen | Frame |
| Effect on reservations | None; not a recompute event | The assignee is metadata, not an allocator input | Frame |
| Existing orders / no assignee | Nullable column, no backfill; unassigned orders visible **only to the manager** | A technician's list is strictly their own | Plan |
| Required on create | Optional; the manager can assign later | Lets the manager plan first and decide who builds later | Plan |
| Who can be assigned | Active technicians **and the manager** | The manager is a superset of technician and may build units | Plan |
| Deactivated assignee | Row kept; order treated as unassigned and shown under "Nieprzypisane" marked "(nieaktywny)" | No data change and nothing orphaned; reactivating restores the queue | Plan |
| PRD | New FR-022; § Access Control unchanged | Easy to trace this change to its own FR | Plan |
| Concurrency | Reassignment reuses `transitionOrder`'s part-row locks | Pick and report rewrite the whole `Order` row and would otherwise overwrite a new assignee | Plan |

## Scope

**In scope:**
- V13 `orders.assignee_id` (nullable FK), the `Order.assignee` field and an assignable-accounts query
- An optional assignee on `/orders/new`; the assignee shown on `/orders` and `/orders/{id}`
- `POST /orders/{id}/assign` (manager-only; reassign or clear)
- Per-viewer `/picking` list with the manager's filter; repaired fixtures in existing tests; PRD FR-022

**Out of scope:**
- Blocking technicians from opening, picking or reporting on other technicians' orders
- Backfilling existing orders, a NOT NULL constraint, assignment history, workload or scheduling views
- Any change to reservations, allocation order or § Access Control

## Architecture / Approach

The assignee is an `Account` reference on `Order`, following the existing convention. One "assignable" rule (active, role TECHNICIAN or MANAGER) drives both pickers, the server-side validation and the manager's filter entries. "Nieprzypisane" is its complement: `assignee IS NULL OR NOT assignee.active`. Writes go through the existing lock and re-read shape without calling the allocator. `/picking` branches on the viewer's role: a technician gets `assignee = self`, and the manager gets everything plus an optional `?assignee=<id>|none` filter.

## Phases at a Glance

| Phase | What it delivers | Key risk |
| --- | --- | --- |
| 1. Data model + assign on create | V13, entity field, optional picker on create, assignee shown in the manager's order views | Invalid or inactive id handling on create |
| 2. Reassign / unassign | `POST /orders/{id}/assign` with a reassign card that stays visible after the order is taken | Lost update against a concurrent pick or report if the lock shape isn't reused |
| 3. `/picking` visibility + filter + FR-022 | Technician sees own orders; manager gets the filter; existing fixtures repaired; PRD updated | Existing technician list tests break; orders without an assignee vanish for technicians |

**Prerequisites:** Docker running for `./mvnw verify`. No prior change pending.
**Estimated effort:** ~2–3 sessions across 3 phases.

## Open Risks & Assumptions

- After the Phase 3 deploy, every existing open order is unassigned and therefore hidden from technicians until the manager assigns it. Assign them right after the deploy.
- An order assigned to the manager is invisible to every technician. This is intended.
- Visibility is a filter, not a permission: a technician who has another order's URL can still open and pick from it.

## Success Criteria (Summary)

- A technician's picking list shows exactly the open orders assigned to them, including orders handed over mid-build.
- The manager can assign, reassign or clear an order's technician at any point while it's open, and can always find orders nobody is working on under "Nieprzypisane".
- Reservations and allocation order behave exactly as before.
