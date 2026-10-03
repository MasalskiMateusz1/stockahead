<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: Stock Correction (S-11) Implementation Plan

- **Plan**: context/changes/stock-correction/plan.md
- **Scope**: Full plan
- **Reviewed phases**: 1, 2, 3, 4
- **Date**: 2026-10-03
- **Verdict**: APPROVED
- **Findings**: 0 critical, 0 warnings, 3 observations

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| Plan Adherence | PASS |
| Scope Discipline | PASS |
| Safety & Quality | PASS |
| Architecture | PASS |
| Pattern Consistency | PASS |
| Success Criteria | PASS |

## Evidence

- Commits reviewed: cb7a411, 7506059, 98907b3, 7936415, 9910dae, 4e490c0 (20 files). Every plan file is in the diff; the only unplanned code is `OrderLineRepository.reservedQuantityForPart` (backs the planned "reserved" figure) and finer-grained `corrections.error.*` keys, all pinned in `MessagesBundleTests`.
- Automated: `./mvnw verify` green at 4e490c0's tree (349 tests, 0 failures). `StockCorrectionConcurrencyTests` (6), `StockCorrectionIntegrationTests` (29), `MessagesBundleTests` (51) green on 3 consecutive runs.
- Manual: all Progress manual items `[x]` with SHAs; the UI, routes and messages they describe exist in the diff.
- Safety: `@PreAuthorize` on GET + both POSTs with 403 tests; only `th:text`/`th:value` in templates; CSRF via `th:action`; no `Part` loaded before `findByIdInForUpdate`; stale/below-zero checks under the lock; `BigInteger` + bounds + `Math.addExact`; `JOIN FETCH` for author; flush before `reallocateForParts`.

## Findings

### F1 — Shortage assertions not tied to the part's row

- **Severity**: 💡 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Success Criteria
- **Location**: src/test/java/pl/regavio/stockahead/parts/StockCorrectionIntegrationTests.java:436, :486
- **Detail**: `/purchasing` checks are `contains("Mikrokontroler")` + `contains("<td>4</td>")` (and `<td>3</td>`) — they pass if any cell on the page holds that number, not necessarily the part's shortage. The Desired End State's "shortage on the order detail" is not asserted anywhere.
- **Fix**: Assert the shortage within the part's row (e.g. regex `Mikrokontroler.*?<td>4</td>` up to `</tr>`), and add one order-detail assertion in the taken-deficit test.
- **Decision**: FIXED — `rowCells` helper pins /purchasing shortage + blocked order to the part's row in both tests; taken-deficit test now asserts the order-detail row (10 / 5 / 2 / 3). 29/29 green.

### F2 — Form headings differ from the plan

- **Severity**: 💡 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Adherence
- **Location**: src/main/resources/templates/parts-correction.html:23, :37
- **Detail**: Plan names the forms "Ustaw stan na" and "Dodaj / odejmij"; the template says "Ustaw stan po spisie" and "Zmień stan o". Cosmetic; behaviour matches.
- **Fix**: Pick one wording and align plan or template.
- **Decision**: FIXED — kept the page wording; plan.md and plan-brief.md now say "Ustaw stan po spisie" and "Zmień stan o" (Progress titles untouched).

### F3 — Correction history is unbounded

- **Severity**: 💡 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: src/main/java/pl/regavio/stockahead/parts/StockCorrectionRepository.java:12-14
- **Detail**: `findByPartIdNewestFirst` loads every correction for the part on each render, including error re-renders. Corrections are manual and rare, so low risk at expected volume.
- **Fix**: Leave as is, or show the latest N (e.g. `Pageable`/`Limit` of 50).
- **Decision**: FIXED — `findByPartIdNewestFirst(partId, Limit)` with `HISTORY_LIMIT = 50` in the controller; new test `pageListsAtMostTheNewestFiftyCorrections`; plan Phase 2 contract updated.
