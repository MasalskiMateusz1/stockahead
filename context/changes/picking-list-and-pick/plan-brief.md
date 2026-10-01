# Picking List and Pick — Plan Brief

> Full plan: `context/changes/picking-list-and-pick/plan.md`

## What & Why

Technicians currently have no way to see which orders need assembling or where the parts physically are, and no way to pull parts from stock — the app can reserve parts but can't record that they were actually taken. This plan adds a picking screen (FR-012, FR-013) so a technician (or manager) can see every OPEN order's checklist — part, quantity, location — and pick a quantity, partial or full, which reduces physical stock and starts protecting that order's reservation from being stolen by a later higher-priority order.

## Starting Point

`Order`/`OrderLine` already track `requiredQuantity`/`reservedQuantity`, and `ReservationAllocator` already assigns stock to open orders by priority → date → age (S-04, done). There is no picking mechanism, no "order taken" concept, and no technician-visible order view — `/orders` is manager-only today.

## Desired End State

A technician opens `/picking`, picks an order, and works through its checklist row by row, entering how many of each part they're taking (up to what's currently reserved-but-unpicked). Once they've picked anything on an order, that order is "taken" and visibly marked — and from then on, no newly created or recomputed order, however high its priority, can shrink what's already reserved for it.

## Key Decisions Made

| Decision | Choice | Why (1 sentence) |
| --- | --- | --- |
| Physical stock timing | Picking decrements `Part.quantity` immediately | Matches FR-005's "physical stock" meaning the real warehouse count, and FR-011's cancel-returns-to-stock flow only makes sense if picking removed it first |
| Taken-order protection | Freeze a taken order's reservation exactly as it was at the moment of taking; exclude it from all future reallocation recomputes | The PRD's literal "nie podlegają przejęciu" wording is satisfied by exclusion alone; letting it keep growing from new stock would need a riskier two-pass allocator change in the system's most fragile component |
| Pick UI granularity | One part line picked per form submission, not a whole-order batch | Mirrors the codebase's existing one-action-per-request pattern and needs only a single `Part` row lock per request |
| Picking route scope | New `/picking` routes open to both roles, rather than opening up the existing manager-only `/orders` screens | PRD §Access Control gives both roles the picking list/pick actions but keeps order planning/cancellation manager-only; a separate screen avoids retrofitting authorization onto `/orders` |
| Taken-order detection | Order-level flag (`taken_at`), set on first successful pick of *any* line | PRD defines "podjęcie" as an order-wide state ("pierwsze pobranie części przez technika"), reused later by FR-021 (S-09) to block priority/date edits |

## Scope

**In scope:**
- `orders.taken_at`, `order_lines.picked_quantity` schema + entity fields
- `ReservationAllocator` change to exclude/protect taken orders' lines
- `PickingController` (list, detail, per-line pick action), both roles
- Picking list/detail templates, dashboard link
- Algorithmic, concurrency, and full HTTP integration tests

**Out of scope:**
- Blocking priority/date edits or cancellation on a taken order (FR-021, FR-011 → S-09)
- Growing a taken order's reservation from later stock arrivals
- Order completion reporting (FR-014, FR-020 → S-08)
- Batch/multi-line pick submission

## Architecture / Approach

Picking never calls `ReservationAllocator` — a pick only shifts quantity between `reservedQuantity` and a new `pickedQuantity` on the same line while decrementing `Part.quantity` by the same amount, which keeps every existing invariant true without a recompute. `ReservationAllocator` itself gets one change: before running its existing priority-ordered algorithm, it subtracts taken orders' current reservations from the stock pool and excludes their lines entirely from the write set, so they're mathematically protected rather than special-cased mid-loop.

## Phases at a Glance

| Phase | What it delivers | Key risk |
| --- | --- | --- |
| 1. Schema & entities | `taken_at`, `picked_quantity` columns + entity fields | Low — additive, defaults preserve all existing behavior |
| 2. Allocator protection logic | Taken orders excluded/protected in `ReservationAllocator` | High — the system's riskiest shared component; isolated with dedicated algorithmic tests before any controller depends on it |
| 3. Picking domain & controller | `PickingController`, locking, validation, concurrency tests | Medium — new locking surface; mirrors proven `OrderController` pattern |
| 4. UI & integration tests | Templates, dashboard link, full HTTP tests | Low — thin layer over already-tested logic |

**Prerequisites:** S-04 (`order-reserves-parts`) and S-06 (`technician-accounts`), both already done.
**Estimated effort:** ~4 sessions, one per phase.

## Open Risks & Assumptions

- Assumes the Postgres auto-generated name for V6's inline `reserved_quantity` check is `order_lines_reserved_quantity_check` — verify before relying on it if the migration fails.
- Freezing a taken order's reservation (no later growth) is a deliberate MVP simplification; if the business later wants a taken-but-short order to still benefit from a delivery, that needs its own follow-up decision in S-10/S-11's planning.

## Success Criteria (Summary)

- A technician can see every OPEN order's checklist with locations and pick parts, partially or fully, with physical stock updating immediately.
- Once any part of an order has been picked, that order's reservation can never be reduced by a later reallocation, proven under real concurrent contention.
- A manager has identical picking access, per PRD §Access Control.
