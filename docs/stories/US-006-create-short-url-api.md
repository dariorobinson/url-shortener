---
id: US-006
title: Create short URL API
status: Open
plan_task: 6
depends_on: [US-002, US-003, US-004, US-005]
requirements: [FR-1, FR-3, FR-7, FR-10, FR-11, D4, D5, D6, D11, D17, D28, D29, D30, D31, D33]
requires_design_approval: true
---

# US-006: Create short URL API

## User story
As an authenticated USER (or ADMIN), I want to submit a long URL, optionally with a custom alias, and receive a short code, so that I can share a shortened link.

## Acceptance criteria
- **AC1:** Given an authenticated caller submits `{"originalUrl": "https://example.com/page"}` with no alias, when `POST /api/v1/urls` is called, then the response is `201 Created` with a `Location` header of `/api/v1/urls/{code}` (D33 — the management resource, not the public redirect) and a body including at least `shortCode`, `shortUrl`, `originalUrl`, `status: "ACTIVE"`, `customAlias: false`, `createdAt`.
- **AC2:** Given the caller also submits a valid, unused `alias` (D17 — the request field is named `alias`), when `POST /api/v1/urls` is called, then the response is `201 Created`, `shortCode` equals the submitted alias, and `customAlias: true`.
- **AC3:** Given the submitted alias already exists (any status), when `POST /api/v1/urls` is called, then the response is `409 Conflict` with `errorCode: "ALIAS_ALREADY_EXISTS"` (D31); no retry is attempted for custom aliases (per architecture).
- **AC4:** Given the submitted alias violates `AliasPolicy` (bad length, bad charset, or reserved word), when `POST /api/v1/urls` is called, then the response is `400 Bad Request` with `errorCode: "INVALID_ALIAS"` and a validation-error body identifying the field.
- **AC5:** Given `originalUrl` fails `UrlValidator` (wrong scheme, too long, embedded credentials, or self-referencing host per D28), when `POST /api/v1/urls` is called, then the response is `400 Bad Request` with `errorCode: "INVALID_URL"`.
- **AC6:** Given no alias is submitted and the generator produces a code that collides with an existing row on the first attempt (forced via a test seam that pre-inserts a colliding row), when `POST /api/v1/urls` is called, then the service retries in a fresh transaction, succeeds on a subsequent attempt (within the configured max), and returns `201 Created`.
- **AC7:** Given no alias is submitted and every attempt up to the configured maximum collides (forced via the same test seam), when `POST /api/v1/urls` is called, then the response is `503 Service Unavailable` with `errorCode: "SHORT_CODE_UNAVAILABLE"` (D31), and no row is committed.
- **AC8:** Given no credentials are supplied, when `POST /api/v1/urls` is called, then the response is `401` with `errorCode: "AUTHENTICATION_REQUIRED"` (D31, per US-005).
- **AC9:** Given two concurrent `POST` requests both submit the same custom alias, when both execute concurrently, then exactly one succeeds with `201` and the other fails with `409`/`ALIAS_ALREADY_EXISTS` — the database unique constraint (US-002) is the final guarantee, not an application-level check-then-act.
- **AC10:** Given a successful create, when the row is persisted, then `created_by` is set to the authenticated principal's username (foundation for ownership, D4); it is not returned in the response body to other callers (enforced together with US-007).
- **AC11:** Given the same `originalUrl` is submitted twice by the same caller in two separate requests, when `POST /api/v1/urls` is called each time, then both responses are `201 Created` and the two `shortCode` values are different (D5 — no deduplication; a new code is always created).
- **AC12:** Given the request body is missing `originalUrl` or has a blank `originalUrl`, when `POST /api/v1/urls` is called, then the response is `400 Bad Request` with a `ProblemDetail` body and `errorCode: "VALIDATION_FAILED"` (D31), and the body contains no stack trace and no internal exception message.
- **AC13:** Given the application is running, when `GET /v3/api-docs` is requested, then the generated OpenAPI document includes the `POST /api/v1/urls` operation with its request and response schemas (Task 6 requires OpenAPI docs).
- **AC14:** Given the request body is syntactically malformed JSON (e.g. an unterminated object), when `POST /api/v1/urls` is called, then the response is `400 Bad Request` with `errorCode: "MALFORMED_REQUEST"` (D31, distinct from AC12's `VALIDATION_FAILED`), and the body contains no internal parser exception message.
- **AC15:** Given no alias is submitted and the generator's first attempt produces a code that matches a configured reserved word (forced via the test seam — D29), when `POST /api/v1/urls` is called, then the service treats the match as a collision, retries in a fresh transaction, and succeeds within the configured max attempts, returning `201 Created` with a `shortCode` that is not a reserved word.
- **AC16:** Given `APP_BASE_URL=https://short.example` is configured, when `POST /api/v1/urls` is called with a request header `Host: attacker.example`, then the response body's `shortUrl` is built from `APP_BASE_URL` (`https://short.example/{code}`) and never reflects the spoofed `Host` header (D33 — prevents host-header injection).

## Tests required
| Type | Behaviour proven | Owner |
|---|---|---|
| Unit | Service create logic: alias path vs generated-code path, retry loop bounds, mapping to DTO | mid-engineer |
| Web slice | Request validation (400s for malformed JSON, missing required fields), auth wiring (401), response shape (AC1, AC2, AC4, AC5, AC8, AC12, AC14) | mid-engineer |
| Cucumber | Create with no alias returns 201, `Location: /api/v1/urls/{code}`, and the documented body shape (AC1) | qa-tester |
| Cucumber | Create with a valid custom alias returns 201 with `shortCode` equal to the alias and `customAlias: true` (AC2) | qa-tester |
| Cucumber | Create with a duplicate alias returns 409 `ALIAS_ALREADY_EXISTS` (AC3) | qa-tester |
| Cucumber | Create with an invalid alias (bad length/charset/reserved word) returns 400 `INVALID_ALIAS` (AC4) | qa-tester |
| Cucumber | Create with an invalid `originalUrl` (bad scheme, too long, credentials, self-referencing host) returns 400 `INVALID_URL` (AC5) | qa-tester |
| Cucumber | Create with no credentials returns 401 `AUTHENTICATION_REQUIRED` (AC8) | qa-tester |
| Cucumber | Submitting the same `originalUrl` twice produces two 201s with distinct `shortCode`s (AC11) | qa-tester |
| Cucumber | Missing/blank `originalUrl` returns 400 `VALIDATION_FAILED`; malformed JSON returns 400 `MALFORMED_REQUEST` (AC12, AC14) | qa-tester |
| Cucumber | Create with a spoofed `Host` header still returns `shortUrl` built from `APP_BASE_URL` (AC16) | qa-tester |
| Integration (`*IT`) | Forced real unique-constraint collision against Testcontainers PostgreSQL proves retry-in-fresh-transaction works (AC6) and exhaustion returns 503 `SHORT_CODE_UNAVAILABLE` with no committed row (AC7) | qa-tester |
| Integration (`*IT`) | Duplicate custom alias against a pre-existing row returns 409 (AC3) | qa-tester |
| Integration (`*IT`) | Two simultaneous requests with the same alias resolve to exactly one 201 and one 409 (AC9) | qa-tester |
| Integration (`*IT`) | A forced reserved-word-matching generated code is treated as a collision and retried, succeeding with a non-reserved `shortCode` (AC15) | qa-tester |
| Integration (`*IT`) | The generated OpenAPI document at `/v3/api-docs` includes the `POST /api/v1/urls` operation (AC13) | qa-tester |

## Out of scope
- `GET /api/v1/urls/{code}` — see US-007.
- Redirect endpoint — see US-008.
- The single `@RestControllerAdvice`/`ProblemDetail` infrastructure is introduced by this story (first API endpoint) and reused by every later API story; changing its shape later affects all of them.

## Risks
- This story establishes the created-resource JSON field set; every later API story inherits this convention, so getting it wrong here is expensive to fix later. The `errorCode` catalogue itself is already fixed by US-005/D31, so that risk is retired.
- The forced-collision test seam (a way to make the generator return a predetermined/colliding code in a test) needs a clean design that doesn't leak test-only code paths into production logic (e.g. an injectable `ShortCodeGenerator` test double is preferable to a "test mode" flag). The same seam is reused for AC15's reserved-word-collision test.

## Open questions
- The response JSON field set for a created/detail resource is still not fully specified. D33 fixes that the body includes `shortUrl` (built from `APP_BASE_URL`, never the `Host` header) and that `Location` is `/api/v1/urls/{code}`, but the complete field list (e.g. whether `clickCount`/`lastAccessedAt` appear on create, exact field names/casing) still needs design-gate confirmation and must match US-007's response shape exactly.

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
