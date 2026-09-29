---
id: US-007
title: Get short URL details API
status: Open
plan_task: 6
depends_on: [US-002, US-005, US-006]
requirements: [FR-12, D4, D13, D31]
requires_design_approval: true
---

# US-007: Get short URL details API

## User story
As the owner of a short URL (or an ADMIN), I want to retrieve its details, so that I can confirm what was created and see its current status.

## Acceptance criteria
- **AC1:** Given the caller is the owner (`created_by` matches the authenticated principal) and the link is `ACTIVE` or `DEACTIVATED`, when `GET /api/v1/urls/{code}` is called, then the response is `200 OK` with the same resource shape produced by US-006 (`shortCode`, `originalUrl`, `status`, `customAlias`, `clickCount`, `createdAt`, `lastAccessedAt`).
- **AC2:** Given the caller has role `ADMIN`, when `GET /api/v1/urls/{code}` is called for any link regardless of owner, then the response is `200 OK` with the same shape.
- **AC3:** Given the caller is an authenticated `USER` who does not own the link, when `GET /api/v1/urls/{code}` is called, then the response is `404 Not Found` with `errorCode: "SHORT_URL_NOT_FOUND"` — ownership is never revealed by a 403 (D4).
- **AC4:** Given `{code}` does not exist, when `GET /api/v1/urls/{code}` is called, then the response is `404 Not Found` with `errorCode: "SHORT_URL_NOT_FOUND"`.
- **AC5:** Given `{code}` belongs to a `DELETED` link, when `GET /api/v1/urls/{code}` is called by any caller including `ADMIN`, then the response is `404 Not Found` (D13 — deleted links are invisible to everyone in the management API).
- **AC6:** Given no credentials are supplied, when `GET /api/v1/urls/{code}` is called, then the response is `401` with `errorCode: "AUTHENTICATION_REQUIRED"` (D31).

## Tests required
| Type | Behaviour proven | Owner |
|---|---|---|
| Unit | Ownership check logic: owner vs non-owner vs ADMIN vs deleted (AC1–AC5) | mid-engineer |
| Web slice | Response shape and status codes wired through the controller (AC1, AC4, AC6) | mid-engineer |
| Cucumber | Owner retrieves their own link and gets 200 with the documented shape (AC1) | qa-tester |
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
- Same response-shape open question as US-006 applies here (the exact field set beyond `shortUrl`/`Location`, e.g. whether `clickCount`/`lastAccessedAt` appear); the two must stay in sync since both surface the same resource type.

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
