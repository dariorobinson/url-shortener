# Requirements

Last updated: 2026-09-29 (G2 US-007, D72–D74). Decisions below were made by the engineer during planning; see [ai-usage-log.md](ai-usage-log.md).

## Functional requirements

| ID | Requirement |
|---|---|
| FR-1 | Create a short URL from a long URL |
| FR-2 | Redirect a short code to its original URL |
| FR-3 | Support optional custom aliases |
| FR-4 | Support optional expiration — **deferred to the brownfield scenario** (see [scenarios.md](scenarios.md)) |
| FR-5 | Track click analytics: total clicks, click timestamps, last accessed time, clicks per day |
| FR-6 | Deactivate/reactivate and delete short URLs |
| FR-7 | Reject invalid URLs |
| FR-8 | Unknown short codes return 404 |
| FR-9 | Expired short codes are handled explicitly (response defined in Scenario 3) |
| FR-10 | Short-code collisions are handled safely, including under concurrency |
| FR-11 | Production-quality error responses |
| FR-12 | View a short URL's details and statistics |
| FR-13 | Role-based access control with `USER` and `ADMIN` roles |

## Non-functional requirements

- **Integrity:** database constraints (UNIQUE, NOT NULL, CHECK, FK) are the final correctness guarantee.
- **Concurrency:** no check-then-act races; counters updated atomically in SQL.
- **Security:** only `http`/`https` targets; no stack traces or internals in responses; full URLs never logged; authenticated management API.
- **Performance:** redirect is a unique-index lookup plus one analytics write.
- **Observability:** structured logs with a request ID; actuator health endpoint.
- **Maintainability/testability:** layered design, constructor injection, injectable `Clock` and short-code generator.
- **Portability:** app and database run via Docker Compose.
- **Testing:** integration tests run against real PostgreSQL via Testcontainers.

## Decisions on ambiguous requirements

| # | Question | Decision |
|---|---|---|
| D1 | Hard or soft delete? | **Soft delete.** Row retained for audit (`status = DELETED`, `deleted_at`, `deleted_by`). Deleted codes are never reused. |
| D2 | Deactivated link response? | **404**, same as unknown and deleted codes, so the redirect never reveals link state. Deactivation is reversible. |
| D3 | Authentication/authorization? | **Spring Security with `USER` and `ADMIN` roles.** Only `ADMIN` can delete. `ADMIN` inherits all `USER` permissions. |
| D4 | Ownership | A `USER` can view, see stats for, and deactivate/reactivate **only links they created**; others' links return 404. `ADMIN` can act on any link. |
| D5 | Same long URL submitted twice? | **Always create a new code.** No deduplication. |
| D6 | Code/alias character set | **`[A-Za-z0-9]` (Base62), case-sensitive.** No `_` or `-`. Aliases 3–32 characters; reserved words blocked. |
| D7 | Redirect status | **302 Found** with `Cache-Control: no-store`. |
| D8 | Analytics data captured | **Click timestamp only.** No IP, user agent, or referrer. |
| D9 | What counts as a click? | **GET only.** HEAD and other methods are not counted. |
| D10 | Time zone for daily stats | **Caller's time zone** via an IANA zone ID query parameter (e.g. `America/New_York`), default `UTC`. |
| D11 | URL validation rules | Absolute `http`/`https` with a host; max 2048 chars; no embedded credentials; no links to this service's own host. |
| D12 | Analytics failure during redirect | **Fail open:** the redirect succeeds, the failure is logged, the click is lost. |
| D13 | Deleted links in the management API | **404 for everyone**, including `ADMIN`. Audit history is retained in the database. |
| D14 | Expiration | **Deferred** to Scenario 3 (clarification) and Scenario 2 (brownfield implementation). |
| D15 | Deactivating an already-deactivated link | **Returns an error status stating the link is already deactivated** (exact status/`errorCode` confirmed at US-009 design). Refined by D26. |
| D16 | Clicks vs. concurrent edits | **Recording a click never bumps `version` and management updates never overwrite `click_count` or `last_accessed_at`.** Refined by D27. |
| D17 | Custom alias request field | **`alias`** |
| D18 | `HEAD /{code}` | **302** (same as GET), **not counted** as a click (D9). Public access: D32. |
| D19 | Days with zero clicks in stats | **Included** in the daily series. |
| D20 | Test coverage gate | **JaCoCo ≥ 70%**, enforced by the build. Refined by D38. |
| D21 | Integration test database | **Testcontainers PostgreSQL** for all database tests, including Cucumber. H2 considered and rejected (see AI usage log). |
| D22 | Platform and library versions | **Spring Boot 3.5.16**, Java 25. Boot-managed: Lombok 1.18.46, Hibernate 6.6.53, Spring Security 6.5.11, Flyway 11.7.2, PostgreSQL driver 42.7.11, Testcontainers 1.21.4. Explicit: springdoc-openapi 2.8.17, Cucumber 7.34.9 (BOM), JaCoCo 0.8.15, Maven 3.9.16 via the Maven Wrapper. **Known risk:** Boot 3.5 open-source support ended 2026-06-30 and 3.5.16 is the final release; upgrading to Boot 4.x is on the production roadmap. |
| D23 | PostgreSQL image | **`postgres:18.6-alpine`** for Docker Compose. PostgreSQL 18 changed its data directory layout, so the Compose volume mounts `/var/lib/postgresql` (not `/var/lib/postgresql/data`). Testcontainers uses the same image (D41). |
| D24 | Spring profiles | **`local`** (Docker Compose) and **`test`**. No `prod` profile: production configuration comes from environment variables only. |
| D25 | Test build layout | Cucumber is part of the US-001 build: `cucumber-java`, `cucumber-spring`, `cucumber-junit-platform-engine`, `junit-platform-suite`. **Surefire** runs `*Test`; **Failsafe** runs `*IT` and Cucumber. |
| D26 | Redundant state transitions | Deactivating an already-deactivated link → **409 `SHORT_URL_ALREADY_DEACTIVATED`**; reactivating an already-active link → **409 `SHORT_URL_ALREADY_ACTIVE`**. |
| D27 | Analytics column mapping | `click_count` and `last_accessed_at` are **read-only in the entity** (`insertable = false, updatable = false`). Clicks are recorded by a direct JPQL/SQL `UPDATE` that does **not** touch `version`. Repository tests must prove both. |
| D28 | "Own host" (D11) | The host of the configured **`APP_BASE_URL`**, compared case-insensitively and ignoring a trailing dot. |
| D29 | Reserved words | Configurable; default `api, actuator, v3, error, health, admin, login, logout, static, assets, docs`. Matching is **case-insensitive**. Generated codes are also checked; a match counts as a collision and triggers a retry. |
| D30 | Security error responses | Custom `AuthenticationEntryPoint` → **401 `AUTHENTICATION_REQUIRED`** plus `WWW-Authenticate: Basic`; custom `AccessDeniedHandler` → **403 `ACCESS_DENIED`**. Both use the same `ProblemDetail` body as all other errors (`type` `about:blank`, generic `detail`). |
| D31 | `ErrorCode` catalogue | `VALIDATION_FAILED`, `MALFORMED_REQUEST`, `INVALID_URL`, `INVALID_ALIAS`, `ALIAS_ALREADY_EXISTS`, `SHORT_URL_NOT_FOUND`, `SHORT_URL_ALREADY_DEACTIVATED`, `SHORT_URL_ALREADY_ACTIVE`, `CONCURRENT_MODIFICATION`, `SHORT_CODE_UNAVAILABLE`, `AUTHENTICATION_REQUIRED`, `ACCESS_DENIED`, `INTERNAL_ERROR`. Defined in full in US-005. |
| D32 | `HEAD /{code}` access | **Public.** Spring routes HEAD to GET handlers, so "not counted" (D18) must be implemented and tested explicitly. |
| D33 | Create response links | `Location: /api/v1/urls/{code}` (the management resource). The public link is returned in the body as **`shortUrl`**, built from `APP_BASE_URL` and **never from the request `Host` header** (prevents host-header injection). |
| D34 | PATCH contract | `PATCH /api/v1/urls/{code}` with body **`{"active": boolean}`**, designed so `expiresAt` can be added later. |
| D35 | Concurrent modification | Server-side optimistic locking with `@Version`; a conflict → **409 `CONCURRENT_MODIFICATION`**. No ETag/`If-Match` for now; reconsider when `expiresAt` becomes editable. Two concurrent deactivations must produce exactly one 200 and one 409 (tested by QA). |
| D36 | Lifecycle on deleted/deactivated links | PATCH or DELETE on a **deleted** link → 404. Deleting a **deactivated** link is allowed. |
| D37 | HSTS and TLS | Spring Security default HSTS (sent over HTTPS only, `max-age` one year, `includeSubDomains`, no `preload`). Documented: TLS terminates at the load balancer; forwarded headers are trusted only from that proxy. |
| D38 | Coverage gate detail | JaCoCo **LINE** coverage ≥ 70% on **merged** unit and integration results; the build fails below that. Only the main `Application` class is excluded. Lombok-generated code is excluded via `@Generated` (D42). |
| D39 | JUnit version | Override `junit-jupiter.version` to **5.14.4** (Boot 3.5.16 manages 5.12.2, which is incompatible with Cucumber 7.34.9's need for JUnit Platform ≥ 1.13). 5.14.4 is the version Spring Framework 6.2.19 is built against. |
| D40 | Coverage gate placement | The JaCoCo plugin and the ≥ 70% LINE gate (D38) are part of **US-001**, so every story is held to it; removed from US-014. |
| D41 | Testcontainers image | **`postgres:18.6-alpine`**, the same as Compose. |
| D42 | Lombok-generated code in coverage | `lombok.config` with `lombok.addLombokGeneratedAnnotation = true`, so JaCoCo ignores Lombok-generated code. Engineer-approved refinement of D38. |
| D43 | Mockito agent | Mockito is loaded as an explicit `-javaagent` in the Surefire and Failsafe JVMs (no dynamic self-attach). |
| D44 | Deleted-row consistency CHECK | `ck_short_url_deleted_consistency` = `(status = 'DELETED' AND deleted_at IS NOT NULL AND deleted_by IS NOT NULL) OR (status <> 'DELETED' AND deleted_at IS NULL AND deleted_by IS NULL)`, so a non-deleted row can never carry deletion audit fields. |
| D45 | Timestamp ownership | The application sets `created_at`/`updated_at` from the injected `Clock`, truncated to microseconds. The database `now()` defaults remain for raw SQL inserts only. |
| D46 | State changes on deleted links | Any state change on a `DELETED` link, including a second soft delete, throws `ShortUrlDeletedException` and leaves the row unchanged; the API maps it to `404 SHORT_URL_NOT_FOUND` (D13, D36). |
| D47 | Text column types in V1 | `short_code` and `original_url` are **`TEXT NOT NULL`**, not `VARCHAR(n)`. `ck_short_url_code_format` is the only length and format limit on `short_code`. A new `ck_short_url_original_url_length CHECK (char_length(original_url) <= 2048)` limits `original_url`. Reason: database constraints are the final guarantee, and `VARCHAR(n)` silently truncates over-length input when the excess is trailing spaces. Application-layer validation (US-004) must still reject such input without trimming. |
| D48 | Reserved-word configuration | The D29 default words (`api, actuator, v3, error, health, admin, login, logout, static, assets, docs`) are **always reserved** as a built-in set. The property `shortener.alias.additional-reserved-words` **adds** words to it and can never remove a built-in. Matching stays case-insensitive (D29). |
| D49 | Target host scope | IP-literal, `localhost` and private or internal hosts are **accepted** for now: the service only redirects and never fetches targets, so there is no SSRF surface. Revisit if the service ever fetches targets (previews, reachability checks). Non-ASCII (IDN) hosts are **rejected**; clients must submit punycode, and the create API documentation (US-006) must say so. |
| D50 | User configuration | Users are a list at `app.security.users[n].{username, password-hash, role}` (role `USER` or `ADMIN`), validated at startup (fail-fast). There are no defaults in `application.yml` (D24): the `local` profile gets them via `.env`, and the `test` profile via `application-test.yml`. |
| D51 | Username bounds | Usernames are 1–100 lowercase ASCII characters with no whitespace, validated at startup, and unique. `ShortUrl` guards the actor values it stores using the shared constant `ShortUrl.MAX_ACTOR_LENGTH`. **No migration**: `created_by` and `deleted_by` stay `VARCHAR(100)`. |
| D52 | Authentication logging | 401 and 403 responses log **no usernames**, attempted or authenticated, and **no client IPs**. |
| D53 | Password hashing | BCrypt with cost **fixed at 10**. Every configured hash must be a cost-10 BCrypt hash, never plaintext, so unknown-user timing matches known-user timing. |
| D54 | Username matching at login | Case-insensitive (Spring's in-memory user store behaviour). The stored actor is always the configured, lowercase username. |
| D55 | Invalid credentials on public paths | Invalid HTTP Basic credentials get `401 AUTHENTICATION_REQUIRED` on public paths too (Spring default). |
| D56 | Error body extensions | Every error body shares the base `ProblemDetail` keys (`type`, `title`, `status`, `detail`, `instance`, `errorCode`). Security errors (401/403) carry `errorCode` as their **only** extension. Other error types, such as validation errors (US-006), may add documented extensions on top of the base shape. |
| D57 | Default access rule | The final filter-chain rule is **`anyRequest().denyAll()`**. Anything no explicit rule matches is refused: anonymous callers get `401 AUTHENTICATION_REQUIRED` through the entry point, and authenticated callers get `403 ACCESS_DENIED`. This closes case- and path-variant bypasses of role rules (for example `DELETE /API/v1/urls/{code}`). The ADMIN delete rule is `DELETE /api/v1/urls/**`. |
| D58 | Short URL resource shape (create and detail) | `shortCode`, `shortUrl`, `originalUrl`, `status`, `customAlias`, `clickCount`, `createdAt` (ISO-8601 UTC), `lastAccessedAt` (always present; `null` until the first click). `createdBy`, `id`, `updatedAt` and `version` are **not** returned. Shared by US-006 and US-007. |
| D59 | Strict request parsing | Unknown JSON fields and duplicate keys are rejected with `400 MALFORMED_REQUEST` (global Jackson setting). |
| D60 | Empty alias | `alias: ""` or whitespace → `400 INVALID_ALIAS`, never trimmed. A missing alias or JSON `null` means absent, so a code is generated. |
| D61 | Additional error codes (extends D31) | `RESOURCE_NOT_FOUND` (404), `METHOD_NOT_ALLOWED` (405), `NOT_ACCEPTABLE` (406), `UNSUPPORTED_MEDIA_TYPE` (415). |
| D62 | Test base URL | The test profile's `app.base-url` is `https://short.example`. |
| D63 | Validation precedence | When both fields are invalid, `INVALID_URL` is reported first. Both `INVALID_URL` and `INVALID_ALIAS` name their field in the `errors` extension. |
| D64 | No row data in logs | The PostgreSQL driver sets `logServerErrorDetail=false`, and Hibernate's `SqlExceptionHelper` error logging is off, so a CHECK violation's "Failing row contains …" (full URL, username) never reaches the logs. A test proves it, with a positive log-capture assertion. |
| D65 | PostgreSQL driver scope | The PostgreSQL JDBC driver is a **compile**-scope dependency, so the production `PostgresServerErrors` can read `ServerErrorMessage` (the SQLSTATE and constraint name) rather than parsing message text. |
| D66 | Test-only Host forging | `-Djdk.httpclient.allowRestrictedHeaders=host` is set in the **Failsafe** `argLine` only, and never in runtime JVM options. |
| D67 | US-007 resource shape | US-007 AC1 lists `shortUrl` and the full D58 field set. |
| D68 | Request body size | The body-size limit is deferred to US-014 (load balancer or filter). |
| D69 | Other 400 mappings | Method-validation and query-parameter type errors fall back to `400 MALFORMED_REQUEST`. US-011, which has the first query parameters, decides whether they become `VALIDATION_FAILED`. |
| D70 | Content negotiation on the management API | `ShortUrlController` declares class-level `produces = application/json` (never `application/problem+json`). An unacceptable `Accept` is rejected with 406 at mapping lookup, **before** the body is read or the service runs, so nothing is created. Precedence is 401 > 405 > 415 > 406 > 400. An **unparseable** `Accept` header gets a 406 with an **empty body**, a known deviation from D61's "406 carries a problem+json body". DELETE with an unacceptable `Accept` also gets 406, inherited from the class. The redirect controller (US-008) **never** declares `produces`. |
| D71 | Lost 201 before a retry | Accepted and documented. If a create commits but the client never sees the 201 (the response write fails or the client disconnects), a same-alias retry gets `409 ALIAS_ALREADY_EXISTS`. After an unexpected 409, the client can call `GET /api/v1/urls/{alias}`, which returns 200 only if the alias is its own (D4), confirming the earlier create succeeded. This is stated in the OpenAPI 409 description and in US-007. A generated-code retry creates a second link (D5). A real idempotency key is on the production roadmap (US-014/US-015). |
| D72 | Malformed codes | A `{code}` that fails the D6 format (length 3–32, `[A-Za-z0-9]`) returns the **same `404 SHORT_URL_NOT_FOUND`** as an unknown code. It is checked in the service before any DB call, and applies to the management API (US-007, US-009) and the redirect (US-008). |
| D73 | Cache-Control on management reads | Spring Security's default `Cache-Control` (which includes `no-store`) is kept, and the application does not set it. Tests pin the exact value. |
| D74 | What a 404 reveals | In the management API, a 404 hides **ownership and details, not existence**. Existence is already revealed by create's 409 (D1) and by the public redirect. The 404 body is identical for malformed, unknown, deleted and not-yours codes. |

## Environment and platform decisions

| Decision | Detail |
|---|---|
| Java version | **Java 25** (engineer override of the assignment's Java 21). Trade-off: the project will not build on a Java 21 toolchain. |
| Migrations | Flyway |
| Commits | Local commits at the end of each major feature |
