# User Stories

One file per story: `US-<nnn>-<short-slug>.md`. Each file holds the story's complete trail — requirements, design, implementation, and review — so the history of every change is auditable.

## Status workflow

`Open` → `In Progress` → `In Review` → `Done`

`Blocked` may be set at any point, with the reason recorded under Open questions.

| Transition | Who | Gate |
|---|---|---|
| (new) → `Open` | planner | Engineer approves the backlog (G1) |
| `Open` → `In Progress` | orchestrator | Design approved (G2), if required |
| `In Progress` → `In Review` | orchestrator | Implementation and tests complete |
| `In Review` → `Done` | orchestrator | Review passed and engineer approves (G3) |

## Template

```markdown
---
id: US-001
title: <short title>
status: Open
plan_task: <task number from the implementation plan>
depends_on: []
requirements: [FR-1, D6]
requires_design_approval: false
---

# US-001: <title>

## User story
As a <role>, I want <capability>, so that <benefit>.

## Acceptance criteria
- **AC1:** Given …, when …, then …
- **AC2:** …

## Tests required
| Type | Behaviour proven | Owner |
|---|---|---|
| Unit | … | mid-engineer |
| Cucumber | … | qa-tester |
| Integration (`*IT`) | … | qa-tester |

## Out of scope
- …

## Risks
- …

## Open questions
- None

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
```
