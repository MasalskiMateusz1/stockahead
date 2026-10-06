<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: Company-Scoped Parts Catalog

- **Plan**: context/changes/company-scoped-parts/plan.md
- **Scope**: Full plan
- **Reviewed phases**: 1, 2
- **Date**: 2026-10-06
- **Verdict**: APPROVED
- **Findings**: 0 critical, 0 warnings, 4 observations

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| Plan Adherence | PASS |
| Scope Discipline | PASS |
| Safety & Quality | PASS |
| Architecture | PASS |
| Pattern Consistency | PASS |
| Success Criteria | PASS |

Evidence: `./mvnw verify` on HEAD passed (539 tests, 0 failures; PartsCompanyIsolationIntegrationTests 7/7, PartCompanySchemaTests 2/2). `grep -n "findById(" .../PartController.java` returned nothing. Manual checks 1.4, 2.6 and 2.7 were confirmed by the user in-session. Plan drift review: every planned item MATCH, nothing MISSING or EXTRA. The "NOT doing" items were confirmed untouched.

## Findings

### F1 — Delivery resolves shelves under the receiver's company for posted foreign part ids

- **Severity**: OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: src/main/java/pl/regavio/stockahead/parts/DeliveryController.java:161-163
- **Detail**: `applyReceipt` takes shelf locks and spellings from the principal's company but loads parts by posted, unscoped ids (deferred to S-05). If B posts A's part id, the shelf is resolved against B's spellings and lock keys, not A's, so A could end up with two spellings of one shelf. The FK stays consistent, because the location takes its part's company. The roadmap's S-05 Risk note names the bare lookup but not this effect.
- **Fix**: Append a clause to the S-05 Risk line in roadmap.md: scoping the posted ids also fixes the cross-company shelf resolution and locking in `applyReceipt`.
- **Decision**: FIXED — clause appended to roadmap S-05 Risk

### F2 — Concurrency test has a 3 s timing margin

- **Severity**: OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: src/test/java/pl/regavio/stockahead/parts/PartsCompanyIsolationIntegrationTests.java:349-372
- **Detail**: The test asserts that B's request completes while A sits in a 3 s `pg_sleep`. A GC pause or cold JIT on a slow runner could exceed that and fail the test falsely. A real lock-key collision fails every time, so the signal is sound. The trigger is always dropped (finally, @BeforeEach, @AfterEach).
- **Fix**: Raise the slowdown to ~6 s. The extra time is spent only when the test fails, because the passing path doesn't wait out A's sleep.
- **Decision**: FIXED — slowdown raised to 6 s (note: passing path also waits it out, +3 s per run)

### F3 — Future operator account would see an empty catalog instead of 403 on GET /parts

- **Severity**: OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Architecture
- **Location**: src/main/java/pl/regavio/stockahead/parts/PartController.java:68-74
- **Detail**: `GET /parts` has no `@PreAuthorize`, so any authenticated user reaches it. An operator with `companyId == null` would get an empty list (`p.company.id = null` matches nothing). Nothing leaks, but AGENTS.md says the operator has no route to warehouse controllers. No operator exists yet; that is S-08.
- **Fix**: Add a Risk note to roadmap S-08: block the operator on warehouse routes (role gate), not by relying on empty results.
- **Decision**: FIXED — note appended to roadmap S-08 Risk

### F4 — Composite-FK violation would surface as "duplicate name"

- **Severity**: OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: src/main/java/pl/regavio/stockahead/parts/PartController.java:428-436
- **Detail**: `constraintErrorKey` maps every violation other than the location index to `parts.error.duplicateName`, now including `part_locations_part_company_fkey`. That path is unreachable today, because every write sets the location's company from its part.
- **Fix**: No change. Revisit only if a write path ever sets `PartLocation.companyId` independently.
- **Decision**: ACCEPTED — path unreachable today; revisit if PartLocation.companyId is ever set independently of its part
