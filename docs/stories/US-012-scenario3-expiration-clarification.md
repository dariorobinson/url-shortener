---
id: US-012
title: "Scenario 3: clarifying questions for URL expiration"
status: Done
plan_task: 10
depends_on: []
requirements: [FR-4, FR-9, D14]
requires_design_approval: false
---

# US-012: Scenario 3 — clarifying questions for URL expiration

## User story
As the engineering team, I want a documented list of clarifying questions (with proposed, non-binding defaults) for the ambiguous requirement "URLs should expire after some time", so that Scenario 2's brownfield impact analysis (US-013) has a concrete, engineer-approved requirement to analyze instead of a guess.

## Acceptance criteria
- **AC1:** Given the ambiguous requirement behind FR-4/FR-9, when this story's analysis is written into `docs/scenarios.md`'s Scenario 3 section, then it lists at minimum:
  - what "expired" means for `GET /{code}` (same 404-hides-state treatment as D2's deactivated/deleted, or a distinct response?);
  - how expiration is set (a fixed TTL applied to all links, an explicit `expires_at` provided at creation, or configurable per link) and whether it is mutable after creation;
  - whether expiration applies to auto-generated codes, custom aliases, or both;
  - whether an expired code can ever be reused for a new link, consistent with D1's "deleted codes are never reused";
  - who may set or change expiration (creator only, or ADMIN only, mirroring D3's delete restriction);
  - the default when no expiration is specified (never expires vs a mandatory default TTL);
  - how expiration interacts with the existing `status` enum (`ACTIVE`/`DEACTIVATED`/`DELETED`) — a new status value, or a computed condition layered on top of `ACTIVE`;
  - whether an expired link's statistics remain viewable by its owner/ADMIN after expiration.
- **AC2:** Given each question, when documented, then it is paired with a proposed default clearly marked as a non-binding proposal, not a decision.
- **AC3:** Given this story's scope, when it is implemented, then no source code, migration, entity, or API changes are made — the only artifact is the `docs/scenarios.md` update.

## Tests required
| Type | Behaviour proven | Owner |
|---|---|---|
| Review | Engineer review confirms the question list is complete (covers all items in AC1), each has a proposed default marked as non-binding, and no code was touched | architect (author); senior-engineer (reviewer) |

This story is docs-only: it has no Cucumber scenarios or `*IT` tests, since no code or API surface is introduced.

## Out of scope
- Any implementation of expiration.
- The brownfield impact analysis (module list, V3 schema sketch, regression risks) — see US-013, which depends on this story's answers.

## Risks
- If the engineer does not answer these questions before US-013 starts, US-013 must be set to `Blocked` rather than guessed at.

## Open questions
- This entire story's deliverable *is* a set of open questions for the engineer; see AC1. There are no additional open questions about how to run the story itself.

## Resolution
Delivered by the main session (no agents; engineer direction 2026-09-30). The questions and defaults are in `docs/scenarios.md` (Scenario 3). The engineer accepted all proposed defaults, recorded as D106–D121. No code, migration, entity or API was changed.

## Design note
*(not applicable: docs-only story)*

## Implementation notes
*(mid-engineer: files changed, decisions, items needing human review, test command and result)*

## QA notes
*(qa-tester: feature files and IT classes, AC-to-test mapping, test results, defects D-<story>-<n>)*

## Review log
*(senior-engineer findings per round, and how each was resolved)*

| Round | ID | Severity | Finding | Resolution |
|---|---|---|---|---|
