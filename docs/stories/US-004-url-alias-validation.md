---
id: US-004
title: URL and alias validation
status: Open
plan_task: 4
depends_on: [US-001]
requirements: [FR-3, FR-7, D6, D11, D28, D29]
requires_design_approval: false
---

# US-004: URL and alias validation

## User story
As an engineer, I want a `UrlValidator` and an `AliasPolicy` that enforce D11 and D6 respectively, so that the create-URL flow (US-006) can reject invalid input before touching the database.

## Acceptance criteria
- **AC1:** Given an absolute URL with scheme `http` or `https` and a host, and length ≤ 2048 characters, when validated, then `UrlValidator` reports it valid.
- **AC2:** Given a URL with a scheme other than `http`/`https` (e.g. `ftp://`, `javascript:`, or a scheme-relative/relative URL with no scheme), when validated, then `UrlValidator` reports it invalid.
- **AC3:** Given a URL longer than 2048 characters, when validated, then `UrlValidator` reports it invalid.
- **AC4:** Given a URL with embedded credentials (e.g. `https://user:pass@host/path`), when validated, then `UrlValidator` reports it invalid.
- **AC5:** Given a URL whose host equals the host of the configured `APP_BASE_URL`, compared case-insensitively and ignoring a trailing dot (D28), when validated, then `UrlValidator` reports it invalid (D11 "no links to this service's own host"). Table-driven cases include: `https://short.example/x` rejected when `APP_BASE_URL=https://short.example`; `HTTPS://SHORT.EXAMPLE./x` rejected (case-insensitive, trailing dot ignored); `https://short.example.evil.com/x` accepted (host is not an exact match, just a suffix); `https://other.example/x` accepted.
- **AC6:** Given an alias of length 3–32 using only `[A-Za-z0-9]`, when validated, then `AliasPolicy` reports it valid.
- **AC7:** Given an alias shorter than 3, longer than 32, or containing any character outside `[A-Za-z0-9]`, when validated, then `AliasPolicy` reports it invalid.
- **AC8:** Given an alias that matches a reserved word from the configurable list (default: `api, actuator, v3, error, health, admin, login, logout, static, assets, docs` — D29), compared case-insensitively, when validated, then `AliasPolicy` reports it invalid. Table-driven cases include: `api` rejected, `API` rejected (case-insensitive match), `admin` rejected, `myapi` accepted (not an exact match), a custom-configured word rejected.

## Tests required
| Type | Behaviour proven | Owner |
|---|---|---|
| Unit | Table-driven cases for AC1–AC5 (`UrlValidator`), including the D28 own-host case-insensitive/trailing-dot table above | mid-engineer |
| Unit | Table-driven cases for AC6–AC8 (`AliasPolicy`), including the D29 case-insensitive reserved-word table above | mid-engineer |

Note: HTTP status codes and `errorCode` values for validation failures are wired and tested in US-006 (create), not here — this story tests the validator/policy components in isolation.

## Out of scope
- Wiring validation failures to HTTP 400 responses — see US-006.
- Treating a reserved-word match on a *generated* code as a collision and retrying — the generator/`AliasPolicy` stay pure lookup/validation components; that retry behaviour is service-layer logic in US-006 (D29).

## Risks
- None beyond what D28/D29 already resolve. If `APP_BASE_URL` is misconfigured at runtime (wrong host), self-referencing links could still pass validation — an operational/configuration risk, not a design ambiguity.

## Open questions
- None. D28 defines "own host" (the `APP_BASE_URL` host, case-insensitive, trailing dot ignored) and D29 defines the reserved-word list, its configurability, and case-insensitive matching, including that a match against a *generated* code is treated as a collision and retried (implemented in US-006).

## Design inputs carried from US-002 (engineer-approved at US-002 G3)
- `AliasPolicy` must reject any alias that doesn't match `^[A-Za-z0-9]{3,32}$` **as submitted**. It must never trim or normalise the input. Include a test for a 32-character alias followed by a trailing space, which must be rejected. The same no-trim rule applies to `UrlValidator` for the 2048-character limit (D11, D47).

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
