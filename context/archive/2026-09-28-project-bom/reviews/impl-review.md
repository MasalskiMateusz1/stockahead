<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: Projekty urządzeń i BOM (S-03)

- **Plan**: context/changes/project-bom/plan.md
- **Scope**: Full plan
- **Reviewed phases**: 1, 2, 3, 4
- **Date**: 2026-09-28
- **Verdict**: APPROVED
- **Findings**: 0 critical, 2 warnings, 5 observations

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| Plan Adherence | WARNING |
| Scope Discipline | PASS |
| Safety & Quality | WARNING |
| Architecture | PASS |
| Pattern Consistency | PASS |
| Success Criteria | PASS |

Success criteria: `./mvnw verify` on 126c174 (rebased onto origin/main ea07131, V1–V5) — 114 tests, 0 failures; project classes 14 + 16 + 12 + 7. All 10 manager routes have a technician-403 test. Manual items 2.6, 3.9, 4.6 confirmed by the user during implementation; 4.7 done in-session (rebase, verify).

## Findings

### F1 — MessagesBundleTests lacks the projects.links.* assertions

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Adherence
- **Location**: src/test/java/pl/regavio/stockahead/MessagesBundleTests.java
- **Detail**: Plan Phase 4 §3 (plan.md:271) requires one exact-text assertion per new `projects.links.*` key. The five keys (`urlRequired`, `urlTooLong`, `urlInvalid`, `labelTooLong`, `saveFailed`) have none; `saveFailed` is not asserted anywhere, and `urlTooLong`'s `{0,number,#}` format choice is pinned only indirectly by ProjectLinkIntegrationTests. Cause: the Phase 4 subagent was briefed from a stale copy of the plan.
- **Fix**: Add five exact-text assertions to MessagesBundleTests in the style of the existing `projects.bom.*` ones.
- **Decision**: FIXED — five exact-text assertions added to MessagesBundleTests

### F2 — Plan still describes GET /projects/{id}/edit and projects-edit.html

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Adherence
- **Location**: context/changes/project-bom/plan.md:143, :149, :176
- **Detail**: Commit 9062d67 deliberately moved rename onto the detail page (found during manual check) and removed the edit route and template; the deviation is recorded only in the commit message. The plan's Phase 2 contract, file list and success criterion still name `GET /projects/{id}/edit` and `projects-edit.html`, so a future reader (or S-04 planning) sees a route that does not exist.
- **Fix**: Add a short "Deviation" note under Phase 2 (rename lives on `project-detail`; `/edit` and `projects-edit.html` removed in 9062d67; 403 for that route replaced by an "absent rename form" assertion).
- **Decision**: FIXED — Deviation note added under Phase 2 in plan.md

### F3 — URL check rejects some legitimate intranet/IDN URLs with a misleading error

- **Severity**: 🔍 OBSERVATION
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Safety & Quality
- **Location**: src/main/java/pl/regavio/stockahead/projects/ProjectLinkController.java:141-155
- **Detail**: `java.net.URI#getHost()` returns null for `http://file_server/docs` (underscore) and `https://żółw.pl/x` (IDN), and `new URI` throws on an unencoded space. All are rejected with "musi … zawierać nazwę hosta". Fails closed, so not a security issue, but a small plant's NAS/intranet hostnames often contain underscores.
- **Fix A ⭐ Recommended**: Keep the strict check, reword `urlInvalid` to mention a correct host and no spaces, and add the underscore/space cases to the rejected-URL test.
  - Strength: No change to the security boundary the plan specified ("non-empty host"); behaviour becomes documented and tested.
  - Tradeoff: Managers with underscore hostnames must use an IP or DNS alias.
  - Confidence: HIGH — two-line message change plus test rows.
  - Blind spot: Unknown whether the plant's documentation servers use underscore hostnames.
- **Fix B**: Fall back to `URI#getRawAuthority()` (minus userinfo/port) when `getHost()` is null, keeping the scheme whitelist.
  - Strength: Accepts real intranet/IDN URLs.
  - Tradeoff: Hand-rolled authority parsing is easy to get subtly wrong; deviates from the plan's `URI` contract.
  - Confidence: MED — needs its own test matrix.
  - Blind spot: Edge cases like `http://@/`, `http://:80/`.
- **Decision**: FIXED via Fix A — urlInvalid reworded (valid host, no underscores/Polish characters, no spaces); underscore, IDN and space cases added to the rejected-URL test

### F4 — ProjectDetailModel javadoc misstates why post-rollback reads are safe

- **Severity**: 🔍 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: src/main/java/pl/regavio/stockahead/projects/ProjectDetailModel.java:15-22
- **Detail**: The javadoc says the model loads in "its own fresh … transaction", but with `spring.jpa.open-in-view` at its default (true) the read reuses the request-bound EntityManager; it is safe because `JpaTransactionManager` clears that EntityManager on rollback. Works today (duplicate-rename/BOM tests re-render), but the comment points a future maintainer at the wrong mechanism.
- **Fix**: Reword the javadoc to name the actual mechanism (request-bound EntityManager cleared on rollback under open-in-view).
- **Decision**: FIXED — javadoc now names the real mechanism (request-bound EntityManager cleared on rollback)

### F5 — changeQuantity writes bom_lines without a DataIntegrityViolationException catch

- **Severity**: 🔍 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: src/main/java/pl/regavio/stockahead/projects/ProjectBomController.java:128-132
- **Detail**: Lesson "Handle DB constraint violations at write boundaries" — the update touches a CHECK/UNIQUE-guarded table without a catch. Currently unreachable (qty validated ≥ 1 first; UNIQUE columns unchanged), so no live 500, but a future edit (e.g. changing a line's part) would silently introduce one.
- **Fix**: Add a catch outside the transaction that re-renders the detail page with a generic error, matching `addLine`.
- **Decision**: FIXED — changeQuantity catches DataIntegrityViolationException outside the transaction and re-renders with row error `projects.bom.error.saveFailed` (+ bundle assertion); catch path currently unreachable, so no integration test

### F6 — Detail page loads the whole active catalog, even for technicians

- **Severity**: 🔍 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: src/main/java/pl/regavio/stockahead/projects/ProjectDetailModel.java:84-86
- **Detail**: Every render runs `partRepository.search("", false)` (DISTINCT + LEFT JOIN on locations, unbounded) to fill the add-line `<select>`, including for technicians who never see it. Fine at MVP volume; grows with CSV import (FR-017).
- **Fix**: Load `activeParts` only when `isManager`.
- **Decision**: FIXED — activeParts queried only when isManager (empty list otherwise)

### F7 — No index on project_links.project_id / bom_lines.part_id

- **Severity**: 🔍 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: src/main/resources/db/migration/V5__create_projects.sql:18
- **Detail**: `bom_lines.project_id` is covered by `UNIQUE (project_id, part_id)`, but `project_links.project_id` and `bom_lines.part_id` are unindexed. Irrelevant at MVP volume; S-04 will likely query BOM lines by part.
- **Fix**: Defer — add indexes in a new migration when S-04 introduces part-driven BOM queries (V5 must not be edited once deployed).
- **Decision**: ACCEPTED (deferred) — queued in follow-ups/review-fixes.md for S-04; add indexes in a new migration
