# UI Enhancement — Plan Brief

> Full plan: `context/changes/ui-enhancement/plan.md`
> Research: `context/changes/ui-enhancement/research.md`

## What & Why

Stockahead's 22 Thymeleaf views are unstyled HTML. Their only CSS is a centering rule copied by hand into 14 templates, and a lessons.md rule exists just to keep those copies in sync. This change adds a real, shared design system: one token-based stylesheet with light and dark themes, a shared top-bar navigation, and reusable fragments. The app becomes consistent, easier to navigate and pleasant on a technician's tablet, with no frontend build step.

## Starting Point

There are no static assets, JavaScript or Thymeleaf fragments today. Each page has its own `<head>` and a "Powrót do pulpitu" link, and the dashboard is the only navigation. `SecurityConfig` would block CSS on the login page. About 12 test assertions match exact markup such as `<td>7</td>`.

## Desired End State

Every page loads `/css/app.css` and `/js/theme.js` through a shared `head` fragment. Logged-in pages also get a role-aware top bar with links, the signed-in user, a theme toggle and logout. The theme follows the OS by default and can be forced through an Auto → Jasny → Ciemny toggle that persists across visits. Tables, forms, buttons, alerts, badges and stat lists look consistent in both themes. A convention test blocks inline styles and pages that skip the shared head.

## Key Decisions Made

| Decision | Choice | Why (1 sentence) | Source |
| --- | --- | --- | --- |
| Token values | shadcn/ui default "Neutral" theme (oklch, ui.shadcn.com/docs/theming, fetched 2026-10-03); status colours (success/warning/info) chosen by us at WCAG AA | Proven, familiar palette; shadcn ships no status colours | Plan |
| Styling technology | Plain CSS + shadcn-style tokens | No Node/build change to Maven, Docker or CI, and element selectors keep exact-markup tests green | Plan |
| Scope | All 22 templates | Consistency, and the copy-the-`<style>` lessons rule can be retired cleanly | Plan |
| Dark mode | OS preference + manual toggle (localStorage) | User control on shared workshop devices; adds the app's first, tiny JS | Plan |
| Markup sharing | Native `th:fragment` / `th:replace` | No new dependency; plain Thymeleaf | Plan (research Q4) |
| Navigation | Shared top bar; dashboard kept as tile landing page; per-page "Powrót" links removed | Every view is one click away; wraps on tablet width | Plan |
| Verification | MockMvc asset-access tests + file-level convention test + manual light/dark/tablet pass | Guards the contract without brittle pixel tests | Plan |
| Static asset access | `PathRequest.toStaticResources().atCommonLocations()` → `permitAll` | Login page must load CSS/JS before auth | Research Q5, resolved in plan |
| Cache busting | Spring content-hash resource versioning | Deploys never serve stale CSS; no build tooling | Plan |

## Scope

**In scope:** `static/css/app.css`, `static/js/theme.js`, `templates/fragments.html`, all 22 templates migrated, `SecurityConfig` static-resource permit, resource versioning properties, `StaticResourceAccessTests`, `TemplateConventionTests`, replacing the lessons.md table-centering rule.

**Out of scope:** Tailwind/Node/Layout Dialect, Playwright screenshot tests, sidebar or hamburger menu, server-side theme preference, controller/route/text changes, the stale roadmap sentence.

## Architecture / Approach

Spring Boot serves `static/` and versions it by content hash. `fragments.html` exposes `head(title)`, `topbar` and `alerts`. The topbar reads identity via `sec:authentication`/`sec:authorize`, so no controller changes are needed. Colors exist only as CSS custom properties, defined for light, for OS-dark guarded by `:not([data-theme="light"])`, and for forced dark. A render-blocking `theme.js` sets `data-theme` before paint. Elements matched by tests (`<td>`, `<th>`, `<strong>`) stay attribute-free and are styled through element and parent selectors.

## Phases at a Glance

| Phase | What it delivers | Key risk |
| --- | --- | --- |
| 1. Foundation | CSS tokens + components, theme script, fragments, static `permitAll`, versioning; applied to login/setup/dashboard; asset-access tests | Theme flash or toggle broken without JS/storage |
| 2. Parts, deliveries, purchasing | 8 templates migrated | Breaking exact-markup assertions (`<td>7</td>`, `<strong>Lokalizacja</strong>`) |
| 3. Projects, orders, picking, technicians | 11 templates migrated, priority/status badges | Same, plus row-action forms at tablet width |
| 4. Guard + cleanup | `TemplateConventionTests`, lessons.md rule replaced, full manual pass | Contrast issues found only in the manual pass |

**Prerequisites:** Docker running for `./mvnw verify`; a browser able to emulate dark mode and ~768px width.
**Estimated effort:** ~3–4 sessions across 4 phases (Phase 1 is the largest design effort; Phases 2–3 are mechanical).

## Open Risks & Assumptions

- Assumes no test asserts the "Powrót do pulpitu" text (grep found none). If one appears, the assertion is updated, not the topbar.
- A template that cannot be styled without touching a test-matched element should change the selector strategy, not the test.
- Hand-written CSS has no linter. Consistency relies on tokens and the convention test.

## Success Criteria (Summary)

- All 22 views look consistent in light and dark, at desktop and tablet width, and the login page is styled before sign-in.
- Any view is reachable in one click from the top bar, filtered by role.
- `./mvnw verify` is green, including the new access and convention tests, and no inline styles remain.
