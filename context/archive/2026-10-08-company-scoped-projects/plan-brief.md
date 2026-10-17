# Company-Scoped Projects — Plan Brief

> Full plan: `context/changes/company-scoped-projects/plan.md`
> Frame brief: `context/changes/multi-company-support/frame.md`

## What & Why

This is roadmap item S-02 of milestone M-2. Projects, their BOMs and their documentation links stay within one company. Another company sees none of them, gets 404 on their ids, may reuse their names, and cannot build a BOM from someone else's parts. This is FR-028 plus the prd-v2 guessed-id guardrail. The new risk here is the cross-company *reference* (a BOM line pointing at a foreign part), not only the lookups.

## Starting Point

S-01 scoped the parts catalog with an explicit `companyId` parameter, `findByIdAndCompanyId` and a composite FK. Projects still have a global `UNIQUE (name)`, a global `list()` and ten bare `findById`/`existsById` lookups across the three project controllers and `ProjectDetailModel`. `ProjectBomController.java:85` accepts any part id. `OrderController` lists every project in the new-order picker and loads the posted project unscoped (`:544`), so company B could order company A's project and reserve A's parts.

## Desired End State

Each company's managers and technicians see only their own projects. All nine `/projects/{id}…` routes return 404 for another company's id and change nothing. The database rejects any BOM line whose project and part belong to different companies. A foreign part id in a BOM post gets the existing "part unavailable" message. The new-order form offers only the user's own company's projects, and a foreign project id gets "project unavailable".

## Key Decisions Made

| Decision                                     | Choice                                                                                                           | Why (1 sentence)                                                                                                       | Source       |
| -------------------------------------------- | ---------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------- | ------------ |
| Scoping mechanism                            | Explicit `companyId` parameter, no `@TenantId`/`@Filter`                                                         | Same as F-01/S-01, and native SQL isn't filtered automatically anyway                                                  | Frame / S-01 |
| BOM cross-company guard                      | `bom_lines.company_id` with composite FKs to `projects (id, company_id)` and `parts (id, company_id)`            | Impossible at the DB level even if an app check is forgotten; mirrors `part_locations`                                 | Plan         |
| `project_links` company column               | None, reached only through its scoped project                                                                    | No query reads links on their own; AGENTS.md doesn't list the table                                                    | Plan         |
| Order project lookup (`OrderController:544`) | Scoped in S-02, along with the picker                                                                            | The only path where a project leak becomes cross-company stock reservation; one line next to code S-02 already touches | Plan         |
| Response to a foreign id                     | Same as a missing id: 404 on project routes, `partUnavailable` for a BOM part, `projectUnavailable` for an order | AGENTS.md: "exactly like a missing id"                                                                                 | AGENTS.md    |
| Name uniqueness                              | `UNIQUE (company_id, name)`, case-sensitive as today                                                             | FR-028; no change to v1 semantics                                                                                      | Roadmap      |
| Phase split                                  | Schema and fixtures first, then scoping and tests                                                                | Proven in S-01: a red build never mixes fixture churn with isolation bugs                                              | Plan         |

## Scope

**In scope:**
- V16: `company_id` on `projects` and `bom_lines`, per-company project names, composite FKs
- Company on project creation; `CompanyFixtures.project`/`bomLine` and cleanup; about 28 test files' inserts
- Scoped `ProjectRepository`, all project routes, the BOM part lookup, and the order form's project picker and lookup
- `ProjectsCompanyIsolationIntegrationTests`; an S-03 hand-off note in the roadmap

**Out of scope:**
- `company_id` on `project_links`, `orders`, `order_lines`; the `orders.project_id` composite FK (S-03)
- The order assignee picker and lookup, order by-id routes, allocation (S-03/S-04)
- UI changes, a project delete, new 403 tests

## Architecture / Approach

`CompanyUser.companyId()` goes from the controller into `ProjectRepository.findByIdAndCompanyId`/`list(companyId, …)` and `PartRepository.findByIdAndCompanyId`. `BomLine.companyId` is set from its project in `Project.addBomLine`. The composite FKs then make a mixed-company BOM line a constraint violation. The code never reaches that violation because the part lookup is scoped first.

## Phases at a Glance

| Phase | What it delivers | Key risk |
| --- | --- | --- |
| 1. Schema, entities and test fixtures | V16, company on `Project`/`BomLine`, every test seed carries a company; green build, behavior unchanged | Large mechanical fixture churn; a missed insert fails on `NOT NULL` |
| 2. Scoped routes and two-company tests | All project lookups, the BOM part lookup and the order project picker/lookup scoped; B→404 tests on nine routes | A forgotten handler (e.g. `removeLine`) still has no principal; the grep criterion catches it |

**Prerequisites:** S-01 archived (done); a running Docker for Testcontainers.
**Estimated effort:** ~2 sessions, one per phase, similar to S-01.

## Open Risks & Assumptions

- V16 assumes empty `projects`/`bom_lines`/`project_links`. This holds through the V14 guard, since multi-company work ships from its branch together.
- Until S-03, an order can still be assigned to another company's account (the assignee lookup is unscoped). The S-11 release gate covers this.
- A composite-FK violation would surface as "duplicate part". The code can't reach it today (same accepted shape as S-01 impl review F4).

## Success Criteria (Summary)

- Two companies each own a project "Board X". Neither sees, opens nor changes the other's, and a pasted foreign id shows the 404 page.
- A BOM can't contain another company's part, whether through the form or the database.
- An order can be created only from the user's own company's project.
