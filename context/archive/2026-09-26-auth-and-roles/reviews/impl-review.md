<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: Logowanie i role (F-01)

- **Plan**: context/changes/auth-and-roles/plan.md
- **Scope**: Full plan
- **Reviewed phases**: 1, 2, 3, 4, 5
- **Date**: 2026-09-27
- **Verdict**: NEEDS ATTENTION
- **Findings**: 0 critical, 3 warnings, 4 observations

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| Plan Adherence | PASS |
| Scope Discipline | PASS |
| Safety & Quality | WARNING |
| Architecture | PASS |
| Pattern Consistency | WARNING |
| Success Criteria | PASS |

## Findings

### F1 — Manager-creation race lacks a DB-level backstop

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Safety & Quality
- **Location**: src/main/java/pl/regavio/stockahead/account/SetupController.java:60-68
- **Detail**: The plan explicitly required race-condition resistance for `/setup` ("odporność na wyścig dwóch równoczesnych zgłoszeń"). `SetupController.createManagerAccount` re-checks `existsByRole(Role.MANAGER)` immediately before `save()`, which narrows the window but doesn't close it — it's a classic check-then-act (TOCTOU). Two `POST /setup` requests with *different* emails can both pass the check before either commits, since nothing at the DB layer enforces "at most one MANAGER." The migration's only constraint on `role` is `CHECK (role IN ('MANAGER','TECHNICIAN'))` (V1__create_accounts.sql:5) — no uniqueness guard.
- **Fix A ⭐ Recommended**: Add a partial unique index in a new Flyway migration — `CREATE UNIQUE INDEX accounts_single_manager_idx ON accounts (role) WHERE role = 'MANAGER';` — so Postgres rejects the second concurrent insert outright.
  - Strength: Closes the race definitively at the only layer that can — the DB — matching AGENTS.md's "schema changes only via migration" convention already followed by this feature.
  - Tradeoff: A new migration file plus a follow-up code change (F2) to handle the resulting exception gracefully.
  - Confidence: HIGH — standard Postgres partial-unique-index pattern; the app's existing migration-only-for-schema convention makes this a natural fit.
  - Blind spot: Haven't independently confirmed Hibernate/Spring translates this exact constraint violation to `DataIntegrityViolationException` in this stack version (very likely — it's Spring's standard translation for unique-constraint violations — but not run here).
- **Fix B**: Accept the residual risk as-is — a one-time bootstrap screen, realistically set up by a single administrator, where the app-level recheck already narrows the window to milliseconds.
  - Strength: No additional work; matches the "MVP, small shop" performance posture already stated in the plan.
  - Tradeoff: Leaves a real (if narrow) gap against the plan's own explicit race-safety requirement.
  - Confidence: MEDIUM — low real-world likelihood, not zero.
  - Blind spot: Haven't checked whether this bootstrap screen could ever be reachable by more than one person in practice (e.g. a shared setup link).
- **Decision**: FIXED via Fix A — added `src/main/resources/db/migration/V2__enforce_single_manager.sql` (partial unique index on `accounts(role) WHERE role = 'MANAGER'`). `./mvnw -B verify` confirmed clean (14/14 tests).

### F2 — Unhandled DataIntegrityViolationException on account save

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: src/main/java/pl/regavio/stockahead/account/SetupController.java:68
- **Detail**: `accountRepository.save(account)` has no error handling. A constraint violation (e.g. duplicate email today, or the race in F1 if that fix lands) would surface as a raw 500 instead of the friendly re-rendered form the token/password-mismatch paths already use earlier in the same method (lines 46-56).
- **Fix**: Wrap the `save()` call in a try/catch for `DataIntegrityViolationException`, re-render `setup` with an error message, consistent with the existing validation-failure pattern in the same method.
- **Decision**: FIXED + ACCEPTED-AS-RULE: "Handle DB constraint violations at write boundaries" (context/foundation/lessons.md). Wrapped `accountRepository.save()` in try/catch, re-renders `setup` with a friendly error. `./mvnw -B verify` confirmed clean (14/14 tests).

### F3 — Setup-token fallback is a well-known public default

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Safety & Quality
- **Location**: src/main/resources/application.properties:5
- **Detail**: `app.setup-token=${STOCKAHEAD_SETUP_TOKEN:local-only-setup-token}` — if the env var isn't set in a real deployment, the setup token silently falls back to a literal that's now public (also hardcoded in `AuthenticationIntegrationTests.java:45` and this repo's own docs). Anyone who knows the default could bootstrap the first MANAGER account. This was a deliberate, documented decision from `context/foundation/deployment.md` (D10/S3) — Coolify is supposed to set the real token in production — not an implementation defect introduced by this change. Flagged for visibility/sign-off, not because the implementer did anything wrong.
- **Fix A ⭐ Recommended**: Leave as-is — this matches the plan's explicit contract and the existing deployment runbook; Coolify sets the real value, and the fallback exists purely for local/test convenience.
  - Strength: Consistent with documented architecture; changing it would be scope creep beyond this change.
  - Tradeoff: Security posture still depends entirely on Coolify's env var being set correctly, with no in-app fail-fast check.
  - Confidence: HIGH — this is exactly what the deployment runbook and plan intended, and was verified together with the user during this implementation.
  - Blind spot: Haven't independently re-verified Coolify's production env var is actually set (out of scope for this repo-local review).
- **Fix B**: Add a startup guard — refuse to start (or log loudly) when `spring.profiles.active=prod` and `STOCKAHEAD_SETUP_TOKEN` is unset or equals the known default.
  - Strength: Converts a silent misconfiguration into an obvious, loud failure.
  - Tradeoff: New code path and complexity for a scenario Coolify's setup process should already prevent; out of this plan's stated scope.
  - Confidence: MEDIUM — solid defense-in-depth, but not something this change committed to deliver.
- **Decision**: ACCEPTED via Fix A — deliberate, documented decision (deployment.md D10/S3); user signed off, no code change.

### F4 — `@PreAuthorize` is the sole defense for manager-only routes, with no structural backstop

- **Severity**: 👁️ OBSERVATION
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Architecture
- **Location**: src/main/java/pl/regavio/stockahead/account/ManagerPingController.java:16
- **Detail**: `SecurityConfig` only has `anyRequest().authenticated()` at the URL level; the MANAGER-only restriction lives entirely in the method-level `@PreAuthorize("hasRole('MANAGER')")`. This is correct today and is explicitly the pattern the plan says F-01 exists to "unlock" for S-01…S-10. But it means a future manager-only controller that forgets the annotation silently degrades to "any authenticated user," with nothing structural to catch it — only a test written for that specific route would notice.
- **Fix**: Worth a `/10x-lesson` entry: every future manager-only (or role-gated) route should ship with an explicit test asserting 403 for the wrong role, since `@PreAuthorize` is the only enforcement layer.
- **Decision**: ACCEPTED-AS-RULE: "Test the wrong-role case for every role-gated route" (context/foundation/lessons.md). No current-code gap — ManagerPingController already has @PreAuthorize + a 403 test; this guides future routes.

### F5 — `/setup/**` permitAll is broader than the actual mapped surface

- **Severity**: 👁️ OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: src/main/java/pl/regavio/stockahead/security/SecurityConfig.java:22
- **Detail**: Only `GET /setup` and `POST /setup` exist; the wildcard doesn't expose anything extra today, but would silently permitAll any future `/setup/*` endpoint too.
- **Fix**: Narrow to exact `/setup` once no sub-paths are planned, or leave as-is if sub-paths (e.g. a confirmation step) are anticipated.
- **Decision**: SKIPPED

### F6 — Redundant `Authentication` + `Principal` parameters

- **Severity**: 👁️ OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Pattern Consistency
- **Location**: src/main/java/pl/regavio/stockahead/account/HomeController.java:25
- **Detail**: `dashboard(Authentication authentication, Principal principal, Model model)` — `Authentication` already extends `Principal`; the second parameter is unused redundancy, not a bug.
- **Fix**: Drop the `Principal` parameter, use `authentication.getName()` in its place.
- **Decision**: FIXED — dropped `Principal` param, uses `authentication.getName()`. `./mvnw -B verify` confirmed clean (14/14 tests).

### F7 — Duplicated Testcontainers configuration

- **Severity**: 👁️ OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Pattern Consistency
- **Location**: src/test/java/pl/regavio/stockahead/account/AuthenticationIntegrationTests.java:171-180
- **Detail**: `AuthenticationIntegrationTests` declares its own nested `PostgresTestcontainersConfiguration` instead of reusing `pl.regavio.stockahead.TestcontainersConfiguration`, because the shared class is package-private in a different package. Both currently pin `postgres:18`, so no drift today — but a future Postgres-version bump in one location would silently desync the two.
- **Fix**: Make the shared `pl.regavio.stockahead.TestcontainersConfiguration` `public` so both `account` and `security` test packages can import it directly, and drop the local duplicate.
- **Decision**: FIXED — made `TestcontainersConfiguration` public, `AuthenticationIntegrationTests` now imports it directly, local duplicate removed. `./mvnw -B verify` confirmed clean (14/14 tests).
