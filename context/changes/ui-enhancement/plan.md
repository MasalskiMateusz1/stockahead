# UI Enhancement Implementation Plan

## Overview

Give Stockahead a shared design system. Today every template is unstyled HTML, and 14 of them carry a duplicated inline `<style>` block. This change adds one plain-CSS stylesheet with shadcn-style design tokens: a light theme, a dark theme that follows the OS, and a manual theme toggle. It also adds shared Thymeleaf fragments (head, top-bar navigation, alerts) and moves all 22 templates onto them. There is no frontend build step: the Dockerfile, CI and Maven build are unchanged.

## Current State Analysis

From `research.md`, verified again for this plan:

- There is no stylesheet, no `src/main/resources/static/` folder, no JavaScript, and no `th:fragment`/`th:replace` anywhere. Each of the 22 templates in `src/main/resources/templates/` has its own `<head>`.
- 14 templates carry the same inline `<style>td, th { text-align: center; }</style>` block (lines 6–10). This is required by the `lessons.md` rule "Center cell content in every table template".
- There are 10 `style="display:inline"` forms in 5 templates (`picking-detail`, `orders-detail`, `projects-list`, `parts-list`, `project-detail`).
- "Powrót do pulpitu" links are copied into the views. The dashboard (`dashboard.html:12-23`) is the only navigation.
- Errors appear as plain `<p th:if="${error}">` in 12 templates. Success flashes are plain `<p>` (e.g. `parts-list.html:15-19`). Login status messages are plain `<p>` (`login.html:10-13`).
- `SecurityConfig.java:25-27` permits only `/login`, `/setup/**` and `/actuator/health/**`. Any `/css/**` or `/js/**` request would redirect to login, so the login page could not load its stylesheet.

## Desired End State

- Every page loads `/css/app.css` (content-hashed URL) and `/js/theme.js` through one shared `head` fragment. No template contains a `<style>` block or a `style="…"` attribute.
- Every authenticated page has a shared top bar. It shows role-filtered links (same `sec:authorize` rules as the dashboard today), the signed-in email and role, a theme toggle and a logout button. The dashboard remains the landing page, with its links shown as tiles. Per-page "Powrót do pulpitu" links are gone.
- `login` and `setup` use the shared head and a minimal centered card layout, without the nav.
- Colors come only from CSS custom properties on `:root`:
  - light by default;
  - dark under `@media (prefers-color-scheme: dark)` guarded by `:root:not([data-theme="light"])`;
  - forced by `:root[data-theme="dark"]` / `:root[data-theme="light"]`.
- The toggle cycles Auto → Jasny → Ciemny. The choice is stored in `localStorage` and applied before first paint.
- Tables, forms, buttons (primary / secondary / destructive), alerts (error / success / info), badges, empty states and stat lists share one consistent look. Table cells stay centered, as the old rule required.
- `./mvnw verify` is green. New tests cover anonymous access to static assets, use of the shared head fragment, and the "no inline styles" convention.

### Key Discoveries:

- Tests match exact markup in ~12 places. Examples: `containsString("<td>7</td>")` in `ShoppingListIntegrationTests`, `"<th>Do pobrania</th>"`, `"<strong>Lokalizacja</strong>"`, `"<td>7 / 10</td>"`. The `<td>`, `<th>` and `<strong>` elements these assertions match must not get attributes. Style through element selectors and classes on parents (`table`, `section`, wrapper `div`).
- `SecurityRedirectTests.java:24-26` shows the no-Postgres `@WebMvcTest` + `@Import(SecurityConfig.class)` pattern with mocked `AccountUserDetailsService`/`AccountRepository`. Reuse it for the static-asset access tests.
- `HomeController.java:28-29` puts `email`/`role` into the dashboard model only. The shared nav must read identity from `sec:authentication="name"` and `sec:authorize`, not from model attributes.
- Spring Boot content versioning (`spring.web.resources.chain.strategy.content.enabled=true`, `…paths=/**`, verified via Context7 docs) rewrites `@{/css/app.css}` to a hashed URL through the auto-configured `ResourceUrlEncodingFilter`. Stale CSS after a deploy is not an issue.
- `PathRequest.toStaticResources().atCommonLocations()` covers `/css/**`, `/js/**`, `/images/**`, `/webjars/**` and favicon. Use it rather than hand-listing paths.

## What We're NOT Doing

- No Tailwind, no Node/npm, no build plugin, no Layout Dialect dependency. Dockerfile and `.github/workflows/ci.yml` stay untouched.
- No Playwright/browser screenshot tests. Visual checking is manual.
- No sidebar layout. No responsive hamburger menu: the top bar wraps on narrow screens.
- No server-side storage of the theme preference. It lives in `localStorage` only.
- No changes to controllers, view models, routes, message content or business logic. User-facing text stays as it is; the only text removed is the per-page "Powrót do pulpitu" link, which the topbar replaces.
- No fix to the stale `roadmap.md:77` sentence. That is a separate housekeeping change.
- Nothing from PRD § Non-Goals.

## Implementation Approach

Build the foundation first and prove it on the three pages that have no tables (`login`, `setup`, `dashboard`). Then migrate the templates in two area-based batches, so each diff stays reviewable and `./mvnw verify` catches markup regressions per batch. Add the convention guard test last, once it can pass. Use semantic classes (`.btn`, `.btn-danger`, `.alert-error`, `.badge`, `.stats`, `.empty`, `.actions`, `.card`, `.form-field`) on the elements tests do not match. Everything else gets element-level styles (`table`, `th`, `td`, `input`, `label`, `h1`, `h2`).

## Critical Implementation Details

- **Timing & lifecycle**: `theme.js` must load in `<head>` without `defer`, so it sets `data-theme` on `<html>` before the body paints (no light flash for dark-mode users). It binds the toggle handler on `DOMContentLoaded`. The toggle button is rendered with `hidden` and is revealed by the script, so it never shows when JavaScript is off. Wrap every `localStorage` read and write in try/catch. When storage is unavailable, fall back to Auto.
- **Test-matched markup**: before editing a template, grep `src/test/java` for strings from it. Leave any matched element byte-identical, including whitespace inside the tag.

## Phase 1: Foundation (stylesheet, theme script, fragments, security)

### Overview

Create the design system and shared fragments, open static assets to anonymous users, and apply everything to `login`, `setup` and `dashboard`.

### Changes Required:

#### 1. Stylesheet

**File**: `src/main/resources/static/css/app.css`

**Intent**: Single source of visual truth. Shadcn-style tokens plus base element styles and a small set of component classes, all colors via `var(--…)`. Restates `td, th { text-align: center; }` so the old rule's behaviour is preserved.

**Contract**:
- Tokens, with base values copied from the shadcn/ui default theme (base colour "Neutral", `oklch`), fetched from https://ui.shadcn.com/docs/theming on 2026-10-03. shadcn's `.dark` block maps to our dark selectors below. Of shadcn's tokens, skip `--popover*`, `--chart-*` and `--sidebar-*`: nothing in the app uses them.

  | Token | Light (`:root`) | Dark | Source |
  | --- | --- | --- | --- |
  | `--radius` | `0.625rem` | (same) | shadcn |
  | `--background` | `oklch(1 0 0)` | `oklch(0.145 0 0)` | shadcn |
  | `--foreground` | `oklch(0.145 0 0)` | `oklch(0.985 0 0)` | shadcn |
  | `--card` | `oklch(1 0 0)` | `oklch(0.205 0 0)` | shadcn |
  | `--card-foreground` | `oklch(0.145 0 0)` | `oklch(0.985 0 0)` | shadcn |
  | `--primary` | `oklch(0.205 0 0)` | `oklch(0.922 0 0)` | shadcn |
  | `--primary-foreground` | `oklch(0.985 0 0)` | `oklch(0.205 0 0)` | shadcn |
  | `--secondary` | `oklch(0.97 0 0)` | `oklch(0.269 0 0)` | shadcn |
  | `--secondary-foreground` | `oklch(0.205 0 0)` | `oklch(0.985 0 0)` | shadcn |
  | `--muted` | `oklch(0.97 0 0)` | `oklch(0.269 0 0)` | shadcn |
  | `--muted-foreground` | `oklch(0.556 0 0)` | `oklch(0.708 0 0)` | shadcn |
  | `--accent` | `oklch(0.97 0 0)` | `oklch(0.269 0 0)` | shadcn |
  | `--accent-foreground` | `oklch(0.205 0 0)` | `oklch(0.985 0 0)` | shadcn |
  | `--destructive` | `oklch(0.577 0.245 27.325)` | `oklch(0.704 0.191 22.216)` | shadcn |
  | `--border` | `oklch(0.922 0 0)` | `oklch(1 0 0 / 10%)` | shadcn |
  | `--input` | `oklch(0.922 0 0)` | `oklch(1 0 0 / 15%)` | shadcn |
  | `--ring` | `oklch(0.708 0 0)` | `oklch(0.556 0 0)` | shadcn |
  | `--destructive-foreground`, `--success`, `--success-foreground`, `--warning`, `--warning-foreground`, `--info` | chosen in Phase 1 | chosen in Phase 1 | own: hues near Tailwind red/green/amber/blue |

  The shadcn defaults have no status colours, and the current shadcn theme has no `--destructive-foreground`. These "own" tokens are picked in Phase 1. Each text/background pair must meet WCAG AA (≥ 4.5:1) in both themes, with the computed ratio in a CSS comment next to the token. Badge colours (`.badge-high`/`-normal`/`-low`) are built from `--destructive`/`--warning`/`--muted`, with no new hues.
- Each token is defined three times: `:root` (light); `@media (prefers-color-scheme: dark) { :root:not([data-theme="light"]) { … } }`; and `:root[data-theme="dark"] { … }`. `body` gets an explicit `background`/`color`.
- Component classes:
  - layout: `.topbar`, `.container`, `.card`, `.tiles`;
  - buttons: `.btn`, `.btn-secondary`, `.btn-danger`;
  - messages: `.alert`, `.alert-error`, `.alert-success`, `.alert-info`;
  - data display: `.badge` (+ `.badge-high`/`.badge-normal`/`.badge-low`/`.badge-muted`), `.stats` (dl-based stat list), `.empty`;
  - forms: `.form-field`, `.hint`;
  - table helpers: `.actions` (inline row-action container replacing `style="display:inline"`), `.table-wrap` (horizontal scroll for wide tables on narrow screens).
- Visible focus ring via `--ring` on links, buttons and inputs. Readable at 768px tablet width.

#### 2. Theme script

**File**: `src/main/resources/static/js/theme.js`

**Intent**: Apply the stored theme before paint and drive the toggle.

**Contract**:
- `localStorage` key `stockahead-theme`, with values `light` | `dark` (absent = Auto).
- It sets or removes `data-theme` on `document.documentElement`.
- The toggle element is `[data-theme-toggle]`. Each click cycles Auto → Jasny → Ciemny → Auto, updating the button text (`Motyw: Auto` / `Motyw: Jasny` / `Motyw: Ciemny`) and `aria-label`.

#### 3. Shared fragments

**File**: `src/main/resources/templates/fragments.html`

**Intent**: The single place for the shared head, nav and alert markup.

**Contract**:
- `head(title)`: charset, viewport meta, `<title>` = `${title} + ' — Stockahead'`, `<link rel="stylesheet" th:href="@{/css/app.css}">` and `<script th:src="@{/js/theme.js}">`. Pages use `<head th:replace="~{fragments :: head('Zlecenia')}">`.
- `topbar`: brand link to `/`, then the same links and `sec:authorize` conditions as `dashboard.html:12-23`. That covers manager-only Zlecenia, Lista zakupów, Import CSV and Konta techników. The "Sprawdź dostęp kierownika" ping link stays on the dashboard only. Also: `sec:authentication="name"`, a role label, the `hidden` theme toggle button, and the logout POST form.
- `alerts`: renders `${error}` as `.alert-error` when present. Pages with named flash attributes keep their own `th:if` but use alert classes.

#### 4. Security and resource versioning

**File**: `src/main/java/pl/regavio/stockahead/security/SecurityConfig.java`, `src/main/resources/application.properties`

**Intent**: Let the login page load CSS/JS without a session, and bust caches on deploy.

**Contract**:
- Add `.requestMatchers(PathRequest.toStaticResources().atCommonLocations()).permitAll()` before `anyRequest()`.
- Add `spring.web.resources.chain.strategy.content.enabled=true` and `spring.web.resources.chain.strategy.content.paths=/**`.

#### 5. Apply to non-table pages

**File**: `src/main/resources/templates/login.html`, `setup.html`, `dashboard.html`

**Intent**: Prove the system end-to-end.
- `login` and `setup`: shared head, centered `.card`, `.form-field` rows, `.btn`, and login status messages as `.alert-*`.
- `dashboard`: shared head + topbar, links as `.tiles`. The "Zalogowano jako" line and the logout form move into the topbar.

**Contract**: Message keys and form field names/ids are unchanged (`username`, `password`, setup fields). `AuthenticationIntegrationTests` assertions stay green.

#### 6. Asset access tests

**File**: `src/test/java/pl/regavio/stockahead/security/StaticResourceAccessTests.java`

**Intent**: Lock in anonymous access to assets and their presence on the login page.

**Contract**: `@WebMvcTest` + `@Import(SecurityConfig.class)` in the same style as `SecurityRedirectTests`. Tests:
- anonymous `GET /css/app.css` → 200 with `text/css`;
- anonymous `GET /js/theme.js` → 200;
- anonymous `GET /login` → 200, and the body contains `/css/app` and `/js/theme`. This needs a login-controller slice or `@WebMvcTest` without a controller filter; pick whichever renders `login.html`.

### Success Criteria:

#### Automated Verification:

- `./mvnw test -Dtest=StaticResourceAccessTests` passes
- `./mvnw verify` passes (all existing tests green)

#### Manual Verification:

- `./mvnw spring-boot:test-run`: the login page is styled before logging in, with no 302 for CSS/JS in the browser network tab
- Dashboard shows the topbar with role-correct links for a manager and for a technician
- Theme toggle cycles Auto → Jasny → Ciemny, survives a reload, and causes no light flash on reload in dark mode; with OS set to dark and toggle on Auto, the page is dark
- With JavaScript disabled, the toggle is not visible and the page still follows the OS theme

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful before proceeding to the next phase.

---

## Phase 2: Migrate parts, deliveries and purchasing views

### Overview

Move the 8 inventory-side templates onto the shared head, topbar and component classes.

### Changes Required:

#### 1. Templates

**File**: `src/main/resources/templates/parts-list.html`, `parts-new.html`, `parts-edit.html`, `parts-correction.html`, `parts-import.html`, `parts-import-preview.html`, `deliveries-new.html`, `purchasing-list.html`

**Intent**: Apply the design system consistently. In each template:
- Replace `<head>` (including the inline `<style>`) with the `head` fragment, and add `topbar`.
- Drop "Powrót do pulpitu".
- Make page-level actions `.btn` links. Wrap row actions in `.actions` instead of `style="display:inline"`.
- Make Dezaktywuj a `.btn-danger`.
- Render `error` and the named flashes (`deliveryReceived`, `stockCorrected`, `partsImported`) as alerts.
- Make "(nieaktywna)" / "NOWA" `.badge`s. Make the stock/reserved/available lines in `parts-correction` and `parts-import-preview` a `.stats` list.
- Show empty-list text with `.empty`. Wrap tables in `.table-wrap`.

**Contract**: No change to form `action`s, field `name`s/`id`s, `th:text` expressions or visible text. Elements matched by tests stay byte-identical: the `<td>…</td>` cells and the "Brak braków." text in `ShoppingListIntegrationTests`, and the `<strong>Lokalizacja</strong>` in `parts-import.html` along with the other `<strong>` column names in that `<li>`. The `PartsCatalogIntegrationTests`, `PartImportIntegrationTests`, `StockCorrectionIntegrationTests` and `DeliveryIntegrationTests` assertions stay green without edits. If one cannot, change the template, not the test, unless the assertion targets removed "Powrót do pulpitu" text.

### Success Criteria:

#### Automated Verification:

- `./mvnw verify` passes
- `grep -lE '<style|style="' src/main/resources/templates/{parts-*,deliveries-new,purchasing-list}.html` returns nothing

#### Manual Verification:

- Each of the 8 views looks consistent in light and dark at desktop and ~768px width; wide tables scroll inside `.table-wrap` instead of the page
- Parts list row actions sit on one line; Dezaktywuj is visually destructive
- CSV import error table, import preview with NOWA badges, and delivery form read cleanly

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful before proceeding to the next phase.

---

## Phase 3: Migrate projects, orders, picking and technicians views

### Overview

Move the remaining 11 production-side and account templates onto the design system.

### Changes Required:

#### 1. Templates

**File**: `src/main/resources/templates/projects-list.html`, `projects-new.html`, `project-detail.html`, `orders-list.html`, `orders-new.html`, `orders-detail.html`, `orders-cancel.html`, `picking-list.html`, `picking-detail.html`, `technicians-list.html`, `technicians-new.html`

**Intent**: Same treatment as Phase 2. Order and picking views additionally get:
- priority shown as `.badge-high`/`-normal`/`-low`, mapped from `order.priority`;
- "Zgłoszone" and completion-pending states as badges;
- order quantities/deadlines on `orders-detail` as a `.stats` list;
- cancel and reject actions as `.btn-danger`;
- technician deactivation as `.btn-danger` and "(nieaktywne)"-style labels as `.badge-muted`.

**Contract**:
- No change to routes, form fields, `th:text` expressions or visible text.
- The priority badge wraps the existing `th:text="#{orders.priority.…}"` in a `<span class="badge …">`, and the `<td>` keeps no attributes.
- Matched strings stay byte-identical. That covers `"<td>7 / 10</td>"`, `"<td>10 / 10</td>"`, `"<td>—</td>"`, `"<th>Do pobrania</th>"`, and the "Zbudowano: 7 z 10", "Zakończone częściowo" and PARTIAL_NOTE texts.
- `OrderListAndDetailIntegrationTests`, `OrderCompletionIntegrationTests`, `OrderCancelIntegrationTests`, `OrderChangeIntegrationTests`, `PickingListAndDetailIntegrationTests`, `Project*IntegrationTests` and `TechnicianAccountIntegrationTests` stay green.

### Success Criteria:

#### Automated Verification:

- `./mvnw verify` passes
- `grep -rlE '<style|style="' src/main/resources/templates` returns nothing

#### Manual Verification:

- Orders list shows both sections with priority badges; pending-review rows stand out
- Order detail, cancel and completion review screens: destructive actions are clearly distinct from confirm actions
- Picking detail pick forms sit inline in their rows and stay usable at ~768px width (technician tablet)
- Project detail (BOM, links) and technician list read cleanly in light and dark

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful before proceeding to the next phase.

---

## Phase 4: Convention guard, lessons update and final pass

### Overview

Make the new convention enforceable and retire the obsolete rule.

### Changes Required:

#### 1. Template convention test

**File**: `src/test/java/pl/regavio/stockahead/TemplateConventionTests.java`

**Intent**: Stop future templates from bypassing the shared head or reintroducing inline styles, which is the failure mode the old lessons rule patched by hand.

**Contract**: Plain JUnit 5 with no Spring context. It reads every `*.html` under `src/main/resources/templates/` except `fragments.html` and asserts:
- each file contains `fragments :: head(`;
- each file except `login.html` and `setup.html` contains `fragments :: topbar`;
- no file contains `<style` or `style="`.

Failure messages name the offending file.

#### 2. Lessons register

**File**: `context/foundation/lessons.md`

**Intent**: The "Center cell content in every table template" rule now contradicts the codebase.

**Contract**: Replace that section's Problem/Rule with the new convention. Every template uses `fragments :: head(...)` (+ `topbar` when authenticated). Styling goes only in `static/css/app.css` via tokens, never in inline `<style>`/`style=`. Do not add attributes to elements that tests match by exact markup. The rule is enforced by `TemplateConventionTests`. Keep the heading's place in the file. Use `/10x-lesson` conventions.

### Success Criteria:

#### Automated Verification:

- `./mvnw test -Dtest=TemplateConventionTests` passes
- `./mvnw verify` passes

#### Manual Verification:

- Full walkthrough of all 22 views as manager and as technician in light, dark and Auto at desktop and ~768px width; no unreadable contrast, no horizontally scrolling page body
- Keyboard-only pass: focus ring visible on nav links, buttons and inputs
- `lessons.md` reads correctly and no longer instructs copying a `<style>` block

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful.

---

## Testing Strategy

### Unit Tests:

- `TemplateConventionTests`: file-level convention checks (shared head, topbar, no inline styles).

### Integration Tests:

- `StaticResourceAccessTests`: anonymous 200 for `/css/app.css` and `/js/theme.js`, and the login page references both.
- All existing MockMvc integration tests are the regression net for markup changes. They must pass without assertion edits, except assertions on removed "Powrót do pulpitu" links, if any appear.

### Manual Testing Steps:

1. `./mvnw spring-boot:test-run`, open `/login` logged out: styled, assets load with 200.
2. Log in as manager: topbar shows all manager links; as technician: manager-only links absent.
3. Cycle the theme toggle on any page, reload, navigate: the choice persists, with no flash.
4. Set OS to dark with toggle on Auto: dark theme; force Jasny: light despite OS.
5. Shrink to ~768px: topbar wraps, tables scroll inside their wrapper, picking forms usable.

## Performance Considerations

One small CSS file and one small JS file, served with content-hash URLs, so they can be cached long-term. A render-blocking `theme.js` in `<head>` is intentional: it must be tiny, with no dependencies.

## Migration Notes

No data or schema changes. Rollback is a plain revert of the change's commits.

## References

- Related research: `context/changes/ui-enhancement/research.md`
- Test pattern for security slice: `src/test/java/pl/regavio/stockahead/security/SecurityRedirectTests.java:24-43`
- Current nav and role gating: `src/main/resources/templates/dashboard.html:12-23`
- Security chain: `src/main/java/pl/regavio/stockahead/security/SecurityConfig.java:24-33`
- Rule being replaced: `context/foundation/lessons.md` § "Center cell content in every table template"

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles. See `references/progress-format.md`.

### Phase 1: Foundation (stylesheet, theme script, fragments, security)

#### Automated

- [x] 1.1 `./mvnw test -Dtest=StaticResourceAccessTests` passes — 0d74b17
- [x] 1.2 `./mvnw verify` passes (all existing tests green) — 0d74b17

#### Manual

- [x] 1.3 `./mvnw spring-boot:test-run`: the login page is styled before logging in, with no 302 for CSS/JS in the browser network tab — 0d74b17
- [x] 1.4 Dashboard shows the topbar with role-correct links for a manager and for a technician — 0d74b17
- [x] 1.5 Theme toggle cycles Auto → Jasny → Ciemny, survives a reload, and causes no light flash on reload in dark mode; with OS set to dark and toggle on Auto, the page is dark — 0d74b17
- [x] 1.6 With JavaScript disabled, the toggle is not visible and the page still follows the OS theme — 0d74b17

### Phase 2: Migrate parts, deliveries and purchasing views

#### Automated

- [x] 2.1 `./mvnw verify` passes — cfd81d6
- [x] 2.2 `grep -lE '<style|style="' src/main/resources/templates/{parts-*,deliveries-new,purchasing-list}.html` returns nothing — cfd81d6

#### Manual

- [x] 2.3 Each of the 8 views looks consistent in light and dark at desktop and ~768px width; wide tables scroll inside `.table-wrap` instead of the page — cfd81d6
- [x] 2.4 Parts list row actions sit on one line; Dezaktywuj is visually destructive — cfd81d6
- [x] 2.5 CSV import error table, import preview with NOWA badges, and delivery form read cleanly — cfd81d6

### Phase 3: Migrate projects, orders, picking and technicians views

#### Automated

- [x] 3.1 `./mvnw verify` passes — 4085e38
- [x] 3.2 `grep -rlE '<style|style="' src/main/resources/templates` returns nothing — 4085e38

#### Manual

- [x] 3.3 Orders list shows both sections with priority badges; pending-review rows stand out — 4085e38
- [x] 3.4 Order detail, cancel and completion review screens: destructive actions are clearly distinct from confirm actions — 4085e38
- [x] 3.5 Picking detail pick forms sit inline in their rows and stay usable at ~768px width (technician tablet) — 4085e38
- [x] 3.6 Project detail (BOM, links) and technician list read cleanly in light and dark — 4085e38

### Phase 4: Convention guard, lessons update and final pass

#### Automated

- [x] 4.1 `./mvnw test -Dtest=TemplateConventionTests` passes
- [x] 4.2 `./mvnw verify` passes

#### Manual

- [x] 4.3 Full walkthrough of all 22 views as manager and as technician in light, dark and Auto at desktop and ~768px width; no unreadable contrast, no horizontally scrolling page body
- [x] 4.4 Keyboard-only pass: focus ring visible on nav links, buttons and inputs
- [x] 4.5 `lessons.md` reads correctly and no longer instructs copying a `<style>` block
