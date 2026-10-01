# Requirements

Last updated: 2026-09-30 (final, US-015; D1–D131). Decisions below were made by the engineer during planning; see [ai-usage-log.md](ai-usage-log.md).

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
| D75 | Non-ASCII in `Location` | When the redirect builds `Location`, every character outside printable ASCII is percent-encoded as UTF-8 (the RFC 3987 mapping from IRIs to URIs). Stored URLs that are all ASCII are sent byte-identical. This avoids Tomcat dropping the header (and logging the full URL) for characters above U+00FF. |
| D76 | Redirect `Cache-Control` | The 302 carries exactly `Cache-Control: no-store` (D7), set by the application. Spring Security then adds no `Pragma` or `Expires`. Tests assert an exact match. |
| D77 | Malformed codes on the redirect | Checked in the service with `ShortCodeFormat` before any transaction or lookup (D72). No route regex, so the 404 is the identical `SHORT_URL_NOT_FOUND` (D74). |
| D78 | Bare `/api` for an authenticated caller | It reaches the redirect mapping and gets `404 SHORT_URL_NOT_FOUND`, never a 302, because `api` is a built-in reserved word (D48) and can never be a code. |
| D79 | Query strings on short links | A query string on `/{code}` is **not** forwarded to the target, so links cannot become configurable open redirects. |
| D80 | `/{code}/` (trailing slash) | It currently gets 401 with a Basic challenge (D57), and browsers show a login prompt. This is accepted for now and revisited in US-014. |
| D81 | Browser 404 page | The redirect's 404 stays `application/problem+json` for every `Accept`. An HTML 404 page is on the production roadmap. |
| D82 | Direct `GET /error` | It returns 500 today, which can inflate 5xx monitoring. This is recorded and handled in US-014. |
| D83 | No DB CHECK for reserved words | Reserved words are enforced only by `AliasPolicy` (D29, D48), with no database CHECK, so the list stays configurable. |
| D84 | Encoded URL length (extends D11) | Create also rejects an `originalUrl` whose D75-encoded (ASCII wire) form exceeds **2048 bytes**, with `400 INVALID_URL`. This is in addition to D11's 2048-character limit. `UrlValidator` computes it with the same encoder the redirect uses, in one shared helper, so the rule is defined once. The redirect's `Location` therefore never exceeds 2048 bytes. Tomcat's default `max-http-response-header-size` is unchanged. |
| D85 | D84 is enforced in the application only | The D84 encoded-length limit is enforced only at create, in `UrlValidator`, with **no database CHECK**. A row inserted by raw SQL can still hold a URL whose encoded form breaks the redirect (Tomcat returns a bare 500 for that link, and the URL is not logged). This is an accepted gap, like D83. The application is the only writer. |
| D86 | Who loses a concurrent deactivation (AC11) | Either `409 CONCURRENT_MODIFICATION` (the requests overlap and the optimistic lock catches it) or `409 SHORT_URL_ALREADY_DEACTIVATED` (the requests serialise). The race test accepts either and asserts exactly one 200 and `version` N+1. Deterministic tests pin each path: a row-lock test (commit and rollback variants) and a serialised test. |
| D87 | 409s involving DELETE | DELETE can return `409 CONCURRENT_MODIFICATION` (D35). A PATCH that loses to a concurrent DELETE gets 409, not 404; a re-read then gives 404. |
| D88 | PATCH media type | PATCH accepts `application/json` only. `application/merge-patch+json` and other types get `415 UNSUPPORTED_MEDIA_TYPE`. |
| D89 | Strict booleans | Jackson's scalar coercion is **disabled for the Boolean type**. Only real JSON `true`/`false` are accepted; `"false"`, `0` and `1` get `400 MALFORMED_REQUEST`. A missing or `null` `active` gets `400 VALIDATION_FAILED` (`@NotNull`). The setting is scoped to Boolean and does not change create (no boolean inputs) or responses. |
| D90 | Click data in the PATCH response | The PATCH 200 body shows click data as read inside the PATCH transaction. A click that lands during the PATCH appears on the next GET. |
| D91 | Clicks on links that change state mid-redirect | A click is recorded only if the link is still `ACTIVE` when the click UPDATE runs (`WHERE status = 'ACTIVE'`). If the link was deactivated or deleted after it resolved, the 302 is still served, but no counter change and no `click_event` row are written. |
| D92 | `click_event.clicked_at` default | It keeps `DEFAULT now()` for raw SQL inserts only. The application always supplies `clicked_at` from the injected Clock, truncated to microseconds (D45). |
| D93 | Where fail-open lives | Click-recording failures are caught in `RedirectService` (D12), not in a decorator bean, so no replacement `ClickRecorder` can remove fail-open. The failure is logged at WARN with the code, id, exception class and SQLSTATE only: never the exception message, the stack trace or the URL. |
| D94 | `last_accessed_at` never moves backwards | The click UPDATE sets `last_accessed_at = GREATEST(last_accessed_at, :clickedAt)`, so it always equals the latest `click_event.clicked_at` even when clicks commit out of order. PostgreSQL's `GREATEST` ignores NULL, so the first click still sets it. **To be implemented in US-011** (carry-over from US-010 R9). US-010's AC1 is reworded to "the latest click time" at the same point. |
| D95 | Where stats are bucketed by day (US-011) | Java does all time-zone arithmetic: it validates the zone, resolves the defaults from the injected `Clock`, and computes the start instant of each local day with `LocalDate.atStartOfDay(zone)`, so 23-hour, 25-hour and skipped days are handled. One native query counts the link's clicks in `[start(from), start(to + 1))` with a range scan on `ix_click_event_short_url_id_clicked_at` (the bare `clicked_at` column, never wrapped), and assigns each click to its day with `width_bucket` over the Java-computed day starts. **No zone string is ever sent to PostgreSQL** (no `AT TIME ZONE`, no `timezone(...)`). Java fills in the zero-click days (D19) from the bucket index. This **supersedes** the planning-stage architecture recommendation to group with `AT TIME ZONE` in PostgreSQL. Testing on PostgreSQL 18.6 showed that PostgreSQL resolves `CET` as a fixed +01 abbreviation, inverts the sign of `+05:00`, matches zone names case-insensitively, and uses its own tzdata. |
| D96 | Accepted `timezone` values (refines D10) | Exactly `UTC`, or an ID in the JDK's `ZoneRulesProvider.getAvailableZoneIds()`, matched case-sensitively with no trimming. This includes backward links and `Etc/GMT±N`. A missing parameter means `UTC`; an empty value is invalid. Rejected: offsets (`+05:00`, `Z`), prefixed offsets (`UTC+5`, `GMT-3`), `UT`, three-letter short IDs (`PST`), and wrong case. `EST`, `MST` and `HST` are also rejected: IANA defines them as links, but the JDK excludes them from its zone set (confirmed on JDK 25.0.2; pinned by a test). The OpenAPI description warns that `Etc/GMT+5` means UTC−5. |
| D97 | Stats `from` / `to` | Inclusive local dates (`yyyy-MM-dd`) in the caller's zone. A one-day query has `from == to`. Internally the instant range is half-open. Future dates are allowed and return zero counts. |
| D98 | Stats defaults and limits | `to` defaults to today in the caller's zone (`LocalDate.ofInstant(clock.instant(), zone)`) and `from` defaults to `max(to − 29, 1970-01-01)`, each independently. The window is at most **366 days**. Both dates must lie between 1970-01-01 and 9999-12-31. `from` after `to` is invalid. |
| D99 | Stats parameter errors (settles D69 for US-011) | Invalid parameter values (zone, dates, range) get `400 VALIDATION_FAILED` with the D56 `errors` extension (`field`, `message`), sorted, never echoing the submitted value. **No `INVALID_TIMEZONE` code** is added; the D31 catalogue is unchanged. |
| D100 | Unknown or repeated stats query parameters | The stats endpoint rejects unknown and repeated query parameter names with `400 MALFORMED_REQUEST`, by the same reasoning as D59: a misspelled `timeZone` would otherwise silently return UTC buckets. This is stricter than the details endpoint. |
| D101 | Stats response shape | `{shortCode, timezone, from, to, totalClicks, clicksInRange, lastAccessedAt, daily: [{date, clicks}]}`. `timezone`, `from` and `to` echo the resolved values. `totalClicks` is the all-time `click_count`. `lastAccessedAt` is a UTC instant or `null`. `daily` has exactly one entry per local date in `[from, to]`, in order, including zero-click days. The field is `clicks`, not `count`. |
| D102 | Stats consistency | Stats run in a read-only `REPEATABLE READ` `TransactionTemplate` (no `@Transactional`), so the link read and the daily counts share one snapshot. |
| D103 | Stats index-use guard | A repository test runs `EXPLAIN` with `enable_seqscan` off and asserts that the stats query uses `ix_click_event_short_url_id_clicked_at`. |
| D104 | Stats validation order | Parameters are validated (400) before visibility (404), and both happen before any database work. Precedence: 401 > 405 > 406 > 400 > 404. Neither order reveals ownership (D74). |
| D105 | Stats for deactivated links | Stats reuse `loadVisible`, so a DEACTIVATED link's stats are returned to its owner and to ADMIN, as the details endpoint returns the link. Malformed, unknown, deleted and not-yours codes all get the identical `404 SHORT_URL_NOT_FOUND` (D4, D13, D74). |
| D106 | Expiration is optional (E1, US-012) | Expiration is set per link and is optional. There is **no default TTL**: a link without `expiresAt` never expires, so existing links and clients are unaffected. |
| D107 | How expiration is set (E2) | An absolute instant `expiresAt` (ISO-8601 with an explicit offset or `Z`), supplied at create. It must be strictly in the future (injected `Clock`) and at most **10 years** ahead (configurable). Stored at microsecond precision (D45). No relative TTL form. |
| D108 | Scope of expiration (E3) | Applies to generated codes and custom aliases alike. |
| D109 | Redirect of an expired link (E4) | `GET /{code}` on an expired link returns **410 Gone** with a new `errorCode` **`SHORT_URL_EXPIRED`** (extends D31). D74 already accepts that existence is not hidden, so 410 reveals nothing new. |
| D110 | Caching of 410 (E5) | The 410 carries `Cache-Control: no-store`, because 410 is heuristically cacheable and an owner may later extend the link (D115). |
| D111 | Expiry boundary (E6) | A link is expired when `now >= expiresAt`, measured with the injected `Clock`. |
| D112 | Expiry is computed, not stored (E7) | No new status value and no background job. The stored `status` stays `ACTIVE` / `DEACTIVATED` / `DELETED`; "expired" is computed from `expires_at` at request time. |
| D113 | Precedence with deactivated/deleted (E8) | Deleted or deactivated wins (404, D2). Order: format → lookup → deleted/deactivated → expired (410) → redirect (302). |
| D114 | Changing expiry (E9) | Owner or ADMIN (D4) may set, extend, shorten or clear `expiresAt` through the existing `PATCH` (D34). An absent `expiresAt` means "unchanged"; an explicit `null` clears it (never expires). `PATCH` accepts a body with `active`, `expiresAt`, or both, but not neither. |
| D115 | Reviving an expired link (E10) | Allowed: extending or clearing `expiresAt` on an expired link makes it redirect again. |
| D116 | Reuse of expired codes (E11) | Never, consistent with D1; an expired link keeps its code. |
| D117 | Analytics after expiry (E12) | Owner and ADMIN can still read details and stats (200). Requests after expiry get 410 and are **not** counted as clicks. |
| D118 | API representation (E13) | Details, create, PATCH and stats responses add `expiresAt` (`null` = never) and `expired` (boolean, computed at request time). Additive only. |
| D119 | HEAD on an expired link (E14) | 410 with no body; never counted (D18). |
| D120 | No configured default TTL (E15) | Not now; could be added later without breaking changes. |
| D121 | No cleanup of expired links (E16) | Expired links are never hard-deleted (D1); storage growth is a production concern for the roadmap. |
| D122 | PATCH absent vs `null` (X1, US-013) | `UpdateShortUrlRequest` becomes a small request class that records whether `expiresAt` was present (a Jackson setter sets a flag), so absent = unchanged, `null` = clear, value = set. No `jackson-databind-nullable` dependency. |
| D123 | Strict `expiresAt` parsing (X2) | `expiresAt` is an `OffsetDateTime` given as a JSON string with an explicit offset or `Z`. Numbers (epoch values) and offset-less local date-times are rejected with `400 MALFORMED_REQUEST` (as D59/D89). |
| D124 | `expiresAt` validation errors (X3) | A past value, or one beyond the configured horizon, gets `400 VALIDATION_FAILED` with `errors: [{field: "expiresAt", message}]`, never echoing the value (D56, D99). Checked with the injected `Clock` (not `@Future`), after `INVALID_URL` and `INVALID_ALIAS` (extends D63). |
| D125 | Redundant expiry change (X4) | A PATCH setting the `expiresAt` the link already has returns 200 and writes nothing (no `version` / `updated_at` bump). D26's 409s remain for `active` only. |
| D126 | Mixed PATCH with a redundant `active` (X5) | If `active` is redundant, the request fails with the D26 409 and **nothing** is applied, expiry included (all-or-nothing). |
| D127 | Past expiry via PATCH (X6) | Rejected with 400 (same rule as create). To stop a link now, deactivate it. |
| D128 | Redirect when the clock fails (US-016 G3) | The redirect reads the clock once to decide expiry (D111). If the clock fails, the redirect fails (500, logged once by the advice); nothing is recorded and no URL is logged. This replaces US-010's fail-open behaviour for a failing clock; recording failures still fail open (D12, D93). |
| D129 | Hardening scope (US-014 G2) | Tier A (H1–H13 in the US-014 design note) is built in US-014; tier B (idempotency key, stats rollups/timeouts, CORS, rate limiting/abuse screening, async click events, HTML 404, NOT VALID constraints, Boot 4.x) goes to the US-015 production roadmap. |
| D130 | Request-body limit | 16 KiB by default (`app.http.max-body-bytes`), enforced before authentication; larger bodies get `413 PAYLOAD_TOO_LARGE` (a new error code). |
| D131 | Connection-pool timeout | `spring.datasource.hikari.connection-timeout` is 3 s, so a pool-exhausted request fails fast. |

## Environment and platform decisions

| Decision | Detail |
|---|---|
| Java version | **Java 25** (engineer override of the assignment's Java 21). Trade-off: the project will not build on a Java 21 toolchain. |
| Migrations | Flyway |
| Commits | Local commits at the end of each major feature |
