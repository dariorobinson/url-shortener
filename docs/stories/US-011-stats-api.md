---
id: US-011
title: Statistics API
status: Open
plan_task: 9
depends_on: [US-005, US-007, US-010]
requirements: [FR-5, FR-12, D8, D10, D19, D31]
requires_design_approval: true
---

# US-011: Statistics API

## User story
As the owner of a short URL (or an ADMIN), I want to see total clicks and a per-day breakdown in my own time zone, so that I can understand how the link is being used.

## Acceptance criteria
- **AC1:** Given the caller owns the link, when `GET /api/v1/urls/{code}/stats?timezone=America/New_York` is called, then the response is `200 OK` with the total click count, `last_accessed_at`, and a per-day click breakdown bucketed using the `America/New_York` calendar day (correct across a DST transition, proven with data straddling one).
- **AC2:** Given no `timezone` parameter is supplied, when `GET /api/v1/urls/{code}/stats` is called, then buckets default to `UTC` (D10).
- **AC3:** Given `timezone` is not `"UTC"` and not a valid IANA zone ID (e.g. a raw offset like `+05:00`, or a garbage string), when `GET /api/v1/urls/{code}/stats` is called, then the response is `400 Bad Request` with an `errorCode` from the D31 catalogue (proposed: `VALIDATION_FAILED` — D31 has no dedicated timezone error code; see Open questions).
- **AC4:** Given the caller is an authenticated `USER` who does not own the link, when the stats endpoint is called, then the response is `404 Not Found` with `errorCode: "SHORT_URL_NOT_FOUND"` (D4).
- **AC5:** Given the caller has role `ADMIN`, when the stats endpoint is called for any link, then the response is `200 OK`.
- **AC6:** Given `{code}` does not exist or belongs to a `DELETED` link, when the stats endpoint is called by any caller, then the response is `404 Not Found`.
- **AC7:** Given no credentials are supplied, when `GET /api/v1/urls/{code}/stats` is called, then the response is `401 Unauthorized` with `errorCode: "AUTHENTICATION_REQUIRED"` (D31, per US-005).
- **AC8:** Given a link has clicks on day 1 and day 3 of a 3-day window but none on day 2, when `GET /api/v1/urls/{code}/stats` is called for that window, then the daily breakdown includes day 2 with a zero count rather than omitting it (D19 — zero-click days are included in the series).

## Tests required
| Type | Behaviour proven | Owner |
|---|---|---|
| Unit | Timezone parameter validation (`UTC` and valid IANA IDs accepted; offsets and garbage rejected) (AC3) | mid-engineer |
| Web slice | Status codes, ownership/role enforcement, default-timezone behaviour (AC2, AC4, AC5, AC6) | mid-engineer |
| Cucumber | Owner requests stats with an explicit `timezone` and gets 200 with total/last-accessed/daily breakdown (AC1) | qa-tester |
| Cucumber | Stats with no `timezone` defaults to UTC bucketing (AC2) | qa-tester |
| Cucumber | Invalid `timezone` (offset or garbage) returns 400 with the proposed `errorCode` (AC3) | qa-tester |
| Cucumber | Non-owner `USER` requests stats for another's link: 404 `SHORT_URL_NOT_FOUND` (AC4) | qa-tester |
| Cucumber | `ADMIN` requests stats for any link: 200 (AC5) | qa-tester |
| Cucumber | Unknown or `DELETED` `{code}`: 404 (AC6) | qa-tester |
| Cucumber | No credentials: 401 `AUTHENTICATION_REQUIRED` (AC7) | qa-tester |
| Cucumber | A window with a zero-click day in the middle still lists that day with `count: 0` (AC8) | qa-tester |
| Integration (`*IT`) | Daily bucketing against Testcontainers PostgreSQL using `AT TIME ZONE`, including a case spanning a DST transition (AC1) and a case with a zero-click gap day (AC8) | qa-tester |

## Out of scope
- Recording clicks — see US-010.
- Any UI/visualization of stats.

## Risks
- Grouping in the database via `AT TIME ZONE` is correct for DST but must be tested against a real transition date, not just an arbitrary date, or a subtle off-by-one-hour bug could pass review unnoticed.

## Open questions
- The `from`/`to` query parameter semantics referenced in `docs/architecture.md`'s REST table are still not specified: defaults when omitted (all-time? last 30 days?), a maximum allowed range, and the expected date/date-time format. Needs an engineer decision.
- The response JSON shape (field names, whether the daily breakdown is an array of `{date, count}` objects or a map keyed by date) is still not specified — D19 only settles that zero-click days appear with `count: 0` rather than being omitted; the surrounding shape (field names, array vs. map) needs an engineer decision at the design gate.
- D31's `ErrorCode` catalogue has no entry specific to an invalid timezone. AC3 proposes reusing `VALIDATION_FAILED`. Needs engineer confirmation: either accept `VALIDATION_FAILED`, or add a dedicated `INVALID_TIMEZONE` code to the D31 catalogue (which would also require revisiting US-005, where the catalogue was ratified).

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
