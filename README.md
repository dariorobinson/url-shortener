# URL Shortener

A production-quality prototype of a URL shortener service, built with Java 25, Spring Boot 3.x, and PostgreSQL.

> **Status:** In development. Sections marked *(pending)* are completed as the related tasks land.

## Features (planned)

- Create short URLs from long URLs, with optional custom aliases
- 302 redirects from short codes to original URLs
- Click analytics: total clicks, last accessed time, clicks per day in the caller's time zone
- Deactivate / reactivate links (owner or admin); soft delete (admin only)
- Role-based access: `USER` and `ADMIN`
- Consistent RFC 7807 (`application/problem+json`) error responses
- URL expiration (added later as the brownfield enhancement)

## Prerequisites

- JDK 25
- No local Maven needed — `./mvnw` downloads Maven 3.9.16
- Docker (Docker Desktop 4.x or newer) — used for PostgreSQL and Testcontainers

## Quick start

```
cp .env.example .env                 # then set POSTGRES_PASSWORD
docker compose up -d postgres        # wait for "healthy": docker compose ps
set -a; . ./.env; set +a             # export the same credentials to the app
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
curl -s http://localhost:8080/actuator/health    # {"status":"UP"}
```

`docker compose up` currently starts only the PostgreSQL database; the `app` service is added to Compose in a later task (US-014).

## Running tests

```
./mvnw -q verify
```

This runs unit/slice tests (Surefire, `*Test.java`) against a Testcontainers PostgreSQL instance, then integration tests and Cucumber scenarios (Failsafe, `*IT.java`), merges JaCoCo coverage from both phases, and fails the build if merged line coverage drops below 70%. A Docker daemon must be running: there is no H2 fallback.

To skip the coverage gate deliberately (for example when running with `-Djacoco.skip=true`), add
`-Dcoverage.gate.skip=true`. The gate is skipped automatically with `-DskipTests` or `-Dmaven.test.skip=true`.
Never skip it for a build that is being reviewed or committed.

## API usage *(pending — Tasks 6–9)*

## Configuration *(pending)*

## Documentation

| Document | Contents |
|---|---|
| [docs/requirements.md](docs/requirements.md) | Requirements, assumptions, and decisions |
| [docs/architecture.md](docs/architecture.md) | Architecture, schema, key design decisions |
| [docs/scenarios.md](docs/scenarios.md) | Greenfield, brownfield, and ambiguous-requirement scenarios |
| [docs/ai-usage-log.md](docs/ai-usage-log.md) | Record of AI-assisted work and engineer decisions |
| [docs/engineering-summary.md](docs/engineering-summary.md) | Trade-offs, limitations, production roadmap |
