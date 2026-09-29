# Architecture

Last updated: 2026-09-29 (US-001 design). **Status: planned.** Each section is marked *planned*, *designed (US-nnn)*, or *implemented (US-nnn)* as work progresses.

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

## Build and test infrastructure — *designed (US-001), approved at G2; not yet implemented*

The full detail and exact configuration are in the US-001 Design note. This section is the durable summary.

### Versions

Verified 2026-09-29 against the Spring Boot 3.5.16 BOM, Maven Central, and vendor release notes (sources are in the US-001 Design note).

| Component | Version | Source of version | Notes |
|---|---|---|---|
| Java | 25 | `java.version` | Boot 3.5.16 supports Java 17–25 |
| Spring Boot | 3.5.16 | parent | Final 3.5 release; OSS support ended 2026-06-30 (D22 risk) |
| Spring Framework | 6.2.19 | Boot-managed | |
| Hibernate ORM | 6.6.53.Final | Boot-managed | |
| Spring Security | 6.5.11 | Boot-managed | Added in US-005, not US-001 |
| Flyway | 11.7.2 | Boot-managed | `flyway-core` + `flyway-database-postgresql` |
| PostgreSQL JDBC | 42.7.11 | Boot-managed | |
| Lombok | 1.18.46 | Boot-managed | Needs `annotationProcessorPaths` on JDK 23+ |
| Testcontainers | 1.21.4 | Boot-managed | |
| Mockito / Byte Buddy | 5.17.0 / 1.17.8 | Boot-managed | |
| JUnit Jupiter / Platform | 5.12.2 / 1.12.2 (Boot) → **5.14.4 proposed** | Boot-managed, override pending **E1** | Cucumber 7.34.9 requires Platform ≥ 1.13 |
| springdoc-openapi | 2.8.17 | explicit | Built on Boot 3.5.x; 3.x is for Boot 4 |
| Cucumber | 7.34.9 | explicit (BOM) | |
| JaCoCo | 0.8.15 | explicit | Official Java 25 support since 0.8.14; gate placement pending **E2** |
| Maven | 3.9.16 | Maven Wrapper (`only-script`) | Enforcer: JDK `[25,)`, Maven `[3.9.16,)` |
| Surefire / Failsafe | 3.5.6 | Boot-managed | |
| PostgreSQL image | `postgres:18.6-alpine` | Compose (D23); Testcontainers pending **E3** | Volume mounted at `/var/lib/postgresql` |

### Build lifecycle (`./mvnw -q verify`)

```
validate            enforcer: JDK ≥ 25, Maven ≥ 3.9.16 (fails before compilation)
initialize          jacoco:prepare-agent → argLine (target/jacoco.exec); dependency:properties (Mockito agent path)
compile             javac --release 25, Lombok via annotationProcessorPaths
test                Surefire: **/*Test.java  (unit, @WebMvcTest, @RepositoryTest on Testcontainers)
pre-integration-test jacoco:prepare-agent-integration → argLine (target/jacoco-it.exec)
integration-test    Failsafe: **/*IT.java incl. CucumberIT (JUnit Platform suite → Cucumber engine)
post-integration-test jacoco:merge → jacoco-merged.exec; jacoco:report → target/site/jacoco-merged/
verify              failsafe:verify; jacoco:check BUNDLE LINE COVEREDRATIO ≥ 0.70, excluding UrlShortenerApplication only (D38)
```

Surefire and Failsafe strip `SPRING_PROFILES_ACTIVE` and `SPRING_DATASOURCE_*` from the forked test JVMs.

### Configuration and profiles (D24)

- `application.yml`: production baseline. No datasource entries; production supplies `SPRING_DATASOURCE_URL`/`USERNAME`/`PASSWORD`. Settings: `open-in-view=false`, `ddl-auto=validate`, Flyway on `classpath:db/migration`, actuator exposes `health` only (no details), error responses never include stack traces or messages.
- `application-local.yml`: localhost datasource with defaults matching `.env.example`. These are the only defaults in the codebase.
- `application-test.yml` (test classpath only): no datasource; the database comes solely from Testcontainers `@ServiceConnection`.
- No `prod` profile. `APP_BASE_URL` is bound when first used (US-004).
- `config/ClockConfig` provides `Clock.systemUTC()`.

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
   (qa-tester)    cucumber/CucumberIT (@Suite @IncludeEngines("cucumber") @SelectClasspathResource("features"))
```

- **One container per test JVM.** Every `*IT` class and Cucumber resolve to the same Spring context-cache key, so they share one context and one container. All `@RepositoryTest` classes share another context and container in the Surefire JVM.
- **Rule:** subclasses of `IntegrationTestBase` and `@RepositoryTest` classes add no context-affecting annotations (`@MockitoBean`, `@TestPropertySource`, extra `@Import`, `@DirtiesContext`, …). A controllable clock, when needed, is one shared `@Primary` bean in the shared test configuration.
- **HTTP client:** `TestRestTemplate`. No H2 on the classpath (D21). No Testcontainers reuse mode.

## Package structure (planned)

```
com.schwab.urlshortener
├── config/            # properties, Clock, OpenAPI
├── security/          # SecurityConfig, user details, 401/403 handlers
├── api/               # controllers
│   ├── dto/
│   └── error/         # GlobalExceptionHandler, ErrorCode
├── service/
├── domain/            # entities, enums
│   └── exception/
├── shortcode/         # ShortCodeGenerator + implementation
├── validation/        # UrlValidator, AliasPolicy
├── analytics/         # ClickRecorder + implementation
└── repository/
```

## Database schema (planned)

**V1 — short_url**

```sql
CREATE TABLE short_url (
  id               BIGSERIAL PRIMARY KEY,
  short_code       VARCHAR(32)   NOT NULL,
  original_url     VARCHAR(2048) NOT NULL,
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
  CONSTRAINT ck_short_url_deleted_consistency CHECK (
    (status = 'DELETED') = (deleted_at IS NOT NULL AND deleted_by IS NOT NULL))
);
```

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

## REST API (planned)

| Method | Path | Access | Success | Errors |
|---|---|---|---|---|
| GET | `/{code}` | Public | 302 | 404 (unknown / deleted / deactivated) |
| POST | `/api/v1/urls` | USER, ADMIN | 201 + `Location` | 400, 401, 409 |
| GET | `/api/v1/urls/{code}` | Owner, ADMIN | 200 | 401, 404 |
| PATCH | `/api/v1/urls/{code}` | Owner, ADMIN | 200 | 400, 401, 404, 409 |
| DELETE | `/api/v1/urls/{code}` | ADMIN | 204 | 401, 403, 404 |
| GET | `/api/v1/urls/{code}/stats?from&to&timezone` | Owner, ADMIN | 200 | 400, 401, 404 |

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

### Migrations
- **Recommendation:** Flyway. **Reason:** plain, reviewable SQL. **Alternative:** Liquibase. **Trade-off:** Liquibase's multi-database support and rollbacks aren't needed here.

### Error handling
RFC 7807 `ProblemDetail` responses with an `errorCode` extension, produced by a single `@RestControllerAdvice`. Stack traces and exception messages are never included; unexpected errors return a generic 500 with a request ID.
