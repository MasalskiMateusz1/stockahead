# Projekty urządzeń i BOM (S-03) — Plan Brief

> Full plan: `context/changes/project-bom/plan.md`

## What & Why

A manager needs device projects that list the parts (and quantities per unit) needed to build one device, plus links to schematics and other documentation (FR-007, FR-019). This BOM is what S-04 will multiply by N and reserve against stock — without it no production order can exist.

## Starting Point

`main` has login/roles (F-01) and the parts catalog with locations and deactivation (S-01, `parts` package, migrations V1–V3). There is no project, BOM or link model. Codex is implementing S-02 in this same working tree and owns migration `V4`.

## Desired End State

The manager creates, renames, deactivates and reactivates projects, builds each BOM line by line from active parts, and adds or removes http/https documentation links. Every logged-in user can browse active projects and read their BOM and links. Lines on parts deactivated later stay in the BOM and are marked "nieaktywna".

## Key Decisions Made

| Decision | Choice | Why |
| --- | --- | --- |
| Project "delete" | Deactivate/reactivate, no hard delete | S-04 orders will reference projects; same pattern as parts and accounts |
| BOM editing | Per-line forms on the detail page (select of active parts + quantity) | No name typos, atomic changes, no JavaScript needed |
| Access | Everyone reads projects, BOM and links; only the manager edits | Technicians need the schematics before assembly; mirrors `/parts` |
| Deactivated part in a BOM | Line stays and is marked; only active parts can be added | Deactivating a part never silently changes a BOM |
| Documentation link | http/https URL (≤ 2048) + optional label, opened with `rel="noopener noreferrer"` | Scheme whitelist blocks `javascript:` XSS; labels read better than URLs |
| Duplicates | DB `UNIQUE` is the only check (project name, one line per part) | Race and plain duplicate share one tested path (S-02 review lesson) |
| Coordination with Codex | Separate worktree/branch, migration `V5`, merge after S-02 | Flyway `outOfOrder=false` would reject S-02's `V4` after a deployed `V5` |

## Scope

**In scope:**

- `V5` schema (`projects`, `bom_lines`, `project_links`) with DB constraints.
- Project list/detail/create/rename/deactivate/reactivate, dashboard link.
- BOM line add/change/remove; inactive-part marker.
- Documentation link add/delete with URL validation.
- Integration tests per phase, 403 on every manager route.

**Out of scope:**

- Hard delete of projects, link editing, extra project fields, search/pagination.
- File upload and PDF preview (FR-008/FR-009).
- Blocking part deactivation when used in a BOM; any order-related rule (S-04).
- Bulk/paste BOM entry; row locking (no stock changes here).

## Architecture / Approach

New package `pl.regavio.stockahead.projects`: `Project` owns `BomLine` and `ProjectLink` collections; `BomLine` references `parts.Part`. Three controllers split by concern (`ProjectController`, `ProjectBomController`, `ProjectLinkController`) follow `PartController` — no service layer, `TransactionTemplate` writes, constraint violations re-rendered as form errors — and share `ProjectDetailModel` to render the detail page with errors.

## Phases at a Glance

| Phase | What it delivers | Key risk |
| --- | --- | --- |
| 1. Schema & domain | `V5`, entities, repository, DB-constraint tests | Migration numbering vs S-02 |
| 2. Projects | List, detail, create, rename, deactivate/reactivate, dashboard link | Role split and inactive visibility |
| 3. BOM lines | Add/change/remove lines, inactive-part marker | Duplicate and tampered-part handling |
| 4. Documentation links | Add/delete with http/https whitelist; rebase + merge after S-02 | XSS via link URL; merge order |

**Prerequisites:** F-01 and S-01 done (both archived). Work from a separate worktree: `git worktree add ../stockahead-project-bom -b project-bom main`, then copy `context/changes/project-bom/` into it. Merge only after S-02 is on `main`.
**Estimated effort:** ~3–4 after-hours sessions across 4 small phases.

## Open Risks & Assumptions

- If S-02 slips and S-03 must ship first, rename the migration to `V4` and ask Codex to renumber to `V5` before either merges.
- `dashboard.html` and `messages.properties` will conflict on rebase; changes here are append-only, so keep both sides.
- Test accounts use lowercase, class-unique emails so tests pass both before and after S-02's canonical-email login and activity filter land.

## Success Criteria (Summary)

- A manager builds a project's BOM from active parts and attaches documentation links; a technician can read them but not change them.
- Duplicate names/lines, non-positive quantities, inactive parts and non-http(s) URLs are rejected with friendly errors, never a 500.
- `./mvnw verify` passes in the worktree and again after rebasing onto `main` with S-02.
