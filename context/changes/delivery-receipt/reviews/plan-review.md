<!-- PLAN-REVIEW-REPORT -->
# Plan Review: Delivery Receipt (S-10) Implementation Plan

- **Plan**: context/changes/delivery-receipt/plan.md
- **Mode**: Deep (claims checked inline against the code; no sub-agent)
- **Date**: 2026-10-02
- **Verdict**: SOUND
- **Findings**: 0 critical, 2 warnings, 1 observation
- **Note**: The plan was reviewed after implementation (p1–p3 committed, impl-review triaged), so its claims were checked against the code as built.

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| End-State Alignment | PASS |
| Lean Execution | PASS |
| Architectural Fitness | PASS |
| Blind Spots | WARNING |
| Plan Completeness | WARNING |

## Grounding
10/10 paths ✓, 6/6 symbols ✓, brief↔plan ✓, Progress↔Phase ✓ (3/3 phases, 10/10 criteria). `docs/reference/contract-surfaces.md` is absent, so that check was skipped.

## Findings

### F1 — Concurrency phase misses the other writer of parts rows

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Blind Spots
- **Location**: Phase 3 scenarios; Phase 2 §2 (saveFailed bullet)
- **Detail**: Phase 3 covers a receipt racing a pick and an order creation, but not `PartController` edit/deactivate/reactivate. Those handlers write `Part` without a lock or `@Version` while open-in-view is on, so they can overwrite delivered stock and leave reserved > stock. The Phase 2 saveFailed bullet implied that a concurrent manager edit was covered. Impl-review F1 found this afterwards and queued it.
- **Fix**: Add a "What We're NOT Doing" line pointing at `follow-ups/review-fixes.md` F1, and reword the saveFailed bullet.
- **Decision**: FIXED — both plan edits applied

### F2 — Plan says case-sensitive location match; the code is now case-insensitive

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Completeness
- **Location**: Phase 2 §2 Contract
- **Detail**: The plan said "exactly equal string (case-sensitive)". Impl-review F6 switched both controllers to `PartLocation.sameLocation` (ignores case). The DB `UNIQUE (part_id, location)` stays case-sensitive, so a race against an edit that uses a different case isn't caught by saveFailed.
- **Fix**: Rewrite the bullet and add an addendum naming `locationsAreMatchedIgnoringCase`.
- **Decision**: FIXED — bullet rewritten and addendum added

### F3 — Trailing-NBSP locations remain unhandled

- **Severity**: 💡 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Blind Spots
- **Location**: Phase 1 §1 (location is trimmed)
- **Detail**: `String.trim()` (and `strip()`) keep U+00A0, so "A1" pasted from a spreadsheet with a trailing NBSP became a separate shelf.
- **Fix**: Normalize non-breaking spaces in both controllers.
- **Decision**: FIXED differently (code change at the user's request)
  - Added `PartLocation.normalize`, which turns U+00A0/U+2007/U+202F into spaces and then calls `strip()`.
  - `DeliveryController` validation and `PartController.parseLocations` both use it.
  - `sameLocation` compares normalized values.
  - Tests added: `DeliveryIntegrationTests.locationsPastedWithNonBreakingSpacesMatchTypedOnes`, `PartsCatalogIntegrationTests.nonBreakingSpacesInLocationsAreNormalizedOnCreate` and `createWithLocationLinesDifferingOnlyByNonBreakingSpaceFailsValidation`.
  - Plan Phase 1 §1 has an addendum.
