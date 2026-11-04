# Company Accounts — Plan Brief

> Full plan: `context/changes/company-accounts/plan.md`

## What & Why

Roadmap M-2 S-07 (FR-030, FR-032): a manager manages technician **and** manager accounts, only within their own company. A company must never lock itself out, so the last active manager can't be deactivated or demoted, even when two managers act at the same moment.

## Starting Point

`TechnicianAccountController` (`/manager/technicians`) already creates, deactivates and reactivates technicians, scoped by company (404 for another company's id). It ignores managers entirely. The schema has allowed several managers per company since V14, but nothing creates them. `AccountActivityFilter` logs out deactivated accounts and doesn't check roles.

## Desired End State

One **Konta** page (`/manager/accounts`) lists every account of the company with its role and status. A manager creates technicians or managers, deactivates and reactivates any of them (including themselves), and promotes or demotes between the two roles. Any action that would leave zero active managers is refused with an inline error. A changed-role account is sent back to login on its next request.

## Key Decisions Made

| Decision | Choice | Why (1 sentence) |
| --- | --- | --- |
| UI shape | One `/manager/accounts` page for both roles (renames `/manager/technicians`) | One controller, list and validation path, with the rule next to the toggle it guards. |
| Self-deactivation | Allowed unless the caller is the last active manager | One rule with no special case, matching FR-032 literally. |
| Action scope | Create, deactivate, reactivate **and** role change (promote/demote) | Covers promotion without a second email; demotion shares the same guard. |
| Concurrency guard | `@Lock(PESSIMISTIC_WRITE)` on the company row (Postgres: `FOR NO KEY UPDATE`) before reading the target and counting other active managers | Per-company mutex with no key collisions; S-11/S-12 will lock the same row. |
| Stale authorities after role change | The filter ends the session on a role mismatch (`/login?roleChanged`) | Reuses the deactivation path; safer than keeping stale manager rights. |
| Verification | Real concurrency tests (mutual deactivate, demote vs deactivate, cross-company independence) | Only a parallel test proves the lock works; a missing lock would pass sequential tests. |
| Inactive managers | Don't count toward "at least one active manager" | The rule is about who can still log in and manage the company. |

## Scope

**In scope:** rename to the accounts page, role select on create, manager deactivate/reactivate, role change, last-active-manager rule under the company lock, role-change session invalidation, two-company 404 and technician 403 tests, concurrency tests, AGENTS.md pointer update.

**Out of scope:** account deletion, setup/operator/registration (S-08, S-09), an anti-enumeration duplicate-email message (S-09's open question), password reset or email change, schema migration, redirects from old URLs.

## Architecture / Approach

The existing controller is generalised into `CompanyAccountController`. Manager-removing transitions (deactivating or demoting an active manager) run one transaction: lock the company row → `findByIdAndCompanyId` → count *other* active managers → refuse with a flash error or apply. Manager-adding transitions (create, reactivate, promote) need no guard. `AccountActivityFilter` reuses its existing per-request account lookup to also compare roles.

## Phases at a Glance

| Phase | What it delivers | Key risk |
| --- | --- | --- |
| 1. Company accounts page with managers | Rename, manager creation, guarded deactivation, concurrency test | Rename leaves stale references (routes, message keys, AGENTS.md); the grep criterion catches it |
| 2. Role change | Promote/demote under the same guard; changed-role sessions end | Demote and deactivate taking different code paths and bypassing the shared guard |

**Prerequisites:** F-01 done (company on accounts and principal, `CompanyFixtures`).
**Estimated effort:** ~2 sessions across 2 phases.

## Open Risks & Assumptions

- Assumes the old `/manager/technicians` URLs have no external bookmarks worth redirecting.
- The duplicate-email error still reveals that an email exists elsewhere on the platform. That's accepted here for an authenticated manager; S-09 decides the public-form posture.

## Success Criteria (Summary)

- A manager creates a second manager who can log in and manage the company; neither company ever sees the other's accounts.
- No sequence of deactivations or demotions, concurrent or not, leaves a company with zero active managers.
- A deactivated or changed-role account loses access on its next request.
