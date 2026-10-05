# Company Boundary and Cross-Company Test Pattern — Plan Brief

> Full plan: `context/changes/company-boundary-harness/plan.md`
> Frame brief: `context/changes/multi-company-support/frame.md`

## What & Why

Roadmap M-2 F-01. Stockahead becomes multi-tenant (prd-v2). Before any warehouse table can be scoped, a company has to exist, every account has to belong to one, and the logged-in user has to carry its company. Every later slice also needs one shared way to test that "company B's user gets 404 for company A's record id". From the frame: *Stockahead is built as a single-plant tool … it must become a multi-tenant app where any company can register on one website and only ever see its own data.*

## Starting Point

There is no company concept anywhere. `accounts` has a one-manager-per-database index (V2), the principal is a plain Spring `User`, `/setup` creates the single manager, and 28 test classes each hand-roll their own `new Account()` fixtures.

## Desired End State

- A `companies` table (name, status, created) exists, every account has a non-null company, and several managers can exist.
- The principal (`CompanyUser`) exposes `companyId`.
- `/setup` asks for a company name and creates company + manager together.
- The technician routes are company-scoped and answer 404 across companies.
- All tests build accounts through one `CompanyFixtures` bean that later slices reuse.

## Key Decisions Made

| Decision | Choice | Why (1 sentence) | Source |
| --- | --- | --- | --- |
| Tenancy model | Shared schema, `company_id`, company taken from the principal | Usual convention for a hard partition in one app and DB, and what the user asked for | Frame |
| `companies` columns | id, name, status (PENDING/ACTIVE/REJECTED/BLOCKED, default ACTIVE), created_at | The prd-v2 lifecycle lands once, so S-09/S-11/S-12 don't each have to change the table | Plan |
| Existing prod data | V14 refuses to run on a non-empty `accounts`; wipe by hand before deploy | No placeholder data and no destructive SQL inside a migration; prd-v2 allows the wipe | Plan |
| Setup until S-08 | Form asks for the company name; company + manager in one transaction | Same shape as FR-023 registration, so S-09 can reuse it | Plan |
| Demonstration route | Technician list/create/deactivate/reactivate scoped by company | A real route on a table F-01 already scopes, and it closes a real leak | Plan |
| Test fixture | One `@TestComponent CompanyFixtures`, all 28 tests migrated | One place for the A/B setup that every Stream A slice copies | Plan |
| Operator / nullable company | `company_id NOT NULL` now; S-08 relaxes it | The operator role doesn't exist yet, so the stricter constraint holds for now | Plan |

## Scope

**In scope:**
- V14 migration with a guard
- `Company` entity and repository
- `Account.company`
- `CompanyUser` principal
- `/setup` company name
- Company-scoped technician routes
- `CompanyFixtures` and the migration of every test to it
- `V14MigrationTests`
- AGENTS.md and deployment.md notes

**Out of scope:**
- Company scoping of any warehouse table (S-01…S-06)
- Operator role (S-08), registration and approval (S-09/S-11), block/unblock (S-12)
- Manager-account management and the last-manager rule (S-07)
- `findAssignable` scoping (S-03)
- Showing the company name in the UI
- Company-name uniqueness

## Architecture / Approach

The company comes from the database, then the principal, then the controller. `AccountUserDetailsService` returns a `CompanyUser` with the account's company id, and controllers read it only through `@AuthenticationPrincipal`. Lookups go by `(id, company_id)`, and a miss is a 404 exactly like a missing id. Phase 1 has to migrate all the tests, setup and technician create together with the schema, because the NOT NULL column breaks every account-creating path at once.

## Phases at a Glance

| Phase | What it delivers | Key risk |
| --- | --- | --- |
| 1. Schema, principal, setup, shared fixture | V14, `Company`, `Account.company`, `CompanyUser.companyId`, setup creates company + manager, technician create sets company, `CompanyFixtures`, all tests green | Wide diff across about 30 test files plus login/setup; cleanup order (accounts before companies) |
| 2. Two-company pattern | Company-scoped technician list/deactivate/reactivate, reference A/B 404 tests, docs | A route left on a bare `findById`, which the grep criterion catches |

**Prerequisites:** commit the M-2 roadmap draft (it's uncommitted in the working tree); Docker running for `./mvnw verify`.
**Estimated effort:** about 2–3 sessions across 2 phases.

## Open Risks & Assumptions

- V14 is not expand/contract, which deviates from deployment.md S5. The production deploy needs a backup and a manual wipe first, or CI rolls it back.
- `findAssignable` stays global until S-03. This is safe only because production has a single company until S-09 ships.
- The `status` column is unused until S-09. Whoever writes S-09 has to make login respect it.

## Success Criteria (Summary)

- `./mvnw verify` is green on the V14 schema, with every test using `CompanyFixtures`.
- A fresh instance's `/setup` creates a named company and its manager, who logs in normally.
- Company B's manager cannot see or change company A's technicians: 404, and the data is unchanged.
