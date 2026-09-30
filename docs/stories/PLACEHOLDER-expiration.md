> This file is **not** a user story. It intentionally has no `US-` id and no story frontmatter/status, so it is never picked up by the orchestrator as backlog work.

# Deferred work: URL expiration implementation

> **Superseded (2026-09-30):** US-012 and US-013 are `Done`, and the implementation story is now `docs/stories/US-016-implement-expiration.md`.

Implementation stories for URL expiration (schema, entity, redirect behaviour, API changes, tests) are **not created yet**.

They will be created — as new, sequentially-numbered `US-` stories starting after the highest-numbered story in this backlog at the time — only after both of the following are reviewed and approved by the engineer:

1. `docs/stories/US-012-scenario3-expiration-clarification.md` — the clarifying questions and proposed defaults for "URLs should expire after some time".
2. `docs/stories/US-013-scenario2-expiration-impact-analysis.md` — the brownfield impact analysis based on the answers to (1).

Pointer for those future stories: expiry tests will need exact, controllable time in the ITs. Reuse the controllable test `Clock` added to the shared test configuration in US-010 (engineer-approved at US-009 G3, from QA observation (b)). Do not add a second one or create a second context.

Do not start coding expiration, and do not create `US-` stories for it, before both are marked `Done`.
