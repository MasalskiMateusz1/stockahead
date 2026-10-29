# Company-Scoped Orders Implementation Plan

## Overview

This is roadmap item S-03 of milestone M-2. Orders and their reservations stay within one company. A manager orders production, changes it, cancels it, confirms or rejects a completion report, and assigns it to someone. All of this works only on their own company's orders, and only accounts of the same company can be assignees.

Reservations are allocated by priority, then deadline, then age, over that company's open orders and parts only. Two companies never compete for stock or wait on each other's locks. A user of company B gets 404 for company A's order ids.

This implements FR-028 for orders, the prd-v2 Business Logic Changes (allocation per company) and Success Criteria › Guardrails (stock and reservation guarantees per company).

## Current State Analysis

S-01 (`company-scoped-parts`) and S-02 (`company-scoped-projects`) scoped parts, projects and BOMs. Orders have no company concept yet.

- **Schema.** `orders` and `order_lines` (`V6__create_orders.sql`) have no `company_id`. They use single-column FKs with Postgres default names: `orders_project_id_fkey`, `order_lines_order_id_fkey` and `order_lines_part_id_fkey`. `V13__add_order_assignee.sql` adds `orders.assignee_id` with `orders_assignee_id_fkey`, so an order can reference another company's account. The composite FK targets `projects_id_company_key` (V16) and `parts_id_company_key` (V15) already exist. `accounts` has no `(id, company_id)` key.
- **Allocator.** `ReservationAllocator.reallocateForParts(Set<Long>)` (`orders/ReservationAllocator.java:91-108`) has three steps:
  - It locks exactly the given part ids (`PartRepository.findByIdInForUpdate`, `parts/PartRepository.java:33-35`).
  - It reads their quantities (`findCurrentQuantities`, `:97-98`).
  - It reads the open lines on those parts (`OrderLineRepository.findOpenLinesForParts`, `orders/OrderLineRepository.java:60-62`).

  So allocation already separates by part (frame row 3: WEAK), but none of these takes a company. AGENTS.md requires all three to scope to the current company.
- **Allocator callers:** `OrderController` has 5 (`:159`, `:183`, `:231`, `:387`, `:580`). Outside the package there are `parts/DeliveryController.java:204`, `parts/StockCorrectionController.java:253` and `parts/PartImportController.java:348`, plus the `reallocate(Set)` helper in `ReservationAllocatorTests` (`:208-209`) and 2 direct calls in `ReservationConcurrencyTests` (`:109`, `:161`). `PickingController` doesn't call it.
- **Manager order routes.** None of them is scoped:
  - `GET /orders` lists every company's open orders (`OrderController.java:123`, `OrderRepository.findByStatusWithProject`).
  - `GET /orders/{id}` and `GET /orders/{id}/cancel` load through `OrderDetailModel.load`/`loadCancel` with a bare `findByIdWithDetails` (`OrderDetailModel.java:109`, `:137`).
  - `confirm-completion`, `reject-completion`, `change`, `assign` and `POST cancel` go through `transitionOrder` (`OrderController.java:457-473`), which uses a bare `findPartIdsForOrder(orderId)`, `findByIdForUpdate(orderId)` and `findById(orderId)`.
- **Assignee.** `accountRepository.findAssignable()` (`account/AccountRepository.java:31-35`) is global. It is used by the create form (`OrderController.java:734`), the detail page's reassign picker (`OrderDetailModel.java:96`) and the manager's picking-list filter (`PickingController.java:115`). The assignee lookups in `create` (`:555`) and `assign` (`:270`) are bare `findById`. `AccountRepository.findByIdAndCompanyId` already exists (`:29`).
- **Picking (S-04) reuses order queries.** `PickingController.list` calls the three `findByStatus…WithProject` queries (`:118-133`) and `reservedQuantitiesByOpenOrder` (`:140`). `PickingController.reportCompletion` calls `findPartIdsForOrder(orderId)` (`:240`) and `orderRepository.findById` (`:254`, `:322`), and `PickingDetailModel` calls `findByIdWithDetails` (`:68`).
- **Tests.** 53 raw `INSERT INTO orders` / `INSERT INTO order_lines` statements across 22 test files will break once the columns are `NOT NULL`. Most tests delete `order_lines`/`orders` themselves before `companyFixtures.cleanUp()` (e.g. `orders/OrderAssignIntegrationTests.java:111-112`). `CompanyFixtures.cleanUp()` (`CompanyFixtures.java:162-181`) deletes projects, parts, accounts and companies, but not orders.
- **Empty tables.** The V14 guard (`V14__create_companies.sql:3-13`) requires `orders` and `order_lines` to be empty. Multi-company work stays on its branch, so V17 always runs against empty tables and needs no backfill.

## Desired End State

- `orders` and `order_lines` carry a non-null `company_id`. Composite FKs make these impossible in the database:
  - an order on another company's project, or assigned to another company's account;
  - a line on another company's order or part.
- `GET /orders` lists only the user's own company's open and pending orders. Eight routes answer 404 for another company's order id and change nothing: `GET /orders/{id}`, `GET /orders/{id}/cancel`, and `POST /orders/{id}/confirm-completion`, `/reject-completion`, `/change`, `/assign` and `/cancel`.
- The new-order form, the detail page's reassign picker and the manager's picking-list filter list only the user's own company's assignable accounts. Posting another company's account id to `POST /orders` or `/orders/{id}/assign` gets the same `orders.error.assigneeUnavailable` as a missing account, and nothing is written.
- `reallocateForParts(companyId, partIds)` locks, reads stock of and allocates only that company's parts and open order lines. A foreign part id in `partIds` is neither locked nor read.
- Company A holding its part locks never delays company B's order creation or reallocation.
- The picking list (manager and technician) shows only the user's own company's orders. Its by-id routes stay for S-04.
- `./mvnw verify` is green.

To verify, run `OrderSchemaTests`, `OrdersCompanyIsolationIntegrationTests` and `CrossCompanyAllocationLockTests`, then a manual `spring-boot:test-run` with two companies.

### Key Discoveries:

- S-02's shape is the one to copy:
  - `BomLine.companyId` is a plain `Long` column set by `Project.addBomLine`/`setCompany` (`projects/BomLine.java:31-38`, `projects/Project.java:69-105`).
  - Composite FKs in `V16__company_scoped_projects.sql`.
  - The two-company test class `projects/ProjectsCompanyIsolationIntegrationTests.java`.
- `transitionOrder` already returns 404 for a missing order (`OrderController.java:469-471`). With a scoped `findPartIdsForOrder` and a scoped `findByIdForUpdate`, a foreign id yields an empty part set, then an empty lock lookup, then 404, before any mutation.
- A Postgres `SELECT … FOR UPDATE` only locks (and waits on) rows that pass its `WHERE`. Adding `company_id = :companyId` to the allocator's lock query is enough to keep another company's locked rows from blocking it.
- `StockCorrectionController.set`/`adjust` (`:226`, `:253`) still load the part by bare id (S-05's hole). If its allocator call passed the principal's company, a foreign correction that lowers company A's stock would skip A's reallocation and leave A over-reserved. Today the allocator shrinks A's taken reservations in that case.
- `PartImportController` matches parts through the company-scoped `CatalogNameIndex`. Its `raisedIds` are always the principal's company's parts. `DeliveryController.applyReceipt` already receives the receiver's `companyId` (`DeliveryController.java:158`).
- `OrderController.create`'s overflow guard (`MAX_QUANTITY_UNITS`) and the `DataIntegrityViolationException` → `orders.error.saveFailed` mapping stay as they are. A composite-FK violation the code can't reach would surface there (the same accepted shape as the S-01 impl review F4).

## What We're NOT Doing

- No Hibernate `@TenantId` or `@Filter`. Scoping uses an explicit `companyId` parameter, as in F-01, S-01 and S-02.
- No scoping of the picking by-id routes (`/picking/{id}`, pick, report completion), of `PickingDetailModel`, of `reservedQuantitiesByOpenOrder`, or of `completion_reported_by`. That's S-04. The bare `findByIdWithDetails(id)` and `findPartIdsForOrder(orderId)` stay for those callers until S-04 removes them.
- No scoping of the delivery, stock-correction or picking part lookups (`findByIdInForUpdate` with posted ids, `StockCorrectionController.findById`, `reservedQuantityForPart`). That's S-05/S-04. The bare `PartRepository.findByIdInForUpdate` stays.
- No scoping of the shopping list (`findOpenLinesWithShortage`). That's S-06.
- No composite FK on `orders.cancelled_by` or `orders.completion_reported_by`. Both are set from the authenticated user, so they are same-company by construction. `completion_reported_by` belongs to S-04's picking flow.
- No change to the allocation rule, the lock order, `LockRetry`, or the narrow part-lock strategy (lessons.md: "Narrowing ReservationAllocator's part-row lock is unsafe…"). The lock set only gains a company predicate.
- No UI change, and no new 403 tests beyond the existing wrong-role tests on order routes.

## Implementation Approach

The company flows from `CompanyUser` through the controller as an explicit argument, as in S-01 and S-02. A miss on `(id, companyId)` is treated exactly like a missing id: 404 on order routes and `assigneeUnavailable` for an assignee. `projectUnavailable` for an order's project is already scoped by S-02.

Phase 1 is the mechanical fallout of the `NOT NULL` columns: the migration, the entities, and every test insert and cleanup. All queries stay global and behavior is unchanged. Phase 2 makes the semantic changes:
- the allocator's contract gains a `companyId`, and its four outside callers pass it;
- the manager order routes, the order lists and the assignee are scoped;
- the two-company and cross-company lock tests prove it.

Shared repository methods that S-04 still calls bare get a company-scoped overload next to the old one, and the bare one stays for S-04 to remove.

## Critical Implementation Details

- **Which company the stock correction passes.** `StockCorrectionController` passes the company of the part row it just locked, not the principal's. The reason: the allocator must reconcile whichever company owns that part, or a still-unscoped foreign correction (S-05) would leave that company with more units reserved than in stock. S-05 switches it to `principal.companyId()` once the part lookup is scoped. All other callers pass the principal's company.
- **Composite assignee FK and nulls.** `FOREIGN KEY (assignee_id, company_id)` uses Postgres's default `MATCH SIMPLE`, so an unassigned order (`assignee_id IS NULL`) passes. Do not declare `MATCH FULL`.

## Phase 1: Schema, entities and test fixtures

### Overview

Add `company_id` to `orders` and `order_lines` with composite FKs, set it from the project when an order is created, and make every test seed carry it. All queries stay as they are, and the build is green with unchanged behavior.

### Changes Required:

#### 1. V17 migration

**File**: `src/main/resources/db/migration/V17__company_scoped_orders.sql` (new)

**Intent**: Give orders and their lines a company and make every cross-company reference from an order impossible in the database. This mirrors V15/V16, relies on V14's empty-table guarantee and needs no backfill.

**Contract**:
- `accounts`: add `accounts_id_company_key UNIQUE (id, company_id)`. This is the target of the assignee FK.
- `orders`:
  - Add `company_id BIGINT NOT NULL REFERENCES companies (id) ON DELETE RESTRICT`.
  - Replace `orders_project_id_fkey` with `orders_project_company_fkey (project_id, company_id) → projects (id, company_id) ON DELETE RESTRICT`.
  - Replace `orders_assignee_id_fkey` with `orders_assignee_company_fkey (assignee_id, company_id) → accounts (id, company_id) ON DELETE RESTRICT`.
  - Add `orders_id_company_key UNIQUE (id, company_id)`.
  - Add the index `orders_company_status_idx (company_id, status)` for the list queries. `orders_assignee_idx` stays.
- `order_lines`:
  - Add `company_id BIGINT NOT NULL`.
  - Replace `order_lines_order_id_fkey` with `order_lines_order_company_fkey (order_id, company_id) → orders (id, company_id) ON DELETE CASCADE`.
  - Replace `order_lines_part_id_fkey` with `order_lines_part_company_fkey (part_id, company_id) → parts (id, company_id) ON DELETE RESTRICT`.
- The ON DELETE actions match V6/V13. Header comments follow V16's style.

#### 2. Entities

**Files**: `src/main/java/pl/regavio/stockahead/orders/Order.java`, `src/main/java/pl/regavio/stockahead/orders/OrderLine.java`

**Intent**: An order carries its project's company, and a line carries its order's company. Code that builds an order never has to set the company by hand. This is the same shape as `Project`/`BomLine`.

**Contract**:
- `Order` gets a plain `@Column(name = "company_id", nullable = false) Long companyId` with a public getter.
- `Order.setProject(project)` sets it from `project.getCompany().getId()` and propagates it to lines already added.
- `OrderLine` gets the same plain column, with a public getter and a package-private setter. `Order.addLine` sets it from the order.
- The `project` and `assignee` `@JoinColumn`s stay single-column.

#### 3. Test fixtures

**Files**: `src/test/java/pl/regavio/stockahead/CompanyFixtures.java`, and the 22 test files with raw `INSERT INTO orders` / `INSERT INTO order_lines` (`grep -rln "INSERT INTO orders\|INSERT INTO order_lines" src/test`)

**Intent**: Every seeded order and line belongs to the company of the project and parts the test seeds. Fixture cleanup must not trip over orders left in a fixture company.

**Contract**:
- Each `INSERT INTO orders` / `INSERT INTO order_lines` gains `company_id`, set to the company of its project (for an order) or its order (for a line), usually the test's `CompanyFixtures` company.
- `CompanyFixtures.cleanUp()` first deletes `orders WHERE company_id = ?` for every company it created, before its email-account loop: `assignee_id`, `cancelled_by` and `completion_reported_by` reference accounts with `RESTRICT`. Lines cascade. Update its Javadoc, which says tests delete their orders first.
- No new fixture helper is needed.

#### 4. Schema tests

**File**: `src/test/java/pl/regavio/stockahead/orders/OrderSchemaTests.java` (new), following `projects/ProjectSchemaTests.java`

**Intent**: Prove that the database refuses every cross-company reference an order can make, independently of the application code.

**Contract**: Use raw JDBC inserts with two `CompanyFixtures` companies. Each of these is rejected with `DataIntegrityViolationException`:
- an order of company B on A's project;
- an order of A assigned to B's account;
- a line of A's order on B's part;
- a line whose `company_id` differs from its order's.

An order of A with a null assignee is accepted.

### Success Criteria:

#### Automated Verification:

- `./mvnw verify` passes
- `OrderSchemaTests` passes: all four cross-company inserts are rejected, and the unassigned order is accepted
- `grep -rn "INSERT INTO orders\|INSERT INTO order_lines" src/test | grep -v company_id` returns nothing (multi-line inserts checked by reading the 22 files)

#### Manual Verification:

- With `./mvnw spring-boot:test-run`, a manager creates an order for a project with a BOM. The order shows its reservations, and `orders.company_id` and `order_lines.company_id` equal the manager's company in the DB

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful before proceeding to the next phase.

---

## Phase 2: Scoped allocator, routes and two-company tests

### Overview

Give the allocator a company, scope every manager order route, the order lists and the assignee, and prove with two-company tests that B gets 404 on A's orders and that the companies never compete for stock or locks.

### Changes Required:

#### 1. Repositories

**Files**: `src/main/java/pl/regavio/stockahead/parts/PartRepository.java`, `src/main/java/pl/regavio/stockahead/orders/OrderRepository.java`, `src/main/java/pl/regavio/stockahead/orders/OrderLineRepository.java`, `src/main/java/pl/regavio/stockahead/account/AccountRepository.java`

**Intent**: Every query the allocator and the manager order routes use takes the company. Bare variants that S-04/S-05 callers still use stay, so this slice doesn't scope their routes behind their tests' backs.

**Contract**:
- `PartRepository`:
  - New `findByIdInAndCompanyIdForUpdate(Collection<Long> ids, Long companyId)`: `PESSIMISTIC_WRITE`, `ORDER BY p.id`, with `p.company.id = :companyId` in the `WHERE`.
  - `findCurrentQuantities` gains `companyId`. Its only caller is the allocator.
  - The bare `findByIdInForUpdate` stays for delivery, correction, import and picking.
- `OrderLineRepository`:
  - `findOpenLinesForParts` gains `companyId` (`ol.companyId = :companyId`).
  - New overload `findPartIdsForOrder(Long orderId, Long companyId)`. The bare one stays for `PickingController` (S-04).
- `OrderRepository`:
  - The three `findByStatus…WithProject` queries gain `companyId` (`o.companyId = :companyId`).
  - New overload `findByIdWithDetails(Long id, Long companyId)`. The bare one stays for `PickingDetailModel` (S-04).
  - `findByIdForUpdate` gains `companyId`.
  - New derived `findByIdAndCompanyId`.
- `AccountRepository.findAssignable` gains `companyId` (`a.company.id = :companyId`). The Javadoc says "of company `companyId`".

#### 2. Allocator

**File**: `src/main/java/pl/regavio/stockahead/orders/ReservationAllocator.java`

**Intent**: Allocation reads and locks only one company's stock and orders, as AGENTS.md requires. The algorithm is unchanged.

**Contract**:
- `reallocateForParts(Long companyId, Set<Long> partIds)` uses `findByIdInAndCompanyIdForUpdate`, `findCurrentQuantities(companyId, …)` and `findOpenLinesForParts(companyId, …)`.
- A part id of another company in `partIds` is ignored: not locked, not read, not allocated.
- The class and method Javadoc say so. The `{@link #reallocateForParts(Set)}` references are updated.

#### 3. Allocator callers outside orders

**Files**: `src/main/java/pl/regavio/stockahead/parts/DeliveryController.java`, `src/main/java/pl/regavio/stockahead/parts/PartImportController.java`, `src/main/java/pl/regavio/stockahead/parts/StockCorrectionController.java`, `src/test/java/pl/regavio/stockahead/orders/ReservationAllocatorTests.java`, `src/test/java/pl/regavio/stockahead/orders/ReservationConcurrencyTests.java`

**Intent**: Pass the company each caller already has. Every caller's own part lookups stay as they are (S-05).

**Contract**:
- `DeliveryController.applyReceipt` passes its `companyId` parameter.
- `PartImportController.applyImport` passes its `companyId`.
- `StockCorrectionController` passes the company of the part row it locked (`locked` part's `getCompany().getId()`), with a one-line comment naming S-05 (see Critical Implementation Details).
- `ReservationAllocatorTests`' `reallocate` helper and `ReservationConcurrencyTests`' 2 direct calls pass the seeded company's id.

#### 4. Order controller and detail model

**Files**: `src/main/java/pl/regavio/stockahead/orders/OrderController.java`, `src/main/java/pl/regavio/stockahead/orders/OrderDetailModel.java`

**Intent**: Every manager order route works only on the principal's company's orders and assignees. Another company's order id is a 404 that changes nothing, and another company's account is `assigneeUnavailable`.

**Contract**:
- `list`, `detail`, `confirmCompletion`, `rejectCompletion`, `change`, `assign`, `cancelForm` and `cancel` take `@AuthenticationPrincipal CompanyUser principal`.
- `transitionOrder` and `transitionReportedOrder` take `companyId`. They use the scoped `findPartIdsForOrder`, `findByIdForUpdate` and `findByIdAndCompanyId`, and pass `companyId` to every `reallocateForParts`.
- `create` and `assign` load the assignee through `accountRepository.findByIdAndCompanyId(...).filter(isAssignable)`. `populateFormModel` uses `findAssignable(companyId)`.
- `OrderDetailModel.render(...)`, `loadCancel` and `renderCancel` take `companyId` and use the scoped `findByIdWithDetails` and `findAssignable`. Their Javadoc notes it.
- The class Javadoc of `OrderController` mentions the company scope.

#### 5. Picking list pass-through

**File**: `src/main/java/pl/regavio/stockahead/orders/PickingController.java`

**Intent**: The picking list keeps compiling against the scoped list queries and the assignable filter (decided: scope the shared API now). Its by-id routes stay bare for S-04.

**Contract**: `list` takes `@AuthenticationPrincipal CompanyUser principal` and passes `principal.companyId()` to the three `findByStatus…WithProject` calls and to `findAssignable`. Nothing else in the class changes.

#### 6. Two-company tests

**File**: `src/test/java/pl/regavio/stockahead/orders/OrdersCompanyIsolationIntegrationTests.java` (new), following `projects/ProjectsCompanyIsolationIntegrationTests.java`

**Intent**: Prove the S-03 guardrail on every manager order route, on the assignee reference and on allocation.

**Contract**: Use `@Import({ TestcontainersConfiguration.class, CompanyFixtures.class })` with a real Postgres. Companies A and B each have part "R1" and project "Board X" with the same names. Cases:
- **404 on reads.** B's manager gets 404 on `GET /orders/{A}` and `GET /orders/{A}/cancel`.
- **404 on writes, nothing changed.** B's manager gets 404 on `POST /orders/{A}/confirm-completion`, `/reject-completion`, `/change`, `/assign` and `/cancel`. Use an A order in the state each route accepts: reported for confirm and reject, untaken for change, open for assign and cancel. A's status, priority, required date, assignee, completion report and `order_lines.reserved_quantity` stay unchanged in the DB.
- **Lists.** `GET /orders` for B's manager lists none of A's orders. `GET /picking` for B's manager (with no filter) and for B's technician lists none of A's orders.
- **Assignee pickers.** The `assignableAccounts` model attribute on `GET /orders/new` and on `GET /orders/{B}` holds only B's accounts.
- **Foreign assignee.** Posting A's technician id to `POST /orders` shows `assigneeUnavailable` and creates no order. Posting it to `POST /orders/{B}/assign` shows `assigneeUnavailable` and leaves B's assignee unchanged.
- **Allocation.** A's "R1" and B's "R1" each have stock 5. An order for 5 in each company reserves 5 in both, so they don't compete. Cancelling B's order leaves A's reservation at 5.

#### 7. Cross-company lock test

**File**: `src/test/java/pl/regavio/stockahead/orders/CrossCompanyAllocationLockTests.java` (new), using the latch and executor pattern of `ReservationConcurrencyTests`

**Intent**: Turn "companies never wait on each other's locks" into an executable check that fails if a future change re-globalizes a lock.

**Contract**: One transaction locks A's part rows (`findByIdInForUpdate`) and holds them until released. While it holds them, two things must complete within a bounded wait (e.g. `future.get(5, SECONDS)`), not block on A's lock:
- `POST /orders` by B's manager for B's project;
- `reallocateForParts(B, {A's part id, B's part id})` in its own transaction.

Afterwards, A's reservations are unchanged and B's order is fully reserved. The `POST /orders` half already passes before this change (B's create locks only B's parts), so it guards against regressions only.

A separate case proves a foreign part id is not read: seed A's order on "R1" short (raise A's "R1" stock with raw SQL and don't reallocate), call `reallocateForParts(B, {A's R1, B's R1})`, and assert A's line keeps its short `reserved_quantity`. An unscoped allocator would top it up.

#### 8. Docs

**File**: `context/foundation/roadmap.md`

**Intent**: Leave accurate hand-offs for S-04/S-05 and settle the assignee ownership the roadmap contradicted.

**Contract**:
- **S-04:** the Outcome no longer claims the assignee. S-03 owns it. The Risk names the bare lookups left for it:
  - `PickingDetailModel`'s `findByIdWithDetails(id)`;
  - `PickingController`'s `findPartIdsForOrder(orderId)`, `orderRepository.findById` and the part lock in `pick`;
  - `reservedQuantitiesByOpenOrder`.

  It also notes that the picking list itself is already company-scoped.
- **S-05:** the Risk says `StockCorrectionController` passes the locked part's company to the allocator until its lookup is scoped, and must then switch to the principal's. It also says that until S-05, a delivery with a foreign part id raises that part's stock without reallocating it, so the owner's short open orders on that part (taken or not) miss the units until the next event on that part. That is never over-reservation.
- **S-11:** the Prerequisites note says the assignee gate is met.

### Success Criteria:

#### Automated Verification:

- `./mvnw verify` passes
- `OrdersCompanyIsolationIntegrationTests` passes: B gets 404 on all eight `/orders/{id}…` routes with A's orders unchanged, the lists and assignee pickers show only B's own company, a foreign assignee is `assigneeUnavailable`, and the companies' same-named parts don't compete
- `CrossCompanyAllocationLockTests` passes: B's order creation and B's reallocation complete while A's part rows are locked, A's reservations are unchanged, and a short A line passed to B's reallocation stays short
- All existing concurrency tests (`ReservationConcurrencyTests`, `OrderCancelConcurrencyTests`, `OrderCompletionConcurrencyTests`, `PickingConcurrencyTests`, `DeliveryConcurrencyTests`, `StockCorrectionConcurrencyTests`, `PartImportConcurrencyTests`) pass without changes other than the Phase 1 fixture columns and the allocator's `companyId` argument
- `grep -nE "findPartIdsForOrder\(orderId\)|findByIdWithDetails\(orderId\)|findById\((orderId|parsedAssigneeId)\)|findAssignable\(\)" src/main/java/pl/regavio/stockahead/orders/OrderController.java src/main/java/pl/regavio/stockahead/orders/OrderDetailModel.java` returns nothing

#### Manual Verification:

- With `spring-boot:test-run`, companies A and B each create part "R1" (stock 5), project "Board X" and an order for 5 units. Both orders are fully reserved, and each manager's `/orders` list shows only their own order
- Pasting A's order id into `/orders/{id}` and `/orders/{id}/cancel` as B shows the 404 page
- B's new-order form and order detail page offer only B's accounts as assignees

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful before proceeding.

---

## Testing Strategy

### Unit Tests:

- No new pure unit tests. The behavior lives in SQL constraints, row locks and the security principal, and AGENTS.md requires real Postgres for these invariants. `ReservationAllocatorTests` keeps its algorithmic coverage with the added `companyId` argument.

### Integration Tests:

- Phase 1: `OrderSchemaTests`, where the composite FKs reject cross-company project, assignee, order and part references.
- Phase 2: `OrdersCompanyIsolationIntegrationTests` (Phase 2 § 6) and `CrossCompanyAllocationLockTests` (Phase 2 § 7).
- Every existing orders, picking, parts, projects and purchasing test keeps passing on single-company data.

### Manual Testing Steps:

1. `./mvnw spring-boot:test-run`, set up company A, log in as A's manager, create part "R1" (stock 5), project "Board X" with a BOM line of 1 × R1, and an order for 5 units.
2. Create company B, its manager and a technician in the DB. Log in as B's manager and create the same part, project and order. Confirm both orders are fully reserved.
3. As B, open `/orders/<A's id>` and `/orders/<A's id>/cancel`, and confirm the 404 page.
4. As B, open `/orders/new` and B's order detail page, and confirm only B's accounts are offered as assignees. Open `/picking` and confirm only B's order is listed.

## Migration Notes

V17 assumes `orders` and `order_lines` are empty. That holds because V14's guard required an empty database, and V14–V17 ship together from the multi-company branch. If V17 meets rows, `ADD COLUMN … NOT NULL` without a default fails and Flyway rolls back. The V14 wipe procedure in `context/foundation/deployment.md` applies.

## References

- Roadmap: `context/foundation/roadmap.md` § S-03 (and S-04, S-05, S-11 for the hand-offs)
- PRD: `context/foundation/prd-v2.md` FR-028, Business Logic Changes, Success Criteria › Guardrails
- Frame: `context/changes/multi-company-support/frame.md` (row 3: allocation already separates by part)
- S-02 plan: `context/archive/2026-10-08-company-scoped-projects/plan.md`
- Pattern: `V16__company_scoped_projects.sql`, `projects/BomLine.java:31-38`, `projects/ProjectsCompanyIsolationIntegrationTests.java`, `orders/ReservationConcurrencyTests.java:93-160`
- Lessons: `context/foundation/lessons.md` (narrowing the allocator lock; DB constraint violations at write boundaries; wrong-role tests)

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles. See `references/progress-format.md`.

### Phase 1: Schema, entities and test fixtures

#### Automated

- [x] 1.1 `./mvnw verify` passes
- [x] 1.2 `OrderSchemaTests` passes: all four cross-company inserts are rejected, and the unassigned order is accepted
- [x] 1.3 `grep -rn "INSERT INTO orders\|INSERT INTO order_lines" src/test | grep -v company_id` returns nothing (multi-line inserts checked by reading the 22 files)

#### Manual

- [x] 1.4 With `./mvnw spring-boot:test-run`, a manager creates an order for a project with a BOM. The order shows its reservations, and `orders.company_id` and `order_lines.company_id` equal the manager's company in the DB

### Phase 2: Scoped allocator, routes and two-company tests

#### Automated

- [ ] 2.1 `./mvnw verify` passes
- [ ] 2.2 `OrdersCompanyIsolationIntegrationTests` passes: B gets 404 on all eight `/orders/{id}…` routes with A's orders unchanged, the lists and assignee pickers show only B's own company, a foreign assignee is `assigneeUnavailable`, and the companies' same-named parts don't compete
- [ ] 2.3 `CrossCompanyAllocationLockTests` passes: B's order creation and B's reallocation complete while A's part rows are locked, A's reservations are unchanged, and a short A line passed to B's reallocation stays short
- [ ] 2.4 All existing concurrency tests pass without changes other than the Phase 1 fixture columns and the allocator's `companyId` argument
- [ ] 2.5 The bare-lookup grep over `OrderController.java` and `OrderDetailModel.java` returns nothing

#### Manual

- [ ] 2.6 With `spring-boot:test-run`, companies A and B each create part "R1" (stock 5), project "Board X" and an order for 5 units. Both orders are fully reserved, and each manager's `/orders` list shows only their own order
- [ ] 2.7 Pasting A's order id into `/orders/{id}` and `/orders/{id}/cancel` as B shows the 404 page
- [ ] 2.8 B's new-order form and order detail page offer only B's accounts as assignees
