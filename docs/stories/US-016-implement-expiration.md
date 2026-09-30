---
id: US-016
title: Implement URL expiration
status: Open
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
*(main session; requires engineer approval of the exact V3 SQL before coding)*

## Implementation notes

## Review log
| Round | ID | Severity | Finding | Resolution |
|---|---|---|---|---|
