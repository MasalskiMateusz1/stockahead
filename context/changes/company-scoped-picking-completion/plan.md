# Company-Scoped Picking and Completion Implementation Plan

## Overview

Roadmap item S-04 of milestone M-2. A technician or manager opens, picks and reports completion only for their own company's orders. Another company's order id is a 404 on `/picking/{id}`, on the pick action and on report-completion, and nothing is locked or changed. The DB refuses a completion reporter from another company (V18). The picking list's "to pick" counts read only the user's company. This finishes FR-028 for picking and completion, and the prd-v2 guardrail "a user of company A must never see or change company B's data, not even by guessing a record id".

## Current State Analysis

S-03 scoped orders, allocation, the picking **list** (`PickingController.list`'s three order queries and assignee filter), the assignee reference, and the manager's confirm/reject completion (`OrderController.transitionReportedOrder`, `OrderController.java:454`). On purpose, it left these picking by-id lookups bare:

- `PickingDetailModel.load` → `orderRepository.findByIdWithDetails(orderId)` (`PickingDetailModel.java:68`). This serves `GET /picking/{id}` and every error re-render of pick and report-completion.
- `PickingController.pick` → `orderLineRepository.findPartIdForLine(orderId, lineId)` (`PickingController.java:164`), then `partRepository.findByIdInForUpdate(Set.of(partId))` (`:176`), then `findLine` → `orderRepository.findById(orderId)` (`:325`).
- `PickingController.reportCompletion` → `orderLineRepository.findPartIdsForOrder(orderId)` (`:243`), `partRepository.findByIdInForUpdate(partIds)` (`:255`) and `orderRepository.findById(orderId)` (`:257`).
- `PickingController.list` → `orderLineRepository.reservedQuantitiesByOpenOrder()` (`:143`). This reads every company's open lines. The result is keyed by order id, so nothing foreign is rendered today, but AGENTS.md forbids reading other companies' rows.

Today, company B's user can open, pick from and report completion on company A's order by typing its id.

`orders.completion_reported_by` still has V8's plain FK to `accounts(id)` (`V8__add_order_completion.sql:2`). S-03 handed its composite FK to S-04 (archived plan, "What We're NOT Doing").

## Desired End State

- `GET /picking/{A}`, `POST /picking/{A}/lines/{lineA}/pick` and `POST /picking/{A}/report-completion` return 404 for company B's manager and technician. A's order, its lines and A's part stock are byte-for-byte unchanged.
- A line id that belongs to another company's order, posted under B's own order id, is a 404.
- The "to pick" totals on `/picking` come from a company-scoped query.
- The DB rejects `completion_reported_by` pointing to an account of another company. NULL (not reported) still passes.
- Within a company nothing changes. A technician can still open and pick any open order of their own company by id (decided: company boundary only).
- The bare `findByIdWithDetails(Long)`, `findPartIdsForOrder(Long)`, `findPartIdForLine(Long, Long)` and `reservedQuantitiesByOpenOrder()` are gone. `PartRepository.findByIdInForUpdate` stays for S-05.

### Key Discoveries:

- Scoped versions already exist from S-03: `OrderRepository.findByIdWithDetails(id, companyId)` (`OrderRepository.java:39-40`), `findByIdAndCompanyId` (`:48`), `findByIdForUpdate(id, companyId)` (`:43-45`), `OrderLineRepository.findPartIdsForOrder(orderId, companyId)` (`OrderLineRepository.java:115-116`) and `PartRepository.findByIdInAndCompanyIdForUpdate(ids, companyId)` (`PartRepository.java:46`).
- Pattern to copy: `OrderController.transitionOrder` (`OrderController.java:480-509`) does a scoped scalar pre-lock lookup, takes the scoped part lock, then does a scoped re-read. A foreign id yields no part ids and no order, so it is a 404 before anything is locked.
- The open-in-view stale-cache rule in `PickingController`'s Javadoc (`:46-60`) still holds. Pre-lock lookups stay scalar projections, and the scoped `findPartIdForLine` must stay one too.
- No test calls the bare methods (grep of `src/test`), so they can be deleted without changing any test.
- V17 pattern for composite account FKs: `orders_assignee_company_fkey` (`V17__company_scoped_orders.sql:15-16`) targets `accounts_id_company_key (id, company_id)`. MATCH SIMPLE lets a NULL column pass.
- `OrderSchemaTests` already seeds two companies with accounts, and is where the assignee FK tests live (`OrderSchemaTests.java:116-141`).
- Test template for routes: `OrdersCompanyIsolationIntegrationTests`, which has seeds for untaken and reported orders, `snapshotOf` and the B→404 write-route test (`:237-274`).

## What We're NOT Doing

- No restriction of a technician to orders assigned to them. Picking by-id routes enforce the company boundary only. The list's assignee filter stays a view filter, not an access rule (decided in planning; prd-v2 keeps the role → capability matrix).
- No change to confirm/reject completion. S-03 already scoped it and covers it with a B→404 test.
- No scoping of the delivery, stock-correction or import part lookups (`PartRepository.findByIdInForUpdate`, `OrderLineRepository.reservedQuantityForPart`). That's S-05. No change to the shopping-list query `findOpenLinesWithShortage`. That's S-06.
- No composite FK on `orders.cancelled_by`. It's set from the authenticated manager (S-03's call), and not part of the picking flow.
- No change to the pick or report-completion business rules, the lock strategy or the allocator. A pick still never calls `ReservationAllocator`.
- No new wrong-role 403 test. Every picking route allows MANAGER and TECHNICIAN, and the role that should be refused (operator) arrives in S-08, which owns its 403 tests.
- No UI or template change.

## Implementation Approach

Two phases, the same schema-first split as S-03:

- **Phase 1** adds V18 and its schema tests. Behavior doesn't change and the build stays green.
- **Phase 2** gives every picking by-id lookup the company from `@AuthenticationPrincipal CompanyUser`, deletes the bare repository variants so no caller can drift back to them, and adds a dedicated two-company test class for the picking routes.

A foreign id is caught by the first scoped lookup, before validation output, locks or writes, exactly like an unknown id.

## Phase 1: Reporter composite FK

### Overview

The DB guarantees that a completion reporter belongs to the order's company.

### Changes Required:

#### 1. Migration V18

**File**: `src/main/resources/db/migration/V18__company_scoped_completion_reporter.sql`

**Intent**: Replace V8's single-column FK on `orders.completion_reported_by` with a composite FK to the reporter's account in the same company, so an order and its reporter can never link two companies.

**Contract**: Drop `orders_completion_reported_by_fkey`. Add `orders_completion_reporter_company_fkey FOREIGN KEY (completion_reported_by, company_id) REFERENCES accounts (id, company_id) ON DELETE RESTRICT`. That's the default MATCH SIMPLE, so a NULL reporter passes, and `ON DELETE` matches V8. Leave V8's CHECK constraints alone. Add a header comment in V17's style. Confirm the FK's real name (Postgres default naming for V8's inline `REFERENCES`) before dropping it.

#### 2. Schema tests

**File**: `src/test/java/pl/regavio/stockahead/orders/OrderSchemaTests.java`

**Intent**: Prove the new FK next to the existing assignee FK tests.

**Contract**: Two new tests:
- `completionReportedByAnotherCompanysAccountIsRejected`: a taken order of company A, updated with `completion_reported_at = now()` and `completion_reported_by` set to company B's account, throws `DataIntegrityViolationException`.
- `completionReportedByOwnCompanysAccountIsAccepted`: the same update with an account of A succeeds.

Reuse the class's existing two-company seed.

### Success Criteria:

#### Automated Verification:

- `./mvnw verify` passes, including Flyway applying V18 on a fresh Testcontainers Postgres
- `OrderSchemaTests` passes with the two new reporter-FK tests
- `OrderCompletionSchemaTests`, `OrderCompletionIntegrationTests` and `OrderCompletionConcurrencyTests` pass unchanged, so every existing reported-order seed already uses a same-company reporter

#### Manual Verification:

- `\d orders` on a local DB shows `orders_completion_reporter_company_fkey` and no `orders_completion_reported_by_fkey`

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful before proceeding to the next phase.

---

## Phase 2: Scoped picking routes and two-company tests

### Overview

Every picking by-id route and the list's "to pick" totals use the principal's company, and two-company tests prove a foreign order id is a 404 that changes nothing.

### Changes Required:

#### 1. Repository scoping

**File**: `src/main/java/pl/regavio/stockahead/orders/OrderLineRepository.java`

**Intent**: The picking pre-lock lookup and the per-order "to pick" totals read only one company's lines. Bare variants are removed so no caller can use them by mistake.

**Contract**:
- `findPartIdForLine(Long orderId, Long lineId, Long companyId)` is still a scalar `ol.part.id` projection, with `ol.companyId = :companyId` added. It replaces the 2-argument version.
- `reservedQuantitiesByOpenOrder(Long companyId)` adds `ol.companyId = :companyId` and replaces the no-argument version.
- Delete the bare `findPartIdsForOrder(Long)`.
- Update the Javadocs that point to the removed overloads.

**File**: `src/main/java/pl/regavio/stockahead/orders/OrderRepository.java`

**Intent**: Remove the last unscoped detail lookup.

**Contract**: Delete `findByIdWithDetails(Long)`, and update the Javadoc of the scoped overload that points to it.

#### 2. Picking detail model

**File**: `src/main/java/pl/regavio/stockahead/orders/PickingDetailModel.java`

**Intent**: The detail page and every error re-render load the order only within the user's company. Another company's order is 404, exactly like a missing one.

**Contract**: `render(Model, Long companyId, Long orderId)` and `render(Model, Long companyId, Long orderId, String error, Long errorLineId)`. `load` uses `findByIdWithDetails(orderId, companyId)`. This follows `OrderDetailModel`'s `(model, companyId, orderId, …)` order.

#### 3. Picking controller

**File**: `src/main/java/pl/regavio/stockahead/orders/PickingController.java`

**Intent**: Take the company from the principal on detail, pick and report-completion, and pass it to every lookup and lock. A foreign order or line fails the first scoped lookup with 404, before validation, locks or writes.

**Contract**:
- `detail(...)` gains `@AuthenticationPrincipal CompanyUser principal`.
- `pick(...)` gains the principal. It uses `findPartIdForLine(orderId, lineId, companyId)`, then `partRepository.findByIdInAndCompanyIdForUpdate(Set.of(partId), companyId)` in place of the bare `findByIdInForUpdate`, then `findLine(companyId, orderId, lineId)` → `orderRepository.findByIdAndCompanyId`.
- `reportCompletion(...)` gains the principal. It uses `findPartIdsForOrder(orderId, companyId)`, `findByIdInAndCompanyIdForUpdate(partIds, companyId)` and `findByIdAndCompanyId(orderId, companyId)`.
- `list` calls `reservedQuantitiesByOpenOrder(companyId)`.
- `renderPickError`/`renderReportError` pass `companyId` through to `PickingDetailModel.render`.
- The reporter lookup (`findByCanonicalEmail`) stays. It's the principal's own account.
- Update the class and method Javadocs that name the bare methods: say the lookups are scoped to the principal's company, and that a foreign id is 404 like an unknown one, before anything is locked.
- After the change, the `PickingController` and `PickingDetailModel` sources contain no `findById(`, no bare `findByIdInForUpdate(` and no unscoped `findByIdWithDetails(`.

#### 4. Two-company picking tests

**File**: `src/test/java/pl/regavio/stockahead/orders/PickingCompanyIsolationIntegrationTests.java` (new)

**Intent**: Every company-scoped picking route ships with its B→404 test (AGENTS.md), for both roles that can reach picking.

**Contract**: Use `@Import({TestcontainersConfiguration.class, CompanyFixtures.class})`, with a setup copied from `OrdersCompanyIsolationIntegrationTests`: companies A and B, a manager and a technician each, a same-named part and project per company, and orders seeded through the real create route or the same seed helpers. Tests:
- `otherCompanysUsersGet404OnPickingDetail`: B's manager and B's technician get 404 on `GET /picking/{A}`.
- `otherCompanysUsersGet404OnPickAndChangeNothing`: B's manager and technician post a valid quantity to A's line. Both get 404, and a snapshot of A's order (`taken_at`), its lines (`reserved_quantity`, `picked_quantity`) and A's part `quantity` is unchanged. A non-integer quantity on A's ids is also 404, which proves the lookup runs before validation output.
- `otherCompanysUsersGet404OnReportCompletionAndChangeNothing`: A's order is taken, B posts `builtUnits` and gets 404. `completion_reported_at`, `completion_reported_by` and `built_units` stay NULL.
- `foreignLineUnderOwnOrderIdIs404`: B posts a pick to `/picking/{B's order}/lines/{A's line}/pick`. It gets 404 and nothing changes on either side.
- `ownCompanyPickAndReportStillWork`: B's technician picks on B's order and reports completion. Both redirect, and the stock and report fields change as before. This guards against over-scoping.
- `toPickTotalsReadOnlyTheGivenCompany`: `orderLineRepository.reservedQuantitiesByOpenOrder(companyB)` contains B's open order and not A's.

### Success Criteria:

#### Automated Verification:

- `./mvnw verify` passes
- `PickingCompanyIsolationIntegrationTests` passes all six tests
- `PickingIntegrationTests`, `PickingListAndDetailIntegrationTests`, `PickingVisibilityIntegrationTests`, `PickingConcurrencyTests`, `OrderCompletionIntegrationTests`, `OrderCompletionConcurrencyTests` and `OrdersCompanyIsolationIntegrationTests` pass with no edits other than call-site signature changes
- Grep check: `grep -nE "findById\(|findByIdInForUpdate\(|reservedQuantitiesByOpenOrder\(\)|findByIdWithDetails\([a-zA-Z]+\)" src/main/java/pl/regavio/stockahead/orders/Picking*.java` returns nothing

#### Manual Verification:

- With two companies registered locally (`./mvnw spring-boot:test-run`), A's technician opens and picks A's order normally. Logged in as B, pasting A's `/picking/{id}` URL shows the 404 page.
- B's `/picking` list shows correct "to pick" numbers for B's own orders only

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful before proceeding to the next phase.

---

## Testing Strategy

### Unit Tests:

- None new. The behavior is DB-scoped and tested against real Postgres (AGENTS.md § Testing).

### Integration Tests:

- `OrderSchemaTests`: the reporter composite FK rejects a foreign reporter and accepts a same-company one.
- `PickingCompanyIsolationIntegrationTests`: B→404 on detail, pick and report-completion for both roles, with snapshots proving nothing changed. A foreign line under B's own order id is 404. The own-company flow still works. The "to pick" totals are scoped.
- Existing picking and completion suites and the concurrency suites stay green, which proves the scoped part lock doesn't change the single-company serialization.

### Manual Testing Steps:

1. Start `./mvnw spring-boot:test-run`, then create companies A and B, each with a technician, part, project and order.
2. As A's technician, pick a line on A's order and report completion. Both work.
3. As B's technician, paste A's `/picking/{id}` URL. You get the 404 page.
4. As B's manager, open `/picking`. Only B's orders are listed, with correct "to pick" totals.

## Performance Considerations

None. The scoped queries add one equality predicate on `company_id`, and `order_lines` lookups go by order id or part id as before.

## Migration Notes

V18 only swaps an FK. It fails if any existing row has a cross-company reporter. None can exist, because the reporter is always the authenticated user and multi-company work ships from its branch with V14's empty-table guard. No backfill.

## References

- Roadmap: `context/foundation/roadmap.md` § S-04
- Previous slice: `context/archive/2026-10-17-company-scoped-orders/plan.md` (hand-offs to S-04, lines 61-64)
- Pattern: `src/main/java/pl/regavio/stockahead/orders/OrderController.java:480-509` (`transitionOrder`)
- Test template: `src/test/java/pl/regavio/stockahead/orders/OrdersCompanyIsolationIntegrationTests.java:237-274`
- FK template: `src/main/resources/db/migration/V17__company_scoped_orders.sql:15-16`
- Lessons: `context/foundation/lessons.md` (wrong-role 403 per gated route, re-subtracting a live counter)

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles. See `references/progress-format.md`.

### Phase 1: Reporter composite FK

#### Automated

- [x] 1.1 `./mvnw verify` passes, including Flyway applying V18 on a fresh Testcontainers Postgres
- [x] 1.2 `OrderSchemaTests` passes with the two new reporter-FK tests
- [x] 1.3 `OrderCompletionSchemaTests`, `OrderCompletionIntegrationTests` and `OrderCompletionConcurrencyTests` pass unchanged

#### Manual

- [x] 1.4 `\d orders` shows `orders_completion_reporter_company_fkey` and no `orders_completion_reported_by_fkey`

### Phase 2: Scoped picking routes and two-company tests

#### Automated

- [ ] 2.1 `./mvnw verify` passes
- [ ] 2.2 `PickingCompanyIsolationIntegrationTests` passes all six tests
- [ ] 2.3 Existing picking, completion and order-isolation suites pass with only call-site signature changes
- [ ] 2.4 Grep check finds no bare lookups in `Picking*.java`

#### Manual

- [ ] 2.5 As company B, pasting A's `/picking/{id}` URL shows the 404 page while A picks normally
- [ ] 2.6 B's `/picking` list shows correct "to pick" numbers for B's own orders only
