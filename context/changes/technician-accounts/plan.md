# Konta techników (S-02) Implementation Plan

## Overview

Kierownik zakłada, dezaktywuje i reaktywuje konta techników. Dezaktywacja zachowuje konto i jego historię oraz odbiera dostęp najpóźniej przy następnym żądaniu. Plan realizuje FR-002 i odblokowuje pracę technika w S-05.

## Current State Analysis

F-01 dostarczył tabelę `accounts`, role, formularz logowania, kodowanie haseł i sprawdzanie `active` przy logowaniu. Nie ma ekranu ani trasy do zarządzania kontami techników. Obecna sesja uwierzytelnionego technika zachowuje dostęp po zmianie `active=false`, a wyszukiwanie e-maila i ograniczenie `UNIQUE` są wrażliwe na wielkość liter. S-01 ma własny pakiet `parts`; wspólne z tym slice'em pozostają tylko pulpit i `messages.properties`.

## Desired End State

Kierownik widzi listę aktywnych i nieaktywnych techników, tworzy konto z e-mailem i hasłem o długości co najmniej 12 znaków oraz może je dezaktywować i reaktywować. Technik nie ma dostępu do tych tras. E-maile różniące się wyłącznie wielkością liter oznaczają jedno konto; deaktywowany technik nie zaloguje się i traci dostęp przy następnym żądaniu z istniejącej sesji. Wiersz konta pozostaje w bazie z tym samym `id`.

### Key Discoveries:

- `src/main/resources/db/migration/V1__create_accounts.sql:1` ma `email UNIQUE`, `active` i rolę ograniczoną do `MANAGER`/`TECHNICIAN`; `V2__enforce_single_manager.sql:1` ogranicza konta kierowników do jednego.
- `src/main/java/pl/regavio/stockahead/security/AccountUserDetailsService.java:26` blokuje nieaktywne konto przy logowaniu, lecz nie odczytuje stanu ponownie podczas sesji.
- `src/main/java/pl/regavio/stockahead/account/ManagerPingController.java:16` oraz `src/main/java/pl/regavio/stockahead/parts/PartController.java:69` pokazują ochronę akcji kierownika przez `@PreAuthorize`.
- `context/foundation/lessons.md` wymaga przyjaznej odpowiedzi na naruszenie unikalności przy zapisie oraz testu 403 dla każdej trasy chronionej rolą.
- `context/foundation/roadmap.md` identyfikuje kartotekę jako S-01 i konta jako S-02. Starszy plan kartoteki używał numeru S-02; identyfikatorem tej zmiany jest zawsze `technician-accounts`.

## What We're NOT Doing

- Usuwanie wierszy kont, tworzenie kolejnego kierownika i publiczna rejestracja.
- Reset hasła, zaproszenia e-mail, samodzielna zmiana hasła i edycja kont techników po utworzeniu.
- Zmiana polityki hasła istniejącego ekranu `/setup`.
- Modyfikacja kartoteki części, rezerwacji lub innych funkcji produkcyjnych.

## Implementation Approach

Rozszerzyć istniejący pakiet `account`: migracja Flyway ustala kanoniczną tożsamość e-maila, repozytorium obsługuje wyszukiwanie bez uwzględniania wielkości liter, a trasy zarządzania korzystają z `@PreAuthorize("hasRole('MANAGER')")`. Utworzenie używa istniejącego `PasswordEncoder`; zapis jest wykonywany i zatwierdzany w granicy, w której można przełożyć konflikt unikalności na komunikat formularza. Osobny komponent bezpieczeństwa sprawdza aktywność konta przy każdym uwierzytelnionym żądaniu i kończy sesję konta nieaktywnego. Nie trzeba zmieniać modelu `Account` ani tabel części.

## Critical Implementation Details

Migracja e-maili musi wykryć kolizje istniejących wartości po `trim` i sprowadzeniu do małych liter przed zmianą danych lub indeksu. Nie wolno automatycznie scalać kont, bo przyszła historia odwołuje się do `id`; przy kolizji migracja ma zatrzymać start z czytelnym błędem i wymagać ręcznego rozstrzygnięcia danych. Lookup logowania i sprawdzanie aktywnej sesji muszą używać tej samej kanonicznej definicji e-maila.

**Jedna kanoniczna definicja e-maila.** W Javie istnieje dokładnie jedna funkcja `Emails.canonical(String)` = `strip()` + `toLowerCase(Locale.ROOT)` (nigdy `trim()` ani `toLowerCase()` bez locale). Używają jej wszystkie zapisy (formularz technika i `/setup` — zmienia się tylko postać zapisanego e-maila, nie polityka hasła `/setup`) oraz wszystkie wyszukiwania (logowanie, kontrola sesji). Dzięki temu wartości w bazie i parametr zapytania są już kanoniczne; wyrażenie SQL `lower(btrim(email))` służy migracji V4 i indeksowi unikalnemu.

Sprawdzenie `active` przy żądaniu obejmuje wszystkie chronione trasy, również strony części dostępne technikowi. Po wykryciu nieaktywnego lub brakującego konta usuwa uwierzytelnienie, unieważnia bieżącą sesję i kieruje przeglądarkę do `/login?deactivated` (własny komunikat, nie „Nieprawidłowy e-mail lub hasło” z `/login?error`); żądanie, które to wykryło, nie wykonuje akcji biznesowej.

**Rejestracja filtra.** `AccountActivityFilter` nie jest `@Component` (Spring Boot zarejestrowałby go drugi raz jako zwykły filtr servletowy, poza łańcuchem bezpieczeństwa i przed wczytaniem `SecurityContext`). Tworzy go `SecurityConfig` i dodaje przez `http.addFilterBefore(filter, AuthorizationFilter.class)`, więc działa po przywróceniu kontekstu z sesji, a przed autoryzacją i kontrolerem. Żądania anonimowe przepuszcza bez odczytu bazy.

**Duplikat e-maila rozstrzyga baza.** Nie ma aplikacyjnego sprawdzenia „e-mail zajęty” przed zapisem. Jedyną regułą jest unikalny indeks z V4; `DataIntegrityViolationException` obsługuje zarówno zwykły duplikat, jak i wyścig dwóch żądań (wzorzec `PartController`, lekcja #1). Tworzone konto ma zawsze rolę `TECHNICIAN`, więc `accounts_single_manager_idx` nie może być źródłem tego wyjątku.

## Phase 1: Tożsamość e-maila i reguła bazy

### Overview

Ujednolicić zapis i wyszukiwanie e-maili oraz zagwarantować unikalność niezależnie od wielkości liter.

### Changes Required:

#### 1. Migracja Flyway

**File**: `src/main/resources/db/migration/V4__normalize_account_emails.sql`

**Intent**: Sprowadzić istniejące e-maile do jednej postaci bez zmiany identyfikatorów kont i zabezpieczyć unikalność na poziomie PostgreSQL.

**Contract**: Przed aktualizacją wykryć kolizje `lower(btrim(email))` w bloku `DO $$ … $$`: jeśli jakakolwiek grupa kanoniczna ma więcej niż jeden wiersz, `RAISE EXCEPTION 'V4: kolizja kanonicznych e-maili: %', <lista kolidujących wartości>` — migracja i start aplikacji zatrzymują się, żaden wiersz nie jest zmieniany. W przeciwnym razie zapisać `lower(btrim(email))` w `accounts.email` i dodać unikalny indeks wyrażeniowy `accounts_email_canonical_idx` na `lower(btrim(email))`. Istniejące ograniczenie `UNIQUE` z V1 pozostaje. Bez scalania lub usuwania kont.

#### 2. Kanoniczny e-mail i wyszukiwanie konta

**File**: `src/main/java/pl/regavio/stockahead/account/Emails.java` (nowy), `src/main/java/pl/regavio/stockahead/account/AccountRepository.java`, `src/main/java/pl/regavio/stockahead/security/AccountUserDetailsService.java`, `src/main/java/pl/regavio/stockahead/account/SetupController.java`

**Intent**: Logowanie przyjmuje e-mail z pominięciem otaczających spacji i wielkości liter, także dla konta kierownika utworzonego wcześniej przez `/setup`; nowe zapisy od razu mają postać kanoniczną.

**Contract**: `Emails.canonical(String)` zgodnie z „Critical Implementation Details”. Nowa metoda `AccountRepository.findByCanonicalEmail(String canonicalEmail)` — natywne zapytanie `WHERE lower(btrim(email)) = :canonicalEmail` (to samo wyrażenie co indeks). `loadUserByUsername` wywołuje `findByCanonicalEmail(Emails.canonical(email))`. `SetupController` zapisuje `Emails.canonical(email)` (linia `account.setEmail(email)`). `findByEmail` zostaje bez zmian — używają go do sprzątania `PartsCatalogIntegrationTests:100-101` i `AuthenticationIntegrationTests:64,88`. Dotychczasowe uwierzytelnienie według hasła, `active` i ról pozostaje zgodne z F-01.

#### 3. Testy fazy

**File**: `src/test/java/pl/regavio/stockahead/account/V4MigrationTests.java` (nowy), `src/test/java/pl/regavio/stockahead/security/AccountUserDetailsServiceTest.java`, `src/test/java/pl/regavio/stockahead/account/AuthenticationIntegrationTests.java`

**Intent**: Udowodnić zachowanie migracji na danych istniejących przed V4 — czego nie da się zrobić w `@SpringBootTest`, bo Flyway stosuje V1–V4 na pustej bazie przy starcie kontekstu.

**Contract**: `V4MigrationTests` bez kontekstu Spring: uruchamia własny `PostgreSQLContainer` (ten sam obraz co w `TestcontainersConfiguration`, żeby nie dzielić bazy z testami kontekstowymi) i steruje Flyway przez API — `migrate()` z `target("3")`, seed wierszy przez JDBC, potem `migrate()` do końca. Przypadki: (a) niekolidujący `" Tech@Example.com "` zachowuje `id` i dostaje `tech@example.com`; (b) `Tech@X.com` + `tech@x.com` → `FlywayException` z komunikatem `V4: kolizja kanonicznych e-maili`, liczba wierszy i ich e-maile bez zmian; (c) po pełnej migracji insert `A@x.com` obok `a@x.com` narusza unikalność. `AccountUserDetailsServiceTest`: stuby `findByEmail` → `findByCanonicalEmail`, plus przypadek wejścia `" Manager@Example.COM "`. `AuthenticationIntegrationTests`: logowanie e-mailem o innej wielkości liter i z otaczającymi spacjami kończy się sukcesem.

### Success Criteria:

#### Automated Verification:

- Migracja na PostgreSQL zachowuje istniejący identyfikator konta (`V4MigrationTests`) i pozwala zalogować się e-mailem o innej wielkości liter (`AuthenticationIntegrationTests`).
- PostgreSQL odrzuca dwa konta z e-mailami różniącymi się tylko wielkością liter lub otaczającymi spacjami (`V4MigrationTests`).
- Migracja wykrywa istniejącą kolizję kanonicznych e-maili bez utraty wierszy (`V4MigrationTests`).
- `./mvnw verify` przechodzi.

#### Manual Verification:

- Kierownik może zalogować się istniejącym e-mailem niezależnie od wielkości liter.

---

## Phase 2: Zarządzanie kontami przez kierownika

### Overview

Udostępnić listę techników, zakładanie kont oraz odwracalne zmiany aktywności bez usuwania danych.

### Changes Required:

#### 1. Trasy i walidacja kont

**File**: `src/main/java/pl/regavio/stockahead/account/TechnicianAccountController.java`, `src/main/java/pl/regavio/stockahead/account/AccountRepository.java`

**Intent**: Kierownik zarządza wyłącznie kontami techników. Formularz odrzuca pusty lub zbyt długi e-mail, niepoprawny format, hasło krótsze niż 12 znaków i różne hasła. E-mail już zajęty (także przez konto nieaktywne lub kierownika) odrzuca baza — patrz §2.

**Contract**: `GET /manager/technicians` zwraca listę wszystkich kont `TECHNICIAN` z ich stanem; `GET /manager/technicians/new` renderuje formularz; `POST /manager/technicians` tworzy aktywne konto z rolą `TECHNICIAN`, e-mailem `Emails.canonical(email)` i hashem BCrypt; `POST /manager/technicians/{id}/deactivate` oraz `/reactivate` zmieniają wyłącznie `active` istniejącego konta technika. Każda metoda ma `@PreAuthorize("hasRole('MANAGER')")`. Id kierownika i nieistniejące id dają tę samą odpowiedź 404 (bez ujawniania, które id należy do kierownika) i nie zmieniają żadnego konta. Powtórzenie akcji aktywności pozostawia docelowy stan bez dodatkowego efektu.

#### 2. Obsługa konfliktu zapisu

**File**: `src/main/java/pl/regavio/stockahead/account/TechnicianAccountController.java`, `src/main/resources/messages.properties`

**Intent**: Pokazać czytelny komunikat o zajętym e-mailu — zarówno przy zwykłym duplikacie, jak i przy równoczesnym utworzeniu konta — bez surowej odpowiedzi 500.

**Contract**: Brak aplikacyjnego sprawdzenia duplikatu przed zapisem (patrz „Critical Implementation Details”). Zapis i flush kończą się przed przechwyceniem `DataIntegrityViolationException`; wyjątek powoduje ponowne wyrenderowanie formularza z zachowanym e-mailem i błędem `technicians.error.duplicateEmail`, bez pokazywania lub logowania hasła. Komunikaty walidacji pochodzą z `MessageSource`.

#### 3. Widoki i nawigacja

**File**: `src/main/resources/templates/technicians-list.html`, `src/main/resources/templates/technicians-new.html`, `src/main/resources/templates/dashboard.html`

**Intent**: Kierownik widzi aktywne i nieaktywne konta, może założyć konto oraz naprawić przypadkową dezaktywację. Link na pulpicie jest widoczny tylko dla kierownika.

**Contract**: Lista pokazuje e-mail, status i akcję `Dezaktywuj` albo `Reaktywuj`; formularze POST zawierają CSRF. Nowy formularz ma e-mail, hasło i potwierdzenie hasła; po błędzie hasła pozostają puste. Pulpit zachowuje istniejący link do kartoteki S-01 (`/parts`) i dopisuje link do `/manager/technicians` w bloku `sec:authorize="hasRole('MANAGER')"`; `messages.properties` dostaje klucze `technicians.*` bez usuwania istniejących.

#### 4. Testy fazy

**File**: `src/test/java/pl/regavio/stockahead/account/TechnicianAccountIntegrationTests.java` (nowy)

**Intent**: Utrwalić utworzenie, walidację, konflikt unikalności, ochronę ról i zachowanie danych przy dezaktywacji/reaktywacji w rzeczywistym przepływie HTTP.

**Contract**: `@SpringBootTest`, `@AutoConfigureMockMvc` i `@Import(TestcontainersConfiguration.class)` przeciw PostgreSQL; logowanie sesyjne i CSRF jak w `PartsCatalogIntegrationTests`. **Bez `@Transactional`** (klasy i metod): fixture'y są zatwierdzane, a po każdym żądaniu konto odczytuje się ponownie z repozytorium. Sprzątanie `@BeforeEach` i `@AfterEach` usuwa konta tej klasy (wzorzec `PartsCatalogIntegrationTests:100`), a e-mail kierownika jest unikalny dla klasy — pozostawiony kierownik złamałby `accounts_single_manager_idx` i `AuthenticationIntegrationTests.setupFormIsAvailableWhenNoManagerExists`. Konflikt unikalności testuje się sekwencyjnie: drugie utworzenie z wariantem wielkości liter istniejącego e-maila trafia w ograniczenie bazy.

### Success Criteria:

#### Automated Verification:

- Kierownik tworzy aktywne konto `TECHNICIAN`; zapisany hash różni się od hasła, a nowe konto może się zalogować.
- Formularz odrzuca hasło krótsze niż 12 znaków i niezgodne potwierdzenie bez utworzenia konta.
- Kanonicznie zajęty e-mail (wariant wielkości liter lub spacji, także konta nieaktywnego i kierownika) trafia w ograniczenie bazy i wraca jako błąd formularza z zachowanym e-mailem, bez odpowiedzi 500 i bez drugiego konta.
- Każda trasa zarządzania zwraca 403 dla zalogowanego technika; id kierownika i nieistniejące id zwracają 404 i nie zmieniają żadnego konta.
- Dezaktywacja i reaktywacja zachowują to samo `id`, e-mail, hash, rolę i `created_at`; nie ma `DELETE`.
- `./mvnw verify` przechodzi.

#### Manual Verification:

- Kierownik widzi na pulpicie link do kont techników, listę obu stanów i działające formularze; technik nie widzi linku.

---

## Phase 3: Dezaktywacja aktywnej sesji

### Overview

Sprawić, by zmiana `active=false` odebrała dostęp także technikowi, który zalogował się wcześniej.

### Changes Required:

#### 1. Kontrola bieżącego uwierzytelnienia

**File**: `src/main/java/pl/regavio/stockahead/security/AccountActivityFilter.java`, `src/main/java/pl/regavio/stockahead/security/SecurityConfig.java`, `src/main/resources/templates/login.html`, `src/main/resources/messages.properties`

**Intent**: Sprawdzić aktywność konta przed wykonaniem każdego uwierzytelnionego żądania. Publiczne logowanie, setup i healthcheck zachowują dotychczasową dostępność.

**Contract**: Filtr rejestrowany zgodnie z „Critical Implementation Details” (nie `@Component`; `http.addFilterBefore(filter, AuthorizationFilter.class)` w `SecurityConfig`; żądania anonimowe przechodzą bez odczytu bazy). Dla uwierzytelnionego użytkownika odczytać konto przez `findByCanonicalEmail(Emails.canonical(name))`; `active=false` albo brak konta czyści `SecurityContext`, unieważnia sesję i przekierowuje do `/login?deactivated`, bez przekazania żądania dalej. `login.html` pokazuje dla `param.deactivated` komunikat `login.status.deactivated`. Aktywne konto przechodzi normalnie; kontrola nie zmienia mapowania ról. Reaktywacja pozwala zalogować się ponownie, ale nie wskrzesza starej sesji.

#### 2. Testy fazy

**File**: `src/test/java/pl/regavio/stockahead/account/TechnicianAccountIntegrationTests.java`

**Intent**: Dopisać przypadki sesji do klasy z Fazy 2 (ta sama izolacja: bez `@Transactional`, sprzątanie przed i po teście).

**Contract**: Sesja technika (`MockHttpSession` z logowania) → dezaktywacja przez kierownika → następny GET `/parts` i POST na trasę zapisu kończą się przekierowaniem do `/login?deactivated`, a stan bazy się nie zmienia; reaktywacja → stara sesja nadal przekierowuje, nowe logowanie działa.

### Success Criteria:

#### Automated Verification:

- Technik zalogowany przed dezaktywacją traci dostęp przy następnym GET oraz POST; te żądania nie wykonują akcji biznesowych.
- Aktywny kierownik i technik zachowują dostęp do swoich tras, a `/login` i `/actuator/health/readiness` pozostają publiczne.
- Po reaktywacji technik może utworzyć nową sesję; stara sesja nie odzyskuje dostępu.
- `./mvnw verify` przechodzi.

#### Manual Verification:

- W dwóch przeglądarkach dezaktywuj zalogowanego technika i sprawdź, że jego następna próba wejścia na `/parts` kończy się ekranem logowania.

---

## Phase 4: Weryfikacja przepływu i integracji

### Overview

Domknąć scenariusz FR-002 na prawdziwym PostgreSQL i sprawdzić styki z F-01 oraz S-01. Testy powstały w Fazach 1–3; ta faza jest przeglądem pokrycia i pełną regresją.

### Changes Required:

#### 1. Przegląd pokrycia testów

**File**: `src/test/java/pl/regavio/stockahead/account/TechnicianAccountIntegrationTests.java` (tylko uzupełnienia, jeśli czegoś brakuje)

**Intent**: Potwierdzić, że każda z pięciu tras zarządzania ma test 403 dla technika (lekcja #2) i że zachowanie danych konta po dezaktywacji/reaktywacji jest sprawdzane odczytem z bazy.

**Contract**: Brak nowego kodu produkcyjnego. Brakujące przypadki dopisać w istniejącej klasie, z tą samą izolacją co w Fazie 2.

### Success Criteria:

#### Automated Verification:

- `./mvnw verify` przechodzi z testami integracyjnymi PostgreSQL, w tym testami F-01 i S-01.
- Testy potwierdzają 403 na każdej trasie kierownika oraz zachowanie danych konta po dezaktywacji i reaktywacji.

#### Manual Verification:

- Przejście kierownik tworzy technika → technik loguje się → kierownik dezaktywuje → następne żądanie technika kończy sesję → ponowny login jest odrzucony → kierownik reaktywuje → technik loguje się ponownie działa.

---

## Testing Strategy

### Unit Tests:

- Tylko wyodrębniona logika kanonizacji lub walidacji, jeżeli implementacja utworzy samodzielny komponent. Główne niezmienniki wymagają PostgreSQL i pełnego łańcucha bezpieczeństwa.

### Integration Tests:

- `V4MigrationTests` (Faza 1): migracja i indeks unikalny na PostgreSQL przez API Flyway, łącznie z kolizją wariantów e-maila.
- `TechnicianAccountIntegrationTests` (Fazy 2–3, bez `@Transactional`, sprzątanie przed i po teście): formularz utworzenia, konflikt unikalności z bazy, ochrona ról, 404 dla konta kierownika i nieistniejącego id, dezaktywacja i reaktywacja przy sesjach HTTP.
- Regresja logowania F-01 (w tym logowanie bez uwzględniania wielkości liter w `AuthenticationIntegrationTests`), dostępu do `/parts` i publicznego healthchecku.

### Manual Testing Steps:

1. Zaloguj kierownika, sprawdź listę i załóż technika z hasłem co najmniej 12 znaków.
2. Zaloguj technika w osobnej sesji i sprawdź dostęp do `/parts` oraz brak dostępu do `/manager/technicians`.
3. Dezaktywuj technika; w jego sesji ponów żądanie, następnie spróbuj zalogować się ponownie.
4. Reaktywuj to samo konto; sprawdź nowy login i zachowany e-mail/status na liście.

## Performance Considerations

Przy kilku użytkownikach dodatkowy odczyt konta dla uwierzytelnionego żądania jest akceptowalny. Odczyt korzysta z unikalnego indeksu e-maila. Nie stosować cache aktywności, który opóźniłby wyegzekwowanie dezaktywacji.

## Migration Notes

Flyway dodaje tylko V4; wcześniejszych migracji nie zmieniać. W razie istniejących e-maili kolidujących po `lower(btrim(email))`, migracja ma zatrzymać wdrożenie z czytelną diagnozą; operator rozstrzyga konflikt danych przed ponowieniem. Nie scalać ani usuwać kont automatycznie. Po wdrożeniu login przyjmuje warianty wielkości liter, a istniejące hashe i `id` pozostają bez zmian.

## References

- `context/foundation/prd.md` — FR-002 i Access Control.
- `context/foundation/roadmap.md` — S-02 `technician-accounts`, zależność F-01, równoległość z S-01.
- `context/foundation/lessons.md` — obsługa konfliktu unikalności i test 403 dla każdej trasy rolą.
- `src/main/java/pl/regavio/stockahead/account/SetupController.java:50` — istniejący wzorzec tworzenia konta i kodowania hasła.
- `src/main/java/pl/regavio/stockahead/parts/PartController.java:69` — wzorzec manager-only i obsługi konfliktu bazy.
- `src/test/java/pl/regavio/stockahead/parts/PartsCatalogIntegrationTests.java:1` — wzorzec integracyjny PostgreSQL oraz sesji HTTP.

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles. See `references/progress-format.md`.

### Phase 1: Tożsamość e-maila i reguła bazy

#### Automated

- [x] 1.1 Migracja na PostgreSQL zachowuje istniejący identyfikator konta (`V4MigrationTests`) i pozwala zalogować się e-mailem o innej wielkości liter (`AuthenticationIntegrationTests`).
- [x] 1.2 PostgreSQL odrzuca dwa konta z e-mailami różniącymi się tylko wielkością liter lub otaczającymi spacjami (`V4MigrationTests`).
- [x] 1.3 Migracja wykrywa istniejącą kolizję kanonicznych e-maili bez utraty wierszy (`V4MigrationTests`).
- [x] 1.4 `./mvnw verify` przechodzi.

#### Manual

- [x] 1.5 Kierownik może zalogować się istniejącym e-mailem niezależnie od wielkości liter.

### Phase 2: Zarządzanie kontami przez kierownika

#### Automated

- [ ] 2.1 Kierownik tworzy aktywne konto `TECHNICIAN`; zapisany hash różni się od hasła, a nowe konto może się zalogować.
- [ ] 2.2 Formularz odrzuca hasło krótsze niż 12 znaków i niezgodne potwierdzenie bez utworzenia konta.
- [ ] 2.3 Kanonicznie zajęty e-mail (wariant wielkości liter lub spacji, także konta nieaktywnego i kierownika) trafia w ograniczenie bazy i wraca jako błąd formularza z zachowanym e-mailem, bez odpowiedzi 500 i bez drugiego konta.
- [ ] 2.4 Każda trasa zarządzania zwraca 403 dla zalogowanego technika; id kierownika i nieistniejące id zwracają 404 i nie zmieniają żadnego konta.
- [ ] 2.5 Dezaktywacja i reaktywacja zachowują to samo `id`, e-mail, hash, rolę i `created_at`; nie ma `DELETE`.
- [ ] 2.6 `./mvnw verify` przechodzi.

#### Manual

- [ ] 2.7 Kierownik widzi na pulpicie link do kont techników, listę obu stanów i działające formularze; technik nie widzi linku.

### Phase 3: Dezaktywacja aktywnej sesji

#### Automated

- [ ] 3.1 Technik zalogowany przed dezaktywacją traci dostęp przy następnym GET oraz POST; te żądania nie wykonują akcji biznesowych.
- [ ] 3.2 Aktywny kierownik i technik zachowują dostęp do swoich tras, a `/login` i `/actuator/health/readiness` pozostają publiczne.
- [ ] 3.3 Po reaktywacji technik może utworzyć nową sesję; stara sesja nie odzyskuje dostępu.
- [ ] 3.4 `./mvnw verify` przechodzi.

#### Manual

- [ ] 3.5 W dwóch przeglądarkach dezaktywuj zalogowanego technika i sprawdź, że jego następna próba wejścia na `/parts` kończy się ekranem logowania.

### Phase 4: Weryfikacja przepływu i integracji

#### Automated

- [ ] 4.1 `./mvnw verify` przechodzi z testami integracyjnymi PostgreSQL, w tym testami F-01 i S-01.
- [ ] 4.2 Testy potwierdzają 403 na każdej trasie kierownika oraz zachowanie danych konta po dezaktywacji i reaktywacji.

#### Manual

- [ ] 4.3 Przejście kierownik tworzy technika → technik loguje się → kierownik dezaktywuje → następne żądanie technika kończy sesję → ponowny login jest odrzucony → kierownik reaktywuje → technik loguje się ponownie działa.
