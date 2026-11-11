# Enhance UI Design (orders-list + global tokens) — Plan Brief

> Full plan: `context/changes/enhance-ui-design/plan.md`
> Research: `context/changes/enhance-ui-design/research.md`
> Charge list: `context/changes/enhance-ui-design/charges.md`

## What & Why

The goal is to make the app "look like a 21st-century app, not a 20th". Today's UI is grayscale, uses the system font with sizes chosen per component, centres every table cell and gives every button the same black block. This change sets up a Linear/Vercel-style token system (type, spacing, shadow and one blue accent) and applies it fully to one view, `orders-list`. It also gives the manager feedback after confirming or rejecting a completion report.

## Starting Point

`static/css/app.css` has shadcn "Neutral" colour tokens only, with the dark palette declared twice. `orders-list.html` has two centred tables whose row links use two different full-size button styles, and it shows no alerts. Confirm and reject completion redirect to `/orders` silently. Tests pin bare `<tr>`/`<td>` markup and forbid inline styles.

## Desired End State

On `/orders`:
- "Nowe zlecenie" is the only solid blue element.
- Rows are compact, with text left-aligned and quantities and dates right-aligned in tabular digits.
- Both tables use one small ghost row action.
- Confirm and reject end with a green alert naming the project.

Every other view inherits the new type scale, the blue accent and visible input outlines, but keeps its own layout. Before/after screenshots cover every state in light and dark, at desktop width and 390px.

## Key Decisions Made

| Decision | Choice | Why (1 sentence) | Source |
| --- | --- | --- | --- |
| Stack path | Thymeleaf + plain CSS, no build/dependency | Cheapest route to a modern look; the React options were rejected | Research / `/10x-ui` |
| Scope | One view (orders-list) + global tokens | Proves the system before rolling it out | `/10x-ui` |
| Motif | Linear/Vercel-like, mapped onto existing shadcn token names | Extend, don't fork a second palette | `/10x-ui` |
| Accent | Blue on `--primary`/`--ring`, ≥4.5:1 text, ≥3:1 ring | User preference; `--info` stays as is and is told apart by form | Plan |
| Font | Modern system stack (Inter only if installed), `ui-monospace`, tabular nums | No font file, so no new asset or dependency | Plan |
| Dark palette | One `light-dark()` value per token plus `color-scheme` on `data-theme` | Removes the duplicated dark block without touching `theme.js` | Plan |
| Table alignment | Opt-in `table.data-table`, column selectors scoped to section ids | Cells can't carry classes; other 15 tables stay untouched | Plan |
| Feedback scope | `success` flash on confirm/reject only; error page deferred | Only these two redirect to `/orders` (C5 assumed more; code corrected it) | Plan |
| Border contrast | Raise `--input` to ≥3:1; `--border` stays subtle | Fixes control findability without heavy grid lines | Plan |
| Visual check | Manual before/after screenshot set + `visual-gate.md` | Screenshot tests were declined before; no new tooling | Plan |

## Scope

**In scope:**
- Token scales (`--font-*`, `--text-*`, `--space-*`, `--shadow-*`), the blue accent, `--input` contrast and the dark palette merge
- `.btn-sm`, `.btn-ghost` and `table.data-table`
- orders-list template: table classes, row-link variant, alerts and success alert
- Success flashes for confirm/reject, 2 message keys, and tests
- Screenshots and the visual-gate record

**Out of scope:**
- React or any JS layer
- Restyling other views' layouts
- The topbar redesign
- A styled error page
- Success flashes on the detail page
- Changes to `--border` and `--info`
- Automated screenshot tests

## Architecture / Approach

Contract before pixels. Tokens and base elements change first, so every view inherits them. Reusable components (`.btn-*`, `.data-table`) come next and are opted into by orders-list only, without adding attributes to table rows or cells. The only server change is two `RedirectAttributes` flashes in `OrderController`, rendered with the existing `alert-success` markup used by `operator-companies.html`.

## Phases at a Glance

| Phase | What it delivers | Key risk |
| --- | --- | --- |
| 1. Baseline + tokens | Before screenshots; `light-dark()` palette; type, spacing and shadow scales; blue accent; `--input` ≥3:1 | Global change visible on all 27 views; theme toggle regression |
| 2. Shared components + orders-list | `.btn-sm`/`.btn-ghost`, `.data-table`, restyled orders-list | `nth-child` column selectors break silently if columns change |
| 3. Feedback after confirm/reject | Success flash + alert on `/orders`, tests | Project name must be read under the lock, inside the transition |
| 4. Visual gate | After screenshots, state × theme × width table, contrast numbers, charges.md closure | Manual: needs seeded data and patience |

**Prerequisites:** Docker running (`./mvnw verify`, `spring-boot:test-run`), a browser for screenshots (Claude in Chrome or by hand).
**Estimated effort:** ~2–3 after-hours sessions across 4 phases.

## Open Risks & Assumptions

- The blue `--primary` and the blue-700 `--info` (outlined "Zgłoszone" badge on detail pages) are close in hue. We accept this and rely on the difference in form; revisit when the detail pages are restyled.
- `light-dark()` needs 2024+ browsers. We assume the plant's machines run current browsers.
- Token-level changes (font, accent, radius, input outline) will show on all views without a per-view visual check, beyond dashboard and parts-list.
- `charges.md` C5 overstated the silent redirects. Create, change and cancel land on the detail page, which already shows alerts.

## Success Criteria (Summary)

- A manager opening `/orders` sees a clearly modern page: one blue main action, scannable aligned columns, compact rows and consistent type, in both themes and on a phone.
- Confirming or rejecting a completion report visibly confirms what happened.
- `./mvnw verify` is green, and no other view's layout breaks.
