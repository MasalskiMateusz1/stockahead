---
bootstrapped_at: 2026-09-18T17:06:55Z
starter_id: spring
starter_name: Spring Boot
project_name: stockahead
language_family: java
package_manager: maven
cwd_strategy: subdir-then-move
bootstrapper_confidence: verified
phase_3_status: ok
audit_command: "null"
---

## Hand-off

```yaml
starter_id: spring
package_manager: maven
project_name: stockahead
hints:
  language_family: java
  team_size: solo
  deployment_target: self-host
  ci_provider: github-actions
  ci_default_flow: auto-deploy-on-merge
  bootstrapper_confidence: verified
  path_taken: standard
  quality_override: false
  self_check_answers: null
  has_auth: true
  has_payments: false
  has_realtime: true
  has_ai: false
  has_background_jobs: false
```

**Why this stack** (verbatim from hand-off):

A solo developer with about four years of Java and Spring Boot experience is building a small warehouse and production-reservation web app in three weeks of after-hours work, so staying in a familiar, agent-friendly stack matters more than out-of-the-box features. Spring Boot is the recommended default for a Java web app, clears all four agent-friendly gates, and has verified bootstrapper support. The author can also judge when an agent drifts from Spring practice. The default scaffold lacks auth, realtime and persistence, and the user chose to add them manually: Spring Security for email and password login with manager and technician roles, JPA with PostgreSQL and migrations for the transactional reservation guardrails (stock never below zero, no double reservation), and server-sent events or WebSocket so reservations and the shopping list refresh within two seconds. Payments, AI and background jobs are out of scope per the PRD. Deployment is self-hosted, and CI runs on GitHub Actions with auto-deploy on merge, which needs SSH or a self-hosted runner to reach the server.

## Pre-scaffold verification

| Signal             | Value                                                        | Severity | Notes                                                                 |
| ------------------ | ------------------------------------------------------------ | -------- | --------------------------------------------------------------------- |
| npm package        | not run                                                      | —        | non-JS starter                                                        |
| GitHub repo        | not run                                                      | —        | card `docs_url` is https://docs.spring.io/spring-boot/ (not GitHub); no recency signal available |
| Initializr service | start.spring.io live; default Spring Boot 4.1.1; Java 17/21/25/27 offered | fresh    | supplementary check; all requested dependency ids present in metadata |
| Local toolchain    | OpenJDK 21.0.12.1, Apache Maven 3.8.6, curl, tar             | —        | informational; project uses the bundled Maven wrapper (`mvnw`)        |

## Scaffold log

**Card template**: `curl -s https://start.spring.io/starter.tgz -d dependencies=web,devtools -d type=maven-project -d javaVersion=21 -d groupId=com.example -d artifactId={name} | tar -xzf -`

**Resolved invocation**: `curl -sSf https://start.spring.io/starter.tgz -d dependencies=web,devtools,security,data-jpa,postgresql,flyway,validation,thymeleaf,testcontainers -d type=maven-project -d javaVersion=21 -d groupId=pl.regavio -d artifactId=stockahead -d name=stockahead -d packageName=pl.regavio.stockahead -d baseDir=.bootstrap-scaffold | tar -xzf -`

**Deviations from card template** (user-approved at run time):
- Dependencies extended from `web,devtools` to add security, data-jpa, postgresql, flyway, validation, thymeleaf, testcontainers (hand-off flags `has_auth`, `has_realtime`; realtime planned via SSE in spring-webmvc, no extra dependency). Initializr auto-added `thymeleaf-extras-springsecurity6` and `flyway-database-postgresql`.
- `groupId` changed from `com.example` to `pl.regavio`.
- `{name}` was not substituted with `.bootstrap-scaffold` into `artifactId`: the Initializr tarball extracts into cwd unless `baseDir` is set, and a leading-dot artifactId yields an invalid package name. Used `artifactId=stockahead` + `baseDir=.bootstrap-scaffold` to keep the temp-dir-then-move semantics.

**Strategy**: subdir-then-move
**Exit code**: 0 (tar printed harmless warnings: unknown `LIBARCHIVE.creationtime` headers and future timestamps from server clock skew)
**Files moved**: 12 (`.gitattributes`, `.gitignore`, `.mvn/wrapper/maven-wrapper.properties`, `HELP.md`, `mvnw`, `mvnw.cmd`, `pom.xml`, `src/main/java/pl/regavio/stockahead/StockaheadApplication.java`, `src/main/resources/application.properties`, `src/test/java/pl/regavio/stockahead/StockaheadApplicationTests.java`, `src/test/java/pl/regavio/stockahead/TestStockaheadApplication.java`, `src/test/java/pl/regavio/stockahead/TestcontainersConfiguration.java`) plus empty dirs `src/main/resources/{db/migration,static,templates}`
**Conflicts (.scaffold siblings)**: none
**.gitignore handling**: moved silently (absent in cwd)
**.bootstrap-scaffold cleanup**: deleted

Resulting stack: Spring Boot 4.1.1 parent, Java 21, starters webmvc, security, data-jpa, flyway, thymeleaf, validation, devtools; PostgreSQL driver; test starters plus Testcontainers (JUnit Jupiter, PostgreSQL).

## Post-scaffold audit

**Tool**: skipped — no built-in audit tool for java
**Recommended external tool**: OWASP Dependency-Check (`org.owasp:dependency-check-maven`) or Snyk (`snyk test` against `pom.xml`).

## Hints recorded but not acted on

| Hint                       | Value                  |
| -------------------------- | ---------------------- |
| bootstrapper_confidence    | verified               |
| quality_override           | false                  |
| path_taken                 | standard               |
| self_check_answers         | null                   |
| team_size                  | solo                   |
| deployment_target          | self-host              |
| ci_provider                | github-actions         |
| ci_default_flow            | auto-deploy-on-merge   |
| has_auth                   | true (dependencies added; no security config generated) |
| has_payments               | false                  |
| has_realtime               | true (SSE via spring-webmvc; no code generated) |
| has_ai                     | false                  |
| has_background_jobs        | false                  |

## Next steps

Next: a future skill will set up agent context (CLAUDE.md, AGENTS.md). For now, your project is scaffolded and verified — happy hacking.

Useful manual steps in the meantime:
- `git init` (if you have not already) to start your own repo history.
- Review any `.scaffold` siblings the conflict policy created and decide which version of each file to keep (none this run).
- Address audit findings per your project's risk tolerance — the full breakdown is in this log.
- The app will not start until a PostgreSQL datasource is configured in `application.properties`; for local dev, `TestStockaheadApplication` runs the app against a Testcontainers Postgres (needs Docker).
