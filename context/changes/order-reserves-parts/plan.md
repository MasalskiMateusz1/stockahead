# Zlecenie produkcji rezerwuje części — Implementation Plan

## Overview

A manager orders production of N units of a project; the system reserves available parts against that project's BOM following the priority → deadline → age allocation rule, and shows reserved/missing quantities per BOM line. This is the roadmap's north-star slice (S-04): the first piece that runs the allocation rule from `context/foundation/prd.md` §Business Logic end-to-end.

## Current State Analysis

- `Part` (`src/main/java/pl/regavio/stockahead/parts/Part.java`), `Project`/`BomLine` (`src/main/java/pl/regavio/stockahead/projects/`) and `Account` entities exist; no `Order` concept exists anywhere in the codebase.
- `parts-list.html:44-46` already renders "Zarezerwowane"/"Dostępne" columns, but they're hardcoded (`0` and full `part.quantity`) — S-02 deliberately deferred wiring them until this slice.
- No service layer anywhere: every controller (`PartController`, `ProjectController`, `ProjectBomController`) uses its `Repository` directly plus a `TransactionTemplate` for writes, `MessageSource` for i18n error messages, and `ResponseStatusException` for 404s. `ProjectDetailModel` is the one precedent for extracting shared, reusable logic into a plain `@Component` (not a `@Controller`).
- **No `PESSIMISTIC_WRITE`/row-locking exists anywhere in the codebase.** AGENTS.md's hard rule ("lock the part row … before changing stock or reservations") and the roadmap's `F-01` foundation were meant to establish this pattern, but `F-01` is still `status: ready` (not executed). This plan introduces the pattern itself, since it's the first slice that needs the concurrency guarantee.
- `V5__create_projects.sql` is the latest migration; the next one is `V6`.
- Existing DB `CHECK` constraints (e.g. `parts.quantity >= 0`, `bom_lines.quantity_per_unit > 0`) are used generously alongside app-level validation — not just one or the other.
- Integration tests (`ProjectBomIntegrationTests`, `PartsCatalogIntegrationTests`) run against real Postgres via Testcontainers, deliberately not `@Transactional` so DB constraints actually fire, seed data with `JdbcTemplate`, and always test the wrong-role 403 case per `context/foundation/lessons.md`.

### Key Discoveries:

- `PartController.list()` (`src/main/java/pl/regavio/stockahead/parts/PartController.java:50-67`) is the only place that renders the parts catalog; it will need a reserved-quantity lookup added.
- `ProjectDetailModel.render()` pattern (own read-only `TransactionTemplate`, converts entities to plain records before handing them to the view) is the model to follow for the order list/detail screens.
- `ProjectBomController`'s `addLine` (`src/main/java/pl/regavio/stockahead/projects/ProjectBomController.java:52-107`) shows the established shape for a write endpoint: validate raw params → `transactionTemplate.execute` → catch `DataIntegrityViolationException` → re-render with a localized error.
- Full recompute (see Implementation Approach) makes priority preemption fall out for free: re-running the whole allocation from scratch on every event always gives higher-priority orders first claim on the parts pool, with no explicit "steal reservation from order X" logic needed.

## Desired End State

A manager can open a new-order form, pick an active project, a quantity, a priority, and a required date; on submit, the system reserves available stock against the project's BOM following priority → deadline → age, and redirects to an order detail page showing, per BOM line, required/reserved/missing quantities. An order list page shows all open orders in allocation order. The parts catalog's "Zarezerwowane"/"Dostępne" columns show real numbers reflecting all open orders. Two managers creating orders concurrently against scarce stock can never together reserve more than the part's physical quantity.

Verification: `./mvnw verify` passes, including a concurrency test that fires two order-creation requests at once against a part with limited stock and asserts total reservations never exceed physical stock; and a test reproducing US-01's own acceptance example (BOM needs 10 resistors, stock is 6, order 1 unit → reserved 6, missing 4).

## What We're NOT Doing

- **"Taken order" protection and already-picked-part exclusions** — no picking exists yet (that's S-07); this slice's allocation only ever deals with not-yet-picked orders, so there's nothing to exclude.
- **Order cancellation or priority/deadline changes** — S-09's scope.
- **Order completion / confirmation** — S-08's scope.
- **Recomputing reservations when a project's BOM is edited** — `ProjectBomController` is not touched by this plan. Editing a BOM while a project has open orders does not recompute those orders' reservations; each order's required quantities are frozen (snapshotted) at creation time regardless. This is a known, intentional gap (roadmap's Open Question #3), not a silent bug.
- **Shopping list / CSV export** — S-05's scope; this slice only produces the reserved/missing numbers S-05 will later aggregate.
- **Picking list with part locations** — S-07's scope; order detail here shows required/reserved/missing only, not locations.
- **Incremental/partial recompute** — every event fully recomputes every open order's reservations from scratch (see Implementation Approach).

## Implementation Approach

Introduce `Order`/`OrderLine` entities and a single reusable allocation component, `ReservationAllocator`, that fully recomputes every open order's reservations on every triggering event (here: order creation). It locks every `Part` row up front (`SELECT … FOR UPDATE ORDER BY id`), loads all `OPEN` orders with their lines, sorts them by priority (desc) → required date (asc) → created-at/id (asc), and greedily assigns each part's physical stock to lines in that order. Because the whole allocation is rebuilt from scratch inside one lock, priority preemption (a new high-priority order taking stock from an existing low-priority one) requires no special-case logic — it's just what a fresh top-to-bottom assignment produces. This full-recompute approach is deliberately chosen at this project's small/low-QPS scale, over an incremental one, for correctness simplicity.

`Order` gets a `status` enum (`OPEN`/`CANCELLED`/`COMPLETED`) now, even though this slice only ever writes `OPEN`, so S-08/S-09 don't need their own migration just to add it, and every allocation query can filter `WHERE status = 'OPEN'` from day one.

Each `OrderLine.required_quantity` is a snapshot of `bomLine.quantityPerUnit * order.quantityUnits` taken at order-creation time — not a live join to the BOM — so an order's material requirement is a fixed contract once created (see "What We're NOT Doing").

The allocation engine is a `@Component` (following `ProjectDetailModel`'s precedent), not a full "service layer" — the codebase doesn't have one yet and this plan doesn't introduce one wholesale, but this specific algorithm is reused by five future slices (S-08 through S-12) and is complex enough to warrant one shared, unit-testable home now.

## Phase 1: Schema & entities

### Overview

Add the `orders`/`order_lines` tables and their JPA entities/repositories, following the existing `Part`/`Project` style. No allocation logic yet — this phase is verifiable purely on schema/entity correctness.

### Changes Required:

#### 1. Migration

**File**: `src/main/resources/db/migration/V6__create_orders.sql`

**Intent**: Create the `orders` and `order_lines` tables with the invariants AGENTS.md and the PRD require encoded as DB `CHECK` constraints, not just app validation.

**Contract**: `orders(id, project_id FK→projects ON DELETE RESTRICT, quantity_units INT CHECK > 0, priority VARCHAR CHECK IN ('LOW','NORMAL','HIGH'), required_date DATE NOT NULL, status VARCHAR CHECK IN ('OPEN','CANCELLED','COMPLETED') DEFAULT 'OPEN', created_at TIMESTAMPTZ DEFAULT now())`. `order_lines(id, order_id FK→orders ON DELETE CASCADE, part_id FK→parts ON DELETE RESTRICT, required_quantity INT CHECK > 0, reserved_quantity INT NOT NULL DEFAULT 0 CHECK (reserved_quantity >= 0 AND reserved_quantity <= required_quantity), UNIQUE(order_id, part_id))`. The `reserved_quantity <= required_quantity` check is the DB-level mirror of "never reserve more than what's needed."

#### 2. Entities and repositories

**File**: `src/main/java/pl/regavio/stockahead/orders/Order.java`, `OrderLine.java`, `Priority.java` (enum: `LOW, NORMAL, HIGH`), `OrderStatus.java` (enum: `OPEN, CANCELLED, COMPLETED`), `OrderRepository.java`, `OrderLineRepository.java`

**Intent**: Mirror `Part`/`Project`'s entity style (plain JPA entities, `@Enumerated(EnumType.STRING)` for enums, `IDENTITY` generation, bidirectional `addLine`/`Project`-style helper on `Order` for its lines).

**Contract**: `Order` holds `project` (`@ManyToOne`), `quantityUnits`, `priority`, `requiredDate` (`LocalDate`), `status`, `createdAt`, and a `List<OrderLine> lines` (`@OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)`). `OrderLine` holds `order`, `part` (`@ManyToOne`), `requiredQuantity`, `reservedQuantity`. Add `PartRepository.findAllForUpdate()`: `@Lock(LockModeType.PESSIMISTIC_WRITE) @Query("SELECT p FROM Part p ORDER BY p.id") List<Part> findAllForUpdate();` and `OrderRepository.findByStatus(OrderStatus status)`.

### Success Criteria:

#### Automated Verification:

- `./mvnw verify` passes (migration applies cleanly against Testcontainers Postgres, `ddl-auto=validate` matches the new entities)

#### Manual Verification:

- None — pure schema/entity phase, fully covered by the app booting successfully against the new migration.

---

## Phase 2: Allocation engine

### Overview

Implement `ReservationAllocator`, the reusable full-recompute allocation component, with tests proving the algorithm and the concurrency invariant — the "invariant test pattern" AGENTS.md/`F-01` call for, which doesn't exist anywhere in this codebase yet.

### Changes Required:

#### 1. Allocation component

**File**: `src/main/java/pl/regavio/stockahead/orders/ReservationAllocator.java`

**Intent**: One method, callable inside an existing transaction, that recomputes every `OPEN` order's `OrderLine.reservedQuantity` from scratch: lock every part, sort open orders by allocation order, greedily assign stock, persist.

**Contract**: `@Component class ReservationAllocator { void reallocateAll() }`. Locks all parts via `partRepository.findAllForUpdate()`. Loads open orders via `orderRepository.findByStatus(OrderStatus.OPEN)`, sorted with `Comparator.comparing(Order::getPriority, Comparator.reverseOrder()).thenComparing(Order::getRequiredDate).thenComparing(Order::getCreatedAt).thenComparing(Order::getId)` — `Priority` must be declared `LOW, NORMAL, HIGH` in that order so `reverseOrder()` on the enum's natural ordinal ordering puts `HIGH` first. Tracks a running `Map<Long, Integer>` of remaining stock per part (seeded from each part's current `quantity`, since no picking exists yet to subtract), and for each order line in sorted order sets `reservedQuantity = min(remaining, requiredQuantity)`, decrementing `remaining`. Must be invoked by the caller inside the same `TransactionTemplate` block that creates/modifies the triggering row, so the whole operation (part locks + order writes) is one atomic transaction.

#### 2. Algorithm tests

**File**: `src/test/java/pl/regavio/stockahead/orders/ReservationAllocatorTests.java`

**Intent**: Prove the allocation rule against `context/foundation/prd.md` §Business Logic and US-01's own acceptance example, without going through HTTP.

**Contract**: Seed orders/parts directly via `JdbcTemplate` (matching `ProjectBomIntegrationTests`'s fixture style), call `reallocateAll()`, assert `reservedQuantity` per line. Cover: US-01's literal example (BOM needs 10, stock 6, order 1 unit → reserved 6); two orders on the same part where the higher-priority one gets served first even if created later; equal priority, earlier `requiredDate` wins; equal priority and date, older order (earlier `createdAt`/lower id) wins; a lower-priority order's existing reservation shrinks when a new higher-priority order is created and reallocation reruns (preemption via full recompute).

#### 3. Concurrency invariant test

**File**: `src/test/java/pl/regavio/stockahead/orders/ReservationConcurrencyTests.java`

**Intent**: Prove the no-double-reservation / never-negative-stock guarantee under real concurrent writes, against real Postgres — the pattern `F-01` was meant to establish but hasn't yet.

**Contract**: Against a part with a small fixed stock, fire two threads (`ExecutorService` + a `CountDownLatch` to force overlap) each creating an order that alone would exceed the part's stock, both going through the full HTTP order-creation endpoint (not calling the allocator directly) so the test also exercises the controller's transaction boundary. Assert after both complete: `SUM(reserved_quantity)` for that part across both orders' lines never exceeds the part's `quantity`, and the part's `quantity` row itself is unchanged (this slice never decrements physical stock — only `S-07`'s picking will).

### Success Criteria:

#### Automated Verification:

- `./mvnw verify` passes, including `ReservationAllocatorTests` and `ReservationConcurrencyTests`

#### Manual Verification:

- None — algorithm correctness is fully covered by automated tests at this phase.

---

## Phase 3: Order creation

### Overview

`OrderController`'s creation flow: form, validation, snapshotting BOM quantities into `OrderLine`s, and invoking `ReservationAllocator` in the same transaction.

### Changes Required:

#### 1. Controller

**File**: `src/main/java/pl/regavio/stockahead/orders/OrderController.java`

**Intent**: `GET /orders/new` (manager-only form) and `POST /orders` (manager-only create), following `ProjectBomController`'s validate-then-`transactionTemplate.execute`-then-catch-`DataIntegrityViolationException` shape.

**Contract**: Form fields: `projectId` (dropdown of active projects only, reusing `projectRepository.list(false)` the way `ProjectDetailModel` reuses `partRepository.search("", false)`), `quantityUnits` (int, ≥ 1), `priority` (`LOW`/`NORMAL`/`HIGH`, radio/select with Polish labels Niski/Normalny/Wysoki from `messages.properties`), `requiredDate` (HTML `date` input, `min` attribute = today; server re-validates not-in-the-past). On submit: reject if `projectId` doesn't resolve to an *active* project (mirrors `ProjectBomController.addLine`'s `part == null` pattern for inactive parts). Inside one `transactionTemplate.execute`: create the `Order`, copy every `BomLine` of the project into an `OrderLine` with `requiredQuantity = bomLine.quantityPerUnit * quantityUnits` and `reservedQuantity = 0`, save, then call `reservationAllocator.reallocateAll()`, then redirect to `/orders/{id}`. An active project with zero `BomLine`s is a valid order — it is created with zero `OrderLine`s (nothing to reserve), not rejected.

#### 2. Templates & messages

**File**: `src/main/resources/templates/orders-new.html`, `src/main/resources/messages.properties`

**Intent**: Match `parts-new.html`/`projects-new.html`'s form style (labeled inputs, `error` model attribute rendered at top, submitted values re-populated on error).

**Contract**: New message keys: `orders.error.quantityNotInteger`, `orders.error.quantityNotPositive`, `orders.error.projectUnavailable`, `orders.error.dateRequired`, `orders.error.datePast`, plus `orders.priority.low`/`normal`/`high` for the select labels.

### Success Criteria:

#### Automated Verification:

- `./mvnw verify` passes
- Integration test: happy-path order creation reserves stock correctly and redirects to the new order's detail page
- Integration test: invalid quantity (`0`, negative, non-integer), missing/past `requiredDate`, and an inactive/nonexistent `projectId` are all rejected with the correct localized error and no row written
- Integration test: technician gets 403 on both `GET /orders/new` and `POST /orders`
- Integration test: two concurrent `POST /orders` requests against a part with stock insufficient for both never together reserve more than the part's quantity — the HTTP-level counterpart to `ReservationConcurrencyTests` (Phase 2), which calls `reallocateAll()` directly and bypasses the controller's own transaction boundary. Mirror `ReservationConcurrencyTests`'s `ExecutorService`/`CountDownLatch` pattern, but drive it through `MockMvc` against `POST /orders` with two logged-in manager sessions. (per `context/foundation/lessons.md`'s wrong-role rule)

#### Manual Verification:

- As a manager, create an order for a real project and confirm the reservation matches the BOM and current stock

---

## Phase 4: Order list & detail

### Overview

`GET /orders` and `GET /orders/{id}`, manager-only (per the PRD's Access Control table, "Planowanie produkcji" — which viewing reservations/plans falls under — is manager-only; the technician-facing order view is S-07's separate picking list).

### Changes Required:

#### 1. Controller & view model

**File**: `src/main/java/pl/regavio/stockahead/orders/OrderController.java` (add routes), `OrderDetailModel.java`

**Intent**: `GET /orders` lists all `OPEN` orders in allocation order (same sort as `ReservationAllocator`); `GET /orders/{id}` shows one order's lines with required/reserved/missing (`missing = requiredQuantity - reservedQuantity`). Follow `ProjectDetailModel`'s pattern: a read-only `TransactionTemplate`, entities converted to plain records before reaching the view.

**Contract**: `OrderDetailModel.render(Model, Long orderId)` populates `order` (project name, quantityUnits, priority, requiredDate) and `lines` (partName, requiredQuantity, reservedQuantity, missingQuantity) records; unknown order id is 404. `ReservationAllocator.ALLOCATION_ORDER` is currently `private` — widen it to package-visible (or add a package-private `static Comparator<Order> allocationOrder()` accessor) so `GET /orders` sorts `orderRepository.findByStatus(OrderStatus.OPEN)` in memory with the exact same `Comparator`, rather than re-deriving the priority→date→createdAt→id rule as a second, hand-synced implementation (e.g. a JPQL `ORDER BY`).

#### 2. Templates & nav

**File**: `src/main/resources/templates/orders-list.html`, `orders-detail.html`, `dashboard.html`

**Intent**: Match `projects-list.html`/`project-detail.html`'s table/detail structure. Add a "Zlecenia" link next to "Projekty" in `dashboard.html`, manager-only like the existing "Konta techników" link.

**Contract**: List columns: project, quantity, priority, required date, link to detail. Detail shows the per-line table (part, required, reserved, missing).

### Success Criteria:

#### Automated Verification:

- `./mvnw verify` passes
- Integration test reproducing US-01's full acceptance example end-to-end through HTTP: create the order, then `GET /orders/{id}` shows reserved=6, missing=4 for the resistor line
- Integration test: technician gets 403 on both `GET /orders` and `GET /orders/{id}`
- Integration test: unknown order id on `GET /orders/{id}` is 404

#### Manual Verification:

- As a manager, open the order list and detail pages and confirm the numbers match what was just reserved

---

## Phase 5: Parts catalog wiring

### Overview

Replace `parts-list.html`'s hardcoded reserved/available columns with real numbers, completing FR-005 as the roadmap's `S-02` baseline note anticipated.

### Changes Required:

#### 1. Aggregate query

**File**: `src/main/java/pl/regavio/stockahead/orders/OrderLineRepository.java`

**Intent**: Sum reserved quantity per part across all `OPEN` orders' lines, for the parts catalog to consume.

**Contract**: `@Query("SELECT ol.part.id, SUM(ol.reservedQuantity) FROM OrderLine ol WHERE ol.order.status = pl.regavio.stockahead.orders.OrderStatus.OPEN GROUP BY ol.part.id") List<Object[]> reservedQuantitiesByPart();`

#### 2. Controller & template

**File**: `src/main/java/pl/regavio/stockahead/parts/PartController.java`, `src/main/resources/templates/parts-list.html`

**Intent**: `PartController.list()` builds a `Map<Long, Integer>` from `reservedQuantitiesByPart()` and passes a per-part `reserved`/`available` (= `quantity - reserved`) alongside each part to the view, replacing the hardcoded `0`/`part.quantity` cells.

**Contract**: Since `Part` shouldn't gain a derived JPA field for this, wrap each row in a small view record (e.g. `PartRow(Part part, int reserved, int available)`) the way `ProjectDetailModel` wraps entities into records, rather than adding ad-hoc model attributes per column.

### Success Criteria:

#### Automated Verification:

- `./mvnw verify` passes
- Extend `PartsCatalogIntegrationTests`: after an order reserves stock, `GET /parts` shows the correct non-zero "Zarezerwowane" and reduced "Dostępne" for that part

#### Manual Verification:

- As any authenticated user, browse `/parts` after creating an order and confirm the reserved/available numbers reflect it

---

## Testing Strategy

### Unit Tests:

- `ReservationAllocator` sort/assignment logic (Phase 2) — priority, deadline, age tie-breaks, preemption via recompute.

### Integration Tests:

- Full HTTP flow: create order → view detail → view parts catalog, matching US-01's acceptance example throughout.
- Concurrency: two simultaneous order-creation requests against scarce stock never over-reserve (Phase 2).
- Role gating: technician 403 on every manager-only order route (Phase 3, 4).
- Edge cases: inactive project rejected, past/missing required date rejected, unknown order id 404.

### Manual Testing Steps:

1. As manager, create a project's order for more units than stock allows; confirm the detail page shows the correct missing quantities.
2. Create a second, higher-priority order competing for the same parts; confirm the first order's detail page now shows reduced reservations after a page refresh.
3. Browse `/parts` and confirm reserved/available reflect both orders combined.

## References

- PRD: `context/foundation/prd.md` §Business Logic, §User Stories (US-01), FR-010, FR-005
- Roadmap slice: `context/foundation/roadmap.md` S-04 (`order-reserves-parts`)
- AGENTS.md hard rules: stock/reservation locking, priority allocation order
- Pattern precedent: `src/main/java/pl/regavio/stockahead/projects/ProjectDetailModel.java`, `src/main/java/pl/regavio/stockahead/projects/ProjectBomController.java`

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles. See `references/progress-format.md`.

### Phase 1: Schema & entities

#### Automated

- [x] 1.1 `./mvnw verify` passes with the new migration and entities — 95bbf27

### Phase 2: Allocation engine

#### Automated

- [x] 2.1 `./mvnw verify` passes including `ReservationAllocatorTests` and `ReservationConcurrencyTests` — 579a577

### Phase 3: Order creation

#### Automated

- [x] 3.1 `./mvnw verify` passes — 733ddfe
- [x] 3.2 Integration test: happy-path order creation reserves stock and redirects correctly — 733ddfe
- [x] 3.3 Integration test: invalid quantity/date/project rejected with correct error, no row written — 733ddfe
- [x] 3.4 Integration test: technician gets 403 on order-creation routes — 733ddfe
- [x] 3.6 Integration test: two concurrent `POST /orders` against scarce stock never together over-reserve (HTTP-level counterpart to Phase 2's `ReservationConcurrencyTests`) — 733ddfe

#### Manual

- [x] 3.5 As a manager, create a real order and confirm the reservation matches BOM and stock — 733ddfe

### Phase 4: Order list & detail

#### Automated

- [x] 4.1 `./mvnw verify` passes
- [x] 4.2 Integration test: US-01 acceptance example end-to-end (reserved=6, missing=4)
- [x] 4.3 Integration test: technician gets 403 on order list/detail routes
- [x] 4.4 Integration test: unknown order id is 404

#### Manual

- [x] 4.5 As a manager, open order list and detail pages and confirm numbers match

### Phase 5: Parts catalog wiring

#### Automated

- [ ] 5.1 `./mvnw verify` passes
- [ ] 5.2 Extended `PartsCatalogIntegrationTests` shows correct reserved/available after a reservation

#### Manual

- [ ] 5.3 As any authenticated user, confirm `/parts` reserved/available reflect a real order
