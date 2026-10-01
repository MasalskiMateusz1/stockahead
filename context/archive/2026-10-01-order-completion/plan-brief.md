# Order Completion (Report & Confirm) — Plan Brief

> Full plan: `context/changes/order-completion/plan.md`

## What & Why

Roadmap S-08 (FR-014, FR-020): a technician reports an order as finished and a manager confirms it; on confirmation the order's unpicked reservations are released and flow to the next open orders. Without it, finished orders keep holding parts and the shopping list overstates what to buy.

## Starting Point

`OrderStatus.COMPLETED` exists but is never set. Reservations, the parts "reserved" column and the shopping list all count `OPEN` lines only, and unpicked units are still in `Part.quantity` — so flipping status plus calling the existing `ReservationAllocator` releases them. Picking (S-07) already defines a "taken" order (`taken_at`) and the lock-then-re-read action pattern.

## Desired End State

Technicians (and managers) see "Zgłoś zakończenie" on a taken order's picking page; reporting freezes picking. Managers see a "Do potwierdzenia" section on `/orders`, review reporter, time and unmet lines on the detail page, then confirm (order closes, reservations reallocate, shortages leave the shopping list) or reject (order returns to picking).

## Key Decisions Made

| Decision | Choice | Why (1 sentence) |
| --- | --- | --- |
| Who can be reported | Taken orders only (≥ 1 pick); unmet lines allowed | Blocks meaningless reports but lets builds with substitutes close; manager confirmation is the safeguard. |
| State while pending | Stays `OPEN`, reservations held, picking frozen | FR-020 releases on confirmation; manager confirms a stable state. |
| Rejection | Yes — clears the report, no reason field | Otherwise a mistaken report is stuck or forces a cancel that wrongly returns used parts. |
| Manager shortcut | None — report, then confirm | One state path; manager can file the report themselves. |
| Visibility | "Do potwierdzenia" section on `/orders`; completed orders leave both lists | Pending reports visible where the manager works; no history list (FR-006 is a Non-Goal). |
| Release mechanics | `COMPLETED` + zero lines' reserved, flush, then `reallocateForParts` | Reuses the allocator unchanged; order matters (see plan). |
| Concurrency | All transitions lock the order's part rows like a pick | Serializes report vs pick and double submits without a new lock type. |

## Scope

**In scope:** V8 migration (reported/completed columns + CHECKs), pick guard, report action + picking UI, confirm/reject actions, `/orders` pending section, order detail review UI, integration + concurrency tests.

**Out of scope:** direct confirm without report, rejection reason/notifications, completed-orders history, cancellation and returning picked parts (S-09), realtime push.

## Architecture / Approach

Three POST routes (`/picking/{id}/report-completion` for both roles; `/orders/{id}/confirm-completion` and `/reject-completion` for managers), each: scalar lookup of the order's part ids → `LockRetry` + transaction → `findByIdInForUpdate` → fresh order read → state re-check → write. Confirm then calls `ReservationAllocator.reallocateForParts`.

## Phases at a Glance

| Phase | What it delivers | Key risk |
| --- | --- | --- |
| 1. Schema & completion state | V8 columns + CHECKs, `Order` mapping, pick guard | CHECK wording rejecting valid transitions |
| 2. Technician report | Report route, picking UI, race test | Pick slipping in after the report |
| 3. Manager confirm/reject & visibility | Confirm releases & reallocates, reject, `/orders` section, detail UI | Calling the allocator before the status flip leaves units stranded |

**Prerequisites:** S-07 `done` (taken orders, picking); Docker for tests.
**Estimated effort:** ~2–3 sessions across 3 phases.

## Open Risks & Assumptions

- Confirming an order with unmet lines silently drops its shortages from the shopping list — intended, surfaced to the manager as a warning before confirm.
- S-09 (cancel) is planned in parallel; it must decide how cancellation treats a reported order.
- Manager remains the bottleneck (PRD risk) — unchanged by design.

## Success Criteria (Summary)

- A reported order can no longer be picked, and only a manager can close or reopen it.
- After confirmation, the next order in allocation order receives the released units and `/purchasing` no longer lists the closed order's shortages.
- `./mvnw verify` green with role, transition, repeat-cycle and concurrency tests.
