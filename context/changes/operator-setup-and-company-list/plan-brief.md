# Operator Setup and Company List — Plan Brief

> Full plan: `context/changes/operator-setup-and-company-list/plan.md`

## What & Why

Roadmap slice S-08: the token-gated `/setup` creates the single platform operator, an account that belongs to no company, instead of the first manager. The operator logs in to a read-only list of companies. This gives the company lifecycle (registration S-09, approval S-11) someone to report to, while keeping the prd-v2 guardrail that the operator has no path to any company's warehouse data.

## Starting Point

Setup creates "company + first manager" under an advisory lock and closes once a manager exists. The schema requires a company on every account. Several layers assume every principal has one (the user-details service and the activity filter). Four warehouse GET routes have no role gate at all, so a non-warehouse role would see empty pages instead of a 403.

## Desired End State

On a fresh database, setup creates exactly one active OPERATOR with no company and then closes. The operator lands on `/operator/companies`, which lists each company's name, the email of its first-created manager, the registration date and the status, newest first. Every warehouse route answers the operator with 403, and managers and technicians get 403 on `/operator/**`.

## Key Decisions Made

| Decision | Choice | Why (1 sentence) |
| --- | --- | --- |
| Refusing the operator | URL deny-by-default (`/operator/**` → OPERATOR, everything else non-public → MANAGER/TECHNICIAN) plus one operator→403 test per warehouse controller | A future route that forgets `@PreAuthorize` still refuses the operator, and the tests prove it per controller. |
| "Manager's email" | First-created MANAGER (earliest `created_at`, then id), active or not | A stable single value that, after S-09, is the registrant the operator judges. |
| List order | Newest first (`created_at DESC, id DESC`) | Fresh registrations, the operator's main job, sit on top. |
| Setup validation | Same rules as the accounts page (email format and ≤255 characters, password ≥12), extracted into a shared `AccountCredentials` helper | The most powerful account is no longer the weakest, and S-09 reuses the helper. |
| Setup race (PRD Open Question 1) | The token alone is enough | Constant-time comparison of a secret token, an advisory lock and self-closing setup; resolved and recorded in the PRD. |
| Fresh-deploy gap | Accept it: no company can be created until S-09/S-11 | Matches FR-033 with no throwaway code, and the branch isn't merged before the milestone closes. |
| Single operator | DB partial unique index plus a CHECK that OPERATOR ⇔ `company_id IS NULL` | The database enforces the invariants, not only the form (lessons.md). |

## Scope

**In scope:**
- V20 migration and `Role.OPERATOR`
- Operator-aware principal, activity filter and URL rules
- Setup creates the operator, with shared validation
- The `/operator/companies` list and `/` routing by role
- A role-aware topbar
- Guardrail tests
- AGENTS.md, PRD and roadmap notes

**Out of scope:**
- Registration, approve/reject and block/unblock (S-09, S-11, S-12)
- Operator data access or impersonation
- A second operator, or operator password and deactivation management
- A dev seeder
- Pagination or search

## Architecture / Approach

The operator is an `accounts` row with role OPERATOR and a NULL `company_id`. The DB guarantees there is only one and that it has no company. Its principal carries only `ROLE_OPERATOR`. `SecurityConfig` becomes the structural barrier: `/`, `/error` and the public pages aside, `/operator/**` requires OPERATOR and everything else requires a warehouse role. `@PreAuthorize` stays the fine-grained layer. The company list is one cross-company query returning name, first-created manager email, `created_at` and status.

## Phases at a Glance

| Phase | What it delivers | Key risk |
| --- | --- | --- |
| 1. Operator role in schema and auth | V20 and the OPERATOR principal; the filter keeps operator sessions alive; URL deny-by-default | Forgetting `/error` or `/` in the URL rules, so the operator gets double-403s or can't land anywhere. |
| 2. Setup creates the operator | Setup creates the operator, validated by the shared helper; rewritten setup tests | The validation extraction changes `CompanyAccountController` behaviour or messages. |
| 3. Company list and guardrail tests | `/operator/companies`, `/` routing, role-aware topbar, operator→403 per controller, docs | Topbar edits break markup that existing tests match exactly. |

**Prerequisites:** F-01 (company-boundary-harness) is done. Docker is running for `./mvnw verify`.
**Estimated effort:** about 2–3 sessions across 3 phases.

## Open Risks & Assumptions

- Until S-09/S-11 land, manual warehouse testing on a fresh database needs an SQL-inserted company and manager.
- On a database where the old setup already created a manager, `/setup` reopens until an operator is created. This is intended.
- Under deny-by-default, any future public page (e.g. S-09 `/register`) must be added to the `permitAll` list explicitly.

## Success Criteria (Summary)

- The owner completes `/setup` once and logs in as the operator to a company list showing the right manager email and status.
- The operator cannot reach any warehouse page or action (403 everywhere), and managers and technicians cannot reach the operator page.
- Managers and technicians see no change in their own screens.
