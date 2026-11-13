---
change_id: modern-ui-refresh
view: all view types (dashboard ×2 roles, parts-list, orders-list, orders-detail, picking-list, orders-new, operator-companies, login)
checked: 2026-11-12
evidence: screenshots/ (39 PNGs, untracked and git-ignored; not committed)
---

# Visual gate — modern-ui-refresh (Phase 4)

## How the screenshots were taken

- App: `scripts/manual-test.sh` with `PORT=8081`, which runs `./mvnw spring-boot:test-run` against a throwaway
  Postgres and seeds through the real forms: operator setup → companies "Regavio Sp. z o.o." and "Pusta firma"
  registered and approved, "Oczekująca firma" left pending → 2 technicians, 7 parts, 4 projects with BOMs, 5 orders
  (high/normal/low, one unassigned) → the technician picked one order completely and reported its completion.
- Browser: headless Firefox 158, driven over Marionette from a scratchpad script, with a throwaway profile. Login
  went through the real `/login` form; cookies were cleared between users. The theme was forced through
  `localStorage["stockahead-theme"]` and `data-theme`, which is what the toggle does, then the page was reloaded.
- Widths: the viewport was pinned to exactly 1280px and 390px, measured as `innerWidth`. The Firefox window has a
  500px minimum, so the content `<browser>` element was constrained from chrome context. Screenshots are full-page.
- Focus: real Tab key presses (not `element.focus()`) from page load until the parts-list filter input (`#q`) had
  focus; `:focus-visible` matched.
- Reduced motion: `ui.prefersReducedMotion` set to 1 at runtime, then the dashboard was reloaded.
- Naming: `<view>-<state>-<theme>-<width>.png`.

## View × theme × width

| View | Light 1280 | Light 390 | Dark 1280 | Dark 390 | Note |
| --- | --- | --- | --- | --- | --- |
| dashboard — manager (`/`) | pass | pass | pass | pass | Three KPI cards: "Do potwierdzenia" 1 and "Braki do zakupu" 2 carry the amber accent, "Otwarte zlecenia" 4 is neutral. 8 tiles with icon chips. No `/manager/ping` link (checked in the DOM). At 390px the brand overflows, see F1. No sidebar item is highlighted, because the nav has no dashboard link. |
| dashboard — technician (`/`) | pass | pass | pass | pass | One KPI card only, "Moje zlecenia do kompletacji" 3. Four tiles; no manager links in tiles or sidebar. |
| parts-list (`/parts`) | pass | pass | pass | pass | "Kartoteka części" is highlighted. Filter card, table and compact row buttons are intact. At 390px the table scrolls inside `.table-wrap`; rows are about 110px tall, see N2. |
| orders-list (`/orders`) | pass | pass | pass | pass | "Zlecenia" is highlighted. The pending row has the amber left bar and tint, and the priority badges are tinted. In dark the pending tint is a muted olive-grey, see N3. Both tables scroll at 390px. |
| orders-detail (`/orders/2`) | pass | pass | pass | pass | "Zlecenia" is highlighted. Header actions, fact grid, two form cards and the "Pozycje" table. At 390px the actions wrap onto two lines and the cards stack. |
| orders-new (`/orders/new`) | pass | pass | pass | pass | Form card with stepper, selects and date input. At 1280px the card is centred while the `h1` is left-aligned, see N4. |
| picking-list (`/picking`, technician) | pass | pass | pass | pass | "Lista kompletacyjna" is highlighted. 3 rows; the reported order shows the "Zgłoszone" badge. |
| operator-companies (`/operator/companies`) | pass | pass | pass | pass | Sidebar has only "Firmy", highlighted. "Oczekuje" and "Aktywna" badges, plus Zatwierdź/Odrzuć/Zablokuj row buttons. At 390px the brand overflows, see F1. |
| login (`/login`) | pass | pass | pass | pass | Centred card with brand, no sidebar. Fields and the full-width button are aligned. |

Checks:

| Check | Result | Note |
| --- | --- | --- |
| keyboard focus (`parts-list-focus-filter-{light,dark}-1280`) | pass | 15 real Tab presses through the sidebar and header buttons reach `#q`; `:focus-visible` matches. A teal ring is visible around the input in both themes. |
| reduced motion (`dashboard-manager-reduced-motion-light-1280`) | pass | Before: `main` has `animation-name: fade-up`, `0.25s`, and `prefers-reduced-motion: reduce` is false. After: the media query matches, and `main` and `.kpi` compute `animation-name: none` and `transition-duration: 0s`. The page renders fully. |
| no horizontal page overflow | pass | `document.documentElement.scrollWidth` ≤ `innerWidth` on every shot (1268/1280 or 378/390 when a classic scrollbar is shown). Wide tables scroll inside `.table-wrap` only. |

## Manual criterion 4.5 — dashboard counts equal list rows

Checked with curl and a session cookie (form login with CSRF) against the seeded app, counting `<tr>` rows in each
`<tbody>`:

| Card (role) | Dashboard | List | Rows | Result |
| --- | --- | --- | --- | --- |
| Do potwierdzenia (manager) | 1 | `/orders` `#pending-orders` | 1 | pass |
| Otwarte zlecenia (manager) | 4 | `/orders` `#open-orders` | 4 | pass |
| Braki do zakupu (manager) | 2 | `/purchasing` | 2 | pass |
| Moje zlecenia do kompletacji (technik@regavio.test) | 3 | `/picking` | 3 (including the reported one) | pass |

## Findings

- **F1 — minor, fixed 2026-11-12 (user chose fix over defer).** At 390px the topbar brand "Stockahead" overflows its box (`scrollWidth > clientWidth`) when
  the session badge is "Kierownik" or "Operator". When the page has a vertical scrollbar (378px usable) the text is
  clipped to "Stockahe"; otherwise it runs under the role badge (brand box 56–157px, badge starts at 169px, text
  wider). The technician's shorter "Technik" badge fits. Visible in `dashboard-manager-*-390`, `parts-list-*-390`,
  `orders-*-390` and `operator-companies-*-390`. Cosmetic; candidates are hiding the email/badge or the brand text
  below a breakpoint, or `text-overflow: ellipsis`. Fix: below 30rem the brand shows only its teal mark
  (`font-size: 0` on `.topbar .brand`, accessible name kept); the 390px screenshots above predate it.
  **Re-checked 2026-11-12 (re-run 2): pass.** At 390px, `.topbar .brand` has `scrollWidth == clientWidth` on all 16
  shots (manager 89/89 or 101/101, operator 106/106, technician 115/115). Computed `font-size` is 0px, and the text
  content is still "Stockahead". There is a 12px gap to the role badge on every shot, and no bounding box in the bar
  overlaps another. At 1280px the brand text shows at 16px (215/215). Evidence: `*-390-r2.png`.
- **N1 — headless artefact, not a defect.** A white 14px strip shows under each horizontally scrolled table at 390px,
  most visible in dark. It is headless Firefox's classic GTK scrollbar track, as in the previous gate (N2 there).
- **N2 — note.** At 390px the parts-list rows are about 110px tall, because the three row buttons in the actions
  column stack. It matches the "no mobile card layout" decision, but makes the table long on phones.
- **N3 — note.** The dark-theme pending-row tint on orders-list is olive-grey rather than amber. This is the
  oklch `color-mix` hue walk recorded as N1 in the previous gate (accepted then); the amber left bar still marks the row.
- **N4 — note.** On orders-new at 1280px the form card is centred in the content column while the `h1` sits at the
  left edge, so the heading and card do not share an edge.
- **N5 — note, by design.** On the dashboard no sidebar item is highlighted, because the nav has no dashboard link
  (the brand links home). The two order KPI cards share the orders icon, as the plan specifies.
- Full-page capture renders the sticky sidebar at its top position, so a 12px strip of page background shows under it
  on pages taller than the viewport (orders-detail). This is a capture artefact.

## Re-run 2026-11-12 (after table, picking and F1 fixes)

Checks the fixes recorded after the first gate: centred cells, rows without stripes and with a new divider and
hover, the picking-detail action cell that is always rendered, and the F1 brand fix. The method matches the first
run: a fresh `PORT=8081 scripts/manual-test.sh` (same seed), headless Firefox over Marionette with a throwaway
profile, a real `/login`, the theme set through `localStorage` + `data-theme`, and the viewport fixed at exactly
1280 and 390px. Two additions:

- Before every capture the pointer is parked in the bottom-right corner, and `tbody tr:hover` is 0 on every shot. On
  the first attempt the pointer stayed where the hover test left it and painted a hover row into the operator 390
  shot. That capture was discarded.
- On every shot the DOM is measured: `text-align` of every `th`/`td`, the cell count of each body row against the
  header, the right edge of each row's last cell against the table, `.topbar .brand` scroll and client width,
  overlaps between bounding boxes in the top bar, and `documentElement.scrollWidth`.

Naming: `<view>-<state>-<theme>-<width>-r2.png` (47 new files; the first-run PNGs are untouched).

| View | Light 1280 | Light 390 | Dark 1280 | Dark 390 | Note |
| --- | --- | --- | --- | --- | --- |
| dashboard — manager (`/`) | pass | pass | pass | pass | At 390px the bar shows menu, teal mark, "Kierownik", Wyloguj and theme, with no clipping (F1 fixed). |
| dashboard — technician (`/`) | pass | pass | pass | pass | Brand 115/115 at 390, 12px from the "Technik" badge. |
| parts-list (`/parts`) | pass | pass | pass | pass | All 48 cells are centred, actions included. No stripes: every row has the same background. The divider is a 1px mixed rule, and the header has a firmer one. Rows at 390px are about 120px tall (N2 still applies). |
| orders-list (`/orders`) | pass | pass | pass | pass | All 46 cells in both tables are centred. The pending row keeps its amber tint and bar. |
| orders-detail (`/orders/2`) | pass | pass | pass | pass | All 20 cells are centred, with clear dividers in both themes. |
| orders-new (`/orders/new`) | pass | pass | pass | pass | No table. Unchanged from the first run (N4 still applies). |
| picking-list (`/picking`, technician) | pass | pass | pass | pass | 7/7 cells on every row. Status badge and button are centred. |
| picking-detail (`/picking/1`, reported order, all lines 0 to pick) | pass | — | pass | — | Header 7 cells; all 4 body rows have 7 cells. The last cell's right edge equals the table's right edge (1235px), so the row dividers run the full width. |
| operator-companies (`/operator/companies`) | pass | pass | pass | pass | All 20 cells are centred. Brand 106/106 at 390, 12px from the "Operator" badge, no overlap. |
| login (`/login`) | pass | pass | pass | pass | No top bar. The card is unchanged. |

Checks:

| Check | Result | Note |
| --- | --- | --- |
| (a) cells centred | pass | `text-align: center` on every `th`/`td` in every shot (0 exceptions out of 20–48 cells per view). `.actions` containers are centred (0 exceptions). |
| (b) dividers and hover (`parts-list-hover-*`, `orders-list-hover-*`) | pass | Real pointer hover (`WebDriver:PerformActions` pointerMove) with `tr:hover` matching. Body divider is 1px `color-mix(--input 35%, --card)`: L 0.865 on a ~0.99 card in light, L 0.374 on a ~0.27 card in dark. The header rule is firmer (L 0.732 light / 0.477 dark). Hover fill is 14% teal (L 0.931 light / 0.335 dark) with a 3px inset teal bar on the first cell (`#077a72` / `#3cbfb2`). A hovered pending row keeps its amber bar (`#e0a43a` / `#e9b44c`). Both read clearly in the PNGs. First-column weight is 500, padding 12px. |
| (c) picking-detail alignment | pass | Reported order `/picking/1`: header 7, rows [7,7,7,7], last cell right edge minus table right edge = 0. Open orders `/picking/2` and `/picking/3` are the same: 7 cells per row, including the row at 0 to pick on `/picking/3` (`picking-detail-open-light-1280-r2`). The last row has no bottom border, by design: the card edge closes the table. |
| (d) F1 brand at 390 | pass | See F1 above. 0 overlaps in all 16 shots at 390px; `font-size: 16px` text visible at 1280px. |
| (e) no page-level horizontal overflow | pass | `scrollWidth` ≤ `innerWidth` on all 38 view shots (1268/1280 or 378/390 with a classic scrollbar, otherwise 390/390). |
| keyboard focus (`parts-list-focus-filter-{light,dark}-1280-r2`) | pass | 15 real Tab presses reach `#q`, and `:focus-visible` matches. The input shows the teal border plus a soft ring in both themes. The computed `box-shadow`, read straight after the key press, was still transparent because of the transition. |
| reduced motion (`dashboard-manager-reduced-motion-light-1280-r2`) | pass | Before: `fade-up 0.25s`, media query false. After `ui.prefersReducedMotion=1`: media query true, and `main`/`.kpi` compute `animation-name: none`, `transition-duration: 0s`. |

### Findings (re-run)

- **R1 — minor, fixed 2026-11-13 (user chose fix).** On picking-detail for an open order at 1280px, the table is slightly wider than its
  `.table-wrap`: `/picking/2` has scrollWidth 981 vs clientWidth 962 (19px), and `/picking/3` 967 vs 962 (5px). So a
  desktop-width page gets a small horizontal scroll inside the card, and the first-column part names wrap onto two
  lines ("Dioda LED / czerwona"). Cause: the seven uppercase `nowrap` headers (widest "ZAREZERWOWANA ILOŚĆ", 181px)
  plus the 200px action column (stepper and "Pobierz"). The reported order, which has no forms, fits exactly.
  Cosmetic; it does not affect alignment (every row still spans the table). Possible fixes: let `th` wrap on this
  table, or shorten the action column.
  Fix: `th` no longer has `white-space: nowrap` (it uses `text-wrap: balance`), so a header label wraps only when the
  columns run out of room. Re-checked 2026-11-13 in headless Firefox at 1280px, light and dark, against the
  `scripts/seed-data.sh` app: `/picking/1`, `/picking/2`, `/picking/3` and `/picking` each have table width =
  `.table-wrap` clientWidth = scrollWidth = 974px (no horizontal scroll), 7 cells in every row. The long headers wrap to
  two even lines (header row 61px on the open orders and on `/orders`; single line, 43px, on `/picking/1`, `/picking`,
  `/parts` and `/purchasing`). Part names in the first column of the open picking orders still wrap, because the action
  column holds the stepper; the table no longer overflows.
- **N1 (unchanged) — headless artefact.** The white strip under horizontally scrolled tables at 390px is still the
  headless GTK scrollbar track (`operator-companies-default-dark-390-r2`).
- **N2 (unchanged).** Parts-list rows at 390px are about 120px tall because the row buttons stack.
- No regressions found in the views carried over from the first run.
