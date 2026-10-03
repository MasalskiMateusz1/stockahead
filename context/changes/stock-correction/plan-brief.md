# Stock Correction (S-11) — Plan Brief

> Full plan: `context/changes/stock-correction/plan.md`

## What & Why

After an inventory count, the manager corrects one part's stock and must give a reason (FR-018). Reservations and the shopping list recompute right after. This is the only path that can push stock below what orders hold, so it is where the "never reserved twice" guarantee is easiest to break.

## Starting Point

Stock changes today only through picks (down), deliveries (up) and cancel returns (up). `parts-edit.html` already points to a correction path that doesn't exist. `ReservationAllocator` never shrinks a taken order's reservation; it clamps the pool to 0 (`ReservationAllocator.java:97-100`). So a correction below the taken reservations would leave reserved > stock, and nothing would stop it.

## Desired End State

On `/parts`, a manager-only "Koryguj stan" link opens `/parts/{id}/correction`. The page shows stock, reserved and available, and two forms, each with a required reason:
- **Ustaw stan na N** sets a counted total and is rejected if stock changed since the page opened.
- **Dodaj / odejmij N** applies a signed change and can't go below 0.

A table lists the part's past corrections (time, author, before → after, change, reason). After saving, reservations recompute and any new shortage appears on `/purchasing`.

## Key Decisions Made

| Decision | Choice | Why (1 sentence) |
| --- | --- | --- |
| Stock below taken orders' unpicked reservations | Save; free non-taken first, then shrink taken reservations (completion-reported orders first, then reverse allocation order) | Missing units can't be picked anyway; physical loss isn't the "przejęcie" (takeover) the PRD forbids. |
| Where the shrink lives | In `ReservationAllocator` (deficit pass), not the controller | Keeps reserved ≤ stock true for every caller. |
| Input | Two separate forms: "Ustaw stan" (total) and "Dodaj / odejmij" (signed delta) | User wants both an inventory total and quick ± adjustments. |
| Stale total | Reject and re-show the fresh stock, inputs kept; delta mode needs no check | A total never silently overwrites a pick the manager didn't see. |
| Reason storage | New `stock_corrections` table (part, before, after, reason, author, time), listed on the correction page | Answers "why did stock change" in-app without building full FR-006 history. |
| Scope | One part per correction, linked per row on `/parts`; inactive parts included | Simple form and stale check; physical stock of inactive parts still counts. |
| Bounds | Delta: non-zero, absolute value ≤ 1 000 000, `Math.addExact`. Total: 0..int max. Reason: required, ≤ 500 characters. No-op rejected | Explicit bounds per lessons.md, no generic DB-CHECK errors. |
| Docs | AGENTS.md hard rule and PRD § Business Logic get a one-line exception | The shrink would otherwise read as a hard-rule violation. |

## Scope

**In scope:** allocator deficit pass and its tests; V11 `stock_corrections`; the correction page with two forms and a history table; the two locked, reallocating write paths; the `/parts` link and flash; integration, 403 and concurrency tests; AGENTS.md and PRD wording.

**Out of scope:** movement history for picks and deliveries (FR-006); a multi-row inventory sheet; reason categories; editing or undoing corrections; technician access; location edits; narrowing the allocator lock; notifying technicians.

## Architecture / Approach

`StockCorrectionController` (in `parts`) follows `DeliveryController`. It validates raw strings first, then inside `LockRetry` + `TransactionTemplate` it runs `findByIdInForUpdate`, applies the stale or below-zero checks under the lock, sets stock, inserts the `StockCorrection`, flushes and calls `reallocateForParts(Set.of(id))`. The allocator then frees non-taken reservations by normal recompute. Its new deficit pass cuts taken lines' `reservedQuantity` (never `pickedQuantity`) in reverse `ALLOCATION_ORDER` until reserved ≤ stock.

## Phases at a Glance

| Phase | What it delivers | Key risk |
| --- | --- | --- |
| 1. Allocator deficit pass and rule docs | Reserved ≤ stock restored after any drop; AGENTS.md/PRD exception | Shrinking in the wrong order, or touching picked units |
| 2. Schema and correction screen | V11 table, read-only page with history, `/parts` link, 403 tests | Lessons misses (403 test, centered table, JOIN FETCH) |
| 3. Set-total and adjust write paths | Atomic correction + reallocation, every rejection, shortage on `/purchasing` | Loading `Part` before the lock (open-in-view stale entity); forgetting the flush before reallocating |
| 4. Concurrency guarantees | Real-Postgres races vs pick, delivery, order creation | Flaky timing; mitigated with `pg_sleep` triggers as in sibling tests |

**Prerequisites:** S-02 and S-04 done (they are); Docker running for `./mvnw verify`.
**Estimated effort:** ~2–3 sessions across 4 phases.

## Open Risks & Assumptions

- A taken order's reservation can now shrink. The technician sees it only on the picking list, with no notification.
- Each correction replaces stock or applies a delta, so a mistyped total is fixed only by another correction. The history table makes that visible.
- `stock_corrections` is a deliberate, narrow step toward history. It must not grow into FR-006 without a roadmap item.

## Success Criteria (Summary)

- After a downward correction, affected orders show the new shortage and `/purchasing` lists it, with no manual step.
- Every correction has a visible reason and author on the part's correction page.
- Under concurrent picks, deliveries and order creation, stock never goes below 0, reserved never exceeds stock, and a stale "Ustaw stan" never overwrites a pick.
