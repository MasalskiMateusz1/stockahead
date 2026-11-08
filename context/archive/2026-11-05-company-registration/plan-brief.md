# Company Registration — Plan Brief

> Full plan: `context/changes/company-registration/plan.md`
> Frame brief: `context/changes/per-company-registration-token/frame.md`

## What & Why

Roadmap slice S-09: a public `/register` lets a visitor create a company and become its manager in one step. The company starts as pending, and its users see only "awaiting approval" until the operator approves it. The frame's reframed problem: *on this branch there is no in-app way to create a company and its manager until S-09 (public registration) lands; the manager→company link itself is already solved by the planned design and needs no token.*

## Starting Point

The schema already allows PENDING companies and non-unique company names, so no migration is needed. `AccountCredentials` and the `/setup` form provide the validation and constraint-catch pattern. URL rules deny by default, but a pending company's manager still holds `ROLE_MANAGER`, so today nothing would keep them out of the warehouse.

## Desired End State

A visitor registers from a link on the login page, then logs in and lands on a standalone "awaiting approval" page. Every warehouse URL, GET or POST, sends them back there and changes nothing. When the company becomes ACTIVE (S-11, or by SQL for now), the same session reaches the dashboard on its next request. The operator's list shows the new company as "Oczekuje" with the registrant's email.

## Key Decisions Made

| Decision | Choice | Why (1 sentence) | Source |
| --- | --- | --- | --- |
| Per-company token | Not built; the founder creates the company and becomes its manager (FR-023) | Nobody needs to join an existing company from outside. | Frame |
| Public company exposure | No list, picker or company name on any public page; duplicate names allowed | Nothing reveals which companies are registered. | Frame |
| Duplicate email on `/register` | Explicit "Konto o tym adresie e-mail już istnieje." | With no email features, a neutral message would leave honest users stuck; the same leak already exists on the technician form, and S-10 limits bulk probing. | Plan |
| Pending gate | `AccountActivityFilter` checks company status per request and redirects non-ACTIVE users to `/awaiting-approval` | Approval takes effect on the next request with no re-login, and S-12 can reuse the check. | Plan |
| Gate condition | `status != ACTIVE` (fail-closed) | REJECTED/BLOCKED users stay out of the warehouse until S-11/S-12 define their login behaviour. | Plan |
| After registration | Redirect to `/login?registered`; no auto-login | Matches the US-01 flow and the setup pattern, with no programmatic-login code on a public endpoint. | Plan |
| Before setup | `/register` is always open | Pending companies simply wait for the operator; no coupling to setup. | Plan |
| Validation | Company name trimmed, required, ≤255; credentials via `AccountCredentials` | Same rules as every account form (answers the S-09 roadmap unknown). | Plan |
| Awaiting page layout | Standalone card with logout, no warehouse topbar | A pending manager holds `ROLE_MANAGER` and would otherwise see warehouse links. | Plan |

## Scope

**In scope:**
- Company-status gate in the filter, with a fetch-join account lookup
- The `/awaiting-approval` page
- Public `/register` with an atomic company + manager write
- Login link and success message
- Per-controller gate tests and registration tests
- AGENTS.md and roadmap notes

**Out of scope:**
- Bot protection (S-10)
- Approve, reject and block, and REJECTED/BLOCKED login messages (S-11/S-12)
- Auto-login
- Neutral duplicate response
- Email features
- Unique company names
- A migration
- A dev seeder

## Architecture / Approach

`RegistrationController` validates and then, in one transaction, inserts `companies(PENDING)` and `accounts(MANAGER, company)`. A unique-email violation rolls both back and re-renders the form. On every authenticated request, `AccountActivityFilter` loads the account together with its company. It handles deactivated accounts first, then role changes, then redirects users of a non-ACTIVE company to `/awaiting-approval`. The awaiting page, `/error` and static resources are exempt. Deny-by-default URL rules and `@PreAuthorize` stay as the layers behind it.

## Phases at a Glance

| Phase | What it delivers | Key risk |
| --- | --- | --- |
| 1. Pending-company gate | Filter gate, `/awaiting-approval`, one redirect test per warehouse controller | A missing exemption (static, error) causes a redirect loop or an unstyled page. |
| 2. Public company registration | `/register`, login link, registration tests, docs | A constraint violation leaves an orphan company, or the form leaks existing companies. |

**Prerequisites:** F-01 done; S-08 code on `multi-company-support` (operator list, deny-by-default). Docker is running for `./mvnw verify`.
**Estimated effort:** about 2 sessions across 2 phases.

## Open Risks & Assumptions

- Until S-11 lands, approval during manual testing is an SQL `UPDATE companies SET status = 'ACTIVE'`. That is simpler than today's SQL insert of a company and manager.
- The explicit duplicate-email error lets an anonymous visitor check whether an address is registered. This was accepted, and S-10 is the mitigation for bulk probing.
- `/register` is the first public write endpoint and has no bot protection until S-10. That is acceptable because the branch is not deployed publicly before the milestone closes.

## Success Criteria (Summary)

- A visitor registers, logs in and sees only "awaiting approval"; no warehouse URL works for them.
- Flipping the company to ACTIVE gives the same session full warehouse access on the next request.
- No public page reveals existing companies, and a duplicate email never leaves a half-created company.
