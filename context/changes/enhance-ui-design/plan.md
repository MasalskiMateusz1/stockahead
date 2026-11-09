# Enhance UI Design (orders-list + global tokens) Implementation Plan

## Overview

Make the app read as a modern product rather than a default browser document, starting with one view: `orders-list` (`GET /orders`, manager only). The global token blocks in `src/main/resources/static/css/app.css` get a type, spacing and shadow scale plus one blue accent. orders-list then gets compact, aligned tables and a single row-action style. The manager also sees a success message after confirming or rejecting a completion report. The motif is Linear/Vercel-like: monochrome surfaces, one sharp accent, thin borders, compact rows and a tight type scale. Everything stays on Thymeleaf + plain CSS with no build step and no new dependency (decided in `change.md`, 2026-11-09).

## Current State Analysis

- `app.css` (695 lines) defines only colour tokens plus `--radius` (`app.css:19-44`). The font stack, heading sizes, small sizes and gaps are literal values (`app.css:123,135-149,210,341,411,424,609`).
- The dark palette is declared twice, once as the OS-dark media block (`app.css:46-73`) and once as the forced-dark block (`app.css:75-100`). Every dark-token edit has to be made in both.
- `--primary` is near-black in light and near-white in dark (`app.css:26,54,82`). Every `.btn` and bare `button` defaults to it (`app.css:334-352`), so "Nowe zlecenie" and the row's "Sprawdź" look identical. There is no small or ghost button variant.
- Every `td`/`th` is centred (`app.css:194-197`). Numbers and dates use proportional digits.
- `orders-list.html` uses `class="btn"` for the pending-row link (`:43`) and `class="btn btn-secondary"` for the open-row link (`:74`). Both open the same order.
- `orders-list.html` does not include `fragments :: alerts` and renders no success message. `GET /orders` (`OrderController.java:125-135`) never sets `error`.
- Contrast of `--input`/`--border`: about 1.25:1 in light and 1.4:1 in dark (research §3), below WCAG 1.4.11's 3:1 for controls.

### Correction to charges.md C5

`charges.md` C5 says create, change and cancel redirect to `/orders`. The code says otherwise. Create redirects to `/orders/{id}` (`OrderController.java:613`), change and assign to `/orders/{id}` (`:231`, `:278`), and cancel to `/orders/{id}` (`:364`). The detail page already includes the alerts fragment (`orders-detail.html:13`). Only **confirm-completion** and **reject-completion** redirect to `/orders` (`OrderController.java:453`, asserted in `OrderCompletionIntegrationTests.java:263-273`). This plan's feedback work targets those two.

## Desired End State

On `/orders` in both themes:
- Text uses the system sans stack and a 5-step type scale, and spacing follows a 4px scale.
- "Nowe zlecenie" is the only solid blue element on the page.
- Row links in both tables are the same small ghost button.
- Text columns are left-aligned, and quantity, built and date columns are right-aligned with tabular digits.
- Pending rows keep their amber tint and bar.
- After confirming or rejecting a completion report, the manager lands on the list with a green success alert naming the project.

Other views pick up the new tokens (accent, type, input outline) but keep their own layout, and their tables stay centred.

`./mvnw verify` passes. A before/after screenshot set of every state in `charges.md`, in light and dark at desktop width and 390px, sits under `context/changes/enhance-ui-design/screenshots/`.

### Key Discoveries:

- Success messages already follow a pattern: `RedirectAttributes.addFlashAttribute("success", …)` (`account/OperatorCompanyController.java:55-60`), a hand-written `<p th:if="${success}" class="alert alert-success" role="status">` (`templates/operator-companies.html:14`), and a `flash().attribute("success", …)` assertion (`OperatorCompanyLifecycleIntegrationTests.java:230`).
- `transitionReportedOrder` (`OrderController.java:449-460`) is shared by confirm and reject and fixes the success view to `"redirect:/orders"`. The project name is reachable on the re-read order inside the transition lambda.
- Tests pin markup. `<tr>`/`<td>`/`<th>` must have no attributes (research §5; `OrderCompletionIntegrationTests.java:650,692,849,861`). The ids `pending-orders`/`open-orders` and the empty-state text `Brak zleceń.` must stay. Only `OperatorAccessIntegrationTests.java:183` matches a literal `<table>`, and only on the operator view.
- `TemplateConventionTests` forbids `<style` and `style="` in templates. All styling goes in `app.css` (`lessons.md` "Build every template on the shared head and stylesheet").
- The app runs locally with `./mvnw spring-boot:test-run` against a throwaway Postgres container (AGENTS.md). The database starts empty.

## What We're NOT Doing

- No React, htmx, build step, Node or new dependency. No vendored font file: `--font-sans` lists Inter only as an optional locally installed font.
- No changes to views other than orders-list beyond what they inherit from tokens and base elements. The other 15 tables stay centred, because the new alignment is scoped to `table.data-table`.
- No change to `--info` (blue-700). It stays close in hue to the new blue `--primary` and is told apart by form (outlined badge vs. solid button). It is not used on orders-list. Recorded as a risk.
- No change to `--border` contrast. Decorative hairlines stay subtle. Only `--input`, which identifies form controls, is raised to ≥3:1.
- No styled error page (`templates/error.html`) and no fix for the technician-sees-Whitelabel case. That is a global entry-point change, deferred to its own change.
- No success messages for create, change, assign or cancel (they land on the detail page). No extension of `fragments :: alerts` with a success variant: `operator-companies.html` includes the fragment *and* its own success `<p>`, so extending the fragment would render that view's message twice.
- No topbar redesign: no collapse menu, no active-nav state, no `aria-current`. The topbar only inherits tokens.
- No automated screenshot or visual-regression tests (declined again; recorded decision in `context/archive/2026-10-03-ui-enhancement/plan.md:39-47`).
- No change to the `th:classappend="|badge-${priority}|"` coupling. It is a known risk and is left as is.

## Implementation Approach

Contract before pixels. Phase 1 changes only tokens and base elements, after first capturing a "before" baseline. Phase 2 adds the reusable pieces (`.btn-sm`, `.btn-ghost`, `table.data-table`) and applies them to orders-list. Phase 3 is the only Java change: two flash messages and their tests. Phase 4 runs the visual check across the states listed in `charges.md`.

The dark palette is merged into `light-dark()` values before any dark value changes. That way the accent and input values are set once per theme.

## Critical Implementation Details

- **Dark palette merge**: CSS cannot share one declaration block between a selector inside `@media (prefers-color-scheme: dark)` and one outside it. Use `light-dark()` instead. `theme.js` only sets `data-theme="light"|"dark"` or removes it (`theme.js:32-38`). So:
  - `:root` declares `color-scheme: light dark` (auto, which follows the OS).
  - `:root[data-theme="light"]` declares `color-scheme: light`, and `:root[data-theme="dark"]` declares `color-scheme: dark`.
  - Every colour token is written once in `:root` as `light-dark(<light>, <dark>)`.

  This deletes both dark blocks and needs no change to `theme.js`. `light-dark()` only accepts colours: shadow colours can use it inside `--shadow-*`, and non-colour tokens are theme-independent anyway. Browser support is Chrome 123, Firefox 120 and Safari 17.5 (all 2024), which is acceptable for an internal tool in 2026. The contrast comments then list "light X:1 / dark Y:1" per token.
- **Bare `button` shares `.btn` styling** (`app.css:334-358`). `.btn-ghost` and `.btn-sm` must work on both `a.btn` and `button` and must override the primary border colour, not only the background.

## Phase 1: Baseline + tokens

### Overview

Capture the "before" state. Then add the missing token scales and the blue accent, raise `--input` contrast, and make base elements read the tokens. No template changes.

### Changes Required:

#### 1. Baseline screenshots

**File**: `context/changes/enhance-ui-design/screenshots/before/` (new folder)

**Intent**: Record how orders-list looks today so Phase 4 has something to compare against. Two other views (dashboard, parts-list) are recorded too, to detect side effects of the global tokens.

**Contract**: PNG files named `<view>-<state>-<theme>-<width>.png`, for example `orders-list-default-light-1280.png`. Capture orders-list default in light and dark at 1280px and 390px, plus dashboard and parts-list default in light at 1280px. Data setup: run `./mvnw spring-boot:test-run`, go through `/setup`, then create a project with a BOM, two parts with stock, a technician, and at least three open orders with different priorities. Have the technician report completion on one of them so `#pending-orders` appears. Use browser automation (Claude in Chrome) or capture by hand.

#### 2. Dark palette deduplication

**File**: `src/main/resources/static/css/app.css`

**Intent**: Make each dark token set once, so the C2 accent and the `--input` change can't drift between "OS dark" and "forced dark".

**Contract**: The two dark blocks (`app.css:46-100`) are replaced by `light-dark()` token values in `:root` plus the `color-scheme` switch on `data-theme` (see Critical Implementation Details). Theme behaviour is unchanged: auto follows the OS, and light/dark are forced by the toggle. The header comment (`app.css:12-16`) is updated to describe the new mechanism. Do this first, carrying today's values over unchanged, and check the three theme modes before changing any value.

#### 3. Non-colour tokens (C1)

**File**: `src/main/resources/static/css/app.css` (`:root` block)

**Intent**: Give the page one type, spacing and elevation system so sizes and gaps stop being chosen per component.

**Contract**: New theme-independent tokens in `:root`:
- `--font-sans`: Inter (only if installed locally) → `ui-sans-serif` → `system-ui` → the current fallbacks.
- `--font-mono`: `ui-monospace` → `SFMono-Regular` → `Menlo` → `Consolas` → `monospace`.
- A 5-step type scale, `--text-xs` through `--text-xl`, roughly 0.75 / 0.8125 / 0.875 / 1 / 1.5rem. The body size is `--text-base`.
- A 4px spacing scale, `--space-1` through `--space-8` (0.25rem steps, with larger steps as needed).
- `--shadow-sm` and `--shadow-md`, subtle and low-alpha. The dark theme can override them with stronger alpha.

`--radius` drops to a sharper value (≈0.5rem), in keeping with the motif.

#### 4. Accent (C2) and input contrast

**File**: `src/main/resources/static/css/app.css` (light and dark token blocks)

**Intent**: Make the page's single main action blue in both themes, keep the focus ring visible, and make form-field outlines findable.

**Contract**:
- `--primary` becomes blue: Tailwind blue-600 range in light (≈`oklch(0.546 0.245 262.9)`), a lighter blue in dark.
- `--primary-foreground` must reach ≥4.5:1 on `--primary` in each theme. `--ring` is blue and ≥3:1 on `--background` in each theme.
- `--input` reaches ≥3:1 on `--background` in each theme. `--border` is unchanged.
- `--secondary`, `--accent`, `--muted` and the status colours are unchanged.
- Each new value gets a contrast comment in the existing style ("X:1 on --background"), computed from oklch → sRGB like the existing ones.

#### 5. Base elements read the tokens

**File**: `src/main/resources/static/css/app.css` (base, buttons, topbar, alerts, badges sections)

**Intent**: Make the tokens visible everywhere without touching templates.

**Contract**:
- `body` uses `--font-sans` and `--text-base`.
- `h1`/`h2`/`h3` map onto the type scale, with tighter letter-spacing on h1.
- `small`/`.hint`, `label`, `.btn`, `.badge`, `.alert`, `th` and topbar text sizes use `--text-*` instead of literal rem values.
- Gaps and padding in those same rules use `--space-*`.
- `.card` and `.table-wrap` may use `--shadow-sm`.
- The narrow-screen media query keeps working.

No selector is renamed, and no class is removed.

### Success Criteria:

#### Automated Verification:

- Full build and tests pass: `./mvnw verify`
- `TemplateConventionTests` passes (no inline styles introduced)
- The literal font stack and the h1/h2/h3 literal sizes are gone from `app.css`: `grep -nE 'font-family: system-ui|font-size: 1\.(75|3|1)rem' src/main/resources/static/css/app.css` returns nothing
- Dark tokens exist only as `light-dark()` values: `grep -c 'prefers-color-scheme' src/main/resources/static/css/app.css` returns 0

#### Manual Verification:

- The `screenshots/before/` set exists with the files listed in change 1
- orders-list, dashboard and parts-list render in light, dark and auto with no flash of the wrong theme, and the theme toggle still cycles auto → light → dark
- "Nowe zlecenie" is blue in both themes, and its text is readable
- The focus ring is visible (Tab onto "Nowe zlecenie") in both themes
- Form-field outlines on `/orders/new` are clearly visible in both themes
- The contrast numbers in the new comments were checked with a contrast tool (e.g. browser devtools), not only estimated

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful before proceeding to the next phase.

---

## Phase 2: Shared components + orders-list

### Overview

Add the reusable row-action button and data-table styles (C3, C4) and apply them to orders-list without touching the markup that tests match.

### Changes Required:

#### 1. Button variants (C3)

**File**: `src/main/resources/static/css/app.css` (buttons section)

**Intent**: Give row-level actions a quiet, compact style so they stop competing with the page's main action. Any list view can reuse it.

**Contract**:
- `.btn-sm`: smaller padding and `--text-xs`/`--text-sm`, height ≈28px.
- `.btn-ghost`: transparent background, `--foreground` text, a transparent or `--border` border, and `--accent` background on hover.
- Both combine with `.btn` and work on `a` and `button`.
- Focus-visible keeps the ring.

#### 2. Data table (C4)

**File**: `src/main/resources/static/css/app.css` (tables section)

**Intent**: Make columns scannable. Text reads left, and numbers and dates line up at the right edge. Only opted-in tables are affected.

**Contract**:
- New `table.data-table` rules: `th`/`td` left-aligned, compact vertical padding from `--space-*`, `th` in `--text-xs` uppercase or semibold muted style, and a subtle row hover.
- Numeric columns are right-aligned with `font-variant-numeric: tabular-nums`, selected by column position and scoped to the section ids, because cells cannot carry classes:
  - `#pending-orders`: columns 2 (Liczba sztuk), 4 (Wymagany termin), 6 (Zgłoszono), 7 (Zbudowano)
  - `#open-orders`: columns 2 and 4
- The last (action) column is right-aligned.
- `tr.row-pending` tint and leading bar keep working inside `.data-table`.
- The base `td, th { text-align: center }` rule stays for all other tables.

Column positions come from `orders-list.html:24-31` and `:59-64`. If a column is added to either table later, these selectors must be updated, so put a comment next to them saying so.

#### 3. orders-list template

**File**: `src/main/resources/templates/orders-list.html`

**Intent**: Opt the two tables into the new styles and give both row links the same quiet variant.

**Contract**:
- Both `<table>` elements get `class="data-table"`.
- Both row links become `class="btn btn-ghost btn-sm"`, keeping their texts "Sprawdź"/"Szczegóły" and hrefs.
- Nothing is added to `<tr>`, `<td>` or `<th>`. Ids, the `row-pending` class, the badge markup and the `Brak zleceń.` text are unchanged.
- The page header (h1 + `.page-actions`) may be laid out as one row with the button aligned right, using a CSS rule on existing classes. No new wrapper element is needed unless it carries only a class.

### Success Criteria:

#### Automated Verification:

- Full build and tests pass: `./mvnw verify` (in particular `OrderCompletionIntegrationTests`, `OrderListAndDetailIntegrationTests`, `OrdersCompanyIsolationIntegrationTests`)
- No attributes on table rows or cells in orders-list: `grep -nE '<t[rdh] ' src/main/resources/templates/orders-list.html` lists only the existing `<tr class="row-pending" th:each=…>` and `<tr th:each=…>` lines (the `th:` attributes are Thymeleaf-processed and do not reach the output; verify the rendered HTML in tests still has bare `<tr>`/`<td>`)

#### Manual Verification:

- In both tables, project and technician columns are left-aligned. Quantity, built and dates are right-aligned and line up digit by digit
- "Sprawdź" and "Szczegóły" look identical (small ghost buttons). "Nowe zlecenie" is the only filled blue button on the page
- Pending rows still show the amber tint and leading bar
- Other list views (parts-list, projects-list, picking-list) are unchanged in layout. Their tables are still centred

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful before proceeding to the next phase.

---

## Phase 3: Feedback after confirm/reject (C5)

### Overview

Confirming or rejecting a completion report redirects to `/orders` today with no feedback. Add success flashes for both and render them, together with the standard alerts fragment, on orders-list.

### Changes Required:

#### 1. Message keys

**File**: `src/main/resources/messages.properties`

**Intent**: Polish success texts that name the project, so the manager knows which order the action applied to.

**Contract**: Two new keys with the project name as `{0}`:
- `orders.completion.confirmed`, e.g. "Potwierdzono zakończenie zlecenia projektu {0}."
- `orders.completion.rejected`, e.g. "Odrzucono zgłoszenie zakończenia zlecenia projektu {0}."

Mind `MessageFormat` apostrophe escaping if the wording changes.

#### 2. Controller flashes

**File**: `src/main/java/pl/regavio/stockahead/orders/OrderController.java` (`confirmCompletion` `:155`, `rejectCompletion` `:183`, `transitionReportedOrder` `:449`)

**Intent**: On a successful confirm or reject, add a `success` flash attribute with the matching message. The redirect to `/orders` stays. Failure paths (which re-render the detail page with `error`) are unchanged.

**Contract**:
- Both handlers gain a `RedirectAttributes` parameter.
- The flash attribute is named `success`, following `OperatorCompanyController`.
- The project name is read from the order re-read under the lock (inside the transition), not from a pre-lock read.
- The redirect target `"redirect:/orders"` is unchanged.

#### 3. orders-list renders alerts

**File**: `src/main/resources/templates/orders-list.html`

**Intent**: Show the success message, and any `error`, at the top of the list in the same style as other views.

**Contract**:
- Under the `<h1>`, add `<div th:replace="~{fragments :: alerts}"></div>`, followed by `<p th:if="${success}" class="alert alert-success" role="status" th:text="${success}"></p>`. This is the same order and markup as `operator-companies.html:13-14`.
- `fragments.html` is not changed.

#### 4. Tests

**Files**:
- `src/test/java/pl/regavio/stockahead/orders/OrderCompletionIntegrationTests.java`
- `src/test/java/pl/regavio/stockahead/MessagesBundleTests.java`

**Intent**: Pin the new behaviour: the right flash on each success path, no flash on failure, and the message rendered on the list.

**Contract**:
- The `confirm`/`reject` helpers (`:262-273`), or dedicated tests, assert `flash().attribute("success", <expected text with project name>)`.
- One test follows the redirect (GET `/orders` with the flash) and asserts the rendered `alert alert-success` containing the text.
- A failure case (order not reported) asserts no `success` flash.
- `MessagesBundleTests` gets cases for both keys, following its existing per-key pattern.

### Success Criteria:

#### Automated Verification:

- New and updated tests pass: `./mvnw test -Dtest=OrderCompletionIntegrationTests,MessagesBundleTests`
- Full build and tests pass: `./mvnw verify`

#### Manual Verification:

- Confirming a reported order lands on `/orders` with a green success alert naming the project. Refreshing the page removes it
- Rejecting a reported order shows the reject message the same way
- The alert reads well in light and dark and at 390px

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful before proceeding to the next phase.

---

## Phase 4: Visual gate

### Overview

Check the finished view against every state in `charges.md` in both themes and both widths, record the results, and confirm that untouched views did not regress.

### Changes Required:

#### 1. After screenshots + state checklist

**File**: `context/changes/enhance-ui-design/screenshots/after/` (new folder) and `context/changes/enhance-ui-design/visual-gate.md` (new)

**Intent**: Evidence that the restyle covers every named state and did not break neighbours. This is the screenshot gate agreed in `/10x-ui`.

**Contract**:
- Screenshots in the Phase 1 naming scheme for orders-list in light and dark at 1280 and 390px, one per state:
  - default (both sections populated)
  - hover (row, ghost button)
  - focus (ghost row link, "Nowe zlecenie")
  - success (after confirm)
  - empty (no open orders, no pending section)
  - error (no route sets `error` on the list; record as "fragment present, not reachable from a GET")
- Disabled and loading are recorded as n/a, with the reason given in `charges.md`.
- Dashboard and parts-list default in light at 1280, for comparison with `before/`.
- `visual-gate.md` holds a table with one row per state × theme × width (pass or note). It also lists the measured contrast numbers for `--primary-foreground`/`--primary`, `--ring`/`--background` and `--input`/`--background` in both themes.

#### 2. Charge list closure

**File**: `context/changes/enhance-ui-design/charges.md`

**Intent**: Mark C1–C5 as resolved or partially resolved, with a pointer to the evidence. Record the C5 scope correction and the decisions about `--border` and `--info`.

**Contract**: One status line appended per charge. The deferred list gains "styled error page", "flash on detail-page actions" and "`--info` vs blue `--primary`".

### Success Criteria:

#### Automated Verification:

- Full build and tests pass: `./mvnw verify`
- Gate artefacts exist: `ls context/changes/enhance-ui-design/screenshots/after/ context/changes/enhance-ui-design/visual-gate.md`

#### Manual Verification:

- Every state × theme × width row in `visual-gate.md` is pass or has an accepted note
- Side by side with `before/`, orders-list reads as clearly more modern: blue main action, aligned columns, compact rows, consistent type
- Dashboard and parts-list show only token-level differences (type, accent, input outline) and no broken layout
- At 390px the tables scroll horizontally inside `.table-wrap` without breaking the page, and the header button stays reachable
- The contrast numbers recorded in `visual-gate.md` meet the thresholds (4.5:1 text, 3:1 ring and input)

**Implementation Note**: This is the final phase. Manual confirmation closes the plan.

---

## Testing Strategy

### Unit Tests:

- `MessagesBundleTests`: both new keys resolve to the expected Polish text with a project-name argument.

### Integration Tests:

- `OrderCompletionIntegrationTests`:
  - confirm → `success` flash with the confirm text
  - reject → `success` flash with the reject text
  - a not-reported order → error re-render, no flash
  - GET `/orders` carrying the flash renders `alert alert-success` with the text
- The existing suites that assert bare `<tr>`/`<td>` markup on orders-list stay green unchanged. They are the regression guard for the "no attributes on cells" rule.

### Manual Testing Steps:

1. `./mvnw spring-boot:test-run`. Set up a manager, a technician, a project with a BOM, stock, and three open orders (high/normal/low).
2. As the technician, pick some parts and report completion on one order. As the manager, open `/orders` and check the pending table and alignment.
3. Confirm that order. Check the success alert, then refresh and check it is gone.
4. Report again on another order and reject it. Check the reject message.
5. Cycle the theme toggle through auto, light and dark. At 390px width, repeat the checks on the default and success states.
6. Tab through the page and check the focus ring on "Nowe zlecenie" and the row links.

## Performance Considerations

None of note. The changes are CSS-only, apart from two flash attributes. No font file is downloaded.

## Migration Notes

No data or schema migration. Rollback is a revert of `app.css`, `orders-list.html`, `messages.properties` and the two controller methods.

## References

- Research: `context/changes/enhance-ui-design/research.md`
- Charge list: `context/changes/enhance-ui-design/charges.md`
- Prior UI change: `context/archive/2026-10-03-ui-enhancement/plan.md`, `plan-brief.md`
- Success-flash pattern:
  - `src/main/java/pl/regavio/stockahead/account/OperatorCompanyController.java:55-60`
  - `src/main/resources/templates/operator-companies.html:13-14`
  - `src/test/java/pl/regavio/stockahead/account/OperatorCompanyLifecycleIntegrationTests.java:230`
- Template rules: `context/foundation/lessons.md` ("Build every template on the shared head and stylesheet"), `src/test/java/pl/regavio/stockahead/TemplateConventionTests.java`

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles. See `references/progress-format.md`.

### Phase 1: Baseline + tokens

#### Automated

- [x] 1.1 Full build and tests pass: `./mvnw verify` — 6df09e9
- [x] 1.2 `TemplateConventionTests` passes (no inline styles introduced) — 6df09e9
- [x] 1.3 The literal font stack and the h1/h2/h3 literal sizes are gone from `app.css` — 6df09e9
- [x] 1.4 Dark tokens exist only as `light-dark()` values — 6df09e9

#### Manual

- [x] 1.5 The `screenshots/before/` set exists with the files listed in change 1 — 6df09e9
- [x] 1.6 orders-list, dashboard and parts-list render in light, dark and auto with no flash of the wrong theme, and the theme toggle still cycles auto → light → dark — 6df09e9
- [x] 1.7 "Nowe zlecenie" is blue in both themes, and its text is readable — 6df09e9
- [x] 1.8 The focus ring is visible (Tab onto "Nowe zlecenie") in both themes — 6df09e9
- [x] 1.9 Form-field outlines on `/orders/new` are clearly visible in both themes — 6df09e9
- [x] 1.10 The contrast numbers in the new comments were checked with a contrast tool, not only estimated — 6df09e9

### Phase 2: Shared components + orders-list

#### Automated

- [x] 2.1 Full build and tests pass: `./mvnw verify`
- [x] 2.2 No attributes on table rows or cells in orders-list

#### Manual

- [x] 2.3 In both tables, text columns are left-aligned and quantity, built and dates are right-aligned with aligned digits
- [x] 2.4 "Sprawdź" and "Szczegóły" look identical, and "Nowe zlecenie" is the only filled blue button on the page
- [x] 2.5 Pending rows still show the amber tint and leading bar
- [x] 2.6 Other list views are unchanged in layout, and their tables are still centred

### Phase 3: Feedback after confirm/reject (C5)

#### Automated

- [ ] 3.1 New and updated tests pass: `./mvnw test -Dtest=OrderCompletionIntegrationTests,MessagesBundleTests`
- [ ] 3.2 Full build and tests pass: `./mvnw verify`

#### Manual

- [ ] 3.3 Confirming a reported order lands on `/orders` with a green success alert naming the project, and a refresh removes it
- [ ] 3.4 Rejecting a reported order shows the reject message the same way
- [ ] 3.5 The alert reads well in light and dark and at 390px

### Phase 4: Visual gate

#### Automated

- [ ] 4.1 Full build and tests pass: `./mvnw verify`
- [ ] 4.2 Gate artefacts exist: `screenshots/after/` and `visual-gate.md`

#### Manual

- [ ] 4.3 Every state × theme × width row in `visual-gate.md` is pass or has an accepted note
- [ ] 4.4 Side by side with `before/`, orders-list reads as clearly more modern
- [ ] 4.5 Dashboard and parts-list show only token-level differences and no broken layout
- [ ] 4.6 At 390px the tables scroll inside `.table-wrap` without breaking the page, and the header button stays reachable
- [ ] 4.7 The recorded contrast numbers meet the thresholds (4.5:1 text, 3:1 ring and input)
