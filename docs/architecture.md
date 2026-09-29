# Architecture

Last updated: 2026-09-29 (US-005 implemented). Each section is marked *planned*, *designed (US-nnn)*, or *implemented (US-nnn)* as work progresses.

## Overview

A layered Spring Boot monolith.

```
HTTP ─▶ Controller (DTOs, Bean Validation, ProblemDetail errors)
          │
          ▼
        Service (business rules, transactions, collision retry, lifecycle, ownership)
          │         ├─▶ ShortCodeGenerator (interface; SecureRandom Base62 impl)
          │         ├─▶ UrlValidator / AliasPolicy
          │         └─▶ ClickRecorder (interface; synchronous JPA impl)
          ▼
        Repository (Spring Data JPA)
          │
          ▼
        PostgreSQL (Flyway migrations; constraints are the final safeguard)
```

Spring Security (HTTP Basic, stateless) sits in front of the controllers.

## Development process

The SDLC is run by a team of Claude Code agents defined in `.claude/agents/`. Every approval gate stops for the human engineer.

```
engineer ⇄ main session ⇄ orchestrator ─┬─▶ planner          (docs/stories/)
                                        ├─▶ architect        (design notes, this document)
                                        ├─▶ mid-engineer     (code + JUnit tests, fixes)
                                        ├─▶ qa-tester        (Cucumber + *IT integration tests, defects)
                                        └─▶ senior-engineer  (read-only review of code and tests)

Gates: G1 backlog · G2 design · G3 story completion · G4 commit · escalation on any ambiguity
```

Per story: design note → implementation → QA integration testing → review → fixes (≤ 2 rounds) → orchestrator-run build (incl. 70% coverage gate) → post-task report → engineer approval.

## Build and test infrastructure — *implemented (US-001)*; carry-over R5/N1/N2 *implemented (US-002)*

The full detail and exact configuration are in the US-001 Design note. This section is the durable summary.

### Versions

Verified 2026-09-29 against the Spring Boot 3.5.16 BOM, Maven Central, and vendor release notes (sources are in the US-001 Design note).

| Component | Version | Source of version | Notes |
|---|---|---|---|
| Java | 25 | `java.version` | Boot 3.5.16 supports Java 17–25 |
| Spring Boot | 3.5.16 | parent | Final 3.5 release; OSS support ended 2026-06-30 (D22 risk) |
| Spring Framework | 6.2.19 | Boot-managed | |
| Hibernate ORM | 6.6.53.Final | Boot-managed | |
| Spring Security | 6.5.11 | Boot-managed | `spring-boot-starter-security` + test `spring-security-test`, *implemented (US-005)* |
| Flyway | 11.7.2 | Boot-managed | `flyway-core` + `flyway-database-postgresql` |
| PostgreSQL JDBC | 42.7.11 | Boot-managed | |
| Lombok | 1.18.46 | Boot-managed | Needs `annotationProcessorPaths` on JDK 23+ |
| Testcontainers | 1.21.4 | Boot-managed | |
| Mockito / Byte Buddy | 5.17.0 / 1.17.8 | Boot-managed | |
| JUnit Jupiter / Platform | **5.14.4** (Boot manages 5.12.2) | `junit-jupiter.version` override (D39) | Cucumber 7.34.9 requires Platform ≥ 1.13 |
| springdoc-openapi | 2.8.17 | explicit | Built on Boot 3.5.x; 3.x is for Boot 4 |
| Cucumber | 7.34.9 | explicit (BOM) | |
| JaCoCo | 0.8.15 | explicit | Official Java 25 support since 0.8.14; gate lives in US-001 (D40) |
| Maven | 3.9.16 | Maven Wrapper (`only-script`) | Enforcer: JDK `[25,)`, Maven `[3.9.16,)` |
| Surefire / Failsafe | 3.5.6 | Boot-managed | |
| PostgreSQL image | `postgres:18.6-alpine` | Compose (D23) and Testcontainers (D41) | Volume mounted at `/var/lib/postgresql` |

### Build lifecycle (`./mvnw -q verify`)

```
validate            enforcer: JDK ≥ 25, Maven ≥ 3.9.16 (fails before compilation)
initialize          clean stale target/jacoco*.exec; jacoco:prepare-agent → argLine (target/jacoco.exec); dependency:properties (Mockito agent path)
compile             javac --release 25, Lombok via annotationProcessorPaths
test                Surefire: **/*Test.java  (unit, @WebMvcTest, @RepositoryTest on Testcontainers)
pre-integration-test jacoco:prepare-agent-integration → argLine (target/jacoco-it.exec)
integration-test    Failsafe: **/*IT.java incl. CucumberIT (JUnit Platform suite → Cucumber engine)
post-integration-test jacoco:merge → jacoco-merged.exec; jacoco:report → target/site/jacoco-merged/
verify              enforcer requireFilesExist target/jacoco-merged.exec (skip: -Dcoverage.gate.skip=true; automatic with
                    -DskipTests / -Dmaven.test.skip=true); failsafe:verify; jacoco:check BUNDLE LINE COVEREDRATIO ≥ 0.70,
                    excluding UrlShortenerApplication only (D38)
```

Surefire and Failsafe strip `SPRING_PROFILES_ACTIVE` and `SPRING_DATASOURCE_*` from the forked test JVMs.

### Configuration and profiles (D24)

- `application.yml`: production baseline. No datasource entries; production supplies `SPRING_DATASOURCE_URL`/`USERNAME`/`PASSWORD`. Settings: `open-in-view=false`, `ddl-auto=validate`, Flyway on `classpath:db/migration`, actuator exposes `health` only (no details), error responses never include stack traces or messages.
- `application-local.yml`: localhost datasource with defaults matching `.env.example`. These are the only defaults in the codebase.
- `application-test.yml` (test classpath only): no datasource; the database comes solely from Testcontainers `@ServiceConnection`.
- No `prod` profile. `APP_BASE_URL` is bound when first used (US-004).
- `config/ClockConfig` provides `Clock.systemUTC()`.
- **Environment-variable names** *(verified in US-005 design)*: in the real `systemEnvironment` source, Spring Boot's `SystemEnvironmentPropertyMapper` binds a dashed property in both forms: canonical (dashes removed: `APP_BASEURL`) and legacy (dash becomes underscore: `APP_BASE_URL`). If both are set, the canonical form wins. Tests must add env-style keys through a `SystemEnvironmentPropertySource` named `systemEnvironment`.
- **Users** *(implemented (US-005))*:
  - Shape: `app.security.users[n].{username, password-hash, role}` (env `APP_SECURITY_USERS_<n>_USERNAME`, `…_PASSWORD_HASH` or `…_PASSWORDHASH`, `…_ROLE`).
  - None in `application.yml` or `application-local.yml` (D24), so a missing list fails startup. `local` reads them from the exported `.env`, and `test` from `application-test.yml` (admin, alice, bob).

### Local runtime

`docker compose up -d postgres` (Postgres only, bound to `127.0.0.1:5432`, `pg_isready` healthcheck, named volume at `/var/lib/postgresql`), then `./mvnw spring-boot:run -Dspring-boot.run.profiles=local`. The `app` service and hardened Dockerfile arrive in US-014; until then, `docker compose up` alone starts only the database.

### Test harness

```
support/TestcontainersConfiguration   @TestConfiguration: @Bean @ServiceConnection PostgreSQLContainer
        ▲                         ▲
        │ @Import                 │ @Import
support/IntegrationTestBase     support/@RepositoryTest
 @SpringBootTest(RANDOM_PORT)    @DataJpaTest (Surefire, mid-engineer)
 @ActiveProfiles("test")         @ActiveProfiles("test")
        ▲            ▲
   *IT classes    cucumber/CucumberSpringConfiguration (@CucumberContextConfiguration)
   (qa-tester)    cucumber/CucumberIT (@Suite @IncludeEngines("cucumber") @SelectPackages("features"))
```

`@SelectPackages("features")` replaces US-001's `@SelectClasspathResource("features")` (carry-over R5, *implemented (US-002)*). It removes Cucumber's discovery warning. `@Suite(failIfNoTests = true)` is the default, so discovering zero features fails the build.

- **One container per test JVM.** Every `*IT` class and Cucumber resolve to the same Spring context-cache key, so they share one context and one container. All `@RepositoryTest` classes share another context and container in the Surefire JVM.
- **Rule:** subclasses of `IntegrationTestBase` and `@RepositoryTest` classes add no context-affecting annotations (`@MockitoBean`, `@TestPropertySource`, extra `@Import`, `@DirtiesContext`, …). A controllable clock, when needed, is one shared `@Primary` bean in the shared test configuration.
- **HTTP client:** `TestRestTemplate`. No H2 on the classpath (D21). No Testcontainers reuse mode.
- **Repository tests** *(implemented (US-002))*:
  - Constraint tests insert through `JdbcTemplate`, which joins the `@DataJpaTest` transaction, so they hit the DB constraint rather than the entity. They assert SQLSTATE plus constraint name from pgjdbc's `ServerErrorMessage`, with one failing statement per test because PostgreSQL aborts the transaction after an error.
  - Tests flush and `clear()` the persistence context before reloading, and assert DB truth with `JdbcTemplate`.

## Package structure (planned; `domain/`, `domain/exception/`, `repository/` *implemented (US-002)*)

```
com.schwab.urlshortener
├── config/            # properties, Clock, OpenAPI
├── security/          # implemented (US-005): SecurityConfig (filter chain, RoleHierarchy),
│                      #   UserAccountsConfig/Properties, UserAccounts, Role,
│                      #   ProblemDetailAuthenticationEntryPoint/AccessDeniedHandler/ResponseWriter
├── api/               # controllers
│   ├── dto/
│   └── error/         # ErrorCode, ProblemDetails (implemented (US-005)); GlobalExceptionHandler (US-006)
├── service/
├── domain/            # ShortUrl entity, ShortUrlStatus
│   └── exception/     # ShortUrlAlreadyDeactivated/AlreadyActive/Deleted exceptions
├── shortcode/         # ShortCodeGenerator + implementation
├── validation/        # UrlValidator, AliasPolicy
├── analytics/         # ClickRecorder + implementation
└── repository/        # ShortUrlRepository (Spring Data JPA)
```

## Database schema (V1 *implemented (US-002)*, amended by D47; V2 planned)

**V1 — short_url** (`V1__create_short_url.sql`). The SQL below is the approved schema.
- `ck_short_url_deleted_consistency` was tightened at the US-002 design gate (D44). The original `(status = 'DELETED') = (both set)` form accepted a non-deleted row with only one audit field set.
- `short_code` and `original_url` are `TEXT` with CHECK-enforced limits (D47). `VARCHAR(n)` silently truncates over-length input whose excess is only trailing spaces, so a named CHECK on `TEXT` is the only reliable limit.
- `char_length` counts characters, matching D11's "2048 chars".
- Hibernate `validate` accepts `String` ↔ `text` without mapping changes, because pgjdbc reports `text` as `Types.VARCHAR`.

```sql
CREATE TABLE short_url (
  id               BIGSERIAL PRIMARY KEY,
  short_code       TEXT          NOT NULL,
  original_url     TEXT          NOT NULL,
  custom_alias     BOOLEAN       NOT NULL DEFAULT FALSE,
  status           VARCHAR(16)   NOT NULL DEFAULT 'ACTIVE',
  click_count      BIGINT        NOT NULL DEFAULT 0,
  last_accessed_at TIMESTAMPTZ   NULL,
  created_by       VARCHAR(100)  NOT NULL,
  created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
  updated_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
  deleted_at       TIMESTAMPTZ   NULL,
  deleted_by       VARCHAR(100)  NULL,
  version          BIGINT        NOT NULL DEFAULT 0,
  CONSTRAINT uk_short_url_short_code UNIQUE (short_code),
  CONSTRAINT ck_short_url_status CHECK (status IN ('ACTIVE','DEACTIVATED','DELETED')),
  CONSTRAINT ck_short_url_click_count CHECK (click_count >= 0),
  CONSTRAINT ck_short_url_code_format CHECK (short_code ~ '^[A-Za-z0-9]{3,32}$'),
  CONSTRAINT ck_short_url_original_url_length CHECK (char_length(original_url) <= 2048),
  CONSTRAINT ck_short_url_deleted_consistency CHECK (
    (status = 'DELETED' AND deleted_at IS NOT NULL AND deleted_by IS NOT NULL)
    OR (status <> 'DELETED' AND deleted_at IS NULL AND deleted_by IS NULL))
);
```

`created_by`/`deleted_by` stay `VARCHAR(100)` (D51, *implemented (US-005)*). Usernames are bounded at startup to 1–100 lowercase ASCII characters with no whitespace, and a domain actor guard checks the stored values. Both use `ShortUrl.MAX_ACTOR_LENGTH`. The rejected alternative was a forward migration to `TEXT` plus a length CHECK.

Column ownership (see the domain model below): the application writes every column except `id` (sequence), `version` (Hibernate), and `click_count`/`last_accessed_at` (only US-010's atomic UPDATE, D27). The `DEFAULT now()`/`'ACTIVE'`/`FALSE`/`0` values apply only to raw SQL inserts, except `click_count`, whose `DEFAULT 0` is how every new row gets its initial count. The unique constraint's index serves the redirect lookup. Its keys stay at most 32 bytes because PostgreSQL evaluates `ck_short_url_code_format` before inserting index entries. Application validation (US-004) must reject over-length or space-padded input as submitted, without trimming (D47).

**V2 — click_event** (analytics)

```sql
CREATE TABLE click_event (
  id           BIGSERIAL PRIMARY KEY,
  short_url_id BIGINT NOT NULL REFERENCES short_url(id),
  clicked_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_click_event_url_time ON click_event (short_url_id, clicked_at);
```

**V3 — expiration** is designed after the brownfield impact analysis (Scenario 2).

## Domain model — *implemented (US-002)*

The full detail is in the US-002 Design note.

**`ShortUrl`** (`domain`):
- JPA entity on `short_url`. Lombok `@Getter`, `@NoArgsConstructor(access = PROTECTED)`, `@ToString(onlyExplicitlyIncluded = true)` (id, shortCode, status, version only: never the URL or usernames). No setters. Object-identity equality.
- `id`: `Long`, `IDENTITY`. `status`: `@Enumerated(STRING)`. `version`: `Long @Version`. Hibernate seeds 0, and a `null` version tells Spring Data the entity is new.
- Timestamps are `Instant` ↔ `timestamptz`.
- Creation-time columns are `updatable = false`.
- `click_count`/`last_accessed_at` are `insertable = false, updatable = false` (D27). The factory initializes them to the DB defaults (0/NULL), because Hibernate does not re-read non-insertable columns after INSERT.
- Creation: `ShortUrl.create(shortCode, originalUrl, customAlias, createdBy, createdAt)`.

**Time:** the application owns `created_at`/`updated_at`. Callers pass `Instant`s from the injected `Clock`, and every transition sets `updatedAt`. The entity truncates to microseconds (`timestamptz` resolution). Hibernate `@CreationTimestamp`/`@UpdateTimestamp` are not used: they bypass the `Clock` bean.

**Lifecycle** (D26, D36; checks run in this order: arguments → deleted → redundant; a throwing call changes nothing):

| From \ call | `deactivate(at)` | `reactivate(at)` | `softDelete(by, at)` |
|---|---|---|---|
| `ACTIVE` | → `DEACTIVATED` | `ShortUrlAlreadyActiveException` → 409 `SHORT_URL_ALREADY_ACTIVE` | → `DELETED` |
| `DEACTIVATED` | `ShortUrlAlreadyDeactivatedException` → 409 `SHORT_URL_ALREADY_DEACTIVATED` | → `ACTIVE` | → `DELETED` |
| `DELETED` | `ShortUrlDeletedException` → 404 `SHORT_URL_NOT_FOUND` | same | same (D46) |

HTTP mappings are implemented in US-009.

**`ShortUrlRepository`** (`repository`): `JpaRepository<ShortUrl, Long>` plus `findByShortCode` (case-sensitive, no status filtering). Integrity under concurrency comes from `uk_short_url_short_code`, `@Version`, and the D27 mapping, not from application pre-checks.

## REST API (planned)

| Method | Path | Access | Success | Errors |
|---|---|---|---|---|
| GET | `/{code}` | Public | 302 | 404 (unknown / deleted / deactivated) |
| POST | `/api/v1/urls` | USER, ADMIN | 201 + `Location` | 400, 401, 409 |
| GET | `/api/v1/urls/{code}` | Owner, ADMIN | 200 | 401, 404 |
| PATCH | `/api/v1/urls/{code}` | Owner, ADMIN | 200 | 400, 401, 404, 409 |
| DELETE | `/api/v1/urls/{code}` | ADMIN | 204 | 401, 403, 404 |
| GET | `/api/v1/urls/{code}/stats?from&to&timezone` | Owner, ADMIN | 200 | 400, 401, 404 |

### Access rules in the filter chain — *implemented (US-005)*

The rules below are evaluated top to bottom, and the first match wins. The exact configuration is in the US-005 Design note §3.

| # | Matcher | Rule |
|---|---|---|
| 1 | ERROR dispatch | permitAll (only reached after the original request was authorized) |
| 2 | `GET /actuator/health` | public |
| 3 | `GET /v3/api-docs`, `/v3/api-docs/**`, `/v3/api-docs.yaml`, `/swagger-ui.html`, `/swagger-ui/**` | public (springdoc 2.8.17 defaults) |
| 4 | `/actuator`, `/actuator/**` | authenticated (keeps `/actuator` from matching rule 7) |
| 5 | `DELETE /api/v1/urls/**` | `hasRole(ADMIN)` (D3). It covers trailing-slash and nested variants. 403 is returned before any handler or lookup (US-009 AC6, AC8) |
| 6 | `/api`, `/api/**` | `hasRole(USER)`; ADMIN passes through the `ADMIN > USER` hierarchy. Ownership (D4) is enforced in the service |
| 7 | `GET` and `HEAD /*` (any single segment) | public (D32). Not narrowed to the code regex: US-008 AC6 requires 404, not 401, for malformed codes |
| 8 | anything else | **`denyAll`** (D57): anonymous gets 401 through the entry point, authenticated gets 403 `ACCESS_DENIED`. Only explicitly listed paths can reach a handler, so case or path variants of a restricted prefix (for example `/API/...`) never fall through to a weaker rule |

Rule: no handler other than the redirect may be mapped to a single path segment, because rule 7 would make it public. No method security (`@EnableMethodSecurity`) is used.

**Operational notes (engineer-approved at US-005 G3):**
- `/error` is a **permitted single-segment GET handler**, reachable through the public `GET /*` rule. It is an allowed exception to the "no single-segment handler other than the redirect" rule, and is harmless while error details stay off (`server.error.include-*: never`).
- `org.springframework.security` must **never** be set to DEBUG or TRACE logging in shared environments, because at those levels Spring logs attempted usernames, which would break D52.

## Key design decisions

### Short-code generation
- **Recommendation:** `SecureRandom` Base62 codes (default length 7, configurable), inserted in a dedicated transaction per attempt; on a violation of `uk_short_url_short_code`, retry up to a configurable bound (default 5), then fail with 503.
- **Reason:** PostgreSQL aborts a transaction after a failed statement, so retries must run in fresh transactions. The UNIQUE constraint is the correctness guarantee under concurrency.
- **Alternative:** native `INSERT … ON CONFLICT (short_code) DO NOTHING RETURNING id`.
- **Trade-off:** `ON CONFLICT` avoids exceptions but bypasses JPA and ties the code to PostgreSQL; collisions are rare at 62⁷ ≈ 3.5 × 10¹², so readability wins.
- Custom aliases are never retried: a uniqueness violation returns 409.

### Analytics
- **Recommendation:** atomic `UPDATE … SET click_count = click_count + 1, last_accessed_at = :now` plus an insert into `click_event`, behind a `ClickRecorder` interface. Daily stats are computed with `GROUP BY` in the caller's time zone.
- **Reason:** exact counts, cheap total reads, no rollup table to keep consistent.
- **Alternative:** a `daily_click_stats` upsert table, or asynchronous events via Kafka/SQS.
- **Trade-off:** synchronous writes add latency to redirects and contend on the counter row for hot links. A production system would publish click events to a message broker and aggregate them asynchronously. Failures fail open (D12).

### Time zones for daily stats
- **Recommendation:** accept only IANA region IDs and `UTC`, validated in Java, then group in PostgreSQL with `AT TIME ZONE`.
- **Reason:** handles DST correctly and keeps aggregation in the database.
- **Alternative:** accept raw offsets, or group in Java.
- **Trade-off:** PostgreSQL interprets POSIX-style offsets with an inverted sign, so offsets are rejected to prevent silently wrong results. Grouping in Java would load every click into memory.

### Security
- **Recommendation:** Spring Security, HTTP Basic, stateless, users configured with BCrypt-hashed passwords from environment variables; roles `USER` and `ADMIN` (hierarchy `ADMIN > USER`).
- **Reason:** the smallest mechanism that genuinely enforces the permission model and is easy to test.
- **Alternative:** OAuth2 resource server (JWT) backed by an external identity provider.
- **Trade-off:** Basic sends credentials on every request, so HTTPS is required in any real deployment. Migrating to JWT changes only the security configuration. CSRF is disabled because the API uses no cookies or sessions.
- **Detail** *(implemented (US-005); full design in the US-005 Design note)*:
  - `SessionCreationPolicy.STATELESS` with an explicit `NullRequestCache`; no form login and no logout; Spring default headers (HSTS per D37).
  - **The only published authentication bean is a `DaoAuthenticationProvider`, by its concrete type.** Its `BCryptPasswordEncoder(10)` and `InMemoryUserDetailsManager` are built inside the bean method and are never beans (CLAUDE.md security-sensitive-bean rule). That single bean also switches off Boot's generated-password user and becomes Spring's global provider too. The chain uses `new ProviderManager(provider)`.
  - BCrypt cost is fixed at 10, and every configured hash must have cost 10. This keeps unknown-user timing, which uses a dummy hash at the encoder's cost, equal to known-user timing. The hash format is validated at startup without echoing the value.
  - `RoleHierarchy` bean `ADMIN > USER`, picked up automatically by every `hasRole` URL rule.
  - 401 and 403 are written by a custom entry point and access-denied handler (D30), with `WWW-Authenticate: Basic realm="url-shortener"`. The body is the same for missing, malformed and wrong credentials and for unknown users. No usernames or exception messages are logged.

### Migrations
- **Recommendation:** Flyway. **Reason:** plain, reviewable SQL. **Alternative:** Liquibase. **Trade-off:** Liquibase's multi-database support and rollbacks aren't needed here.

### Error handling
RFC 7807 `ProblemDetail` responses with an `errorCode` extension, produced by a single `@RestControllerAdvice`. Stack traces and exception messages are never included; unexpected errors return a generic 500 with a request ID.

*Implemented (US-005):*
- **`api/error/ErrorCode`** holds exactly the D31 catalogue. Each constant carries its HTTP status:
  - 400: `VALIDATION_FAILED`, `MALFORMED_REQUEST`, `INVALID_URL`, `INVALID_ALIAS`
  - 409: `ALIAS_ALREADY_EXISTS`, `SHORT_URL_ALREADY_DEACTIVATED`, `SHORT_URL_ALREADY_ACTIVE`, `CONCURRENT_MODIFICATION`
  - 404: `SHORT_URL_NOT_FOUND`
  - 503: `SHORT_CODE_UNAVAILABLE`
  - 401: `AUTHENTICATION_REQUIRED`
  - 403: `ACCESS_DENIED`
  - 500: `INTERNAL_ERROR`
- **`api/error/ProblemDetails.of(code, detail, requestUri)`** is the single factory for every error body. The shape is exactly `type` (`about:blank`), `title` (reason phrase), `status`, `detail` (generic), `instance` (request path, no query) and `errorCode`. `instance` is set because Spring MVC fills it in for controller-returned `ProblemDetail`s. `errorCode` is top-level only when serialized by the context's `ObjectMapper` (`ProblemDetailJacksonMixin`).
- The security entry point and access-denied handler write through this factory. US-006's `@RestControllerAdvice` must use it too, so any later extension (such as a request ID) reaches every error.
- Requests rejected by `StrictHttpFirewall` get Spring's plain 400, and requests to unknown paths get Boot's default error JSON, until US-006's advice handles them.
