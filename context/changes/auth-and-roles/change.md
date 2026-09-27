---
change_id: auth-and-roles
title: Logowanie i role (kierownik/technik)
status: implemented
created: 2026-09-26
updated: 2026-09-27
archived_at: null
---

## Notes

Odpowiada F-01 w `context/foundation/roadmap.md` (milestone `core-mvp-loop`). Zakres celowo minimalny: logowanie, egzekwowanie ról, pierwszy kierownik przez ekran `/setup`. Zarządzanie kontami techników to osobny slice (S-02, `technician-accounts`).

Mechanizm pierwszego konta kierownika (`/setup` + `STOCKAHEAD_SETUP_TOKEN`) musiał zostać uzgodniony z już wdrożoną infrastrukturą opisaną w `context/foundation/deployment.md` (D10, S3) — nie z zerowym punktem startu.
