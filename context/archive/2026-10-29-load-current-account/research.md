---
date: 2026-10-29T13:57:15+01:00
researcher: Claude (Opus 5.5) for Mateusz Masalski
git_commit: 3ac06e2
branch: multi-company-support
repository: stockahead
topic: "Load the current account by (id, company_id) instead of by email (F3 from company-scoped-picking-completion)"
tags: [research, security, CompanyUser, AccountRepository, company-isolation]
status: complete
last_updated: 2026-10-29
last_updated_by: Claude (Opus 5.5)
---

# Research: Load the current account by (id, company_id) instead of by email

**Date**: 2026-10-29T13:57:15+01:00
**Researcher**: Claude (Opus 5.5) for Mateusz Masalski
**Git Commit**: 3ac06e2 (plus uncommitted edits to `orders/PickingController.java`; its line numbers below come from the working tree)
**Branch**: multi-company-support
**Repository**: stockahead

## Research Question

What does it take to carry out follow-up F3 from `context/changes/company-scoped-picking-completion/follow-ups/review-fixes.md`? F3 adds `accountId()` to `security/CompanyUser`, filled at login. It then loads the current account through a company-scoped lookup and removes the uses of `AccountRepository.findByCanonicalEmail` outside login.

Note on the source: `change.md` § Notes links to `context/archive/2026-09-28-project-bom/follow-ups/review-fixes.md`. That file only holds F7 (BOM indexes) and does not mention account loading. This change-id matches F3 in `context/changes/company-scoped-picking-completion/follow-ups/review-fixes.md:5-11`, so this research uses F3 as the source.

## Summary

- `findByCanonicalEmail` is a native, cross-company query (`account/AccountRepository.java:14-15`). In `src/main`, it has **6 callers**. One is the login lookup (`security/AccountUserDetailsService.java:28`), which AGENTS.md allows. The other **5** are not on AGENTS.md's exception list (`AGENTS.md:36`). F3 lists 4 of them. The fifth is `security/AccountActivityFilter.java:32`, which F3 does not mention. It runs on every authenticated request.
- `CompanyUser` holds only `companyId` beyond Spring's `User` (`security/CompanyUser.java:18-27`). It is built in **one** production place (`AccountUserDetailsService.java:31-32`). A grep for `new CompanyUser` in `src/test` finds 0 hits. The inspected integration tests sign in through real `formLogin()`, for example `orders/PickingIntegrationTests.java:132-133`. Once `accountId` is added to the constructor and the service, the tests' principals get it automatically. In the unit test `security/AccountUserDetailsServiceTest.java`, the `Account` fixtures never call `setId`, so a new `accountId` assertion there must set one.
- The 5 call sites need different things:
  - Picking list (technician branch) needs only the id. The lookup can go away.
  - Completion reporter and order canceller need an `Account` to set on a relation.
  - Stock correction needs an `Account`. Its controller does not take `CompanyUser` yet.
  - The activity filter needs the `active` flag.
- Sessions: the codebase has no Spring Session, and `src/main/resources` has no session-persistence property. Production keeps sessions in memory, and every deploy drops them (`context/foundation/infrastructure.md:98`), so production users simply sign in again after the deploy. One local-dev risk is inferred and not verified: devtools (`pom.xml`, runtime/optional) may persist sessions across restarts. `CompanyUser` keeps `serialVersionUID = 1L` (`CompanyUser.java:16`), so a restored old principal would deserialize with `accountId == null`.

## Detailed Findings

### The query and the rule it bends

- `AccountRepository.findByCanonicalEmail` is `SELECT * FROM accounts WHERE lower(btrim(email)) = :canonicalEmail`, a native query with no company filter (`account/AccountRepository.java:14-15`).
- AGENTS.md says native queries that read company-owned rows must filter by `company_id`. The only account exception it names is "the login email lookup in `AccountRepository`" (`AGENTS.md:36`).
- A company-scoped lookup already exists: `Optional<Account> findByIdAndCompanyId(Long id, Long companyId)` (`account/AccountRepository.java:29`). It is a derived JPQL query, used for example in `account/TechnicianAccountController.java:125`.
- In the inspected entity, accounts always belong to a company: `@JoinColumn(name = "company_id", nullable = false)` (`account/Account.java:42-43`). There is no operator account yet, so `account.getCompany().getId()` at `AccountUserDetailsService.java:32` cannot hit null today. A later operator account (AGENTS.md: "no company") will change that, and the same applies to `companyId()`.

### Principal construction

- `CompanyUser extends User`. It has `serialVersionUID = 1L`, a `Long companyId` field and a 5-argument constructor (`security/CompanyUser.java:14-27`).
- The only production construction is `new CompanyUser(account.getEmail(), account.getPasswordHash(), account.isActive(), authoritiesFor(account.getRole()), account.getCompany().getId())` (`security/AccountUserDetailsService.java:31-32`). `account.getId()` is available at that point, so F3's "filled at login" needs no extra query.
- Unit tests: `security/AccountUserDetailsServiceTest.java` has 6 tests, at :29, :49, :69, :92, :102 and :120. The one at :69 (`principalCarriesTheAccountsCompanyId`) asserts `companyId()` (:84). It is the natural template for an `accountId()` assertion. Its fixtures set the company through the `company(Long id)` helper (:137-142) but never call `Account.setId`, so an id test must call `setId` (`Account.java:49`).
- `security/SecurityRedirectTests.java:31-35` and `security/StaticResourceAccessTests.java:37` replace `AccountUserDetailsService` with a `@MockitoBean`. They never build a principal, so the constructor change does not affect them.
- Integration tests: 32 test files under `src/test` match `formLogin`/`user(`/`authentication(`. The ones inspected use a `loginAs` helper that runs `formLogin().user(email).password(...)`, for example `orders/PickingIntegrationTests.java:132-133`. That path goes through the real `AccountUserDetailsService`, so these tests need no fixture change for `accountId`. I did not open every one of the 32 files to rule out `user(...)`/`@WithMockUser` principals (see Open Questions).

### The 5 non-login call sites

| # | Location | What the code needs | Current failure | Notes |
|---|----------|---------------------|-----------------|-------|
| 1 | `orders/PickingController.java:142-144` (technician branch of `GET /picking`) | Only `self.getId()` for `findByStatusAndAssigneeWithProject(companyId, OPEN, self.getId())` | `ResponseStatusException(FORBIDDEN)` if missing | Handler already takes `@AuthenticationPrincipal CompanyUser principal` (:118). The lookup can become `principal.accountId()` with no DB read. |
| 2 | `orders/PickingController.java:289-292` (report completion) | An `Account` for `order.setCompletionReportedBy(reporter)` | FORBIDDEN | V18 adds `FOREIGN KEY (completion_reported_by, company_id) REFERENCES accounts (id, company_id)` (`V18__company_scoped_completion_reporter.sql:8`), so the DB rejects a mismatched account. That failure is caught as `picking.error.reportFailed` (:298-300). |
| 3 | `orders/OrderController.java:388-390` (cancel) | An `Account` for `order.setCancelledBy(canceller)` (:402) | FORBIDDEN | `orders.cancelled_by` still has a single-column FK (F4, `follow-ups/review-fixes.md:13-18`). The DB does not check the company here, so a company-scoped lookup is the only guard. The handler class already uses `CompanyUser` (`OrderController.java:42`, :217). |
| 4 | `parts/StockCorrectionController.java:237-238` (`applyCorrection`) | An `Account` for `correction.setAccount(author)` (:244) | FORBIDDEN | The controller takes `Authentication`, not `CompanyUser`, in all 4 inspected signatures (:107, :150, :194, :225). Its part lookup `findByIdInForUpdate` is not company-scoped yet. A comment at :253 says S-05 will scope it. `stock_corrections.account_id` also has a single-column FK (F4). |
| 5 | `security/AccountActivityFilter.java:29-41` | `account.isActive()`, checked on every authenticated request | Clears the context, invalidates the session and redirects to `/login?deactivated` | Not listed in F3. It is wired with `addFilterBefore(..., AuthorizationFilter.class)` (`security/SecurityConfig.java:35`). It gets the principal from `SecurityContextHolder` and would need to cast to `CompanyUser` to read the id. `TechnicianAccountIntegrationTests` covers the deactivated-redirect behaviour (it is the only test file matching `login?deactivated`). |

### Choosing between `getReferenceById` and `findByIdAndCompanyId`

- F3 allows either (`follow-ups/review-fixes.md:10`). `getReferenceById` returns a proxy with no existence or company check.
  - Site 2: the V18 composite FK backstops it.
  - Sites 3 and 4: no DB constraint guards the company (F4). `findByIdAndCompanyId` keeps an application-level check and the current FORBIDDEN-on-missing behaviour.
- Inference, not tested: `findByIdAndCompanyId` costs one primary-key read per write request. That matches today's one email read per call.

### Sessions and the principal shape

- `src/main/resources/application*.properties` and `pom.xml` contain no `spring-session` dependency and no `server.servlet.session.persistent` property. The grep matched only `server.servlet.session.cookie.secure=true` (`application-prod.properties:2`).
- Production: `infrastructure.md:98` records that "every deploy drops SSE streams and in-memory sessions". F3's "existing sessions need a re-login" warning (`follow-ups/review-fixes.md:11`) is therefore already true for every production deploy. This change adds no extra effect there.
- Local dev (inference, not verified): `spring-boot-devtools` is on the runtime classpath (`pom.xml`). Devtools may turn on session persistence across restarts. If a pre-change session is restored, `CompanyUser` deserializes with the same `serialVersionUID = 1L` and `accountId == null`. Bumping `serialVersionUID` would make a restored old session fail to load, which forces a fresh login and avoids the null.

## Code References

- `src/main/java/pl/regavio/stockahead/account/AccountRepository.java:14-15`: cross-company native `findByCanonicalEmail`
- `src/main/java/pl/regavio/stockahead/account/AccountRepository.java:29`: `findByIdAndCompanyId`
- `src/main/java/pl/regavio/stockahead/security/CompanyUser.java:14-27`: principal class, `serialVersionUID = 1L`
- `src/main/java/pl/regavio/stockahead/security/AccountUserDetailsService.java:27-33`: login lookup and the only principal construction
- `src/main/java/pl/regavio/stockahead/security/AccountActivityFilter.java:29-41`: per-request active check by email
- `src/main/java/pl/regavio/stockahead/security/SecurityConfig.java:35`: filter registration
- `src/main/java/pl/regavio/stockahead/orders/PickingController.java:142-144, 289-292`: picking list self id, completion reporter
- `src/main/java/pl/regavio/stockahead/orders/OrderController.java:388-390`: canceller
- `src/main/java/pl/regavio/stockahead/parts/StockCorrectionController.java:225, 237-238`: correction author (no `CompanyUser` yet)
- `src/main/resources/db/migration/V18__company_scoped_completion_reporter.sql:8`: composite FK on the reporter
- `src/test/java/pl/regavio/stockahead/security/AccountUserDetailsServiceTest.java:69-84`: `companyId()` assertion to copy
- `src/test/java/pl/regavio/stockahead/orders/PickingIntegrationTests.java:132-133`: real-login test helper pattern

## Architecture Insights

- The principal is the single source of tenant identity ("Controllers take the current company only from here", `CompanyUser.java:8-12`). Adding the account id there follows the same pattern and removes the last reason controllers re-read the account by email.
- The FORBIDDEN-on-missing fallback is the same at sites 1–4. With an id from the principal, a missing row only happens if the account was deleted or moved, and FR-002 forbids deleting accounts. Inference: plan whether to keep the fallback or let it become a 404.

## Historical Context (from prior changes)

- `context/changes/company-scoped-picking-completion/reviews/impl-review.md:48-65`, F3. Fix A (widen the AGENTS.md exception) was recommended, but the user chose Fix B and deferred it to its own change. Its call-site list covers 4 sites. **Supported**, apart from the missing `AccountActivityFilter` site. Its line numbers (~139, ~285) are **partial**: they have moved to 142 and 289 in the current working tree.
- Same review, F4 (:67-75): the single-column FKs on `cancelled_by` and `stock_corrections.account_id` are handed off to S-05. That affects the choice of lookup at sites 3 and 4 (above).
- The F3 claim that "email is globally unique (FR-029)" is not re-verified here. AGENTS.md § Company isolation states the same rule.

## Related Research

- Not applicable. No other `research.md` covers principal or account loading.

## Open Questions

1. **`AccountActivityFilter` scope**: should this change move it to an id-based lookup? If yes, it removes every non-login `findByCanonicalEmail` use, which is F3's stated goal. If not, AGENTS.md needs a second named exception.
2. **Stock correction**: should this change thread `@AuthenticationPrincipal CompanyUser` through `StockCorrectionController`'s 4 methods? Or should site 4 wait for S-05, which will scope that controller anyway?
3. **Missing-account behaviour**: keep `FORBIDDEN` when `findByIdAndCompanyId` finds no row, or change it?
4. **`serialVersionUID`**: bump it to make stale dev sessions fail to load? (The devtools session-persistence default is not verified here.)
5. **Test principals**: I did not open every one of the 32 auth-using test files to confirm none builds a principal with `user(...)`/`@WithMockUser`. A grep for `new CompanyUser` in `src/test` returned 0, so any such principal would not be a `CompanyUser` and would already fail `@AuthenticationPrincipal CompanyUser` handlers.
