# Frame Brief: Multi-company support

> Framing step before /10x-plan. This document captures what is *actually*
> at issue, separated from what was initially assumed.

## Reported Observation

"add suport for multiplecompanies at the same time": a scope request for more than
one company to be able to use Stockahead at the same time.

## Initial Framing (preserved)

- **User's stated cause or approach**: none stated. The request implies multi-tenancy
  inside one running Stockahead application.
- **User's proposed direction**: make Stockahead support multiple companies at once.
- **Pre-dispatch narrowing**: the trigger is a second company wanting in; the companies
  share **nothing** physical (no stock, parts or people); the motivation is a
  **learning exercise**, not a request from users.
- **User's pushback on the interim reframe** (one instance per company): "I don't want
  separete instance, I only want ot manage one instance with multiple companies
  registering on just one website".

## Dimension Map

1. **Product contract**: the PRD lists multi-plant/multi-warehouse as a Non-Goal, and
   AGENTS.md forbids implementing Non-Goals.
2. **Data-isolation surface**: every table, query and view would need a company boundary.
3. **Stock/reservation invariants**: the locking and allocation might assume one global company.
4. **Identity and first-run setup**: accounts, roles and `/setup` might assume exactly one organization.
5. **Deployment vs. code**: one instance per company could satisfy the observation with
   no code changes. ← interim reframe, **rejected by the user**
6. **Public company registration**: self-service sign-up of a new company on a shared
   website (surfaced by the user's pushback).

## Hypothesis Investigation

| Hypothesis | Evidence | Verdict |
| --- | --- | --- |
| 1. The change collides with the product contract | `prd.md` § Non-Goals: "Wiele zakładów / wiele magazynów — jedna instalacja obsługuje jeden zakład" (multiple plants or warehouses: one installation serves one plant); AGENTS.md hard rule "Do not implement anything listed in PRD § Non-Goals" | STRONG |
| 2. The isolation surface is large | 9 tables (V1–V13), none with an owner column. 5 global unique rules: `accounts.email` (V1:3, V4:21), `accounts_single_manager_idx` (V2:1), `parts.name` (V3:3), `projects.name` (V5:3). About 20 unscoped query methods (e.g. `PartRepository.java:19,33,89`, `OrderLineRepository.java:13,28,59`, the shopping list via `findOpenLinesWithShortage`) and about 20 `findById` lookups with no ownership check (IDOR risk: e.g. `PartController.java:203`, `OrderController.java:268`, `PickingController.java:254`). Global advisory locks (`PartRepository.java:62`, `LocationSpellings.java:45-71`) and `respellOnOtherParts` (`PartRepository.java:50-55`), which rewrites shelf spellings across the whole database. All 13 controllers and 23 templates are affected. No tenant or company concept exists anywhere | STRONG |
| 3. The invariants and locking assume one global company | `ReservationAllocator.reallocateForParts` (`ReservationAllocator.java:91-108`) locks only the part ids it is given (`PartRepository.findByIdInForUpdate`, :28-30), and every caller passes the parts it touched. So allocation separates by company on its own once parts belong to a company | WEAK: the invariant holds; only naming and locks leak (see row 2) |
| 4. Identity and setup assume one organization | One manager per database (`V2__enforce_single_manager.sql:1`). `/setup` closes once any manager exists (`SetupController.java:44,67`). The logged-in user carries no company (`AccountUserDetailsService.java:28-44`). Only the MANAGER and TECHNICIAN roles exist (`Role.java:5-6`), and all 48 `@PreAuthorize` checks are role-only. Email is unique across the whole database | STRONG |
| 5. One instance per company covers it | It is technically viable (the app is configured only by env vars, and the server is oversized: `infrastructure.md:59`), but the user explicitly wants one shared instance with companies registering on one website | REJECTED by user |
| 6. Public registration is a new product surface | `prd.md:127` (Access Control): "Brak publicznej rejestracji — konta zakłada kierownik" (no public registration; the manager creates accounts). The only public routes are `/login`, `/setup/**` and `/actuator/health/**` (`SecurityConfig.java:26`); `/setup` is gated by a secret `app.setup-token` (`application.properties:7`). There is no mail starter in `pom.xml`, so no email verification exists | STRONG |

## Narrowing Signals

- The companies share **nothing**. This rules out shared stock competing across
  companies and users belonging to several companies. The boundary is a hard partition.
- The user rejected instance-per-company: the requirement is **one running app, one
  website, companies sign themselves up**. This keeps dimensions 2 and 4 in scope and
  adds dimension 6.
- The motivation is learning. There is no real user waiting, so scope can be sliced for
  learning value rather than for delivery pressure.

## Cross-System Convention

A partition with nothing shared, in one app and one database, usually means a
shared-schema design with a tenant id on every root entity, the tenant resolved from the
logged-in principal, and every query and lookup scoped by it. Self-service sign-up
usually creates the tenant and its first admin in one step, which is the role `/setup`
plays here for a single plant. The user's direction matches this convention. The main
risks are cross-tenant data leaks through unscoped queries or lookups (row 2) and abuse
of a public, unauthenticated write endpoint (row 6).

## Reframed (or Confirmed) Problem Statement

> **The actual problem to plan around is**: Stockahead is built as a single-plant tool
> (one manager, global names, no public sign-up, a PRD that forbids multiple plants). It
> must become a multi-tenant app where any company can register on one website and only
> ever see its own data.

The initial framing was correct: proceed with in-app multi-tenancy. The investigation
adds two things the request didn't state. First, this is a **product pivot**: it reverses
a PRD Non-Goal *and* the PRD's "no public registration" rule. Second, most of the
difficulty is not the stock invariant, which already separates by part, but the
**isolation surface** (about 40 unscoped queries and lookups across all controllers)
and **identity and bootstrap** (one manager per database, the token-gated `/setup`, and
a logged-in user with no company).

## Confidence

**HIGH**: strong evidence for every in-scope dimension, the user's direction matches the
usual convention, and the user's pushback was decisive.

## What Changes for /10x-plan

1. **Before planning:** amend the PRD (`prd.md` § Non-Goals, § Access Control, plus the
   new FRs for company registration and tenant isolation). Until then, AGENTS.md
   ("Do not implement anything listed in PRD § Non-Goals") blocks any implementation.
   For this, use a brownfield `/10x-shape` run followed by `/10x-prd`, or edit in place.
2. The plan has to cover three separate concerns: (a) the company boundary on data,
   queries and lookups, with a test that a cross-company read is denied, (b) identity
   and bootstrap (company-scoped manager, principal carries the company, replacing the
   single-manager index and `/setup`), and (c) public company sign-up. Together this is
   larger than one slice, so it should probably be a new roadmap milestone rather than a
   single change.

## References

- Source files: `src/main/resources/db/migration/V1–V5`, `V2__enforce_single_manager.sql:1`,
  `orders/ReservationAllocator.java:91-108`, `parts/PartRepository.java:19-89`,
  `account/SetupController.java:35-67`, `AccountUserDetailsService.java:28-44`,
  `security/SecurityConfig.java:26`, `application.properties:7`, `pom.xml` (no mail starter)
- Docs: `context/foundation/prd.md` § Access Control (:127), § Non-Goals;
  `context/foundation/roadmap.md` (M-1: every slice done)
- Investigation: 2 Explore sub-agents (data-isolation surface; invariants and identity)
  plus direct reads of the PRD, deployment docs and security config
