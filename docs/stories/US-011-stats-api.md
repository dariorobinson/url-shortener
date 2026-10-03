---
id: US-011
title: Statistics API
status: Done
plan_task: 9
depends_on: [US-005, US-007, US-010]
requirements: [FR-5, FR-12, D8, D10, D19, D31, D94, D95, D96, D97, D98, D99, D100, D101, D102, D103, D104, D105]
requires_design_approval: true
---

# US-011: Statistics API

## User story
As the owner of a short URL (or an ADMIN), I want to see total clicks and a per-day breakdown in my own time zone, so that I can understand how the link is being used.

## Acceptance criteria
- **AC1:** Given the caller owns the link, when `GET /api/v1/urls/{code}/stats?timezone=America/New_York` is called, then the response is `200 OK` with the D101 fields `{shortCode, timezone, from, to, totalClicks, clicksInRange, lastAccessedAt, daily: [{date, clicks}]}`. `totalClicks` is the all-time `click_count`, `lastAccessedAt` is a UTC instant or `null`, and `daily` is bucketed using the `America/New_York` calendar day (D95), correct across a DST transition, proven with data straddling one.
- **AC2:** Given no `timezone` parameter is supplied, when `GET /api/v1/urls/{code}/stats` is called, then buckets default to `UTC` (D10, D96) and the response `timezone` field is `"UTC"` (D101).
- **AC3:** Given `timezone` is present and is not exactly `UTC` or an ID in the JDK's `ZoneRulesProvider.getAvailableZoneIds()` (D96: case-sensitive, no trimming; this accepts `America/New_York`, `Asia/Calcutta`, `Etc/GMT+5`; it rejects the empty string, offsets such as `+05:00` or `Z`, prefixed offsets such as `UTC+5` or `GMT-3`, `UT`, short IDs such as `PST`, wrong case such as `america/new_york`, and garbage), when `GET /api/v1/urls/{code}/stats` is called, then the response is `400 Bad Request` with `errorCode: "VALIDATION_FAILED"` and an `errors` entry with `field: "timezone"` that does not echo the submitted value (D99, D56). No `INVALID_TIMEZONE` code exists.
- **AC4:** Given the caller is an authenticated `USER` who does not own the link, when the stats endpoint is called, then the response is `404 Not Found` with `errorCode: "SHORT_URL_NOT_FOUND"` (D4).
- **AC5:** Given the caller has role `ADMIN`, when the stats endpoint is called for any link, then the response is `200 OK`.
- **AC6:** Given `{code}` does not exist or belongs to a `DELETED` link, when the stats endpoint is called by any caller, then the response is `404 Not Found`.
- **AC7:** Given no credentials are supplied, when `GET /api/v1/urls/{code}/stats` is called, then the response is `401 Unauthorized` with `errorCode: "AUTHENTICATION_REQUIRED"` (D31, per US-005).
- **AC8:** Given a link has clicks on day 1 and day 3 of a 3-day window but none on day 2, when `GET /api/v1/urls/{code}/stats` is called for that window, then `daily` includes day 2 with `clicks: 0` rather than omitting it (D19, D101: one entry per local date in `[from, to]`, in ascending order). This also holds for a local date that has no clicks because it was skipped by a zone transition (D95).
- **AC9:** Given `from` and `to` are omitted, when the stats endpoint is called, then `to` is today in the resolved zone (`LocalDate.ofInstant(clock.instant(), zone)`), `from` is `to − 29`, each default is applied independently of the other, and the response echoes the resolved `timezone`, `from` and `to`, with `daily` holding 30 entries when both are omitted (D98, D101).
- **AC10:** Given `from` and `to` are valid `yyyy-MM-dd` dates, when the stats endpoint is called, then both are inclusive local dates in the caller's zone, `from == to` returns exactly one `daily` entry, and future dates are accepted and return `clicks: 0` (D97).
- **AC11:** Given a window longer than 366 days (`to − from + 1 > 366`), when the stats endpoint is called, then the response is `400` with `VALIDATION_FAILED` and `errors[].field` `from`; a window of exactly 366 days returns `200` (D98).
- **AC12:** Given `from` or `to` is not a strict ISO date (e.g. `2026-02-30`, `2026-2-3`, `20260203`, `2026-02-03T00:00`) or lies outside `1970-01-01` to `9999-12-31` (e.g. `1969-12-31`, `0000-01-01`), when the stats endpoint is called, then the response is `400` with `VALIDATION_FAILED` and an `errors` entry whose `field` is the offending parameter (`from` or `to`), never echoing the value (D98, D99).
- **AC13:** Given `from` is after `to`, when the stats endpoint is called, then the response is `400` with `VALIDATION_FAILED` and `errors[].field` `from` (D98, D99).
- **AC14:** Given the request has a query parameter outside `timezone`, `from`, `to` (e.g. `timeZone`, `tz`; exact case), or a known parameter sent more than once, when the stats endpoint is called, then the response is `400` with `errorCode: "MALFORMED_REQUEST"` and the parameter name is not echoed (D100).
- **AC15:** Given invalid parameters and a `{code}` that the caller cannot see (non-existent, `DELETED`, or owned by someone else), when the stats endpoint is called, then the response is `400` (parameters are validated first), not `404`; and given valid parameters for the same `{code}`, the response is the identical `404 SHORT_URL_NOT_FOUND` as AC4 and AC6. Neither order reveals whether the link exists or who owns it (D104, D74).
- **AC16:** Given the link has status `DEACTIVATED` and the caller is its owner or an `ADMIN`, when the stats endpoint is called, then the response is `200 OK` with the link's stats; a non-owner `USER` still gets `404 SHORT_URL_NOT_FOUND` (D105, D4, D13).

## Tests required
| Type | Behaviour proven | Owner |
|---|---|---|
| Unit | Timezone parameter validation per D96 (`UTC`, region IDs, backward links and `Etc/GMT+5` accepted; empty, offsets, prefixed offsets, `UT`, short IDs, wrong case, whitespace and garbage rejected) (AC3) | mid-engineer |
| Unit | `StatsPeriod` defaults from the injected `Clock` (`to` = today in the zone, `from` = `to − 29`, each independent), inclusive dates, 366-day maximum (365 days before `to` accepted, 366 rejected), 1970-01-01 to 9999-12-31 bounds, strict date format, `from` after `to`, every failure collected, fixed rule texts never echo the value (AC9, AC10, AC11, AC12, AC13) | mid-engineer |
| Unit | Day-start instants and densification: one entry per date, zero days filled from the bucket index, index outside `1..N` throws `IllegalStateException` (AC8, AC10) | mid-engineer |
| Web slice | Status codes, ownership/role enforcement, default-timezone behaviour (AC2, AC4, AC5, AC6) | mid-engineer |
| Web slice | Response shape per D101 and `VALIDATION_FAILED` with sorted `errors` for bad values (AC1, AC3, AC11, AC12, AC13) | mid-engineer |
| Web slice | Unknown or repeated query parameter names give `400 MALFORMED_REQUEST` with no name echoed (AC14); bad parameters on an unseen code give 400 and never reach the service's lookup (AC15) | mid-engineer |
| Web slice / service | `DEACTIVATED` link stats returned to owner and ADMIN, 404 for a non-owner (AC16) | mid-engineer |
| Repository | `EXPLAIN` of the exact stats SQL with `enable_seqscan` off uses `ix_click_event_short_url_id_clicked_at` with an `Index Cond` on `short_url_id` and `clicked_at`; the SQL literal contains no `AT TIME ZONE`, `timezone(` or zone parameter (D103, D95) | mid-engineer |
| Cucumber | Owner requests stats with an explicit `timezone` and gets 200 with total/last-accessed/daily breakdown (AC1) | qa-tester |
| Cucumber | Stats with no `timezone` defaults to UTC bucketing (AC2) | qa-tester |
| Cucumber | Invalid `timezone` (offset or garbage) returns 400 `VALIDATION_FAILED` with `errors[].field` `timezone` (AC3; covers accepted forms such as `Asia/Calcutta` and `Etc/GMT%2B5` too) | qa-tester |
| Cucumber | Non-owner `USER` requests stats for another's link: 404 `SHORT_URL_NOT_FOUND` (AC4) | qa-tester |
| Cucumber | `ADMIN` requests stats for any link: 200 (AC5) | qa-tester |
| Cucumber | Unknown or `DELETED` `{code}`: 404 (AC6) | qa-tester |
| Cucumber | No credentials: 401 `AUTHENTICATION_REQUIRED` (AC7) | qa-tester |
| Cucumber | A window with a zero-click day in the middle still lists that day with `clicks: 0` (AC8) | qa-tester |
| Cucumber | Omitted `from`/`to` return the last 30 days ending today with resolved values echoed (AC9) | qa-tester |
| Cucumber | `from == to` returns one entry; a future window returns zero counts (AC10) | qa-tester |
| Cucumber | A 366-day window returns 200; a 367-day window returns 400 `VALIDATION_FAILED` on `from` (AC11) | qa-tester |
| Cucumber | Malformed or out-of-bounds `from`/`to` return 400 `VALIDATION_FAILED` naming the parameter (AC12) | qa-tester |
| Cucumber | `from` after `to` returns 400 `VALIDATION_FAILED` on `from` (AC13) | qa-tester |
| Cucumber | Unknown parameter (`timeZone`) and repeated parameter return 400 `MALFORMED_REQUEST` (AC14) | qa-tester |
| Cucumber | Bad parameters on an unknown or another user's code return 400; valid parameters return the identical 404 (AC15) | qa-tester |
| Cucumber | Owner and ADMIN get 200 for a `DEACTIVATED` link; a non-owner gets 404 (AC16) | qa-tester |
| Integration (`*IT`) | Daily bucketing against Testcontainers PostgreSQL with Java-computed day-start instants and `width_bucket` counting, and no zone string sent to PostgreSQL (D95). DST coverage of a 23-hour day, a 25-hour day and a day skipped or shortened by a gap, in at least two zones, one of them southern-hemisphere (AC1); boundary-instant fixtures at the exact day edges; and a zero-click gap day (AC8) | qa-tester |
| Integration (`*IT`) | `totalClicks` comes from `click_count` (one deliberately divergent fixture) and `clicksInRange` equals the sum of `daily` (D101, AC1) | qa-tester |

## Out of scope
- Recording clicks — see US-010.
- Any UI/visualization of stats.

## Risks
- Day boundaries are computed in Java (`LocalDate.atStartOfDay(zone)`) and PostgreSQL only counts with `width_bucket` (D95). DST correctness must still be tested against real transition dates (23-hour, 25-hour and gap days), not an arbitrary date, or an off-by-one-hour bug could pass review unnoticed.
- In tests, a `+` in a zone ID such as `Etc/GMT+5` must be sent as `%2B`, or it is decoded as a space and rejected (D96).
- DST-hour fixtures must be bound as UTC `OffsetDateTime` values so the session zone cannot shift them.
- Zone rules come only from the JDK's tzdata (D95), so a government rule change reaches stats only with a JDK update, not with a database image update.

## Open questions
- **Resolved (D97, D98):** the `from`/`to` semantics. Inclusive `yyyy-MM-dd` local dates; `to` defaults to today and `from` to `to − 29`; maximum 366 days; dates between 1970-01-01 and 9999-12-31.
- **Resolved (D101, D19):** the response JSON shape. An array of `{date, clicks}` inside the D101 object, with zero-click days included.
- **Resolved (D99):** an invalid timezone returns `VALIDATION_FAILED`; no `INVALID_TIMEZONE` code is added and the D31 catalogue is unchanged.

## Carry-over from US-010 (engineer-approved at US-010 G3)
- **R4 (SHOULD, mid-engineer):** `PostgresServerErrors.sqlState` falls back to the first `java.sql.SQLException.getSQLState()` in the cause chain when there is no `ServerErrorMessage`, so a lost connection logs `08006` instead of `none`. `isUniqueViolation` stays server-message-only. Tests:
  - Split `PostgresServerErrorsTest`'s "empty sqlState for null, no PSQLException or no server message" test into "empty for null or no `SQLException`" and "falls back to the client SQLSTATE". Add a plain `SQLException` case wrapped by Spring.
  - `RedirectServiceTest`: add a fail-open variant, for example `CannotCreateTransactionException` wrapping `PSQLException(CONNECTION_FAILURE)`, that expects `sqlState=08006`.
  - Add a regression test: `isUniqueViolation` is false for a client-side `PSQLException` whose state is `23505`.
- **R9 / D94 (low priority, mid-engineer):**
  - Change `ShortUrlRepository.RECORD_CLICK_SQL` to `last_accessed_at = GREATEST(last_accessed_at, :clickedAt)`.
  - Update the pinned literal in `RepositoryAnnotationsTest`.
  - Add `ShortUrlRepositoryTest.shouldNotMoveLastAccessedAtBackwards`: record clicks in reverse time order and check the count is 2 and `last_accessed_at` is the later instant.
  - Reword US-010 AC1 to "the latest click time", and list every US-010 test change one by one in US-010's Post-completion section. US-010 is a Done story.
- **N1 (NIT):** wrap `ShortUrlTestData.java:10` (qa-tester).
- **N2 (NIT):** fix the lowercase sentence starts and the long line in `ClickRecordingConcurrencyIT:42-44` (qa-tester).
- **N4 (NIT):** add a positive control to `RepositoryAnnotationsTest`, showing the `isTransactional` detector returns true for a directly annotated sample and for one using a composed `@Transactional` (mid-engineer).
- **N5 (NIT, optional):** consolidate or rename the three older private `stableHeaders` helpers in `GetShortUrlIT`, `RedirectIT` and `RedirectSteps` (qa-tester).

## Design note
*(architect, 2026-09-30; **approved at G2 on 2026-09-30** ("approve all": Q1 to Q11, S-1 to S-13, the test plan and the carry-overs as written). The labels S-1 to S-13 (decisions) and Q1 to Q11 (questions) belong to this note only. Mapping: S-1 + S-9 = D95, S-2 = D96, S-3 = D97, S-4 = D98, S-5 + S-7 = D99, S-6 = D100, S-8 = D101, S-10 = D102, S-11 = D103, S-12 = D104, Q11 = D105; S-13 is the existing carry-over D94 (plus R4). Code, tests, Javadoc and SQL cite only those `Dnn`, never `S-n`, `Qn` or section numbers (CLAUDE.md review rule).)*

### 0. Summary and scope

`GET /api/v1/urls/{code}/stats?timezone=&from=&to=` returns data for a link the caller may see (D4, D13):
- the all-time click count and the last access time;
- a **dense** per-day series (D19) over an **inclusive** range of local dates, bucketed by the calendar day in the caller's IANA zone (D10).

**The main design choice (S-1): Java does all the time-zone arithmetic, and PostgreSQL only ever receives instants.**
1. Java validates the zone and resolves `from`/`to` (with defaults taken from the injected `Clock`).
2. Java computes the start instant of every local day in the window with `LocalDate.atStartOfDay(zone)`.
3. One native query counts the link's clicks in `[start(from), start(to + 1))`. It uses a range scan on `ix_click_event_short_url_id_clicked_at` and assigns each click to its day with `width_bucket` over the day-start instants.
4. Java fills in the zero days.

The grouping still runs in PostgreSQL, so no click is loaded into memory. PostgreSQL just never has to interpret a zone name, and several failure modes that `AT TIME ZONE` would bring are removed by construction:
- the POSIX sign inversion;
- PostgreSQL's abbreviation-first name lookup, which in PostgreSQL 18 also depends on the session `TimeZone` (and pgjdbc sets that from the JVM default);
- case-insensitive matching;
- skew between the JDK's tzdata and the Alpine `tzdata` package that the Postgres image uses.

This refines the architecture's "group in PostgreSQL with `AT TIME ZONE`" and the story's wording (the IT row and the Risk), so it needs the engineer's decision (Q1). The `AT TIME ZONE` design is fully specified as the alternative in §4.6.

| Area | Change |
|---|---|
| `api/ShortUrlController` | **Adds** `stats(...)` on `GET /{code}/stats` (§1) |
| `api/dto/ShortUrlStatsResponse`, `api/dto/DailyClicksResponse` | **New** records (§3) |
| `service/ShortUrlService` | **Adds** `stats(code, timezone, from, to, caller)`. The constructor gains `ClickEventRepository`. A fourth template, `snapshotRead`, is read-only with REPEATABLE READ (§5) |
| `service/StatsPeriod` | **New**: parses and validates the parameters, applies defaults, computes the day-start instants, densifies (§2, §4.1, §4.3) |
| `service/ShortUrlStats`, `service/DailyClicks` | **New** service records (the entity never leaves the service) |
| `service/exception/InvalidStatsQueryException` | **New**. Becomes `400 VALIDATION_FAILED` with `errors` (§2.6) |
| `repository/ClickEventRepository` | **Adds** the native per-day query and a `default` wrapper (§4.2) |
| `repository/ShortUrlRepository` | **D94**: `GREATEST` in `RECORD_CLICK_SQL` (§6.2) |
| `repository/PostgresServerErrors` | **R4**: `sqlState` falls back to the client SQLSTATE (§6.1) |
| `api/error/GlobalExceptionHandler` | **Adds** one handler (§2.6) |
| `SecurityConfig`, `ErrorCode`, migrations | **Unchanged**. There is no V3, and the D31/D61 catalogue gets no new code |

### 1. Endpoint

```java
// ShortUrlController (class-level @RequestMapping(path = "/api/v1/urls", produces = application/json), D70)
static final Set<String> STATS_PARAMETERS = Set.of("timezone", "from", "to");

@GetMapping("/{code}/stats")
ShortUrlStatsResponse stats(@PathVariable("code") String code,
        @Parameter(hidden = true) @RequestParam MultiValueMap<String, String> query,
        @Parameter(hidden = true) Authentication authentication) throws ServletRequestBindingException {
    requireOnlyKnownSingleParameters(query);            // unknown or repeated name -> 400 MALFORMED_REQUEST (S-6)
    return ShortUrlStatsResponse.from(service.stats(code, query.getFirst("timezone"), query.getFirst("from"),
            query.getFirst("to"), callerOf(authentication)));
}
```

- **Security:** rule 6 of the access table (`/api`, `/api/**` → `hasRole(USER)`) admits `GET` and `HEAD /api/v1/urls/{code}/stats`. ADMIN passes through the `ADMIN > USER` hierarchy. `SecurityConfig` does not change. A DELETE on the stats path hits rule 5 first: a USER gets 403, and an ADMIN gets 405 because the path has no DELETE mapping.
- **Content negotiation:** the class-level `produces = application/json` is inherited (D70). An `Accept` that excludes JSON gets 406 at mapping lookup, before any parameter is read or the service runs.
- **Ownership:** `ShortUrlService.stats` reuses `loadVisible` unchanged, with its checks in the same order: the D72 format check (no DB call), `findByShortCode`, DELETED hidden for everyone (D13), then the owner-or-ADMIN check (D4). Malformed, unknown, deleted and not-yours codes all get the one `404 SHORT_URL_NOT_FOUND` with an identical body (D74). A DEACTIVATED link's stats are returned, as the details endpoint returns the link itself (Q11).
- **No `@Transactional`:** the service runs a constructor-built `TransactionTemplate` (§5). `NoTransactionalAnnotationIT` already covers `ShortUrlService` and `ShortUrlController`.
- **HEAD:** Spring MVC serves it through the GET mapping. The status is the same as GET (200, 400, 404), `Content-Type: application/json` on a 200, and no body. The query runs, and there are no side effects. HEAD is never counted anywhere (D9 concerns the redirect only).
- **Caching:** there is no application header. Spring Security's default `Cache-Control: no-cache, no-store, max-age=0, must-revalidate` applies (D73).
- **`@RequestParam MultiValueMap` with no name** gets every request parameter (Spring 6.2 reference, *@RequestParam*). The three documented parameters are declared in `@Operation(parameters = …)` with `in = QUERY` (§8), because the map itself is hidden from springdoc.
- **Why the parameters are bound as `String`s:** Spring never converts them, so no `MethodArgumentTypeMismatchException` can occur. Every value error comes from `StatsPeriod`, with a field name and a fixed rule text (§2.6).

### 2. Parameters

#### 2.1 `timezone`: which strings are accepted

```java
// StatsPeriod
static ZoneId parseZone(String id) {   // null means absent, so the default applies
    if (id.equals("UTC") || ZoneRulesProvider.getAvailableZoneIds().contains(id)) {   // exact, case-sensitive
        return ZoneId.of(id);
    }
    throw invalid(TIMEZONE);
}
```

**The rule:** a value is accepted if and only if it is exactly `UTC` or exactly an ID in the JDK's IANA tzdb region set (`ZoneRulesProvider.getAvailableZoneIds()`, which is unmodifiable and not copied on each call). Absent means `UTC` (D10). Present but empty means invalid, never "absent" (as with D60).

| Input | `ZoneId.of` alone | This rule | Why |
|---|---|---|---|
| `America/New_York`, `Europe/London`, `Asia/Kathmandu` (+05:45), `Australia/Lord_Howe` (30-minute DST) | accepted | **accepted** | IANA region IDs (D10) |
| `UTC` | accepted | **accepted** | D10 default, also sendable explicitly |
| `Asia/Calcutta`, `US/Eastern`, `GMT`, `Etc/UTC`, `EST5EDT` | accepted | **accepted** | IANA IDs from tzdb's `backward` and `etcetera` files. Java's rules for them are correct, and PostgreSQL never sees them (S-1) |
| `Etc/GMT+5` (sent as `Etc/GMT%2B5`) | accepted | **accepted** | IANA ID. Its name uses the POSIX sign (it means UTC−5), but Java applies tzdb's own rules, so the buckets are correct. The OpenAPI text warns about the sign (Q2) |
| `Z`, `+05:00`, `-03`, `+0530` | `ZoneOffset` | **rejected** | Offsets are not IANA IDs (AC3, architecture). `getAvailableZoneIds` "does not include offset-based IDs" |
| `UTC+5`, `UTC+05:00`, `GMT-3`, `UT+1` | prefixed offset | **rejected** | Not in the tzdb set. PostgreSQL would read `UTC+5` as a POSIX spec, meaning UTC−5 |
| `UT` | accepted (special case) | **rejected** | Not in tzdb. This proves the rule is set membership and not `ZoneId.of` |
| `PST`, `IST`, `CTT`, `EST`, `MST`, `HST` | rejected (these are `SHORT_IDS` only) | **rejected** | Deprecated three-letter IDs, not in the JDK's tzdb set. `EST`, `MST` and `HST` are IANA zones, but the JDK's tzdb compiler deliberately excludes them because it supports them only through the `SHORT_IDS` mapping |
| `america/new_york`, `AMERICA/NEW_YORK`, `utc` | rejected | **rejected** | Java lookup is exact (PostgreSQL would accept these, being case-insensitive) |
| `""`, `" UTC"`, `"UTC "`, `America/New York`, `Mars/Olympus_Mons`, `../../etc/passwd`, a 10 000-character string | rejected | **rejected** | Not in the set. No trimming |

*Erratum: `EST`, `MST` and `HST` moved from the accepted row to the rejected short-ID row. Corrected in review, 2026-09-30; no decision or code change.*

**Case sensitivity.** Java matches exactly: `ZoneRegion.ofId` looks the ID up in the provider's map. PostgreSQL matches "case-insensitively" in every case (PostgreSQL 18 §8.5.3). Under S-1 only Java's behaviour matters, and it is exact, like codes (D6). Canonicalising the case would be a product nicety, not a requirement (Q2).

**Skew between Java and PostgreSQL tzdata.** Under S-1 the zone is never sent to PostgreSQL:
- no zone can be "unknown to PostgreSQL" (so no `22023 time zone "…" not recognized`, and so no 500);
- no zone can mean something different in PostgreSQL (no abbreviation or POSIX reinterpretation).

The only tzdata that matters is the JDK's. **Operational note:** a government rule change reaches the stats when the JDK is updated (a JDK update release, or the vendor's tzupdater), not when the database image is. The Postgres image builds with `--with-system-tzdata` and Alpine's `tzdata` package, which updates on its own schedule, and that is exactly why it is kept out of the calculation.

```
S-2 Recommendation: accept exactly "UTC" or an ID in ZoneRulesProvider.getAvailableZoneIds(), case-sensitive, no trimming;
                    absent means UTC and empty is invalid.
Reason:             it is the IANA set that D10 names, so every backward link and Etc zone is included; it excludes every
                    offset and prefixed-offset form (Java documents that offset-based IDs are not in the set); it needs no
                    regex and makes no DB call; and under S-1, Java's reading of the ID is the only one that exists.
Alternative:        (a) region IDs only ("Area/Location", which rejects GMT, EST5EDT and Etc/GMT+5 because they have no
                    slash, or because of their confusing sign); (b) case-insensitive matching to the canonical ID.
Trade-off:          Etc/GMT+5's inverted-looking name is accepted, and it is correct. (a) is needed only under the
                    AT TIME ZONE alternative (§4.6), where slashless names collide with PostgreSQL abbreviations. (b) is
                    friendlier but adds a canonicalisation step and a second spelling of every ID.
```

#### 2.2 `from` and `to`

- **Format:** `LocalDate.parse` (ISO_LOCAL_DATE, `ResolverStyle.STRICT`). So `2026-02-30`, `2026-2-3`, `20260203`, `2026-02-03T00:00` and non-ASCII digits are all rejected.
- **Bounds:** `1970-01-01` ≤ date ≤ `9999-12-31`.
  - The lower bound is where tzdb guarantees its data, and it keeps every instant a positive four-digit year. That matters: `LocalDate.parse` accepts year `0000`, which PostgreSQL does not have (it has no year zero), so without the bound that input would reach the database and cause a 500.
  - The upper bound excludes the signed years ISO allows beyond 9999.
- **Semantics (S-3):** both bounds are **inclusive** local dates in the caller's zone. The window is `[from, to]` and the daily series has `to − from + 1` entries. Internally the instant range is half-open: `[start(from), start(to + 1))`.

```
S-3 Recommendation: from and to are inclusive local dates (yyyy-MM-dd) in the caller's zone.
Reason:             calendar ranges are naturally inclusive ("1 to 31 March"), a one-day query is from == to, and the
                    series has exactly one entry per date in [from, to]; the half-open instant range lives only in code.
Alternative:        half-open dates [from, to).
Trade-off:          half-open dates compose without overlap, but surprise callers (a one-day query needs to = from + 1)
                    and make "to" mean the day after the last day shown.
```

#### 2.3 Defaults and maximum range

- `today = LocalDate.ofInstant(clock.instant(), zone)`. **Never** `clock.withZone(...)`: the shared `TestClock` throws on it, and the clock's own zone is never used, so the default is UTC whatever the JVM zone is.
- `to` absent → `today`.
- `from` absent → `max(to − 29 days, 1970-01-01)`, so the default is the last 30 days ending today.
- Future dates are allowed. Their days are 0.
- **Maximum 366 days** inclusive (`to − from + 1 ≤ 366`), so a whole leap year fits.
- The clock is read **once**, before any transaction.

```
S-4 Recommendation: defaults to = today in the caller's zone and from = to − 29; at most 366 days; dates from
                    1970-01-01 to 9999-12-31.
Reason:             each missing bound has one rule that doesn't depend on the other; the window bounds the response
                    (≤ 366 entries), the bucket array and the scanned index range; 366 admits a leap year.
Alternative:        all-time by default; or a from-only request defaulting to from + 29.
Trade-off:          all-time is unbounded in response size and scan. With from-only meaning "from to today", an old from
                    gives 400 (range too long) instead of a silently truncated window.
```

#### 2.4 Unknown and repeated query parameters

The controller rejects any parameter name outside `{timezone, from, to}` (exact case, so `timeZone` and `tz` are rejected), and any name that is sent more than once, with **`400 MALFORMED_REQUEST`** (base keys only). The name is never echoed. It throws `ServletRequestBindingException("Unexpected or repeated query parameter")`, which `ResponseEntityExceptionHandler` maps to 400. The existing `handleExceptionInternal` fallback turns that into `MALFORMED_REQUEST` (D69), so the advice needs no new code for it.

```
S-6 Recommendation: reject unknown and repeated query parameters on the stats endpoint with 400 MALFORMED_REQUEST.
Reason:             the same reasoning as D59 for bodies: a misspelled timeZone or tz would otherwise silently return UTC
                    buckets, which is a wrong answer that looks right. A repeated name is the query-string form of a
                    duplicate JSON key. MALFORMED_REQUEST is what D59 uses for both.
Alternative:        ignore unknown parameters, as every other endpoint does (GET /api/v1/urls/{code} ignores its query).
Trade-off:          clients cannot add cache-busting parameters to this endpoint, and it is stricter than the details
                    endpoint (which has no parameters whose misspelling changes the answer). Product-visible: Q5.
```

#### 2.5 Validation order

1. The controller checks names and repetition: `MALFORMED_REQUEST`.
2. `StatsPeriod.resolve` parses `timezone`, `from` and `to` independently and collects every format or bound failure.
3. Only if all three are valid does it check `from ≤ to`, then the length.

Both range errors are reported on **`from`**, because a defaulted `to` (today) is never what the client got wrong. It all runs **before** any transaction or lookup, so a request with bad parameters never touches the database.

**Precedence:** 401 > 405 > 406 > 400 (parameters) > 404. A bad parameter on someone else's code gets the same 400 as on your own code, and valid parameters then get the D74 404. There is no oracle either way (Q8).

#### 2.6 Error mapping (D69, AC3)

| Case | Status / `errorCode` | `errors` |
|---|---|---|
| Unknown or repeated parameter name | 400 `MALFORMED_REQUEST` | none (base keys only) |
| Bad `timezone` (every rejected form in §2.1) | 400 `VALIDATION_FAILED` | `{field: "timezone", message: TIMEZONE_RULE}` |
| Bad `from` / `to` (format or bounds) | 400 `VALIDATION_FAILED` | `{field: "from"/"to", message: DATE_RULE}` |
| `from` after `to` | 400 `VALIDATION_FAILED` | `{field: "from", message: ORDER_RULE}` |
| More than 366 days | 400 `VALIDATION_FAILED` | `{field: "from", message: LENGTH_RULE}` |

Fixed texts are defined once, in `StatsPeriod`, and tests pin them:
- `TIMEZONE_RULE` = "must be an IANA time zone ID such as America/New_York, or UTC; IDs are case-sensitive and UTC offsets such as +05:00 are not accepted"
- `DATE_RULE` = "must be a date in the form yyyy-MM-dd between 1970-01-01 and 9999-12-31"
- `ORDER_RULE` = "must not be after to"
- `LENGTH_RULE` = "must be at most 365 days before to (at most 366 days in total)"

`InvalidStatsQueryException` carries a list of `(parameter, rule)` pairs built only from these constants, and the parameter names come from a fixed enum. So the rejected value can never reach the body or the logs (D56).

The handler, added to `GlobalExceptionHandler`:
```java
@ExceptionHandler(InvalidStatsQueryException.class)
ResponseEntity<ProblemDetail> handleInvalidStatsQuery(InvalidStatsQueryException ex, HttpServletRequest request) {
    List<FieldViolation> violations = ex.violations().stream()
            .map(v -> new FieldViolation(v.parameter(), v.rule()))
            .sorted(Comparator.comparing(FieldViolation::field).thenComparing(FieldViolation::message)).toList();
    return respond(ProblemDetails.of(ErrorCode.VALIDATION_FAILED, QUERY_VALIDATION_DETAIL, request.getRequestURI(),
            violations));
}
```
- `QUERY_VALIDATION_DETAIL` = "The query parameters failed validation." The `DETAIL` map's VALIDATION_FAILED text says "request body" and stays as it is, because US-006 and US-009 pin it.
- `instance` is the path without the query (as `ProblemDetails` requires).
- Nothing is logged by the advice. The service logs at DEBUG with the parameter names only.

```
S-5 Recommendation: parameter value errors give 400 VALIDATION_FAILED with the D56 errors extension ({field, message},
                    sorted, never echoing the value); unknown or repeated names give 400 MALFORMED_REQUEST. This settles
                    D69 for US-011: the D69 fallback stays for any framework-level conversion error, and none can occur
                    on this endpoint because it binds raw Strings.
Reason:             the client learns which parameter is wrong through a field it can act on, like body validation;
                    MALFORMED_REQUEST keeps the D59 meaning "the request's structure is wrong".
Alternative:        MALFORMED_REQUEST for unparseable dates and zones (the D69 fallback, as if Spring had converted).
Trade-off:          one more advice handler and one more detail text. The alternative gives no field, so a client cannot
                    tell timezone from from/to.
```
```
S-7 Recommendation: reuse VALIDATION_FAILED for an invalid timezone (errors[0].field = "timezone"); do not add INVALID_TIMEZONE.
Reason:             the catalogue ratified in US-005 (D31, D61) and ErrorCodeTest stay unchanged; the field already
                    identifies the parameter; one code covers the zone, the dates and the range, and so scales.
Alternative:        a new INVALID_TIMEZONE code, by analogy with INVALID_URL and INVALID_ALIAS.
Trade-off:          clients that switch on errorCode alone cannot tell a zone error from a date error without reading
                    errors[]. INVALID_URL and INVALID_ALIAS exist because those fields have policy rules, which is not
                    the case here (Q6).
```

### 3. Response (200, `application/json`)

```json
{
  "shortCode": "aB3dE9x",
  "timezone": "America/New_York",
  "from": "2026-03-07",
  "to": "2026-03-09",
  "totalClicks": 1234,
  "clicksInRange": 6,
  "lastAccessedAt": "2026-03-09T03:59:59.999999Z",
  "daily": [
    {"date": "2026-03-07", "clicks": 1},
    {"date": "2026-03-08", "clicks": 4},
    {"date": "2026-03-09", "clicks": 1}
  ]
}
```

| Field | Type | Meaning |
|---|---|---|
| `shortCode` | string | As stored |
| `timezone` | string | The zone used for the buckets, exactly as accepted (`UTC` when absent) |
| `from`, `to` | `yyyy-MM-dd` | The **resolved** window, after defaults, so the client always sees which days it got |
| `totalClicks` | integer (int64) | All-time `short_url.click_count`, the same value as the details resource's `clickCount` (D58) |
| `clicksInRange` | integer (int64) | The sum of `daily[].clicks` |
| `lastAccessedAt` | ISO-8601 UTC instant, microseconds | `short_url.last_accessed_at`, always present and `null` before the first click (as D58). With D94 it is the latest click time. It is not converted to the caller's zone, because an instant has no zone |
| `daily` | array of `{date, clicks}` | Dense (D19), ascending, exactly one entry per local date in `[from, to]` |

- **`totalClicks` comes from `click_count`, not `count(click_event)`.** It is O(1), it has one writer (D27), and it matches the details endpoint. The two are equal by construction for application-written data: the recorder writes both in one transaction, and the D91 guard skips both together. They can differ only when rows are written by raw SQL (for example the `seedClicks` test helper, or a manual repair). QA pins the source with one deliberately divergent fixture.
- **DST-transition days** appear as ordinary dates. Their count covers the whole local day, which is 23 or 25 hours long (see §4.4). There is no per-day length field (Q7). A local date that does not exist at all (Samoa skipped 2011-12-30) is listed with 0 clicks.
- **Names:** `clicks` rather than the story's proposed `count` (AC8 row), for consistency with `totalClicks`/`clicksInRange` (Q7). DTOs are records: `ShortUrlStatsResponse.from(ShortUrlStats)` and `DailyClicksResponse(LocalDate date, long clicks)`. Boot's Jackson writes `LocalDate` as `"2026-03-07"` and `Instant` as ISO-8601 (dates are not written as timestamps).

```
S-8 Recommendation: the shape above: an array of {date, clicks}, the resolved from/to/timezone echoed, totalClicks from
                    click_count, and lastAccessedAt as a UTC instant or null.
Reason:             an ordered array is dense and self-describing and keeps JSON order; echoing the resolved window
                    makes defaults visible; the click_count source is O(1) and consistent with GET /api/v1/urls/{code}.
Alternative:        a map keyed by date; totalClicks as count(click_event); lastAccessedAt in the caller's zone.
Trade-off:          a map is more compact, but JSON object order is not guaranteed. count(click_event) scans every event
                    the link has. A zoned lastAccessedAt would differ in format from the details resource.
```

### 4. Query, time and DST

#### 4.1 Bounds in Java (`StatsPeriod`)

```java
List<Instant> dayStarts = from.datesUntil(to.plusDays(1)).map(d -> d.atStartOfDay(zone).toInstant()).toList();
Instant end = to.plusDays(1).atStartOfDay(zone).toInstant();       // exclusive
```

- `atStartOfDay(zone)` returns "the earliest valid time" of the date, so a date whose midnight falls in a DST gap starts when the gap ends (Java 25 `LocalDate` Javadoc). A fall-back overlap never affects midnight in the common zones. If it did, "earliest" is still the right start.
- `dayStarts` is non-decreasing and has `to − from + 1` entries. Two consecutive entries are equal only for a date that does not exist (Pacific/Apia 2011-12-30), which then gets 0 (§4.4).

#### 4.2 The native query (`ClickEventRepository`)

```java
/** Clicks per local day: width_bucket over the Java-computed day starts. No zone ever reaches PostgreSQL. */
String CLICKS_PER_DAY_SQL = "SELECT width_bucket(clicked_at, CAST(string_to_array(:dayStarts, ',') AS timestamptz[]))"
        + " AS day_index, count(*) AS clicks FROM click_event"
        + " WHERE short_url_id = :shortUrlId AND clicked_at >= :rangeStart AND clicked_at < :rangeEnd"
        + " GROUP BY day_index ORDER BY day_index";

@Query(value = CLICKS_PER_DAY_SQL, nativeQuery = true)
List<Object[]> countClicksPerDayRows(@Param("shortUrlId") long shortUrlId, @Param("rangeStart") Instant rangeStart,
        @Param("rangeEnd") Instant rangeEnd, @Param("dayStarts") String dayStarts);

/** Rows only for days with clicks; dayIndex is 1-based. dayStarts: 1 to 366 non-decreasing instants. */
default List<DayCount> countClicksPerDay(long shortUrlId, List<Instant> dayStarts, Instant end) {
    // precondition checks: non-empty, size <= 366, non-decreasing, end > last
    String csv = dayStarts.stream().map(Instant::toString).collect(Collectors.joining(","));
    return countClicksPerDayRows(shortUrlId, dayStarts.getFirst(), end, csv).stream()
            .map(r -> new DayCount(((Number) r[0]).intValue(), ((Number) r[1]).longValue())).toList();
}

record DayCount(int dayIndex, long clicks) { }   // nested in ClickEventRepository, or its own file in repository
```

- **Return type:** `width_bucket` returns `integer` and `count(*)` returns `bigint`. The raw rows are `Object[]{Integer, Long}`, which the repository test pins. The `default` method keeps the CSV encoding and the casts next to the SQL. Spring Data proxies default methods, and "declared query methods (including default methods) do not get any transaction configuration" (Spring Data JPA reference), so it joins the caller's transaction.
- **`width_bucket(operand, thresholds anycompatiblearray)`** "returns the number of the bucket in which operand falls given an array listing the inclusive lower bounds of the buckets", and 0 below the first. The thresholds "must be sorted" (PostgreSQL 18 §9.3; its own example uses `timestamptz[]`). The WHERE clause keeps every row in `[dayStarts[1], end)`, so the index is 1 to N. With equal thresholds, a row goes to the last of them, so a skipped date's bucket is empty.
- **Parameter encoding:** `Instant.toString()` is ISO-8601 UTC with `Z`, for example `2026-03-08T05:00:00Z` (with only four-digit years, thanks to the 1970 to 9999 bounds). PostgreSQL accepts the `T` separator and `z` ("short form of zulu (also in ISO 8601)", §8.5.1). An explicit offset makes the text-to-`timestamptz` cast independent of the session `TimeZone` and `DateStyle`. The value is built by Java from instants, never from client text. There is no `::` cast, because `::` interferes with native named-parameter parsing, so the query uses `CAST(… AS …)`. At most 366 × 21 characters.
- **Index use:** `short_url_id = :shortUrlId AND clicked_at >= :rangeStart AND clicked_at < :rangeEnd` is an equality on the leading column plus a range on the second column of `ix_click_event_short_url_id_clicked_at`, bound as `timestamptz` (Hibernate binds `Instant` as UTC, as `recordClick` already relies on). `width_bucket(clicked_at, …)` and `count(*)` need only indexed columns, so an index-only scan is possible once the visibility map is current.
- `RepositoryAnnotationsTest` pins the SQL as a literal, like `RECORD_CLICK_SQL`. The literal contains no `AT TIME ZONE`, `timezone(` or zone parameter, which is the S-1 guard.

```
S-1 Recommendation: Java owns every time-zone calculation (validation, defaults, day starts); PostgreSQL counts clicks
                    per Java-computed day with width_bucket; the zone is never sent to PostgreSQL.
Reason:             PostgreSQL resolves a zone string abbreviation-first ("the timezone database unwisely uses a few
                    zone names that are identical to offset abbreviations", datetime.c), and in PG 18 it checks the
                    session TimeZone's abbreviations before timezone_abbreviations. pgjdbc sets the session TimeZone
                    from the JVM default. So 'CET' AT TIME ZONE is a fixed +01 with no summer time, while Java's CET
                    has DST; POSIX specs invert the sign ('+05' means UTC−5); names match case-insensitively; and the
                    image's Alpine tzdata updates independently of the JDK's. With Java-only zone arithmetic, none of
                    these can give a 500 or a silently different bucket. Aggregation still happens in the database
                    (no clicks are loaded), the index is used, and 23/25-hour days are exact by construction.
Alternative:        GROUP BY CAST(clicked_at AT TIME ZONE :zone AS date), as in the architecture and the story (§4.6).
Trade-off:          the SQL is less familiar (width_bucket over a text-encoded array), and the story's "using AT TIME
                    ZONE" wording (the IT row and the Risk) must be reworded if approved (Q1). The alternative is more
                    readable, but needs three extra safeguards and still has a residual tzdata-skew risk.
```

#### 4.3 Densify in Java

`StatsPeriod.densify(List<DayCount>)`:
1. Start with `long[N]` zeros.
2. For each row, fill `counts[dayIndex − 1]`. An index outside `1..N` throws `IllegalStateException`. That is impossible given the WHERE clause, so it would be a genuine bug and a 500.
3. Return `DailyClicks(from.plusDays(i), counts[i])` for `i` in `0..N−1`.

`clicksInRange` is the sum.

```
S-9 Recommendation: densify in Java from the 1-based bucket index.
Reason:             N ≤ 366 array slots; the date for index i is from + (i − 1) with no second time-zone calculation;
                    the SQL stays a plain aggregate.
Alternative:        generate_series of local dates LEFT JOINed in SQL.
Trade-off:          the SQL alternative needs the zone in PostgreSQL (the S-1 problem) or a second array parameter.
```

#### 4.4 Proof on 23-hour, 25-hour and missing days

A bucket is exactly `[start(d), start(d + 1))`, with both ends computed by java.time. For `America/New_York`, 2026:

| Local date | `start(d)` (UTC) | Length |
|---|---|---|
| 2026-03-07 | 2026-03-07T05:00Z (EST, −05) | 24 h |
| **2026-03-08** (02:00 EST → 03:00 EDT, at 07:00Z) | 2026-03-08T05:00Z | **23 h** |
| 2026-03-09 | 2026-03-09T04:00Z (EDT, −04) | 24 h |
| 2026-10-31 | 2026-10-31T04:00Z (EDT) | 24 h |
| **2026-11-01** (02:00 EDT → 01:00 EST, at 06:00Z) | 2026-11-01T04:00Z | **25 h** |
| 2026-11-02 | 2026-11-02T05:00Z (EST) | 24 h |

Fixtures that separate a correct zone from a fixed offset:

| Click (UTC) | NY local | NY day | UTC day | Caught if the code wrongly uses |
|---|---|---|---|---|
| a `2026-03-08T04:59:59.999999Z` | 03-07 23:59:59.999999 EST | 03-07 | 03-08 | a fixed −04 (gives 03-08) |
| b `2026-03-08T05:00:00Z` | 03-08 00:00 EST | 03-08 | 03-08 | an exclusive lower edge |
| c `2026-03-08T06:59:59.999999Z` | 01:59:59.999999 EST | 03-08 | 03-08 | |
| d `2026-03-08T07:00:00Z` | 03:00 EDT | 03-08 | 03-08 | |
| e `2026-03-09T03:59:59.999999Z` | 03-08 23:59:59.999999 EDT | 03-08 | 03-09 | an inclusive upper edge |
| f `2026-03-09T04:00:00Z` | 03-09 00:00 EDT | 03-09 | 03-09 | a fixed −05 (gives 03-08) |
| g `2026-11-01T03:59:59.999999Z` | 10-31 23:59:59.999999 EDT | 10-31 | 11-01 | a fixed −05 |
| h `2026-11-01T04:00:00Z` | 11-01 00:00 EDT | 11-01 | 11-01 | |
| i `2026-11-01T05:30:00Z` | 01:30 EDT (first) | 11-01 | 11-01 | |
| j `2026-11-01T06:30:00Z` | 01:30 EST (second) | 11-01 | 11-01 | |
| k `2026-11-02T04:30:00Z` | 11-01 23:30 EST | 11-01 | 11-02 | a fixed −04 (gives 11-02) |
| l `2026-11-02T05:00:00Z` | 11-02 00:00 EST | 11-02 | 11-02 | |

Expected results:
- **Spring:** `timezone=America/New_York&from=2026-03-07&to=2026-03-09` gives `[1, 4, 1]`. With the timezone omitted, the default UTC gives `[0, 4, 2]`.
- **Fall:** `from=2026-10-31&to=2026-11-02` gives NY `[1, 4, 1]` and UTC `[0, 4, 2]`.
- **`from`/`to` on a DST day:** `from=to=2026-03-08` gives `[4]`, excluding a and f. `from=to=2026-11-01` gives `[4]`, excluding g and l.

Further cases:
- **Midnight gap:** in `America/Sao_Paulo`, DST began on 2018-11-04 at 00:00, which became 01:00. `start(2018-11-04)` = 01:00−02 = `2018-11-04T03:00Z`, so that day is 23 hours.
- **Missing date:** in `Pacific/Apia`, `start(2011-12-30)` equals `start(2011-12-31)` (the gap swallows the whole day). The implementer confirms the exact instants from the JDK. A click at that instant counts for 12-31, and 12-30 shows 0.
- **Fractional offsets:** in `Asia/Kathmandu` (+05:45), `2026-03-01T18:14:59Z` counts for 03-01 and `18:15:00Z` for 03-02.

#### 4.5 Cost, and whether an EXPLAIN check belongs in tests

- The rows read are the link's clicks inside the window, found by a range scan on the index. Each costs O(log N) for `width_bucket`'s binary search, with N ≤ 366.
- The 366-day limit bounds the response, the bucket array and the index range. It does **not** bound the rows for a very hot link (for example 10 M clicks a year).
- A rollup table, or asynchronous aggregation, is already the production roadmap item in the architecture's *Analytics* trade-off. A statement timeout is a US-014 candidate.
- **EXPLAIN (S-11):** one repository test runs `EXPLAIN` of the exact `CLICKS_PER_DAY_SQL` through `NamedParameterJdbcTemplate`, which accepts the same `:name` syntax, with `SET LOCAL enable_seqscan = off` inside the test transaction.
  - It asserts that the plan names `ix_click_event_short_url_id_clicked_at` with an `Index Cond` on both `short_url_id` and `clicked_at`.
  - That proves the predicate can use the index (no function wraps `clicked_at`), without depending on planner costs for a tiny table.
  - If pgjdbc does not accept parameters inside EXPLAIN, the test inlines literal values, and the implementer records which way it went.

#### 4.6 Alternative: `AT TIME ZONE` in PostgreSQL (if Q1 is answered that way)

```sql
SELECT CAST(clicked_at AT TIME ZONE :zone AS date) AS day, count(*) AS clicks
FROM click_event
WHERE short_url_id = :shortUrlId AND clicked_at >= :rangeStart AND clicked_at < :rangeEnd
GROUP BY day ORDER BY day
```

`:zone` is bound and never concatenated. The Java bounds are the same as in §4.1, and densify is keyed by date. This design additionally needs:
1. **Zone rule narrowed to `UTC` or IDs containing `/`** (so it rejects `CET`, `EET`, `MET`, `WET`, `EST`, `MST`, `HST`, `EST5EDT`, `GMT`…). PostgreSQL resolves abbreviations first (the session zone's own, then `timezone_abbreviations`), so `'CET'` would be a fixed +01 with no summer time. No abbreviation contains `/`, and a name with a `/` cannot be read as a POSIX spec.
2. **SQLSTATE `22023`** (`time zone "…" not recognized`, `ERRCODE_INVALID_PARAMETER_VALUE`) from the stats statement, caught outside the template through `PostgresServerErrors.sqlState` and mapped to `400 VALIDATION_FAILED` on `timezone`. This covers a JDK that knows a zone the image's tzdata does not.
3. **Residual rule skew.** If both know the zone but their rules differ (a recent government change), Java's range and PostgreSQL's grouping disagree near midnight. At the edges, PostgreSQL can return `from − 1` or `to + 1`. That must be clamped into the edge day or treated as an error, and inside the range a click can move to the neighbouring day. To remove it fully, the range must also be computed in SQL (`CAST(:from AS timestamp) AT TIME ZONE :zone`), which makes PostgreSQL's tzdata the single authority instead of Java's.

### 5. Transaction and consistency

```java
// ShortUrlService constructor: a fourth template, never published as a bean
this.snapshotRead = new TransactionTemplate(transactionManager);
this.snapshotRead.setReadOnly(true);
this.snapshotRead.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);

public ShortUrlStats stats(String code, String timezone, String from, String to, Caller caller) {
    StatsPeriod period = StatsPeriod.resolve(timezone, from, to, clock.instant());       // 400s, no DB (D45 clock once)
    return snapshotRead.execute(status -> {
        ShortUrl url = loadVisible(code, caller);                                        // 404s (D4, D13, D72, D74)
        List<DayCount> rows = clickEvents.countClicksPerDay(url.getId(), period.dayStarts(), period.end());
        return ShortUrlStats.of(url.getShortCode(), period, url.getClickCount(), url.getLastAccessedAt(),
                period.densify(rows));
    });
}
```

- **Why REPEATABLE READ.** Under READ COMMITTED, "two successive SELECT commands can see different data" (PostgreSQL 18 §13.2.1). A click that commits between `loadVisible` and the daily query would appear in `daily` but not in `totalClicks`. For a link younger than the window, the client would see `clicksInRange = totalClicks + 1`. Reordering the statements cannot help, because `loadVisible` must run first (it supplies the id and the ownership decision).
  - Under REPEATABLE READ, every statement sees "a snapshot as of the start of the first non-transaction-control statement in the transaction". So `totalClicks`, `lastAccessedAt` and `daily` describe one moment, and `clicksInRange ≤ totalClicks` holds for application data.
  - "read-only transactions will never have serialization conflicts" (§13.2.2), so no `40001` retry is needed.
- **Spring support.** `HibernateJpaDialect.beginTransaction` applies a non-default isolation level and the read-only flag through `DataSourceUtils.prepareConnectionForTransaction`, when `prepareConnection` is true and the connection is held until close. `HibernateJpaVendorAdapter` sets `DELAYED_ACQUISITION_AND_HOLD` for exactly that reason. `resetSessionState` restores the pooled connection's isolation after the transaction (spring-orm 6.2.19 source).
  - The cost is the isolation `SET` on the connection before the transaction and its reset afterwards (pgjdbc's `setTransactionIsolation`), about two extra round trips per stats call.
  - `readWrite` and `readOnly` are unchanged.
- **The entity never leaves the service:** `ShortUrlStats` is built inside the callback.
- **Concurrency with clicks and PATCH:** the stats transaction takes no locks. A click, a PATCH or a DELETE committing during it is simply not seen, and appears on the next call.

```
S-10 Recommendation: stats runs in a read-only REPEATABLE READ TransactionTemplate; the loadVisible read and the daily
                     query share one snapshot.
Reason:              the three numbers are mutually consistent at no failure cost (read-only RR never gets 40001);
                     it needs only template configuration and no locks.
Alternative:         the existing read-only READ COMMITTED template, accepting a one-click skew between totalClicks and
                     daily.
Trade-off:           two extra round trips to set and reset isolation. The alternative is cheaper but can show
                     clicksInRange > totalClicks.
```

### 6. Carry-overs from US-010

Every change to a Done story's test is listed test by test in that story's *Post-completion change* section, by the engineer who makes it. The story's own wording (US-010 AC1) is reworded by the orchestrator or planner, because the architect and the engineers do not edit acceptance criteria.

#### 6.1 R4: `sqlState` falls back to the client SQLSTATE (mid-engineer)

```java
/** SQLSTATE: the server's (first PSQLException with a server message), else the first non-blank
 *  SQLException.getSQLState() in the cause chain (client-side states such as 08006), else empty. */
public static Optional<String> sqlState(Throwable t)
```

- The same `MAX_CAUSE_DEPTH` guard applies, and so does the cycle safety.
- `serverError` and `isUniqueViolation` are **unchanged**: they read the server message only, so a client-side `23505` is never a "code taken" collision.
- It is logged only by `RedirectService`'s WARN (D93). Under S-1, stats has no use for it.

Tests (US-010 post-completion):
1. `PostgresServerErrorsTest.shouldReturnAnEmptySqlStateForNullNoPsqlExceptionOrNoServerMessage` is **split** into two:
   - `shouldReturnAnEmptySqlStateForNullOrAChainWithoutSqlException`: null, and a `RuntimeException` → `IllegalStateException` chain;
   - `shouldFallBackToTheClientSqlStateWhenThereIsNoServerMessage`: `PSQLException("client side", PSQLState.UNEXPECTED_ERROR)` gives `99999`; `new DataAccessResourceFailureException("x", new SQLException("x", "08006"))` gives `08006`; and a null-state `SQLException` wrapping `PSQLException(CONNECTION_FAILURE)` gives `08006` (the fallback skips a null state). The existing server-state positive control is kept.
2. **New** `PostgresServerErrorsTest.shouldNotTreatAClientSideUniqueStateAsAUniqueViolation`: `PSQLException("x", PSQLState.UNIQUE_VIOLATION)` has no server message. `isUniqueViolation` is false, and `sqlState` gives `23505` (the positive control).
3. **New** `RedirectServiceTest` fail-open variant: the recorder throws `CannotCreateTransactionException` wrapping `PSQLException("x", PSQLState.CONNECTION_FAILURE)`. The target is still returned, with exactly one WARN containing `sqlState=08006`, and no throwable.

#### 6.2 D94: `last_accessed_at` never moves backwards (mid-engineer)

```java
String RECORD_CLICK_SQL = "UPDATE short_url SET click_count = click_count + 1,"
        + " last_accessed_at = GREATEST(last_accessed_at, :clickedAt) WHERE id = :id AND status = 'ACTIVE'";
```

- PostgreSQL's `GREATEST` ignores NULL, so the first click still sets it.
- The parameter's type is taken from the column (`timestamptz`).
- The `@Modifying` flags, the signature and the absence of `@Transactional` are unchanged, and so is the Javadoc citation, plus D94.

Tests (US-010 post-completion):
1. `RepositoryAnnotationsTest.EXPECTED_CLICK_SQL` becomes the new literal. `shouldDeclareTheClickUpdateExactlyAsApproved` is otherwise unchanged.
2. **New** `ShortUrlRepositoryTest.shouldNotMoveLastAccessedAtBackwards`: `recordClick(id, CLICK_2)` then `recordClick(id, CLICK_1)` with `CLICK_1 < CLICK_2`. Both return 1, `click_count` is 2 (the writes happened), and `last_accessed_at` is `CLICK_2`.
3. **Unchanged and still valid:** `shouldIncrementRatherThanSet` (forward order), `shouldNotChangeVersionOrUpdatedAtWhenAClickIsRecorded`, and QA's `ClickRecordingIT` and `ClickRecordingConcurrencyIT` (forward or equal instants). `LifecycleConcurrencyIT.holdClick` is a raw statement and is unaffected.
4. **New QA IT (US-011):** set the clock to T2 and GET, then set the clock to T1 < T2 and GET. The result is 2 clicks, `lastAccessedAt` = T2 in both the details and the stats, and both events are present.
5. **US-010 AC1** becomes: "…`last_accessed_at` is set to the latest click time (D94)…". The orchestrator makes that wording change.
6. Architecture: the `recordClick` SQL is updated (this note's architecture change).

#### 6.3 Nits

| ID | Owner | Change | Post-completion entry in |
|---|---|---|---|
| N1 | qa-tester | Wrap the Javadoc line `ShortUrlTestData.java:10` to 120 characters or fewer | US-010 |
| N2 | qa-tester | `ClickRecordingConcurrencyIT:42-44`: capitalise sentence starts, wrap the long line | US-010 |
| N4 | mid-engineer | `RepositoryAnnotationsTest.shouldDetectDirectAndComposedTransactionalAnnotations`: `isTransactional` is true for a nested sample interface with a directly annotated method and for one using a test-local composed annotation meta-annotated with `@Transactional` (Spring and Jakarta) | US-010 |
| N5 | qa-tester (optional) | Replace the private `stableHeaders` in `GetShortUrlIT`, `RedirectIT` and `RedirectSteps` with `ApiClient.stableHeaders`, or rename them if their semantics differ. The assertions are unchanged | US-007, US-008 |

#### 6.4 Other Done-story tests that change

- `ShortUrlServiceTest`: its three constructor sites (lines 112, 871 and 884) gain a `ClickEventRepository` mock. No assertion changes. Recorded in US-007's post-completion section, next to US-010's N1 entry.
- The shared `ShortUrlTestData` gains `seedClickEvents` (§9.2). It is new code, not a change.

### 7. Error and status summary

| Request | Result |
|---|---|
| No or invalid credentials | 401 `AUTHENTICATION_REQUIRED` + Basic challenge (D30, D55) |
| `POST`/`PATCH`/`PUT` on the stats path | 405 `METHOD_NOT_ALLOWED` (QA records `Allow`) |
| `DELETE` on the stats path | USER: 403 `ACCESS_DENIED` (rule 5). ADMIN: 405 |
| `Accept: application/xml` or `application/problem+json` | 406 `NOT_ACCEPTABLE`, before any validation. An unparseable `Accept` gets 406 with an empty body (D70) |
| Unknown or repeated parameter | 400 `MALFORMED_REQUEST` |
| Bad value or range | 400 `VALIDATION_FAILED` + `errors` |
| Malformed, unknown, deleted or not-yours code | 404 `SHORT_URL_NOT_FOUND`, identical bodies |
| `/api/v1/urls/{code}/stats/` (trailing slash) | 404 `RESOURCE_NOT_FOUND` (QA records it) |
| Owner or ADMIN, valid parameters, ACTIVE or DEACTIVATED link | 200 |

### 8. OpenAPI

`GET /api/v1/urls/{code}/stats`, tag `Short URLs`, `basicAuth`:
- **Parameters, exactly four:**
  - `code` (path);
  - `timezone` (query, optional, default `UTC`; the description states the IANA-only rule, that IDs are case-sensitive, the `Etc/GMT+5` sign convention, and that `+` must be sent as `%2B`);
  - `from` and `to` (query, optional, `format: date`; the descriptions state inclusive, the defaults, the 366-day limit and the 1970 to 9999 bounds).
- **Responses:**
  - `200` (`application/json`, `ShortUrlStatsResponse`);
  - `400` "VALIDATION_FAILED (errors names the parameter) or MALFORMED_REQUEST (unknown or repeated parameter)";
  - `401`, `404` (the same text as the details endpoint), `406`.

  Every error is under `application/problem+json` with `ErrorResponseSchema`.
- **The description** says that days are local calendar days in the zone and can be 23 or 25 hours long, that `totalClicks` is all-time, and that `lastAccessedAt` is UTC.

### 9. Tests: acceptance criteria, components and owners

Every 404 assertion also asserts `errorCode: SHORT_URL_NOT_FOUND`, because `RESOURCE_NOT_FOUND` is also a 404. Every HEAD that expects a 404 or 400 has a same-path HEAD 200 control, and a GET on the same path that asserts `errorCode` (review rules from US-007 and US-008).

| AC | Component | Proving tests (owner) |
|---|---|---|
| AC1 | `StatsPeriod`, `countClicksPerDay`, `stats` | `StatsPeriodTest` bounds (mid); `ClickEventRepositoryTest` DST fixtures (mid); `StatsIT` spring/fall and DST-day windows (QA); Cucumber (QA) |
| AC2 | `StatsPeriod` default | `StatsPeriodTest` (a clock in `Asia/Tokyo` still gives UTC) (mid); web slice passes null (mid); `StatsIT` same fixtures without `timezone` give UTC buckets (QA); Cucumber (QA) |
| AC3 | `StatsPeriod.parseZone`, advice | `StatsPeriodTest` accept/reject table (mid); slice 400 body (mid); `StatsIT` every rejected form, correctly encoded (QA); Cucumber outline (QA) |
| AC4 | `loadVisible` | `ShortUrlServiceTest` (mid); slice (mid); `StatsIT` matrix (QA); Cucumber (QA) |
| AC5 | `Caller.admin` | slice (mid); `StatsIT` (QA); Cucumber (QA) |
| AC6 | `loadVisible` | service and slice (mid); `StatsIT` unknown, deleted for owner and ADMIN, malformed (QA); Cucumber (QA) |
| AC7 | Security rule 6 | slice (mid); `StatsIT` anonymous and bad credentials (QA); Cucumber (QA) |
| AC8 | `StatsPeriod.densify` | `StatsPeriodTest` (mid); `StatsIT` 3-day window `[1, 0, 1]` (QA); Cucumber (QA) |

#### 9.1 mid-engineer (Surefire)

| Test | Proves |
|---|---|
| `service/StatsPeriodTest` zone table | Every row of §2.1: accepted gives that `ZoneId`, and rejected gives exactly one violation `(timezone, TIMEZONE_RULE)` |
| same, dates | Valid; `2026-02-30`, `2026-2-3`, `20260203`, `2026-02-03T00:00`, `+2026-02-03`, `" 2026-02-03"`, `""`, Arabic-Indic digits, `1969-12-31`, `0000-01-01` and `10000-01-01` are rejected with `DATE_RULE`; `1970-01-01` and `9999-12-31` are accepted |
| same, defaults | Both absent: `[today − 29, today]`. Only `to`: `to − 29`. Only `from`: `to = today`. `today` is taken in the zone (clock `2026-03-10T03:30Z`: NY gives `to 2026-03-09`, UTC gives `2026-03-10`). The clock's own zone is ignored. The clamp at 1970-01-01 |
| same, range | `from == to` is valid; 366 days valid, 367 invalid (`from`, `LENGTH_RULE`); `from > to` gives (`from`, `ORDER_RULE`); several format errors are all reported and sorted; range checks are skipped while any field is invalid; no message contains the input |
| same, bounds | NY 2026-03-08 (23 h) and 2026-11-01 (25 h) day starts from §4.4; Sao_Paulo 2018-11-04 starts at `03:00Z`; Apia 2011-12-30 equals 2011-12-31; Kathmandu; `end = start(to + 1)`; `dayStarts` size and non-decreasing order; the extremes `1970-01-01`/`Etc/GMT+12` and `9999-12-31`/`Pacific/Kiritimati` do not throw |
| same, densify | No rows gives all zeros; gaps are filled and ordered; an index of 0 or N + 1 throws `IllegalStateException`; the sum is `clicksInRange` |
| `ShortUrlServiceTest` + stats | Parameter errors throw before any transaction-manager or repository interaction (`verifyNoInteractions`). Malformed, unknown, deleted (ADMIN too) and not-owner codes give `ShortUrlNotFoundException` and never call `clickEvents`. Owner and ADMIN get a view built from the entity plus densified rows. The captured `TransactionDefinition` is read-only and `ISOLATION_REPEATABLE_READ`. The clock is read once |
| `ShortUrlControllerWebMvcTest` + stats | 200 with the exact JSON key set, date and instant formats, and `lastAccessedAt: null` present. `Caller` has the admin flag. Raw strings are passed through (absent gives null, `timezone=` gives `""`). Unknown (`tz`, `Timezone`) and repeated names give 400 `MALFORMED_REQUEST` with base keys only, the name not in the body, and the service not called. `InvalidStatsQueryException` gives 400 `VALIDATION_FAILED` with the sorted `errors` and the query detail text. Not-found gives 404. 406 for XML with no service call. 401 anonymous. HEAD 200 with no body. The default `Cache-Control` is pinned |
| `GlobalExceptionHandlerTest` + handler | The handler output: no rejected value, the exact detail text |
| `ClickEventRepositoryTest` + `countClicksPerDay` | Fixtures a–l inserted with `JdbcTemplate` and `OffsetDateTime` at UTC give exactly `[(1, 1), (2, 4), (3, 1)]` for NY. Another link's events are excluded. No events gives an empty list. The Apia equal-threshold case (index 2 absent, click at index 3). Raw row types `Integer`/`Long` are pinned. `9999-12-31` bounds work |
| same, EXPLAIN (S-11) | The index and both `Index Cond` columns with `enable_seqscan = off` (§4.5) |
| `RepositoryAnnotationsTest` | Pins `CLICKS_PER_DAY_SQL` and `countClicksPerDayRows(long, Instant, Instant, String)` as literals; it is not `@Modifying` (the only `@Modifying` method is still `recordClick`); no `@Transactional`; plus D94 and N4 (§6) |
| R4 and D94 tests | §6.1, §6.2 |

#### 9.2 qa-tester (Failsafe: `*IT` and Cucumber)

New helper: `ShortUrlTestData.seedClickEvents(code, Instant...)`.
- It inserts `click_event` rows with explicit `clicked_at`, bound as `OffsetDateTime` at UTC (never `Timestamp`, so the fall-back-hour fixtures cannot depend on the JVM zone).
- In the same statement batch it applies `click_count += n` and `last_accessed_at = GREATEST(last_accessed_at, max)`, mirroring the recorder, so the fixtures are realistic.
- `seedClicks` stays for the deliberately divergent case.

`support/StatsIT` (real HTTP, `TestClock`):
1. **DST:** fixtures a–f and g–l; the NY and UTC expectations of §4.4 as exact JSON arrays; `clicksInRange`; `totalClicks`.
2. **DST days as `from`/`to`:** `from=to=2026-03-08` gives one entry with 4, and `from=to=2026-11-01` gives one entry with 4. This covers inclusive/exclusive edges at local midnight (a, b, e, f, g, h, k, l).
3. **AC8:** clicks at `2026-03-01T12:00Z` and `2026-03-03T12:00Z` with `from=2026-03-01&to=2026-03-03` give `[1, 0, 1]`.
4. **Default window:** the clock at `2026-03-10T03:30Z`. With no parameters: `from 2026-02-09`, `to 2026-03-10`, and 30 entries. With `timezone=America/New_York`: `2026-02-08`/`2026-03-09`.
5. **Range:** `2028-01-01..2028-12-31` gives 200 with 366 entries; `2025-01-01..2026-01-02` (367 days) gives 400 `(from, LENGTH_RULE)`; `from > to` gives `ORDER_RULE`.
6. **Accepted zones:** `UTC`, `Etc/GMT%2B5` (buckets at −05), `Asia/Kathmandu` (the +05:45 edge), `Pacific/Apia` 2011-12-29..31 gives `[x, 0, y]`.
7. **Rejected zones:** each form from §2.1, sent URL-encoded (`%2B05:00`, `UTC%2B5`, `GMT-3`, `Z`, `UT`, `PST`, `america/new_york`, empty, `Mars/Olympus_Mons`). Each gives 400 with exactly `errors = [{timezone, TIMEZONE_RULE}]`, and marker values are absent from the body. Also recorded: an unencoded `+05:00` arrives as `" 05:00"` and is also rejected.
8. **Dates and parameters:** malformed dates; `from=` empty; `tz=UTC`, `Timezone=UTC` and `timezone=UTC&timezone=UTC` give `MALFORMED_REQUEST` with base keys only.
9. **Sources:** `seedClicks(code, 41, t)` with no events gives `totalClicks 41` and `clicksInRange 0`, which pins the `click_count` source. The D94 backwards-clock IT (§6.2, item 4). `lastAccessedAt` equals the latest `clicked_at` after real GETs.
10. **Ownership matrix:**
    - owner 200, other USER 404, ADMIN 200 on alice's link;
    - DEACTIVATED: owner 200;
    - DELETED: owner 404 and ADMIN 404;
    - unknown 404; malformed (`ab`, 33 characters, `a_b`) 404;
    - anonymous 401 with a challenge; bad credentials 401;
    - `Accept: application/xml` 406; POST 405; DELETE as USER 403 and as ADMIN 405;
    - trailing slash (recorded);
    - HEAD variants with controls;
    - bad `timezone` on a not-owned code gives 400, and valid parameters give 404 (§2.5).
    - The `Cache-Control` value is pinned.
11. **Tzdata skew:** not testable end to end, and not needed. Under S-1 the zone never reaches PostgreSQL, and that is pinned by the SQL literal and signature in `RepositoryAnnotationsTest`. The design records this, and QA does not attempt it.

`features/stats.feature`: one scenario or outline per AC (AC1 with the NY spring data, AC2, the AC3 outline over offset/prefixed/garbage/wrong case, AC4 to AC7, AC8), plus the default window and the 367-day case. `OpenApiDocsIT` gets the stats path with exactly `get`, the four parameters, the response codes and the content types from §8.

### 10. Decisions (summary)

S-1 to S-10 are given in full above. The remaining decisions:

```
S-11 Recommendation: one repository EXPLAIN test (enable_seqscan off) proving the index can serve the stats predicate.
Reason:              it is the only guard against a future non-sargable rewrite (for example wrapping clicked_at); it
                     doesn't depend on costs for a tiny table.
Alternative:         no plan test; rely on review.
Trade-off:           it pins the plan's text format (index name, "Index Cond"), which PostgreSQL could reword in a
                     major release.
```
```
S-12 Recommendation: validate parameters (400) before visibility (404); both before any transaction.
Reason:              no DB work for invalid requests, and validation doesn't depend on the link, so it reveals nothing
                     (D74); deterministic precedence.
Alternative:         404 first (loadVisible, then parameters).
Trade-off:           an invalid request against a not-yours code gets 400, not 404. Neither order leaks ownership.
```
```
S-13 Recommendation: R4 and D94 exactly as in §6, in this story (engineer-approved carry-over).
```

If approved, S-1 to S-8, S-10 and S-12 become numbered decisions in `requirements.md`, so that code comments can cite them.

### 11. Open questions for the engineer

- **Q1 (S-1): Java-computed buckets or `AT TIME ZONE`.** The story's Tests-required IT row and its Risk say "using `AT TIME ZONE`", and so does the architecture. Recommendation: Java-computed day starts with `width_bucket` (§4.2). If approved, the orchestrator or planner rewords those two lines. (The risk they describe, DST correctness against a real transition, is still tested exactly.)
- **Q2 (S-2): which timezone strings are accepted.** Recommendation: exactly `UTC` or any JDK tzdb ID, case-sensitive, including backward links and `Etc/GMT±N`. Reject offsets, prefixed offsets, three-letter `SHORT_IDS`-only abbreviations and wrong case.
- **Q3 (S-3, S-4): `from`/`to` semantics.** Inclusive local dates; defaults to the last 30 days ending today in the caller's zone; future dates allowed.
- **Q4 (S-4): limits.** At most 366 days; dates from 1970-01-01 to 9999-12-31.
- **Q5 (S-6): unknown or repeated query parameters.** Recommendation: 400 `MALFORMED_REQUEST` (the D59 reasoning). This is stricter than the details endpoint.
- **Q6 (S-7):** `VALIDATION_FAILED` (recommended) or a new `INVALID_TIMEZONE` code, which would reopen the US-005 catalogue.
- **Q7 (S-8): the response shape.** The field names; `clicks` rather than the story's `count` (the AC8 row); no per-day length field; `lastAccessedAt` in UTC.
- **Q8 (S-12):** 400 before 404.
- **Q9: Done-story test changes** in §6: `PostgresServerErrorsTest` (split plus one new), `RedirectServiceTest` (new variant), `RepositoryAnnotationsTest` (literal plus N4), `ShortUrlRepositoryTest` (new), `ShortUrlServiceTest` (constructor), the N1/N2/N5 formatting changes, and the US-010 AC1 rewording.
- **Q10 (S-11):** include the EXPLAIN test (recommended), or leave plan checks to review.
- **Q11:** stats for a DEACTIVATED link are returned (it follows from reusing `loadVisible`, and matches the details endpoint). Confirm.

### 12. Risks for the implementer and the reviewer

- **The `+` in query strings.** Servlet decoding turns `+` into a space. Tests of `+05:00` or `Etc/GMT+5` that are not sent as `%2B` pass for the wrong reason, or fail confusingly. The ITs must encode, and should also record the unencoded case.
- **`clock.withZone`.** It throws in `TestClock`. Use `LocalDate.ofInstant(clock.instant(), zone)`. Never use the clock's zone, or the default silently becomes the JVM zone rather than UTC.
- **Fixture time binding.** Seeding the fall-back hour with `Timestamp` depends on the JVM default zone. Use `OffsetDateTime` at UTC for `click_event` fixtures.
- **SQL details.** Use `CAST(… AS timestamptz[])`, not `::`, which can break native named-parameter parsing. `ORDER BY day_index` and the 1-based index are both needed by densify. Reviewers should check that the WHERE clause is the only range filter and that it compares the bare `clicked_at` column (sargable).
- **Instant text format.** `Instant.toString()` prints a `+`/`-` year outside 0000–9999. The 1970 to 9999 bounds prevent that for the thresholds, while `rangeEnd` (up to year 10000) is bound as a typed parameter, never as text. The repository test at `9999-12-31` proves that no 500 occurs.
- **Isolation.** `setIsolationLevel` works only with `HibernateJpaDialect`'s prepared connection (Boot's default). If anyone turns off `prepareConnection`, or changes the connection-handling mode, the stats call fails with `InvalidIsolationLevelException`, which is loud rather than silent. The unit test pins the definition, but not the runtime, so the ITs exercise it.
- **Error bodies.** `InvalidStatsQueryException` must carry only enum names and constant texts, never the raw value, even in `getMessage()`. The advice uses the query detail text, not the body text.
- **Hot links.** Query time grows with clicks in the window (§4.5). Watch this in US-014 (statement timeout, rollups).
- **JDK tzdata is now the authority.** A tz rule change needs a JDK update to reach the stats. Record this in the README or runbook at US-015.
- **D94 wording drift.** The US-010 design note still shows the old SQL, because it is a Done story's history. `architecture.md` is authoritative, and it is updated.

### 13. Sources checked (2026-09-30)

- **Java SE 25 API:**
  - `ZoneId.of`: the parsing order (Z, ±offset, UTC/GMT/UT, prefixed offsets, then region IDs from the configured set, which throw `ZoneRulesException` if unknown);
  - `ZoneId.getAvailableZoneIds`: "Offset-based zone IDs are not included", "a modifiable copy";
  - `SHORT_IDS` (deprecated, and in line with TZDB 2024b for EST, MST and HST);
  - `ZoneRulesProvider.getAvailableZoneIds`: "the unmodifiable set";
  - `ZoneRulesProvider`: the default TZDB provider, no dynamic provider in the specification;
  - `LocalDate.atStartOfDay(ZoneId)`: "the earliest valid time";
  - `LocalDate.parse`: ISO_LOCAL_DATE;
  - `DateTimeFormatter.ISO_LOCAL_DATE`: four or more digits, sign beyond 9999, STRICT.
  - https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/time/ZoneId.html, …/zone/ZoneRulesProvider.html, …/LocalDate.html, …/format/DateTimeFormatter.html
- **PostgreSQL 18 docs:**
  - §8.5.3 *Time Zones*: the three forms, the POSIX sign opposite to ISO-8601, case-insensitive names, `pg_timezone_names`;
  - Table 8.12, `z`/`zulu`; the `T` separator note;
  - §9.9.4 *AT TIME ZONE*: `'+05'` meaning UTC−5;
  - §9.3 `width_bucket(anycompatible, anycompatiblearray)`;
  - §13.2 *Transaction Isolation*: READ COMMITTED per-statement snapshots, the REPEATABLE READ single snapshot, read-only transactions never conflict.
  - https://www.postgresql.org/docs/18/datatype-datetime.html, functions-datetime.html, functions-math.html, transaction-iso.html
- **PostgreSQL `REL_18_STABLE` source** `src/backend/utils/adt/datetime.c`:
  - `DecodeTimezoneName` looks up the abbreviation table first, then `pg_tzset`, and raises `ERRCODE_INVALID_PARAMETER_VALUE` "time zone … not recognized";
  - `DecodeTimezoneAbbrev` checks `session_timezone` abbreviations before `zoneabbrevtbl`.
- **pgjdbc 42.7.11** `ConnectionFactoryImpl.createPostgresTimeZone`: the startup `TimeZone` is the JVM default, with the sign of `GMT±` flipped for POSIX.
- **docker-library/postgres `18/alpine3.24/Dockerfile`:** `--with-system-tzdata=/usr/share/zoneinfo`, and the `tzdata` apk.
- **Spring Framework 6.2 reference, *@RequestParam*:** a `MultiValueMap` with no name gets every parameter; `String` targets are not converted.
- **spring-orm 6.2.19 source:**
  - `HibernateJpaDialect.beginTransaction`: isolation and read-only through `prepareConnectionForTransaction` with ON_CLOSE release, otherwise `InvalidIsolationLevelException`; `resetSessionState` resets them;
  - `HibernateJpaVendorAdapter`: `DELAYED_ACQUISITION_AND_HOLD`.
- **Spring Data JPA 3.5 reference, *Transactionality*** (re-used from US-010): declared query methods, default methods included, get no transaction configuration.

## Implementation notes
*(mid-engineer, 2026-09-30)*

**Files changed**
- New, main: `service/StatsPeriod` (validation, defaults, day starts, densify), `service/ShortUrlStats`, `service/DailyClicks`, `service/exception/InvalidStatsQueryException`, `service/exception/StatsParameter`, `api/dto/ShortUrlStatsResponse`, `api/dto/DailyClicksResponse`.
- Edited, main: `api/ShortUrlController` (`stats`, `STATS_PARAMETERS`, OpenAPI), `api/error/GlobalExceptionHandler` (`handleInvalidStatsQuery`, `QUERY_VALIDATION_DETAIL`), `service/ShortUrlService` (constructor, `snapshotRead` template, `stats`), `repository/ClickEventRepository` (`CLICKS_PER_DAY_SQL`, `countClicksPerDayRows`, `countClicksPerDay`, `DayCount`), `repository/ShortUrlRepository` (D94 `GREATEST`), `repository/PostgresServerErrors` (R4 `sqlState` fallback). `SecurityConfig`, `ErrorCode` and the migrations are unchanged.
- New tests: `service/StatsPeriodTest`, `api/ShortUrlStatsWebMvcTest`.
- Edited tests: `service/ShortUrlServiceTest` (constructor + stats section), `api/error/GlobalExceptionHandlerTest` (+3), `repository/ClickEventRepositoryTest` (+14), `repository/RepositoryAnnotationsTest` (+3, literal, threshold), `repository/PostgresServerErrorsTest`, `repository/ShortUrlRepositoryTest`, `service/RedirectServiceTest` (R4, D94, N4 tests; each listed in the Post-completion sections of US-010 and US-007).
- Docs: US-010 AC1 reworded and a Post-completion section added; US-007 Post-completion section added.
- Not touched (QA-owned): N1, N2, N5, `StatsIT`, `stats.feature`, `OpenApiDocsIT`, `ShortUrlTestData.seedClickEvents`.

**Decisions**
- Built as the approved design: Java computes every day start (`atStartOfDay(zone)`), PostgreSQL only runs `width_bucket`. No SQL in `src/main` contains `AT TIME ZONE` or `timezone(`; this is pinned by `RepositoryAnnotationsTest.shouldKeepAnyTimeZoneConversionOutOfEveryRepositorySql`. The only hit of the case-insensitive `grep -rniE "at time zone|timezone\(" src/main` is the enum constant `TIMEZONE(StatsParameter.TIMEZONE_NAME)` in `service/exception/StatsParameter`. The service records name their component `zoneId` (the JSON property of the response is still `timezone`).
- "Today" is `LocalDate.ofInstant(now, zone)`: `StatsPeriod.resolve` takes an `Instant`, so the clock's own zone cannot reach it. The service reads `clock.instant()` once, before the transaction.
- `InvalidStatsQueryException.Violation(StatsParameter parameter, String rule)` takes the `StatsParameter` enum (`TIMEZONE`, `FROM`, `TO`); its message is fixed. The service logs the parameter names at DEBUG.
- `ShortUrlService` constructor: `ClickEventRepository` is the second argument (after `ShortUrlRepository`).
- The service-level "densify precondition" failure (index outside 1..N) throws `IllegalStateException` inside the template, so the transaction rolls back and the advice returns a generic 500.
- Day starts are sent as one ISO-8601 CSV string with `CAST(string_to_array(:dayStarts, ',') AS timestamptz[])`. The range end is a typed `Instant` parameter, never text (year 10000 at the top bound).
- EXPLAIN test (D103): `NamedParameterJdbcTemplate` accepts the exact `CLICKS_PER_DAY_SQL` with bound parameters, so no literal inlining was needed. `SET LOCAL enable_seqscan = off` runs in the test transaction. The plan must name `ix_click_event_short_url_id_clicked_at`, contain an `Index Cond` line with both `short_url_id` and `clicked_at`, and contain no `Seq Scan`. A negative control wraps `clicked_at` in `date_trunc` and shows the `Index Cond` no longer mentions `clicked_at`.
- The web-slice tests are in a new class, `ShortUrlStatsWebMvcTest`, not added to the 70-test `ShortUrlControllerWebMvcTest`. Same setup and imports.

**Deviations from the design note**
- None in behaviour. Three small differences, all inside the design's intent: (1) the slice tests live in a separate class (above); (2) the HEAD "no body" and unencoded `+05:00` -> space checks are not in the slice, because MockMvc neither strips the HEAD body nor decodes `queryParam` values; they are left to the QA ITs over real HTTP; (3) the repository proxy translates the `IllegalArgumentException` of `countClicksPerDay`'s argument checks into `InvalidDataAccessApiUsageException` (with the original as cause), which the repository test pins. Both are a 500 in production, and unreachable from `StatsPeriod`.

**Extra human review**
- `ClickEventRepository.CLICKS_PER_DAY_SQL` and the CSV encoding; `StatsPeriod.resolve` (zone set membership, strict date parse, clamp at 1970-01-01).
- The REPEATABLE READ template: the unit test pins the `TransactionDefinition`, and `StatsIT` (QA) proves that Hibernate applies REPEATABLE READ on a pooled connection (the design risk).
- `@RequestParam MultiValueMap` with `@Parameter(hidden = true)`: check the generated OpenAPI shows exactly the four declared parameters (QA's `OpenApiDocsIT`).

**Test command and result**
`JAVA_HOME=/usr/local/opt/openjdk/libexec/openjdk.jdk/Contents/Home ./mvnw -q clean verify`: exit 0. Surefire: 1052 run, 0 failed, 0 errors, 0 skipped. Failsafe: 866 run, 0 failed, 0 errors, 0 skipped. Merged JaCoCo LINE coverage 650 of 653 (99.5%). (Counts are for the current build, after review round 2 and the QA additions.) The build log has 0 "Failing row" lines.

**Review round 1 fixes (mid-engineer)**
- R1: the `ClickEventRepositoryTest` edits (class Javadoc, imports, field, 14 added tests) are listed test by test in US-010's Post-completion section. A re-check of the other Done-story test files found one more gap: `GlobalExceptionHandlerTest` (US-006), now listed in US-006's Post-completion section. `ShortUrlServiceTest` (US-007), `RedirectServiceTest`, `PostgresServerErrorsTest`, `ShortUrlRepositoryTest` and `RepositoryAnnotationsTest` were already listed.
- R2: the `N4` label in `RepositoryAnnotationsTest` is replaced by a description of what the test guards (D102). A grep of `src/main`, `src/test/java` and SQL for `R<n>`, `N<n>`, `S-<n>`, `Q<n>` and section signs finds nothing else.
- R3: new enum `service/exception/StatsParameter` (`TIMEZONE`, `FROM`, `TO`, `wireName()`, plus `*_NAME` compile-time constants for annotations). `InvalidStatsQueryException.Violation` now takes a `StatsParameter`; `StatsPeriod` lost its three string constants; the handler and the service log use `wireName()`. Tests updated; `StatsPeriodTest.shouldNameTheThreeQueryParametersExactlyAsOnTheWire` pins the names.
- R4: the controller derives `STATS_PARAMETERS` from the enum, and uses the `*_NAME` constants in `@Parameter(name = ...)` and `wireName()` in `getFirst`.
- R5: both EXPLAIN tests call `disableSeqScan()`, which asserts `SHOW enable_seqscan` is `off`.
- R6: `StatsPeriodTest` test renamed `shouldResolveTheBoundDatesAndALongWindowInTheExtremeZonesIncludingTheYear10000End`; it adds `Etc/GMT+12` with `to` 9999-12-31 (end `+10000-01-01T12:00Z`) and a 366-day window ending 9999-12-31. In `ClickEventRepositoryTest` the year-10000 comment now sits on the UTC line.
- R7: the DEBUG log in `ShortUrlService.stats` has `errorCode=VALIDATION_FAILED` in the format string.
- R8: the controller class Javadoc is re-wrapped (longest line is now within 120 characters).
- R9: the `from` parameter description now says the default is "but not before 1970-01-01".

**AC coverage by my tests (Surefire)**
- AC1, AC8, AC10: `StatsPeriodTest` (day starts in New York, Sydney, Sao Paulo, Santiago, Apia, Kathmandu; densify), `ClickEventRepositoryTest` (fixtures a to l at the exact edges, `[1,4,1]` and UTC `[0,4,2]`, DST-day windows, Sydney, Apia, Kathmandu, session `TimeZone` independence, 9999-12-31, 366 thresholds), `ShortUrlStatsWebMvcTest` (exact JSON).
- AC2: `StatsPeriodTest` default, `ShortUrlServiceTest` (Tokyo clock still gives UTC), slice passes null.
- AC3: `StatsPeriodTest` accept and reject tables, slice and `GlobalExceptionHandlerTest` body.
- AC4, AC5, AC6, AC16: `ShortUrlServiceTest` and the slice.
- AC7: slice (401, anonymous and wrong password).
- AC9, AC11, AC12, AC13: `StatsPeriodTest`; slice and advice for the body.
- AC14: slice (13 query shapes, service never called).
- AC15: `ShortUrlServiceTest` (`verifyNoInteractions` on bad parameters, then 404 for the same code with valid parameters), slice.
- D94: `RepositoryAnnotationsTest` literal, `ShortUrlRepositoryTest.shouldNotMoveLastAccessedAtBackwards`. D103: the EXPLAIN tests.

**Open questions**
- None blocking. The Design note's section 4.5 said the test inlines literals if pgjdbc rejects parameters inside EXPLAIN. It does not, so no inlining was done.

## QA notes
*(qa-tester, 2026-09-30)*

**Files**
- New: `src/test/resources/features/stats.feature` (35 scenarios and outlines, of which 7 are outlines; 64 Cucumber scenarios after Examples expansion), `cucumber/StatsSteps.java`, `support/StatsIT.java` (155 tests after parameterisation).
- Edited: `support/OpenApiDocsIT.java` (+6 tests), `support/ShortUrlTestData.java` (`seedClickEvents`, N1), `support/ApiClient.java` (`headersExceptFraming`, used for HEAD), `support/ClickRecordingConcurrencyIT.java` (N2), `support/GetShortUrlIT.java`, `support/RedirectIT.java`, `cucumber/RedirectSteps.java` (N5: the three private `stableHeaders` helpers differ from `ApiClient.stableHeaders` because they keep Content-Length, so they are renamed `headersExceptDate` with a one-line Javadoc; assertions unchanged).
- No production code was touched. No change to the Spring context: `StatsIT` adds only `@ExtendWith(OutputCaptureExtension.class)`, which is not part of the context cache key.

**AC to test mapping** (F = `stats.feature`, IT = `StatsIT`, API = `OpenApiDocsIT`)

| AC | Cucumber | `StatsIT` |
|---|---|---|
| AC1 | F: New York spring (23 h) and autumn (25 h), Sydney spring and autumn (southern hemisphere), clicks at the exact edges; end-to-end redirect clicks | `shouldBucketByLocalDayInTheRequestedZoneAndByUtcDayWhenNoZoneIsGiven` (7 fixtures: NY x2, Sydney x2, Sao Paulo, Apia, Kathmandu, each checked against java.time first), `shouldCountOnlyTheClicksOfAOneDayWindowOnADstDay...` (4 DST days as `from == to`), `shouldShowRealRedirectClicks...`, `shouldTakeTotalClicksFromTheStoredCounter...` |
| AC2 | F: no timezone gives UTC buckets and `"UTC"` | same fixture test (UTC and explicit `timezone=UTC` compared) |
| AC3 | F: rejected outline (8 forms) plus a separate empty-`timezone` scenario, accepted outline (4 forms) | `shouldRejectEveryNonIanaZone...` (24 forms), `shouldAcceptEveryIdOfTheJdkZoneRulesProviderAndEchoItExactly` (every tzdb ID plus `UTC`, URL-encoded), `shouldAcceptEveryIanaIdOfTheJdkSet`, `shouldBucketEtcGmtPlusFive...`, `shouldReadAnUnencodedPlusAsASpace...`, EST/MST/HST, long zones, log check |
| AC4 | F: bob gets 404 | `shouldApplyTheOwnerAdminAndHiddenRulesToStats` (17 rows), `shouldGiveIdenticalNotFoundBodies...` |
| AC5 | F: admin 200 | same matrix |
| AC6 | F: deleted and unknown for alice and admin, malformed `ab` | matrix, malformed codes, `shouldStopReturningStatsWhenTheLinkIsSoftDeleted...` |
| AC7 | F: anonymous and wrong password | `shouldReturn401WithAChallenge...` (also 401 before 400) |
| AC8 | F: `[1,0,1]`, Samoa skipped date, Sao Paulo midnight gap | `shouldListAZeroClickDay...`, Apia and Sao Paulo fixtures |
| AC9 | F: UTC and New York defaults, each default on its own | `shouldDefaultToTheLastThirtyDays...`, `shouldApplyEachDefaultIndependently...`, `shouldFollowTheClockAcrossMidnight...` (TestClock, never `withZone`) |
| AC10 | F: `from == to`, future window | `shouldReturnOneEntryForASingleDay...`, lower bound 1970-01-01, upper bound 9999-12-31 with `Pacific/Kiritimati` |
| AC11 | F: 366 ok, 367 refused on `from` | `shouldAcceptExactly366DaysAndRefuse367` |
| AC12 | F: outline of 9 malformed or out-of-range dates plus a separate empty-`from` scenario | `shouldRefuseMalformedOrOutOfRangeDates...` (21 forms incl. Arabic-Indic digits, `0000-01-01`, `10000-01-01`), multi-error sorting |
| AC13 | F: `from` after `to` | `shouldRefuseFromAfterTo...` |
| AC14 | F: outline of 5 shapes | `shouldRefuseAnUnknownOrRepeatedParameter...` (14 shapes), known parameter with no value is VALIDATION_FAILED |
| AC15 | F: outline over visible-to-nobody codes (not yours, unknown, deleted) | `shouldRefuseBadParametersBeforeLookingTheLinkUpAndGiveTheIdentical404...` (7 codes), admin on a deleted link |
| AC16 | F: owner and admin 200, bob 404 | `shouldReturnStatsForADeactivatedLink...` and matrix rows |

Other tests: `*IT` rows of the Tests-required table are `shouldBucketBy...` (DST, boundaries, gap days, zero-click day) and `shouldTakeTotalClicksFromTheStoredCounter...` (41 divergent counter) with `assertDenseAndConsistent` (`clicksInRange` equals the sum of `daily`, dates consecutive) applied to every 200 body.
- D94: `shouldKeepTheLaterLastAccessAndCountBothClicksWhenTheClockMovesBackwards` (and F: clock moves backwards) over HTTP, asserting two events, count 2 and `lastAccessedAt` still the later instant in both the details and the stats.
- D102 REPEATABLE READ: `shouldServeOneSnapshotWhenAClickCommitsBetweenTheLinkLookupAndTheDailyQuery` holds `ACCESS EXCLUSIVE` on `click_event` from a test connection so the daily query blocks (found via `pg_stat_activity`), commits a click in that window, and asserts totalClicks, clicksInRange, daily and lastAccessedAt all show the old state, then that the next call shows the click. Two harness controls prove it is not vacuous: the same flow by hand at READ COMMITTED shows `(0, 1)` and at REPEATABLE READ `(0, 0)`. `shouldLeaveEveryPooledConnectionAtReadCommittedAndWritable...` checks the isolation and read-only reset on every pooled connection.
- HEAD (real HTTP): `shouldAnswerHeadWithTheSameStatusAndHeadersAsGetAndAnEmptyBody` (8 rows: 200, 404 x3, 400 x3, 401, each with a GET control asserting `errorCode`) and F. Compared with `ApiClient.headersExceptFraming`: HEAD has no `Transfer-Encoding: chunked`, and Tomcat adds `Connection: close` to some 4xx GET responses, which is transport framing, not representation.
- Methods and negotiation: POST, PUT, PATCH 405 (Allow recorded: contains GET, no POST or DELETE); DELETE 403 for a USER, 405 for an ADMIN; `Accept: application/xml` and `application/problem+json` 406 before validation; trailing slash is 404 `RESOURCE_NOT_FOUND`; `Cache-Control` pinned to the D73 default on 200 and on errors.
- Rejected values never reach the log (`shouldNeverWriteTheRejectedValuesToTheLog`).
- API: `OpenApiDocsIT` has exactly `get` on the stats path, exactly `code`, `timezone`, `from`, `to`, the `Etc/GMT+5` BEHIND UTC warning and the `%2B` hint, `format: date`, exactly 200/400/401/404/406, JSON or problem+json only.
- Guardrails: fixtures are bound as UTC `OffsetDateTime` (`seedClickEvents`); `%2B` is used for `+`; an unencoded `+05:00` and an unencoded `Etc/GMT+5` are both refused (the `+` arrives as a space); "today" comes from `TestClock.setInstant` only.

**Result** (`JAVA_HOME=/usr/local/opt/openjdk/libexec/openjdk.jdk/Contents/Home ./mvnw -q clean verify`): exit 0. Surefire 1052 run, 0 failures, 0 errors, 0 skipped. Failsafe 866 run, 0 failures, 0 errors, 0 skipped (CucumberIT 240, of which 64 are stats scenarios; StatsIT 155; OpenApiDocsIT 37). 0 "Failing row" lines. No test was skipped, weakened or deleted.

**Defects**
- None found in production code.

**Findings and notes for the architect and engineer (no production defect)**
1. Design note table 2.1 says `EST` is accepted. It is not: the JDK leaves `EST`, `MST` and `HST` out of `ZoneRulesProvider.getAvailableZoneIds()` (they are `ZoneId.SHORT_IDS` only), so the D96 membership rule rejects them with 400 `VALIDATION_FAILED`. This matches AC3's wording ("exactly an ID in the JDK's `ZoneRulesProvider.getAvailableZoneIds()`", "short IDs such as `PST`") and is pinned by `shouldRejectEstBecauseTheJdkLeavesItOut...`. The design table row should be corrected. `EST5EDT`, `GMT`, `US/Eastern` and `Asia/Calcutta` are accepted.
2. A query longer than Tomcat's 8 KiB request-line limit (for example the 10 000-character zone of the design table) is answered by the connector with a bare 400 (`text/html`, not `application/problem+json`), before the application runs. It echoes nothing and leaks no internals, which `shouldRefuseAZoneBeyondTheConnectorLimit...` pins, but the error body is not the RFC 7807 shape. This is general to every endpoint and outside US-011; recorded for US-014 (hardening) if the engineer wants a uniform error shape. A 5 000-character zone reaches the application and gets the normal 400.
3. Tzdata skew between the JDK and the Postgres image is not tested end to end; D95 removes it by construction, because no zone reaches PostgreSQL.
4. No acceptance criterion was ambiguous or untestable.

### Review round 1 fixes (qa-tester)
- Done-story test edits are listed test by test: `ShortUrlTestData` and `ClickRecordingConcurrencyIT` in US-010; `GetShortUrlIT` and `OpenApiDocsIT` in US-007; `RedirectIT` and `RedirectSteps` in US-008; `ApiClient` in US-006.
- `RedirectIT`: the over-long HEAD/GET header comparison line is wrapped.
- `ApiClient.headersExceptFraming`: static constant set and `Locale.ROOT`.
- `StatsIT`: variable `spring` renamed `fixture`; `URLDecoder`, `URLEncoder`, `StandardCharsets`, `TreeSet`, `ZoneRulesProvider` and `ObjectNode` imported instead of inline qualified names.
- `stats.feature`: "does not echo" rows that could never match now use distinctive markers (`zzunknown`, `Asia/Tokyo`, `2026-03-02`); the empty `timezone=` and `from=` rows moved to their own scenarios that check only the field, without the echo step.
- No review-finding labels appear in test code or comments.

## Review log
*(senior-engineer findings per round, and how each was resolved)*

| Round | ID | Severity | Finding | Resolution |
|---|---|---|---|---|

## Post-completion change (engineer-approved at the US-016 G2; D106–D127; main session, US-016)

- `StatsPeriodTest.shouldSumTheDailyCountsIntoClicksInRange` — **updated**: `ShortUrlStats.of` takes the expiry and the `expired` flag.
- `ShortUrlStatsWebMvcTest`, `StatsIT` and `StatsSteps` — **updated** class-level `STATS_KEYS` (two added keys).
- `OpenApiDocsIT.shouldDocumentTheStats200AsJsonOnlyWithTheD101FieldsAndTheDailyEntryShape` — **updated**: `expiresAt` and `expired`.

## Post-completion change (engineer-approved; D135; main session, API documentation removal)

- `OpenApiDocsIT` — **removed**. Methods owned by this story: `shouldDocumentOnlyGetOnTheStatsPathWithExactlyTheFourDeclaredParameters`, `shouldDocumentTheTimezoneRuleTheSignWarningAndThePlusEncoding`, `shouldDocumentFromAndToAsOptionalDatesWithTheirDefaultsAndLimits`, `shouldDocumentTheStatsResponsesAsExactlyTheFiveExpectedStatuses`, `shouldDocumentTheStats200AsJsonOnlyWithTheD101FieldsAndTheDailyEntryShape`, `shouldApplyBasicAuthenticationAndDescribeLocalDaysAndTheUtcLastAccess`. The timezone, window and sign-convention rules now live in the `ShortUrlController.stats` Javadoc and D96.
