# Load Current Account by (id, company_id) — Plan Brief

> Full plan: `context/changes/load-current-account/plan.md`
> Research: `context/changes/load-current-account/research.md`

## What & Why

The app finds "the account of whoever is signed in" by email, using `findByCanonicalEmail`, a native query that searches every company. AGENTS.md allows that query only for the login lookup. This change puts the account id in the signed-in principal and loads the current account by `(accountId, companyId)`. This carries out F3 (Fix B) from the company-scoped-picking-completion review.

## Starting Point

`CompanyUser` carries only `companyId`. Outside login, 5 places re-read the account by email:
- the per-request deactivation filter
- the picking list
- the completion reporter
- the order canceller
- the stock-correction author

## Desired End State

Login puts `accountId` in the principal. The filter and all four controller sites use it with a company-scoped lookup. `findByCanonicalEmail` is left with one caller, login, and a convention test keeps it that way. What users see stays the same, apart from one re-login after the deploy.

## Key Decisions Made

| Decision | Choice | Why (1 sentence) | Source |
| --- | --- | --- | --- |
| Approach | Fix B: `accountId` on `CompanyUser` | Removes the cross-company read instead of widening the exception list | Research (review F3) |
| Activity filter | Also moves to `(id, company)`; a non-`CompanyUser` principal is logged out | Without it, one non-login email lookup would remain and AGENTS.md would need a second exception | Plan |
| Stock correction | Fix the author lookup now, leave part lookups to S-05 | Closes all F3 sites in one change, and S-05 keeps its own scope | Plan |
| Stale sessions | Raise `serialVersionUID` to 2L | No principal can ever restore with a null `accountId` | Plan |
| Lookup method | `findByIdAndCompanyId`, not `getReferenceById` | `cancelled_by` and `stock_corrections.account_id` have no composite FK, so the app-level check is the only company guard | Research |
| Missing account | Keep 403 | Existing behaviour, and practically unreachable because accounts are never deleted (FR-002) | Plan |
| Regression guard | Source-scan convention test | Same enforcement style as `TemplateConventionTests` | Plan |

## Scope

**In scope:**
- `CompanyUser`
- `AccountUserDetailsService`
- `AccountActivityFilter`
- `PickingController` (2 sites)
- `OrderController`
- `StockCorrectionController` (author only)
- the unit test and a new convention test
- an AGENTS.md pointer

**Out of scope:**
- S-05 part-lookup scoping and the reallocation company
- F4 composite FKs
- Spring Session
- operator accounts
- any change to the login lookup or to the 403 responses

## Architecture / Approach

The id travels with the principal. `AccountUserDetailsService` loads the account by email once, at login, and builds a `CompanyUser(…, companyId, accountId)`. From then on, the filter and the controllers call `findByIdAndCompanyId(principal.accountId(), principal.companyId())`. The one exception is the picking list, which needs only the id itself.

## Phases at a Glance

| Phase | What it delivers | Key risk |
| --- | --- | --- |
| 1. Principal carries the account id | `accountId()` on the principal, UID 2L, filter by `(id, company)`, unit test | The filter breaks deactivation logout. Covered by `TechnicianAccountIntegrationTests` |
| 2. Controllers resolve from principal | 4 sites switched, convention test, AGENTS.md pointer | `StockCorrectionController` signature churn. Its integration and concurrency suites cover it |

**Prerequisites:** Docker running for `./mvnw verify`.
**Estimated effort:** ~1 session across 2 small phases.

## Open Risks & Assumptions

- Assumed: every integration test signs in through `formLogin()`. The grep found no hand-built principals, so they pick up `accountId` with no changes.
- Every user signs in again once after the deploy. Production sessions are dropped on every deploy anyway.

## Success Criteria (Summary)

- Signed-in users can pick, report completion, cancel and correct stock exactly as before, and a deactivated user is still logged out on the next request.
- No code outside login resolves an account by email, and the build fails if someone adds such code.
