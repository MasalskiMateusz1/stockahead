---
date: 2026-11-09T17:47:06+01:00
researcher: Mateusz Masalski (with Claude)
git_commit: 3b21cf385428b27b9b6bdcd6b17f056c71061f22
branch: multi-company-support
repository: stockahead
topic: "Quality of the current UI, and what moving it to a framework such as React would take"
tags: [research, ui, css, thymeleaf, design-system, accessibility, react, spa, migration]
status: complete
last_updated: 2026-11-09
last_updated_by: Mateusz Masalski (with Claude)
last_updated_note: "Rebuilt 2026-11-09T17:50 as one document: the first version (UI audit, 17:38) was deleted from disk before the React follow-up was appended; its verified findings are restored in Part 1."
---

# Research: Quality of the current UI, and moving it to a framework such as React

**Date**: 2026-11-09T17:47:06+01:00
**Researcher**: Mateusz Masalski (with Claude)
**Git Commit**: 3b21cf3
**Branch**: multi-company-support
**Repository**: stockahead

## Research Question

"Investigate quality of current UI, and how to change it into frameworks such as React." Part 1 is a full UI audit: the design system (`app.css`, `theme.js`, `fragments.html`), consistency across the 27 view templates, prior UI decisions and markup pinned by tests. Part 2 covers what moving to React (or another framework) would involve: server coupling, test coupling, build/deploy, and the options.

Paths are relative to `src/main/resources/` unless they start with `src/`, `context/` or a repo-root file name.

## Summary

- **Quality:** a working design system exists (ui-enhancement, 2026-10-03): plain CSS, shadcn "Neutral" oklch colour tokens, light/dark with an Auto→Light→Dark toggle, shared head/topbar/alerts fragments, no build step. All 27 views pass `TemplateConventionTests`. Its gaps: tokens cover colour only; behaviour across pages is inconsistent (alerts, success feedback, status badges, destructive-action confirmation, button roles, hard-coded vs keyed text); phone use is weak (one breakpoint, wrapping nav, small touch targets); some accessibility gaps (faint input borders, no skip link, no field-level errors).
- **React:** a full React SPA means rewriting the web layer and roughly 470 tests, not swapping templates: 63 of 65 handlers render HTML or redirect, validation and transactions live in the controllers, and ~61% of test methods assert HTML. Cheaper paths keep Thymeleaf as the shell and add JS only where it pays (a no-build library, or Vite-built React islands on a few pages).
- **Realtime gap:** the PRD's <2 s live refresh of reservations and the shopping list is not implemented in any form. It is the strongest functional argument for adding JavaScript, and it does not require React.

# Part 1 — Quality of the current UI

## 1. Stylesheet, tokens and theming (`static/css/app.css`, 695 lines; `static/js/theme.js`)

- **Colour tokens**: background, foreground, card, primary, secondary, muted, accent, border, input, ring, destructive, success, warning, info (light `app.css:19-44`). Popover/chart/sidebar tokens skipped on purpose (`app.css:6`).
- **The dark palette is declared twice**: `@media (prefers-color-scheme: dark) :root:not([data-theme="light"])` (`app.css:46-73`) and `:root[data-theme="dark"]` (`app.css:75-100`); every dark-token edit must be made in both.
- **theme.js** cycles auto/light/dark (`theme.js:8,58`), stores `stockahead-theme` in localStorage (`theme.js:20-29`), runs blocking in `<head>` to avoid a flash (`fragments.html:8`, `theme.js:49-50`), and un-hides the toggle only when JS runs (`fragments.html:32`, `theme.js:56`).
- **Non-colour values**: only `--radius` is a token (`app.css:21`). Font stack hard-coded (`app.css:123`); h1 1.75rem / h2 1.3rem / h3 1.1rem (`app.css:128-149`), h4+ unstyled; small sizes 0.9 / 0.875 / 0.78rem chosen per component; gaps use nine different rem values (0.25–1.25rem). No spacing, type-scale, shadow or z-index tokens. No literal hex/rgb colours.
- **Component classes** (every defined class is used by some template; no template uses an undefined class): `.topbar`, `.container` (72rem), `.page-actions`, `.card`, `.auth-page`, `.tiles`, `.btn` (+`-secondary`, `-danger`, `-icon`), `.alert` (+`-error`, `-success`, `-info`), `.badge` (+`-high`, `-normal`, `-low`, `-muted`, `-info`), `.stats`, `.summary`, `.empty`, `.form-field`, `.form-inline`, `.form-trap`, `.hint`, `.actions`, `.table-wrap`, `tr.row-pending`, `.icon`.
- **Dynamic coupling**: `.badge-high/-normal/-low` are reached only via `th:classappend="|badge-${priority}|"` (`templates/orders-list.html:38,71`, `picking-list.html:48`, `orders-detail.html:19`, `picking-detail.html:19`); renaming the Priority enum would silently unstyle them.
- **Tables**: every `td`/`th` is centred (`app.css:194-197`), so numbers are not right-aligned. All 16 tables sit in `.table-wrap` (horizontal scroll).
- **Missing** (searched `app.css` and templates): `.alert-warning`, success/danger badges, `:disabled` button style, button sizes, page-header component, breadcrumbs, pagination, active-nav state, fieldset/legend styling, field-level error style, skip link, `.sr-only`.

## 2. Responsive behaviour

- One media query, `max-width: 48rem` (`app.css:679-695`), which only reduces padding and the h1 size.
- The topbar nav wraps via flex (`app.css:386,403`); a manager's 8 links (`templates/fragments.html:15-24`) stack into several rows on a phone. No collapse menu (explicitly not done in ui-enhancement, `context/archive/2026-10-03-ui-enhancement/plan.md:39-47`).
- Touch targets read from CSS (not rendered): button ≈34px (`app.css:344`), nav link padding 0.2rem (`app.css:412`), `.btn-icon` 36px. `white-space: nowrap` on buttons and badges (`app.css:351,611`) widens tables.
- No PRD names a device; the 768px tablet target exists only in the ui-enhancement plan.

## 3. Accessibility

- Focus: `:focus-visible` 2px `--ring` outline (`app.css:173-181`), raised to ≥3:1 in ui-enhancement impl-review F1 (`context/archive/2026-10-03-ui-enhancement/reviews/impl-review.md:30-42`).
- Rough contrast estimates (by hand, not tool-measured): light `--input`/`--border` on white ≈1.25:1, dark border ≈1.4:1 — below the 3:1 asked for UI components (WCAG 1.4.11). Light muted text ≈4.7:1 on white but ≈4.3:1 on `--muted`/`--accent` (hovered rows `app.css:213-215`, `th`, `.summary`).
- Each of the 27 views has one `<main>`. A grep of `templates/` and `static/` finds no skip link, `aria-current` or `th:errors`.
- The operator confirmation intro `alert-info` has no `role` (`templates/operator-companies-block.html:20`).

## 4. Template consistency (27 views)

`TemplateConventionTests` (`src/test/java/pl/regavio/stockahead/TemplateConventionTests.java`) checks by substring only: `fragments :: head(` (:51), `fragments :: topbar` unless the view is login, setup, register or awaiting-approval (:29-30, :54), no `<style` (:57), no `style="` (:60).

1. **Alerts**: the fragment (`fragments.html:43`) renders only `${error}`. 12 views do not include it: awaiting-approval, dashboard, login, operator-companies-block, operator-companies-reject, orders-list, parts-import, parts-import-preview, parts-list, picking-list, projects-list, purchasing-list. Success/info alerts are hand-written under different attribute names (`templates/parts-list.html:13-17`, `operator-companies.html:14-15`, `login.html:12-18`).
2. **Success feedback after POST**: `addFlashAttribute` appears only in CompanyAccountController (3, errors only), OperatorCompanyController (3), DeliveryController (1), PartImportController (1), StockCorrectionController (1). Project, BOM, link, order, picking and part create/edit/deactivate POSTs redirect with no confirmation message.
3. **Button roles**: row "view" links primary vs secondary on the same page (`orders-list.html:43` vs `:74`); filter buttons primary (`parts-list.html:36`, `picking-list.html:25`) vs secondary (`projects-list.html:24`); "Odblokuj" primary (`operator-companies.html:47`) vs "Reaktywuj" secondary (`accounts-list.html:35`).
4. **Destructive actions**: reject/block company and cancel order use confirmation pages; deactivate part (`parts-list.html:62-64`), delete BOM line/link (`project-detail.html:58,96`) and reject a completion report (`orders-detail.html:94`) POST immediately. The three confirmation pages use three layouts (`operator-companies-block.html:13-27`, `orders-cancel.html:15-52`, `parts-import-preview.html:50-59`).
5. **Statuses**: REJECTED and BLOCKED companies both `badge badge-muted` (`operator-companies.html:32-33`); order status is a plain `<p>` (`orders-detail.html:25-31`) while picking status is a badge (`picking-detail.html:23-25`); active/inactive wording and casing differ across accounts, projects, purchasing and order assignee; parts-list shows no status.
6. **Empty states**: parts-list and accounts-list have none.
7. **Text source**: 17 of 27 views contain no `#{...}` message expression; `operator-companies.html` is hard-coded while its block/reject pages are keyed.
8. **Forms and layout**: `form.card` is the norm with exceptions (`orders-cancel.html:15`, `deliveries-new.html:15`, `project-detail.html:17`); `.page-actions` sits above content on lists and below on forms.
9. **Small defects**: a manager dashboard tile links to `/manager/ping` (`dashboard.html:46`), which returns plain "pong"; the back link on `orders-new.html:56` goes to `/projects`; picking-detail rows drop a cell when no action is shown (`picking-detail.html:49,60`).

## 5. Markup pinned by tests (any restyle must preserve)

- No attributes on `<tr>`, `<td>`, `<th>`, `<dd>`, `<dt>`: tests split on literal `"<tr>"` and match bare cells in at least 12 test classes (e.g. `OperatorCompanyLifecycleIntegrationTests.java:163`, `CompanyAccountIntegrationTests.java:216-223`).
- Adjacent header cells with no whitespace (`OperatorCompanyLifecycleIntegrationTests.java:659`, `OperatorAccessIntegrationTests.java:144-145`); exact `Zablokuj`/`Odblokuj` controls (`OperatorCompanyLifecycleIntegrationTests.java:667,673`); `<header class="topbar">` (`OperatorAccessIntegrationTests.java:208`); title `… — Stockahead`; ids `to-pick-N`, `pending-orders`, `open-orders`, `order-assign`, `order-change`; exact `<select>`/`<option>` markup; honeypot wrapper `form-trap`; several empty-state strings.

## 6. Prior UI decisions

- `context/archive/2026-10-03-ui-enhancement/plan-brief.md:20-30`: shadcn Neutral tokens, own status colours at ≥4.5:1, plain CSS, native `th:fragment`, role-aware topbar, tile dashboard.
- `context/archive/2026-10-03-ui-enhancement/plan.md:39-47`: not done on purpose — screenshot tests, hamburger nav, server-side theme preference, copy/route changes.
- `context/archive/2026-10-05-company-boundary-harness/plan.md:44`: company name in the UI was a non-goal; no later change added it.
- `context/foundation/lessons.md:65-70`: shared head and stylesheet rule; it lists only login and setup as topbar-exempt, while the test now also exempts register and awaiting-approval.

# Part 2 — Moving the UI to React or another framework

**Question:** What would it take to move it to a client-side framework such as React?

**Answer in short:** A full React SPA means rewriting the web layer and roughly 470 tests, not swapping templates. The server renders HTML in 63 of 65 handlers, validation and transactions live in the controllers, and about 61% of test methods assert HTML. The cheaper paths keep Thymeleaf as the shell and add JavaScript only where it pays: either a no-build library (htmx/Alpine-style) or Vite-built React "islands" mounted on a few pages. The one real product gap a JS layer would close — the PRD's <2 s live refresh — is not implemented today in any form, and does not require React.

### F1. Server coupling (paths under `src/main/java/pl/regavio/stockahead/`)

- 17 controller classes; their files total 4,249 of 8,585 Java lines in `src/main/java` (wc, this commit). 63 of 65 handlers return a view name or `redirect:`; the exceptions are `account/ManagerPingController.java:12-19` (text "pong") and the CSV export `purchasing/ShoppingListController.java:39-47`. No `@ResponseBody`, `@ControllerAdvice` or JSON endpoint exists.
- Input is parsed by hand: ~47 `@RequestParam`s, many `String` parsed with `parseInt`/`LocalDate.parse`; dynamic fields via a param `Map` (`orders/OrderController.java:335`, `parts/DeliveryController.java:103`). No `@Valid`, `BindingResult` or DTO classes.
- Business logic sits in controllers: 12 of 17 controller files use `TransactionTemplate` and call repositories directly (grep, this commit); e.g. order cancel does stock returns and reallocation inline (`orders/OrderController.java:333-402`). Reusable services are few: `ReservationAllocator`, `CompanyLifecycle`, `LockRetry`, `OrderMoments`, `RegistrationRateLimiter`, `RegistrationBotCheck`. A REST layer would first need logic extracted into services (worker estimate: 20–30% of controller code reusable as-is — a judgement, not measured).
- View models `OrderDetailModel`, `PickingDetailModel`, `ProjectDetailModel`, `ShoppingListModel` fill a `Model` and return a view name (e.g. `orders/OrderDetailModel.java:66`), but their nested records (`OrderView`, `LineView`, `ShoppingListRow`, …) are flat and would make reasonable DTOs once made public.
- Errors and flash messages are resolved to Polish strings on the server: `MessageSource` in 14 of 17 controllers, ~103 `getMessage` calls; `messages.properties` has 157 keys and no other locale.
- Server-session state a SPA must keep working: CSV import preview stored in `HttpSession` (`parts/PartImportController.java:148-149`), registration bot check that stamps the session when the form is rendered plus a honeypot field (`account/RegistrationController.java:84`, `account/RegistrationBotCheck.java`), and flash attributes.
- Security (`security/SecurityConfig.java`): form login with redirects `/login?blocked` / `?error` (:64-70, :113-118); `AccountActivityFilter` answers with redirects to `/awaiting-approval` or `/login?...` (`security/AccountActivityFilter.java:84-100`); 58 `@PreAuthorize` annotations, which carry over to any API unchanged. A SPA needs JSON login/failure handlers, 401/403 instead of redirects, a `/me` endpoint (replacing 31 `sec:authorize` uses in templates), and token-style CSRF.
- Spring Security 7 (shipped with Boot 4.x) provides `http.csrf(csrf -> csrf.spa())`: a cookie-based CSRF token repository with a handler suited to single-page apps (Spring Security 7.0 reference, *servlet/exploits/csrf*, fetched via Context7 2026-11-09). Serving the bundle from the same origin keeps session-cookie login; no CORS needed.

### F2. Test coupling (`src/test/java`)

- 763 test methods (760 `@Test` + 3 `@ParameterizedTest`) in 65 classes; MockMvc only, Testcontainers Postgres, shared `CompanyFixtures` (`CompanyFixtures.java:43`). No JS, browser or `jsonPath` tests exist.
- By class (worker classification, grep-based, ±10%): 22 classes / ~235 tests touch no HTTP; ~11 classes / ~61 tests check only status, redirect or DB; 32 classes / ~467 tests assert HTML, view names, model or flash.
- Assertion counts (grep): 460 `containsString` (108 negated), 431 `content().string`, 263 `is3xxRedirection`, 72 `isNotFound`, 59 `isForbidden`, 35 `view().name`, 28 `flash().attribute*`, 19 `model().attribute*`; ~358 direct `jdbcTemplate.queryFor*` DB checks.
- Guardrails: company-isolation 404 tests and wrong-role 403 tests would survive with new URLs, JSON bodies and API login; list-isolation tests that read HTML or the model (e.g. `OrdersCompanyIsolationIntegrationTests.java:277-296`) need rewriting. Concurrency tests keep their DB invariant checks but need new request builders.
- Estimated outcome of a full SPA migration: ~150 tests unchanged, ~61 survive mechanical edits, ~470 need their response assertions rewritten (DB checks inside them stay valid). `TemplateConventionTests` would be deleted; `MessagesBundleTests` (81) survives only if messages stay server-side.

### F3. Build, CI and deploy

- Build is Maven only: Spring Boot 4.1.1, Java 21 (`pom.xml:7-8,30`), single `spring-boot-maven-plugin` (:138-143); no Node, npm or frontend plugin.
- `Dockerfile:4-12` copies only `src/main/` and runs `mvnw package`; CI (`.github/workflows/ci.yml`) runs `mvnw verify`, builds an image to GHCR, deploys through the Coolify API and auto-rolls back on a failed health probe (:194-243).
- Adding a Vite/React build needs a Node stage in the Dockerfile (or `frontend-maven-plugin`), copying the frontend sources, and setup-node plus an npm cache in the `verify` job. Shipping the bundle inside the jar keeps GHCR, Coolify, the health check, rollback and same-origin cookies unchanged.

### F4. Recorded decisions and constraints

- `context/archive/2026-10-03-ui-enhancement/plan.md:41`: "No Tailwind, no Node/npm, no build plugin… Dockerfile and `.github/workflows/ci.yml` stay untouched." Its research called a Node toolchain "a stack decision" not covered by `tech-stack.md` (`research.md:109`). Earlier plans also chose plain HTML on purpose (`context/archive/*-parts-catalog/plan.md:19`).
- `context/foundation/tech-stack.md:24`: solo developer, familiar agent-friendly stack preferred; `prd.md:12-15` and `prd-v2.md:12-15`: 3-week after-hours budgets; `roadmap.md` frontmatter `top_blocker: capacity`.
- `context/foundation/infrastructure.md:9,28,112`: platform chosen for Spring Boot + Thymeleaf with SSE in mind (heartbeat, no-transform, gzip off).
- **Realtime gap:** `prd.md:112` / `prd-v2.md:144-145` require reservations and the shopping list to update within 2 s without manual refresh. A grep of `src/main` for `SseEmitter`, `EventSource`, `text/event-stream`, `WebSocket`, meta refresh, `setInterval` and `fetch(` finds nothing; the only JS is `static/js/theme.js`.
- No document forbids React outright, but adopting it reverses the recorded no-build decision and means updating `tech-stack.md`, `AGENTS.md:3`, `lessons.md:65-70` and the `stack-assessment.md` scoring.

### F5. Options (for planning — the choice is the user's)

| Option | What changes | Rough blast radius | Fits recorded constraints? |
|---|---|---|---|
| **A. Stay on Thymeleaf, finish the design system** | `app.css`, fragments, templates (the audit above) | Templates and CSS; tests mostly untouched if markup rules are respected | Yes — no stack change |
| **B. Thymeleaf + no-build JS enhancement** (htmx/Alpine-style, one vendored file) + SSE | Partial page updates, inline confirmations, live refresh via `SseEmitter` | Some controllers return fragments; existing HTML tests largely still valid | Mostly — adds a JS dependency but no build step; closes the realtime NFR |
| **C. React islands** (Vite bundle mounted on a few Thymeleaf pages, e.g. picking list, shopping list) | Node build in Docker/CI, a few JSON endpoints, `csrf.spa()` or meta-tag CSRF | Only the pages converted; their tests move to JSON | Partially — reverses the no-build decision; needs tech-stack update |
| **D. Full React SPA + JSON API** | All 65 handlers re-expressed as API, logic extracted to services, JSON login/errors, client i18n, router, `/me` | Web layer (~4,200 lines) plus ~470 tests rewritten; all docs updated | No — conflicts with solo/capacity constraints and no-build decision |

Not verified here: htmx/Alpine and React tooling specifics (no library docs were fetched for them); effort figures are deliberately not given.

## Open Questions

1. **Goal of this round** — consistency pass, phone/tablet usability, accessibility fixes, visual refresh, or a framework move (Part 2)?
2. **Behavioural changes in scope?** Success flash messages for silent POSTs and confirmation steps for immediate destructive actions touch controllers and tests.
3. **Text extraction** — move hard-coded text in 17 views into `messages.properties`?
4. **Small defects** — fix the ping tile, the `orders-new` back link and picking-detail row alignment here?
5. **Verification** — manual visual checks only, or add screenshot checks (previously declined)?
6. **Target devices** — phones, tablets or desktops? No PRD states it.

7. **Why React?** If the goal is a nicer look and better usability, option A or B delivers that without a stack change. If the goal is learning React (as M-2 was a learning change), option C limits the cost to chosen pages. Which is it?
8. **Is the <2 s live refresh in scope** for this change? It is the strongest functional argument for adding JS, and it is unimplemented.
9. **If a build step is accepted**, should `/10x-tech-stack-selector` be re-run so `tech-stack.md`, `AGENTS.md` and `lessons.md` record the new frontend layer before planning?
