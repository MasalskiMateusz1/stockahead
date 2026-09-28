# Kartoteka części z lokalizacjami (S-02) — Plan Brief

> Full plan: `context/changes/parts-catalog/plan.md`

## What & Why

Build the parts catalog: managers add/edit/deactivate/reactivate parts (name, quantity, one or more free-text locations), and any logged-in user searches it. This is slice `S-02` from `context/foundation/roadmap.md` — the first slice to hold a stock quantity, so it's also where the DB's non-negative-stock guardrail lands.

## Starting Point

Only `pl.regavio.stockahead.account` (login, roles, `/setup`) and `.security` exist. Two migrations (`accounts` table + single-manager index). No `parts` table, no CRUD, no search screen.

## Desired End State

A manager maintains the full parts catalog through `/parts` (add/edit/deactivate/reactivate). Any user searches it by name or location and sees quantity / reserved (stub `0` until `S-04`) / available / locations. Deactivated parts are hidden by default; a manager can filter to see and reactivate them.

## Key Decisions Made

| Decision | Choice | Why (1 sentence) | Source |
| --- | --- | --- | --- |
| Quantity mutability | Set once at creation; catalog edit never touches it | Keeps S-11's reason-based correction as the only path that changes stock, and avoids needing row-locking code for a race that doesn't exist yet | Plan (user-confirmed) |
| Delete semantics | Soft delete (`active` flag), reactivable, no quantity gate | User chose to mirror FR-002's account-deactivation pattern rather than a hard delete | Plan (user-confirmed) |
| Search scope | Matches part name OR location text | Serves the "gdzie leżą" (where things are) pain point, not just "what's the name" | Plan (user-confirmed) |
| Locations | ≥ 1 required at creation, duplicates (same text on one part) rejected | Matches FR-003's "one or more" floor while keeping the location list meaningful | Plan (user-confirmed) |
| F-01 concurrency-test pattern | Deferred, not built here | No operation in this slice mutates an existing part's quantity, so there's no race to guard against yet — the `CHECK` constraint still lands now | Plan |
| Multi-location input | Single textarea, one location per line | No JavaScript exists anywhere in this codebase; avoids introducing client-side tooling for a 3-week MVP | Plan |
| Package/architecture | New `pl.regavio.stockahead.parts`, no service layer; controller uses `TransactionTemplate` for writes | Aggregate updates need an explicit atomic boundary, with errors caught after rollback | Plan review (user-approved F3) |
| Location edits | Reconcile in place, retaining matching rows and maintaining both relationship sides | Preserves child identity and defines atomic edit/rollback behavior | Plan review (user-approved F3) |
| Integration-test transactions | No enclosing test transaction; committed fixtures, fresh reads, explicit cleanup | Verifies real request commits and constraint-error rollback without reusing an aborted transaction | Plan review (user-approved F2) |

## Scope

**In scope:**
- `parts` + `part_locations` migration with `CHECK (quantity >= 0)` and uniqueness constraints
- `/parts` search/browse (any authenticated user)
- Manager-only create / edit (name + locations only) / deactivate / reactivate
- Dashboard link to the new screen
- Integration tests for the schema guard, every business rule above, and role-gating

**Out of scope:**
- Quantity changes after creation (owned by `S-04`/`S-10`/`S-11`/`S-12`)
- The F-01 concurrent-transaction test (deferred to the slice that first mutates quantity)
- Hard delete, extra fields (description/SKU/unit), pagination, BOM-reference guards (no BOM yet), file uploads/PDF preview

## Architecture / Approach

One new feature package (`pl.regavio.stockahead.parts`): two entities (`Part` owning `PartLocation` via cascade), one repository with a JPQL search query joining both tables, one controller for both the public search route and the manager-only write routes, three plain Thymeleaf templates. No service layer or JavaScript. The controller uses `TransactionTemplate` for atomic writes and catches persistence failures after rollback. Edits retain matching location rows, remove absent rows, and add new rows. Request tests use committed fixtures and fresh database reads, with coverage for unchanged locations, overlapping replacements, and failed-edit rollback.

## Phases at a Glance

| Phase | What it delivers | Key risk |
| --- | --- | --- |
| 1. Schema & domain model | `parts`/`part_locations` migration, entities, search query | Getting the `CHECK` constraint and uniqueness right now avoids a corrective migration once real data exists |
| 2. Search & browse | `/parts` list+search for any user, dashboard link | Search must join locations, not just match name, per the accepted decision |
| 3. Manager CRUD | Create/edit/deactivate/reactivate, manager-only | Edit form must genuinely have no quantity field — that's the whole point of the immutability decision |
| 4. Tests | Full integration coverage incl. the raw `CHECK`-constraint test | Distinguishing "constraint exists" (tested here) from "constraint holds under a race" (explicitly deferred) |

**Prerequisites:** `S-01` (done — `auth-and-roles`), which this slice depends on for authentication and role checks.
**Estimated effort:** 4 phases, no time estimate per roadmap convention.

## Open Risks & Assumptions

- Deferring the F-01 concurrent-transaction test means the first slice to add a real quantity-mutating update (likely `S-10` or `S-11`) inherits the job of writing that test — flagged explicitly in the plan so it isn't silently dropped.
- A manager who mis-enters the initial quantity has no fix until `S-11` (stock-correction) ships — accepted tradeoff of the immutability decision.
- No BOM exists yet, so this slice can't guard against deleting/deactivating a part that's actually in use — `S-03` will need to add that guard itself.

## Success Criteria (Summary)

- A manager can fully manage the parts catalog (add, edit name/locations, deactivate, reactivate) and nothing else can change quantity through this slice.
- Any logged-in user can find a part by name or location and see its stock/reserved/available/locations.
- `./mvnw verify` passes, including role-gating (403 for technician on every write route) and the DB `CHECK` constraint test.
