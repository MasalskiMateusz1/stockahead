# Stock per location — implementation plan

**State:** `awaiting_persistence`  
**Change:** `stock`  
**Deliverables:** `context/changes/stock/plan.md` and `context/changes/stock/plan-brief.md`

## Plan brief

### What & why

Track physical stock at each location so staff can see where units are, pick from a named shelf, and record where received or returned units go. Reservations and shopping shortages remain pooled by part.

### Starting point

`parts.quantity` holds the physical total; `part_locations` holds labels without balances ([research](D:/10xdevs/context/changes/stock/research.md)). Delivery, import, correction, picking, and cancellation change the part total. The current PRDs explicitly exclude stock per location.

### Desired end state

Each location shows a nonnegative balance, and the part total equals the sum of its locations. Every stock change identifies a source or destination location. Company isolation, reservation priority, protected taken orders, and the shopping list retain their current behavior.

### Key decisions

| Decision | Choice | Reason | Source |
| --- | --- | --- | --- |
| Reservations | Pooled by part | A reservation does not promise a shelf; existing allocation remains valid. | Plan |
| Picking | One source shelf per submission | Each decrement has an exact source; repeated partial picks handle multiple shelves. | Plan |
| Existing stock | Reset totals and live reservations to zero | Existing shelf distribution is unknowable, and production stock can be discarded. | Plan |
| Existing records | Keep catalog, locations, picked counts, and order history | Only current physical stock is discarded. | Plan |
| Positive inflows | Require a destination location | New stock must have a known physical position. | Plan |
| Correction | Set or adjust one shelf | Inventory counts correspond to a physical location. | Plan |
| Cancellation returns | Choose a destination shelf per returned part | Staff record where units are placed; original-pick provenance is unnecessary. | Plan |
| Transfers | Atomic movement between shelves of one part | Physical relocation must leave the part total and reservations unchanged. | Plan |
| Shelf edits | Preserve balance on rename; remove only when empty | Editing a label must never discard stock. | Plan |

### Scope and approach

Add a checked quantity to `part_locations` and keep `parts.quantity` as the synchronized aggregate used by the existing allocator. All writers lock the company-owned part before changing either value. Update the PRDs in place to lift the stock-per-location exclusion while retaining one warehouse per company. Keep the existing CSV columns.

This change excludes shelf-specific reservations, cross-company transfers, an Unassigned fallback, full stock-movement history, and other PRD Non-Goals.

### Phases at a glance

| Phase | Delivers | Main risk |
| --- | --- | --- |
| 1. Schema and stock contract | Migration, reset, shared stock invariant, updated product boundary | Aggregate and shelf balances drifting |
| 2. Stock entering and moving | Creation, delivery, import, correction, transfer, shelf edits | Losing row-to-shelf quantities |
| 3. Stock leaving and returning | Shelf-selected picks and cancellation returns | Races or stale form submissions |
| 4. Views and verification | Shelf balances in UI and complete regression coverage | Missed company or role boundary |

**Prerequisites:** PostgreSQL and Docker for integration tests; a pre-deployment backup if the current stock values are needed outside this application.  
**Estimated effort:** Four implementation phases across several sessions.

### Risks and success

The reset is irreversible without a backup. Part-level locking serializes writers to one part, matching the existing low-volume product target. Success means staff can receive, move, count, pick, and return units at named shelves; every shelf and part total stays nonnegative and consistent; reservations and shortages remain correct within each company.

---

## Full implementation plan

### Overview

Replace label-only locations with location balances while preserving part-wide production reservations. Implement all stock paths before deploying the feature.

### Current state analysis

`V3` defines nonnegative `parts.quantity` and label-only `part_locations`; `V15` adds their company boundary. The allocator locks company-owned part rows and reads the part total as a scalar ([PartRepository.java](D:/10xdevs/src/main/java/pl/regavio/stockahead/parts/PartRepository.java), [ReservationAllocator.java](D:/10xdevs/src/main/java/pl/regavio/stockahead/orders/ReservationAllocator.java)). Delivery and CSV import currently merge quantities by part and lose the row-to-shelf association. Picks and returns record no shelf.

### Desired end state

For each part, `parts.quantity = SUM(part_locations.quantity)`. Every location balance and part total is nonnegative. A stock mutation locks the company-owned part, validates the affected location after that lock, updates shelf and aggregate balances atomically, then invokes part-wide reallocation when the total changes. A transfer leaves the total unchanged.

### Key discoveries

- The migration sequence currently ends at `V21`; use a new Flyway migration.
- The current edit form reconciles locations by text and can remove rows. It needs stable row identity to distinguish a rename from removal.
- The import preview and confirmation currently compare part totals. A shelf transfer can change the relevant shelf while leaving that total unchanged, so the confirmation snapshot must include shelf balances.
- `OrderLine.reservedQuantity` is a live, unpicked counter. A repeated pick decrements it once; never subtract `pickedQuantity` from it again.
- Preserve advisory shelf/name lock ordering and the scalar-read-after-part-lock pattern documented in the existing code.

### What we’re not doing

Shelf-specific reservations; automatic split picks; return-to-original-shelf tracking; an Unassigned balance; a new CSV format; cross-company or cross-part transfers; full stock-movement history; unrelated PRD Non-Goals.

### Implementation approach

Use the existing company-scoped part row as the serialization lock for every stock writer. Add location quantity with a database nonnegative check. Keep the part total as a synchronized aggregate to preserve allocator and read-model contracts. Treat `PartLocation.quantity` as the physical shelf balance and assert the aggregate equality in PostgreSQL integration tests. Existing quantities and open-order live reservations reset to zero at migration; historical picked quantities and order records remain.

### Critical implementation details

**Lock and persistence ordering.** Resolve company ownership before mutation, lock the part, then read current shelf balances without trusting an entity loaded before the lock. Flush the shelf and aggregate changes before reallocation. Preserve existing shelf advisory-lock acquisition before part locks where a write creates or normalizes a shelf name.

**Migration boundary.** The zero-stock reset changes availability. Zero live reservations of open order lines in the same migration transaction; retain picked counters. Do not deploy an intermediate phase against production.

## Phase 1: Schema and stock contract

### Overview

Establish location balances, reset the unknowable legacy stock, and define the update invariant.

### Changes required

#### 1. Migration and entities

**File:** `src/main/resources/db/migration/V22__part_location_stock.sql`, `parts/PartLocation.java`, `parts/Part.java`

**Intent:** Add nonnegative quantities to existing locations without guessing their historical distribution. Retain the current part total for allocation and views.

**Contract:** `part_locations.quantity INT NOT NULL DEFAULT 0 CHECK (quantity >= 0)`; set existing `parts.quantity` and live `order_lines.reserved_quantity` on open orders to zero in the migration. Keep existing location and order rows, including picked quantities. Do not edit V1–V21.

#### 2. Shared stock update boundary

**File:** `parts/PartRepository.java` and a stock service in `parts/`

**Intent:** Give all stock writers one company-scoped, locked way to change a shelf and its aggregate.

**Contract:** Operations accept `companyId`, part id, location id or validated name, and quantity; reject foreign identifiers as 404. Lock the part before reading or writing stock. Validate nonnegative shelf and aggregate results, integer overflow, and `parts.quantity = SUM(location quantities)` within the transaction. Reallocate affected parts whenever the total changes; transfers do not reallocate.

#### 3. Product contract

**File:** `context/foundation/prd.md`, `context/foundation/prd-v2.md`, `context/foundation/roadmap.md`

**Intent:** Make the requested feature consistent with the repository’s hard rule against implementing PRD Non-Goals.

**Contract:** Edit the current stock and location requirements and remove the stock-per-location exclusion in place. Retain one warehouse per company and all unrelated Non-Goals. Update the parked roadmap statement; no `stock` Change ID currently appears for a planning-status flip.

### Success criteria

#### Automated verification

- V22 migrates populated PostgreSQL data while preserving catalog and order records and zeroing old stock and live reservations.
- Database checks reject a negative location balance, and service tests preserve the shelf-sum invariant under concurrent writes.

#### Manual verification

- A restored pre-change test database starts with the same parts and locations, zero physical stock, and zero live reservations.

**Implementation note:** Complete automated checks and obtain manual confirmation before the next phase.

## Phase 2: Stock entering and moving

### Overview

Make every positive inflow name a shelf, support shelf counts and transfers, and prevent stocked location removal.

### Changes required

#### 1. Part creation and editing

**File:** `parts/PartController.java`, `templates/parts-new.html`, `templates/parts-edit.html`

**Intent:** Assign a new part’s initial positive quantity to one selected location and retain stable location identity while editing.

**Contract:** A positive initial quantity requires a destination among the part’s locations; zero quantity requires none. Edit submissions identify existing location rows, preserve quantity on rename, reject removal of a nonempty row, and keep company-wide shelf spelling rules.

#### 2. Delivery and CSV import

**File:** `parts/DeliveryController.java`, `parts/PartsImportFile.java`, `parts/PartImportController.java`, import preview model and templates

**Intent:** Preserve the quantity-to-location association of each receipt or CSV row.

**Contract:** Every positive row requires a nonblank destination location; identical part/location rows may aggregate after normalization. Keep existing CSV columns. Preview shows resulting part and shelf balances. Confirmation compares the relevant shelf snapshot after locks, so a transfer that leaves the part total unchanged invalidates a stale preview. Successful writes trigger part-wide reallocation.

#### 3. Corrections and transfer

**File:** `parts/StockCorrectionController.java`, correction model/template, transfer route and form

**Intent:** Count one shelf at a time and move units without changing total availability.

**Contract:** Existing set/adjust correction routes take a company-owned location and validate its current balance under the part lock; retain required reason and record a location-name snapshot for new correction history while allowing old rows to lack one. Add manager-only `GET /parts/{id}/transfer` and `POST /parts/{id}/transfer` with source location, destination location, and positive quantity. Both locations must belong to the same part and company; reject insufficient source stock and same-source/destination submissions.

### Success criteria

#### Automated verification

- Creation, delivery, and CSV tests keep per-row shelf quantities, reject positive rows without a location, and update the part total exactly once.
- Correction, transfer, and catalog-edit tests cover overflow, insufficient stock, stale input, nonempty removal, rename, role denial, and foreign-company 404.

#### Manual verification

- A manager can receive and move units between two shelves, see the expected balances, and cannot delete a stocked shelf.

**Implementation note:** Complete automated checks and obtain manual confirmation before the next phase.

## Phase 3: Stock leaving and returning

### Overview

Give every pick a source shelf and every cancellation return a destination shelf.

### Changes required

#### 1. Picking

**File:** `orders/PickingController.java`, `orders/PickingDetailModel.java`, `templates/picking-detail.html`

**Intent:** Record which shelf physically supplied a pick while keeping the order line’s pooled reservation.

**Contract:** The pick submission includes one company-owned location id and a positive quantity. Under the part lock, require quantity no greater than both the live reservation and current shelf balance; decrement shelf and part total once, shift that amount from reserved to picked once, and preserve first-pick taken protection. A multi-shelf pick uses repeated submissions.

#### 2. Cancellation return

**File:** `orders/OrderController.java` and cancellation form/model

**Intent:** Add returned picked units to the shelf where staff place them.

**Contract:** Each positive returned part quantity requires one destination location of that part and company. Preserve the existing stale-picked-count and order-state checks. Add returned units to that shelf and the part total atomically, then reallocate affected parts. A zero return needs no destination.

### Success criteria

#### Automated verification

- Repeated partial picks from different shelves preserve live-counter, part-total, and reservation invariants.
- Pick and cancellation tests cover insufficient shelf stock, stale submissions, concurrent pick/return, wrong role, and foreign-company 404 without stock mutation.

#### Manual verification

- A picker can take six units from one shelf and two from another in two actions; cancellation can return selected units to a chosen shelf.

**Implementation note:** Complete automated checks and obtain manual confirmation before the next phase.

## Phase 4: Views and verification

### Overview

Expose shelf balances consistently and run the full regression and isolation suite.

### Changes required

#### 1. Stock views and messages

**File:** Part list/detail, picking, correction, import, delivery, and cancellation templates and message bundles

**Intent:** Show actionable location balances alongside the existing part-wide physical, reserved, and available totals.

**Contract:** Render only company-owned shelf data. Show each location’s balance; pick options show current shelf stock. Keep the shared Thymeleaf head, topbar, stylesheet, and template conventions.

#### 2. Cross-flow tests

**File:** Relevant parts/orders integration and concurrency tests

**Intent:** Prove the aggregate invariant across real PostgreSQL transactions and every stock event.

**Contract:** Cover two companies, role boundaries, operator and pending-company access for any added controller, concurrent delivery/pick/correction/transfer, import preview invalidation, taken-order shortage behavior, priority allocation, and shopping-list recomputation. Preserve the existing less-than-two-second refresh expectation at the project’s small target scale.

### Success criteria

#### Automated verification

- `mvnw.cmd verify` passes with Docker, including real-PostgreSQL stock, reservation, role, and two-company isolation tests.
- Every supported stock event leaves each part total equal to its location sum, with no negative balance or double reservation.

#### Manual verification

- Complete a two-company flow from receipt through reservation, split-shelf picking, correction, transfer, cancellation return, and shopping-list review; neither company sees the other’s shelves.

## Testing strategy

Use JUnit 5 integration tests with `TestcontainersConfiguration` and `CompanyFixtures`; do not substitute H2 or mocked stock persistence. Include migration tests with legacy nonzero totals and open orders, repeated picks on one line, two concurrent writers on one part, and cross-company id probes for each new route. Exercise stale import previews after a same-total transfer.

## Performance considerations

The existing part lock serializes same-part stock writes and allocator updates. Keep shelf lookups company-scoped and avoid one query per shelf in lists or import previews. Check the existing under-two-second post-event expectation with representative small-company data; no caching or new asynchronous allocation is planned.

## Migration notes

V22 intentionally discards current physical stock and live reservations while retaining catalog, locations, picked counts, corrections, and order history. Capture a database backup before rollout if those discarded values might be needed later. Deploy all four phases together; a partial release would permit old writers to change the part total without changing shelf balances.

## References

- `context/changes/stock/research.md`
- `context/foundation/prd.md` § Business Logic and Non-Goals
- `context/foundation/prd-v2.md` § Company isolation and Non-Goals
- `context/foundation/lessons.md` § Reservation locks and live counters
- `src/main/resources/db/migration/V3__create_parts_catalog.sql`
- `src/main/java/pl/regavio/stockahead/orders/ReservationAllocator.java`

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append `— <commit sha>` when a step lands. Do not rename step titles.

### Phase 1: Schema and stock contract

#### Automated

- [x] 1.1 V22 migrates populated PostgreSQL data while preserving catalog and order records and zeroing old stock and live reservations.
- [x] 1.2 Database checks reject a negative location balance, and service tests preserve the shelf-sum invariant under concurrent writes.

#### Manual

- [x] 1.3 A restored pre-change test database starts with the same parts and locations, zero physical stock, and zero live reservations.

### Phase 2: Stock entering and moving

#### Automated

- [ ] 2.1 Creation, delivery, and CSV tests keep per-row shelf quantities, reject positive rows without a location, and update the part total exactly once.
- [ ] 2.2 Correction, transfer, and catalog-edit tests cover overflow, insufficient stock, stale input, nonempty removal, rename, role denial, and foreign-company 404.

#### Manual

- [ ] 2.3 A manager can receive and move units between two shelves, see the expected balances, and cannot delete a stocked shelf.

### Phase 3: Stock leaving and returning

#### Automated

- [ ] 3.1 Repeated partial picks from different shelves preserve live-counter, part-total, and reservation invariants.
- [ ] 3.2 Pick and cancellation tests cover insufficient shelf stock, stale submissions, concurrent pick/return, wrong role, and foreign-company 404 without stock mutation.

#### Manual

- [ ] 3.3 A picker can take six units from one shelf and two from another in two actions; cancellation can return selected units to a chosen shelf.

### Phase 4: Views and verification

#### Automated

- [ ] 4.1 `mvnw.cmd verify` passes with Docker, including real-PostgreSQL stock, reservation, role, and two-company isolation tests.
- [ ] 4.2 Every supported stock event leaves each part total equal to its location sum, with no negative balance or double reservation.

#### Manual

- [ ] 4.3 Complete a two-company flow from receipt through reservation, split-shelf picking, correction, transfer, cancellation return, and shopping-list review; neither company sees the other’s shelves.
