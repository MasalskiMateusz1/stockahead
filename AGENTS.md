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
- Spring Security is 7.x: configure via a `SecurityFilterChain` bean with the lambda DSL (see `security/SecurityConfig.java`); `WebSecurityConfigurerAdapter`, `antMatchers` and `authorizeRequests` do not exist. Method security uses `@PreAuthorize` as in the existing controllers.
- Hibernate is 7.4: import `jakarta.persistence.*`, never `javax.*`.
- The Thymeleaf security dialect artifact is `thymeleaf-extras-springsecurity6` even on Security 7; do not rename it to `…springsecurity7`.
- Patch a vulnerable transitive library by overriding its BOM property (`tomcat.version`, `jackson-bom.version`) in @pom.xml, never with an explicit `<version>` on a starter. Remove the override once the Boot parent catches up.

## Company isolation (prd-v2)

- Every table holding warehouse data (parts, locations, projects, BOM lines, orders, reservations, stock movements, corrections, accounts except the operator) carries a non-null `company_id`; add it in a new Flyway migration, never by editing V1–V13.
- Uniqueness that is global today (part name, project name, location spelling) becomes `UNIQUE (company_id, …)` per FR-028; email stays globally unique (FR-029).
- Load records by `(id, company_id)` — never a bare `findById` on a company-owned entity from a controller. A record of another company answers 404, exactly like a missing id.
- Hibernate 7.4 `@TenantId` does not filter native SQL and has no automatic row-level security. A native query that reads company-owned rows must include `company_id = :companyId`. Exceptions: the login email lookup in `AccountRepository` (intentionally cross-company) and the advisory locks in `PartRepository`/`LocationSpellings` (no data read); once names are unique per company, include the company in the lock key.
- Pessimistic-lock (`PESSIMISTIC_WRITE` / `FOR UPDATE`) and allocation queries in `PartRepository`, `OrderRepository` and `ReservationAllocator` scope to the current company; allocation never reads another company's orders or stock.
- The current company comes from the authenticated principal, never from a request parameter or form field.
- The operator account has no company and no route to any warehouse controller; its access is limited to the company list and approve/reject/block.
- Every company-scoped route ships with a test: a user of company B requesting company A's record id gets 404 (and cannot mutate it), alongside the existing wrong-role 403 test.

## Coding style

- Indent Java and XML with tabs, matching the generated sources. No formatter or linter is configured yet.

## Testing

- Integration tests use JUnit 5 with `@Import(TestcontainersConfiguration.class)` against real Postgres, as in `StockaheadApplicationTests`. Do not add H2 or mock the database for stock or reservation invariants.
