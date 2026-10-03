<!-- PLAN-REVIEW-REPORT -->
# Plan Review: Parts CSV Import (S-12)

- **Plan**: context/changes/parts-csv-import/plan.md
- **Mode**: Deep
- **Date**: 2026-10-03
- **Verdict**: REVISE → SOUND after triage
- **Findings**: 0 critical, 4 warnings, 1 observation

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| End-State Alignment | PASS |
| Lean Execution | PASS |
| Architectural Fitness | WARNING |
| Blind Spots | WARNING |
| Plan Completeness | WARNING |

## Grounding
10/10 paths ✓, 7/7 symbols ✓, brief↔plan ✓, Progress↔Phase ✓. Allocator locks only the given ids; LockRetry catches only PessimisticLockingFailureException; CSRF is enabled by default.

## Findings

### F1 — Oversized upload won't reach the friendly message

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Blind Spots
- **Location**: Phase 1 §3 — Upload page and route
- **Detail**: With max-file-size=1MB, the CSRF token in the multipart body is lost when the parse fails (CsrfFilter → 403). MaxUploadSizeExceededException is thrown before a handler is mapped, so a controller-local @ExceptionHandler can't see it. Tomcat resets connections above max-swallow-size. MockMvc hides all three.
- **Fix**: Multipart limits at 10MB, enforce 1 MB in the controller via file.getSize(), plus a manual 3 MB browser upload step (1.8).
- **Decision**: FIXED

### F2 — Name matching splits across SQL LOWER and Java folding

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Architectural Fitness
- **Location**: Phase 2 §1; Name-matching predicate
- **Detail**: Postgres lower() depends on LC_CTYPE (under C/POSIX it doesn't fold Ł/Ś/Ż). Catalog names are trim()-ed, not normalize()-d, so a trailing NBSP never matches and the import creates a near-duplicate.
- **Fix A ⭐ Recommended**: Java-side matching over a scalar (id, name) read; lock matched ids with findByIdInForUpdate; re-check names after the lock.
- **Fix B**: Keep SQL LOWER, assert the DB ctype, document the NBSP gap.
- **Decision**: FIXED (Fix A)

### F3 — Re-render after rollback uses detached entities

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Completeness
- **Location**: Phase 3 §1 steps 3–4; Critical Implementation Details
- **Detail**: With OSIV, rollback clears the EntityManager, so lazy locations on the locked Parts throw LazyInitializationException. The duplicate-name path contradicted itself ("wgraj ponownie" vs re-render preview).
- **Fix**: The transaction returns an outcome only; the preview is rebuilt with the Phase 2 builder after the transaction; one message for the duplicate-name path.
- **Decision**: FIXED

### F4 — Error row numbers drift from Excel on quoted newlines

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Completeness
- **Location**: Phase 1 §1 — PartsCsvReader rowNumber
- **Detail**: Physical line numbers diverge from Excel rows after a quoted multi-line cell.
- **Fix**: rowNumber = record ordinal; added a quoted-newline test case.
- **Decision**: FIXED

### F5 — Case-variant new-part race not guarded

- **Severity**: 💡 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Blind Spots
- **Location**: Phase 4 scenario 4; Critical Implementation Details
- **Detail**: parts.name is UNIQUE only case-sensitively, so concurrent case-variant creates both commit.
- **Fix**: Proposed: document only. User chose: "all imported names should be case insensitive". Applied a per-name advisory lock (own namespace, after shelf locks, before the scalar name read) in the import and PartController.create, plus two Phase 4 race scenarios.
- **Decision**: FIXED (differently)
