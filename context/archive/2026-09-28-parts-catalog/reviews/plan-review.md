<!-- PLAN-REVIEW-REPORT -->
# Plan Review: Kartoteka części z lokalizacjami (S-02)

- **Plan**: context/changes/parts-catalog/plan.md
- **Mode**: Deep
- **Date**: 2026-09-28
- **Verdict**: REVISE (pre-triage) → SOUND (post-triage)
- **Findings**: 0 critical, 2 warnings, 1 observation — all triaged and fixed

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| End-State Alignment | PASS |
| Lean Execution | PASS |
| Architectural Fitness | PASS |
| Blind Spots | WARNING (fixed) |
| Plan Completeness | WARNING (fixed) |

## Grounding

5/5 paths ✓ (SecurityConfig.java, ManagerPingController.java, SetupController.java, AuthenticationIntegrationTests.java, dashboard.html), 3/3 symbols ✓ (`anyRequest().authenticated()`, `@PreAuthorize("hasRole('MANAGER')")`, `DataIntegrityViolationException` catch pattern), brief↔plan ✓.

## Findings

### F1 — "showInactive" semantics contradict the reactivate redirect

- **Severity**: WARNING
- **Impact**: MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Blind Spots
- **Location**: Phase 1 (`PartRepository.search`) / Phase 2 (`GET /parts`) / Phase 3 (reactivate)
- **Detail**: `PartRepository.search(q, active)` took one non-null boolean, so it could only return active-only OR inactive-only results, never both. The plan never stated which `showInactive=true` mapped to, and `POST /parts/{id}/reactivate` redirects to `/parts?showInactive=true` — under the exclusive-filter reading, the just-reactivated part would immediately vanish from the view the manager lands on. Phase 4's test 4.9 didn't disambiguate either.
- **Fix A ⭐ Recommended**: Make `showInactive=true` a union (active + inactive), not an exclusive filter.
  - Strength: Matches natural UX reading; makes the reactivate redirect meaningful.
  - Tradeoff: Repository signature changes from `active` to `showInactive`; test 4.9 needs an explicit both-states assertion.
  - Confidence: MED — small change, no local precedent for this toggle shape.
  - Blind spot: Whether managers want a dedicated inactive-only review workflow instead — not confirmed.
- **Fix B**: Keep the exclusive filter; redirect reactivate to `/parts` (default view) instead.
  - Strength: Zero contract change, one-line fix.
  - Tradeoff: Loses the "browse and reactivate several in one sitting" flow.
  - Confidence: HIGH.
- **Decision**: FIXED (Fix A) — repository query, Phase 2 controller intent, and Phase 4 test 4.9 updated in plan.md to specify union semantics.

### F2 — FR-003's literal "delete" isn't reconciled with the plan's soft-delete decision

- **Severity**: WARNING
- **Impact**: LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Completeness
- **Location**: What We're NOT Doing (plan.md)
- **Detail**: PRD FR-003 literally says "dodawać, edytować i usuwać" (add, edit, delete). FR-002 (accounts) has an explicit Socratic resolution changing delete to deactivate; FR-003 had no equivalent resolution, even though the plan (user-confirmed in the brief) builds soft-delete instead.
- **Fix**: Add a resolution note to `prd.md` FR-003 mirroring FR-002's Socratic format.
- **Decision**: FIXED — added a second `> Socrates:` resolution line to FR-003 in `context/foundation/prd.md`, dated to this plan's decision, citing the same BOM/history-preservation rationale as FR-002.

### F3 — N+1 queries when rendering the locations column

- **Severity**: OBSERVATION
- **Dimension**: Architectural Fitness
- **Location**: Phase 1 — search query
- **Detail**: `search()` uses a plain `LEFT JOIN` (not `JOIN FETCH`), so rendering each part's location list lazy-loads per part (N+1). Acceptable given the plan's own "small data volume" assumption.
- **Fix**: Note it as an accepted tradeoff in "What We're NOT Doing"; revisit if `S-12`'s CSV import grows the table.
- **Decision**: FIXED — added a "What We're NOT Doing" bullet acknowledging the N+1 tradeoff.
