---
project: "Stockahead"
version: 1
status: draft
created: 2026-09-18
context_type: greenfield
product_type: web-app
target_scale:
  users: small
  qps: low
  data_volume: small
timeline_budget:
  mvp_weeks: 3
  hard_deadline: null
  after_hours_only: true
---

# Stockahead — PRD

## Vision & Problem Statement

W małym zakładzie produkującym urządzenia elektroniczne stan magazynu części istnieje tylko w pamięci pracowników. Przy każdym projekcie technik lub kierownik szuka części po całym magazynie, a braki wychodzą dopiero w trakcie kompletowania. Kosztuje to stracony czas i koszty opóźnienia projektu.

Insight: problem jest w zakładzie znany, ale nikt nie miał czasu wdrożyć rozwiązania. Rodzaj bólu: dane uwięzione w głowie oraz brak jasnej decyzji „co zamówić”.

## User & Persona

Technik lub kierownik w małym zakładzie produkującym urządzenia elektroniczne (znajomy z pracy autora). Przyjmuje dostawy części i kompletuje części do projektów urządzeń. Sięga po produkt na starcie projektu, gdy trzeba ustalić, czy części są na stanie i gdzie leżą, oraz przy przyjęciu dostawy.

## Success Criteria

### Primary
- Kierownik zleca produkcję N sztuk urządzenia; system rezerwuje dostępne części, technik pobiera je ze stanu, a brakujące części pojawiają się na liście zakupów możliwej do wyeksportowania do CSV.

### Secondary
- Podgląd dołączonych plików PDF (np. schematów) w przeglądarce.

### Guardrails
- Stan magazynowy żadnej części nigdy nie spada poniżej zera.
- Ta sama sztuka części nie może zostać zarezerwowana przez dwa zlecenia jednocześnie (brak podwójnej rezerwacji).

## User Stories

### US-01: Kierownik zleca produkcję i dostaje listę braków

- **Given** zalogowany kierownik, części w kartotece oraz projekt z listą części (BOM)
- **When** zleca technikowi produkcję N sztuk tego projektu
- **Then** dostępne części zostają zarezerwowane, zlecenie pojawia się na liście technika do zmontowania, a brakujące ilości trafiają na listę zakupów

#### Acceptance Criteria
- BOM wymaga 10 rezystorów, na stanie jest 6, zlecenie na 1 sztukę → rezerwacja 6, na liście zakupów 4.

## Functional Requirements

### Konta
- FR-001: Użytkownik może zalogować się e-mailem i hasłem. Priority: must-have
  > Socrates: Rozważono reset hasła i login zamiast e-maila. Resolution: brak kontrargumentu; zostaje.
- FR-002: Kierownik może zakładać i dezaktywować konta techników; dezaktywowany technik nie może się zalogować, jego dane i historia zostają. Priority: must-have
  > Socrates: Counter-argument considered: „usunięcie konta gubi historię”. Resolution: zmieniono usuwanie na dezaktywację.

### Magazyn
- FR-003: Kierownik może dodawać, edytować i usuwać części w kartotece, wraz z jedną lub kilkoma lokalizacjami (tekst); stan części jest łączny dla wszystkich lokalizacji. Priority: must-have
  > Socrates: Counter-argument considered: „ręczne wprowadzanie całego magazynu to bariera startu”. Resolution: dodano import CSV jako osobne FR-017 (must-have).
- FR-004: Technik może przyjąć dostawę części, zwiększając jej stan; lista zakupów przelicza się automatycznie. Priority: must-have
  > Socrates: Counter-argument considered: „dostawa powinna zdejmować braki z listy zakupów”. Resolution: lista zakupów zawsze pokazuje aktualne braki i przelicza się po przyjęciu dostawy.
- FR-005: Użytkownik może wyszukać część i zobaczyć jej stan fizyczny, ilość zarezerwowaną, ilość dostępną oraz lokalizację. Priority: must-have
  > Socrates: Counter-argument considered: „stan bez rezerwacji wprowadza w błąd”. Resolution: wynik pokazuje trzy liczby: stan / zarezerwowane / dostępne.
- FR-006: Kierownik może przeglądać historię ruchów magazynowych (kto, kiedy, ile przyjął/pobrał). Priority: nice-to-have
  > Socrates: Counter-argument considered: „korekta stanu (inwentaryzacja) ważniejsza niż historia”. Resolution: historia zostaje nice-to-have; dodano korektę stanu jako FR-018 (must-have).
- FR-017: Kierownik może zaimportować części z pliku CSV; przed zapisem widzi podgląd stanów, jakie będą po zaakceptowaniu pliku. Dla części już istniejących import dopisuje ilość do stanu i dodaje lokalizację z pliku, jeśli jej brak; nowe części są tworzone. Priority: must-have
  > Socrates: Counter-argument considered: „zły plik = zepsuty magazyn”. Resolution: import najpierw pokazuje stany wynikowe i zapisuje dopiero po akceptacji.
- FR-018: Kierownik może skorygować stan części (inwentaryzacja), podając powód korekty. Priority: must-have
  > Socrates: Counter-argument considered: „korekta bez powodu — po czasie nie wiadomo, czemu stan się zmienił”. Resolution: korekta wymaga podania powodu.

### Projekty
- FR-007: Kierownik może tworzyć, edytować i usuwać projekty urządzeń z listą części (BOM). Priority: must-have
  > Socrates: Rozważono edycję BOM i usunięcie projektu przy otwartych zleceniach. Resolution: brak kontrargumentu; zostaje.
- FR-019: Kierownik może dodawać do projektu linki do dokumentacji (np. schematów). Priority: must-have
  > Socrates: Rozważono brak dostępu technika do wskazywanego zasobu oraz obniżenie do nice-to-have. Resolution: brak kontrargumentu; zostaje.
- FR-008: Kierownik może dołączać i usuwać pliki projektu (np. schematy). Priority: nice-to-have
  > Socrates: Counter-argument considered: „link zamiast pliku wystarczy”. Resolution: w MVP linki (FR-019); upload plików zostaje nice-to-have.
- FR-009: Użytkownik może podejrzeć dołączony plik PDF w przeglądarce. Priority: nice-to-have
  > Socrates: Counter-argument considered: „zależy od FR-008”. Resolution: realizowane tylko razem z FR-008.

### Produkcja
- FR-010: Kierownik może zlecić technikowi produkcję N sztuk według projektu z priorytetem (np. niski / normalny / wysoki) i wymaganym terminem realizacji; dostępne części zostają zarezerwowane, a zlecenie o wyższym priorytecie przejmuje niepobrane rezerwacje niepodjętych zleceń o niższym priorytecie. Priority: must-have
  > Socrates: Counter-argument considered: „częściowa rezerwacja blokuje inne zlecenia”. Resolution (słowa autora): „Zlecenia powinny mieć priorytet i w pierwszej kolejności powinny być realizowane zlecenia z najwyższym priorytetem”. Kilka poziomów priorytetu, must-have; wyższy priorytet przejmuje rezerwacje od niższych.
- FR-021: Kierownik może zmienić priorytet i termin realizacji zlecenia, dopóki nie zostało podjęte (pierwsze pobranie części przez technika); rezerwacje zostają przeliczone. Priority: must-have
  > Socrates: Counter-argument considered: „technik traci części w trakcie kompletowania”. Resolution (słowa autora): „Po podjęciu zlecenia brak możliwości zmiany jego priorytetu”. Podjęcie = pierwsze pobranie; po podjęciu zablokowane są priorytet i termin, a niepobrane rezerwacje zlecenia nie mogą zostać przejęte.
- FR-011: Kierownik może anulować zlecenie, zwalniając jego rezerwacje; części już pobrane mogą zostać zwrócone na stan. Priority: must-have
  > Socrates: Counter-argument considered: „anulowanie po częściowym pobraniu”. Resolution: przy anulowaniu technik/kierownik zwraca pobrane części na stan.
- FR-012: Technik może zobaczyć listę zleceń do zmontowania; każde zlecenie pokazuje listę kompletacyjną: części, ilości i lokalizacje. Priority: must-have
  > Socrates: Counter-argument considered: „lista bez lokalizacji części nie rozwiązuje bólu”. Resolution: zlecenie pokazuje listę kompletacyjną z lokalizacjami.
- FR-013: Technik może pobrać zarezerwowane części ze stanu, także częściowo (np. 6 z 10). Priority: must-have
  > Socrates: Counter-argument considered: „pobranie częściowe”. Resolution: pobranie obsługuje ilości częściowe.
- FR-014: Technik może zgłosić zlecenie jako zakończone. Priority: must-have
  > Socrates: Counter-argument considered: „zakończenie powinien potwierdzać kierownik”. Resolution: technik zgłasza, kierownik potwierdza (FR-020).
- FR-020: Kierownik może potwierdzić zakończenie zlecenia; niepobrane rezerwacje zostają wtedy zwolnione. Priority: must-have
  > Socrates: Rozważono wąskie gardło na kierowniku i odrzucanie zgłoszeń. Resolution: brak kontrargumentu; zostaje.

### Zakupy
- FR-015: Kierownik może zobaczyć listę zakupów z brakującymi częściami, wraz ze zleceniami, które dany brak blokuje. Priority: must-have
  > Socrates: Counter-argument considered: „lista powinna wskazywać, które zlecenia blokuje brak”. Resolution: każda pozycja pokazuje blokowane zlecenia.
- FR-016: Kierownik może wyeksportować listę zakupów do CSV. Priority: must-have
  > Socrates: Rozważono brak kodu dostawcy i kodowanie polskich znaków w arkuszu kalkulacyjnym. Resolution: brak kontrargumentu; zostaje.

## Non-Functional Requirements

- Po każdym zdarzeniu zmieniającym stan lub zlecenia (zlecenie, zmiana priorytetu/terminu, dostawa, import, korekta, anulowanie, zamknięcie) użytkownik widzi aktualne rezerwacje i listę zakupów w < 2 s, bez ręcznego odświeżania.
- Równoczesna praca kilku użytkowników nigdy nie prowadzi do stanu części poniżej zera ani do zarezerwowania tej samej sztuki przez dwa zlecenia.

## Business Logic

Zlecenie rezerwuje części według priorytetu, a braki trafiają na listę zakupów.

Wejścia: stan fizyczny części, otwarte zlecenia produkcji (projekt z BOM × N sztuk, priorytet, termin realizacji, data utworzenia) oraz ilości już pobrane przez techników. Kolejność przydziału części między zleceniami: najpierw wyższy priorytet; przy równym priorytecie — wcześniejszy termin realizacji; przy równym terminie — starsze zlecenie. Zlecenie o wyższej pozycji w tej kolejności przejmuje niepobrane rezerwacje zleceń niższych. Nie podlegają przejęciu: części już pobrane oraz niepobrane rezerwacje zleceń podjętych (podjęcie = pierwsze pobranie części przez technika; od tej chwili priorytet i termin zlecenia są zablokowane). Zlecenie podjęte zachowuje swoje niepobrane rezerwacje, a jego pozostały brak jest nadal uzupełniany według kolejności przydziału, gdy tylko zwolnią się sztuki — z wyjątkiem okresu, w którym jego zgłoszenie zakończenia czeka na potwierdzenie. Przydzielone w ten sposób sztuki od razu podlegają ochronie przed przejęciem. Wyjątek: gdy korekta obniży stan poniżej niepobranych rezerwacji zleceń podjętych, rezerwacje te maleją w odwrotnej kolejności przydziału — brak fizycznych sztuk nie jest przejęciem.

Wyjście: dla każdego zlecenia — ilość zarezerwowana i brakująca dla każdej części z BOM; dla każdej części — stan / zarezerwowane / dostępne; jedna zagregowana lista zakupów (jedna pozycja na część, suma braków ze wszystkich otwartych zleceń, wraz ze zleceniami, które dany brak blokuje).

Użytkownik styka się z regułą, gdy: kierownik tworzy zlecenie lub zmienia jego priorytet/termin; ktoś przyjmuje dostawę lub importuje CSV (nowe sztuki automatycznie uzupełniają rezerwacje wg kolejności); kierownik koryguje stan; zlecenie zostaje anulowane lub zamknięte (zwolnione rezerwacje trafiają do kolejnych zleceń). Po każdym z tych zdarzeń rezerwacje i lista zakupów są przeliczane.

## Access Control

Logowanie e-mail + hasło. Brak publicznej rejestracji — konta zakłada kierownik; pierwsze konto kierownika powstaje przy uruchomieniu systemu. Niezalogowany użytkownik trafia na ekran logowania.

Role:

| Czynność | Kierownik | Technik |
|---|---|---|
| Dodawanie/edycja/usuwanie części (kartoteka) | ✅ | ❌ |
| Dodawanie/edycja/usuwanie projektów urządzeń i ich BOM | ✅ | ❌ |
| Linki do dokumentacji; dołączanie/usuwanie plików projektu | ✅ | ❌ |
| Zakładanie/dezaktywacja kont użytkowników | ✅ | ❌ |
| Import części z CSV | ✅ | ❌ |
| Korekta stanu (inwentaryzacja) | ✅ | ❌ |
| Planowanie produkcji (zlecenia z priorytetem, anulowanie) | ✅ | ❌ |
| Potwierdzenie zakończenia zlecenia | ✅ | ❌ |
| Zgłoszenie zakończenia zlecenia | ✅ | ✅ |
| Planowanie zakupów (lista zakupów) | ✅ | ❌ |
| Przyjęcie dostawy | ✅ | ✅ |
| Pobranie części ze stanu | ✅ | ✅ |
| Lista projektów do zmontowania | ✅ | ✅ |

Kierownik jest nadzbiorem roli technika.

## Non-Goals

- Harmonogram produkcji / kalendarz — termin realizacji służy wyłącznie do rozstrzygania kolejności przy równym priorytecie; brak planowania obciążenia techników.
- Zamawianie u dostawców z aplikacji — brak integracji z dostawcami, cen i ofert; lista zakupów kończy się na eksporcie CSV.
- Wiele zakładów / wiele magazynów — jedna instalacja obsługuje jeden zakład z jednym magazynem; lokalizacje to zwykły tekst, bez stanu per lokalizacja.
- Tryb offline i skanowanie kodów kreskowych/QR — aplikacja wymaga połączenia; brak skanera w MVP.
- Poza MVP (nice-to-have): historia ruchów magazynowych (FR-006), upload plików projektu (FR-008), podgląd PDF w przeglądarce (FR-009, tylko razem z FR-008).

## Open Questions

Brak otwartych pytań. Kontrola jakości w sesji planistycznej (2026-09-18) nie wykazała luk; pytanie o semantykę importu CSV zostało rozstrzygnięte przed zamknięciem sesji (import dopisuje ilość i dodaje brakującą lokalizację; część może mieć kilka lokalizacji przy łącznym stanie).
