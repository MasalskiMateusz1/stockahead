# Repository Guidelines

Stockahead is a parts-warehouse and production-reservation web app for a small electronics plant, built on Spring Boot 4.1 / Java 21 with Thymeleaf, Spring Security, JPA, PostgreSQL and Flyway. Product spec: @context/foundation/prd.md.

## Hard rules

- A part's stock must never drop below zero, and one unit must never be reserved by two orders. Guard stock with a DB `CHECK (quantity >= 0)` constraint in the Flyway migration, and lock the part row (`@Lock(PESSIMISTIC_WRITE)` or `SELECT … FOR UPDATE`) before changing stock or reservations; form validation alone is not enough.
- Allocate reservations by priority (high first), then earlier deadline, then older order. Never preempt parts already picked, or any reservation of an order that has been taken (first pick done) — except when stock drops below those reservations (stock correction); then taken reservations shrink — completion-reported orders first, then in reverse allocation order — never into picked parts. Recompute reservations and the shopping list after every event listed in PRD § Business Logic.
- Deactivate user accounts; never delete them (FR-002). The manager role is a superset of technician; permissions follow PRD § Access Control.
- Do not implement anything listed in PRD § Non-Goals.
- Never write under `context/archive/`. Edit `context/foundation/` docs in place (@context/foundation/README.md).

## Project structure

- `src/main/resources/db/migration/` holds the Flyway migrations. Change the schema only by adding a new migration; do not rely on Hibernate `ddl-auto`.
- `context/` holds the PRD, the tech-stack decision (@context/foundation/tech-stack.md) and per-change folders. @CLAUDE.md describes the skill workflow that writes there.

## Commands

- `./mvnw verify` compiles the code and runs all tests (`mvnw.cmd verify` from cmd/PowerShell). Docker must be running.
- `./mvnw spring-boot:test-run` starts the app against a throwaway Postgres container via `TestStockaheadApplication`. Plain `spring-boot:run` fails until a datasource is set in `application.properties`.

## Version tripwires

- This project runs Spring Boot 4.x, not 3.x: starters are modular (`spring-boot-starter-webmvc`, per-module `*-test` starters), and Testcontainers 2 packages are used (`org.testcontainers.postgresql.PostgreSQLContainer`). Copy artifact and import names from @pom.xml and @src/test/java/pl/regavio/stockahead/TestcontainersConfiguration.java.

## Coding style

- Indent Java and XML with tabs, matching the generated sources. No formatter or linter is configured yet.

## Testing

- Integration tests use JUnit 5 with `@Import(TestcontainersConfiguration.class)` against real Postgres, as in `StockaheadApplicationTests`. Do not add H2 or mock the database for stock or reservation invariants.
