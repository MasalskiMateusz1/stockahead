<!-- PLAN-REVIEW-REPORT -->
# Plan Review: Projekty urządzeń i BOM (S-03)

- **Plan**: context/changes/project-bom/plan.md
- **Mode**: Deep
- **Date**: 2026-09-28
- **Verdict**: SOUND
- **Findings**: 0 critical, 4 warnings, 1 observation
- **Note**: Reviewed after Phase 1 landed (57fa8b5); findings apply to Phases 2–4.

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| End-State Alignment | PASS |
| Lean Execution | PASS |
| Architectural Fitness | WARNING |
| Blind Spots | WARNING |
| Plan Completeness | WARNING |

## Grounding
6/6 paths ✓, 4/4 symbols ✓ (PartRepository.search, accounts_single_manager_idx, PartController:53-66 showInactive, PartsCatalogIntegrationTests cleanup), brief↔plan ✓, Flyway outOfOrder default ✓, contract-surfaces.md absent (skipped).

## Findings

### F1 — Two copies of the change folder are drifting apart

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Completeness
- **Location**: Critical Implementation Details — "Parallel work with Codex"
- **Detail**: The main-checkout copy of `context/changes/project-bom/` is untracked and stale: its Phase 1 is unchecked. The worktree copy is committed. Edits made to the main copy never reach the branch, and the stale copy could be swept onto `main` by a broad `git add` during S-02.
- **Fix**: State in the plan that the worktree copy is canonical, and delete the main-checkout copy after copying it.
- **Decision**: ACCEPTED

### F2 — New projects.* keys are not added to MessagesBundleTests

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Blind Spots
- **Location**: Phases 2, 3, 4
- **Detail**: `MessagesBundleTests` says it is the only guard against a typo'd or missing bundle key, but no phase extends it for the new `projects.*` keys.
- **Fix**: Add `MessagesBundleTests.java` to the files changed in Phases 2–4, with one exact-text assertion per new key.
- **Decision**: FIXED

### F3 — Existing parts tests delete parts rows but not BOM lines

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Architectural Fitness
- **Location**: Phase 1 FK × `PartsCatalogIntegrationTests.cleanUp()`
- **Detail**: `cleanUp()` deletes `parts` without deleting `bom_lines`. With `ON DELETE RESTRICT`, one stray BOM line makes every parts test fail in setUp.
- **Fix**: In Phase 2, delete `project_links`, `bom_lines` and `projects` before the parts deletes. Recorded as a general rule under "Test cleanup across feature packages".
- **Decision**: FIXED

### F4 — Where BOM and link errors appear on the detail page is not specified

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Architectural Fitness
- **Location**: Critical Implementation Details — "Detail page errors across controllers"
- **Detail**: The detail page has several forms (add line, per-row quantity edits, add link, deletes). The plan did not say where the error shows or which form gets its submitted value back.
- **Fix**: One page-level `error` paragraph, rendered with HTTP 200. The add-line form gets `partId`/`quantityPerUnit` back. A row edit error sets `errorLineId`, and that row shows the submitted value. The link form gets `url`/`label` back.
  - Strength: One shared rule for all three controllers; easy to test.
  - Tradeoff: The error is shown at the top of the page, not next to the row.
  - Confidence: HIGH — matches the single-`error` pattern in the `parts-*` templates.
  - Blind spot: None significant.
- **Decision**: FIXED

### F5 — "Duplicate project name" is case- and whitespace-sensitive

- **Severity**: 💡 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Blind Spots
- **Location**: Phase 1 (`projects.name UNIQUE`) / Phase 2 validation
- **Detail**: Names differing only in case or inner spacing are treated as distinct. The plan left this implicit.
- **Fix**: Documented: a duplicate is an exact match after trimming, consistent with `parts.name`.
- **Decision**: FIXED
