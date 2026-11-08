# Operator Blocks and Unblocks Companies — Plan Brief

> Full plan: `context/changes/operator-blocks-companies/plan.md`

## What & Why

Roadmap slice S-12 (FR-027, the milestone's only nice-to-have). The operator blocks an active company so its users can no longer log in, and unblocks it so everyone gets back in with their data intact. Blocking must not be confused with deactivation: unblock restores the whole company, not just the manager.

## Starting Point

`BLOCKED` already exists in `CompanyStatus` and the V14 CHECK. `CompanyLifecycle` (S-11) is the single locked write path for status, but it only knows approve/reject. Today a blocked company's user **can still log in**, because login checks only the account. Its session is then held on `/awaiting-approval`, which wrongly says "awaiting approval".

## Desired End State

ACTIVE rows on `/operator/companies` offer "Zablokuj" (through a confirmation page), and BLOCKED rows offer "Odblokuj" (direct). A blocked user who types the correct password lands on `/login?blocked` with "Twoja firma została zablokowana przez operatora."; a wrong password still gets the generic message. An open session hits a blocked-wording gate page on its next request and works again after unblock. Account flags, emails and warehouse data are never touched.

## Key Decisions Made

| Decision               | Choice                                                                  | Why (1 sentence)                                                                                       |
| ---------------------- | ----------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------ |
| Blocked login message  | Specific "company blocked", shown only after the password matches        | Users learn why login fails, while a wrong password still says nothing about whether the email exists. |
| Mechanism              | `DaoAuthenticationProvider` post-auth check + exception-mapped failure URL | Post-checks run after the password check, unlike Spring's built-in locked/disabled pre-checks.        |
| Open session on block  | Gated on next request to the gate page (blocked wording), session kept   | Matches "blocked users cannot reach any warehouse feature"; immediate logout is parked in the roadmap. |
| Gate page              | Reuse `/awaiting-approval` with status-specific wording                  | Already gate-exempt; no new route or exempt path.                                                       |
| Block UX               | Confirmation page for block, direct POST for unblock                     | Guards the action that locks out a whole company; unblock is a harmless restore.                       |
| Transitions            | Only ACTIVE → BLOCKED and BLOCKED → ACTIVE, in `CompanyLifecycle`        | Same row lock + no-op on stale state as approve/reject; `NOT_PENDING` becomes `STATUS_CHANGED`.        |
| Accounts on block      | Untouched                                                                | Unblock restores everyone exactly as before; a deactivated technician stays deactivated.               |

## Scope

**In scope:**
- Post-password blocked check, `/login?blocked` message, blocked wording on the gate page
- `CompanyLifecycle.block/unblock`, three operator routes, block confirmation page, per-status list actions, flashes
- Tests: login matrix, block → unblock end to end, stale clicks, 404/403/anonymous/CSRF, concurrency
- AGENTS.md bullet on the blocked-login rule

**Out of scope:**
- Immediate session termination, keeping open sessions fully working
- Blocking PENDING/REJECTED companies, block reasons, audit history, email notifications
- Any migration, a separate `/company-blocked` route, JS confirm dialogs

## Architecture / Approach

Phase 1 adds a `companyBlocked` flag to `CompanyUser` at login. A post-authentication `UserDetailsChecker` throws `CompanyBlockedException`, and `ExceptionMappingAuthenticationFailureHandler` maps it to `/login?blocked`. `AwaitingApprovalController` passes the company status so the gate template can show blocked wording. The filter is unchanged, because it already gates every non-ACTIVE company and re-reads status per request. Phase 2 adds the two transitions on the existing `findByIdForUpdate` lock path and the operator routes on the S-11 PRG + flash pattern.

## Phases at a Glance

| Phase                                                        | What it delivers                                              | Key risk                                                                                       |
| ------------------------------------------------------------ | ------------------------------------------------------------- | ---------------------------------------------------------------------------------------------- |
| 1. Blocked company refuses login and shows its own gate wording | Blocked users refused at login with a specific message; gate wording | Putting the check before the password (email enumeration) or dropping the default post-check |
| 2. Block and unblock from the operator list                  | Operator actions, confirm page, FR-027 end to end             | Block touching account flags, so unblock fails to restore everyone                                |

**Prerequisites:** S-11 done (it is); Docker running for `./mvnw verify`.
**Estimated effort:** ~1–2 sessions, one per phase.

## Open Risks & Assumptions

- A login that starts just before a block may finish. The session is gated on its very next request, so this is harmless.
- `CompanyUser.serialVersionUID` is bumped. Sessions are in-memory, so a deploy logs everyone out anyway.

## Success Criteria (Summary)

- The operator blocks an active company; its users cannot log in and are told why only after typing the right password.
- An open session of a blocked company is held on the gate page and works again after unblock, with all data intact.
- Unblock restores every account exactly as it was; stale or concurrent clicks never leave a mixed state.
