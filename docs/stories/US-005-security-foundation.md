---
id: US-005
title: Security foundation (USER/ADMIN, HTTP Basic)
status: Open
plan_task: 5
depends_on: [US-001]
requirements: [FR-13, D3, D30, D31, D32]
requires_design_approval: true
---

# US-005: Security foundation (USER/ADMIN, HTTP Basic)

## User story
As an engineer, I want stateless HTTP Basic authentication with `USER`/`ADMIN` roles (`ADMIN` inheriting `USER`), so that later API stories can declare access rules and get consistent 401/403 `ProblemDetail` responses.

## Acceptance criteria
- **AC1:** Given a request to a secured path with no credentials, when it is processed, then the response is `401` with a `ProblemDetail` body containing `errorCode: "AUTHENTICATION_REQUIRED"` (D31) and a `WWW-Authenticate: Basic` header, produced by a custom `AuthenticationEntryPoint` (D30).
- **AC2:** Given a request with valid HTTP Basic credentials for a configured user, when it is processed, then Spring Security authenticates the principal and populates its granted role(s) (`USER` or `ADMIN`).
- **AC3:** Given the role hierarchy `ADMIN > USER`, when an endpoint is restricted to `hasRole('USER')`, then a principal with role `ADMIN` also passes the check.
- **AC4:** Given a request that fails an authorization check (authenticated but insufficient role), when it is processed, then the response is `403` with a `ProblemDetail` body containing `errorCode: "ACCESS_DENIED"` (D31), produced by a custom `AccessDeniedHandler` (D30).
- **AC5:** Given the public path patterns (`GET /{code}` and `HEAD /{code}` as a single path segment — D32, `/actuator/health`, OpenAPI/Swagger paths), when accessed with no credentials, then the security filter chain permits the request through to its handler (no 401).
- **AC6:** Given users are configured via BCrypt-hashed passwords sourced from environment variables (never plaintext), when the application starts, then the `UserDetailsService`/`PasswordEncoder` combination authenticates using `BCryptPasswordEncoder.matches`, not a plaintext comparison.
- **AC7:** Given the API is stateless and uses no cookies, when a request is processed, then no session is created (`SessionCreationPolicy.STATELESS`) and CSRF protection is disabled.
- **AC8:** Given both the 401 (AC1) and 403 (AC4) responses, when their bodies are inspected, then both use the exact same `ProblemDetail` shape as every other error response in the API: `type` is `about:blank`, `detail` is a generic, non-leaking message, and `errorCode` is the sole distinguishing extension (D30) — proving the security error responses are not a special case.
- **AC9:** Given the full `ErrorCode` catalogue is defined in this story (D31), when the enum is inspected, then it contains exactly: `VALIDATION_FAILED`, `MALFORMED_REQUEST`, `INVALID_URL`, `INVALID_ALIAS`, `ALIAS_ALREADY_EXISTS`, `SHORT_URL_NOT_FOUND`, `SHORT_URL_ALREADY_DEACTIVATED`, `SHORT_URL_ALREADY_ACTIVE`, `CONCURRENT_MODIFICATION`, `SHORT_CODE_UNAVAILABLE`, `AUTHENTICATION_REQUIRED`, `ACCESS_DENIED`, `INTERNAL_ERROR` — every later story reuses these values verbatim rather than inventing new ones.

## Tests required
| Type | Behaviour proven | Owner |
|---|---|---|
| Web slice | 401 with `AUTHENTICATION_REQUIRED` errorCode and `WWW-Authenticate: Basic` for missing credentials on a secured test endpoint (AC1) | mid-engineer |
| Web slice | 403 with `ACCESS_DENIED` errorCode when role is insufficient (AC4) | mid-engineer |
| Unit | Role hierarchy: `ADMIN` satisfies a `USER`-only rule (AC3) | mid-engineer |
| Web slice | Public paths (health, OpenAPI, single-segment redirect pattern for both `GET` and `HEAD`) are reachable unauthenticated (AC5) | mid-engineer |
| Unit | Password matching goes through `BCryptPasswordEncoder`, not plaintext (AC6) | mid-engineer |
| Web slice | No `Set-Cookie`/session is created on a successful authenticated request (AC7) | mid-engineer |
| Unit | Both 401 and 403 bodies match the shared `ProblemDetail` shape (`type: about:blank`, generic `detail`, only `errorCode` differs) (AC8) | mid-engineer |
| Unit | `ErrorCode` enum contains exactly the D31 catalogue, no more, no fewer (AC9) | mid-engineer |
| Cucumber | `GET /actuator/health` and `GET /v3/api-docs` are reachable with no credentials (AC5), proven end-to-end against the running application | qa-tester |

Since no business endpoint requiring authentication/authorization exists yet at this point in the plan, AC1–AC4, AC6, AC7, and AC8 are proved end-to-end by Cucumber starting in US-006 (the first story with a real secured endpoint), not here — here they are proved by web-slice tests against a minimal test-only secured controller, per the note below. AC5 and AC9 are the only ACs testable end-to-end in this story: AC5 because `/actuator/health` and `/v3/api-docs` are already real, public endpoints; AC9 is a catalogue check, not an HTTP behaviour, so it is unit-tested only.

Web-slice tests use a minimal test-only secured controller or direct `SecurityFilterChain`/`MockMvc` configuration to exercise the rules; US-006 onward re-verifies the same rules against real endpoints with Cucumber.

## Out of scope
- The actual list of configured users/passwords for a real deployment (env-var driven; `.env.example` already exists at the repo root and should be kept in sync, but populating real secrets is a deployment concern, not this story).
- Business endpoints — see US-006 onward.

## Risks
- HTTP Basic sends credentials on every request; this is acceptable only behind TLS. This project runs over plain HTTP in local Docker Compose — already flagged as a trade-off in `docs/architecture.md`, not a new risk, but worth restating here since this story is where it becomes concrete.

## Open questions
- None. D30 fixes the security error-handler shapes and `errorCode`s, D31 fixes the full `ErrorCode` catalogue (defined once, here), and D32 fixes `HEAD /{code}` as public.

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
