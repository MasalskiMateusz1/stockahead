---
change_id: error-messages-resource-bundle
title: Wydzielenie komunikatów błędów do resource bundle
status: archived
created: 2026-09-28
updated: 2026-09-30
archived_at: 2026-09-30T05:23:07Z
---

## Notes

Zmiana ad-hoc, techniczna — nie wynika z żadnego FR/NFR w `context/foundation/prd.md` (PRD nie wspomina o wielojęzyczności) i celowo nie jest slice'em na `context/foundation/roadmap.md`. Zakres: wszystkie dynamiczne komunikaty błędów/statusu w `SetupController`, `PartController` i `login.html` trafiają do `messages.properties` przez Spring `MessageSource`, z zachowaniem dzisiejszej dokładnej treści — bez `LocaleResolver`, bez drugiego języka, bez ruszania statycznych etykiet/nagłówków/przycisków. Czysty grunt pod przyszłe wsparcie wielu języków.
