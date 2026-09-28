<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: Kartoteka części z lokalizacjami (S-02)

- **Plan**: context/changes/parts-catalog/plan.md
- **Scope**: Full plan
- **Reviewed phases**: 1, 2, 3, 4
- **Date**: 2026-09-28
- **Verdict**: APPROVED
- **Findings**: 0 critical, 1 warning, 5 observations

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| Plan Adherence | PASS |
| Scope Discipline | PASS |
| Safety & Quality | WARNING |
| Architecture | PASS |
| Pattern Consistency | PASS |
| Success Criteria | PASS |

Success Criteria confirmed directly: `./mvnw verify` — 29/29 tests green, BUILD SUCCESS.

## Findings

### F1 — DataIntegrityViolationException handling assumes name-uniqueness for any constraint hit

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: src/main/java/pl/regavio/stockahead/parts/PartController.java:122-124, 185-188
- **Detail**: Both `create()` and `edit()` catch `DataIntegrityViolationException` and unconditionally render "Część o tej nazwie już istnieje." Neither `name` nor `location` (both `VARCHAR(255)`) has server-side length validation, so a too-long value would also land in this catch and show a misleading "already exists" message instead of the real cause. Satisfies lessons.md rule 1 (no raw 500) but the message can mislead.
- **Fix**: Add a length check alongside the existing non-blank/quantity validation (mirroring the pattern already used for quantity parsing), so oversized input gets its own friendly message instead of falling into the constraint-violation catch.
- **Decision**: FIXED — added `MAX_FIELD_LENGTH = 255` checks for name (both `create()`/`edit()`) and each location (both methods), returning "Nazwa/Lokalizacja może mieć maksymalnie 255 znaków." before the DB write. `./mvnw verify` still 29/29 green. Committed as 3180159.

### F2 — Search query doesn't eager-fetch locations (N+1 on catalog render)

- **Severity**: ℹ️ OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: src/main/java/pl/regavio/stockahead/parts/PartRepository.java:14-21
- **Detail**: `LEFT JOIN p.locations l` filters but doesn't `JOIN FETCH`, so rendering the locations column in `parts-list.html` lazy-loads per part. This is not new drift — the plan's own "What We're NOT Doing" section names this exact tradeoff explicitly and accepts it given `tech-stack.md`'s small-data-volume assumption, revisiting only if S-12's CSV import grows the table.
- **Fix**: No action needed now; already an accepted, documented tradeoff. Revisit if/when S-12 lands.
- **Decision**: SKIPPED — already an accepted tradeoff per the plan's own "What We're NOT Doing" section.

### F3 — "Zarezerwowane" hardcoded to 0 with no inline stub marker

- **Severity**: ℹ️ OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Pattern Consistency
- **Location**: src/main/resources/templates/parts-list.html:45
- **Detail**: "Dostępne" always equals `quantity` since reservations don't exist yet. This is the FR-005 stub the roadmap already settled (not a decision made in this plan), just without a `<!-- TODO S-04 -->`-style marker for a future reader.
- **Fix**: Optional — add a short comment near the column noting it's a stub until S-04 reservations land.
- **Decision**: SKIPPED

### F4 — No locking on concurrent location edits to the same part

- **Severity**: ℹ️ OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: src/main/java/pl/regavio/stockahead/parts/PartController.java (edit/deactivate/reactivate)
- **Detail**: No `@Version`/pessimistic lock on concurrent edits to the same part's locations (last-write-wins). Consistent with the plan's explicit "Critical Implementation Details" deferral — locations aren't a stock-safety invariant, and the AGENTS.md locking rule is scoped to quantity/reservation mutation, which this slice doesn't do.
- **Fix**: No action needed — correctly out of scope per the plan.
- **Decision**: SKIPPED — deliberately deferred per the plan's "Critical Implementation Details" section.

### F5 — Stale doc cross-reference in test Javadoc

- **Severity**: ℹ️ OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Pattern Consistency
- **Location**: src/test/java/pl/regavio/stockahead/parts/PartsCatalogIntegrationTests.java (duplicate-name race test Javadoc)
- **Detail**: The Javadoc references "the '(race)' comment in `PartController.create()`" — no such comment exists in the controller's catch block.
- **Fix**: Remove or correct the dangling cross-reference in the Javadoc.
- **Decision**: FIXED — reworded the Javadoc to describe the catch block directly instead of referencing a nonexistent "(race)" comment. Committed as 3180159.

### F6 — A few assertions read via `partRepository.findById()` directly instead of the `transactionTemplate.execute` wrapper

- **Severity**: ℹ️ OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Pattern Consistency
- **Location**: src/test/java/pl/regavio/stockahead/parts/PartsCatalogIntegrationTests.java (e.g. `reactivatingPartMakesItReappearInDefaultList`, `editNeverChangesQuantityEvenWhenQuantityParamIsInjected`)
- **Detail**: These calls only touch scalar fields (`isActive()`, `getQuantity()`), so they don't hit the lazy-collection issue fixed elsewhere in this phase — Spring Data self-wraps `findById` in its own transaction, so behaviorally this is still a fresh-transaction read. Harmless, just stylistically inconsistent with the `locationIdsOf`/`locationValuesOf`/`locationIdOf` helpers used for collection-touching assertions.
- **Fix**: No action needed — not a bug, purely stylistic.
- **Decision**: SKIPPED
