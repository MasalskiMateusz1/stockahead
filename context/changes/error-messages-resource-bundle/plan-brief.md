# Wydzielenie komunikatów błędów do resource bundle — Plan Brief

> Full plan: `context/changes/error-messages-resource-bundle/plan.md`

## What & Why

Wydzielenie wszystkich 14 komunikatów błędów/statusu, dziś zaszytych na sztywno po polsku w `SetupController`, `PartController` i `login.html`, do jednego pliku `messages.properties` rozwiązywanego przez Spring `MessageSource`. To ad-hoc zmiana techniczna, poza zakresem PRD i roadmapy — PRD nie zawiera żadnego wymagania o wielojęzyczności; celem jest wyłącznie grunt pod ewentualne wsparcie kolejnego języka w przyszłości, bez dodawania go teraz.

## Starting Point

Wszystkie komunikaty są dziś literałami stringów w kodzie Javy (`SetupController`, `PartController`) lub wprost w szablonie (`login.html`, sterowane parametrami `?error`/`?logout`/`?setup`, bez udziału kontrolera). Żaden wzorzec `MessageSource` nie istnieje jeszcze w repo.

## Desired End State

Każdy z 14 komunikatów renderuje się z dokładnie tą samą polską treścią co dziś, ale źródłem prawdy jest `messages.properties`. Dodanie drugiego języka w przyszłości wymaga tylko nowego pliku `messages_xx.properties` plus osobnego `LocaleResolver` — bez ponownego dotykania kontrolerów czy szablonów.

## Key Decisions Made

| Decision | Choice | Why (1 sentence) | Source |
| --- | --- | --- | --- |
| Zakres napisów | Błędy + komunikaty statusu (14: SetupController×3, PartController×8, login.html×3) | Dokładnie to, o co poprosił użytkownik, plus pokrewne komunikaty statusu z tego samego mechanizmu w login.html | Plan |
| Konwencja kluczy | Kropkowana wg feature: `<feature>.error.<name>` / `<feature>.status.<name>` | Standard w ekosystemie Spring/Thymeleaf, skaluje się na przyszłość | Plan |
| Komunikat z parametrem (duplikat lokalizacji) | Placeholder `MessageSource` `{0}` | Standardowy mechanizm `MessageFormat`, całe zdanie da się przetłumaczyć | Plan |
| Locale teraz | Tylko bundle, bez `LocaleResolver` | PRD nie wspomina o wielojęzyczności — resolver bez drugiego języka byłby spekulacyjną złożonością | Plan |
| Istniejące asercje testowe | Bez zmian — dalej dosłowny tekst | Bundle zawiera bajtowo identyczną treść, więc `.contains("...")` przechodzi bez modyfikacji | Plan |

## Scope

**In scope:**
- `messages.properties` z 14 kluczami (treść identyczna z dzisiejszą).
- `spring.messages.encoding=UTF-8` w `application.properties`.
- `SetupController`, `PartController` — wstrzyknięcie `MessageSource`, zastąpienie literałów.
- `login.html` — trzy `#{key}` zamiast literałów, bez zmian w `HomeController`.
- Nowy test `MessagesBundleTests` potwierdzający kompletność i treść bundle'a.

**Out of scope:**
- `LocaleResolver` / przełącznik języka.
- Drugi plik językowy (`messages_en.properties` itp.).
- Statyczne etykiety/nagłówki/przyciski w którymkolwiek z sześciu szablonów.
- Przepisywanie istniejących asercji testowych na rozwiązywanie przez `MessageSource`.

## Architecture / Approach

Jeden bundle, jeden `MessageSource` (auto-konfigurowany przez Spring Boot z domyślnego basename `messages`). Kontrolery dostają wstrzyknięty `MessageSource` + parametr `Locale` (rozwiązywany automatycznie przez Spring MVC, zero konfiguracji). `parseLocations` w `PartController` też dostaje `MessageSource`/`Locale`, żeby zbudować jedyny komunikat z parametrem tekstowym tak jak dziś. `login.html` omija kontroler całkowicie — Thymeleaf's Spring-integration `#{key}` rozwiązuje klucze wprost.

## Phases at a Glance

| Phase | What it delivers | Key risk |
| --- | --- | --- |
| 1. Resource bundle i wpięcie kontrolerów | `messages.properties` + `SetupController`/`PartController` wstrzykują `MessageSource`; nowy test kompletności bundle'a | Literówka w kluczu lub treści złamałaby istniejące asercje w `PartsCatalogIntegrationTests.java` — `MessagesBundleTests` to wykrywa niezależnie |
| 2. Wpięcie szablonu `login.html` | Trzy statyczne napisy → `#{key}` | Żaden — najprostsza faza, zero zmian w Javie |

**Prerequisites:** Brak — samodzielna zmiana techniczna, nie zależy od żadnego innego change'u.
**Estimated effort:** Mała zmiana, 2 fazy.

## Open Risks & Assumptions

- Zakładamy, że domyślne kodowanie `MessageSource` w tej wersji Spring Boota to UTF-8 (jawnie ustawione w Phase 1 jako zabezpieczenie, nie jako obejście realnego problemu).
- `parseLocations` zmienia sygnaturę (dodaje `MessageSource`/`Locale`) — to jedyna zmiana kontraktu metody prywatnej w tej zmianie; oba miejsca wołające są aktualizowane w tej samej fazie.

## Success Criteria (Summary)

- Wszystkie 14 komunikatów renderuje się z identyczną treścią jak przed zmianą (zero regresji widocznej dla użytkownika).
- `./mvnw verify` przechodzi bez modyfikacji istniejących asercji na dosłowny tekst.
- Nowy `MessagesBundleTests` niezależnie potwierdza kompletność i poprawność bundle'a.
