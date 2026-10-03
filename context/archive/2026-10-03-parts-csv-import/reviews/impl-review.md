<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: Parts CSV Import (S-12)

- **Plan**: context/changes/parts-csv-import/plan.md
- **Scope**: Full plan
- **Reviewed phases**: 1, 2, 3, 4
- **Date**: 2026-10-03
- **Verdict**: NEEDS ATTENTION
- **Findings**: 0 critical, 3 warnings, 3 observations

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| Plan Adherence | WARNING |
| Scope Discipline | WARNING |
| Safety & Quality | WARNING |
| Architecture | PASS |
| Pattern Consistency | PASS |
| Success Criteria | WARNING |

Automated: `./mvnw verify` exit 0. 463 tests, 0 failures, 0 errors. PartImportIntegrationTests 45, PartsCsvReaderTests 21, PartsImportFileTests 14, PartImportConcurrencyTests 7. Every scenario named in the Success Criteria exists in the tests. Manual items 1.6–4.3 are all checked.

## Findings

### F1 — Part rename bypasses the case-insensitive name lock

- **Severity**: ⚠️ WARNING
- **Impact**: 🔬 HIGH — architectural stakes; think carefully before deciding
- **Dimension**: Safety & Quality
- **Location**: src/main/java/pl/regavio/stockahead/parts/PartController.java:235 (edit), :265-270
- **Detail**: The import keeps names unique ignoring case only if every writer that creates or renames a part takes `lockPartNames` and rejects case variants. `create` was updated (:167-172); `edit` was not. `edit` still uses the case-sensitive `findByName` (:235) and takes only shelf locks before `part.setName` (:270). This has two effects:
  1. A manager can rename a part to `rezystor 10K` while `Rezystor 10k` exists. Every later import naming it is then rejected as `ambiguousName` until someone renames one of them.
  2. An import confirm can race a concurrent rename to a case variant and leave two parts that differ only in case. PartImportConcurrencyTests has no test for import vs. rename.

  The create form and the edit form now disagree on what counts as a duplicate.
- **Fix A ⭐ Recommended**: In the `edit` transaction, call `lockPartNames(List.of(trimmedName))` right after `lockShelves`, then run create's `CatalogNameIndex` check excluding the part's own id. Add an integration test and an import-vs-rename concurrency test.
  - Strength: Closes the last writer of `parts.name` outside the lock protocol and uses the same lock order as create (shelves → names → rows). The helper and the check already exist.
  - Tradeoff: Touches `PartController.edit`, which is outside the plan's file list. Manual renames to a case variant become a new rejection.
  - Confidence: HIGH — the edit path is a mechanical copy of the create change.
  - Blind spot: Existing data may already hold case variants; this fix does not clean them up.
- **Fix B**: Record it as a follow-up change and accept the gap for now.
  - Strength: Keeps this change within its planned scope.
  - Tradeoff: The import's "unique ignoring case" guarantee stays incomplete, and rename is a real UI path a manager uses.
  - Confidence: MED — the race is unlikely at PRD scale, but the single-request case is easy to hit.
  - Blind spot: How often managers rename parts in practice.
- **Decision**: FIXED via Fix A — `edit` takes `lockPartNames` after the shelf locks and rejects a case variant held by another part. Tests added: `PartsCatalogIntegrationTests.editRenamingToACaseVariantOfAnotherPartIsRejected` and `editChangingOnlyTheCaseOfItsOwnNameSucceeds`, plus `PartImportConcurrencyTests.importAndRenameToACaseVariantNeverLeaveTwoCaseVariantParts` with a new rename `pg_sleep` trigger. Both new tests fail against the pre-fix code.

### F2 — Unplanned case-insensitive duplicate check in manual part create

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Scope Discipline
- **Location**: src/main/java/pl/regavio/stockahead/parts/PartController.java:171-173, :192-195
- **Detail**: The plan asked only for the name lock in `create`. The code also rejects any case variant of an existing name with `parts.error.duplicateName`. The Phase 4 test "exactly one part exists" needs this check, so the addition is justified. It does change manual-create behaviour outside the import: `kondensator` next to `Kondensator` is now rejected, where before it was allowed. No PartsCatalog test covers this rule on its own; only the concurrency test reaches it.
- **Fix**: Add a plan addendum recording this behaviour change. Add a catalog integration test showing that a case-variant create is rejected with the duplicate-name error.
  - Strength: Updates the plan, which is the source of truth, and pins the new rule outside the concurrency suite.
  - Tradeoff: A few lines of plan text and one test.
  - Confidence: HIGH — the behaviour is intended and required by Phase 4.
  - Blind spot: None significant.
- **Decision**: FIXED — plan addendum records case-insensitive name uniqueness for create and edit; test `PartsCatalogIntegrationTests.createWithACaseVariantOfAnExistingNameIsRejected` added.

### F3 — N+1 location loads in confirm while all locks are held

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: src/main/java/pl/regavio/stockahead/parts/PartImportController.java:247-250, :336
- **Detail**: `findByIdInForUpdate` does not fetch `locations`. `addMissingLocations` then triggers one lazy SELECT per matched part, up to 5,000. Every shelf, name and part-row lock is held during these loads, so deliveries, picks and order creation wait on the whole series. The preview path already avoids this with `findWithLocationsByIdIn`.
- **Fix**: After the `FOR UPDATE` query, call `partRepository.findWithLocationsByIdIn(matchedIds)` in the same transaction. It initializes the location collections of the already-locked managed instances in one query. Don't add `JOIN FETCH` to the `FOR UPDATE` query itself.
- **Decision**: FIXED — confirm calls `findWithLocationsByIdIn(matchedIds)` right after the `FOR UPDATE` lock; import integration and concurrency tests pass.

### F4 — English column headers accepted (not in the format predicate)

- **Severity**: 💡 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Scope Discipline
- **Location**: src/main/java/pl/regavio/stockahead/parts/PartsCsvReader.java:19-21, :46-48 (also messages.properties:112, parts-import.html:21)
- **Detail**: The plan's format predicate, meant to be carried over verbatim, lists `Nazwa` / `Ilość` / `Ilosc` / `Lokalizacja`. The reader also accepts `Name` / `Quantity` / `Location`. The upload page, the error message and the tests all document this. Header matching also uses `normalize` + NFC, not only trim. Both widenings are harmless.
- **Fix**: Add a plan addendum recording the English headers and the looser header normalization.
- **Decision**: FIXED — plan addendum documents the English headers and the looser header normalization.

### F5 — Pending import dropped on DataIntegrity/lock failure when the rebuilt preview has errors

- **Severity**: 💡 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Adherence
- **Location**: src/main/java/pl/regavio/stockahead/parts/PartImportController.java:356-358
- **Detail**: The plan says that `DataIntegrityViolationException` and `PessimisticLockingFailureException` "keep the pending import". The code calls `rePreview`, which removes the session attribute and shows the error list whenever the rebuilt preview has catalog errors. The Javadoc documents this. It is reasonable, because nothing invalid could be accepted anyway, but it departs from the plan's wording.
- **Fix**: Note it in the plan addendum together with F2/F4. No code change.
- **Decision**: FIXED — plan addendum documents that a re-preview with catalog errors drops the pending import. No code change.

### F6 — Constraint-violation, lock-failure and rename re-check branches untested

- **Severity**: 💡 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Success Criteria
- **Location**: src/main/java/pl/regavio/stockahead/parts/PartImportController.java:174-181, :257-258
- **Detail**: The case-variant races resolve through the advisory lock and the STALE outcome, so in tests the `DataIntegrityViolationException → notice.catalogChanged` and `PessimisticLockingFailureException → notice.saveFailed` mappings probably never run. No test covers the rename re-check either: a matched part renamed between preview and confirm should make the import stale. These are not in the plan's criteria, but they are live error paths.
- **Fix**: Add an integration test that renames a matched part between preview and confirm and asserts the stale notice. Cover the two exception mappings with a test that forces each exception, for example a `@MockitoSpyBean` on PartRepository throwing on `flush`.
- **Decision**: FIXED — `PartImportIntegrationTests` gains `matchedPartRenamedSincePreviewWritesNothingAndShowsItAsNew`, `constraintViolationOnConfirmShowsTheCatalogChangedNoticeAndKeepsThePendingImport` and `lockFailureOnConfirmShowsTheSaveFailedNoticeAndKeepsThePendingImport`. The last two use `@MockitoSpyBean PartRepository` throwing on `flush()`.
