# Engineering Summary

Final summary of the URL shortener assignment (2026-09-28 to 2026-09-30). Decisions are referenced as `Dnn` ([requirements.md](requirements.md)); the full record of AI recommendations and engineer decisions is in [ai-usage-log.md](ai-usage-log.md).

## What was built

A URL shortener service: Java 25, Spring Boot 3.5.16, PostgreSQL 18.6, Flyway, Spring Security, Testcontainers and Cucumber.

| Capability | Where | Key decisions |
|---|---|---|
| Create short URLs: random Base62 codes or custom aliases; collision-safe | US-003, US-004, US-006 | D6, D29, D48, D58–D71 |
| Public redirect: 302 + `no-store`; identical 404 for unknown, deactivated, deleted and malformed codes | US-008 | D2, D72–D84 |
| Owner-only details; deactivate, reactivate; ADMIN-only soft delete; codes never reused | US-007, US-009 | D1, D4, D13, D26, D34–D36, D86–D90 |
| Click analytics: atomic counting, fail-open; daily stats in the caller's time zone | US-010, US-011 | D9, D12, D91–D105 |
| Optional expiration: 410 Gone; set, extend or clear by PATCH | US-012, US-013, US-016 | D106–D128 |
| HTTP Basic with USER/ADMIN; deny-by-default access rules | US-005 | D3, D30–D32, D50–D57 |
| Request IDs, container image, Compose stack, hardening | US-014 | D129–D131 |

**By the numbers:**
- 16 user stories and 14 commits (C1–C10, the last being this documentation), each approved before it was made.
- 131 recorded decisions.
- About 2,050 automated tests: 1,179 unit, slice and repository tests, plus 878 integration tests including 250 Cucumber scenarios. All database tests run against real PostgreSQL.
- 98.5% merged line coverage, with a 70% gate enforced by the build.

## Key trade-offs

| Decision | Chosen | Instead of | Why |
|---|---|---|---|
| Uniqueness of codes | Database `UNIQUE` constraint, plus a bounded retry with a fresh transaction per attempt | A "does this code exist?" check before inserting | Only the constraint is correct under concurrency. PostgreSQL aborts a transaction after an error, so a retry needs a new one. |
| Redirect status | 302 with `Cache-Control: no-store` (D7, D76) | 301 | Every click is counted, and deactivation or expiry takes effect at once. The cost is no browser caching. |
| Click recording | Synchronous, in its own transaction, fail-open (D12, D93) | A message broker | Simple and exact. A failure never breaks a redirect. The costs are one extra write per click, and contention on the counter row for very popular links. |
| Counter integrity | One atomic SQL `UPDATE` that never touches `version` (D16, D27) | Read, modify, write through the entity | No lost clicks, and a click never causes a false 409. |
| Deleting | Soft delete; the code is retired forever (D1) | Hard delete | Keeps an audit trail, and an old link can never be redirected somewhere new (phishing). |
| Deactivated vs unknown | Identical 404 (D2, D74) | A distinct 410 | The redirect doesn't reveal a link's state. |
| Expiry | Computed at request time from a nullable column (D112) | A stored `EXPIRED` status updated by a job | No scheduler, no stale window, and an exact boundary. |
| Daily stats | Java computes the day boundaries; PostgreSQL only counts (D95) | `AT TIME ZONE` in SQL | Testing showed PostgreSQL treats `CET` as a fixed offset and inverts `+05:00`, which would silently put clicks on the wrong day. |
| Concurrent edits | Optimistic locking; a conflict is 409 `CONCURRENT_MODIFICATION` (D35) | Pessimistic locks or ETags | Simpler. The state rules already block stale overwrites; ETags can come later. |
| Input strictness | Unknown fields, duplicate keys, string booleans and epoch timestamps are rejected (D59, D89, D123) | Jackson's lenient defaults | A typo is a clear 400, never a silently different request. |
| Length limits | `TEXT` columns plus `CHECK` (D47) | `VARCHAR(n)` | `VARCHAR` silently truncates trailing spaces; the database stays the final guarantee. |
| Authentication | HTTP Basic, users defined in configuration (D50) | OAuth2/JWT | The smallest real enforcement. Moving to JWT changes only the security configuration. |
| Test database | Testcontainers PostgreSQL only (D21) | H2 | H2 cannot run the schema and does not abort transactions after errors, so it would test different behaviour. |

## Known limitations

- **Spring Boot 3.5 is out of open-source support** (since 2026-06-30; 3.5.16 is the last release, D22). An upgrade to 4.x is the first roadmap item.
- **Java 25** was the engineer's choice over the assignment's Java 21, so the project does not build on a Java 21 toolchain.
- **Users are configured, not managed.** There is no sign-up, password change or lockout. HTTP Basic needs TLS in front of it, and the local credentials in `.env.example` are public by design (K10).
- **No rate limiting or abuse screening** of target URLs. Open redirects to malicious sites are mitigated only by the URL rules (D11, D49).
- **Very popular links:** clicks queue on one counter row, and the stats query scans the link's clicks in the window (at most 366 days, D98).
- **A lost 201** (the response failed after the commit) cannot be retried safely without an idempotency key. The 409 guidance in D71 is a workaround.
- **D84 is enforced by the application only** (D85). A row inserted by raw SQL with a very long non-ASCII URL could still produce a bare 500 on redirect.
- **A clock failure makes the redirect fail** rather than fail open, because expiry needs "now" (D128).
- **Tomcat's own 400** for request lines over 8 KiB is minimal HTML, not a problem document; it can't be reached by application code.

## Production roadmap

1. Upgrade to Spring Boot 4.x (D22).
2. Replace HTTP Basic with OAuth2/JWT from the identity provider; terminate TLS at the load balancer and set `SERVER_TOMCAT_REMOTEIP_INTERNALPROXIES`.
3. Publish click events to Kafka or SQS and aggregate them asynchronously, with idempotent consumers keyed by event ID. This removes counter-row contention, and the `ClickRecorder` interface is the seam for it.
4. Daily rollups and/or a statement timeout for stats; partition `click_event` by time.
5. An idempotency key for create (`Idempotency-Key` header, D71).
6. Rate limiting (gateway or Bucket4j), target-URL screening (e.g. Safe Browsing), and an abuse takedown process.
7. An HTML 404/410 page for browsers (D81).
8. For future constraints on large tables, `ADD CONSTRAINT … NOT VALID` then `VALIDATE CONSTRAINT`.
9. Distributed tracing (the request ID is the first step), dashboards and alerts on `shortener.clicks.lost`.
10. A CI pipeline that runs `./mvnw verify`, builds and scans the image, and deploys.

## How AI was used

The engineer stayed the decision-maker throughout. The AI proposed; the engineer approved, modified or rejected; and the AI recorded each outcome in the [AI usage log](ai-usage-log.md).

- **US-001–US-011** ran through a team of Claude Code agents (orchestrator, planner, architect, mid-level engineer, QA tester, read-only senior reviewer), stopping at every gate: backlog, design, story completion and commit.
- **US-012–US-016** were done by the main session directly, after the engineer switched off the agents.

**Where AI recommendations were modified or rejected by the engineer:**
- Java 25 instead of 21.
- 404 for deactivated links, instead of the AI's proposed 410.
- `USER`/`ADMIN` roles, where the AI had proposed leaving authentication out.
- The caller's time zone for stats, instead of UTC.
- Strict booleans, choosing the orchestrator's recommendation over the architect's.
- `TEXT` columns, choosing the main session's suggestion over the orchestrator's.
- Keeping D84 enforced in the application only, against the main session's suggested database CHECK.

**Where the process caught AI mistakes before they shipped:**
- A flawed CHECK constraint in the AI's own planning-stage schema (D44), caught at design review.
- A design risk (K5) that was simply wrong: it would have saved a link and then returned 406. A real-PostgreSQL test caught it.
- Tomcat silently dropping `Location` headers (D75), then the 8 KiB header limit (D84), both found because the engineer required the real Tomcat behaviour to be tested rather than assumed.
- The planning-stage `AT TIME ZONE` design (D95), overturned by an empirical test.
- A latent privilege escalation through `/API/...` paths (D57), found by a review rule approved one round earlier.
- An agent's inaccurate status report, caught by the main session's independent checks.

**What made the process trustworthy:**
- The orchestrator and the main session each re-ran the full build independently before every approval.
- Every change to a finished story's tests is listed by method name in that story.
- No agent was allowed to resolve an ambiguity or change an approved decision on its own.
