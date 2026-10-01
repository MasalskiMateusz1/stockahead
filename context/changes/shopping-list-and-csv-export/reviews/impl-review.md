<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: Lista zakupów i eksport do CSV

- **Plan**: context/changes/shopping-list-and-csv-export/plan.md
- **Scope**: Full plan
- **Reviewed phases**: 1, 2, 3, 4
- **Date**: 2026-10-01
- **Verdict**: NEEDS ATTENTION
- **Findings**: 0 critical, 2 warnings, 0 observations

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| Plan Adherence | WARNING |
| Scope Discipline | PASS |
| Safety & Quality | WARNING |
| Architecture | PASS |
| Pattern Consistency | PASS |
| Success Criteria | PASS |

## Findings

### F1 — CSV export does not neutralize Excel formula injection

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: src/main/java/pl/regavio/stockahead/purchasing/ShoppingListCsvWriter.java:58-65
- **Detail**: `escapeField` correctly RFC4180-quotes fields containing `;`, `"`, `\r`, or `\n` (verified against `ShoppingListIntegrationTests.java:299`), which prevents structural CSV injection. It does not neutralize a field whose content *starts* with `=`, `+`, `-`, or `@` — the classic CSV/Excel "formula injection" class. A part or project named e.g. `=1+1` or `=cmd|'/c calc'!A1` would open in Excel (the file's own stated purpose — BOM is there precisely for Excel compatibility) as a live formula rather than literal text. Part/project names are entered by authenticated manager/technician accounts, not anonymous public input, so exploitability is low — but the file is explicitly built for export and onward sharing, which crosses a trust boundary once it leaves the app.
- **Fix**: In `escapeField`, when the field's first character is one of `=+-@`, prefix it with a protective character (e.g. a leading `'` or space) before applying the existing quoting logic, matching the standard CSV-export mitigation for this class of issue.
- **Decision**: FIXED — added `neutralizeLeadingFormulaChar` to `ShoppingListCsvWriter.escapeField`, prefixing a leading apostrophe when a field starts with `=+-@`, applied before the existing RFC4180 quoting check.

### F2 — CSV row shape deviates from Phase 3's plan contract

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Plan Adherence
- **Location**: src/main/java/pl/regavio/stockahead/purchasing/ShoppingListCsvWriter.java, src/main/java/pl/regavio/stockahead/purchasing/ShoppingListController.java
- **Detail**: Plan specified one CSV row per part (header `Część;Brakująca ilość;Zablokowane zlecenia`, blocked orders joined into one field as composed sentences). The shipped code (commit 3819f91) flattens to one row per (part, blocking order) pair, 4 columns (`Część;Zlecenie;Termin;Brakująca ilość`), with order/date/quantity as separate columns. This was a disclosed, deliberate change — the commit message states it was "requested during manual verification for easier order-by-order scanning" — and verification confirms: `ShoppingListModel.rowsForExport()` still returns the plan's per-part aggregated shape (the flattening happens only in the writer), the `/purchasing` HTML screen is unaffected and still renders one row per part as planned, no data is lost (same four facts, now columnar instead of prose), and `ShoppingListIntegrationTests` was updated to assert the new shape consistently (no stale assertions against the old one). One side effect beyond the commit's own deviation note: the CSV header text was also externalized through `MessageSource`/`Locale` (`ShoppingListController.csvHeader()`) rather than hardcoded — not mentioned in the plan or the commit's deviation note, though it follows this codebase's existing i18n convention used by other controllers, so it's low-risk on its own.
- **Fix A ⭐ Recommended**: Update the plan's Phase 3 contract (and the Phase 4 success-criteria text that still describes the old joined-field shape) with an addendum documenting the shipped per-order-row CSV shape and the header's MessageSource-based i18n, so the plan matches what a manager actually downloads today.
  - Strength: Preserves the already-tested, deliberately-chosen UX improvement; keeps the plan as an accurate source of truth for future changes to this screen (e.g. the not-yet-built CSV *import* in FR-017/S-12 will need to match whatever shape this export actually produces).
  - Tradeoff: The plan becomes a moving target relative to what was originally reviewed/approved.
  - Confidence: HIGH — this repo's plans are routinely amended with addenda for scope discovered mid-implementation (see this plan's own Progress section commit-linking convention).
  - Blind spot: Whether any other document (e.g. a future CSV-import plan) already assumes the old per-part joined-field shape.
- **Fix B**: Revert `ShoppingListCsvWriter` to the plan's original one-row-per-part, 3-column, joined-field shape.
  - Strength: Restores exact plan conformance.
  - Tradeoff: Discards a change the user explicitly requested during manual verification for a concrete usability reason (easier order-by-order scanning); would need re-verification and re-testing.
  - Confidence: MEDIUM — reverses a decision already validated by the person who made it, without new information suggesting it was wrong.
  - Blind spot: No evidence this is actually desired — included only because a genuine tradeoff exists.
- **Decision**: FIXED via Fix A — added a "post-implementation" addendum to plan.md's Phase 3 CSV-writer contract documenting the shipped per-order-row shape, the `MessageSource`-based header, and the F1 formula-injection fix.
