# Frame Brief: Taken orders that are short never receive freed units

> Framing step before /10x-plan. This document captures what is *actually*
> at issue, separated from what was initially assumed.

## Reported Observation

When stock is freed (today only by confirming a completed order), the freed units go only to OPEN orders that haven't been taken yet. A taken order that is still short on the same part never receives them. (Source: order-completion impl-review F6; roadmap Open Roadmap Question 4.)

## Initial Framing (preserved)

- **User's stated cause or approach**: `ReservationAllocator`'s "taken order is frozen" rule (`ReservationAllocator.java:82-104`) both protects a taken order's reservation and excludes it from receiving more.
- **User's proposed direction**: let taken orders with a shortage receive units freed later (F6).
- **Pre-dispatch narrowing**: found only in review (code reading), not seen in the app. Concern covers all stock-freeing events: confirm (S-08), cancel (S-09), delivery/CSV import (S-10/S-12), stock correction (S-11). Plant flow, in the user's words: a short taken order "should be marked as partially completed, and show how many was build".

## Dimension Map

1. **Allocator freeze rule**: taken lines are subtracted from the pool and never written, so they can't grow. ← initial framing
2. **Product rule gap**: PRD protects taken reservations from being taken, but is silent on whether they can receive; the code chose one reading.
3. **Order end-state**: COMPLETED is all-or-nothing; there is no "partially completed, built N of M" outcome for an order that stays short.
4. **Quantity granularity**: shortage is tracked per part line; "how many built" is in device units and is not stored or derived anywhere.

## Hypothesis Investigation

| Hypothesis | Evidence | Verdict |
| --- | --- | --- |
| 1. Allocator freeze excludes taken lines from receiving | `ReservationAllocator.java:82-104` subtracts taken reservations, writes only non-taken lines; only callers are confirm (`OrderController.java:128`) and create (`:252`). Deliberate: picking `plan-brief.md:22,38,61`, picking `plan.md:32` ("deliberate MVP simplification … needs its own follow-up decision in S-10/S-11"). No test pins "taken stays short". Extra effect: units freed with no non-taken taker stay free and go to the next *new* order, even LOW priority. | STRONG |
| 2. PRD is silent; code picked the "frozen" reading | `prd.md` § Business Logic forbids only takeover ("Nie podlegają przejęciu … niepobrane rezerwacje zleceń podjętych"); deliveries "automatycznie uzupełniają rezerwacje wg kolejności", released reservations "trafiają do kolejnych zleceń", no taken-order exemption. FR-021 Socrates concern was "technik traci części", i.e. losing, not gaining. Roadmap S-10 Outcome: "nowe sztuki uzupełniają braki zgodnie z kolejnością przydziału". | STRONG |
| 3. No end-state for an order that stays short | FR-014/FR-020 binary; no FR/US/Non-Goal mentions built count or partial completion; order-completion plan allows confirming with unmet lines as plain COMPLETED; shortfall then vanishes from shopping list and detail view (F4 hid the column). | STRONG (separate problem) |
| 4. Built units not stored or derived | V6 status CHECK only OPEN/CANCELLED/COMPLETED; V8 adds no quantity; report/confirm take no quantity input. Derivable as min over lines of ⌊picked·units/required⌋, but nothing computes it. | STRONG (supports 3) |

## Narrowing Signals

- A short taken order is not closed short by default: "Technician picks them and keeps building". Top-up is a real workflow need, not just a code-reading artifact. Confirms dimensions 1 and 2 as the problem for this change.
- Freed units follow **normal allocation order** (priority → date → age), taken or not. A taken LOW order does not jump ahead of a non-taken HIGH one for *extra* units. Its *existing* reservation stays protected (PRD takeover rule).
- "How many were built" is a number the **technician enters**, not one derived from picked parts. Dimension 4's derivation is not the answer. Dimension 3 needs a stored, user-supplied value, which makes it a distinct feature.

## Cross-System Convention

- Prior decisions: the freeze was recorded in S-07 as a deliberate MVP simplification, with exactly this follow-up deferred to "S-10/S-11's planning". It was reopened as F6 / Roadmap Q4. No earlier decision contradicts top-up.
- PRD convention: every stock event ("dostawa", "import", "korekta", "anulowanie/zamknięcie") recomputes reservations in one allocation order. The user's "normal allocation order" answer matches it; the current code is the outlier.
- Inverse checks the plan must honour (not solved here):
  - (a) a taken line can only grow up to `required − picked` (V7 CHECK `reserved + picked <= required`);
  - (b) its current reservation is a floor, because shrinking it is takeover;
  - (c) a **completion-reported** order is OPEN and taken but cannot be picked (`PickingController.java:131`); giving it units would park stock nobody can use until confirm;
  - (d) lock scope is unchanged (`findByIdInForUpdate(partIds)`), so the lessons.md rule on narrowing locks does not apply, but real-concurrency tests are still expected.

## Reframed (or Confirmed) Problem Statement

> **The actual problem to plan around is**: the allocator treats "taken" as "excluded from allocation" when the product rule only requires "protected from takeover". A taken order that is short must take part in normal allocation order for its unmet quantity on every stock-freeing event, without ever losing what it already holds.

The initial framing was correct, so proceed with the proposed direction (top up taken orders). The investigation adds three constraints:
- the allocation position is the normal one, with no taken-first bonus;
- the cap is `required − picked` and the floor is the current reservation;
- reported-but-unconfirmed orders need an explicit decision.

Separately, the investigation surfaced a **second, distinct gap**: an order that stays short has no "partially completed, built N (technician-entered)" outcome. That is not part of this change.

## Confidence

**HIGH**. Strong code and document evidence, a matching PRD convention, a decisive user signal ("keeps building", "normal allocation order"), and the earlier decision explicitly deferred this follow-up.

## What Changes for /10x-plan

- Plan an allocator change: taken lines receive extra units in normal allocation order, capped at `required − picked`, floored at their current reservation. Decide how reported orders are treated. Prove it with allocator, integration and concurrency tests on every existing caller (confirm, create); S-09/S-10/S-11/S-12 inherit it.
- Do **not** fold partial completion / built count into this plan. Record it as its own roadmap item or change (it touches FR-014/FR-020 and needs a migration plus a technician input).
- Update PRD § Business Logic and close Roadmap Open Question 4 with the decided rule.

## References

- `src/main/java/pl/regavio/stockahead/orders/ReservationAllocator.java:29-32, 82-104`
- `src/main/java/pl/regavio/stockahead/orders/OrderController.java:113-131, 252`
- `src/main/java/pl/regavio/stockahead/orders/PickingController.java:131`
- `src/main/resources/db/migration/V6__create_orders.sql`, `V7__add_order_picking.sql`, `V8__add_order_completion.sql`
- `context/foundation/prd.md` § Business Logic, FR-010, FR-013, FR-014, FR-020, FR-021
- `context/foundation/roadmap.md` Open Roadmap Question 4; S-08, S-10 Outcomes
- `context/archive/2026-10-01-picking-list-and-pick/plan-brief.md:22,38,61`, `plan.md:32`
- `context/archive/2026-10-01-order-completion/reviews/impl-review.md` F6; `plan-brief.md` Key Decisions / Open Risks
- Investigation: three parallel read-only agents (allocator freeze; product docs H2/H3; built-units data model)
