# Engineering Scenarios

This document records the three required engineering scenarios.

---

## Scenario 1 — Greenfield: initial URL-shortening capability

**Status:** In progress.

### Requirement understanding
See [requirements.md](requirements.md). The ambiguous requirements were resolved with the engineer before any code was written (decisions D1–D14).

### Decomposition

| Task | Scope | Status |
|---|---|---|
| 0 | Repository and documentation skeleton | Done (pending review) |
| 1 | Maven project, Spring Boot app, Flyway, Docker Compose, Testcontainers base | Done (US-001) |
| 2 | Domain model, V1 schema, repository | Planned |
| 3 | Short-code generator | Planned |
| 4 | URL and alias validation | Planned |
| 5 | Security foundation (`USER` / `ADMIN`, 401/403) | Planned |
| 6 | Create/read API and error handling | Planned |
| 7 | Redirect | Planned |
| 8 | Deactivate/reactivate and soft delete | Planned |
| 9 | Analytics | Planned |

### Architecture decisions
See [architecture.md](architecture.md).

### Implementation, review, testing, validation
*(Recorded per task as they complete.)*

- **US-001 (Task 1):**
  - **Design:** architect design note, approved at G2 (D39–D43).
  - **Implementation:** the mid-engineer built the Maven, Boot, Compose, Testcontainers and JaCoCo setup plus 4 unit tests. The qa-tester added 7 `*IT` tests and 3 Cucumber scenarios.
  - **Review:** the senior-engineer found 2 BLOCKING issues in round 1. The JaCoCo gate was reading coverage left over from earlier builds, and AC12 had no test. Both were fixed, and round 2 approved.
  - **Validation:** the orchestrator ran `./mvnw -q clean verify` (14 tests, 0 failures, coverage gate passed) and a manual Compose plus local-profile run.

---

## Scenario 3 — Ambiguous requirement: "URLs should expire after some time"

**Status:** Not started (Task 10). No code will be written until the open questions are answered by the engineer.

---

## Scenario 2 — Brownfield: adding URL expiration

**Status:** Not started (Tasks 11–12). An impact analysis of the existing codebase is performed and approved before any code changes.

> Scenario 3 is performed before Scenario 2 because the expiration requirement must be clarified before its impact can be analysed.
