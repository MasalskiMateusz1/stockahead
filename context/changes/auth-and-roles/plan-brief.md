# Logowanie i role (F-01) — Plan Brief

> Full plan: `context/changes/auth-and-roles/plan.md`

## What & Why

Wdrożenie logowania e-mail+hasło (Spring Security), dwóch ról (kierownik jest nadzbiorem technika) i ekranu `/setup` tworzącego pierwsze konto kierownika. To fundament (`F-01`) z `context/foundation/roadmap.md` — odblokowuje wszystkie 10 pozostałych slice'ów MVP, bo każda akcja w PRD wymaga zalogowanego kierownika lub technika.

## Starting Point

Kod to wyłącznie scaffold Spring Boota — brak encji, migracji, konfiguracji bezpieczeństwa i szablonów. `spring-boot-starter-security` i `thymeleaf-extras-springsecurity6` są już zależnościami w `pom.xml`, nieużywanymi. Za to infrastruktura deploymentu jest już wdrożona i live (`test.regavio.com`) i zawiera decyzję wiążącą dla tego planu: pierwsze konto kierownika ma powstawać przez `/setup` chroniony `STOCKAHEAD_SETUP_TOKEN`, który jest już wpięty w Coolify.

## Desired End State

Kierownik i technik logują się e-mailem i hasłem; niezalogowany trafia na `/login`. Pierwszy kierownik powstaje przez `/setup`, który sam się wyłącza po utworzeniu jednego kierownika. Role egzekwowane są na poziomie metod (`@PreAuthorize`); jedna referencyjna trasa (`/manager/ping`) dowodzi, że wzorzec działa. `/actuator/health/**` zostaje publiczne, więc deploy nie łamie się na healthchecku.

## Key Decisions Made

| Decision | Choice | Why (1 sentence) | Source |
| --- | --- | --- | --- |
| Bootstrap pierwszego kierownika | Ekran `/setup` + `STOCKAHEAD_SETUP_TOKEN` | Już wdrożone w Coolify/CI/.env.example (D10, S3 w `deployment.md`) — nie zaczynaliśmy od zera | Plan (skorygowane po znalezieniu konfliktu z deployment.md) |
| Egzekwowanie ról | Metody (`@PreAuthorize`), nie same reguły URL | Tabela Access Control w PRD jest per-akcja na współdzielonych stronach, nie per-trasa | Plan |
| Strona po zalogowaniu | Minimalna zaślepka pulpitu | Daje konkretny cel do weryfikacji faz, zanim wylądują S-01…S-10 | Plan |
| Testy bezpieczeństwa | Mieszane: Testcontainers dla ścieżek dotykających bazy, szybki test bez bazy dla samego przekierowania | Realna baza tylko tam, gdzie faktycznie potrzebna, zgodnie z kulturą testową repo | Plan |
| Restart/bootstrap | `/setup` wyłącza się trwale po utworzeniu pierwszego kierownika (nie synchronizuje z env przy każdym starcie) | Redeploy (auto po merge) nigdy nie nadpisze cicho zmienionego konta | Plan |
| Flaga `active` na koncie | Dodana już teraz w schemacie i sprawdzana przy logowaniu | Guardrail z FR-002 działa za darmo, gdy wyląduje S-02 (dezaktywacja kont) | Plan |

## Scope

**In scope:**
- Tabela `accounts` (migracja Flyway) + encja + repozytorium
- Konfiguracja Spring Security: formularzowe logowanie, wylogowanie, `@PreAuthorize`
- Ekran `/setup` chroniony tokenem, samo-wyłączający się
- Minimalny pulpit po zalogowaniu + jedna referencyjna trasa chroniona rolą (`/manager/ping`)
- Testy integracyjne (Testcontainers) + jeden szybki test bez bazy

**Out of scope:**
- Zarządzanie kontami techników (create/deactivate) — slice `S-02`
- Reset hasła — PRD to jawnie odrzucił
- Zmiana hasła kierownika przez UI po bootstrapie
- Jakiekolwiek strony biznesowe poza zaślepką i jedną referencyjną trasą

## Architecture / Approach

Dwa nowe pakiety: `pl.regavio.stockahead.account` (encja, rola, repozytorium, `/setup`) i `pl.regavio.stockahead.security` (konfiguracja Spring Security, `UserDetailsService`). Ten podział "feature-first" staje się konwencją dla kolejnych slice'ów roadmapy (`parts`, `projects`, `orders`, `purchasing`).

## Phases at a Glance

| Phase | What it delivers | Key risk |
| --- | --- | --- |
| 1. Konta — schemat i model | Migracja `accounts` + encja + repozytorium | Model musi wspierać `active` od razu, bez drugiej migracji w S-02 |
| 2. Konfiguracja Spring Security | Filtr bezpieczeństwa, kodowanie haseł, ładowanie kont | Kierownik musi dziedziczyć uprawnienia technika — łatwe do przeoczenia |
| 3. Ekran /setup | Formularz pierwszego kierownika chroniony tokenem | Musi być zgodny z już wdrożoną infrastrukturą (D10/S3), nie wymyślony od nowa |
| 4. Logowanie, pulpit, trasa referencyjna | `/login`, zaślepka, `/manager/ping` | Healthcheck `/actuator/health/**` musi zostać publiczny, inaczej każdy deploy pada |
| 5. Testy | Integracyjne (Testcontainers) + szybki test bez bazy | Rozróżnienie, co faktycznie wymaga realnej bazy |

**Prerequisites:** brak — to pierwszy kod w projekcie poza scaffoldem.
**Estimated effort:** brak estymat czasowych (poza zakresem roadmapy/planu) — 5 faz, każda z jasnym kryterium sukcesu.

## Open Risks & Assumptions

- Zakładamy, że `/setup` jest jedynym mechanizmem tworzenia kierownika — jeśli w przyszłości pojawi się potrzeba dodania *drugiego* kierownika, to wymaga osobnej funkcji (poza zakresem F-01 i S-02).
- Wyścig dwóch równoczesnych `POST /setup` jest obsłużony przez ponowne sprawdzenie `existsByRole(MANAGER)` tuż przed zapisem, nie przez blokadę bazodanową — przy jednym operatorze ręcznie klikającym `/setup` raz, ryzyko jest praktycznie zerowe.

## Success Criteria (Summary)

- Kierownik i technik mogą się zalogować i wylogować; niezalogowany zawsze trafia na `/login`.
- Pierwsze konto kierownika powstaje przez `/setup`, który następnie sam się wyłącza.
- `/manager/ping` potwierdza, że `@PreAuthorize` działa i że kierownik ma też uprawnienia technika.
- `./mvnw verify` przechodzi, a deploy (CI → Coolify) nie łamie się na healthchecku.
