# Lista zakupów i eksport do CSV — Plan Brief

> Full plan: `context/changes/shopping-list-and-csv-export/plan.md`

## What & Why

A manager-only screen (`/purchasing`) showing one row per part that's short across all open orders — total missing quantity plus which orders it blocks — with a CSV export of the same data. This closes the PRD's Primary success criterion (FR-015, FR-016): it's the point where the manager finally gets "what to order" in a form they can carry out of the application.

## Starting Point

`OrderLine.requiredQuantity - reservedQuantity` already exists and is shown per-order on `/orders/{id}`, but nowhere aggregated across orders. No CSV writing exists anywhere in the codebase yet. The PRD already fixes the aggregation shape (one row per part, summed across all open orders, listing blocking orders) — this plan only had to settle the presentation and CSV-format decisions the PRD left open.

## Desired End State

Opening `/purchasing` as manager shows every part currently blocking at least one open order, with its total shortage and the specific orders (project, required date, that order's own missing amount) waiting on it, each linked to its detail page. A CSV download of the same data is one click away and opens cleanly in Polish-locale Excel.

## Key Decisions Made

| Decision | Choice | Why (1 sentence) | Source |
| --- | --- | --- | --- |
| CSV delimiter & encoding | Semicolon + UTF-8 with BOM | Polish-locale Excel uses `,` as the decimal separator, so comma-delimited CSV mis-splits; the BOM makes Excel auto-detect UTF-8 so Polish diacritics render correctly on double-click. | Plan |
| CSV content scope | Include a blocked-orders column (not just part + quantity) | Keeps the exported file self-contained and matching the on-screen view, satisfying FR-015's traceability intent; safe because the PRD rules out supplier-facing integration entirely. | Plan |
| On-screen blocked-order display | Project name + required date + that order's missing quantity, linked to `/orders/{id}` | Gives the manager enough to judge urgency at a glance, matching the existing `orders-list.html` pattern of linking rows to order detail. | Plan |
| Deactivated parts with open shortages | Shown, flagged `(nieaktywna)`, never hidden | The underlying order is still open and genuinely blocked; hiding it would silently lose a real shortage. | Plan |
| Row ordering | Alphabetical by part name | Matches `PartRepository.search`'s existing `ORDER BY p.name` — the only list-ordering convention already in the codebase. | Plan |
| Empty-list CSV export | Always available, header-only file when there are no shortages | No conditional UI logic; matches how no other action in the app is ever disabled. | Plan |
| Per-row stock context | Missing quantity only — no stan/zarezerwowane/dostępne columns | Keeps the list focused on "what to buy"; that context is one click away at `/parts`. | Plan |

## Scope

**In scope:**
- `GET /purchasing` — aggregated shopping-list screen, manager-only
- `GET /purchasing/export` — CSV download of the same data
- New `OrderLineRepository.findOpenLinesWithShortage()` query
- Widening `ReservationAllocator`'s visibility to reuse its allocation-order comparator for sorting blocked orders within a row
- Dashboard nav link

**Out of scope:**
- CSV *import* (FR-017 / roadmap item S-12, a separate change)
- Supplier code, pricing, or ordering integration (PRD Non-Goals)
- Stock/reserved/available columns on the shopping list
- Urgency-based sorting of the shopping-list rows themselves
- Hiding the export link, or excluding deactivated parts, when the list/a row is empty or inactive

## Architecture / Approach

New `pl.regavio.stockahead.purchasing` package, mirroring the existing domain-per-package structure (`account`, `orders`, `parts`, `projects`). One repository query fetches every shortage line in one round trip; `ShoppingListModel` groups them by part (same shape as the existing `OrderDetailModel`); `ShoppingListController` exposes the HTML screen and the CSV route, both manager-only; a small dependency-free `ShoppingListCsvWriter` handles RFC4180-style field escaping for the semicolon-delimited, BOM-prefixed output.

## Phases at a Glance

| Phase | What it delivers | Key risk |
| --- | --- | --- |
| 1. Aggregation query & read model | Repository query + grouping logic, no UI yet | Widening `ReservationAllocator`'s visibility must not change its behavior |
| 2. Shopping-list screen | `/purchasing` HTML view + nav link | Blocked-orders display getting too dense to scan |
| 3. CSV export | `/purchasing/export` route + CSV writer | Getting the Polish-character/Excel encoding wrong |
| 4. Tests | Full integration coverage against real Postgres | Missing the cross-order aggregation or role-gating case |

**Prerequisites:** S-04 (`order-reserves-parts`) is already `done` — reservations and missing quantities already exist on every `OrderLine`.
**Estimated effort:** ~1 session across 4 phases; small, pattern-following feature with no new architecture.

## Open Risks & Assumptions

- Assumes Excel with Polish regional settings is the actual consumer of the CSV (per the PRD's own flagged concern); if the real consumer is a different tool, the delimiter/BOM choice may need revisiting.
- A part can be deactivated while still blocking an open order; this plan surfaces that (flagged) rather than preventing deactivation — preventing it was not in scope for this change.

## Success Criteria (Summary)

- A manager can see, in one place, every part short across all open orders and exactly which orders that blocks.
- A manager can download that same list as a CSV that opens correctly, with correct Polish characters, in Excel.
- A technician cannot reach either route.
