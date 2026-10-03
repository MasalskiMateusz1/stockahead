# Parts CSV Import (S-12) Implementation Plan

## Overview

A manager uploads a CSV of parts and sees a preview of the stock each part will have once the file is accepted. Nothing is written until they accept. On accept, existing parts get the file's quantity added to their stock and gain any location from the file they lack. Parts not yet in the catalog are created. Reservations and the shopping list then recompute (FR-017, PRD § Business Logic: "importuje CSV (nowe sztuki automatycznie uzupełniają rezerwacje wg kolejności)"). This is the PRD's answer to the "entering the whole warehouse by hand" start-up barrier, and the last item in roadmap stream C (S-10 → S-11 → S-12).

## Current State Analysis

- Stock rises today only through delivery receipts (`DeliveryController`) and cancel returns. The delivery write path (`parts/DeliveryController.java:146-209`) already does what an import of existing parts needs: shelf locks, then part-row locks, `Math.addExact` overflow guard, case-insensitive location add in the shelf's canonical spelling, **flush**, then `ReservationAllocator.reallocateForParts`.
- Part creation (`parts/PartController.java:102-182`) requires a trimmed name ≤ 255, a quantity ≥ 0 and **at least one** location. It checks the name with `findByName` (exact, case-sensitive) and maps a constraint violation to a friendly error.
- `parts.name` is `UNIQUE` case-sensitively (`V3__create_parts_catalog.sql:3`). Locations are unique per part case-insensitively (`V10…sql`, index `part_locations_part_location_ci_idx`). They compare through `PartLocation.sameLocation`/`normalize`, which also strip non-breaking and zero-width spaces pasted from spreadsheets.
- The only CSV in the app is the export `purchasing/ShoppingListCsvWriter.java`: `;`-delimited, UTF-8 with a BOM, RFC4180 quoting. There is no CSV reader, no multipart upload anywhere, and nothing stored in the HTTP session (`security/AccountActivityFilter.java:35` only invalidates sessions).
- `spring.jpa.open-in-view` is on. Any `Part` loaded before the write path's lock would be served stale afterwards, so the confirm request must not load parts before locking (same rule as `DeliveryController`'s class Javadoc).
- No migration is needed. The import writes only `parts` and `part_locations`.

## Desired End State

`/parts/import` (manager only, linked from the dashboard and `/parts`) shows an upload form and documents the file format. Uploading a file leads to one of two outcomes:
- **Every error in the file**, listed with row number and reason. Nothing is kept, and the manager fixes the file and re-uploads.
- **A preview table**, one row per part: catalog name (or the file's name for a new part), a `NOWA` / `(nieaktywna)` marker, current stock, quantity added, resulting stock and locations to be added, plus totals. The table comes with "Zatwierdź import" and "Anuluj" buttons.

Accepting writes everything or nothing. If, under lock, any part's current stock or new/existing status differs from the preview, nothing is written and a refreshed preview is shown to accept again. Otherwise the manager lands on `/parts` with a summary flash, and `/parts` and `/purchasing` already reflect the recomputed reservations.

Verify with `./mvnw verify` plus the manual steps in Testing Strategy.

### Key Discoveries:

- `DeliveryController.applyReceipt` (`parts/DeliveryController.java:146-209`) is the write pattern to copy. The order is shelf locks (`LocationSpellings.lockShelves`), then `findByIdInForUpdate`, then validate everything, then mutate, then `partRepository.flush()`, then `reallocateForParts`. Reallocating before the flush leaves the imported units unallocated, because the allocator reads stock with a scalar query.
- `LockRetry.executeWithLockRetry` wraps a `TransactionTemplate` block (`DeliveryController.java:114-121`). `DataIntegrityViolationException`/`PessimisticLockingFailureException` map to a friendly re-render (lessons.md: "Handle DB constraint violations at write boundaries").
- `LocationSpellings.resolve` gives a new location the shelf's existing spelling. Within one request the first spelling seen wins (`parts/LocationSpellings.java:72-83`).
- The CSV writer's escaping rules (`ShoppingListCsvWriter.java:61-75`) are the dialect the reader must accept: `;`, `"`-quoting with `""` escapes, CRLF.
- `MessagesBundleTests` pins every bundle key to its exact Polish text, so every new key needs a test there.
- lessons.md: every `@PreAuthorize` route needs a 403 test for TECHNICIAN; every `<table>` template needs the inline centered-cell `<style>`; guard summed user quantities explicitly rather than relying on the DB `CHECK`.

## What We're NOT Doing

- No import history / audit table (FR-006 movement history is a PRD Non-Goal).
- No partial import: a file with any invalid row is rejected as a whole. There is no "skip bad rows" mode.
- No preview of reservations or available quantities after import. There is no allocator dry run; reservations recompute only on accept.
- No reactivation of inactive parts. The import adds stock and locations and leaves `active` unchanged.
- No creation of a part without a location.
- No removal or renaming of locations, no renaming of parts, no stock decrease (stock correction is S-11).
- No per-location stock, no supplier/price columns, no XLSX, no JavaScript.
- No shared CSV format with the shopping-list export (roadmap open question #2 is answered for import only; the export stays as is).
- No change to the allocator's lock scope.
- No new dependency (no Commons CSV). The reader is a small class in `parts`.

## Implementation Approach

Three units in package `parts`, kept separate so the parser is unit-testable without Spring or a database:

1. `PartsCsvReader`: bytes → decoded text → header-mapped records. Handles encoding and dialect only.
2. `PartsImportFile` (validation + merge): records → either a full list of row errors or the merged file lines (one per part, keyed case-insensitively by normalized name, with quantities summed and locations deduplicated via `sameLocation`).
3. `PartImportController`: upload, preview, confirm and cancel routes. Preview resolves merged lines against the catalog and stores a `PendingImport` (merged lines + per-line snapshot + random token) in the HTTP session. Confirm re-resolves under lock, compares with the snapshot, and either re-previews or writes.

**Agreed decision predicates (carry verbatim into code and tests):**

- **Format.** A header row is required. The required columns are `Nazwa`, `Ilość` and `Lokalizacja`, matched case-insensitively after trimming. `Ilosc` (no diacritic) is also accepted. Column order is free and extra columns are ignored. The delimiter is `;` if the header line contains a `;` outside quotes, otherwise `,`. Encoding: a UTF-8 BOM means UTF-8. Without a BOM the file is decoded as strict UTF-8, falling back to Windows-1250 on malformed input. Each row holds one location, and a part's row is repeated to give more shelves.
- **Name matching.** A file name matches a catalog part when `normalize(file name)` equals `normalize(catalog name)` ignoring case, using `PartLocation.normalize` semantics for whitespace. File rows naming the same part this way merge: quantities are summed, and the first spelling in the file is the new part's name. If two catalog parts match one file name, that is a file error ("ambiguous").
- **Bad rows.** Any error rejects the whole file. All errors are listed (row number + reason, capped at 50 shown with "…i N więcej"), and no preview is stored.
- **Preview.** The preview shows the stock before → after plus the locations to be added, with totals. It does not show reservations.
- **Staleness.** On accept, under lock: if for any line the matched part (id, or none) or that part's current `quantity` differs from the snapshot, nothing is written. The pending import's snapshot is refreshed and the preview re-rendered with a "Stany zmieniły się od podglądu — sprawdź i zatwierdź ponownie" notice. Location or `active` differences do not trigger a re-preview, because adding a location is idempotent.
- **Inactive parts.** Inactive parts get stock and locations added and stay inactive. They are labelled `(nieaktywna)` in the preview.
- **New part without location.** It is a file error if a line resolved as NEW has no non-blank location across all its rows. An existing part may have a blank location.

**Defaults derived from existing patterns (agent-decided):** quantity is an integer 0..1 000 000 per row. Quantity 0 is valid: it creates a new part at stock 0, or only adds a location to an existing part. The merged quantity per part is summed as a `long` and must be ≤ 1 000 000 000. The resulting stock goes through `Math.addExact`. Name and location ≤ 255. At most 5 000 data rows. File ≤ 1 MB, checked in the controller (`file.getSize()`), not by the multipart limit (see Phase 1 §3). A row whose every column is blank is skipped. An empty file or a file with no data rows is an error. One pending import per session: a new upload replaces it, and a successful accept or cancel removes it.

## Critical Implementation Details

- **Timing & lifecycle**: The confirm request must not touch `PartRepository` entity queries before the lock. Name → part resolution for confirm happens **inside** the transaction: a scalar `(id, name)` read, then `findByIdInForUpdate` on the matched ids. The preview request may load parts freely, because it never writes. Lock order is shelves → part names → part rows, extending `LocationSpellings.lockShelves`' rule, so the import can't deadlock with delivery, part create or part edit.
- **State sequencing**: New parts are inserted after existing parts are mutated and before `flush()`. `reallocateForParts` receives only the ids of **existing** parts whose quantity rose, since new parts can't be referenced by any order. A unique-name violation from a concurrently created part surfaces at flush/commit as `DataIntegrityViolationException`. Map it to a re-preview with "Katalog części zmienił się w trakcie importu — sprawdź i zatwierdź ponownie", keeping the pending import; the next confirm sees the new part as existing.
- **Re-render after rollback**: `open-in-view` binds the EntityManager to the request, and `JpaTransactionManager` clears it on rollback, so every `Part` loaded under lock is detached afterwards and its lazy `locations` throw `LazyInitializationException`. The confirm transaction therefore returns only an outcome (`APPLIED` / `STALE` / an error key), never entities or preview rows. Every re-preview (staleness, catalog error, `DataIntegrityViolationException`, `PessimisticLockingFailureException`) runs the Phase 2 preview builder again after the transaction (a fresh unlocked read), stores its snapshot in `PendingImport` and renders it.
- **Case-insensitive names under concurrency**: `parts.name` is `UNIQUE` only case-sensitively, so the DB can't stop two writers creating `Rezystor 10k` and `rezystor 10K`. Every imported name must stay unique case-insensitively, so name creation is serialized with a transaction-scoped advisory lock per case-folded name key (`pg_advisory_xact_lock(PART_NAME_LOCK_NAMESPACE, key.hashCode())`, keys sorted, its own namespace constant next to `SHELF_LOCK_NAMESPACE`, through the existing native `PartRepository.lockShelf(namespace, key)` query). The import takes it for every line's name key after the shelf locks and **before** the scalar `(id, name)` read, so a concurrently committed case-variant part is always seen and makes the line stale. `PartController.create` takes the same lock for its name after its shelf locks, so a manual create can't slip in between the import's read and its insert.
- **Session state**: `PendingImport` must be `Serializable` and hold only plain values (strings, ints, longs), never entities. The confirm form carries the token in a hidden field. A missing or mismatched token (another tab, an expired session, a double submit after success) renders the upload page with "Podgląd wygasł — wgraj plik ponownie" and writes nothing.

## Phase 1: CSV reading and file validation

### Overview

Parse an uploaded file into merged lines or a complete error list, and show the upload page with the documented format and errors. No catalog lookup and no write yet.

### Changes Required:

#### 1. CSV reader

**File**: `src/main/java/pl/regavio/stockahead/parts/PartsCsvReader.java` (new)

**Intent**: Decode bytes and split them into records under the agreed format rules, independent of part semantics.

**Contract**: `static Result read(byte[] bytes)` returns either a structural error (empty file, missing required header, unterminated quote, row count > 5 000) or a list of `Record(int rowNumber, String name, String quantity, String location)`. `rowNumber` is the 1-based record ordinal, with the header as row 1 and every following record (blank ones included) adding 1, so error messages match the row numbers Excel shows. It is not the physical line: Excel shows a quoted multi-line cell as one row. Encoding: BOM → UTF-8, else `CharsetDecoder` with `CodingErrorAction.REPORT` for UTF-8, and on `CharacterCodingException` decode as `windows-1250`. Quoted fields may contain the delimiter, `""` and newlines. Both CRLF and LF are accepted.

#### 2. Row validation and merge

**File**: `src/main/java/pl/regavio/stockahead/parts/PartsImportFile.java` (new)

**Intent**: Apply the per-row rules, collect every error rather than the first, and merge valid rows per normalized case-insensitive name.

**Contract**: `static PartsImportFile parse(List<Record>)` exposes `errors()` (an ordered list of `(rowNumber, messageKey, args)`) and `lines()` (an ordered map from a case-folded name key to `ImportLine(String name, long quantity, List<String> locations, List<Integer> rowNumbers)`). Row rules:
- Fully blank rows are skipped.
- The name is required and ≤ 255 after `normalize`.
- The quantity is required, an integer, and 0..1 000 000.
- The location is ≤ 255 after `normalize`.
- The merged quantity is ≤ 1 000 000 000.

The "new part has no location" rule is **not** here, because it needs the catalog (Phase 2).

#### 3. Upload page and route

**File**: `src/main/java/pl/regavio/stockahead/parts/PartImportController.java` (new), `src/main/resources/templates/parts-import.html` (new), `src/main/resources/application.properties`

**Intent**: Manager-only upload form explaining the format (columns, one location per row, quantity added to existing stock, new parts need a location, Excel "CSV UTF-8" or plain CSV both work). It posts multipart and re-renders with the error list.

**Contract**: `GET /parts/import` and `POST /parts/import` (`file` multipart part), both `@PreAuthorize("hasRole('MANAGER')")`. Set `spring.servlet.multipart.max-file-size=10MB` and `max-request-size=10MB`, and enforce the 1 MB limit in the controller (`file.getSize() > MAX_FILE_BYTES`). A file over 1 MB and an empty/absent file render the upload page with a dedicated message, not an error page. Do not rely on `MaxUploadSizeExceededException` for the 1 MB limit: the CSRF token travels in the multipart body, so a size-limit parse failure makes `CsrfFilter` answer 403 before the controller runs, the exception fires in `DispatcherServlet.checkMultipart` before a handler is mapped (a controller-local `@ExceptionHandler` never sees it), and Tomcat resets connections above its 2 MB `max-swallow-size`. Files over 10 MB may still get a container error; that is accepted. In this phase a valid file re-renders with a placeholder "Plik poprawny: N pozycji"; Phase 2 replaces that with the preview. The template carries the centered-cell `<style>` for its error table.

#### 4. Messages

**File**: `src/main/resources/messages.properties`, `src/test/java/pl/regavio/stockahead/MessagesBundleTests.java`

**Intent**: Polish texts for every structural and row error under the `partsImport.*` prefix, each pinned by a bundle test.

**Contract**: `partsImport.error.*` keys with `{0}` = row number where applicable, using the `{n,number,#}` formatting as in `deliveries.error.*`.

### Success Criteria:

#### Automated Verification:

- `PartsCsvReaderTests` passes, covering: UTF-8 with BOM, UTF-8 without BOM, Windows-1250 with `ąęłóśżź`, `;` and `,` delimiters, quoted delimiter/quote/newline (a quoted newline followed by a bad row still reports the Excel row number), reordered columns plus an extra column, `Ilosc` header, missing header column, empty file, header only, 5 001 data rows, and an unterminated quote.
- `PartsImportFileTests` passes, covering: blank row skipped, missing name, missing quantity, non-integer, negative, 1 000 001, name/location 256 chars, all errors collected with correct row numbers, two rows of `rezystor 10K` / `Rezystor 10k ` merged with summed quantity and deduplicated `A1`/`a1` locations, and merged sum > 10^9 rejected.
- `PartImportIntegrationTests` passes, covering: manager GET 200, TECHNICIAN GET and POST 403, an invalid file re-renders with all row errors, and an oversized/empty upload shows the friendly message.
- `MessagesBundleTests` covers every new key.
- `./mvnw verify` passes.

#### Manual Verification:

- A file saved from Excel as "CSV UTF-8" and one saved as plain "CSV (rozdzielany średnikami)" both parse with correct Polish characters.
- A file with three bad rows shows all three errors with the row numbers Excel displays.
- A 3 MB file uploaded from the browser shows the friendly "file too large" message, not a 403 or a reset connection.

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful before proceeding to the next phase.

---

## Phase 2: Preview against the catalog

### Overview

Resolve every merged line against the catalog, add the catalog-dependent errors, render the preview of resulting stock, and keep the pending import in the session.

### Changes Required:

#### 1. Catalog name matching (in Java)

**File**: `src/main/java/pl/regavio/stockahead/parts/PartRepository.java`, `PartImportController.java` (or a small package-private `CatalogNameIndex` in `parts`)

**Intent**: Resolve file names to catalog parts with exactly the agreed predicate, in Java only, so matching never depends on the database's `LC_CTYPE` (Postgres `lower()` does not fold `Ł`/`Ś`/`Ż` under a C/POSIX ctype) and catalog names stored with a stray NBSP (they are `trim()`-ed on create/edit, not `normalize`-d) still match.

**Contract**: Add `@Query("SELECT p.id, p.name FROM Part p") List<Object[]> findAllIdsAndNames()`, a scalar projection that hydrates no `Part` (OSIV-safe, like `findCurrentQuantities`). Index it by a case-folded key of `PartLocation.normalize(name)`, folded per code point as `Character.toLowerCase(Character.toUpperCase(cp))` (the `equalsIgnoreCase` semantics `LocationSpellings.shelfKey` uses). `PartsImportFile` merges file rows by the same key. A key with two catalog ids is "ambiguous". No new locking query: Phase 3 locks the matched ids with the existing `findByIdInForUpdate`. Tests cover a Polish capital (`ŁĄCZNIK` vs catalog `łącznik`) and a catalog name ending in U+00A0.

#### 2. Preview model and session state

**File**: `src/main/java/pl/regavio/stockahead/parts/PendingImport.java` (new), `PartImportController.java`

**Intent**: Build per-line preview rows and store the merged lines plus snapshot under a fresh random token.

**Contract**: `PendingImport implements Serializable` holds `token` (a `UUID` string), the merged `ImportLine`s, and the per-line `Snapshot(Long partId /* null = NEW */, int quantityBefore)`. A preview row is `(displayName, isNew, isInactive, quantityBefore, quantityAdded, quantityAfter, locationsToAdd)`, where `locationsToAdd` are the file locations the part lacks, compared through `sameLocation`. Totals cover the new-part count, the updated-part count and the units added. Catalog-dependent errors join the Phase 1 error list and are reported the same way (whole file rejected): an ambiguous name (two catalog parts match), a NEW line with no location, and `quantityBefore + added` overflowing `int`. Session attribute name: `partsImport.pending`.

#### 3. Preview template

**File**: `src/main/resources/templates/parts-import-preview.html` (new)

**Intent**: Show the preview table, totals, "Zatwierdź import" (POST with the hidden token) and "Anuluj". It also shows the optional staleness notice used in Phase 3.

**Contract**: The confirm form posts to `POST /parts/import/confirm` with `token`. Cancel posts to `POST /parts/import/cancel`, which removes the session attribute and redirects to `/parts`. Includes the centered-cell `<style>`.

### Success Criteria:

#### Automated Verification:

- `PartImportIntegrationTests` passes, covering:
  - an existing part (stock 6) + file 10 → preview shows 6 → 16 and `A1` listed only if missing case-insensitively;
  - an inactive part shows `(nieaktywna)`;
  - a new part shows `NOWA`, 0 → N;
  - `rezystor 10K` resolves to catalog `Rezystor 10k`;
  - a NEW line with a blank location rejects the file listing that row;
  - two catalog parts `Kondensator` / `kondensator` + file `KONDENSATOR` → ambiguous error;
  - the session holds a pending import after preview and none after an error;
  - cancel clears it;
  - TECHNICIAN on cancel → 403.
- The preview request writes nothing: stock and part counts are unchanged.
- `./mvnw verify` passes.

#### Manual Verification:

- The preview of a realistic ~100-row file is readable, and the totals match the file.

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful before proceeding to the next phase.

---

## Phase 3: Accept — atomic write with reallocation

### Overview

Write the accepted import all-or-nothing under lock, re-preview on staleness, recompute reservations, and link the feature into the app.

### Changes Required:

#### 1. Confirm route and write

**File**: `src/main/java/pl/regavio/stockahead/parts/PartImportController.java`, `src/main/java/pl/regavio/stockahead/parts/PartController.java` (name lock in `create` only), `src/main/java/pl/regavio/stockahead/parts/LocationSpellings.java` (or a sibling `PartNameLocks`, holding the namespace and the sorted-key lock helper)

**Intent**: Apply the pending import inside one `LockRetry` + `TransactionTemplate` block, following `DeliveryController.applyReceipt`'s ordering, plus the staleness check and new-part creation.

**Contract**: `POST /parts/import/confirm` (MANAGER) with `token`. Inside the transaction:
1. `LocationSpellings.lockShelves` over every file location, then the part-name advisory locks over every line's case-folded name key (see Critical Implementation Details).
2. `findAllIdsAndNames()` (scalar), resolve every line, then `findByIdInForUpdate(matched ids)`.
3. Re-check each locked part's name against its line under the same predicate (a rename between steps 2 and lock counts as a difference), rebuild the resolution, and compare with the snapshot (matched id or none, current `quantity`). On any difference, roll back and return `STALE`. After the transaction, rebuild the preview with the Phase 2 builder, store its snapshot in `PendingImport` (same token is fine), and render it with the staleness notice.
4. If the comparison passes, re-check the catalog-dependent errors from Phase 2 (ambiguity can appear if a case-variant part was created meanwhile). On error, roll back and return the error key. After the transaction, re-render the rebuilt preview (or the error list) with that error.
5. Apply `Math.addExact` to the existing parts, add missing locations in the resolved spelling, create new parts (`active = true`, `createdAt = now`, the file's first spelling as name, locations resolved via `LocationSpellings`), `flush()`, then `reallocateForParts(ids of existing parts with added > 0)`.

On success, remove the session attribute and redirect to `/parts` with flash `partsImported` ("Zaimportowano: {0} nowych części, {1} zaktualizowanych, {2} szt."). `DataIntegrityViolationException` renders the rebuilt preview with "Katalog części zmienił się w trakcie importu — sprawdź i zatwierdź ponownie", and `PessimisticLockingFailureException` renders it with a save-failed message. Both keep the pending import.

#### 2. Links and flash

**File**: `src/main/resources/templates/parts-list.html`, `src/main/resources/templates/dashboard.html`

**Intent**: A manager-only "Importuj części z CSV" link on both pages, and `/parts` showing the `partsImported` flash next to the existing `deliveryReceived`/`stockCorrected` ones.

**Contract**: `sec:authorize="hasRole('MANAGER')"` around both links.

### Success Criteria:

#### Automated Verification:

- `PartImportIntegrationTests` passes, covering:
  - accepting writes 6 → 16 plus the missing location in the shelf's existing spelling;
  - a new part is created with its locations;
  - an inactive part stays inactive with stock raised;
  - quantity-0 rows only add a location;
  - an order short 4 units of a part becomes fully reserved and the part leaves `/purchasing` after importing 4 (PRD US-01 numbers);
  - an imported surplus beyond shortages leaves `available` increased;
  - stock changed between preview and confirm (direct repository update in test) → nothing written, refreshed preview with notice, second confirm writes;
  - a part created with the same case-insensitive name between preview and confirm → refreshed preview showing it as existing;
  - a missing/wrong token → "Podgląd wygasł", nothing written;
  - a second confirm after success → "Podgląd wygasł", stock not doubled;
  - TECHNICIAN confirm → 403;
  - the links are visible to the manager only.
- `./mvnw verify` passes.

#### Manual Verification:

- End to end in `./mvnw spring-boot:test-run`: create an order with a shortage, import a file covering it, and see `/purchasing` shrink and `/parts` show updated reserved/available with no manual refresh beyond the redirect.
- Accepting from two browser tabs with the same preview writes once.

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful before proceeding to the next phase.

---

## Phase 4: Concurrency guarantees

### Overview

Prove on real Postgres that the import keeps the hard rules (stock ≥ 0, never double-reserved, no lost update) when it races other stock writers.

### Changes Required:

#### 1. Concurrency tests

**File**: `src/test/java/pl/regavio/stockahead/parts/PartImportConcurrencyTests.java` (new)

**Intent**: Race import confirm against the existing writers using the `pg_sleep` trigger technique from `DeliveryConcurrencyTests` (`parts/DeliveryConcurrencyTests.java:52-140`).

**Contract**: Scenarios:
- **Import vs. delivery on the same part.** Final stock = start + both increments, unless the import detects staleness, in which case it re-previews and writes nothing. Either way no update is lost.
- **Import vs. pick.** Stock ≥ 0 and reserved ≤ stock for every part.
- **Import vs. order creation.** No unit is double-reserved.
- **Import creating "Nowa część" vs. manual `POST /parts` with the same name, and with a case variant (`nowa CZĘŚĆ`).** Exactly one part exists either way. A losing manual create gets a friendly error, not a 500; a losing import re-previews the line as existing.
- **Two imports creating the same new part under case-variant names on different shelves.** Exactly one part exists; the second import re-previews it as existing.
- **Two imports touching the same new shelf with different spellings.** One spelling is stored.

### Success Criteria:

#### Automated Verification:

- `PartImportConcurrencyTests` passes 5 consecutive runs: `./mvnw verify -Dtest=PartImportConcurrencyTests -Dsurefire.rerunFailingTestsCount=0` repeated.
- The full `./mvnw verify` passes.

#### Manual Verification:

- Review the test timings and confirm no scenario passes only because its threads ran sequentially (each test asserts the overlap actually happened, as the sibling tests do).

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful.

---

## Testing Strategy

### Unit Tests:

- `PartsCsvReaderTests`: encoding detection, delimiter detection, RFC4180 quoting, header mapping, limits.
- `PartsImportFileTests`: every row rule, collecting all errors, row numbering, case-insensitive merge, sum bound.

### Integration Tests:

- `PartImportIntegrationTests` (MockMvc + `@Import(TestcontainersConfiguration.class)`, real Postgres): role gates, preview correctness, every catalog-dependent error, write semantics, staleness re-preview, token handling, reallocation visible on `/purchasing`.
- `PartImportConcurrencyTests`: the races above.

### Manual Testing Steps:

1. As manager, open `/parts/import` from the dashboard and read the format description.
2. Upload a file with a bad quantity on two rows and confirm both errors are listed with Excel row numbers.
3. Fix the file, upload it, check the preview (existing, new and inactive markers; before → after; locations to add) and accept.
4. Confirm `/parts` shows the flash, the new parts and the raised stock, and that `/purchasing` dropped the covered shortage.
5. Preview again, record a delivery for one of the parts in another tab, accept, and see the refreshed preview with the notice. Accept again.
6. Log in as technician and confirm there is no import link and `/parts/import` returns 403.

## Performance Considerations

At most 5 000 rows and a 1 MB file. The preview reads every part's `(id, name)` once (scalar, hundreds to low thousands of rows at PRD scale), and confirm repeats that read and then locks only the matched ids, plus the allocator's lock on the same ids. That's fine at the PRD's small scale. The session holds at most one pending import (≤ 5 000 small lines) per manager session.

## Migration Notes

No schema change. Existing case-variant duplicate part names (if any were created by hand) make matching file rows fail as "ambiguous" until the manager renames one.

## References

- PRD: `context/foundation/prd.md` — FR-017, § Business Logic, § Access Control
- Roadmap: `context/foundation/roadmap.md` — S-12; open question #2 (answered here for the import direction only)
- Write pattern: `src/main/java/pl/regavio/stockahead/parts/DeliveryController.java:146-209`
- Part creation rules: `src/main/java/pl/regavio/stockahead/parts/PartController.java:102-182`
- Location normalization and shelf locks: `src/main/java/pl/regavio/stockahead/parts/PartLocation.java`, `src/main/java/pl/regavio/stockahead/parts/LocationSpellings.java`
- CSV dialect: `src/main/java/pl/regavio/stockahead/purchasing/ShoppingListCsvWriter.java`
- Concurrency test technique: `src/test/java/pl/regavio/stockahead/parts/DeliveryConcurrencyTests.java`
- Prior plan: `context/archive/2026-10-02-delivery-receipt/plan.md`

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles. See `references/progress-format.md`.

### Phase 1: CSV reading and file validation

#### Automated

- [x] 1.1 PartsCsvReaderTests passes (encodings, delimiters, quoting, headers, limits) — 8266b67
- [x] 1.2 PartsImportFileTests passes (row rules, all errors collected, merge, sum bound) — 8266b67
- [x] 1.3 PartImportIntegrationTests passes (role gates, error list, oversized/empty upload) — 8266b67
- [x] 1.4 MessagesBundleTests covers every new key — 8266b67
- [x] 1.5 ./mvnw verify passes — 8266b67

#### Manual

- [x] 1.6 Excel "CSV UTF-8" and plain semicolon CSV both parse with Polish characters — 8266b67
- [x] 1.7 A file with three bad rows lists all three with Excel row numbers — 8266b67
- [x] 1.8 A 3 MB browser upload shows the friendly size message — 8266b67

### Phase 2: Preview against the catalog

#### Automated

- [x] 2.1 PartImportIntegrationTests passes (preview values, markers, name matching, catalog errors, session state, cancel)
- [x] 2.2 Preview request writes nothing
- [x] 2.3 ./mvnw verify passes

#### Manual

- [x] 2.4 Preview of a ~100-row file is readable and totals match

### Phase 3: Accept — atomic write with reallocation

#### Automated

- [ ] 3.1 PartImportIntegrationTests passes (write semantics, reallocation, staleness re-preview, tokens, links)
- [ ] 3.2 ./mvnw verify passes

#### Manual

- [ ] 3.3 End-to-end import covers a shortage and /purchasing shrinks
- [ ] 3.4 Accepting from two tabs writes once

### Phase 4: Concurrency guarantees

#### Automated

- [ ] 4.1 PartImportConcurrencyTests passes 5 consecutive runs
- [ ] 4.2 Full ./mvnw verify passes

#### Manual

- [ ] 4.3 Each concurrency scenario demonstrably overlaps
