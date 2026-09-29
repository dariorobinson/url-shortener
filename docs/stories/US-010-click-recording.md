---
id: US-010
title: Click recording on redirect
status: Open
plan_task: 9
depends_on: [US-001, US-002, US-008]
requirements: [FR-5, D8, D9, D12, D16, D18, D27, D32]
requires_design_approval: true
---

# US-010: Click recording on redirect

## User story
As the system, I want every successful GET redirect on an active link to be recorded atomically, so that accurate click analytics are available without slowing down or breaking the redirect itself.

## Acceptance criteria
- **AC1:** Given `{code}` belongs to an `ACTIVE` link, when `GET /{code}` is called, then `click_count` is incremented by exactly 1 via an atomic SQL update that does not touch `version` (D27), `last_accessed_at` is set to the current time (via the injected `Clock`), and a `click_event` row is inserted with `clicked_at` equal to that same time.
- **AC2:** Given the same request, when a `HEAD /{code}` is made instead, then no `click_count` increment and no `click_event` row occur (D9 — GET only; D18/D32 confirm `HEAD` is public and returns 302 like GET, but is never counted). Because Spring routes `HEAD` to the same handler mapping as `GET` by default, the redirect handler must explicitly check the HTTP method and skip invoking the `ClickRecorder` for `HEAD` — this is implementation behaviour, not incidental, and must be proven by asserting `click_count`/`click_event` are unchanged after a `HEAD` request, not merely that no exception occurred.
- **AC3:** Given the `click_event` insert (or counter update) fails for any reason (simulated via a test double that throws), when `GET /{code}` is called, then the redirect still returns `302` as normal (D12 — fail open), the failure is logged with the short code but never the full URL, and no exception escapes to the client.
- **AC4:** Given 50 concurrent `GET /{code}` requests against the same active link, when all complete, then `short_url.click_count` equals exactly 50 and `click_event` contains exactly 50 rows for that link — no lost updates.
- **AC5:** Given the `click_event` table (V2 migration), when its schema is inspected, then it stores only `id`, `short_url_id` (FK to `short_url`), and `clicked_at` — no column for IP address, user agent, or referrer (D8: click timestamp only).
- **AC6:** Given the click-recording `UPDATE` statement implemented in this story (D16, D27), when a row's `version` is read before and after a recorded click, then it is unchanged — proving management updates (US-009's `PATCH`, which does use `version`) can never be starved by redirect traffic. This is the second half of the split described in US-002's AC9: US-002 proves the entity mapping does not let a save clobber SQL-updated columns; this story proves the click `UPDATE` itself never bumps `version`.

## Tests required
| Type | Behaviour proven | Owner |
|---|---|---|
| Repository | V2 migration creates `click_event` with the FK, index, and default `clicked_at` as specified in `docs/architecture.md` | mid-engineer |
| Unit | `ClickRecorder` fail-open wrapper swallows and logs recorder failures without propagating (AC3) | mid-engineer |
| Repository | The click-recording `UPDATE` statement does not change `version` (AC6) | mid-engineer |
| Cucumber | `GET /{code}` on an active link increments the visible `clickCount` (verified via a subsequent `GET /api/v1/urls/{code}` as owner) (AC1) | qa-tester |
| Cucumber | `HEAD /{code}` on an active link leaves `clickCount` unchanged (AC2) | qa-tester |
| Integration (`*IT`) | Atomic counter update and `click_event` insert against Testcontainers PostgreSQL (AC1) | qa-tester |
| Integration (`*IT`) | `HEAD` does not record: `click_count` and `click_event` row count asserted unchanged directly against the database, not just "no error" (AC2) | qa-tester |
| Integration (`*IT`) | Click recording fails (forced via a test double) but the redirect still returns 302, and the failure is logged without the full URL (AC3) | qa-tester |
| Integration (`*IT`) | 50 parallel redirects on one code yield an exact count of 50 with no lost updates (AC4) | qa-tester |
| Repository | `click_event`'s schema contains only `id`, `short_url_id`, and `clicked_at` — no IP/user-agent/referrer column exists, proving D8 (AC5) | mid-engineer |

## Out of scope
- The stats query endpoint (`GET /api/v1/urls/{code}/stats`) — see US-011.

## Risks
- Synchronous analytics writes on the hot redirect path add latency and create row contention on `short_url.click_count` for popular links, as already flagged as a trade-off in `docs/architecture.md`. No mitigation is in scope here beyond what D12 already specifies (fail open).
- Logging discipline: it is easy to accidentally log the full `original_url` (which may contain tokens in a query string) when logging a click-recording failure. Review must check the log statement uses only the short code/ID.
- Transaction-boundary risk: if click recording runs inside the same database transaction as the redirect's lookup/read, a failed recording statement marks the transaction rollback-only under PostgreSQL (any subsequent statement in that transaction, including the one that would return the redirect, fails too), which would break fail-open (D12) instead of honoring it. Click recording therefore needs its own transaction boundary, separate from the redirect's read — a design-gate item for this story's design note; the design must specify how the `ClickRecorder` is invoked (e.g. `REQUIRES_NEW` propagation, or invoked after the redirect's transaction has already committed/closed) so a recording failure cannot roll back or block the redirect.

## Open questions
- None. D16/D27 fix the entity-mapping/atomic-update split with US-002 (AC6 above: the click `UPDATE` never touches `version`, and the entity never overwrites the analytics columns), and D18/D32 fix `HEAD`'s status and public access; this story only needs to implement and test the "not counted" half.

## Design inputs carried from US-002 (engineer-approved at US-002 G2)
- The click-recording `UPDATE` must **not** change `updated_at`. That column reflects management changes only.
- The click time must be taken from the injected `Clock` and **truncated to microseconds**, matching D45 and PostgreSQL's `timestamptz` resolution.
- Any read of the `ShortUrl` after the click `UPDATE` in the same persistence context must not see stale values. Use `@Modifying(clearAutomatically = true)` or run the click recording in its own transaction.

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
