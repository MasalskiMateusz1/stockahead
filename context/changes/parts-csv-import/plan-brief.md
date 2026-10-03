# Parts CSV Import (S-12) — Plan Brief

> Full plan: `context/changes/parts-csv-import/plan.md`

## What & Why

A manager uploads a CSV of parts and sees the stock each part will have once the file is accepted. Nothing is written until they accept. On accept, existing parts get the quantity added and any missing location, new parts are created, and reservations and the shopping list recompute. This is FR-017, the PRD's answer to the "entering the whole warehouse by hand" start-up barrier, and the last item in roadmap stream C.

## Starting Point

`DeliveryController.applyReceipt` already does the write an import needs for existing parts. It takes shelf locks, then part-row locks, guards overflow, adds locations case-insensitively, flushes, then reallocates. The app has no CSV reader, no file upload and no session state yet. Part names are unique only case-sensitively, and a new part needs at least one location.

## Desired End State

`/parts/import` (manager only, linked from the dashboard and `/parts`) documents the format and accepts a file. A file with any error is rejected, and every bad row is listed by its Excel row number. A valid file shows a preview per part: `NOWA` / `(nieaktywna)` marker, stock before → added → after, and locations to add. Accepting writes everything or nothing. If stock changed since the preview, the refreshed preview is shown again instead of writing.

## Key Decisions Made

| Decision | Choice | Why (1 sentence) |
| --- | --- | --- |
| File format | Own: header `Nazwa;Ilość;Lokalizacja`, columns by name, `;` or `,` auto-detected, UTF-8 (BOM) with Windows-1250 fallback, one location per row | Opens from Polish Excel in either CSV flavour and mirrors the delivery receipt's row shape. |
| Existing-part match | Case-insensitive, trimmed (incl. non-breaking spaces); two catalog matches → error | A spreadsheet's casing must never silently create a near-duplicate part. |
| Invalid rows | Reject whole file, list all errors | What gets imported is exactly the file, matching the receipt's all-or-nothing rule. |
| Preview contents | Stock before → after + locations to add, no reservation preview | Exactly what FR-017 asks for, with no locking on preview. |
| Stale preview | Re-preview if any part's stock or new/existing status changed; write nothing | What's written always equals what the manager accepted. |
| Inactive parts | Add stock and locations, keep inactive, label in preview | Consistent with delivery receipt (S-10). |
| New part without location | File error | Keeps the catalog rule that every part has a shelf. |
| Preview → confirm state | `PendingImport` in HTTP session + one-time token | No JS, no tamperable hidden rows, and double submit can't double stock. |
| Limits | 0..1 000 000 per row (0 = location-only / empty new part), ≤ 5 000 rows, ≤ 1 MB, 255-char fields | Follows the delivery bounds and the lessons.md overflow rule. |
| Schema | No migration, no import history table | FR-006 movement history is a Non-Goal. |

## Scope

**In scope:** CSV reader and validation, upload page with format docs, catalog-resolved preview, atomic accept with staleness check and reallocation, links and flash, integration and concurrency tests.

**Out of scope:** partial import, reservation preview, reactivating parts, parts without a location, renaming or removing locations, stock decrease, XLSX, JS, a shared CSV format with the shopping-list export, any change to the allocator's lock scope.

## Architecture / Approach

There are three units in `parts`. `PartsCsvReader` turns bytes into header-mapped records. `PartsImportFile` validates every row and merges by case-insensitive name. `PartImportController` handles upload → preview → confirm/cancel. The preview resolves names against the catalog and stores the merged lines plus a snapshot (matched id, stock before) in the session. Confirm runs in one `LockRetry` + `TransactionTemplate` block: `lockShelves`, then resolve names in Java over a scalar `(id, name)` read and lock the matched ids, then compare with the snapshot (re-preview on mismatch), then add stock and locations, create new parts, `flush()`, and `reallocateForParts(existing ids)`.

## Phases at a Glance

| Phase | What it delivers | Key risk |
| --- | --- | --- |
| 1. CSV reading and file validation | Upload page, format docs, full error list; no write | Encoding/delimiter edge cases from real Excel files |
| 2. Preview against the catalog | Name resolution, catalog errors, preview table, session state | Name matching drifting from the file-merge predicate (single Java predicate, no SQL `lower()`) |
| 3. Accept — atomic write with reallocation | Locked write, staleness re-preview, new parts, reallocation, links | Loading parts before the lock (open-in-view stale entity); reallocating before flush |
| 4. Concurrency guarantees | Real-Postgres races against delivery, pick, order creation, manual create | Flaky timing; mitigated with `pg_sleep` triggers as in sibling tests |

**Prerequisites:** S-02 and S-04 done (they are), and Docker running for `./mvnw verify`.
**Estimated effort:** ~3 sessions across 4 phases.

## Open Risks & Assumptions

- The plant's real spreadsheet is unknown. If it differs, they reshape it to three columns.
- An accidental duplicate row silently sums into stock, as in the delivery receipt. The preview's "added" column is the only check.
- Nothing records who imported what. A mistaken import is undone through stock correction (S-11).
- Existing case-variant duplicate part names block matching rows as "ambiguous" until one is renamed.
- Imported names are unique case-insensitively even under concurrency: the import and manual part creation share a per-name advisory lock. Manual create alone still allows case variants (unchanged), and a rename via part edit is not name-locked.
- Session state assumes a single app instance, which matches the current Coolify deployment.

## Success Criteria (Summary)

- A manager loads the initial warehouse from one file and sees the exact resulting stock before anything is saved.
- An import covering a shortage fills the waiting order and shrinks `/purchasing`, with no lost update under concurrent deliveries or picks.
