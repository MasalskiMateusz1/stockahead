# Order Assignee Implementation Plan

## Overview

Orders get an optional assignee: an active technician or the manager. The manager sets it at creation and can change or clear it at any point while the order is `OPEN`, including after it has been taken. On `/picking`, a technician sees only the orders assigned to them. The manager sees all open orders and can filter them by assignee or by "Nieprzypisane". This closes the PRD gap where US-01 and FR-010 say "zleca **technikowi**" but no slice ever stored who the order is for (see `frame.md`).

## Current State Analysis

- `orders` has no assignee column (`src/main/resources/db/migration/V6__create_orders.sql`). The latest migration is `V12__add_order_built_units.sql`, so this change adds **V13**.
- `Order` already references `Account` for the people who acted on it: `completionReportedBy` and `cancelledBy`, both `@ManyToOne(fetch = LAZY)` (`src/main/java/pl/regavio/stockahead/orders/Order.java:62-75`). The assignee follows the same convention.
- `GET /picking` (`PickingController.java:91-103`) shows every `OPEN` order to MANAGER and TECHNICIAN alike, sorted by `ReservationAllocator.ALLOCATION_ORDER`.
- `POST /orders` (`OrderController.java:451-521`) has no assignee input. The manager's order transitions all run through `transitionOrder` (`OrderController.java:403-430`). It does a scalar part-id lookup, takes part-row locks (or the order row lock when the order has no lines), re-reads the order, applies the transition and maps errors.
- Accounts are deactivated through the `active` flag and never deleted (`TechnicianAccountController.java:751-761`, FR-002). `AccountRepository.findByRoleOrderByEmailAsc` is the only listing query.
- `ReservationAllocator` never saves `Order` rows, so the assignee cannot affect allocation.
- The existing technician `GET /picking` assertions seed orders via raw SQL `INSERT INTO orders` without an assignee: `PickingListAndDetailIntegrationTests.java:147,292-311`, `OrderCancelIntegrationTests.java:596,623` and `OrderCompletionIntegrationTests.java:553,561,616`. Once Phase 3 hides unassigned orders from technicians, those assertions break.
- No roadmap item has Change ID `order-set-to-specific-technician`.

## Desired End State

- `orders.assignee_id` is a nullable FK to `accounts`, and existing rows stay `NULL`.
- `/orders/new` has an optional "Technik" picker listing active technicians and the manager. Choosing nothing creates an unassigned order.
- `/orders/{id}` shows the assignee and, while the order is `OPEN`, a reassign form that also offers "— nieprzypisane —". The `/orders` lists show an assignee column.
- On `GET /picking`:
  - A technician sees only open orders where `assignee = self`.
  - The manager sees all open orders, with an assignee column and a filter. "Wszystkie" is the default, each assignable account (active technician or the manager) has its own entry, and "Nieprzypisane" matches orders whose assignee is `NULL` **or an inactive account**.
- The PRD has a new **FR-022** describing these rules.
- `./mvnw verify` passes.

### Key Discoveries:

- Pick and report-completion re-read the `Order` and `saveAndFlush` it under the part locks (`PickingController.java:131-157,205-240`). Hibernate's default UPDATE writes every column, so a reassignment that took only the order row lock could be overwritten by a concurrent report that read the order earlier. Going through `transitionOrder` serializes reassignment with pick, report, change, cancel and confirm/reject.
- `OrderDetailModel.OrderView` is the view record for `/orders/{id}` (`OrderDetailModel.java:186-190`). Assignee fields go there, and the detail page reads nothing from entities directly.
- The `TemplateConventionTests` lesson applies: no inline styles, and new markup uses `fragments :: head/topbar` and `app.css` classes only.

## What We're NOT Doing

- No authorization boundary. `/picking/{id}`, pick and report-completion stay open to any MANAGER/TECHNICIAN, so a technician who knows another order's URL can still open it (frame scope: picking list only).
- No change to reservations or allocation order. Assigning or reassigning is not a recompute event.
- No backfill of existing orders and no `NOT NULL` constraint.
- No automatic data change when an account is deactivated: `assignee_id` is kept, and visibility derives from `accounts.active`.
- No workload view or technician scheduling (PRD Non-Goals: "brak planowania obciążenia techników").
- No history of earlier assignees.
- No change to PRD § Access Control. This is a list filter, not a permission.

## Implementation Approach

Data first, then writes, then reads. Phase 1 adds the column, the entity field and the "assignable accounts" query, and wires them into order creation and the manager's order views. Phase 2 adds reassignment through the existing `transitionOrder` lock shape, without calling the allocator. Phase 3 switches `/picking` to per-viewer visibility with the manager filter, repairs the existing technician list fixtures, and records FR-022.

## Critical Implementation Details

**Lost-update ordering.** Reassignment must reuse `OrderController.transitionOrder` (part-row locks, or the order row lock when the order has no lines, then re-read). Locking only the order row is not enough. Pick and report-completion write the whole `Order` row from a copy read under the part locks, and would silently restore the old assignee.

**"Assignable" is one rule used everywhere.** An assignable account is active, with role TECHNICIAN or MANAGER. The create picker, the reassign picker, the server-side validation and the manager's `/picking` filter entries all use this same query. "Nieprzypisane" is its complement for open orders: `assignee IS NULL OR assignee.active = false`.

## Phase 1: Assignee data model and assignment on create

### Overview

Store the assignee, let the manager choose one when creating an order, and show it in the manager's order views.

### Changes Required:

#### 1. Migration

**File**: `src/main/resources/db/migration/V13__add_order_assignee.sql`

**Intent**: Add a nullable assignee reference so existing orders stay valid and unassigned.

**Contract**: `orders.assignee_id BIGINT NULL REFERENCES accounts (id) ON DELETE RESTRICT`, plus an index on `assignee_id`. No backfill.

#### 2. Order entity

**File**: `src/main/java/pl/regavio/stockahead/orders/Order.java`

**Intent**: Map the assignee the same way as `completionReportedBy`/`cancelledBy`.

**Contract**: `@ManyToOne(fetch = LAZY) @JoinColumn(name = "assignee_id") Account assignee`, with a getter and setter.

#### 3. Assignable accounts query

**File**: `src/main/java/pl/regavio/stockahead/account/AccountRepository.java`

**Intent**: One query for "who can be assigned": active TECHNICIAN or MANAGER accounts, ordered by email.

**Contract**: `List<Account> findAssignable()` (name is the implementer's choice), returning `active = true AND role IN (TECHNICIAN, MANAGER)` ordered by email.

#### 4. Create form and POST

**Files**: `src/main/java/pl/regavio/stockahead/orders/OrderController.java`, `src/main/resources/templates/orders-new.html`, `src/main/resources/messages.properties`

**Intent**: Add an optional `assigneeId` select (first option "— nieprzypisane —", then the assignable accounts by email) that is kept on a failed re-render. Inside the create transaction, resolve a non-blank `assigneeId` to an assignable account. An unknown, inactive or malformed id re-renders the form with an error, following the same pattern as `projectUnavailable`.

**Contract**: `POST /orders` gains an optional request param `assigneeId`. Blank or missing means an unassigned order. New message key `orders.error.assigneeUnavailable` ("Wybrany technik jest nieaktywny lub nie istnieje."). `populateFormModel` adds `assignableAccounts`.

#### 5. Manager order views show the assignee

**Files**: `src/main/java/pl/regavio/stockahead/orders/OrderDetailModel.java`, `src/main/resources/templates/orders-detail.html`, `src/main/resources/templates/orders-list.html`, `src/main/java/pl/regavio/stockahead/orders/OrderRepository.java`

**Intent**: Show who the order is for: on the detail page a "Technik:" entry in the stats list, and in both `/orders` tables a "Technik" column. Show the email, add " (nieaktywny)" when the account is inactive, and show "—" when there is no assignee. Fetch the assignee with the list query so the lists don't load it lazily per row.

**Contract**: `OrderView` gains `assigneeEmail` (nullable) and `assigneeActive`. `findByStatusWithProject` adds `LEFT JOIN FETCH o.assignee`.

### Success Criteria:

#### Automated Verification:

- `./mvnw verify` passes, with V13 applied by Flyway on Testcontainers Postgres
- `OrderCreationIntegrationTests`: creating with an active technician's id stores that assignee, creating with the manager's id stores the manager, and creating with no `assigneeId` stores `NULL`
- `OrderCreationIntegrationTests`: an inactive technician's id, an unknown id and a non-numeric id each re-render `orders-new` with `orders.error.assigneeUnavailable`, and no order is created
- `OrderListAndDetailIntegrationTests`: the detail page and the `/orders` list show the assignee email, "(nieaktywny)" for a deactivated assignee, and "—" for an unassigned order

#### Manual Verification:

- On `/orders/new`, the Technik dropdown lists active technicians and the manager and leaves out a deactivated technician
- A created order's detail page shows the chosen technician

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful before proceeding to the next phase.

---

## Phase 2: Reassign and unassign from the order detail

### Overview

Let the manager change or clear the assignee at any time while the order is `OPEN`, including when it is taken or its completion report is pending.

### Changes Required:

#### 1. Assign route

**File**: `src/main/java/pl/regavio/stockahead/orders/OrderController.java`

**Intent**: Add a manager-only POST that parses `assigneeId` before taking any lock, then runs through `transitionOrder`. Under the lock it checks that the order is still `OPEN` and that a non-blank id resolves to an assignable account, then sets or clears the assignee and `saveAndFlush`es. It does **not** call `reservationAllocator`. On success it redirects to `/orders/{id}`. Business errors re-render the detail page via `orderDetailModel.render(model, id, error)`.

**Contract**: `POST /orders/{id}/assign`, `@PreAuthorize("hasRole('MANAGER')")`, with optional param `assigneeId` (blank means unassigned). New message keys `orders.error.notAssignable` (the order is not open) and `orders.error.assignFailed` (DB or lock failure). Reuses `orders.error.assigneeUnavailable`. An unknown order returns 404 through `transitionOrder`.

#### 2. Detail page reassign section

**Files**: `src/main/resources/templates/orders-detail.html`, `src/main/java/pl/regavio/stockahead/orders/OrderDetailModel.java`

**Intent**: While the order is `OPEN`, show a `<section id="order-assign">` card with a select ("— nieprzypisane —" plus the assignable accounts) preselected to the current assignee, and a "Zapisz technika" button. When the current assignee is inactive, nothing is preselected. Unlike `#order-change`, this section stays visible after the order is taken.

**Contract**: The detail model adds `assignableAccounts` and the current `assigneeId` to the model.

### Success Criteria:

#### Automated Verification:

- `./mvnw verify` passes
- New `OrderAssignIntegrationTests`: the manager reassigns an untaken order, a **taken** order and a completion-reported order, and each new assignee is persisted
- `OrderAssignIntegrationTests`: a blank `assigneeId` clears the assignee
- `OrderAssignIntegrationTests`: assigning to an inactive or unknown account re-renders with `orders.error.assigneeUnavailable`, and the assignee is unchanged
- `OrderAssignIntegrationTests`: a `COMPLETED` or `CANCELLED` order is rejected with `orders.error.notAssignable`
- `OrderAssignIntegrationTests`: every line's `reserved_quantity`/`picked_quantity` and the order's `priority`/`required_date`/`taken_at` are identical before and after reassignment
- `OrderAssignIntegrationTests`: a TECHNICIAN posting to `/orders/{id}/assign` gets 403 (wrong-role lesson)

#### Manual Verification:

- On a taken order's detail page, the reassign card is visible while the priority/date card is not, and saving a new technician updates the "Technik:" entry

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful before proceeding to the next phase.

---

## Phase 3: Per-viewer `/picking` list, manager filter and FR-022

### Overview

A technician's picking list shows only their own orders. The manager's list shows everything and can be filtered. The PRD records the rules.

### Changes Required:

#### 1. Picking list visibility and filter

**Files**: `src/main/java/pl/regavio/stockahead/orders/PickingController.java`, `src/main/java/pl/regavio/stockahead/orders/OrderRepository.java`

**Intent**: Resolve the current account from `Authentication`.
- A TECHNICIAN gets open orders where `assignee.id = self.id`.
- A MANAGER gets all open orders by default.
  - `assignee=<id>` narrows the list to that account's open orders.
  - `assignee=none` narrows it to open orders whose assignee is `NULL` or inactive.
  - A malformed value, or an id that isn't assignable, falls back to all orders. It is a view filter, so it should not produce an error page.

Sorting by `ALLOCATION_ORDER` and the `toPickByOrderId` map stay unchanged.

**Contract**: `GET /picking` gains optional param `assignee` (`<accountId>` or `none`), which only the manager branch reads. The model adds `assignableAccounts`, `selectedAssignee` and `isManager`. Repository queries fetch `project` and `assignee` in one go, filtered by status plus the assignee condition.

#### 2. Picking list template

**File**: `src/main/resources/templates/picking-list.html`

**Intent**: For the manager only, add a GET filter form above the table (a select with "Wszystkie", "Nieprzypisane" and the assignable accounts, plus a "Filtruj" button) and a "Technik" column showing the email, " (nieaktywny)" or "—". The technician's table keeps today's columns.

**Contract**: The form is `<form method="get" th:action="@{/picking}">` with `name="assignee"`. Styling uses only `app.css` classes, per `TemplateConventionTests`.

#### 3. Repair existing technician `/picking` fixtures

**Files**: `src/test/java/pl/regavio/stockahead/orders/PickingListAndDetailIntegrationTests.java`, `OrderCancelIntegrationTests.java`, `OrderCompletionIntegrationTests.java`

**Intent**: Where a technician session asserts an order **is** listed on `GET /picking`, seed or update that order with `assignee_id` = that technician. The assertions themselves stay the same. Assertions that an order is *absent* (cancelled or completed) stay as they are.

**Contract**: The test helpers' raw `INSERT INTO orders` gain an optional `assignee_id`. Cleanup stays unchanged, since accounts and orders are already wiped.

#### 4. PRD FR-022

**File**: `context/foundation/prd.md`

**Intent**: Record the rule as a new requirement placed after FR-021.

**Contract**: "FR-022: Kierownik może przypisać zlecenie technikowi lub sobie przy zakładaniu (opcjonalnie) i zmienić albo usunąć przypisanie, dopóki zlecenie jest otwarte — także po podjęciu; przypisanie nie wpływa na rezerwacje. Technik widzi na liście do zmontowania tylko zlecenia przypisane do siebie; kierownik widzi wszystkie otwarte zlecenia z filtrem po technikach i „Nieprzypisane” (brak przypisania lub przypisany nieaktywny technik). Priority: must-have". § Access Control is unchanged.

### Success Criteria:

#### Automated Verification:

- `./mvnw verify` passes, including the repaired existing suites
- New `PickingVisibilityIntegrationTests`: technician A sees their order and does not see technician B's order, an unassigned order or a manager-assigned order
- `PickingVisibilityIntegrationTests`: the manager without a filter sees all four orders
- `PickingVisibilityIntegrationTests`: the manager with `assignee=<A>` sees only A's order, and with `assignee=none` sees the unassigned order and the order of a **deactivated** technician C, and nothing else
- `PickingVisibilityIntegrationTests`: the manager with `assignee=abc` or an unknown id gets 200 with all orders
- `PickingVisibilityIntegrationTests`: after A's order is reassigned to B via `POST /orders/{id}/assign` (taken order), it leaves A's `/picking` and appears on B's
- `TemplateConventionTests` passes for the changed `picking-list.html`

#### Manual Verification:

- Logged in as a technician, `/picking` lists only that technician's orders, with no filter form and no Technik column
- Logged in as the manager, the filter switches between Wszystkie, Nieprzypisane and each technician, and a deactivated technician's order appears under Nieprzypisane marked "(nieaktywny)"
- PRD FR-022 reads correctly next to FR-010 and FR-012

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful before proceeding to the next phase.

---

## Testing Strategy

### Unit Tests:

- None needed. All rules are query or controller behavior and are tested end to end against real Postgres (AGENTS.md: no H2, no mocked DB).

### Integration Tests:

- Create with an assignee, with no assignee, and with an inactive, unknown or malformed assignee (Phase 1).
- Reassign while untaken, taken and reported; clear the assignee; reject on closed orders; reservations unchanged; technician gets 403 (Phase 2).
- `/picking` visibility matrix across technicians A and B, unassigned, manager-assigned and deactivated C, plus the manager filter values and a handover moving an order between queues (Phase 3).

### Manual Testing Steps:

1. As the manager, create two orders: one for technician A and one unassigned.
2. Log in as A: `/picking` shows only the first order. Pick one line so the order becomes taken.
3. As the manager, reassign the taken order to technician B on `/orders/{id}`.
4. Log in as B: the order is listed, and picking continues normally.
5. As the manager, deactivate B. On `/picking?assignee=none`, B's order appears marked "(nieaktywny)".

## Performance Considerations

The list queries fetch `assignee` alongside `project`, so adding the column causes no per-row lazy loads (JOIN FETCH lesson). The index on `assignee_id` supports the technician filter.

## Migration Notes

V13 is additive and nullable. Existing open orders become unassigned and, after Phase 3 deploys, are visible only to the manager until they are assigned. Assign them right after the deploy. Rollback means reverting the code. The column can stay, since nothing reads it once the code is reverted.

## References

- Frame brief: `context/changes/order-set-to-specific-technician/frame.md`
- Lock/re-read shape: `src/main/java/pl/regavio/stockahead/orders/OrderController.java:403-430`
- Picking list: `src/main/java/pl/regavio/stockahead/orders/PickingController.java:91-103`
- Account refs convention: `src/main/java/pl/regavio/stockahead/orders/Order.java:62-75`
- Deactivation: `src/main/java/pl/regavio/stockahead/account/TechnicianAccountController.java:751-761`
- PRD: US-01, FR-002, FR-010, FR-012, FR-021, § Access Control, § Non-Goals
- Prior change: `context/archive/2026-10-01-picking-list-and-pick/plan-brief.md`

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles. See `references/progress-format.md`.

### Phase 1: Assignee data model and assignment on create

#### Automated

- [x] 1.1 `./mvnw verify` passes, with V13 applied by Flyway on Testcontainers Postgres — f234081
- [x] 1.2 `OrderCreationIntegrationTests`: creating with an active technician's id stores that assignee, creating with the manager's id stores the manager, and creating with no `assigneeId` stores `NULL` — f234081
- [x] 1.3 `OrderCreationIntegrationTests`: an inactive technician's id, an unknown id and a non-numeric id each re-render `orders-new` with `orders.error.assigneeUnavailable`, and no order is created — f234081
- [x] 1.4 `OrderListAndDetailIntegrationTests`: the detail page and the `/orders` list show the assignee email, "(nieaktywny)" for a deactivated assignee, and "—" for an unassigned order — f234081

#### Manual

- [x] 1.5 On `/orders/new`, the Technik dropdown lists active technicians and the manager and leaves out a deactivated technician — f234081
- [x] 1.6 A created order's detail page shows the chosen technician — f234081

### Phase 2: Reassign and unassign from the order detail

#### Automated

- [x] 2.1 `./mvnw verify` passes — fb8b6c2
- [x] 2.2 New `OrderAssignIntegrationTests`: the manager reassigns an untaken order, a **taken** order and a completion-reported order, and each new assignee is persisted — fb8b6c2
- [x] 2.3 `OrderAssignIntegrationTests`: a blank `assigneeId` clears the assignee — fb8b6c2
- [x] 2.4 `OrderAssignIntegrationTests`: assigning to an inactive or unknown account re-renders with `orders.error.assigneeUnavailable`, and the assignee is unchanged — fb8b6c2
- [x] 2.5 `OrderAssignIntegrationTests`: a `COMPLETED` or `CANCELLED` order is rejected with `orders.error.notAssignable` — fb8b6c2
- [x] 2.6 `OrderAssignIntegrationTests`: every line's `reserved_quantity`/`picked_quantity` and the order's `priority`/`required_date`/`taken_at` are identical before and after reassignment — fb8b6c2
- [x] 2.7 `OrderAssignIntegrationTests`: a TECHNICIAN posting to `/orders/{id}/assign` gets 403 (wrong-role lesson) — fb8b6c2

#### Manual

- [x] 2.8 On a taken order's detail page, the reassign card is visible while the priority/date card is not, and saving a new technician updates the "Technik:" entry — fb8b6c2

### Phase 3: Per-viewer `/picking` list, manager filter and FR-022

#### Automated

- [x] 3.1 `./mvnw verify` passes, including the repaired existing suites — a399885
- [x] 3.2 New `PickingVisibilityIntegrationTests`: technician A sees their order and does not see technician B's order, an unassigned order or a manager-assigned order — a399885
- [x] 3.3 `PickingVisibilityIntegrationTests`: the manager without a filter sees all four orders — a399885
- [x] 3.4 `PickingVisibilityIntegrationTests`: the manager with `assignee=<A>` sees only A's order, and with `assignee=none` sees the unassigned order and the order of a **deactivated** technician C, and nothing else — a399885
- [x] 3.5 `PickingVisibilityIntegrationTests`: the manager with `assignee=abc` or an unknown id gets 200 with all orders — a399885
- [x] 3.6 `PickingVisibilityIntegrationTests`: after A's order is reassigned to B via `POST /orders/{id}/assign` (taken order), it leaves A's `/picking` and appears on B's — a399885
- [x] 3.7 `TemplateConventionTests` passes for the changed `picking-list.html` — a399885

#### Manual

- [x] 3.8 Logged in as a technician, `/picking` lists only that technician's orders, with no filter form and no Technik column — a399885
- [x] 3.9 Logged in as the manager, the filter switches between Wszystkie, Nieprzypisane and each technician, and a deactivated technician's order appears under Nieprzypisane marked "(nieaktywny)" — a399885
- [x] 3.10 PRD FR-022 reads correctly next to FR-010 and FR-012 — a399885
