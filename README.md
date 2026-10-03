# URL Shortener

A production-quality prototype of a URL shortener, built with Java 25, Spring Boot 3.5 and PostgreSQL 18 as a Charles Schwab software-engineering assignment. The emphasis is on disciplined, AI-assisted engineering: every design decision was approved by the engineer and is recorded in [docs/](docs/).

## Features

- **Create** short URLs from long URLs, with an optional custom alias and an optional expiry.
- **Redirect** `GET /{code}` with `302` and `Cache-Control: no-store`. Unknown, deactivated and deleted codes are an identical `404`; expired links are `410 Gone`.
- **Collision-safe codes:** random Base62 codes from `SecureRandom`, a database `UNIQUE` constraint as the final guarantee, and a bounded retry in a fresh transaction per attempt.
- **Lifecycle:** the owner (or an ADMIN) can deactivate, reactivate, and set, extend or clear the expiry. Only an ADMIN can (soft) delete. Codes are never reused.
- **Analytics:** total clicks, last access, and clicks per day in the caller's time zone, including zero-click days. GET is counted, HEAD is not. If recording fails, the redirect still succeeds.
- **Security:** HTTP Basic with `USER` / `ADMIN` roles; deny-by-default access rules; strict input parsing; no stack traces, internals or submitted values in responses or logs.
- **Errors:** RFC 7807 `application/problem+json` with a stable `errorCode`.
- **Operations:** a request ID on every response and log line; a multi-stage Docker image running as a non-root user; health checks; an ADMIN-only metrics endpoint.

## Prerequisites

- JDK 25 (`JAVA_HOME` must point to it)
- Docker (Docker Desktop 4.x or newer), for the stack and for Testcontainers
- No local Maven needed: `./mvnw` uses Maven 3.9.16

## Quick start (Docker Compose)

```sh
cp .env.example .env                 # set POSTGRES_PASSWORD; the users in it are local-only
docker compose up -d --build         # PostgreSQL + the app; waits for PostgreSQL to be healthy
curl -s http://localhost:8080/actuator/health        # {"status":"UP"}
```

The first build takes a few minutes (it downloads the Maven dependencies inside the build stage). The app listens on `127.0.0.1:8080`.

`.env.example` ships two **public, local-development-only** users: `admin` / `local-admin-password` (ADMIN) and `user` / `local-user-password` (USER). Never use them anywhere shared.

To run the app from source instead: `docker compose up -d postgres`, export the variables from `.env` (`set -a; . ./.env; set +a`), then `./mvnw spring-boot:run -Dspring-boot.run.profiles=local`.

## Running the tests

```sh
./mvnw verify
```

This runs about 1,200 unit, web-slice and repository tests (Surefire, `*Test`), then about 880 integration tests (Failsafe, `*IT`), including 250 Cucumber scenarios. Every test that touches a database uses real PostgreSQL through Testcontainers; there is no H2. The build fails if merged line coverage drops below 70% (it is currently about 98%). Docker must be running.

## Using the API

```sh
B=http://localhost:8080
U=user:local-user-password
A=admin:local-admin-password

# Create (a random code, or a custom alias, optionally expiring)
curl -s -u $U -H 'Content-Type: application/json' -d '{"originalUrl":"https://example.com/page"}' $B/api/v1/urls
curl -s -u $U -H 'Content-Type: application/json' \
     -d '{"originalUrl":"https://example.com/sale","alias":"sale2027","expiresAt":"2027-01-31T23:59:59Z"}' $B/api/v1/urls

# Follow the short link (public)
curl -i $B/sale2027                                  # 302, Location: https://example.com/sale

# Details and stats (owner or ADMIN)
curl -s -u $U $B/api/v1/urls/sale2027
curl -s -u $U "$B/api/v1/urls/sale2027/stats?timezone=America/New_York&from=2026-09-01&to=2026-09-30"

# Lifecycle (owner or ADMIN): deactivate, extend or clear the expiry; delete (ADMIN only)
curl -s -u $U -X PATCH -H 'Content-Type: application/json' -d '{"active":false}' $B/api/v1/urls/sale2027
curl -s -u $U -X PATCH -H 'Content-Type: application/json' -d '{"expiresAt":null}' $B/api/v1/urls/sale2027
curl -s -u $A -X DELETE $B/api/v1/urls/sale2027      # 204; the code is then 404 everywhere, forever
```

| Method | Path | Access | Success |
|---|---|---|---|
| GET, HEAD | `/{code}` | public | 302 · 404 · 410 when expired |
| POST | `/api/v1/urls` | USER, ADMIN | 201 + `Location: /api/v1/urls/{code}` |
| GET | `/api/v1/urls/{code}` | owner, ADMIN | 200 |
| PATCH | `/api/v1/urls/{code}` | owner, ADMIN | 200 (`active` and/or `expiresAt`) |
| DELETE | `/api/v1/urls/{code}` | ADMIN | 204 |
| GET | `/api/v1/urls/{code}/stats` | owner, ADMIN | 200 |

There is no generated API documentation (D135); the contract is the table above, `docs/architecture.md` (REST API) and the story acceptance criteria.

An error is always a problem document, for example:

```json
{ "type": "about:blank", "title": "Conflict", "status": 409, "detail": "The alias is already in use.",
  "instance": "/api/v1/urls", "errorCode": "ALIAS_ALREADY_EXISTS" }
```

Validation errors add an `errors` array naming the field (never echoing the value), and 500s add the `requestId` that also appears in the `X-Request-Id` header and in the server logs.

## Configuration

Configuration comes from environment variables (there is deliberately no `prod` profile). The names below are the forms used in `.env.example`; Spring Boot's relaxed binding also accepts the dash-less forms (for example `APP_BASEURL`).

| Variable | Meaning | Default |
|---|---|---|
| `SPRING_DATASOURCE_URL`, `_USERNAME`, `_PASSWORD` | PostgreSQL connection | required |
| `APP_BASE_URL` | Public base URL used to build short links (never taken from the `Host` header) | required |
| `APP_SECURITY_USERS_<n>_USERNAME`, `_PASSWORD_HASH`, `_ROLE` | Users: lowercase name, BCrypt cost-10 hash (generate with `htpasswd -bnBC 10 "" 'pw' \| tr -d ':\n'`), `USER` or `ADMIN` | required |
| `SHORTENER_CODE_LENGTH`, `SHORTENER_CODE_MAX_ATTEMPTS` | Generated code length (3–32) and collision retries | 7, 5 |
| `SHORTENER_ALIAS_ADDITIONAL_RESERVED_WORDS` | Extra reserved aliases (the built-in list cannot be removed) | none |
| `SHORTENER_EXPIRATION_MAX_HORIZON` | Furthest allowed expiry, as an ISO-8601 period | `P10Y` |
| `APP_HTTP_MAX_BODY_BYTES` | Request-body limit (413 above it) | 16384 |
| `SERVER_TOMCAT_REMOTEIP_INTERNALPROXIES` | Regex of proxies trusted for `X-Forwarded-*` (set it to your load balancer) | private and loopback ranges |

## How this project was built

The work followed an approval-gated process: requirements and assumptions first, then for each story a design approval, implementation, review, an independent build, a post-task report, and a commit, each approved by the engineer.

- **US-001–US-011** (the greenfield service) were delivered by a team of Claude Code agents: an orchestrator, planner, architect, mid-level engineer, QA tester and read-only senior reviewer.
- **US-012–US-016** were done directly by the main Claude Code session, at the engineer's direction.
- The three required scenarios are recorded in [docs/scenarios.md](docs/scenarios.md): greenfield, the ambiguous requirement "URLs should expire after some time" (clarified before any code), and expiration added to the shipped code as a brownfield change (after an impact analysis).

| Document | Contents |
|---|---|
| [docs/requirements.md](docs/requirements.md) | Requirements and every engineer decision (D1–D131) |
| [docs/architecture.md](docs/architecture.md) | Architecture, schema, API, and the reasoning behind key decisions |
| [docs/scenarios.md](docs/scenarios.md) | The greenfield, ambiguous-requirement and brownfield scenarios |
| [docs/ai-usage-log.md](docs/ai-usage-log.md) | Every significant AI recommendation, and whether it was accepted, modified or rejected |
| [docs/engineering-summary.md](docs/engineering-summary.md) | What was built, trade-offs, known limitations, production roadmap |
| [docs/stories/](docs/stories/) | One file per user story, with its design note, implementation notes, QA notes and review log |
