# Delivery Receipt (S-10) — Plan Brief

> Full plan: `context/changes/delivery-receipt/plan.md`

## What & Why

A technician or manager records an incoming delivery as a multi-line receipt. Stock goes up, and reservations and the shopping list recompute immediately, so new units fill shortages in allocation order. This is FR-004 and the first stock-raising trigger in the roadmap's stream C (S-10 → S-11 → S-12).

## Starting Point

Today stock only changes through picks (down) and cancel returns (up). `ReservationAllocator.reallocateForParts` already does the full priority recompute, including taken-order top-up, and its contract requires every delivery to call it under the part-row lock. The cancel action (`OrderController.java:259-330`) is the pattern to copy: lock, then mutate, then flush, then reallocate.

## Desired End State

`/deliveries/new` (both roles, linked from the dashboard and `/parts`) shows a receipt with 5 blank rows. Each row has a part, a quantity, and an optional location. "Dodaj wiersze" adds 5 more. A valid receipt commits all-or-nothing: stock rises, missing locations are added, reservations recompute, and the user lands on `/parts` with a summary. Any invalid row rejects the whole receipt and keeps the inputs.

## Key Decisions Made

| Decision | Choice | Why (1 sentence) |
| --- | --- | --- |
| Form shape | Multi-line receipt | Matches a real delivery note in one step. |
| Record keeping | Stock only, no deliveries table | Movement history (FR-006) is a PRD Non-Goal for MVP. |
| Inactive parts | Accepted, labelled "(nieaktywna)" in the dropdown | Physical stock that arrived should never be blocked from booking. |
| Location | Optional per row, added if missing | Keeps the catalog accurate at shelving time; this deliberately lets technicians add locations. |
| Adding rows | 5 blank rows + "Dodaj wiersze" round-trip (+5, values kept) | Pure Thymeleaf, no JS, fully MockMvc-testable. |
| Same part on 2+ rows | Quantities summed, each location added | A delivery note can list one part twice (two boxes, two shelves). |
| Atomicity | Whole receipt or nothing | A half-booked delivery is harder to reconcile than a re-submit. |
| Code placement | `parts/DeliveryController`; allocator + `LockRetry` made public | The `parts` → `orders` dependency already exists; no logic is duplicated. |
| Quantity bounds | 1..1 000 000 per row, `Math.addExact` on stock | Per lessons, an explicit bound instead of relying on the DB CHECK. |

## Scope

**In scope:** receipt form with row adding and validation; the atomic stock + location write; reallocation; links and a success summary; integration and concurrency tests.

**Out of scope:** delivery history or record table (FR-006); JS add-row; removing or renaming locations; per-location stock; supplier/PO linkage; negative quantities (S-11); narrowing the allocator's lock scope.

## Architecture / Approach

The raw form rows are validated without loading any `Part`, because open-in-view would otherwise leave a stale entity in the persistence context. Rows are then merged per part. A single `LockRetry` + `TransactionTemplate` block runs `findByIdInForUpdate(ids)`, sets the quantities and adds locations on the locked entities, then **flushes**, then `reallocateForParts(ids)`, then redirects with a flash summary. `/purchasing` and the reserved/available columns on `/parts` read live data, so they update with no extra code.

## Phases at a Glance

| Phase | What it delivers | Key risk |
| --- | --- | --- |
| 1. Receipt form and row validation | `/deliveries/new`, add-rows, all validation errors with inputs kept; no write yet | Row-parse edge cases (partial row, blank rows, clamp) |
| 2. Atomic receipt with reallocation | Stock + locations + reallocation, links, summary; business-rule tests | Calling the allocator before flush, so delivered units reach no order |
| 3. Concurrency guarantees | Real-Postgres tests: no lost update, receipt vs pick, receipt vs order creation | Flaky timing; mitigated with `pg_sleep` triggers as in the sibling tests |

**Prerequisites:** S-02 and S-04 done (they are), Docker running for `./mvnw verify`.
**Estimated effort:** ~2–3 sessions across 3 phases.

## Open Risks & Assumptions

- Nothing records who received a delivery, so a mistaken receipt is visible only as a stock change. Undoing it needs S-11 (stock correction), which doesn't exist yet.
- Summing duplicate rows means an accidental double entry silently doubles that part's stock.
- Technicians can now add part locations, a small widening of the PRD Access Control table that was chosen deliberately.
- The dropdown lists the whole catalog. That's fine at the PRD's small scale.

## Success Criteria (Summary)

- A delivery that covers an order's shortage makes the order fully reserved and removes (or shrinks) the part on `/purchasing`, with no manual refresh.
- Receipts never lose an update under concurrency, and stock ≥ 0 and reserved ≤ stock hold for every part.
