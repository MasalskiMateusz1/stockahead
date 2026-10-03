<!-- PLAN-REVIEW-REPORT -->
# Plan Review: Stock Correction (S-11) Implementation Plan

- **Plan**: context/changes/stock-correction/plan.md
- **Mode**: Deep (retrospective — plan already implemented, cb7a411…9910dae; claims verified against shipped code)
- **Date**: 2026-10-03
- **Verdict**: SOUND
- **Findings**: 0 critical, 0 warnings, 2 observations

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| End-State Alignment | PASS |
| Lean Execution | PASS |
| Architectural Fitness | PASS |
| Blind Spots | PASS (2 observations) |
| Plan Completeness | PASS |

## Grounding
11/11 paths ✓, 4/4 symbols ✓ (findByIdInForUpdate, executeWithLockRetry, findByCanonicalEmail, reallocateForParts), brief↔plan ✓, Progress↔Phase ✓ (4/4 phases, 15/15 criteria). Contract surfaces: skipped (docs/reference/contract-surfaces.md absent).

## Findings

### F1 — Deficit cuts pickable orders before reported ones

- **Severity**: 💡 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Blind Spots
- **Location**: Phase 1 — Allocator contract ("reported orders included")
- **Detail**: The deficit pass shrank taken lines in strict reverse ALLOCATION_ORDER, completion-reported orders included. A reported order's unpicked reservation can no longer be picked, yet a higher-priority reported order kept it while a lower-priority order still being picked lost units, showing a false shortage until the manager confirmed completion (confirm zeroes reservations and reallocates).
- **Fix**: Shrink completion-reported orders' lines first, then the rest, each group in reverse allocation order.
- **Decision**: FIXED — plan (Desired End State, Critical Implementation Details, Phase 1 contract/tests/docs wording, Testing Strategy), plan-brief, AGENTS.md hard rule, PRD § Business Logic, `ReservationAllocator.shrinkTakenReservationsToStock` comparator + Javadoc, new test `reportedTakenOrderShrinksBeforeActiveTakenOrder`. `./mvnw verify` green (349 tests).

### F2 — "500 characters" has no defined unit

- **Severity**: 💡 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Blind Spots
- **Location**: Phase 3 — Reason validation
- **Detail**: `String.length()` counts UTF-16 code units (StockCorrectionController.java:259); Postgres VARCHAR(500) counts characters. Reasons with emoji are rejected earlier than the DB would. Fails safe (clear error, never a 500); Polish text unaffected.
- **Fix**: Count code points (`reason.codePointCount(0, reason.length())`).
- **Decision**: ACCEPTED
