---
date: 2026-10-02T14:54:08+02:00
researcher: Claude (Opus 5.5) for Mateusz Masalski
git_commit: d375a80
branch: main
repository: stockahead
topic: "Case-insensitive locations; spreadsheet-pasted locations normalized like typed ones"
tags: [research, codebase, parts, part-locations, delivery-receipt, normalization]
status: complete
last_updated: 2026-10-02
last_updated_by: Claude (Opus 5.5)
---

# Research: Case-insensitive locations; spreadsheet-pasted locations normalized like typed ones

**Date**: 2026-10-02T14:54:08+02:00
**Researcher**: Claude (Opus 5.5) for Mateusz Masalski
**Git Commit**: d375a80 (plus uncommitted working-tree changes, see below)
**Branch**: main
**Repository**: stockahead

## Research Question

From `change.md` notes: locations should be case-insensitive. A location pasted from a spreadsheet
should be trimmed the same way as one typed in the app. For example, `'A1 '` pasted from a spreadsheet
must not create a new location when `A1` already exists.

## Summary

**Most of this change is already in the working tree, but it is uncommitted.** It was written during
the `delivery-receipt` impl-review triage (F6, "fixed differently") and the plan-review fix (F3). HEAD
`d375a80` still has the old behaviour: exact, case-sensitive matching with `String.trim()`.

The uncommitted diff adds:

- `PartLocation.normalize`, which turns U+00A0/U+2007/U+202F into plain spaces and then calls `strip()`.
- `PartLocation.sameLocation`, which normalizes both sides and compares with `equalsIgnoreCase`
  (`src/main/java/pl/regavio/stockahead/parts/PartLocation.java:57-71`).
- Both location write paths now use these helpers:
  - `PartController` create/edit, through `parseLocations` (`PartController.java:289-303`) and
    `reconcileLocations` (`PartController.java:318-352`).
  - `DeliveryController` receipt validation (`DeliveryController.java:229`), row merge
    (`DeliveryController.java:315`) and the "part already has this location" check
    (`DeliveryController.java:173`).
- 2 tests in `DeliveryIntegrationTests` and 5 in `PartsCatalogIntegrationTests` (listed under Code
  References).

With that diff, the requested example works on the two paths I inspected. For a part that already has
`A1`, entering `A1 `, `a1`, `A1 ` or `\tA1` in a receipt row or the part-edit textarea adds no new
row for that part.

What is **not** covered yet:

1. **No case-insensitive DB backstop.** `UNIQUE (part_id, location)` in
   `V3__create_parts_catalog.sql` compares exact strings. Under concurrency, two writers using
   different case can both insert. This is acknowledged in the delivery-receipt plan addendum F6.
2. **Matching is per part, not global.** If part X has `A1` and a receipt adds `a1` to part Y, Y gets
   a new row spelled `a1`. That is correct under the schema, but the shelf now has two spellings
   across parts. The delivery datalist would then offer both, because
   `PartRepository.findAllLocationNames()` uses a case-sensitive `SELECT DISTINCT`
   (`PartRepository.java:31-33`).
3. **Some invisible or odd whitespace survives normalization.** Zero-width space (U+200B) and BOM
   (U+FEFF) are not whitespace for `String.strip()`, so they are kept. Repeated inner spaces (`A  1`
   vs `A 1`) are not collapsed.
4. **The future CSV import (S-12, FR-017) does not exist yet.** It must reuse
   `PartLocation.normalize`/`sameLocation`. Both are package-private `static` methods in
   `pl.regavio.stockahead.parts`.
5. **Ownership and commit question.** The uncommitted code is recorded as part of the
   `delivery-receipt` triage, which has status `impl_reviewed` and is not committed. It is unresolved
   whether that code is committed under delivery-receipt or under this change.

## Detailed Findings

### Storage model

- Locations are rows in `part_locations(part_id, location VARCHAR(255))` with
  `UNIQUE (part_id, location)` (`src/main/resources/db/migration/V3__create_parts_catalog.sql`). No
  later migration (V4–V9) touches that table. I checked by listing `db/migration/`. V4 normalizes
  account emails only, as its name says.
- No global "location" entity exists. A shelf name is free text repeated per part. PRD § Non-Goals
  (`context/foundation/prd.md:150`) says: "lokalizacje to zwykły tekst, bez stanu per lokalizacja"
  ("locations are plain text, with no stock per location").
- `context/deployment/` does not exist, so no production database is known to hold legacy
  case-duplicates. This matters if a migration has to deduplicate rows before adding a functional
  unique index.

### Normalization and comparison (uncommitted)

- `PartLocation.normalize(String)` (`PartLocation.java:69-71`) replaces exactly 3 code points (U+00A0,
  U+2007, U+202F) with U+0020 and then calls `strip()`. `strip()` removes leading and trailing
  characters for which `Character.isWhitespace` is true. That covers tab, CR/LF and ASCII space, but
  not U+200B or U+FEFF.
- `PartLocation.sameLocation` (`PartLocation.java:57-59`) compares with `equalsIgnoreCase`, which folds
  case one character at a time. This handles Polish letters such as `Ł`/`ł`. It is not full Unicode
  case folding, which is irrelevant for shelf codes like `A1`.
- The stored value is the normalized string in its **original case**. The first spelling wins. Case is
  never lowercased on write.

### Part create/edit path (`PartController`)

- `parseLocations` (`PartController.java:289-303`) splits on `\r?\n` and normalizes each line. It drops
  blank lines and rejects duplicates by `sameLocation` with `parts.error.locationDuplicate`. Callers:
  create at `:140` and edit at `:224`.
- `reconcileLocations` (`PartController.java:306-352`) works in two passes:
  1. It claims exact-string matches first.
  2. It claims case-insensitive matches and re-spells them in place (`existing.setLocation(target)`).
     Unmatched rows are removed and unmatched targets are added.

  Claiming exact matches first avoids a transient `UNIQUE (part_id, location)` violation when legacy
  rows `a1`/`A1` coexist. The Javadoc at `:306-317` gives this rationale, and
  `editKeepsTheExactSpellingWhenLegacyRowsDifferOnlyByCase` tests it.
- Effect: changing only the case on edit re-spells the existing row and keeps its id
  (`editChangingOnlyLocationCaseRespellsTheExistingRow`).

### Delivery receipt path (`DeliveryController`)

- Row validation normalizes the location (`DeliveryController.java:229`) and checks the 255-character
  length (`:230-232`) on the normalized value.
- `ReceiptLine.add` (`DeliveryController.java:312-317`) keeps a location only if no location already
  collected for that part matches by `sameLocation`. The first-typed spelling wins.
- `applyReceipt` (`DeliveryController.java:170-180`) adds a `PartLocation` only when none of the locked
  part's rows match by `sameLocation`. This is the code path for the request's example.
- `findAllLocationNames()` (`PartRepository.java:31-33`) feeds `<datalist id="known-locations">`
  (`DeliveryController.java:282`, `deliveries-new.html:48-50`). Its `SELECT DISTINCT` is
  case-sensitive, so `A1` and `a1` on different parts appear as 2 suggestions.

### Read paths (display and search)

- Search already matches without regard to case: `LOWER(l.location) LIKE LOWER(...)`
  (`PartRepository.java:18-25`).
- Display paths show stored spellings as they are. I inspected:
  - `parts-list.html:56`
  - `PickingDetailModel.java:82-84` → `picking-detail.html:45`
  - `OrderDetailModel.java:175-177` → `orders-cancel.html:34`

  None of them compare locations, so case-insensitivity does not affect them.

### "Spreadsheet" input surfaces

- I inspected `src/main` (grep for `location`/`csv`). Today a spreadsheet value can reach a location
  only by copy-paste into the parts textarea or a delivery row input. No CSV import endpoint exists.
  S-12 `parts-csv-import` is `proposed` (`context/foundation/roadmap.md:59`).
- FR-017 (`prd.md:70`) says the import "dodaje lokalizację z pliku, jeśli jej brak" ("adds the
  location from the file if it is missing"). That is exactly the rule this change defines, so S-12
  must use `sameLocation`.

## Code References

- `src/main/resources/db/migration/V3__create_parts_catalog.sql` — `part_locations` with case-sensitive `UNIQUE (part_id, location)`
- `src/main/java/pl/regavio/stockahead/parts/PartLocation.java:51-71` — `sameLocation` / `normalize` (uncommitted)
- `src/main/java/pl/regavio/stockahead/parts/PartController.java:140,224` — `parseLocations` callers (create/edit)
- `src/main/java/pl/regavio/stockahead/parts/PartController.java:289-303` — `parseLocations` (uncommitted change)
- `src/main/java/pl/regavio/stockahead/parts/PartController.java:306-352` — `reconcileLocations` two-pass match (uncommitted change)
- `src/main/java/pl/regavio/stockahead/parts/DeliveryController.java:170-180` — add-location-if-missing under part lock
- `src/main/java/pl/regavio/stockahead/parts/DeliveryController.java:229-232` — normalize + length check
- `src/main/java/pl/regavio/stockahead/parts/DeliveryController.java:312-317` — per-part row merge
- `src/main/java/pl/regavio/stockahead/parts/PartRepository.java:18-25` — search (already uses `LOWER`)
- `src/main/java/pl/regavio/stockahead/parts/PartRepository.java:31-33` — `findAllLocationNames` (case-sensitive `DISTINCT`)
- `src/test/java/pl/regavio/stockahead/parts/DeliveryIntegrationTests.java:639,655` — `locationsAreMatchedIgnoringCase`, `locationsPastedWithNonBreakingSpacesMatchTypedOnes`
- `src/test/java/pl/regavio/stockahead/parts/PartsCatalogIntegrationTests.java:542,556,574,589,603` — create dup-by-case, edit re-spell, NBSP create, NBSP dup, legacy `a1`/`A1`
- `src/test/java/pl/regavio/stockahead/parts/PartsCatalogIntegrationTests.java:143-155` — `seedPart` writes through JPA, which is how the legacy-pair test seeds `a1`+`A1`

## Architecture Insights

- There is one normalization point (`PartLocation.normalize`) and one equality point
  (`PartLocation.sameLocation`). Any future writer of `part_locations` (S-12 CSV import, S-11 if it ever
  touches locations) should go through them, not through `trim()` or `equals`.
- "First spelling wins": stored values keep their original case. Changing that to canonical lowercase
  or uppercase on write would be a product decision, because shelf labels show in picking lists.
- A DB-level guarantee would be `CREATE UNIQUE INDEX … ON part_locations (part_id, lower(location))`.
  It conflicts with the existing test that seeds a legacy `a1`/`A1` pair
  (`PartsCatalogIntegrationTests.java:603`). That test would have to go, or seed differently, and any
  existing duplicate rows would need deduplication in the same migration. Per AGENTS.md, schema changes
  go only through a new Flyway migration (V10). Per lessons.md "Handle DB constraint violations at write
  boundaries", both controllers then need to map the new violation to a form error.
  `DeliveryController` already maps `DataIntegrityViolationException` to `saveFailed`. I did not inspect
  `PartController`'s save error mapping for this.

## Historical Context (from prior changes)

- `context/changes/delivery-receipt/reviews/plan-review.md` F3 flagged trailing NBSP. It is
  **supported** by current code: the fix is in `PartLocation.normalize`, and the plan addendum in
  `delivery-receipt/plan.md` Phase 1 says so.
- `context/changes/delivery-receipt/reviews/impl-review.md` F6 decision ("fixed differently —
  case-insensitive at the app level in both controllers, no migration, DB UNIQUE stays case-sensitive as
  a backstop") is **supported** by the working tree.
  - Its last bullet, "The NBSP part of this finding was not addressed", is **contradicted** by the
    working tree. `normalize` handles NBSP, and two NBSP tests exist (`DeliveryIntegrationTests.java:655`,
    `PartsCatalogIntegrationTests.java:574,589`). The note appears to predate the plan-review F3 fix
    landing.
  - Its triage line "`./mvnw verify` after fixes: 275 tests, 0 failures" is a historical claim. I did
    not re-run the build during this research.
- `context/changes/delivery-receipt/plan.md` addendum F6 records the known gap: a concurrent part edit
  using a different case is not caught by `saveFailed`.

## Related Research

- Not applicable. No other `research.md` covers locations.

## Open Questions

1. **Where the existing uncommitted code lands.** Option A: commit it with the `delivery-receipt`
   impl-review fixes, so this change only covers the gaps below. Option B: move it into this change.
   This is a git/ownership decision for the user.
2. **Per-part or global matching.** Should a location typed for part Y reuse the spelling already used
   for the same shelf on another part X (`a1` → stored as `A1`)? Today matching is scoped to one part.
   A global canonical spelling would also fix the duplicate datalist suggestions.
3. **DB backstop.** Should a V10 migration add a unique index on `(part_id, lower(location))`, with
   deduplication of legacy rows and removal or rewrite of the legacy-pair test? Or is the app-level
   check enough at this plant's concurrency? The delivery-receipt F6 decision chose app-level only.
4. **Wider normalization.** Should normalization also strip zero-width characters (U+200B, U+FEFF) and
   collapse runs of inner whitespace? No concrete reproduction from a spreadsheet was observed. The
   request names only trailing space.
5. **Datalist de-duplication.** If matching stays per-part, should `findAllLocationNames()` collapse
   case variants (for example `GROUP BY lower(location)` and pick one spelling)?
