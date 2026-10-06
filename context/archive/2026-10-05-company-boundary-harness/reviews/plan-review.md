<!-- PLAN-REVIEW-REPORT -->
# Plan Review: Company Boundary and Cross-Company Test Pattern

- **Plan**: context/changes/company-boundary-harness/plan.md
- **Mode**: Deep
- **Date**: 2026-10-05
- **Verdict**: REVISE → SOUND after triage
- **Findings**: 1 critical, 2 warnings, 1 observation

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| End-State Alignment | PASS |
| Lean Execution | PASS |
| Architectural Fitness | PASS |
| Blind Spots | WARNING |
| Plan Completeness | FAIL |

## Grounding
12/12 paths ✓, 5/5 symbols ✓, brief↔plan ✓, Progress↔Phase ✓ (docs/reference/contract-surfaces.md absent — skipped)

## Findings

### F1 — Phase 1 cannot end green: production code still creates company-less accounts

- **Severity**: ❌ CRITICAL
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Plan Completeness
- **Location**: Phase 1 criterion 1.1 vs. old Phase 2 §2 / Phase 3 §2
- **Detail**: V14 makes `accounts.company_id` NOT NULL in Phase 1, but `SetupController` (fixed in old Phase 2) and `TechnicianAccountController.create` (fixed in old Phase 3) kept saving accounts without a company. As a result, `setupWithCorrectTokenCreatesActiveManagerAccount` and the technician-create redirect test (`TechnicianAccountIntegrationTests:86-91`) fail at the end of Phase 1.
- **Fix A ⭐ Recommended**: Move the principal, the setup company name, and create-sets-company into Phase 1, which leaves two phases.
  - Strength: Each phase ends green; no throwaway code.
  - Tradeoff: A bigger Phase 1.
  - Confidence: HIGH — those two are the only `new Account()` calls in src/main.
  - Blind spot: None significant.
- **Fix B**: Add a Phase 1 bridge: technician create copies the company from the manager's Account (looked up by email).
  - Strength: Phase sizes stay close to today's.
  - Tradeoff: Throwaway code.
  - Confidence: HIGH
  - Blind spot: None significant.
- **Decision**: FIXED (Fix A)

### F2 — Dropping the V2 index removes the DB guard against a concurrent setup race

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Blind Spots
- **Location**: Phase 1 §5 (setup)
- **Detail**: The `existsByRole(MANAGER)` re-check is check-then-act. `accounts_single_manager_idx` was the DB backstop for two concurrent POSTs. Without it, both succeed and create two companies with two managers.
- **Fix**: Take `pg_advisory_xact_lock` (pattern: `PartRepository.java:62`) inside the setup transaction, then re-check, then create. Add a two-thread test. S-09 must not reuse the lock.
  - Strength: Restores the guarantee using the existing lock pattern.
  - Tradeoff: One more query; this lock is setup-only.
  - Confidence: HIGH
  - Blind spot: S-08 replaces setup, so the lock may only live until then.
- **Decision**: FIXED

### F3 — The company created by /setup is never cleaned up in tests

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Blind Spots
- **Location**: Phase 1 §10 (setup tests)
- **Detail**: The fixture cleanup deletes only fixture-made companies, so the companies created by `POST /setup` leak. A global count assertion would be flaky.
- **Fix**: Assert through the created accounts' companies, and delete those companies after the accounts.
- **Decision**: FIXED

### F4 — Wipe timing vs. auto-deploy on push to main

- **Severity**: 💡 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Blind Spots
- **Location**: Migration Notes; Phase 2 §4
- **Detail**: `ci.yml` deploys on every push to main. If the old /setup is used between the wipe and the deploy, the V14 guard trips. That failure is safe but confusing.
- **Fix**: Write the deployment.md note as an ordered sequence: Backup now → TRUNCATE → merge immediately → watch the deploy → /setup. If V14 fails, re-wipe and redeploy the tag.
- **Decision**: FIXED
