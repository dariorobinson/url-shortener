---
id: US-013
title: "Scenario 2: brownfield impact analysis for URL expiration"
status: Open
plan_task: 11
depends_on: [US-012, US-011]
requirements: [FR-4, FR-9, D14]
requires_design_approval: false
---

# US-013: Scenario 2 — brownfield impact analysis for URL expiration

## User story
As the engineering team, I want a full impact analysis of adding expiration to the existing, already-shipped URL shortener, so that implementation stories for expiration can be scoped, sequenced, and reviewed without breaking existing behaviour.

## Acceptance criteria
- **AC1:** Given the engineer has answered US-012's clarifying questions, when this story's analysis is written into `docs/scenarios.md`'s Scenario 2 section, then it lists every module/class expected to change (e.g. `ShortUrl` entity and `ShortUrlStatus`, the V1/V2 migrations' relationship to a new V3, `ShortUrlRepository` queries, the redirect flow's 404 logic (US-008/US-002), the create/PATCH DTOs and validation (US-006/US-009), the stats endpoint (US-011), and any security/ownership rule affected).
- **AC2:** Given the answered questions, when the analysis is written, then it includes a proposed V3 migration sketch (columns, defaults, constraints) that is forward-only and consistent with the existing CHECK-constraint conventions in `docs/architecture.md`.
- **AC3:** Given the existing shipped behaviour, when the analysis is written, then it lists concrete regression risks (e.g. existing 404-for-deleted/deactivated tests that must keep passing unchanged, redirect-path performance impact of an added expiration check, existing stats queries that must not double-count or drop expired-link data unexpectedly).
- **AC4:** Given the existing test suite, when the analysis is written, then it lists which existing tests need updating and what new test types are required (e.g. `Clock`-based time-travel tests for expiry boundaries).
- **AC5:** Given the existing shipped API, when the analysis is written, then it proposes a backward-compatible rollout plan (e.g. a nullable `expires_at` column defaulting to `NULL` = never expires, so existing links and existing API responses are unaffected until a caller opts in).
- **AC6:** Given this story's scope, when it is implemented, then no source code or migrations are introduced — the only artifact is the `docs/scenarios.md` update. Implementation stories are created separately, after this analysis and US-012 are both approved by the engineer (see the placeholder note in `docs/stories/`).

## Tests required
| Type | Behaviour proven | Owner |
|---|---|---|
| Review | Engineer review confirms the impact analysis covers AC1–AC5 and that no code or migrations were touched | architect (author); senior-engineer (reviewer) |

This story is docs-only: it has no Cucumber scenarios or `*IT` tests, since no code or API surface is introduced.

## Out of scope
- Any implementation of expiration — deferred until this analysis and US-012 are approved; see `docs/stories/PLACEHOLDER-expiration.md`.

## Risks
- This analysis is most accurate once the full Scenario 1 backlog (US-001–US-011) is implemented, since it needs to reason about the real, shipped redirect/lifecycle/stats code rather than a plan. Starting it earlier risks a stale analysis that has to be redone. (Corrected: the approved plan order is Task 9 analytics → Task 10 Scenario 3 → Task 11 Scenario 2 → Task 12 expiration implementation → Task 13 hardening → Task 14 docs, so US-014 hardening comes *after* expiration, not before; this analysis should not wait on US-014.)

## Open questions
- Blocked until the engineer answers US-012's clarifying questions; if unanswered, this story's status should be set to `Blocked` rather than guessed at.

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
