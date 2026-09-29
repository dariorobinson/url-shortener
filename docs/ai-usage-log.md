# AI Usage Log

This log records significant AI-assisted work on the project and the engineer's decision on each AI recommendation. The AI assistant was Claude Code; the engineer (Dario Robinson) owns every architecture, implementation, and validation decision.

Decision values: **Accepted**, **Modified**, **Rejected**.

---

## Entry 1 — Initial planning and requirements analysis

- **Date:** 2026-09-28
- **Task:** Requirements analysis, architecture, and implementation plan (no code).
- **Prompt / intent:** Inspect the (empty) project and produce functional and non-functional requirements, ambiguities, assumptions, architecture, package structure, schema, API, short-code strategy, analytics design, error handling, security, risks, testing, documentation, and a step-by-step plan.
- **AI recommendation:** Layered Spring Boot monolith; Flyway; SecureRandom Base62 codes with a UNIQUE constraint and bounded retry in a separate transaction per attempt; synchronous analytics behind a `ClickRecorder` interface; RFC 7807 errors; expiration deliberately left out of the V1 schema so the brownfield scenario is a real change. The AI flagged ten ambiguous requirements and proposed assumptions for each.
- **Engineer decision:** Modified (see entries 2–4 for changes to individual assumptions).
- **Rationale:** The overall structure was accepted; several product and security assumptions were changed.
- **Validation:** Engineer review of the plan.

## Entry 2 — Changes to the AI's proposed assumptions

- **Date:** 2026-09-29
- **Task:** Resolve ambiguous requirements.

| Topic | AI recommendation | Engineer decision | Rationale / outcome |
|---|---|---|---|
| Delete semantics | Soft delete, codes never reused | **Accepted** | Rows retained for audit. |
| Deactivated link response | 410 Gone | **Modified → 404** | Redirect should not reveal link state; unknown, deleted, and deactivated all return 404. |
| Authentication | Out of scope for the prototype; document as the top gap | **Rejected** | Engineer required `USER` and `ADMIN` roles; only `ADMIN` can delete. AI then proposed HTTP Basic with config-defined BCrypt users (accepted). |
| Ownership | Users manage only their own links; 404 for others' links | **Accepted** | Prevents any user taking down another user's link. |
| Duplicate long URLs | New code per request | **Accepted** | |
| Alias character set | `[A-Za-z0-9_-]`, case-sensitive | **Modified → `[A-Za-z0-9]`** | Engineer removed `_` and `-`; codes and aliases share a Base62 alphabet. The AI asked for clarification because "A-Z 0-9, case sensitive" could mean Base36 or Base62; the engineer confirmed Base62. |
| Redirect status | 302 + `Cache-Control: no-store` | **Accepted** | Keeps analytics and deactivation effective. |
| Analytics fields | Click timestamp only | **Accepted** | Avoids storing personal data. |
| Click definition | GET only | **Accepted** | |
| Daily stats time zone | UTC | **Modified → caller's time zone** | AI then recommended IANA zone IDs only, because PostgreSQL interprets POSIX-style offsets with an inverted sign (accepted). |
| URL validation rules | http/https only, ≤2048 chars, no credentials, no self-links | **Accepted** | |
| Analytics failure policy | Fail open | **Accepted** | Availability of redirects outweighs losing one click. |
| Deleted links for admins | 404 for everyone | **Accepted** | Audit history lives in the database. |
| Expiration in V1 schema | Defer to brownfield | **Accepted** | |

- **Validation:** Decisions recorded in [requirements.md](requirements.md).

## Entry 3 — Java version

- **Date:** 2026-09-29
- **AI recommendation:** The assignment specifies Java 21; the local JDK was 25. The AI proposed compiling with `--release 21` on JDK 25.
- **Engineer decision:** **Rejected — use Java 25.**
- **Rationale:** *(engineer to add)*. The AI flagged the trade-off that the project will not build on a Java 21 toolchain; the engineer accepted it.
- **Validation:** `mvn -v` reports Java 25.0.2.

## Entry 4 — Development environment setup

- **Date:** 2026-09-29
- **Task:** Fix environment issues before implementation.
- **AI findings:**
  - Maven ran on JDK 11 because `~/.zshenv` set `JAVA_HOME` via `/usr/libexec/java_home`, which does not know about the Homebrew JDK 25. `~/.zshrc` overrode it with a non-JDK-home path in interactive shells only.
  - Docker Desktop 4.16.2 (engine 20.10, API 1.41) was too old for current Testcontainers.
  - Git had no user name or email configured.
  - The GitHub clone was nested inside an empty, previously initialized repository.
- **Actions:** The AI pointed `JAVA_HOME` in `~/.zshenv` at the JDK 25 home and removed the duplicate from `~/.zshrc`. With the engineer's approval, it removed the empty outer repository and moved the clone to the project root. The engineer upgraded Docker Desktop (4.93.0, engine 29.8.1) and configured the git identity.
- **Engineer decision:** Accepted.
- **Validation:** `mvn -v` shows Java 25.0.2 in interactive and non-interactive shells; `docker version` shows engine 29.8.1; `git remote -v` and `git ls-remote origin` succeed.

## Entry 5 — Task 0: repository and documentation skeleton

- **Date:** 2026-09-29
- **Task:** Create `.gitignore`, `.env.example`, the README skeleton, and the `docs/` files.
- **AI recommendation:** Record all decisions to date in `requirements.md` and `architecture.md`; mark unimplemented sections as planned/pending.
- **Engineer decision:** *(pending review)*
- **Validation:** Engineer review of the documents.

## Entry 6 — Multi-agent SDLC workflow

- **Date:** 2026-09-29
- **Task:** Define an agent team to run the SDLC.
- **Prompt / intent:** The engineer requested an Orchestrator, a planning agent that produces user stories, an architect, a mid-level engineer who implements stories, and a senior engineer who reviews code and proactively reduces boilerplate (SLF4J, Lombok).
- **AI verification before design:** Checked the Claude Code subagent documentation, then tested empirically. Findings: subagents cannot interact with the user (`AskUserQuestion` is unavailable); a subagent *can* launch other subagents (confirmed by test, despite contradictory documentation); a subagent can be resumed with its context intact via `SendMessage`.

| Topic | AI recommendation | Engineer decision | Rationale / outcome |
|---|---|---|---|
| Orchestrator placement | Main session acts as orchestrator, so it can stop for approvals | **Modified → subagent** | The engineer chose a subagent. To keep every approval, the orchestrator is designed to stop at gates G1–G4 and return a checkpoint; the main session relays it and resumes the orchestrator with the engineer's decision. |
| Review separation | Senior engineer read-only; mid-level engineer fixes findings | **Accepted** | Separation of duties between author and reviewer; review findings are auditable. |
| Story storage | Markdown files in `docs/stories/` | **Accepted** | Versioned with the code. The engineer also approved installing the `gh` CLI. |
| Lombok usage | `@Slf4j`, `@RequiredArgsConstructor`, `@Getter`; records for DTOs; no `@Data`, blanket `@Setter`, `@AllArgsConstructor`, or `@EqualsAndHashCode` on JPA entities | **Accepted** | Modifies the engineer's original request for `@Getter`/`@Setter` everywhere: blanket setters would let callers bypass lifecycle rules, and `@Data`/`@EqualsAndHashCode` on entities cause known Hibernate issues. |
| Models | Opus for architect and senior engineer; Sonnet for planner and mid-level engineer; Opus for orchestrator | **Accepted** | Stronger model for judgement-heavy roles. |

- **Safeguards built in:** at most two review/fix rounds before escalation; the orchestrator runs the build itself rather than trusting agent reports; agents must escalate ambiguities rather than resolve them; only the orchestrator commits, and only after approval; no pushes without explicit approval.
- **Known limitation:** the senior engineer's read-only status is enforced by removing its file-editing tools, but it keeps Bash (for running tests), so "no file changes through Bash" is enforced by its instructions only.
- **Validation:** *(pending — first orchestrator run)*.

## Entry 7 — Backlog creation (G1)

- **Date:** 2026-09-29
- **Task:** Turn the engineer-approved implementation plan (Tasks 1–14) into user stories in `docs/stories/`.
- **Prompt / intent:** Orchestrator delegated story writing to the `planner` agent, reviewed the output for gaps, sequencing, and traceability, and sent back one revision pass.
- **AI recommendation (planner):** 15 stories (US-001–US-015). Task 6 split into create (US-006) and read (US-007); Task 9 split into click recording (US-010) and the stats API (US-011). Task 12 (expiration implementation) is a non-story placeholder (`docs/stories/PLACEHOLDER-expiration.md`). Open questions were raised rather than decided (versions, response shapes, errorCode catalogue, PATCH body, conflict signalling, HEAD behaviour, reserved words, own-host definition, stats range/shape, HSTS, JaCoCo threshold).
- **Orchestrator review findings (fixed by planner, no decisions taken):** D5 had no test; no ACs for 401 on PATCH/DELETE/stats, for request-body validation, or for the OpenAPI docs; ambiguous USER-DELETE-on-missing-code (clarified: 403, because the role check comes before the lookup); a correctness risk that Hibernate's full-column UPDATE on PATCH could overwrite atomically incremented `click_count` (now a US-002 design-gate item); a risk that click recording in the redirect transaction breaks fail-open; the errorCode catalogue must be ratified at US-005, not US-006; `swagger-ui` could never be a reserved alias; US-008 had a spurious dependency on US-007 and was missing one on US-005; US-013's risk text contradicted the plan's order.
- **Engineer decision:** **Accepted** all five G1 recommendations: US-013 depends on US-011; US-014/US-015 stay in plan order; commit groups C1–C10; architect writes the US-012/US-013 analysis docs with senior-engineer review; start US-001 next. Accepted together with the QA workflow change in Entry 8, which requires the planner to add QA test ownership to the stories.
- **Rationale:** *(engineer to add)*
- **Validation:** Orchestrator read all 15 story files, checked that the dependency graph is acyclic, and spot-checked the revised ACs. No code exists; no build was run.

## Entry 8 — QA-tester agent and integration-test database

- **Date:** 2026-09-29
- **Task:** Add a QA agent responsible for JUnit and Cucumber-based integration tests of the APIs and workflows.
- **Engineer request:** A `qa-tester` agent using **H2** instead of PostgreSQL for this testing, because it is simpler.
- **AI recommendation:** Use Testcontainers PostgreSQL instead. H2 cannot run the V1 migration (the code-format CHECK uses PostgreSQL's `~` regex operator), does not abort a transaction after a failed statement (so the collision-retry design could not be tested), and reports constraint violations differently (so alias-409 and retry scenarios would test different behaviour). The assignment also asks to prefer Testcontainers over H2. The US-001 Testcontainers base makes PostgreSQL no harder for the QA agent to use.
- **Engineer decision:** **Modified — H2 rejected; Testcontainers PostgreSQL adopted** (D21).
- **Test ownership (AI recommendation, accepted with a modification):** the AI proposed that the mid-engineer keep writing unit/component tests while QA writes Cucumber scenarios black-box from acceptance criteria. The engineer set the split as: **qa-tester owns integration testing** (Cucumber + full-stack `*IT` tests); **mid-engineer continues JUnit** unit, web-slice, and repository tests.
- **Changes:** new `.claude/agents/qa-tester.md`; orchestrator lifecycle now runs QA after implementation and before review; mid-engineer, senior-engineer, planner, story template, `CLAUDE.md`, and `docs/architecture.md` updated.
- **Engineer answers to G1 open questions** (recorded as D15–D20 in `requirements.md`): an already-deactivated link returns an error status saying so; clicks never bump `version` or get overwritten by management updates; the alias field is `alias`; `HEAD /{code}` returns 302 and is not counted; days with zero clicks are included; JaCoCo coverage ≥ 70% is enforced.
- **Validation:** *(pending — first QA run in US-001)*.

## Entry 9 — G1 answers folded into the backlog

- **Date:** 2026-09-29
- **Task:** Record the engineer's answers to the G1 open questions and update the stories for the new QA workflow.
- **Engineer decision (relayed):** "approve all". This covers the five G1 recommendations (Entry 7) and the answers to the open questions, recorded as **D22–D38** in `requirements.md`: versions; the postgres image; profiles; the Cucumber build; 409s for redundant transitions; read-only analytics columns; own host; reserved words; security error handlers; the ErrorCode catalogue; HEAD access; Location and `shortUrl`; the PATCH contract; `@Version` conflicts; lifecycle on deleted links; HSTS; the coverage gate. D15, D16, D18, and D20 are annotated as refined by the new entries. **Accepted.**
- **Actions:** The planner updated all 15 stories. Changes:
  - An Owner column in Tests required, and a QA notes section.
  - Cucumber and `*IT` rows owned by qa-tester for every API AC.
  - Provisional errorCodes replaced with the D31 catalogue.
  - New ACs for D26, D29, D33, D34, D35, D36, D37, and D38.
  - US-011 added to US-013's `depends_on`.
  - Architect as author and senior-engineer as reviewer on US-012 and US-013.

  The orchestrator added the missing test rows for US-001 AC1, AC5, and AC6. No stories were added, removed, or re-scoped. All G1 open questions answered by D22–D38 have been removed from the stories.
- **Still open (not covered by the engineer's answers):**
  - Testcontainers image (US-001).
  - Create/detail response field set (US-006/007).
  - Which errorCode the losing concurrent deactivation gets (US-009).
  - `from`/`to` semantics and the stats response shape (US-011).
  - `VALIDATION_FAILED` vs a new `INVALID_TIMEZONE` (US-011).
  - Who writes US-015.
  - Whether JaCoCo moves from US-014 to US-001.
- **Rationale:** *(engineer to add)*
- **Validation:** Orchestrator checked that no provisional errorCodes or H2 references remain, that every story has the Owner column and QA notes, and that the dependency graph is still acyclic.

## Entry 10 — US-001 design (G2)

- **Date:** 2026-09-29
- **Task:** Architect design note for US-001 (project skeleton) and a new "Build and test infrastructure" section in `architecture.md`.
- **AI recommendation (architect):**
  - Leave Spring Security out until US-005, because it would make `/v3/api-docs` return 401.
  - Share the Testcontainers container as a Spring bean with `@ServiceConnection`, so the `*IT` classes and Cucumber use one context.
  - Use `TestRestTemplate` rather than REST Assured.
  - Add a `Clock` bean now.
  - Bind `APP_BASE_URL` in US-004.
  - Use Spring Boot's standard `SPRING_DATASOURCE_*` variables in production.
  - Configure Lombok explicitly as an annotation processor, which JDK 23+ requires.
- **Engineer decisions requested at G2:**
  - **E1 (blocking):** Cucumber 7.34.9 needs JUnit Platform 1.13+, but Boot 3.5.16 manages JUnit 5.12.2. The architect recommends overriding `junit-jupiter.version` to 5.14.4; the alternative is to downgrade Cucumber to 7.23.0.
  - **E2:** move the JaCoCo 70% gate into US-001.
  - **E3:** use `postgres:18.6-alpine` for Testcontainers.
  - **E4:** add `lombok.addLombokGeneratedAnnotation` so generated code is left out of coverage.
  - **E5:** load Mockito as an explicit Java agent.
- **Engineer decision:** "approve all" (relayed). **Accepted:** E1–E5 and the architect's decisions D-R1 to D-R7, with the design note as written. Recorded as D39 (JUnit 5.14.4 override), D40 (JaCoCo gate moves into US-001; the planner may amend US-001 and remove AC5/AC7 from US-014), D41 (Testcontainers uses `postgres:18.6-alpine`), D42 (`lombok.config` with `addLombokGeneratedAnnotation`, an **engineer-approved refinement of D38**), and D43 (explicit Mockito `-javaagent`). The engineer did not answer the other open questions (US-006/007 response fields, US-009 losing errorCode, US-011 stats parameters and shape, US-015 author); they wait for their own design gates. The main session updated CLAUDE.md's run commands and its decisions reference.
- **Rationale:** *(engineer to add)*
- **Validation:** The architect cited official sources for every D22 version. The orchestrator independently confirmed from Maven Central that `spring-boot-dependencies-3.5.16.pom` manages `junit-jupiter.version` 5.12.2, and that `cucumber-junit-platform-engine-7.34.9.pom` is built against JUnit 5.14.2. It also confirmed that the other Boot-managed versions in D22 match the BOM.

## Entry 11 — US-001 implementation, QA, and review (G3)

- **Date:** 2026-09-29
- **Task:** Implement US-001 (project skeleton and infrastructure) to the design approved at G2.
- **Planner (E2 applied, engineer-approved scope move):** Added US-001 AC9–AC12: merged JaCoCo report, 70% gate, Mockito agent, and Testcontainers image. US-014 AC5 and AC7 are marked "moved to US-001 (D40)".
- **Mid-engineer:**
  - **Files:** pom.xml, Maven Wrapper (3.9.16), lombok.config, application class, `ClockConfig`, `application.yml` and `application-local.yml`, docker-compose.yml, README Quick start, `application-test.yml`, `TestcontainersConfiguration`, `@RepositoryTest`, and unit and slice tests.
  - **Deviation:** one, `@Autowired` on a test-method parameter, which the reviewer confirmed was needed.
  - **Local run:** created a temporary `.env` from `.env.example` for the manual Compose run, then deleted it.
- **QA-tester:** `IntegrationTestBase`, Cucumber configuration and suite (`CucumberIT`), `smoke.feature` (3 scenarios), and 6 `*IT` classes, plus `JvmAgentIT` in the fix round. No defects found.
- **Senior review, round 1: CHANGES_REQUIRED.**

  | ID | Severity | Finding |
  |---|---|---|
  | R1 | BLOCKING | JaCoCo exec files accumulated across builds, so the gate could pass on stale coverage |
  | R2 | BLOCKING | AC12 had no test |
  | R3 | SHOULD | The gate was skipped silently if the merged file was missing |
  | R4 | SHOULD | The Mockito agent was unproven |
  | R5 | SHOULD | Switch `@SelectPackage` for the Cucumber discovery warning |
  | R6–R12 | NIT | Various |

  The mid-engineer fixed R1–R4, R6, R7, R10 and R12. The qa-tester fixed R8, R9 and R11. **R5 was not applied**, because it changes an annotation the design prescribes, so it goes to the engineer.
- **Senior review, round 2: APPROVE.** All round-1 fixes were verified. Four new NITs (N1–N4) are left open.
- **Engineer decision:** "approve all" (relayed). **Accepted.**
  - US-001 is Done.
  - **AC2 is accepted on the configuration as proof (enforcer `requireJavaVersion [25,)`), with no JDK 21 run.**
  - R5 (`@SelectPackage`) is approved as a one-line amendment to design §8.4, made by the qa-tester in US-002.
  - N1 and N2 are folded into US-002; N3 and N4 are accepted as they are.
  - Senior-engineer memory: the engineer approved removing `memory: project` from the senior-engineer agent. Recurring rules now appear as "Proposed review rules" in its output, and approved rules go into `CLAUDE.md`. `.claude/agent-memory/` is added to `.gitignore`. The main session applied both changes.
  - Correction: the orchestrator's G3 checkpoint wrongly said the reviewer's suggested memory notes were already in the US-001 Review log. They have now been added there under "Proposed review rules" (six notes, verbatim).
- **Rationale:** *(engineer to add)*
- **Validation:**
  - The orchestrator ran `./mvnw -q clean verify`: exit 0, Surefire 4/0 and Failsafe 10/0 (run/failed), with 3 Cucumber scenarios. Merged LINE coverage was 2/2 and the gate passed.
  - The orchestrator also ran the local setup by hand: Compose PostgreSQL 18.6 was healthy with the volume at `/var/lib/postgresql`, the app with the local profile returned health UP and api-docs 200, and `/actuator/env` returned 404.
  - AC2 (the enforcer on a JDK below 25) is not verified because no older JDK is installed.

## Entry 12 — Commit C1 (G4)

- **Date:** 2026-09-29
- **Task:** First local commit (group C1): Task 0 docs skeleton, the agent setup and CLAUDE.md, the backlog (US-001–US-015 and the expiration placeholder), and US-001.
- **AI recommendation:** One commit covering C1, with the six proposed review rules from US-001 presented to the engineer for possible inclusion in CLAUDE.md.
- **Engineer decision:** "approve all" (relayed). **Accepted:**
  - Commit C1 with the proposed message unchanged. No push.
  - Add `.gitattributes` (`*.cmd text eol=crlf`, `mvnw text eol=lf`), which the main session created.
  - Add proposed review rules 1–3 to `CLAUDE.md` under a new "Review rules" subsection of Tests, which the main session also did. Rules 4–6 were **Rejected** for `CLAUDE.md` and stay only in the US-001 Review log.
  - Start US-002 after the commit.
- **Rationale:** *(engineer to add)*
- **Process-validation finding (self-correction):**
  - The orchestrator's G3 checkpoint said the senior-engineer's suggested memory notes were in the US-001 Review log. They were not; they existed only in the reviewer's report to the orchestrator.
  - The main session's independent check of the story and log files caught this. The orchestrator corrected it at G4 by copying the notes verbatim into the US-001 Review log under "Proposed review rules".
  - There were **6** notes, not 2 as first stated.
  - Lesson: a checkpoint must only claim content that has actually been written to a file.
- **Independent checks by the main session:** at G3, the main session re-ran `./mvnw -q clean verify` itself (exit 0; 14 tests, 0 failures; merged coverage report present). It also checked the G4 staging.
- **Validation:** Before staging, checked that `mvnw` is staged as 100755 and that no `.env`, `target/` or `.claude/agent-memory/` files are staged. The committed code is the same code that passed `./mvnw -q clean verify` at G3; only docs have changed since.
