---
change_id: enhance-ui-design
view: orders-list (`GET /orders`, manager)
checked: 2026-11-11
evidence: screenshots/after/ (27 PNGs, untracked; not committed)
---

# Visual gate — orders-list

## How the screenshots were taken

- App: `./mvnw spring-boot:test-run` on port 8081 against a throwaway Postgres. Seeded through the real forms:
  operator setup → company "Regavio Sp. z o.o." registered and approved → technician, 4 parts, 4 projects with
  BOMs, 5 orders (high/normal/low, one unassigned) → the technician picked and reported completion on 3 of them.
  A second, approved company "Pusta firma" with no orders gives the empty state.
- Browser: headless Firefox 158, driven over Marionette from a scratchpad script. The theme was forced through
  `localStorage["stockahead-theme"]` and `data-theme`, which is what the toggle does.
- Widths: the viewport was pinned to exactly 1280px and 390px, measured as `innerWidth`. The Firefox window has a
  500px minimum, so the content `<browser>` element was constrained from chrome context.
- Hover: a real pointer move onto the first "Szczegóły" link of `#open-orders`. This covers both the row hover and
  the ghost-button hover.
- Focus: a real Tab key press (not `element.focus()`), so `:focus-visible` applies.
- Naming: `<view>-<state>-<theme>-<width>.png`, as in Phase 1. The before set uses the user's own names
  (`screenshots/before/`), for example `orders-light-390-480.png` and `orders.png`.

## State × theme × width

| State | Light 1280 | Light 390 | Dark 1280 | Dark 390 | Note |
| --- | --- | --- | --- | --- | --- |
| default (both sections) | pass | pass | pass | pass | At 390px both tables scroll horizontally inside `.table-wrap`. Project names wrap onto 2–3 lines. See N2 for dark. |
| hover (row + ghost button) | pass | pass | pass | pass | Row hover tint is visible. The ghost button's hover `--accent` fill is subtle but present. |
| focus — row link ("Szczegóły") | pass | pass | pass | pass | Blue ring around the ghost button in both themes. |
| focus — "Nowe zlecenie" | pass | pass | pass | pass | Ring offset is visible against the blue fill. |
| success (after confirm) | pass | pass | pass | pass | Re-shot after fix F1. Green text and green border, and the alert names the project. Bonus: `orders-list-success-reject-light-1280` shows the reject message. Despite the file name, reject uses a neutral `info` flash (`alert-info`), a user decision during p3 (8a89bf1). Taken before F1, which doesn't affect `alert-info`. |
| empty (no open, no pending) | pass | pass | pass | pass | Only "Otwarte zlecenia" with the dashed `Brak zleceń.` box. `#pending-orders` is absent. |
| error | n/a | n/a | n/a | n/a | The `fragments :: alerts` include is present, but no route sets `error` on a GET of `/orders`, so it can't be reached from the UI. |
| disabled | n/a | n/a | n/a | n/a | The view has no disabled controls (`charges.md`). |
| loading | n/a | n/a | n/a | n/a | Server-rendered, so there is no loading state (`charges.md`). |

Neighbour views, light 1280, compared with `before/`:

| View | Result | Note |
| --- | --- | --- |
| dashboard (`dashboard-default-light-1280`) | pass | Token-level changes only: type scale, radius and shadow. The card grid is intact. |
| parts-list (`parts-list-default-light-1280`) | pass | Token-level changes only. "Przyjmij dostawę" and "Szukaj" pick up the blue `--primary`, the input outline is darker, and the table stays centred. |

## Findings

- **F1 — fixed in Phase 4.** The `.alert-success` border rendered amber (measured hue 60.7, while the text was hue
  151.7). `color-mix(in oklch, var(--success) 40%, var(--border))` interpolates the hue against `--border`. That
  token is achromatic but written with hue `0`, not `none`, so the mix walked 151 → 0 through orange. `.alert-success`
  and `.alert-error` now mix `in oklab`, which has no hue axis, and the success screenshots were re-shot.
- **N1 — note, deferred.** The same oklch-mix pattern is still in three rules: the `tr.row-pending` tint (`app.css`,
  `color-mix(in oklch, var(--warning) 14%, var(--card))`) and the hover fills of `.btn` and `.btn-destructive`
  (`… 88%, var(--foreground)`). The pending tint comes out pink/maroon instead of amber, as the screenshots show. It
  was accepted in Phase 2 (check 2.5), so it is left unchanged here.
- **N2 — headless artefact, not a defect.** At 390px in dark, a white 14px strip shows under each scrolled table.
  It is headless Firefox's classic GTK scrollbar track. The computed `color-scheme` on `.table-wrap` is `dark`, and
  the user's real-browser shot `before/orders-dark-390-480.png` uses overlay scrollbars. Re-check on a real browser
  if in doubt.

## Contrast (WCAG 2.x)

Computed from the oklch token values → sRGB → relative luminance. These match the comments in `app.css`. In Phase 1
(check 1.10) the numbers were also cross-checked with browser devtools.

| Pair | Light | Dark | Threshold | Result |
| --- | --- | --- | --- | --- |
| `--primary-foreground` on `--primary` | 5.03:1 | 7.51:1 | 4.5:1 (text) | pass |
| `--ring` on `--background` | 5.26:1 | 7.51:1 | 3:1 (non-text) | pass |
| `--ring` on `--card` | 5.26:1 | 6.79:1 | 3:1 | pass |
| `--input` on `--background` | 3.36:1 | 3.59:1 | 3:1 (non-text) | pass |
| `--input` on `--card` | 3.36:1 | 3.25:1 | 3:1 | pass |
