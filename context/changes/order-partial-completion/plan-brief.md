# Order Partial Completion — Plan Brief

> Full plan: `context/changes/order-partial-completion/plan.md`

## What & Why

Roadmap S-13 (FR-014/FR-020 extension). A short order should be "marked as partially completed and show how many were built". The count is entered by the technician and not derived from picks. Today a short order confirms as a plain `COMPLETED`, and its shortfall disappears without a trace.

## Starting Point

S-08 gives report (technician) → confirm/reject (manager). Confirm sets `COMPLETED`, releases unpicked reservations and reallocates. No built count is stored anywhere, and completed orders appear in no list (only `/orders/{id}`).

## Desired End State

The report form has a required "Zbudowano sztuk" field (0…N, pre-filled with N). The manager sees "X / N" on `/orders` and on the detail page before deciding. A confirmed order with X < N reads "Zakończone częściowo: zbudowano X z N". The remainder is dropped from reservations and the shopping list, and the manager re-orders it manually if needed.

## Key Decisions Made

| Decision | Choice | Why (1 sentence) |
| --- | --- | --- |
| Unbuilt remainder | Dropped and visible on the order, with no automatic follow-up order | Matches "mark partial, show how many built" and leaves the allocator unchanged. |
| Count range | 0…N, no comparison with picked parts | The technician's number is authoritative (substitutes), and 0 covers a failed build without cancel returning soldered parts. |
| When entered | Every report, a required field pre-filled with N | One form and one path, so every new completion records a count. |
| Manager correction | None. The manager rejects and the technician re-reports | Reuses the S-08 reject flow, so the number always comes from the technician. |
| Visibility | "Zbudowano" column in the pending list plus three-state text on the detail page | Shows the count where the decision is made, with no history list (as in S-08). |
| Legacy completed orders | `built_units` NULL, shown as "nie została zapisana" | Doesn't invent "fully built" for orders that may have been confirmed short. |
| State model | `COMPLETED` + nullable `built_units`, partial derived as X < N | No ripple through every status query or existing CHECK. |

## Scope

**In scope:** PRD FR-014/FR-020 amendment and roadmap S-13 unknowns; V12 column with CHECKs; report input and validation; reject clears the count; pending-list column; detail-page states; integration, schema and concurrency test updates.

**Out of scope:** automatic follow-up orders; manager editing the count; comparing the count with picks; a new status value; a completed-orders history list; backfill; allocator, locking or shopping-list changes.

## Architecture / Approach

One nullable column, `orders.built_units`, with DB CHECKs (0…`quantity_units`; count only with a report). It is written in the existing locked report transaction (validated against `quantity_units` re-read under the lock) and cleared in reject before the flush. Confirm keeps its mechanics. The view records in `PickingDetailModel`/`OrderDetailModel` carry the count to the templates.

## Phases at a Glance

| Phase | What it delivers | Key risk |
| --- | --- | --- |
| 1. PRD rule & schema | Amended FR-014/FR-020, S-13 unknowns closed, V12 + `Order` mapping + schema tests | Pending pre-V12 reports have no count, so the DB must not require it |
| 2. Technician report with built count | Required 0…N field, validation under lock, picking page shows the count | Existing report tests and concurrency tests break until they send the field |
| 3. Manager review & display | Pending column, detail states, reject clears count, confirm-partial tests | Reject flushing a count without a report trips the new CHECK |

**Prerequisites:** S-08 `done`; Docker for `./mvnw verify`.
**Estimated effort:** ~2 sessions across 3 phases.

## Open Risks & Assumptions

- A dropped remainder is only re-ordered if the manager remembers to. The detail page warns at confirm time, but no list tracks partial orders afterwards.
- Typos in the count are caught only by the manager's review, since nothing is checked against picks.
- Reports pending at deploy time confirm with an unknown count.

## Success Criteria (Summary)

- A technician cannot report without a count of 0…N, and the manager sees it before confirming.
- Confirming 7 of 10 closes the order as "Zakończone częściowo: zbudowano 7 z 10", its shortage leaves `/purchasing`, and released units go to the next order.
- `./mvnw verify` is green with schema, boundary, two-cycle, legacy and concurrency tests.
