# Taken Order Top-Up — Plan Brief

> Full plan: `context/changes/taken-order-top-up/plan.md`
> Frame brief: `context/changes/taken-order-top-up/frame.md`

## What & Why

The actual problem to plan around is: the allocator treats "taken" as "excluded from allocation" when the product rule only requires "protected from takeover". A taken order that is short must take part in normal allocation order for its unmet quantity on every stock-freeing event, without ever losing what it already holds. Technicians wait for late parts and keep building, so today's frozen behaviour leaves real work stuck.

## Starting Point

`ReservationAllocator` subtracts taken orders' reservations from the pool and rebuilds only non-taken orders. That was a deliberate MVP shortcut in S-07 (review F6, Roadmap Q4). The PRD forbids only takeover. Confirm and order creation call the allocator today; reject-completion does not.

## Desired End State

When units become free (today: confirm; later: cancel, delivery, correction, import), a short taken order receives them in normal allocation order and the technician sees them on `/picking` ("Do pobrania") and on the order's pick forms. An order awaiting completion confirmation receives nothing extra, and catches up as soon as its report is rejected.

## Key Decisions Made

| Decision | Choice | Why (1 sentence) | Source |
| --- | --- | --- | --- |
| Top up taken orders? | Yes, for unmet quantity | Plant waits for late parts and keeps building; PRD forbids only takeover. | Frame |
| Position for extra units | Normal allocation order (priority → date → age) | Matches PRD; no "taken first" bonus. | Frame |
| Cap / floor | Grow to at most required − picked; never below current reservation | V7 CHECK; shrinking would be takeover. | Frame |
| Reported orders | Skip while reported; reject reallocates | Never park stock on an order nobody can pick. | Plan |
| Technician signal | "Do pobrania" column on `/picking` | Waiting orders that receive parts stand out without opening each. | Plan |
| Partial completion / built count | Out of scope, roadmap S-13 | Separate end-state problem with its own migration. | Frame |

## Scope

**In scope:**
- Allocator rule change
- Reject reallocation
- Allocator, integration and concurrency tests
- Picking-list column
- PRD sentence and closing Roadmap Q4

**Out of scope:**
- Partial completion (S-13)
- Cancel, delivery, correction and import events (S-09 to S-12; they inherit the rule)
- Schema change, new locks, notifications
- "Taken first" priority

## Architecture / Approach

One pass in `ALLOCATION_ORDER` per affected part:
1. Seed the pool with stock minus every taken line's current reservation (the protection floor).
2. Walk non-taken orders and taken, unreported orders together:
   - non-taken lines are rebuilt as min(pool, required);
   - taken lines gain min(pool, required − picked − reserved).

Reported lines are untouched. Lock scope (`findByIdInForUpdate(partIds)`) is unchanged. Reject clears the report, flushes, then reallocates, the same order of steps confirm already uses.

## Phases at a Glance

| Phase | What it delivers | Key risk |
| --- | --- | --- |
| 1. Allocator top-up | New rule + 6 allocator tests; existing protection tests still green | Floor applied after top-up would allow takeover |
| 2. Event paths | Reject reallocates; end-to-end confirm/create/reject tests; confirm vs pick race test | Reject reallocating before flush skips the order |
| 3. Signal & docs | "Do pobrania" on `/picking`; PRD rule; Roadmap Q4 closed | Count misread as "missing" instead of "ready to pick" |

**Prerequisites:** order-completion (S-08) archived; Docker running for Testcontainers.
**Estimated effort:** ~1–2 sessions across 3 small phases.

## Open Risks & Assumptions

- S-09 to S-12 must call `reallocateForParts` for their events to benefit; this plan only covers today's callers.
- Allocator tests raise `parts.quantity` directly to stand in for a future delivery or correction. Integration tests use only real endpoints.
- A topped-up taken order can still be short; how it ends short is S-13's problem.

## Success Criteria (Summary)

- A short taken order receives freed units in allocation order and the technician can pick them.
- A reported order receives nothing extra and catches up after a reject.
- `./mvnw verify` green with the new allocator, integration and concurrency tests.
