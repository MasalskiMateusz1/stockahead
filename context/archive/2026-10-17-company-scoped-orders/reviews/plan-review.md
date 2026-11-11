<!-- PLAN-REVIEW-REPORT -->
# Plan Review: Company-Scoped Orders (S-03)

- **Plan**: context/changes/company-scoped-orders/plan.md
- **Mode**: Deep
- **Date**: 2026-10-17
- **Verdict**: SOUND
- **Findings**: 0 critical, 2 warnings, 2 observations

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| End-State Alignment | PASS |
| Lean Execution | PASS |
| Architectural Fitness | PASS |
| Blind Spots | WARNING |
| Plan Completeness | WARNING |

## Grounding
11/11 paths ✓, 12/12 symbols ✓ (53 inserts / 22 files confirmed), brief↔plan ✓, Progress↔Phase ✓

## Findings

### F1 — Fixture cleanup deletes accounts before orders

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Blind Spots
- **Location**: Phase 1 § 3 — Test fixtures
- **Detail**: `CompanyFixtures.cleanUp()` deletes the fixture's accounts by email before its per-company loop. Orders reference accounts (`assignee_id`, `cancelled_by`, `completion_reported_by`) with RESTRICT, so deleting orders only "before the projects" is too late.
- **Fix**: Delete `orders WHERE company_id = ?` for every created company before the email-account loop.
- **Decision**: FIXED

### F2 — Allocation-isolation tests would pass even with an unscoped allocator

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Blind Spots
- **Location**: Phase 2 § 6 "Allocation" and § 7
- **Detail**: The § 6 case passes today, because parts are separate rows. In § 7, "A unchanged" also holds for an unscoped allocator, because a consistent allocation recomputes to the same values. The `POST /orders` half of § 7 passes before the change. Only the bounded wait tells the scoped and unscoped allocator apart.
- **Fix**: Seed A's line short (raw SQL stock raise, no reallocation), call `reallocateForParts(B, {A.R1, B.R1})`, and assert A's line stays short.
  - Strength: Directly proves that a foreign part id is not read.
  - Tradeoff: One more case with a deliberately inconsistent seed.
  - Confidence: HIGH — non-taken lines are rebuilt as `min(pool, required)`.
  - Blind spot: None significant.
- **Decision**: FIXED

### F3 — The plan's list of allocator callers in tests is wrong

- **Severity**: OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Completeness
- **Location**: Current State › Allocator callers; Phase 2 § 3
- **Detail**: `ReservationAllocatorTests` has one call inside a `reallocate(Set)` helper, not 7 direct calls. `ReservationConcurrencyTests` (`:109`, `:161`) wasn't listed.
- **Fix**: List `ReservationConcurrencyTests` in § 3 and say the helper gains `companyId`.
- **Decision**: FIXED

### F4 — The S-05 delivery gap is wider than the plan says

- **Severity**: OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Blind Spots
- **Location**: Phase 2 § 8 (roadmap S-05 Risk); plan-brief Open Risks
- **Detail**: When B delivers to A's part id, the delivery passes the receiver's company, so A's part is skipped. Every short open order of A on that part misses the units, not only taken ones. It is never over-reservation.
- **Fix**: Change the wording to "short open orders on that part (taken or not)".
- **Decision**: FIXED
