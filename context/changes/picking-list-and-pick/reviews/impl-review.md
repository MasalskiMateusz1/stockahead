<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: Picking List and Pick Implementation Plan

- **Plan**: context/changes/picking-list-and-pick/plan.md
- **Scope**: Full plan
- **Reviewed phases**: 1, 2, 3, 4
- **Date**: 2026-10-01
- **Verdict**: NEEDS ATTENTION
- **Findings**: 0 critical, 3 warnings, 0 observations

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| Plan Adherence | WARNING |
| Scope Discipline | WARNING |
| Safety & Quality | PASS |
| Architecture | PASS |
| Pattern Consistency | WARNING |
| Success Criteria | PASS |

## Verified non-issue (for the record)

The safety/quality sub-agent initially flagged `V7__add_order_picking.sql:4`'s `ALTER TABLE order_lines DROP CONSTRAINT order_lines_check;` as a CRITICAL migration bug, reasoning that Postgres should have auto-named V6's inline multi-column CHECK as `order_lines_reserved_quantity_check` instead. Before trusting this, it was checked against `target/surefire-reports/pl.regavio.stockahead.StockaheadApplicationTests.txt` and the Picking test classes' cached reports (all dated 2026-10-01 16:36–16:37, immediately preceding the Phase 4 commit `fa83764`): `StockaheadApplicationTests` (Spring context + Flyway boot) and all of `PickingConcurrencyTests`, `PickingIntegrationTests`, `PickingListAndDetailIntegrationTests`, `ReservationAllocatorTests` passed with 0 failures/errors. Flyway could not have reached any of these tests if `DROP CONSTRAINT order_lines_check` were wrong. Confirmed as a false positive — no action needed.

## Findings

### F1 — ReservationAllocator's stock-pool seeding mechanism changed without updating the plan

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Plan Adherence / Architecture
- **Location**: `src/main/java/pl/regavio/stockahead/orders/ReservationAllocator.java:68-79`, `src/main/java/pl/regavio/stockahead/parts/PartRepository.java:31-45`

- **Detail**: Phase 2's plan contract only describes partitioning taken/reallocatable lines and subtracting a taken order's reservation from the pool — it says nothing about changing how `remainingStockByPartId` is seeded, implying the existing seeding logic (presumably reading `Part.quantity` off the locked entities from `findByIdInForUpdate`) continues unchanged. The actual code now seeds the pool from a new scalar-projection query, `PartRepository.findCurrentQuantities(partIds)`, and only calls `findByIdInForUpdate` for its locking side effect, discarding its return value. Both the new repository method's Javadoc and an inline comment in `ReservationAllocator` explain why: with `spring.jpa.open-in-view`, a caller earlier in the same transaction (concretely, `OrderController.create` resolving `BomLine.getPart()` at `OrderController.java:138`, before `reallocateForParts` is called) can already have a stale `Part` managed in the persistence context, and a second full-entity read for the same id would hand back that stale instance rather than the DB's current row — defeating the lock. This is the exact same `open-in-view` staleness class the plan already reasons about for `PickingController`'s pre-lock `partId` lookup (Phase 3), just discovered independently for `ReservationAllocator`'s own stock-pool read, and never written into Phase 2's contract.

- **Fix**: Add a short paragraph to Phase 2 §1's contract documenting the `findCurrentQuantities` scalar-projection seeding and the `open-in-view` staleness rationale, so the plan's stated data-freshness guarantee for `reallocateForParts` matches what's actually shipped. This is a correct, verified-necessary fix (confirmed: `OrderController.create` really does touch the same `Part` earlier in its own transaction) — not something to revert, just to document.

### F2 — `orders-list.html` gained an undocumented link in Phase 4

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Scope Discipline
- **Location**: `src/main/resources/templates/orders-list.html:12`

- **Detail**: Commit `fa83764` adds `<p><a th:href="@{/orders/new}">Nowe zlecenie</a></p>` to `orders-list.html`. This file isn't in Phase 4's "Changes Required" list at all. The commit message explains it closes a pre-existing gap (the `/orders/new` route already existed and was correctly role-gated; only its dashboard/list discoverability link was missing, from an earlier already-archived change), needed to create test orders through the real UI for the new integration tests. Low-risk, additive, one line, doesn't touch any plan contract item or AGENTS.md hard rule.

- **Fix**: Add one line to Phase 4's "Changes Required" (or at minimum the Progress log) noting `orders-list.html` was touched to add the missing "Nowe zlecenie" link, so the plan's file list matches what it actually changed.

### F3 — `OrderControllerTests.java` no longer tests `OrderController`

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Pattern Consistency
- **Location**: `src/test/java/pl/regavio/stockahead/orders/OrderControllerTests.java`

- **Detail**: Per the plan, this class's two cases were retargeted to call the extracted `LockRetry` directly instead of `OrderController#executeWithLockRetry` — exactly as planned, confirmed correct. But the class now contains zero `OrderController` coverage; it's purely `LockRetry` coverage left under its pre-extraction name, which is misleading for anyone searching for either class's test coverage.

- **Fix**: Rename the file/class to `LockRetryTests.java`.

## Decisions

### F1 — ReservationAllocator's stock-pool seeding mechanism changed without updating the plan

- **Decision**: FIXED — added the seeding-mechanism/open-in-view paragraph to Phase 2 §1's contract in plan.md.

### F2 — `orders-list.html` gained an undocumented link in Phase 4

- **Decision**: FIXED — added `orders-list.html` as its own "Changes Required" item (§3a) in Phase 4.

### F3 — `OrderControllerTests.java` no longer tests `OrderController`

- **Decision**: FIXED — renamed to `LockRetryTests.java`/`LockRetryTests` (git mv + class rename), plan.md's three references updated, `./mvnw test -Dtest=LockRetryTests` passes (2/2).
