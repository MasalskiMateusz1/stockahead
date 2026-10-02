# Case-insensitive Locations — Plan Brief

> Full plan: `context/changes/case-insensitive-locations/plan.md`
> Frame brief: `context/changes/case-insensitive-locations/frame.md`
> Research: `context/changes/case-insensitive-locations/research.md`

## What & Why

The request: a shelf name pasted from a spreadsheet (`'A1 '`) or typed in another case (`a1`) must not
become a second location. The frame found that this already works per part in the uncommitted working
tree. The remaining job is to commit it under the right owner, then close the gaps: no DB guarantee,
zero-width characters, and two spellings of one shelf across parts.

## Starting Point

`PartLocation.normalize`/`sameLocation` (NBSP-safe, case-insensitive) are used by every location
writer in `PartController` and `DeliveryController`. They are uncommitted and filed as
`delivery-receipt` F6. The DB constraint `UNIQUE (part_id, location)` is still case-sensitive, and
matching stops at the part boundary.

## Desired End State

Pasted or typed variants of a shelf never create a second row on a part, and the DB enforces it. A
shelf has one spelling on every part. Adding `a1` anywhere stores the existing `A1`, and re-spelling
it on one part renames it everywhere. The delivery form suggests each shelf once.

## Key Decisions Made

| Decision | Choice | Why (1 sentence) | Source |
| --- | --- | --- | --- |
| Is case/NBSP re-implemented? | No, reuse the working-tree code | Frame showed it already works on every writer | Frame |
| Where the existing diff is committed | Under `delivery-receipt` (F6), Phase 1 | Matches how F6 records it; the diff touches delivery-receipt files | Plan |
| Stale F6 "NBSP not addressed" bullet | Corrected before commit | It contradicts `normalize` and three tests | Frame |
| DB backstop | V10 `(part_id, lower(location))` unique index, replacing the V3 constraint | No prod DB yet, so the dedupe is free now | Plan |
| Wider normalization | Strip U+200B/U+FEFF at the ends; no inner-space collapsing | The only chars still breaking `A1` in the jshell check | Plan |
| Spelling across parts | Reuse the spelling already used by any part (oldest row wins) | One spelling per shelf in lists and suggestions | Plan |
| Case-only respell on edit | Renames the shelf on every part | Keeps the invariant and allows fixing a wrong first spelling | Plan |
| Existing cross-part variants | V10 unifies them to the lowest-id spelling | Invariant holds from the start; the datalist query stays as is | Plan |
| New shelf in two spellings in one receipt | First typed wins across parts | Same rule the per-part merge already uses | Plan |
| Location-index violation | Own message `parts.error.locationConflict` | Lessons: map constraint violations at the write boundary | Research |

## Scope

**In scope:**

- Commit the delivery-receipt triage and fix the F6 note
- Zero-width edge stripping
- V10 migration (unify, dedupe, functional index) and its tests
- Constraint-name-aware error mapping in `PartController`
- Removing the legacy-pair pass and its test
- Cross-part spelling resolution in create, edit and delivery
- Respell everywhere on edit

**Out of scope:**

- Inner-whitespace collapsing
- A DB guarantee across parts
- A global location entity
- Canonical lower- or uppercasing
- SQL normalization of NBSP in legacy rows
- delivery-receipt F1 (part-row lock on edit)
- S-12 CSV import

## Architecture / Approach

`PartLocation.normalize` stays the single normalization point and `sameLocation` the single
comparison. A new request-scoped resolver, backed by a `PartRepository` lookup
(`LOWER(location) = LOWER(:x)`, oldest first), gives every writer the canonical spelling. A
`@Modifying` bulk update re-spells a shelf on other parts after an edit's flush. V10 makes the stored
data satisfy both invariants and enforces the per-part one.

## Phases at a Glance

| Phase | What it delivers | Key risk |
| --- | --- | --- |
| 1. Commit delivery-receipt triage | Existing case/NBSP work verified, committed and correctly documented | Committing unrelated untracked files; exclude this folder and `skills-lock.json` |
| 2. Normalization + per-part DB guarantee | Zero-width stripping, V10 index, location-specific error, legacy pass removed | V10 step order: drop the old constraint before unifying |
| 3. One spelling per shelf | Cross-part spelling lookup on every writer; respell everywhere on edit | Bulk update vs persistence context: flush first and exclude the edited part |

**Prerequisites:** Docker running for `./mvnw verify`; the working tree as it is now (uncommitted delivery-receipt triage).
**Estimated effort:** ~2 sessions across 3 phases (Phase 1 is minutes).

## Open Risks & Assumptions

- Across parts, "one spelling" is app-level only. Two concurrent writes that introduce the same
  brand-new shelf on two different parts in different case can store two spellings. This was
  accepted, because the per-part index is the guarantee that protects stock rows.
- Postgres `lower()` and Java `equalsIgnoreCase` can disagree on exotic characters. If they do, the
  index still prevents duplicates and the user sees `locationConflict`. This is irrelevant for shelf
  codes.
- A respell on one part silently changes other parts' rows. This is the intended behaviour, but it is
  invisible on the edit form.
- Assumes no production DB exists before V10 ships. If a deployment comes first, re-check V10's
  data rewrite.

## Success Criteria (Summary)

- Pasting `A1 ` (any trailing whitespace, NBSP, U+200B/U+FEFF) or typing `a1` never creates a second
  row for a part that has `A1`, and the DB rejects it even under a race.
- Every part shows the same spelling for a shelf, and the delivery form suggests it once.
