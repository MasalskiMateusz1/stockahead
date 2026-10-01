---
change_id: shopping-list-and-csv-export
title: Lista zakupów i eksport do CSV
status: implemented
created: 2026-10-01
updated: 2026-10-01
---

## Notes

S-05 z `context/foundation/roadmap.md` (ścieżka główna A, milestone realizujący Kryterium sukcesu Primary); realizuje FR-015, FR-016, §Business Logic. Zależy od S-04 (`order-reserves-parts`), status `done`. Domyka Primary success criterion: dopiero tu kierownik dostaje odpowiedź „co zamówić” w formie, którą da się wynieść z aplikacji (CSV).

Rozstrzygnięty podczas planowania otwarty punkt roadmapy S-05: format CSV to średnik jako separator + UTF-8 z BOM (zgodność z Excelem w polskiej lokalizacji i polskimi znakami).
