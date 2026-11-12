---
change_id: modern-ui-refresh
title: Modern UI refresh inspired by boost4body.com and anki.pl
status: implementing
created: 2026-11-11
updated: 2026-11-12
archived_at: null
---

## Notes

- 2026-11-11 — User: "the UI still does not look good, I want you to take into inspiration those sites https://boost4body.com/ and https://anki.pl/ to see how the modern app looks and how can we improve it". Follows the archived `2026-11-09-enhance-ui-design` (orders-list only).
- 2026-11-11 — Phase 2 manual check: user accepted the list views in dark mode; "light mode will need rebuilding later" — the light-mode list styling is deferred to a later pass. Empty-state check (2.7) not yet seen; the seed data has no empty lists.
- 2026-11-11 — Phase 3: user asked for the confirmation pages' secondary link to read "Wróć" (overrides "labels stay as they are" for these three links). Changed `operator.companies.{block,reject}.cancel` and the orders-cancel back link; updated the two `OperatorCompanyLifecycleIntegrationTests` assertions that pinned "Anuluj".
- 2026-11-11 — Phase 3 manual check: user asked for "pokaż nieaktywne" to reload the table on its own and for the filter to share one container with the table. Added `static/js/filters.js` (checkboxes and selects in a GET `.form-inline` submit on change; filter forms with no text field hide their now-redundant button when JS is on) and a `.list-panel` wrapper around filter + table on parts-list, projects-list and picking-list.
- 2026-11-12 — Phase 3 manual check (orders-new): user asked to centre the form card on form-only pages, to move the back link into the form footer opposite "Utwórz zlecenie" (`.form-actions`), and to relabel it "Anuluj" (overrides "labels stay as they are" for this link). Added `scripts/manual-test.sh` (starts `spring-boot:test-run` and seeds demo data through the forms; `--seed-only` for a running app).
- 2026-11-12 — Phase 3 manual check (orders-detail): centred the working area (`.detail-grid` + `#completion-report`) as one column; added a header link "Przejdź do kompletacji" to `/picking/{id}` for OPEN orders (new link, user request; route already allows MANAGER).
- 2026-11-12 — Phase 3 manual check: user asked for increment/decrement controls on numeric fields. Quantity inputs became `type="number"` with a `min` (attribute order kept for the tests that pin `name=… value=…`), and `static/js/stepper.js` wraps every number input with "−"/"+" buttons (progressive enhancement; the native spinners are hidden inside `.stepper`). Added `openOrderDetailLinksToItsPickingPageAndClosedOrderDoesNot`.
- 2026-11-12 — Phase 3 manual check (orders-detail): `#completion-report` moved inside `.detail-grid` so it sits beside "Przypisany technik" with equal card heights; the report line now reads "Zgłoszono <date> przez <email>" in an amber `.report-meta` strip (copy change requested by the user; "Zgłosił(a):" was not test-pinned).
- 2026-11-12 — Same `.form-actions` footer (submit left, "Anuluj" right; header back link removed) applied to parts-new, parts-edit, projects-new and accounts-new. Left as they were: deliveries-new (no back link; two submit buttons), parts-import (upload + preview page) and parts-correction (two forms).
- 2026-11-12 — project-detail, after two tries (a centred column, then separate content-sized cards; both looked off to the user): "Lista materiałowa" and "Dokumentacja" are each one content-sized `.list-panel.panel-fit` holding the list (BOM table / `ul.link-list`) and a one-line `.form-row` add form at its foot ("Dodaj pozycję", "Dodaj link"; the separate h3 cards are gone, fields, names and ids unchanged). Card-opening headings no longer carry the section gap. Both panels sit in a `.panel-stack` so they share the wider one's width.
- 2026-11-12 — `.summary` (parts-correction "Stan / zarezerwowane / dostępne", import preview totals) is only as wide as its text.
- 2026-11-12 — parts-correction: both forms became one-line `form.card.form-row` rows (reason field `size` 60 → 40), and the forms plus the "Poprzednie korekty" panel share one width in a `.panel-stack`, as on project-detail.
