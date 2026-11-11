# Modern UI Refresh Implementation Plan

## Overview

Give every Stockahead view a modern product look modelled on anki.pl's app-side design. The new look is a cool off-white page with white cards, a deep teal-navy ink, a teal action colour, larger radii, tinted status pills and hairline borders. Add a left-sidebar app shell with a current-page highlight, simple motion, a consistent page header, and dashboard cards that show live counts. Everything stays on Thymeleaf + plain CSS with system fonts. The only backend work is four company-scoped count queries for the dashboard.

## Current State Analysis

From `research.md`, verified against the files on 2026-11-11:

- `static/css/app.css` already has single-source `light-dark()` colour tokens (`:51-72`), a type scale (`:27-31`), 4px spacing steps (`:34-41`), two shadows (`:44-46`) and one radius, `--radius: .5rem` (`:48`). In light mode `--background` and `--card` are both white (`:51,53`), so surfaces don't separate.
- The app has no motion. `transition`, `animation`, `@keyframes` and `transform` don't occur in `app.css`; the only grep hit is `text-transform` at `:240`.
- `fragments.html:12-41` renders `<header class="topbar">` as a horizontal bar with up to 8 role-gated text links and a session block. At 1280px it wraps into two rows. There is no active-page state.
- Every authenticated template (23 files) renders `<header th:replace="~{fragments :: topbar}">` followed by its own `<main class="container">`, then `<h1>`, alerts, `.page-actions` (stacked under the `h1`), and a table or `form.card`. Examples: `parts-list.html:10-35`, `orders-new.html:10-15`, `orders-detail.html:10-35`.
- Tables: the global `td, th { text-align: center }` rule (`app.css:176-179`) applies, with a grey `th` band (`:188-193`). Only `orders-list.html` uses the left-aligned `table.data-table` (`app.css:228-262`). Its numeric-column positions are hard-wired to `#pending-orders`/`#open-orders`.
- Buttons: `.btn` is solid `--primary`. `.btn-danger` is solid red. Row actions in `parts-list.html:56-64` are full-size, and the last one is a solid red "Dezaktywuj".
- `dashboard.html` is 9 link tiles with no data, including the debug tile `/manager/ping`. `HomeController` (`account/HomeController.java:22-28`) builds no model and redirects the operator to `/operator/companies`.

## Desired End State

On every authenticated view, in light and dark, at 1280px and 390px:

- A fixed left sidebar holds the brand mark, icon + label nav links with the current section highlighted, and the session block (email, role badge, theme toggle, Wyloguj) at the bottom. Below 48rem it collapses to a compact top bar with a horizontally scrolling nav.
- The page is cool off-white paper, and cards, tables and forms are white surfaces with hairline borders. The action colour is teal.
- Each page opens with a header row: the title (plus an optional muted subtitle) on the left and the primary actions on the right.
- In list tables, text is left-aligned, numbers are right-aligned in tabular digits, and headers are 11–12px uppercase muted labels. Row actions are compact quiet buttons, and destructive row actions are red text, not solid red.
- Forms sit in a readable column (max about 40rem) with soft-filled inputs. Detail pages show their key facts as a card grid.
- Buttons, links, rows and inputs fade between states. Content fades up on load, alerts slide in, and clickable cards lift. All motion stops under `prefers-reduced-motion: reduce`.
- The dashboard shows count cards:
  - **Manager**: Do potwierdzenia, Otwarte zlecenia, Braki do zakupu.
  - **Technician**: Moje zlecenia do kompletacji.
  - Each count links to its list and equals that list's row count. The tiles below are restyled, and the `/manager/ping` tile is gone.
- `./mvnw verify` passes, and a screenshot set under `context/changes/modern-ui-refresh/screenshots/` covers the views listed in Phase 4.

### Key Discoveries:

- `<header class="topbar">` must stay the first `<header>`. The operator's topbar must contain exactly `<a href="/operator/companies">Firmy</a>` (`OperatorAccessIntegrationTests.java:208-209`), so nav links get no classes, attributes or child elements. Nav icons and the active state must come from CSS selectors on `href`.
- `<tr>`, `<td>` and `<th>` must carry no attributes. Many assertions match bare cells; the research worker counted about 54 across 12 test files. `<td id="to-pick-N">` stays exact.
- `OperatorAccessIntegrationTests.java:183` asserts the empty operator page `not(containsString("<table>"))`. A class on that `<table>` would make the check pass vacuously, so the operator table stays a bare `<table>`.
- `<a class="btn btn-danger" …>Zablokuj</a>` and `<button type="submit" class="btn">Odblokuj</button>` are pinned (`OperatorCompanyLifecycleIntegrationTests.java:667,673`). Compact row buttons must come from a context selector (`td .btn`), not from new classes on those elements.
- `TemplateConventionTests` forbids `<style` and `style="` in templates and requires `fragments :: head(` and `fragments :: topbar` (except the 4 unauthenticated or pending views). Attributes on `<body>` are not constrained.
- Contrast was checked on 2026-11-11 with the WCAG 2.x formula:
  - Light: ink `#142c34` on paper `#f5f6f0` is 13.4:1. Muted `#586c72` on paper is 5.1:1. Teal `#077a72` on paper is 4.8:1, and white on it is 5.2:1. Input border `#778b85` on paper is 3.3:1 and on card 3.6:1.
  - anki's own teal `#087f77` on paper is 4.48:1 and its input `#c8d6d1` is 1.38:1. Both fail, so they are darkened.
  - Dark: fg `#e8efe9` on bg `#0f1d22` is 14.7:1. Muted `#9fb3b0` on card `#162a30` is 6.8:1. Primary `#3cbfb2` on card is 6.6:1, with bg-coloured text on it at 7.6:1. Input `#5f7d78` on card is 3.3:1.
- KPI data sources:
  - `OrderRepository.findByStatusWithProject` (`:16-17`) and `findByStatusAndAssigneeWithProject` (`:20-22`) are company-scoped.
  - `Order` has `companyId` (`:45`), `status` (`:59`), `completionReportedAt` (`:68`) and `assignee` (`:89`).
  - The shopping list groups `OrderLineRepository.findOpenLinesWithShortage` (`:79-82`) by part id (`ShoppingListModel.java:58-66`), so its row count is the number of distinct short parts.
  - The technician's `/picking` list is OPEN orders assigned to them, including reported ones (`PickingController.java:140-142`).

## What We're NOT Doing

- No React, Tailwind, htmx, Node, build step, Thymeleaf Layout Dialect, new Maven dependency or vendored font (decisions of 2026-11-09 and 2026-11-11).
- No marketing patterns: no hero images, animated gradients, serif display type, offset-shadow CTAs or decorative keyframes such as float, flip or ken-burns.
- No `aria-current` on nav links and no change to the `Firmy` assertion. The active state is visual only (user decision: body marker).
- No "parts out of stock" KPI and no new reorder-level concept.
- No styled `error.html`, no success flash for the detail-page actions, and no screenshot or visual-regression tests. These stay deferred, as in the archived `charges.md`.
- No change to routes, copy (labels stay as they are), permissions or business logic. `/manager/ping` and its tests stay; only its dashboard tile goes.
- No mobile card layout for tables. At 390px, tables keep scrolling horizontally inside `.table-wrap`.

## Implementation Approach

The work runs in four phases, ordered so that each one leaves the app shippable:

1. **Tokens, motion and shell**: global CSS plus the `<body data-section>` marker. This already changes every page.
2. **List views**: new default table, button and badge styles, plus a page-header and filter-bar pattern applied to every list template.
3. **Forms, detail and auth pages**: the same patterns applied to the remaining templates.
4. **Dashboard counts and visual gate**: the only Java work.

Shared patterns are CSS classes on wrapper elements, never on test-pinned elements. All colours stay in the `light-dark()` token block, and each new text or control pair carries a contrast comment, following the convention in the `app.css` header.

## Critical Implementation Details

- **Shell without a wrapper.** Every authenticated page has `header.topbar` directly before `main.container`. The sidebar is therefore a two-column CSS grid on `body:has(> .topbar)`. Auth pages have no topbar and keep the centred card layout.
- **Icons and active state live in CSS only.** Nav icons go in `a[href="…"]::before` using `mask-image` with a Lucide SVG data URI, coloured by `currentColor` through `background-color`. The active state is `body[data-section="X"] .topbar nav a[href="/X-route"]`. `th:href` renders root-relative hrefs with no context path; the test literal `href="/operator/companies"` shows this.
- **Enter animation.** Animate only `opacity` and `transform`, on `main`, for 200–300ms. Every POST redirects to a full page load, so the animation replays on every navigation; anything longer feels slow.

## Phase 1: Tokens, motion and sidebar shell

### Overview

Swap the palette to anki-style teal, add the radius, motion and elevation tokens, turn the topbar into a left sidebar with icons and a current-page highlight, and add the body section marker to every authenticated template.

### Changes Required:

#### 1. Design tokens

**File**: `src/main/resources/static/css/app.css`

**Intent**: Replace the shadcn Neutral colour values with the anki-derived palette. Keep the existing token names so every component picks up the new look. Add the missing scales.

**Contract**:
- Same token names; each colour stays one `light-dark()` value with a contrast comment.
- Light values:

  | Token | Value |
  |---|---|
  | `--background` | `#f5f6f0` |
  | `--card` | `#fffefb` |
  | `--foreground` | `#142c34` |
  | `--muted-foreground` | `#586c72` |
  | `--primary` / `--ring` | `#077a72` |
  | `--primary-foreground` | `#fff` |
  | `--secondary` / `--muted` / `--accent` | `#e8eee7` family (hover about `#d4e2d7`) |
  | `--border` | `#dce4dd` |
  | `--input` | `#778b85` |

- Dark values:

  | Token | Value |
  |---|---|
  | `--background` | `#0f1d22` |
  | `--card` | `#162a30` |
  | `--foreground` | `#e8efe9` |
  | `--muted-foreground` | `#9fb3b0` |
  | `--primary` / `--ring` | `#3cbfb2`, with `--primary-foreground` = the background colour |
  | `--input` | `#5f7d78` |
  | `--border` | white at about 10% |

- Status tokens (`--success`, `--warning`, `--destructive`, `--info`) get a tinted-surface pair each, for example `--success-surface` and `--success-text`, taken from anki's rating pairs and boost's status pairs. `--info` moves off the primary hue (anki lavender family) so it no longer collides with the new primary.
- New tokens: `--radius-sm` (10px, controls), `--radius` (14px, cards), `--radius-lg` (20px, large surfaces), `--radius-pill` (999px); `--ease`, `--dur-fast` (150ms), `--dur-base` (250ms). Shadows are retuned to a near-invisible ink tint (anki `.surface` style), and `--shadow-md` is used for hover lift.
- The header comment records the new token source: anki.pl `style.css`, fetched 2026-11-11.

#### 2. Motion

**File**: `src/main/resources/static/css/app.css`

**Intent**: Add simple state transitions and entrance motion that make the UI feel alive without slowing it down.

**Contract**:
- `transition` on `background-color, color, border-color, box-shadow` at `--dur-fast` for `.btn`/`button`, links, nav links, inputs, `tbody tr` and `.badge`.
- One `@keyframes` fade-up (opacity 0→1, translateY 6px→0) used by `main` (about 250ms) and `.alert` (about 200ms).
- `.tiles a` and the future KPI cards lift on hover (`translateY(-2px)` plus `--shadow-md`) over `--dur-base`.
- `@media (prefers-reduced-motion: reduce)` sets `animation: none` and `transition: none` on everything.

#### 3. Sidebar shell

**Files**: `src/main/resources/static/css/app.css`, `src/main/resources/templates/fragments.html`

**Intent**: Restyle the topbar as a fixed left sidebar so the 8 nav links never wrap and the page reads as an app.

**Contract**:
- `body:has(> header.topbar)` becomes a grid: a sidebar column of about 15rem and `1fr`. The `.topbar` is sticky, full height, and laid out as a column: brand at the top, the nav as a vertical list, `.session` pushed to the bottom.
- `.container` keeps its padding but drops its centred narrow max-width inside the shell, using roughly 80rem.
- The brand gets a small teal mark: a CSS `::before` square with a radius, or an inline SVG inside the brand `<a>`. The brand link is not test-pinned; the `Firmy` link is.
- Nav links are a full-width row with a `--radius-sm` hover fill and a `::before` icon. The Lucide paths are the ones already in `fragments.html:45-56`, re-encoded as data URIs in CSS. The `<a>` markup in `fragments.html` stays byte-identical.
- The active link (see item 4) gets a teal-tinted fill, teal text and a 3px leading bar.
- Below 48rem the shell is a single column. The `.topbar` becomes a compact top bar: brand, a horizontally scrolling nav row, and the session block collapsed to the role badge, theme toggle and Wyloguj, with the email hidden.

#### 4. Section marker per template

**Files**: the 23 authenticated templates in `src/main/resources/templates/` (all except `login`, `register`, `setup`, `awaiting-approval`)

**Intent**: Tell the CSS which nav item is current without touching link markup.

**Contract**: `<body data-section="…">` with these values:

| Section | Templates |
|---|---|
| `dashboard` | dashboard |
| `parts` | parts-list, parts-new, parts-edit, parts-correction |
| `import` | parts-import, parts-import-preview |
| `deliveries` | deliveries-new |
| `projects` | projects-list, projects-new, project-detail |
| `orders` | orders-list, orders-new, orders-detail, orders-cancel |
| `picking` | picking-list, picking-detail |
| `purchasing` | purchasing-list |
| `accounts` | accounts-list, accounts-new |
| `companies` | operator-companies, operator-companies-block, operator-companies-reject |

The CSS maps each value to its nav href: `/`, `/parts`, `/parts/import`, `/deliveries/new`, `/projects`, `/orders`, `/picking`, `/purchasing`, `/manager/accounts`, `/operator/companies`.

### Success Criteria:

#### Automated Verification:

- Full build and tests pass: `./mvnw verify`
- `TemplateConventionTests` passes, so no inline style was introduced: `./mvnw test -Dtest=TemplateConventionTests`
- The 23 authenticated templates each carry `data-section`: `grep -L 'data-section=' src/main/resources/templates/*.html` lists only `fragments.html`, `login.html`, `register.html`, `setup.html`, `awaiting-approval.html`
- `fragments.html` nav `<a>` lines are unchanged: `git diff` shows no edit inside the `<nav>` element

#### Manual Verification:

- At 1280px the sidebar shows all links for a manager in one column with icons. The current section is highlighted on parts-list, orders-detail and picking-list.
- At 390px the top bar is compact, the nav scrolls horizontally, and the content starts within about 120px of the top.
- Light and dark both read correctly: paper vs card surfaces are distinguishable, and the teal button and focus ring are visible.
- With OS reduced motion on, no fade-up or slide animation plays.

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful before proceeding to the next phase.

---

## Phase 2: List views

### Overview

Make the modern table, page header, filter bar, row action and badge styles the default, and apply the page-header and filter-bar markup to every list view.

### Changes Required:

#### 1. Default table style

**File**: `src/main/resources/static/css/app.css`

**Intent**: Every table gets the clean look that orders-list already has, without per-template opt-in.

**Contract**:
- Remove the global centring (`:176-179`). `th`/`td` become left-aligned with `--space-2`/`--space-3` padding.
- `th` sits on the card surface as an 11–12px uppercase muted label with letter spacing.
- Rows get a subtle hover fill. The last column (actions) is right-aligned.
- `table.data-table` becomes redundant: fold its rules into the defaults, keep the class as a no-op alias, and keep the `#pending-orders`/`#open-orders` positional numeric rules.
- New utility classes on `<table>` mark numeric columns by position, `num-2` to `num-8` (`table.num-3 :is(th,td):nth-child(3)`). They right-align and apply `tabular-nums`.
- `.table-wrap` gets the `--radius` card surface with a hairline border.

#### 2. Row actions and buttons

**File**: `src/main/resources/static/css/app.css`

**Intent**: Row buttons stop dominating the table, and destructive actions in rows become quiet, without changing pinned button markup.

**Contract**:
- Button radius becomes `--radius-sm` and weight 600.
- `.btn-secondary` is a tinted neutral fill (anki `#e8eee7` style).
- Context rule `td .btn, td button`: compact size (as `.btn-sm`), ghost style (transparent background, hairline border).
- `td .btn-danger`: destructive text colour on a transparent background, with a tinted-red fill on hover.
- `.actions` aligns to the end, matching the right-aligned last column.
- Solid `.btn-danger` stays the style outside tables (confirmation pages).

#### 3. Page header and filter bar

**Files**: `src/main/resources/static/css/app.css`; `parts-list.html`, `projects-list.html`, `accounts-list.html`, `purchasing-list.html`, `picking-list.html`, `operator-companies.html`, `orders-list.html`

**Intent**: Give every list page the modern header row and a filter card.

**Contract**:
- New `.page-header`: a flex row holding the `h1` (plus an optional `.page-subtitle` in muted text) and a `.page-actions` group on the right. It wraps under 48rem.
- In each list template, the `h1` and its existing `.page-actions` move into one `<div class="page-header">`; alerts follow the header.
- A primary action stays `.btn`; the other actions become `.btn-secondary`. The parts-list "Przyjmij dostawę" stays primary.
- `.form-inline` used as a search form becomes a filter card: a card surface with padding, a grid of fields, and the submit button aligned to the field baseline (anki `.library-search` pattern). The class name and form markup are unchanged.
- The checkbox label aligns with the inputs.

#### 4. Numeric columns, badges, pending tint, empty states

**Files**: `app.css`; the list templates above

**Intent**: Finish the per-list details that make tables readable.

**Contract**:
- Add `num-*` classes to each list `<table>` for its quantity and date columns, never to the operator-companies table (`OperatorAccessIntegrationTests.java:183`). For example, parts-list gets `num-2 num-3 num-4` for Stan, Zarezerwowane and Dostępne.
- `.badge-*` become tinted-surface + dark-text pills on the new status token pairs, with the `--radius-pill` radius. `.badge-high` is a red tint, not solid red.
- `tr.row-pending` is tinted with `color-mix(in oklab, var(--warning) …, var(--card))`. This fixes the pink tint (`visual-gate.md` N1); the button hover mixes also move to `oklab`.
- parts-list and accounts-list get the shared `.empty` paragraph when their list is empty. Use the existing empty-state copy style, for example "Brak części." and "Brak kont." Keep it outside the table so no `<table>` renders.
- `.empty` becomes a dashed-border centred block (anki pattern).

### Success Criteria:

#### Automated Verification:

- Full build and tests pass: `./mvnw verify`
- No attribute was added to table cells or rows: `grep -nE '<(tr|td|th) [a-z]' src/main/resources/templates/*.html` shows only pre-existing `th:`/`sec:` attributes and `id="to-pick-…"`, compared against `git diff`
- The operator companies table is still bare: `grep -n '<table>' src/main/resources/templates/operator-companies.html` matches

#### Manual Verification:

- parts-list at 1280px light: left-aligned names, right-aligned numbers, compact quiet row buttons, red-text "Dezaktywuj", the header row with actions on the right, and the filter card.
- orders-list: pending rows read amber, not pink; priority pills are tinted.
- picking-list, purchasing-list, accounts-list and operator-companies look consistent with parts-list in light and dark.
- Empty parts-list and accounts-list show the dashed empty state.

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful before proceeding to the next phase.

---

## Phase 3: Forms, detail and auth pages

### Overview

Apply the page header, form column, soft inputs and detail card grid to all remaining templates, and unify the confirmation and auth pages.

### Changes Required:

#### 1. Form styling

**File**: `src/main/resources/static/css/app.css`

**Intent**: Forms read as focused, modern panels.

**Contract**:
- `form.card` and `.card` that contain `.form-field` get `max-width: 40rem`.
- Inputs, selects and textareas get a `--radius-sm` radius, a slightly tinted fill (light about `#fcfdf9`), and a white fill on focus with a teal border and a 3px ring.
- Labels: 13px semibold, in the foreground colour.
- `.form-field` gap and spacing move onto the `--space-*` tokens.
- The submit button sits at the start of the form.
- `.hint` is muted 12–13px text.

#### 2. Form templates

**Files**: `parts-new.html`, `parts-edit.html`, `projects-new.html`, `orders-new.html`, `accounts-new.html`, `deliveries-new.html`, `parts-import.html`, `parts-correction.html`

**Intent**: Same header pattern as the lists.

**Contract**:
- `h1` plus the back link go into `.page-header`, with the back link as `.btn-secondary` on the right. The bottom `.page-actions` "Powrót…" block moves into the header.
- No change to form fields, names, ids, options or the `datalist#known-locations`.

#### 3. Detail and confirmation templates

**Files**: `orders-detail.html`, `project-detail.html`, `picking-detail.html`, `parts-import-preview.html`, `orders-cancel.html`, `operator-companies-block.html`, `operator-companies-reject.html`

**Intent**: Facts read as a summary card, actions sit in the header, and confirmation pages share one layout.

**Contract**:
- Page header: the title, plus status as a badge where the page already shows a status. The page-level destructive action, for example orders-detail "Anuluj zlecenie", moves into the header actions as `.btn-danger`.
- `dl.stats` becomes a card with a responsive grid of fact tiles: `dt` as a muted uppercase label, `dd` as a 16px value. `dt`/`dd` stay attribute-free, and the colon suffixes in `dt` text stay unchanged because their copy may be test-pinned.
- Sections with `h2` and a `form.card` keep their order. On wide screens the change and assign forms may sit side by side in a two-column grid wrapper (`.detail-grid`).
- Confirmation pages (cancel, block, reject) use one pattern: page header, a summary card, then an actions row with the solid destructive button and a secondary "Wróć". Pinned `Zablokuj`/`Odblokuj` markup stays byte-identical.

#### 4. Auth and pending pages

**Files**: `app.css`; `login.html`, `register.html`, `setup.html`, `awaiting-approval.html`

**Intent**: The first screen a user sees matches the new look.

**Contract**:
- The `.auth-page` card sits on the paper background with the brand mark above it and `--radius-lg` corners.
- The submit button is full width; the registration form may use a two-column field grid at ≥48rem.
- The `.form-trap` honeypot wrapper is unchanged.

### Success Criteria:

#### Automated Verification:

- Full build and tests pass: `./mvnw verify`
- No inline styles: `./mvnw test -Dtest=TemplateConventionTests`
- The honeypot is untouched: `git diff src/main/resources/templates/register.html` shows no change to the `form-trap` wrapper

#### Manual Verification:

- orders-new and parts-edit: a form column of about 40rem, soft inputs, a visible teal focus ring on Tab, and the back link in the header.
- orders-detail: a fact card grid, a priority badge, "Anuluj zlecenie" in the header, and the forms aligned.
- orders-cancel and operator block/reject pages share one layout.
- Login and register in light and dark at 390px and 1280px.

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful before proceeding to the next phase.

---

## Phase 4: Dashboard counts and visual gate

### Overview

Add the role-based count cards to the dashboard, restyle the tiles, remove the ping tile, and run the screenshot gate across the app.

### Changes Required:

#### 1. Count queries

**Files**: `src/main/java/pl/regavio/stockahead/orders/OrderRepository.java`, `src/main/java/pl/regavio/stockahead/orders/OrderLineRepository.java`

**Intent**: Cheap, company-scoped counts whose values equal the row counts of the lists they link to.

**Contract**:
- `long countByCompanyIdAndStatusAndCompletionReportedAtIsNotNull(Long companyId, OrderStatus status)`: "Do potwierdzenia", equal to the orders-list pending table filter (`OrderController.java:134`).
- `long countByCompanyIdAndStatusAndCompletionReportedAtIsNull(Long companyId, OrderStatus status)`: "Otwarte zlecenia", equal to the open table (`:135`).
- `long countByCompanyIdAndStatusAndAssigneeId(Long companyId, OrderStatus status, Long assigneeId)`: "Moje zlecenia do kompletacji", equal to the technician's `/picking` (`PickingController.java:140-142`), with reported orders included.
- A JPQL `COUNT(DISTINCT ol.part.id)` using the same WHERE clause as `findOpenLinesWithShortage` (`OrderLineRepository.java:79-82`): "Braki do zakupu", equal to the shopping-list rows (`ShoppingListModel.java:58-66`).
- Every query filters by `companyId`; none takes the company from a request parameter.

#### 2. HomeController model

**File**: `src/main/java/pl/regavio/stockahead/account/HomeController.java`

**Intent**: Supply the counts for the signed-in user's role.

**Contract**:
- `GET /` adds `@AuthenticationPrincipal CompanyUser` and `Model`. The operator redirect stays first, because the operator's `companyId()` is null.
- A manager gets `pendingConfirmationCount`, `openOrderCount` and `shortagePartCount`.
- A technician gets `myPickingCount`. The role check follows the pattern in `PickingController.java:119-121`.
- Manager-only queries never run for a technician.
- Update the class javadoc, which currently says the dashboard needs no model.

#### 3. Dashboard template and styles

**Files**: `src/main/resources/templates/dashboard.html`, `app.css`

**Intent**: The dashboard opens with useful numbers.

**Contract**:
- A `.kpis` grid of link cards above the tiles. Each card shows a 28px tabular number, a muted label and a section icon, and links to `/orders`, `/orders`, `/purchasing` or `/picking`. Cards are rendered by role with `th:if` on the model attributes.
- A non-zero "Do potwierdzenia" or "Braki do zakupu" card gets a warning-tinted accent.
- Tiles get a restyled card: icon chip, label and lift on hover.
- The `/manager/ping` tile is removed.

#### 4. Tests

**Files**: new `src/test/java/pl/regavio/stockahead/account/DashboardCountsIntegrationTests.java`

**Intent**: The counts are correct, company-isolated and role-gated.

**Contract**:
- Copy the setup of `purchasing/PurchasingCompanyIsolationIntegrationTests.java`: `CompanyFixtures`, JDBC seeding and the cleanUp order.
- Tests:
  - The manager of company A sees counts that match A's seeded data (pending, open, shortage) while company B has different data.
  - The technician sees only their own open-order count and no manager counts or links.
  - A company with no orders shows zeros.
  - The operator is still redirected.
- Existing dashboard assertions stay green: `OperatorAccessIntegrationTests.java:220-244` and the tile-link tests in `PartImportIntegrationTests` and `DeliveryIntegrationTests`.

#### 5. Visual gate

**File**: `context/changes/modern-ui-refresh/visual-gate.md` (new); screenshots under `context/changes/modern-ui-refresh/screenshots/` (untracked)

**Intent**: Evidence that every view type passed, using the method in the archived `2026-11-09-enhance-ui-design/visual-gate.md`.

**Contract**:
- Seed the data through the real forms and run `./mvnw spring-boot:test-run`.
- Use headless Firefox at exactly 1280 and 390px, light and dark.
- Views: dashboard (manager and technician), parts-list, orders-list, orders-detail, picking-list, orders-new, operator-companies and login. Also capture one reduced-motion check and one keyboard-focus check.
- A pass/fail table plus findings, in the same format as last time.
- Screenshots are not committed (memory: no screenshots in commits).

### Success Criteria:

#### Automated Verification:

- Full build and tests pass: `./mvnw verify`
- The new dashboard tests pass: `./mvnw test -Dtest=DashboardCountsIntegrationTests`
- The existing dashboard and role tests pass: `./mvnw test -Dtest='OperatorAccessIntegrationTests,PartImportIntegrationTests,DeliveryIntegrationTests'`
- The ping tile is gone: `grep -c '/manager/ping' src/main/resources/templates/dashboard.html` returns 0

#### Manual Verification:

- The manager dashboard's counts equal the row counts on `/orders` (both tables) and `/purchasing`; the technician's count equals the rows on their `/picking`.
- `visual-gate.md` shows a pass for every view, theme and width listed; any failure is fixed or explicitly deferred with a reason.

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful before proceeding to the next phase.

---

## Testing Strategy

### Unit Tests:

- None new. The count logic is repository queries, covered by the integration tests against real Postgres (AGENTS.md: no H2, no mocked DB).

### Integration Tests:

- `DashboardCountsIntegrationTests`: two-company isolation, manager vs technician, empty company, operator redirect.
- The existing markup-pinning tests are the regression net for the template edits in Phases 1–3. They must stay green without being edited. If one fails, fix the template, not the test.

### Manual Testing Steps:

1. Log in as a manager. Walk the sidebar: each link highlights its section, and the pages fade in.
2. On parts-list, hover a row and a quiet button, Tab to the filter input, and deactivate then reactivate a part.
3. On orders-list, check that the pending rows are amber and the badges tinted. Open an order and check the fact grid and the header actions.
4. Turn on OS reduced motion and reload: no animation.
5. Log in as a technician: the dashboard shows only "Moje zlecenia do kompletacji", and the sidebar shows no manager links.
6. Resize to 390px: a compact top bar and a scrollable nav.

## Performance Considerations

- Four `COUNT` queries per dashboard load (3 for a manager, 1 for a technician), each using existing company-scoped predicates. They don't load entities, unlike the list pages.
- The CSS grows by an estimated few hundred lines; there is no new HTTP request. The data-URI icons are inline in `app.css`.

## Migration Notes

- No schema change and no Flyway migration.
- Rollback is a revert of the change's commits.

## References

- Research: `context/changes/modern-ui-refresh/research.md`
- Prior UI change: `context/archive/2026-11-09-enhance-ui-design/` (`charges.md:78-94` deferred items, `visual-gate.md` method)
- Token convention: `src/main/resources/static/css/app.css:1-19`
- Isolation test pattern: `src/test/java/pl/regavio/stockahead/purchasing/PurchasingCompanyIsolationIntegrationTests.java`
- Reference sites: https://anki.pl/ (`style.css`), https://boost4body.com/ (status-colour pairs), fetched 2026-11-11

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles. See `references/progress-format.md`.

### Phase 1: Tokens, motion and sidebar shell

#### Automated

- [x] 1.1 Full build and tests pass: `./mvnw verify` — b62133e
- [x] 1.2 `TemplateConventionTests` passes, so no inline style was introduced — b62133e
- [x] 1.3 The 23 authenticated templates each carry `data-section` — b62133e
- [x] 1.4 `fragments.html` nav `<a>` lines are unchanged — b62133e

#### Manual

- [x] 1.5 Sidebar at 1280px shows all manager links with icons and highlights the current section — b62133e
- [x] 1.6 At 390px the top bar is compact and the nav scrolls horizontally — b62133e
- [x] 1.7 Light and dark both read correctly (surfaces, teal button, focus ring) — b62133e
- [x] 1.8 With reduced motion on, no animation plays — b62133e

### Phase 2: List views

#### Automated

- [x] 2.1 Full build and tests pass: `./mvnw verify`
- [x] 2.2 No attribute was added to table cells or rows
- [x] 2.3 The operator companies table is still bare

#### Manual

- [x] 2.4 parts-list shows aligned columns, quiet row buttons, header row and filter card
- [x] 2.5 orders-list pending rows read amber and priority pills are tinted
- [x] 2.6 Other list views look consistent with parts-list in light and dark
- [ ] 2.7 Empty parts-list and accounts-list show the dashed empty state

### Phase 3: Forms, detail and auth pages

#### Automated

- [ ] 3.1 Full build and tests pass: `./mvnw verify`
- [ ] 3.2 No inline styles (`TemplateConventionTests`)
- [ ] 3.3 The honeypot wrapper is untouched

#### Manual

- [ ] 3.4 Forms show a 40rem column, soft inputs, a teal focus ring and the back link in the header
- [ ] 3.5 orders-detail shows the fact grid, priority badge and header actions
- [ ] 3.6 Confirmation pages share one layout
- [ ] 3.7 Login and register look right in light/dark at 390 and 1280px

### Phase 4: Dashboard counts and visual gate

#### Automated

- [ ] 4.1 Full build and tests pass: `./mvnw verify`
- [ ] 4.2 The new dashboard tests pass
- [ ] 4.3 The existing dashboard and role tests pass
- [ ] 4.4 The ping tile is gone from `dashboard.html`

#### Manual

- [ ] 4.5 Dashboard counts equal the row counts of the linked lists for manager and technician
- [ ] 4.6 `visual-gate.md` shows a pass for every listed view, theme and width
