# SHA repoint before archive

- **Date**: 2026-09-30
- **Target**: `origin/main` @ `af139ba58b5ad54690ce04c191d72d4cb50654f1` (refreshed via `git fetch`)
- **Integration commit**: `40a8e343ba17b5ea4bcbb00c34d8d02f8ff23f80` (short `40a8e34`) — "Add device projects with BOM and documentation links (S-03) (#13)"
- **PR**: https://github.com/MasalskiMateusz1/stockahead/pull/13 — state `MERGED`, `mergeCommit.oid` = `40a8e343ba17b5ea4bcbb00c34d8d02f8ff23f80`, ancestor of `origin/main` (verified via `git merge-base --is-ancestor`)
- **Evidence**: the 4 old SHAs did not resolve to any object in this repository (`git rev-parse --verify '<sha>^{commit}'` → "Needed a single revision" for each). PR #13's commit list (`gh pr view 13 --json commits`) contains exactly those 4 oids (`b5a8d072ec219c25074752f98895e0eb76a17463`, `df1416ab45deeb54f535284edd7deb2e8c3f0c07`, `9062d67ecb1eb6864785103c8f6ff47bd43507ea`, `efad7ae291f817aa3514ac38adae7803e78f0283`) plus one epilogue commit, and the squash-merge diff of `40a8e34` matches the plan's deliverables (`Project`/`BomLine`/`ProjectLink`/`ProjectRepository`, `V5__create_projects.sql`, `ProjectController`/`ProjectBomController`/`ProjectLinkController`/`ProjectDetailModel`, all four project test classes, templates, message keys).
- **Decision**: Update and archive (user-approved)
- **Affected rows**: 23

| Row ID | Old suffix (resolved OID) | New SHA |
|---|---|---|
| 1.1 | `b5a8d07` (missing object) | `40a8e34` |
| 1.2 | `b5a8d07` (missing object) | `40a8e34` |
| 2.1 | `df1416a` (missing object) | `40a8e34` |
| 2.2 | `df1416a` (missing object) | `40a8e34` |
| 2.3 | `df1416a` (missing object) | `40a8e34` |
| 2.4 | `df1416a` (missing object) | `40a8e34` |
| 2.5 | `df1416a` (missing object) | `40a8e34` |
| 2.6 | `df1416a` (missing object) | `40a8e34` |
| 3.1 | `9062d67` (missing object) | `40a8e34` |
| 3.2 | `9062d67` (missing object) | `40a8e34` |
| 3.3 | `9062d67` (missing object) | `40a8e34` |
| 3.4 | `9062d67` (missing object) | `40a8e34` |
| 3.5 | `9062d67` (missing object) | `40a8e34` |
| 3.6 | `9062d67` (missing object) | `40a8e34` |
| 3.7 | `9062d67` (missing object) | `40a8e34` |
| 3.8 | `9062d67` (missing object) | `40a8e34` |
| 3.9 | `9062d67` (missing object) | `40a8e34` |
| 4.1 | `efad7ae` (missing object) | `40a8e34` |
| 4.2 | `efad7ae` (missing object) | `40a8e34` |
| 4.3 | `efad7ae` (missing object) | `40a8e34` |
| 4.4 | `efad7ae` (missing object) | `40a8e34` |
| 4.5 | `efad7ae` (missing object) | `40a8e34` |
| 4.6 | `efad7ae` (missing object) | `40a8e34` |

Row 4.7 (SHA-less, manual rebase/merge verification) left untouched — this is provenance, not an implementation review and not review coverage.
