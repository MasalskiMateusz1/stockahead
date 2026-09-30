<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: Wydzielenie komunikatów błędów do resource bundle

- **Plan**: context/changes/error-messages-resource-bundle/plan.md
- **Scope**: Full plan
- **Reviewed phases**: 1, 2
- **Date**: 2026-09-28
- **Verdict**: APPROVED
- **Findings**: 0 critical, 0 warnings, 1 observation

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| Plan Adherence | PASS |
| Scope Discipline | PASS |
| Safety & Quality | PASS |
| Architecture | PASS |
| Pattern Consistency | PASS |
| Success Criteria | PASS |

Success Criteria confirmed directly: `./mvnw verify` — 43/43 tests green (14 new `MessagesBundleTests` + 29 existing, unchanged), BUILD SUCCESS.

Plan Adherence confirmed by dedicated review: all 14 message keys byte-identical to the plan's contract (including `„…”` quote characters and Polish diacritics), all method signatures match exactly (`MessageSource`/`Locale` injection in both controllers, `parseLocations`'s new signature), `DataIntegrityViolationException` catch blocks intact and unweakened (lessons.md rule #1), all 5 `@PreAuthorize` annotations untouched. Scope Discipline confirmed: no `LocaleResolver`, no second-language file, no static-label externalization, `PartsCatalogIntegrationTests.java` and `HomeController.java` untouched — exactly as "What We're NOT Doing" specified.

## Findings

### F1 — `parseLocations` takes `messageSource` as a parameter despite it being an instance field

- **Severity**: ℹ️ OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Pattern Consistency
- **Location**: src/main/java/pl/regavio/stockahead/parts/PartController.java:264 (and its two call sites at lines 116, 200)
- **Detail**: `parseLocations(String rawLocations, MessageSource messageSource, Locale locale)` threads `messageSource` through explicitly even though `this.messageSource` is already available as a field on the same class — this is exactly what the plan's Phase 1 Contract specified verbatim, so it's not implementation drift, but it is a minor redundancy worth noting now that the code exists.
- **Fix**: Drop the `messageSource` parameter and reference `this.messageSource` directly inside `parseLocations`; update both call sites to drop the argument.
- **Decision**: FIXED — dropped the `messageSource` parameter from `parseLocations`; the method body now resolves `messageSource` to the instance field automatically. Updated both call sites. `./mvnw verify` still 43/43 green.
