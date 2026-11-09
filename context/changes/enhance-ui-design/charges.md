---
change_id: enhance-ui-design
view: orders-list (`src/main/resources/templates/orders-list.html`, `GET /orders`, manager only)
motif: Linear/Vercel-like — monochrome surfaces, one sharp accent, thin borders, compact rows, tight type scale
token_source: `src/main/resources/static/css/app.css` (`:root`, dark block x2) — extended, not replaced
path: A — stay on Thymeleaf + plain CSS, no build step, no new dependency
audited: 2026-11-09 (from source; no screenshot yet — first screenshot is the baseline step of the plan)
---

# Charge list — orders-list

Paths are relative to `src/main/resources/` unless stated. Each charge: evidence → effect on the user → fix direction.
Constraint carried from research §5: no attributes on `<tr>`/`<td>`/`<th>` that tests match by exact markup
(`<td>7 / 10</td>`, `<td>—</td>` in `OrderCompletionIntegrationTests.java:650,692,849,861`); ids `pending-orders`
and `open-orders` stay.

## C1 — Missing tokens: no type, spacing or elevation scale (Missing tokens)

- **Evidence**: font stack hard-coded `static/css/app.css:123` (`system-ui, …`); heading sizes literal at
  `app.css:135-149` (1.75 / 1.3 / 1.1rem); small sizes 0.9 / 0.875 / 0.78rem chosen per component
  (`app.css:210,341,411,424,609`); nine different gap values; only `--radius` is a non-colour token (`app.css:21`).
- **Effect on the user**: the page reads as a default browser document — heading, section titles and table text
  sit at near-random sizes, and spacing rhythm changes from block to block, so nothing guides the eye to what matters.
- **Fix**: add `--font-sans`, `--font-mono` (tabular numbers), a 5-step `--text-*` scale, a 4px-based `--space-*`
  scale and one `--shadow-*` pair to the token blocks; make base elements and the components orders-list uses read them.

## C2 — Missing tokens: no accent; "primary" is a black slab (Missing tokens)

- **Evidence**: `--primary: oklch(0.205 0 0)` light / `oklch(0.922 0 0)` dark (`app.css:26,53,80`); every `.btn`
  defaults to it (`app.css:345-348`); the dark palette is declared twice (`app.css:46-73` and `75-100`).
- **Effect on the user**: "Nowe zlecenie" and each row's "Sprawdź" are the same heavy black block, so the page has
  no single obvious next action and looks dated (grayscale + system font is the "20th-century" read).
- **Fix**: one accent value (Linear-style, solid, no gradient) published through the existing `--primary` /
  `--ring` roles in both themes; dedupe the dark block first so the value is set once per theme. Contrast ≥4.5:1
  for `--primary-foreground` on `--primary`, ≥3:1 for `--ring`, in both themes.

## C3 — Missing shared component: row actions use full buttons, with two different roles (Missing shared component)

- **Evidence**: `templates/orders-list.html:43` row link `class="btn"` (primary) vs `templates/orders-list.html:74`
  `class="btn btn-secondary"` for the same "open the order" action; `app.css` has no small or ghost variant
  (research §1 "Missing: button sizes").
- **Effect on the user**: the table becomes a column of large buttons competing with the page's main action; the
  same action looks important in one table and secondary in the next, so the manager can't learn what colour means.
- **Fix**: add `.btn-sm` and `.btn-ghost` to `app.css` (one place, reusable by every list view); both row links use
  the same variant. Pending rows keep their emphasis through `tr.row-pending`, not through the button.

## C4 — Missing tokens: centred table cells, no numeric alignment (Missing tokens)

- **Evidence**: `app.css:193-197` centres every `td`/`th`; columns "Liczba sztuk" and "Zbudowano"
  (`orders-list.html:37,42,70`) and the dates (`:39,41,72`) use proportional digits.
- **Effect on the user**: names, quantities and dates zig-zag down each column, so comparing deadlines or
  quantities across orders means reading every cell instead of scanning.
- **Fix**: left-align by default, right-align numeric columns with `font-variant-numeric: tabular-nums`. Because
  cells cannot carry classes (tests), target columns from the table: a class on `<table>` (e.g. `data-table`) plus
  column selectors scoped to `#pending-orders` / `#open-orders`. Global change to `td, th` alignment touches every
  list view — the plan must decide whether to scope it to `.data-table` first and roll out later.

## C5 — Accidental architecture: arrivals on /orders give no feedback (Accidental architecture)

- **Evidence**:
  - `orders-list.html` does not include `fragments :: alerts` (research §4.1); `templates/fragments.html:43` renders
    only `${error}`, no success variant.
  - Create/change/cancel POSTs redirect to `/orders` without `addFlashAttribute` (`orders/OrderController.java`, no
    flash anywhere; research §4.2), e.g. the redirect at `OrderController.java:453`.
  - A technician following a link to `/orders` hits `@PreAuthorize("hasRole('MANAGER')")`
    (`OrderController.java:126`); there is no `templates/error*.html`, so Spring's unstyled English Whitelabel page shows.
- **Effect on the user**: after creating or cancelling an order the manager lands on the list with no sign it
  worked; a technician who opens a shared link sees a raw English error page outside the app shell.
- **Fix**: include the alerts fragment on orders-list and extend it with a success flash (`${success}`); add flash
  messages to the order POSTs that redirect here. The error page is a global entry-point fix — **proposed deferred**
  to its own change (touches every view), unless the plan pulls it in.

## Deferred / out of scope (kept visible)

- Topbar wraps into several rows on phones, no active-nav state, no `aria-current` (research §2, §3) — global shell,
  not this view. The topbar will inherit C1/C2 tokens only.
- Faint `--border`/`--input` contrast ≈1.25:1 (research §3) — revisit when C2 touches the token blocks; record
  the decision in the plan.
- Order status/priority badge coupling to enum names via `th:classappend` (`orders-list.html:38,71`) — documented
  risk, no change.
- React / build step (research Part 2, options C/D) — rejected for this change by the user on 2026-11-09.

## States to cover in the visual gate (orders-list)

default (both sections populated) · hover (row, button) · focus (row link, "Nowe zlecenie") · disabled (n/a on this
view — record as such) · error (alert from `${error}`) · success (flash after create) · empty (no open orders, no
pending section) · loading (n/a, server-rendered — record as such). Desktop + one mobile width (390px), light + dark.
