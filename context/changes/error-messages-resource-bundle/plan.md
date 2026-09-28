# Wydzielenie komunikatów błędów do resource bundle Implementation Plan

## Overview

Wydzielić 14 komunikatów błędów/statusu, dziś zaszytych na sztywno po polsku w `SetupController`, `PartController` i `login.html`, do jednego pliku `messages.properties` rozwiązywanego przez Spring `MessageSource` — z zachowaniem dzisiejszej dokładnej treści, więc bez zmiany zachowania ani istniejących asercji testowych. To czysty grunt pod przyszłe wsparcie kolejnego języka: bez `LocaleResolver`, bez drugiego pliku językowego, bez ruszania statycznych etykiet/nagłówków/przycisków — ta zmiana jest ad-hoc i techniczna, nie wynika z żadnego FR/NFR w `context/foundation/prd.md` i celowo nie jest slice'em na roadmapie.

## Current State Analysis

Wszystkie komunikaty błędów i statusu są dziś literałami stringów wprost w kodzie Javy lub w szablonach Thymeleaf. `SetupController` i `PartController` przekazują je do modelu jako atrybut `error`, renderowany przez `th:text="${error}"` w `setup.html`/`parts-new.html`/`parts-edit.html`. `login.html` nie ma w ogóle udziału kontrolera w tych trzech napisach — są sterowane obecnością parametrów zapytania (`?error`, `?logout`, `?setup`) i wypisane wprost w szablonie.

### Key Discoveries:

- `SetupController.java:50,56,77` — trzy literały błędów w `createManagerAccount(...)`, żaden nie ma parametru.
- `PartController.java:78,165` — `"Nazwa jest wymagana."` (identyczny tekst w `create()` i `edit()`).
- `PartController.java:81,168` — `"Nazwa może mieć maksymalnie " + MAX_FIELD_LENGTH + " znaków."` (identyczny wzorzec w obu metodach, z parametrem liczbowym `MAX_FIELD_LENGTH`).
- `PartController.java:86,134,175,206` — `"Część o tej nazwie już istnieje."` w czterech miejscach (pre-check i catch `DataIntegrityViolationException` w obu metodach) — to jeden klucz, użyty czterokrotnie.
- `PartController.java:94` — `"Stan magazynowy musi być liczbą całkowitą."`, tylko w `create()` (edycja nie ma pola quantity).
- `PartController.java:98` — `"Stan magazynowy nie może być ujemny."`, tylko w `create()`.
- `PartController.java:110,187` — `"Podaj co najmniej jedną lokalizację."` (identyczny tekst w obu metodach).
- `PartController.java:114,192` — `"Lokalizacja może mieć maksymalnie " + MAX_FIELD_LENGTH + " znaków."` (identyczny wzorzec, z parametrem).
- `PartController.java:242-257` (`parseLocations`) — jedyny komunikat z parametrem tekstowym: `"Lokalizacja „" + trimmed + "” została podana więcej niż raz."`. Metoda jest prywatna i współdzielona przez `create()`/`edit()` przez `catch (IllegalArgumentException ex) { ...ex.getMessage()... }` (`PartController.java:105-107,182-184`) — `parseLocations` musi dostać `MessageSource`/`Locale`, żeby zbudować finalny string tak jak dziś.
- `login.html:10-12` — trzy statyczne `<p>` bez udziału kontrolera; Thymeleaf's Spring-integration `#{key}` rozwiązuje klucze wprost przez ten sam `MessageSource`, bez żadnej zmiany w `HomeController`.
- `application.properties` (3 linie) nie deklaruje żadnego `spring.messages.*` — Spring Boot i tak auto-konfiguruje `MessageSource` z domyślnym basename `messages` (czyli `classpath:messages.properties`), więc wystarczy dodać sam plik; domyślne kodowanie w Spring Boot to już UTF-8, ale zostanie ustawione explicite dla czytelności.
- `PartsCatalogIntegrationTests.java` asertuje cztery z tych literałów dosłownie (np. `.contains("Część o tej nazwie już istnieje.")`) — bundle musi zawierać bajtowo identyczny tekst, żeby te asercje przeszły bez zmian.
- Brak istniejącego wzorca `MessageSource` gdziekolwiek w repo — to pierwsze użycie tego mechanizmu w projekcie.

## Desired End State

Każdy z 14 komunikatów renderuje się z dokładnie tą samą polską treścią co dziś, ale źródłem prawdy jest `src/main/resources/messages.properties`, a nie literał w kodzie. Dodanie drugiego języka w przyszłości będzie wymagało wyłącznie nowego pliku `messages_xx.properties` i (osobno) wpięcia `LocaleResolver` — żadnej ponownej zmiany w kontrolerach czy szablonach.

Weryfikacja: `./mvnw verify` przechodzi bez zmian w istniejących asercjach; nowy test potwierdza, że wszystkie 14 kluczy rozwiązuje się do dzisiejszej treści.

## What We're NOT Doing

- Wpinanie `LocaleResolver` (sesja/cookie) lub jakiegokolwiek mechanizmu przełączania języka — brak realnego drugiego języka czyni to spekulacyjną złożonością.
- Dodawanie `messages_en.properties` ani żadnego innego pliku językowego — tylko domyślny bundle z dzisiejszym polskim tekstem.
- Wydzielanie statycznych etykiet, nagłówków, tekstu przycisków i nagłówków tabel (np. „Zaloguj”, „Nazwa”, „Dodaj część”, „Akcje”) w żadnym z sześciu szablonów — to osobna, dużo większa zmiana bez związku z komunikatami błędów.
- Przepisywanie istniejących asercji w `PartsCatalogIntegrationTests.java` na rozwiązywanie przez `MessageSource` — zostają jako dosłowny tekst, bo bundle zawiera identyczną treść.
- Zmiana `HomeController.java` (etykiety ról "MANAGER"/"TECHNICIAN" nie są komunikatem błędu/statusu w rozumieniu tej zmiany).

## Implementation Approach

Jeden nowy plik `messages.properties` z 14 kluczami w konwencji `<feature>.<error|status>.<camelCase>` (np. `parts.error.duplicateName`). `SetupController` i `PartController` dostają wstrzyknięty `MessageSource` oraz parametr `Locale locale` w metodach, które dziś budują komunikat; `parseLocations` też dostaje `MessageSource`/`Locale`, żeby zbudować komunikat z parametrem tak jak dziś. `login.html` rozwiązuje swoje trzy klucze bezpośrednio przez Thymeleaf `#{key}`, bez zmian w `HomeController`.

## Phase 1: Resource bundle i wpięcie kontrolerów

### Overview

Utworzyć bundle z 14 kluczami, wstrzyknąć `MessageSource` do `SetupController`/`PartController`, zastąpić każdy literał wywołaniem rozwiązującym klucz, dodać test potwierdzający, że każdy klucz rozwiązuje się do dzisiejszej treści.

### Changes Required:

#### 1. Resource bundle

**File**: `src/main/resources/messages.properties`

**Intent**: Jedno źródło prawdy dla wszystkich 14 komunikatów, z treścią bajtowo identyczną do dzisiejszych literałów — żadna istniejąca asercja testowa się nie zmienia.

**Contract**: Klucze i wartości (UTF-8, bez escape'owania unicode — Spring Boot domyślnie czyta bundle jako UTF-8):

```properties
setup.error.invalidToken=Nieprawidłowy token konfiguracyjny.
setup.error.passwordMismatch=Hasła nie są identyczne.
setup.error.accountCreationFailed=Nie udało się założyć konta. Spróbuj ponownie.

parts.error.nameRequired=Nazwa jest wymagana.
parts.error.nameTooLong=Nazwa może mieć maksymalnie {0} znaków.
parts.error.duplicateName=Część o tej nazwie już istnieje.
parts.error.quantityNotInteger=Stan magazynowy musi być liczbą całkowitą.
parts.error.quantityNegative=Stan magazynowy nie może być ujemny.
parts.error.locationsRequired=Podaj co najmniej jedną lokalizację.
parts.error.locationTooLong=Lokalizacja może mieć maksymalnie {0} znaków.
parts.error.locationDuplicate=Lokalizacja „{0}” została podana więcej niż raz.

login.error.badCredentials=Nieprawidłowy e-mail lub hasło.
login.status.loggedOut=Wylogowano.
login.status.managerCreated=Konto kierownika zostało założone. Zaloguj się.
```

#### 2. Konfiguracja kodowania

**File**: `src/main/resources/application.properties`

**Intent**: Ustawić kodowanie bundle'a explicite, zamiast polegać na domyślnej wartości Spring Boota — samodokumentujące się i odporne na przyszłą zmianę defaultu.

**Contract**: Dodać `spring.messages.encoding=UTF-8`.

#### 3. `SetupController` — wstrzyknięcie `MessageSource`

**File**: `src/main/java/pl/regavio/stockahead/account/SetupController.java`

**Intent**: Zastąpić trzy literały błędów (linie 50, 56, 77) wywołaniami `messageSource.getMessage(key, null, locale)`.

**Contract**: Konstruktor przyjmuje dodatkowo `MessageSource messageSource`. `createManagerAccount(...)` przyjmuje dodatkowo parametr `Locale locale` (Spring MVC rozwiązuje go automaticznie, bez żadnej konfiguracji `LocaleResolver`). Klucze: `setup.error.invalidToken`, `setup.error.passwordMismatch`, `setup.error.accountCreationFailed`.

#### 4. `PartController` — wstrzyknięcie `MessageSource`

**File**: `src/main/java/pl/regavio/stockahead/parts/PartController.java`

**Intent**: Zastąpić wszystkie literały błędów w `create()` i `edit()` (linie 78, 81, 86, 94, 98, 110, 114, 134, 165, 168, 175, 187, 192, 206) wywołaniami `messageSource.getMessage(...)`; dla `nameTooLong`/`locationTooLong` przekazać `MAX_FIELD_LENGTH` jako argument `{0}`.

**Contract**: Konstruktor przyjmuje dodatkowo `MessageSource messageSource`. `create(...)` i `edit(...)` przyjmują dodatkowo parametr `Locale locale`. `parseLocations(String rawLocations)` zmienia sygnaturę na `parseLocations(String rawLocations, MessageSource messageSource, Locale locale)` i buduje `IllegalArgumentException` przez `messageSource.getMessage("parts.error.locationDuplicate", new Object[]{trimmed}, locale)` zamiast konkatenacji — obie metody wołające przekazują swój `messageSource`/`locale`. Klucze: `parts.error.nameRequired`, `parts.error.nameTooLong`, `parts.error.duplicateName`, `parts.error.quantityNotInteger`, `parts.error.quantityNegative`, `parts.error.locationsRequired`, `parts.error.locationTooLong`, `parts.error.locationDuplicate`.

#### 5. Test kompletności bundle'a

**File**: `src/test/java/pl/regavio/stockahead/MessagesBundleTests.java`

**Intent**: Zabezpieczyć sam bundle — każdy z 14 kluczy musi się rozwiązać, i to do dokładnie tej treści, którą dziś widzi użytkownik. To jedyny test, który by wykrył literówkę w kluczu albo brakujący wpis.

**Contract**: Zwykły test JUnit 5 (bez `@SpringBootTest`, bez Testcontainers — czysta weryfikacja treści bundle'a, nie potrzebuje kontekstu Springa ani bazy danych). Konstruuje `ResourceBundleMessageSource` z `setBasename("messages")` i `setDefaultEncoding("UTF-8")`, wywołuje `getMessage(key, args, Locale.forLanguageTag("pl"))` dla wszystkich 14 kluczy (parametryzowane wywołania z konkretnym `args` dla `nameTooLong`/`locationTooLong`/`locationDuplicate`) i asertuje dokładną, dzisiejszą polską treść jednym-do-jednego z literałami wymienionymi w `## Current State Analysis`.

### Success Criteria:

#### Automated Verification:

- `./mvnw verify` — pełny zestaw testów przechodzi, w tym nowy `MessagesBundleTests` i niezmienione asercje w `PartsCatalogIntegrationTests.java`.

#### Manual Verification:

- (brak — pokryte w pełni przez testy automatyczne tej fazy)

---

## Phase 2: Wpięcie szablonu `login.html`

### Overview

Zastąpić trzy statyczne napisy w `login.html` wyrażeniami Thymeleaf `#{key}`, rozwiązywanymi bezpośrednio przez ten sam `MessageSource` — bez żadnej zmiany w `HomeController`.

### Changes Required:

#### 1. Szablon logowania

**File**: `src/main/resources/templates/login.html`

**Intent**: Zamienić trzy literały (linie 10-12) na `#{key}`, zachowując istniejące `th:if` na parametrach zapytania.

**Contract**:

```html
<p th:if="${param.error}" th:text="#{login.error.badCredentials}"></p>
<p th:if="${param.logout}" th:text="#{login.status.loggedOut}"></p>
<p th:if="${param.setup}" th:text="#{login.status.managerCreated}"></p>
```

### Success Criteria:

#### Automated Verification:

- `./mvnw verify` — pełny zestaw testów przechodzi.

#### Manual Verification:

- Próba logowania błędnym hasłem pokazuje „Nieprawidłowy e-mail lub hasło.” — identyczny tekst jak przed zmianą.
- Wylogowanie pokazuje „Wylogowano.”.
- Zakończenie `/setup` i przekierowanie na `/login?setup` pokazuje „Konto kierownika zostało założone. Zaloguj się.”.

---

## Testing Strategy

### Unit Tests:

- `MessagesBundleTests` (Phase 1) — kompletność i dokładna treść wszystkich 14 kluczy.

### Integration Tests:

- Istniejące `PartsCatalogIntegrationTests.java` i testy w `pl.regavio.stockahead.account` nie zmieniają się — ich asercje na dosłowny tekst pozostają ważnym, pośrednim potwierdzeniem, że bundle renderuje się poprawnie end-to-end przez prawdziwe żądania HTTP.

### Manual Testing Steps:

1. Błędne hasło przy logowaniu → dokładnie ten sam komunikat co dziś.
2. Wylogowanie → dokładnie ten sam komunikat co dziś.
3. Zakończenie `/setup` → dokładnie ten sam komunikat co dziś.

## Migration Notes

Brak — nowy plik, żadne dane nie migrują.

## References

- `SetupController.java:45-82` — trzy literały błędów.
- `PartController.java:69-211` — osiem kluczy błędów w `create()`/`edit()`.
- `PartController.java:242-257` — `parseLocations`, jedyny komunikat z parametrem tekstowym.
- `login.html:10-12` — trzy statyczne napisy bez udziału kontrolera.
- `PartsCatalogIntegrationTests.java` — cztery istniejące asercje na dosłowny tekst, które muszą przejść bez zmian.
- Lessons: `context/foundation/lessons.md` (brak reguł dotykających tej zmiany bezpośrednio).

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles.

### Phase 1: Resource bundle i wpięcie kontrolerów

#### Automated

- [x] 1.1 `./mvnw verify` — pełny zestaw testów przechodzi, w tym nowy `MessagesBundleTests` — bc4ce31

### Phase 2: Wpięcie szablonu `login.html`

#### Automated

- [x] 2.1 `./mvnw verify` — pełny zestaw testów przechodzi — 46526e2

#### Manual

- [x] 2.2 Błędne hasło przy logowaniu pokazuje identyczny komunikat jak przed zmianą — 46526e2
- [x] 2.3 Wylogowanie pokazuje identyczny komunikat jak przed zmianą — 46526e2
- [x] 2.4 Zakończenie `/setup` pokazuje identyczny komunikat jak przed zmianą — 46526e2
