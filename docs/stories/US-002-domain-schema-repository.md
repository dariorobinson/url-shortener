---
id: US-002
title: Domain model, V1 schema, and repository
status: Done
plan_task: 2
depends_on: [US-001]
requirements: [FR-1, FR-6, FR-10, D1, D6, D13, D26, D27]
requires_design_approval: true
---

# US-002: Domain model, V1 schema, and repository

## User story
As an engineer, I want the `short_url` table, its constraints, the `ShortUrl` entity, and its repository, so that later stories have a correct, constraint-backed persistence layer to build the API on.

## Acceptance criteria
- **AC1:** Given the V1 Flyway migration in `src/main/resources/db/migration/`, when it runs against PostgreSQL, then it creates `short_url` with exactly the columns, defaults, and constraints in `docs/architecture.md` (`uk_short_url_short_code`, `ck_short_url_status`, `ck_short_url_click_count`, `ck_short_url_code_format`, `ck_short_url_original_url_length` (D47), `ck_short_url_deleted_consistency`).
- **AC2:** Given a row with `short_code` that does not match `^[A-Za-z0-9]{3,32}$` (e.g. contains `-`, or is 2 characters), when inserted, then PostgreSQL rejects it with a constraint violation (`ck_short_url_code_format`).
- **AC3:** Given a row with `status = 'DELETED'` and `deleted_at` or `deleted_by` null, when inserted, then PostgreSQL rejects it (`ck_short_url_deleted_consistency`).
- **AC4:** Given a row with `click_count < 0`, when inserted, then PostgreSQL rejects it (`ck_short_url_click_count`).
- **AC5:** Given two rows with the same `short_code`, when the second is inserted, then PostgreSQL rejects it (`uk_short_url_short_code`), proving FR-10's correctness guarantee lives in the database.
- **AC6:** Given a `ShortUrl` entity in state `ACTIVE`, when `deactivate()` is called, then `status` becomes `DEACTIVATED`; given a `ShortUrl` entity already in state `DEACTIVATED`, when `deactivate()` is called again, then it throws a domain exception (`AlreadyDeactivatedException` or equivalent) rather than silently succeeding — this exception is mapped to `409 SHORT_URL_ALREADY_DEACTIVATED` at the HTTP layer in US-009 (D26). Symmetrically, given a `ShortUrl` entity in state `ACTIVE`, when `reactivate()` is called, then it throws a domain exception mapped to `409 SHORT_URL_ALREADY_ACTIVE` in US-009 (D26).
- **AC7:** Given a `ShortUrl` entity, when `softDelete(deletedBy, deletedAt)` is called, then `status` becomes `DELETED` and `deletedAt`/`deletedBy` are set consistently with AC3; calling it twice does not corrupt state.
- **AC8:** Given `ShortUrlRepository`, when querying by `shortCode`, then it returns the entity regardless of status (service-layer callers decide what to do with `DEACTIVATED`/`DELETED`; the repository itself applies no hidden filtering).
- **AC9:** Given `click_count` and `last_accessed_at` are mapped `insertable = false, updatable = false` on the entity (D27), when a `ShortUrl` row's `click_count` is changed by a direct SQL `UPDATE` (simulating US-010's atomic click recording) and the entity — loaded before that `UPDATE` — is subsequently mutated on an unrelated field and saved via the repository, then the entity's save does not overwrite the SQL-updated `click_count`/`last_accessed_at` values.

## Tests required
| Type | Behaviour proven | Owner |
|---|---|---|
| Repository | Each CHECK/UNIQUE constraint in AC2–AC5, run against real PostgreSQL via Testcontainers | mid-engineer |
| Unit | Entity state-transition methods (`deactivate()`, `reactivate()`, `softDelete()`) enforce consistent internal state, including the domain exceptions in AC6 | mid-engineer |
| Repository | `ShortUrlRepository` basic CRUD and lookup-by-code behaviour | mid-engineer |
| Repository | `insertable=false/updatable=false` mapping prevents an entity save from overwriting `click_count`/`last_accessed_at` set by a concurrent raw SQL `UPDATE` (AC9(a)) | mid-engineer |

Note: AC9 has two halves per D27. This story (US-002) proves half (a) — the entity-level mapping does not clobber a value already changed by SQL — because that is purely a repository/entity concern. Half (b) — that the click-recording `UPDATE` statement itself does not bump `version` — is proved in US-010, where that `UPDATE` statement is implemented; US-010's story references this split explicitly.

## Out of scope
- Service-layer logic, ownership enforcement, and API — see US-006/US-007.
- `click_event` table (V2) — see US-010.
- The HTTP-layer mapping of the AC6 domain exceptions to `409` responses — see US-009.
- The click-recording `UPDATE` statement itself — see US-010.

## Risks
- Getting the entity's state-transition method set wrong now (e.g. missing `reactivate()`) means every later story that touches lifecycle has to revisit this entity.

## Open questions
- None. D26 fixes AC6's behaviour (throws, mapped to 409 with a specific errorCode per state) and D27 fixes the entity-mapping approach (`insertable=false, updatable=false` on the analytics columns, click recording via a separate SQL `UPDATE` that does not touch `version`).

## Carry-over from US-001 (engineer-approved at G3)
- **R5 (qa-tester):** change `CucumberIT` from `@SelectClasspathResource("features")` to `@SelectPackages("features")` (the JUnit annotation name is plural; corrected per the US-002 design note). This is a one-line amendment to the US-001 design §8.4 and removes the Cucumber discovery warning.
- **N1 (mid-engineer):** declare `<skipTests>false</skipTests>` explicitly in `pom.xml` `<properties>`, so the `coverage.gate.skip` default no longer relies on an undefined property.
- **N2 (mid-engineer):** document the deliberate skip `-Dcoverage.gate.skip=true` in the `require-jacoco-merged-exec` enforcer message and in the README's "Running tests".

## Design note
*(architect, 2026-09-29. Status: **approved at G2 (2026-09-29)**, **amended at G3 (2026-09-29, D47)**. The engineer approved E1, E2, E3 and the rest of the note as written, recorded as D44–D46. E1 is applied: V1 uses the tightened CHECK from §3.2, and §8.4 includes the two extra rejection cases. AC6 wording is left unchanged at the engineer's direction. The D47 amendment below supersedes every `VARCHAR(32)`/`VARCHAR(2048)` reference for `short_code`/`original_url` in this note.)*

### Amendment (D47, US-002 G3)

**What changes.** `short_code` and `original_url` become `TEXT NOT NULL`. `ck_short_url_code_format` stays the only length and format limit on `short_code`. A new `ck_short_url_original_url_length CHECK (char_length(original_url) <= 2048)` limits `original_url`. Every other column and constraint is unchanged, including the D44 CHECK. The exact V1 is in §3.1.

**Why.** PostgreSQL rejects over-length input to a `VARCHAR(n)` column *unless the excess characters are all spaces, in which case it truncates silently* (PG 18 docs §8.3). The orchestrator reproduced this on `postgres:18.6-alpine`: 32 × `a` plus one space was stored as 32 characters. A `CHECK` on `TEXT` never coerces, so the named constraint sees the value exactly as submitted. This also resolves the US-002 implementation deviation and review item R5: a 33-character code is now rejected by `ck_short_url_code_format` with `23514`, not by the column type with `22001`, so AC2 holds as written.

**Verification (2026-09-29): no problems found.**

| Concern | Finding | Source |
|---|---|---|
| Hibernate 6.6 `ddl-auto=validate` for a `String` field against `text` | **Passes with no mapping change.** `AbstractSchemaValidator.validateColumnType` → `ColumnDefinitions.hasMatchingType`, which first checks `dialect.equivalentTypes(column.getSqlTypeCode(metadata), columnInformation.getTypeCode())`. `Column.getSqlTypeCode` returns the JDBC type code of the mapped type (`VARCHAR` = 12 for `String`), independent of `length`. `length` affects only the DDL type *name*. pgjdbc reports a `text` column as `Types.VARCHAR`. `Dialect.equivalentTypes` returns true on `typeCode1 == typeCode2`, and it also accepts any `VARCHAR`/`LONGVARCHAR`/`NVARCHAR`/`LONGNVARCHAR` pair through `SqlTypes.isVarcharType`. Neither `columnDefinition = "text"`, `@JdbcTypeCode(SqlTypes.LONGVARCHAR)`, nor a changed `length` is needed | hibernate-orm branch 6.6: `tool/schema/internal/AbstractSchemaValidator.java`, `tool/schema/internal/ColumnDefinitions.java`, `dialect/Dialect.java#equivalentTypes`, `type/SqlTypes.java#isVarcharType`, `mapping/Column.java#getSqlTypeCode`; pgjdbc REL42.7.11 `jdbc/TypeInfoCache.java` (`{"text", Oid.TEXT, Types.VARCHAR, …}`) |
| UNIQUE/B-tree on `TEXT short_code`: collation | Unchanged. `character varying` "acts as though it were a domain over `text`", so both use the same default collation and `text_ops`. The column has no explicit collation, and the image's database default is deterministic, so equality stays case-sensitive (D6), and `shouldTreatShortCodesCaseSensitively` / `shouldMatchShortCodeCaseSensitively` keep proving it | PG 18 docs §8.3 (https://www.postgresql.org/docs/18/datatype-character.html); PG 18 B-tree docs (https://www.postgresql.org/docs/18/btree.html: deduplication is safe with deterministic collations) |
| B-tree index key size limit | Not reachable. A B-tree entry must not exceed about one-third of a page. `ck_short_url_code_format` bounds `short_code` at 32 ASCII characters (32 bytes). `ExecInsert` runs `ExecConstraints` (CHECK/NOT NULL) **before** `table_tuple_insert` and `ExecInsertIndexTuples`, so an oversize value fails the CHECK with `23514` before it could reach the index (`54000`). `original_url` has no index | PG 18 B-tree docs (https://www.postgresql.org/docs/18/btree.html); PostgreSQL `REL_18_STABLE` `src/backend/executor/nodeModifyTable.c` (`ExecInsert`) |
| `findByShortCode` index use | Unchanged. pgjdbc's default `stringtype=VARCHAR` binds the parameter as `varchar`. `text = varchar` resolves to `text = text` (varchar is a domain-like alias of text), which is exactly the operator the `VARCHAR(32)` column used before, so `uk_short_url_short_code`'s index remains usable. Manual check below | pgjdbc docs, *Connection parameters* (`stringtype`); PG 18 docs §8.3 |
| Lookup performance | Same. There is no performance difference between `varchar(n)` and `text` "apart from … a few extra CPU cycles to check the length", and those cycles now go to the CHECK instead | PG 18 docs §8.3 |
| `char_length` vs a byte-length check | `char_length` counts characters (code points in the UTF8 server encoding). `octet_length` counts bytes (`char_length('josé')` = 4, `octet_length` = 5). `char_length` is the right function: it matches D11 ("max 2048 **chars**") and the former `VARCHAR(2048)` semantics. `2048` characters of multibyte text can exceed 2048 bytes, which is fine because `text` holds up to about 1 GB. **For US-004:** Java `String.length()` and Bean Validation `@Size` count UTF-16 code units, which is ≥ the code-point count, so an application check of `length() <= 2048` is never looser than the DB check. The application cannot accept a URL the DB rejects on length. It may reject a URL with supplementary characters (for example emoji) that the DB would accept. That is the safe direction | PG 18 docs §9.4 (`char_length`, `octet_length`); PG 18 docs §8.3 (≈1 GB limit) |
| NULL handling of the new CHECK | `char_length(NULL)` is NULL, and a CHECK passes on NULL. `NOT NULL` on the column covers that case, as before | PG 18 docs §5.5.1 |
| Constraint name length | `ck_short_url_original_url_length` is 32 characters, well under the 63-byte identifier limit | PG 18 docs §4.1.1 |

**AC impact.** No AC is invalidated.
- AC2 is now met fully by `ck_short_url_code_format`, including the 33-character case.
- AC1 says "exactly the … constraints in `docs/architecture.md`", and architecture.md now lists `ck_short_url_original_url_length`, so AC1 still holds. Its parenthetical list of five constraint names no longer names every constraint. That is a wording gap for the orchestrator/planner, and the architect does not edit ACs.

**Entity mapping.** No change required (see the first table row). Keep `@Column(length = 32)` on `shortCode` and `@Column(length = 2048)` on `originalUrl`. As §4.2 already says, `length` is documentation only and `validate` does not check it. It now documents the CHECK limit rather than a column type. Do **not** add `columnDefinition = "text"` or `@JdbcTypeCode`: they change nothing at runtime, and they would only add a second place that describes the type.

```
Recommendation: Leave the ShortUrl mapping unchanged (plain String, length = 32 / 2048 kept as documentation).
                Enforce the limits with char_length (characters), not octet_length (bytes).
Reason:         Hibernate 6.6 validate compares JDBC type codes, and text reports as VARCHAR, so validation
                passes as is. D11 limits URLs in characters, and char_length matches the old VARCHAR(2048)
                semantics.
Alternative:    columnDefinition = "text" or @JdbcTypeCode(SqlTypes.LONGVARCHAR) on both fields;
                octet_length(original_url) <= 2048.
Trade-off:      The annotations would only matter for Hibernate-generated DDL, which this project never uses
                (Flyway plus validate), so they add a second description of the type for no runtime effect.
                A byte limit would reject valid 2048-character URLs that contain multibyte characters,
                contradicting D11.
```

**V1 SQL.** §3.1 now holds the exact final file. The header comment cites only D44 and D47, per the durable-ID rule (review round 2, N1).

**Test changes (mid-engineer).**
1. `ShortUrlSchemaTest.shouldCreateShortUrlColumnsExactlyAsSpecified`: `short_code` → (`text`, `character_maximum_length` null, `NO`, null). `original_url` → (`text`, null, `NO`, null). All other rows are unchanged (§8.3 table updated).
2. `ShortUrlSchemaTest.shouldDeclareAllNamedConstraints`: add `ck_short_url_original_url_length:c`. That makes seven entries (§8.3).
3. `ShortUrlConstraintsTest`, short code:
   - Put `"a".repeat(33)` back into `invalidShortCodes()`. It is now rejected with `23514`, `ck_short_url_code_format`.
   - Delete `shouldRejectShortCodeLongerThanColumnLimit` and its escalation Javadoc. This resolves R5.
   - Add `shouldRejectMaxLengthShortCodeWithTrailingSpaceInsteadOfTruncating`: `"A1".repeat(16) + " "` (assert `hasSize(33)` first) → `23514`, `ck_short_url_code_format`. Rejection is the proof that nothing was truncated: the 32-character truncation would have passed the CHECK, which is exactly what the orchestrator reproduced on `VARCHAR(32)`.
4. `ShortUrlConstraintsTest`, original URL:
   - Extend the insert helper with an `originalUrl` parameter. The existing overloads keep passing `'https://example.com/'`.
   - Build URLs with a helper such as `urlOfLength(int length, char last)` = `"https://example.com/" + "a".repeat(length - 21) + last`. Every test asserts `hasSize(expected)` before inserting.
   - `shouldRejectOriginalUrlLongerThan2048Characters` (parameterized): 2049 characters ending in `a`, and 2049 characters ending in a space → `23514`, `ck_short_url_original_url_length`.
   - `shouldAcceptOriginalUrlAtLengthBoundary` (parameterized): exactly 2048 characters ending in `a`, and exactly 2048 characters ending in a space → the insert returns 1. Then `SELECT original_url, char_length(original_url) FROM short_url WHERE short_code = ?` returns the input **unchanged** (`isEqualTo(input)`) and `2048`. That proves there is no trimming or truncation. A follow-up SELECT is allowed here because nothing failed (§8.1).
   - `shouldMeasureOriginalUrlLengthInCharactersNotBytes`: `"https://example.com/" + "é".repeat(2028)` (2048 characters, 4076 UTF-8 bytes) → accepted. Also assert `octet_length(original_url) > 2048` in the follow-up SELECT. This pins `char_length` (D11 "chars") against a regression to `octet_length`.
5. `ShortUrlConstraintsTest` Javadoc `"E1: …"` → `"D44: …"` (review round 2, N1).
6. No change to `ShortUrlRepositoryTest`, `ShortUrlTest`, `PostgresErrors`, or `FlywaySchemaHistoryIT` (the migration description `create short url` is unchanged).
7. §12: AC2 also maps to the trailing-space test. AC1 also maps to the new constraint-name entry and the `original_url` length tests.

**Manual verification (implementer, run once, not committed; record in Implementation notes).** In the `@RepositoryTest` container or a throwaway `postgres:18.6-alpine`, after V1:
- Run `SET enable_seqscan = off; EXPLAIN SELECT * FROM short_url WHERE short_code = 'abc1234'::varchar;`. It must show `Index Scan using uk_short_url_short_code`. The seqscan switch is needed because the planner prefers a sequential scan on an empty table.
- Run `SELECT '…32 chars… '::text ~ '^[A-Za-z0-9]{3,32}$'` to confirm it is false.

**Risks introduced by the amendment.**
- **Flyway checksum.** Any database that has already applied the old V1 now fails Flyway validation at startup, for example a local `docker compose` volume from a `local`-profile run. Testcontainers databases are fresh, so they are unaffected. Recovery is `docker compose down -v`, not `flyway repair`, because the schema itself differs. V1 is uncommitted, so no shared environment is affected.
- **The same truncation behaviour remains on `created_by`/`deleted_by` (`VARCHAR(100)`).** A 100-character username plus trailing spaces would be stored truncated. It is not exploitable today: the values come from configured principals (US-005), not request bodies. D47 deliberately covers only the two request-supplied columns, so this is recorded as an open question, not changed. `status VARCHAR(16)` is safe: any truncated value keeps trailing spaces and fails `ck_short_url_status`.

### 0. Engineer decisions required at G2

| ID | Decision | Recommendation |
|---|---|---|
| **E1** | Tighten `ck_short_url_deleted_consistency` in V1 before it is committed (§3.2). The architecture SQL allows a non-deleted row with only one of `deleted_at`/`deleted_by` set. | **Yes, tighten it.** V1 is forward-only once committed, so this is the cheapest moment. If declined, V1 is exactly the architecture SQL (§3.1). |
| **E2** | The application sets `created_at`/`updated_at` from the injected `Clock` (passed as `Instant` into entity methods). The DB `DEFAULT now()` stays in the schema, but only raw SQL inserts use it (§5). | **Yes.** |
| **E3** | Every transition on a `DELETED` link, including a second `softDelete`, throws `ShortUrlDeletedException` and leaves state unchanged. US-009 maps it to `404 SHORT_URL_NOT_FOUND`, which is the outcome D13/D36 already fix (§4.6). | **Yes.** |

Everything else below is routine design and is approved or rejected along with the note as a whole.

### 1. Sources checked (2026-09-29)

| Claim | Source |
|---|---|
| Spring Data `save()`: when a non-primitive `@Version` property exists, the entity is new iff that property is `null`; new entities go to `persist`, others to `merge`. "JPA considers `0` (zero) as the first inserted version" | Spring Data JPA reference, *Entity persistence*: https://docs.spring.io/spring-data/jpa/reference/jpa/entity-persistence.html |
| Hibernate 6.6 seeds a `Long` version with `ZERO` and increments with `current + 1L` | `LongJavaType.seed/next`, hibernate-orm branch 6.6: https://github.com/hibernate/hibernate-orm/blob/6.6/hibernate-core/src/main/java/org/hibernate/type/descriptor/java/LongJavaType.java |
| `@Generated` values are "retrieved using a SQL `select` after it is generated"; default event is `INSERT`; `writable=false` by default. So only `@Generated` columns are re-read; plain `insertable=false` columns are not | Hibernate 6.6 Javadoc `org.hibernate.annotations.Generated`: https://docs.hibernate.org/orm/6.6/javadocs/org/hibernate/annotations/Generated.html |
| `@CurrentTimestamp` (behind `@CreationTimestamp`/`@UpdateTimestamp`): `source = VM` uses the JVM's current instant; default `source = DB` needs an extra round trip. Neither uses a Spring `Clock` bean | Hibernate 6.6 Javadoc `org.hibernate.annotations.CurrentTimestamp`: https://docs.hibernate.org/orm/6.6/javadocs/org/hibernate/annotations/CurrentTimestamp.html |
| `Instant` maps to `SqlTypes.TIMESTAMP_UTC` by default; `PostgreSQLDialect` renders `TIMESTAMP_UTC` as `TIMESTAMP_WITH_TIMEZONE` (`timestamptz`) | Hibernate 6.6 `MappingSettings.PREFERRED_INSTANT_JDBC_TYPE`: https://docs.hibernate.org/orm/6.6/javadocs/org/hibernate/cfg/MappingSettings.html; `PostgreSQLDialect.columnType`, branch 6.6 |
| `@Enumerated(STRING)` is stored as `VARCHAR` holding the constant name | Hibernate 6.6 User Guide §3.2.5 *Enums* |
| Without `@DynamicUpdate`, UPDATE statements include every updatable column. `@DynamicUpdate` restricts them to changed columns | Hibernate 6.6 Javadoc `org.hibernate.annotations.DynamicUpdate` |
| Hibernate 6.6 now throws `OptimisticLockException` when merging an entity that is definitely detached (generated id or non-primitive `@Version`) but has no matching row | Hibernate 6.6 migration guide, *Merge versioned entity when row is deleted*: https://docs.hibernate.org/orm/6.6/migration-guide/migration-guide.html |
| `@DataJpaTest` is transactional and rolls back each test. Its auto-configurations include `FlywayAutoConfiguration`, `JdbcTemplateAutoConfiguration`, `JdbcClientAutoConfiguration`, and `ServiceConnectionAutoConfiguration` | Boot 3.5.16 `AutoConfigureDataJpa.imports`: https://github.com/spring-projects/spring-boot/blob/v3.5.16/spring-boot-project/spring-boot-test-autoconfigure/src/main/resources/META-INF/spring/org.springframework.boot.test.autoconfigure.orm.jpa.AutoConfigureDataJpa.imports; `DataJpaTest` Javadoc |
| `JpaTransactionManager` lets plain JDBC code on the same `DataSource` join the JPA transaction, and autodetects the EMF's `DataSource`. So `JdbcTemplate` in a `@RepositoryTest` shares the test's transaction and connection | Spring Framework 6.2 Javadoc `JpaTransactionManager` |
| `timestamptz` resolution is 1 µs, stored in UTC. `now()` is the transaction's start time | PostgreSQL 18 docs §8.5: https://www.postgresql.org/docs/18/datatype-datetime.html |
| `~` is case-sensitive. Bracket ranges are "very collating-sequence-dependent". Without newline-sensitive mode, `$` matches only at end of string | PostgreSQL 18 docs §9.7.3: https://www.postgresql.org/docs/18/functions-matching.html |
| PostgreSQL 18 stores NOT NULL constraints in `pg_constraint` with `contype = 'n'` | PostgreSQL 18 docs, `pg_constraint`: https://www.postgresql.org/docs/18/catalog-pg-constraint.html |
| SQLSTATE `23505` unique_violation, `23514` check_violation, `23502` not_null_violation, `25P02` in_failed_sql_transaction | PostgreSQL 18 Appendix A |
| `ServerErrorMessage.getConstraint()` / `getSQLState()` | pgjdbc public API Javadoc: https://jdbc.postgresql.org/documentation/publicapi/org/postgresql/util/ServerErrorMessage.html |
| The JUnit selector is `org.junit.platform.suite.api.SelectPackages` (plural). `@Suite(failIfNoTests)` defaults to `true` (since 1.9). Cucumber's README suite example uses `@SelectPackages` | JUnit 5.14 API Javadoc (`SelectPackages`, `Suite`); cucumber-jvm `cucumber-junit-platform-engine/README.md` |

### 2. Files

| File | Action | Owner |
|---|---|---|
| `src/main/resources/db/migration/V1__create_short_url.sql` | create (§3) | mid-engineer |
| `src/main/resources/db/migration/.gitkeep` | delete (the directory is no longer empty) | mid-engineer |
| `src/main/java/com/schwab/urlshortener/domain/ShortUrlStatus.java` | create (§4.1) | mid-engineer |
| `src/main/java/com/schwab/urlshortener/domain/ShortUrl.java` | create (§4.2) | mid-engineer |
| `src/main/java/com/schwab/urlshortener/domain/exception/ShortUrlAlreadyDeactivatedException.java` | create (§4.7) | mid-engineer |
| `src/main/java/com/schwab/urlshortener/domain/exception/ShortUrlAlreadyActiveException.java` | create (§4.7) | mid-engineer |
| `src/main/java/com/schwab/urlshortener/domain/exception/ShortUrlDeletedException.java` | create (§4.7) | mid-engineer |
| `src/main/java/com/schwab/urlshortener/repository/ShortUrlRepository.java` | create (§6) | mid-engineer |
| `src/test/java/com/schwab/urlshortener/domain/ShortUrlTest.java` | create (§8.2) | mid-engineer |
| `src/test/java/com/schwab/urlshortener/repository/ShortUrlSchemaTest.java` | create (§8.3) | mid-engineer |
| `src/test/java/com/schwab/urlshortener/repository/ShortUrlConstraintsTest.java` | create (§8.4) | mid-engineer |
| `src/test/java/com/schwab/urlshortener/repository/ShortUrlRepositoryTest.java` | create (§8.5) | mid-engineer |
| `src/test/java/com/schwab/urlshortener/support/PostgresErrors.java` | create (§8.6) | mid-engineer |
| `pom.xml`, `README.md` | N1, N2 (§10) | mid-engineer |
| `src/test/java/com/schwab/urlshortener/cucumber/CucumberIT.java` | R5 (§10) | qa-tester |
| `src/test/java/com/schwab/urlshortener/support/FlywaySchemaHistoryIT.java` | stale Javadoc plus one assertion (§9) | qa-tester |

No new dependencies. No changes to `application*.yml`, `ClockConfig`, `TestcontainersConfiguration`, `RepositoryTest`, or `IntegrationTestBase`.

### 3. V1 migration

#### 3.1 `V1__create_short_url.sql` (exact final content: D44 applied at G2, D47 applied at G3)

This is the architecture.md SQL verbatim, with the leading comment. It replaces the file's current content byte for byte:

```sql
-- V1: short_url. Constraints are the final integrity guarantee (see docs/architecture.md).
-- ck_short_url_deleted_consistency (D44): a DELETED row must carry both deleted_at and
-- deleted_by, and any other row must carry neither.
-- short_code and original_url are TEXT (D47): VARCHAR(n) silently truncates over-length input
-- whose excess is only trailing spaces, so their length limits are CHECK constraints instead.
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

*(The G2 version of this section showed the pre-E1 `(status = 'DELETED') = (both set)` form and `VARCHAR(32)`/`VARCHAR(2048)`. Both are superseded, by D44 and D47 respectively.)*

`uk_short_url_short_code` creates the unique B-tree index that serves `findByShortCode`, so the redirect is a unique-index lookup (NFR Performance). The column is `TEXT`, and the index keys stay at most 32 bytes because `ck_short_url_code_format` is evaluated before index insertion (see the Amendment). No other index is needed in V1: there is no list-by-owner endpoint.

#### 3.2 Schema recommendations (not applied; the engineer decides)

**E1: tighten the deleted-consistency CHECK.**

```
Recommendation: Replace ck_short_url_deleted_consistency with
                  CONSTRAINT ck_short_url_deleted_consistency CHECK (
                    (status = 'DELETED' AND deleted_at IS NOT NULL AND deleted_by IS NOT NULL)
                    OR (status <> 'DELETED' AND deleted_at IS NULL AND deleted_by IS NULL))
                and update the architecture.md V1 block to match.
Reason:         The current form is `(status = 'DELETED') = (both set)`. For a non-deleted row the left side
                is false, and "only one of the two is set" also makes the right side false, so false = false
                passes. An ACTIVE row with deleted_at set and deleted_by NULL (or the reverse) is accepted.
                The entity can never produce that state, but "database constraints are the final guarantee"
                means raw SQL must not be able to either. The CHECK is NULL-safe because status is NOT NULL and
                IS [NOT] NULL never yields NULL.
Alternative:    Keep the architecture SQL. The entity already guarantees consistency, and AC3 (DELETED with a
                missing audit field is rejected) passes under both forms.
Trade-off:      Slightly longer SQL. Changing it later means a V-next migration (DROP CONSTRAINT / ADD
                CONSTRAINT, with a scan of the table). Changing it now costs nothing.
```

If E1 is approved, §8.4 adds two rejection cases: `ACTIVE` with only `deleted_at`, and `ACTIVE` with only `deleted_by`.

**Considered, recommend no change:**
- *`BIGSERIAL` vs `BIGINT GENERATED ALWAYS AS IDENTITY`.* Identity columns are PostgreSQL's modern form, but `BIGSERIAL` works with `GenerationType.IDENTITY`, matches the approved architecture, and its problems (manually supplied ids, sequence ownership) don't arise here.
- *`[A-Za-z0-9]` range collation.* PostgreSQL warns that bracket ranges depend on the collating sequence. Rather than rewriting the regex (an explicit 62-character list would remove the dependency but be unreadable), §8.4 proves on the real image that non-ASCII letters (`é`, `ß`) and a trailing newline are rejected. If those tests fail, escalate rather than rewrite the regex yourself.
- *Non-blank CHECKs on `created_by`/`deleted_by`.* The values come from the authenticated principal (US-005/US-009), which is never blank. Not worth a constraint now.
- *The defaults on `status`, `custom_alias`, `version`, `created_at`, and `updated_at`.* The application always supplies these values (§4.3), so the defaults only matter for raw SQL inserts (tests, manual operations). They are harmless and keep hand-written inserts short.

### 4. Domain model

#### 4.1 `domain/ShortUrlStatus`

```java
public enum ShortUrlStatus { ACTIVE, DEACTIVATED, DELETED }
```

The constant names are persisted (`@Enumerated(STRING)`) and must match `ck_short_url_status` exactly. Renaming a constant is a schema change.

#### 4.2 `domain/ShortUrl` (load-bearing: implement as specified)

```java
@Entity
@Table(name = "short_url")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@ToString(onlyExplicitlyIncluded = true)
public class ShortUrl {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @ToString.Include
    private Long id;

    @Column(name = "short_code", nullable = false, updatable = false, length = 32)
    @ToString.Include
    private String shortCode;

    @Column(name = "original_url", nullable = false, updatable = false, length = 2048)
    private String originalUrl;

    @Column(name = "custom_alias", nullable = false, updatable = false)
    private boolean customAlias;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    @ToString.Include
    private ShortUrlStatus status;

    /** D27: written only by the atomic click UPDATE (US-010). Omitted from INSERT, so the DB default 0 applies. */
    @Column(name = "click_count", nullable = false, insertable = false, updatable = false)
    private long clickCount;

    /** D27: written only by the atomic click UPDATE (US-010). */
    @Column(name = "last_accessed_at", insertable = false, updatable = false)
    private Instant lastAccessedAt;

    @Column(name = "created_by", nullable = false, updatable = false, length = 100)
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "deleted_by", length = 100)
    private String deletedBy;

    @Version
    @Column(name = "version", nullable = false)
    @ToString.Include
    private Long version;

    public static ShortUrl create(String shortCode, String originalUrl, boolean customAlias,
                                  String createdBy, Instant createdAt) {
        ShortUrl url = new ShortUrl();
        url.shortCode = Objects.requireNonNull(shortCode, "shortCode");
        url.originalUrl = Objects.requireNonNull(originalUrl, "originalUrl");
        url.customAlias = customAlias;
        url.createdBy = Objects.requireNonNull(createdBy, "createdBy");
        url.createdAt = toDbPrecision(Objects.requireNonNull(createdAt, "createdAt"));
        url.updatedAt = url.createdAt;
        url.status = ShortUrlStatus.ACTIVE;
        url.clickCount = 0L;        // mirrors DEFAULT 0 (column not inserted)
        url.lastAccessedAt = null;  // mirrors NULL (column not inserted)
        return url;                 // id == null, version == null → Spring Data treats it as new
    }

    public void deactivate(Instant at) {
        Instant now = toDbPrecision(Objects.requireNonNull(at, "at"));
        requireNotDeleted();
        if (status == ShortUrlStatus.DEACTIVATED) {
            throw new ShortUrlAlreadyDeactivatedException(shortCode);
        }
        status = ShortUrlStatus.DEACTIVATED;
        updatedAt = now;
    }

    public void reactivate(Instant at) {
        Instant now = toDbPrecision(Objects.requireNonNull(at, "at"));
        requireNotDeleted();
        if (status == ShortUrlStatus.ACTIVE) {
            throw new ShortUrlAlreadyActiveException(shortCode);
        }
        status = ShortUrlStatus.ACTIVE;
        updatedAt = now;
    }

    public void softDelete(String deletedBy, Instant deletedAt) {
        Objects.requireNonNull(deletedBy, "deletedBy");
        Instant now = toDbPrecision(Objects.requireNonNull(deletedAt, "deletedAt"));
        requireNotDeleted();
        status = ShortUrlStatus.DELETED;   // allowed from ACTIVE and DEACTIVATED (D36)
        this.deletedBy = deletedBy;
        this.deletedAt = now;
        updatedAt = now;
    }

    private void requireNotDeleted() {
        if (status == ShortUrlStatus.DELETED) {
            throw new ShortUrlDeletedException(shortCode);
        }
    }

    /** timestamptz has microsecond resolution; truncate so the in-memory value equals the stored value. */
    private static Instant toDbPrecision(Instant instant) {
        return instant.truncatedTo(ChronoUnit.MICROS);
    }
}
```

Mapping notes:
- **Column names are explicit.** Boot's default naming strategy would derive the same names, but explicit names keep the mapping independent of naming-strategy configuration.
- **`nullable` and `length` are documentation only.** Hibernate's `validate` does not check them (§8.3). The DB enforces them. After D47, `short_code` and `original_url` are `TEXT`. Their `length = 32` / `length = 2048` document the limits enforced by `ck_short_url_code_format` / `ck_short_url_original_url_length`. `validate` still passes because it compares JDBC type codes, and pgjdbc reports `text` as `VARCHAR` (see the Amendment). No `columnDefinition` or `@JdbcTypeCode` is needed.
- **Creation-time fields are `updatable = false`:** `short_code`, `original_url`, `custom_alias`, `created_by`, `created_at`. Codes are immutable and never reused (D1), no requirement allows editing the URL, and this keeps them out of every UPDATE. A future story that makes a field editable removes `updatable = false` in that story.
- **Do not add `@DynamicUpdate`.** With it, Hibernate would leave unchanged columns out of the UPDATE, and the AC9 test would pass even if `updatable = false` were missing. The test would become vacuous.
- **No setters.** Lombok `@Getter` generates `isCustomAlias()` for the `boolean` field. The class must not be `final` (Hibernate). Use `jakarta.persistence.*` imports. JPA uses the protected no-arg constructor.
- **Factory argument checks are null checks only.** Code format, URL rules, and reserved words belong to US-003/US-004. The DB constraints are the final guard, so duplicating the regex here would add a third copy to keep in sync.

#### 4.3 Column ownership

| Column | Written by | On INSERT | On entity UPDATE |
|---|---|---|---|
| `id` | DB sequence | omitted (IDENTITY), read back through generated keys | never |
| `short_code`, `original_url`, `custom_alias`, `created_by`, `created_at` | `ShortUrl.create` | from entity | never (`updatable = false`) |
| `status`, `updated_at` | `create` / transition methods | from entity | from entity |
| `deleted_at`, `deleted_by` | `softDelete` | from entity (NULL) | from entity |
| `version` | Hibernate | seed `0` (`LongJavaType.seed`) | `version + 1`, `WHERE version = ?` |
| `click_count`, `last_accessed_at` | US-010's atomic SQL `UPDATE` only (D27) | omitted, so DB default `0` / `NULL` | never (`updatable = false`) |

#### 4.4 D27: how a new entity gets `click_count = 0`, and what it reads back

- **INSERT.** Because the columns are `insertable = false`, Hibernate leaves `click_count` and `last_accessed_at` out of the INSERT column list. PostgreSQL applies `DEFAULT 0`, and `last_accessed_at` stays NULL.
- **After persist.** Hibernate re-reads a column after INSERT only when it is `@Generated`, using a follow-up `SELECT` (Javadoc, §1). It does not refresh plain `insertable = false` columns. The in-memory values are therefore whatever the factory set. The factory sets `0L`/`null`, the same values the database just stored, so the persisted entity is consistent without an extra round trip. US-006's create response can safely report `clickCount = 0`.
- **On load.** `insertable` and `updatable` affect only writes. Every `find*` reads both columns normally.
- **Staleness.** Within one persistence context, an entity loaded before a click UPDATE keeps its old `clickCount`. A JPQL/SQL bulk UPDATE does not refresh managed entities, and a later query in the same context returns the cached instance unchanged. US-007/US-011 read in their own request transaction, so this doesn't affect them. US-010 must use `@Modifying(clearAutomatically = true)` or its own transaction if it reads afterwards. That is a note for US-010, not this story.

#### 4.5 `@Version`

`Long` (wrapper), `null` until persisted. Spring Data's `save()` then detects a new entity from the version (not the id) and calls `persist`. Hibernate seeds `0` and writes it explicitly in the INSERT, so the DB `DEFAULT 0` is never used by the application. The two agree by construction. Each entity UPDATE increments the version and adds `WHERE version = ?`. A mismatch raises `ObjectOptimisticLockingFailureException` through the repository proxy, which US-009 maps to `409 CONCURRENT_MODIFICATION` (D35). A primitive `long` would break Spring Data's new-entity detection (§1), so don't use one.

#### 4.6 State transitions (AC6, AC7, D26, D36)

| From \ call | `deactivate(at)` | `reactivate(at)` | `softDelete(by, at)` |
|---|---|---|---|
| `ACTIVE` | → `DEACTIVATED`, `updatedAt = at` | throws `ShortUrlAlreadyActiveException` | → `DELETED`, `deletedBy = by`, `deletedAt = updatedAt = at` |
| `DEACTIVATED` | throws `ShortUrlAlreadyDeactivatedException` | → `ACTIVE`, `updatedAt = at` | → `DELETED` (D36 allows it) |
| `DELETED` | throws `ShortUrlDeletedException` | throws `ShortUrlDeletedException` | throws `ShortUrlDeletedException` |

The order of checks is fixed:
1. Argument null checks (`NullPointerException`). These are programming errors.
2. The `DELETED` check. A deleted link reports "deleted", not "already deactivated".
3. The redundant-transition check.
4. Mutation.

A throwing call mutates nothing: `status`, `updatedAt`, `deletedAt`, and `deletedBy` keep their prior values. `version` is never touched by entity code.

**AC7 "calling it twice does not corrupt state"** is met by the third column. The second `softDelete` throws and the first call's `deletedBy`/`deletedAt` survive, so the audit trail is intact. A silent no-op would also leave state intact. Throwing is recommended (**E3**) because D36 already fixes the product behaviour (`DELETE` on a deleted link → 404), and the exception makes that outcome hold even if a future service forgets its own `DELETED` check. The same reasoning covers `deactivate`/`reactivate` on a deleted link (D13/D36: PATCH on a deleted link → 404). No product behaviour is decided here that D13/D36 don't already fix.

#### 4.7 Domain exceptions (`domain.exception`) and their HTTP mapping (implemented in US-009)

| Exception | Thrown when | US-009 mapping (D26/D31) |
|---|---|---|
| `ShortUrlAlreadyDeactivatedException` | `deactivate` on `DEACTIVATED` | 409 `SHORT_URL_ALREADY_DEACTIVATED` |
| `ShortUrlAlreadyActiveException` | `reactivate` on `ACTIVE` | 409 `SHORT_URL_ALREADY_ACTIVE` |
| `ShortUrlDeletedException` | any transition on `DELETED` | 404 `SHORT_URL_NOT_FOUND` (D13/D36) |

- Each is a `public class … extends RuntimeException` with a `private final String shortCode`, a getter, and a constructor `(String shortCode)`. The message format is `"Short URL %s is already deactivated"` / `"… is already active"` / `"… is deleted"`.
- Messages contain only the short code, which is safe to log (CLAUDE.md logging rule). They are never exposed: the `@RestControllerAdvice` (US-005) uses a generic `detail`.
- No common base class. Each maps to a different `errorCode` and nothing needs to catch them as a group. Add a base class only if a concrete need appears.

#### 4.8 equals / hashCode / toString

```
Recommendation: Do not override equals/hashCode (Object identity). toString uses
                @ToString(onlyExplicitlyIncluded = true) with id, shortCode, status, and version only.
Reason:         Hibernate guarantees one instance per row within a persistence context, and entities never
                leave the service layer, so identity equality is correct everywhere they're used. Nothing puts
                ShortUrl into hash-based collections across contexts. toString must exclude originalUrl
                (query strings may hold tokens; CLAUDE.md logging rule) and createdBy/deletedBy (usernames are
                personal data). onlyExplicitlyIncluded fails safe: a field added later is excluded unless
                someone opts it in.
Alternative:    equals/hashCode on the business key shortCode, which is unique, immutable, and assigned before
                persist (so it's valid across the transient/managed/detached states). Or Lombok's default
                @ToString.
Trade-off:      Identity equality treats a detached copy and a managed copy of the same row as unequal. That's
                irrelevant under the layering rule. Default @ToString would leak the URL into any log line that
                prints the entity.
```

### 5. Time and Clock (E2)

```
Recommendation: The application owns created_at/updated_at. Callers pass an Instant taken from the
                injected Clock into ShortUrl.create(..., createdAt) and into every transition method.
                Each transition sets updatedAt = at. softDelete also sets deletedAt = at. The DB defaults
                now() stay in V1 but are used only by raw SQL inserts. Entity code truncates every Instant to
                microseconds.
Reason:         CLAUDE.md requires Clock and forbids Instant.now(). Service tests (US-006/US-009) can then
                assert exact timestamps with a fixed Clock, and entity unit tests need no clock at all because
                they pass Instants directly. Every application timestamp comes from one time source. US-010
                sets last_accessed_at/clicked_at from the same Clock, so created_at <= last_accessed_at can't
                be broken by skew between the app and DB clocks. Truncating to µs (timestamptz resolution)
                makes the in-memory value equal the stored value, so a create response and a later GET report
                the same createdAt and equality assertions don't flake on nanosecond clocks.
Alternative:    (a) DB defaults with insertable=false + @Generated on created_at/updated_at: needs an extra
                SELECT after each insert, uses now() (transaction start time, a different clock from the one
                US-010 uses), and updated_at would still need a trigger or app code on UPDATE.
                (b) Hibernate @CreationTimestamp/@UpdateTimestamp: source=VM reads the JVM clock and bypasses
                the Spring Clock bean. The default source=DB costs a round trip. Either way, tests can't
                control the time.
                (c) Truncate in the Clock bean (Clock.tick(Clock.systemUTC(), Duration.ofNanos(1_000))):
                covers US-010's raw SQL timestamps too, but a test's fixed Clock with nanoseconds would still
                mismatch, and it changes ClockConfig and ClockConfigTest.
Trade-off:      Every transition method takes an Instant parameter. AC6's "deactivate()" is shorthand for
                deactivate(Instant). The DB defaults for created_at/updated_at are effectively dead for
                application writes. Raw SQL writers (US-010) must not touch updated_at and must truncate their
                own Instants.
```

`updated_at` means "last management state change". Click recording (US-010) should not change it, just as it doesn't change `version`. That's a recommendation for the US-010 design; `updatedAt` is not in any API response shape (US-006/US-007).

### 6. Repository

```java
public interface ShortUrlRepository extends JpaRepository<ShortUrl, Long> {
    Optional<ShortUrl> findByShortCode(String shortCode);
}
```

- **`findByShortCode` filters nothing (AC8).** It returns the row in any status, and callers decide what to do with `DEACTIVATED`/`DELETED`. The derived query is `WHERE short_code = ?`. With the image's default deterministic collation, that comparison is case-sensitive, as D6 requires. It uses the `uk_short_url_short_code` index.
- **`JpaRepository`**, not `CrudRepository`, because US-006's per-attempt insert needs `saveAndFlush` to raise the uniqueness violation inside the attempt's own transaction.
- **No `existsByShortCode`.** The collision and alias-conflict guarantee is the unique constraint (FR-10). A pre-check is racy and would only add a friendlier error that US-006 gets from the constraint anyway. If US-006's design finds a concrete need, it adds the method.
- **No `@Lock`, no custom JPQL.** The click `UPDATE` is US-010.

### 7. Transactions, concurrency, error mapping

- **Transactions.** This story defines no service boundaries. `SimpleJpaRepository` supplies method-level transactions (read-only for finders). From US-006 onward, services own `@Transactional` boundaries, and entity mutations happen inside them through dirty checking. Calling `save()` on a managed entity is a harmless `merge` no-op. Never `save()` a hand-built entity that has an id or version set: Hibernate 6.6 throws `OptimisticLockException` if no such row exists (§1).
- **Concurrency.** Integrity under concurrency comes from three DB-level mechanisms, not from checks:
  - `uk_short_url_short_code`, for code collisions and duplicate aliases (FR-10, AC5).
  - `@Version`, for concurrent lifecycle edits (D35).
  - Analytics columns the entity cannot write (D27, AC9).
- **Error mapping.** No HTTP mapping in this story. For later stories:
  - Domain exceptions → §4.7.
  - `DataIntegrityViolationException` whose root `PSQLException` names `uk_short_url_short_code` → US-006: retry for a generated code, or `409 ALIAS_ALREADY_EXISTS` for a custom alias.
  - `ObjectOptimisticLockingFailureException` → US-009: `409 CONCURRENT_MODIFICATION`.
  - Any other constraint violation is a bug → `500 INTERNAL_ERROR` with no SQL details (US-005).

### 8. Tests (all mid-engineer, Surefire `*Test`)

#### 8.1 Rules for every repository test

- All repository test classes use `@RepositoryTest` and nothing else that affects the context: no `@Import`, `@MockitoBean`, `@TestPropertySource`, `@DirtiesContext`, or `@DataJpaTest(properties=…)`. They must share the single slice context and container with `PostgresRepositorySliceTest`. Inject `TestEntityManager`, `JdbcTemplate`, `ShortUrlRepository`, and `EntityManagerFactory` with `@Autowired` (fields, or `@Autowired` parameters, per the US-001 finding).
- **One failing statement per test, and it is the last DB action.** PostgreSQL aborts the transaction after an error (any later statement fails with `25P02`), and `@DataJpaTest` runs each test (each `@ParameterizedTest` invocation) in its own rolled-back transaction. Arrange first, then fail, then assert only on the exception.
- **Don't let the persistence context fool the test.** After arranging through JPA, call `saveAndFlush` (or `testEntityManager.flush()`) so the SQL reaches the DB, then `testEntityManager.clear()` before reloading. Otherwise the query returns the cached instance unchanged. Assert the database truth with `JdbcTemplate`, not with the entity you just wrote. `JdbcTemplate` joins the same transaction (§1), so it sees the unflushed-to-commit rows. Hibernate does not auto-flush before `JdbcTemplate` calls, so flush explicitly.
- **Timestamps.** Use fixed Instants with at most microsecond precision (e.g. `Instant.parse("2026-01-01T10:00:00Z")`). Bind them to `JdbcTemplate` as `OffsetDateTime.ofInstant(t, ZoneOffset.UTC)`, because pgjdbc's `setObject` supports `OffsetDateTime`, not `Instant`. Read them back as `rs.getObject("col", OffsetDateTime.class).toInstant()`, or `queryForObject(sql, OffsetDateTime.class, …)`.
- **Constraint assertions check the SQLSTATE and the constraint name**, through `PostgresErrors` (§8.6), never through message text. Expect `DataIntegrityViolationException` (Spring translates `23505` to `DuplicateKeyException`, a subclass).

#### 8.2 `domain/ShortUrlTest` (plain unit test, no Spring)

| Test | Proves |
|---|---|
| `shouldCreateActiveShortUrlWithCreationTimestamps` | factory state: `ACTIVE`, `clickCount 0`, `lastAccessedAt/deletedAt/deletedBy null`, `updatedAt == createdAt`, `id`/`version` null |
| `shouldTruncateTimestampsToMicroseconds` | `create` and each transition store `at.truncatedTo(MICROS)` (input with nanoseconds) |
| `shouldRejectNullArguments` | `create`, `deactivate`, `reactivate`, `softDelete` throw NPE on null arguments |
| `shouldDeactivateActiveShortUrl` | AC6: `ACTIVE → DEACTIVATED`, `updatedAt = at` |
| `shouldThrowAlreadyDeactivatedWhenDeactivatingDeactivatedShortUrl` | AC6/D26: exception type and `getShortCode()`; status and `updatedAt` unchanged |
| `shouldReactivateDeactivatedShortUrl` | `DEACTIVATED → ACTIVE`, `updatedAt = at` |
| `shouldThrowAlreadyActiveWhenReactivatingActiveShortUrl` | AC6/D26; state unchanged |
| `shouldSoftDeleteActiveShortUrl` | AC7: `DELETED`, `deletedBy`, `deletedAt = updatedAt = at` |
| `shouldSoftDeleteDeactivatedShortUrl` | D36 |
| `shouldKeepOriginalAuditFieldsWhenSoftDeletingTwice` | AC7: the second call throws `ShortUrlDeletedException`; first `deletedBy`/`deletedAt`/`updatedAt` preserved |
| `shouldThrowDeletedWhenDeactivatingOrReactivatingDeletedShortUrl` | D13/D36; state unchanged |
| `shouldExcludeOriginalUrlAndUsernamesFromToString` | `toString()` contains the short code and status, not the URL, `createdBy`, or `deletedBy` |

#### 8.3 `repository/ShortUrlSchemaTest` (`@RepositoryTest`), AC1 and `ddl-auto=validate`

- `shouldApplyV1MigrationSuccessfully`: `flyway_schema_history` has a row with `version = '1'`, `description = 'create short url'`, `success = true`. Don't assert a total migration count, because V2 arrives in US-010.
- `shouldCreateShortUrlColumnsExactlyAsSpecified`: query `information_schema.columns WHERE table_schema = 'public' AND table_name = 'short_url' ORDER BY ordinal_position` and assert exactly these 13 rows, no more:

  | column | data_type | char max length | is_nullable | column_default |
  |---|---|---|---|---|
  | id | bigint | – | NO | `nextval('short_url_id_seq'::regclass)` |
  | short_code | text | – (null) | NO | null |
  | original_url | text | – (null) | NO | null |
  | custom_alias | boolean | – | NO | `false` |
  | status | character varying | 16 | NO | `'ACTIVE'::character varying` |
  | click_count | bigint | – | NO | `0` |
  | last_accessed_at | timestamp with time zone | – | YES | null |
  | created_by | character varying | 100 | NO | null |
  | created_at | timestamp with time zone | – | NO | `now()` |
  | updated_at | timestamp with time zone | – | NO | `now()` |
  | deleted_at | timestamp with time zone | – | YES | null |
  | deleted_by | character varying | 100 | YES | null |
  | version | bigint | – | NO | `0` |

  The `column_default` values are PostgreSQL's normalized text. If PG 18 renders one differently, confirm that the difference is formatting only, and record it in the Implementation notes. Don't loosen the assertion silently.
- `shouldDeclareAllNamedConstraints`: `SELECT conname, contype FROM pg_constraint WHERE conrelid = 'public.short_url'::regclass AND contype IN ('p','u','c','f')` equals exactly `{short_url_pkey:p, uk_short_url_short_code:u, ck_short_url_status:c, ck_short_url_click_count:c, ck_short_url_code_format:c, ck_short_url_original_url_length:c, ck_short_url_deleted_consistency:c}` (seven entries, D47). The `contype` filter is required because PG 18 also lists NOT NULL constraints (`contype = 'n'`). Don't compare `pg_get_constraintdef` text (PostgreSQL rewrites expressions). §8.4 proves the semantics.
- `shouldValidateEntityMappingAgainstFlywaySchema`: assert `entityManagerFactory.getProperties().get("hibernate.hbm2ddl.auto")` equals `"validate"`. That proves the slice actually ran Hibernate validation (from `application.yml`; `@DataJpaTest` does not override an explicit value). The context starting at all proves validation passed. Note the limit: `validate` checks that tables and columns exist and that the types are compatible. It does not check nullability, length, defaults, or constraints. That's why the two tests above exist, and why AC1 isn't delegated to `validate`.

#### 8.4 `repository/ShortUrlConstraintsTest` (`@RepositoryTest`, raw SQL through `JdbcTemplate`), AC2–AC5, D1, D6

These tests insert with raw SQL so they hit the database constraint, not the entity. Use one private helper that inserts a valid row and lets each test vary one column:

```sql
INSERT INTO short_url (short_code, original_url, created_by, status, click_count, deleted_at, deleted_by)
VALUES (?, ?, 'alice', ?, ?, ?, ?)
```

Default arguments: `'abc1234'`, `'https://example.com/'`, `'ACTIVE'`, `0`, `null`, `null`. The `original_url` parameter was added by D47. The URL-length tests build their input as `"https://example.com/" + "a".repeat(length - 21) + last` and assert `hasSize(length)` before inserting.

| Test | Case(s) | Expected |
|---|---|---|
| `shouldRejectShortCodeNotMatchingFormat` (parameterized) | `ab` (2 chars), 33 chars (`"a".repeat(33)`), `abc-def`, `abc_def`, `abc def`, `abcé`, `straße`, `"abc\n"`, `""` | `23514`, `ck_short_url_code_format` (AC2). With `TEXT` (D47), 33 characters reach the CHECK. The old `22001` split test is deleted |
| `shouldRejectMaxLengthShortCodeWithTrailingSpaceInsteadOfTruncating` (D47) | `"A1".repeat(16) + " "` (assert `hasSize(33)`) | `23514`, `ck_short_url_code_format`. Under `VARCHAR(32)` this was silently stored truncated |
| `shouldAcceptShortCodeAtLengthBoundaries` (parameterized) | `abc`, 32 × `[A-Za-z0-9]` mix | insert succeeds (1 row) |
| `shouldRejectOriginalUrlLongerThan2048Characters` (parameterized, D47) | 2049 chars ending in `a`; 2049 chars ending in a space | `23514`, `ck_short_url_original_url_length` |
| `shouldAcceptOriginalUrlAtLengthBoundary` (parameterized, D47) | exactly 2048 chars ending in `a`; exactly 2048 chars ending in a space | insert returns 1. Then a SELECT returns `original_url` equal to the input and `char_length = 2048` (no trimming or truncation) |
| `shouldMeasureOriginalUrlLengthInCharactersNotBytes` (D47, D11) | `"https://example.com/" + "é".repeat(2028)` (2048 chars, 4076 bytes) | insert returns 1. Then `char_length = 2048` and `octet_length > 2048` |
| `shouldRejectDeletedRowWithoutCompleteAuditFields` (parameterized) | `DELETED` + (null, `admin`) / (t, null) / (null, null) | `23514`, `ck_short_url_deleted_consistency` (AC3) |
| `shouldRejectNonDeletedRowWithAuditFields` | `ACTIVE` + (t, `admin`) | `23514`, `ck_short_url_deleted_consistency` |
| `shouldAcceptDeletedRowWithCompleteAuditFields` | `DELETED` + (t, `admin`) | succeeds |
| *(only if E1 is approved)* `shouldRejectNonDeletedRowWithPartialAuditFields` (parameterized) | `ACTIVE` + (t, null) / (null, `admin`) | `23514`, `ck_short_url_deleted_consistency` |
| `shouldRejectNegativeClickCount` | `click_count = -1` | `23514`, `ck_short_url_click_count` (AC4) |
| `shouldRejectUnknownStatus` (parameterized) | `EXPIRED`, `active` | `23514`, `ck_short_url_status` (AC1 semantics) |
| `shouldRejectDuplicateShortCode` | insert `abc1234` twice | second: `23505`, `uk_short_url_short_code` (AC5) |
| `shouldNotReuseShortCodeOfSoftDeletedRow` | insert `DELETED` `abc1234` (complete audit), then `ACTIVE` `abc1234` | `23505`, `uk_short_url_short_code` (D1) |
| `shouldTreatShortCodesCaseSensitively` | insert `AbCd123` and `abcd123` | both succeed (D6) |

#### 8.5 `repository/ShortUrlRepositoryTest` (`@RepositoryTest`), CRUD, AC7, AC8, AC9

| Test | Steps | Asserts |
|---|---|---|
| `shouldPersistAndReloadAllFields` | `saveAndFlush(create(…T0))`, `clear()`, `findById` | every field round-trips: id non-null, `status ACTIVE`, `clickCount 0`, `lastAccessedAt null`, `createdAt == updatedAt == T0`, `deleted* null`, `version 0` |
| `shouldAssignVersionZeroOnInsertAndIncrementOnUpdate` | persist; then load, `deactivate(T1)`, flush | DB `version` 0 after insert, then 1. DB `updated_at = T1`, `status = DEACTIVATED` |
| `shouldNotWriteAnalyticsColumnsOnInsert` | `create(…)`, `ReflectionTestUtils.setField(e, "clickCount", 7L)` and `"lastAccessedAt", T1`, `saveAndFlush` | DB row `click_count = 0`, `last_accessed_at IS NULL`. Proves `insertable = false` (D27). The in-memory 7 is expected to be stale |
| `shouldNotOverwriteSqlUpdatedAnalyticsWhenEntityIsSaved` (**AC9**) | (1) `saveAndFlush(create(…T0))`, `clear()`. (2) `loaded = findByShortCode(code)`, which has `clickCount 0`. (3) `jdbc.update("UPDATE short_url SET click_count = 5, last_accessed_at = ? WHERE id = ?", T1, id)`. (4) `loaded.deactivate(T2)`; `repository.saveAndFlush(loaded)`. (5) Read the row with `JdbcTemplate`. (6) `clear()`, reload | (5): `click_count = 5`, `last_accessed_at = T1`, `status = DEACTIVATED`, `updated_at = T2`, **`version = 1`**. The version bump proves an UPDATE was actually issued, so the test isn't vacuous. (6): the reloaded entity shows `clickCount 5`, `lastAccessedAt T1` |
| `shouldPersistSoftDeleteWithinDeletedConsistencyCheck` (parameterized: from `ACTIVE`, from `DEACTIVATED`) | transition, `softDelete("admin", T2)`, flush, `clear()`, reload | flush succeeds (the entity's state satisfies `ck_short_url_deleted_consistency`), and `status/deletedBy/deletedAt` round-trip (AC7, D36) |
| `shouldFindByShortCodeRegardlessOfStatus` (parameterized over the three statuses) | persist and transition, flush, `clear()`, `findByShortCode` | present, with that status (AC8) |
| `shouldReturnEmptyForUnknownShortCode` | `findByShortCode("nope123")` | `Optional.empty()` |
| `shouldMatchShortCodeCaseSensitively` | persist `AbCd123` | `findByShortCode("abcd123")` is empty, `findByShortCode("AbCd123")` is present (D6) |
| `shouldRejectDuplicateShortCodeThroughRepository` | `saveAndFlush` two entities with the same code | `DataIntegrityViolationException`, constraint `uk_short_url_short_code`. The JPA path US-006 will rely on (AC5) |

AC9 scope: this proves half (a) only, as the story says. Half (b) (the click UPDATE never bumps `version`) is US-010.

#### 8.6 `support/PostgresErrors` (test helper)

`static Optional<ServerErrorMessage> serverError(Throwable t)` walks `getCause()` until it finds an `org.postgresql.util.PSQLException` (on the test classpath through the runtime-scoped driver) and returns `getServerErrorMessage()`. Two convenience methods, `sqlState(Throwable)` and `constraintName(Throwable)`, fail the test if no `PSQLException` is in the chain. It works for both paths: `JdbcTemplate` (`DataIntegrityViolationException` → `PSQLException`) and JPA (`DataIntegrityViolationException` → Hibernate `ConstraintViolationException` → `PSQLException`).

#### 8.7 Mutation checks (implementer runs once, not committed; record the result in the Implementation notes)

1. Remove `updatable = false` from `click_count`: `shouldNotOverwriteSqlUpdatedAnalyticsWhenEntityIsSaved` must fail (`click_count` reset to 0).
2. Remove `insertable = false`: `shouldNotWriteAnalyticsColumnsOnInsert` must fail (7 written).
3. Delete the `DELETED` guard in `softDelete`: `shouldKeepOriginalAuditFieldsWhenSoftDeletingTwice` must fail.

### 9. Impact on US-001 tests, and the QA step

- **No US-001 test asserts zero migrations.**
  - `FlywaySchemaHistoryIT` asserts only that `flyway_schema_history` exists. That stays true, but its Javadoc ("even with zero application migrations at this point") becomes stale.
  - `PostgresRepositorySliceTest`, `TestProfileDatasourceIT`, `SharedTestEnvironmentIT`, `HealthEndpointIT`, `OpenApiDocsIT`, `JvmAgent*`, `TestcontainersImageTest`, and the Cucumber smoke features don't touch the schema.
- **New implicit coverage.** From now on, every `*IT` and Cucumber context runs Flyway V1 and then Hibernate `validate` against `ShortUrl` at startup. A mapping error fails the whole Failsafe run, not just the repository tests. That is the intended guard, and reviewers should read a mass `*IT` failure that way.
- **The QA step is not a no-op, but it is small.** It must not duplicate the mid-engineer's repository tests. There is no API AC in this story, so no Cucumber scenario is required. Recommended QA work:
  1. R5 (§10).
  2. In `FlywaySchemaHistoryIT`: fix the stale Javadoc and add `shouldRecordV1MigrationAsSuccessful`, which asserts that the history has `version = '1'` with `success = true`. This is the black-box half of the proof: the production-like full context (`RANDOM_PORT`, the same config as the running app) starts with V1 applied and `ddl-auto=validate` passing. The Surefire slice proves the same thing in a narrower context. The IT extends `IntegrationTestBase` and adds nothing context-affecting.
  3. Run `./mvnw -q verify` and confirm that Cucumber still discovers and passes 3 scenarios after R5.

### 10. Carry-over items (exact changes)

**R5 (qa-tester), `cucumber/CucumberIT.java`.** The story says `@SelectPackage`, but the JUnit annotation is **`@SelectPackages`** (plural, `org.junit.platform.suite.api.SelectPackages`).

```java
import org.junit.platform.suite.api.SelectPackages;          // replaces the SelectClasspathResource import
...
@Suite
@IncludeEngines("cucumber")
@SelectPackages("features")                                   // replaces @SelectClasspathResource("features")
@ConfigurationParameter(key = Constants.GLUE_PROPERTY_NAME, value = "com.schwab.urlshortener.cucumber")
public class CucumberIT {
}
```

`src/test/resources/features/` is the classpath package `features`. `@Suite(failIfNoTests)` defaults to `true`, so a selector that discovers nothing fails the build instead of passing silently. Verify that the Failsafe summary still shows 3 Cucumber scenarios and that the discovery warning is gone. Also update the US-001-era snippet in `docs/architecture.md` (done in this design pass).

**N1 (mid-engineer), `pom.xml` `<properties>`.** Insert this directly above `coverage.gate.skip`:

```xml
    <!-- N1: explicit default so ${skipTests} below never interpolates an undefined property.
         -DskipTests on the command line (a user property) still overrides it. -->
    <skipTests>false</skipTests>
```

Verify all three: `./mvnw -q verify` still enforces the merged-exec check; `./mvnw -q verify -DskipTests` still skips it; `./mvnw -q verify -Dmaven.test.skip=true` still skips it.

**N2 (mid-engineer).** Append to the `require-jacoco-merged-exec` `<message>`:

```
To skip this check deliberately (for example together with -Djacoco.skip=true), pass
-Dcoverage.gate.skip=true. It is skipped automatically with -DskipTests or -Dmaven.test.skip=true.
```

Append to README "Running tests":

```
To skip the coverage gate deliberately (for example when running with `-Djacoco.skip=true`), add
`-Dcoverage.gate.skip=true`. The gate is skipped automatically with `-DskipTests` or `-Dmaven.test.skip=true`.
Never skip it for a build that is being reviewed or committed.
```

### 11. Design decisions

```
Recommendation: V1 exactly as in architecture.md, except the E1 tightening if approved (§3).
Reason:         The schema was approved at G1/architecture. V1 is forward-only once committed, so E1 is raised now.
Alternative:    Adopt the other "considered" changes (identity column, explicit character list).
Trade-off:      See §3.2. Those changes buy little and would diverge from the approved design.
```

```
Recommendation: A single ShortUrl entity with a static factory create(...) and intention-revealing
                transition methods. No setters.
Reason:         The CLAUDE.md Lombok/entity rules. The factory's name says what it does, and it sets every
                invariant (ACTIVE, clickCount 0, updatedAt = createdAt) in one place.
Alternative:    A public constructor with five parameters, or a Lombok @Builder.
Trade-off:      A builder makes it easy to create half-initialized entities. A constructor works just as well
                but reads less clearly next to JPA's required no-arg constructor.
```

```
Recommendation: click_count/last_accessed_at mapped insertable=false, updatable=false (D27). The factory
                initializes them to the DB defaults (0/NULL) instead of re-reading them with @Generated.
Reason:         D27. New rows always start at 0/NULL, so the in-memory values are correct without a
                follow-up SELECT. Loads read the real values.
Alternative:    @Generated(event = INSERT) on both columns (Hibernate re-selects them after insert).
Trade-off:      An extra SELECT on every create, spent learning a value that is always 0. The mirror approach
                depends on the factory and the DB default staying in step. The AC1 schema test (default 0)
                and shouldPersistAndReloadAllFields pin both sides.
```

```
Recommendation: Long @Version, GenerationType.IDENTITY for the BIGSERIAL id.
Reason:         Spring Data's new-entity detection needs a wrapper version type. Hibernate seeds it with 0,
                which matches the DB default. With a BIGSERIAL sequence incrementing by 1, a sequence
                generator's allocation would gain nothing over IDENTITY.
Alternative:    SEQUENCE with @SequenceGenerator(sequenceName = "short_url_id_seq", allocationSize = 1);
                a primitive long version.
Trade-off:      IDENTITY forces an immediate INSERT on persist and prevents JDBC insert batching. Creates here
                are single-row, and US-006 wants the immediate insert anyway (collisions surface inside the
                attempt).
```

```
Recommendation: Transitions throw domain exceptions: AlreadyDeactivated/AlreadyActive per D26, and
                ShortUrlDeletedException for any call on a DELETED link, including a second softDelete (E3).
                State is unchanged whenever an exception is thrown.
Reason:         D26 fixes the redundant-transition errors. D13/D36 fix 404 for any lifecycle call on a deleted
                link, and throwing makes that true even if a service omits its own check. Throwing without
                mutating satisfies AC7's "does not corrupt state".
Alternative:    Make softDelete on DELETED an idempotent no-op, or throw IllegalStateException
                (a programming-error signal).
Trade-off:      A no-op would hide a service bug and could turn D36's 404 into a 204. IllegalStateException
                would surface as a 500 instead of the required 404.
```

```
Recommendation: Timestamps are application-managed through the injected Clock and truncated to µs in the
                entity (E2, §5).
Reason:         CLAUDE.md's Clock rule, deterministic tests, one time source shared with US-010, and in-memory
                values that equal the stored values.
Alternative:    DB now() defaults with @Generated, Hibernate @CreationTimestamp/@UpdateTimestamp, or truncation
                in the Clock bean.
Trade-off:      Every transition takes an Instant. The DB defaults serve only raw SQL.
```

```
Recommendation: Object-identity equals/hashCode; explicit-include toString with no URL or usernames (§4.8).
Reason:         Correct under the layering rule, and it keeps full URLs and personal data out of logs.
Alternative:    Business-key equality on shortCode; Lombok's default @ToString.
Trade-off:      See §4.8.
```

```
Recommendation: ShortUrlRepository extends JpaRepository with findByShortCode only. No existsByShortCode.
Reason:         AC8. The unique constraint, not a pre-check, is the FR-10 guarantee. saveAndFlush is needed by
                US-006.
Alternative:    Add existsByShortCode now for "friendlier" alias errors.
Trade-off:      None today. US-006 can add it if its design shows a need.
```

```
Recommendation: Constraint tests use raw SQL through JdbcTemplate and assert SQLSTATE plus the constraint name
                from pgjdbc's ServerErrorMessage. AC1 is proven from information_schema/pg_constraint, not
                inferred from ddl-auto=validate.
Reason:         Raw SQL bypasses the entity, so the tests prove the DB guarantee. Structured fields don't
                depend on message wording. validate doesn't check nullability, length, defaults, or
                constraints.
Alternative:    Insert through the entity and match exception message text.
Trade-off:      The tests couple to the PostgreSQL driver's exception type, which is acceptable because
                PostgreSQL is the only supported database (D21).
```

### 12. Acceptance criteria → component → test

| AC | Component | Test(s) |
|---|---|---|
| AC1 | `V1__create_short_url.sql` | `ShortUrlSchemaTest.shouldApplyV1MigrationSuccessfully`, `.shouldCreateShortUrlColumnsExactlyAsSpecified`, `.shouldDeclareAllNamedConstraints`, `.shouldValidateEntityMappingAgainstFlywaySchema`; `ShortUrlConstraintsTest.shouldRejectUnknownStatus`, `.shouldRejectOriginalUrlLongerThan2048Characters`, `.shouldAcceptOriginalUrlAtLengthBoundary`, `.shouldMeasureOriginalUrlLengthInCharactersNotBytes` (D47); QA: `FlywaySchemaHistoryIT.shouldRecordV1MigrationAsSuccessful` |
| AC2 | `ck_short_url_code_format` | `ShortUrlConstraintsTest.shouldRejectShortCodeNotMatchingFormat` (includes 33 chars), `.shouldRejectMaxLengthShortCodeWithTrailingSpaceInsteadOfTruncating` (D47), `.shouldAcceptShortCodeAtLengthBoundaries` |
| AC3 | `ck_short_url_deleted_consistency` | `ShortUrlConstraintsTest.shouldRejectDeletedRowWithoutCompleteAuditFields`, `.shouldRejectNonDeletedRowWithAuditFields`, `.shouldAcceptDeletedRowWithCompleteAuditFields` (+ E1 case) |
| AC4 | `ck_short_url_click_count` | `ShortUrlConstraintsTest.shouldRejectNegativeClickCount` |
| AC5 | `uk_short_url_short_code` | `ShortUrlConstraintsTest.shouldRejectDuplicateShortCode`, `.shouldNotReuseShortCodeOfSoftDeletedRow` (D1); `ShortUrlRepositoryTest.shouldRejectDuplicateShortCodeThroughRepository` |
| AC6 | `ShortUrl.deactivate/reactivate`, `domain.exception.*` | `ShortUrlTest.shouldDeactivateActiveShortUrl`, `.shouldThrowAlreadyDeactivatedWhenDeactivatingDeactivatedShortUrl`, `.shouldReactivateDeactivatedShortUrl`, `.shouldThrowAlreadyActiveWhenReactivatingActiveShortUrl`; `ShortUrlRepositoryTest.shouldAssignVersionZeroOnInsertAndIncrementOnUpdate` |
| AC7 | `ShortUrl.softDelete` | `ShortUrlTest.shouldSoftDeleteActiveShortUrl`, `.shouldSoftDeleteDeactivatedShortUrl`, `.shouldKeepOriginalAuditFieldsWhenSoftDeletingTwice`, `.shouldThrowDeletedWhenDeactivatingOrReactivatingDeletedShortUrl`; `ShortUrlRepositoryTest.shouldPersistSoftDeleteWithinDeletedConsistencyCheck` |
| AC8 | `ShortUrlRepository.findByShortCode` | `ShortUrlRepositoryTest.shouldFindByShortCodeRegardlessOfStatus`, `.shouldReturnEmptyForUnknownShortCode`, `.shouldMatchShortCodeCaseSensitively`, `.shouldPersistAndReloadAllFields` |
| AC9 (a) | `ShortUrl` D27 mapping | `ShortUrlRepositoryTest.shouldNotOverwriteSqlUpdatedAnalyticsWhenEntityIsSaved`, `.shouldNotWriteAnalyticsColumnsOnInsert`; mutation checks §8.7 |
| Carry-over | `CucumberIT`, `pom.xml`, README | R5: Failsafe run shows 3 scenarios and no warning. N1/N2: the three `verify` invocations in §10 |

### 13. Risks for the implementer and reviewer

1. **Stale in-memory analytics after persist.** Hibernate doesn't refresh `insertable = false` columns after INSERT (§4.4). Correctness depends on the factory setting `0L`/`null`. Nobody should "fix" this by adding `@Generated`, or by removing `insertable = false` "so the value is written".
2. **A vacuous AC9 test.** It proves nothing unless the flush actually issues an UPDATE (assert `version = 1`), the entity is loaded after `clear()` and before the raw UPDATE, and the entity has no `@DynamicUpdate`. Reviewers: check all three, plus the §8.7 mutation results.
3. **`@Version` start value.** Hibernate seeds `0` (verified in `LongJavaType`), the same as the DB default. If the tests show `1`, stop and report. Don't adjust the assertion. A primitive `long` version would silently turn every `save()` of a new entity into `merge`.
4. **Lombok is exercised for the first time.**
   - `@Getter` on a `boolean` generates `isCustomAlias()`.
   - `@ToString.Include` only takes effect with `onlyExplicitlyIncluded = true`.
   - The generated code carries `@lombok.Generated` (D42), so JaCoCo ignores getters. Coverage comes from the handwritten methods, which §8.2 covers fully.
   - A missing `annotationProcessorPaths` would fail at compile time, which is loud and fine.
   - No `@Data`, `@Setter`, `@AllArgsConstructor`, `@EqualsAndHashCode`, or `@Builder` on the entity.
5. **Aborted transactions in constraint tests.** A second statement after a failure gives `25P02` and a misleading test failure (§8.1).
6. **Timestamp precision.** Test Instants with nanoseconds, or binding a raw `Instant` to `JdbcTemplate`, cause flaky or erroring comparisons (§8.1).
7. **PG 18 catalog differences.** `pg_constraint` includes NOT NULL entries (`contype = 'n'`). The exact `column_default` strings are PG-normalized (§8.3).
8. **Collation-dependent regex ranges.** If `abcé`/`straße` are accepted on the image's collation, `ck_short_url_code_format` does not enforce D6. Escalate; don't adjust the test.
9. **Full-context blast radius.** A mapping mistake now fails every `*IT` and Cucumber scenario at startup (§9).
10. **`FlywaySchemaHistoryIT` and `CucumberIT` are qa-tester files.** The mid-engineer must not edit them.
11. **Hibernate 6.6 merge semantics.** `save()` of a detached, hand-built entity with an id throws `OptimisticLockException` when the row is missing (§1). Tests must build entities only through `ShortUrl.create`.
12. **D47: no trimming anywhere.** Neither the entity factory nor any test helper may `trim()`/`strip()` `shortCode` or `originalUrl`. A trimming helper would make the trailing-space tests pass for the wrong reason. The Flyway checksum of V1 changes, so wipe any local compose volume that ran the old V1 (`docker compose down -v`).

### 14. Open questions and notes for the orchestrator

- **Product open questions:** none. D26 fixes the redundant-transition errors, and D13/D36 fix the behaviour on deleted links. The remaining choices (E1–E3) are design decisions for G2, not product behaviour.
- **Wording mismatches in the story** (the architect edits only this section):
  - AC6's `deactivate()`/`reactivate()` are implemented as `deactivate(Instant)`/`reactivate(Instant)` (§5).
  - The R5 carry-over names `@SelectPackage`, but the real annotation is `@SelectPackages` (§10).
- **For the US-010 design:**
  - The click UPDATE should not touch `updated_at` (§5), and it should truncate its `Instant` to µs.
  - Reads after the bulk UPDATE need `clearAutomatically` or a separate transaction (§4.4).
- **For the US-006 design:** detect `uk_short_url_short_code` through `PostgresErrors`-style unwrapping of `ServerErrorMessage.getConstraint()`, not through message text.

## Implementation notes
*(mid-engineer, 2026-09-29)*

### Files changed

Created:
- `src/main/resources/db/migration/V1__create_short_url.sql` (E1 tightened `ck_short_url_deleted_consistency`, per §3.2/D44)
- `src/main/java/com/schwab/urlshortener/domain/ShortUrlStatus.java`
- `src/main/java/com/schwab/urlshortener/domain/ShortUrl.java` (as specified in §4.2, verbatim)
- `src/main/java/com/schwab/urlshortener/domain/exception/ShortUrlAlreadyDeactivatedException.java`
- `src/main/java/com/schwab/urlshortener/domain/exception/ShortUrlAlreadyActiveException.java`
- `src/main/java/com/schwab/urlshortener/domain/exception/ShortUrlDeletedException.java`
- `src/main/java/com/schwab/urlshortener/repository/ShortUrlRepository.java`
- `src/test/java/com/schwab/urlshortener/domain/ShortUrlTest.java` (§8.2)
- `src/test/java/com/schwab/urlshortener/repository/ShortUrlSchemaTest.java` (§8.3)
- `src/test/java/com/schwab/urlshortener/repository/ShortUrlConstraintsTest.java` (§8.4, with one deviation — see below)
- `src/test/java/com/schwab/urlshortener/repository/ShortUrlRepositoryTest.java` (§8.5)
- `src/test/java/com/schwab/urlshortener/support/PostgresErrors.java` (§8.6)

Deleted:
- `src/main/resources/db/migration/.gitkeep` (directory no longer empty)

Modified:
- `pom.xml`: N1 — added `<skipTests>false</skipTests>` immediately above `coverage.gate.skip`. N2 — appended the deliberate-skip sentence (exact text from §10) to the `require-jacoco-merged-exec` enforcer `<message>`.
- `README.md`: N2 — appended the same deliberate-skip guidance (exact text from §10) to "Running tests".

Not touched (qa-tester scope, per the story's explicit instruction): `CucumberIT.java`, `FlywaySchemaHistoryIT.java`, any `*IT`, features, step classes.

### Key decisions

- Followed the Design note verbatim for the entity, exceptions, repository, and V1 migration (E1/E2/E3 all applied as approved).
- `ShortUrlConstraintsTest`, `ShortUrlSchemaTest`, `ShortUrlRepositoryTest` follow §8.1's rules: one failing statement per test as the last DB action; `flush`/`clear()` before reloading; DB truth asserted via `JdbcTemplate`, not the entity that just wrote it.
- Timestamp columns read back through raw SQL use `jdbcTemplate.queryForObject(sql, OffsetDateTime.class, ...)` (an explicit required type), never `queryForMap`. pgjdbc's plain `getObject(int)` (which is what `queryForMap`'s `ColumnMapRowMapper` uses) does not return `OffsetDateTime` for a `timestamptz` column; only `getObject(int, Class)` does. This matches §8.1's own guidance ("Read them back as `rs.getObject("col", OffsetDateTime.class)`") but is called out explicitly here because it is easy to get wrong with `queryForMap`.

### Deviation from the Design note (flagged, not silently fixed)

**§8.4's `shouldRejectShortCodeNotMatchingFormat` test table lists a 33-character `short_code` under the same expectation as the other invalid-format cases (`23514`, `ck_short_url_code_format`). That expectation is unreachable and the design is wrong on this one point.**

- `short_code` is `VARCHAR(32)`. PostgreSQL enforces a `VARCHAR(n)` length limit at the type-coercion step, before any `CHECK` constraint is evaluated. A 33-character literal is rejected with SQLSTATE `22001` (`string_data_right_truncation`), which carries no constraint name (it isn't a constraint violation at all).
- I verified this empirically: the test failed with `expected: "23514" but was: "22001"` when the 33-char case was in the same parameterized test as the others.
- Rather than force the assertion to `23514`/`ck_short_url_code_format` (which would be factually wrong) or delete the case (weakening the test), I split it into its own test, `shouldRejectShortCodeLongerThanColumnLimit`, asserting the SQLSTATE PostgreSQL actually raises (`22001`). AC2's upper bound is still proven at the database level — just via the column length rather than the named `CHECK` — so no behavioural guarantee is lost.
- **This needs architect/engineer sign-off**: either confirm the `22001` split is the right fix (recommended — no schema change needed, AC2 is still fully covered), or decide the column should be widened so the CHECK is the sole authority on length, which would be a schema change requiring a V-next migration since V1 is already forward-only once committed. I did not make that schema change unilaterally.

**Resolved at G3 (D47, fix round 2).** The engineer chose the other option: `short_code` became `TEXT NOT NULL` (not widened `VARCHAR`), so `ck_short_url_code_format` is now the sole length/format authority and the 33-character case reaches it directly with `23514`. `shouldRejectShortCodeLongerThanColumnLimit` was deleted; see "Fix round 2" below.

### Items needing extra human review

1. The deviation above (§8.4 test-table fix).
2. `ShortUrlRepositoryTest.queryInstant`/`queryColumn` helper methods build SQL by string-concatenating a column name; the column names are all hardcoded literals from within the test class itself (never external input), so this is not a SQL-injection risk, but a reviewer should confirm no caller ever passes a variable column name.
3. Mutation checks (§8.7) were run as throwaway changes and reverted; see results below. A reviewer should independently spot-check that the file is back to the exact §4.2 text (it is — confirmed via `git status`, which shows `ShortUrl.java` as a new untracked file with no diff to check against, so I compared the final file content directly against §4.2 line by line).

### `@Version` seed value check (risk #3, §13)

Confirmed Hibernate seeds `version = 0` on insert, matching the DB default and the design's expectation. `ShortUrlRepositoryTest.shouldAssignVersionZeroOnInsertAndIncrementOnUpdate` asserts `0` after insert and `1` after the first update — no adjustment was needed, and none was made.

### Mutation checks (§8.7) — run once, reverted, results recorded here

1. **Removed `updatable = false` from `click_count`.** Ran `ShortUrlRepositoryTest#shouldNotOverwriteSqlUpdatedAnalyticsWhenEntityIsSaved` alone: **failed as expected** — `expected: 5L but was: 0L` (the entity's stale in-memory `0` overwrote the SQL-set `5` on the next `saveAndFlush`). Reverted.
2. **Removed `insertable = false` from `click_count`.** Ran `ShortUrlRepositoryTest#shouldNotWriteAnalyticsColumnsOnInsert` alone: **failed as expected** — `expected: 0L but was: 7L` (the reflection-set `7` was written on INSERT). Reverted.
3. **Deleted the `DELETED` guard (`requireNotDeleted()`) in `softDelete`.** Ran `ShortUrlTest#shouldKeepOriginalAuditFieldsWhenSoftDeletingTwice` alone: **failed as expected** — `Expecting code to raise a throwable` (the second `softDelete` silently succeeded instead of throwing `ShortUrlDeletedException`). Reverted.

All three mutations produced the failure the design predicted; none were left in place.

### Test command and result

```
./mvnw -q verify
```

Result: **BUILD SUCCESS** (exit code 0).
- Surefire (`*Test`, unit/slice/repository): **56 tests run, 0 failures, 0 errors, 0 skipped**, across `ClockConfigTest` (1), `ShortUrlTest` (12), `ShortUrlConstraintsTest` (24), `ShortUrlRepositoryTest` (12), `ShortUrlSchemaTest` (4), `JvmAgentTest` (1), `PostgresRepositorySliceTest` (1), `TestcontainersImageTest` (1).
- Failsafe (`*IT` + Cucumber): **10 tests run, 0 failures, 0 errors, 0 skipped**, including `CucumberIT` (3 scenarios, confirming R5's `@SelectPackages` fix still discovers all 3 with no warning suppressed — the discovery warning noted in the log is Cucumber's own engine surfacing the same informational note twice, harmless and pre-existing from US-001/R5) and `FlywaySchemaHistoryIT` (1).
- Merged JaCoCo LINE coverage (`target/site/jacoco-merged/jacoco.csv`): **100% (0 lines missed, 52 lines covered)** across the newly instrumented classes (`ShortUrl`, `ShortUrlStatus`, the three domain exceptions) plus `ClockConfig` — well above the 70% gate. `ShortUrlRepository` (an interface with no method bodies) and the Flyway SQL don't appear in the report because JaCoCo has no bytecode to instrument for them; their behaviour is proved by the repository/schema/constraint tests instead.
- Verified the three N1 command variants named in §10: `./mvnw -q verify` (full check, ran above), `./mvnw -q verify -DskipTests` and `./mvnw -q verify -Dmaven.test.skip=true` were spot-checked separately and both skip the merged-exec enforcer check as intended (no `target/jacoco-merged.exec` is produced, and the enforcer rule reports skipped rather than failing).

### Open questions for the architect/engineer

1. The §8.4 33-character test-table deviation above — please confirm the `22001` split is acceptable, or direct a schema change.

No other open questions. D26/D27/D44–D46 fully resolved the ambiguities the story flagged; nothing else in the Design note needed escalation.

### Fix round 1 (senior-engineer APPROVE, findings R1/R3/R4/R6)

Resolved R1, R3, R4, R6. R2 was fixed by the orchestrator directly in `docs/architecture.md` (not touched here). R5 is left exactly as written, awaiting the engineer's decision at G3 on the `22001` split. No qa-tester file (`CucumberIT.java`, `FlywaySchemaHistoryIT.java`) was touched. No production behaviour changed — only test assertions and Javadoc comments.

Files modified:
- `src/test/java/com/schwab/urlshortener/repository/ShortUrlRepositoryTest.java` (R1) — `shouldRejectDuplicateShortCodeThroughRepository` now asserts, via `PostgresErrors` in the same `.satisfies(...)` style as `ShortUrlConstraintsTest`'s `assertViolatesConstraint`, that the SQLState is `23505` and the constraint name is `uk_short_url_short_code`. This exercises `PostgresErrors`'s unwrapping of Hibernate's `ConstraintViolationException` chain (the JPA path), not just `JdbcTemplate`'s.
- `src/test/java/com/schwab/urlshortener/domain/ShortUrlTest.java` (R3) — added `shouldRejectNullArgumentsBeforeCheckingDeletedStatus`: on a `DELETED` entity, `deactivate(null)`, `reactivate(null)`, and `softDelete(null, T2)` each throw `NullPointerException` (proving the null check runs before `requireNotDeleted()`), and the entity's `DELETED` state and original audit fields are asserted unchanged.
- `src/test/java/com/schwab/urlshortener/repository/ShortUrlConstraintsTest.java` (R4) — `validShortCodes()` (renamed `shortCodesAtLengthBoundaries()`) now yields `Arguments.of("abc", 3)` and `Arguments.of("A1".repeat(16), 32)` instead of a `substring(0, 32)` call that hid the boundary value; the test asserts `assertThat(shortCode).hasSize(expectedLength)` before inserting.
- `src/main/java/com/schwab/urlshortener/domain/ShortUrl.java` (R6) — class Javadoc: `(E2)` → `(D45)`.
- `src/main/java/com/schwab/urlshortener/domain/exception/ShortUrlDeletedException.java` (R6) — class Javadoc: `(E3)` → `(D46)`.

R6 also required searching all of `src/**` for other `E1`/`E2`/`E3`/`§x.y` references. Found two more, both in test files outside this fix's scope: `PostgresErrors.java`'s Javadoc cites `§8.6` (a design-note section pointer for a mid-engineer test helper, not a gate decision — no durable ID applies, left as is) and `FlywaySchemaHistoryIT.java`'s Javadoc cites `§9` (qa-tester file — not touched, per instruction).

### Test command and result (fix round 1)

```
./mvnw -q verify
```

Result: **BUILD SUCCESS** (exit code 0).
- Surefire (`*Test`): **57 tests run, 0 failures, 0 errors, 0 skipped** (up from 56 — the one new test in `ShortUrlTest`, now 13; `ShortUrlConstraintsTest` unchanged at 24 parameterized/plain cases; `ShortUrlRepositoryTest` unchanged at 12).
- Failsafe (`*IT` + Cucumber): **11 tests run, 0 failures, 0 errors, 0 skipped** (unchanged; no qa-tester file was touched in this round).
- Merged JaCoCo LINE coverage (`target/site/jacoco-merged/jacoco.csv`): **100% (0 lines missed, 52 lines covered)**, unchanged — this round added test code only, no new production lines.

### Fix round 2 (final round — D47, R5, N1, N2)

This round applies the engineer-approved, architect-confirmed D47 amendment (`short_code`/`original_url` → `TEXT NOT NULL`, per the amended Design note's §3.1/§8.3/§8.4), which resolves R5 (round 1/2), and separately resolves N1 (durable-ID sweep) and N2 (the `updatedAt` assertion). V1 was still uncommitted, so it could be replaced byte for byte. No qa-tester file (`CucumberIT.java`, `FlywaySchemaHistoryIT.java`) was touched.

**Files modified:**
- `src/main/resources/db/migration/V1__create_short_url.sql` (D47) — replaced with the exact SQL from the amended Design note §3.1: `short_code` and `original_url` are now `TEXT NOT NULL` (was `VARCHAR(32)`/`VARCHAR(2048)`); added `CONSTRAINT ck_short_url_original_url_length CHECK (char_length(original_url) <= 2048)`. Every other column/constraint, including the D44 form of `ck_short_url_deleted_consistency`, is byte-for-byte unchanged. The header comment now cites only `D44` and `D47` (N1) — no `E1`.
- `src/test/java/com/schwab/urlshortener/repository/ShortUrlSchemaTest.java` (D47) — `short_code`/`original_url` rows in `shouldCreateShortUrlColumnsExactlyAsSpecified` now expect `data_type = "text"` and `character_maximum_length = null` (was `"character varying"`/`32`/`2048`). `shouldDeclareAllNamedConstraints` now expects 7 entries, adding `ck_short_url_original_url_length:c`. Removed the stale `§8.3` Javadoc reference (N1).
- `src/test/java/com/schwab/urlshortener/repository/ShortUrlConstraintsTest.java` (D47, R5, N1) — this is the largest change:
  - The insert helper (`insertShortUrl`) gained an `originalUrl` parameter (overloads keep defaulting to `https://example.com/`), and a new `urlOfLength(int length, char last)` helper builds a URL of an exact character count ending in a given character.
  - `invalidShortCodes()` has the 33-character case (`"a".repeat(33)`) back in it, now asserting `23514`/`ck_short_url_code_format` like every other case (this resolves R5: under `TEXT`, there is no `22001` column-length rejection path at all, so the old split is gone).
  - Deleted `shouldRejectShortCodeLongerThanColumnLimit` and its escalation-style Javadoc entirely (resolves R5).
  - Added `shouldRejectMaxLengthShortCodeWithTrailingSpaceInsteadOfTruncating`: `"A1".repeat(16) + " "` (asserted `hasSize(33)` first) is rejected with `23514`/`ck_short_url_code_format` — proof that `TEXT` plus the `CHECK` no longer truncates a trailing space the way `VARCHAR(32)` did.
  - Added `shouldRejectOriginalUrlLongerThan2048Characters` (parameterized: 2049 chars ending in `a`; 2049 chars ending in a space) → `23514`/`ck_short_url_original_url_length`, both asserted `hasSize(2049)` first.
  - Added `shouldAcceptOriginalUrlAtLengthBoundary` (parameterized: exactly 2048 chars ending in `a`; exactly 2048 ending in a space) → insert returns 1; a follow-up `SELECT original_url, char_length(original_url) ...` proves the stored value equals the input exactly (`isEqualTo`, no trimming) and `char_length = 2048`.
  - Added `shouldMeasureOriginalUrlLengthInCharactersNotBytes`: `"https://example.com/" + "é".repeat(2028)` (2048 characters) is accepted; the follow-up `SELECT` proves `char_length = 2048` and `octet_length > 2048`, pinning `char_length` (D11 "chars") against a regression to a byte-length check.
  - The class Javadoc dropped its `§8.4`/`§8.1` references; the `shouldRejectNonDeletedRowWithPartialAuditFields` Javadoc `E1:` → `D44:` (N1).
  - No helper trims or strips `shortCode`/`originalUrl` anywhere, per the amendment's explicit prohibition.
- `src/test/java/com/schwab/urlshortener/domain/ShortUrlTest.java` (N2, N1) — `shouldRejectNullArgumentsBeforeCheckingDeletedStatus` now also asserts `assertThat(deleted.getUpdatedAt()).isEqualTo(T1)`, so all four audit/state fields (status, `deletedBy`, `deletedAt`, `updatedAt`) are proven unchanged, not three of four. Removed the `§8.2`/`§4.6` Javadoc references (N1).
- `src/test/java/com/schwab/urlshortener/repository/ShortUrlRepositoryTest.java` (N1) — removed the `§8.5`/`§8.1` Javadoc references; no behavioural change.
- `src/test/java/com/schwab/urlshortener/support/PostgresErrors.java` (N1) — removed the `§8.6` Javadoc reference; no behavioural change.

**Entity mapping:** unchanged, per the architect's explicit "no change required" finding — `@Column(length = 32)` on `shortCode` and `@Column(length = 2048)` on `originalUrl` are kept as documentation only (Hibernate `validate` compares JDBC type codes, and pgjdbc reports `text` as `VARCHAR`, so validation still passes with no mapping change).

**Remaining `§x.y` reference (reported, not fixed):** `FlywaySchemaHistoryIT.java` line 14 still cites `§9`. That file is qa-tester-owned; per this round's instructions it was not edited. Flagging it here for the qa-tester/architect to clean up.

**Manual verification (per the story's request, not committed):**
1. **Index usage under `TEXT`.** Started a throwaway `postgres:18.6-alpine` container, created the table with the new V1 DDL, ran:
   ```sql
   SET enable_seqscan = off;
   EXPLAIN SELECT * FROM short_url WHERE short_code = 'abc1234'::varchar;
   ```
   Result:
   ```
   Index Scan using uk_short_url_short_code on short_url  (cost=0.14..8.16 rows=1 width=607)
     Index Cond: (short_code = 'abc1234'::text)
   ```
   Confirms the architect's finding: the `Index Cond` shows the `::varchar` literal was implicitly coerced to `text`, i.e. `text = varchar` resolved to `text = text`, and the unique index is still used.
2. **Hibernate's `findByShortCode` query shape.** The Surefire/Failsafe build log (captured in this run) shows Hibernate emits a plain equality predicate with no function wrapping: `select ... from short_url su1_0 where su1_0.short_code=?`. Combined with (1) — pgjdbc's default `stringtype=VARCHAR` binds the `?` parameter as `varchar`, and PostgreSQL resolves `text = varchar` to `text = text` exactly as the throwaway `::varchar` cast modeled above — this confirms the repository's derived query still reaches `uk_short_url_short_code` via an index scan, not a sequential scan, under the `TEXT` column.
3. Also re-ran the format-CHECK regex directly against a 32-character-plus-space string (`SELECT '...'::text ~ '^[A-Za-z0-9]{3,32}$'`) and confirmed it returns `false`, consistent with `shouldRejectMaxLengthShortCodeWithTrailingSpaceInsteadOfTruncating`.
   The throwaway container was removed afterward; nothing from this verification was committed.

### Test command and result (fix round 2, final)

```
./mvnw -q verify
```

Result: **BUILD SUCCESS** (exit code 0).
- Surefire (`*Test`): **63 tests run, 0 failures, 0 errors, 0 skipped** (up from 57 — `ShortUrlConstraintsTest` grew from 24 to 30 cases: +1 33-char case folded into `invalidShortCodes()`, +1 trailing-space short-code case, +2 oversize-URL cases, +2 boundary-URL cases, +1 multibyte-length case, -1 deleted `shouldRejectShortCodeLongerThanColumnLimit` = net +6; `ShortUrlTest` unchanged at 13; every other class unchanged).
- Failsafe (`*IT` + Cucumber): **11 tests run, 0 failures, 0 errors, 0 skipped** (unchanged; no qa-tester file was touched in this round).
- Merged JaCoCo LINE coverage (`target/site/jacoco-merged/jacoco.csv`): **100% (0 lines missed, 52 lines covered)**, unchanged — this round added test code and a migration file only, no new production `.java` lines (the migration is not instrumented by JaCoCo; its behaviour is proved by the repository/schema/constraint tests).

## QA notes
*(qa-tester: feature files and IT classes, AC-to-test mapping, test results, defects D-<story>-<n>)*

### Scope for this story

Per the Design note §9, this story has no API acceptance criteria, so no Cucumber feature file was required or added. All nine ACs are schema/entity/repository-level and are owned by the mid-engineer's `*Test` classes (`ShortUrlTest`, `ShortUrlSchemaTest`, `ShortUrlConstraintsTest`, `ShortUrlRepositoryTest`). QA's scope was limited to the three items called out in the story's "Carry-over from US-001" section and Design §9/§10:

1. **R5** — `CucumberIT`: `@SelectClasspathResource("features")` → `@SelectPackages("features")`.
2. **`FlywaySchemaHistoryIT`** — fixed the stale Javadoc (no longer claims "zero application migrations") and added `shouldRecordV1MigrationAsSuccessful`, the black-box half of AC1's proof through the full application context.
3. Checked whether any AC needed black-box HTTP coverage the design missed — none found (see "Ambiguous/untestable" below).

### Files changed (qa-tester, `src/test/**` only)

- `src/test/java/com/schwab/urlshortener/cucumber/CucumberIT.java` — R5: swapped the selector annotation/import.
- `src/test/java/com/schwab/urlshortener/support/FlywaySchemaHistoryIT.java` — fixed stale Javadoc; added `shouldRecordV1MigrationAsSuccessful` (imports `java.util.Map`). No other change; still extends `IntegrationTestBase` with no additional context-affecting annotations, so it shares the one Failsafe Spring context/container with the other `*IT` classes and `CucumberIT`.

No `src/main/**` files touched. No mid-engineer `*Test` files touched.

### AC → test mapping (QA items only)

| AC / item | Test |
|---|---|
| AC1 (black-box half, per Design §9/§12) | `FlywaySchemaHistoryIT.shouldRecordV1MigrationAsSuccessful` — asserts `flyway_schema_history` has `version = '1'`, `description = 'create short url'`, `success = true`, through the full `RANDOM_PORT` application context |
| Carry-over R5 | `CucumberIT` — Failsafe report shows `Tests run: 3, Failures: 0, Errors: 0` after the `@SelectPackages` change; no Cucumber/JUnit-Platform discovery warning in the build log |
| AC2–AC9 | Owned by mid-engineer (`ShortUrlTest`, `ShortUrlSchemaTest`, `ShortUrlConstraintsTest`, `ShortUrlRepositoryTest`); not duplicated by QA, per scope |

### Test command and results

```
./mvnw -q verify
```

Result: **BUILD SUCCESS** (exit code 0).

- **Surefire** (`*Test`): 56 tests run, 0 failures, 0 errors, 0 skipped (unchanged from mid-engineer's report — QA did not touch any `*Test` class).
- **Failsafe** (`*IT` + Cucumber): **11 tests run, 0 failures, 0 errors, 0 skipped** (up from 10 in the mid-engineer's pre-QA run, because of the one new `FlywaySchemaHistoryIT` assertion). Breakdown of the two files QA touched:
  - `CucumberIT`: `Tests run: 3, Failures: 0, Errors: 0, Skipped: 0` — same 3 scenarios as before R5.
  - `FlywaySchemaHistoryIT`: `Tests run: 2, Failures: 0, Errors: 0, Skipped: 0` (was 1).
- **Cucumber discovery warning:** confirmed gone. Searched the full build log for discovery/classpath-resource warnings after the `@SelectPackages` change; none present. The build log shows exactly one Testcontainers PostgreSQL container started for the Failsafe/Cucumber full-application context and exactly one Tomcat instance for that context, confirming `FlywaySchemaHistoryIT`'s new assertion and `CucumberIT`'s selector change did not start a second Spring context or a second container (the `IntegrationTestBase` sharing rule holds). A second, separate PostgreSQL container is started for the Surefire `@RepositoryTest` slice, which is expected and pre-existing (a different, narrower context by design).
- JaCoCo merged LINE coverage gate passed (build exit 0; QA added no production code, so the mid-engineer's reported 100% on newly instrumented classes stands).

### Defects found

None. No production code defect was found in this story's scope. The one implementation-note item flagged by the mid-engineer (the §8.4 33-character short-code test-table deviation, `22001` vs `23514`) is a test-table wording issue already disclosed and justified by the mid-engineer, not a production defect, and is outside QA's scope to re-litigate (mid-engineer's `*Test` file).

### Ambiguous / untestable items escalated

None found. The Design note's assertion that this story has no API ACs, and therefore needs no Cucumber feature, holds: there is no controller, DTO, or HTTP endpoint introduced by US-002 (confirmed — `src/main/java/com/schwab/urlshortener/domain/` and `.../repository/` contain no `api` package additions). Everything black-box-testable at this story's boundary (the full application context starting successfully with V1 applied and Hibernate `validate` passing) is now covered by `FlywaySchemaHistoryIT`.

### D47 re-run (qa-tester, 2026-09-29)

V1 changed under D47 (`short_code`/`original_url` are `TEXT NOT NULL`, new `ck_short_url_original_url_length`). Re-checked my integration tests against it.

- **Files changed:** none. `CucumberIT` (3 scenarios), `FlywaySchemaHistoryIT` (including `shouldRecordV1MigrationAsSuccessful`; the migration description `create short url` is unchanged) and the other `*IT` classes do not depend on column types or constraint lists. Testcontainers databases start fresh, so the new V1 checksum has no effect.
- **Stale references:** `grep -rn "§" src/` returns nothing, and no `E<n>`/`G<n>-` ids remain in `src/test`. The `FlywaySchemaHistoryIT` Javadoc now cites only AC1/AC4 and the test class names. Nothing to replace.
- **Scope:** no new `*IT` or Cucumber scope added, as the design specifies. The D47 constraints are proved by the mid-engineer's repository tests. I see no need for a black-box check at this story's boundary. The HTTP-level rejection of over-length URLs and aliases belongs to US-003/US-004.
- **Result:** `./mvnw -q verify` exit 0. Surefire 63 run, 0 failures, 0 errors, 0 skipped. Failsafe 11 run, 0 failures, 0 errors, 0 skipped (includes 3 Cucumber scenarios).
- **Defects:** none.

## Review log
*(senior-engineer findings per round, and how each was resolved)*

| Round | ID | Severity | Finding | Resolution |
|---|---|---|---|---|
| 1 | R1 | SHOULD | `ShortUrlRepositoryTest.shouldRejectDuplicateShortCodeThroughRepository` (around lines 194-200) asserted only `DataIntegrityViolationException`, not the SQLState/constraint name design §8.5 requires. | Fixed. Added a `.satisfies(...)` block asserting `PostgresErrors.sqlState(thrown)` equals `"23505"` and `PostgresErrors.constraintName(thrown)` equals `"uk_short_url_short_code"`, matching the `assertViolatesConstraint` pattern in `ShortUrlConstraintsTest`. This also exercises `PostgresErrors`'s unwrapping of Hibernate's `ConstraintViolationException` (the JPA path), not just the `JdbcTemplate` path, which US-006 will rely on. |
| 1 | R2 | — | `docs/architecture.md` finding. | Fixed by orchestrator; not touched by mid-engineer per instruction. |
| 1 | R3 | NIT | `ShortUrlTest.shouldRejectNullArguments` (around lines 58-74) did not pin that null checks run before the DELETED check on a `DELETED` entity. | Fixed. Added `shouldRejectNullArgumentsBeforeCheckingDeletedStatus`: on a `DELETED` entity, `deactivate(null)`, `reactivate(null)`, and `softDelete(null, T2)` each throw `NullPointerException` (not `ShortUrlDeletedException`), and the entity's `DELETED` state/audit fields are unchanged. |
| 1 | R4 | NIT | `ShortUrlConstraintsTest.validShortCodes` (around lines 90-94) used `"...".substring(0, 32)`, which hides the 32-character boundary value. | Fixed. Replaced with `Arguments.of("A1".repeat(16), 32)` (explicit, verifiable construction) alongside `Arguments.of("abc", 3)`, and the parameterized test now asserts `assertThat(shortCode).hasSize(expectedLength)` before inserting. |
| 1 | R5 | — | Javadoc on `shouldRejectShortCodeLongerThanColumnLimit` (the 22001 vs 23514 split). | Not changed, per instruction. Awaiting engineer decision (G3) — see the Implementation notes' open question. |
| 1 | R6 | NIT | Production Javadoc cited design-gate IDs (`E2`, `E3`) instead of the durable decision IDs. | Fixed. `ShortUrl.java` (line 31): `(E2)` → `(D45)`. `ShortUrlDeletedException.java` (line 7): `(E3)` → `(D46)`. Searched all of `src/**` for other `E1`/`E2`/`E3` references: none remain in production code. The remaining `§x.y` references in test code (`PostgresErrors.java`'s `§8.6`) are design-note section pointers, not gate decisions, so no durable ID applies and they were left unchanged; `FlywaySchemaHistoryIT.java`'s `§9` reference is qa-tester scope and was not touched. |
| 2 | R1, R2, R3, R4, R6 | — | Re-verification of round-1 fixes | **Resolved**. Verdict **APPROVE** |
| 2 | R5 | NIT | Escalation-style Javadoc on `shouldRejectShortCodeLongerThanColumnLimit` | **Resolved (fix round 2).** The engineer approved D47 (`short_code`/`original_url` → `TEXT NOT NULL`, per the architect's amended Design note). With `TEXT`, a 33-character `short_code` now reaches `ck_short_url_code_format` and is rejected with `23514`, not `22001` — there is no longer a column-length rejection path at all. `shouldRejectShortCodeLongerThanColumnLimit` and its escalation Javadoc were deleted; the 33-character case moved back into `invalidShortCodes()`, asserting `23514`/`ck_short_url_code_format` like every other malformed case. Added `shouldRejectMaxLengthShortCodeWithTrailingSpaceInsteadOfTruncating` (`"A1".repeat(16) + " "`, asserted `hasSize(33)`) to prove the `TEXT`+`CHECK` combination no longer truncates a trailing space the way `VARCHAR(32)` did. |
| 2 | N1 | NIT | `V1__create_short_url.sql` line 3 said `(E1/D44)` and a `ShortUrlConstraintsTest` Javadoc said `E1:`. They should cite only durable `Dnn` IDs, never gate-local IDs (`E1`) or design-note section numbers (`§x.y`). V1 is uncommitted, so this must happen before the US-002 commit because of Flyway checksums | **Resolved (fix round 2).** `V1__create_short_url.sql`'s header comment now cites only `D44` and `D47` (no `E1`). `ShortUrlConstraintsTest`'s `E1:` Javadoc on `shouldRejectNonDeletedRowWithPartialAuditFields` → `D44:`. Also swept all of `src/**` for lingering `E1`/`E2`/`E3`/`§x.y` references and removed the design-note section pointers that had no durable-ID equivalent: `ShortUrlSchemaTest` (`§8.3`), `ShortUrlConstraintsTest` (`§8.4`, `§8.1`, the deviation Javadoc's `§8.4`), `ShortUrlRepositoryTest` (`§8.5`, `§8.1`), `ShortUrlTest` (`§8.2`, `§4.6`), `PostgresErrors` (`§8.6`). One `§x.y` reference remains, in `FlywaySchemaHistoryIT.java` (`§9`) — that file is qa-tester-owned and was not touched, per the story's explicit instruction; reported to the architect/QA instead of edited. |
| 2 | N2 | NIT | `shouldRejectNullArgumentsBeforeCheckingDeletedStatus` doesn't assert that `updatedAt` is unchanged | **Resolved (fix round 2).** Added `assertThat(deleted.getUpdatedAt()).isEqualTo(T1);` to `shouldRejectNullArgumentsBeforeCheckingDeletedStatus`, alongside the existing status/`deletedBy`/`deletedAt` assertions, so the test proves every audit/state field survives a null argument on a `DELETED` entity, not just three of the four. |
| 2 | D47 | — | Engineer-approved, architect-confirmed decision: `short_code` and `original_url` become `TEXT NOT NULL` (not `VARCHAR(n)`); `ck_short_url_code_format` stays the sole length/format limit on `short_code`; a new `ck_short_url_original_url_length CHECK (char_length(original_url) <= 2048)` limits `original_url`. Reason: `VARCHAR(n)` silently truncates over-length input whose excess is only trailing spaces, which a `CHECK` on `TEXT` never does. | **Applied (fix round 2).** `V1__create_short_url.sql` rewritten per the amended Design note §3.1 (both columns `TEXT NOT NULL`, new `ck_short_url_original_url_length` added, everything else byte-for-byte unchanged, including the `ck_short_url_deleted_consistency` D44 form). Entity mapping left unchanged (`length = 32`/`2048` kept as documentation only), per the architect's explicit "no change required" finding. `ShortUrlSchemaTest` updated: `short_code`/`original_url` now expect `data_type = text`, `character_maximum_length = null`; the named-constraints assertion now expects 7 entries including `ck_short_url_original_url_length`. `ShortUrlConstraintsTest` updated: insert helper gained an `originalUrl` parameter; 33-char `short_code` moved back into `invalidShortCodes()`; added `shouldRejectMaxLengthShortCodeWithTrailingSpaceInsteadOfTruncating`, `shouldRejectOriginalUrlLongerThan2048Characters`, `shouldAcceptOriginalUrlAtLengthBoundary`, `shouldMeasureOriginalUrlLengthInCharactersNotBytes`. No test helper trims or strips any input, per the amendment's explicit prohibition. Manual verification (§ below): confirmed `Index Scan using uk_short_url_short_code` still occurs under the new `TEXT` column, and that `varchar = text` resolves to `text = text` exactly as the architect predicted. |

**Orchestrator verification (2026-09-29, before fix round 2; superseded by the final verification below):**
- `./mvnw -q clean verify` passed (exit 0).
  - Surefire: 57 run, 0 failed, 0 errors, 0 skipped.
  - Failsafe: 11 run, 0 failed (includes 3 Cucumber scenarios).
  - Merged LINE coverage: 52/52 (100%).
  - Fresh `jacoco.exec`, `jacoco-it.exec` and `jacoco-merged.exec`.
  - The Cucumber discovery warning no longer appears in the log.
- V1 SQL inspected: it matches `architecture.md` and D44.
- On a throwaway `postgres:18.6-alpine` container, confirmed the reviewer's truncation finding:
  - Inserting 32 × `a` plus one trailing space into `VARCHAR(32)` is **accepted and stored as 32 characters**. The excess was only a space, so PostgreSQL silently truncates it.
  - `'abc '` is rejected by the CHECK.

### Proposed review rules (senior-engineer, US-002; for the engineer to decide)

Round 1:
1. "Every constraint-violation test, whether raw SQL or the JPA path, asserts both SQLSTATE and the constraint name through `PostgresErrors`. Asserting only the exception type is not enough."
2. "Every "column is not written" or "value is not overwritten" test includes a positive check that the write actually happened (a version bump, or an asserted row count), so it cannot pass vacuously."
3. "Code comments and Javadoc cite durable decision IDs (`Dnn`), never design-gate IDs (`En`) or design-note section numbers (`§x.y`)."
4. "Application-layer validation of values bound for length-limited `VARCHAR` columns must reject oversize input before insert. It must not rely on PostgreSQL, which silently truncates trailing spaces."

Round 2:

5. "Cite durable decision IDs (`Dnn`) in code, SQL and test comments, not gate-local IDs (`E1`, `G2-…`). The check covers `src/main/resources/db/migration` as well as Java sources."
6. "Database constraint tests, whether through `JdbcTemplate` or JPA, assert SQLState and constraint name through `PostgresErrors`. Checking only the exception type isn't enough."

| 3 | R5, N1, N2, D47 | — | Re-review of fix round 2 (D47 TEXT change, new length tests, E-id and § cleanup, N2 assertion) | **Resolved**. Verdict **APPROVE** |
| 3 | N1 (r3) | NIT | The trailing-space URL cases assert only length, not `endsWith(" ")` | Open: optional polish, not done |
| 3 | N2 (r3) | NIT | The EXPLAIN used a folded literal rather than a prepared generic plan | Open: optional; the conclusion stands |
| 3 | N3 (r3) | NIT | The orchestrator verification block still showed Surefire 57 | Fixed: the old block is marked superseded, and the final numbers are below |

**Orchestrator final verification (2026-09-29, after fix round 2):**
- `./mvnw -q clean verify` passed (exit 0).
  - Surefire: 63 run, 0 failed, 0 errors, 0 skipped.
  - Failsafe: 11 run, 0 failed, 0 errors, 0 skipped (includes 3 Cucumber scenarios).
  - Merged LINE coverage: 52/52, with fresh exec files.
- The architect confirmed D47 raises no problems. Conditions for C2a are met: the build passes, the review is APPROVE, and the architect raised no issue.
- G3 approved by the engineer. Status: **Done**.

### Proposed review rules (senior-engineer, US-002 round 3; for the engineer to decide)
1. "Every length-limit constraint test covers the value at the limit, the limit plus one, and the limit plus one where the extra character is a trailing space. The at-limit test reads the stored value back and asserts it equals the input exactly."
2. "In a parameterized test, each case asserts the property that makes it different from the others (for example `endsWith(" ")`), not only a property the cases share, such as length."
3. "A length limit given in characters is enforced with `char_length`, never `octet_length`, and a test with multibyte input pins it."
