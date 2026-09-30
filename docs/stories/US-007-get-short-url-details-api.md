---
id: US-007
title: Get short URL details API
status: Open
plan_task: 6
depends_on: [US-002, US-005, US-006]
requirements: [FR-12, D4, D13, D31, D58, D67, D71]
requires_design_approval: true
---

# US-007: Get short URL details API

## User story
As the owner of a short URL (or an ADMIN), I want to retrieve its details, so that I can confirm what was created and see its current status.

## Acceptance criteria
- **AC1:** Given the caller is the owner (`created_by` matches the authenticated principal) and the link is `ACTIVE` or `DEACTIVATED`, when `GET /api/v1/urls/{code}` is called, then the response is `200 OK` with a body containing exactly the D58 field set, identical to the US-006 create response: `shortCode`, `shortUrl`, `originalUrl`, `status`, `customAlias`, `clickCount`, `createdAt` (ISO-8601 UTC) and `lastAccessedAt` (always present; `null` until the first click). `createdBy`, `id`, `updatedAt` and `version` are not returned (D58, D67).
- **AC2:** Given the caller has role `ADMIN`, when `GET /api/v1/urls/{code}` is called for any link regardless of owner, then the response is `200 OK` with the same shape.
- **AC3:** Given the caller is an authenticated `USER` who does not own the link, when `GET /api/v1/urls/{code}` is called, then the response is `404 Not Found` with `errorCode: "SHORT_URL_NOT_FOUND"` — ownership is never revealed by a 403 (D4).
- **AC4:** Given `{code}` does not exist, when `GET /api/v1/urls/{code}` is called, then the response is `404 Not Found` with `errorCode: "SHORT_URL_NOT_FOUND"`.
- **AC5:** Given `{code}` belongs to a `DELETED` link, when `GET /api/v1/urls/{code}` is called by any caller including `ADMIN`, then the response is `404 Not Found` (D13 — deleted links are invisible to everyone in the management API).
- **AC6:** Given no credentials are supplied, when `GET /api/v1/urls/{code}` is called, then the response is `401` with `errorCode: "AUTHENTICATION_REQUIRED"` (D31).

## Tests required
| Type | Behaviour proven | Owner |
|---|---|---|
| Unit | Ownership check logic: owner vs non-owner vs ADMIN vs deleted (AC1–AC5) | mid-engineer |
| Web slice | 200 body has exactly the D58 field set (`shortCode`, `shortUrl`, `originalUrl`, `status`, `customAlias`, `clickCount`, `createdAt`, `lastAccessedAt`), with `lastAccessedAt` present as `null` before any click and `createdBy`, `id`, `updatedAt`, `version` absent; status codes for AC4 and AC6 wired through the controller (AC1, AC4, AC6) | mid-engineer |
| Cucumber | Owner retrieves their own link and gets 200 with exactly the D58 field set (no extra fields; `createdBy`, `id`, `updatedAt`, `version` absent; `lastAccessedAt` is `null` before the first click; shape matches the US-006 create response) (AC1) | qa-tester |
| Cucumber | ADMIN retrieves any link and gets 200 (AC2) | qa-tester |
| Cucumber | Non-owner USER retrieves someone else's link and gets 404 `SHORT_URL_NOT_FOUND` (AC3) | qa-tester |
| Cucumber | Unknown `{code}` returns 404 `SHORT_URL_NOT_FOUND` (AC4) | qa-tester |
| Cucumber | A `DELETED` link returns 404 for any caller including ADMIN (AC5) | qa-tester |
| Cucumber | No credentials returns 401 `AUTHENTICATION_REQUIRED` (AC6) | qa-tester |
| Integration (`*IT`) | Real ownership scenarios against Testcontainers PostgreSQL: owner, non-owner USER, ADMIN, deleted link (AC1–AC5) | qa-tester |

## Out of scope
- Statistics (click counts over time, timezone bucketing) — see US-011. This story returns only the aggregate `clickCount` already stored on the row, not a daily breakdown.
- Lifecycle changes (PATCH/DELETE) — see US-009.

## Risks
- None beyond those already flagged in US-006 (shared response shape). The `errorCode` catalogue itself is fixed by D31.

## Open questions
- None. The response shape is resolved by D58 (shared with US-006) and D67 (AC1 lists `shortUrl` and the full D58 field set).

## Carry-over from US-006 (engineer-approved at US-006 G3)
- **N1 (NIT, qa-tester):** move `import java.util.UUID` into order in `SecurityIT`.
- **N2 (NIT, qa-tester):** import the inline fully qualified `java.util.ArrayList` (`OpenApiDocsIT`) and `java.util.Spliterators` (`SecurityIT`). Reuse `ApiClient.keys` for the duplicated key-set extraction in `SecurityIT`.
- **N3 (NIT, qa-tester):** the `Host: bad host` positive control in `CreateShortUrlIT` must also assert that the 400 came from Tomcat (no `errorCode` and no `application/problem+json`).
- **D71 note (product):** after an unexpected `409 ALIAS_ALREADY_EXISTS` on create, a client can call `GET /api/v1/urls/{alias}` to check. It returns 200 only if the alias is the caller's own (D4), which confirms that an earlier create whose 201 was lost did succeed. The US-007 OpenAPI description for `GET /api/v1/urls/{code}` should mention this use.

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
