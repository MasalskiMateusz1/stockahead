# Company-Scoped Parts Catalog — Plan Brief

> Full plan: `context/changes/company-scoped-parts/plan.md`
> Frame brief: `context/changes/multi-company-support/frame.md`

## What & Why

Roadmap M-2 S-01, the north star: the first warehouse table to get a company boundary, proven end to end with the guessed-id → 404 guardrail. From the frame: *Stockahead … must become a multi-tenant app where any company can register on one website and only ever see its own data.* Under prd-v2 FR-028, names and shelf spellings are unique per company.

## Starting Point

F-01 is done. Companies exist, every account has one, `CompanyUser.companyId()` carries the company, and `CompanyFixtures` builds two-company tests. The parts catalog is fully global:
- a `UNIQUE` part name;
- global search, spelling and name-index queries;
- shelf re-spelling across the whole database;
- advisory locks keyed by name only;
- five bare `findById` calls in `PartController`.

## Desired End State

Company A's manager adds, edits and searches parts and shelves. Company B's users see none of them, get 404 on A's part ids, and can reuse the same part names and shelf spellings. Import matching, the delivery form and the BOM part picker show only the user's own company. `./mvnw verify` is green.

## Key Decisions Made

| Decision | Choice | Why (1 sentence) | Source |
| --- | --- | --- | --- |
| Tenancy model | Shared schema, `company_id`, company from the principal | Usual convention for a hard partition, and what the user asked for | Frame |
| Enforcement | Explicit `companyId` parameter on every scoped query | Same as F-01, covers JPQL, native SQL and locks alike, and a missed scope is greppable | Plan (user) |
| S-01 boundary | Parts catalog fully scoped, plus the changes forced in import, delivery and the BOM picker; their own by-id lookups stay for S-02/S-05 | Keeps the slice the size of the roadmap item, while visible leaks close as a side effect | Plan (user) |
| Name uniqueness | `UNIQUE (company_id, name)`, case-insensitive check per company under a lock | FR-028; AGENTS.md § Company isolation | Frame / AGENTS.md |
| Locations | `part_locations.company_id` with a composite FK to `parts (id, company_id)` | AGENTS.md lists locations as carrying `company_id`; the FK keeps it equal to the part's | Plan |
| Lock keys | Company folded into the shelf and name advisory-lock key; lock order unchanged | Companies never block each other; sorted order keeps it deadlock-free | Plan |
| Existing data | No backfill; V15 relies on the V14-empty database | Multi-company work isn't on `main` yet; both migrations ship in one deploy | Plan |
| Phasing | 1: schema and fixture fallout; 2: scoping and two-company tests | Keeps the ~27 mechanical test edits apart from the isolation logic | Plan (user) |

## Scope

**In scope:**
- V15 migration
- `Part.company` and `PartLocation.companyId`
- Company-scoped `PartRepository` reads and re-spelling
- `LocationSpellings`, `CatalogNameIndex` and `ImportPreview` scoped to the company
- `PartController` by `(id, companyId)`
- Company-scoped reserved quantities on the parts list
- Import, delivery form and BOM picker passing the company
- `CompanyFixtures.part(...)`, with every part-seeding test migrated
- `PartsCompanyIsolationIntegrationTests`
- AGENTS.md and roadmap notes

**Out of scope:**
- By-id lookups in delivery, correction and import (S-05)
- `ProjectBomController` part lookup (S-02)
- Order, picking and allocator locks (S-03/S-04)
- `company_id` on any other table
- `@TenantId` or `@Filter`
- UI changes

## Architecture / Approach

The company flows from the database to `CompanyUser`, then to the controller, then into an explicit `companyId` argument for the repository and the package-private helpers. A miss on `(id, companyId)` is a 404, like a missing id. The database backs it up: per-company `UNIQUE` on names and a composite FK that makes a location's company match its part's.

## Phases at a Glance

| Phase | What it delivers | Key risk |
| --- | --- | --- |
| 1. Schema, entity and test fixtures | V15, `Part.company`, company set on create and import, every test seeds parts with a company, green build | Wide mechanical diff (~27 test files); composite-FK mapping on `PartLocation` |
| 2. Scoped catalog and two-company tests | Scoped queries, locks and lookups, forced callers updated, isolation and concurrency tests, docs | A missed global query or lock key; the grep criterion and B-side tests catch it |

**Prerequisites:** Docker running for `./mvnw verify`; work on the `multi-company-support` branch, never `main`.
**Estimated effort:** about 2 sessions across 2 phases.

## Open Risks & Assumptions

- Import, delivery and correction routes keep bare by-id lookups (delivery locks posted ids). This is safe only while production has a single company, until S-05 and S-09 ship.
- `ProjectBomController` can still attach another company's part id via a hand-crafted POST until S-02.
- V15 assumes empty `parts` tables. A non-empty database fails the deploy and needs the deployment.md wipe.

## Success Criteria (Summary)

- Company B's user gets 404 on every `/parts/{A-id}` route, and A's data stays unchanged.
- Two companies each hold "R 10k" at shelf "A1" with their own spellings and never block each other's writes.
- Lists, search, the import preview and the BOM picker show only the user's own company's parts.
