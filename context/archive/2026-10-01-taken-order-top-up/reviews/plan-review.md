<!-- PLAN-REVIEW-REPORT -->
# Plan Review: Taken Order Top-Up Implementation Plan

- **Plan**: context/changes/taken-order-top-up/plan.md
- **Mode**: Deep (verified inline, no sub-agent; review run after implementation)
- **Date**: 2026-10-02
- **Verdict**: SOUND
- **Findings**: 0 critical, 2 warnings, 0 observations

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| End-State Alignment | WARNING |
| Lean Execution | PASS |
| Architectural Fitness | PASS |
| Blind Spots | WARNING |
| Plan Completeness | PASS |

## Grounding
9/9 paths ✓, 5/5 symbols ✓ (findByIdInForUpdate, transitionReportedOrder, isCompletionReported, ALLOCATION_ORDER, reservedQuantitiesByOpenOrder), brief↔plan ✓, Progress↔Phase ✓

## Findings

### F1 — A top-up can't be taken back, so the order of events decides who gets units

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Blind Spots
- **Location**: What We're NOT Doing; Desired End State
- **Detail**: The plan said a taken LOW order never beats a non-taken HIGH one, but top-up units join the protected floor at once (`ReservationAllocator.java:104-107`), so a HIGH order created later cannot reclaim them. Only the allocator javadoc (impl-review F4) recorded this. The plan and the PRD sentence (`prd.md:116`) implied allocation order always wins.
- **Fix**: Qualify the What We're NOT Doing bullet (rule holds within a single reallocation pass; top-up units are protected at once) and add "Przydzielone w ten sposób sztuki od razu podlegają ochronie przed przejęciem." to PRD § Business Logic.
- **Decision**: FIXED (plan bullet qualified; PRD sentence added)

### F2 — "Catches up immediately" on reject is too strong

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: End-State Alignment
- **Location**: Desired End State; Phase 2 manual check 2.4
- **Detail**: Units freed while B is reported may go to an order that gets its first pick before the reject. Those units are then protected, so B cannot catch up with them. The plan stated the catch-up as unconditional.
- **Fix**: Change Desired End State to "...catches up immediately with whatever is still unprotected (units already handed to an order that has since been taken stay there)".
- **Decision**: FIXED
