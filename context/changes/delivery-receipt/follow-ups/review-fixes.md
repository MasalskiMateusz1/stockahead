# Review fixes — delivery-receipt

Source: `context/changes/delivery-receipt/reviews/impl-review.md`

## F1 — Lock the part row in PartController edit / deactivate / reactivate (Fix B)

- **Where**: `src/main/java/pl/regavio/stockahead/parts/PartController.java:200`, `:242-248`, `:258-280`
- **Problem**: `edit` hydrates the `Part` outside its transaction. With open-in-view on, the in-transaction `findById` returns that stale, unlocked instance. `Part` has no `@Version`/`@DynamicUpdate`, so `saveAndFlush` writes back the old `quantity`. A delivery, pick or cancel that commits in between is lost, while its reservations stay, leaving reserved > stock. `deactivate`/`reactivate` also read without a lock.
- **Fix**: Open as its own change (`/10x-new` → `/10x-plan`):
  - Inside each transaction, evict or refresh the open-in-view copy, then lock with `partRepository.findByIdInForUpdate(Set.of(id))`, following `PickingController`'s documented pattern.
  - Wrap each write in `LockRetry`.
  - Add a concurrency test of a delivery receipt against a part edit (and against deactivate). Use pg_sleep triggers as in `DeliveryConcurrencyTests`, and assert that final stock = start + delivered and that reserved ≤ stock.
