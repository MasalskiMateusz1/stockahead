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
