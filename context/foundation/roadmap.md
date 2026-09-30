---
project: "Stockahead"
version: 1
status: draft
created: 2026-09-21
updated: 2026-09-30

prd_version: 1
main_goal: speed
top_blocker: capacity
milestone_id: mvp-production-loop
milestone_seq: 1
milestone_status: open
---

# Roadmap: Stockahead

> Derived from `context/foundation/prd.md` (v1) + auto-researched codebase baseline (2026-09-21).
> Edit-in-place; archive when superseded.
> Slices below are listed in dependency order. The "At a glance" table is the index.

## Milestone

**M-1: Zakład prowadzi pełny cykl zlecenia w systemie** — Status: open

- **Intent:** Kierownik zleca produkcję według projektu, system rezerwuje części regułą priorytetu, technik pobiera je z podanych lokalizacji, a braki wychodzą na liście zakupów do wyeksportowania — wszystko na kontach z rolami, na danych, które da się wprowadzić i skorygować.
- **Source materials:** `context/foundation/prd.md` (v1)
- **Done when:** każdy F-NN i S-NN poniżej ma status `done`, czyli wszystkie 18 wymagań koniecznych PRD jest dowiezione.
- **Scope anchors:** FR-001, FR-002, FR-003, FR-004, FR-005, FR-007, FR-010, FR-011, FR-012, FR-013, FR-014, FR-015, FR-016, FR-017, FR-018, FR-019, FR-020, FR-021, US-01.

## Vision recap

W małym zakładzie produkującym elektronikę stan magazynu części istnieje wyłącznie w pamięci pracowników: przy każdym projekcie ktoś szuka części po całym magazynie, a braki wychodzą dopiero w trakcie kompletowania. Stockahead ma wyjąć te dane z głowy i zamienić je w dwie odpowiedzi: „czy części są i gdzie leżą" oraz „co zamówić". Problem jest w zakładzie znany od dawna — nikt nie miał czasu go rozwiązać.

Rdzeń produktu — ta jedna własność, bez której aplikacja byłaby zwykłą kartoteką części — to reguła przydziału: dostępne sztuki są rozdzielane między otwarte zlecenia według priorytetu, a to, czego brakuje, samo trafia na listę zakupów.

## North star

**S-04: Kierownik zleca produkcję i widzi rezerwacje oraz braki** — pierwszy kawałek pracy, który uruchamia regułę przydziału z §Business Logic end-to-end, więc dopiero on pokazuje, czy produkt w ogóle działa.

> Gwiazda przewodnia (ang. *north star*) to tutaj najmniejszy przepływ od danych do ekranu, którego udane dowiezienie dowodzi, że główna hipoteza produktu jest prawdziwa — ustawiony tak wcześnie, jak pozwalają zależności, bo reszta roadmapy ma znaczenie tylko wtedy, gdy ten kawałek zadziała.

## At a glance

| ID    | Change ID                        | Outcome (user can …)                                                                          | Prerequisites | PRD refs                                    | Status   |
| ----- | -------------------------------- | --------------------------------------------------------------------------------------------- | ------------- | ------------------------------------------- | -------- |
| F-01  | migration-and-invariant-harness  | (fundament) migracje Flyway działają w aplikacji i w teście, jest wzorzec testu współbieżnego  | —             | §Wymagania niefunkcjonalne, §Business Logic | ready    |
| S-01  | login-and-role-access            | Użytkownik loguje się e-mailem i hasłem, a dostęp zależy od roli                                | F-01          | FR-001, §Kontrola dostępu                   | done     |
| S-02  | parts-catalog                    | Kierownik prowadzi kartotekę części z lokalizacjami, każdy ją przeszukuje                       | S-01          | FR-003, FR-005                              | done |
| S-03  | projects-and-bom                 | Kierownik prowadzi projekty urządzeń z listą części i linkami do dokumentacji                   | S-01          | FR-007, FR-019                              | proposed |
| S-04  | order-reserves-parts             | Kierownik zleca produkcję N sztuk i widzi rezerwacje oraz braki                                 | S-02, S-03    | US-01, FR-010, FR-005, §Business Logic      | proposed |
| S-05  | shopping-list-and-csv-export     | Kierownik widzi listę zakupów z blokowanymi zleceniami i eksportuje ją do CSV                   | S-04          | FR-015, FR-016                              | proposed |
| S-06  | technician-accounts              | Kierownik zakłada i dezaktywuje konta techników                                                 | S-01          | FR-002, §Kontrola dostępu                   | done     |
| S-07  | picking-list-and-pick            | Technik widzi listę zleceń z lokalizacjami i pobiera części, także częściowo                    | S-04, S-06    | FR-012, FR-013, §Business Logic             | proposed |
| S-08  | order-completion                 | Technik zgłasza zakończenie zlecenia, a kierownik je potwierdza                                 | S-07          | FR-014, FR-020                              | proposed |
| S-09  | order-change-and-cancel          | Kierownik zmienia priorytet i termin niepodjętego zlecenia albo je anuluje                      | S-07          | FR-021, FR-011                              | proposed |
| S-10  | delivery-receipt                 | Technik przyjmuje dostawę, a rezerwacje i lista zakupów przeliczają się same                    | S-02, S-04    | FR-004                                      | proposed |
| S-11  | stock-correction                 | Kierownik koryguje stan części, podając powód                                                   | S-02, S-04    | FR-018                                      | proposed |
| S-12  | parts-csv-import                 | Kierownik importuje części z CSV po obejrzeniu podglądu stanów wynikowych                       | S-02, S-04    | FR-017                                      | proposed |

## Streams

Navigation aid — groups items that share a Prerequisites chain. Canonical ordering still lives in the dependency graph below; this table is the proposed reading order across parallel tracks.

| Stream | Theme                             | Chain                                                   | Note                                                                                                  |
| ------ | --------------------------------- | ------------------------------------------------------- | ----------------------------------------------------------------------------------------------------- |
| A      | Ścieżka główna do listy zakupów   | `F-01` → `S-01` → `S-02` → `S-03` → `S-04` → `S-05`     | Realizuje Kryterium sukcesu (Primary) w całości; przy celu `speed` ma pierwszeństwo przed B i C.       |
| B      | Kompletacja i cykl życia zlecenia | `S-06` → `S-07` → `S-08` → `S-09`                       | Dołącza do strumienia A przy `S-01` (konta) i przy `S-04` (zlecenia z rezerwacjami).                   |
| C      | Ruch magazynu                     | `S-10` → `S-11` → `S-12`                                | Dołącza do strumienia A przy `S-04`; trzy niezależne wyzwalacze przeliczenia, dobre do zrównoleglenia. |

## Baseline

What's already in place in the codebase as of `2026-09-21` (auto-researched + user-confirmed).
Foundations below assume these are present and do NOT re-scaffold them.

- **Frontend:** partial — Thymeleaf i `thymeleaf-extras-springsecurity6` są w `pom.xml`, ale nie ma katalogu `src/main/resources/templates/` ani żadnych zasobów statycznych; warstwa widoku nie istnieje.
- **Backend / API:** partial — `spring-boot-starter-webmvc` jest podpięty, ale jedyną klasą Javy jest `src/main/java/pl/regavio/stockahead/StockaheadApplication.java`; brak kontrolerów i serwisów.
- **Data:** partial — JPA, Flyway i sterownik PostgreSQL są podpięte, `src/main/resources/application.properties:3` ustawia `ddl-auto=validate`, a `src/main/resources/db/migration/` istnieje, ale jest **pusty**. Brak encji i schematu. Działa za to harness testowy na realnym Postgresie (`src/test/java/pl/regavio/stockahead/TestcontainersConfiguration.java`).
- **Auth:** partial — `spring-boot-starter-security` jest w `pom.xml` (więc działa domyślny formularz z generowanym hasłem), ale nie ma konfiguracji bezpieczeństwa, encji użytkownika, ról ani zasianego pierwszego kierownika.
- **Deploy / infra:** **present** — `Dockerfile`, `deploy/compose.local.yml`, `src/main/resources/application-prod.properties` oraz `.github/workflows/ci.yml` z pełnym łańcuchem verify → obraz w GHCR → deploy na Coolify z weryfikacją, że aplikacja serwuje, i automatycznym rollbackiem do ostatniego działającego tagu. Runbook: `context/foundation/deployment.md`; wybór platformy: `context/foundation/infrastructure.md`.
- **Observability:** partial — actuator wystawia wyłącznie `health` z sondami (`src/main/resources/application.properties:5-6`), co obsługuje bramkę deployu. Brak metryk, śledzenia błędów i logowania strukturalnego.

## Foundations

### F-01: Ścieżka migracji i wzorzec testu niezmienników

- **Outcome:** (fundament) pierwsza migracja Flyway wykonuje się przy starcie aplikacji i w teście na Testcontainers przy `ddl-auto=validate`, a w repozytorium istnieje wzorzec testu integracyjnego potwierdzający ograniczenie bazodanowe przy dwóch równoczesnych transakcjach.
- **Change ID:** migration-and-invariant-harness
- **PRD refs:** §Wymagania niefunkcjonalne (stan nigdy poniżej zera, brak podwójnej rezerwacji przy równoczesnej pracy), §Business Logic
- **Unlocks:** `S-01` (pierwsza tabela w ogóle), `S-02` (ograniczenie stanu części), `S-04` (ścieżka weryfikacji gwarancji rezerwacji); zmniejsza niewiadomą „czy blokada wiersza wystarcza, gdy po każdym zdarzeniu przeliczana jest cała alokacja".
- **Prerequisites:** —
- **Parallel with:** —
- **Blockers:** —
- **Unknowns:** —
- **Risk:** Katalog migracji jest pusty, a `ddl-auto=validate` sprawia, że aplikacja nie wstanie z żadną encją — bez tego kroku pierwszy kawałek pracy zatrzymuje się na starcie, a gwarancji z §Wymagań niefunkcjonalnych nie ma czym udowodnić. Ryzyko przeciwne to rozdęcie fundamentu do projektowania całego schematu: zakres kończy się na ścieżce migracji i wzorcu testu, a każdy kawałek pracy dokłada własną migrację.
- **Status:** ready

## Slices

### S-01: Logowanie i dostęp zależny od roli

- **Outcome:** Użytkownik loguje się e-mailem i hasłem, niezalogowany trafia na ekran logowania, a zakres tego, co widzi, wynika z roli — przy czym pierwsze konto kierownika powstaje samo przy uruchomieniu systemu.
- **Change ID:** login-and-role-access
- **PRD refs:** FR-001, §Kontrola dostępu
- **Prerequisites:** F-01
- **Parallel with:** —
- **Blockers:** —
- **Unknowns:** —
- **Risk:** Sekwencjonowane pierwsze, bo §Kontrola dostępu bramkuje każde inne wymaganie, a rola decyduje o kształcie każdego późniejszego ekranu. Wpadka do uniknięcia: rozszerzenie tego kawałka o zarządzanie kontami (FR-002) — zasiany kierownik wystarcza, żeby ruszyć dalej, a konta techników mają własny kawałek S-06.
- **Status:** done — dostarczone jako `auth-and-roles` (zaplanowane i wdrożone przed wygenerowaniem tej wersji roadmapy, pod poprzednim ID F-01 z usuniętej roadmapy `core-mvp-loop`); zarchiwizowane pod `context/archive/2026-09-26-auth-and-roles/`. Zakres pokrywa się z tym slice'em 1:1: logowanie e-mail+hasło, `@PreAuthorize` per rola, kierownik jako nadzbiór technika, pierwszy kierownik przez `/setup` + `STOCKAHEAD_SETUP_TOKEN`. Zarządzanie kontami techników pozostaje w S-06, zgodnie z notatką zamykającą tamten change.

### S-02: Kartoteka części z lokalizacjami

- **Outcome:** Kierownik dodaje, edytuje i usuwa części wraz z jedną lub kilkoma lokalizacjami, a każdy zalogowany użytkownik wyszukuje część i widzi jej stan oraz lokalizację.
- **Change ID:** parts-catalog
- **PRD refs:** FR-003, FR-005, §Wymagania niefunkcjonalne (stan nigdy poniżej zera)
- **Prerequisites:** S-01
- **Parallel with:** S-03, S-06
- **Blockers:** —
- **Unknowns:** —
- **Risk:** Tu powstaje tabela części, więc tu ląduje ograniczenie bazodanowe na stan nieujemny i wzorzec blokowania wiersza z F-01 — odłożenie tego do kawałka z rezerwacjami oznaczałoby migrację korygującą dane, które już są w zakładzie. Kolumny „zarezerwowane" i „dostępne" z FR-005 pokazują zero, dopóki nie powstaną rezerwacje w S-04; to celowe, a nie brak.
- **Status:** done

### S-03: Projekty urządzeń z listą części i linkami

- **Outcome:** Kierownik tworzy, edytuje i usuwa projekty urządzeń z listą części (BOM) oraz dopina do nich linki do dokumentacji, np. schematów.
- **Change ID:** projects-and-bom
- **PRD refs:** FR-007, FR-019
- **Prerequisites:** S-01
- **Parallel with:** S-02, S-06
- **Blockers:** —
- **Unknowns:**
  - Co ma się dziać z BOM projektu, który ma otwarte zlecenia — edycja przelicza rezerwacje czy jest zablokowana? — Owner: user. Block: no.
- **Risk:** Niezależny od S-02, więc oba kawałki mogą iść obok siebie — przy ryzyku „dostępne godziny" to jedyne miejsce w strumieniu A, gdzie da się coś zrównoleglić. Linki z FR-019 są tu, a nie osobno, bo to jedno pole na tym samym ekranie projektu; osobny kawałek na nie byłby nieproporcjonalnie mały.
- **Status:** proposed

### S-04: Kierownik zleca produkcję i widzi rezerwacje oraz braki ★

- **Outcome:** Kierownik zleca technikowi produkcję N sztuk według projektu, z priorytetem i wymaganym terminem; dostępne części zostają zarezerwowane zgodnie z kolejnością przydziału, a dla każdej części z BOM widać ilość zarezerwowaną i brakującą.
- **Change ID:** order-reserves-parts
- **PRD refs:** US-01, FR-010, FR-005, §Business Logic, §Wymagania niefunkcjonalne
- **Prerequisites:** S-02, S-03
- **Parallel with:** S-06
- **Blockers:** —
- **Unknowns:**
  - Czy przeliczanie całej alokacji po każdym zdarzeniu wystarczy przy skali zakładu, czy potrzebne jest przeliczanie przyrostowe? — Owner: user. Block: no.
- **Risk:** To jest gwiazda przewodnia i jednocześnie najtrudniejsza część systemu: kolejność przydziału (priorytet → wcześniejszy termin → starsze zlecenie) plus przejmowanie niepobranych rezerwacji przez zlecenia wyżej w kolejności. Wyjątki dotyczące zleceń podjętych i części już pobranych nie mieszczą się tu, bo pobrania powstają dopiero w S-07 — ten kawałek dowozi kolejność i przejmowanie między zleceniami niepodjętymi, a S-07 dokłada wyłączenia. Rozliczany kryterium akceptacji US-01: BOM 10 rezystorów, stan 6, zlecenie na 1 sztukę → rezerwacja 6, brak 4.
- **Status:** proposed

### S-05: Lista zakupów i eksport do CSV

- **Outcome:** Kierownik widzi jedną zagregowaną listę zakupów — jedna pozycja na część, suma braków ze wszystkich otwartych zleceń, wraz ze zleceniami, które dany brak blokuje — i eksportuje ją do pliku CSV.
- **Change ID:** shopping-list-and-csv-export
- **PRD refs:** FR-015, FR-016, §Business Logic
- **Prerequisites:** S-04
- **Parallel with:** S-06, S-07, S-10, S-11, S-12
- **Blockers:** —
- **Unknowns:**
  - W jakim formacie CSV ma być lista zakupów, żeby otworzyła się poprawnie u odbiorcy (arkusz kalkulacyjny z polskimi znakami vs. narzędzie dostawcy)? — Owner: user. Block: no.
- **Risk:** Domyka Kryterium sukcesu (Primary) — dopiero tutaj kierownik dostaje odpowiedź „co zamówić" w formie, którą da się wynieść z aplikacji. Sekwencjonowane zaraz po gwieździe przewodniej, bo przy celu `speed` jest to najkrótsza droga do kompletnego, użytecznego przepływu; pułapka to policzenie braków per zlecenie zamiast agregatu per część.
- **Status:** proposed

### S-06: Konta techników

- **Outcome:** Kierownik zakłada konta techników i je dezaktywuje; dezaktywowany technik nie może się zalogować, a jego dane i historia zostają w systemie.
- **Change ID:** technician-accounts
- **PRD refs:** FR-002, §Kontrola dostępu
- **Prerequisites:** S-01
- **Parallel with:** S-02, S-03, S-04, S-05
- **Blockers:** —
- **Unknowns:** —
- **Risk:** Zależy wyłącznie od S-01, więc może iść równolegle z całym strumieniem A — przy ryzyku „dostępne godziny" to najbardziej elastyczny kawałek w roadmapie. Musi jednak wylądować przed S-07, bo bez kont techników listę kompletacyjną testuje wyłącznie kierownik (rola nadrzędna), a zakład i tak nie może z niej korzystać. Wpadka do uniknięcia: usuwanie kont zamiast dezaktywacji — FR-002 wprost tego zabrania.
- **Status:** done

### S-07: Lista kompletacyjna i pobranie części

- **Outcome:** Technik widzi listę zleceń do zmontowania, dla każdego z nich listę kompletacyjną z częściami, ilościami i lokalizacjami, i pobiera zarezerwowane części ze stanu — także częściowo, np. 6 z 10.
- **Change ID:** picking-list-and-pick
- **PRD refs:** FR-012, FR-013, §Business Logic, §Wymagania niefunkcjonalne
- **Prerequisites:** S-04, S-06
- **Parallel with:** S-05, S-10, S-11, S-12
- **Blockers:** —
- **Unknowns:** —
- **Risk:** Tutaj powstaje pojęcie zlecenia podjętego (pierwsze pobranie), które zamyka zmiany priorytetu i terminu oraz chroni niepobrane rezerwacje przed przejęciem — czyli wyłączenia, których S-04 nie mógł jeszcze wyrazić. To także miejsce, gdzie gwarancja „ta sama sztuka nie trafia do dwóch zleceń" spotyka realne pobranie, więc wzorzec testu z F-01 musi być tu użyty, a nie tylko walidacja formularza. Dopiero ten kawałek daje technikowi odpowiedź „gdzie leżą części" z §Wizji.
- **Status:** proposed

### S-08: Zgłoszenie i potwierdzenie zakończenia zlecenia

- **Outcome:** Technik zgłasza zlecenie jako zakończone, a kierownik je potwierdza; niepobrane rezerwacje zostają wtedy zwolnione i wracają do kolejnych zleceń.
- **Change ID:** order-completion
- **PRD refs:** FR-014, FR-020, §Business Logic
- **Prerequisites:** S-07
- **Parallel with:** S-05, S-09, S-10, S-11, S-12
- **Blockers:** —
- **Unknowns:** —
- **Risk:** Domyka cykl życia zlecenia: bez potwierdzenia rezerwacje zakończonych zleceń wiszą i fałszują listę zakupów. Ryzyko produktowe zapisane w PRD to wąskie gardło na kierowniku — roadmapa go nie rozwiązuje, tylko utrzymuje podział z FR-014/FR-020.
- **Status:** proposed

### S-09: Zmiana priorytetu i terminu oraz anulowanie zlecenia

- **Outcome:** Kierownik zmienia priorytet i wymagany termin zlecenia, dopóki nie zostało podjęte, albo anuluje zlecenie — w obu przypadkach rezerwacje są przeliczane, a przy anulowaniu części już pobrane wracają na stan.
- **Change ID:** order-change-and-cancel
- **PRD refs:** FR-021, FR-011, §Business Logic
- **Prerequisites:** S-07
- **Parallel with:** S-05, S-08, S-10, S-11, S-12
- **Blockers:** —
- **Unknowns:** —
- **Risk:** Oba wymagania siedzą w jednym kawałku, bo to ta sama operacja: zmiana istniejącego zlecenia plus przeliczenie alokacji — rozdzielenie ich dałoby dwa kawałki dotykające tego samego kodu. Wymagają S-07, bo blokada po podjęciu i zwrot pobranych części nie istnieją, dopóki nie ma pobrań. Najłatwiejsza wpadka: zwrot pobranych części z pominięciem ograniczenia na stan nieujemny w drugą stronę.
- **Status:** proposed

### S-10: Przyjęcie dostawy

- **Outcome:** Technik przyjmuje dostawę części, zwiększając jej stan, a rezerwacje i lista zakupów przeliczają się automatycznie — nowe sztuki uzupełniają braki zgodnie z kolejnością przydziału.
- **Change ID:** delivery-receipt
- **PRD refs:** FR-004, §Business Logic
- **Prerequisites:** S-02, S-04
- **Parallel with:** S-05, S-06, S-07, S-08, S-09, S-11, S-12
- **Blockers:** —
- **Unknowns:** —
- **Risk:** Pierwszy z trzech wyzwalaczy przeliczenia poza samym zleceniem; sekwencjonowany po S-04, bo bez rezerwacji „przelicza się automatycznie" nie ma treści. Razem z S-11 i S-12 tworzy niezależny strumień C, który można puścić obok kompletacji.
- **Status:** proposed

### S-11: Korekta stanu z powodem

- **Outcome:** Kierownik koryguje stan części po inwentaryzacji, podając powód korekty; rezerwacje i lista zakupów przeliczają się po zapisie.
- **Change ID:** stock-correction
- **PRD refs:** FR-018, §Business Logic
- **Prerequisites:** S-02, S-04
- **Parallel with:** S-05, S-06, S-07, S-08, S-09, S-10, S-12
- **Blockers:** —
- **Unknowns:** —
- **Risk:** Jedyna ścieżka, która może obniżyć stan poniżej sumy istniejących rezerwacji, więc to tutaj gwarancja z §Wymagań niefunkcjonalnych jest najłatwiejsza do złamania — korekta w dół musi zabrać rezerwacje wg kolejności przydziału, a nie tylko zapisać nową liczbę. Powód korekty jest wymagany wprost przez FR-018.
- **Status:** proposed

### S-12: Import części z CSV z podglądem

- **Outcome:** Kierownik importuje części z pliku CSV i przed zapisem widzi stany, jakie będą po zaakceptowaniu pliku; dla istniejących części import dopisuje ilość i dodaje lokalizację, jeśli jej brak, a nowe części są tworzone.
- **Change ID:** parts-csv-import
- **PRD refs:** FR-017, §Business Logic
- **Prerequisites:** S-02, S-04
- **Parallel with:** S-05, S-06, S-07, S-08, S-09, S-10, S-11
- **Blockers:** —
- **Unknowns:**
  - Jaki układ kolumn ma plik, z którego zakład dziś korzysta — czy jest próbka, czy definiujemy własny format i go dokumentujemy? — Owner: user. Block: no.
- **Risk:** To jest odpowiedź PRD na barierę startu („ręczne wprowadzanie całego magazynu"), więc w praktyce zakład zacznie od tego ekranu — ale sekwencjonowany jest późno, bo podgląd stanów wynikowych ma sens dopiero, gdy stan i rezerwacje mają znaczenie. Zapis bez wcześniejszej akceptacji podglądu łamie FR-017 i psuje cały magazyn jednym złym plikiem.
- **Status:** proposed

## Backlog Handoff

| Roadmap ID | Change ID                       | Suggested issue title                                  | Ready for `/10x-plan` | Notes                                               |
| ---------- | ------------------------------- | ------------------------------------------------------ | --------------------- | --------------------------------------------------- |
| F-01       | migration-and-invariant-harness | Ścieżka migracji Flyway i wzorzec testu niezmienników  | yes                   | Uruchom `/10x-plan migration-and-invariant-harness` |
| S-01       | login-and-role-access           | Logowanie e-mailem i dostęp zależny od roli            | done                  | Dostarczone jako `auth-and-roles` (patrz §Done)     |
| S-02       | parts-catalog                   | Kartoteka części z lokalizacjami i wyszukiwaniem       | no                    | Czeka na S-01                                       |
| S-03       | projects-and-bom                | Projekty urządzeń z BOM i linkami do dokumentacji      | no                    | Czeka na S-01; równolegle z S-02                    |
| S-04       | order-reserves-parts            | Zlecenie produkcji rezerwuje części i pokazuje braki   | no                    | Gwiazda przewodnia; czeka na S-02 i S-03            |
| S-05       | shopping-list-and-csv-export    | Lista zakupów z blokowanymi zleceniami i eksport CSV   | no                    | Czeka na S-04                                       |
| S-06       | technician-accounts             | Zakładanie i dezaktywacja kont techników               | no                    | Czeka na S-01; równolegle z całym strumieniem A     |
| S-07       | picking-list-and-pick           | Lista kompletacyjna z lokalizacjami i pobranie części  | no                    | Czeka na S-04 i S-06                                |
| S-08       | order-completion                | Zgłoszenie i potwierdzenie zakończenia zlecenia        | no                    | Czeka na S-07                                       |
| S-09       | order-change-and-cancel         | Zmiana priorytetu/terminu i anulowanie zlecenia        | no                    | Czeka na S-07                                       |
| S-10       | delivery-receipt                | Przyjęcie dostawy z automatycznym przeliczeniem        | no                    | Czeka na S-02 i S-04                                |
| S-11       | stock-correction                | Korekta stanu części z wymaganym powodem               | no                    | Czeka na S-02 i S-04                                |
| S-12       | parts-csv-import                | Import części z CSV z podglądem stanów wynikowych      | no                    | Czeka na S-02 i S-04                                |

This table is the clean handoff to Jira/Linear or any MCP-backed backlog.

## Open Roadmap Questions

1. **Czy odświeżenie rezerwacji i listy zakupów w mniej niż 2 s musi docierać do pozostałych zalogowanych użytkowników, czy wystarczy odpowiedź serwera dla osoby, która wykonała akcję?** — Owner: user. Block: wpływa na S-04, S-05, S-07, S-10, S-11, S-12, ale nie blokuje ich planowania. `tech-stack.md` deklaruje `has_realtime: true` i mechanizm push, a §Wymagania niefunkcjonalne mówią tylko „bez ręcznego odświeżania" — te dwa zdania da się spełnić na dwa sposoby o bardzo różnym koszcie. Roadmapa celowo nie przesądza odpowiedzi i nie tworzy na to osobnego fundamentu.
2. **Jaki format plików CSV obowiązuje po obu stronach — eksport listy zakupów (FR-016) i import kartoteki (FR-017)?** — Owner: user. Block: wpływa na S-05 i S-12, nie blokuje ich planowania. Jedna decyzja obsługuje oba kierunki; brak odpowiedzi oznacza, że każdy z tych kawałków zdefiniuje własny format i trzeba będzie je później uzgodnić.
3. **Czy edycja BOM projektu, który ma otwarte zlecenia, przelicza ich rezerwacje, czy jest zablokowana do czasu zamknięcia zleceń?** — Owner: user. Block: wpływa na S-03 i S-04, nie blokuje ich planowania. PRD odnotowuje w FR-007, że kwestia była rozważana, ale nie rozstrzyga jej wprost.

## Parked

- **Historia ruchów magazynowych (FR-006)** — Why parked: §Non-Goals wymienia ją jako nice-to-have poza MVP; korekta stanu (FR-018) została uznana za ważniejszą i jest w zakresie jako S-11.
- **Dołączanie i usuwanie plików projektu (FR-008)** — Why parked: §Non-Goals; w MVP wystarczają linki do dokumentacji (FR-019, w zakresie S-03).
- **Podgląd PDF w przeglądarce (FR-009)** — Why parked: §Non-Goals; realizowalne wyłącznie razem z FR-008, więc odpada razem z nim. To zarazem Kryterium sukcesu (Secondary), które MVP świadomie zostawia.
- **Reset hasła** — Why parked: FR-001 odnotowuje, że reset hasła był rozważany i nie został dodany; w MVP hasła ustawia kierownik przy zakładaniu konta (S-06).
- **Harmonogram produkcji i kalendarz** — Why parked: §Non-Goals; termin realizacji służy wyłącznie rozstrzyganiu kolejności przy równym priorytecie, bez planowania obciążenia techników.
- **Zamawianie u dostawców z aplikacji** — Why parked: §Non-Goals; lista zakupów kończy się na eksporcie CSV (S-05), bez integracji, cen i ofert.
- **Wiele zakładów lub magazynów oraz stan per lokalizacja** — Why parked: §Non-Goals; jedna instalacja obsługuje jeden zakład, lokalizacje to zwykły tekst przy łącznym stanie części.
- **Tryb offline i skanowanie kodów kreskowych/QR** — Why parked: §Non-Goals; aplikacja wymaga połączenia, skanera w MVP nie ma.
- **Metryki, śledzenie błędów i logowanie strukturalne** — Why parked: nie wynika z żadnego wymagania PRD, a przy celu `speed` i ryzyku „dostępne godziny" actuator z sondą `health` wystarcza jako bramka deployu (§Baseline).

## Milestone History

(Append-only. Carried forward verbatim into each successor milestone's roadmap. Empty — M-1 is the first milestone and is still open.)

## Done

- **S-01 (login-and-role-access)** — done, 2026-09-28 (retroactive). Delivered as change-id `auth-and-roles`, planned and archived on `origin/main` (`context/archive/2026-09-26-auth-and-roles/`) before this roadmap version existed, under an earlier roadmap's F-01. Reconciled into `phase-7-tune` by merging `origin/main` on 2026-09-28 rather than re-planning; scope confirmed to match this slice's Outcome and PRD refs exactly.
- **S-02: Kierownik dodaje, edytuje i usuwa części wraz z jedną lub kilkoma lokalizacjami, a każdy zalogowany użytkownik wyszukuje część i widzi jej stan oraz lokalizację.** — Archived 2026-09-29 → `context/archive/2026-09-28-parts-catalog/`. Lesson: —.
- **S-06: Kierownik zakłada konta techników i je dezaktywuje; dezaktywowany technik nie może się zalogować, a jego dane i historia zostają w systemie.** — Archived 2026-09-30 → `context/archive/2026-09-28-technician-accounts/`. Lesson: —.
