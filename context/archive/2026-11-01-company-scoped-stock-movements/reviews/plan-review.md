<!-- PLAN-REVIEW-REPORT -->
# Plan Review: Company-Scoped Stock Movements

- **Plan**: context/changes/company-scoped-stock-movements/plan.md
- **Mode**: Deep
- **Date**: 2026-11-01
- **Verdict**: SOUND
- **Findings**: 0 critical, 1 warning, 1 observation

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| End-State Alignment | PASS |
| Lean Execution | PASS |
| Architectural Fitness | PASS |
| Blind Spots | PASS |
| Plan Completeness | WARNING |

## Grounding

12/12 paths ✓, 6/6 symbols ✓ (FK names, setPart, getCompany, scoped lock, bare-lock callers, test auth), brief↔plan ✓, Progress 11↔11 ✓, contract-surfaces: n/a (file absent)

## Findings

### F1 — Phase 1 doesn't list every raw-SQL test that V19 affects

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Completeness
- **Location**: Phase 1 — §3 Test seeds and schema tests
- **Detail**: Two kinds of raw-SQL test writes were missing from the plan.
  - Tests write `cancelled_by` directly at `OrderCancelIntegrationTests.java:721`/`:725` and `StockCorrectionIntegrationTests.java:166`. They keep passing only if the canceller belongs to the order's company.
  - The CHECK-constraint tests at `StockCorrectionIntegrationTests.java:747-767` expect a `DataIntegrityViolationException`. If `seedCorrection` misses `company_id`, the NOT NULL violation raises that exception before any CHECK runs. The tests stay green but no longer test their CHECK.
- **Fix**: List these call sites. `seedCorrection` passes the part's `company_id`, and the cancel seeds keep the canceller in the order's company.
- **Decision**: FIXED

### F2 — An image rollback after V19 breaks stock corrections

- **Severity**: 💡 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Blind Spots
- **Location**: Migration Notes
- **Detail**: After V19 runs, the previous image still starts, because `ddl-auto=validate` tolerates the extra column. But its `StockCorrection` never sets `company_id`, which is NOT NULL, so every correction fails with `saveFailed` until roll-forward. V15 and V17 accepted the same posture.
- **Fix**: Add one sentence to Migration Notes.
- **Decision**: FIXED
