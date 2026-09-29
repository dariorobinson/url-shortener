---
id: US-014
title: Production hardening
status: Open
plan_task: 13
depends_on: [US-001, US-005, US-006]
requirements: [FR-11, D37]
requires_design_approval: true
---

# US-014: Production hardening

## User story
As an engineer, I want request tracing, security headers, a hardened container image, and code-coverage reporting, so that the application is closer to production-ready rather than a bare functional prototype.

## Acceptance criteria
- **AC1:** Given any request, when it is processed, then a request ID (taken from an incoming correlation header if present, otherwise generated) is present in the response headers, is added to the logging MDC for the duration of the request, and — for an unhandled `500` error — appears in the `ProblemDetail` body (per `docs/architecture.md`: "unexpected errors return a generic 500 with a request ID").
- **AC2:** Given any HTTP response, when inspected, then it includes `X-Content-Type-Options: nosniff` and `X-Frame-Options: DENY`.
- **AC3:** Given the production Dockerfile, when built, then it uses a multi-stage build (a build stage with the JDK/Maven wrapper, and a slim runtime stage) and the resulting container runs the application as a non-root user.
- **AC4:** Given `docker-compose.yml`, when `docker compose up` is run, then an `app` service starts, declares `depends_on` the `postgres` service with a health check, and the app becomes reachable and healthy at `/actuator/health` once Postgres is ready.
- **AC5:** moved to US-001 (D40).
- **AC6:** Given Spring Security's default HSTS support (D37), when a request arrives over HTTPS (or is forwarded as HTTPS by the trusted proxy, via `X-Forwarded-Proto`, trusted only from the configured proxy address — documented, since TLS terminates at the load balancer, not this application), then the response includes `Strict-Transport-Security: max-age=31536000; includeSubDomains` (one year, `includeSubDomains`, no `preload`); given a request arrives over plain HTTP with no trusted forwarded-HTTPS indication, then no `Strict-Transport-Security` header is present.
- **AC7:** moved to US-001 (D40).

## Tests required
| Type | Behaviour proven | Owner |
|---|---|---|
| Web slice | Request ID appears in response headers and MDC-backed log output for a sample request (AC1) | mid-engineer |
| Web slice | Security headers (`X-Content-Type-Options`, `X-Frame-Options`) present on a sample response (AC2) | mid-engineer |
| Web slice | HSTS header present with the exact D37 directives when the request is trusted-forwarded as HTTPS; absent over plain HTTP (AC6) | mid-engineer |
| Cucumber | A sample authenticated request's response includes the request-ID header (AC1) | qa-tester |
| Cucumber | A sample response includes `X-Content-Type-Options: nosniff` and `X-Frame-Options: DENY` (AC2) | qa-tester |
| Integration (`*IT`) | `500` error `ProblemDetail` body includes the request ID (AC1) | qa-tester |
| Integration (`*IT`) | HSTS header appears only when the request is forwarded as HTTPS from the trusted proxy address, never otherwise (AC6) | qa-tester |
| Manual/build | Docker image builds, runs as non-root, and `docker compose up` brings the app to a healthy state against Postgres (AC3, AC4) — verified by the engineer running the build/compose commands, since this is infrastructure outside `./mvnw verify`'s scope | mid-engineer (build config); engineer (manual verification) |

## Out of scope
- Any change to authentication/authorization rules already established in US-005.
- JaCoCo report and coverage gate — moved to US-001 (D40).

## Risks
- Adding security headers or a request-ID filter after every other story is done means every prior story's tests must still pass unmodified; if any test asserted on exact header sets, this story could cause unrelated-looking failures elsewhere. Running the full suite after this story, not just its own new tests, is required.

## Open questions
- None. D37 fixes HSTS's exact directives and scope (HTTPS-only, trusted-proxy forwarded headers).

## Design note
*(architect)*

## Implementation notes
*(mid-engineer: files changed, decisions, items needing human review, test command and result)*

## QA notes
*(qa-tester: feature files and IT classes, AC-to-test mapping, test results, defects D-<story>-<n>)*

## Review log
*(senior-engineer findings per round, and how each was resolved)*

| Round | ID | Severity | Finding | Resolution |
|---|---|---|---|---|
