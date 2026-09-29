> This file is **not** a user story. It intentionally has no `US-` id and no story frontmatter/status, so it is never picked up by the orchestrator as backlog work.

# Deferred work: URL expiration implementation

Implementation stories for URL expiration (schema, entity, redirect behaviour, API changes, tests) are **not created yet**.

They will be created — as new, sequentially-numbered `US-` stories starting after the highest-numbered story in this backlog at the time — only after both of the following are reviewed and approved by the engineer:

1. `docs/stories/US-012-scenario3-expiration-clarification.md` — the clarifying questions and proposed defaults for "URLs should expire after some time".
2. `docs/stories/US-013-scenario2-expiration-impact-analysis.md` — the brownfield impact analysis based on the answers to (1).

Do not start coding expiration, and do not create `US-` stories for it, before both are marked `Done`.
