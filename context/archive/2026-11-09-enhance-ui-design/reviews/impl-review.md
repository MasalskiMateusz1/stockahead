<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: Enhance UI Design (orders-list + global tokens)

- **Plan**: context/changes/enhance-ui-design/plan.md
- **Scope**: Full plan
- **Reviewed phases**: 1, 2, 3, 4
- **Date**: 2026-11-11
- **Verdict**: APPROVED
- **Findings**: 0 critical, 1 warning, 2 observations

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| Plan Adherence | WARNING |
| Scope Discipline | PASS |
| Safety & Quality | PASS |
| Architecture | PASS |
| Pattern Consistency | PASS |
| Success Criteria | PASS |

Evidence notes:
- Commits 6df09e9, 3c5d93f, 8a89bf1, 8ee09bb and d83c763 touch exactly the files the plan names. The only extra is a
  comment-only edit to `theme.js:4`, which describes the new `light-dark()` mechanism. It is benign.
- Automated criteria:
  - `./mvnw verify` passed on the final code (2026-11-11, run after the p4 CSS fix; later commits are doc-only).
  - grep 1.3 returns nothing.
  - grep 1.4 returns 0.
  - grep 2.2 lists only the `th:`-attributed `<tr>`/`<td>` lines.
  - `screenshots/after/` (27 PNGs) and `visual-gate.md` exist.
- Manual items 1.5–4.7 are all `[x]`. Phase 4's items were confirmed by the user in-session on 2026-11-11.
- Safety:
  - The flash text is rendered with `th:text`, which escapes it.
  - The project name comes from the order re-read under the lock (`OrderController.java:466-470`). It is cleared on
    retry and on error, so only a committed transition flashes.
  - `MessageFormat` arguments are not subject to apostrophe escaping.

## Findings

### F1 — Reject flashes `info`, but the plan and the gate docs still say `success`

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Adherence
- **Location**: src/main/java/pl/regavio/stockahead/orders/OrderController.java:190, context/changes/enhance-ui-design/charges.md (C5 status), context/changes/enhance-ui-design/visual-gate.md (success row)
- **Detail**: The Phase 3 contract says "the flash attribute is named `success`" for both confirm and reject. The code
  flashes `info` for reject, which renders as `alert alert-info`. This was the user's choice during Phase 3 manual
  verification, but it is recorded only in commit 8a89bf1's message. `charges.md` C5 says "the success flash covers
  those two". `visual-gate.md` calls the reject screenshot `orders-list-success-reject-light-1280` and lists it under
  the success row, but it shows the neutral info alert.
- **Fix**: Add one line to `charges.md` C5 and to `visual-gate.md` noting "reject uses a neutral `info` flash (user
  decision, p3, 8a89bf1)".
- **Decision**: FIXED — note added to charges.md C5 and visual-gate.md

### F2 — The primary-button hover mix shifts the blue toward violet

- **Severity**: 💡 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: src/main/resources/static/css/app.css:378
- **Detail**: This change rewrote the hover as `color-mix(in oklch, var(--primary) 88%, var(--foreground))`.
  `--foreground` is achromatic but has hue 0, so the hue moves from 262.9 to about 274.5 in light and from 254.6 to
  about 267.3 in dark. This is the same mechanism as the success-border bug fixed in p4. Contrast still passes
  (light 6.27:1, dark 8.35:1). The comment's 6.12/8.52 figures assume the hue is preserved, which only holds with
  `in oklab`. The pre-existing `tr.row-pending` (pink tint) and `.btn-danger:hover` rules have the same issue, and
  they are already deferred in `charges.md` / `visual-gate.md` N1.
- **Fix**: Switch the `button:hover, .btn:hover` mix to `in oklab`. The existing contrast comment then holds as
  written.
- **Decision**: FIXED — `.btn:hover` mix switched to `in oklab` (app.css:378); `.btn-danger:hover` and the pending-row tint stay deferred (N1)

### F3 — A list-page assertion was narrowed to the table cell

- **Severity**: 💡 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Success Criteria
- **Location**: src/test/java/pl/regavio/stockahead/orders/OrderCompletionIntegrationTests.java:634
- **Detail**: `not(containsString("Order Completion Board"))` became `not(containsString("<td>Order Completion Board</td>"))`.
  The change is legitimate: after a confirm, the flash itself names the project, so the old assertion would fail even
  though the row is gone. The narrowed form still proves the completed order left the table, because cells carry no
  attributes. It does couple the test to the bare-`<td>` markup rule, which `lessons.md` already enforces.
- **Fix**: None needed. Recorded so the narrowing is visible.
- **Decision**: ACCEPTED — the narrowing is correct; the flash now names the project
