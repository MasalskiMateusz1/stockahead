# Zlecenie produkcji rezerwuje części — Plan Brief

> Full plan: `context/changes/order-reserves-parts/plan.md`

## What & Why

A manager orders production of N units of a project; the system reserves available parts against the project's BOM by priority → deadline → age, and shows reserved/missing quantities per part. This is the roadmap's north-star slice (S-04) — the first piece that runs the PRD's core allocation rule end-to-end, proving the product's central hypothesis works.

## Starting Point

`Part` and `Project`/`BomLine` catalogs already exist and are populated (S-02/S-03, both done). The parts catalog page already has "Zarezerwowane"/"Dostępne" columns, but they're hardcoded to `0`/full stock — this slice is what was meant to fill them in. No `Order` concept, no row-locking pattern, and no allocation logic exist anywhere in the codebase yet.

## Desired End State

A manager fills in a new-order form (project, quantity, priority, required date) and immediately sees, per BOM part, how much got reserved and how much is missing. An order list shows all open orders in allocation order. The parts catalog shows real reserved/available numbers reflecting every open order. Two managers ordering at once can never together over-reserve a part's stock.

## Key Decisions Made

| Decision | Choice | Why (1 sentence) |
| --- | --- | --- |
| Recompute strategy | Full recompute of all open orders on every event | Matches the PRD's own wording, and makes priority preemption fall out for free instead of needing explicit "steal reservation" logic |
| Concurrency guarantee | Lock every `Part` row (`SELECT … FOR UPDATE ORDER BY id`) up front, once per recompute transaction | Simple, provably deadlock-free, appropriate at this project's small/low-QPS scale; satisfies AGENTS.md's explicit pessimistic-locking requirement |
| Priority levels | Fixed 3-value enum: Niski / Normalny / Wysoki | Matches FR-010's own example labels exactly |
| Order status field | Add now (`OPEN`/`CANCELLED`/`COMPLETED`), only `OPEN` ever written here | Avoids a schema migration later in S-08/S-09 just to add it |
| BOM-edit interaction | Out of scope — editing a project's BOM does not recompute its open orders' reservations | Keeps this slice's diff focused; each order's required quantities are snapshotted at creation, decoupling it from later BOM edits |
| Inactive project | Order creation rejected for an inactive project | Mirrors the existing pattern of excluding inactive parts from new BOM lines |
| Inactive part in BOM | Still eligible for reservation | Matches existing precedent: a BOM line on a deactivated part keeps working, "active" only gates the catalog/new-BOM-line UI |
| Required date | Required, must be today or later | FR-010 calls it a required term; a past deadline breaks the earlier-deadline-wins tie-break |
| UI scope | Three screens: create, list, detail | Matches the existing list+detail pattern (parts/projects) and is needed to review a past order's reservations, not just the one just created |

## Scope

**In scope:**
- `Order`/`OrderLine` entities, migration, repositories
- `ReservationAllocator`: full-recompute allocation engine with pessimistic locking
- Order creation form + validation (manager-only)
- Order list + detail screens (manager-only)
- Wiring real reserved/available numbers into the existing parts catalog page
- Concurrency invariant test (first of its kind in this codebase)

**Out of scope:**
- Taken-order protection / already-picked-part exclusions (S-07)
- Order priority/deadline change or cancellation (S-09)
- Order completion/confirmation (S-08)
- Recomputing reservations on BOM edits (documented gap, future change)
- Shopping list / CSV export (S-05)
- Picking list with part locations (S-07)

## Architecture / Approach

New `orders` package: `Order`/`OrderLine` entities, `OrderController` (creation + list/detail routes), and `ReservationAllocator` — a `@Component` (following the existing `ProjectDetailModel` precedent for shared logic, since this codebase has no service layer) that, inside one transaction, locks every part row, loads all open orders sorted by priority→deadline→age, and greedily reassigns every part's stock from scratch. This one component is reused by five future slices (S-08 through S-12), so it's built as a standalone, independently unit-tested piece from day one rather than inlined into the controller.

## Phases at a Glance

| Phase | What it delivers | Key risk |
| --- | --- | --- |
| 1. Schema & entities | `orders`/`order_lines` tables, JPA entities, repositories | Getting the `reserved_quantity <= required_quantity` CHECK and FK cascade rules right up front |
| 2. Allocation engine | `ReservationAllocator` + algorithm tests + concurrency invariant test | First-ever pessimistic-locking pattern in this codebase; the concurrency test is genuinely new ground |
| 3. Order creation | Form, validation, BOM snapshot into `OrderLine`s, allocator invocation | Snapshotting `required_quantity` correctly at creation time |
| 4. Order list & detail | Manager-only list/detail screens | Matching `ProjectDetailModel`'s read-model pattern instead of leaking entities to templates |
| 5. Parts catalog wiring | Real reserved/available on `/parts` | Aggregate query correctness (only counting `OPEN` orders) |

**Prerequisites:** S-02 (`parts-catalog`) and S-03 (`project-bom`), both already done.
**Estimated effort:** 5 phases; Phase 2 (allocation engine + concurrency test) is the heaviest.

## Open Risks & Assumptions

- The `F-01` foundation (migration-and-invariant-harness) that was meant to generically establish the locking/testing pattern is still unexecuted — this plan builds that pattern directly rather than waiting on it.
- Full recompute on every order-creation event is O(open orders × BOM lines); acceptable at this project's stated small/low-QPS scale, would need revisiting if that changes.
- BOM-edit/open-order interaction (roadmap's Open Question #3) remains genuinely open beyond this slice — documented as a known gap, not resolved here.

## Success Criteria (Summary)

- Creating an order reserves available stock per BOM line following priority → deadline → age, matching US-01's own acceptance example exactly (BOM needs 10, stock 6, order 1 unit → reserved 6, missing 4).
- Two concurrent order-creation requests against scarce stock never together over-reserve a part.
- The parts catalog's reserved/available columns show real, correct numbers.
