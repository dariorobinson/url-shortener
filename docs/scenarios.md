# Engineering Scenarios

This document records the three required engineering scenarios.

---

## Scenario 1 — Greenfield: initial URL-shortening capability

**Status:** Done (US-001–US-011, commits C1–C6b).

### Requirement understanding
See [requirements.md](requirements.md). The ambiguous requirements were resolved with the engineer before any code was written (decisions D1–D14).

### Decomposition

| Task | Scope | Status |
|---|---|---|
| 0 | Repository and documentation skeleton | Done (C1) |
| 1 | Maven project, Spring Boot app, Flyway, Docker Compose, Testcontainers base | Done (US-001) |
| 2 | Domain model, V1 schema, repository | Done (US-002) |
| 3 | Short-code generator | Done (US-003) |
| 4 | URL and alias validation | Done (US-004) |
| 5 | Security foundation (`USER` / `ADMIN`, 401/403) | Done (US-005) |
| 6 | Create/read API and error handling | Done (US-006 create, US-007 read) |
| 7 | Redirect | Done (US-008) |
| 8 | Deactivate/reactivate and soft delete | Done (US-009) |
| 9 | Analytics | Click recording done (US-010); stats done (US-011) |

### Architecture decisions
See [architecture.md](architecture.md).

### Implementation, review, testing, validation
*(Recorded per task as they complete.)*

- **US-001 (Task 1):**
  - **Design:** architect design note, approved at G2 (D39–D43).
  - **Implementation:** the mid-engineer built the Maven, Boot, Compose, Testcontainers and JaCoCo setup plus 4 unit tests. The qa-tester added 7 `*IT` tests and 3 Cucumber scenarios.
  - **Review:** the senior-engineer found 2 BLOCKING issues in round 1. The JaCoCo gate was reading coverage left over from earlier builds, and AC12 had no test. Both were fixed, and round 2 approved.
  - **Validation:** the orchestrator ran `./mvnw -q clean verify` (14 tests, 0 failures, coverage gate passed) and a manual Compose plus local-profile run.
- **US-002 (Task 2):**
  - **Design:** architect design note, approved at G2.
    - The architect caught a flaw in the planning-stage deleted-consistency CHECK, which is now tightened (D44).
    - Timestamps come from the Clock (D45).
    - Changes to deleted links throw (D46).
  - **Implementation:** the mid-engineer delivered V1, the `ShortUrl` entity (read-only analytics columns, D27), the domain exceptions, and the repository.
    - 57 unit, slice, and repository tests, including constraint-name assertions and an AC9 proof that can't pass vacuously.
    - Three mutation checks, each confirmed to fail and then reverted.
  - **QA:** the qa-tester switched the Cucumber suite to `@SelectPackages` and added a V1-applied assertion.
  - **Review:** APPROVE. One SHOULD finding (R1) and NITs were fixed. The reviewer found silent `VARCHAR` trailing-space truncation, which needs an engineer decision.
  - **Validation:** the orchestrator ran `./mvnw -q clean verify` (68 tests, 0 failures, LINE coverage 100%).
  - **Engineer's G3 decision:** `short_code` and `original_url` become `TEXT`, with CHECK constraints as the only length limits (D47). The re-review approved, and the final build was 74 tests, 0 failures. Committed alone as C2a.
- **US-003 (Task 3):**
  - **Design:** none. The engineer approved going straight to implementation.
  - **Implementation:** the mid-engineer delivered a `SecureRandom` Base62 generator (D6) with validated `shortener.code.*` properties, 3–32 characters long and at least one attempt.
  - **QA:** the qa-tester added a wiring IT that inserts generated codes through `ck_short_url_code_format`, and cleaned up the `JvmAgentIT` comment.
  - **Review:** APPROVE in both rounds. The SHOULD findings were fixed: removed a package cycle, added constructor validation, and made the startup-failure assertions precise. How the random source is exposed as a bean went to the engineer.
  - **Validation:** the orchestrator ran `./mvnw -q clean verify` (105 tests, 0 failures, LINE coverage 100%). In the final round, the engineer approved removing the `SecureRandom` bean (R1) and sharing the 3–32 constants (N1). Final build: 105 tests, 0 failures.
- **US-004 (Task 4):**
  - **Design:** none. The engineer approved going straight to implementation.
  - **Implementation:** the mid-engineer delivered `UrlValidator` and `AliasPolicy`, pure and boolean-returning.
    - URLs are parsed strictly with `java.net.URI`: http or https only, a non-empty host, and no userinfo of any kind.
    - URL length counts code points, up to 2048 (D11, D47).
    - The own-host check matches the `APP_BASE_URL` host exactly and case-insensitively, ignoring one trailing dot (D28).
    - Aliases are Base62, 3–32 characters, never trimmed (D6, D47), and checked case-insensitively against configurable reserved words (D29).
    - `app.base-url` is required and validated at startup.
  - **QA:** the qa-tester added a wiring IT for the full context.
  - **Review:** round 1 found one BLOCKING issue, a vacuous environment-binding test, which was fixed. Round 2 approved. Five behaviour questions went to the engineer.
  - **Validation:** the orchestrator ran `./mvnw -q clean verify` (253 tests, 0 failures, LINE coverage 99.1%).
  - **Final round (engineer-approved):**
    - The base URL rejects a query or fragment.
    - Built-in reserved words, with additions only (D48).
    - URLs that can't be encoded as UTF-8 are rejected.
    - The D49 host-scope behaviour is pinned by regression tests.
  - **Final build:** 281 tests, 0 failures.
- **US-005 (Task 5):**
  - **Design:** architect design note, approved at G2 (D50–D56).
  - **Implementation:** the mid-engineer delivered:
    - an HTTP Basic, stateless filter chain with ordered, deny-by-default rules;
    - a concrete `DaoAuthenticationProvider` as the only security bean, with the encoder and user store built inside it;
    - list-shaped users validated at startup (BCrypt cost 10, bounded lowercase usernames);
    - `ErrorCode` and `ProblemDetails`, plus 401/403 handlers;
    - the `ShortUrl` actor guard;
    - the US-004 carry-over (both env-var forms tested).
  - **QA:** the qa-tester added `SecurityIT` on real Tomcat (48 tests: encoded-path bypass pins, identical 401 bodies, 403 before handler) and a `security.feature` with 12 scenarios.
  - **Review:** APPROVE in both rounds. Four SHOULD test-strength findings were fixed. The reviewer and QA found a latent trailing-slash gap in the ADMIN DELETE matcher (R15), which went to the engineer.
  - **Validation:** the orchestrator ran `./mvnw -q clean verify` (473 tests, 0 failures, LINE coverage 99.6%).
  - **Final rounds (engineer-approved):**
    - R15: `DELETE /api/v1/urls/**` requires ADMIN.
    - Escalation: a case-variant probe (`DELETE /API/...`) showed a USER could reach a handler. The engineer chose `anyRequest().denyAll()` (D57) and authorised a third round.
  - **Final build:** 482 tests, 0 failures.
- **US-006 (Task 6, create):**
  - **Design:** architect design note, approved at G2 (D58–D69).
  - **Implementation:** the mid-engineer delivered:
    - `POST /api/v1/urls`, with per-attempt REQUIRES_NEW transactions and a bounded retry, retrying only on `uk_short_url_short_code`. Reserved-word codes use up an attempt, a taken alias gives 409, and running out gives 503 with no row.
    - `PostgresServerErrors` and `GlobalExceptionHandler` using the D56 `errors` extension.
    - Strict JSON parsing and the D61 error codes.
    - D64: row data kept out of the logs, with a test.
    - OpenAPI with the Basic scheme and the punycode note.
  - **Escalation:** the mid-engineer found empirically that the approved design's risk K5 was wrong: a request with `Accept: application/xml` committed the row and then returned 406. The architect confirmed the cause from the Spring source, and the engineer approved class-level `produces` (D70).
  - **QA:** the qa-tester added a scripted-generator seam, 47 create scenarios, and `CreateShortUrlIT`, `ShortCodeCollisionIT` and `CreateShortUrlConcurrencyIT`. The concurrency test includes a lock-based, sleep-free proof. No defects.
  - **Review:** APPROVE in both rounds. The SHOULD findings were fixed: framework 5xx errors weren't logged, and one branch could never run.
  - **Validation:** the orchestrator ran `./mvnw -q clean verify` (731 tests, 0 failures, LINE coverage 99.5%).
- **US-007 (Task 6, read):**
  - **Design:** approved at G2 (D72–D74).
  - **Implementation:** `GET /api/v1/urls/{code}` with the shared D58 body and a `Caller` record (exact `ROLE_ADMIN` check). The service checks run in a fixed order: D6 format with no DB call, then a case-sensitive lookup, then DELETED returns 404 before the ADMIN shortcut, then a non-owner gets 404. The 404 body is byte-identical for every not-visible cause, and the read-only transaction uses no `@Transactional`.
  - **QA:** `GetShortUrlIT` (ownership matrix, byte-identical 404 proof, case sensitivity, round trip, D71 check-after-409, pins for `Cache-Control` and HEAD) and 36 Cucumber scenarios. No defects.
  - **Review:** APPROVE in both rounds. The SHOULD findings were fixed: stale docs and unresolved Gherkin owners. R4 conflicts with a recorded decision, so it went to the engineer.
  - **Validation:** the orchestrator ran `./mvnw -q clean verify` (915 tests, 0 failures, LINE coverage 99.5%).
- **US-008 (Task 7, redirect):**
  - **Design:** approved at G2 (D75–D83).
  - **Implementation:** `RedirectController`/`RedirectService`.
    - `GET`/`HEAD /{code}` returns 302 with the raw, D75-encoded `Location` and exactly `Cache-Control: no-store`.
    - It returns an identical 404 for unknown, deactivated, deleted and malformed codes.
    - It never declares `produces` and never forwards the query string.
  - **Empirical check:** QA pinned Tomcat's real behaviour (C1 condition). It confirmed the architect's source reading and exposed R1: a long non-ASCII target made the redirect return a bare 500. The engineer approved D84, which limits the encoded form to 2048 bytes at create.
  - **Review:** round 1 was CHANGES_REQUIRED (R1, plus three test-guardrail gaps), followed by two fix rounds. The final review was APPROVE.
  - **Validation:** the orchestrator ran `./mvnw -q clean verify` (1156 tests, 0 failures, LINE coverage 99.56%).
- **US-009 (Task 8, lifecycle):**
  - **Design:** approved at G2 (D86–D90). The engineer chose the orchestrator's strict-boolean option over the architect's Jackson default.
  - **PATCH** `{"active": boolean}`: the D26 409s, and the D58 body.
  - **DELETE:** ADMIN only, with 403 before any lookup. It returns 204, sets `deleted_by` to the admin's username, and the code can never be reused.
  - **Transactions:** a `readWrite` template with one explicit flush. `OptimisticLockingFailureException` is caught outside it and becomes 409 `CONCURRENT_MODIFICATION`; nothing is logged at ERROR.
  - **Click data:** never overwritten.
  - **QA:** `LifecycleIT` and `LifecycleConcurrencyIT`: a race test accepting either 409 code, plus deterministic held-row-lock and serialised tests, and a click-race test. There are also D70/D88/D89 no-change tests and 37 Cucumber scenarios.
  - **Review:** APPROVE in both rounds. The fixes included the web-slice Jackson trap and some fixtures that could never fail.
  - **Validation:** the orchestrator ran `./mvnw -q clean verify` (1453 tests, 0 failures, LINE coverage 99.6%).
- **US-010 (Task 9, click recording):**
  - **Design:** approved at G2 (D91–D93).
  - **Schema:** V2 adds `click_event`, with three columns (D8).
  - **Recording:** GET only, never HEAD. `JpaClickRecorder` runs an atomic `click_count + 1` UPDATE that is guarded by `status = 'ACTIVE'` and never touches `version` or `updated_at`, then an INSERT, both in one `REQUIRES_NEW` transaction. The time is truncated to microseconds.
  - **Fail-open:** handled in `RedirectService`, which logs WARN with the code, id, class and SQLSTATE only.
  - **Redirect:** the response is byte-identical to before.
  - **QA:**
    - A controllable `TestClock` in the single shared context.
    - `ClickRecordingIT`, `ClickRecordingFailureIT` (a real PL/pgSQL trigger whose message contains the URL; no partial click) and `ClickRecordingConcurrencyIT` (50 concurrent clicks give exactly 50; the D91 state race; PATCH racing a real click).
    - 9 Cucumber scenarios.
  - **Review:** APPROVE in both rounds. R4 (SQLSTATE fallback) and R9 (monotonic `last_accessed_at`) went to the engineer.
  - **Validation:** the orchestrator ran `./mvnw -q clean verify` (1522 tests, 0 failures, LINE coverage 99.62%).
- **US-011 (Task 9, statistics API):**
  - **Design:** approved at G2 (D95–D105). Empirical testing on PostgreSQL 18.6 overturned the planning-stage recommendation to bucket days with `AT TIME ZONE` (PostgreSQL treats `CET` as a fixed abbreviation, inverts `+05:00` and matches names case-insensitively). Instead Java computes each local day's start instant and PostgreSQL counts with `width_bucket`; no zone string is sent to PostgreSQL (D95).
  - **Implementation:** `GET`/`HEAD /api/v1/urls/{code}/stats` with `timezone`, `from`, `to`; `StatsPeriod` validates and resolves the window before any database work (D104); a read-only REPEATABLE READ snapshot (D102); a `StatsParameter` enum so errors can never echo input (D99). Carry-overs: SQLSTATE fallback (R4) and `GREATEST` for `last_accessed_at` (D94).
  - **QA:** `StatsIT` (155 tests, including DST 23-hour, 25-hour and gap days in New York, Sydney, Sao Paulo and Apia, every JDK tzdb ID over HTTP, and a black-box REPEATABLE READ proof on a pooled connection) and 64 stats Cucumber scenarios.
  - **Review:** CHANGES_REQUIRED in rounds 1 and 2 (Done-story test listings not exact, a free-form parameter name replaced by an enum, stale notes); APPROVE in round 3.
  - **Validation:** the orchestrator ran `./mvnw -q clean verify` (Surefire 1052, Failsafe 866, 0 failures; LINE coverage 650/653).

---

## Scenario 3 — Ambiguous requirement: "URLs should expire after some time"

**Status:** Done (US-012, 2026-09-30). No code was written; the engineer answered every question before the impact analysis (Scenario 2) began.

### The requirement as given

> "URLs should expire after some time."

Taken literally, this cannot be implemented: it does not say *whether* every URL expires, *when*, *who decides*, or *what a visitor sees*. Guessing would bake product decisions into code and schema, and the V1/V2 migrations are forward-only, so a wrong guess is expensive to undo.

### Approach

1. Read the shipped behaviour that expiration would touch: the redirect (US-008), lifecycle and PATCH (US-009), click recording (US-010), stats (US-011), and decisions D1, D2, D4, D18, D34, D74.
2. List every question whose answer changes code, schema, API, or security, and pair each with a **non-binding** proposed default, its reason, and its trade-off.
3. Stop and ask the engineer. Scenario 2's impact analysis waited until every question was answered.

### Questions, proposed defaults, and the engineer's answers

The AI proposed a default for every question; the engineer answered **"accept all proposed defaults"**. Each answer is now a recorded decision.

| # | Question | Proposed default (non-binding when proposed) | Why / trade-off | Decision |
|---|---|---|---|---|
| E1 | Is expiration optional? | Yes, per link; no default TTL, so links never expire unless set. | Existing links and clients unchanged. A mandatory TTL would change every existing link. | D106 |
| E2 | How is it set? | Absolute `expiresAt` (ISO-8601 with offset) at create; strictly in the future; at most 10 years ahead; microsecond precision. | One precise format is simpler than also accepting a relative TTL. The cap avoids extreme dates (a lesson from US-011's date limits). | D107 |
| E3 | Generated codes, aliases, or both? | Both. | Nothing in the requirement distinguishes them. | D108 |
| E4 | What does the redirect return? | 410 Gone, `SHORT_URL_EXPIRED` (new error code). | The assignment suggests 410; D74 already accepts that a 404 does not hide existence. The 404 alternative is more private but less helpful to a legitimate visitor. | D109 |
| E5 | Can the 410 be cached? | No: `Cache-Control: no-store`. | 410 is heuristically cacheable; a later extension would otherwise stay invisible to cached clients. | D110 |
| E6 | Exact boundary? | Expired when `now >= expiresAt`, via the injected `Clock`. | Clear and testable. | D111 |
| E7 | New status, or computed? | Computed from `expires_at`; `status` unchanged. | No scheduler, no stale window, no race at the boundary. A stored `EXPIRED` status needs a background job. | D112 |
| E8 | Expired and deactivated/deleted? | Deleted/deactivated wins (404). Order: format → lookup → deleted/deactivated → expired → redirect. | Keeps D2's guarantee that a deactivated link looks unknown. | D113 |
| E9 | Can it change after create, and by whom? | Owner or ADMIN via the existing PATCH: set, extend, shorten, or clear. Absent = unchanged; `null` = clear. | Mirrors D4. Absent-vs-null needs explicit handling; PATCH must accept a body without `active`. | D114 |
| E10 | Can an expired link be revived? | Yes, by extending or clearing. | Avoids punishing an owner who forgot to extend. | D115 |
| E11 | Can an expired code be reused? | Never (D1). | Reuse would silently redirect old links elsewhere — a phishing risk. | D116 |
| E12 | Stats after expiry? | Owner/ADMIN still see details and stats; post-expiry visits get 410 and are not counted. | Analytics survive expiry; a 410 is not a successful click. | D117 |
| E13 | API representation? | Add `expiresAt` (`null` = never) and computed `expired`. | Additive; existing clients unaffected. | D118 |
| E14 | HEAD on an expired link? | 410, no body, not counted. | Consistent with D18. | D119 |
| E15 | Configured default TTL? | No, not now. | Can be added later without breaking changes. | D120 |
| E16 | Cleanup of expired links? | No hard delete (D1). | Storage growth becomes a production decision. | D121 |

### AI assistance and engineer review

- **AI contribution:** read the shipped code and decisions, framed 16 questions (the story required at least 8), and proposed defaults. It flagged two non-obvious points the literal requirement hides: 410 responses are heuristically cacheable (E5), and PATCH's absent-vs-`null` semantics (E9).
- **Engineer decision:** accepted all proposed defaults (D106–D121).
- **Validation:** US-012 AC1 (all eight required topics are covered: E4, E2/E9, E3, E11, E9, E1, E7, E12), AC2 (each default was labelled non-binding until answered), AC3 (no code, migration, entity or API change in this story).

---

## Scenario 2 — Brownfield: adding URL expiration

**Status:** Done. Impact analysis approved (US-013); implemented and approved (US-016, D128). The analysis and X1–X6 were approved as D122–D127.

The requirement being analysed is the one clarified in Scenario 3 (D106–D121). This analysis was written against the shipped code at commit `f89795f` (US-001–US-011 done).

### 1. What changes, module by module

| Layer | Class / file | Change | Why (decision) |
|---|---|---|---|
| Schema | new `V3__add_short_url_expires_at.sql` | Add nullable `expires_at TIMESTAMPTZ` plus a CHECK (see §2) | D106, D107 |
| Domain | `ShortUrl` | New `expiresAt` field (updatable). New `isExpiredAt(Instant now)` (`now >= expiresAt`, D111). New `changeExpiry(Instant newExpiry, Instant now)` that refuses deleted links (reusing `requireNotDeleted`) and sets `updated_at` | D111, D114 |
| Domain | `ShortUrlStatus` | **No change**: expiry is computed, not a status | D112 |
| Service | `RedirectService.lookup` | Read the `Clock` **once**, before the lookup. After the existing ACTIVE check, throw a new `ShortUrlExpiredException` if expired. Pass that same instant to `ClickRecorder` (today `resolveAndRecordClick` reads the clock a second time, after the lookup) | D109, D111, D113 |
| Repository | `ShortUrlRepository.RECORD_CLICK_SQL` | Add `AND (expires_at IS NULL OR expires_at > :clickedAt)`, so a link that expires between lookup and recording is not counted, the same idea as D91 | D117 |
| Service | `ShortUrlService.create` | Validate optional `expiresAt` with the injected `Clock`: strictly future, and at most the configured horizon (10 years). Store it at microsecond precision | D107 |
| Service | `ShortUrlService.setActive` → generalised update | Apply `active` and/or `expiresAt` in one `readWrite` transaction (same flush/catch pattern, D35). Absent = unchanged, `null` = clear, value = set (must be future and within the horizon) | D114, D115 |
| Service | `ShortUrlView` | Add `expiresAt`, and `expired` computed with the request's instant | D118 |
| Service | `loadVisible`, `stats`, `delete` | **No logic change.** Expired links stay visible to owner and ADMIN (D117); delete of an expired link works as today | D117 |
| API | `CreateShortUrlRequest` | Add optional `expiresAt` | D107 |
| API | `UpdateShortUrlRequest` | `active` becomes optional; add `expiresAt` with absent / `null` / value semantics; reject a body with neither | D114 |
| API | `ShortUrlResponse`, `ShortUrlStatsResponse` | Add `expiresAt` and `expired` (additive) | D118 |
| API | `RedirectController` | No change to the 302 path. A 410 path comes from the handler below | D109 |
| Errors | `ErrorCode` | Add `SHORT_URL_EXPIRED(410)` | D109 |
| Errors | `GlobalExceptionHandler` | Map `ShortUrlExpiredException` → 410 ProblemDetail **with `Cache-Control: no-store`**. HEAD → 410, empty body | D110, D119 |
| Config | new `ExpirationProperties` | `shortener.expiration.max-horizon` (default `P10Y`), validated at startup | D107 |
| Config | `JacksonConfig` | Strict date parsing for `expiresAt` (see §4, X2) | D59 |
| Docs | OpenAPI annotations | New fields, 410 on the redirect, absent-vs-`null` PATCH semantics | D118 |
| Security | `SecurityConfig` | **No change.** No new endpoint; the existing `/api/**` and public `/*` rules already admit everything | — |

### 2. Proposed V3 migration (sketch; exact SQL fixed in US-016's design)

```sql
-- V3: optional per-link expiration (D106-D112). NULL = never expires.
ALTER TABLE short_url ADD COLUMN expires_at TIMESTAMPTZ NULL;
ALTER TABLE short_url ADD CONSTRAINT ck_short_url_expires_after_created
  CHECK (expires_at IS NULL OR expires_at > created_at);
```

- **Forward-only and backward-compatible.** A nullable column with no default is a metadata-only change in PostgreSQL (no table rewrite). Every existing row gets `NULL`, meaning "never expires", which matches D106.
- **The CHECK** encodes the only rule that is timeless: an expiry cannot precede creation. "Strictly in the future at request time" and the 10-year horizon depend on the clock and on configuration, so they stay in the application.
- **Naming** follows the V1 convention (`ck_short_url_*`), and constraint tests assert the SQLSTATE and constraint name (CLAUDE.md).
- **No index** is needed. Every lookup is by `short_code` or `id`; `expires_at` is read from the row already loaded. There is no cleanup job to scan by expiry (D121).
- **Production note:** `ADD CONSTRAINT … CHECK` scans the table under a lock. That is trivial here; on a large table you would use `NOT VALID` and then `VALIDATE CONSTRAINT`. This is recorded for the roadmap.
- **Old code on the new schema:** Hibernate's `ddl-auto=validate` tolerates an extra column, so a rolling deploy (old app, V3 schema) keeps working.

### 3. API changes (all additive, except one deliberate reversal)

- **Create:** `{"originalUrl", "alias"?, "expiresAt"?}`. A past value, a value beyond the horizon, or a value with no offset is rejected with 400 and nothing is created.
- **PATCH:** `{"active"?, "expiresAt"?}` with at least one field present:
  - `{"expiresAt": "…"}` sets or extends the expiry;
  - `{"expiresAt": null}` clears it;
  - `{}` is still 400.
- **Responses:** create, details, PATCH and stats gain `expiresAt` (nullable) and `expired` (boolean).
- **Redirect:** an expired link gets 410 `SHORT_URL_EXPIRED` with `no-store`. Deleted and deactivated links are still 404, and take precedence (D113).
- **Deliberate reversal:** today a PATCH or create carrying `expiresAt` is rejected as an unknown field (400 `MALFORMED_REQUEST`, D59). After the change it is accepted. No client can depend on the old behaviour, because it always failed.

### 4. Design points (recommendations approved as D122–D127)

- **X1 — PATCH absent vs `null`.**
  - **Recommendation:** replace the `UpdateShortUrlRequest` record with a small request class that records whether `expiresAt` was present (a Jackson setter that sets a flag). Tests pin all three cases.
  - **Reason:** a Java record cannot tell a missing field from `null`, which is the whole difference between "unchanged" and "clear".
  - **Alternative:** the `jackson-databind-nullable` library (`JsonNullable<T>`).
  - **Trade-off:** the library is a new dependency and needs springdoc configuration; the hand-written class is about 20 lines, but the rule must be applied by hand.
- **X2 — Strict timestamps.**
  - **Recommendation:** type `expiresAt` as `OffsetDateTime`, accept only JSON strings with an explicit offset or `Z`, and reject numbers (Jackson accepts epoch numbers by default) and offset-less local date-times with 400 `MALFORMED_REQUEST`. This follows D59 and D89.
  - **Reason:** a number or a local time would be read in some implicit zone, which is exactly the class of silent bug US-011 removed.
  - **Alternative:** Jackson defaults.
  - **Trade-off:** stricter for clients.
- **X3 — Validation errors.**
  - **Recommendation:** a past or too-distant `expiresAt` gets 400 `VALIDATION_FAILED`, with `errors: [{field: "expiresAt", message}]`, never echoing the value (D56, D99). It is checked after `INVALID_URL` and `INVALID_ALIAS` (extends D63). Use the injected `Clock`, **not** Bean Validation's `@Future`.
  - **Reason:** `@Future` reads the system clock unless a `ClockProvider` is configured, so it would bypass `TestClock` and D45.
  - **Alternative:** a new `INVALID_EXPIRATION` code.
  - **Trade-off:** clients read `errors[]`, as they already do for stats (D99).
- **X4 — A redundant expiry change.**
  - **Recommendation:** PATCH with the same `expiresAt` the link already has returns 200 and writes nothing (no `version` or `updated_at` bump). The D26 409s stay only for `active`.
  - **Reason:** setting an expiry is naturally idempotent. Unlike deactivation, "already expires then" is not a conflict.
  - **Alternative:** 409, mirroring D26.
  - **Trade-off:** none of substance.
- **X5 — Mixed PATCH with a redundant `active`.**
  - **Recommendation:** `{"active": true, "expiresAt": …}` on an already-active link returns 409 `SHORT_URL_ALREADY_ACTIVE`, and **nothing** is applied, expiry included.
  - **Reason:** this keeps D26 unchanged, and one request is all-or-nothing.
  - **Alternative:** apply the expiry and ignore the redundant `active`.
  - **Trade-off:** clients combining both must send the correct `active`.
- **X6 — Setting an expiry in the past through PATCH.**
  - **Recommendation:** reject it (400), the same rule as create. To stop a link now, deactivate it.
  - **Reason:** one rule for "an expiry must be in the future".
  - **Alternative:** allow a past value, as "expire now".
  - **Trade-off:** none.

### 5. Regression risks

1. **The deliberate reversal breaks two US-009 (Done) tests.** Both currently list `{"expiresAt":"2030-01-01T00:00:00Z"}` as a body that must be rejected:
   - `LifecycleIT.shouldReturn400MalformedRequestForABodyThatIsNotAStrictBooleanDocumentAndChangeNothing`
   - `ShortUrlControllerWebMvcTest.shouldReturn400MalformedRequestWithoutParserDetailsForUnreadablePatchBodies`

   The entry must move out of the "reject" list into a new "accepted" test, and the change must be listed in US-009 by method name.
2. **PATCH `{}` and `{"active": null}`** must still give 400. Today `@NotNull` on `active` produces `VALIDATION_FAILED` with field `active`. After `active` becomes optional, `{}` needs an explicit "at least one field" check. `{"active": null}` must still be refused, because `active` cannot be cleared. There are existing tests for both, and their expected error shape must not change.
3. **The pinned click SQL** in `RepositoryAnnotationsTest.EXPECTED_CLICK_SQL` (US-010/US-011 Done) changes, because the new guard is added.
4. **Exact schema tests** in `ShortUrlSchemaTest.shouldCreateShortUrlColumnsExactlyAsSpecified` and `shouldDeclareAllNamedConstraints` (US-002 Done) must add `expires_at` and the new CHECK.
5. **The exact error catalogue** in `ErrorCodeTest.shouldContainExactlyTheD31AndD61CatalogueInOrder` (US-005/US-006 Done) must add `SHORT_URL_EXPIRED`.
6. **The OpenAPI schema field lists** in `OpenApiDocsIT` must add the new fields and the 410 response.
7. **The create/GET byte-equality round trip** (US-007) still holds for links with no expiry. A link that crosses its expiry between the two calls would differ in `expired`, so tests of it must fix `TestClock`.
8. **The 302 path must stay byte-identical** (D75, D76). `RedirectIT`'s exact-header assertions must pass unchanged for non-expired links.
9. **A new counting race:** a link that expires between lookup and click recording must not be counted. The guard in `RECORD_CLICK_SQL` covers it, with a held-lock test like D91's.
10. **Redirect performance:** there is no new query. `expires_at` is in the row already fetched by `findByShortCode`, so the cost is one `Instant` comparison.
11. **Stats** must not change. Clicks recorded before expiry remain counted, and post-expiry 410s are never recorded (D117). No stats query changes.
12. **Time in tests:** every expiry test must use `TestClock` (the placeholder pointer), never the system clock. The boundary is exact (D111), so tests cover `expiresAt − 1µs`, `== expiresAt` and `+ 1µs`.

### 6. Tests required (new)

| Type | Behaviour | Owner (main session) |
|---|---|---|
| Unit | `ShortUrl.isExpiredAt` boundary; `changeExpiry` on deleted (throws), on active/deactivated, set/clear | main session |
| Unit | Service: create validation (past, now, +1µs, horizon, horizon + 1µs); PATCH absent/null/value; X4, X5, X6 | main session |
| Repository | V3 columns and CHECK (SQLSTATE `23514`, constraint name); click UPDATE not counting after expiry; pinned SQL | main session |
| Web slice | Strict `expiresAt` parsing (numbers, local date-times, offsets); PATCH `{}` still 400; 410 mapping with `no-store` | main session |
| Integration (`*IT`) | Redirect: 302 before expiry, 410 at and after (GET and HEAD), 404 when deactivated or deleted even if expired (D113), not counted after expiry, revival by extend and clear (D115), expired code never reusable (D116); stats and details still 200 after expiry (D117); `TestClock`-driven boundaries | main session |
| Integration (`*IT`) | Race: expiry between lookup and recording is not counted (held lock) | main session |
| Cucumber | One scenario per US-016 acceptance criterion (CLAUDE.md) | main session |
| Regression | The full existing suite passes, with only the listed Done-story test edits | main session |

### 7. Backward-compatible rollout plan

1. **Deploy V3 first.** The column is nullable and every row is `NULL` (never expires). The currently running app is unaffected, because `validate` ignores extra columns.
2. **Deploy the application.** Existing clients see only two added response fields (`expiresAt: null`, `expired: false`), and every existing request still behaves identically, apart from the previously always-rejected `expiresAt` field.
3. **Opt in.** Clients start sending `expiresAt` on create or PATCH. Nothing expires until someone sets an expiry.
4. **Rollback.** Before any expiry has been set, rolling the app back is safe, because the old app ignores the column. After expiries exist, the old app would redirect expired links, which fails open for availability. The column is never dropped (migrations are forward-only).

### 8. Proposed implementation story (US-016), for creation on approval

US-016 "Implement URL expiration" covers §1–§7. It needs a design gate for X1–X6 and the exact V3 SQL. The `depends_on` of US-015 gains US-016, so the final docs are written last.

> Scenario 3 is performed before Scenario 2 because the expiration requirement must be clarified before its impact can be analysed.

### 9. Implementation and what the analysis predicted (US-016)

- **Predicted and confirmed:** the two US-009 tests that rejected `expiresAt` had to flip (§5.1); the pinned click SQL, the exact schema tests and the error catalogue changed (§5.3–§5.5); the redirect needed a single clock read (§1); the 302 path stayed byte-identical (§5.8, `RedirectIT` unchanged and green); the empty-PATCH body stayed identical (§5.2, its test unchanged).
- **Not predicted:**
  - Web slices could not load an advice that depended on a service bean, so the expiry error now carries its rule text.
  - Exact response-key sets were duplicated in 10 test classes (ITs, slices and Cucumber steps), and all had to gain the two fields.
  - A US-010 test pinned fail-open for a failing clock. That is no longer possible once the clock decides expiry; the engineer accepted the change as D128.
- **Validation:** 1140 unit/slice/repository tests and 904 integration tests (255 Cucumber scenarios) pass; coverage 99.34%.
