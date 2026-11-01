# Review follow-ups: company-scoped-picking-completion

Source: `reviews/impl-review.md` (2026-10-29).

## F3: Load the current account by (id, company_id) instead of by email

- **Decision**: Fix B, as its own change (open with `/10x-new`).
- **Problem**: The current user's account is resolved with `AccountRepository.findByCanonicalEmail(authentication.getName())`, a cross-company native query. It is safe today (email is globally unique, and V18 backstops the completion reporter), but AGENTS.md lists only the login lookup as an intentional cross-company exception.
- **Call sites**: `orders/PickingController.java` (list assignee filter ~:139, report-completion reporter ~:285), `orders/OrderController.java:389`, `parts/StockCorrectionController.java:237`.
- **Intent**: Add `accountId()` to `security/CompanyUser`, filled at login. Load the current account with a company-scoped lookup (`findByIdAndCompanyId(principal.accountId(), principal.companyId())`, or `getReferenceById` where only a reference is needed). Remove the non-login uses of `findByCanonicalEmail`.
- **Watch**: The serialized principal shape changes, so existing sessions need a re-login. Check how `CompanyUser` is built in the `UserDetailsService` and in the test login helpers, and keep the login email lookup as the one documented exception.

## F4: Composite account FKs still missing on cancelled_by and stock_corrections

- **Decision**: Hand off to S-05 (`company-scoped-stock-movements`) and a follow-up for `cancelled_by`.
- **Problem**: `orders.cancelled_by` (V9) and `stock_corrections.account_id` (V11) still reference `accounts(id)` alone, so the DB does not guarantee the actor belongs to the row's company. `stock_corrections` has no `company_id` column yet (no migration after V14 touches it).
- **For S-05**: When `stock_corrections` gets its `company_id` (AGENTS.md § Company isolation lists corrections as company-owned), replace `stock_corrections_account_id_fkey` with `FOREIGN KEY (account_id, company_id) REFERENCES accounts (id, company_id)`, and `stock_corrections_part_id_fkey` with `(part_id, company_id) REFERENCES parts (id, company_id)`. Copy the V17/V18 pattern.
- **For `cancelled_by`**: Swap `orders_cancelled_by_fkey` for `(cancelled_by, company_id) REFERENCES accounts (id, company_id)`. MATCH SIMPLE keeps NULL passing. Fold it into S-05's migration or the next slice touching cancellation (S-09).
