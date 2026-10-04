---
project: "Stockahead"
version: 2
status: draft
created: 2026-10-04
context_type: brownfield
product_type: web-app
target_scale:
  users: small
  qps: low
  data_volume: small
timeline_budget:
  delivery_weeks: 3
  hard_deadline: null
  after_hours_only: true
---

# Stockahead — Multi-company support

## Current System Overview

Stockahead is a parts-warehouse and production-reservation web app for one small
electronics plant. Monolith: Spring Boot 4.1 / Java 21, Thymeleaf, Spring Security, JPA,
PostgreSQL, Flyway; self-hosted on Coolify (OVH) behind Cloudflare Tunnel. Users: one
manager (created once via the token-gated `/setup`) and technicians the manager creates.
Core functionality (milestone M-1, all slices done): parts catalog with locations,
device projects with BOMs, production orders that reserve parts by priority → deadline →
age, picking, completion, deliveries, stock correction, CSV import, shopping list with
CSV export. The PRD's Non-Goals state one installation serves one plant, and Access
Control states there is no public registration.

## Problem Statement & Motivation

Today only one company can ever use a Stockahead installation. The change: one running
instance, one website, where many companies register themselves and each sees only its
own data.

Why now: the single-plant scope was a deliberate MVP limit and that MVP is done; this
change is a learning goal (multi-tenant isolation and self-service sign-up), not a
customer request. Current workaround: none — a second company cannot use the system.

## User & Persona

- **New company's manager** — someone from another small plant who finds the website,
  registers their company and becomes its manager, then creates their own technicians.
- **Platform operator** — oversees all companies on the instance.
- Existing roles (manager, technician) keep their meaning, now within one company.

## Success Criteria

### Primary
- Two companies register, are approved by the operator, and each runs the full existing
  Stockahead flow (parts, projects, orders with reservations, picking, shopping list)
  without ever seeing the other's parts, orders or people.

### Secondary
- The operator blocks a company: its users can no longer log in (sessions already open
  may finish); unblocking restores them with their data intact.

### Guardrails
- No cross-company access: a user of company A can never see or change company B's data,
  including by guessing a record id in the URL.
- Stock/reservation guarantees hold per company: stock never below zero, no double
  reservation, allocation order (priority → deadline → age) unchanged.
- Users of a pending or blocked company cannot reach any warehouse feature.
- The operator account has no path to any company's parts, orders or accounts.
- Existing warehouse behaviour (FR-003…FR-022 of the current PRD) works unchanged within
  a company.

## User Stories

### US-01: Two companies register and work side by side without seeing each other

- **Given** a running instance with an operator account and no companies
- **When** a visitor registers company A, sees "awaiting approval", the operator approves A, and A's manager then uses parts, projects, orders and the shopping list; and company B goes through the same steps
- **Then** A and B each run the full existing flow, and neither ever sees the other's parts, orders or people

Before this change: only one company could exist; there was no registration and no operator.

#### Change flow

1. A visitor opens the website and registers company "A" (company name, email, password).
2. They log in and see "awaiting approval".
3. The operator logs in, sees company A as pending, approves it.
4. A's manager logs in and uses Stockahead exactly as today (parts, projects, orders,
   technicians, shopping list…).
5. Company "B" goes through 1–4; A and B never see each other's parts, orders or people.

## Scope of Change

New FRs continue the current PRD's numbering (FR-001…FR-022 already exist).

### Company registration & approval
- [new] FR-023: Visitor can register a company (company name, email, password) and becomes its manager; the company starts as pending. Priority: must-have
  > Socrates: Counter-argument considered: "bots spam sign-ups — a public form fills the pending list with junk; approval only moves the cleanup to the operator." Resolution: registration must pass a human-verification check before it reaches the pending list (mechanism decided downstream).
- [new] FR-024: Manager of a pending company can log in and sees only "awaiting approval". Priority: must-have
  > Socrates: Counter-argument considered: "what does a rejected registrant see?" Resolution: a generic login failure is acceptable; no special "rejected" message.
- [new] FR-025: Operator can see the list of companies (name, manager's email, registration date, status). Priority: must-have
  > Socrates: Counter-argument considered: "the manager's email is personal data about another company's people — does it contradict 'operator never sees company data'?" Resolution: kept; the manager's contact is platform data needed to judge a registration, not warehouse data.
- [new] FR-026: Operator can approve or reject a pending registration; a rejected registration is kept as a record, and its email may register again. Priority: must-have
  > Socrates: Counter-argument considered: "deleting rejected registrations loses the trace of who applied (cf. 'deactivate, never delete')." Resolution: revised — rejected registrations are kept for history; the email is still freed for a new registration.
- [new] FR-027: Operator can block and unblock a company; a blocked company's users cannot log in, its data stays. Priority: nice-to-have
  > Socrates: Counter-argument considered: "active sessions survive the block unless they are cut." Resolution: accepted — blocking takes effect at next login; open sessions may finish.

### Company isolation
- [new] FR-028: Every user sees and acts only on their own company's data (parts, projects, orders, picking, deliveries, corrections, import, shopping list, accounts). Priority: must-have
  > Socrates: Counter-argument considered: "part/project names and shelf spellings are unique across the whole system today — two companies both having 'R 10k' or shelf 'A1' must just work." Resolution: names and shelf spellings are unique per company; companies may reuse them freely.

### Accounts
- [modified] FR-029: User (operator, manager, technician) can log in with email + password; an email is unique across the whole platform. Priority: must-have — was FR-001
  > Socrates: No counter-argument; it stands as written.
- [modified] FR-030: Manager can create and deactivate technician accounts only within their own company. Priority: must-have — was FR-002
  > Socrates: No counter-argument; it stands as written.
- FR-031: (withdrawn — "all FR-003…FR-022 work unchanged within a company" moved to Success Criteria › Guardrails and a [preserved] scope item)
  > Socrates: Counter-argument considered: "too broad to verify as one FR — it's really a guardrail." Resolution: moved to guardrail.
- [new] FR-032: Manager can create and deactivate additional manager accounts in their own company; the last active manager cannot be deactivated. Priority: must-have
  > Socrates: Counter-argument considered: "a company could lock itself out by deactivating every manager, and the operator can't help (no data access)." Resolution: revised — the last active manager cannot be deactivated.
- [modified] FR-033: The one-time, token-gated setup screen creates the operator account, then closes. Priority: must-have — was: setup creates the first manager
  > Socrates: Counter-argument considered: "race on a fresh deploy — whoever reaches setup first with the token wins; the token is the only protection." Resolution: left open — see Open Questions.

### Preserved
- [preserved] Existing warehouse behaviour (FR-003…FR-022 of the current PRD) works unchanged within a company.
- [preserved] Stock never below zero, no double reservation, allocation order — per company.
- [preserved] Manager/technician capability matrix within a company; deactivate, never delete accounts.

## Constraints & Compatibility

- Product type unchanged: web-app. Scale stays small — a handful of companies.
- CSV formats unchanged: parts import and shopping-list export keep their current columns.
- The existing refresh NFR holds per company: after any event, reservations and shopping
  list update in < 2 s without manual refresh.
- Existing data: none carried over — the change may start with no data; the current
  instance holds test data only and can be wiped.
- Preserved: stock never below zero, no double reservation, allocation order — per company.
- Preserved: manager/technician capability matrix within a company; deactivate, never
  delete accounts.

### Non-functional requirements

- No user of one company can view or change another company's data through any page,
  action or URL (including a guessed record id).
- Automated mass registration is rejected before it reaches the operator's pending list,
  while a human completes sign-up without noticeable friction.
- Existing: after any event, a user sees current reservations and shopping list in < 2 s
  without manual refresh — within their company.
- Existing: concurrent work never drives a part's stock below zero nor reserves the same
  unit for two orders — within each company.

## Business Logic Changes

Each company's orders reserve only that company's parts — by priority, then earlier deadline, then older order — and a company's account can reach warehouse features only while the company is active.

**Modified rule.** The system currently allocates parts across all open orders of the
single plant by priority → deadline → age, and aggregates all shortages into one shopping
list. This change modifies it so allocation, recompute after every event, and the
shopping list consider only one company's parts and orders; companies never compete for
stock.

**New rule — company lifecycle.** A registered company is *pending*; the operator moves it
to *active* (approve) or *rejected* (reject — kept as a record, email freed). An active
company can be *blocked* and unblocked by the operator (nice-to-have). Only users of an
active company reach warehouse features; a pending company's manager sees "awaiting
approval"; blocked or rejected users cannot log in.

## Access Control Changes

**Current model:** email + password login; roles manager (superset of technician) and
technician; no public registration; the first manager is created once via token-gated
setup; the manager creates and deactivates technician accounts (never deletes).

**Planned changes:**

- **Company registration (new):** anyone can register a company on the public website,
  becoming that company's manager. The company starts as *pending* and is usable only
  after the platform operator approves it. The operator can approve or reject; a rejected
  registration does not become a company and its email can be used again.
- **Pending state (new):** the registrant can log in but sees only "awaiting approval" —
  no warehouse features. No email notification.
- **Platform operator (new role):** a separate account, not a member of any company. Can
  see the list of companies (name, manager's email, registration date, status) and block
  or unblock a company — a blocked company's users cannot log in, its data stays. The
  operator **cannot** see any company's warehouse data.
- **Company isolation (new):** every user sees and acts only on their own company's data.
- **Email (modified):** one email belongs to exactly one account in one company; login
  stays email + password with no company picker.
- **Preserved:** manager/technician roles and the role → capability matrix, now applied
  within one company; manager creates/deactivates their own company's technicians.

## Non-Goals

- No billing or plans — no subscriptions, pricing tiers or per-company usage limits.
- No shared data across companies — no shared parts catalog, no cross-company stock
  transfers, no user belonging to several companies.
- No operator access to company data — no support or impersonation mode; the operator
  never enters a company.
- One warehouse per company remains — locations stay plain text with no stock per
  location (carried over from the current PRD; only the "one plant per installation"
  part of that Non-Goal is lifted).

## Open Questions

1. **FR-033 setup race** — on a fresh deploy, whoever reaches the setup screen first with the token creates the operator; is the token alone enough protection? Owner: user.
2. **Email features** — email verification, password reset and approval/rejection
   notifications are neither in scope nor ruled out. Owner: user.
