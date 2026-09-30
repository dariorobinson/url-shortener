# Engineering Scenarios

This document records the three required engineering scenarios.

---

## Scenario 1 — Greenfield: initial URL-shortening capability

**Status:** In progress.

### Requirement understanding
See [requirements.md](requirements.md). The ambiguous requirements were resolved with the engineer before any code was written (decisions D1–D14).

### Decomposition

| Task | Scope | Status |
|---|---|---|
| 0 | Repository and documentation skeleton | Done (pending review) |
| 1 | Maven project, Spring Boot app, Flyway, Docker Compose, Testcontainers base | Done (US-001) |
| 2 | Domain model, V1 schema, repository | Done (US-002) |
| 3 | Short-code generator | Done (US-003) |
| 4 | URL and alias validation | Done (US-004) |
| 5 | Security foundation (`USER` / `ADMIN`, 401/403) | Done (US-005) |
| 6 | Create/read API and error handling | Done (US-006 create, US-007 read) |
| 7 | Redirect | Done (US-008) |
| 8 | Deactivate/reactivate and soft delete | Done (US-009) |
| 9 | Analytics | Click recording done (US-010); stats are US-011 |

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

---

## Scenario 3 — Ambiguous requirement: "URLs should expire after some time"

**Status:** Not started (Task 10). No code will be written until the open questions are answered by the engineer.

---

## Scenario 2 — Brownfield: adding URL expiration

**Status:** Not started (Tasks 11–12). An impact analysis of the existing codebase is performed and approved before any code changes.

> Scenario 3 is performed before Scenario 2 because the expiration requirement must be clarified before its impact can be analysed.
