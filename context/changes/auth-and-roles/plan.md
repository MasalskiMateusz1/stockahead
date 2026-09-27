# Logowanie i role (F-01) Implementation Plan

## Overview

Wdrożenie logowania e-mail+hasło przez Spring Security z dwiema rolami (kierownik jest nadzbiorem technika), oraz ekranu `/setup` tworzącego pierwsze konto kierownika. To fundament (`F-01` w `context/foundation/roadmap.md`) odblokowujący wszystkie pozostałe slice'y MVP — żadna dalsza funkcja nie jest weryfikowalna bez działającego logowania i ról.

## Current State Analysis

Kod aplikacji to wyłącznie scaffold Spring Boota (`StockaheadApplication.java`) — brak encji, migracji, konfiguracji bezpieczeństwa i szablonów. `spring-boot-starter-security` i `thymeleaf-extras-springsecurity6` są już zależnościami w `pom.xml`, ale nieużywanymi. `application.properties` ma już `server.servlet.session.cookie.secure=true` (sesje, nie tokeny) i `spring.jpa.hibernate.ddl-auto=validate` (schemat musi pochodzić z migracji Flyway, nie z encji).

Infrastruktura deploymentu jest już wdrożona i zawiera decyzje wiążące dla tego planu (`context/foundation/deployment.md`):
- **D10 + S3**: pierwsze konto kierownika powstaje przez ekran `/setup`, chroniony `STOCKAHEAD_SETUP_TOKEN`; ekran wyłącza się sam, gdy istnieje już kierownik. Token jest już wpięty w Coolify (env var aplikacji), `deploy/.env.example` i `deploy/compose.local.yml` (`local-only-setup-token`).
- Ostrzeżenie z runbooka 6.3: gdy funkcja logowania doda własny `SecurityFilterChain`, musi mieć `permitAll()` na `/actuator/health/**` — inaczej healthcheck dostaje 401 i **każdy deploy się wywala**.
- Runbook 6.3 sprawdza, że niezalogowane żądanie z `Accept: text/html` na `/` dostaje `Location: .../login` — czyli `/login` to ścieżka logowania, a przekierowanie niezalogowanego musi tam prowadzić.

### Key Discoveries:

- `pom.xml` — `spring-boot-starter-security`, `thymeleaf-extras-springsecurity6`, `flyway-database-postgresql`, `spring-boot-starter-data-jpa` już obecne; nic więcej do dodania w `pom.xml`.
- `src/test/java/pl/regavio/stockahead/TestcontainersConfiguration.java` — wzorzec integracyjny: `@ServiceConnection` + `PostgreSQLContainer("postgres:18")`, do reużycia w testach tego slice'a.
- `deploy/compose.local.yml` — ustawia `STOCKAHEAD_SETUP_TOKEN=local-only-setup-token` dla lokalnego smoke-testu obrazu; to samo źródło powinno działać jako domyślna wartość dla `./mvnw spring-boot:test-run`.
- `src/main/resources/application-prod.properties` — brak dziś jakiejkolwiek własności `app.*`; trzeba dodać wiązanie `STOCKAHEAD_SETUP_TOKEN` → property.

## Desired End State

Kierownik i technik logują się e-mailem i hasłem; niezalogowany użytkownik trafia na `/login`. Pierwsze konto kierownika powstaje przez `/setup`, wymagający poprawnego `STOCKAHEAD_SETUP_TOKEN`; ekran przestaje działać, gdy w bazie istnieje już jakikolwiek kierownik. Role są egzekwowane na poziomie metod (`@PreAuthorize`) — kierownik automatycznie ma też uprawnienia technika. `/actuator/health/**` pozostaje publiczne, więc deploy nie łamie się na healthchecku. Weryfikacja: `./mvnw verify` przechodzi (w tym nowe testy integracyjne), a ręczne przejście `/setup` → `/login` → pulpit → `/manager/ping` (403 dla technika, 200 dla kierownika) → `/logout` działa w przeglądarce.

## What We're NOT Doing

- Zarządzanie kontami techników (zakładanie/dezaktywacja) — to osobny slice `S-02` (`technician-accounts`).
- Reset hasła — PRD (FR-001, notatka Sokratesa) rozstrzygnął to jawnie: zostaje bez resetu.
- Zmiana hasła kierownika po bootstrapie przez UI — brak takiej funkcji w PRD na tym etapie; dziś to ręczna operacja na bazie.
- Jakiekolwiek strony biznesowe poza minimalnym pulpitem i jedną referencyjną trasą `/manager/ping` — to zakres `S-01`…`S-10`.
- Zarządzanie sesjami poza domyślnym zachowaniem Spring Security (bez "zapamiętaj mnie", bez limitu sesji na użytkownika).

## Implementation Approach

Konta i logika logowania trafiają do pakietu `pl.regavio.stockahead.account` (encja, enum roli, repozytorium, kontroler `/setup`, pulpit po zalogowaniu, referencyjna trasa chroniona rolą), a przekrojowa konfiguracja bezpieczeństwa do `pl.regavio.stockahead.security` (`SecurityConfig`, `AccountUserDetailsService`). To pierwsza funkcja w repo, więc ten podział pakietów "feature-first" (`account`, docelowo `parts`, `projects`, `orders`, `purchasing`) staje się konwencją dla kolejnych 10 slice'ów z roadmapy — żaden kontroler tej funkcji nie ląduje w pakiecie głównym.

## Critical Implementation Details

**Kierownik dziedziczy uprawnienia technika.** `AccountUserDetailsService` musi nadawać kontu z rolą `MANAGER` obie uprawnienia — `ROLE_MANAGER` **i** `ROLE_TECHNICIAN` — nie tylko `ROLE_MANAGER`. Bez tego każde przyszłe `@PreAuthorize("hasRole('TECHNICIAN')")` (np. zgłoszenie zakończenia zlecenia, które wg PRD może zrobić też kierownik) błędnie odrzuci konto kierownika. To wynika wprost z PRD ("Kierownik jest nadzbiorem roli technika"), ale jest łatwe do przeoczenia przy mapowaniu jednej roli encji na jedną rolę Spring Security.

**Healthcheck musi zostać publiczny.** `SecurityFilterChain` musi mieć `permitAll()` na `/actuator/health/**` od pierwszego commita tej funkcji. `context/foundation/deployment.md` (krok 6.3) już to zaznacza jako twarde wymaganie: obraz ma wbudowany `HEALTHCHECK` na `/actuator/health/readiness`, a `serving()` w `.github/workflows/ci.yml` odpytuje ten sam endpoint po każdym deployu z rollbackiem, jeśli dostanie coś innego niż 200. Zablokowanie tego endpointu logowaniem wywala każdy kolejny deploy, nie tylko ten.

## Phase 1: Konta — schemat i model domenowy

### Overview

Migracja Flyway i encja JPA dla kont, z kolumną `active` gotową pod przyszłą dezaktywację (S-02), choć żadne UI jeszcze jej nie ustawia.

### Changes Required:

#### 1. Migracja Flyway

**File**: `src/main/resources/db/migration/V1__create_accounts.sql`

**Intent**: Pierwsza migracja w projekcie — tabela `accounts` z rolą ograniczoną CHECK-iem i domyślnie aktywnym kontem, zgodnie z AGENTS.md ("zmieniaj schemat tylko przez nową migrację Flyway").

**Contract**: Tabela `accounts`: `id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY`, `email VARCHAR(255) NOT NULL UNIQUE`, `password_hash VARCHAR(255) NOT NULL`, `role VARCHAR(20) NOT NULL CHECK (role IN ('MANAGER','TECHNICIAN'))`, `active BOOLEAN NOT NULL DEFAULT TRUE`, `created_at TIMESTAMPTZ NOT NULL DEFAULT now()`.

#### 2. Encja i enum roli

**File**: `src/main/java/pl/regavio/stockahead/account/Role.java`, `src/main/java/pl/regavio/stockahead/account/Account.java`

**Intent**: `Role` to enum `MANAGER`/`TECHNICIAN`. `Account` to encja JPA mapująca 1:1 na tabelę `accounts`, pól bez logiki biznesowej ponad gettery/settery.

**Contract**: `Account` pola: `id: Long`, `email: String`, `passwordHash: String`, `role: Role` (`@Enumerated(EnumType.STRING)`), `active: boolean` (domyślnie `true`), `createdAt: Instant`.

#### 3. Repozytorium

**File**: `src/main/java/pl/regavio/stockahead/account/AccountRepository.java`

**Intent**: Dostęp do kont potrzebny logowaniu (`UserDetailsService`) i bootstrapowi (`/setup`, sprawdzenie czy kierownik już istnieje).

**Contract**: `interface AccountRepository extends JpaRepository<Account, Long>` z `Optional<Account> findByEmail(String email)` i `boolean existsByRole(Role role)`.

### Success Criteria:

#### Automated Verification:

- Migracja aplikuje się czysto: `./mvnw -B verify` (Flyway uruchamia się przeciw Testcontainers Postgres 18)
- Kompilacja przechodzi: `./mvnw -B compile`

#### Manual Verification:

- `flyway_schema_history` zawiera wiersz dla `V1` po starcie aplikacji lokalnie (`./mvnw spring-boot:test-run`)

---

## Phase 2: Konfiguracja Spring Security

### Overview

Filtr bezpieczeństwa, ładowanie użytkownika z bazy, kodowanie haseł i włączenie autoryzacji na poziomie metod — fundament, z którego korzysta reszta roadmapy.

### Changes Required:

#### 1. Ładowanie kont dla Spring Security

**File**: `src/main/java/pl/regavio/stockahead/security/AccountUserDetailsService.java`

**Intent**: Implementacja `UserDetailsService` czytająca konto po e-mailu i mapująca je na `UserDetails` Spring Security.

**Contract**: `loadUserByUsername(email)` rzuca `UsernameNotFoundException`, gdy brak konta; w przeciwnym razie zwraca użytkownika z `enabled = account.isActive()` i uprawnieniami: `MANAGER` → `ROLE_MANAGER` + `ROLE_TECHNICIAN`; `TECHNICIAN` → `ROLE_TECHNICIAN` (patrz „Critical Implementation Details" powyżej).

#### 2. Filtr bezpieczeństwa

**File**: `src/main/java/pl/regavio/stockahead/security/SecurityConfig.java`

**Intent**: Konfiguracja formularzowego logowania, wylogowania, kodera haseł i publicznych ścieżek. `@EnableMethodSecurity` włącza `@PreAuthorize` używane w Fazie 4.

**Contract**: `SecurityFilterChain` — `permitAll()` na `/login`, `/setup/**`, `/actuator/health/**`; wszystko inne wymaga uwierzytelnienia. `formLogin` z `loginPage("/login")` i `defaultSuccessUrl("/", true)`. `logout` z `logoutSuccessUrl("/login?logout")`. Osobny bean `PasswordEncoder` (`BCryptPasswordEncoder`) i `AuthenticationProvider` spinający `AccountUserDetailsService` z tym koderem.

### Success Criteria:

#### Automated Verification:

- `./mvnw -B verify` przechodzi (kontekst Springa ładuje się z nową konfiguracją bezpieczeństwa)
- Test jednostkowy: `AccountUserDetailsService.loadUserByUsername(<e-mail kierownika>).getAuthorities()` zawiera zarówno `ROLE_MANAGER`, jak i `ROLE_TECHNICIAN` (patrz „Critical Implementation Details" — to jedyne miejsce, które to sprawdza automatycznie)

#### Manual Verification:

- Niezalogowane żądanie do `/` w przeglądarce przekierowuje na `/login`
- `curl -sI http://localhost:8080/actuator/health/readiness` zwraca `200` bez logowania

---

## Phase 3: Ekran /setup pierwszego uruchomienia

### Overview

Formularz zakładania pierwszego konta kierownika, chroniony tokenem z env, który sam się wyłącza po utworzeniu kierownika — zgodnie z D10/S3 z `deployment.md`.

### Changes Required:

#### 1. Własność `app.setup-token`

**File**: `src/main/resources/application.properties`

**Intent**: Wiązanie property → env var z domyślną wartością na lokalny dev/test, spójną z `deploy/compose.local.yml`.

**Contract**: `app.setup-token=${STOCKAHEAD_SETUP_TOKEN:local-only-setup-token}` — w produkcji Coolify ustawia `STOCKAHEAD_SETUP_TOKEN` i nadpisuje wartość domyślną.

#### 2. Kontroler /setup

**File**: `src/main/java/pl/regavio/stockahead/account/SetupController.java`

**Intent**: `GET /setup` renderuje formularz (e-mail, hasło, potwierdzenie hasła, token) tylko wtedy, gdy `accountRepository.existsByRole(MANAGER)` jest `false` — w przeciwnym razie przekierowuje na `/login`. `POST /setup` waliduje token stałoczasowym porównaniem, ponownie sprawdza brak kierownika (odporność na wyścig dwóch równoczesnych zgłoszeń), tworzy konto z rolą `MANAGER` i `active=true`, koduje hasło przez wstrzyknięty `PasswordEncoder`, przekierowuje na `/login` z komunikatem sukcesu.

**Contract**: Niepoprawny token lub istniejący już kierownik → formularz ponownie z błędem (token) albo przekierowanie na `/login` (kierownik już istnieje); brak efektu ubocznego w obu przypadkach.

#### 3. Szablon formularza

**File**: `src/main/resources/templates/setup.html`

**Intent**: Minimalny formularz Thymeleaf (e-mail, hasło, potwierdzenie, token) wysyłany na `POST /setup`, z miejscem na komunikat błędu.

**Contract**: `th:action="@{/setup}"` (automatyczny token CSRF przez integrację Thymeleaf-Spring).

### Success Criteria:

#### Automated Verification:

- Test integracyjny: `GET /setup` bez istniejącego kierownika zwraca `200`
- Test integracyjny: `POST /setup` z poprawnym tokenem tworzy konto z `role=MANAGER`, `active=true`
- Test integracyjny: `GET /setup` po utworzeniu kierownika przekierowuje na `/login`
- Test integracyjny: `POST /setup` z błędnym tokenem nie tworzy konta

#### Manual Verification:

- Lokalnie: `docker compose -f deploy/compose.local.yml up`, otworzenie `/setup`, założenie kierownika tokenem `local-only-setup-token`, potwierdzenie że powtórne wejście na `/setup` przekierowuje
- Podgląd źródła renderowanej strony `/setup`: obecne wypełnione pole `<input type="hidden" name="_csrf" value="...">` (nie puste) — Spring Security 6.x domyślnie używa `XorCsrfTokenRequestAttributeHandler`, co przy niektórych konfiguracjach zostawia atrybut `_csrf` niewypełniony dla formularza renderowanego przez GET

---

## Phase 4: Logowanie, pulpit, referencyjna trasa chroniona rolą

### Overview

Strona logowania, minimalna zaślepka po zalogowaniu i jedna trasa chroniona `@PreAuthorize`, która dowodzi wzorca autoryzacji dla przyszłych slice'ów.

### Changes Required:

#### 1. Strona logowania

**File**: `src/main/resources/templates/login.html`, rejestracja widoku w `WebMvcConfigurer` lub prostym `@Controller`

**Intent**: Formularz e-mail+hasło na `POST /login` (przetwarzane przez Spring Security), z komunikatem błędu przy `?error` i komunikatem po wylogowaniu przy `?logout`.

**Contract**: `th:action="@{/login}"`, pola `username`/`password` (domyślne nazwy Spring Security `formLogin`).

#### 2. Pulpit po zalogowaniu

**File**: `src/main/java/pl/regavio/stockahead/account/HomeController.java`, `src/main/resources/templates/dashboard.html`

**Intent**: `GET /` pokazuje "Zalogowano jako `<rola>`" i link wylogowania; sekcja widoczna tylko dla kierownika (`sec:authorize="hasRole('MANAGER')"`) linkuje do `/manager/ping`.

**Contract**: Widok czyta rolę z bieżącego `Authentication`/`Principal`; brak innej logiki biznesowej (to tymczasowa zaślepka, zastępowana przez kolejne slice'y).

#### 3. Referencyjna trasa chroniona rolą

**File**: `src/main/java/pl/regavio/stockahead/account/ManagerPingController.java` (lub metoda w istniejącym kontrolerze)

**Intent**: Jedna trasa istniejąca wyłącznie po to, by udowodnić i przetestować wzorzec `@PreAuthorize` — wzorzec, który Foundation (F-01) ma "odblokować" dla `S-01`…`S-10`.

**Contract**: `GET /manager/ping` z `@PreAuthorize("hasRole('MANAGER')")`; zwraca prosty widok/tekst potwierdzający dostęp.

### Success Criteria:

#### Automated Verification:

- Test integracyjny: logowanie poprawnymi danymi przekierowuje na `/` i uwierzytelnia sesję
- Test integracyjny: logowanie błędnym hasłem przekierowuje na `/login?error`
- Test integracyjny: konto z `active=false` (zasiane bezpośrednio przez `AccountRepository`, bo UI dezaktywacji jeszcze nie istnieje) nie może się zalogować
- Test integracyjny: `/manager/ping` zwraca `200` dla konta `MANAGER` i `403` dla konta `TECHNICIAN`

#### Manual Verification:

- Ręczne przejście w przeglądarce: `/setup` → `/login` → pulpit pokazuje poprawną rolę → `/manager/ping` działa dla kierownika, daje 403 dla technika → `/logout` wraca na `/login`
- Podgląd źródła renderowanej strony `/login`: obecne wypełnione pole `<input type="hidden" name="_csrf" value="...">` (patrz uwaga o `XorCsrfTokenRequestAttributeHandler` w Fazie 3)

---

## Phase 5: Testy

### Overview

Testy integracyjne na realnym Postgresie (Testcontainers, zgodnie z AGENTS.md) dla ścieżek dotykających bazy, plus szybki test samego łańcucha bezpieczeństwa bez bazy.

### Changes Required:

#### 1. Testy integracyjne logowania i bootstrapu

**File**: `src/test/java/pl/regavio/stockahead/account/AuthenticationIntegrationTests.java`

**Intent**: Pokrywa wszystkie scenariusze z Success Criteria Faz 3 i 4 jednym `@SpringBootTest` + `@AutoConfigureMockMvc`, `@Import(TestcontainersConfiguration.class)`, wzorem `StockaheadApplicationTests`.

**Contract**: Każdy test czyści/zakłada stan przez `AccountRepository` bezpośrednio tam, gdzie UI jeszcze nie istnieje (np. konto nieaktywne).

#### 2. Test przekierowania niezalogowanego

**File**: `src/test/java/pl/regavio/stockahead/security/SecurityRedirectTests.java`

**Intent**: Sprawdza, że niezalogowane żądanie do chronionej ścieżki przekierowuje na `/login`, bez uruchamiania Testcontainers/bazy — `UserDetailsService` nigdy nie jest wywoływany dla żądania bez uwierzytelnienia, więc ten test może być szybkim testem samego łańcucha filtrów.

**Contract**: `@WebMvcTest(controllers = HomeController.class)` + `@Import(SecurityConfig.class)` + `@MockBean` na `AccountUserDetailsService` (żeby nie ciągnąć `AccountRepository`/JPA) — `@WebMvcTest` wyklucza auto-konfigurację `DataSource`, więc kontener Postgres nigdy nie jest potrzebny; asercja na `302` + nagłówek `Location` zawierający `/login`.

### Success Criteria:

#### Automated Verification:

- `./mvnw -B verify` — wszystkie testy z Faz 1–5 przechodzą
- Test przekierowania (Faza 5.2) kończy się bez uruchamiania kontenera Postgres

#### Manual Verification:

- (brak dodatkowych — Fazy 3 i 4 już pokryły ręczną weryfikację)

---

## Testing Strategy

### Integration Tests:

- Pełny cykl `/setup` → `/login` → trasa chroniona rolą → `/logout`, na realnym Postgresie przez Testcontainers (zgodnie z konwencją `StockaheadApplicationTests`, rozszerzoną tu poza sam zakres stock/reservation).
- Konto nieaktywne zasiewane bezpośrednio przez repozytorium (nie ma jeszcze UI dezaktywacji — to S-02).

### Manual Testing Steps:

1. `docker compose -f deploy/compose.local.yml up`, otwórz `/setup`, załóż kierownika tokenem `local-only-setup-token`.
2. Zaloguj się, sprawdź pulpit i `/manager/ping`.
3. Załóż drugie konto ręcznie w bazie z rolą `TECHNICIAN`, zaloguj się nim, potwierdź `403` na `/manager/ping`.
4. Sprawdź `curl -sI http://localhost:8080/actuator/health/readiness` bez logowania → `200`.

## Performance Considerations

Brak realnego wymogu wydajnościowego na tym etapie — mały zakład, kilku użytkowników. Domyślny `BCryptPasswordEncoder` i sesje HTTP wystarczają.

## Migration Notes

Pierwsza migracja w projekcie — brak istniejących danych do przeniesienia.

## References

- Roadmap: `context/foundation/roadmap.md` (F-01, `auth-and-roles`)
- PRD: `context/foundation/prd.md` (FR-001, `## Access Control`)
- Deployment runbook: `context/foundation/deployment.md` (D10, S3, krok 6.3)
- Wzorzec testów integracyjnych: `src/test/java/pl/regavio/stockahead/StockaheadApplicationTests.java`

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles. See `references/progress-format.md`.

### Phase 1: Konta — schemat i model domenowy

#### Automated

- [x] 1.1 Migracja aplikuje się czysto: `./mvnw -B verify` — 5002767
- [x] 1.2 Kompilacja przechodzi: `./mvnw -B compile` — 5002767

#### Manual

- [x] 1.3 `flyway_schema_history` zawiera wiersz dla `V1` po starcie lokalnym — 5002767

### Phase 2: Konfiguracja Spring Security

#### Automated

- [x] 2.1 `./mvnw -B verify` przechodzi z nową konfiguracją bezpieczeństwa — 768b3c3
- [x] 2.4 MANAGER ma zarówno `ROLE_MANAGER`, jak i `ROLE_TECHNICIAN` — 768b3c3

#### Manual

- [x] 2.2 Niezalogowane żądanie do `/` przekierowuje na `/login` — 768b3c3
- [x] 2.3 `/actuator/health/readiness` zwraca 200 bez logowania — 768b3c3

### Phase 3: Ekran /setup pierwszego uruchomienia

#### Automated

- [x] 3.1 `GET /setup` bez kierownika zwraca 200
- [x] 3.2 `POST /setup` z poprawnym tokenem tworzy konto MANAGER aktywne
- [x] 3.3 `GET /setup` po utworzeniu kierownika przekierowuje na `/login`
- [x] 3.4 `POST /setup` z błędnym tokenem nie tworzy konta

#### Manual

- [x] 3.5 Lokalny setup przez compose.local.yml działa end-to-end
- [x] 3.6 Renderowany `/setup` zawiera wypełnione pole `_csrf`

### Phase 4: Logowanie, pulpit, referencyjna trasa chroniona rolą

#### Automated

- [ ] 4.1 Logowanie poprawnymi danymi przekierowuje na `/`
- [ ] 4.2 Logowanie błędnym hasłem przekierowuje na `/login?error`
- [ ] 4.3 Konto nieaktywne nie może się zalogować
- [ ] 4.4 `/manager/ping`: 200 dla MANAGER, 403 dla TECHNICIAN

#### Manual

- [ ] 4.5 Pełne ręczne przejście: setup → login → pulpit → manager/ping → logout
- [ ] 4.6 Renderowany `/login` zawiera wypełnione pole `_csrf`

### Phase 5: Testy

#### Automated

- [ ] 5.1 `./mvnw -B verify` — wszystkie testy Faz 1–5 przechodzą
- [ ] 5.2 Test przekierowania niezalogowanego nie wymaga Testcontainers
