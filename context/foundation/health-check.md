---
project: stockahead
checked_at: 2026-10-04T21:14:36+02:00
health_status: needs-attention
context_type: brownfield
language_family: java
stack_assessment_available: true
checks_run:
  - lockfile
  - dependency_audit
  - outdated_deps
  - test_runner
  - ci_cd
  - configuration
audit_findings:
  critical: 3
  high: 5
  moderate: 2
  low: 0
test_runner_detected: true
ci_provider: GitHub Actions
recommended_fixes: 6
fixes_applied: [1, 2, 4a]
updated_at: 2026-10-04
---

## Dependency Health

### Lockfile

```
Status: missing by design. Maven has no lockfile. The pom.xml declares only parent-managed versions with no ranges, so every build resolves the same tree.
Package manager: maven (Maven Wrapper 3.3.4 pins Maven 3.9.16)
```

All 15 dependencies in `pom.xml` get their version from `spring-boot-starter-parent` 4.1.1, and none use version ranges. Resolution is therefore reproducible without a lockfile. No action needed.

### Security Audit

```
Tool: ./mvnw dependency:list (107 runtime artifacts), then each coordinate queried against the OSV.dev batch API (Maven has no built-in audit tool)
Summary: 3 CRITICAL, 5 HIGH, 2 MODERATE, 0 LOW (at audit time; all 10 resolved on 2026-10-04 by fixes #1–#2)
Direct vs transitive: all 10 are transitive. They come in through Spring Boot starters, and the BOM-managed versions in spring-boot-dependencies 4.1.1 are the vulnerable ones.
```

Spring Boot 4.1.1 is still the newest GA release (checked on Maven Central 2026-10-04; the only newer release is 4.2.0-M2), so a Boot upgrade can't fix these yet. Overriding the BOM version properties does.

#### CRITICAL findings

- **org.apache.tomcat.embed:tomcat-embed-core** 11.0.24, GHSA-9xv2-5v5q-p794 / CVE-2026-65905: the DIGEST authenticator allows authentication bypass by capture-replay. Fix: update to 11.0.25+.
- **org.apache.tomcat.embed:tomcat-embed-core** 11.0.24, GHSA-gcx9-497g-6cp6 / CVE-2026-65182: improper access control / incorrect authorization. Fix: update to 11.0.25+.
- **org.apache.tomcat.embed:tomcat-embed-core** 11.0.24, GHSA-h3x4-894j-xpx5 / CVE-2026-68525: incorrect authorization in Tomcat's FORM authentication. Fix: update to 11.0.25+.

Exploitability note: authentication here is Spring Security `formLogin` (`security/SecurityConfig.java:29`), not Tomcat's container DIGEST/FORM authenticators, so the first and third advisories probably don't apply directly. The second is less narrowly scoped. The advisories are labelled CRITICAL, the patch is a one-line override, and the app is internet-facing behind Cloudflare Tunnel, so treat all three as fix-now.

#### HIGH findings

- **tools.jackson.core:jackson-core** 3.1.5, GHSA-7hhh-6rmp-j9qf / CVE-2026-89425: unbounded StringBuilder growth in error-token reporting (DoS). Fix: update to 3.1.7.
- **tools.jackson.core:jackson-core** 3.1.5, GHSA-p6pp-m3f8-5c89 / CVE-2026-89407: ReDoS in `NumberInput.PATTERN_FLOAT`. Fix: update to 3.1.7.
- **tools.jackson.core:jackson-databind** 3.1.5, GHSA-cxp5-3px4-pw24 / CVE-2026-91777: quadratic forward-reference completion (DoS). Fix: update to 3.1.7.
- **tools.jackson.core:jackson-databind** 3.1.5, GHSA-q4xh-88c3-wmh7 / CVE-2026-68497: unbounded number parse in Duration/XMLGregorianCalendar (DoS). Fix: update to 3.1.6+.
- **tools.jackson.core:jackson-databind** 3.1.5, GHSA-wv8q-qhhj-9h54 / CVE-2026-91776: retains every unknown raw type ID (memory DoS). Fix: update to 3.1.7.

Exposure is low. The app is server-rendered Thymeleaf with form posts, and the only `@RestController` (`account/ManagerPingController.java`) and the actuator health endpoint write JSON without reading request bodies. A future JSON endpoint would be exposed, though.

#### MODERATE findings

- tools.jackson.core:jackson-databind 3.1.5, GHSA-gx83-3vf8-gh7j: `Comparable` missing from the polymorphic-type denylist. Fixed in 3.1.6.
- tools.jackson.core:jackson-databind 3.1.5, GHSA-wjgm-6hv5-3cvf: `Path` deserialization lacks a scheme allowlist. Fixed in 3.1.6.

### Outdated Dependencies

```
Packages with major version gaps: 0
```

The parent (Spring Boot 4.1.1) is the newest GA release, and every dependency follows its BOM. The only lag is the patch-level gap behind the advisories above: Tomcat 11.0.24 vs 11.0.26, Jackson 3.1.5 vs 3.1.7 (3.2.3 on the next minor). GitHub Actions in `ci.yml` are on current majors (`checkout@v7`, `setup-java@v6`, `build-push-action@v7`).

## Test Suite

```
Test runner: JUnit 5 via Maven Surefire (Spring Boot 4 per-module test starters + Testcontainers 2 / PostgreSQL)
Tests found: 512 tests in 44 test files
Test execution: passing (./mvnw -B verify, 512 run, 0 failures, 0 errors, 0 skipped, 2 min 34 s, Docker 29.8.2)
```

```
Configuration: pom.xml (Surefire defaults), src/test/java/pl/regavio/stockahead/TestcontainersConfiguration.java
Framework: JUnit Jupiter via spring-boot-starter-*-test 4.1.1; Testcontainers 2.0.5
```

Notes:
- `target/surefire-reports/` holds two `*-jvmRun1.dumpstream` files (2026-10-01, 2026-10-03) with `EOFException … TestSet has not finished`. These come from interrupted earlier runs, not this one. `./mvnw clean` removes them.
- Spring Security logs a warning at startup: "Global AuthenticationManager configured with an AuthenticationProvider bean. UserDetailsService beans will not be used…". The tests pass, so this is likely intentional, but it is noise an agent may try to "fix". If it is intentional, silence it as the message suggests.

## CI/CD

```
Provider: GitHub Actions
Configuration: .github/workflows/ci.yml
```

| Stage      | Status | Notes                                                                 |
|------------|--------|-----------------------------------------------------------------------|
| Lint       | ✗      | not configured (no Checkstyle/Spotless/PMD in the build)              |
| Test       | ✓      | `./mvnw -B verify` (JUnit 5 + Testcontainers Postgres)                |
| Build      | ✓      | `./mvnw -B verify`, then a Docker build pushed to GHCR as `sha-<7>`        |
| Type check | ✓      | javac compilation inside `verify` (Java is statically typed)          |
| Security   | ✗      | no dependency scanning (no Dependabot, CodeQL, OSV or OWASP check) |

The pipeline goes beyond the MVP stage. It deploys through the Coolify API with a check that the app is actually serving, rolls back automatically to the previous tag, and offers a manual tag dispatch for rollbacks. The missing security stage is the reason the advisories above went unnoticed.

## Configuration

### High severity

None. `.gitignore` is present and covers `target/`, `.env`, dumps, IDE files and per-user Claude settings.

### Medium severity

- **Formatter / linter (Java)**: none is configured, and AGENTS.md says so ("No formatter or linter is configured yet"). The tab-indent rule depends on the agent remembering it, and nothing catches unused imports or mixed indentation. Fix: add Spotless with a minimal tab-preserving config (see Recommended Fixes #5).

### Low severity

- **`.editorconfig`**: missing. AGENTS.md requires tabs for Java and XML, and an `.editorconfig` would encode that for every editor and for agents that honour it. Fix: see Recommended Fixes #6.
- **`.env.example`**: present at `deploy/.env.example` rather than the root. It documents `SPRING_PROFILES_ACTIVE`, the datasource variables and `STOCKAHEAD_SETUP_TOKEN`. No action needed.

## Stack Assessment Cross-Reference

```
Stack assessment: context/foundation/stack-assessment.md
Agent readiness (from stack-assess): ready-with-compensation
```

| Quality Gate Gap | Health-Check Finding | Status |
|---|---|---|
| Training data: framework partial (Security 7 / Hibernate 7 / Thymeleaf extras drift) | AGENTS.md § Version tripwires still covers only starters and Testcontainers. The four recommended Security/Hibernate/Thymeleaf/docs-lookup entries have not been added. | Open, compensation missing |
| Training data: test runner partial (Testcontainers 2 naming) | AGENTS.md tripwire present; 512 tests pass on Testcontainers 2.0.5 | Mitigated |
| Tenant isolation on Hibernate 7.4 (prd-v2) | `context/changes/multi-company-support/` already exists, but AGENTS.md has no `## Company isolation (prd-v2)` section. The stack assessment said to add it "once prd-v2 work starts", and that work has started. | Open, compensation missing |
| Typed: pass | javac in CI verify; no gap | Reinforced (strength) |
| Version drift in general | The project is on the newest Boot GA, but the BOM-pinned Tomcat/Jackson are vulnerable. Patching means overriding BOM properties, a Boot-specific idiom agents often get wrong (for example, adding explicit `<version>` to starters) | Reinforced, see fixes #1–#2 |

## Recommended Fixes

### Fix before agent work (Category A)

### 1. Patch Tomcat (3 CRITICAL advisories)

**Status**: ✅ Applied 2026-10-04. `tomcat.version` is 11.0.26, `./mvnw verify` passes 512/512 and OSV reports no advisories for 11.0.26.

**Impact**: The app is internet-facing. Agent work on prd-v2 adds a public registration endpoint and changes authorization, which is the area these advisories touch. Patch first so new code isn't built and tested on a vulnerable container.
**Severity**: critical
**Effort**: quick (< 5 min)
**Fix**:

Override the BOM property in `pom.xml` (tabs, as in the rest of the file):

```xml
	<properties>
		<java.version>21</java.version>
		<!-- CVE-2026-65905/65182/68525; drop when spring-boot-starter-parent ships Tomcat >= 11.0.25 -->
		<tomcat.version>11.0.26</tomcat.version>
	</properties>
```

Then run `./mvnw verify` and `./mvnw dependency:tree -Dincludes=org.apache.tomcat.embed` to confirm 11.0.26.

### 2. Patch Jackson 3 (5 HIGH, 2 MODERATE advisories)

**Status**: ✅ Applied 2026-10-04. `jackson-bom.version` is 3.1.7, `./mvnw verify` passes 512/512 and OSV reports no advisories for 3.1.7.

**Impact**: DoS vectors on any JSON parsing. They are latent today, but they become reachable the moment an agent adds a JSON endpoint.
**Severity**: high
**Effort**: quick (< 5 min)
**Fix**:

Add next to the Tomcat override:

```xml
		<!-- CVE-2026-89425/89407/91777/68497/91776; drop when Boot's BOM reaches Jackson >= 3.1.7 -->
		<jackson-bom.version>3.1.7</jackson-bom.version>
```

Confirm with `./mvnw dependency:tree -Dincludes=tools.jackson.core`. Use the BOM property, not explicit `<version>` tags on dependencies.

### 3. Add dependency scanning to CI

**Status**: open

**Impact**: Nothing in the pipeline watches advisories, so the 10 findings above surfaced only through this manual audit. The agent can't react to what nobody reports.
**Severity**: medium
**Effort**: moderate (15–30 min)
**Fix**:

Create `.github/dependabot.yml`:

```yaml
version: 2
updates:
  - package-ecosystem: maven
    directory: /
    schedule:
      interval: weekly
  - package-ecosystem: github-actions
    directory: /
    schedule:
      interval: weekly
```

Enable Dependabot alerts and security updates under the repo's Settings → Code security. Dependabot raises PRs for parent and property bumps, and the existing `verify` job gates them.

### 4. Add the stack-assessment compensation rules to AGENTS.md

**Status**: partly applied 2026-10-04. (a) ✅ Version tripwires for Security 7, Hibernate 7.4, the Thymeleaf dialect and BOM overrides were added to AGENTS.md. (b) ⏸ The `## Company isolation` section and the Hibernate tenant/native-query warning are deferred on purpose to the multi-company branch: main runs production single-company Stockahead and gets that work only through a manual merge. When you add them there, list the advisory-lock query in `PartRepository` as an exception next to the login lookup, since it reads no data.

**Impact**: Multi-company work has started (`context/changes/multi-company-support/`). Without these rules, the agent will write Boot 3-era Security config and bare `findById` lookups, and the docs say Hibernate 7.4 won't stop cross-company leaks through native queries.
**Severity**: medium
**Effort**: quick (< 5 min)
**Fix**:

Paste the two blocks from `context/foundation/stack-assessment.md` § Recommended Instruction File Additions into `AGENTS.md`: the four bullets under `## Version tripwires`, and the new `## Company isolation (prd-v2)` section. While editing the tripwires, add one line: "Patch a vulnerable transitive library by overriding its BOM property (`tomcat.version`, `jackson-bom.version`) in `pom.xml`, never with an explicit `<version>` on a starter."

### 5. Add a formatter check (Spotless)

**Status**: open

**Impact**: Tab indentation and import hygiene currently depend on the agent remembering AGENTS.md. A failing `verify` gives the agent a feedback loop it can act on.
**Severity**: medium
**Effort**: moderate (15–30 min)
**Fix**:

Add to `<build><plugins>` in `pom.xml` a minimal config that keeps the existing tab style instead of reformatting everything:

```xml
			<plugin>
				<groupId>com.diffplug.spotless</groupId>
				<artifactId>spotless-maven-plugin</artifactId>
				<version><!-- current release from Maven Central --></version>
				<configuration>
					<java>
						<removeUnusedImports/>
						<trimTrailingWhitespace/>
						<endWithNewline/>
						<leadingSpacesToTabs/>
					</java>
				</configuration>
				<executions>
					<execution>
						<goals><goal>check</goal></goals>
					</execution>
				</executions>
			</plugin>
```

Run `./mvnw spotless:apply` once, commit the result, then update the AGENTS.md § Coding style line ("No formatter or linter is configured yet") to name `./mvnw spotless:apply`.

### 6. Add `.editorconfig`

**Status**: open. Revised content: drop `properties` from the tab group (those files have no indentation) and give `*.md` only `trim_trailing_whitespace = false`, because Markdown indentation in the repo is mixed.

**Impact**: This encodes the tabs rule for editors and agents that honour it, at no cost.
**Severity**: low
**Effort**: quick (< 5 min)
**Fix**:

```ini
root = true

[*]
charset = utf-8
end_of_line = lf
insert_final_newline = true
trim_trailing_whitespace = true

[*.{java,xml,html,css,js,sql,properties}]
indent_style = tab

[*.{yml,yaml,md}]
indent_style = space
indent_size = 2

[*.cmd]
end_of_line = crlf
```

### Addressed in upcoming lessons (Category B)

Nothing is pending here. The usual Category B items are already in place:

- **CI/CD pipeline**: present (`.github/workflows/ci.yml`, with verify, image, deploy and automatic rollback). This was covered in [Sprint Zero z Agentem: infrastruktura, walking skeleton i pierwszy deploy (M1L5)](https://platforma.przeprogramowani.pl/external/10xdevs-3/m1-l5). The only gap, a security scanning stage, is listed as fix #3 because it is small and needed now.
- **Agent instruction files**: `AGENTS.md` and `CLAUDE.md` are present. This was covered in [Agent Onboarding: Agents.md, AI Rules i feedback loops (M1L4)](https://platforma.przeprogramowani.pl/external/10xdevs-3/m1-l4). The missing compensation entries are fix #4.
- **Deployment configuration**: present (`Dockerfile`, `deploy/compose.local.yml`, `deploy/.env.example`, `context/foundation/infrastructure.md`).

## Summary

Health status: needs-attention (was critical-issues at audit time; fixes #1–#2 applied 2026-10-04)

The project is otherwise in very good shape for agent work. It builds on the newest Spring Boot GA, a single `./mvnw verify` command runs 512 real-Postgres integration tests and all of them pass, and CI deploys with a serving check and automatic rollback. The verdict comes entirely from transitive libraries: Tomcat 11.0.24, which Boot's BOM pins, carries three CRITICAL advisories, and Jackson 3.1.5 carries five HIGH ones. Two property overrides in `pom.xml` fix all of them in a few minutes, and after that the status drops to needs-attention: no dependency scanning in CI, the stack-assessment compensation rules missing from AGENTS.md, and no formatter.

Update 2026-10-04: fixes #1–#2 are applied and verified, so no known advisories remain and the status is needs-attention. The general AGENTS.md tripwires (#4a) are in.

Next step: add Dependabot (#3) so new advisories surface on their own. Add the company-isolation rules (#4b) as the first commit on the multi-company branch. Spotless (#5) and `.editorconfig` (#6) are optional quality work.
