---
id: US-016
title: Implement URL expiration
status: Done
plan_task: 12
depends_on: [US-012, US-013]
requirements: [FR-4, FR-9, D106, D107, D108, D109, D110, D111, D112, D113, D114, D115, D116, D117, D118, D119, D122, D123, D124, D125, D126, D127]
requires_design_approval: true
---

# US-016: Implement URL expiration

## User story
As a link owner, I want to give a short URL an optional expiry time that I can later change or remove, so that a link stops redirecting when it is no longer valid, without losing its code or its analytics.

## Acceptance criteria
- **AC1 (create):** Given an authenticated USER, when they `POST /api/v1/urls` with an optional `expiresAt` (ISO-8601 string with an explicit offset or `Z`), then the link is created with that expiry, stored at microsecond precision; without `expiresAt` the link never expires (D106, D107, D108).
- **AC2 (create validation):** Given `expiresAt` is not strictly in the future, or is beyond the configured horizon (default 10 years), then create returns `400 VALIDATION_FAILED` with an `errors` entry for `expiresAt` that does not echo the value, and nothing is created (D124). A number or an offset-less date-time returns `400 MALFORMED_REQUEST` (D123).
- **AC3 (redirect):** Given an ACTIVE link whose `expiresAt` is at or before now, when anyone requests `GET /{code}`, then the response is `410` with `errorCode` `SHORT_URL_EXPIRED` and `Cache-Control: no-store`; `HEAD` returns `410` with no body; neither is counted as a click (D109–D111, D117, D119). One instant earlier, the link still redirects with the unchanged 302.
- **AC4 (precedence):** Given a link that is both expired and deactivated or deleted, the redirect returns the D2 `404` (D113).
- **AC5 (PATCH):** Given the owner or ADMIN, when they `PATCH` with `{"expiresAt": "..."}`, the expiry is set, extended or shortened; with `{"expiresAt": null}` it is cleared; with the field absent it is unchanged; a body with neither `active` nor `expiresAt` returns `400` (D114, D122). A past value returns `400` (D127). The same value returns 200 with no write (D125). A redundant `active` fails the whole request with the D26 `409` (D126). A non-owner gets `404` (D4).
- **AC6 (revival and reuse):** Given an expired link, when its expiry is extended or cleared, then it redirects again (D115); an expired code can never be taken by a new link (D116).
- **AC7 (representation and analytics):** Create, details, PATCH and stats responses include `expiresAt` (`null` = never) and `expired` (computed at request time); details and stats of an expired link still return 200 to owner and ADMIN (D117, D118).
- **AC8 (race):** Given a link that expires between the redirect's lookup and the click recording, the click is not counted (D117).
- **AC9 (schema):** V3 adds nullable `expires_at` and `ck_short_url_expires_after_created`; existing rows are unaffected (never expire).

## Tests required
See `docs/scenarios.md` Scenario 2 §6. Every AC has a Cucumber scenario; boundaries use `TestClock` (`expiresAt − 1µs`, `==`, `+ 1µs`); every edit to a Done story's test is listed by method name in that story.

## Out of scope
- A configured default TTL (D120) and cleanup of expired links (D121).

## Risks
See `docs/scenarios.md` Scenario 2 §5.

## Design note
*(main session, 2026-09-30. Status: **approved at G2 (2026-09-30)**. Builds on the approved analysis in `docs/scenarios.md` Scenario 2 and on D106–D127.)*

### 1. V3 migration (exact SQL)

`src/main/resources/db/migration/V3__add_short_url_expires_at.sql`:

```sql
-- V3: optional per-link expiration (D106, D107, D112). NULL means the link never expires.
-- Expiry is computed at request time (now >= expires_at, D111); there is no EXPIRED status and no job.
-- ck_short_url_expires_after_created holds the only timeless rule; "strictly in the future" and the
-- 10-year horizon depend on the clock and on configuration, so they are enforced by the application (D107).
ALTER TABLE short_url ADD COLUMN expires_at TIMESTAMPTZ NULL;
ALTER TABLE short_url ADD CONSTRAINT ck_short_url_expires_after_created
  CHECK (expires_at IS NULL OR expires_at > created_at);
```

### 2. Domain (`ShortUrl`)
- New mapped field `expiresAt` (`expires_at`, updatable).
- **A new 6-argument `create(..., Instant expiresAt)` overload.** The existing 5-argument factory delegates to it with `null`, so its 23 call sites (mostly Done-story tests) are unchanged.
- `boolean isExpiredAt(Instant now)`: `expiresAt != null && !now.isBefore(expiresAt)` (D111).
- `boolean changeExpiry(Instant newExpiry, Instant now)`:
  - it calls `requireNotDeleted()` first (D46);
  - it returns `false` and changes nothing if the value is equal after truncation to microseconds (D125);
  - otherwise it sets the value and `updatedAt`.

### 3. Validation (service, injected `Clock`; D107, D124, D127)
- New `ExpirationProperties` holds `shortener.expiration.max-horizon`, a `java.time.Period` with default `P10Y`. It must be positive, and is checked at startup.
- A value is accepted only if `truncate(expiresAt) > truncate(now)` **and** `expiresAt <= now (UTC) + maxHorizon`.
  - **The comparison is on the truncated values.** An `expiresAt` 500 ns after `now` would otherwise pass the application check, then equal `created_at` after truncation and break the V3 CHECK with a 500.
- Failures throw `InvalidExpirationException`. It carries **no data**, so it cannot echo input (CLAUDE.md), and maps to `400 VALIDATION_FAILED` with `errors: [{field: "expiresAt", message: "must be in the future and at most 10 years ahead"}]`. The message is built from the configured horizon.
- **Order on create:** `INVALID_URL`, then `INVALID_ALIAS`, then expiry (D124). **On PATCH:** expiry is validated before the lookup, so a 400 comes before a 404, as D104 does for stats.

### 4. Strict `expiresAt` parsing (D123)
- A dedicated `StrictOffsetDateTimeDeserializer` is attached to the `expiresAt` properties with `@JsonDeserialize`.
- It accepts only `VALUE_STRING` parsed with `DateTimeFormatter.ISO_OFFSET_DATE_TIME`. Numbers, local date-times, `Z`-less strings, empty strings, objects and arrays all become `MALFORMED_REQUEST`.
- **Why a field-level deserializer rather than a global coercion config:** the JSR-310 deserializers accept epoch numbers through their own code paths. A deserializer on the field makes the rule explicit and testable, and it can't affect other date fields.

### 5. PATCH request (D114, D122)
- `UpdateShortUrlRequest` changes from a record to a small class with `@JsonSetter` methods for `active` and `expiresAt`. Each setter also sets a `present` flag, so both absent-vs-`null` distinctions are available.
- Unknown fields still fail (D59), and `active` keeps D89 strict booleans.
- The outcome for each body:

  | Body | Result |
  |---|---|
  | neither field present | `400 VALIDATION_FAILED`, `errors: [{field: "active", message: "must not be null"}]`. **Byte-identical to today**, so `LifecycleIT.shouldReturn400ValidationFailedNamingTheActiveFieldWhenItIsMissingOrNullAndChangeNothing` passes unchanged. |
  | `active` present but `null` | the same 400, as today |
  | `expiresAt` absent | unchanged |
  | `expiresAt` `null` | clear the expiry |
  | `expiresAt` with a value | set it (validated per §3) |

- **Service method** `update(code, UpdateCommand, caller)` replaces `setActive`. `UpdateCommand` is `Optional<Boolean> active`, plus `boolean expiryPresent` and `Instant expiresAt`. It runs in the existing `readWrite` template, with one flush and one catch (D35):
  1. the `active` transition first, which throws the D26 409 if redundant, before anything is applied (D126);
  2. then the expiry change;
  3. if nothing changed (an expiry-only PATCH with the same value, D125), there is no flush and no write.

### 6. Redirect (D109–D113, D117, D119)
- `RedirectService` reads `clock.instant()` **once** per request. `lookup(code, now)` keeps the order format → lookup → not ACTIVE (404) → **`isExpiredAt(now)` → `ShortUrlExpiredException`** → resolved.
- `resolveAndRecordClick` passes that same `now` to `ClickRecorder.record` (today it reads the clock a second time).
- `RECORD_CLICK_SQL` gains `AND (expires_at IS NULL OR expires_at > :clickedAt)`, so a link that expires between lookup and recording is not counted (AC8).
- `GlobalExceptionHandler` maps `ShortUrlExpiredException` to `410` with `ErrorCode.SHORT_URL_EXPIRED` through `ProblemDetails.of`, and adds `Cache-Control: no-store` (D110). HEAD gets 410 with no body; Spring strips the body for HEAD.
- The 302 path is not touched, so `RedirectIT`'s exact-header assertions stay valid.

### 7. Representation (D118)
- `ShortUrlView` gains `expiresAt` and `expired`. `ShortUrlView.from(url, now)` takes the request's instant (4 call sites, all in `ShortUrlService`).
- `ShortUrlResponse` and `ShortUrlStatsResponse` gain `expiresAt` (ISO-8601 UTC, `null` = never) and `expired`. They are appended after the existing fields.
- `ErrorCode.SHORT_URL_EXPIRED(410)` is **appended at the end** of the enum, so no existing ordinal or order-dependent assertion moves except the exact catalogue test.

### 8. Done-story tests expected to change (each will be listed by method in its story)

| Story | Test | Change |
|---|---|---|
| US-009 | `LifecycleIT.shouldReturn400MalformedRequestForABodyThatIsNotAStrictBooleanDocumentAndChangeNothing` | Remove `{"expiresAt":"2030-01-01T00:00:00Z"}` from the rejected list (now valid) |
| US-009 | `ShortUrlControllerWebMvcTest.shouldReturn400MalformedRequestWithoutParserDetailsForUnreadablePatchBodies` | Same entry removed; the `doesNotContain("expiresAt")` check is adjusted accordingly |
| US-002 | `ShortUrlSchemaTest.shouldCreateShortUrlColumnsExactlyAsSpecified`, `shouldDeclareAllNamedConstraints` | Add `expires_at` and `ck_short_url_expires_after_created` |
| US-005/006 | `ErrorCodeTest.shouldContainExactlyTheD31AndD61CatalogueInOrder`, `shouldMapEachCodeToItsFixedHttpStatus` | Add `SHORT_URL_EXPIRED` (410) |
| US-010/011 | `RepositoryAnnotationsTest` pinned `EXPECTED_CLICK_SQL` | Add the expiry guard |
| US-006/007/011 | `OpenApiDocsIT` schema/field assertions; exact response-key assertions in ITs and slices | Add `expiresAt` and `expired` |
| US-009 | Service and slice tests calling `setActive` | Renamed call to `update` |

Any further Done-story test edit found during implementation is listed the same way.

### 9. Risks
- **The absent-vs-`null` setter class is custom code.** Tests pin all six body shapes, over real HTTP as well as in the web slice.
- **The truncation edge (§3)** is the one place a naive check would cause a 500. A unit test and an IT sit at exactly `now + 500 ns`.
- **Time in tests:** every expiry test drives `TestClock`, and fixes it for boundary cases.

## Implementation notes
*(main session, 2026-09-30; no agents)*

**Production (new):** `V3__add_short_url_expires_at.sql`; `config/ExpirationProperties`; `service/ExpirationPolicy`; `service/UpdateShortUrlCommand`; `api/dto/StrictOffsetDateTimeDeserializer`; `service/exception/InvalidExpirationException`, `ShortUrlExpiredException`, `InvalidUpdateRequestException`.

**Production (modified):** `domain/ShortUrl` (`expiresAt`, 6-arg `create`, `isExpiredAt`, `changeExpiry`); `service/ShortUrlService` (`create` with expiry, `update` replaces `setActive`, `now` passed to views); `service/RedirectService` (one clock read, 410 path); `repository/ShortUrlRepository` (click SQL guard); `service/ShortUrlView`, `ShortUrlStats`, `CreateShortUrlCommand` (new components plus backward-compatible constructors); `api/dto/CreateShortUrlRequest`, `UpdateShortUrlRequest` (record → presence-tracking class), `ShortUrlResponse`, `ShortUrlStatsResponse`; `api/ShortUrlController`, `RedirectController` (OpenAPI); `api/error/ErrorCode` (`SHORT_URL_EXPIRED`), `GlobalExceptionHandler` (410 with `no-store`, expiry and empty-PATCH handlers); `config/ValidationConfig`.

**Deviations from the design note:**
- `InvalidExpirationException` carries the rule text (fixed server text from configuration, never client input) instead of no data. With no data, the advice would have needed the `ExpirationPolicy` bean, which `@WebMvcTest` slices do not load, and every slice failed to start.
- `HEAD`'s empty body is asserted over real Tomcat (`ExpirationIT`), not in the web slice: MockMvc does not strip HEAD bodies.
- **Behaviour change (accepted by the engineer as D128):** because the redirect must read the clock to decide expiry, a clock failure now fails the redirect instead of failing open (US-010 had pinned fail-open for a failing clock). `Clock.systemUTC()` does not fail in practice.

**New tests:** `ShortUrlExpiryTest` (9), `ExpirationPolicyTest` (10), `ExpirationPropertiesTest` (9), `ExpiresAtParsingTest` (17), `ShortUrlServiceExpiryTest` (14), `RedirectServiceExpiryTest` (4), `ShortUrlExpiryRepositoryTest` (7), `ShortUrlExpiryWebMvcTest` (15), `RedirectExpiryWebMvcTest` (2), `ExpirationIT` (23), `expiration.feature` (12 scenarios, 15 with examples).

**AC → tests:** AC1 `ExpirationIT` create tests, `ShortUrlServiceExpiryTest`, Cucumber "Creating a short URL with/without an expiry"; AC2 `ExpirationPolicyTest`, `ExpiresAtParsingTest`, `ExpirationIT` 400 tests, Cucumber outlines; AC3 `RedirectServiceExpiryTest`, `RedirectExpiryWebMvcTest`, `ExpirationIT` boundary/no-count tests, Cucumber GET and HEAD scenarios; AC4 `RedirectServiceExpiryTest`, `ExpirationIT`, Cucumber; AC5 `ShortUrlServiceExpiryTest`, `ShortUrlExpiryWebMvcTest`, `ExpirationIT`, Cucumber; AC6 `ExpirationIT`, Cucumber; AC7 `ShortUrlServiceExpiryTest`, `ExpirationIT`, Cucumber; AC8 `ShortUrlExpiryRepositoryTest.shouldNotCountAClickAtOrAfterTheExpiry` plus `RedirectServiceExpiryTest` (the check and the click use one instant) and the AC3 no-count scenario; AC9 `ShortUrlSchemaTest`, `ShortUrlExpiryRepositoryTest` (named CHECK, SQLSTATE 23514), `ExpirationIT.shouldStoreCreatedAtTruncatedAndTheExpiryStrictlyAfterIt`.

**Done-story test edits:** listed by method in US-002, US-005 (pointer), US-006, US-007, US-008, US-009, US-010 and US-011, under "Post-completion change (… US-016 …)".

**Final build (main session, clean):** `./mvnw -o clean verify` — exit 0; Surefire 1140/0, Failsafe 904/0 (CucumberIT 255, ExpirationIT 23), 0 skipped; merged LINE coverage 750/755 (99.34%); 0 "Failing row contains".

## Review log
| Round | ID | Severity | Finding | Resolution |
|---|---|---|---|---|
