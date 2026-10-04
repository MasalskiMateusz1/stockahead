---
project: stockahead
assessed_at: 2026-10-04T21:05:17+02:00
agent_readiness: ready-with-compensation
context_type: brownfield
stack_components:
  language: Java 21
  framework: Spring Boot 4.1.1 (Web MVC, Thymeleaf, Spring Security 7.1, Data JPA / Hibernate 7.4, Flyway, Actuator)
  build_tool: Maven 3.9.16 via Maven Wrapper 3.3.4
  test_runner: JUnit 5 + Spring Boot test starters + Testcontainers 2 (PostgreSQL)
  package_manager: Maven (Maven Central)
  ci_provider: GitHub Actions
  deployment_target: Docker image on self-hosted Coolify (OVH) behind Cloudflare Tunnel
gates_passed: 7
gates_partial: 2
gates_failed: 0
---

## Stack Components

**Language: Java 21.** Set in `pom.xml` (`<java.version>21</java.version>`) and installed in CI (`actions/setup-java`, `java-version: '21'`, Temurin) and in the Docker image (`eclipse-temurin:21-jdk-resolute` / `21-jre-resolute`). The language is statically typed. The main code has 96 Java files in feature packages (`account`, `orders`, `parts`, `projects`, `purchasing`, `security`).

**Framework: Spring Boot 4.1.1.** Comes from the `spring-boot-starter-parent` 4.1.1 parent. It uses the modular 4.x starters: `spring-boot-starter-webmvc`, `-data-jpa`, `-flyway`, `-security`, `-thymeleaf`, `-validation`, `-actuator`. The local Maven repository resolves Hibernate ORM 7.4.5.Final and Spring Security 7.1.1. The UI is server-rendered Thymeleaf (23 templates, 1 CSS file, 1 JS file, no JS toolchain), and `thymeleaf-extras-springsecurity6` provides the security dialect. The schema is managed only by Flyway (V1–V13, `spring.jpa.hibernate.ddl-auto=validate`). Role checks use method-level `@PreAuthorize`, and `AccountActivityFilter` sits before `AuthorizationFilter`.

**Build tool: Maven 3.9.16** through the Maven Wrapper 3.3.4 (`.mvn/wrapper/maven-wrapper.properties`, `distributionType=only-script`). `./mvnw verify` is the single build-and-test command, both locally (AGENTS.md) and in CI.

**Test runner: JUnit 5** with the per-module Spring Boot 4 test starters (`spring-boot-starter-*-test`) and Testcontainers 2 (`testcontainers-junit-jupiter`, `testcontainers-postgresql`). There are 44 test files. Integration tests run against real PostgreSQL through `TestcontainersConfiguration`, and `TestStockaheadApplication` serves local runs.

**CI/CD and deployment.** `.github/workflows/ci.yml` runs verify, then pushes an image to GHCR tagged `sha-<7>`, then deploys through the Coolify API. A health check gates the deploy and it rolls back automatically. The Dockerfile has two stages, uses layered-jar extraction, runs as a non-root user, and has a readiness `HEALTHCHECK`. The platform decision is recorded in `context/foundation/infrastructure.md`.

**Scope of the change being prepared** (`context/foundation/prd-v2.md`, multi-company support). The change mostly touches Spring Security (registration, operator role, company status gate), JPA queries and locking (per-company isolation, per-company allocation), and Flyway (company column, per-company uniqueness). The assessment weights these parts most heavily.

## Quality Gate Assessment

| Component   | Typed | Convention | Training Data | Documented | Verdict |
|-------------|-------|------------|---------------|------------|---------|
| Language (Java 21) | ✓ | — | — | — | pass |
| Framework (Spring Boot 4.1 + Security 7 / Hibernate 7) | — | ✓ | ~ | ✓ | pass (version drift) |
| Build tool (Maven + wrapper) | — | ✓ | ✓ | ✓ | pass |
| Test runner (JUnit 5 + Testcontainers 2) | — | — | ~ | ✓ | pass (version drift) |

Legend: ✓ = pass, ✗ = fail, ~ = partial, — = not applicable

7 of 9 applicable criteria pass outright. 2 pass partially, and both partials have the same cause: the project is on new major versions while the agent's training data mostly shows the previous ones. No criterion fails.

### Gate Details

**Typed: Language passes.** Java is statically typed. Evidence: `pom.xml` `<java.version>21</java.version>`. Entities, repositories and controllers declare their parameter and return types, so an agent can infer data shapes by reading the code.

**Convention-based: Framework passes.** Spring Boot relies on autoconfiguration plus the standard Maven layout (`src/main/java`, `src/main/resources/templates`, `src/main/resources/db/migration`). The project adds its own feature-package convention, and AGENTS.md § Project structure documents the Flyway-only schema rule. Evidence: `application.properties` sets `ddl-auto=validate`, and the migrations are named `V<n>__<verb>_<subject>.sql`.

**Convention-based: Build tool passes.** Maven's lifecycle (`verify`, `package`) and the wrapper pin the build. Evidence: `.mvn/wrapper/maven-wrapper.properties` pins Maven 3.9.16, and CI calls `./mvnw -B verify`.

**Training data: Framework is partial.** Spring is the mainstream choice in Java, so it is well represented in training data. The problem is the version: the project runs Boot 4.1, Security 7.1 and Hibernate 7.4, and most public Spring code shows Boot 2.x/3.x idioms. Evidence of version-specific traps:
- the starters are modular (`spring-boot-starter-webmvc` replaced `-web`);
- each module has its own test starter (`spring-boot-starter-data-jpa-test` and so on);
- the Thymeleaf security dialect artifact is still named `thymeleaf-extras-springsecurity6`, even though it runs on Security 7. An agent may try to "fix" this name.

AGENTS.md § Version tripwires already covers starters and Testcontainers. It doesn't cover Security, Hibernate or the Thymeleaf dialect.

**Training data: Build tool passes.** Maven is the dominant Java build tool.

**Training data: Test runner is partial.** JUnit 5 and Testcontainers are mainstream. Testcontainers 2 renamed artifacts and packages (`testcontainers-postgresql`, `org.testcontainers.postgresql.PostgreSQLContainer`), and agents tend to fall back to the 1.x names (`org.testcontainers.containers.PostgreSQLContainer`, `@Container` + `@Testcontainers` boilerplate). AGENTS.md already compensates for this with a tripwire that tells agents to copy from `TestcontainersConfiguration.java`.

**Documented: Framework passes.** Spring Boot, Spring Security and Hibernate ORM all publish versioned reference docs. Context7 has current entries, such as `/hibernate/hibernate-orm`, and versioned ones, such as `/websites/hibernate_orm_7_4`. One finding matters directly for prd-v2: Hibernate's discriminator multi-tenancy (`@TenantId`) only gets database-enforced row-level security from **8.0** onward. On 7.4, which this project uses, **native SQL queries are not filtered by tenant**. The project has native queries in `AccountRepository` (canonical email lookup) and `PartRepository`.

**Documented: Build tool passes.** The Maven reference docs are versioned. **Test runner passes.** JUnit 5 and the Testcontainers 2 docs are current.

## Gaps & Compensation

No criterion fails. There are two partials (version drift in the framework and in the test runner), plus one change-specific risk that comes from the docs check:

1. **Version drift on Spring Security 7 / Hibernate 7 / Thymeleaf extras.** The agent will tend to produce Boot 3-era security config, `javax.*` imports, or a "corrected" `thymeleaf-extras-springsecurity7` artifact. This matters for prd-v2 because the change adds a public registration endpoint, a new operator role and a company-status gate, which is exactly where outdated Security idioms tend to show up. **Compensation:** extend the existing version-tripwire section and require a docs lookup before writing any security or persistence configuration.

2. **Testcontainers 2 naming.** This is already compensated in AGENTS.md, so no action is needed. Keep the rule.

3. **Tenant isolation on Hibernate 7.4.** The framework won't enforce isolation on its own. Whatever isolation mechanism the plan picks (a `@TenantId` discriminator or explicit `company_id` predicates), native queries, `findById` lookups and pessimistic-lock queries are all places where one company's data can leak to another. The PRD guardrail ("including by guessing a record id in the URL") depends on every one of those paths being covered. **Compensation:** add rules that tell the agent where the gaps are and require one negative test per route. The negative-test rule follows the existing "wrong-role case" lesson.

### Recommended Instruction File Additions

Add these under the existing `## Version tripwires` section of `AGENTS.md`:

```markdown
- Spring Security is 7.x: configure via a `SecurityFilterChain` bean with the lambda DSL (see `security/SecurityConfig.java`); `WebSecurityConfigurerAdapter`, `antMatchers` and `authorizeRequests` do not exist. Method security uses `@PreAuthorize` as in the existing controllers.
- Hibernate is 7.4 (`jakarta.persistence.*`, never `javax.*`). Its `@TenantId` multi-tenancy does NOT filter native SQL and has no automatic row-level security (that arrives in Hibernate 8.0).
- The Thymeleaf security dialect artifact is `thymeleaf-extras-springsecurity6` even on Security 7 — do not rename it to `…springsecurity7`.
- Before writing Spring Security, JPA/Hibernate, or Flyway configuration, fetch current docs (Context7: Spring Boot 4.1, Spring Security 7.1, `/hibernate/hibernate-orm` 7.4) instead of relying on memory; this project is one major version ahead of most public examples.
```

Add this as a new section in `AGENTS.md` once prd-v2 work starts (multi-company support):

```markdown
## Company isolation (prd-v2)

- Every table holding warehouse data (parts, locations, projects, BOM lines, orders, reservations, stock movements, corrections, accounts except the operator) carries a non-null `company_id`; add it in a new Flyway migration, never by editing V1–V13.
- Uniqueness that is global today (part name, project name, location spelling) becomes `UNIQUE (company_id, …)` per FR-028; email stays globally unique (FR-029).
- Load records by `(id, company_id)` — never a bare `findById` on a company-owned entity from a controller. A record of another company answers 404, exactly like a missing id.
- Native queries (`nativeQuery = true`, `JdbcClient`) are not tenant-filtered by Hibernate 7.4: every native query on a company-owned table must include `company_id = :companyId` explicitly. The login email lookup in `AccountRepository` is the one intentional cross-company query.
- Pessimistic-lock (`PESSIMISTIC_WRITE` / `FOR UPDATE`) and allocation queries in `PartRepository`, `OrderRepository` and `ReservationAllocator` scope to the current company; allocation never reads another company's orders or stock.
- The current company comes from the authenticated principal, never from a request parameter or form field.
- The operator account has no company and no route to any warehouse controller; its access is limited to the company list and approve/reject/block.
- Every company-scoped route ships with a test: a user of company B requesting company A's record id gets 404 (and cannot mutate it), alongside the existing wrong-role 403 test.
```

## Summary

**Overall verdict: ready with compensation.** Java 21 with Spring Boot meets all four criteria for agent-friendliness: typed, convention-based, mainstream in its language family, and well documented. The repo is also in good shape for an agent:
- `./mvnw verify` is a single command that runs real-Postgres integration tests;
- the schema is managed only through Flyway;
- CI deploys with a health gate and automatic rollback;
- AGENTS.md already carries hard rules and version tripwires.

**Key strengths:** strong typing, opinionated framework, a Testcontainers-based testing culture with no database mocks, and invariants enforced in the database (`CHECK`, pessimistic locks).

**Key gaps:** the gaps are about versions, not the stack itself. The project is one major version ahead of most public Spring/Hibernate/Testcontainers code. For the multi-company change, Hibernate 7.4 won't enforce tenant isolation for native queries or id lookups. The compensation rules above make that explicit.

**Recommended next step:** run `/10x-health-check` to audit dependencies, security and config, using the isolation and version-drift findings as focus areas.
