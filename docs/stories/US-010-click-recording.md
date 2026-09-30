---
id: US-010
title: Click recording on redirect
status: Done
plan_task: 9
depends_on: [US-001, US-002, US-008]
requirements: [FR-5, D8, D9, D12, D16, D18, D27, D32]
requires_design_approval: true
---

# US-010: Click recording on redirect

## User story
As the system, I want every successful GET redirect on an active link to be recorded atomically, so that accurate click analytics are available without slowing down or breaking the redirect itself.

## Acceptance criteria
- **AC1:** Given `{code}` belongs to an `ACTIVE` link, when `GET /{code}` is called, then `click_count` is incremented by exactly 1 via an atomic SQL update that does not touch `version` (D27), `last_accessed_at` is set to the current time (via the injected `Clock`), and a `click_event` row is inserted with `clicked_at` equal to that same time.
- **AC2:** Given the same request, when a `HEAD /{code}` is made instead, then no `click_count` increment and no `click_event` row occur (D9 — GET only; D18/D32 confirm `HEAD` is public and returns 302 like GET, but is never counted). Because Spring routes `HEAD` to the same handler mapping as `GET` by default, the redirect handler must explicitly check the HTTP method and skip invoking the `ClickRecorder` for `HEAD` — this is implementation behaviour, not incidental, and must be proven by asserting `click_count`/`click_event` are unchanged after a `HEAD` request, not merely that no exception occurred.
- **AC3:** Given the `click_event` insert (or counter update) fails for any reason (simulated via a test double that throws), when `GET /{code}` is called, then the redirect still returns `302` as normal (D12 — fail open), the failure is logged with the short code but never the full URL, and no exception escapes to the client.
- **AC4:** Given 50 concurrent `GET /{code}` requests against the same active link, when all complete, then `short_url.click_count` equals exactly 50 and `click_event` contains exactly 50 rows for that link — no lost updates.
- **AC5:** Given the `click_event` table (V2 migration), when its schema is inspected, then it stores only `id`, `short_url_id` (FK to `short_url`), and `clicked_at` — no column for IP address, user agent, or referrer (D8: click timestamp only).
- **AC6:** Given the click-recording `UPDATE` statement implemented in this story (D16, D27), when a row's `version` is read before and after a recorded click, then it is unchanged — proving management updates (US-009's `PATCH`, which does use `version`) can never be starved by redirect traffic. This is the second half of the split described in US-002's AC9: US-002 proves the entity mapping does not let a save clobber SQL-updated columns; this story proves the click `UPDATE` itself never bumps `version`.

## Tests required
| Type | Behaviour proven | Owner |
|---|---|---|
| Repository | V2 migration creates `click_event` with the FK, index, and default `clicked_at` as specified in `docs/architecture.md` | mid-engineer |
| Unit | `ClickRecorder` fail-open wrapper swallows and logs recorder failures without propagating (AC3) | mid-engineer |
| Repository | The click-recording `UPDATE` statement does not change `version` (AC6) | mid-engineer |
| Cucumber | `GET /{code}` on an active link increments the visible `clickCount` (verified via a subsequent `GET /api/v1/urls/{code}` as owner) (AC1) | qa-tester |
| Cucumber | `HEAD /{code}` on an active link leaves `clickCount` unchanged (AC2) | qa-tester |
| Integration (`*IT`) | Atomic counter update and `click_event` insert against Testcontainers PostgreSQL (AC1) | qa-tester |
| Integration (`*IT`) | `HEAD` does not record: `click_count` and `click_event` row count asserted unchanged directly against the database, not just "no error" (AC2) | qa-tester |
| Integration (`*IT`) | Click recording fails (forced via a test double) but the redirect still returns 302, and the failure is logged without the full URL (AC3) | qa-tester |
| Integration (`*IT`) | 50 parallel redirects on one code yield an exact count of 50 with no lost updates (AC4) | qa-tester |
| Repository | `click_event`'s schema contains only `id`, `short_url_id`, and `clicked_at` — no IP/user-agent/referrer column exists, proving D8 (AC5) | mid-engineer |

## Out of scope
- The stats query endpoint (`GET /api/v1/urls/{code}/stats`) — see US-011.

## Risks
- Synchronous analytics writes on the hot redirect path add latency and create row contention on `short_url.click_count` for popular links, as already flagged as a trade-off in `docs/architecture.md`. No mitigation is in scope here beyond what D12 already specifies (fail open).
- Logging discipline: it is easy to accidentally log the full `original_url` (which may contain tokens in a query string) when logging a click-recording failure. Review must check the log statement uses only the short code/ID.
- Transaction-boundary risk: if click recording runs inside the same database transaction as the redirect's lookup/read, a failed recording statement marks the transaction rollback-only under PostgreSQL (any subsequent statement in that transaction, including the one that would return the redirect, fails too), which would break fail-open (D12) instead of honoring it. Click recording therefore needs its own transaction boundary, separate from the redirect's read — a design-gate item for this story's design note; the design must specify how the `ClickRecorder` is invoked (e.g. `REQUIRES_NEW` propagation, or invoked after the redirect's transaction has already committed/closed) so a recording failure cannot roll back or block the redirect.

## Open questions
- None. D16/D27 fix the entity-mapping/atomic-update split with US-002 (AC6 above: the click `UPDATE` never touches `version`, and the entity never overwrites the analytics columns), and D18/D32 fix `HEAD`'s status and public access; this story only needs to implement and test the "not counted" half.

## Design inputs carried from US-002 (engineer-approved at US-002 G2)
- The click-recording `UPDATE` must **not** change `updated_at`. That column reflects management changes only.
- The click time must be taken from the injected `Clock` and **truncated to microseconds**, matching D45 and PostgreSQL's `timestamptz` resolution.
- Any read of the `ShortUrl` after the click `UPDATE` in the same persistence context must not see stale values. Use `@Modifying(clearAutomatically = true)` or run the click recording in its own transaction.

## Carry-over from US-009 (engineer-approved at US-009 G3)
- **N1 (NIT, mid-engineer):** in `ShortUrlServiceTest`, an ACTIVE fixture is still created at `NOW_MICROS`, so the `ACTIVE,true` case of `shouldRollBackWithoutFlushingWhenTheTransitionIsRedundant` can't fail on `updatedAt`. Create the fixture at an earlier instant.
- **N2, Javadoc half (NIT, mid-engineer):** the `JacksonConfig` Javadoc should say that `String`, `EmptyString`, `Integer` and `EmptyArray` are the shapes Jackson consults for Boolean, and that `Float` and `Array` are set only as a precaution.
- **Design input (US-009 review):** `NoTransactionalAnnotationIT` scans application beans, but it can't see annotations on repository **interface** methods, because the proxy target is `SimpleJpaRepository`. If the atomic click UPDATE uses `@Modifying` (and possibly `@Transactional`) on a repository method, it needs its own guard or test. That covers its transaction boundary, the D12 fail-open behaviour, and never touching `version` or `updated_at` (D16, D27).
- **Design input (controllable test Clock, engineer-approved at US-009 G3, from QA observation (b)):** AC1 needs an exact `clicked_at` == `Clock` time assertion in the ITs, which the real system clock cannot give. Add a controllable `Clock` bean (for example a mutable or offset clock) to the shared test configuration that `IntegrationTestBase` imports. Register it once and reset it per test and per Cucumber scenario. Production code keeps the injected `Clock` (D45). It must not create a second Spring context: no change in `IntegrationTestBase` subclasses, per the CLAUDE.md review rule.

## Design note
*(architect, 2026-09-30; **approved at G2 (2026-09-30)**, with Q1–Q7 and D-2 to D-9 approved as written. Q1/D-7 is recorded as D91, Q5/D-1 as D92, and D-5 as D93.)*

### 0. Summary and scope

A GET on an ACTIVE link is resolved exactly as in US-008. After the read-only transaction has closed, the service records the click through `ClickRecorder`. `JpaClickRecorder` runs, in one new transaction:

- one native `UPDATE short_url SET click_count = click_count + 1, last_accessed_at = :clickedAt WHERE id = :id AND status = 'ACTIVE'`;
- then, only if it changed one row, one `INSERT` into the new `click_event` table.

Both statements use the same microsecond-truncated `Clock` instant. `RedirectService` catches any recorder failure, logs it at WARN without the URL, and returns the target anyway (D12). HEAD never reaches the recorder (D9, D18). The status and headers of the 302 are unchanged (D75, D76).

| Area | Change |
|---|---|
| `db/migration/V2__create_click_event.sql` | **New**: table, FK, index (§1) |
| `domain/ClickEvent` | **New** immutable entity on `click_event` (§2.3) |
| `repository/ClickEventRepository` | **New** `JpaRepository<ClickEvent, Long>`, no custom methods |
| `repository/ShortUrlRepository` | **Adds** `recordClick(long id, Instant clickedAt)`, native `@Modifying` (§2.1) |
| `repository/PostgresServerErrors` | **Adds** `Optional<String> sqlState(Throwable)` (§4.3) |
| `analytics/ClickRecorder` | **New** interface (§4.1) |
| `analytics/JpaClickRecorder` | **New** `@Component`, owns a `REQUIRES_NEW` `TransactionTemplate` (§4.2) |
| `service/RedirectService` | **Adds** `resolveAndRecordClick(code)`, `ClickRecorder` + `Clock` injection, fail-open catch (§4.3, §5.1) |
| `api/RedirectController` | **Adds** an `HttpMethod` parameter and the GET/HEAD dispatch (§5.2) |
| Test support | `ShortUrlTestData.truncate()` (mandatory with V2), `TestClock` + configuration + reset (§7) |
| `SecurityConfig`, `GlobalExceptionHandler`, `ErrorCode`, OpenAPI | **Unchanged** |

There is no new error code and no new HTTP status. A recording failure is invisible to the client by design (D12).

### 1. V2 migration (`V2__create_click_event.sql`)

Exact SQL:

```sql
-- V2: click_event, one row per counted redirect (FR-5).
-- D8: the click timestamp only. There is no column for an IP address, user agent or referrer.
-- D45: the application always supplies clicked_at from its injected Clock, truncated to microseconds.
-- The DEFAULT now() serves raw SQL inserts only, as for short_url's timestamps in V1.
-- fk_click_event_short_url: every click belongs to an existing short_url row. Links are only ever
-- soft-deleted (D1), so the default NO ACTION rule never has to cascade and a hard delete of a
-- clicked link is refused.
-- ix_click_event_short_url_id_clicked_at serves the per-link, time-ranged daily stats query (FR-5,
-- D10) and the referencing-side lookups of the foreign key.
-- No CHECK on the encoded length of short_url.original_url is added here (D85).
CREATE TABLE click_event (
  id           BIGSERIAL     PRIMARY KEY,
  short_url_id BIGINT        NOT NULL,
  clicked_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
  CONSTRAINT fk_click_event_short_url FOREIGN KEY (short_url_id) REFERENCES short_url (id)
);

CREATE INDEX ix_click_event_short_url_id_clicked_at ON click_event (short_url_id, clicked_at);
```

- **Names (V1 conventions).**
  - The primary key is left unnamed, as in V1, so PostgreSQL names it `click_event_pkey` (V1's is `short_url_pkey`).
  - Named constraints use `<kind>_<table>_<what>`: `fk_click_event_short_url`.
  - The index is `ix_click_event_short_url_id_clicked_at`, named after its columns like `uk_short_url_short_code`. This replaces the sketch's `ix_click_event_url_time` (architecture updated).
  - The migration comment cites Dnn only, never story IDs or section numbers (CLAUDE.md review rule).
- **Index and US-011.** The stats query will be `WHERE short_url_id = :id AND clicked_at >= :from AND clicked_at < :to`, bucketed with `AT TIME ZONE`. It is an equality on the leading column plus a range on the second, which is a plain b-tree range scan. The index also covers `count(*)` per link. The FK needs no separate index on `short_url_id`, because the composite index leads with it.
- **Forward-only and backward-compatible.** V2 only creates a new table and index. The V1-era code never references `click_event`, so a V1 build running against a V2 schema is unaffected. `CREATE INDEX` on a new, empty table inside Flyway's transaction takes no lock that matters, so `CONCURRENTLY` isn't needed (and isn't allowed in a transaction).
- **`ddl-auto=validate`.** `ClickEvent` (§2.3) makes Hibernate validate the table at every context start.
- **`DEFAULT now()` on `clicked_at`: keep it** (recommendation D-1, Q5). Hibernate always writes `clicked_at` explicitly, because the column is mapped and non-nullable. An application insert never falls back to the default: a missing value would be sent as `NULL` and rejected by `NOT NULL`. So the default is only reachable from raw SQL, exactly the D45 rule for V1. It is kept for consistency and for raw-SQL fixtures. The story's schema test row also expects it.
- **No `octet_length` / encoded-length CHECK on `short_url` (D85).** V2 does not touch `short_url`. The existing `ShortUrlSchemaTest.shouldDeclareAllNamedConstraints` (`containsOnly`) already fails if one is ever added, so D85 stays pinned with no new test.
- **AC5 (D8)** asserts on `information_schema.columns` for `click_event`. There are exactly three columns, in order:

  | Column | Type | Nullable | Default |
  |---|---|---|---|
  | `id` | `bigint` | NO | `nextval('click_event_id_seq'::regclass)` |
  | `short_url_id` | `bigint` | NO | none |
  | `clicked_at` | `timestamp with time zone` | NO | `now()` |

  An exact list makes "no IP, user agent or referrer column" true by construction, with no deny-list. Also asserted:
  - the constraints (`contype IN ('p','u','c','f')`, excluding PG 18's `'n'` not-null entries, as `ShortUrlSchemaTest` does) are `containsOnly(click_event_pkey → p, fk_click_event_short_url → f)`;
  - `pg_get_constraintdef` of the FK is `FOREIGN KEY (short_url_id) REFERENCES short_url(id)`;
  - `pg_indexes.indexdef` of the index is exactly `CREATE INDEX ix_click_event_short_url_id_clicked_at ON public.click_event USING btree (short_url_id, clicked_at)`;
  - `flyway_schema_history` has version `2`, description `create click event`, `success = true`.

### 2. The atomic click UPDATE and the event INSERT

#### 2.1 Repository method

```java
// ShortUrlRepository
/** D16, D27: the only writer of click_count/last_accessed_at. Never touches version or updated_at. */
String RECORD_CLICK_SQL = "UPDATE short_url SET click_count = click_count + 1, last_accessed_at = :clickedAt"
        + " WHERE id = :id AND status = 'ACTIVE'";

@Modifying(flushAutomatically = true, clearAutomatically = true)
@Query(value = RECORD_CLICK_SQL, nativeQuery = true)
int recordClick(@Param("id") long id, @Param("clickedAt") Instant clickedAt);
```

- **`version` and `updated_at` are not in the statement** (D16, D27, the US-002 input). A native statement is sent verbatim, so no Hibernate versioning applies. Hibernate's HQL `update` also leaves `@Version` alone unless you write `update versioned`, but native SQL removes that question entirely (§11, D-2).
- **`:clickedAt`** is the `Clock` instant truncated to microseconds (D45). `JpaClickRecorder` truncates it once and passes the same value to the INSERT (AC1), so the two can never differ.
- **`AND status = 'ACTIVE'`** is the race guard (§6.2, Q1). A return value of `0` means "not counted", and the INSERT is skipped.
- **Return type `int`**: Spring Data's `ModifyingExecution` allows only `void`, `int`/`Integer` or `long`/`Long`, and returns the `executeUpdate()` count.
- **`@Param`** is explicit, so binding doesn't depend on the `-parameters` compiler flag.

#### 2.2 No stale `ShortUrl` after the UPDATE

Spring Data 3.5 `ModifyingExecution.doExecute` runs `em.flush()` if `flushAutomatically`, then `executeUpdate()`, then `em.clear()` if `clearAutomatically`. Three facts together mean no stale entity can be served:

1. **Own persistence context.** The recorder's `REQUIRES_NEW` transaction starts with no outer transaction. `open-in-view=false`, and `resolve`'s read-only transaction has closed its `EntityManager`. So `JpaTransactionManager` opens a fresh `EntityManager` that holds no `ShortUrl`.
2. **`clearAutomatically = true`.** If a future caller ever runs `recordClick` inside a transaction that already loaded the `ShortUrl`, the context is cleared after the UPDATE. The next `findById`/`findByShortCode` reloads from the database and sees the new `click_count`. (The Spring Data reference says the `EntityManager` "might contain outdated entities after the execution of the modifying query" and is not cleared by default.)
3. **`flushAutomatically = true`.** This makes the clear safe: pending changes are flushed before the UPDATE rather than silently discarded by the clear. A pending deactivation is therefore visible to the `status = 'ACTIVE'` predicate (repository test §9.1).

Both flags are cheap on an empty context. The guard (§3) pins both.

#### 2.3 `ClickEvent` entity and the INSERT

```java
@Entity @Table(name = "click_event") @Immutable            // org.hibernate.annotations.Immutable: append-only
@Getter @NoArgsConstructor(access = AccessLevel.PROTECTED)
@ToString(onlyExplicitlyIncluded = true)
public class ClickEvent {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) @ToString.Include private Long id;
    @Column(name = "short_url_id", nullable = false, updatable = false) @ToString.Include private long shortUrlId;
    @Column(name = "clicked_at", nullable = false, updatable = false) @ToString.Include private Instant clickedAt;

    /** clickedAt is truncated to microseconds (D45), idempotently. */
    public static ClickEvent of(long shortUrlId, Instant clickedAt) { ... }
}
```

- The entity has a plain `long shortUrlId` with **no `@ManyToOne`**. There is nothing lazy to load, nothing to `@ToString` by accident, and the FK is the integrity guarantee.
- It follows the CLAUDE.md entity rules: no setters, `@Data`, `@AllArgsConstructor` or `@EqualsAndHashCode`.
- The INSERT is `clickEventRepository.saveAndFlush(ClickEvent.of(id, at))`. With `IDENTITY`, Hibernate inserts at persist time anyway. `saveAndFlush` makes "the INSERT ran inside the callback" independent of the generation strategy, so a failure always surfaces before commit, inside the template.
- `ClickEventRepository` has no custom methods in US-010. US-011 adds read-only stats queries.

### 3. Guard for the repository-interface `@Modifying` method

`NoTransactionalAnnotationIT` checks bean target classes. For a repository, that target is `SimpleJpaRepository`, so interface methods are invisible to it. Add a **Surefire unit test, `repository/RepositoryAnnotationsTest`**, that uses plain reflection with no Spring context:

1. **Finds every repository interface.** It uses `ClassPathScanningCandidateComponentProvider(false)`, with `isCandidateComponent` overridden to accept interfaces and `AssignableTypeFilter(org.springframework.data.repository.Repository.class)`, under `com.schwab.urlshortener`. It asserts the set is exactly `{ShortUrlRepository, ClickEventRepository}`. That makes the scan non-vacuous, and it fails when a new repository appears, so the new one gets reviewed.
2. **`recordClick` has exactly the approved shape:**
   - it is `getDeclaredMethod("recordClick", long.class, Instant.class)` and returns `int`;
   - `@Modifying` has `flushAutomatically() == true` and `clearAutomatically() == true`;
   - `@Query` has `nativeQuery() == true`, and `value()` equals a **literal duplicated in the test**: `"UPDATE short_url SET click_count = click_count + 1, last_accessed_at = :clickedAt WHERE id = :id AND status = 'ACTIVE'"`. Any edit to the SQL, such as adding `version`, `updated_at` or removing the status guard, fails the build and needs review.
3. **No `@Transactional`** (Spring's or Jakarta's) on either interface or on any declared method of either interface, `recordClick` included. The recorder's template must own the transaction. Spring Data applies **no** transaction to declared query methods ("Declared query methods (including default methods) do not get any transaction configuration applied by default", Spring Data JPA 3.5 reference). An annotation would add an independent transaction boundary around the UPDATE alone. Under `REQUIRES_NEW` it would commit the counter without the event, so the two could split.
4. **No other `@Modifying` method** in either interface, and no `@Lock`.

**Runtime counterpart (`@RepositoryTest`):** `shouldRefuseToRunTheClickUpdateOutsideATransaction`. The test method is annotated `@Transactional(propagation = NOT_SUPPORTED)`. That is a test-method annotation, not a context-cache key, so no second context starts. It calls `recordClick` and expects an exception: an `InvalidDataAccessApiUsageException` whose cause is a `jakarta.persistence.TransactionRequiredException`. Spring's shared `EntityManager` refuses `executeUpdate` with no transaction. The implementer pins the exact observed types. This proves the UPDATE can never auto-commit on its own. It needs no seeded row and has nothing to clean up.

**Transaction attributes.**

| Place | Attribute |
|---|---|
| `recordClick` | none; it requires the caller's transaction |
| `JpaClickRecorder` template | `PROPAGATION_REQUIRES_NEW`, read-write, default isolation (PostgreSQL READ COMMITTED), no timeout |

Extend `NoTransactionalAnnotationIT`'s non-vacuity list to `contains("ShortUrlService", "RedirectService", "ShortUrlController", "JpaClickRecorder")`.

### 4. `ClickRecorder`, `JpaClickRecorder` and fail-open

#### 4.1 Interface (`analytics`)

```java
/**
 * Records one counted click (FR-5): GET only (D9), on a link resolved as ACTIVE. Implementations record atomically
 * or not at all, need no caller transaction, and may throw any RuntimeException: the caller fails open (D12).
 * A future broker-backed implementation (architecture: Analytics) replaces this bean, not the caller.
 */
public interface ClickRecorder {
    void record(long shortUrlId, Instant clickedAt);
}
```

It takes the id, not the code: the UPDATE uses the primary key, and the FK needs the id. It returns `void`, because "skipped because the link is no longer ACTIVE" is an implementation detail with no meaning for a broker-backed recorder.

#### 4.2 `JpaClickRecorder` (`analytics`, `@Component`)

```java
public JpaClickRecorder(ShortUrlRepository shortUrls, ClickEventRepository events, PlatformTransactionManager tm) {
    ...
    this.requiresNew = new TransactionTemplate(tm);           // never published as a bean (would replace Boot's)
    this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
}

@Override
public void record(long shortUrlId, Instant clickedAt) {
    Instant at = clickedAt.truncatedTo(ChronoUnit.MICROS);                       // D45, one value for both rows
    requiresNew.executeWithoutResult(status -> {
        if (shortUrls.recordClick(shortUrlId, at) == 1) {                        // row lock taken here
            events.saveAndFlush(ClickEvent.of(shortUrlId, at));
        } else {
            log.debug("Click not recorded: id={} reason=NOT_ACTIVE", shortUrlId);
        }
    });
}
```

- **One transaction, both statements (AC1 atomicity).** Any exception from either statement, or from the commit, makes the template roll back **both** and rethrow. The recorder never swallows; the caller does (§4.3).
- **UPDATE first, then INSERT**, for three reasons: the status guard decides before any event row exists; the INSERT's FK check runs against a row this transaction already holds; and there is only one row lock per transaction, always on the same row, so recorders cannot deadlock each other (§6).
- **Why `REQUIRES_NEW` rather than `REQUIRED`.** On today's path there is no outer transaction, so both start one physical transaction on one connection and behave identically. They differ only if someone later calls `record` inside a transaction:
  - `REQUIRED` would join it, and a failure would mark the outer transaction rollback-only. The outer commit would then throw `UnexpectedRollbackException` (Spring 6.2 reference, *Transaction Propagation*), which breaks D12.
  - `REQUIRES_NEW` keeps D12 intact and costs a second pooled connection. The Spring reference warns that a nested second connection needs a pool larger than the concurrent callers. That warning does not apply while recording runs after the read transaction closes: at most one connection is held per request at any time.
- **Logging:** DEBUG only, with the id and the reason. There is no INFO per click, because this is the hot path.

#### 4.3 Fail-open in `RedirectService` (not a decorator bean)

```java
public String resolveAndRecordClick(String code) {
    Resolved link = lookup(code);                          // read-only template, already committed and closed
    try {
        clickRecorder.record(link.id(), clock.instant());
    } catch (RuntimeException e) {                         // D12: the redirect never depends on analytics
        log.warn("Click not recorded: code={} id={} exception={} sqlState={}", code, link.id(),
                e.getClass().getSimpleName(), PostgresServerErrors.sqlState(e).orElse("none"));
    }
    return link.target();
}
```

- **What is logged.** WARN, with the code, the id, the exception's simple class name and the SQLSTATE. The SQLSTATE is read from pgjdbc's `ServerErrorMessage` through the new `PostgresServerErrors.sqlState(Throwable)`, which keeps `org.postgresql` imports in that one class (D65).
- **What is never logged.** The exception is **not** passed as a logger argument, so there is no stack trace. `getMessage()` is not logged either: a PL/pgSQL or CHECK message can carry row data, and D64 only strips the server *detail*. The URL and host are never logged. Only strings are passed.
- **What is caught.** Every `RuntimeException`, which covers every Spring `DataAccessException`, `TransactionException`, `CannotCreateTransactionException` (pool timeout) and the translated JPA exceptions. `Error`s such as `OutOfMemoryError` are deliberately not caught.
- **`clock.instant()` is inside the `try`.** A failing clock is still an analytics failure.

```
Recommendation: fail-open lives in RedirectService, around the recorder call; there is one ClickRecorder bean
                (JpaClickRecorder) and no decorator.
Reason:         D12 is redirect policy, so it belongs to the redirect's service, and it then covers every current
                and future ClickRecorder implementation. With a single bean of the type there is no @Primary or
                qualifier ambiguity: a test @Primary recorder (or a future broker recorder) can replace the
                implementation but can never remove fail-open, which the security-sensitive-bean rule is about.
Alternative:    a FailOpenClickRecorder decorator bean wrapping JpaClickRecorder, or the catch inside JpaClickRecorder.
Trade-off:      the decorator adds a second bean of the same type, which needs @Primary/@Qualifier, and a
                @Primary test double would silently bypass it. A catch inside the implementation must be repeated
                by every implementation. The chosen design means the story's "fail-open wrapper" unit test lives
                in RedirectServiceTest.
```

**Micrometer counter for lost clicks (Q4).** Recommendation: **defer to US-014.** Actuator exposes only `health` (D24 baseline), so a counter would have no reader today, and adding the metrics endpoint is new scope. The WARN line is the signal for now.

### 5. Integrating the US-008 seam

#### 5.1 `RedirectService`

- Constructor: `RedirectService(ShortUrlRepository, PlatformTransactionManager, ClickRecorder, Clock)`.
- `private record Resolved(long id, String target)` is returned by a private `lookup(code)`. That is the current body of `resolve`: the format check before any transaction (D72, D77), `findByShortCode` in the read-only template, and ACTIVE only (D2). The entity never leaves the service, and the record never leaves the class.
- `public String resolve(String code)` behaves exactly as today and is used for **HEAD**: `lookup(code).target()`, with no click.
- `public String resolveAndRecordClick(String code)` is used for **GET** (§4.3).
- `@Transactional` stays forbidden. The existing reflection guard adds `resolveAndRecordClick` to its non-vacuity names.

```
Recommendation: two intention-revealing service methods (resolve for HEAD, resolveAndRecordClick for GET),
                with recording inside the service.
Reason:         the service stays HTTP-agnostic (as US-008 recommended), the id never leaves the service, Clock
                and ClickRecorder live where the architecture diagram puts them, and existing HEAD tests and
                resolve() unit tests stay valid unchanged.
Alternative:    (a) resolve(code, boolean countClick), as sketched in US-008; (b) resolve returns a (target, id)
                record and the controller calls the recorder.
Trade-off:      (a) is one method but a boolean argument is opaque at the call site and changes every stub;
                (b) moves the database id and the fail-open policy into the api layer. The chosen design means
                every GET stub in the US-008 web slice changes (listed in §9.3).
```

#### 5.2 `RedirectController`

```java
ResponseEntity<Void> redirect(@PathVariable("code") String code, HttpMethod method) {
    String target = HttpMethod.GET.equals(method) ? service.resolveAndRecordClick(code) : service.resolve(code);
    return ResponseEntity.status(HttpStatus.FOUND)
            .header(HttpHeaders.LOCATION, LocationEncoder.encode(target))
            .cacheControl(CacheControl.noStore())                    // D76: exactly "no-store"
            .build();
}
```

- **`HttpMethod`**, not `HttpServletRequest`. `HttpMethod` is a supported handler argument ("The HTTP method of the request", Spring 6.2 *Method Arguments*). For HEAD on a `@GetMapping` it resolves to `HEAD` (US-008 §4). It exposes no query string, so the D79 intent of the reflection guard survives. springdoc ignores it (`PARAM_TYPES_TO_IGNORE`, verified in US-008), so `OpenApiDocsIT`'s single `code` parameter stays green.
- **Only GET counts:** the check is `HttpMethod.GET.equals(method)`, not "not HEAD" (D9). Only GET and HEAD reach this handler (rule 7), and HEAD takes `resolve` (D18, D32).
- **Timing.** Recording is synchronous, after resolution and before the 302 is built, as the architecture accepts. The latency cost is one connection checkout, one UPDATE, one INSERT and a commit. A 404 records nothing, because `lookup` throws first.
- **The response builder is unchanged byte for byte**, so every US-008 header pin keeps passing. The source-scan test's required `.header(HttpHeaders.LOCATION, LocationEncoder.encode(` text is kept. The Javadoc's "No recorder exists yet" becomes a description of the GET/HEAD dispatch (citing D9, D12). The OpenAPI description already says HEAD is never counted and does not change.

### 6. Concurrency

#### 6.1 AC4: 50 parallel GETs → exactly 50

- `click_count = click_count + 1` is evaluated by PostgreSQL on the latest committed row version. Under READ COMMITTED, a second updater waits for the first to commit, then re-applies its change to the updated version (PostgreSQL 18 docs, *Transaction Isolation*, Read Committed). So there are no lost updates, and each click transaction inserts exactly one event.
- Lock contention: every click on one link takes that row's `FOR NO KEY UPDATE` lock, from the UPDATE until commit (INSERT plus commit, about two round trips). Hot links serialise, which is the architecture's accepted trade-off. There are no deadlocks, because there is one row lock per transaction.
- The FK check's `FOR KEY SHARE` on the same row does not conflict with `FOR NO KEY UPDATE` (PostgreSQL 18 docs, *Explicit Locking*, table 13.3).
- **Pool:** each request holds at most one connection at a time (the read, then the recorder), so 50 requests against the default 10-connection Hikari pool queue but never deadlock.

#### 6.2 The status race (Q1)

The link is resolved as ACTIVE, then deactivated or deleted, and the change commits before the click UPDATE runs or while it waits on the row lock.

- **With `AND status = 'ACTIVE'` (recommended):** READ COMMITTED re-evaluates the `WHERE` on the committed row. The UPDATE matches 0 rows, so the INSERT is skipped. The visitor still gets the 302 that was already decided; the click is not counted. There is no WARN, because it is not a failure, only DEBUG.
- **Without the predicate:** the click is counted on a DEACTIVATED or DELETED row, and `click_event.clicked_at` can be later than `deleted_at`.

```
Recommendation: count a click only if the row is still ACTIVE when the click UPDATE applies (WHERE status = 'ACTIVE');
                0 rows means no event row. Record as a new decision (proposed D91).
Reason:         the database, not a Java check, then guarantees "clicks are recorded on ACTIVE links only" (the
                AC1 precondition), and no deleted row gains analytics after its deletion (D1 audit). The lost click
                is bounded to a milliseconds-wide window, the same kind of loss D12 already accepts.
Alternative:    no status predicate: every served 302 is counted.
Trade-off:      a redirect served in that window is not counted, so total served redirects can exceed click_count
                by a few around a deactivation. The alternative counts it, but lets DEACTIVATED/DELETED rows gain
                clicks after their state change.
```

This is product behaviour not in `requirements.md`, so it needs the engineer's decision. The guard test (§3), the repository test (§9.1) and QA's race test (§9.2 R2) all pin whichever is chosen.

#### 6.3 PATCH or DELETE racing clicks

- **PATCH:** the versioned `UPDATE … WHERE id = ? AND version = ?` and the click UPDATE queue on the same row lock. The click never changes `version`, so after a click commits, PATCH's re-evaluated predicate still matches: it returns 200, never 409 (D16, D27, the US-009 condition).
- **Click after PATCH:** its `status = 'ACTIVE'` predicate is re-evaluated. A deactivation drops the click (§6.2). A reactivation, or the same status, counts it.
- **No overwrite:** Hibernate's entity UPDATE never lists `click_count`/`last_accessed_at` (D27), so a PATCH never overwrites a click.
- **D90 is unchanged:** a click landing during a PATCH shows on the next GET.
- **Proof with the real statement:** QA's R1 (§9.2) holds the **real** `recordClick` inside a test-held transaction.

### 7. Controllable test `Clock`

```java
/** Test-only Clock: delegates to the production clock bean unless a test fixes the instant. Thread-safe. */
public final class TestClock extends Clock {
    private final Clock delegate;                                   // the production "clock" bean (systemUTC)
    private final AtomicReference<Instant> fixed = new AtomicReference<>();
    public void setInstant(Instant instant) { fixed.set(Objects.requireNonNull(instant)); }
    public void advance(Duration by) { fixed.updateAndGet(i -> Objects.requireNonNull(i, "not fixed").plus(by)); }
    public void reset() { fixed.set(null); }
    @Override public Instant instant() { Instant f = fixed.get(); return f != null ? f : delegate.instant(); }
    @Override public ZoneId getZone() { return delegate.getZone(); }
    @Override public Clock withZone(ZoneId zone) { throw new UnsupportedOperationException("not used by the app"); }
}
```

- **Registration.** The bean is `@Bean @Primary TestClock testClock(@Qualifier("clock") Clock production)` in a new `support/TestClockConfiguration` (`@TestConfiguration(proxyBeanMethods = false)`). `IntegrationTestBase` imports it next to `TestcontainersConfiguration` and `ShortCodeGeneratorTestConfiguration`.
  - The change is on the **base**, so every `*IT` and `CucumberSpringConfiguration` still has one `MergedContextConfiguration`: one context, one container. `SharedTestEnvironmentIT` keeps proving it.
  - It is `@Primary` rather than a replacement, because replacing `ClockConfig`'s `clock` bean needs bean-definition overriding, which Boot disables by default. Every production injection is by type (`Clock`), so the primary wins.
  - `@RepositoryTest` does not import it and doesn't need it: the repository method takes an `Instant`.
- **Reset: real time by default.** `reset()` restores delegation to the production `Clock.systemUTC()`, so tests that bracket server time with `Instant.now()` keep passing unchanged. Those are the `LifecycleIT` windows (lines 194, 265, 280, 363) and the create timestamp checks.
  - **JUnit:** `IntegrationTestBase` also gets `@ExtendWith(TestClockResetExtension.class)`, which resets before and after each test through `SpringExtension.getApplicationContext(ctx).getBean(TestClock.class)`. JUnit extensions are not part of Spring's context-cache key. This removes per-class discipline: a class that fixes the clock and fails midway can't leak fixed time into `LifecycleIT`.
  - **Cucumber:** `@Before(order = 0)`/`@After(order = 0)` in the existing `ShortCodeGeneratorHooks`, or in a sibling `TestClockHooks`.
- **Thread safety.** `AtomicReference` gives Tomcat request threads visibility of the test thread's `setInstant`. AC4 can run with real time or with one fixed instant (50 equal `clicked_at` values are legal). Serial test execution is assumed, as for the generator seam.
- **Ownership (Q3).** Recommendation: **qa-tester** owns `TestClock`, its configuration, the extension, the hooks and the `ShortUrlTestData` click helpers. Only ITs and Cucumber need exact time, because unit tests use `Clock.fixed` and repository tests pass `Instant`s. **Exception:** the **mid-engineer** changes `ShortUrlTestData.truncate()` to `TRUNCATE TABLE click_event, short_url` in the **same change as V2**. PostgreSQL's default `RESTRICT` refuses to truncate a table referenced by a foreign key unless all referencing tables are in the same command (PostgreSQL 18 docs, `TRUNCATE`). Without that change, every IT and Cucumber scenario fails at setup on the mid-engineer's first `./mvnw verify`. The tables are listed explicitly rather than using `CASCADE`, which would silently truncate any table added later.

### 8. AC3 failure injection (ITs, no context change)

```
Recommendation: a PL/pgSQL trigger created and dropped by the test itself: one variant fails the click_event
                INSERT, one fails the click UPDATE.
Reason:         it is a real failure path through pgjdbc, Hibernate, Spring translation and the TransactionTemplate
                rollback. The INSERT variant is the only way to prove the UPDATE is rolled back with it (no partial
                click), which a test double cannot show. It needs no bean, no @Primary seam and no context change.
Alternative:    (b) a scripted, failing @Primary ClickRecorder in the shared test config (like the generator seam);
                (c) REVOKE INSERT on click_event.
Trade-off:      DDL in a shared database must be cleaned up defensively (drop in @BeforeEach and @AfterEach). (b)
                never exercises the real rollback, and it adds a permanent test seam. (c) doesn't work, because
                the Testcontainers user owns the tables and is a superuser. The unit test (§9.1) still uses a
                Mockito double, which matches the AC's "test double" wording.
```

```sql
-- The messages deliberately carry the stored URL: if the app ever logged getMessage(), the IT would see it.
CREATE FUNCTION test_fail_click_insert() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'injected click failure for %', (SELECT original_url FROM short_url WHERE id = NEW.short_url_id);
END $$;
CREATE FUNCTION test_fail_click_update() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'injected click failure for %', NEW.original_url;
END $$;
-- INSERT variant:
CREATE TRIGGER test_fail_click BEFORE INSERT ON click_event
  FOR EACH ROW EXECUTE FUNCTION test_fail_click_insert();
-- UPDATE variant (fires only for statements whose SET lists click_count, so PATCH is unaffected):
CREATE TRIGGER test_fail_click BEFORE UPDATE OF click_count ON short_url
  FOR EACH ROW EXECUTE FUNCTION test_fail_click_update();
-- Cleanup, in @BeforeEach and @AfterEach (and in the Cucumber @Before and @After hooks):
DROP TRIGGER IF EXISTS test_fail_click ON click_event;
DROP TRIGGER IF EXISTS test_fail_click ON short_url;
DROP FUNCTION IF EXISTS test_fail_click_insert();
DROP FUNCTION IF EXISTS test_fail_click_update();
```

There are two functions because PL/pgSQL resolves `NEW` fields per table: `NEW.short_url_id` does not exist on a `short_url` row. `RAISE EXCEPTION` with no `ERRCODE` gives SQLSTATE `P0001`.

What the AC3 IT asserts:

- **The redirect is unchanged:** 302, the exact `Location`, `Cache-Control` exactly `no-store`, no `Pragma` or `Expires`.
- **No partial click:** `click_count` is unchanged and there are 0 `click_event` rows. In the INSERT variant, this proves the UPDATE was rolled back with the INSERT.
- **The log** contains `Click not recorded: code=<code>` and `sqlState=P0001` exactly once, at WARN.
- **Every form of the URL is absent from the log:**
  - the raw URL, its `LocationEncoder.encode` form, and a secret query token inside it (for example `?token=u010-secret`);
  - the trigger text `injected click failure`;
  - `"\tat "` (no stack trace) and `" ERROR "`.
- **Positive controls:**
  - the same test proves the value really is in the exception message: a direct JDBC insert into `click_event` under the trigger throws, and its message contains the token;
  - the capture proves it sees the WARN line.
- **Recovery:** after the trigger is dropped, the next GET is counted (1 and 1).

### 9. Tests: acceptance criteria, components and owners

#### 9.1 mid-engineer (Surefire)

| Test | Proves | AC / D |
|---|---|---|
| `repository/ClickEventSchemaTest` (`@RepositoryTest`) | Exact columns, types, nullability and defaults; `containsOnly` constraints; FK definition; exact index definition; Flyway V2 row (§1) | AC5, D8 |
| `repository/ClickEventConstraintsTest` | A raw `JdbcTemplate` INSERT with an unknown `short_url_id` gives SQLSTATE `23503` **and** constraint `fk_click_event_short_url` via `PostgresErrors`, one failing statement per test | integrity |
| `ShortUrlRepositoryTest` + `shouldNotChangeVersionOrUpdatedAtWhenAClickIsRecorded` | Seed at T0 and `recordClick(id, T1)` returns 1. `click_count` is 0→1 and `last_accessed_at` = T1 (the write happened), while `version` 0 and `updated_at` T0 are unchanged | AC6, D16, D27 |
| `…shouldIncrementRatherThanSet` | Two calls give `click_count` 2 and `last_accessed_at` = the second instant (distinct instants) | AC1 |
| `…shouldNotCountAClickOnADeactivatedOrDeletedRowOrAnUnknownId` | Returns 0 and the row is unchanged, with the ACTIVE row in the same test as positive control (returns 1) | Q1 |
| `…shouldNotServeAStaleEntityAfterRecordingAClick` | A loaded entity is detached after `recordClick` (`contains` is false), and a re-find returns the new count | US-002 input |
| `…shouldFlushPendingChangesBeforeTheClickUpdate` | Load, `deactivate(T2)` without flushing, then `recordClick` returns 0. The DB has `DEACTIVATED` and `version` 1 (flushAutomatically) | §2.2 |
| `…shouldRefuseToRunTheClickUpdateOutsideATransaction` | `NOT_SUPPORTED`: an exception, with no implicit transaction (§3) | §3 |
| `ClickEventRepository` save test | `saveAndFlush(ClickEvent.of(id, t))` round-trips `clicked_at` to the microsecond | AC1 |
| `repository/RepositoryAnnotationsTest` | The guard of §3 | §3 |
| `analytics/JpaClickRecorderTest` (mocks; real `TransactionTemplate` on a mock transaction manager) | `REQUIRES_NEW` and not read-only; UPDATE then INSERT with the **same** instant, truncated from one with nanoseconds (`…123456789Z` → `…123456Z`; PostgreSQL would round it to `…123457`); 0 rows means no INSERT and a commit; an INSERT failure means a rollback and the exception propagates | AC1, AC3 |
| `RedirectServiceTest` (existing, extended) | `resolveAndRecordClick` records `(id, clock.instant())` **after** the read commit (`InOrder`: commit, then record); `resolve` never touches the recorder; unknown, deactivated, deleted and malformed codes never record | AC1, AC2 |
| `RedirectServiceTest`, the fail-open test | The recorder throws a `DataIntegrityViolationException` whose message holds a URL marker, with a `PSQLException(new ServerErrorMessage("SERROR\0C23503\0M…marker…\0"))` cause. The target is still returned, with exactly one WARN event that has code, id, class and `sqlState=23503`, `getThrowableProxy()` null, and no marker or `https://`. A variant has no `PSQLException` in the chain (`sqlState=none`) | AC3, D12 |
| `RedirectControllerWebMvcTest` (new tests) | GET calls `resolveAndRecordClick` only, and HEAD calls `resolve` only (`verify` plus `never()`), through real Spring HEAD routing | AC2, D9 |
| `PostgresServerErrorsTest` + `sqlState` | Present, absent, and a cause chain | §4.3 |

Unit-test note: `ShortUrl.create` leaves `id` null. Service fixtures that go through `lookup` must set it with `ReflectionTestUtils`, or the `long` unboxing throws.

#### 9.2 qa-tester (Failsafe: `*IT` and Cucumber)

| Test | Proves | AC |
|---|---|---|
| `ClickRecordingIT` | Clock fixed at `2026-03-01T10:15:30.123456789Z`, then GET gives: 302 with unchanged headers; `click_count` 1; `last_accessed_at` = `…123456Z`; exactly one `click_event` row with that `short_url_id` and `clicked_at` equal to it; `version`/`updated_at` unchanged; the owner's `GET /api/v1/urls/{code}` shows `clickCount` 1 and `lastAccessedAt` `2026-03-01T10:15:30.123456Z`. A second GET at a later instant gives 2 and 2 rows with distinct times | AC1, D45 |
| same | HEAD three times leaves `click_count` and `click_event` unchanged (DB asserts), then a **same-path GET control** gives 1 and 1 | AC2, D9, D18 |
| same | DEACTIVATED, DELETED, unknown and malformed codes give 404 `SHORT_URL_NOT_FOUND`, with 0 rows and `click_count` unchanged. Invalid Basic credentials on an ACTIVE code give 401 and are not counted | AC1 precondition, D2, D55 |
| same | Headers are unchanged for GET: exact `Location` (ASCII and non-ASCII D75), exactly `no-store`; the query string is not forwarded and the click is counted once (D79) | D75, D76 |
| `ClickRecordingFailureIT` | §8, both trigger variants, positive controls, recovery | AC3, D12, D64 |
| `ClickRecordingConcurrencyIT` | 50 parallel GETs behind a barrier: all 302, `click_count` 50, 50 rows for the id, `version` unchanged, no WARN | AC4 |
| same, **R1** | A background thread holds the **real** `recordClick` in a `TransactionTemplate`. A PATCH deactivate blocks on the row lock (`pg_stat_activity`, query with `version`), then the click commits: PATCH returns 200, `version` N+1, `click_count` 1 is kept, no 409 | D16, D27, D35 |
| same, **R2** | A holder connection holds a raw deactivation (like `holdDeactivate`). A GET is fired, its recorder UPDATE blocks (`pg_stat_activity` query `ILIKE 'update short_url set click_count%'`), and the holder commits. The GET returns 302, with `click_count` 0 and 0 rows. If Q1 goes the other way, the expectation becomes 1 and 1. The soft-delete variant is the same | Q1 |
| `features/click-recording.feature` | AC1 (visible `clickCount`/`lastAccessedAt`), AC2 (HEAD then a GET control), AC3 (the trigger step, 302, count unchanged), AC4 (50 parallel, count 50), deactivated link not counted | AC1–AC4 |

The story table lists Cucumber only for AC1 and AC2. CLAUDE.md requires a scenario for **every** API acceptance criterion, so AC3 and AC4 get scenarios too (Q6). New `ShortUrlTestData` helpers: `shortUrlId(code)`, `clickEventCount(code)`, `clickedAts(code)` (ordered `Instant`s). Test isolation still comes from truncation in per-test and per-scenario setup only.

#### 9.3 Existing tests that change (Done stories: engineer approval needed, Q2)

US-008:
1. `RedirectControllerTest.shouldNeverDeclareProducesOnTheClassOrTheMethodOrInheritOne`: `getDeclaredMethod("redirect", String.class)` becomes `("redirect", String.class, HttpMethod.class)`. The assertions are unchanged.
2. `RedirectControllerTest.shouldCarryNoMetaAnnotationThatDeclaresProduces`: the same lookup change only.
3. `RedirectControllerTest.shouldTakeOnlyTheCodeAndReturnAResponseEntitySoTheQueryStringIsNeverReachable` is renamed `…TakeOnlyTheCodeAndTheHttpMethod…`. It asserts:
   - parameter types are exactly `[String.class, HttpMethod.class]` and the count is 2;
   - parameter 0 has exactly one annotation (`@PathVariable`), and parameter 1 has none;
   - the return type is `ResponseEntity`.

   Optionally the source scan adds `doesNotContain("HttpServletRequest")`.
4. `RedirectControllerWebMvcTest`: GET stubs and verifies move from `resolve(...)` to `resolveAndRecordClick(...)`. The affected tests are:
   - `shouldReturn302WithTheExactLocationAndNoStoreForAnonymousGet`
   - `shouldPercentEncodeNonAsciiCharactersInTheLocationHeader`
   - `shouldReturn302ForAValidAuthenticatedCallerToo`
   - `shouldIgnoreTheQueryStringOfTheShortLink`
   - `shouldReturn302ForAnyAcceptHeaderOnGetAndHead` (GET half; the HEAD half keeps `resolve`)
   - `shouldReturn404ProblemJsonWithSecurityDefaultCacheControlWhenTheServiceThrowsNotFound`
   - `shouldReturn404ProblemJsonForEveryParseableAcceptNeverNotAcceptable`
   - `shouldReturn404ProblemJsonWhenNoAcceptHeaderIsSent`
   - `shouldReturn404WithAnEmptyBodyForAnUnparseableAccept`
   - `shouldPassEveryMalformedSingleSegmentToTheServiceAndReturnItsNotFound`
   - `shouldReturn404ShortUrlNotFoundForAuthenticatedApiNeverA302`
   - `shouldReturn500LogOnceAtErrorAndNeverLogTheTargetWhenTheDatabaseFails`

   The HEAD-only tests are unchanged. The slice needs no new `@MockitoBean`, because the controller depends only on the already-mocked `RedirectService`.
5. `RedirectServiceTest`: the constructor gains `ClickRecorder` and `Clock` mocks, and the non-vacuity list gains `resolveAndRecordClick`. Every existing assertion is unchanged.
6. `RedirectIT.shouldWriteNothingOnGetOrHead` is replaced by `shouldWriteNothingOnHeadWhileTheSamePathGetIsCounted`. HEAD twice leaves `rowState` equal and 0 events. A GET then gives `click_count` 1 and 1 event, the positive control that replaces the old `seedClicks` one.
7. `redirect.feature`, the scenario "Following a link changes nothing in the stored row" (and its comment "until click counting in US-010"), becomes HEAD-only, plus a GET control that asserts `clickCount` 1.

US-009 / shared:

8. `NoTransactionalAnnotationIT`: the non-vacuity list adds `JpaClickRecorder`.
9. `ShortUrlTestData.truncate()` becomes `TRUNCATE TABLE click_event, short_url` (§7). This is shared support code; it is mandatory with V2.
10. `IntegrationTestBase`: an extra `@Import(TestClockConfiguration.class)` and `@ExtendWith(TestClockResetExtension.class)`. Its Javadoc is updated.

Unchanged but relevant:
- `ShortUrlSchemaTest` still pins that V2 adds no CHECK to `short_url` (D85).
- `FlywaySchemaHistoryIT` asserts no migration count (US-002 advice).
- `ShortCodeGeneratorWiringIT`'s `DELETE FROM short_url` is safe, because it never clicks.
- `LifecycleConcurrencyIT.holdClick` stays as the raw-statement control next to R1.

### 10. Carry-over from US-009 (mid-engineer)

- **N1:** in `ShortUrlServiceTest`, create the ACTIVE fixture at an instant **earlier** than `NOW_MICROS`, so the `ACTIVE,true` case of `shouldRollBackWithoutFlushingWhenTheTransitionIsRedundant` can fail on `updatedAt`.
- **N2 (Javadoc half):** the `JacksonConfig` Javadoc should state that `String`, `EmptyString`, `Integer` and `EmptyArray` are the input shapes Jackson consults for `Boolean`, and that `Float` and `Array` are set only as a precaution.

### 11. Decisions

```
D-1 Recommendation: keep clicked_at DEFAULT now() (raw SQL only).
Reason:         consistent with D45 and V1; Hibernate always sends clicked_at explicitly, so an application insert
                can never fall back to database time; the story's schema test row expects the default.
Alternative:    no default, so every writer, raw SQL included, must supply clicked_at.
Trade-off:      a raw-SQL fixture that forgets clicked_at gets transaction-start time, not the test Clock's time.
                The alternative would catch that, but diverges from V1's convention.
```
```
D-2 Recommendation: the click UPDATE is a native @Modifying(flushAutomatically = true, clearAutomatically = true)
                @Query on ShortUrlRepository, with no @Transactional.
Reason:         the exact SQL is visible and pinned by the guard; no Hibernate versioning or updatable=false
                handling is involved; @RepositoryTest can test it with no context change (the slice already
                scans repositories); clear/flush guarantee no stale entity for any future caller.
Alternative:    JPQL bulk update (does not bump @Version unless "update versioned", per the Hibernate 6 query
                guide); or JdbcTemplate inside JpaClickRecorder.
Trade-off:      the native SQL is PostgreSQL-flavoured (plain SQL, in practice portable) and bypasses entity
                mapping. The JPQL route depends on how Hibernate treats updatable=false columns in mutation
                queries. The JdbcTemplate route would need an extra @Import to test in the repository slice,
                which starts a second context.
```
```
D-3 Recommendation: ClickEvent entity + ClickEventRepository for the INSERT (saveAndFlush), not a second native
                @Modifying INSERT.
Reason:         ddl-auto=validate checks click_event at every start; only one @Modifying method needs guarding;
                US-011 gets a natural repository for its stats queries.
Alternative:    native "INSERT INTO click_event …" as a second @Modifying method on ShortUrlRepository.
Trade-off:      two small classes more. The alternative puts writes to another table on ShortUrlRepository, and the
                guard has two SQL literals to pin.
```
```
D-4 Recommendation: JpaClickRecorder uses a constructor-built REQUIRES_NEW TransactionTemplate; UPDATE first, the
                INSERT only if exactly one row changed.
Reason:         REQUIRED and REQUIRES_NEW behave the same with no outer transaction; REQUIRES_NEW keeps D12 even
                if a caller ever adds one; UPDATE-first lets the status guard decide before any event row exists.
Alternative:    REQUIRED; or INSERT first, then UPDATE, which shortens the row-lock hold by one round trip.
Trade-off:      REQUIRES_NEW would take a second connection if it were ever nested. The INSERT-first order needs
                setRollbackOnly when 0 rows are updated, which is more logic for a small gain on hot links.
```
```
D-5 Recommendation: fail-open in RedirectService (see §4.3); WARN with code, id, exception class and SQLSTATE only.
D-6 Recommendation: two service methods, resolve (HEAD) and resolveAndRecordClick (GET), selected by an HttpMethod
                handler argument (see §5.1).
D-7 Recommendation: status-guarded click (see §6.2); proposed D91, pending Q1.
D-8 Recommendation: an AC3 trigger in the ITs (see §8).
```
```
D-9 Recommendation: TestClock as a @Primary bean that delegates to the production clock, imported by
                IntegrationTestBase, and reset by a JUnit extension on the base and by a Cucumber hook.
Reason:         one context (the change is on the base only); real time by default, so existing window
                assertions are untouched; automatic reset removes the leak risk that per-class discipline carries.
Alternative:    reset explicitly in every class, like the generator seam; or override the ClockConfig bean.
Trade-off:      one more small support class (the extension). Overriding the bean would need
                allow-bean-definition-overriding, which is a context-wide setting.
```
```
D-10 Recommendation: no Micrometer lost-click counter in US-010; revisit in US-014.
Reason:         actuator exposes only health, so a counter would have no reader; exposing metrics is new scope.
Alternative:    a counter shortener.clicks.lost now.
Trade-off:      lost clicks are visible only as WARN lines until US-014.
```

If approved, D-1, D-5 and D-7 become numbered decisions in `requirements.md`, so that code comments can cite them (the CLAUDE.md "Dnn" rule). Code must never cite D-n or §n from this note.

### 12. Open questions for the engineer

- **Q1: Status race (product behaviour).** Is a click on a link that was deactivated or deleted after resolution, but before the click write, counted? Recommendation: **no**, using the `status = 'ACTIVE'` guard (§6.2). The 302 is still served.
- **Q2: Done-story test changes.** Approve the US-008 and shared test changes listed in §9.3 (items 1–10). Items 1–7 alter US-008's pinned behaviour ("GET writes nothing", "handler takes exactly one String").
- **Q3: Ownership.** Recommendation:
  - qa-tester owns `TestClock`, its configuration, the reset extension, the hooks and the click helpers;
  - the mid-engineer makes the one-line `truncate()` change together with V2, because without it the whole IT suite fails.
- **Q4: Micrometer lost-click counter.** Recommendation: defer to US-014 (D-10).
- **Q5: `DEFAULT now()` on `clicked_at`.** Recommendation: keep it (D-1).
- **Q6: Cucumber scope.** The story's table lists scenarios for AC1 and AC2 only. Recommendation: add AC3 and AC4 scenarios, per CLAUDE.md's "every API acceptance criterion has a Cucumber scenario".
- **Q7: AC3 wording.** The AC says "simulated via a test double". The unit test uses a Mockito double, and the IT uses a real database failure (a trigger), which is stronger. Confirm this satisfies AC3.

### 13. Risks for the implementer and the reviewer

- **Truncate breaks everything.** Forgetting `TRUNCATE TABLE click_event, short_url` makes every IT and Cucumber scenario fail in setup with "cannot truncate a table referenced in a foreign key constraint".
- **Logging leaks.** Watch for:
  - passing `e` as the last logger argument (it prints the stack trace and message, which with the AC3 trigger contains the URL);
  - logging `e.getMessage()`;
  - adding the target to the WARN.

  The reviewer checks the exact log statement and that the IT's "not logged" assertions have their positive controls.
- **A stray `@Transactional` on `recordClick` or the interface** splits the counter from the event. The guard catches it, but only if the SQL and annotation assertions stay exact.
- **Instant binding in a native query.** Hibernate 6.6 binds `Instant` as UTC. The repository round-trip test at microsecond precision must be present, or an off-by-zone bug could pass.
- **Truncation.** Truncate in `JpaClickRecorder` (and `ClickEvent.of`), never rely on PostgreSQL. PostgreSQL **rounds** sub-microsecond input, which breaks AC1's equality. The AC1 IT uses `…123456789Z` to catch it.
- **Isolation.** If anyone raises the recorder's isolation to REPEATABLE READ or SERIALIZABLE, the status race becomes a `40001` serialization failure. That is still fail-open, but it becomes a WARN instead of a silent skip.
- **Pool-timeout latency.** If the pool is exhausted, the recorder waits up to Hikari's `connectionTimeout` (30 s) before failing open, so the redirect is slow, not broken. A `lock_timeout`/statement timeout on the recorder is a candidate for US-014.
- **WARN volume.** A persistent recording failure logs one WARN per GET. That is accepted until US-014 (rate limit or counter).
- **Hot-link contention.** The row lock serialises clicks per link and makes PATCH wait behind queued clicks. This is the architecture's accepted trade-off, bounded by transaction length. Keep the recorder transaction to exactly two statements.
- **Trigger hygiene in the shared container.** A leaked trigger fails unrelated tests, so drop it in both setup and teardown.
- **Service fixtures** need an id on the `ShortUrl` (§9.1 note).
- **`RedirectControllerWebMvcTest`.** A GET test left stubbing `resolve` returns `null`, and `LocationEncoder.encode(null)` gives a 500. That is a confusing failure, not a silent pass, but review the whole class.

### 14. Sources checked (2026-09-30)

- Spring Data JPA 3.5 reference, *Transactionality*: CRUD methods inherit `SimpleJpaRepository`'s configuration; "Declared query methods (including default methods) do not get any transaction configuration applied by default".
- Spring Data JPA reference, *Modifying Queries*: the `EntityManager` may hold outdated entities and is not cleared automatically; `clearAutomatically`. `@Modifying` Javadoc: `flushAutomatically`/`clearAutomatically` default `false`.
- Spring Data JPA `3.5.x` source, `JpaQueryExecution.ModifyingExecution`: the return type must be `void`, `int`/`Integer` or `long`/`Long`; the order is flush → `executeUpdate()` → clear.
- Spring Framework 6.2 reference, *Transaction Propagation*: `REQUIRED` inner rollback-only leads to `UnexpectedRollbackException`; `REQUIRES_NEW` uses an independent physical transaction, with the connection-pool sizing warning.
- Spring Framework 6.2 reference, *Method Arguments*: `HttpMethod`, "The HTTP method of the request". HEAD via `@GetMapping` and springdoc ignoring `HttpMethod` were verified in US-008.
- Hibernate ORM 6 query language guide: an `update` does not affect the `@Version` column unless `update versioned` is used.
- PostgreSQL 18 docs:
  - *Transaction Isolation* (Read Committed): the second updater waits, then re-evaluates the `WHERE` on the updated row version;
  - *Explicit Locking*: `UPDATE` without key changes takes `FOR NO KEY UPDATE`, which does not conflict with `FOR KEY SHARE` (table 13.3);
  - `TRUNCATE`: refused on a table with foreign-key references unless all referencing tables are in the same command; `RESTRICT` is the default;
  - Release 18: NOT NULL constraints are now stored in `pg_constraint`, which is why schema tests filter `contype`.

## Implementation notes
*(mid-engineer, 2026-09-30)*

**Files changed**
- New, main: `db/migration/V2__create_click_event.sql` (the design's SQL, verbatim), `domain/ClickEvent`, `repository/ClickEventRepository`, `analytics/ClickRecorder`, `analytics/JpaClickRecorder`.
- Edited, main: `repository/ShortUrlRepository` (`RECORD_CLICK_SQL`, `recordClick`), `repository/PostgresServerErrors` (`sqlState`), `service/RedirectService` (`resolveAndRecordClick`, fail-open, constructor), `api/RedirectController` (`HttpMethod`, GET/HEAD dispatch, Javadoc), `config/JacksonConfig` (N2 Javadoc).
- New tests: `repository/ClickEventSchemaTest`, `repository/ClickEventConstraintsTest`, `repository/ClickEventRepositoryTest`, `repository/RepositoryAnnotationsTest`, `analytics/JpaClickRecorderTest`.
- Edited tests: `repository/ShortUrlRepositoryTest` (+6), `repository/PostgresServerErrorsTest` (+3), `service/RedirectServiceTest`, `api/RedirectControllerTest`, `api/RedirectControllerWebMvcTest`, `service/ShortUrlServiceTest` (N1), `support/ShortUrlTestData` (the one-line `TRUNCATE TABLE click_event, short_url`, in the same change as V2).
- Docs: `docs/stories/US-008-redirect.md` (new "Post-completion change" section, test by test).
- Not touched: `RedirectIT`, `redirect.feature`, `NoTransactionalAnnotationIT`, `IntegrationTestBase`, `TestClock` and its hooks, the click helpers, every other QA file.

**Decisions**
- Built as the approved design. No dependency was added.
- `RedirectServiceTest.stored` now sets the entity `id` (`ReflectionTestUtils`), as the design's unit-test note says, because `lookup` unboxes it. No existing assertion changed.
- N1: `ShortUrlServiceTest.stored` creates the fixture at `EARLIER` for every status. That made `shouldReturnTheViewToTheOwnerForActiveAndDeactivatedLinks` fail on `view.createdAt()` (it had been equal to the clock only by coincidence), so that one assertion now expects `EARLIER`, the fixture's creation instant.
- The fail-open unit test has positive controls: the exception chain is shown to contain the URL and the marker, and the WARN line is captured exactly. Absence is checked for the raw URL, its `LocationEncoder.encode` form (a non-ASCII URL, so the two differ), the marker, the host and `https://`.
- `shouldRefuseToRunTheClickUpdateOutsideATransaction` pins the observed types: `InvalidDataAccessApiUsageException` with root cause `jakarta.persistence.TransactionRequiredException`.

**Extra human review**
- `JpaClickRecorder` and the `@Modifying` SQL (the whole story rests on them).
- The log statement in `RedirectService.resolveAndRecordClick`: only strings are passed; no exception object, no `getMessage()`.
- The `JacksonConfig` Javadoc states the consulted shapes as the carry-over wording gives them; I did not re-derive that from Jackson's source.

**Test command and result**
`JAVA_HOME=/usr/local/opt/openjdk/libexec/openjdk.jdk/Contents/Home ./mvnw -q clean verify`: exit non-zero, only because of two QA-owned tests that US-010 changes by design (design section 9.3, items 6 and 7):
- `RedirectIT.shouldWriteNothingOnGetOrHead` (line 550): the row after GET twice is `click_count` 2, `last_accessed_at` set, and `version` and `updated_at` unchanged.
- `CucumberIT` scenario "Follow a short link - Following a link changes nothing in the stored row" (`redirect.feature:166`, step `RedirectSteps.theStoredRowIsUnchanged`): the same.
- Surefire: 881 run, 0 failed, 0 errors, 0 skipped. Failsafe: 611 run, 2 failed (the two above), 0 errors, 0 skipped.
- Merged JaCoCo LINE coverage, from `target/site/jacoco-merged/jacoco.xml`: 529 of 531 (99.62%). This report comes from the run above, which failed in `failsafe:verify`.

## QA notes
*(qa-tester, 2026-09-30)*

**Result: no defects found.** `JAVA_HOME=/usr/local/opt/openjdk/libexec/openjdk.jdk/Contents/Home ./mvnw -q verify` exits 0. From the reports: Surefire 881 run, 0 failures, 0 errors, 0 skipped. Failsafe 641 run, 0 failures, 0 errors, 0 skipped. That includes `CucumberIT` with 176 tests (9 of them are the new click-recording scenarios and examples) and the new `ClickRecordingIT` (11), `ClickRecordingFailureIT` (5) and `ClickRecordingConcurrencyIT` (5). The mid-engineer's two expected failures (`RedirectIT.shouldWriteNothingOnGetOrHead` and the redirect.feature row scenario) are re-pinned and pass.

**Support written (qa-tester):** `support/TestClock`, `TestClockConfiguration` (`@Primary`, delegates to the production `clock` bean), `TestClockResetExtension` (before and after every test, registered on `IntegrationTestBase`), `cucumber/TestClockHooks` (before and after every scenario), click helpers in `ShortUrlTestData` (`shortUrlId`, `clickEventCount(code)`, `clickEventCount()`, `clickedAts`). `SharedTestEnvironmentIT` still passes: one context, one container.

**Files:** `features/click-recording.feature`, `cucumber/ClickRecordingSteps`, `support/ClickRecordingIT`, `support/ClickRecordingFailureIT`, `support/ClickRecordingConcurrencyIT`; edited `RedirectIT`, `redirect.feature`, `RedirectSteps`, `GetShortUrlSteps`, `NoTransactionalAnnotationIT`, `IntegrationTestBase`, `ShortUrlTestData`. The US-008 changes are listed in `US-008-redirect.md`.

| AC | Proven by |
|---|---|
| AC1 (+1, `last_accessed_at`, one event, same truncated instant, `version`/`updated_at` unchanged) | `ClickRecordingIT.shouldCountOneClickAtTheTruncatedClockInstantAndLeaveVersionAndUpdatedAtAlone` (clock `…123456789Z`, expects `…123456Z`), `shouldIncrementRatherThanSet…`, `shouldCountOnTopOfExistingClicks…`; Cucumber "Following an active link records one click at the current time" and "A second click at a later time…"; `ClickRecordingConcurrencyIT` (version unchanged) |
| AC2 (HEAD not counted) | `ClickRecordingIT.shouldRecordNothingForHeadWhileAGetOnTheSamePathIsCounted` (DB asserts plus GET control); `RedirectIT.shouldWriteNothingOnHeadWhileTheSamePathGetIsCounted`; Cucumber "Sending HEAD for an active link records nothing…" and the redirect.feature row scenario |
| AC3 (fail open, logged without URL) | `ClickRecordingFailureIT`: INSERT variant and UPDATE variant (302, identical headers, no partial click, one WARN with code, id, class and `sqlState=P0001`, no raw or encoded URL, no token, no trigger text, no stack trace, no ERROR line), two positive controls that the URL is in the DB message, and a recovery test; Cucumber "A failing click event insert…" and "A failing click counter update…" |
| AC4 (50 concurrent) | `ClickRecordingConcurrencyIT.shouldCountExactlyFiftyClicksAndFiftyEventsForFiftyConcurrentGets` (barrier, no WARN); Cucumber "Fifty simultaneous visitors…" |
| AC5 (schema, D8) | Mid-engineer `ClickEventSchemaTest`; at IT level `ClickRecordingIT.shouldStoreOnlyIdShortUrlIdAndClickedAtEvenWhenTheClientSendsTrackingHeaders` (exact columns, tracking headers not stored) |
| AC6 (`version` unchanged) | Mid-engineer repository test; at IT level the AC1 test, the concurrency test, and the Cucumber AC1 scenario |
| D91 | `ClickRecordingIT.shouldAnswer404AndWriteNothingForDeactivatedDeletedUnknownAndMalformedCodes` (ACTIVE control); `ClickRecordingConcurrencyIT` R2: deactivate and soft-delete held while the GET waits at the click UPDATE (pg_stat_activity), 302 served, 0 counted, no WARN, plus a rollback twin that is counted; Cucumber deactivated/deleted outline and "A link deactivated through the API stops being counted" |
| D16, D27 (R1) | `ClickRecordingConcurrencyIT.shouldAnswer200AndKeepTheClickWhenAPatchWaitsBehindTheRealClickStatement`: the real `recordClick` held in a test transaction, the PATCH blocked on the row lock, then 200 (never 409), version N+1, the click kept |
| D75, D76, D79 | `ClickRecordingIT` header tests (exact `Location` ASCII and non-ASCII, exactly `no-store`, no `Pragma`/`Expires`, empty body, headers equal HEAD's, query string ignored and counted once) |

**Notes**
- The PATCH response body's `clickCount` (D90) is deliberately not asserted in R1: D90 says it shows the value read inside the PATCH transaction, which the test does not pin. The next GET showing the click is asserted through the database.
- The WARN line is found by prefix and checked for `WARN`, `id=`, `sqlState=P0001` and an `exception=<name>` token; the exception class name is not pinned, because it depends on Spring/Hibernate translation.
- Hibernate did not log the trigger message (no ERROR line), so the "no ERROR" assertion holds today with the default logging configuration.
- AC3 says "simulated via a test double". The IT uses a real database failure (Q7 of the design); the unit test uses the double. No ambiguity remains.
- Serial execution is assumed, as for the generator seam; the clock and the trigger are shared state.

## Review log
*(senior-engineer findings per round, and how each was resolved)*

| Round | ID | Severity | Finding | Resolution |
|---|---|---|---|---|
| 1 | R1 | - | Orchestrator item | Orchestrator |
| 1 | R2 | SHOULD | `ClickRecordingConcurrencyIT` Javadoc and comments used the design note's labels "R1:" and "R2:" | Fixed (qa-tester): now describe the scenarios ("the PATCH race", "the state race (D91)"). |
| 1 | R3 | SHOULD | Failure-trigger DDL and drop list duplicated in `ClickRecordingFailureIT` and `ClickRecordingSteps` | Fixed (qa-tester): `failClickInserts()`, `failClickUpdates()`, `dropClickFailures()` now live in `ShortUrlTestData`; both classes call them and still drop in setup and teardown. Positive controls intact. |
| 1 | R4 | - | The `sqlState` fallback | Awaiting engineer decision |
| 1 | R5 | SHOULD | Inline fully qualified names in `RedirectControllerTest` (`PathVariable`), `ShortUrlRepositoryTest` (`java.sql.Timestamp`) and `PostgresServerErrorsTest` (`ConstraintViolationException`, `SQLException`) | Fixed (mid-engineer): all imported. `jakarta.transaction.Transactional` stays qualified in `RepositoryAnnotationsTest` because it clashes with Spring's. `ClickRecordingFailureIT` left to QA; fixed (qa-tester): `Pattern` imported. |
| 1 | R6 | NIT | Lines over 120 characters in `RedirectServiceTest` (2) and `ClickEventConstraintsTest` (1) | Fixed (mid-engineer): wrapped. |
| 1 | R7 | NIT | Does `shouldFlushPendingChangesBeforeTheClickUpdate` fail without `flushAutomatically`? | Checked with a temporary local change, reverted. It **still passes** (Hibernate flushes before a native query inside a transaction). Renamed to `shouldLetTheStatusGuardSeeAPendingDeactivation` with a comment saying so. The flag itself stays pinned by `RepositoryAnnotationsTest`. |
| 1 | R8 | NIT | Annotation detection misses composed annotations | Fixed (mid-engineer): `RepositoryAnnotationsTest` uses `MergedAnnotations.from(element, SearchStrategy.TYPE_HIERARCHY).isPresent(...)` for `@Transactional` (Spring and Jakarta), `@Modifying` and `@Lock`. |
| 1 | R9 | - | `last_accessed_at` `GREATEST` | Awaiting engineer decision |
| 1 | R10 | NIT | Javadoc of `ShortCodeGeneratorHooks` and `ShortUrlTestData` mentions only `short_url` | Fixed (qa-tester): both now mention `click_event`. |
| 1 | R11 | NIT | `RedirectSteps.theStoredRowHasOneClick` takes an `int` | Fixed (qa-tester): renamed `theStoredRowHasClicks`. |
| 1 | R12 | NIT | `ClickRecorder.record` has no method Javadoc | Fixed (mid-engineer): added, with `@throws RuntimeException on any failure; the caller fails open (D12, D93)`. |
| 1 | R13 | NIT | N1 changed a Done-story (US-007) test without a record | Fixed (mid-engineer): "Post-completion change" entry added to `US-007-get-short-url-details-api.md`, test by test. |
| 1 | R14 | NIT | `stableHeaders` duplicated in `ClickRecordingIT` and `ClickRecordingFailureIT` | Fixed (qa-tester): moved to `ApiClient.stableHeaders`. |
| 2 | R1–R3, R5–R8, R10–R14 | — | Re-review of fix round 1 | **Resolved**. Verdict **APPROVE**. R3 verified: triggers are dropped in both setup and teardown for both callers, and the positive controls are intact. R8 verified: `MergedAnnotations` detection passes on the real repositories |
| 2 | R4 | SHOULD | `PostgresServerErrors.sqlState` logs `none` for client-side `PSQLException`s such as connection loss (SQLSTATE 08xxx) | **Open**: engineer decision. The reviewer recommends a fallback to `SQLException.getSQLState()`, with `isUniqueViolation` still reading the server message only |
| 2 | R9 | NIT | `last_accessed_at` can move backwards under contention, because the clock is read before the row lock | **Open**: engineer decision. The reviewer recommends `GREATEST`, which would be a new decision plus an AC1 wording change |
| 2 | N1 | NIT | `ShortUrlTestData.java:10` is over 120 characters after the R10 edit | Open |
| 2 | N2 | NIT | `ClickRecordingConcurrencyIT:42-44` has lowercase sentence starts after R2, and a long line | Open |
| 2 | N3 | NIT | `architecture.md` lines 342, 416 and 419 (missing space and duplicate sentence, stale tense, D93 not cited) | Fixed by the orchestrator |
| 2 | N4 | NIT | No positive control shows the `isTransactional` detector returns true for a composed `@Transactional` | Open |
| 2 | N5 | NIT | Three older private `stableHeaders` helpers remain alongside `ApiClient.stableHeaders` | Open (outside US-010 scope) |

**Orchestrator final verification (2026-09-30):**
- `./mvnw -q clean verify` passed (exit 0).
  - Surefire: 881 run, 0 failed.
  - Failsafe: 641 run, 0 failed (includes 176 Cucumber scenarios).
  - Merged LINE coverage: 529/531 (99.62%).
- The build log contains 0 occurrences of the trigger text or URL token, and 0 "Failing row contains".

### Proposed review rules (senior-engineer, US-010; for the engineer to decide)
Round 1:
1. "Test code that creates shared database objects (triggers, functions, roles) must define the DDL and its cleanup once, in shared test support. Every user calls that single definition, so cleanup can't drift from creation."
2. "Comments and Javadoc must not use design-note test labels (`R1`, `R2`, `§9.2`-style) as identifiers. Describe the scenario and cite Dnn. This extends the existing durable-ID rule, and avoids clashing with review-finding IDs."
3. "A runtime test that claims to prove a specific annotation flag (for example `flushAutomatically`) must be shown to fail without that flag, or be named for the behaviour it actually proves."

Round 2:

4. "When a fix mechanically replaces labels or identifiers in prose, re-read the surrounding sentences for capitalisation and line length. The replacement is only half the fix."
5. "Every reflection-based "no annotation X" guard test includes a positive control showing that its detector returns true for a sample that is directly annotated and for one that uses a composed annotation. Otherwise a broken detector passes vacuously."

**G3 (2026-09-30):** approved by the engineer. R4, R9 (D94), N1, N2, N4 and N5 are carried into US-011. When US-011 implements D94, it rewords this story's AC1 and changes its pinned SQL and tests, listing each test change here in a Post-completion section. Status: **Done**.
