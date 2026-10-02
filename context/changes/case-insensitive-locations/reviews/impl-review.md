<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: Case-insensitive Locations

- **Plan**: context/changes/case-insensitive-locations/plan.md
- **Scope**: Full plan
- **Reviewed phases**: 1, 2, 3
- **Date**: 2026-10-02
- **Verdict**: APPROVED
- **Findings**: 0 critical, 1 warning, 1 observation

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| Plan Adherence | PASS |
| Scope Discipline | PASS |
| Safety & Quality | WARNING |
| Architecture | PASS |
| Pattern Consistency | PASS |
| Success Criteria | PASS |

Notes: `./mvnw verify` passes at HEAD (f7000f4). Every planned change is present and matches its intent: V10 in the specified order, `normalize` edge-stripping, `constraintErrorKey`, the single-pass `reconcileLocations`, `LocationSpellings`, `findSpellingsOf` / `respellOnOtherParts`, and wiring into create, edit and delivery. Every test the plan named exists. No unplanned files were changed. Manual items 2.5, 3.5 and 3.6 are ticked; they can't be confirmed from the diff.

## Findings

### F1 — Respelling a shelf on edit can race a delivery and leave two spellings

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Safety & Quality
- **Location**: src/main/java/pl/regavio/stockahead/parts/PartController.java:255-257, src/main/java/pl/regavio/stockahead/parts/DeliveryController.java:173-179
- **Detail**: `respellOnOtherParts` rewrites other parts' rows without locking those parts. A delivery only locks its own parts and reads the canonical spelling through `findSpellingsOf` (READ COMMITTED). Example: X and Z hold `A1`, and a manager edits X to `a1` while a delivery adds `a1` to Y. The delivery resolves `A1` before the edit commits. The edit then renames X and Z to `a1`, and the delivery inserts `A1` on Y. Different parts, so the per-part index doesn't object. The shelf now has two spellings, and the datalist shows both. The plan's Non-Goals accept this race only for a brand-new shelf; this is the same gap for an existing shelf, which the plan doesn't record.
- **Fix A ⭐ Recommended**: Record this race next to the existing cross-part race in the plan's "What We're NOT Doing" / brief Open Risks.
  - Strength: Costs nothing and matches the plan's decision to keep cross-part spelling app-level only. It is rare (a case-only rename by a manager at the same moment as a receipt), and a later edit repairs it.
  - Tradeoff: The "one spelling per shelf" invariant stays best-effort.
  - Confidence: HIGH — consistent with the plan's existing accepted risk.
  - Blind spot: How often managers respell shelves in practice.
- **Fix B**: In `LocationSpellings.resolve` and before `respellOnOtherParts`, take a transaction-scoped advisory lock keyed on the shelf (`pg_advisory_xact_lock(hashtext(lower(:location)))`).
  - Strength: Closes this race and the brand-new-shelf race the plan accepted.
  - Tradeoff: New locking primitive in the codebase; it needs a concurrency test and care around lock ordering (lessons.md "Narrowing ReservationAllocator's part-row lock" warns about multi-round locking and deadlocks).
  - Confidence: MED — the mechanism is standard Postgres, but the delivery path resolves several shelves per request, so locks must be taken in a sorted order.
  - Blind spot: Interaction with `lockRetry` on the delivery path is untested.
- **Decision**: FIXED via Fix B — `LocationSpellings.lockShelves` (per-shelf `pg_advisory_xact_lock`, sorted, always before part-row locks) wired into create, edit and delivery; `LocationSpellingConcurrencyTests` (red before, green after); plan addendum + brief Open Risks updated.

### F2 — Case matching depends on the production database's ctype

- **Severity**: 💡 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: src/main/resources/db/migration/V10__case_insensitive_part_locations.sql:19, src/main/java/pl/regavio/stockahead/parts/PartRepository.java:42
- **Detail**: In Java, `sameLocation` uses `equalsIgnoreCase`, which is Unicode-aware. The V10 index and `findSpellingsOf` use Postgres `lower()`, which follows the cluster's ctype. Tests run on the Testcontainers image (`en_US.utf8`), so `Regał A1` and `REGAŁ A1` match. A production cluster created with libc `C` ctype lowercases ASCII only. There, Polish shelf names would miss the cross-part lookup and wouldn't be deduplicated by the index. No production DB exists yet (no `context/deployment/`).
- **Fix**: When the deploy plan is written, require a UTF-8-aware ctype for the database (e.g. `en_US.UTF-8`, or the `builtin` provider's `C.UTF-8` on PG17+) and add it as a verification step.
- **Decision**: FIXED — queued as a deploy-plan requirement in `follow-ups/review-fixes.md` (ctype check + verification query).

## Triage summary

- Fixed: F1 (Fix B), F2 (queued as a deploy-plan requirement)
- `./mvnw verify` passes after the fixes (2026-10-02).
