---
id: US-002
title: Domain model, V1 schema, and repository
status: Open
plan_task: 2
depends_on: [US-001]
requirements: [FR-1, FR-6, FR-10, D1, D6, D13, D26, D27]
requires_design_approval: true
---

# US-002: Domain model, V1 schema, and repository

## User story
As an engineer, I want the `short_url` table, its constraints, the `ShortUrl` entity, and its repository, so that later stories have a correct, constraint-backed persistence layer to build the API on.

## Acceptance criteria
- **AC1:** Given the V1 Flyway migration in `src/main/resources/db/migration/`, when it runs against PostgreSQL, then it creates `short_url` with exactly the columns, defaults, and constraints in `docs/architecture.md` (`uk_short_url_short_code`, `ck_short_url_status`, `ck_short_url_click_count`, `ck_short_url_code_format`, `ck_short_url_deleted_consistency`).
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
- **R5 (qa-tester):** change `CucumberIT` from `@SelectClasspathResource("features")` to `@SelectPackage("features")`. This is a one-line amendment to the US-001 design §8.4 and removes the Cucumber discovery warning.
- **N1 (mid-engineer):** declare `<skipTests>false</skipTests>` explicitly in `pom.xml` `<properties>`, so the `coverage.gate.skip` default no longer relies on an undefined property.
- **N2 (mid-engineer):** document the deliberate skip `-Dcoverage.gate.skip=true` in the `require-jacoco-merged-exec` enforcer message and in the README's "Running tests".

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
