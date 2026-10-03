---
date: 2026-10-03T16:11:12+02:00
researcher: Claude (Opus 5.5) dla Mateusz Masalski
git_commit: f61cf9d
branch: main
repository: stockahead
topic: "Konfiguracja stylów i użycie klas CSS w Stockahead"
tags: [research, ui, styles, thymeleaf, templates]
status: complete
last_updated: 2026-10-03
last_updated_by: Claude (Opus 5.5)
---

# Research: Konfiguracja stylów i użycie klas CSS w Stockahead

**Date**: 2026-10-03T16:11:12+02:00
**Researcher**: Claude (Opus 5.5) dla Mateusz Masalski
**Git Commit**: f61cf9d (drzewo robocze czyste poza `skills-lock.json` i tym folderem zmiany)
**Branch**: main
**Repository**: stockahead

## Research Question

Przeanalizuj konfigurację stylów i użycie klas w tym projekcie. Wypisz:
1. Dokładną ścieżkę do głównego pliku stylów oraz listę zdefiniowanych zmiennych w `:root` i `.dark`.
2. Zmienne, które są poprawnie publikowane w bloku `@theme` lub `@theme inline`.
3. Listę komponentów obecnych fizycznie w katalogu `src/components/ui`.
4. Zestawienie plików w `src`, które używają twardo zakodowanych klas kolorów (np. `bg-purple-*`, `text-blue-*`) zamiast klas semantycznych (`bg-primary`, `text-muted-foreground`).
5. Brakujące prymitywy UI, które będą niezbędne do wyświetlenia danych w docelowym widoku.

Bez zmian w kodzie.

## Summary

Pytanie zakłada układ Tailwind v4 + shadcn/ui (plik CSS z `:root`/`.dark`, `@theme`, `src/components/ui`). **Ten projekt go nie ma.** Stockahead to Spring Boot 4.1 z widokami Thymeleaf renderowanymi po stronie serwera, bez pipeline'u frontendowego. W zbadanym zakresie (wszystkie pliki śledzone przez git oprócz `context/archive/`, plus `find` w katalogu repo z pominięciem `target/` i `node_modules/`):

1. **Głównego pliku stylów brak.** Nie ma żadnego pliku `*.css` ani katalogu `src/main/resources/static/`. Jedynymi stylami są identyczne bloki `<style>` (linie 6–10) w 14 z 22 szablonów. Każdy zawiera tylko regułę `td, th { text-align: center; }`. Zmiennych `:root` i `.dark`: **0**.
2. **Zmiennych w `@theme` / `@theme inline`: 0.** Tailwind nie jest zainstalowany (brak `package.json`, `tailwind.config.*`, `postcss.config.*`, `components.json`; `pom.xml` nie zawiera webjarów ani pluginu frontendowego).
3. **Katalog `src/components/ui` nie istnieje** (również `src/components`). Komponentów: 0. Thymeleaf nie ma tu fragmentów: 0 trafień dla `th:fragment`, `th:replace`, `th:insert`, `layout:`.
4. **Plików z twardo zakodowanymi klasami kolorów: 0.** W całym `src` nie ma ani jednego atrybutu `class="…"`. Nie ma też literałów kolorów (`#hex`, `rgb`, `hsl`, `color`, `background`, `var(--…)`) w szablonach. Jedyne style inline to 10 wystąpień `style="display:inline"` na formularzach w 5 plikach.
5. **„Docelowy widok” nie jest zdefiniowany** w `change.md` ani w dokumentach `context/foundation/` (zob. Open Questions). Poniżej jest lista prymitywów wynikająca z kształtu danych, które dziś pokazują istniejące widoki.

Wniosek dla planowania: to nie jest „audyt i porządki w istniejącym design systemie”, tylko **wprowadzenie warstwy stylów od zera**. Pierwsza decyzja należy do produktu i stacku: Tailwind (a jeśli tak, to jak budować go bez Node w Maven/CI/Dockerfile), klasyczny CSS z custom properties czy klasowy framework CSS. Do tego dochodzi wybór mechanizmu współdzielenia (fragmenty Thymeleaf / layout).

## Detailed Findings

### 1. Plik stylów, `:root`, `.dark`

- `src/main/resources/static/` nie istnieje (`ls` → „No such file or directory”). `find . -name '*.css'` (z pominięciem `target/`, `node_modules/`) zwraca 0 wyników.
- 14 szablonów ma w `<head>` (linie 6–10) blok `<style>` o tej samej treści:
  `deliveries-new`, `orders-cancel`, `orders-detail`, `orders-list`, `parts-correction`, `parts-import`, `parts-import-preview`, `parts-list`, `picking-detail`, `picking-list`, `project-detail`, `projects-list`, `purchasing-list`, `technicians-list` (wszystkie `src/main/resources/templates/<nazwa>.html:6-10`). Przykład: `src/main/resources/templates/purchasing-list.html:6-10`.
- 8 szablonów nie ma żadnego bloku `<style>`: `dashboard`, `login`, `setup`, `orders-new`, `parts-edit`, `parts-new`, `projects-new`, `technicians-new`.
- Ta duplikacja jest celowa i zapisana jako reguła w `context/foundation/lessons.md` („Center cell content in every table template”): „There is no shared stylesheet… Every template with a `<table>` must include the inline `<style>` block”. Każdy z 14 szablonów z `<table>` ma ten blok.
- W żadnym szablonie nie ma `<link rel="stylesheet">` ani `<script>` (grep `<link|<script` → 0 trafień).

### 2. `@theme` / `@theme inline`

- Brak plików, w których mógłby wystąpić blok `@theme`. Zmiennych opublikowanych poprawnie: 0, błędnie: 0.
- `pom.xml` zawiera tylko startery Spring Boot, Flyway, PostgreSQL, `thymeleaf-extras-springsecurity6` i Testcontainers. Nie ma `frontend-maven-plugin`, webjarów ani Tailwind CLI.

### 3. `src/components/ui`

- `src/components` nie istnieje (`ls` → „No such file or directory”). Kod źródłowy to `src/main/java/pl/regavio/stockahead/**` (Java) i `src/main/resources/templates/*.html` (22 szablony).
- Szablony nie mają mechanizmu ponownego użycia: 0 wystąpień `th:fragment`, `th:replace`, `th:insert`, `layout:`. Nagłówek `<head>`, link „Powrót do pulpitu” i komunikaty błędów są kopiowane w każdym pliku.

### 4. Twardo zakodowane klasy kolorów

- `grep -rnE 'class="|(bg|text|border)-(purple|blue|red|green|gray|slate|zinc|yellow|indigo)-[0-9]' src` → 0 trafień.
- Nie ma też klas semantycznych. Zestawienie „hardcoded vs semantic” jest więc puste po obu stronach.
- Style inline (10 wystąpień, wszystkie `style="display:inline"` na `<form>`, żeby przyciski akcji stały w jednej linii w komórce tabeli):
  - `src/main/resources/templates/picking-detail.html:55`
  - `src/main/resources/templates/orders-detail.html:64`, `:67`
  - `src/main/resources/templates/projects-list.html:45`, `:48`
  - `src/main/resources/templates/parts-list.html:66`, `:69`
  - `src/main/resources/templates/project-detail.html:45`, `:53`, `:88`

### 5. Prymitywy UI wynikające z istniejących danych

Docelowy widok nie jest wskazany (Open Questions). Poniższa lista opiera się na tym, co szablony już renderują gołym HTML-em. To kandydaci na pierwsze prymitywy (np. fragmenty Thymeleaf + klasy CSS) niezależnie od wybranego widoku:

| Prymityw | Dziś renderowane jako | Gdzie (przykłady) |
|---|---|---|
| **Layout / app shell + nawigacja** | lista `<p><a>` na pulpicie; „Powrót do pulpitu” kopiowany w widokach | `dashboard.html:12-23`, `orders-list.html:15` |
| **Tabela danych** (nagłówek, wiersze, wyrównanie liczb, pusty stan) | gołe `<table>`; 15 tabel w 14 plikach (`orders-list.html` ma 2) | `orders-list.html:22-45`, `orders-list.html:53-72`, `purchasing-list.html:21` |
| **Pusty stan** | `<p th:if="${#lists.isEmpty(…)}">Brak …</p>` | `orders-list.html:51`, `purchasing-list.html:19` |
| **Badge statusu / priorytetu** | zwykły tekst: priorytet z `messages.properties`, „Zgłoszone”, „(nieaktywna)”, `<strong>NOWA</strong>` | `orders-list.html:34`, `picking-list.html:38`, `parts-import-preview.html:33-34`, `project-detail.html:17` |
| **Alert błędu** | `<p th:if="${error}" th:text="${error}">` bez wyróżnienia; ta sama postać w 12 plikach | `orders-detail.html:13`, `projects-new.html:10`, `parts-edit.html:10`, `technicians-new.html:9`, `orders-cancel.html:13`, `project-detail.html:13` |
| **Lista błędów walidacji importu** | `<div th:if="${errors}">` + tabela | `parts-import.html:29-43` |
| **Pole formularza** (label + input + błąd) | `<div><label><input>` | `orders-new.html:13-35`, `parts-new.html:13-21`, `login.html:16-20` |
| **Przycisk** (główny / destrukcyjny / inline w tabeli) | `<button>` bez wariantów; dezaktywacja i usuwanie wyglądają jak zapis | `parts-list.html:66-69`, `project-detail.html:53` |
| **Lista definicji / statystyki** (stan / zarezerwowane / dostępne, liczba sztuk, termin) | `<p>Etykieta: <span>` / `<strong>` | `parts-correction.html:16-18`, `orders-detail.html:17-19`, `parts-import-preview.html:45-47` |
| **Pomocniczy tekst** | `<small>` | `parts-edit.html:19` |

Liczba elementów w 22 szablonach (z grep): 85 `<p>`, 44 `<a>`, 38 `<input>`, 38 `<div>`, 35 `<label>`, 35 `<button>`, 34 `<form>`, 24 `<span>`, 22 `<h1>`, 15 `<table>`, 13 `<strong>`, 13 `<h2>`, 5 `<select>`, 4 `<section>`, 3 `<ul>`, 2 `<textarea>`, 2 `<h3>`, 1 `<small>`.

## Code References

- `src/main/resources/templates/purchasing-list.html:6-10` – kanoniczny blok `<style>` (jedyna reguła CSS w projekcie)
- `src/main/resources/templates/dashboard.html:1-29` – „nawigacja” jako lista akapitów z linkami, bez `<style>`
- `src/main/resources/templates/orders-list.html:19-72` – dwie sekcje z tabelami i pustym stanem
- `src/main/resources/templates/parts-list.html:66-69` – akcje w wierszu (`style="display:inline"`)
- `context/foundation/lessons.md` (sekcja „Center cell content in every table template”) – reguła wymagająca kopiowania bloku `<style>`
- `pom.xml` – brak zależności frontendowych

## Architecture Insights

- Warstwa widoku to czysty SSR Thymeleaf: bez JS, bez CSS, bez fragmentów. Każda strona jest samodzielnym plikiem z własnym `<head>`.
- Teksty UI są po polsku. Część z nich pochodzi z `src/main/resources/messages.properties` (np. priorytety), część jest wpisana w szablony.
- Wprowadzenie wspólnego arkusza (np. `src/main/resources/static/css/app.css`, serwowanego przez Spring Boot pod `/css/app.css`) **unieważni regułę z `lessons.md`** o kopiowaniu bloku `<style>`. Plan powinien ją zaktualizować (`/10x-lesson`) i usunąć 14 bloków inline.
- Jeśli wybór padnie na Tailwind: build potrzebuje Node albo samodzielnego Tailwind CLI w Maven, w `Dockerfile` i w `.github/workflows/ci.yml`. To decyzja stackowa. `context/foundation/tech-stack.md` nie wspomina dziś o żadnej warstwie stylów.
- Bezpieczeństwo: `SecurityConfig` wymaga uwierzytelnienia dla wszystkich URL-i. Statyczne zasoby (np. `/css/**`) na stronie logowania mogą wymagać `permitAll`. Do zweryfikowania w planie (nie sprawdzone w tym badaniu).

## Historical Context (from prior changes)

- `context/archive/2026-09-28-parts-catalog/plan.md:19` – „No CSS framework or JavaScript exists in any template… this plan does not introduce either.” Wspiera stan obecny.
- `context/archive/2026-09-28-parts-catalog/plan.md:133` – kontrakt „plain-HTML, no-CSS-framework style”. Świadoma decyzja zakresu MVP, nie przeoczenie.
- `context/archive/2026-09-28-project-bom/plan.md:24` – „Views are plain Thymeleaf with no JavaScript or CSS framework”. Wspiera.
- `context/foundation/roadmap.md:77` – „nie ma katalogu `src/main/resources/templates/`… warstwa widoku nie istnieje”. **Nieaktualne**: katalog ma dziś 22 szablony. Reszta zdania o braku zasobów statycznych **nadal prawdziwa**.
- `context/foundation/lessons.md` – „There is no shared stylesheet” (2026-10-02). **Nadal prawdziwe.**

## Related Research

Nie dotyczy. Brak wcześniejszych `research.md` o UI w `context/changes/**` ani `context/archive/**` (grep `tailwind|stylesheet|css` trafił tylko w dwa powyższe plany).

## Open Questions

1. **Który widok jest „docelowy”?** `change.md` nie ma notatek, a PRD/roadmapa nie definiują zmiany UI. Kandydaci z największą gęstością danych: `orders-list.html` (2 tabele), `purchasing-list.html`, `parts-list.html`, `picking-detail.html`.
2. **Jaka technologia stylów?** Tailwind v4 (+ build w Maven/Docker/CI), czysty CSS z custom properties (bez buildu, zgodny z obecnym brakiem Node) czy lekki framework klasowy. Pytanie zakłada Tailwind/shadcn, ale shadcn/ui to komponenty React i nie da się ich użyć 1:1 w Thymeleaf. Można przenieść jedynie konwencję tokenów (`--primary`, `--muted-foreground`…) do CSS.
3. **Czy tryb ciemny (`.dark`) jest w zakresie?** PRD nic o nim nie mówi.
4. **Mechanizm współdzielenia markupu**: natywne `th:fragment`/`th:replace` czy Thymeleaf Layout Dialect (nowa zależność)?
5. Czy `/css/**` musi być dostępne bez logowania (strona `login.html`)? Do sprawdzenia w `SecurityConfig` podczas planowania.
