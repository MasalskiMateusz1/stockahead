# Stockahead

[![ci](https://github.com/MasalskiMateusz1/stockahead/actions/workflows/ci.yml/badge.svg)](https://github.com/MasalskiMateusz1/stockahead/actions/workflows/ci.yml)

A parts-warehouse and production-reservation web app for a small electronics plant. Managers order production runs against a part BOM; the system reserves available stock by priority, technicians pick reserved parts, and whatever's missing lands on an exportable shopping list.

Built for a real plant where stock levels lived only in people's heads and shortages were discovered halfway through assembly. The UI is in Polish, for its users.

## Features

- **Parts catalog** with free-text storage locations and three numbers per part: on hand / reserved / available. CSV import with a preview of the resulting stock before anything is saved.
- **Projects** with a bill of materials and documentation links.
- **Production orders** with priority and deadline. Stock is allocated automatically: higher priority first, then earlier deadline, then older order. A higher-priority order takes over unpicked reservations from lower-priority ones, but never from an order a technician has already started picking.
- **Picking lists** with locations for technicians, including partial picks, completion reports (including partial builds, "X of N built") and manager confirmation.
- **Shopping list** that aggregates shortages across all open orders, shows which orders each shortage blocks, and exports to CSV.
- **Deliveries and stock corrections** (with a required reason) that recompute reservations and the shopping list immediately.
- **Two roles**: manager (superset) and technician. Accounts are deactivated, never deleted, so history is kept.

## Engineering highlights

- **Concurrency-safe stock.** Stock can never go below zero and one unit can never be reserved by two orders, even with several users working at once. Enforced in the database (`CHECK` constraints in Flyway migrations) and by locking part rows (`PESSIMISTIC_WRITE`) before every stock or reservation change, not just by form validation.
- **Tested against real PostgreSQL.** 500+ tests, including dedicated concurrency tests (parallel picks, cancellations, deliveries, imports, corrections), run against PostgreSQL in Testcontainers. No H2, no mocked database.
- **Schema as code.** 13 versioned Flyway migrations; Hibernate only validates the schema.
- **Continuous deployment with automatic rollback.** Every merge to `main` is tested, built into a Docker image, pushed to GHCR and deployed. The pipeline then checks that the app actually serves traffic and rolls back to the previous image if it doesn't.
- **Spec-driven development.** Product requirements, the tech-stack and infrastructure decisions, the roadmap and per-change plans live in [`context/`](context/), and the work was done with AI coding agents guided by [`AGENTS.md`](AGENTS.md).

## Tech stack

Java 21, Spring Boot 4.1, Spring Security 7, Spring Data JPA (Hibernate 7), Thymeleaf, PostgreSQL, Flyway, JUnit 5 + Testcontainers, Docker, GitHub Actions. See [`context/foundation/tech-stack.md`](context/foundation/tech-stack.md) for the rationale.

## Getting started

Requires JDK 21 and Docker (the dev/test database runs in Testcontainers).

```bash
./mvnw verify               # build + run all tests
./mvnw spring-boot:test-run # run the app against a throwaway Postgres container
```

On Windows cmd/PowerShell, use `mvnw.cmd` instead of `./mvnw`. Plain `spring-boot:run` won't start until a datasource is configured in `application.properties`.

On first start there are no accounts. Open http://localhost:8080/setup and create the first manager with the setup token `local-only-setup-token` (the default for `STOCKAHEAD_SETUP_TOKEN`). The page disables itself once a manager exists.

## Project structure

- `src/main/java/pl/regavio/stockahead/`: one package per feature: `account`, `orders`, `parts`, `projects`, `purchasing`, `security`
- `src/main/resources/db/migration/`: Flyway migrations (schema changes go here, not `ddl-auto`)
- `context/`: product spec ([`foundation/prd.md`](context/foundation/prd.md)), roadmap, stack and infrastructure decisions, and per-change plans

## Deployment

Self-hosted on Coolify behind a Cloudflare Tunnel, deployed by GitHub Actions on every merge to `main`. See [`context/foundation/deployment.md`](context/foundation/deployment.md) for the full runbook and [`context/foundation/infrastructure.md`](context/foundation/infrastructure.md) for the platform decision.

To roll back, run the `ci` workflow manually (Actions → ci → Run workflow) and enter an earlier image tag such as `sha-1234abc`. It deploys that image without rebuilding and checks that it is serving.

## Contributing

See [`AGENTS.md`](AGENTS.md) for the hard business rules (stock/reservation invariants, access control, non-goals) and the coding conventions this project follows.
