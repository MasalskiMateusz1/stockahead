<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: Delivery Receipt (S-10)

- **Plan**: context/changes/delivery-receipt/plan.md
- **Scope**: Full plan
- **Reviewed phases**: 1, 2, 3
- **Date**: 2026-10-02
- **Verdict**: NEEDS ATTENTION
- **Findings**: 0 critical, 2 warnings, 4 observations

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| Plan Adherence | PASS |
| Scope Discipline | WARNING |
| Safety & Quality | WARNING |
| Architecture | PASS |
| Pattern Consistency | PASS |
| Success Criteria | PASS |

Success criteria: `./mvnw verify` was re-run during this review and passed (exit 0). Every automated item has a test, and every planned test case exists at a specific line. Manual items 1.3, 2.3 and 2.4 are checked off with commit shas. The UI behaviour behind them is exercised by the integration tests, but the manual runs themselves cannot be checked from the diff.

## Findings

### F1 — Part edit and deactivate can overwrite delivered stock (pre-existing)

- **Severity**: ⚠️ WARNING
- **Impact**: 🔬 HIGH — architectural stakes; think carefully before deciding
- **Dimension**: Safety & Quality
- **Location**: src/main/java/pl/regavio/stockahead/parts/PartController.java:200, :242-248, :258-280
- **Detail**: This code was not part of the diff, but the new receipt screen makes the problem more likely to happen.
  - `edit` calls `findById(id)` outside a transaction (:200). Open-in-view is on, so the `Part` stays in the request's persistence context.
  - The `findById` inside the transaction (:243) then returns that same stale instance, with no row lock.
  - `Part` has no `@Version` and no `@DynamicUpdate`, so `saveAndFlush` writes every column, including the stale `quantity`.
  - `deactivate` and `reactivate` read without a lock as well. Their gap is narrower but real under READ COMMITTED.
  - Failure: a technician's receipt commits between the manager's load and flush. Stock reverts to the old value, while the reservations the allocator just computed from the delivered stock stay in place. Reserved then exceeds stock, which breaks the AGENTS.md hard rule (lock the part row before changing stock; never double-reserve).
  - Picks and cancels already had this exposure. The receipt adds a new, frequent stock writer used by technicians.
- **Fix A ⭐ Recommended**: Add `@DynamicUpdate` to `Part`, so name and active updates never write `quantity`. Add a concurrency test of a delivery against a part edit.
  - Strength: A one-annotation change that closes the stock overwrite for every name, active and location write path at once.
  - Tradeoff: It relies on Hibernate's dirty-column tracking rather than an explicit lock. A concurrent name edit can still last-write-win, which is harmless.
  - Confidence: MED — standard Hibernate behaviour, but no test proves it here yet.
  - Blind spot: Other entity writers of `Part` have not been audited.
- **Fix B**: In a separate change, lock with `findByIdInForUpdate(Set.of(id))` inside the transaction, after evicting or refreshing the open-in-view copy. Add the same concurrency test.
  - Strength: Matches the AGENTS.md "lock the part row" rule literally, and the `PickingController` pattern.
  - Tradeoff: More code across three handlers. It needs the open-in-view clear/refresh dance documented in `PickingController`.
  - Confidence: HIGH — the same pattern is proven in pick and cancel.
  - Blind spot: None significant.
- **Decision**: QUEUED (Fix B) — follow-ups/review-fixes.md, to be done as a separate change

### F2 — Pressing Enter in a row submits "Dodaj wiersze", not the receipt

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: src/main/resources/templates/deliveries-new.html:51-52
- **Detail**: When the user presses Enter in a form, HTML uses the first submit button in document order. Here that button is `name=action value=addRows`. A technician who types a quantity and presses Enter gets 5 extra rows and no receipt. Nothing is lost, but it is confusing, and the user may think the delivery was recorded.
- **Fix**: Put "Przyjmij dostawę" before "Dodaj wiersze" in the markup. Alternatively, keep the visual order and add a visually hidden default submit button first.
- **Decision**: FIXED — buttons swapped in deliveries-new.html

### F3 — Unplanned additions: location datalist and `partInvalid` key

- **Severity**: 💡 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Scope Discipline
- **Location**: src/main/java/pl/regavio/stockahead/parts/PartRepository.java:31-33; DeliveryController.java:281; deliveries-new.html:43,48-50; messages.properties:79
- **Detail**: Two things go beyond the plan:
  - `findAllLocationNames()` and a `<datalist id="known-locations">` add location autocomplete. It is benign: a scalar query that loads no `Part`, runs only on re-renders, and is tested at DeliveryIntegrationTests.java:289-292.
  - The partId parse error uses a new key, `deliveries.error.partInvalid`. The plan named no key for it.
- **Fix**: Add a one-line addendum to the plan's Phase 1 Changes Required noting both.
- **Decision**: FIXED — addendum added to plan.md Phase 1 §1

### F4 — `saveFailed` branch is untested

- **Severity**: 💡 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: src/main/java/pl/regavio/stockahead/parts/DeliveryController.java:130-132
- **Detail**: No test triggers `DataIntegrityViolationException` (for example a concurrent `UNIQUE (part_id, location)` hit) or lock-retry exhaustion. The sibling cancel and pick suites leave their failure branches untested too, so this is consistent with the existing pattern, not a regression.
- **Fix**: Add one test that installs a throwaway `BEFORE INSERT` trigger on `part_locations` raising SQLSTATE 23505. Assert that the form re-renders with the `saveFailed` text and that stock and locations are unchanged. Drop the trigger in `@AfterEach`.
- **Decision**: FIXED — `constraintViolationDuringWriteReRendersWithSaveFailedAndWritesNothing` in DeliveryIntegrationTests (31/31 green)

### F5 — "poz." in the success flash counts distinct parts, not rows

- **Severity**: 💡 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: src/main/java/pl/regavio/stockahead/parts/DeliveryController.java:138-139
- **Detail**: `lines.size()` counts parts after merging. Three rows, two of them the same part, show "2 poz.", and the test at DeliveryIntegrationTests.java:681 asserts exactly that. The unit total is correct.
- **Fix**: Confirm that "poz." means distinct parts. If so, nothing to change.
- **Decision**: NO CHANGE NEEDED — user confirmed "poz." = distinct parts

### F6 — Location matching is case- and NBSP-sensitive

- **Severity**: 💡 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: src/main/java/pl/regavio/stockahead/parts/DeliveryController.java:172-173, :228
- **Detail**: "Regał A1" and "regał a1" are stored as two locations. `String.trim()` keeps a trailing non-breaking space pasted from a spreadsheet, so that value also becomes a new location. This matches `PartController.parseLocations` (the plan required it), and nothing crashes.
- **Fix**: Leave it consistent for now. If it becomes a problem, add one shared normaliser (`strip()` plus case policy) used by both controllers.
- **Decision**: FIXED differently — locations are now case-insensitive at the app level in both controllers, with no migration. The DB `UNIQUE (part_id, location)` stays case-sensitive as a backstop.
  - `PartLocation.sameLocation` is the single comparison.
  - The receipt merges rows ignoring case and skips locations the part already has.
  - The `PartController` duplicate check ignores case, and edit re-spells a row that matches only by case (exact matches are claimed first).
  - Tests added: `DeliveryIntegrationTests.locationsAreMatchedIgnoringCase`, plus three in `PartsCatalogIntegrationTests`.
  - NBSP is handled too: `PartLocation.normalize` turns U+00A0/U+2007/U+202F into plain spaces before `strip()`. Tests: `DeliveryIntegrationTests.locationsPastedWithNonBreakingSpacesMatchTypedOnes`, `PartsCatalogIntegrationTests.nonBreakingSpacesInLocationsAreNormalizedOnCreate` and `createWithLocationLinesDifferingOnlyByNonBreakingSpaceFailsValidation`.
  - Further hardening (zero-width characters, a case-insensitive DB index, one spelling per shelf across parts) continues in `case-insensitive-locations`.

## Triage summary

- Fixed: F2, F3, F4, F6 (fixed differently: case-insensitive locations)
- No change needed: F5
- Queued as a separate change: F1 (Fix B) → follow-ups/review-fixes.md
- `./mvnw verify` after fixes: 275 tests, 0 failures
