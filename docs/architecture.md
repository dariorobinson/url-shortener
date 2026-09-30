# Architecture

Last updated: 2026-09-30 (US-006 implemented; content negotiation per D70; US-007 implemented; US-008 implemented; US-009 implemented; US-010 implemented). Each section is marked *planned*, *designed (US-nnn)*, or *implemented (US-nnn)* as work progresses.

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
- **Request parsing and database error hygiene** *(implemented (US-006); US-006 Design note §1.2, §3.6)*:
  - `spring.jackson.deserialization.fail-on-unknown-properties: true` and `spring.jackson.parser.strict-duplicate-detection: true`. Unknown fields and duplicate keys give `400 MALFORMED_REQUEST` for every endpoint.
  - `spring.datasource.hikari.data-source-properties.logServerErrorDetail: false`, so pgjdbc exception messages never carry row values (URLs, usernames). The structured `ServerErrorMessage` fields stay available.
  - `logging.level.org.hibernate.engine.jdbc.spi.SqlExceptionHelper: off`, because expected unique violations are handled and logged by the service.
  - The test profile's `app.base-url` becomes `https://short.example`.

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
- **Short-code test seam** *(implemented (US-006); US-006 Design note §6)*:
  - `support/ScriptedShortCodeGenerator` is a `@Primary` bean in `support/ShortCodeGeneratorTestConfiguration`, which `IntegrationTestBase` imports alongside `TestcontainersConfiguration`. That keeps one context for every `*IT` and for Cucumber.
  - It delegates to the production `shortCodeGenerator` bean unless a test has queued codes with `willReturn(...)`. `calls()` counts `generate()` calls.
  - Tests call `reset()` before and after each test, and a Cucumber hook resets every scenario.
  - It is thread-safe (`ConcurrentLinkedQueue`, `AtomicInteger`) and assumes serial test execution.
  - Production code has no test mode.
- **Controllable test clock** *(implemented (US-010); US-010 Design note §7)*:
  - `support/TestClock` extends `Clock`. It is a `@Primary` bean in `support/TestClockConfiguration`, which `IntegrationTestBase` imports, so there is still one context. It delegates to the production `clock` bean unless a test calls `setInstant`/`advance`.
  - `reset()` restores real time. A JUnit extension on `IntegrationTestBase` resets it before and after every IT test, and a Cucumber hook resets it every scenario. It is thread-safe (`AtomicReference`).
  - `ClockConfig` is unchanged, and so is production code.
- **Truncation with V2** *(implemented (US-010))*: `ShortUrlTestData.truncate()` is `TRUNCATE TABLE click_event, short_url`. PostgreSQL refuses to truncate a table referenced by a foreign key unless every referencing table is in the same command. There is no `CASCADE`.
- **Forged `Host` headers** *(implemented (US-006))*: the Failsafe `argLine` gets `-Djdk.httpclient.allowRestrictedHeaders=host`. It is test-only and never set in runtime JVM options.
- **Repository tests** *(implemented (US-002))*:
  - Constraint tests insert through `JdbcTemplate`, which joins the `@DataJpaTest` transaction, so they hit the DB constraint rather than the entity. They assert SQLSTATE plus constraint name from pgjdbc's `ServerErrorMessage`, with one failing statement per test because PostgreSQL aborts the transaction after an error.
  - Tests flush and `clear()` the persistence context before reloading, and assert DB truth with `JdbcTemplate`.

## Package structure (planned; `domain/`, `domain/exception/`, `repository/` *implemented (US-002)*; US-006 entries *implemented (US-006)*)

```
com.schwab.urlshortener
├── config/            # properties, Clock; OpenApiConfig (US-006: info, HTTP Basic security scheme)
├── security/          # implemented (US-005): SecurityConfig (filter chain, RoleHierarchy),
│                      #   UserAccountsConfig/Properties, UserAccounts, Role,
│                      #   ProblemDetailAuthenticationEntryPoint/AccessDeniedHandler/ResponseWriter
├── api/               # ShortUrlController, ShortUrlLinks (shortUrl/Location from APP_BASE_URL) (US-006);
│                      #   RedirectController (GET/HEAD /{code}, never `produces`) (implemented (US-008))
│   ├── dto/           # CreateShortUrlRequest, ShortUrlResponse (shared with US-007) (US-006);
│   │                  #   UpdateShortUrlRequest (PATCH body) (implemented (US-009))
│   └── error/         # ErrorCode, ProblemDetails (implemented (US-005));
│                      #   GlobalExceptionHandler, FieldViolation, ErrorResponseSchema (docs only) (US-006)
├── service/           # ShortUrlService, CreateShortUrlCommand, ShortUrlView (US-006);
│   │                  #   Caller(username, admin) (implemented (US-007));
│   │                  #   RedirectService (public resolution, read-only template) (implemented (US-008))
│   └── exception/     # InvalidUrl/InvalidAlias/AliasAlreadyExists/ShortCodeUnavailable exceptions (US-006);
│                      #   ShortUrlNotFoundException (implemented (US-007));
│                      #   ShortUrlConcurrentModificationException (implemented (US-009))
├── domain/            # ShortUrl entity, ShortUrlStatus; ClickEvent (immutable, click_event) (implemented (US-010))
│   └── exception/     # ShortUrlAlreadyDeactivated/AlreadyActive/Deleted exceptions
├── shortcode/         # ShortCodeGenerator + implementation; ShortCodeFormat (D6 format check, shared by
│                      #   AliasPolicy, US-007 and US-008) (implemented (US-007))
├── validation/        # UrlValidator, AliasPolicy
├── analytics/         # ClickRecorder (interface) + JpaClickRecorder (REQUIRES_NEW template) (implemented (US-010))
└── repository/        # ShortUrlRepository (Spring Data JPA); PostgresServerErrors (US-006; the only
                       #   class that imports org.postgresql.*); ClickEventRepository and
                       #   ShortUrlRepository.recordClick (implemented (US-010))
```

## Database schema (V1 *implemented (US-002)*, amended by D47; V2 *implemented (US-010)*)

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

**V2 — click_event** (`V2__create_click_event.sql`, analytics) *(implemented (US-010); the exact file, with comments, is in the US-010 Design note §1)*

```sql
CREATE TABLE click_event (
  id           BIGSERIAL     PRIMARY KEY,
  short_url_id BIGINT        NOT NULL,
  clicked_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
  CONSTRAINT fk_click_event_short_url FOREIGN KEY (short_url_id) REFERENCES short_url (id)
);

CREATE INDEX ix_click_event_short_url_id_clicked_at ON click_event (short_url_id, clicked_at);
```

- Names follow V1: the unnamed PK is `click_event_pkey`, the FK is named, and the index is named after its columns (it replaces the earlier sketch's `ix_click_event_url_time`).
- The index serves US-011's per-link, time-ranged query and the FK's referencing-side lookups.
- Only three columns exist (D8).
- `clicked_at` always comes from the application `Clock`, truncated to microseconds. As in V1, `DEFAULT now()` serves raw SQL only (D45). Hibernate always sends the column, so an application insert never falls back to it.
- The FK uses the default NO ACTION. Links are soft-deleted (D1), so a hard delete of a clicked link is refused.
- V2 adds no CHECK to `short_url` (D85).

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

HTTP mappings: *implemented (US-009)* (see *Lifecycle* under REST API). `ShortUrlDeletedException` is unreachable through `loadVisible`, which hides `DELETED` rows first, but the advice still maps it to the identical 404 (D46).

**`ShortUrlRepository`** (`repository`): `JpaRepository<ShortUrl, Long>` plus `findByShortCode` (case-sensitive, no status filtering). Integrity under concurrency comes from `uk_short_url_short_code`, `@Version`, and the D27 mapping, not from application pre-checks.

*Implemented (US-010):*
- `ShortUrlRepository` gains `int recordClick(long id, Instant clickedAt)`, a native `@Modifying(flushAutomatically = true, clearAutomatically = true)` `@Query`:
  `UPDATE short_url SET click_count = click_count + 1, last_accessed_at = :clickedAt WHERE id = :id AND status = 'ACTIVE'`.
  - It never touches `version` or `updated_at` (D16, D27).
  - It has no `@Transactional`, and Spring Data gives declared query methods none, so it runs only inside the caller's transaction.
  - A reflection unit test (`RepositoryAnnotationsTest`) pins the SQL, both flags, the absence of `@Transactional`, and that no other repository method is `@Modifying`. This covers the repository-interface gap in `NoTransactionalAnnotationIT`.
- `ClickEventRepository` is `JpaRepository<ClickEvent, Long>`. `ClickEvent` is `@Immutable`, with a plain `long shortUrlId` (no association) and `ClickEvent.of(id, at)`, which truncates to microseconds.

## REST API (planned)

| Method | Path | Access | Success | Errors |
|---|---|---|---|---|
| GET, HEAD | `/{code}` | Public (rule 7) | 302 + `Location` + `Cache-Control: no-store` | 404 (unknown / deleted / deactivated / malformed); 401 only for invalid Basic credentials (D55) |
| POST | `/api/v1/urls` | USER, ADMIN | 201 + `Location` | 400, 401, 406, 409, 415, 503 |
| GET | `/api/v1/urls/{code}` | Owner, ADMIN | 200 | 401, 404, 406 |
| PATCH | `/api/v1/urls/{code}` | Owner, ADMIN | 200 | 400, 401, 404, 406, 409, 415 (implemented (US-009)) |
| DELETE | `/api/v1/urls/{code}` | ADMIN | 204 | 401, 403, 404, 406, 409 (implemented (US-009)) |
| GET | `/api/v1/urls/{code}/stats?from&to&timezone` | Owner, ADMIN | 200 | 400, 401, 404 |

**Content negotiation rule (D70):** management API mappings declare `produces = application/json` (class-level on `ShortUrlController`, never `application/problem+json`); the redirect never does. Every `/api/v1/urls` operation therefore also returns 406 for an `Accept` that excludes `application/json`, DELETE included. The 406 is decided at mapping lookup, before the body is read or the service runs, so a rejected request never creates or changes anything. Precedence and the unparseable-`Accept` deviation are under *Error handling*.

### Create short URL — *implemented (US-006)*

The full detail is in the US-006 Design note.

- **Security:** access-table rule 6 (`/api`, `/api/**` → `hasRole(USER)`, with ADMIN passing through the hierarchy) admits `POST /api/v1/urls`. `created_by` is `Authentication.getName()`, the configured lowercase username (D4, D51, D54).
- **Request** `CreateShortUrlRequest {originalUrl, alias?}` (D17):
  - `@NotBlank originalUrl` → `VALIDATION_FAILED`
  - `UrlValidator` → `INVALID_URL`
  - `AliasPolicy` → `INVALID_ALIAS`
  - A missing or `null` `alias` is absent. `""` or whitespace is invalid, never trimmed (D47).
  - Unknown fields and duplicate keys → `MALFORMED_REQUEST`.
- **Response** `ShortUrlResponse`, shared with US-007: `shortCode`, `shortUrl`, `originalUrl`, `status`, `customAlias`, `clickCount`, `createdAt` (ISO-8601 UTC), `lastAccessedAt` (nullable, always present). There is no `createdBy`.
  - `Location: /api/v1/urls/{code}` is relative.
  - `shortUrl` is APP_BASE_URL with trailing slashes removed, plus `/` and the code, built by `api/ShortUrlLinks`. It never uses `Host` or forwarded headers (D33).
- **Flow:** `ShortUrlController` → `ShortUrlService.create(CreateShortUrlCommand)` → `ShortUrlView`, mapped to `ShortUrlResponse` in `api`. Entities never leave `service`.
- **Content negotiation (D70):** `@RequestMapping(path = "/api/v1/urls", produces = application/json)` on the class. An unacceptable `Accept` (for example `application/xml`, `text/plain` or `application/problem+json`) gets `406 NOT_ACCEPTABLE` with no row created (US-006 AC17).

### Get short URL details — *implemented (US-007)*

The full detail is in the US-007 Design note.

- **Security:** access-table rule 6 admits `GET` and `HEAD /api/v1/urls/{code}`. ADMIN passes through the hierarchy. `SecurityConfig` is unchanged.
- **Response:** the same `ShortUrlResponse.from(view, links)` as create (D58, D67), `200 application/json`, with the class-level `produces` inherited (D70).
- **HEAD** is served by the GET mapping (Spring MVC) and returns headers only: 200 with `Content-Type: application/json`, no body, and no `Content-Length` on embedded Tomcat (recorded by QA). It has no side effects.
- **Caching:** there is no app-level cache header. Spring Security's default `Cache-Control: no-cache, no-store, max-age=0, must-revalidate` (plus `Pragma` and `Expires`) applies, and tests pin it.
- **Caller:** the controller builds `service.Caller(username, admin)` from the `Authentication`:
  - `username` is `getName()`, the configured lowercase name (D54).
  - `admin` is true when `getAuthorities()` contains `Role.ADMIN.authority()` (`ROLE_ADMIN`). The role hierarchy is applied only at authorization decisions, not to `getAuthorities()`.
  - The service has no Spring Security dependency.
- **Visibility rule** (`ShortUrlService.loadVisible`, reused by US-009), in this order:
  1. The D6 format check (`ShortCodeFormat`, format only, never reserved words); a malformed code causes no DB call.
  2. `findByShortCode` (case-sensitive).
  3. `DELETED` → hidden for everyone, ADMIN included (D13).
  4. Not ADMIN and `createdBy` not equal to the username → hidden (D4).

  Every hidden case throws the one `ShortUrlNotFoundException`, which becomes `404 SHORT_URL_NOT_FOUND` with an identical body. The 404 hides ownership and details, not existence: existence is already visible through create's 409 (D1).
- **Transaction:** a read-only `TransactionTemplate` built in the service constructor. There is still no `@Transactional` in `service` or `api`.

### Redirect — *implemented (US-008)*

The full detail is in the US-008 Design note.

- **Security:** rule 7 (`GET`/`HEAD /*`, D32) admits it; `SecurityConfig` is unchanged. Invalid Basic credentials still get 401 (D55).
- **Controller:** `api/RedirectController`, `@GetMapping("/{code}")` with no class-level mapping, no base class and **no `produces`** (D70). Any `Accept` gets the 302. HEAD is served by the same mapping.
- **Resolution:** `service/RedirectService.resolve(code)`:
  1. The D6 format check (`ShortCodeFormat`), **before** the transaction opens, so a malformed code takes no connection (D72).
  2. `findByShortCode` inside a read-only `TransactionTemplate`. There is no `@Transactional`.
  3. Only `ACTIVE` links redirect. Unknown, `DEACTIVATED` and `DELETED` codes throw the one `ShortUrlNotFoundException`, which becomes `404 SHORT_URL_NOT_FOUND` with an identical body (D2, D74).
  4. The service returns the stored URL string.
- **302:** built with `ResponseEntity.status(FOUND).header(LOCATION, String).cacheControl(noStore())`, never `setLocation(URI)`, `sendRedirect` or `"redirect:"`.
  - `Location` is the stored string byte for byte when it is printable ASCII. Code points outside `0x21`–`0x7E` are percent-encoded as UTF-8 (D75, RFC 3987 §3.1). Tomcat 10.1 cannot send them: above U+00FF it drops the header and logs its full value.
  - There is no body and no `Content-Type`.
  - `Cache-Control` is exactly `no-store`, with no `Pragma` or `Expires`, because Spring Security's cache writer backs off when the header is already set. The redirect's 404 keeps Security's default.
- **404 negotiation:** the problem+json fallback applies to every parseable `Accept` (`text/html`, `image/*`, `*/*`, none). An unparseable `Accept` gets a 404 with an empty body. It is never a 406.
- **Routing precedence:** literal mappings (`/error`, `/swagger-ui.html`) win as direct-path matches. Actuator's handler mapping (order −100) runs before `RequestMappingHandlerMapping`. `/{code}` takes every other single segment, including `/favicon.ico`, and bare `/api` for authenticated callers, which gets 404 because it is a reserved word. A QA inventory test pins the single-segment GET mappings to exactly `/{code}`, `/error` and `/swagger-ui.html`.
- **US-010 seam:** one handler and one response builder. The read transaction has closed before `resolve` returns. US-010 adds an `HttpMethod` parameter, records clicks for GET only after a successful resolution, and fails open (D9, D12). US-008 adds no `ClickRecorder`.
- **Click recording** *(implemented (US-010); US-010 Design note §4–§6)*:
  - The handler takes `(@PathVariable String code, HttpMethod method)`. GET calls `RedirectService.resolveAndRecordClick(code)`; HEAD and anything else call `resolve(code)`, which never records (D9, D18). The response builder is unchanged.
  - `resolveAndRecordClick` resolves through the same private lookup, which returns a private `(id, target)` record, so the entity never leaves the service. It then calls `ClickRecorder.record(id, clock.instant())` **after** the read-only transaction has committed.
  - Any `RuntimeException` is caught in `RedirectService` and logged at WARN with the code, id, exception class and SQLSTATE only: never the URL, the exception message or a stack trace (D12, D64, D93). The target is still returned.
  - `JpaClickRecorder` truncates the instant to microseconds once. In a constructor-built `REQUIRES_NEW` template it runs `recordClick`, then, only if one row changed, `saveAndFlush(ClickEvent.of(id, at))`. Either failure rolls back both.
  - A link deactivated or deleted between resolution and the click UPDATE matches 0 rows and is not counted, while the 302 stands (D91).
- **Logging:** DEBUG only, with the well-formed code and a reason (`NOT_FOUND`, `DEACTIVATED`, `DELETED`, `MALFORMED` without the value). Never the target URL or host.

### Lifecycle: deactivate, reactivate, soft delete — *implemented (US-009)*

The full detail is in the US-009 Design note. There is no migration, and `SecurityConfig` is unchanged.

- **PATCH `/api/v1/urls/{code}`** (D34):
  - Admitted by rule 6.
  - `consumes = application/json`, so other types get 415. Class-level `produces` is inherited (D70).
  - Body `UpdateShortUrlRequest(@NotNull Boolean active)`: missing or `null` gives `VALIDATION_FAILED` with `errors`, and unknown fields give `MALFORMED_REQUEST` (D59).
  - 200 with the D58 `ShortUrlResponse`.
  - A redundant change gets the D26 409s. Deleted, unknown, malformed and not-yours codes get the one 404 (D4, D13, D72, D74).
- **DELETE `/api/v1/urls/{code}`** (D1, D3, D36, D46):
  - Rule 5 gives a USER 403 before any handler.
  - 204 with no body.
  - `softDelete(username, clock)`, where `deleted_by` is the configured admin username (D51).
  - Deleting a DEACTIVATED link is allowed; a DELETED or unknown code gets 404.
  - The code is never reused: a create with it gets `409 ALIAS_ALREADY_EXISTS`.
- **Service:** `ShortUrlService.setActive(code, active, caller)` and `delete(code, caller)`. `delete` first checks `caller.admin()` as a guard behind rule 5, throwing `IllegalStateException`. Both reuse `loadVisible` unchanged, then call the entity transition. `updated_at`/`deleted_at` come from the injected `Clock` (D45).
- **Transactions:**
  - A third constructor-built `TransactionTemplate`, `readWrite` (REQUIRED, read-write, READ COMMITTED). There is no `@Transactional`.
  - An explicit `repository.flush()` inside the callback runs the versioned `UPDATE … WHERE id = ? AND version = ?` before commit. That avoids Hibernate's managed-flush ERROR log (`HHH000346`).
  - Spring's `OptimisticLockingFailureException` (flush-time or commit-time) is caught **outside** the template, after rollback, and rethrown as `ShortUrlConcurrentModificationException`, which gives `409 CONCURRENT_MODIFICATION` (D35).
- **Concurrency:**
  - Two concurrent deactivations give one 200 and one 409. The loser gets `CONCURRENT_MODIFICATION` if both read the same version, or `SHORT_URL_ALREADY_DEACTIVATED` if they serialised. Clients must handle both (D86). DELETE can also return `409 CONCURRENT_MODIFICATION`, and a PATCH that loses to a DELETE gets 409; a re-read then gives 404 (D87).
  - DELETE racing with PATCH, or with another DELETE, can also give `409 CONCURRENT_MODIFICATION`.
  - A click (US-010's atomic UPDATE, which never touches `version`) never causes a 409. The entity UPDATE never writes `click_count`/`last_accessed_at` (D16, D27).
- **Logging:** INFO after commit with the code (deactivated, reactivated, deleted), and INFO for a conflict with the code and action. Never usernames (including `deleted_by`) or URLs.

- **Request parsing (D88, D89, D90):** PATCH accepts `application/json` only (other types get 415). `active` must be a real JSON boolean: `"false"`, `0` and `1` get `400 MALFORMED_REQUEST`, and a missing or null value gets `400 VALIDATION_FAILED`. Jackson's scalar coercion is disabled for the Boolean type only, through `config/JacksonConfig`, which every web slice imports via the shared slice configuration. Click data in the PATCH 200 is as read inside that transaction (D90).
- **405 `Allow`:** a 405 on `/api/v1/urls/{code}` lists `GET, DELETE, PATCH`. Spring leaves out the implicit HEAD, although HEAD is served.

### Access rules in the filter chain — *implemented (US-005)*

The rules below are evaluated top to bottom, and the first match wins. The exact configuration is in the US-005 Design note §3.

| # | Matcher | Rule |
|---|---|---|
| 1 | ERROR dispatch | permitAll (only reached after the original request was authorized) |
| 2 | `GET /actuator/health` | public |
| 3 | `GET /v3/api-docs`, `/v3/api-docs/**`, `/v3/api-docs.yaml`, `/swagger-ui.html`, `/swagger-ui/**` | public (springdoc 2.8.17 defaults) |
| 4 | `/actuator`, `/actuator/**` | authenticated (keeps `/actuator` from matching rule 7) |
| 5 | `DELETE /api/v1/urls/**` | `hasRole(ADMIN)` (D3). It covers trailing-slash and nested variants. 403 is returned before any handler or lookup (US-009 AC6, AC8). Admits the ADMIN soft delete (implemented (US-009)) |
| 6 | `/api`, `/api/**` | `hasRole(USER)`; ADMIN passes through the `ADMIN > USER` hierarchy. Ownership (D4) is enforced in the service. It admits `POST /api/v1/urls` (US-006) and `GET`/`HEAD /api/v1/urls/{code}` (implemented (US-007)), and `PATCH /api/v1/urls/{code}` (implemented (US-009)) |
| 7 | `GET` and `HEAD /*` (any single segment) | public (D32). Not narrowed to the code regex: US-008 AC6 requires 404, not 401, for malformed codes. Admits the redirect `GET`/`HEAD /{code}` (implemented (US-008)). A trailing-slash variant `/{code}/` falls to rule 8 |
| 8 | anything else | **`denyAll`** (D57): anonymous gets 401 through the entry point, authenticated gets 403 `ACCESS_DENIED`. Only explicitly listed paths can reach a handler, so case or path variants of a restricted prefix (for example `/API/...`) never fall through to a weaker rule |

Rule: no handler other than the redirect may be mapped to a single path segment, because rule 7 would make it public. No method security (`@EnableMethodSecurity`) is used.

**Operational notes (engineer-approved at US-005 G3):**
- `/error` is a **permitted single-segment GET handler**, reachable through the public `GET /*` rule. It is an allowed exception to the "no single-segment handler other than the redirect" rule, and is harmless while error details stay off (`server.error.include-*: never`). A direct `GET /error` answers **500** (Boot's error JSON), because there are no error attributes; it is recorded in US-008, and monitoring that counts 5xx should exclude it (US-014).
- `/swagger-ui.html` (springdoc's welcome redirect) is the only other permitted single-segment GET handler (implemented (US-008)). Any static resource at the root, such as `index.html` or `favicon.ico`, would be shadowed by `/{code}`.
- `org.springframework.security` must **never** be set to DEBUG or TRACE logging in shared environments, because at those levels Spring logs attempted usernames, which would break D52.

## Key design decisions

### Short-code generation
- **Recommendation:** `SecureRandom` Base62 codes (default length 7, configurable), inserted in a dedicated transaction per attempt; on a violation of `uk_short_url_short_code`, retry up to a configurable bound (default 5), then fail with 503.
- **Reason:** PostgreSQL aborts a transaction after a failed statement, so retries must run in fresh transactions. The UNIQUE constraint is the correctness guarantee under concurrency.
- **Alternative:** native `INSERT … ON CONFLICT (short_code) DO NOTHING RETURNING id`.
- **Trade-off:** `ON CONFLICT` avoids exceptions but bypasses JPA and ties the code to PostgreSQL; collisions are rare at 62⁷ ≈ 3.5 × 10¹², so readability wins.
- Custom aliases are never retried: a uniqueness violation returns 409.
- **Refinement** *(implemented (US-006); US-006 Design note §3)*:
  - `ShortUrlService.create` is not `@Transactional`. Each attempt runs in a `TransactionTemplate` with `PROPAGATION_REQUIRES_NEW`, built in the service constructor (never published as a bean, which would replace Boot's default `transactionTemplate`), and calls `saveAndFlush` on a fresh entity. `DataIntegrityViolationException` is caught outside the template, after rollback.
  - A generated code that fails `AliasPolicy.isValid` (D29, D48) consumes an attempt with no DB round trip.
  - Only a unique violation of `uk_short_url_short_code` counts as a collision. It is recognised by `repository/PostgresServerErrors`, which reads pgjdbc `ServerErrorMessage` SQLSTATE `23505` plus the protocol constraint field, because Hibernate's constraint name is parsed from locale-dependent message text. Any other violation is rethrown and becomes `500 INTERNAL_ERROR`.
  - Exhaustion after `shortener.code.max-attempts` gives `503 SHORT_CODE_UNAVAILABLE`, with no row committed.

### Analytics
- **Recommendation:** atomic `UPDATE … SET click_count = click_count + 1, last_accessed_at = :now` plus an insert into `click_event`, behind a `ClickRecorder` interface. Daily stats are computed with `GROUP BY` in the caller's time zone.
- **Reason:** exact counts, cheap total reads, no rollup table to keep consistent.
- **Alternative:** a `daily_click_stats` upsert table, or asynchronous events via Kafka/SQS.
- **Trade-off:** synchronous writes add latency to redirects and contend on the counter row for hot links. A production system would publish click events to a message broker and aggregate them asynchronously. Failures fail open (D12).
- **Seam** *(implemented (US-008))*: recording hooks into the one redirect handler, after a successful `RedirectService.resolve` and for GET only. `HttpMethod` is resolved as a handler argument, because Spring maps HEAD onto the GET mapping. The read-only resolution transaction has already closed, so a recorder failure cannot roll it back. The response builder and its headers stay as they are. US-010 defined the `ClickRecorder` signature, its transaction and the fail-open wrapper.
- **Recorder design** *(implemented (US-010))*:
  - `ClickRecorder.record(long shortUrlId, Instant clickedAt)` has one bean, `JpaClickRecorder`.
  - Fail-open lives in `RedirectService`, not in a decorator (D93). It then covers any implementation, including a future broker-backed one, and a `@Primary` replacement can never bypass it.
  - The atomicity unit is one `REQUIRES_NEW` transaction: a status-guarded counter UPDATE, then the event INSERT.
  - Concurrency: the atomic `+ 1` under READ COMMITTED loses no updates, and clicks on one link serialise on its row lock. A PATCH queued behind a click still matches `WHERE version = ?`, because clicks never bump `version`.
  - There is no lost-click metric until US-014: actuator exposes only `health`.

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

*Implemented (US-006); US-006 Design note §4:*
- **`api/error/GlobalExceptionHandler`** is the single `@RestControllerAdvice`. It extends `ResponseEntityExceptionHandler` and overrides `handleExceptionInternal`, so every Spring MVC exception body is rebuilt with `ProblemDetails.of`, and Spring's default `detail` texts never reach clients. Headers such as `Allow` and `Accept` are kept.
- **Mappings:**
  - `MethodArgumentNotValidException` → 400 `VALIDATION_FAILED`
  - `HttpMessageNotReadableException` → 400 `MALFORMED_REQUEST`, with no parser text
  - `InvalidUrlException` → 400 `INVALID_URL`
  - `InvalidAliasException` → 400 `INVALID_ALIAS`
  - `AliasAlreadyExistsException` → 409 `ALIAS_ALREADY_EXISTS`
  - `ShortCodeUnavailableException` → 503 `SHORT_CODE_UNAVAILABLE`
  - D31 extension (D61): 404 `RESOURCE_NOT_FOUND` for unmapped paths, 405 `METHOD_NOT_ALLOWED`, 406 `NOT_ACCEPTABLE`, 415 `UNSUPPORTED_MEDIA_TYPE`
  - Other framework 4xx → 400 `MALFORMED_REQUEST`
  - Anything else → 500 `INTERNAL_ERROR`, generic and logged once at ERROR. Spring Security exceptions are rethrown, not mapped.
- *Implemented (US-007):*
  - `ShortUrlNotFoundException` → 404 `SHORT_URL_NOT_FOUND` ("The short URL was not found."), base keys only.
  - It is distinct from `RESOURCE_NOT_FOUND`, which is used for unmapped paths such as `/api/v1/urls/{code}/`. Tests of the details endpoint must therefore assert the `errorCode`, never only the 404.
  - US-009 maps `ShortUrlDeletedException` to the same body (D46). *Implemented (US-009):* the exception is added to the **existing** handler's `@ExceptionHandler` list, so the body is byte-identical by construction.
- *Implemented (US-009):*
  - `ShortUrlAlreadyDeactivatedException` → 409 `SHORT_URL_ALREADY_DEACTIVATED`
  - `ShortUrlAlreadyActiveException` → 409 `SHORT_URL_ALREADY_ACTIVE`
  - `ShortUrlConcurrentModificationException` → 409 `CONCURRENT_MODIFICATION`

  All three carry the base keys only, with fixed `detail` texts, and nothing is logged by the advice. The advice has no handler for Spring's `OptimisticLockingFailureException`: the service translates it, as create translates `DataIntegrityViolationException`.
  - *Implemented (US-008):* the redirect throws the same exception for malformed, unknown, `DEACTIVATED` (D2) and `DELETED` codes. The handler is unchanged.
- **Field-level extension (D56):** `errors`, an array of `{field, message}` sorted by field, used only on `VALIDATION_FAILED`, `INVALID_URL` and `INVALID_ALIAS`. It never contains the rejected value.
- **Shape parity:** advice bodies are serialized by Spring MVC with the context `ObjectMapper`, the same one the security writer uses. So `errorCode` is top-level on both paths, and 401/403 carry the base keys only.
- **Still outside the advice:** `StrictHttpFirewall` rejections (plain 400) and errors raised in filters before the `DispatcherServlet` (Boot's `/error`).
- **406 and precedence (D70):**
  - 406 is raised at handler-mapping lookup (`ProducesRequestCondition`, then `RequestMappingInfoHandlerMapping.handleNoMatch`), never after the handler has run.
  - Error bodies stay `application/problem+json` even though the mapping produces `application/json`. `DispatcherServlet.processHandlerException` removes `HandlerMapping.PRODUCIBLE_MEDIA_TYPES_ATTRIBUTE` before the advice runs, so the `ProblemDetail` fallback applies (Spring 6.2.19 source).
  - Rules that follow from this: never add `application/problem+json` to `produces` (success bodies would be mislabelled), and never put `produces` on an `@ExceptionHandler` (it sets the attribute again and can empty the error body).
  - **Precedence: 401 > 405 > 415 > 406 > 400.** Security (401, and 403 per D57) runs in the filter chain first. `handleNoMatch` checks method (405), consumes (415), then produces (406). Body errors (400) and service errors (409, 503) need a matched handler.
  - **Known deviation from D61 (D70):** an **unparseable** `Accept` header still gets 406 and creates nothing, but the body is **empty**. Spring cannot negotiate a type for the error body either, and for a 4xx it drops the content.

### OpenAPI — *implemented (US-006)*
- `config/OpenApiConfig` declares `@OpenAPIDefinition` and one `@SecurityScheme` `basicAuth` (HTTP, `basic`).
- The requirement is applied **per controller** (`@SecurityRequirement` on `ShortUrlController`), not globally, so the public redirect (US-008) is never documented as secured.
- Error responses are declared on each operation with a documentation-only `Problem` schema (`api/error/ErrorResponseSchema`). springdoc skips advice handlers that have no `@ResponseStatus`, and it models `ProblemDetail` with a nested `properties` map.
- Every error `@Content` names `mediaType = "application/problem+json"` explicitly. Otherwise springdoc falls back to the mapping's `produces` (`application/json`, D70) and documents errors under the wrong type. `POST /api/v1/urls` documents 406 (D61, D70).
- The create operation states that IDN hosts are rejected and that clients must submit punycode (D49).
- *Implemented (US-007):* `GET /api/v1/urls/{code}` documents:
  - 200 (`application/json`), and 401, 404 and 406 (`application/problem+json`). 405 isn't documented, because it concerns other methods on the path.
  - Exactly one parameter, `code`.
  - The D71 use: after an unexpected 409 on create, a 200 confirms the alias is the caller's own.
- *Implemented (US-008):* `GET /{code}` is documented under the tag `Redirect`, with **no** security requirement:
  - one path parameter, `code`, with no `pattern`;
  - `302` with `Location` and `Cache-Control` headers and no content;
  - `404` as `application/problem+json` with the `Problem` schema.

  HEAD is described in the text, not listed as an operation.
- *Implemented (US-009):*
  - `PATCH /api/v1/urls/{code}` documents a request body (`application/json`, `UpdateShortUrlRequest`, `active` required), `200` (`application/json`), and `400`, `401`, `404`, `406`, `409` and `415` (problem+json).
  - `DELETE /api/v1/urls/{code}` documents exactly `204` (no content), `401`, `403`, `404`, `406` and `409`.
- `/v3/api-docs` stays at springdoc's default path and stays public (access-table rule 3).
