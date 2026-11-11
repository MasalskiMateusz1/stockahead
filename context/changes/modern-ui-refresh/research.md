---
date: 2026-11-11T09:29:43+01:00
researcher: Claude (Opus 5.5) for Mateusz Masalski
git_commit: 795c5d5965370865a745f38115665b220019d5e1
branch: main
repository: stockahead
topic: "What makes the current UI look dated, and what can be borrowed from boost4body.com and anki.pl to modernise it"
tags: [research, ui, design-system, app-css, fragments, thymeleaf]
status: complete
last_updated: 2026-11-11
last_updated_by: Claude (Opus 5.5)
last_updated_note: "Follow-up: user wants simple animations, no marketing; added § Follow-up — motion"
---

# Research: Modern UI refresh inspired by boost4body.com and anki.pl

**Date**: 2026-11-11T09:29:43+01:00
**Researcher**: Claude (Opus 5.5) for Mateusz Masalski
**Git Commit**: 795c5d5
**Branch**: main
**Repository**: stockahead

## Research Question

"The UI still does not look good. Take inspiration from https://boost4body.com/ and https://anki.pl/ to see how a modern app looks, and how can we improve it."

## Summary

- **The token layer is fine. The look is weak because of the shell, the surfaces and the components.** The 2026-11-09 change gave `app.css` a type scale, a spacing scale, a single-source `light-dark()` palette and a blue accent (`app.css:21-72`). It restyled one view only, `orders-list`. The other 15 list or detail tables are still centred with a grey header band (`app.css:176-179`). The "dated" impression comes from:
  - a topbar that wraps into two rows at 1280 px (screenshot `after/parts-list-default-light-1280.png`);
  - page and card that are both pure white (`app.css:51,53`), so no surfaces are visible;
  - full-size solid-red row buttons on parts-list;
  - bare `h1` + stacked buttons instead of a page header;
  - a dashboard of icon-only tiles carrying no data (`after/dashboard-default-light-1280.png`).
- **Both reference sites share one transferable recipe**, measured from their CSS:
  - an off-white page with white cards (anki `--paper:#f5f6f0`, boost `--color-surface:#f0ede8`);
  - hairline borders instead of heavy shadows (anki `--line:#dce4dd`);
  - a three-level text colour scale;
  - one saturated action colour (anki teal `#087f77`, boost wine `#7a2535`);
  - large radii, 10–12 px on controls and 16–20 px on cards (Stockahead uses one 8 px `--radius`, `app.css:48`);
  - soft-filled inputs;
  - 11 px uppercase micro-labels;
  - tinted-background + dark-text status pills;
  - a filter-bar grid.

  Their marketing parts don't belong in a dense warehouse tool: animated gradients, photo hero, serif display type, offset-shadow CTAs.
- **anki.pl is the closer technical match.** It is hand-written plain CSS on system fonts with no build step (`anki/fonts.css`: "System fonts keep the pilot independent of external font services"). That matches the twice-recorded Stockahead constraint: Thymeleaf + plain CSS, no new dependency, no vendored font (`context/archive/2026-11-09-enhance-ui-design/change.md:14-19`). boost4body (Next.js + Tailwind v4) contributes values to copy, not an architecture.
- **Most of the improvement can land in `app.css` + `fragments.html` without touching 23 templates.** A sidebar or app shell can be done in CSS alone, because every authenticated page renders `<header class="topbar">` before `<main class="container">`. Markup pinned by tests limits what the topbar and table cells may carry (see "Constraints").

## Detailed Findings

### 1. Current state (verified against files and the archived screenshots)

Tokens (`src/main/resources/static/css/app.css`):
- The font stack starts with `"Inter"`, but no font file ships, so the system sans renders (`:24-25`).
- The type scale has 5 steps: 12 / 13 / 14 / 16 / 24 px (`:27-31`). The jump from `--text-lg` 16 px to `--text-xl` 24 px leaves h2 and h3 close to body size.
- Spacing comes in 4 px steps (`:34-41`), shadows in two levels (`:44-46`), and the radius in one value, 0.5rem (`:48`).
- `--background` and `--card` are both `oklch(1 0 0)` (white) in light mode (`:51,53`). Cards are told apart from the page only by the faint `--border` `oklch(0.922 0 0)` (`:70`).
- `--primary` is Tailwind blue-600/blue-400 (`:55`).
- The dark palette is declared **once**, as `light-dark()` values (`:12-18`). The archived claim "dark palette declared twice" (`context/archive/2026-11-09-enhance-ui-design/charges.md`) is **contradicted** by the current file; that change fixed it.

Shell (`src/main/resources/templates/fragments.html`):
- `topbar` (`:12-41`) holds a text brand link, a flat `<nav>` of up to 8 role-gated text links and a session block (email, role badge, theme toggle, "Wyloguj"). There is no active-link state, no grouping and no icons in the nav.
- `alerts` (`:43`) renders `${error}` only.
- The `icon(name)` fragment holds 9 Lucide icons (`:45-56`). They are used by the dashboard only.
- `.container` is `max-width: 72rem` (`app.css:509-510`). Each template writes its own `<main class="container">`; 23 of the 28 template files match `class="container"`. The other 5 are `login.html`, `register.html`, `setup.html`, `awaiting-approval.html` and `fragments.html`.

Treatment per view (grep of `templates/*.html` for `data-table`):
- `orders-list.html` is the only template using `table.data-table` (2 matches). In all other table views the cells are centred by the global rule `app.css:176-179`.

Observed in the archived screenshots (`context/archive/2026-11-09-enhance-ui-design/screenshots/after/`):
- `parts-list-default-light-1280.png`:
  - All cells are centred.
  - Each row has three full-size buttons, and the last is a solid red "Dezaktywuj" that dominates the page.
  - The search field is a bare label over an input with a native checkbox.
  - Three action buttons sit stacked under the `h1`.
- `dashboard-default-light-1280.png`: nine white tiles with an icon and a label, no counts. A "Sprawdź dostęp kierownika" debug-style tile is visible, and about half the viewport is empty.
- `orders-list-default-light-1280.png`: the cleanest view, with uppercase muted headers and right-aligned numbers. Remaining weaknesses:
  - solid saturated priority pills;
  - pending rows tinted pink instead of amber (the archived `visual-gate.md` N1 records this as a deferred defect);
  - white table cards on a white page.
- At 1280 px in all three screenshots, the topbar's session block (email, badge, toggle, "Wyloguj") wraps onto a second, left-aligned row.

### 2. boost4body.com (Next.js + Tailwind v4; CSS fetched 2026-11-11)

Sources: `/_next/static/css/e531702271812139.css` and the HTML of home, /marketplace, /signup and /faq. Local copies are in the session scratchpad.
- **Surfaces**: page `--color-surface:#f0ede8` (warm off-white), card `#fff`. The dark mode is a burgundy black, `#0d0508` / `#1a0a0d`.
- **Text**: `#1a1a1a` / `#6b6b6b` (`--color-text-secondary`) / `#9e9e9e`.
- **Accent**: `--color-brand-400:#7a2535` (wine) with a 50–900 scale. The primary button is a gradient `135deg #7a2535 → #66202d → #a85667`.
- **Shape**: buttons and inputs `rounded-xl` (12 px), cards `rounded-2xl`/`3xl` (16/24 px), pills `rounded-full`. Shadows are long, soft and brand-tinted, e.g. `0 10px 30px -18px rgba(45,10,15,.16)`, and are paired with `ring-1 ring-black/[0.04]`.
- **Type**: system sans for the body, Playfair Display serif for headings. Labels are `11px` uppercase with `tracking-wide`…`0.25em`. Body text is mostly `text-sm` with `leading-relaxed`.
- **Inputs**: `bg-gray-50 border-gray-200 rounded-xl`, turning white on focus with a brand ring.
- **Status pairs**: info `#ebf5ff`/`#1d6fa4`, success `#ecfdf5`/`#059669`, danger `#fff1f2`/`#be123c`.
- **Not seen**: data tables. None appeared on the fetched pages.
- **Icons**: likely Phosphor, inferred from the 256-unit `viewBox` SVGs; the set is not named in the source.

### 3. anki.pl (hand-written plain CSS; `style.css` fetched 2026-11-11)

- **Tokens**: `--ink:#142c34`, `--muted:#586c72`, `--teal:#087f77`, `--paper:#f5f6f0`, `--line:#dce4dd`, cards `#fffefb`.
- **Fonts**: Arial/Helvetica only, deliberately no web font.
- **Depth comes from borders**: `.surface` uses a 20 px radius with an almost invisible `0 5px 10px #1d363005` shadow. The radius ladder is 10 (inputs), 12 (buttons), 14–16 (notices, tiles), 18–22 (cards) and 30 (pills).
- **Buttons**: teal, `border-radius:12px`, `font-weight:700`, hover `#06685f`, `:disabled{opacity:.55}`. The secondary button is a tinted neutral `#e8eee7`, not an outline. A small `.quiet` button uses 12 px text.
- **Inputs**: `border:1px solid #c8d6d1; border-radius:10px; background:#fcfdf9`. The focus outline is `3px solid #ba600c`.
- **App patterns found in the CSS** (markup not seen rendered):
  - `.library-search{grid-template-columns:2fr 1fr 1fr auto;gap:14px;align-items:end}` is a filter bar.
  - Tabs: `display:flex;gap:28px;border-bottom:1px solid var(--line)`, with the active tab in teal.
  - A stats row with a bold value and a muted label.
  - An empty state with a dashed border, centred.
  - A notice styled `#fff0d8` / border `#e0b671` / text `#634211`.
  - Status-coloured buttons as tinted pairs (red `#f9e2dc`/`#8b3828`, green `#e2eee6`/`#154b3b`, and so on).
- **Motion**: `transition: background .15s, transform .15s`, with hover `translateY(-2px…-5px)` on clickable cards. It honours `prefers-reduced-motion`.
- **Marketing-only, skip**: the animated pastel rainbow hero, the violet offset-shadow CTA (`0 6px 0 #392384`) and the 62 px headlines.

### 4. Mapping: what to borrow, where it lands

Current-state anchors are verified. The proposals are inference, for `/10x-plan` to decide.

| # | Problem (current) | Borrowed trait | Lands in |
|---|---|---|---|
| 1 | Page = card = white (`app.css:51,53`) | Off-white page + white cards (anki `#f5f6f0`/`#fffefb`; boost `#f0ede8`/`#fff`) | `--background` token only |
| 2 | Topbar wraps to 2 rows at 1280 px; no active state (`fragments.html:12-41`) | Fixed app shell: left sidebar or single-row bar, grouped links, icons, brand mark | `fragments.html` topbar + `app.css` (CSS grid on `body`) |
| 3 | 15 tables centred, grey `th` (`app.css:176-179,188-193`) | Left-aligned text / right-aligned numbers; 11 px uppercase muted headers on the card surface (already in `table.data-table`) | Make `data-table` the default table style, or add the class per template |
| 4 | Solid red row buttons (parts-list) | Quiet tinted-neutral secondary and ghost buttons; destructive as text or ghost-red | `.btn-*` rules + parts/projects/accounts templates |
| 5 | Bare `h1` + stacked buttons | Page header row: title + muted subtitle/count left, primary action right | New `.page-header` class; touches every template that has a `.page-actions` line under its `h1` |
| 6 | One 8 px radius (`app.css:48`) | Radius ladder: ~10 controls / ~14 cards / 999 pills | New `--radius-*` tokens |
| 7 | Saturated solid priority pills; pink pending tint | Tinted-bg + dark-text pills (boost/anki status pairs); amber tint via `color-mix(in oklab, …)` | `.badge-*`, `tr.row-pending` |
| 8 | Inputs full width of a 72 rem container; plain filter | Soft-filled inputs (bg slightly tinted, white on focus); filter bar grid inside a card; form column max-width (~40 rem) | `input`/`select` rules, `.form-inline` → filter grid, form `max-width` |
| 9 | Dashboard tiles hold no data | Stats row / KPI tiles (anki `.stats`, `.insight b{28px}`) with counts: pending orders, low-stock parts | `dashboard.html` + controller model. This changes data, not just styling. |
| 10 | No motion | 150 ms colour transitions; hover lift on clickable cards; reduced-motion guard | `app.css` |

## Code References

- `src/main/resources/static/css/app.css:24-25` — font stack, no shipped font
- `src/main/resources/static/css/app.css:27-48` — type, spacing, shadow and radius tokens
- `src/main/resources/static/css/app.css:51-72` — colour tokens (`light-dark()`), background = card in light
- `src/main/resources/static/css/app.css:176-179` — global centred `td`/`th`
- `src/main/resources/static/css/app.css:200-262` — `.table-wrap`, `row-pending`, opt-in `table.data-table`
- `src/main/resources/static/css/app.css:429-475` — topbar
- `src/main/resources/static/css/app.css:509-535` — `.container` (72rem), `.page-actions`, `.card`
- `src/main/resources/templates/fragments.html:12-41` — topbar fragment (nav + session)
- `src/main/resources/templates/fragments.html:45-56` — Lucide `icon(name)` fragment
- `src/test/java/pl/regavio/stockahead/account/OperatorAccessIntegrationTests.java:208-209` — topbar substring and the exact `<a href="/operator/companies">Firmy</a>`
- `src/test/java/pl/regavio/stockahead/account/PendingCompanyAccessIntegrationTests.java:148` — `class="topbar"` absent on pending pages
- `src/test/java/pl/regavio/stockahead/account/OperatorCompanyLifecycleIntegrationTests.java:667,673` — exact `btn btn-danger` / `btn` markup

## Architecture Insights — constraints a redesign must respect

- **Stack**: Thymeleaf + plain CSS in `static/css/app.css`, with no build step, no new dependency and no vendored font. The user decided this on 2026-11-09 (`context/archive/2026-11-09-enhance-ui-design/change.md:14-19`). Shipping a web font (e.g. Inter woff2) would reverse it and needs explicit sign-off.
- **Templates**: no `<style>` or `style="` in templates. Every template uses `fragments :: head`, and authenticated templates use `fragments :: topbar`. `TemplateConventionTests` enforces both, and they are recorded in `lessons.md` ("Build every template on the shared head and stylesheet").
- **Table markup**:
  - Tests assert bare `<tr>`, `<td>` and `<th>` with no attributes. These appear in the inspected `OrderCompletionIntegrationTests`, `OperatorAccessIntegrationTests` and `OperatorCompanyLifecycleIntegrationTests`; the worker's grep counted about 54 bare `<td>` asserts across 12 files, a grep-level count.
  - `<td id="to-pick-N">` must stay exact.
  - Style tables through a class on `<table>` or a wrapper, or through positional selectors.
- **Topbar**: `<header class="topbar">` must stay the first `<header>` on the page, and the `Firmy` link must carry no attributes (`OperatorAccessIntegrationTests.java:208-209`). So an active-nav state cannot be an `aria-current` or class on the `<a>` without changing that test. A sidebar can still be built by restyling `header.topbar` as a grid column.
- **Buttons**: the exact `<a class="btn btn-danger" …>Zablokuj</a>` and `<button type="submit" class="btn">Odblokuj</button>` are pinned (`OperatorCompanyLifecycleIntegrationTests.java:667,673`). Restyle those classes; don't add classes to those two elements.
- **Tokens**: keep the shadcn token names and single-source `light-dark()` values, with WCAG ≥4.5:1 for text and ≥3:1 for `--input` and `--ring` (header comment `app.css:1-19`).

## Historical Context (from prior changes)

- `context/archive/2026-10-03-ui-enhancement/` — introduced `app.css` with shadcn Neutral tokens, the top bar and the tile dashboard. Explicitly excluded a sidebar, a hamburger menu and Tailwind (per the audit worker's reading of `plan-brief.md`, not re-read line by line).
- `context/archive/2026-11-09-enhance-ui-design/charges.md:78-94` — deferred items that this change can absorb:
  - topbar wrap and active state;
  - `--border` contrast;
  - styled error page;
  - success flash on detail pages;
  - `--info` vs `--primary` hue;
  - the `color-mix(in oklch)` pink pending tint (N1).
- The archived `charges.md` statement that the dark palette is declared twice is **contradicted** by the current `app.css:12-18`. Its other deferred items are **supported** (still present in the current files).

## Related Research

- `context/archive/2026-11-09-enhance-ui-design/research.md` — the earlier UI audit and the framework options A–D (React rejected)
- `context/archive/2026-10-03-ui-enhancement/research.md`

## Open Questions (user decisions for `/10x-plan` or `/10x-ui`)

1. **Which reference leads the look?**
   - anki-like: cool green-tinted paper, teal accent, friendly.
   - boost-like: warm paper, wine accent, premium.
   - Neutral: off-white surfaces with the existing blue kept.

   All three are token-only swaps on top of the same structural work (rows 2–8 above).
2. **Shell**: left sidebar or a single-row top bar? The sidebar fits 8 nav links better. Either way it is CSS on the existing `header.topbar`. An active-link highlight needs either a test change (`OperatorAccessIntegrationTests.java:209`) or a non-link marker (e.g. a `data-section` on `<body>` set per template).
3. **Font**: stay on system fonts (both reference sites' bodies do; anki deliberately), or reverse the no-vendored-font decision and ship Inter?
4. **Scope**: global tokens + shell + all tables in one change, or phased (tokens/shell → lists → forms/detail → dashboard KPIs)? Dashboard KPIs need controller data, so they are not CSS-only.

Gaps:
- The reference sites were analysed from HTML and CSS only. No pages were rendered, so photography, JS-driven states and anki's app-screen markup were not seen.
- Neither site has a data table, so table styling is derived from their tokens, not observed.
- The Stockahead app was not run live; current visuals come from the archived 2026-11-11 screenshots plus the current CSS.

## Follow-up 2026-11-11 — simple motion (user decision)

**User decision (2026-11-11):** "I don't care about marketing etc, but some simple animation can help visual side." So:
- Marketing patterns stay out of scope: hero imagery, animated gradients, serif display type, offset-shadow CTAs.
- Motion is **in scope**, limited to simple UI animation.
- Row 10 of the mapping table moves from optional to wanted.

**Current state:** a grep of `src/main/resources/static/css/app.css` for `transition`, `animation`, `@keyframes`, `prefers-reduced-motion` and `transform` matches only `text-transform: uppercase` (`:240`). The app has no motion at all; every hover and state change is an instant swap.

**Motion found in the references** (CSS read from the scratchpad copies, 2026-11-11):

| Source | Rule | Use in Stockahead |
|---|---|---|
| anki `style.css:37-42` | `button, a { transition: background .15s, transform .15s }` | Base: colour fades on buttons, links, nav items, table-row hover |
| anki `style.css:1112` | `.landing-benefits article { transition: transform .25s, box-shadow .25s }` + hover lift | Dashboard tiles / clickable cards: `translateY(-2px)` + `--shadow-md` (defined at `app.css:45`, referenced nowhere else) |
| anki `style.css:1085` | `@keyframes demo-arrive { from { transform: translateY(16px); opacity: 0 } }` | Page content enter (use a smaller 4–8 px offset for an app) |
| boost `a.css` | `@keyframes fadeIn { 0% { opacity: 0; transform: translateY(4px) } }`, `bbContentIn` (8 px) | Same: alerts and flash messages, `main` on load |
| boost `a.css` | `@keyframes pulse { 50% { opacity: .5 } }`, `spin` | Optional: pending-row marker or submit-button busy state |
| anki `style.css:1055` | `.demo-progress span { transition: width .4s }` | Progress bars (e.g. order "Zbudowano 4 / 5", picking progress) if introduced |
| anki `style.css:1022-1029` | `@media (prefers-reduced-motion: reduce) { * { transition: none !important } }` | Required guard |

**Rejected as marketing or too heavy for a data tool:**
- boost `kenburns`, `mistRise`/`mistFall`, `marquee`, `float`, and `bbFadeUp` with its blur filter;
- anki `card-breathe`, `card-reveal` (3D flip) and `landing-colors` (gradient pan).

**Proposed motion set** (inference, for `/10x-plan`; all CSS-only, no JS, no dependency):
1. Tokens: `--ease: cubic-bezier(.4,0,.2,1)`, `--dur-fast: 150ms`, `--dur-base: 250ms`.
2. `transition: background-color, color, border-color, box-shadow` at `--dur-fast` on `.btn`, nav links, inputs (focus ring), `tbody tr`, and `.badge`.
3. Hover lift on clickable tiles and cards: `transform: translateY(-2px)` + a shadow step at `--dur-base`.
4. Enter animation, run once per page load: `main` (or its first children) fades up 4–8 px over about 200–300 ms. Server-rendered pages reload on every action, so it replays on every navigation; keep it short and subtle so it doesn't feel slow.
5. Alerts (`.alert`) slide or fade in so a success or error message after a redirect gets noticed.
6. `@media (prefers-reduced-motion: reduce)` turns off all transitions and animations, like both reference sites.

**Constraints that still apply:**
- Animation lives only in `app.css` (`TemplateConventionTests`; no `style=""`).
- Animate only `opacity` and `transform` for enter and lift effects (no layout properties), so table-heavy pages don't reflow.
- Adds no classes to test-pinned elements: `tr`/`td`/`th`, the Zablokuj/Odblokuj controls and the topbar `Firmy` link. Every effect above attaches to existing selectors (`.btn`, `tbody tr`, `.alert`, `main`, `.tiles a`).
- Dashboard tiles are `<a>` inside `div.tiles` (`dashboard.html:13`), styled by `.tiles a` and `.tiles a:hover` (`app.css:561,574`).
