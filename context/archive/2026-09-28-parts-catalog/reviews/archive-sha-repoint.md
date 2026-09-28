# Archive SHA repoint — parts-catalog

## 2026-09-28

- **Target**: `origin/main` (https://github.com/MasalskiMateusz1/stockahead.git), snapshot `bb2bda9fad40601ceb93ef725ee8e5249268323c`
- **Integration commit**: `bb2bda9` — Parts catalog and error-message resource bundle (#11)
- **PR**: https://github.com/MasalskiMateusz1/stockahead/pull/11 (MERGED, base `main`, head `phase-7-tune`, squash-merged)
- **Evidence**: all four original SHAs appear in PR #11's commit list; none resolve locally after the squash. `bb2bda9` adds the full implementation covered by the rows (`parts/Part.java`, `PartLocation.java`, `PartRepository.java`, `PartController.java`, `V3__create_parts_catalog.sql`, `parts-*.html`, `dashboard.html`, `PartsCatalogIntegrationTests.java`). `git merge-base --is-ancestor bb2bda9 <target>` → 0.
- **Decision**: user chose "Update and archive".

| Row ID | Old suffix (resolved OID) | New SHA |
|---|---|---|
| 1.1 | 69bd01f (not in local repo) | bb2bda9 |
| 2.1 | 768a74d (not in local repo) | bb2bda9 |
| 2.2 | 768a74d (not in local repo) | bb2bda9 |
| 2.3 | 768a74d (not in local repo) | bb2bda9 |
| 3.1 | f904e15 (not in local repo) | bb2bda9 |
| 3.2 | f904e15 (not in local repo) | bb2bda9 |
| 3.3 | f904e15 (not in local repo) | bb2bda9 |
| 4.1–4.15 (15 rows) | 65cd3d2 (not in local repo) | bb2bda9 |

**Total rows repointed: 22.**
