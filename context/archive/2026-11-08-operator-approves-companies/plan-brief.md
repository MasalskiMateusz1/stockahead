# Operator Approves or Rejects Companies — Plan Brief

> Full plan: `context/changes/operator-approves-companies/plan.md`

## What & Why

Roadmap slice S-11 closes the lifecycle half of US-01. The operator approves a pending company so its manager reaches the warehouse, or rejects it. A rejected registration stays on record, its users fail login with the generic message, and its email is free to register again (FR-024, FR-026). Together with S-06 this makes the primary success criterion demonstrable end to end.

## Starting Point

Registration creates a PENDING company and its manager (S-09). The operator sees a read-only company list (S-08). `AccountActivityFilter` keeps users of any non-ACTIVE company on `/awaiting-approval`. Email is globally unique through both the V1 `accounts_email_key` and the V4 canonical index, so a kept rejected account would block its email forever.

## Desired End State

PENDING rows on `/operator/companies` offer "Zatwierdź" (direct) and "Odrzuć" (via a confirmation page). Approval lets the manager's same session reach the dashboard on its next request. Rejection marks the company REJECTED and deactivates and releases its accounts. Login and any open session then fail with "Nieprawidłowy e-mail lub hasło.", the list still shows the rejected row with its email, and the email can register a new company.

## Key Decisions Made

| Decision            | Choice                                                                                  | Why (1 sentence)                                                                                     |
| ------------------- | --------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------- |
| Freeing the email   | `accounts.email_released_at` + partial canonical unique index; drop `accounts_email_key` | The record keeps the real email and uniqueness stays DB-enforced for every live account.            |
| Rejected open session | Ended on next request → `/login?error` (generic message)                              | "Rejected users cannot log in" takes effect at once without any "rejected" wording.                    |
| Reject UX           | Inline buttons; approve posts directly, reject goes through a confirmation page          | Reject is irreversible; a server-rendered confirmation guards it with no JavaScript.                   |
| Stale click         | Row lock, act only if PENDING, otherwise no-op + "Status firmy zmienił się" flash        | Two tabs or approve-after-reject can never produce an inconsistent state, and the operator sees why. |
| Transitions         | Only PENDING → ACTIVE and PENDING → REJECTED, in one `CompanyLifecycle` service           | Single locked write path that S-12 block/unblock can extend.                                         |

## Scope

**In scope:**
- V21 migration (release column, partial unique index, CHECKs), login lookup, filter branch
- `CompanyLifecycle` service, three operator routes, reject confirmation page, list "Akcje" column, flash messages
- Tests: migration, filter, US-01 end to end, reject and re-register, stale clicks, 404/403/CSRF, approve-vs-reject concurrency
- AGENTS.md rules for released emails and lifecycle writes

**Out of scope:**
- Block and unblock (S-12), un-rejecting, reject reasons, audit table
- Email notifications, a special "rejected" login message, JS confirm dialogs

## Architecture / Approach

Phase 1 teaches the system what a "released" account is: the DB invariants (a released account is inactive, the operator is never released), login skipping released rows, and the filter ending released sessions first. Nothing sets the flag yet. Phase 2 adds `CompanyLifecycle.approve/reject`, which locks the company row with `findByIdForUpdate`, checks PENDING, and on reject releases the company's accounts in the same transaction. Operator routes use flash + PRG like `CompanyAccountController`.

## Phases at a Glance

| Phase                                     | What it delivers                                                 | Key risk                                                               |
| ----------------------------------------- | ---------------------------------------------------------------- | ---------------------------------------------------------------------- |
| 1. Email release and generic lockout      | Released accounts invisible to login and uniqueness; sessions end | Forgetting `accounts_email_key`, or letting the inactive check win over released |
| 2. Approve and reject from operator list  | Operator actions, confirm page, US-01 end to end                 | Approve/reject race leaving a half-rejected company                    |

**Prerequisites:** S-03, S-08, S-09 done (they are); Docker running for `./mvnw verify`.
**Estimated effort:** ~2 sessions, one per phase.

## Open Risks & Assumptions

- A pending company has only its registrant manager (it can't create accounts while gated). Reject still releases all of the company's accounts to be safe.
- Test-only `findByEmail` could match two rows once an email is re-registered. Main code never uses it.

## Success Criteria (Summary)

- A registered company is approved by the operator and its manager uses the warehouse in the same session.
- A rejected registrant can't log in (generic message), stays visible to the operator, and can register again with the same email.
- Concurrent or stale operator clicks never leave a company in a mixed state.
