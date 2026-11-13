---
date: 2026-11-13T11:40:52+01:00
researcher: Codex
git_commit: 64791a8747de247fa6a0a8149449d64de1efcc79
branch: origin/feat/modern-ui-refresh
repository: 10xdevs
topic: "Track stock per individual location"
tags: [research, codebase, stock, locations, reservations]
status: complete
last_updated: 2026-11-13
last_updated_by: Codex
---

# Research: Track stock per individual location

**Date**: 2026-11-13T11:40:52+01:00  
**Researcher**: Codex  
**Git Commit**: 64791a8747de247fa6a0a8149449d64de1efcc79  
**Branch**: origin/feat/modern-ui-refresh  
**Repository**: 10xdevs

## Research Question

For the active `stock` change, what currently tracks physical stock and locations, which flows depend on that model, and what must be decided before planning stock per individual location? The request in `change.md` says “per single location” (`context/changes/stock/change.md:3-10`).

## Summary

The current catalog stores physical quantity on `parts` and location names on `part_locations`, without a quantity on a location (`src/main/resources/db/migration/V3__create_parts_catalog.sql:1-14`; `src/main/java/pl/regavio/stockahead/parts/Part.java:36-46`; `src/main/java/pl/regavio/stockahead/parts/PartLocation.java:16-33`). In the inspected delivery, import, correction, pick, and cancellation paths, stock changes use that part total; reservations and shopping shortages are also calculated by part (`src/main/java/pl/regavio/stockahead/parts/DeliveryController.java:159-212`; `src/main/java/pl/regavio/stockahead/parts/PartImportController.java:269-348`; `src/main/java/pl/regavio/stockahead/parts/StockCorrectionController.java:227-256`; `src/main/java/pl/regavio/stockahead/orders/PickingController.java:182-213`; `src/main/java/pl/regavio/stockahead/orders/OrderController.java:320-404`; `src/main/java/pl/regavio/stockahead/orders/ReservationAllocator.java:98-115`; `src/main/java/pl/regavio/stockahead/purchasing/ShoppingListModel.java:58-79`).

The proposed scope reverses an explicit product boundary: the original PRD says that a part's quantity is combined across its locations, and both PRDs exclude stock per location (`context/foundation/prd.md:62,153`; `context/foundation/prd-v2.md:196-198`). The new request establishes the topic for research; the product contract and location-specific behavior still need resolution before an implementation plan.

## Detailed Findings

### Stored model and company boundary

- `V3` creates `parts.quantity` with a nonnegative check and `part_locations` with a location string but no quantity (`src/main/resources/db/migration/V3__create_parts_catalog.sql:1-14`). The corresponding entities follow that split (`src/main/java/pl/regavio/stockahead/parts/Part.java:36-46`; `src/main/java/pl/regavio/stockahead/parts/PartLocation.java:16-33`).
- `V15` adds company ownership and a company-scoped part/location relationship (`src/main/resources/db/migration/V15__company_scoped_parts.sql:4-20`). The current company boundary requires location-level reads and writes to retain that ownership; the existing allocator accepts `companyId` with affected part ids (`src/main/java/pl/regavio/stockahead/orders/ReservationAllocator.java:98-115`).
- In the inspected catalog edit path, location rows can be reconciled and removed while quantity stays on the part (`src/main/java/pl/regavio/stockahead/parts/Part.java:117-119`; `src/main/java/pl/regavio/stockahead/parts/PartController.java:306-352`). Once a row holds stock, removing or renaming it needs defined behavior.

### Stock input and output paths

- A delivery can name a location, but the receipt merges quantities by part, increments `Part.quantity`, and adds a missing location label; the location is not the quantity's storage destination in this path (`src/main/java/pl/regavio/stockahead/parts/DeliveryController.java:159-212,215-264,328-354`; `src/main/resources/templates/deliveries-new.html:41-44`).
- CSV input has name, quantity, and location columns, but `PartsImportFile.ImportLine` aggregates quantity by part and keeps a distinct location list. That merged representation does not retain each source row's quantity-to-location association (`src/main/java/pl/regavio/stockahead/parts/PartsCsvReader.java:41-48,85-88`; `src/main/java/pl/regavio/stockahead/parts/PartsImportFile.java:50-58,175-198`). Preview and acceptance compare and raise part totals (`src/main/java/pl/regavio/stockahead/parts/ImportPreview.java:113-126`; `src/main/java/pl/regavio/stockahead/parts/PartImportController.java:269-348`).
- Manual correction locks and changes the part total and records part-level before/after quantities (`src/main/java/pl/regavio/stockahead/parts/StockCorrectionController.java:227-256`; `src/main/java/pl/regavio/stockahead/parts/StockCorrection.java:18-20,45-49`). Cancellation returns selected picked units to the part total without a destination shelf (`src/main/java/pl/regavio/stockahead/orders/OrderController.java:320-404`).
- Picking locks a part, decreases its total, and moves the picked amount out of the line's live reservation. Its submitted form supplies a line quantity without a source location (`src/main/java/pl/regavio/stockahead/orders/PickingController.java:171-213`; `src/main/resources/templates/picking-detail.html:68-79`). The live-counter convention matters for repeated picks (`context/foundation/lessons.md`, “Don't re-subtract a counter”).

### Allocation, read views, and constraints

- The allocator uses stock pooled by part id, protects taken-order reservations, and handles shortage against that part pool (`src/main/java/pl/regavio/stockahead/orders/ReservationAllocator.java:98-190`). The shopping list computes missing units from each order line's required, reserved, and picked quantities, aggregated by part (`src/main/java/pl/regavio/stockahead/orders/OrderLine.java:121-129`; `src/main/java/pl/regavio/stockahead/purchasing/ShoppingListModel.java:58-79`).
- The current part list shows one total beside a joined shelf list; part creation accepts one quantity and multiple location lines; the pick view shows labels beside a part-level quantity form (`src/main/resources/templates/parts-list.html:58-61`; `parts-new.html:23-28`; `picking-detail.html:68-79`). Those inspected views do not express shelf-level balances or a selected shelf for a pick.
- Existing stock guarantees are per part and company: no negative physical stock and no double reservation, with priority, deadline, and age allocation (`context/foundation/prd.md:40-42,117-123`; `context/foundation/prd-v2.md:54-58,123`). A location model must preserve those totals and company isolation. The prior locking lesson warns that narrowing the allocator's computed lock set needs whole-transaction deadlock retry (`context/foundation/lessons.md`, “Narrowing ReservationAllocator's part-row lock”).

## Code References

- `src/main/resources/db/migration/V3__create_parts_catalog.sql:1-14` — part total and location label schema.
- `src/main/resources/db/migration/V15__company_scoped_parts.sql:4-20` — company ownership of catalog data.
- `src/main/java/pl/regavio/stockahead/orders/ReservationAllocator.java:98-190` — part-level allocation and taken-order shortage handling.
- `src/main/java/pl/regavio/stockahead/orders/PickingController.java:171-213` — part-level pick mutation.
- `src/main/java/pl/regavio/stockahead/parts/PartsImportFile.java:175-198` — CSV rows merged by part.

## Architecture Insights

`Part.quantity` is currently the shared physical-stock source for mutation and allocation paths. If location balances become authoritative, the plan must define how the part total is maintained or derived, and keep the sum of location balances consistent under concurrent writes (`src/main/java/pl/regavio/stockahead/parts/Part.java:80-84`; `src/main/java/pl/regavio/stockahead/orders/ReservationAllocator.java:98-115`). Existing location normalization and shelf spelling rules should be traced rather than replaced: `PartLocation.normalize`/`sameLocation` and `LocationSpellings` are the current entry points (`src/main/java/pl/regavio/stockahead/parts/PartLocation.java:68-89`; `src/main/java/pl/regavio/stockahead/parts/LocationSpellings.java:40-60,95-110`).

## Historical Context (from prior changes)

- **Supported current scope:** the v1 PRD says stock is combined across locations, and the v2 PRD and current roadmap still park stock per location (`context/foundation/prd.md:62,153`; `context/foundation/prd-v2.md:196-198`; `context/foundation/roadmap.md:279`). This is a scope conflict for the new request, not evidence that the requested feature has already been built.
- **Partially superseded historical location research:** the earlier case-insensitive-location research correctly describes the original label-only design, but its statement that no case-insensitive database backstop exists was written before later migrations. Current `V10` and `V15` need to govern planning (`context/archive/2026-10-02-case-insensitive-locations/research.md:74-82`; `src/main/resources/db/migration/V10__case_insensitive_part_locations.sql:13-19`; `V15__company_scoped_parts.sql:4-20`).

## Related Research

- `context/archive/2026-10-02-case-insensitive-locations/research.md` — history of location normalization; assess its dated claims against current migrations.

## Open Questions

1. Does the new change formally lift the “no stock per location” rule in both PRDs and the roadmap, while retaining one warehouse per company (`context/foundation/prd.md:153`; `context/foundation/prd-v2.md:196-198`; `context/foundation/roadmap.md:279`)?
2. Should reservations remain part-wide with a shelf chosen when picking, or be allocated to individual locations? The current allocator and order lines are part-wide (`src/main/java/pl/regavio/stockahead/orders/ReservationAllocator.java:98-115`; `src/main/java/pl/regavio/stockahead/orders/OrderLine.java:121-129`).
3. Which location receives a new part's initial units, a delivery with no location, a correction, and a cancelled order's returned units; which location loses units on a pick? The inspected forms and mutations do not supply each of these mappings (`src/main/resources/templates/parts-new.html:23-28`; `deliveries-new.html:41-44`; `picking-detail.html:68-79`; `src/main/java/pl/regavio/stockahead/parts/StockCorrectionController.java:227-256`; `src/main/java/pl/regavio/stockahead/orders/OrderController.java:320-404`).
4. How should existing aggregate quantities be distributed across existing location rows? The stored model has no quantity-to-location mapping to reconstruct one (`src/main/resources/db/migration/V3__create_parts_catalog.sql:1-14`).
