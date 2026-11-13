# Stock per location — plan brief

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
