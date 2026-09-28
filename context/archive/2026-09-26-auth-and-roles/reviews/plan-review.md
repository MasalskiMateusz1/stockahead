<!-- PLAN-REVIEW-REPORT -->
# Plan Review: Logowanie i role (F-01) Implementation Plan

- **Plan**: context/changes/auth-and-roles/plan.md
- **Mode**: Deep
- **Date**: 2026-09-26
- **Verdict**: REVISE → SOUND (after fixes applied during triage)
- **Findings**: 2 critical, 2 warnings, 0 observations

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| End-State Alignment | PASS |
| Lean Execution | PASS |
| Architectural Fitness | WARNING |
| Blind Spots | WARNING |
| Plan Completeness | FAIL |

## Grounding

8/8 paths ✓ (pom.xml, TestcontainersConfiguration.java, compose.local.yml, application-prod.properties, application.properties, deployment.md, ci.yml, .env.example), symbols ✓ (STOCKAHEAD_SETUP_TOKEN cross-referenced across 6 files), brief↔plan ✓

## Findings

### F1 — SecurityRedirectTests as specified can't start without a DataSource

- **Severity**: ❌ CRITICAL
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Plan Completeness
- **Location**: Phase 5, item 2
- **Detail**: Phase 5's Contract said this test uses MockMvc "bez `@Import(TestcontainersConfiguration.class)`" but didn't say what replaces it. `AccountRepository` is a Spring Data JPA repository, so a bare `@SpringBootTest` without Testcontainers would crash on context startup (no DataSource bean) — the plan's own stated goal for this test was infeasible as written.
- **Fix**: Specify `@WebMvcTest(controllers = HomeController.class)` + `@Import(SecurityConfig.class)` + `@MockBean` for `AccountUserDetailsService` in the Contract. `@WebMvcTest` excludes JPA/DataSource auto-configuration entirely.
- **Decision**: FIXED

### F2 — The plan's own flagged trickiest gotcha shipped with zero automated verification

- **Severity**: ❌ CRITICAL
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Completeness
- **Detail**: "Critical Implementation Details" warns twice that MANAGER must carry both `ROLE_MANAGER` and `ROLE_TECHNICIAN`, calling it easy to miss. No Success Criterion asserted it — `/manager/ping` only proved MANAGER passes and TECHNICIAN gets 403, never that MANAGER also holds `ROLE_TECHNICIAN`.
- **Fix**: Added a Success Criterion to Phase 2 asserting `AccountUserDetailsService.loadUserByUsername(<manager email>).getAuthorities()` contains both roles, plus Progress row 2.4.
- **Decision**: FIXED

### F3 — HomeController broke the plan's own package convention

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Architectural Fitness
- **Location**: Phase 4, item 2 / Implementation Approach
- **Detail**: "Implementation Approach" states the feature lives in exactly two packages (`account`, `security`) as the convention future slices will copy. Phase 4 placed `HomeController.java` at the root package `pl.regavio.stockahead` — a third, unstated location.
- **Fix**: Moved `HomeController` into `pl.regavio.stockahead.account`; updated "Implementation Approach" to state explicitly that no controller of this feature lands in the root package.
- **Decision**: FIXED

### F4 — Thymeleaf's automatic CSRF token insertion isn't guaranteed with Spring Security 6.x's default handler

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Blind Spots
- **Location**: Phase 3, item 3 / Phase 4, item 1
- **Detail**: Both templates' Contracts assert CSRF "just works" via `th:action`. Recent Spring Security defaults to `XorCsrfTokenRequestAttributeHandler`, which can leave the `_csrf` request attribute unpopulated for a GET-rendered form unless explicitly resolved — a known point of confusion the plan stated as settled fact instead of something to verify.
- **Fix**: Added a manual verification step to Phase 3 and Phase 4 ("view page source, confirm a populated hidden `_csrf` input") plus Progress rows 3.6 and 4.6.
- **Decision**: FIXED

## Triage Summary

Fixed: F1, F2, F3, F4 (4). Skipped: none. Accepted: none. Dismissed: none.

Verdict after fixes: **SOUND**.
