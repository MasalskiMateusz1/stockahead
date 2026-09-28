<!-- PLAN-REVIEW-REPORT -->
# Plan Review: Konta techników (S-02)

- **Plan**: context/changes/technician-accounts/plan.md
- **Mode**: Deep (verified inline)
- **Date**: 2026-09-28
- **Verdict**: REVISE → SOUND after triage (all 10 findings fixed in the plan)
- **Findings**: 0 critical, 7 warnings, 3 observations

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| End-State Alignment | PASS |
| Lean Execution | WARNING |
| Architectural Fitness | WARNING |
| Blind Spots | WARNING |
| Plan Completeness | WARNING |

## Grounding
7/7 paths ✓ (V3 is the latest migration, V4 is free), 4/4 symbols ✓ (findByEmail, @PreAuthorize, PasswordEncoder, PartController:69), brief↔plan ✓

## Findings

### F1 — No test harness for the V4 migration's own criteria

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Blind Spots
- **Location**: Phase 1 criteria 1.1, 1.3; Phase 4 §1
- **Detail**: 1.1 and 1.3 need data that exists before V4 runs. The @SpringBootTest TechnicianAccountIntegrationTests applies V1–V4 to an empty DB at context start, so neither criterion can be exercised. The plan also doesn't specify the mechanism for "stops with a readable error" (DO $$ … RAISE EXCEPTION … $$).
- **Fix A ⭐ Recommended**: A separate V4MigrationTests class driving Flyway directly (migrate(target "3") → seed colliding and non-colliding rows → migrate() → assert the FlywayException message, row count, id and canonical email) on a fresh schema of the Testcontainers Postgres; name the RAISE EXCEPTION message in the Phase 1 contract.
  - Strength: Proves 1.1/1.3 on real Postgres; flyway-core is already on the classpath.
  - Tradeoff: A second kind of test in the project (no Spring context, separate schema).
  - Confidence: HIGH — standard Flyway migration-testing approach.
  - Blind spot: The container's @ServiceConnection has to hand its JDBC URL to the test.
- **Fix B**: Narrow 1.1/1.3 to behaviour after the migration; check the collision path by hand.
  - Strength: No new kind of test.
  - Tradeoff: The riskiest code path in the slice has no automated test.
  - Confidence: HIGH
  - Blind spot: None significant
- **Decision**: FIXED (Fix A)

### F2 — Pre-check and DB catch leave 2.3 (the race) with no deterministic test

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Lean Execution
- **Location**: Phase 2 §1–2, criteria 2.2/2.3
- **Detail**: With an application-level "email taken" check, a sequential test never reaches the DataIntegrityViolationException catch; 2.3 is only testable with a real thread race (flaky). Parts-catalog made the DB constraint authoritative instead.
- **Fix**: Drop the application pre-check; the V4 unique index is the only duplicate check and the catch handles both the ordinary duplicate and the race (the role is always TECHNICIAN, so accounts_single_manager_idx isn't a risk; state this in the plan).
  - Strength: One code path; 2.2/2.3 tested with one sequential request; matches PartController and lesson #1.
  - Tradeoff: The catch has to tell the email conflict apart from other constraints (not an issue here).
  - Confidence: HIGH — the pattern is already on main.
  - Blind spot: None significant
- **Decision**: FIXED

### F3 — Phase 1–3 "Automated" criteria aren't commands, and their tests arrive in Phase 4

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Completeness
- **Location**: Success Criteria of Phases 1–3
- **Detail**: /10x-implement runs Automated criteria as gates; in P1–P3 they describe behaviour with no command, and the tests only come in P4, so the gates would be ticked without evidence.
- **Fix**: Move each phase's tests into that phase (V4 migration test → P1, account management → P2, sessions → P3) and add `./mvnw verify` to every phase; P4 keeps the regression pass and the walkthrough.
- **Decision**: FIXED

### F4 — Changing findByEmail breaks 3 existing test files the plan doesn't mention

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Completeness
- **Location**: Phase 1 §2
- **Detail**: findByEmail is used by AccountUserDetailsServiceTest (Mockito stubs in all 4 tests), PartsCatalogIntegrationTests:100-101 and AuthenticationIntegrationTests:64,88.
- **Fix**: Name the new method (e.g. findByCanonicalEmail) and list the callers to update, at minimum the stubs in AccountUserDetailsServiceTest.
- **Decision**: FIXED

### F5 — The canonical email is defined in two places that can disagree

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Blind Spots
- **Location**: Phase 1, Phase 2 contract, Critical Implementation Details
- **Detail**: Java trim()/toLowerCase() vs SQL btrim()/lower() differ (tabs, locale); SetupController:72 keeps saving the raw email, contradicting "unify storage".
- **Fix**: One Java helper (strip() + toLowerCase(Locale.ROOT)) used when saving (technician form and /setup) and when searching.
- **Decision**: FIXED

### F6 — The session filter's registration and position aren't specified

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Architectural Fitness
- **Location**: Phase 3 §1
- **Detail**: A @Component filter is also registered by Spring Boot as a servlet filter (it runs twice, outside the security chain); the position relative to AuthorizationFilter isn't specified.
- **Fix**: Create the filter in SecurityConfig (not a @Component) and add it with http.addFilterBefore(filter, AuthorizationFilter.class); skip anonymous users.
- **Decision**: FIXED

### F7 — New non-transactional tests will collide with the single-manager index

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Blind Spots
- **Location**: Phase 4 §1
- **Detail**: The session and race tests need commits; a manager left behind breaks AuthenticationIntegrationTests.setupFormIsAvailableWhenNoManagerExists and seeding managers in other classes.
- **Fix**: No @Transactional; fixtures committed and deleted @BeforeEach/@AfterEach as in PartsCatalogIntegrationTests:100; a unique manager email.
- **Decision**: FIXED

### F8 — A deactivated session lands on "Nieprawidłowy e-mail lub hasło"

- **Severity**: ℹ️ OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Blind Spots
- **Location**: Phase 3 §1
- **Detail**: /login?error shows login.error.badCredentials (login.html:10).
- **Fix**: Redirect to /login?deactivated with its own message.
- **Decision**: FIXED

### F9 — Phase 4 §2 is stale and duplicates Phase 2

- **Severity**: ℹ️ OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Lean Execution
- **Location**: Phase 4 §2, plan-brief "Prerequisites"
- **Detail**: Refers to parallel work on S-01, which is archived (0b50e39); the dashboard and messages edits already happen in Phase 2 §3.
- **Fix**: Remove Phase 4 §2 and the Prerequisites sentence in the brief.
- **Decision**: FIXED

### F10 — The response for a manager id or a nonexistent id isn't decided

- **Severity**: ℹ️ OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Blind Spots
- **Location**: Phase 2 §1
- **Detail**: "Changes no account" leaves the status open; the test will assert whatever the implementation does.
- **Fix**: 404 for both, with an assertion in 2.4.
- **Decision**: FIXED
