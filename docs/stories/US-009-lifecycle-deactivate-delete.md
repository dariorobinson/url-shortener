---
id: US-009
title: Deactivate, reactivate, and soft delete
status: Open
plan_task: 8
depends_on: [US-002, US-005, US-006, US-007]
requirements: [FR-6, D1, D3, D4, D13, D26, D31, D34, D35, D36]
requires_design_approval: true
---

# US-009: Deactivate, reactivate, and soft delete

## User story
As the owner of a short URL, I want to deactivate or reactivate it, and as an ADMIN, I want to delete any short URL, so that links can be managed over their lifetime without losing audit history.

## Acceptance criteria
- **AC1:** Given the caller owns an `ACTIVE` link, when `PATCH /api/v1/urls/{code}` is called with body `{"active": false}` (D34), then the response is `200 OK` with `status: "DEACTIVATED"`.
- **AC2:** Given the caller owns a `DEACTIVATED` link, when `PATCH /api/v1/urls/{code}` is called with body `{"active": true}` (D34), then the response is `200 OK` with `status: "ACTIVE"`.
- **AC3:** Given the caller is an authenticated `USER` who does not own the link, when `PATCH /api/v1/urls/{code}` is called, then the response is `404 Not Found` with `errorCode: "SHORT_URL_NOT_FOUND"` (D4 — same ownership-hides-existence rule as GET).
- **AC4:** Given the caller has role `ADMIN`, when `PATCH /api/v1/urls/{code}` is called for any link regardless of owner, then the request is processed normally (`200 OK`).
- **AC5:** Given the caller has role `ADMIN`, when `DELETE /api/v1/urls/{code}` is called on an existing, non-deleted link (`ACTIVE` or `DEACTIVATED` — D36 explicitly permits deleting a `DEACTIVATED` link), then the response is `204 No Content`, the row's `status` becomes `DELETED`, and `deleted_at`/`deleted_by` are set.
- **AC6:** Given the caller has role `USER` (whether or not they own the link), when `DELETE /api/v1/urls/{code}` is called, then the response is `403 Forbidden` with `errorCode: "ACCESS_DENIED"` — per D3, only `ADMIN` may delete, regardless of ownership.
- **AC7:** Given `{code}` does not exist, when `PATCH /api/v1/urls/{code}` is called by an authenticated caller, or `DELETE /api/v1/urls/{code}` is called by `ADMIN`, then the response is `404 Not Found` with `errorCode: "SHORT_URL_NOT_FOUND"`.
- **AC8:** Given `{code}` belongs to an already-`DELETED` link, when `PATCH` is called by any caller including `ADMIN`, or `DELETE` is called by `ADMIN`, then the response is `404 Not Found` (D13, D36 — a deleted link accepts no further lifecycle transitions). The 404 outcomes in AC7 and AC8 for `DELETE` apply only to an `ADMIN` caller: because the role check precedes the existence lookup, a `USER` calling `DELETE` on any `{code}` — whether it exists, is missing, is deactivated, or is already deleted — always receives `403 Forbidden` with `errorCode: "ACCESS_DENIED"` (per AC6), never `404`. The 403 therefore reveals nothing about whether the code exists.
- **AC9:** Given `{code}` belongs to an already-`DEACTIVATED` link, when the owner (or `ADMIN`) calls `PATCH /api/v1/urls/{code}` with body `{"active": false}`, then the response is `409 Conflict` with `errorCode: "SHORT_URL_ALREADY_DEACTIVATED"` (D26).
- **AC10:** Given `{code}` belongs to an already-`ACTIVE` link, when the owner (or `ADMIN`) calls `PATCH /api/v1/urls/{code}` with body `{"active": true}`, then the response is `409 Conflict` with `errorCode: "SHORT_URL_ALREADY_ACTIVE"` (D26).
- **AC11:** Given two concurrent `PATCH /api/v1/urls/{code}` requests both attempt to deactivate the same `ACTIVE` link (no client-supplied version/`If-Match` — D35 uses server-side `@Version` only), when both execute concurrently, then exactly one succeeds with `200 OK` (`status: "DEACTIVATED"`) and the other fails with `409 Conflict` (see Open questions for which `errorCode` the loser may receive).
- **AC12:** Given no credentials are supplied, when `PATCH /api/v1/urls/{code}` or `DELETE /api/v1/urls/{code}` is called, then the response is `401 Unauthorized` with `errorCode: "AUTHENTICATION_REQUIRED"` (D31, per US-005).

## Tests required
| Type | Behaviour proven | Owner |
|---|---|---|
| Unit | State-transition and ownership/role authorization logic (AC1–AC6, AC9, AC10) | mid-engineer |
| Web slice | Status codes and errorCodes wired through the controller for all AC1–AC10 and AC12 | mid-engineer |
| Cucumber | Owner deactivates their own `ACTIVE` link: 200, `status: "DEACTIVATED"` (AC1) | qa-tester |
| Cucumber | Owner reactivates their own `DEACTIVATED` link: 200, `status: "ACTIVE"` (AC2) | qa-tester |
| Cucumber | Non-owner `USER` PATCHes another's link: 404 `SHORT_URL_NOT_FOUND` (AC3) | qa-tester |
| Cucumber | `ADMIN` PATCHes any link regardless of owner: 200 (AC4) | qa-tester |
| Cucumber | `ADMIN` deletes an `ACTIVE` link and a `DEACTIVATED` link: both 204 (AC5) | qa-tester |
| Cucumber | `USER` calls `DELETE`: 403 `ACCESS_DENIED` (AC6) | qa-tester |
| Cucumber | `PATCH`/`DELETE` on a nonexistent `{code}`: 404 `SHORT_URL_NOT_FOUND` (AC7) | qa-tester |
| Cucumber | `PATCH`/`DELETE` on an already-`DELETED` link: 404 (AC8) | qa-tester |
| Cucumber | `PATCH {"active": false}` on an already-`DEACTIVATED` link: 409 `SHORT_URL_ALREADY_DEACTIVATED` (AC9) | qa-tester |
| Cucumber | `PATCH {"active": true}` on an already-`ACTIVE` link: 409 `SHORT_URL_ALREADY_ACTIVE` (AC10) | qa-tester |
| Cucumber | No credentials on `PATCH`/`DELETE`: 401 `AUTHENTICATION_REQUIRED` (AC12) | qa-tester |
| Integration (`*IT`) | Real ownership/role/deleted-visibility scenarios against Testcontainers PostgreSQL (AC3, AC4, AC6, AC8) | qa-tester |
| Integration (`*IT`) | Two simultaneous `PATCH` deactivation requests on the same row produce exactly one 200 and one 409 via optimistic locking (AC11) | qa-tester |
| Web slice | A `USER` calling `DELETE` on a missing or already-deleted `{code}` still receives `403`/`ACCESS_DENIED`, not `404`, proving the role check precedes the existence lookup (AC8) | mid-engineer |

## Out of scope
- Hard delete / data purge — never implemented per D1.
- Expiration as a lifecycle state — deferred to Scenario 2/3 (US-012, US-013).
- Client-supplied conflict detection (`If-Match`/`ETag`) — explicitly deferred by D35 until `expiresAt` becomes editable.

## Risks
- Correctness interaction with US-010: US-010 increments `click_count`/`last_accessed_at` via an atomic SQL `UPDATE` that does not touch `version` (D27), while this story's `PATCH` loads the `ShortUrl` entity and flushes it back. Because `click_count`/`last_accessed_at` are mapped `insertable=false, updatable=false` (D27, US-002), a `PATCH`'s save cannot clobber a concurrent click's values, and a concurrent click cannot cause a spurious `409` on an in-flight `PATCH`. This story's `PATCH` implementation must not reintroduce a full-column overwrite that bypasses that mapping.

## Open questions
- In the two-concurrent-deactivations test (AC11), the losing request may receive `409 CONCURRENT_MODIFICATION` (true optimistic-lock conflict) or `409 SHORT_URL_ALREADY_DEACTIVATED` (if the requests serialise and the second sees the committed state). D35 requires one `200` and one `409` but does not say which `errorCode`. Proposed: the test asserts status `409` and accepts either `errorCode` — engineer to confirm.
- Note on something intentionally *not* flagged as open: whether a `USER` calling `DELETE` on their *own* link should get `403` or `404` is already decided — `docs/architecture.md`'s REST table lists `403` as a `DELETE` error and D3 states only `ADMIN` may delete, with no ownership exception. AC6 reflects this; it is not ambiguous.

## Design inputs carried from US-006 (engineer-approved at the US-006 escalation)
- PATCH and DELETE live on `ShortUrlController` and inherit its class-level `produces = application/json` (D70). An unacceptable `Accept` returns 406 **before** any state change. DELETE returns 406 too, even though it has no body.
- PATCH returns a body, so it needs a real-HTTP IT that asserts 406 **and** no state change (the version is unchanged), with a positive control.

## Carry-over from US-008 (engineer-approved at US-008 G3)
- **N1 (SHOULD, mid-engineer; the engineer approved changing a Done-story test):** `UrlValidatorTest.shouldCountCodePointsNotUtf16UnitsOrBytesForShortMultibyteUrls` passes under every counting method, because D84 makes the difference unobservable. Rename it to what it proves, for example `shouldAcceptAShortUrlOfSupplementaryCharacters`, or delete it as a duplicate of `shouldAcceptValidSupplementaryCharacterInPath`. Add a comment in `UrlValidator.isValid` saying that D84 implies the D11 count, and that the D11 check stays as the cheap limit before encoding. List the change test by test in US-004's post-completion section.
- **N2 (SHOULD, qa-tester):** the raw-SQL positive control in `RedirectIT` (around lines 242–252) must also assert that the oversized URL is absent from the captured output. Its comment should say it pins the D85 gap.
- **N4 (NIT, mid-engineer):** wrap the 171-column Javadoc line in `RedirectController` (around line 30).
- **N5 (NIT, mid-engineer):** add `verify(service).resolve(CODE)` to the body-less unparseable-`Accept` and HEAD 404 slice tests in `RedirectControllerWebMvcTest`.
- **N6 (NIT, mid-engineer):** `LocationEncoderTest.shouldLeaveA2048CharacterAsciiUrlByteIdentical` builds 2024 characters. Make it exactly 2048 with `hasSize(2048)`, and use `String.format(Locale.ROOT, …)`.
- **N7 (NIT, mid-engineer):** add `@throws NullPointerException if target is null` to `LocationEncoder.encode`.

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
