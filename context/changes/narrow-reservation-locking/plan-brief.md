# Narrow Reservation Locking — Plan Brief

> Full plan: `context/changes/narrow-reservation-locking/plan.md`
> Research: `context/changes/narrow-reservation-locking/research.md`

## What & Why

`ReservationAllocator` currently locks every row of the `parts` table on
every single order creation, serializing all concurrent order-creation
transactions against the whole table regardless of which parts they touch.
This plan narrows that lock to just the parts a triggering event actually
affects, adds a bounded deadlock-retry as defense-in-depth, and adds the
multi-part concurrency test the current suite doesn't have.

## Starting Point

`ReservationAllocator.reallocateAll()` locks all parts via
`PartRepository.findAllForUpdate()`, then recomputes every line of every
`OPEN` order. Its only caller is `OrderController.create()`. Two prior
narrowing attempts were already analyzed and rejected in
`context/foundation/lessons.md` — an unlocked pre-query (reopens an
under-reservation race) and an iterative lock-escalation loop (deadlock-prone
across concurrent transactions).

## Desired End State

`OrderController.create()` locks only the parts the new order's own BOM
lines reference, in one ascending-id-ordered query, and recomputes only
those parts' reservations — everything else is unaffected and unblocked. If
the transaction hits lock contention it retries automatically a few times
before showing the same generic error it shows today for any save failure.

## Key Decisions Made

| Decision | Choice | Why (1 sentence) | Source |
| --- | --- | --- | --- |
| Lock-set source | Derive affected part ids from the caller's own already-loaded data, never a query | Both previously-rejected approaches failed specifically because they derived the lock set from a database read | Research |
| Deadlock retry | Add a bounded (3-attempt) hand-rolled retry around the whole transaction | `lessons.md`'s rule requires it for any narrowing attempt; Postgres can still deadlock from lock interactions outside this one query | Plan |
| Retry implementation | Hand-rolled loop, no new dependency | No `spring-retry` dependency exists; avoids adding one for a 3-attempt loop | Plan |
| API scope | Generic `reallocateForParts(Set<Long>)` signature, but wire up only `OrderController.create()` | Future triggers (S-09..S-12) aren't built yet; the signature is reusable without pre-building their call sites | Plan |
| Old full-table method | Delete `reallocateAll()` / `findAllForUpdate()` entirely | No caller survives the switch; AGENTS.md convention is to delete confirmed-unused code, not keep a dormant fallback | Plan |
| Retry-exhaustion UX | Reuse the existing `orders.error.saveFailed` message | Matches the existing DB-error-handling pattern; no user-facing value in a separate "system busy" message | Plan |
| New test scope | Two orders sharing an overlapping two-part BOM, concurrently | Directly proves the ascending-id lock order prevents the exact wait-cycle deadlock `lessons.md` warned about | Plan |

## Scope

**In scope:**
- `PartRepository`: new `findByIdInForUpdate(ids)`, remove `findAllForUpdate()`
- `OrderLineRepository`: new `findOpenLinesForParts(partIds)`
- `OrderRepository`: remove dead `findByStatus(OrderStatus)`
- `ReservationAllocator`: `reallocateForParts(Set<Long>)` replaces `reallocateAll()`
- `OrderController`: compute affected part ids; bounded retry on lock contention
- Update `ReservationAllocatorTests` / `ReservationConcurrencyTests` to the new signature; add one new multi-part concurrency test

**Out of scope:**
- Wiring up S-09 (priority/cancel), S-10 (delivery), S-11 (stock correction), S-12 (CSV import) — separate roadmap slices
- Any new Maven dependency (e.g. `spring-retry`)
- Keeping the full-table lock method as a fallback/admin tool
- A dedicated "system busy" error message
- Forcing a real Postgres deadlock in an automated test (verified by code review instead)
- Any schema/migration change

## Architecture / Approach

Every PRD-named trigger for a reservation recompute (order creation,
priority/date change, delivery, stock correction, cancel) already names its
affected parts from data the caller holds — never from a query. Combined
with the allocator's per-part-independent algorithm, this means a trigger
can lock exactly its own affected part ids, in one `ORDER BY id` query, and
recompute only those parts' lines — no unlocked pre-read, no iterative
re-locking, so neither of the two previously-rejected failure modes applies.
A bounded retry around the transaction remains as defense-in-depth against
deadlocks from sources outside this one query.

## Phases at a Glance

| Phase | What it delivers | Key risk |
| --- | --- | --- |
| 1. Narrow the lock and the allocation algorithm | New part-id-scoped lock query + line query; `reallocateForParts` replaces `reallocateAll`; `OrderController` wired up; existing tests updated | A JPA fetch-join-with-filter trap on `order.getLines()` if not sidestepped by selecting `OrderLine` directly |
| 2. Deadlock-retry defense in depth | Bounded 3-attempt retry around the order-creation transaction on `PessimisticLockingFailureException` | Retry-exhaustion path can't be forced in a real deadlock test — verified by code review instead |
| 3. Multi-part concurrency coverage | New test: two orders sharing a two-part BOM, concurrently, prove no deadlock and no over-reservation | A test that passes vacuously without actually exercising contention — mitigated by the manual "break `ORDER BY` and confirm it fails" step |

**Prerequisites:** None beyond the existing `order-reserves-parts` slice (already `in-progress`/merged) — no new dependencies, no schema change.
**Estimated effort:** ~1 session across 3 phases; small, well-bounded diff (2 repositories, 1 allocator class, 1 controller method, 2 test files + 1 new test).

## Open Risks & Assumptions

- Assumes every future trigger (S-09..S-12) will continue to have its
  affected part ids available synchronously without a query, as researched —
  not yet verified against those slices' actual implementations since they
  don't exist yet.
- The deadlock-retry path (Phase 2) is defense-in-depth that can't be proven
  against a real forced deadlock in this test suite; its correctness rests on
  code review, not an automated regression test.

## Success Criteria (Summary)

- Concurrent order creations touching disjoint parts no longer block on each
  other through the removed full-table lock
- Stock-never-negative / never-double-reserved invariant holds under the new
  multi-part concurrency test, same as it already does for the single-part
  case
- `./mvnw verify` green across all three phases with no behavior change to
  the existing single-part order-creation flow
