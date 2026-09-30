# SHA Repoint — error-messages-resource-bundle

- **Date**: 2026-09-30
- **Target remote/branch**: origin/main
- **Target snapshot OID**: af139ba58b5ad54690ce04c191d72d4cb50654f1
- **Integration commit**: bb2bda9fad40601ceb93ef725ee8e5249268323c — "Parts catalog and error-message resource bundle (#11)" (squash merge, single parent ae6de2dee3cb285c9cee35670ebe83ad87698138)
- **PR**: https://github.com/MasalskiMateusz1/stockahead/pull/11 (base `main`, head `phase-7-tune`, state MERGED)
- **User decision**: Update and archive (explicit approval via AskUserQuestion)

## Evidence

The original Progress rows recorded commits from the `phase-7-tune` feature branch:

- `bc4ce3148f349c8b41d0d666ce34bb6ea0993d47` — "feat(error-messages-resource-bundle): Resource bundle i wpięcie kontrolerów (p1)"
- `46526e2cbca01fc6299f049e3e2861060ae0c8fc` — "feat(error-messages-resource-bundle): Wpięcie szablonu login.html (p2)"

Both resolved to valid commits but `git merge-base --is-ancestor <sha> af139ba5` exited 1 for each — neither is an ancestor of `origin/main`. PR #11 merged `phase-7-tune` into `main` as a **squash merge** (single-parent commit `bb2bda9f`), so the original branch commits never entered `main`'s history under their own SHAs.

`bb2bda9f` **is** an ancestor of `origin/main` (`git merge-base --is-ancestor bb2bda9f af139ba5` exits 0). Its diff (`git show --stat bb2bda9f`) touches exactly the files this change's plan specifies:

- `src/main/java/pl/regavio/stockahead/account/SetupController.java`
- `src/main/java/pl/regavio/stockahead/parts/PartController.java`
- `src/main/resources/messages.properties`
- `src/main/resources/templates/login.html`
- `src/test/java/pl/regavio/stockahead/MessagesBundleTests.java`

The PR's commit list includes both `bc4ce31` and `46526e2` verbatim among its squashed commits, confirming this squash commit is the integration point for this change's implementation (alongside the unrelated `parts-catalog` change, also squashed in the same PR).

## Row mapping

| Row ID | Old suffix (resolved OID) | New SHA |
|---|---|---|
| 1.1 | `bc4ce31` (bc4ce3148f349c8b41d0d666ce34bb6ea0993d47) | `bb2bda9f` |
| 2.1 | `46526e2` (46526e2cbca01fc6299f049e3e2861060ae0c8fc) | `bb2bda9f` |
| 2.2 | `46526e2` (46526e2cbca01fc6299f049e3e2861060ae0c8fc) | `bb2bda9f` |
| 2.3 | `46526e2` (46526e2cbca01fc6299f049e3e2861060ae0c8fc) | `bb2bda9f` |
| 2.4 | `46526e2` (46526e2cbca01fc6299f049e3e2861060ae0c8fc) | `bb2bda9f` |

**Total affected rows: 5.**
