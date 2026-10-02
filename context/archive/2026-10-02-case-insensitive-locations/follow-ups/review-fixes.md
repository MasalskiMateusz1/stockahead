# Review fixes — case-insensitive-locations

Queued from `reviews/impl-review.md` triage (2026-10-02).

## F2 — Production Postgres must use a UTF-8-aware ctype

- **Why**: The `part_locations_part_location_ci_idx` index and `PartRepository.findSpellingsOf` rely on
  Postgres `lower()`, while the app compares with Java `equalsIgnoreCase`. With a libc `C` ctype,
  `lower()` folds ASCII only, so `Regał A1` and `REGAŁ A1` would get past the per-part index and miss
  the cross-part spelling lookup. Testcontainers runs on `en_US.utf8`, so the test suite can't catch it.
- **Action**: When `context/deployment/deploy-plan.md` is written, require the database to be created
  with a UTF-8-aware ctype (e.g. `en_US.UTF-8`, or the `builtin` provider's `C.UTF-8` on PG17+). Add a
  verification step: `SELECT lower('REGAŁ A1') = 'regał a1';` returns `true` on the production database.
- **Status**: open — carry into the deploy plan.
