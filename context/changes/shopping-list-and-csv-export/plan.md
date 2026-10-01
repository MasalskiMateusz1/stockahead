# Lista zakupów i eksport do CSV Implementation Plan

## Overview

A manager-only screen at `/purchasing` showing one aggregated row per part that is short across all `OPEN` orders (total missing quantity, summed from every open order line where `requiredQuantity > reservedQuantity`), together with the list of orders that shortage blocks. A linked `/purchasing/export` route streams the same aggregated data as a downloadable CSV file. This closes the PRD's Primary success criterion (FR-015, FR-016): the manager finally gets "what to order" in a form that leaves the application.

## Current State Analysis

- `OrderLine` already carries `requiredQuantity` and `reservedQuantity`; `missingQuantity = requiredQuantity - reservedQuantity` is already computed and displayed per-order in `OrderDetailModel` (src/main/java/pl/regavio/stockahead/orders/OrderDetailModel.java:52-53), but nowhere aggregated across orders.
- `OrderLineRepository` (src/main/java/pl/regavio/stockahead/orders/OrderLineRepository.java) already has two JOIN FETCH-style queries scoped to `OrderStatus.OPEN` (`reservedQuantitiesByPart`, `findOpenLinesForParts`) — the new aggregation query follows the same shape.
- `ReservationAllocator.ALLOCATION_ORDER` (src/main/java/pl/regavio/stockahead/orders/ReservationAllocator.java:34-38) is the PRD's canonical priority → required-date → created-at/id ordering, already reused by `OrderController.list()`. It is currently package-private (the class itself has no `public` modifier) — this plan reuses it from a new `purchasing` package, so it must be widened to `public`, a visibility-only change with no behavior change.
- No CSV reading or writing exists anywhere in the codebase yet (FR-017's CSV import is a separate, not-yet-built change, `parts-csv-import` / S-12). No CSV library is a declared dependency in `pom.xml`.
- Manager-only routes are gated with `@PreAuthorize("hasRole('MANAGER')")` per-method (`SecurityConfig` only enforces authentication at the URL level) — every existing manager-only controller (`OrderController`, `PartController`'s write actions) follows this, and `[[lessons.md]]`'s rule requires a 403 test for every such route.
- `Part.active` (src/main/java/pl/regavio/stockahead/parts/Part.java:30-31) already exists; nothing currently prevents deactivating a part that is still referenced by an open order's shortage.
- Navigation is a flat list of links in `dashboard.html`, gated per-link with `sec:authorize="hasRole('MANAGER')"` (src/main/resources/templates/dashboard.html:14).
- `PartRepository.search` (src/main/java/pl/regavio/stockahead/parts/PartRepository.java:23) establishes the only existing list-ordering convention in the codebase: `ORDER BY p.name`.
- Per `[[AGENTS.md]]`, all tests run against real Postgres via Testcontainers (`OrderListAndDetailIntegrationTests` is the closest precedent: JdbcTemplate fixtures, MockMvc, a manager/technician session pair, explicit 403 role-gate test).

### Key Discoveries:

- The PRD's Business Logic section (`context/foundation/prd.md:118`) already fixes the aggregation shape: "jedna zagregowana lista zakupów (jedna pozycja na część, suma braków ze wszystkich otwartych zleceń, wraz ze zleceniami, które dany brak blokuje)" — one row per part, summed, with its blocking orders. No ambiguity to resolve there.
- `OrderLine` has `UNIQUE(order_id, part_id)` (per `ReservationAllocator`'s own Javadoc), so grouping shortage lines by part id and listing the orders they come from needs no dedup logic — each line already belongs to a distinct order.
- Roadmap item S-05 (`context/foundation/roadmap.md:158-159`) names the CSV-format question as its one open unknown; this plan resolves it (semicolon delimiter, UTF-8 with BOM).

## Desired End State

A manager can open `/purchasing`, see every part currently blocking at least one open order — with the total missing quantity and a list of which orders (project, required date, per-order missing quantity) each shortage blocks, linked to that order's detail page — and click through to download a CSV of the same data. The list and export both update immediately on every event that already recomputes reservations (order creation/priority change, delivery, cancellation, completion) because they read live from `OrderLine`, not from a cached snapshot.

### Key Discoveries:

- Reuse `ReservationAllocator.ALLOCATION_ORDER` (widened to `public`) to sort the blocked-orders list inside each part's row — the same order the allocator itself used to decide who got the stock, so "who's most urgent" on the shopping list matches "who gets parts first" everywhere else in the app.
- Reuse the existing `Part.active` flag to flag (not hide) shortages against deactivated parts.

## What We're NOT Doing

- No supplier integration, pricing, or ordering from the app — CSV export is the end of the purchasing flow (PRD Non-Goals).
- No CSV *import* — that's FR-017 / roadmap item S-12 (`parts-csv-import`), a separate change.
- No supplier code column — PRD's Socrates note on FR-016 explicitly considered and declined this.
- No stock/reserved/available columns on the shopping list — that context lives one click away at `/parts`; the shopping list's job is "what to buy, how much."
- No urgency-based (priority/deadline) sort of the shopping-list rows themselves — alphabetical by part name, matching the only existing list-ordering convention in the codebase. (The *blocked orders within* a row are still ordered by priority/deadline via `ALLOCATION_ORDER`.)
- No hiding or disabling the CSV export link when the list is empty — it always produces a (header-only, when empty) file.
- No excluding deactivated parts from the list/export — a shortage against an open order is real regardless of the part's active flag; it is flagged, not hidden.
- No multi-warehouse/location columns — out of scope per PRD Non-Goals (single warehouse).

## Implementation Approach

Add a new `pl.regavio.stockahead.purchasing` package (matching the PRD's "Zakupy" domain, alongside the existing `account`/`orders`/`parts`/`projects` packages). One new repository query (`OrderLineRepository.findOpenLinesWithShortage`) fetches every shortage line in one round trip; a package-private `ShoppingListModel` (same shape as `OrderDetailModel`) groups those lines by part inside a read-only transaction and builds the row view; `ShoppingListController` exposes it at `GET /purchasing` (HTML) and `GET /purchasing/export` (CSV), both manager-only. A small, dependency-free `ShoppingListCsvWriter` renders the same row data as semicolon-delimited, UTF-8-with-BOM CSV with RFC4180-style field escaping (no new library — the codebase has none, and the escaping logic is a handful of lines).

## Phase 1: Aggregation query & read model

### Overview

Add the repository query and the model that groups open shortage lines by part, producing the row shape both the screen and the CSV export will consume.

### Changes Required:

#### 1. Widen `ReservationAllocator`'s visibility

**File**: `src/main/java/pl/regavio/stockahead/orders/ReservationAllocator.java`

**Intent**: Let the new `purchasing` package reuse the canonical priority → required-date → created-at/id ordering to sort blocked orders within a shopping-list row, instead of duplicating the comparator and risking drift if the allocation rule ever changes.

**Contract**: Change `class ReservationAllocator` to `public class ReservationAllocator`, and its `static final Comparator<Order> ALLOCATION_ORDER` field to `public static final`. No other change — the class's behavior, constructor visibility, and `reallocateForParts` method stay exactly as they are (package-private is fine for the method; only the class and the one field needed cross-package).

#### 2. Add the shortage-lines query

**File**: `src/main/java/pl/regavio/stockahead/orders/OrderLineRepository.java`

**Intent**: Fetch every `OrderLine` that represents a real shortage (open order, `requiredQuantity > reservedQuantity`) in one round trip, with its order/project/part already loaded, matching the file's existing `JOIN FETCH` query style.

**Contract**: New method `List<OrderLine> findOpenLinesWithShortage()`, no parameters:
```java
@Query("SELECT ol FROM OrderLine ol JOIN FETCH ol.order o JOIN FETCH o.project JOIN FETCH ol.part "
        + "WHERE o.status = pl.regavio.stockahead.orders.OrderStatus.OPEN "
        + "AND ol.requiredQuantity > ol.reservedQuantity")
List<OrderLine> findOpenLinesWithShortage();
```

#### 3. New package and read model

**File**: `src/main/java/pl/regavio/stockahead/purchasing/ShoppingListModel.java` (new)

**Intent**: Group shortage lines by part into one aggregated row each (total missing quantity, active flag, blocked-orders list sorted by `ReservationAllocator.ALLOCATION_ORDER`), sorted by part name — the single read path both the screen and the CSV export call into, inside its own read-only transaction, same shape as `OrderDetailModel`.

**Contract**: A package-private `@Component` with two entry points — `String render(Model model)` (populates `model`'s `rows` attribute and returns the `purchasing-list` view name) and `List<ShoppingListRow> rowsForExport()` — both backed by one private `buildRows()` that does the grouping. Two package-visible records:
```java
record ShoppingListRow(String partName, boolean partActive, int missingQuantity,
        List<BlockedOrderView> blockedOrders) {
}

record BlockedOrderView(Long orderId, String projectName, LocalDate requiredDate, int missingQuantity) {
}
```

### Success Criteria:

#### Automated Verification:

- `./mvnw compile` succeeds with the widened visibility and new query.
- `./mvnw -Dtest=ReservationAllocatorTests,OrderListAndDetailIntegrationTests,OrderCreationIntegrationTests test` still passes (no regression from widening `ReservationAllocator`'s visibility).

---

## Phase 2: Shopping-list screen

### Overview

Expose `ShoppingListModel.render` at a manager-only route and give it a Thymeleaf view, reachable from the dashboard.

### Changes Required:

#### 1. Controller route

**File**: `src/main/java/pl/regavio/stockahead/purchasing/ShoppingListController.java` (new)

**Intent**: `GET /purchasing`, manager-only, delegates straight to `ShoppingListModel.render`, matching `OrderController.detail`'s one-line delegation to `OrderDetailModel.render`.

**Contract**: `@Controller` with `@GetMapping("/purchasing")` + `@PreAuthorize("hasRole('MANAGER')")`.

#### 2. View template

**File**: `src/main/resources/templates/purchasing-list.html` (new)

**Intent**: Render the aggregated rows as a table (part name, flagged `(nieaktywna)` when `!partActive`, total missing quantity, and the blocked orders — each linked to `/orders/{id}`, showing project name, required date, and that order's own missing quantity); show "Brak braków." when the list is empty; always show the CSV export link regardless of emptiness. Follows `orders-list.html`'s plain-table structure (no `sec:authorize` needed in the template itself — the whole route is manager-only, same as `orders-detail.html`'s comment on `OrderDetailModel`).

**Contract**: View name `purchasing-list`, model attribute `rows: List<ShoppingListRow>`. Blocked orders within a `<td>` are joined with `"; "` for on-screen readability (each one still an individual `<a>` link to `@{/orders/{id}(id=${blocked.orderId})}`).

#### 3. Dashboard navigation

**File**: `src/main/resources/templates/dashboard.html`

**Intent**: Add a manager-only link to the new screen, next to the existing "Zlecenia" link.

**Contract**: `<p sec:authorize="hasRole('MANAGER')"><a th:href="@{/purchasing}">Lista zakupów</a></p>`.

### Success Criteria:

#### Automated Verification:

- `./mvnw compile` succeeds.
- `./mvnw -Dtest=OrderListAndDetailIntegrationTests,PartsCatalogIntegrationTests test` still passes (no regression).

#### Manual Verification:

- Logged in as manager, `/purchasing` renders the table with correct part names, missing quantities, and blocked-order links that navigate to the right order detail page.
- A shortage against a deactivated part shows the `(nieaktywna)` flag.
- Logged in as technician, the "Lista zakupów" dashboard link is not shown.

---

## Phase 3: CSV export

### Overview

Add the CSV-rendering route and writer, reusing Phase 1's aggregated rows.

### Changes Required:

#### 1. CSV writer

**File**: `src/main/java/pl/regavio/stockahead/purchasing/ShoppingListCsvWriter.java` (new)

**Intent**: Render `List<ShoppingListRow>` as semicolon-delimited CSV bytes, UTF-8 with a leading BOM (Polish-locale Excel compatibility, per the roadmap's flagged open question for S-05), with every field RFC4180-escaped (quote-wrap and double any embedded quote when a field contains the delimiter, a quote, or a line break) so that a part name or project name containing `;`/`"`/newlines can never corrupt the row structure.

**Contract**: A stateless utility, e.g. `static byte[] write(List<ShoppingListRow> rows)`. Header row `Część;Brakująca ilość;Zablokowane zlecenia`. Each data row: part name (with `" (nieaktywna)"` suffix when `!partActive`), missing quantity, and the blocked orders joined with `"; "` into one field, each formatted as `<project name> (#<orderId>) — termin <requiredDate>, brakuje <missingQuantity> szt.`. Rows joined with `\r\n`. Because this is the one genuinely non-obvious piece of logic in the plan (CSV escaping is easy to get subtly wrong), the escaping helper:
```java
private static String escapeField(String field) {
    boolean needsQuoting = field.indexOf(';') >= 0 || field.indexOf('"') >= 0
            || field.indexOf('\r') >= 0 || field.indexOf('\n') >= 0;
    if (!needsQuoting) {
        return field;
    }
    return '"' + field.replace("\"", "\"\"") + '"';
}
```

**Addendum (2026-10-01, post-implementation)**: The shipped writer deviates from the contract above — deliberately, per commit `3819f91`'s own message: "requested during manual verification for easier order-by-order scanning." Recorded here via `/10x-impl-review` (F2) so the plan matches what actually ships:

- `write(List<ShoppingListRow> rows, String header)` takes the header as a parameter (see below) rather than hardcoding it.
- **One row per (part, blocking order) pair**, not one row per part. Header is 4 columns: `Część;Zlecenie;Termin;Brakująca ilość`. Each row: part name (with `(nieaktywna)` suffix when inactive), `<project name> (#<orderId>)`, `requiredDate`, that order's own `missingQuantity` — as separate columns instead of one joined prose field.
- The `/purchasing` HTML screen (Phase 2) is **unaffected** — it still renders one row per part, orders joined within the cell, exactly as originally planned. Only the CSV export's row shape changed.
- The header text is sourced via `MessageSource`/`Locale` in `ShoppingListController.csvHeader()` (new `purchasing.csv.header.*` keys in `messages.properties`) rather than hardcoded in the writer — matching this codebase's existing i18n convention, not a plan deviation in spirit, just not originally specified.
- `escapeField` additionally neutralizes CSV/Excel formula injection (a field starting with `=+-@` gets a protective leading `'`) — added during `/10x-impl-review` triage (F1), not part of the original Phase 3 contract either.

#### 2. Export route

**File**: `src/main/java/pl/regavio/stockahead/purchasing/ShoppingListController.java`

**Intent**: `GET /purchasing/export`, manager-only, streams `ShoppingListCsvWriter.write(shoppingListModel.rowsForExport())` as a file download. Always returns 200 with a valid (header-only when there are no shortages) CSV body — no conditional based on row count.

**Contract**: `@GetMapping("/purchasing/export")` + `@PreAuthorize("hasRole('MANAGER')")`, returning `ResponseEntity<byte[]>` with `Content-Type: text/csv;charset=UTF-8` and `Content-Disposition: attachment; filename="lista-zakupow.csv"`.

### Success Criteria:

#### Automated Verification:

- `./mvnw compile` succeeds.

#### Manual Verification:

- Downloading `/purchasing/export` from a browser and opening it in Excel (Polish regional settings) splits columns on the semicolon and renders Polish diacritics correctly.
- The downloaded file's name is `lista-zakupow.csv`.

---

## Phase 4: Tests

### Overview

Cover the aggregation correctness, the two new routes, and the CSV format against real Postgres, following `OrderListAndDetailIntegrationTests`'s fixture and session conventions.

### Changes Required:

#### 1. Integration tests

**File**: `src/test/java/pl/regavio/stockahead/purchasing/ShoppingListIntegrationTests.java` (new)

**Intent**: Prove the aggregation and both routes against seeded data, mirroring `OrderListAndDetailIntegrationTests`'s `@Import(TestcontainersConfiguration.class)` + JdbcTemplate-seeded-fixtures + MockMvc + manager/technician session pattern.

**Contract**: Test methods, each a distinct scenario:
- Two open orders both short the same part → `/purchasing` shows one row with the summed missing quantity and both orders listed as blocking it.
- A fully-reserved order line (`requiredQuantity == reservedQuantity`) contributes no row for its part.
- A `CANCELLED` or `COMPLETED` order's shortage is excluded from the list.
- A shortage against a deactivated part (`active = false`) still appears, flagged.
- No shortages → `/purchasing` shows the empty-state message, and `/purchasing/export` still returns 200 with a header-only CSV body.
- `/purchasing/export` response has `Content-Type` starting `text/csv`, `Content-Disposition` naming `lista-zakupow.csv`, body bytes start with the UTF-8 BOM (`0xEF 0xBB 0xBF`), and a part name containing a semicolon round-trips quoted.
- Technician gets 403 on both `GET /purchasing` and `GET /purchasing/export`.

### Success Criteria:

#### Automated Verification:

- `./mvnw verify` passes, including the new `ShoppingListIntegrationTests` and the full existing suite (no regressions).

---

## Testing Strategy

### Integration Tests:

- All scenarios listed in Phase 4, against real Postgres via Testcontainers per `[[AGENTS.md]]` (no mocked database, no H2).

### Manual Testing Steps:

1. As manager: create two open orders that both need more of the same part than is in stock; confirm `/purchasing` shows one combined row listing both orders.
2. Deactivate that part; confirm the row stays, now flagged `(nieaktywna)`.
3. Download the CSV and open it in Excel with Polish regional settings; confirm columns and diacritics render correctly.
4. As technician: confirm the dashboard has no "Lista zakupów" link and both routes 403.

## References

- PRD: `context/foundation/prd.md` §Zakupy (FR-015, FR-016), §Business Logic.
- Roadmap: `context/foundation/roadmap.md` S-05 (`shopping-list-and-csv-export`).
- Closest existing pattern: `src/main/java/pl/regavio/stockahead/orders/OrderDetailModel.java`, `src/main/java/pl/regavio/stockahead/orders/OrderController.java`.
- Allocation ordering reused: `src/main/java/pl/regavio/stockahead/orders/ReservationAllocator.java:34-38`.
- Test fixture pattern reused: `src/test/java/pl/regavio/stockahead/orders/OrderListAndDetailIntegrationTests.java`.

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles. See `references/progress-format.md`.

### Phase 1: Aggregation query & read model

#### Automated

- [x] 1.1 `./mvnw compile` succeeds with the widened visibility and new query — de30605
- [x] 1.2 `./mvnw -Dtest=ReservationAllocatorTests,OrderListAndDetailIntegrationTests,OrderCreationIntegrationTests test` still passes — de30605

### Phase 2: Shopping-list screen

#### Automated

- [x] 2.1 `./mvnw compile` succeeds — c420899
- [x] 2.2 `./mvnw -Dtest=OrderListAndDetailIntegrationTests,PartsCatalogIntegrationTests test` still passes — c420899

#### Manual

- [x] 2.3 `/purchasing` renders correct part names, missing quantities, and blocked-order links as manager — c420899
- [x] 2.4 Deactivated-part shortage shows the `(nieaktywna)` flag — c420899
- [x] 2.5 Technician does not see the "Lista zakupów" dashboard link — c420899

### Phase 3: CSV export

#### Automated

- [x] 3.1 `./mvnw compile` succeeds — 3819f91

#### Manual

- [x] 3.2 CSV opens in Excel (Polish regional settings) with correct column split and diacritics — 3819f91
- [x] 3.3 Downloaded file is named `lista-zakupow.csv` — 3819f91

### Phase 4: Tests

#### Automated

- [x] 4.1 `./mvnw verify` passes, including `ShoppingListIntegrationTests` and the full existing suite — 826df8f
