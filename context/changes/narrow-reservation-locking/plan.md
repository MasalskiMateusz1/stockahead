# Narrow Reservation Locking Implementation Plan

## Overview

Replace `ReservationAllocator`'s full `parts`-table `PESSIMISTIC_WRITE` lock
with a lock scoped to only the part ids a triggering event actually affects,
add a bounded deadlock-retry around the order-creation transaction as
defense-in-depth, and add the multi-part concurrency test the existing suite
doesn't cover.

## Current State Analysis

- `ReservationAllocator.reallocateAll()` (`src/main/java/pl/regavio/stockahead/orders/ReservationAllocator.java:53-72`)
  locks **every** `Part` row via `PartRepository.findAllForUpdate()`
  (`src/main/java/pl/regavio/stockahead/parts/PartRepository.java:26-28`),
  then recomputes **every** line of **every** `OPEN` order
  (`orderRepository.findByStatus(OrderStatus.OPEN)`), serializing all
  concurrent order-creation transactions against the whole `parts` table
  regardless of which parts they actually touch.
- The algorithm has no cross-part interaction — the only shared state is
  `Map<Long, Integer> remainingStockByPartId`, keyed and consumed strictly
  per `line.getPart().getId()` (`ReservationAllocator.java:54-70`). Lines for
  different parts never compete or affect each other's outcome.
- The only caller is `OrderController.create()`
  (`src/main/java/pl/regavio/stockahead/orders/OrderController.java:118-140`):
  one `TransactionTemplate.execute()` block saves the new `Order`/`OrderLine`s
  then calls `reservationAllocator.reallocateAll()`. `OrderRepository.findByStatus(OrderStatus)`
  (`src/main/java/pl/regavio/stockahead/orders/OrderRepository.java:12`) has no
  other caller.
- Two prior narrowing approaches were analyzed and rejected
  (`context/foundation/lessons.md:45-53`): an unlocked pre-query to find
  "parts referenced by open orders" (reopens an under-reservation race in the
  gap between the read and the lock), and an iterative lock-escalation loop
  (deadlock-prone across concurrent transactions holding partial, differently
  ordered lock sets). No retry-on-deadlock infrastructure exists anywhere in
  the codebase today (no `spring-retry` dependency, no catch for any
  `PessimisticLockingFailureException` subtype).
- Concurrency correctness is proven today by `ReservationConcurrencyTests`
  and `OrderCreationIntegrationTests.twoConcurrentOrderCreationRequestsForScarcePartNeverTogetherReserveMoreThanStock()`,
  both against real Postgres (Testcontainers `postgres:18`). Neither exercises
  more than one contended part per transaction.
- Full findings: `context/changes/narrow-reservation-locking/research.md`.

### Key Discoveries:

- For every trigger `context/foundation/prd.md:120` (§Business Logic) names
  (order creation, priority/date change, delivery, stock correction, order
  cancel), the set of parts a single event can affect is already known
  synchronously from data the caller holds — never from a database read. This
  is what makes narrowing safe without repeating either rejected approach's
  mistake (both derived their lock set from a query).
- `ORDER BY p.id` is the one property doing the deadlock-avoidance work: a
  single query that locks a caller-supplied id set in ascending order means
  any two transactions needing an overlapping multi-part set always attempt
  acquisition in the same order, so one blocks on the other's first lock
  instead of each holding one and waiting on the other.
- Only `OrderController.create()` exists as a caller today; `S-09`
  (priority/cancel), `S-10` (delivery), `S-11` (stock correction) and `S-12`
  (CSV import) are `proposed`, not built (`context/foundation/roadmap.md:56-59`).

## Desired End State

`ReservationAllocator` exposes `reallocateForParts(Set<Long> partIds)`
instead of `reallocateAll()`. `OrderController.create()` computes the new
order's own part ids and passes them through; the transaction retries a
bounded number of times on a lock-contention failure before falling back to
the existing save-failure error message. A concurrency test proves two
transactions contending for the same two-part BOM neither deadlock nor
jointly over-reserve either part.

Verification: `./mvnw verify` passes with the updated and new tests; manual
smoke-test of order creation still reserves/short-falls correctly.

## What We're NOT Doing

- Not wiring up any of the future triggers (`S-09` priority/cancel, `S-10`
  delivery, `S-11` stock correction, `S-12` CSV import) — those stay separate
  roadmap slices. `reallocateForParts(Set<Long>)`'s signature is generic
  enough for them to adopt later without a rewrite, but this change only
  updates `OrderController.create()`.
- Not adding `spring-retry` or any new Maven dependency — the retry is a
  small hand-rolled loop.
- Not keeping `reallocateAll()` / `findAllForUpdate()` as a fallback or admin
  "recompute everything" tool — deleted outright; nothing references them
  once `OrderController` switches over.
- Not adding a dedicated "system busy" error message — retry exhaustion
  reuses the existing `orders.error.saveFailed` message.
- Not attempting to force a genuine Postgres deadlock in an automated test to
  exercise retry-exhaustion end-to-end — confirmed impractical to do
  reliably; that path is verified by code review instead (see Phase 2).
- Not changing `ALLOCATION_ORDER`, `OrderStatus`, `Priority`, or any
  allocation-ordering rule.
- Not touching `OrderDetailModel`, the `/orders` list/detail rendering, or
  `OrderRepository.findByStatusWithProject` / `findByIdWithDetails`.
- No Flyway migration — no schema change.

## Implementation Approach

Lock exactly the part ids a trigger's own already-loaded data names, in one
`ORDER BY id`-sorted query, and recompute only the `OrderLine`s touching
those parts — never derive the lock set from an unlocked read or an
iterative re-query. Keep the change scoped to today's single caller
(`OrderController.create()`); add deadlock-retry as defense-in-depth per
`context/foundation/lessons.md`'s rule, even though the ordered single-query
design eliminates the two previously-identified failure modes. Add the
multi-part concurrency test this design makes possible (the current suite
has none).

## Critical Implementation Details

- **Lock ordering is the safety property, not a performance nicety.**
  `findByIdInForUpdate` must keep `ORDER BY p.id` even though the caller
  passes an unordered `Set`. Every caller — today's and any future one — must
  lock its *entire* affected-id set in this one query; splitting it across
  two lock calls within the same transaction breaks the ascending-order
  guarantee and reopens the escalation-deadlock risk `lessons.md` already
  rejected once.
- **Group fetched lines by `Order::getId`, not by `Order` entity equality.**
  `OrderLine::getOrder` instances returned by the new query share
  persistence-context identity within one query, but relying on that (e.g.
  `Collectors.groupingBy(OrderLine::getOrder)`) depends on `Order`'s default
  `Object` identity semantics. Group by the `Long` id instead.
- **`findOpenLinesForParts` must return flat `OrderLine` rows, never via
  `order.getLines()`.** A `JOIN FETCH` restricted by a `WHERE` clause on a
  collection association (e.g. `JOIN FETCH o.lines l WHERE l.part.id IN
  :ids`) marks that collection fully initialized in Hibernate's first-level
  cache despite holding only the filtered subset — a later call to
  `order.getLines()` in the same persistence context would silently return
  the filtered list, not every line of that order. Selecting `OrderLine`
  directly sidesteps this trap entirely.

## Phase 1: Narrow the lock and the allocation algorithm

### Overview

Replace the full-table lock and full-order scan with a caller-supplied,
part-id-scoped query pair, and rewrite `ReservationAllocator` around them.
Wire `OrderController` to the new signature. Update the two existing test
files to compile and pass against the new contract — no new test coverage
yet, this phase is a behavior-preserving narrowing for the single-part case.

### Changes Required:

#### 1. `PartRepository`

**File**: `src/main/java/pl/regavio/stockahead/parts/PartRepository.java`

**Intent**: Replace the full-table lock query with one that locks only a
caller-supplied id set, in ascending id order.

**Contract**: Remove `findAllForUpdate()`. Add
`findByIdInForUpdate(Collection<Long> ids)`, `@Lock(LockModeType.PESSIMISTIC_WRITE)`,
equivalent to `SELECT p FROM Part p WHERE p.id IN :ids ORDER BY p.id` — the
`ORDER BY p.id` is load-bearing (see Critical Implementation Details).

#### 2. `OrderLineRepository`

**File**: `src/main/java/pl/regavio/stockahead/orders/OrderLineRepository.java`

**Intent**: Load exactly the `OPEN`-order lines that touch a given part-id
set, with `Order` and `Part` eagerly fetched, without going through
`order.getLines()`.

**Contract**: Add `findOpenLinesForParts(Collection<Long> partIds)` returning
`List<OrderLine>`, selecting `OrderLine` directly with `JOIN FETCH ol.order`
and `JOIN FETCH ol.part`, filtered to `OrderStatus.OPEN` and
`ol.part.id IN :partIds` (see Critical Implementation Details on why this
must select `OrderLine`, not `Order`).

#### 3. `OrderRepository`

**File**: `src/main/java/pl/regavio/stockahead/orders/OrderRepository.java`

**Intent**: Remove the now-dead full-table-scan method.

**Contract**: Delete `findByStatus(OrderStatus status)` — `ReservationAllocator`
was its only caller.

#### 4. `ReservationAllocator`

**File**: `src/main/java/pl/regavio/stockahead/orders/ReservationAllocator.java`

**Intent**: Recompute reservations only for the given parts' lines, locking
only those parts, instead of a full-table recompute.

**Contract**: Replace `reallocateAll()` with `reallocateForParts(Set<Long> partIds)`
(package-private, same visibility as today). Drop the `OrderRepository`
dependency; add `OrderLineRepository`. Shape:

```java
void reallocateForParts(Set<Long> partIds) {
	if (partIds.isEmpty()) {
		return;
	}
	Map<Long, Integer> remainingStockByPartId = new HashMap<>();
	for (Part part : partRepository.findByIdInForUpdate(partIds)) {
		remainingStockByPartId.put(part.getId(), part.getQuantity());
	}
	List<OrderLine> affectedLines = orderLineRepository.findOpenLinesForParts(partIds);
	Map<Long, Order> ordersById = affectedLines.stream()
		.collect(Collectors.toMap(l -> l.getOrder().getId(), OrderLine::getOrder, (a, b) -> a));
	Map<Long, List<OrderLine>> linesByOrderId = affectedLines.stream()
		.collect(Collectors.groupingBy(l -> l.getOrder().getId()));
	for (Order order : ordersById.values().stream().sorted(ALLOCATION_ORDER).toList()) {
		for (OrderLine line : linesByOrderId.get(order.getId())) {
			Long partId = line.getPart().getId();
			int remaining = remainingStockByPartId.getOrDefault(partId, 0);
			int reserved = Math.min(remaining, line.getRequiredQuantity());
			line.setReservedQuantity(reserved);
			remainingStockByPartId.put(partId, remaining - reserved);
		}
	}
}
```

Update the class Javadoc: it no longer locks every `Part` row — it locks
exactly the given ids — and callers must pass every part id their own
already-loaded writes touch (never derive the set from a separate query).

#### 5. `OrderController`

**File**: `src/main/java/pl/regavio/stockahead/orders/OrderController.java`

**Intent**: Compute the new order's own affected part ids from its
just-built lines and pass them to the narrowed allocator call.

**Contract**: In `create()`, after `orderRepository.saveAndFlush(order)`,
derive `Set<Long> affectedPartIds` from `order.getLines()` (each line's
`getPart().getId()`) and call `reservationAllocator.reallocateForParts(affectedPartIds)`
in place of `reallocateAll()`.

#### 6. Existing test updates

**File**: `src/test/java/pl/regavio/stockahead/orders/ReservationAllocatorTests.java`

**Intent**: Keep all 5 existing tests passing unchanged in behavior, against
the new signature.

**Contract**: Change the private `reallocateAll()` helper to
`reallocate(Set<Long> partIds)`, calling `reservationAllocator.reallocateForParts(partIds)`;
update all 6 call sites to pass `Set.of(partId)` for that test's seeded part.

**File**: `src/test/java/pl/regavio/stockahead/orders/ReservationConcurrencyTests.java`

**Intent**: Keep the existing single-part race test passing against the new
signature.

**Contract**: Change `reservationAllocator.reallocateAll()` to
`reservationAllocator.reallocateForParts(Set.of(partId))`; update the class
Javadoc's references to `reallocateAll()`/`findAllForUpdate()`.

### Success Criteria:

#### Automated Verification:

- `./mvnw verify` passes (compiles; all existing tests green with the
  updated signatures; no behavior change for the single-part case)

#### Manual Verification:

- Create an order via `/orders/new` for a project with a known part
  shortage; confirm the reserved/missing quantities on the order detail page
  match what the same input produced before this change

---

## Phase 2: Deadlock-retry defense in depth

### Overview

Wrap `OrderController.create()`'s transaction in a small bounded retry that
catches lock-contention failures, so the new narrower lock has the same
defense-in-depth `lessons.md` requires of any narrowing attempt — even though
the ordered single-query design eliminates the two failure modes that rule
was originally written against.

### Changes Required:

#### 1. `OrderController`

**File**: `src/main/java/pl/regavio/stockahead/orders/OrderController.java`

**Intent**: Retry the whole order-creation transaction a bounded number of
times if it fails on lock contention, before giving up and showing the
existing generic save-failure error.

**Contract**: Wrap the existing `transactionTemplate.execute(...)` call in a
loop of at most 3 attempts, retrying only on
`org.springframework.dao.PessimisticLockingFailureException` (the common
superclass Spring uses for both deadlock and lock-timeout translations — it
covers `DeadlockLoserDataAccessException` and `CannotAcquireLockException`
without needing to enumerate each). On exhaustion, re-throw the last
failure; widen the existing `catch (DataIntegrityViolationException ex)`
block to also catch `PessimisticLockingFailureException` and render the same
`orders.error.saveFailed` message for both:

```java
private <T> T executeWithLockRetry(Supplier<T> action) {
	PessimisticLockingFailureException lastFailure = null;
	for (int attempt = 1; attempt <= MAX_LOCK_RETRY_ATTEMPTS; attempt++) {
		try {
			return action.get();
		}
		catch (PessimisticLockingFailureException ex) {
			lastFailure = ex;
		}
	}
	throw lastFailure;
}
```

`MAX_LOCK_RETRY_ATTEMPTS = 3`, no backoff delay between attempts (contention
windows here are short-lived; this is a small in-process app, not a
high-QPS service).

### Success Criteria:

#### Automated Verification:

- `./mvnw verify` passes (the retry wrapper is a no-op on the happy path;
  existing happy-path and `DataIntegrityViolationException` error-path tests
  are unaffected)

#### Manual Verification:

- Code review confirms: the retry wraps the *entire* transaction (not a
  partial step inside it), the attempt bound is finite, and exhaustion falls
  through to the existing `orders.error.saveFailed` rendering path — forcing
  a genuine Postgres deadlock on demand to test this end-to-end was assessed
  as impractical for this suite (see "What We're NOT Doing")

---

## Phase 3: Multi-part concurrency coverage

### Overview

Add the concurrency test neither existing file covers: two transactions
racing for an *overlapping, multi-part* lock set, proving the ascending-id
single-query design avoids the wait-cycle deadlock `lessons.md` originally
rejected the escalation approach for.

### Changes Required:

#### 1. New concurrency test

**File**: `src/test/java/pl/regavio/stockahead/orders/ReservationConcurrencyTests.java`

**Intent**: Prove two concurrent transactions each needing the same two
scarce parts neither deadlock nor jointly over-reserve either part.

**Contract**: New test
`twoConcurrentOrdersSharingATwoPartBomNeverDeadlockOrOverReserveEitherPart()`,
following the existing test's `CountDownLatch`/`ExecutorService` pattern:
seed two scarce parts (`partA`, `partB`); two threads each insert a
competing order with lines on both parts (same two-part set) and call
`reservationAllocator.reallocateForParts(Set.of(partAId, partBId))` inside
its own transaction; both futures must complete without an unhandled
exception; assert `SUM(reserved_quantity) <= stock` independently for both
`partA` and `partB` afterward.

### Success Criteria:

#### Automated Verification:

- `./mvnw verify` passes, including the new test

#### Manual Verification:

- Temporarily remove `ORDER BY p.id` from `findByIdInForUpdate`, confirm the
  new test then fails or hangs (proving it actually exercises the
  lock-ordering property it claims to), then restore the ordering

---

## Testing Strategy

### Unit Tests:

- `ReservationAllocatorTests` (algorithmic, no HTTP): all 5 existing tests
  updated to the new `reallocateForParts`-backed helper, unchanged assertions

### Integration Tests:

- `ReservationConcurrencyTests`: existing single-part race test updated to
  the new signature; new multi-part overlapping-lock-set test added
- `OrderCreationIntegrationTests`: unchanged call shape (goes through
  `OrderController`), existing single-part HTTP-level race test continues to
  cover the production wiring end-to-end through the new retry path

### Manual Testing Steps:

1. Create an order via `/orders/new` for a project with a known shortage;
   confirm reserved/missing quantities are unchanged from before this plan
2. Code-review the retry wrapper's exception type, bound, and fallback path
3. Temporarily break `ORDER BY p.id` to confirm the new Phase 3 test would
   actually catch a lock-ordering regression, then restore it

## Performance Considerations

This change is the point of the exercise: concurrent order creations
touching disjoint parts no longer serialize against each other through a
full-table lock. No new performance risk is introduced — the new queries
filter by an indexed primary key (`id IN (...)`), same cardinality class as
the removed full scan for any realistic open-order count at this tool's
scale.

## Migration Notes

None — no schema change; this plan touches only Java/JPQL.

## References

- Research: `context/changes/narrow-reservation-locking/research.md`
- Prior rejected approaches and rule: `context/foundation/lessons.md:45-53`
- Original full-table-lock decision and F2 finding: `context/changes/order-reserves-parts/reviews/impl-review.md:47-55`
- Business logic trigger events: `context/foundation/prd.md:120`
- Current implementation: `src/main/java/pl/regavio/stockahead/orders/ReservationAllocator.java`,
  `src/main/java/pl/regavio/stockahead/parts/PartRepository.java`

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles.

### Phase 1: Narrow the lock and the allocation algorithm

#### Automated

- [x] 1.1 `./mvnw verify` passes with updated signatures and no behavior change

#### Manual

- [x] 1.2 Order creation via `/orders/new` still reserves/shortfalls correctly

### Phase 2: Deadlock-retry defense in depth

#### Automated

- [ ] 2.1 `./mvnw verify` passes with retry wrapper in place, no regression

#### Manual

- [ ] 2.2 Code review confirms retry scope, bound, and fallback path

### Phase 3: Multi-part concurrency coverage

#### Automated

- [ ] 3.1 `./mvnw verify` passes including the new multi-part concurrency test

#### Manual

- [ ] 3.2 Breaking `ORDER BY p.id` temporarily makes the new test fail, confirming it's load-bearing
