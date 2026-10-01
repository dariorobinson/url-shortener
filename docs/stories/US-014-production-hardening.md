---
id: US-014
title: Production hardening
status: Done
plan_task: 13
depends_on: [US-001, US-005, US-006]
requirements: [FR-11, D37, D68, D80, D82]
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

## Carry-over from US-005 (engineer-approved at US-005 G3)
- **Actuator:** exposing any actuator endpoint beyond `health` requires `hasRole(ADMIN)` for it. Today `/actuator/**` is only `authenticated()`.
- **HSTS behind the load balancer (D37):** configure `server.forward-headers-strategy` with a trusted proxy, so that `request.isSecure()` reflects HTTPS at the load balancer. Test that HSTS is sent when the trusted proxy reports HTTPS, and is not sent for an untrusted source.
- **Health probes:** `HEAD /actuator/health` returns 401 anonymously, because only `GET` is public there. Health checks (Compose, load balancer) must use `GET`.
- **CORS (if ever added):** configure it through `http.cors(...)` so preflight is handled before authorization, or preflight requests will get 401.
- **Env-var names in deployment docs:** document the canonical forms. Both forms bind; see the correction in the US-004 story.

## Carry-over from US-006 (engineer-approved at US-006 G2 and G3)
- **Request-body size limit (D68):** enforce a maximum request-body size, either at the load balancer or with a servlet filter. Today large bodies are parsed before they are rejected. The limit's value and the rejection response are decided in this story's design.

- **Roadmap (D71):** add a real idempotency key for `POST /api/v1/urls`, for example an `Idempotency-Key` header with a stored key-to-result mapping, so that a create whose 201 was lost can be retried safely. Either implement it here or record it in US-015's production roadmap.

## Carry-over from US-008
- **D80:** `/{code}/` with a trailing slash gets 401 with a Basic challenge, and browsers show a login prompt. Revisit this.
- **D82:** a direct `GET /error` returns 500, which can inflate 5xx monitoring. Handle it.

- **Actuator links page (US-008 review):** an authenticated `GET /actuator` (the discovery links page) returns 200 to any USER. Either set `management.endpoints.web.discovery.enabled: false`, or require `hasRole(ADMIN)` on the exact `/actuator` path. With discovery off, an authenticated `/actuator` reaches `/{code}` and gets 404 (the D83 gap).
- **HEAD on health (US-008 QA):** anonymous `HEAD /actuator/health` returns 401, because rule 2 permits GET only. Decide whether to permit HEAD, or keep requiring probes to use GET (already noted above).
## Carry-over from US-010 (engineer-approved at US-010 G2)
- **Fail-open latency under connection-pool exhaustion:** if the Hikari pool is exhausted, a redirect can wait up to the 30-second connection timeout before click recording fails open (D12, D93). Review the pool size and `connection-timeout` during hardening. A shorter timeout for the recorder is one option.
- **Lost-click metric (deferred from US-010, Q4):** add a Micrometer counter for clicks lost to fail-open. Only do this once a metrics endpoint is exposed; today actuator exposes only `health`.

## Carry-over from US-011 (engineer-approved at US-011 G3)
- **Request lines over Tomcat's 8 KiB limit** get a bare `text/html` 400 from Tomcat, not a ProblemDetail. It affects every endpoint and no controller advice can reach it; it echoes nothing. Decide between a Tomcat error page / `ErrorReportValve` configuration and accepting the bare response.
- **Stats cost for very busy links:** the stats query scans a link's clicks within the window (at most 366 days, D98). Consider a statement timeout or daily rollups.

## Design note
*(main session, 2026-09-30. Status: **approved at G2 (2026-09-30)**, D129–D131.)*

US-014's own ACs plus nine carry-overs make 18 items. The main decision is **scope**: tier A is built here, and tier B goes to the US-015 production roadmap.

### Tier A: build in US-014

| # | Item | Design | Source |
|---|---|---|---|
| H1 | Request ID (AC1) | A `RequestIdFilter`, ordered before Spring Security, takes `X-Request-Id` if it matches `^[A-Za-z0-9-]{1,64}$`; otherwise it generates a UUID. That allow-list prevents log injection and header echo. The ID goes in the MDC (`requestId`, added to the log pattern) and in the `X-Request-Id` response header. 500 ProblemDetails include `requestId` (from the MDC) in the advice and in the security handlers. The MDC is always cleared in `finally`. | AC1 |
| H2 | Security headers (AC2) | Spring Security already sends `X-Content-Type-Options: nosniff` and `X-Frame-Options: DENY` by default, so this item adds tests pinning them on a 200, a 302, a 401 and a 404, without changing configuration. | AC2 |
| H3 | Dockerfile (AC3) | Multi-stage. The build stage is `eclipse-temurin:25-jdk`, running `./mvnw -q -DskipTests package`. The runtime stage is `eclipse-temurin:25-jre-alpine` with a non-root `app` user (`USER app`), copying only the jar, with `ENTRYPOINT ["java","-jar","/app/app.jar"]`. `HEALTHCHECK` uses `GET /actuator/health`. A `.dockerignore` excludes `target/`, `.git/` and `.env`. | AC3 |
| H4 | Compose `app` service (AC4) | It builds the Dockerfile and uses `depends_on: postgres: condition: service_healthy`. It takes the datasource from `SPRING_DATASOURCE_*`, built from `.env`; the `APP_*` users come from `.env`. Port `127.0.0.1:8080`. The healthcheck uses GET (carry-over). | AC4 |
| H5 | HSTS behind a trusted proxy (AC6) | `server.forward-headers-strategy=native` (Tomcat `RemoteIpValve`). `server.tomcat.remoteip.internal-proxies` is configurable through `APP_TRUSTED_PROXIES`, defaulting to Tomcat's private-network regex. HSTS is then sent when a trusted proxy says `X-Forwarded-Proto: https`, and not otherwise. | AC6, D37 |
| H6 | Actuator lock-down | Set `management.endpoints.web.discovery.enabled=false`, so `GET /actuator` no longer lists endpoints. Any actuator path other than `GET`/`HEAD /actuator/health` requires ADMIN. | US-005, US-008 |
| H7 | HEAD on health | Allow anonymous `HEAD /actuator/health`, because some load balancers probe with HEAD. | US-005, US-008 |
| H8 | `/{code}/` trailing slash | Allow anonymous `GET`/`HEAD` on a single segment followed by a slash, so it reaches MVC and gets `404 RESOURCE_NOT_FOUND` instead of a 401 with a Basic challenge, which makes browsers show a login prompt. | D80 |
| H9 | Direct `GET /error` | A small `ErrorController` at `/error` returns the original status when an error is being forwarded, and `404 RESOURCE_NOT_FOUND` when `/error` is requested directly. It is never 500, and always a ProblemDetail. | D82 |
| H10 | Request-body limit | A `RequestBodyLimitFilter` (16 KiB, `app.http.max-body-bytes`) checks `Content-Length` and wraps the input stream for chunked bodies. It answers `413` with a new error code, `PAYLOAD_TOO_LARGE`, as a ProblemDetail, before the body is parsed. | D68 |
| H11 | Tomcat's own error page | Turn off Tomcat's report and server info in its error page (`ErrorReportValve`), so the bare 400 for over-long request lines shows no server version. The response stays text/html, as an accepted limitation. | US-011 |
| H12 | Pool fail-fast | Set `spring.datasource.hikari.connection-timeout` to 3 s (down from 30 s), so a pool-exhausted redirect fails fast. A larger pool size is an operations decision. | US-010 |
| H13 | Lost-click metric | A Micrometer counter, `shortener.clicks.lost`, incremented on fail-open. Expose `metrics` to ADMIN only (with H6). | US-010, Q4 |

### Tier B: production roadmap, recorded in US-015

- An idempotency key for create (D71).
- Daily rollups or a statement timeout for stats on very busy links.
- CORS: if it is ever added, through `http.cors(...)`.
- Rate limiting and abuse screening.
- Asynchronous click events (Kafka or SQS).
- An HTML 404 page for browsers (D81).
- `NOT VALID` / `VALIDATE` for future CHECKs on large tables.
- Upgrading to Boot 4.x (D22).

### Risks
- **H1** adds a header and an MDC field to every response; ProblemDetails gain `requestId` only on 500s, so other error shapes are unchanged.
- **H5:** trusting forwarded headers is only safe behind a proxy that overwrites them. The default trusts only private-network addresses.
- **H6–H8** change the filter chain, so the role-rule variant tests (CLAUDE.md) apply.
- **H10** must run before Spring Security, so an unauthenticated huge body is refused cheaply. Its 413 is written by the shared `ProblemDetailResponseWriter`.
- **H3 and H4** can only be partly checked by `./mvnw verify`. `docker compose up` needs a manual run, which I can do here.

## Implementation notes
*(main session, 2026-09-30; no agents)*

**Production (new):** `web/RequestIdFilter`, `web/RequestBodyLimitFilter`, `web/HttpProperties`, `web/WebFilterConfig`; `api/error/ProblemErrorController`, `api/error/PayloadTooLargeException`; `config/TomcatConfig`; `Dockerfile`, `.dockerignore`.
**Production (modified):** `security/SecurityConfig` (H6–H8 rules), `security/ProblemDetailResponseWriter` (now public, shared with the body-limit filter), `api/error/ProblemDetails` (`requestId` on 500 only), `api/error/ErrorCode` (`PAYLOAD_TOO_LARGE`), `api/error/GlobalExceptionHandler` (413 for chunked overruns; `codeFor`/`detailFor` shared with the error controller), `service/RedirectService` (lost-click counter), `application.yml` (forward headers, Hikari 3 s, metrics exposure, log pattern), `docker-compose.yml` (`app` service).

**Package dependencies:** `web` → `api.error`, `security`; nothing depends on `web`. The MDC key is `ProblemDetails.REQUEST_ID` and the overrun exception lives in `api.error`, precisely so `api.error` never imports `web`.

**Deviation from the design note (accepted by the engineer at G3):** H6 keeps actuator discovery **enabled** (ADMIN-only) instead of disabling it. With discovery off, an ADMIN's `GET /actuator` fell through to the public `/{code}` mapping, so a raw-SQL row with code `actuator` would have redirected an ADMIN. Restricting the links page to ADMIN was the other option the carry-over allowed.

**New tests:** `RequestIdFilterTest` (12), `RequestBodyLimitFilterTest` (4), `ProblemErrorControllerTest` (3), `ProblemDetailsRequestIdTest` (20), `RedirectServiceMetricsTest` (2), `HardeningIT` (15), `UntrustedProxyHstsIT` (1, its own context: the only class that may not reuse `IntegrationTestBase`, because it needs a different Tomcat proxy configuration), `hardening.feature` (3 scenarios, 4 with examples).

**AC → tests:** AC1 `RequestIdFilterTest`, `ProblemDetailsRequestIdTest`, `HardeningIT` request-ID tests (header, echo, log lines, 500 body), Cucumber; AC2 `HardeningIT.shouldSendNosniffAndFrameDenyOnRedirectsSuccessesAndErrors`, Cucumber outline; AC3/AC4 manual `docker compose up` (below); AC6 `HardeningIT.shouldSendHstsOnlyWhenTheTrustedProxyReportsHttps` and `UntrustedProxyHstsIT`; H6–H13 `HardeningIT`, `RedirectIT` updates, unit tests.

**Done-story test edits:** listed by method in US-006, US-007, US-008 and US-016.

**Final build (main session, clean):** `./mvnw -o clean verify` — exit 0; Surefire 1181/0, Failsafe 924/0 (CucumberIT 259); merged LINE coverage 854/867 (98.50%).

**Manual Docker verification (main session):** `docker compose -p urlshortener-verify --env-file <copy of .env.example> up -d --build --wait` → both services healthy in about 3 minutes (cold build). In the running stack:
- the container runs as user `app`, and the image is 289 MB;
- `GET /actuator/health` returns 200 with `X-Request-Id`, `nosniff` and `DENY`, and anonymous `HEAD` health returns 200;
- create with `expiresAt` returns 201; the redirect returns 302; a PATCH clearing the expiry returns 200;
- `/actuator/metrics/shortener.clicks.lost` returns 403 to a USER and 200 to an ADMIN;
- `/Dock001/` returns 404, `/error` returns 404, and a 20 KB body returns 413;
- log lines carry `requestId=<uuid>`.

The stack, its volume and its image were removed afterwards. The engineer's own `.env` and `pgdata` volume were not touched.

## QA notes
*(qa-tester: feature files and IT classes, AC-to-test mapping, test results, defects D-<story>-<n>)*

## Review log
*(senior-engineer findings per round, and how each was resolved)*

| Round | ID | Severity | Finding | Resolution |
|---|---|---|---|---|
