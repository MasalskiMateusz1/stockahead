<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: Taken Order Top-Up

- **Plan**: context/changes/taken-order-top-up/plan.md
- **Scope**: Full plan
- **Reviewed phases**: 1, 2, 3
- **Date**: 2026-10-02
- **Verdict**: APPROVED
- **Findings**: 0 critical, 2 warnings, 3 observations

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| Plan Adherence | PASS |
| Scope Discipline | WARNING |
| Safety & Quality | WARNING |
| Architecture | PASS |
| Pattern Consistency | PASS |
| Success Criteria | PASS |

## Findings

### F1 — Allocator test masks reject's own reallocation

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: src/test/java/pl/regavio/stockahead/orders/ReservationAllocatorTests.java:472-477
- **Detail**: Since Phase 2, `rejectCompletion` reallocates by itself, but `takenHigherPriorityOrderTopsUpBeforeNonTakenLowerPriorityOrder` calls `reallocate(Set.of(partId))` again before asserting 7/0. If reject's reallocation broke, the extra call would hide it here. (`OrderCompletionIntegrationTests.reportedShortOrderIsSkippedUntilRejectCatchesItUp` does cover reject end-to-end, so this is a gap in one test, not in coverage.)
- **Fix**: Assert `takenHigh = 7`, `picked = 1`, `low = 0` straight after `rejectCompletion`, then keep the extra `reallocate` followed by the same assertions as a check that running it twice changes nothing.
- **Decision**: FIXED (assertions added right after reject; second pass kept as a check that running it twice changes nothing)

### F2 — Unplanned template and docs changes in this change

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Scope Discipline
- **Location**: src/main/resources/templates/*.html (8 templates), context/foundation/lessons.md:65, context/foundation/roadmap.md (S-13)
- **Detail**: The centered-cell `<style>` block in 8 templates, a new lessons.md entry, and the S-13 roadmap rows were not in the plan. All were requested by the user during Phase 3 and are harmless (no behaviour change, docs only).
- **Fix**: Accept as user-requested; no code change.
- **Decision**: ACCEPTED (user-requested extras; no code change)

### F3 — Future stock-raising events must call the allocator

- **Severity**: 💬 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Architecture
- **Location**: src/main/java/pl/regavio/stockahead/orders/ReservationAllocator.java:59-74
- **Detail**: Top-up only happens when create, confirm or reject runs on a part. The tests raise stock with a raw `UPDATE parts`, and no endpoint raises stock yet. S-10 (delivery) and S-11 (correction) must call `reallocateForParts` under the part lock, or taken orders will never receive the new units.
- **Fix**: Add a line to the allocator javadoc (or the S-10/S-11 plans) saying every stock-raising write must call `reallocateForParts` under the part lock.
- **Decision**: FIXED (allocator class javadoc: stock-raising writes must call reallocateForParts under the part lock)

### F4 — A top-up is protected immediately ("sticky")

- **Severity**: 💬 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Architecture
- **Location**: src/main/java/pl/regavio/stockahead/orders/ReservationAllocator.java:109-128
- **Detail**: Once a taken lower-priority order is topped up, a higher-priority order created later cannot take those units back. This follows the hard rule (a taken order's reservation is never preempted) and the PRD, but it means the order in which events happen now decides the outcome permanently.
- **Fix**: No change needed; optionally state in the allocator javadoc that units granted by a top-up are protected right away.
- **Decision**: FIXED (allocator class javadoc: top-up units are protected at once; event order decides)

### F5 — Concurrency test end state is the same whichever request wins

- **Severity**: 💬 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: src/test/java/pl/regavio/stockahead/orders/OrderCompletionConcurrencyTests.java:414
- **Detail**: In `concurrentConfirmAndPickOnTheTakenOrderBeingToppedUp`, both orderings end at stock 6, picked 3, reserved 5, and the racing pick (2) always fits inside B's reservation from before the top-up. The invariant checks are the real guard, and serialization is already tested by the existing log-based race tests in that file.
- **Fix**: Accept; optionally make the racing pick larger than B's reservation from before the top-up, so in one of the two orderings it can only succeed if it uses top-up units.
- **Decision**: FIXED (racing pick raised to 4; each of the two orderings asserted to its own exact state — both seen 3/3 over 6 iterations)
