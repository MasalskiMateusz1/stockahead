# Frame Brief: Case-insensitive locations, pasted values normalized like typed ones

> Framing step before /10x-plan. This document captures what is *actually*
> at issue, separated from what was initially assumed.

## Reported Observation

The request in `change.md`: locations should be case-insensitive. A location pasted from a spreadsheet
should be trimmed the same way as a typed one. For example, `'A1 '` from a spreadsheet must not create
a new location when `A1` already exists.

## Initial Framing (preserved)

- **User's stated cause or approach**: `trim()` doesn't remove some characters that come from a
  spreadsheet, and location matching is case-sensitive. A spreadsheet value therefore becomes a second
  shelf.
- **User's proposed direction**: make location matching case-insensitive and normalize pasted values
  exactly like typed ones.
- **Pre-dispatch narrowing**:
  - *Where seen:* "earlier I got information that trim() does not clear some characters from
    spreadsheet". This was not reproduced; it is a reported finding (delivery-receipt plan-review F3).
  - *Build:* current working tree.
  - *Scope:* the same part already has `A1`.

## Dimension Map

1. **Normalization coverage**: some character a spreadsheet produces survives `normalize`, so the value
   doesn't match `A1`. ← initial framing
2. **Comparison rule**: matching against the same part's existing rows is still case- or
   exact-sensitive. ← initial framing
3. **Unrouted writer**: a write path stores a location without going through
   `normalize`/`sameLocation`.
4. **Ownership / verification**: the behaviour exists, but it is uncommitted, filed under another change
   (`delivery-receipt`), and contradicted by a stale review note. That makes it look unsolved.
5. **Concurrency backstop**: under concurrent writes, the case-sensitive DB unique constraint lets a
   case variant through.

## Hypothesis Investigation

| Hypothesis | Evidence | Verdict |
| --- | --- | --- |
| 1. Normalization misses spreadsheet chars | `PartLocation.java:69-71` maps U+00A0/U+2007/U+202F to a space, then `strip()`. A jshell run of the same expression matched `A1` for U+0020, U+00A0, U+0009, U+000D, U+3000 and U+2009 trailing. Only U+200B and U+FEFF did **not** match. No observed spreadsheet sample contains either. | WEAK |
| 2. Same-part comparison is case-sensitive | The working tree uses `sameLocation` (`equalsIgnoreCase` after `normalize`, `PartLocation.java:57-59`) at `DeliveryController.java:173`, `:315` and `PartController.java:297`, `:333`. Tests: `DeliveryIntegrationTests.java:639,655`, `PartsCatalogIntegrationTests.java:542-603`. True only on HEAD `d375a80`, not on the working tree the user is running. | NONE (in working tree) |
| 3. A writer bypasses normalization | `setLocation(` in `src/main` appears at exactly 4 call sites. `PartController.java:166` and `:336`/`:348` get values from `parseLocations` (normalized, `:293`). `DeliveryController.java:176` gets values from `validate` (normalized, `:229`). Raw `INSERT`s appear only in test fixtures. | NONE |
| 4. Behaviour exists but is unowned/unverified | `git status` shows the code is uncommitted. `delivery-receipt/reviews/impl-review.md` F6 records it as that change's triage. Its last bullet ("NBSP part … not addressed") is contradicted by `normalize` plus 3 NBSP tests. The user did not reproduce the bug; they acted on the earlier review note. | STRONG |
| 5. No case-insensitive DB backstop | `V3__create_parts_catalog.sql`: `UNIQUE (part_id, location)` compares exact strings. `delivery-receipt/plan.md` addendum F6 accepts this as a known gap. No reported incident. | WEAK (real but latent) |

## Narrowing Signals

- The user runs the current working tree and is concerned about the **same part**. That is exactly
  dimension 2's scope, which the working tree covers in both write paths.
- The trigger was a **review note**, not a reproduced bug. The note's NBSP claim predates the
  `normalize` fix.
- Step 3 evidence was conclusive (one STRONG, the others WEAK/NONE), so the Step 4 questioning round was
  skipped.

## Cross-System Convention

- Prior occurrence: `delivery-receipt/reviews/plan-review.md` F3 (trailing NBSP) and `impl-review.md`
  F6 (case plus NBSP) raised this exact observation. Both were resolved in the same uncommitted diff.
- Single-point normalization plus case-insensitive comparison, with first spelling stored, is the
  pattern now in place. The project's other normalized identity, account email (V4 migration), chose a
  DB-level fix instead. That is a precedent for dimension 5, not evidence it's needed now.

## Reframed (or Confirmed) Problem Statement

> **The actual problem to plan around is**: the requested behaviour already exists in the uncommitted
> working tree but is unverified and unowned. It is mixed into the `delivery-receipt` triage, and a stale
> review note says the NBSP case is still open.

The user's framing (trim misses spreadsheet characters, and matching is case-sensitive) was **correct
for HEAD `d375a80`**. It no longer describes the code they run. The remaining work is not "make it
case-insensitive". It is:

1. Decide where the existing diff is committed and verify it with `./mvnw verify`.
2. Correct the stale impl-review note.
3. Optionally harden the two latent edges: U+200B/U+FEFF, and the case-insensitive DB unique index.
   Neither has an observed trigger.

## Confidence

**HIGH**: a direct code read and an executable check of `normalize`. Every writer is accounted for, and
the user's own answers place the concern inside the covered scope.

## What Changes for /10x-plan

The plan should not re-implement case-insensitivity or NBSP trimming. It should cover:

1. Committing and verifying the existing diff, with explicit ownership: delivery-receipt or this change.
2. Fixing the stale note.
3. A recorded keep-or-skip decision on U+200B/U+FEFF stripping and a V10
   `(part_id, lower(location))` unique index. The index implies deduplicating legacy rows, rewriting
   `PartsCatalogIntegrationTests.java:603`, and mapping the violation in `PartController`.

If both hardening items are skipped, this change may collapse into a commit of the delivery-receipt
work.

## References

- Source files: `PartLocation.java:57-71`; `PartController.java:166,289-303,318-352`;
  `DeliveryController.java:173-176,229,312-317`; `V3__create_parts_catalog.sql`
- Related research: `context/changes/case-insensitive-locations/research.md`
- Prior decisions: `context/changes/delivery-receipt/reviews/plan-review.md` F3,
  `reviews/impl-review.md` F6, `plan.md` Phase 1–2 addenda
- Investigation tasks: none registered. The evidence was already in hand from research plus one jshell
  check, so no sub-agents were dispatched.
