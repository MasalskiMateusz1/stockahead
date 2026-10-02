# Case-insensitive Locations Implementation Plan

## Overview

The working tree already makes location matching per part case-insensitive and normalizes pasted
non-breaking spaces. That code is uncommitted and filed as `delivery-receipt`'s impl-review F6 fix.

This plan:

1. Commits that code where it belongs, after correcting the F6 note that is now out of date.
2. Hardens it with zero-width edge stripping and a case-insensitive DB unique index per part (V10).
3. Adds one spelling per shelf across all parts. A new location reuses the spelling any part
   already uses, and a case-only respell on edit renames the shelf everywhere.

## Current State Analysis

- HEAD `d375a80` matches locations exactly, with `String.trim()`. The working tree has
  `PartLocation.normalize` (U+00A0/U+2007/U+202F → space, then `strip()`) and `sameLocation`
  (`equalsIgnoreCase` after `normalize`) at `PartLocation.java:51-71`. Every location writer uses
  them:
  - `PartController.parseLocations` (`:289-303`) and `reconcileLocations` (`:306-352`)
  - `DeliveryController.validate` (`:229`), `ReceiptLine.add` (`:312-317`) and `applyReceipt` (`:170-180`)
- Tests in the working tree:
  - `DeliveryIntegrationTests.locationsAreMatchedIgnoringCase` (`:639`) and
    `locationsPastedWithNonBreakingSpacesMatchTypedOnes` (`:655`)
  - `PartsCatalogIntegrationTests:542,556,574,589,603`
- `delivery-receipt/reviews/impl-review.md:106` says "The NBSP part of this finding was not
  addressed". The working tree contradicts this (frame, Hypothesis 4).
- `V3__create_parts_catalog.sql`: `UNIQUE (part_id, location)` compares exact strings, so two writers
  that differ only in case can both insert. No later migration touches `part_locations`. Latest is V9.
- Matching is per part only. `PartRepository.findAllLocationNames()` (`:31-33`) is a case-sensitive
  `SELECT DISTINCT`, so the delivery datalist can show both `A1` and `a1`.
- `normalize` keeps U+200B and U+FEFF (not whitespace for `strip()`). The frame's jshell check showed
  these are the only tested trailing characters that still break matching against `A1`.
- `PartController` catches `DataIntegrityViolationException` at `:172` and `:251` and always reports
  `parts.error.duplicateName`. A location-index clash would show the wrong message.
- No production DB exists (`context/deployment/` absent), so a data-rewriting migration is cheap now.

## Desired End State

- The `delivery-receipt` triage is committed, `./mvnw verify` passes, and F6 no longer contradicts
  the code.
- A location typed or pasted with leading or trailing ASCII or non-breaking spaces, tabs, U+200B or
  U+FEFF matches the stored shelf ignoring case. On the same part it never creates a second row.
- The DB refuses two rows on one part whose locations differ only in case
  (`(part_id, lower(location))` unique index). A violation on create or edit re-renders the part form
  with a location-specific error, not "duplicate name".
- Each shelf has one spelling across all parts:
  - If part X has `A1`, then adding `a1` to part Y on create, edit or delivery stores `A1`.
  - Editing X to change only the case to `a1` re-spells that shelf on every part.
  - V10 unifies any existing variants to the spelling of the oldest row (lowest id).
- The delivery datalist therefore offers one entry per shelf, with no query change.

### Key Discoveries:

- `PartLocation.java:57-71`: the single normalization point and the single comparison point.
- `PartController.java:306-352`: the exact-match-first pass exists only to survive legacy
  `a1`/`A1` pairs under the case-sensitive constraint. V10 makes that state impossible.
- `V4__normalize_account_emails.sql` + `V4MigrationTests` are the precedent for a functional unique
  index and for testing a data migration: migrate to target N-1, seed with JDBC, migrate to latest.
- `DeliveryIntegrationTests.java:715-737` is the precedent for simulating a constraint violation with a
  throwaway `BEFORE INSERT` trigger that raises `unique_violation`.
- `PartLocation.part` is a `@ManyToOne` (`PartLocation.java:20-22`), so a JPQL bulk update can
  filter by `l.part.id`.

## What We're NOT Doing

- Collapsing runs of inner whitespace (`A  1` ≠ `A 1`). Zero-width characters are stripped only
  at the ends.
- A DB guarantee of one spelling **across** parts. The new index is per part. Two concurrent writes that
  introduce a brand-new shelf on two different parts with different case can still store two spellings
  (see Open Risks in the brief).
- A global `locations` entity or table. PRD § Non-Goals: locations are plain text.
- Canonical lowercasing or uppercasing on write. The first spelling stored wins.
- Normalizing legacy rows that contain NBSP or zero-width characters in SQL. V10 compares by
  `lower(location)` only. There is no production data to repair.
- Changing the delivery datalist query. The cross-part invariant makes `SELECT DISTINCT` return one
  entry per shelf.
- delivery-receipt's F1 follow-up (locking the part row in `PartController` edit). It stays a
  separate change.
- The S-12 CSV import. It must reuse `normalize`/`sameLocation` and the spelling lookup when it is built.

## Implementation Approach

Phase 1 makes the existing work durable and correctly owned, without changing behaviour.

Phase 2 changes the schema. It adds the per-part DB guarantee and the zero-width stripping, and it
removes the legacy-pair accommodation that the index makes obsolete. Phase 3 needs this first: V10's
unification step is what makes "one spelling per shelf" true for existing rows.

Phase 3 adds the cross-part spelling rule in the app: one lookup used by every writer, plus the
respell-everywhere bulk update on edit. Each phase ends green under `./mvnw verify`.

## Critical Implementation Details

- **V10 ordering**: drop the old `UNIQUE (part_id, location)` first (Postgres auto-names it
  `part_locations_part_id_location_key`; verify with `\d part_locations` or `pg_constraint` before
  hard-coding). Then unify spellings across parts, then delete per-part duplicates keeping the lowest
  id, then create the index. Unifying while the old constraint exists fails on a part that holds both
  `a1` and `A1`.
- **Telling a location clash from a name clash**: the create path relies on the `parts.name` unique
  constraint for duplicate names (`:172`). So `PartController` must inspect the violated constraint
  name (Hibernate `ConstraintViolationException.getConstraintName()` in the cause chain), not just
  catch the exception type.
- **Respell-everywhere ordering on edit**: re-spell this part's row in memory, `saveAndFlush`, then
  run the bulk update for the other parts' rows (`part_id <> :id`). The bulk JPQL update bypasses the
  persistence context, so including this part's row in it would leave the managed entity stale.

## Phase 1: Commit the delivery-receipt triage

### Overview

Verify the uncommitted case/NBSP work, correct the out-of-date F6 note, and commit everything as
`delivery-receipt`'s review fixes. This change's folder stays out of that commit.

### Changes Required:

#### 1. Correct the F6 decision note

**File**: `context/changes/delivery-receipt/reviews/impl-review.md`

**Intent**: Replace the bullet "The NBSP part of this finding was not addressed" (`:106`) with a line
saying NBSP is handled by `PartLocation.normalize`, naming the three NBSP tests. Add a pointer that
further hardening continues in `case-insensitive-locations`.

**Contract**: F6 `**Decision**` sub-bullets only; Triage summary unchanged.

#### 2. Commit the existing diff

**File**: the working-tree changes listed by `git status`. That covers the `delivery-receipt` folder,
including `follow-ups/` and `reviews/`, `context/foundation/roadmap.md`, the `parts` sources,
`deliveries-new.html` and both test classes.

**Intent**: Land the triage fixes under `delivery-receipt`, with a message like
`fix(delivery-receipt): impl-review triage (case-insensitive, NBSP-safe locations)`. Exclude
`context/changes/case-insensitive-locations/` and `skills-lock.json`.

**Contract**: No code edits in this phase; content committed as-is after verify passes.

### Success Criteria:

#### Automated Verification:

- `./mvnw verify` passes on the working tree before committing
- `git status` after the commit shows only `context/changes/case-insensitive-locations/` and `skills-lock.json` as untracked

#### Manual Verification:

- F6 in `delivery-receipt/reviews/impl-review.md` reads consistently with the code (no "NBSP not addressed")

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful before proceeding to the next phase.

---

## Phase 2: Normalization and per-part DB guarantee

### Overview

Strip zero-width characters at the ends, and add V10. V10 unifies spellings across parts,
deduplicates per part, and replaces the case-sensitive constraint with a
`(part_id, lower(location))` unique index. A location-index violation maps to its own form error.
The legacy-pair code path goes away.

### Changes Required:

#### 1. Zero-width edge stripping

**File**: `src/main/java/pl/regavio/stockahead/parts/PartLocation.java`

**Intent**: `normalize` also removes U+200B and U+FEFF at the start and end, interleaved with
whitespace in any order (e.g. `"A1 ​ "` → `"A1"`). Inner occurrences stay. Update the Javadoc.

**Contract**: `static String normalize(String)`: same signature; ends free of
`Character.isWhitespace`, U+00A0/U+2007/U+202F, U+200B, U+FEFF.

#### 2. V10 migration

**File**: `src/main/resources/db/migration/V10__case_insensitive_part_locations.sql`

**Intent**: Make "one spelling per shelf, one row per shelf per part" true for existing data, and
enforce the per-part half in the DB.

**Contract**, in order:

1. `ALTER TABLE part_locations DROP CONSTRAINT <V3 unique constraint>`.
2. Rewrite every row to the spelling of the lowest-id row sharing its `lower(location)`.
3. Delete rows that duplicate `(part_id, lower(location))`, keeping the lowest id.
4. `CREATE UNIQUE INDEX part_locations_part_location_ci_idx ON part_locations (part_id, lower(location))`.

#### 3. Location-specific constraint error

**Files**: `src/main/java/pl/regavio/stockahead/parts/PartController.java`,
`src/main/resources/messages.properties`

**Intent**: In both `catch (DataIntegrityViolationException)` blocks (`:172`, `:251`), report
`parts.error.locationConflict` when the violated constraint is `part_locations_part_location_ci_idx`.
Keep `duplicateName` otherwise. Example Polish text: "Ta lokalizacja została właśnie dodana do części
— odśwież formularz i spróbuj ponownie."

**Contract**: New message key `parts.error.locationConflict`; form re-render behaviour unchanged.

#### 4. Drop the legacy-pair accommodation

**File**: `src/main/java/pl/regavio/stockahead/parts/PartController.java`

**Intent**: `reconcileLocations` collapses its exact-match-first pass into the single
case-insensitive pass. A part can no longer hold `a1` and `A1`, so the transient-violation rationale
is gone. Update the Javadoc.

**Contract**: Same signature and outcomes: matched rows keep their id and take the target spelling,
unmatched rows are removed, and unmatched targets are added.

#### 5. Tests

**Files**: `src/test/java/pl/regavio/stockahead/parts/V10MigrationTests.java` (new),
`PartsCatalogIntegrationTests.java`, `DeliveryIntegrationTests.java`, and optionally
`PartLocationTests.java` (new, unit)

**Intent**:

- `V10MigrationTests`, following `V4MigrationTests`: migrate to target `9`, seed with JDBC, then
  migrate to latest. Cases:
  - Cross-part variants (X `A1` id lower, Y `a1`) both end as `A1`.
  - A part with `a1` + `A1` keeps one row, the lowest id.
  - After V10, a raw insert of `a1` next to `A1` on one part is rejected.
- `PartsCatalogIntegrationTests`:
  - Remove `editKeepsTheExactSpellingWhenLegacyRowsDifferOnlyByCase` (`:603`); that state is now
    unseedable.
  - Add a trigger-simulated location-index violation on edit. The trigger raises `unique_violation`
    with `CONSTRAINT = 'part_locations_part_location_ci_idx'`, and the test asserts the
    `locationConflict` message re-renders, not `duplicateName`.
- Zero-width matching: `A1​` and `﻿A1` against an existing `A1`. Cover it with one receipt
  test and one part-create duplicate test (or a `PartLocation` unit test plus one integration test).

**Contract**: All tests against real Postgres via Testcontainers (AGENTS.md); no H2.

### Success Criteria:

#### Automated Verification:

- `./mvnw verify` passes
- `V10MigrationTests` covers cross-part unification, per-part dedupe keeping the lowest id, and index rejection of a case duplicate
- A delivery or part-create test proves `A1​` / `﻿A1` match an existing `A1`
- A trigger-simulated location-index violation on part edit re-renders with `parts.error.locationConflict`

#### Manual Verification:

- In `./mvnw spring-boot:test-run`, editing a part that has `A1` and pasting `a1` from a spreadsheet cell (trailing space/NBSP) in its place leaves exactly one location row for that part

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful before proceeding to the next phase.

---

## Phase 3: One spelling per shelf across parts

### Overview

Every writer resolves a new location to the spelling already used by any part. Within one request,
the first spelling typed for a brand-new shelf wins across parts too. A case-only respell on edit
renames the shelf on every part.

### Changes Required:

#### 1. Spelling lookup and shelf rename queries

**File**: `src/main/java/pl/regavio/stockahead/parts/PartRepository.java`

**Intent**: Add a query returning the stored spelling for a location, matched case-insensitively and
ordered by id (oldest first). Add a `@Modifying` bulk update that re-spells a shelf on every part
except one.

**Contract**:

- `Optional<String>` (or first-of-list) lookup on `LOWER(l.location) = LOWER(:location)` ordered by `l.id`.
- `int respellOnOtherParts(String spelling, Long partId)`, an update `WHERE LOWER(l.location) = LOWER(:spelling) AND l.part.id <> :partId`.

#### 2. Request-scoped spelling resolver

**File**: `src/main/java/pl/regavio/stockahead/parts/PartLocation.java` or a small package-private
helper in `parts`

**Intent**: One place that turns a normalized location into its canonical spelling for the current
request. It returns the DB spelling if one exists, otherwise the first spelling seen earlier in the
same request, otherwise the input. This keeps a multi-part receipt with `a1` on Y and `A1` on Z (shelf
new to the DB) to one spelling.

**Contract**: Package-private; keyed by `sameLocation` semantics; used inside the writer's
transaction.

#### 3. Wire every writer

**Files**: `PartController.java` (create at `:162-167`, `reconcileLocations` add branch),
`DeliveryController.java` (`applyReceipt` add branch at `:173-178`)

**Intent**: Each newly added `PartLocation` takes the resolved spelling instead of the typed one.
In `reconcileLocations`, a target that matches this part's existing row only by case still re-spells
that row to the typed target, and the edit then calls `respellOnOtherParts` after `saveAndFlush`.
That bulk update is what renames the shelf everywhere. A target matching an existing row exactly
changes nothing.

**Contract**:

- Create and delivery store the resolved spelling.
- Edit add stores the resolved spelling.
- Edit case-only respell stores the typed spelling on this part and on all other parts.
- Datalist (`findAllLocationNames`) is unchanged.

#### 4. Tests

**Files**: `PartsCatalogIntegrationTests.java`, `DeliveryIntegrationTests.java`

**Intent**:

- Create part Y with `a1` while X has `A1`: Y stores `A1`.
- Edit Y adding `a1` while X has `A1`: Y stores `A1`.
- Delivery adds `a1` to Y while X has `A1`: Y stores `A1`.
- One receipt adds new shelf `n9` to Y and `N9` to Z: both store `n9` (first typed).
- Edit X re-spelling `A1` → `a1` while Y and Z have `A1`: all three read `a1`, and X's row id is unchanged.
- The delivery form's datalist renders a single `A1` entry when two parts hold that shelf.

**Contract**: Integration tests via MockMvc + Testcontainers, following existing helpers
(`seedPart`, `locationValuesOf`, `locationIdOf`, `locationsOf`).

### Success Criteria:

#### Automated Verification:

- `./mvnw verify` passes
- Tests prove create, edit-add and delivery store an existing shelf's spelling from another part
- A test proves a brand-new shelf typed in two spellings within one receipt is stored once, in the first-typed spelling
- A test proves a case-only respell on edit updates the shelf on all other parts and keeps the edited row's id

#### Manual Verification:

- In `./mvnw spring-boot:test-run`: give part X `A1`, receive a delivery for part Y with `a1 ` pasted from a spreadsheet. The parts list shows `A1` for both, and the delivery form's location suggestions list `A1` once
- Edit X to `a1`; both parts and the picking list show `a1`

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful before proceeding to the next phase.

---

## Testing Strategy

### Unit Tests:

- Optional `PartLocationTests` for `normalize`: trailing/leading U+200B, U+FEFF, mixed with NBSP and
  tab; inner U+200B kept.

### Integration Tests:

- V10 migration against seeded V9 data (cross-part unification, per-part dedupe, index rejection).
- Per-writer spelling resolution (create, edit add, delivery), the in-request first-typed rule,
  respell everywhere, and a single datalist entry.
- Constraint-violation mapping via a throwaway trigger (location vs name).

### Manual Testing Steps:

1. Copy a cell containing `A1` (with trailing space) from a spreadsheet and paste it into a receipt row
   for a part that has `A1`. No new location appears.
2. Paste the same cell for a different part. It stores `A1`, and the suggestions list shows one `A1`.
3. Edit one part to `a1`. Every part with that shelf now shows `a1`.

## Performance Considerations

One indexed lookup per newly added location. The functional index covers `(part_id, lower(location))`,
and the cross-part lookup scans `lower(location)` over a small table. That is negligible at this
plant's scale. The respell bulk update runs only on a case-only edit.

## Migration Notes

- V10 rewrites stored spellings and deletes per-part case duplicates. It's safe now because no
  production DB exists. If a deployment lands before this change, re-check for data loss: deleted
  duplicate rows only ever named the same shelf.
- Rollback means a new migration. Flyway is forward-only per AGENTS.md.

## References

- Frame brief: `context/changes/case-insensitive-locations/frame.md`
- Research: `context/changes/case-insensitive-locations/research.md`
- Prior decisions: `context/changes/delivery-receipt/reviews/impl-review.md` F6,
  `context/changes/delivery-receipt/reviews/plan-review.md` F3, `context/changes/delivery-receipt/plan.md` addenda
- Precedents: `V4__normalize_account_emails.sql`, `src/test/java/pl/regavio/stockahead/account/V4MigrationTests.java`,
  `DeliveryIntegrationTests.java:715-737` (trigger-simulated violation)
- Lessons: `context/foundation/lessons.md` "Handle DB constraint violations at write boundaries"

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles. See `references/progress-format.md`.

### Phase 1: Commit the delivery-receipt triage

#### Automated

- [x] 1.1 `./mvnw verify` passes on the working tree before committing — cfd229f
- [x] 1.2 `git status` after the commit shows only `context/changes/case-insensitive-locations/` and `skills-lock.json` as untracked — cfd229f

#### Manual

- [x] 1.3 F6 in `delivery-receipt/reviews/impl-review.md` reads consistently with the code (no "NBSP not addressed") — cfd229f

### Phase 2: Normalization and per-part DB guarantee

#### Automated

- [x] 2.1 `./mvnw verify` passes — 7c456ef
- [x] 2.2 `V10MigrationTests` covers cross-part unification, per-part dedupe keeping the lowest id, and index rejection of a case duplicate — 7c456ef
- [x] 2.3 A delivery or part-create test proves `A1​` / `﻿A1` match an existing `A1` — 7c456ef
- [x] 2.4 A trigger-simulated location-index violation on part edit re-renders with `parts.error.locationConflict` — 7c456ef

#### Manual

- [x] 2.5 In `./mvnw spring-boot:test-run`, editing a part that has `A1` and pasting `a1` from a spreadsheet cell (trailing space/NBSP) in its place leaves exactly one location row for that part — 7c456ef

### Phase 3: One spelling per shelf across parts

#### Automated

- [x] 3.1 `./mvnw verify` passes — 5126a3a
- [x] 3.2 Tests prove create, edit-add and delivery store an existing shelf's spelling from another part — 5126a3a
- [x] 3.3 A test proves a brand-new shelf typed in two spellings within one receipt is stored once, in the first-typed spelling — 5126a3a
- [x] 3.4 A test proves a case-only respell on edit updates the shelf on all other parts and keeps the edited row's id — 5126a3a

#### Manual

- [x] 3.5 In `./mvnw spring-boot:test-run`: give part X `A1`, receive a delivery for part Y with `a1 ` pasted from a spreadsheet. The parts list shows `A1` for both, and the delivery form's location suggestions list `A1` once — 5126a3a
- [x] 3.6 Edit X to `a1`; both parts and the picking list show `a1` — 5126a3a
