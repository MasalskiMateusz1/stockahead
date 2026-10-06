---
project: "Stockahead"
version: 2
status: draft
created: 2026-10-05
updated: 2026-10-05
prd_version: 2
main_goal: quality
top_blocker: capacity
milestone_id: multi-company-isolation
milestone_seq: 2
milestone_status: open
---

# Roadmap: Stockahead

> Derived from `context/foundation/prd-v2.md` (v2) + codebase baseline (2026-10-05) + frame brief `context/changes/multi-company-support/frame.md`.
> Edit-in-place; archive when superseded.
> Slices below are listed in dependency order. The "At a glance" table is the index.

## Milestone

**M-2: Many companies share one instance, each sees only its own warehouse** — Status: open

- **Intent:** Companies register themselves on one website, the platform operator approves them, and each company runs the full existing Stockahead flow without ever seeing another company's parts, orders or people.
- **Source materials:** `context/foundation/prd-v2.md` (v2)
- **Done when:** every F-NN and S-NN below is `done` — all nine must-have FRs of prd-v2 delivered, plus the nice-to-have block/unblock (FR-027).
- **Scope anchors:** FR-023, FR-024, FR-025, FR-026, FR-027, FR-028, FR-029, FR-030, FR-032, FR-033, US-01; Success Criteria › Guardrails; Business Logic Changes; Access Control Changes.

## Vision recap

Stockahead today serves exactly one plant: one manager, globally unique names, no public sign-up. This milestone turns it into one running instance on one website where many small plants register themselves and each sees only its own data. It is a learning change (multi-tenant isolation and self-service sign-up), not a customer request — but the isolation guardrail is absolute: a user of company A must never see or change company B's data, not even by guessing a record id in the URL.

## North star

**S-01: Two companies each see and manage only their own parts catalog** — the first slice where a company boundary on warehouse data is exercised end to end, including the "guessed id → 404" guardrail; under the `quality` goal this is the riskiest promise of the PRD, so it is proven before any new public screen exists.

> The north star here means the smallest end-to-end slice whose successful delivery proves the change works at all — placed as early as its prerequisites allow, because the rest of the roadmap only matters if this holds.

## At a glance

| ID   | Change ID                         | Outcome (user can …)                                                                                   | Prerequisites | PRD refs                                   | Status   |
| ---- | --------------------------------- | ------------------------------------------------------------------------------------------------------ | ------------- | ------------------------------------------ | -------- |
| F-01 | company-boundary-harness          | (foundation) every account belongs to a company, the logged-in user carries it, two-company test pattern exists | —             | FR-028, FR-029, Success Criteria › Guardrails, Access Control Changes (company isolation) | in-progress |
| S-01 | company-scoped-parts              | Manager of each company sees, searches and edits only their company's parts and locations              | F-01          | US-01, FR-028, Success Criteria › Guardrails | proposed |
| S-02 | company-scoped-projects           | Manager keeps projects, BOMs and links visible only within their company                               | S-01          | US-01, FR-028 | proposed |
| S-03 | company-scoped-orders             | Manager orders production; reservations draw only on their company's parts and orders                 | S-02          | US-01, FR-028, Business Logic Changes (modified rule), Success Criteria › Guardrails (stock/reservation guarantees per company) | proposed |
| S-04 | company-scoped-picking-completion | Technician picks and reports completion only for their company's orders                                | S-03          | US-01, FR-028, Success Criteria › Guardrails | proposed |
| S-05 | company-scoped-stock-movements    | Users receive deliveries, correct stock and import CSV only into their company's catalog               | S-01, S-03    | FR-028, Business Logic Changes, Constraints (CSV formats unchanged) | proposed |
| S-06 | company-scoped-shopping-list      | Manager sees and exports a shopping list built only from their company's shortages                     | S-03          | US-01, FR-028, Constraints (CSV formats unchanged) | proposed |
| S-07 | company-accounts                  | Manager creates and deactivates technicians and other managers only in their own company               | F-01          | FR-030, FR-032, FR-029, FR-028 | proposed |
| S-08 | operator-setup-and-company-list   | Setup creates the operator, who sees the list of companies and nothing else                            | F-01          | FR-033, FR-025, FR-029, Success Criteria › Guardrails (operator has no path to company data) | proposed |
| S-09 | company-registration              | Visitor registers a company and, after login, sees only "awaiting approval"                            | F-01          | US-01, FR-023, FR-024, FR-029, Business Logic Changes (company lifecycle) | proposed |
| S-10 | registration-bot-protection       | Automated sign-ups are rejected before they reach the pending list; humans pass without friction       | S-09          | FR-023 (human-verification resolution), NFR "Automated mass registration is rejected…" | proposed |
| S-11 | operator-approves-companies       | Operator approves or rejects a pending company; an approved manager reaches the warehouse              | S-03, S-08, S-09 | US-01, FR-026, FR-024, FR-025, Business Logic Changes (company lifecycle) | proposed |
| S-12 | operator-blocks-companies         | Operator blocks and unblocks a company; blocked users cannot log in, data stays                        | S-11          | FR-027, Success Criteria › Secondary | proposed |

## Streams

Navigation aid — groups items that share a Prerequisites chain. Canonical ordering still lives in the dependency graph below; this table is the proposed reading order across parallel tracks.

| Stream | Theme                        | Chain                                                              | Note                                                                                               |
| ------ | ---------------------------- | ------------------------------------------------------------------ | -------------------------------------------------------------------------------------------------- |
| A      | Warehouse data isolation     | `F-01` → `S-01` → `S-02` → `S-03` → `S-04`, `S-05`, `S-06`         | Carries the `quality` guardrail; every slice adds the company-B→404 test for its routes.            |
| B      | Company lifecycle            | `S-08`, `S-09` → `S-10`, `S-11` → `S-12`                           | Starts after `F-01` in Stream A; runs in parallel with A — the main lever against `capacity`.     |
| C      | Accounts within a company    | `S-07`                                                             | Starts after `F-01` in Stream A; independent of both other streams.                                 |

## Baseline

What's already in place in the codebase as of `2026-10-05` (code checked directly + user-confirmed; isolation surface detailed in `context/changes/multi-company-support/frame.md`).
Foundations below assume these are present and do NOT re-scaffold them.

- **Frontend:** present — server-rendered views in 23 templates on a shared head and stylesheet (`templates/fragments.html`, `static/css/app.css`).
- **Backend / API:** present — 13 controllers across `account`, `parts`, `projects`, `orders`, `purchasing`; no company concept anywhere.
- **Data:** present, single-company — Flyway V1–V13, 9 tables with no company column; global uniqueness on part name, project name and location spelling; one-manager-per-database index (V2); global advisory locks in `PartRepository`/`LocationSpellings`.
- **Auth:** partial for this milestone — email + password login, roles MANAGER/TECHNICIAN only (`account/Role.java`), deactivated accounts blocked by `security/AccountActivityFilter.java`, token-gated `/setup` creates the manager; no operator role, no company on the logged-in user, no public registration.
- **Deploy / infra:** present — `Dockerfile`, `.github/workflows/ci.yml` (verify → image → Coolify with rollback), runbook `context/foundation/deployment.md`.
- **Observability:** partial — actuator health probes only; nothing this milestone requires beyond that.
- **Bot protection:** absent — no human-verification mechanism and no mail sending.

## Foundations

### F-01: Company boundary and the cross-company test pattern

- **Outcome:** (foundation) a company record exists, every account belongs to one company, the logged-in user carries its company id, the one-manager-per-database rule becomes one-or-more-managers-per-company, and the repository has a two-company test pattern asserting that company B's user gets 404 for company A's record id.
- **Change ID:** company-boundary-harness
- **PRD refs:** FR-028, FR-029, Success Criteria › Guardrails, Access Control Changes (company isolation)
- **Unlocks:** `S-01` (first warehouse table to scope), `S-07` (accounts per company), `S-08` and `S-09` (companies must exist before the operator lists them or a visitor creates one); the verification path "every company-scoped route ships with a company-B→404 test" required by every Stream A slice.
- **Prerequisites:** —
- **Parallel with:** —
- **Blockers:** —
- **Unknowns:** —
- **Risk:** Without the company on the logged-in user, no slice can scope anything, and without a shared two-company fixture each slice would invent its own isolation test. The opposite trap is turning this into "add a company column to all nine tables" — the scope stops at accounts and the principal; each Stream A slice scopes its own tables in its own migration. Existing data may be wiped (prd-v2 § Constraints), so no backfill is needed; until S-08/S-09 land, the existing setup keeps working by placing its manager in a company.
- **Status:** in-progress

## Slices

### S-01: Two companies each see only their own parts catalog ★

- **Outcome:** Manager of company A adds, edits and searches parts and their locations; manager of company B sees none of them, gets 404 on A's part ids, and may reuse the same part names and shelf spellings.
- **Change ID:** company-scoped-parts
- **PRD refs:** US-01, FR-028, Success Criteria › Guardrails
- **Prerequisites:** F-01
- **Parallel with:** S-07, S-08, S-09, S-10
- **Blockers:** —
- **Unknowns:**
  - Should company scoping be enforced by one central mechanism or by an explicit company parameter on every query — given that native queries and row locks are not filtered automatically? — Owner: user. Block: no.
- **Risk:** The north star and the first real test of the guardrail. The parts catalog carries the trickiest global pieces: name and location-spelling uniqueness, the advisory locks keyed by name, and the rewrite of location spellings across all parts — any of them left global leaks data or blocks one company on another's lock. Every route ships with the company-B→404 test next to the existing wrong-role 403 test.
- **Status:** proposed

### S-02: Projects, BOMs and links stay within a company

- **Outcome:** Manager creates, edits and deletes projects with their BOM and documentation links; another company sees none of them, gets 404 on their ids, may reuse project names, and a BOM line can reference only the manager's own company's parts.
- **Change ID:** company-scoped-projects
- **PRD refs:** US-01, FR-028
- **Prerequisites:** S-01
- **Parallel with:** S-07, S-08, S-09, S-10
- **Blockers:** —
- **Unknowns:** —
- **Risk:** Sequenced after S-01 because a BOM line points at a part — the cross-company reference (company B's BOM naming company A's part id in a form post) is the new hole here, not just the lookups.
- **Status:** proposed

### S-03: Orders reserve only their own company's parts

- **Outcome:** Manager orders production of N units of a project, changes or cancels the order; reservations are allocated by priority → deadline → age across only that company's open orders and parts, and another company sees none of these orders.
- **Change ID:** company-scoped-orders
- **PRD refs:** US-01, FR-028, Business Logic Changes (modified rule), Success Criteria › Guardrails (stock/reservation guarantees per company)
- **Prerequisites:** S-02
- **Parallel with:** S-07, S-08, S-09, S-10
- **Blockers:** —
- **Unknowns:** —
- **Risk:** The allocation rule is the core of the product; the frame found it already separates by part, but its row locks and allocation queries read all open orders, so they must be scoped explicitly — companies must never compete for stock or wait on each other's locks. Existing concurrency tests have to pass unchanged per company.
- **Status:** proposed

### S-04: Technicians pick and complete only their company's orders

- **Outcome:** Technician sees the picking list, picks parts and reports completion (full or partial) only for their company's orders; an order can be assigned only to a technician of the same company; the manager confirms completion only within the company.
- **Change ID:** company-scoped-picking-completion
- **PRD refs:** US-01, FR-028, Success Criteria › Guardrails
- **Prerequisites:** S-03
- **Parallel with:** S-05, S-06, S-07, S-08, S-09, S-10, S-11, S-12
- **Blockers:** —
- **Unknowns:** —
- **Risk:** Picking is the technician-facing half of the order lifecycle, split from S-03 so each slice has one primary user. The assignee field (V13) is a cross-company reference that form validation alone won't catch.
- **Status:** proposed

### S-05: Deliveries, corrections and CSV import touch only one company's stock

- **Outcome:** Technician receives a delivery, manager corrects stock or imports parts from CSV — each affects only the user's company's parts, and reservations recompute only for that company's orders.
- **Change ID:** company-scoped-stock-movements
- **PRD refs:** FR-028, Business Logic Changes, Constraints (CSV formats unchanged)
- **Prerequisites:** S-01, S-03
- **Parallel with:** S-04, S-06, S-07, S-08, S-09, S-10, S-11, S-12
- **Blockers:** —
- **Unknowns:** —
- **Risk:** CSV import matches parts by name, so with names now unique only per company an unscoped match would add stock to another company's part. Columns of the import file must not change (prd-v2 § Constraints).
- **Status:** proposed

### S-06: Shopping list per company

- **Outcome:** Manager sees and exports to CSV a shopping list aggregating only their company's shortages and blocked orders.
- **Change ID:** company-scoped-shopping-list
- **PRD refs:** US-01, FR-028, Constraints (CSV formats unchanged)
- **Prerequisites:** S-03
- **Parallel with:** S-04, S-05, S-07, S-08, S-09, S-10, S-11, S-12
- **Blockers:** —
- **Unknowns:** —
- **Risk:** The shortage query aggregates across all open order lines today — the easiest leak in the system because it's a read-only page nobody suspects. Last item of US-01's flow.
- **Status:** proposed

### S-07: Accounts managed within one company

- **Outcome:** Manager creates and deactivates technician and manager accounts only in their own company; the last active manager cannot be deactivated; an email stays unique across the whole platform.
- **Change ID:** company-accounts
- **PRD refs:** FR-030, FR-032, FR-029, FR-028
- **Prerequisites:** F-01
- **Parallel with:** S-01, S-02, S-03, S-04, S-05, S-06, S-08, S-09, S-10, S-11, S-12
- **Blockers:** —
- **Unknowns:** —
- **Risk:** "Last active manager" is a "only one allowed"-style invariant: two managers deactivating each other at once can both pass a form check, so it needs the same lock-or-constraint discipline as stock. Deactivate, never delete, still holds.
- **Status:** proposed

### S-08: Setup creates the operator, who sees the company list

- **Outcome:** On a fresh deploy the token-gated setup creates the operator account (member of no company) and then closes; the operator logs in and sees the list of companies (name, manager's email, registration date, status) and has no route to any warehouse page.
- **Change ID:** operator-setup-and-company-list
- **PRD refs:** FR-033, FR-025, FR-029, Success Criteria › Guardrails (operator has no path to company data)
- **Prerequisites:** F-01
- **Parallel with:** S-01, S-02, S-03, S-04, S-05, S-06, S-07, S-09, S-10
- **Blockers:** —
- **Unknowns:**
  - Is the setup token alone enough protection against the fresh-deploy race (PRD Open Question 1)? — Owner: user. Block: no (today's token posture carries over until decided).
  - Should setup adopt the technician form's validation (12-character password minimum, email format and length)? Today `/setup` checks neither, so the first manager may have a weaker password than a technician (found in company-boundary-harness impl review F5). — Owner: user. Block: no.
- **Risk:** A third role that is *not* a superset of manager breaks the current "manager ⊇ technician" assumption — every warehouse route must refuse the operator, which means a 403 test per controller, not one global check.
- **Status:** proposed

### S-09: A visitor registers a company and waits for approval

- **Outcome:** Visitor fills in company name, email and password on the public website; the company is created as pending with them as its manager; logging in shows only "awaiting approval" and no warehouse feature.
- **Change ID:** company-registration
- **PRD refs:** US-01, FR-023, FR-024, FR-029, Business Logic Changes (company lifecycle)
- **Prerequisites:** F-01
- **Parallel with:** S-01, S-02, S-03, S-04, S-05, S-06, S-07, S-08
- **Blockers:** —
- **Unknowns:**
  - Email is globally unique (FR-029), so a "this email already exists" error tells an anonymous visitor that an address is registered in some company (account enumeration). Accept, or answer registration with a neutral message? Same applies to the technician form across companies today (found in company-boundary-harness impl review F5). — Owner: user. Block: no.
  - Should registration reuse the technician form's validation (12-character password minimum, email format and length), which `/setup` lacks today? — Owner: user. Block: no.
- **Risk:** The first public, unauthenticated write endpoint in the system. The pending gate must hold on every warehouse route, not only on the landing page; a duplicate email race lands on the global unique constraint and must re-render the form (lessons.md: handle constraint violations at write boundaries). Bot protection is split into S-10 so the flow itself can be verified first.
- **Status:** proposed

### S-10: Registration rejects automated sign-ups

- **Outcome:** Mass automated registrations are refused before they reach the operator's pending list, while a human completes sign-up without noticeable friction.
- **Change ID:** registration-bot-protection
- **PRD refs:** FR-023 (human-verification resolution), NFR "Automated mass registration is rejected…"
- **Prerequisites:** S-09
- **Parallel with:** S-01, S-02, S-03, S-04, S-05, S-06, S-07, S-08, S-11, S-12
- **Blockers:** —
- **Unknowns:**
  - Which human-verification mechanism fits the instance's hosting and the "no noticeable friction" bar? PRD says "mechanism decided downstream". — Owner: user. Block: no.
- **Risk:** May introduce the milestone's only external dependency; kept as its own slice so a vendor or key problem never blocks the registration flow or isolation work.
- **Status:** proposed

### S-11: Operator approves or rejects a registration

- **Outcome:** Operator approves a pending company, after which its manager reaches the full warehouse; or rejects it, after which login fails with a generic message, the registration stays on record, and its email can register again.
- **Change ID:** operator-approves-companies
- **PRD refs:** US-01, FR-026, FR-024, FR-025, Business Logic Changes (company lifecycle)
- **Prerequisites:** S-03, S-08, S-09 (S-03 is a release gate: until it scopes the order assignee lookup and picker by company, a second approved company could assign orders to — and see — another company's accounts; found in company-boundary-harness impl review F1)
- **Parallel with:** S-04, S-05, S-06, S-07, S-10
- **Blockers:** —
- **Unknowns:**
  - How does a kept rejected registration coexist with platform-wide email uniqueness (FR-029) while freeing its email for a new registration (FR-026)? — Owner: user. Block: no.
- **Risk:** Closes US-01's lifecycle half; together with S-06 it makes the primary success criterion demonstrable end to end. Status changes are state transitions — approving an already-rejected company, or two operator clicks at once, must not produce an inconsistent state.
- **Status:** proposed

### S-12: Operator blocks and unblocks a company

- **Outcome:** Operator blocks an active company so its users cannot log in (open sessions may finish) and unblocks it with all data intact.
- **Change ID:** operator-blocks-companies
- **PRD refs:** FR-027, Success Criteria › Secondary
- **Prerequisites:** S-11
- **Parallel with:** S-01, S-02, S-03, S-04, S-05, S-06, S-07, S-10
- **Blockers:** —
- **Unknowns:** —
- **Risk:** The only nice-to-have in the milestone, sequenced last; the existing deactivated-account check is the natural extension point, but a block must not be confused with deactivation (unblock restores everyone, not just the manager).
- **Status:** proposed

## Backlog Handoff

| Roadmap ID | Change ID                         | Suggested issue title                                         | Ready for `/10x-plan` | Notes                                           |
| ---------- | --------------------------------- | ------------------------------------------------------------- | --------------------- | ----------------------------------------------- |
| F-01       | company-boundary-harness          | Company on accounts and principal + cross-company test pattern | yes                   | Run `/10x-plan company-boundary-harness`        |
| S-01       | company-scoped-parts              | Parts catalog isolated per company                             | no                    | North star; waits on F-01                       |
| S-02       | company-scoped-projects           | Projects, BOMs and links isolated per company                  | no                    | Waits on S-01                                   |
| S-03       | company-scoped-orders             | Orders and allocation isolated per company                     | no                    | Waits on S-02                                   |
| S-04       | company-scoped-picking-completion | Picking and completion isolated per company                    | no                    | Waits on S-03                                   |
| S-05       | company-scoped-stock-movements    | Deliveries, corrections and CSV import isolated per company    | no                    | Waits on S-01, S-03                             |
| S-06       | company-scoped-shopping-list      | Shopping list and CSV export per company                       | no                    | Waits on S-03                                   |
| S-07       | company-accounts                  | Technician and manager accounts within one company             | no                    | Waits on F-01; parallel with everything else    |
| S-08       | operator-setup-and-company-list   | Setup creates operator; operator company list                  | no                    | Waits on F-01; Open Question 1 non-blocking     |
| S-09       | company-registration              | Public company registration with pending state                 | no                    | Waits on F-01                                   |
| S-10       | registration-bot-protection       | Reject automated registrations                                 | no                    | Waits on S-09; mechanism chosen in plan         |
| S-11       | operator-approves-companies       | Operator approves or rejects registrations                     | no                    | Waits on S-03, S-08, S-09                       |
| S-12       | operator-blocks-companies         | Operator blocks and unblocks companies                         | no                    | Nice-to-have; waits on S-11                     |

This table is the clean handoff to Jira/Linear or any MCP-backed backlog.

## Open Roadmap Questions

1. **FR-033 setup race** — on a fresh deploy, whoever reaches the setup screen first with the token creates the operator; is the token alone enough protection? — Owner: user. Block: S-08 (non-blocking; current token posture carries over).
2. **Email features** — email verification, password reset and approval/rejection notifications are neither in scope nor ruled out. — Owner: user. Block: roadmap-wide (non-blocking; no slice includes them, and S-09/S-11 assume no email per Access Control Changes).

## Parked

- **Billing or plans** — Why parked: prd-v2 § Non-Goals — no subscriptions, pricing tiers or per-company usage limits.
- **Shared data across companies** — Why parked: prd-v2 § Non-Goals — no shared catalog, no cross-company stock transfers, no user in several companies.
- **Operator access to company data** — Why parked: prd-v2 § Non-Goals — no support or impersonation mode.
- **Multiple warehouses per company and stock per location** — Why parked: prd-v2 § Non-Goals — only the "one plant per installation" part of the v1 Non-Goal is lifted.
- **Ending open sessions immediately on block** — Why parked: FR-027 resolution — blocking takes effect at next login.
- **Carried from M-1** — stock-movement history (FR-006), project file attachments and PDF preview (FR-008, FR-009), production schedule, supplier ordering, offline mode and barcode scanning, metrics/error tracking — still parked per prd.md § Non-Goals.

## Milestone History

- **M-1: Zakład prowadzi pełny cykl zlecenia w systemie** (`mvp-production-loop`) — closed 2026-10-05. All 18 must-have FRs of prd.md v1 delivered across S-01…S-13 (archived 2026-09-26…2026-10-03); F-01 (`migration-and-invariant-harness`) closed as absorbed — its migration path and concurrency-test pattern landed inside the slices rather than as a separate change.

## Done
