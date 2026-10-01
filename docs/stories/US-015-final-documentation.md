---
id: US-015
title: Final documentation
status: In Progress
plan_task: 14
depends_on: [US-001, US-002, US-003, US-004, US-005, US-006, US-007, US-008, US-009, US-010, US-011, US-012, US-013, US-014, US-016]
requirements: [FR-1, FR-2, FR-3, FR-4, FR-5, FR-6, FR-7, FR-8, FR-9, FR-10, FR-11, FR-12, FR-13, D22]
requires_design_approval: false
---

# US-015: Final documentation

## User story
As a reviewer (interviewer) of this project, I want complete, accurate documentation of what was built, why, and what remains, so that I can evaluate the engineering process without reading every line of code.

## Acceptance criteria
- **AC1:** Given the completed implementation, when `README.md` is reviewed, then it includes setup instructions (JDK 25, Maven Wrapper, `docker compose up`), how to run the full test suite, an API overview, an authenticated request example (`curl` with HTTP Basic), and links into `docs/`.
- **AC2:** Given `docs/requirements.md`, `docs/architecture.md`, and `docs/scenarios.md`, when reviewed, then every "planned"/"in progress" status marker for work that has actually shipped is updated to reflect reality, and no shipped behaviour is left undocumented.
- **AC3:** Given `docs/engineering-summary.md` currently exists only as a stub, when this story completes, then it is filled in with: what was built, key trade-offs, known limitations (including the deferred expiration work and its placeholder), and a production roadmap that explicitly names the D22 known risk (Spring Boot 3.5 open-source support ended 2026-06-30 and 3.5.16 is the final release; upgrading to Boot 4.x is on the roadmap) — cross-referencing `docs/ai-usage-log.md` for how AI assistance was used and which recommendations were accepted, modified, or rejected.
- **AC4:** Given this story's scope, when it is implemented, then no source code changes are made — only documentation files.

## Tests required
| Type | Behaviour proven | Owner |
|---|---|---|
| Review | Engineer review confirms README and docs accuracy against the shipped codebase (AC1, AC2) | mid-engineer (author, proposed); senior-engineer (reviewer, proposed) |
| Review | Engineer review confirms `docs/engineering-summary.md` completeness against AC3 | mid-engineer (author, proposed); senior-engineer (reviewer, proposed) |

This story is docs-only: it has no Cucumber scenarios or `*IT` tests, since no code or API surface is introduced.

## Out of scope
- Any code or test changes.
- Expiration implementation stories, which are created and documented separately once US-012/US-013 are approved.

## Risks
- If run before every other story is `Done`, this story will document a moving target; it should be the last story completed in the initial backlog.
- This story's `depends_on` list currently includes only the stories that exist today (US-001 through US-014). The expiration implementation stories referenced by US-012/US-013 do not exist yet; once they are created, this story's `depends_on` must be updated to include them, or this story could complete (and be marked "final") before expiration is actually implemented and documented.

## Open questions
- Unlike US-012/US-013, the engineer's decisions (D15–D38) do not state who authors and reviews this docs-only story. The Owner column above proposes `mid-engineer` (author) / `senior-engineer` (reviewer) by analogy with the general workflow, but this is a planner proposal, not a decision — needs engineer confirmation.

## Production roadmap inputs (engineer-approved)
- **D81:** an HTML 404 page for browsers hitting the public redirect.
- **D71:** an idempotency key for create (also in US-014's carry-over).
- **Abuse:** screening of target URLs at create, abuse takedown, and rate limits (US-008 design, open-redirect considerations).
- **D22:** upgrade to Spring Boot 4.x.

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
