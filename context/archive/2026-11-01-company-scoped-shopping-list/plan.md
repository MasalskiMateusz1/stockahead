# Company-Scoped Shopping List Implementation Plan

## Overview

Roadmap M-2 S-06: a manager sees, and exports to CSV, a shopping list built only from their own company's shortages and blocked orders. Today both `/purchasing` and `/purchasing/export` aggregate open order lines of every company on the instance.

## Current State Analysis

- `OrderLineRepository.findOpenLinesWithShortage()` (`src/main/java/pl/regavio/stockahead/orders/OrderLineRepository.java:91-94`) selects every OPEN order line with `requiredQuantity > reservedQuantity + pickedQuantity`, with no company filter. It is the only unscoped read on this page.
- `ShoppingListModel.buildRows()` (`src/main/java/pl/regavio/stockahead/purchasing/ShoppingListModel.java:57-65`) is the single read path behind both `render(Model)` (the page) and `rowsForExport()` (the CSV). It groups by part id and sorts by part name.
- `ShoppingListController` (`src/main/java/pl/regavio/stockahead/purchasing/ShoppingListController.java:31-46`) is manager-only (`@PreAuthorize("hasRole('MANAGER')")`) and does not take the principal yet.
- The data model is already scoped: `order_lines.company_id` was added in V17 (`src/main/resources/db/migration/V17__company_scoped_orders.sql:26-31`), with composite FKs tying the line's company to its order's and its part's company. A line's `companyId` is therefore the company of its order, project and part.
- `ShoppingListIntegrationTests` (`src/test/java/pl/regavio/stockahead/purchasing/ShoppingListIntegrationTests.java`) covers aggregation, exclusions, the inactive flag, the CSV format and the technician 403, all within one company.

## Desired End State

Manager of company A opens `/purchasing` or downloads `/purchasing/export` and sees only rows for A's parts with A's blocked orders and quantities. A shortage in company B on a part with the same name never adds to A's row, never appears as a blocked order in A's list and never shows up in A's CSV, and the other way round. A company with no shortages sees the empty state and a header-only CSV even when another company has shortages. The CSV columns and format are unchanged.

### Key Discoveries:

- Scoped siblings in the same repository filter on `ol.companyId = :companyId` (`OrderLineRepository.java:30`, `:66`, `:79`). The shortage query follows the same form.
- The company comes from `@AuthenticationPrincipal CompanyUser principal` → `principal.companyId()`, as in `PickingController.detail` (`src/main/java/pl/regavio/stockahead/orders/PickingController.java:158-159`). AGENTS.md mandates this source.
- Two-company tests seed with `CompanyFixtures` (company, account, part, project, bomLine). It has no order helper, so orders and lines are seeded via JDBC as in `ShoppingListIntegrationTests` (`:151-165`).
- These routes take no record ids, so the "guessed id → 404" guardrail does not apply. The guardrail here is "sees only its own rows".

## What We're NOT Doing

- No migration: `order_lines` already carries `company_id` with composite FKs.
- No change to CSV columns, header, file name, encoding or the one-row-per-(part, blocking order) layout (prd-v2 § Constraints).
- No change to `reservedQuantityForPart(partId)` (used by `StockCorrectionController`). Its part id is already company-scoped by S-05, and the composite FK keeps that part's lines in the same company.
- No operator handling: the existing `hasRole('MANAGER')` gate already refuses a non-manager. The operator's 403 per warehouse route belongs to S-08.
- No JOIN FETCH or performance change beyond adding the filter.

## Implementation Approach

Pass the company id explicitly from the controller through the model into the query, the same "explicit company parameter on every query" style used since S-01. One phase: the change is a single read path, and the two-company test proves it end to end.

## Phase 1: Scoped shortage query and two-company tests

### Overview

Scope the one shortage query by company, thread the principal's company into it, and add the two-company guardrail test.

### Changes Required:

#### 1. Shortage query

**File**: `src/main/java/pl/regavio/stockahead/orders/OrderLineRepository.java`

**Intent**: Restrict the shortage read to one company's lines, so no other company's line is ever loaded. Add a Javadoc in the style of the scoped siblings ("other companies' lines are never read").

**Contract**: `List<OrderLine> findOpenLinesWithShortage(@Param("companyId") Long companyId)`; JPQL gains `AND ol.companyId = :companyId`. The unscoped no-argument variant is removed rather than kept alongside.

#### 2. Read model

**File**: `src/main/java/pl/regavio/stockahead/purchasing/ShoppingListModel.java`

**Intent**: Both entry points build rows for one company only. Update the class Javadoc to say "every open order line of company `companyId` with a shortage".

**Contract**: `String render(Model model, Long companyId)`, `List<ShoppingListRow> rowsForExport(Long companyId)`, `buildRows(Long companyId)`. Grouping, sorting, the view records and the view name are unchanged.

#### 3. Controller

**File**: `src/main/java/pl/regavio/stockahead/purchasing/ShoppingListController.java`

**Intent**: Take the company from the logged-in principal and pass it to the model on both routes.

**Contract**: `list(@AuthenticationPrincipal CompanyUser principal, Model model)` and `export(@AuthenticationPrincipal CompanyUser principal, Locale locale)` pass `principal.companyId()`. Routes, `@PreAuthorize`, headers and the CSV header are unchanged.

#### 4. Two-company isolation tests

**File**: `src/test/java/pl/regavio/stockahead/purchasing/PurchasingCompanyIsolationIntegrationTests.java` (new)

**Intent**: Prove the guardrail through the real HTTP routes and form login, against Testcontainers Postgres, in the shape of the other `*CompanyIsolationIntegrationTests` (`@Import({ TestcontainersConfiguration.class, CompanyFixtures.class })`, own email domain, delete-all cleanup before and after).

**Contract**: Cases:
- A and B each have a part with the same name and an open order short on it, with different missing quantities and project names. A's `/purchasing` shows only A's missing quantity and A's blocked order (no B project name, no B order id). A's CSV has only A's rows. The same holds from B's side.
- B has no shortage while A does: B's page shows the empty state ("Brak braków.") and B's CSV is header-only.
- A part existing only in B with a shortage never shows up in A's page or CSV.

The existing `ShoppingListIntegrationTests` stays as-is and must keep passing, including the technician 403 on both routes.

### Success Criteria:

#### Automated Verification:

- Full build and test suite pass: `./mvnw verify`
- `PurchasingCompanyIsolationIntegrationTests` passes, covering the same-name shortage in both companies, the empty-state company and the part that exists only in B
- Existing `ShoppingListIntegrationTests` pass unchanged
- No caller of the no-argument `findOpenLinesWithShortage()` remains: `grep -rn "findOpenLinesWithShortage()" src` returns nothing

#### Manual Verification:

- With two companies seeded in `./mvnw spring-boot:test-run`, each manager's `/purchasing` page lists only their own shortages and links only to their own orders
- Each manager's CSV export opens in Excel with the unchanged columns and only their own rows

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful.

---

## Testing Strategy

### Unit Tests:

- None new. `ShoppingListCsvWriter` is unchanged and already covered through the export tests.

### Integration Tests:

- `PurchasingCompanyIsolationIntegrationTests` (new): the two-company cases listed in Phase 1 §4, on both the page and the CSV.
- `ShoppingListIntegrationTests` (existing): single-company aggregation, exclusions, inactive flag, CSV format, technician 403.

### Manual Testing Steps:

1. Start `./mvnw spring-boot:test-run` and set up two companies with a manager each (seed via SQL until registration lands in S-09).
2. In both companies, create a part with the same name and an order that leaves it short.
3. Open `/purchasing` as each manager and check that only their own row and blocked order appear.
4. Export the CSV as each manager and check that it contains only their own rows.

## Performance Considerations

The added filter narrows the read. Nothing else changes.

## Migration Notes

None. No schema change.

## References

- Roadmap: `context/foundation/roadmap.md` § S-06
- Scoped sibling queries: `src/main/java/pl/regavio/stockahead/orders/OrderLineRepository.java:30`, `:79`
- Principal usage: `src/main/java/pl/regavio/stockahead/orders/PickingController.java:158-159`
- Isolation test shape: `src/test/java/pl/regavio/stockahead/parts/StockMovementsCompanyIsolationIntegrationTests.java`
- Previous slice: `context/archive/2026-11-01-company-scoped-stock-movements/plan.md`

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles. See `references/progress-format.md`.

### Phase 1: Scoped shortage query and two-company tests

#### Automated

- [x] 1.1 Full build and test suite pass: `./mvnw verify` — 3a01adc
- [x] 1.2 `PurchasingCompanyIsolationIntegrationTests` passes, covering the same-name shortage in both companies, the empty-state company and the part that exists only in B — 3a01adc
- [x] 1.3 Existing `ShoppingListIntegrationTests` pass unchanged — 3a01adc
- [x] 1.4 No caller of the no-argument `findOpenLinesWithShortage()` remains: `grep -rn "findOpenLinesWithShortage()" src` returns nothing — 3a01adc

#### Manual

- [x] 1.5 With two companies seeded in `./mvnw spring-boot:test-run`, each manager's `/purchasing` page lists only their own shortages and links only to their own orders — 3a01adc
- [x] 1.6 Each manager's CSV export opens in Excel with the unchanged columns and only their own rows — 3a01adc
