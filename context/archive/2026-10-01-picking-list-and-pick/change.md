---
change_id: picking-list-and-pick
title: Lista kompletacyjna i pobranie części
status: archived
archived_at: 2026-10-01T17:47:48Z
created: 2026-10-01
updated: 2026-10-01
---

## Notes

S-07 z `context/foundation/roadmap.md` (milestone `mvp-production-loop`); realizuje FR-012, FR-013, §Business Logic i §Wymagania niefunkcjonalne (stan nigdy poniżej zera, brak podwójnej rezerwacji). Wprowadza pojęcie zlecenia "podjętego" (pierwsze pobranie), które chroni jego rezerwacje przed przejęciem przez `ReservationAllocator` — fundament pod FR-021 (S-09). Zależy od S-04 (`order-reserves-parts`) i S-06 (`technician-accounts`), oba `done`.
