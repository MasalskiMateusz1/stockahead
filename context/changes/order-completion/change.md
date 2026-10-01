---
change_id: order-completion
title: Zgłoszenie i potwierdzenie zakończenia zlecenia
status: implementing
created: 2026-10-01
updated: 2026-10-01
archived_at: null
---

## Notes

S-08 z `context/foundation/roadmap.md` (milestone `mvp-production-loop`); realizuje FR-014, FR-020 i §Business Logic (zamknięcie zlecenia zwalnia niepobrane rezerwacje, które trafiają do kolejnych zleceń). Zależy od S-07 (`picking-list-and-pick`, `done`) — korzysta z pojęcia zlecenia podjętego (`orders.taken_at`).
