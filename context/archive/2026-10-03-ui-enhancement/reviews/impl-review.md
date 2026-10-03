<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: UI Enhancement Implementation Plan

- **Plan**: context/changes/ui-enhancement/plan.md
- **Scope**: Full plan
- **Reviewed phases**: 1, 2, 3, 4
- **Date**: 2026-10-03
- **Verdict**: NEEDS ATTENTION
- **Findings**: 0 critical, 2 warnings, 5 observations

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| Plan Adherence | WARNING |
| Scope Discipline | PASS |
| Safety & Quality | WARNING |
| Architecture | PASS |
| Pattern Consistency | WARNING |
| Success Criteria | WARNING |

Automated evidence:
- `./mvnw verify`: exit 0, with 489 tests run and 0 failures, errors or skips. This includes `TemplateConventionTests` and `StaticResourceAccessTests`.
- `grep -rlE '<style|style="' src/main/resources/templates`: no matches.

All manual Progress items are ticked and carry phase SHAs; they are visual checks with no diff evidence by nature.

## Findings

### F1 — Light-mode focus ring below WCAG 3:1 non-text contrast

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: src/main/resources/static/css/app.css:43 (used at :179)
- **Detail**:
  - Focus outlines use `var(--ring)`.
  - In light mode `--ring` is `oklch(0.708 0 0)` (≈ #a1a1a1), about 2.6:1 on the white background. WCAG 1.4.11 asks for at least 3:1 on focus indicators.
  - Dark mode is fine at about 4.2:1.
  - The plan requires a "visible focus ring via `--ring`", and manual check 4.4 depends on it.
- **Fix**: Change the light `--ring` to `oklch(0.556 0 0)` (≈ 4.7:1). Add a ratio comment like the other tokens have. The token is also used for the input focus border at :293, which benefits too.
- **Decision**: FIXED — light --ring set to oklch(0.556 0 0)

### F2 — Narrowed negative assertion in technician list test can silently go vacuous

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Success Criteria
- **Location**: src/test/java/pl/regavio/stockahead/account/TechnicianAccountIntegrationTests.java:169
- **Detail**:
  - `not(containsString(MANAGER_EMAIL))` became `not(containsString("<td>" + MANAGER_EMAIL + "</td>"))`, because the topbar now shows the signed-in email.
  - The new assertion is still a real guard today: `technicians-list.html` renders `<td th:text="${technician.email}">`.
  - But if that `<td>` ever gains an attribute, the negative check passes no matter what.
  - Separately, the plan said existing assertions stay green without edits. The four `contains("td, th")` removals (DeliveryIntegrationTests, PartImportIntegrationTests ×2, StockCorrectionIntegrationTests) were unavoidable, since they asserted the removed inline `<style>`. Nothing now checks that `app.css` keeps `td, th { text-align: center }`.
- **Fix**: Add a positive companion assertion, `containsString("<td>" + <technician email> + "</td>")`, so a markup change breaks the test loudly instead of silently weakening it.
- **Decision**: FIXED — line 165 now asserts `<td>` + TECHNICIAN_EMAIL + `</td>` as positive companion

### F3 — Topbar/toggle drift from plan contract (icon-only toggle, hard-coded role label)

- **Severity**: 💡 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Adherence
- **Location**: src/main/resources/templates/fragments.html:26-32, src/main/resources/static/js/theme.js:42-47, src/main/java/pl/regavio/stockahead/account/HomeController.java:28-29
- **Detail**:
  - **Theme toggle**: the plan says the button text changes to "Motyw: Auto/Jasny/Ciemny". It is icon-only instead (three SVGs switched by `data-mode`), with the text kept in `aria-label` and `title`.
  - **Role label**: it is hard-coded as "Kierownik"/"Technik" instead of the old `${role}`.
  - **Dashboard text**: the "Zalogowano jako" prefix was dropped. The plan's "no visible text changes" rule allowed only "Powrót do pulpitu" to be removed.
  - **Unused model data**: `HomeController` still puts `email`/`role` into the model, but nothing uses them now.
  - **Extra fragment**: an unplanned `icon(name)` fragment was added for the dashboard tiles.
  - All of this is benign and accessible.
- **Fix**: Add a short addendum to the plan recording these choices, and drop the unused `email`/`role` model attributes from `HomeController`.
- **Decision**: FIXED — removed unused email/role model attributes from HomeController; plan addendum added

### F4 — Static asset test doesn't lock in content-hash URLs; no long-lived cache headers

- **Severity**: 💡 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Success Criteria
- **Location**: src/test/java/pl/regavio/stockahead/security/StaticResourceAccessTests.java (loginPageReferencesStylesheetAndThemeScript), src/main/resources/application.properties:19-20
- **Detail**:
  - The test accepts `/css/app` with or without a hash, so it still passes if the versioning config is removed.
  - The plan's Performance section says the hashed URLs "can be cached long-term", but `spring.web.resources.cache.cachecontrol.max-age` is not set.
- **Fix**: Assert a hashed URL (e.g. a regex like `/css/app-[0-9a-f]{32}\.css`). Optionally set `spring.web.resources.cache.cachecontrol.max-age=365d`.
- **Decision**: FIXED — login test asserts 32-hex hashed CSS/JS URLs and `Cache-Control: max-age=31536000` on the hashed stylesheet; `spring.web.resources.cache.cachecontrol.max-age=365d` added (break-check: test red without it)

### F5 — AccountActivityFilter now runs on static asset requests

- **Severity**: 💡 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: src/main/java/pl/regavio/stockahead/security/AccountActivityFilter.java
- **Detail**: CSS and JS are now requested on every page with the session cookie. The account-activity check therefore runs about 3 times per page load (page, CSS, JS) instead of once. This doesn't matter at current scale, and once caching works (F4) it mostly disappears.
- **Fix**: Skip static resource paths in the filter (e.g. `shouldNotFilter` matching `PathRequest.toStaticResources().atCommonLocations()`), if it ever shows up in profiling.
- **Decision**: SKIPPED — F4's long-lived caching makes the extra lookups negligible

### F6 — parts-import error block lacks role="alert"

- **Severity**: 💡 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Pattern Consistency
- **Location**: src/main/resources/templates/parts-import.html:29
- **Detail**: `<div class="alert alert-error">` has no `role="alert"`, unlike the shared `alerts` fragment and the other error alerts. Back-link wording also varies ("Powrót do katalogu części" vs "Powrót do kartoteki części"), but that is pre-existing text the plan kept on purpose.
- **Fix**: Add `role="alert"` to the error block.
- **Decision**: FIXED — role="alert" added

### F7 — Plan's `.stats` list replaced by `.summary` on two views

- **Severity**: 💡 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Adherence
- **Location**: src/main/resources/templates/parts-correction.html, src/main/resources/templates/parts-import-preview.html
- **Detail**: The plan asked for a `.stats` list on these views. Tests assert the inline markup `Stan: <strong>40</strong>` and `Nowe części: <strong>0</strong>`, so a `<dl>` would have broken them. The implementer correctly followed the plan's higher-priority "test-matched markup stays byte-identical" rule.
- **Fix**: No code change; record in the plan addendum together with F3.
- **Decision**: FIXED — recorded in plan addendum (no code change)
