# Lessons Learned

> Append-only register of recurring rules and patterns. Re-read at start by /10x-frame, /10x-research, /10x-plan, /10x-plan-review, /10x-implement, /10x-impl-review.

## Handle DB constraint violations at write boundaries

**Context:** src/main/java/pl/regavio/stockahead/account/SetupController.java:68

**Problem:** `accountRepository.save()` has no error handling. A constraint violation (duplicate email, or a race hitting a unique index) surfaces as a raw 500 instead of the friendly re-rendered form the same method already uses for token/password-mismatch errors.

**Rule:** Any controller that writes to a table with a uniqueness constraint (unique column, or a partial unique index like `accounts_single_manager_idx`) must catch `DataIntegrityViolationException` around the save and respond the same way the method's own validation failures already do — re-render the form with a clear error, never let the constraint violation reach the client as a raw 500.

**Applies to:** Any future controller writing rows guarded by a DB uniqueness constraint — this becomes relevant again for S-01…S-10 wherever a "first one wins" or "only one allowed" invariant is backed by a unique index rather than pure application logic.

## Test the wrong-role case for every role-gated route

**Context:** src/main/java/pl/regavio/stockahead/account/ManagerPingController.java:16

**Problem:** Role restriction lives entirely in method-level `@PreAuthorize`; `SecurityConfig` only enforces authentication at the URL level. A future manager-only route that forgets the annotation silently degrades to "any authenticated user," with nothing structural to catch it.

**Rule:** Every `@PreAuthorize`-gated route must ship with a test asserting 403 for an authenticated account lacking the required role, not just a happy-path 200 test for the correct role.

**Applies to:** Every future role-gated controller in S-01…S-10 relying on `@PreAuthorize` as its sole enforcement layer.

## Consider JOIN FETCH for list/detail views once row counts grow

**Context:** src/main/java/pl/regavio/stockahead/orders/OrderController.java:63-70, src/main/java/pl/regavio/stockahead/orders/OrderDetailModel.java:48-58 (also present in ProjectDetailModel.java/PartController.java)

**Problem:** `GET /orders` and `GET /orders/{id}` render associated entity names (`order.project.name`, `line.getPart().getName()`) via default JPA `@ManyToOne` loading with no `JOIN FETCH` — one extra query per order (list) / per line (detail). Same shape as the pre-existing `ProjectDetailModel`/`PartController` pattern; not egregious at this tool's expected row counts, but a recurring shape across every list/detail view in the codebase.

**Rule:** Add `JOIN FETCH` once a list/detail view's row count is expected to exceed ~50-100, or once query-count monitoring flags it.

**Applies to:** Any future list/detail view rendering a related entity's fields.

## Guard user-supplied multipliers against int overflow before relying on a DB CHECK

**Context:** src/main/java/pl/regavio/stockahead/orders/OrderController.java:131

**Problem:** `line.setRequiredQuantity(bomLine.getQuantityPerUnit() * parsedQuantityUnits)` has no overflow guard on the user-supplied `quantityUnits`. An extreme value could overflow `int` to a negative `required_quantity`; this is caught by the DB `CHECK (required_quantity > 0)` constraint and surfaces as the generic `orders.error.saveFailed` message rather than a targeted validation error. Not exploitable, just a confusing failure mode.

**Rule:** When a user-supplied integer is multiplied into a value guarded only by a DB `CHECK` constraint, add an explicit upper-bound validation with a dedicated error message rather than relying on the DB constraint's generic `DataIntegrityViolationException` path.

**Applies to:** Any future form field whose value is multiplied by another quantity before being persisted.

## Narrowing ReservationAllocator's part-row lock is unsafe without deadlock-retry logic

**Context:** src/main/java/pl/regavio/stockahead/orders/ReservationAllocator.java:53-72, src/main/java/pl/regavio/stockahead/parts/PartRepository.java:26-28

**Problem:** `findAllForUpdate()` locks every row of the `parts` table on every order creation — correct but a scalability ceiling. Two narrower alternatives were analyzed and rejected: (1) computing "parts referenced by open orders" via an unlocked query before locking reintroduces a real race — a concurrent transaction can commit a new order for a shared part in the gap between the read and the lock, silently under-counting that reservation. (2) An iterative lock-escalation loop (lock what's known → recheck → lock anything new → repeat) closes that race but is deadlock-prone across concurrent transactions: two transactions can each hold a partial, overlapping lock set acquired in different orders across rounds and wait on each other forever, which Postgres resolves by killing one transaction with an unhandled error — silently failing that order-creation request.

**Rule:** Any future attempt to narrow this lock's scope must ship with explicit catch-and-retry logic around the whole order-creation transaction for a Postgres deadlock error, verified under both `ReservationAllocatorTests`-style algorithmic tests and `ReservationConcurrencyTests`/`OrderCreationIntegrationTests`-style real-concurrency tests — plan it as its own change via /10x-plan rather than a quick inline edit, since it touches AGENTS.md's hard rule (stock never negative, never double-reserved).

**Applies to:** Any future change to ReservationAllocator's locking strategy, and any other code in this codebase that locks a computed/derived row set across multiple query round-trips instead of one upfront query.

## Don't re-subtract a counter that's already net of what you're subtracting

**Context:** src/main/java/pl/regavio/stockahead/orders/PickingController.java:121 (also present in PickingDetailModel.java's now-fixed `remainingToPick`), found via manual testing of `picking-list-and-pick` Phase 4.

**Problem:** `OrderLine.reservedQuantity` is a *live* counter: every pick shifts its amount out of `reservedQuantity` directly into `pickedQuantity` (`reservedQuantity -= q; pickedQuantity += q`), so `reservedQuantity` already reflects "reserved and not yet picked, right now" after every prior pick. The pick action computed `maxPickable = reservedQuantity - pickedQuantity`, treating `reservedQuantity` as if it were a *static, original* total instead — double-counting every prior pick. The bug was invisible in review and in the first round of tests because every test case only exercised a *single* pick per line (where `pickedQuantity` starts at 0, so the wrong formula and the right one agree); it only surfaces once a line is picked more than once and the first pick removes more than half of what was reserved, at which point the next pick's `maxPickable` goes negative and is wrongly rejected — in the UI, `remainingToPick` going negative also hid the pick form entirely via `th:if="${line.remainingToPick > 0}"`, which is how it was reported ("technician can't pick equipment" / "quantity to pick can be negative" — the same root cause, two symptoms).

**Rule:** Before subtracting field B from field A to get "how much of A is left", check whether A is already defined as *net of* B (decremented every time B increments). If so, A alone is the answer — subtracting B again double-counts. When a field's own history matters (first pick vs. a later one), write a test that performs the mutating action *at least twice* on the same row with the *second* request large enough to expose a double-counted guard (e.g., pick more than half of a reservation, then try to pick the genuine remainder) — a test that only ever acts once per row cannot catch this class of bug, and a raw-SQL-seeded fixture that sets both fields independently can silently describe a state the real action could never produce, masking the same bug.

**Applies to:** Any future code (this codebase's or elsewhere) that maintains a "remaining"/"available" counter alongside a cumulative counter fed from the same mutation, and any test suite's coverage for a repeatable mutating action on one row — S-09 (`order-change-and-cancel`) and any other future change touching `OrderLine.reservedQuantity`/`pickedQuantity` should re-check this invariant before adding new call sites.

## Center cell content in every table template

- **Context**: Any Thymeleaf template under `src/main/resources/templates/` that renders a `<table>`.
- **Problem**: There is no shared stylesheet, so a new table renders left-aligned and looks different from the others. This had to be fixed by hand across 8 templates during `taken-order-top-up` (2026-10-02).
- **Rule**: Every template with a `<table>` must include the inline `<style>td, th { text-align: center; }</style>` block in `<head>`, as in `purchasing-list.html`.
- **Applies to**: plan, implement, impl-review
