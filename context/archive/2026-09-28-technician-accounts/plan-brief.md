# Konta techników (S-02) — Plan Brief

> Full plan: `context/changes/technician-accounts/plan.md`

## What & Why

Kierownik potrzebuje założyć konto technika i odebrać mu dostęp bez usuwania danych ani przyszłej historii pracy. To FR-002 i warunek dla S-05, gdzie technik pobiera części do zlecenia.

## Starting Point

F-01 ma tabelę kont, role, logowanie i flagę `active`, ale nie ma zarządzania kontami. Nieaktywne konto nie może się zalogować ponownie; wcześniej otwarta sesja nadal działa. E-mail jest dziś porównywany z uwzględnieniem wielkości liter. S-01 dostarczył kartotekę części i link na pulpicie.

## Desired End State

Kierownik widzi konta techników, tworzy je i dezaktywuje lub reaktywuje. Technik nie zarządza kontami. Dezaktywacja kończy dostęp przy następnym żądaniu, również ze starej sesji; konto i jego `id` pozostają. Warianty wielkości liter tego samego e-maila wskazują jedno konto.

## Key Decisions Made

| Decision | Choice | Why | Source |
| --- | --- | --- | --- |
| Zakres kont | Tworzenie i dezaktywacja techników; bez usuwania | Zachowuje dane i historię | PRD FR-002 |
| Reaktywacja | Kierownik może przywrócić to samo konto | Umożliwia cofnięcie pomyłki bez nowej tożsamości | Plan |
| Pierwsze hasło | Kierownik wpisuje i przekazuje technikowi | Działa bez poczty i zaproszeń | Plan |
| Polityka hasła | Co najmniej 12 znaków, bez reguł składu | Prosta zasada dla nowych kont techników | Plan |
| Dezaktywacja sesji | Brak dostępu od następnego żądania | Otwarta sesja nie omija decyzji kierownika | Plan |
| Tożsamość e-maila | Jedna funkcja `Emails.canonical()` (`strip()` + `toLowerCase(Locale.ROOT)`) przy każdym zapisie (także `/setup`) i wyszukiwaniu; unikalny indeks `lower(btrim(email))` w PostgreSQL rozstrzyga duplikaty | Warianty wielkości liter nie tworzą odrębnych kont | Plan |
| Role | Każda trasa zarządzania tylko dla `MANAGER`; konto tworzone zawsze jako `TECHNICIAN` | Technik nie może podnieść sobie uprawnień ani zmienić kierownika | PRD Access Control / Plan |

## Scope

**In scope:**

- Lista techników, formularz utworzenia, dezaktywacja i reaktywacja.
- Normalizacja e-maili oraz migracja unikalności.
- Zakończenie dostępu aktywnej sesji nieaktywnego konta.
- Testy integracyjne PostgreSQL i krótki przepływ ręczny.

**Out of scope:**

- Usuwanie kont, drugi kierownik, publiczna rejestracja.
- Reset hasła, zaproszenia e-mail, samodzielna zmiana hasła i edycja konta.
- Zmiana reguły hasła istniejącego `/setup` i zmiany w kartotece części.

## Architecture / Approach

S-02 rozszerza pakiet `account` o trasy i widoki kierownika. Migracja Flyway ustala kanoniczny e-mail bez zmiany `id`; repozytorium używa tej samej reguły przy logowaniu i kontroli sesji. Komponent bezpieczeństwa sprawdza `active` przed chronionymi żądaniami i unieważnia sesję nieaktywnego konta. Istniejące `@PreAuthorize` i `PasswordEncoder` pozostają wzorcami.

## Phases at a Glance

| Phase | What it delivers | Key risk |
| --- | --- | --- |
| 1. E-mail | Migracja, unikalność i login bez rozróżnienia wielkości liter | Kolizja starych danych |
| 2. Konta | Lista, tworzenie, dezaktywacja i reaktywacja | Konflikt unikalności i ochrona kierownika |
| 3. Sesje | Odbieranie dostępu przy następnym żądaniu | Żądanie nie może wykonać akcji po dezaktywacji |
| 4. Weryfikacja | Integracja PostgreSQL i przepływ użytkownika | Regresja F-01 lub S-01 |

**Prerequisites:** F-01 i S-01 są ukończone (S-01 zarchiwizowany).

**Estimated effort:** cztery niewielkie fazy; największe ryzyko techniczne leży w migracji e-maili i egzekwowaniu aktywności sesji.

## Open Risks & Assumptions

- Stare e-maile kolidujące po `lower(trim(email))` wymagają ręcznego rozstrzygnięcia przed migracją; plan nie scala kont automatycznie.
- Hasło początkowe przekazuje technikowi kierownik poza aplikacją; zmiana hasła przez użytkownika jest poza tym slice'em.
- Starszy plan kartoteki używa numeru S-02, ale aktualna roadmapa identyfikuje konta jako S-02 przez `technician-accounts`.

## Success Criteria (Summary)

- Kierownik tworzy konto technika z hasłem co najmniej 12 znaków; technik loguje się i nie może otworzyć tras zarządzania.
- Dezaktywacja blokuje nowe logowanie i kończy dostęp starej sesji przy następnym żądaniu; reaktywacja przywraca login tego samego konta.
- E-maile różniące się tylko wielkością liter nie tworzą dwóch kont; `./mvnw verify` przechodzi z testami PostgreSQL.
