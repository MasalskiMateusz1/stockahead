# Modern UI Refresh — Plan Brief

> Full plan: `context/changes/modern-ui-refresh/plan.md`
> Research: `context/changes/modern-ui-refresh/research.md`

## What & Why

The user's verdict: "the UI still does not look good". The last UI change modernised only `orders-list`. Every other view still looks like a default admin template: centred spreadsheet-like tables, a nav bar that wraps onto two rows, a white page with white cards, solid red row buttons, and a dashboard with no data. This plan restyles the whole app in the app-side style of anki.pl (with a few boost4body status colours). It adds simple motion and turns the dashboard into live count cards.

## Starting Point

`app.css` already has single-source light/dark tokens, a type scale and a spacing scale. All 23 logged-in templates share the topbar fragment and the same `h1 → actions → table/form` structure. Tests pin some markup: bare `<tr>`/`<td>`/`<th>`, the topbar `<header>` and its `Firmy` link, the Zablokuj/Odblokuj buttons, and the operator's bare `<table>`.

## Desired End State

Every page has a left sidebar with icons and the current section highlighted, sitting on cool off-white paper with white cards and a teal action colour. Each page opens with a header row (title on the left, actions on the right). Tables are clean: text left-aligned, numbers right-aligned, quiet row actions. Forms sit in a 40rem column with soft inputs, and detail pages show a fact-card grid. Pages fade up, alerts slide in, cards lift on hover, and all of it switches off under reduced motion. Managers open the app to see "Do potwierdzenia", "Otwarte zlecenia" and "Braki do zakupu"; technicians see "Moje zlecenia do kompletacji".

## Key Decisions Made

| Decision | Choice | Why (1 sentence) | Source |
| --- | --- | --- | --- |
| Visual direction | anki-like teal, cool off-white paper | anki.pl is plain CSS on system fonts, so its values map 1:1 onto our stack | Plan |
| Colour values | anki values darkened where needed (teal `#077a72`, input `#778b85`) | anki's own teal (4.48:1) and input border (1.38:1) fail WCAG | Plan |
| Navigation | Left sidebar, CSS-only on `header.topbar` | 8 links fit without wrapping, and no template wrapper is needed | Plan |
| Active nav | `data-section` on `<body>` and CSS on `a[href]` | The `Firmy` link assertion forbids attributes on nav links | Plan |
| Font | System fonts | Matches both reference sites and the 2026-11-09 no-vendored-font decision | Plan |
| Motion | 150–250ms fades, fade-up on load, hover lift, reduced-motion guard | The user wants simple animation, no marketing effects | Research |
| Marketing patterns | Excluded | User: "I don't care about marketing" | Research |
| Row buttons | Compact and quiet through `td .btn`; destructive as red text | Keeps the pinned Zablokuj/Odblokuj markup byte-identical | Plan |
| Dashboard | Role-based counts: manager 3, technician 1 | Each number equals the row count of the list it links to | Plan |
| Debug tile | Remove the `/manager/ping` tile; keep the endpoint | Only real work belongs on the home page | Plan |
| Stack | Thymeleaf + plain CSS, no new dependency | Recorded on 2026-11-09 and kept | Research |

## Scope

**In scope:**
- Colour, radius and motion tokens.
- The sidebar shell and its current-section highlight.
- The page-header and filter-bar patterns.
- Default table, button and badge styles, and empty states.
- Forms, detail, confirmation and auth pages.
- Dashboard count queries, model, cards and tests.
- A visual gate.

**Out of scope:**
- React, Tailwind or any build step, and web fonts.
- `aria-current` on nav links.
- A parts-out-of-stock KPI.
- A styled error page, success flash on detail pages, a mobile card layout for tables, and screenshot tests.
- Copy, route or permission changes.

## Architecture / Approach

The sidebar is a CSS grid on `body:has(> header.topbar)`. Nav icons and the active state come from CSS selectors on the existing `href`s; the icons are Lucide SVG data URIs in `mask-image`. Shared patterns are classes on wrapper elements (`.page-header`, `.table-wrap`, and `num-*` classes on `<table>`), never on test-pinned elements. The only Java change is four company-scoped `COUNT` queries plus a `HomeController` model, built by role.

## Phases at a Glance

| Phase | What it delivers | Key risk |
| --- | --- | --- |
| 1. Tokens, motion, sidebar shell | New palette and motion on every page; sidebar with icons and the active section | `:has()` / `mask-image` support; nav markup must stay byte-identical |
| 2. List views | Clean default tables, quiet row actions, page header, filter card, tinted badges, amber pending rows | Breaking the bare `<td>`/`<table>` assertions |
| 3. Forms, detail, auth | 40rem form column, soft inputs, fact-card grid, unified confirmation and auth pages | Pinned `dt`/`strong`/honeypot markup |
| 4. Dashboard counts + visual gate | Role-based count cards with tests; screenshot pass across views | Counts drifting from the list definitions |

**Prerequisites:** Docker running, for `./mvnw verify` and `spring-boot:test-run`. Headless Firefox is available for the gate, as last time.
**Estimated effort:** about 4 sessions, one per phase.

## Open Risks & Assumptions

- The `:has()` selector and CSS `mask-image` are assumed to be supported by the plant's browsers (current evergreen Chrome, Edge and Firefox). An old browser would show the stacked layout without icons.
- The enter animation replays on every full-page navigation; it is kept at 250ms or less so it doesn't feel slow.
- The `dt` colon text and some `<strong>` texts may be pinned by tests, so the detail restyle is CSS-only on those elements.

## Success Criteria (Summary)

- Every view shows the sidebar shell, the header row and the teal-on-paper look in light and dark, with no test edited to make it pass.
- The dashboard counts match their linked lists for manager and technician, and company isolation is tested.
- `visual-gate.md` passes for every listed view, theme and width.
