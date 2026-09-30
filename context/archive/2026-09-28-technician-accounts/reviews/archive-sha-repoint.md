# Archive SHA repoint — technician-accounts

- **Date**: 2026-09-30
- **Target**: origin/main @ 0ebe8cf7a6226384183d0e55f2a4555e0433f6ee
- **Integration commit**: ea07131 — Add technician account management and immediate deactivation (#12)
- **PR**: https://github.com/MasalskiMateusz1/stockahead/pull/12 (MERGED, squash, base `main`)
- **Evidence**: The PR commit list contains c6c2040, be21274, 8a11bd9, 79234ff. None are ancestors of the target (`merge-base --is-ancestor` exit 1, non-shallow repo). ea07131 is an ancestor (exit 0). `git diff 79234ff ea07131 -- src` is empty, so the squash integrates the exact implementation for phases 1–4.
- **Decision**: The user chose "Update and archive".

| Row ID | Old suffix (resolved) | New SHA |
| --- | --- | --- |
| 1.1–1.5 | c6c2040 (c6c2040600f0f7aead7208f2e94ac15c7b66cdea) | ea07131 |
| 2.1–2.7 | be21274 (be21274032c672bd93c8a651b8b8097f8e6be808) | ea07131 |
| 3.1–3.5 | 8a11bd9 (8a11bd96063eed23140b31d2e76b1476127d851f) | ea07131 |
| 4.1–4.3 | 79234ff (79234ff31f3fa439fb2c1b8afeb371fee419b97b) | ea07131 |

**Rows repointed**: 20
