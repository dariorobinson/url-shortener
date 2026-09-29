---
id: US-008
title: Redirect endpoint
status: Open
plan_task: 7
depends_on: [US-002, US-005, US-006]
requirements: [FR-2, FR-8, D2, D7, D18, D31, D32]
requires_design_approval: true
---

# US-008: Redirect endpoint

## User story
As any visitor, I want to follow a short link, so that I am redirected to the original URL.

## Acceptance criteria
- **AC1:** Given `{code}` belongs to an `ACTIVE` link, when `GET /{code}` is called, then the response is `302 Found` with `Location` set to the stored `original_url` exactly and header `Cache-Control: no-store`.
- **AC2:** Given `{code}` does not exist, when `GET /{code}` is called, then the response is `404 Not Found` with `errorCode: "SHORT_URL_NOT_FOUND"` (D31).
- **AC3:** Given `{code}` belongs to a `DEACTIVATED` link, when `GET /{code}` is called, then the response is `404 Not Found` with the same `errorCode` as AC2 — indistinguishable from unknown, per D2.
- **AC4:** Given `{code}` belongs to a `DELETED` link, when `GET /{code}` is called, then the response is `404 Not Found` with the same `errorCode` as AC2.
- **AC5:** Given requests to `/api/**`, `/actuator/**`, `/v3/api-docs/**`, and Swagger UI paths, when they are made, then they are routed to their own handlers and are never matched by the `/{code}` redirect mapping — verified with a case where a reserved prefix segment (e.g. `/api`) would otherwise look like a valid short code.
- **AC6:** Given `{code}` does not match the short-code shape `^[A-Za-z0-9]{3,32}$`, when `GET /{code}` is called, then the response is `404 Not Found` without requiring a database round trip to prove it (defensive path-pattern constraint at the routing layer, consistent with the DB `ck_short_url_code_format` constraint in US-002).
- **AC7:** Given `{code}` belongs to an `ACTIVE` link, when `HEAD /{code}` is called instead of `GET`, then the response is `302 Found` with the same `Location` and `Cache-Control: no-store` headers as AC1 (D18, D32 — public, same redirect semantics as GET). Whether the click is counted is out of scope here and is proved by US-010.

## Tests required
| Type | Behaviour proven | Owner |
|---|---|---|
| Cucumber | `GET /{code}` on an active link returns 302 with correct `Location`/`Cache-Control` (AC1) | qa-tester |
| Cucumber | `GET /{code}` on unknown, deactivated, and deleted codes all return 404 `SHORT_URL_NOT_FOUND`, indistinguishable (AC2–AC4) | qa-tester |
| Cucumber | `HEAD /{code}` on an active link returns 302 with the same `Location`/`Cache-Control` as `GET` (AC7) | qa-tester |
| Integration (`*IT`) | 302 with correct `Location` and `Cache-Control` for an active link (AC1) | qa-tester |
| Integration (`*IT`) | 404 for unknown, deactivated, and deleted codes, all indistinguishable (AC2–AC4) | qa-tester |
| Web slice/Integration (`*IT`) | Routing precedence: `/api`, `/actuator/health`, OpenAPI paths are not captured by the redirect mapping (AC5) | mid-engineer / qa-tester |
| Unit/Web slice | Path-pattern rejection for malformed codes returns 404 without a repository call (AC6) | mid-engineer |

## Out of scope
- Click counting/analytics — added to this endpoint's flow by US-010, not by this story.

## Risks
- If the `/{code}` mapping is registered with too broad a pattern or too high a priority relative to `/api/**`, it can accidentally shadow the management API. This must be verified with an explicit routing-precedence test, not just manual spot-checking.

## Open questions
- None. D18/D32 fix `HEAD /{code}`'s status (302, same as GET) and public access; whether it is counted is proved by US-010, not this story.

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
