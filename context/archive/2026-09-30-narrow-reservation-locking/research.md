---
researched: 2026-10-01
researcher: Claude Sonnet 5
commit: 98915f8c9fda49d77f74bed2dad44152697dc41a
branch: main
---

# Research: Narrowing ReservationAllocator's part-row lock

## Question

How can `ReservationAllocator`'s full-`parts`-table `PESSIMISTIC_WRITE` lock be
narrowed to only the rows a triggering event actually affects, without
reintroducing the under-reservation race or the deadlock risk already recorded
in `context/foundation/lessons.md`?

## Starting priors (from `context/foundation/lessons.md`)

The lessons file carries one directly-on-topic entry, written the same day
this change folder was created (`context/foundation/lessons.md:45-53`,
commit `98915f8`, from `context/changes/order-reserves-parts/reviews/impl-review.md:47-55`,
finding F2). It records two narrowing alternatives already analyzed and
rejected:

1. **Unlocked pre-query**: compute "parts referenced by open orders" via an
   unlocked `SELECT`, then lock only that part set. Rejected because a
   concurrent transaction can commit a new order against a shared part in the
   gap between the unlocked read and the lock acquisition, silently
   under-counting that reservation.
2. **Iterative lock-escalation**: lock what's known, recheck for newly
   relevant parts, lock those too, repeat until stable. Rejected because two
   transactions can each hold a partial, differently-ordered lock set across
   rounds and wait on each other forever — a Postgres deadlock that kills one
   transaction with an unhandled error, silently failing that order-creation
   request.

The lesson's rule: any future narrowing attempt "must ship with explicit
catch-and-retry logic around the whole order-creation transaction for a
Postgres deadlock error, verified under both `ReservationAllocatorTests`-style
algorithmic tests and `ReservationConcurrencyTests`/`OrderCreationIntegrationTests`-style
real-concurrency tests," planned via `/10x-plan` rather than an inline edit.

## Current implementation

- `ReservationAllocator.reallocateAll()` (`src/main/java/pl/regavio/stockahead/orders/ReservationAllocator.java:53-72`)
  does a full recompute: lock every `Part` row via `PartRepository.findAllForUpdate()`
  (`PESSIMISTIC_WRITE`, `src/main/java/pl/regavio/stockahead/parts/PartRepository.java:26-28`,
  `ORDER BY p.id`), seed a per-part stock pool, then walk **every** `OPEN`
  order's **every** line (`orderRepository.findByStatus(OrderStatus.OPEN)`) in
  `ALLOCATION_ORDER` and assign `min(remainingStock, requiredQuantity)`.
- The allocation algorithm has **no cross-part interaction**: the only shared
  state is `Map<Long, Integer> remainingStockByPartId`, keyed and consumed
  strictly per `line.getPart().getId()` (`ReservationAllocator.java:54-70`).
  An order's lines for different parts never compete with each other or
  affect each other's outcome — only lines sharing the same `part_id` compete
  for that part's pool.
- The only current caller is `OrderController.create()`
  (`src/main/java/pl/regavio/stockahead/orders/OrderController.java:118-140`):
  inside one `TransactionTemplate.execute()` block it saves the new
  `Order`/`OrderLine`s (`saveAndFlush`), then calls `reservationAllocator.reallocateAll()`
  — part locks and order writes commit atomically together, per the class
  Javadoc's documented contract (`ReservationAllocator.java:17-22`).
- No retry-on-deadlock logic exists anywhere in the codebase today (`grep` for
  `CannotAcquireLockException`, `DeadlockLoserDataAccessException`,
  `PessimisticLockingFailureException`, `@Retryable` across `src/main/java`
  returned nothing), and `pom.xml` has no `spring-retry` dependency — any
  deadlock-retry logic would be new infrastructure, not an existing pattern to
  reuse.
- Concurrency correctness is proven today by two real-Postgres
  (Testcontainers, `postgres:18` — `src/test/java/pl/regavio/stockahead/TestcontainersConfiguration.java:14-15`)
  tests: `ReservationConcurrencyTests` (calls `reallocateAll()` directly,
  two threads racing to reserve the same scarce part) and
  `OrderCreationIntegrationTests.twoConcurrentOrderCreationRequestsForScarcePartNeverTogetherReserveMoreThanStock()`
  (same race at the HTTP layer through `OrderController`). Both assert
  `SUM(reserved_quantity) <= stock`. Neither currently exercises more than one
  contended part at a time, so neither would catch a cross-part deadlock from
  a narrowed, multi-part lock acquisition.

## Future call sites (not yet implemented)

`context/foundation/prd.md:120` (§Business Logic) lists every event that must
trigger a recompute: order creation; a manager changing an order's priority or
required date; a delivery receipt or CSV import ("new units automatically
fill reservations in order"); a stock correction; an order being canceled or
closed (freed reservations flow to the next orders). Today only order
creation is wired up (`OrderController.create()`); the roadmap
(`context/foundation/roadmap.md:52-59`) has the other triggers as `proposed`
slices not yet started: `S-09` order-change-and-cancel (FR-021, FR-011),
`S-10` delivery-receipt (FR-004), `S-11` stock-correction (FR-018), `S-12`
parts-csv-import (FR-017). Any narrowing design should generalize across
these future call sites, not just the one that exists today — the lesson's
rule is written at the `ReservationAllocator`/`PartRepository` level, not
scoped to `OrderController` alone.

## A candidate narrowing shape the prior analysis did not evaluate

The two alternatives lessons.md rejected both share one trait: they derive
the lock's part-id set **from a database read** (an unlocked query, or an
iterative re-query loop) — which is exactly what opens the TOCTOU race (for
alternative 1) and the cross-transaction lock-ordering hazard (for
alternative 2).

For every trigger event §Business Logic names, the set of parts a single
event can possibly affect is already known **synchronously, from data the
caller already holds, with no query at all**:

- Order creation: the new order's own BOM-derived lines name their parts
  directly (`OrderController.create()` already builds these lines before
  calling `reallocateAll()`, `OrderController.java:130-136`).
- Priority/required-date change on an existing order: that order's own
  `lines` collection names its parts.
- Stock correction or delivery receipt: the operation names one specific
  part (or, for CSV import, an explicit list of parts) directly.
- Order cancel/close: the canceled order's own `lines` collection.

Because the allocation algorithm has no cross-part interaction (confirmed
above), a recompute triggered by any one of these events only needs to touch
the parts named by the event itself — never a part discovered by reading
other orders' state. A design built on this fact would:

1. Collect the affected part ids from already-in-hand data (no unlocked
   read).
2. Lock exactly that id set in one query, e.g.
   `SELECT p FROM Part p WHERE p.id IN (:ids) ORDER BY p.id FOR UPDATE` —
   keeping the existing `ORDER BY p.id` discipline so two transactions
   needing an overlapping multi-part set (e.g. one order with several BOM
   lines) always attempt acquisition in the same ascending order, which is
   the standard means of avoiding a wait-cycle deadlock between lock
   acquisitions on the same set of rows.
3. Reload and recompute only the `OrderLine`s whose `part_id` is in that
   locked set (not every open order), leaving every other part's
   reservations untouched.

This sidesteps both rejected failure modes as described: it removes the
unlocked-read-then-lock gap (no DB read precedes lock acquisition — the id
set comes from already-loaded/caller-supplied data) and it removes the
iterative, round-by-round lock growth that produced the escalation
deadlock (the full id set is known and locked in a single statement, not
discovered incrementally).

## Open questions this research surfaces but does not settle

- **Does a single-query, consistently-ordered lock acquisition eliminate the
  need for the lesson's deadlock-retry requirement, or only the two
  specific failure modes it was written against?** Postgres deadlocks can
  still arise from lock interactions outside this one query (e.g. unique
  index or foreign-key locks taken during the same transaction's `INSERT`s),
  so retry-on-`CannotAcquireLockException`/`DeadlockLoserDataAccessException`
  may still be warranted as defense-in-depth even if the specific mechanism
  the lesson analyzed no longer applies. This is a design choice for
  `/10x-plan`, not something this research can close by inspection alone.
- **Algorithm change scope**: narrowing from "recompute every open order's
  every line" to "recompute only lines whose part is in the locked set"
  is a real change to `reallocateAll()`'s shape (today it unconditionally
  loads `orderRepository.findByStatus(OrderStatus.OPEN)` and iterates every
  line, `ReservationAllocator.java:59-71`), not a one-line change to
  `PartRepository.findAllForUpdate()`'s query. No existing repository method
  loads orders filtered by a part-id set — `OrderRepository` currently
  only has `findByStatus`, `findByStatusWithProject`, and
  `findByIdWithDetails` (`src/main/java/pl/regavio/stockahead/orders/OrderRepository.java:12-18`);
  a new query would be needed.
  **This research did not verify whether such a query is actually simple to
  write against the existing `OrderLine`/`Order` mapping** — that's
  implementation detail for the plan, not confirmed here.
- **Test coverage gap**: neither `ReservationAllocatorTests` nor
  `ReservationConcurrencyTests`/`OrderCreationIntegrationTests` currently
  exercises a transaction that needs to lock more than one part at once, so
  none of them would currently catch a multi-part lock-ordering bug. A plan
  that narrows the lock should add a concurrency test shaped around two (or
  more) transactions each needing an overlapping multi-part set, per the
  lesson's own testing rule.
- **Whether this shape generalizes to every future trigger** is asserted
  here from reading §Business Logic's event list, not from having
  implemented any of S-09/S-10/S-11/S-12 yet — those slices are `proposed`,
  not built, so their exact data shape (e.g. what a CSV import's affected-parts
  list looks like) isn't locked down.

## For planning hand-off

- Settled facts (with sources above): the current full-table lock's shape
  and single call site; the algorithm's per-part independence; the absence
  of any existing retry/deadlock infrastructure; the two previously-rejected
  approaches and why.
- Unresolved product/design choice for `/10x-plan`: whether to adopt the
  "caller-supplied affected-part-ids, single ordered query" shape described
  above, keep the full-table lock, or choose a different approach — and,
  if narrowing, whether to still add deadlock-retry logic as defense in
  depth per the lesson's existing rule.
- Per the lesson's explicit rule, any implementation of this must still ship
  with both an algorithmic test (`ReservationAllocatorTests`-style) and a
  real-concurrency test (`ReservationConcurrencyTests`/`OrderCreationIntegrationTests`-style)
  covering a multi-part contention scenario, not just the single-part
  scenario the existing tests cover.
