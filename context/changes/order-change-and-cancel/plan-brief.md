# Order Change and Cancel — Plan Brief

> Full plan: `context/changes/order-change-and-cancel/plan.md`

## What & Why

The manager can change an order's priority and required date until a technician takes it (FR-021), and can cancel an open order (FR-011). Picked parts go back into stock in the quantities the manager enters. Without this, a wrong priority or a dropped job stays locked into the allocation and distorts the reservations and the shopping list (roadmap S-09).

## Starting Point

`OrderStatus.CANCELLED` already exists, but nothing sets it. Confirm-completion in `OrderController` already implements "lock parts → re-read → zero reservations → flush → `reallocateForParts`", and the allocator rebuilds untaken orders in priority → date → age order. So changing the schedule and releasing reservations need no new allocation logic.

## Desired End State

On an untaken order's detail page the manager edits priority and date, and reservations shift at once. An open order without a pending completion report has a cancel page that lists the picked lines with a return field prefilled with the picked amount. Confirming marks the order `CANCELLED` (who, when), adds the returned units back to stock, and hands freed and returned units to the remaining orders. The cancelled order then disappears from the order, picking and shopping lists.

## Key Decisions Made

| Decision | Choice | Why (1 sentence) | Source |
| --- | --- | --- | --- |
| Picked parts on cancel | Manager enters returned qty per line (0..picked, prefilled with picked); the rest counts as used | Soldered or damaged parts don't go back, so stock stays honest without a later correction | Plan |
| Who/when returns | Manager, in the same request that cancels | One step, no "awaiting return" state, fits the existing lock/re-check pattern | Plan |
| Cancel while completion reported | Blocked; the report must be rejected first | A pending report keeps exactly two outcomes (confirm/reject) | Plan |
| Past required date on edit | New date ≥ today, unless it equals the order's current date | A late order's priority can change without forcing its deadline to move | Plan |
| Stale cancel form | Reject if any line's picked qty differs from what the form showed | A pick made after the page loaded must not silently count as "used" | Plan |
| Unknown priority on edit | Error (create's NORMAL fallback is not reused) | Silent fallback would lower a priority by accident | Plan |
| Allocator | Unchanged; both events call `reallocateForParts(order's parts)` | Preemption both ways already falls out of the full rebuild | Plan |

## Scope

**In scope:** change priority/date of untaken orders; cancel open, unreported orders with per-line returns; `V9` columns `cancelled_at`, `cancelled_by`, `returned_quantity` with CHECKs; detail page showing cancellation and returns; integration, schema and concurrency tests.

**Out of scope:** cancelling a reported order without rejecting first; two-step technician returns; changing quantity or project; lists of cancelled or completed orders; undoing a cancel; stock-movement history (FR-006); allocator changes.

## Architecture / Approach

Two new manager actions in `OrderController` reuse the confirm/reject transition shape: a scalar pre-lock part-id lookup, then `LockRetry` + transaction → `findByIdInForUpdate` → fresh order read → state re-check → mutate → `saveAndFlush` → `reallocateForParts`. Cancel adds returns to `parts.quantity` and flushes them with the `CANCELLED` status before the reallocation, so the returned units reach other orders. DB CHECKs back the invariants (`0 ≤ returned ≤ picked`, `CANCELLED ⇔ cancelled_at`).

## Phases at a Glance

| Phase | What it delivers | Key risk |
| --- | --- | --- |
| 1. Change priority and required date | Edit form + `POST /orders/{id}/change` with reallocation | Date rule edge (unchanged past date) and the taken re-check under the lock |
| 2. Cancel with per-line returns | `V9`, cancel page + endpoint, detail display | Flush order (returns + status before the reallocation) and the double-subtract trap on return bounds |
| 3. Concurrency guarantees | Pick/cancel and pick/change race tests, stale-form test | Flaky timing in two-thread tests; reuse the existing detector pattern |

**Prerequisites:** S-07 picking and S-08 completion are done (archived), and the taken-order top-up is archived.
**Estimated effort:** ~2–3 sessions across 3 phases.

## Open Risks & Assumptions

- Assumes the physical return of parts is agreed in person before the manager confirms. The app does not involve the technician.
- The stale-form check makes the manager reload when a technician is still picking. This is intended, but could be a nuisance during busy picking.

## Success Criteria (Summary)

- The manager reorders untaken work by priority or date and sees reservations and the shopping list follow at once.
- Cancelling a partly built order puts exactly the returned parts back on the shelf in stock, and freed parts go to the next orders.
- No race between picking and cancelling or changing ever drives stock negative or records a pick on a cancelled order.
