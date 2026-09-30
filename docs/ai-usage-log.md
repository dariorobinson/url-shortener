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

## Entry 13 — US-002 design (G2)

- **Date:** 2026-09-29
- **Task:** Architect design note for US-002 (domain model, V1 schema, repository). Committed baseline: `efa36e8` (C1).
- **AI recommendation (architect):**
  - **Migration:** `V1__create_short_url.sql` exactly as in the architecture, unless E1 is approved.
  - **Entity:** `ShortUrl` created through a static factory with no setters. `IDENTITY` id, a `Long @Version` starting at 0, and `@Enumerated(STRING)` status.
  - **Analytics columns (D27):** `click_count` and `last_accessed_at` are `insertable=false, updatable=false`. The factory mirrors their DB defaults in memory, so no `@Generated` re-read is needed.
  - **Transitions:**
    - `deactivate(Instant)` and `reactivate(Instant)` throw the D26 exceptions.
    - Any transition on a DELETED link throws `ShortUrlDeletedException`, which maps to 404 in US-009.
  - **Equality and logging:** identity equals/hashCode; `toString` limited to id, code, status and version, so no URL or username appears in logs.
  - **Timestamps:** set from the injected Clock and truncated to microseconds.
  - **Repository:** `findByShortCode` only, with no status filter.
  - **Tests:** constraint tests use raw SQL and assert the SQLState and the constraint name. The AC9 test requires the version to reach 1, so an UPDATE really ran.
  - **QA step:** the R5 change, plus a V1 success assertion in `FlywaySchemaHistoryIT`.
- **Engineer decisions requested:**
  - **E1:** tighten `ck_short_url_deleted_consistency`. The approved form accepts a non-deleted row with only one audit field set.
  - **E2:** the application owns `created_at`/`updated_at`.
  - **E3:** transitions on deleted links throw.
- **Orchestrator validation:**
  - Confirmed the E1 logic: with the current CHECK, an ACTIVE row with `deleted_at` set and `deleted_by` NULL evaluates `false = false`, which passes.
  - Ran the code-format regex on a throwaway `postgres:18.6-alpine` container (collation en_US.utf8). `abc` and `ABC123` are accepted. `abcé`, `straße`, `ÀBC`, `abc٣`, `ab-c` and `ab` are rejected. The collation risk the architect raised does not occur on this image.
  - Corrected the carry-over note's annotation name to `@SelectPackages`, which the architect identified.
- **Engineer decision:** "approve all" (relayed). **Accepted.** E1, E2, E3 and the rest of the design note as written, including the qa-tester scope (R5 `@SelectPackages`, the V1 assertion in `FlywaySchemaHistoryIT`) and the mid-engineer carry-over (N1, N2). Recorded as D44 (tightened CHECK), D45 (the application owns timestamps, from the Clock, truncated to microseconds) and D46 (transitions on deleted links throw, mapped to 404). The optional AC6 wording change was not requested, so AC6 stays as written. The US-010 design inputs (the click UPDATE must not touch `updated_at`, must truncate to microseconds, and must use `clearAutomatically` or a separate transaction) are recorded in the US-010 story.
- **AI-originated defect caught by agent review:**
  - **Origin:** the flawed `ck_short_url_deleted_consistency` form `(status = 'DELETED') = (deleted_at IS NOT NULL AND deleted_by IS NOT NULL)` came from the main session's original planning SQL in the Task 0 `architecture.md`.
  - **Detection:** the architect caught it during the US-002 design review, before any migration was written. The main session independently confirmed the logic: an ACTIVE row with only `deleted_at` set gives false = false, so it passes. The orchestrator confirmed the same.
  - **Fix:** V1 uses the tightened form (D44), and `architecture.md` is updated.
- **Rationale:** *(engineer to add)*

## Entry 14 — US-002 implementation, QA, and review (G3)

- **Date:** 2026-09-29
- **Task:** Implement US-002 to the design approved at G2 (D44–D46).
- **Mid-engineer:**
  - **Built:** `V1__create_short_url.sql` (tightened CHECK), `ShortUrl`, `ShortUrlStatus`, three domain exceptions, `ShortUrlRepository`, four test classes plus the `PostgresErrors` helper, and carry-overs N1 and N2.
  - **Checks:** ran three mutation checks. Removing `updatable=false`, removing `insertable=false`, and removing the DELETED guard each made the targeted test fail, and each was reverted. `@Version` started at 0 as designed, so no escalation was needed.
  - **Deviation:** the design expected a 33-character code to fail the format CHECK (23514). PostgreSQL rejects it at `VARCHAR(32)` first (22001), so that case is now a separate test asserting 22001. No schema change was made.
- **QA-tester:** R5 is done (`@SelectPackages("features")`, and the warning is gone). Added a V1 success assertion to `FlywaySchemaHistoryIT`. No defects found.
- **Senior review, round 1: APPROVE.**

  | ID | Severity | Finding | Resolution |
  |---|---|---|---|
  | R1 | SHOULD | Duplicate-code JPA test asserted only the exception type | Fixed by the mid-engineer |
  | R2 | SHOULD | Stale `architecture.md` markers | Fixed by the orchestrator |
  | R3, R4, R6 | NIT | Various | Fixed by the mid-engineer |
  | R5 | NIT | Javadoc on the 22001 test | Waits for the engineer |

  The reviewer also found that `VARCHAR(32)` silently truncates trailing spaces on over-length input. The orchestrator confirmed this on `postgres:18.6-alpine`.
- **Senior review, round 2: APPROVE.** All fixes were verified. New NITs:
  - **N1:** V1 and one test comment still cite `E1`. This must be fixed before V1 is committed.
  - **N2:** an optional `updatedAt` assertion.
- **Proposed review rules:** six, recorded verbatim in the US-002 Review log for the engineer to decide on.
- **Engineer decision** (relayed; the engineer chose "Approve all", "TEXT + CHECK" and "Commit + push now" from the main session's structured question):
  - **US-002 approved:** **Accepted**, subject to the fixes below passing.
  - **R5, the 22001 split:** **Accepted**. Because of the TEXT change, the 33-character test now asserts the format CHECK (23514) instead.
  - **Trailing-space truncation:** **Modified**. The orchestrator had recommended keeping `VARCHAR(32)` and validating at the application layer. The engineer chose the main session's alternative, recorded as D47: `short_code` becomes `TEXT` (the format CHECK is the only limit), `original_url` becomes `TEXT` with a new `ck_short_url_original_url_length` CHECK, and the US-004 no-trim design input is kept.
  - **Pre-commit fixes N1, N2 and the R5 Javadoc:** **Accepted**.
  - **Review rules:** 1, 2, 3 (extended by 5) and 4 are **Accepted** and added to `CLAUDE.md` by the main session. Rule 6 is **Rejected** as a duplicate.
  - **Commit plan:** **Modified**. C2 is split: **C2a** is US-002 alone, committed now; **C2b** is US-003 and US-004.
  - **G4:** pre-approved on three conditions: the build passes, the senior re-review is APPROVE, and the architect raises no problem. No push; the main session verifies and pushes.
- **Rationale:** the database is the final guarantee (CLAUDE.md), and `VARCHAR(n)` truncates silently. Further rationale: *(engineer to add)*.
- **Validation:** the orchestrator ran `./mvnw -q clean verify`. It passed with exit 0: Surefire 57/0 and Failsafe 11/0 (run/failed), 3 Cucumber scenarios, and merged LINE coverage 52/52. The orchestrator also inspected the V1 SQL and reproduced the truncation behaviour.

## Entry 15 — US-002 fix round 2 (D47) and commit C2a (G4)

- **Date:** 2026-09-29
- **Task:** Apply D47 and the pre-commit fixes, re-verify, and make the conditional C2a commit, which the engineer pre-approved at G3 ("Commit + push now": commit only if the build passes, the review is APPROVE, and the architect raised no problem; the main session pushes).
- **Session interruption:** the previous Claude Code session ended partway through this work. The main session resumed with the engineer's message "continue implementation". The orchestrator checked the working tree before continuing:
  - Already done: V1 converted to TEXT, N1, N2, R5 (the 22001 test deleted), the D47 tests, and the G3 decision recorded in Entry 14.
  - Missing: the QA re-run and the senior re-review. These were then carried out.
- **Architect:** confirmed D47 with no problems.
  - Hibernate's validate step compares JDBC type codes, and pgjdbc reports `text` as `VARCHAR`, so no mapping change was needed.
  - The unique B-tree index on `short_code` is unaffected.
  - `char_length` is the correct measure for D11.
  - The design note and `architecture.md` were amended. The architect noted that `created_by` and `deleted_by` stay `VARCHAR(100)`; their values come from configured users, not request bodies.
- **Mid-engineer:** V1 now uses `TEXT` plus `ck_short_url_original_url_length`.
  - New tests cover a code of 32 characters plus a trailing space (rejected, not truncated) and URLs of 2049 characters, with and without a trailing space (rejected).
  - At exactly 2048 characters, a URL is accepted unchanged, including one that ends in a space. A 2048-character multibyte URL is also accepted.
  - The fix round also removed E-ids and § references from `src/`.
  - A manual EXPLAIN showed `Index Scan using uk_short_url_short_code`.
- **QA-tester:** re-ran the integration tests. No defects and no file changes.
- **Senior review, round 3: APPROVE.** Three NITs:
  - URL trailing-space cases assert only length, not `endsWith`.
  - The EXPLAIN was run with a literal, not a prepared statement.
  - A stale test count in the orchestrator's verification block (fixed).

  Three more proposed review rules are recorded in the US-002 Review log for the engineer to decide on.
- **Engineer decision:** G3 approval of US-002 is **Accepted** (from Entry 14). G4 for C2a was pre-approved on conditions, and the orchestrator confirmed all of them before committing.
- **Rationale:** *(engineer to add)*
- **Validation:** the orchestrator ran `./mvnw -q clean verify`: exit 0, Surefire 63/0 and Failsafe 11/0 (run/failed), including 3 Cucumber scenarios, and merged LINE coverage 52/52. Before committing, it confirmed `mvnw` is still 100755 and that no `.env`, `target/`, or `.claude/agent-memory/` files are staged.
- **Push (G4 follow-up):** the engineer said "approve all". The main session verified `34489d5` (parent `efa36e8`, clean tree, no forbidden files, 25 files) and **pushed it; `origin/main` is now `34489d5`**. The engineer **Accepted** round-3 review rules 1 and 3, which the main session added to `CLAUDE.md` (uncommitted, to go in C2b). Rule 2 was **Rejected** as too fine-grained.

## Entry 16 — US-003 short-code generator (start)

- **Date:** 2026-09-29
- **Task:** Implement US-003 without a design note, because its frontmatter has `requires_design_approval: false`. The engineer approved this ("approve all").
- **Constraints given:** D6 (Base62, case-sensitive), SecureRandom, configurable length (default 7) and max attempts (default 5), both validated at startup. Reserved-word handling for generated codes follows D29.
  - The relayed message cited "D28/D37" for reserved words. In `requirements.md` that rule is D29; D28 is own host and D37 is HSTS. The orchestrator treated this as a citation slip, because the behaviour described matches D29 exactly.
  - As the US-003 story's Out of scope states, the reserved-word retry is service-layer logic in US-006, and the generator stays pure.
- **Housekeeping:** clean up the stale design-note section reference in `JvmAgentIT.java:15` (owned by the qa-tester).
- **Mid-engineer:**
  - Built the `ShortCodeGenerator` interface, `SecureRandomShortCodeGenerator` (Base62, `nextInt(62)` with no modulo bias), the `ShortCodeProperties` record (`@Validated`, length 3–32 default 7, max-attempts at least 1 default 5), `ShortCodeConfig`, and unit plus `ApplicationContextRunner` tests.
  - Made six small design choices itself and flagged all of them. The main ones: `new SecureRandom()` rather than `getInstanceStrong()`, and publishing `SecureRandom` as a bean injected as `RandomGenerator`.
- **Orchestrator check:** the mid-engineer's first run was unusually short (8 tool uses, about 2.5 minutes). The orchestrator confirmed from disk that the files and Surefire reports exist before continuing.
- **QA-tester:**
  - Removed the design-note pointer from the `JvmAgentIT` Javadoc (now cites D43).
  - Added `ShortCodeGeneratorWiringIT`: the bean is wired with its defaults in the full context, and 50 generated codes are inserted through `ck_short_url_code_format` and the unique constraint.
  - No defects.
- **Senior review, round 1: APPROVE.**

  | ID | Severity | Finding |
  |---|---|---|
  | R1 | SHOULD | Security: the random source is published as a broadly typed bean, which a later `@Primary` bean could silently replace. Flagged as an engineer decision |
  | R2 | SHOULD | Package cycle between `shortcode` and `config` |
  | R3 | SHOULD | No length validation in the constructor |
  | R4 | SHOULD | Startup-failure tests asserted only `hasFailed()` |
  | R5 | SHOULD | The 3–32 bound is not tied to `ck_short_url_code_format` or D6 |
  | R6–R9 | NIT | Various |

  The mid-engineer fixed R2, R3, R4, R5 (citations only), R8 and R9. The qa-tester fixed R7. R1, R6 and R5's possible D-id were held for the engineer.
- **Senior review, round 2: APPROVE.** R4 was confirmed to fail on any unrelated startup failure. New NITs: N1 (duplicated 3–32 literals; a shared constant is suggested) and N2–N5.
- **Proposed review rules:** five, recorded verbatim in the US-003 Review log.
- **Engineer decision:** "approve all" (relayed). **Accepted:**
  - **R1:** do not publish `SecureRandom` as a bean; build it inside the generator bean method.
  - **R6:** keep the class name.
  - **Random source:** `new SecureRandom()`, the platform default.
  - **AC5:** accept the statistical flake (about 1 in 70,000).
  - **R5:** no new D-id; D6 plus the constraint name is enough.
  - **Final fix round:** R1, N1 (shared 3–32 constants), N2, N3 and N5 in one round, the last allowed. N4 stays open.
  - **Review rules:** rules 1, 4 (replacing 2) and 5 go into `CLAUDE.md`; the main session added them, uncommitted, for C2b. Rule 3 is **Rejected**, since the durable-ID rule covers it.
  - **Next:** after an APPROVE re-review and a passing build, set US-003 to Done and start US-004. C2b is **not** pre-approved.
- **Rationale:** *(engineer to add)*
- **Validation:** the orchestrator ran `./mvnw -q clean verify`: exit 0, Surefire 92/0, Failsafe 13/0 (run/failed), merged LINE coverage 66/66.
- **Fix round 2 (final):**
  - The mid-engineer applied R1: no `SecureRandom` or `RandomGenerator` bean; it is built inside the generator bean method, and tests assert the bean is absent. It also applied N1 (public `MIN_LENGTH`/`MAX_LENGTH` used by `@Min`/`@Max`), N2, N3 and N5.
  - **Process finding:** the mid-engineer reported that the generator file on disk was stale and rewrote it. The orchestrator's earlier read and passing build showed this was inaccurate. The senior-engineer confirmed every approved behaviour is intact.
- **Senior review, round 3: APPROVE.** New NITs:
  - **N6:** coverage figure should be 65/65 (fixed).
  - **N7:** restore the constructor's `@throws` Javadoc. Still open, because fix rounds are used up.
  - **N8:** QA-notes text out of date (fixed with an orchestrator addendum).

  Two more proposed rules are recorded in the story.
- **Validation:** the orchestrator ran `./mvnw -q clean verify`: exit 0, Surefire 92/0, Failsafe 13/0 (run/failed), merged LINE coverage 65/65. **US-003 set to Done.**

## Entry 17 — US-004 URL and alias validation (start)

- **Date:** 2026-09-29
- **Task:** Implement US-004 without a design note, because its frontmatter has `requires_design_approval: false`. The engineer approved this.
- **Carried design input (US-002 G3, D47):** reject length and format violations on the raw input, and never trim. This includes a test for a 32-character alias with a trailing space.
- **Mid-engineer:**
  - Built `UrlValidator`, `AliasPolicy`, the `HttpUris` strict parser, `AppProperties` (`app.base-url` from `APP_BASE_URL`, `@NotBlank` plus a custom `@HttpBaseUrl`), `AliasProperties` (`shortener.alias.reserved-words`, defaulting to D29), `ValidationConfig`, and 132 unit and `ApplicationContextRunner` tests.
  - Nine decisions were flagged for the engineer. The main ones: strict `java.net.URI` parsing; reject any userinfo; `codePointCount` to match `char_length`; the base URL rejects userinfo but allows a path; setting the reserved-word property replaces the default list; blank entries are ignored.
  - Open questions it raised: IP/localhost/private hosts, IDN hosts, and an empty reserved-word list.
- **Orchestrator check:** the report was compact for 224 tests, so the orchestrator confirmed the files and Surefire reports on disk.
- **QA-tester:** added `ValidationWiringIT` (full context: own host is `localhost` from the test profile, and the D29 default list is active). No defects.
- **Senior review, round 1: CHANGES_REQUIRED.**

  | ID | Severity | Finding |
  |---|---|---|
  | R1 | BLOCKING | Vacuous env-binding test: `withPropertyValues("APP_BASE_URL=…")` never binds |
  | R2 | SHOULD | Wrong stated reason for not using a regex |
  | R3 | SHOULD | The base URL accepts a query or fragment |
  | R4 | SHOULD | Bypass inputs are not pinned as tests |
  | R5 | NIT | Where the length constants live |
  | R6 | NIT | Default-locale `toUpperCase` in the IT |
  | R7 | NIT | A misfiled test case |
  | R8 | NIT | Lone surrogates |
  | R9 | NIT | The uncovered private constructor |

  The reviewer also wrongly said CLAUDE.md lacks the startup-failure rule; the orchestrator pointed to line 78, and the reviewer accepted the correction. The mid-engineer fixed R1, R2, R4 and R7, and the qa-tester fixed R6. R3, R5 and R8 are held for the engineer.
- **Senior review, round 2: APPROVE.** R1 was confirmed no longer vacuous, and the R4 inputs were checked with jshell. R10, the lost severities in the review log, was fixed by the orchestrator.
- **Proposed review rules:** six, recorded verbatim in the US-004 Review log.
- **Engineer decision:** "approve all" (relayed). **Accepted:**
  - **R3:** the base URL rejects a query or fragment.
  - **D48:** the D29 words are always-on built-ins, and `shortener.alias.additional-reserved-words` adds to them. This **modifies** the mid-engineer's replace behaviour, and makes the empty-list question moot.
  - **R8:** URLs that can't be encoded as UTF-8 are rejected.
  - **D49:** IP, localhost and private hosts are accepted for now; IDN hosts are rejected and clients send punycode. The US-006 API docs must say so; this is recorded as a design input in the US-006 story.
  - **R5:** the constants stay where they are.
  - **Flagged defaults:** all nine approved, with 3, 8 and 9 as modified.
  - **Final fix round:** R3, D48, R8 and US-003 N7.
  - **Review rules:** 2, 3, 5, 6 and 7 go into `CLAUDE.md` (the main session added them, uncommitted, for C2b). Rules 1 and 8 are **Rejected**, and 4 was replaced by 7.
  - **C2b:** **not** pre-approved.
- **Rationale:** *(engineer to add)*
- **Validation:** the orchestrator ran `./mvnw -q clean verify`: exit 0, Surefire 235/0, Failsafe 18/0 (run/failed), merged LINE coverage 110/111.
- **Fix round 2 (final):**
  - The mid-engineer applied:
    - **R3:** `HttpBaseUrl` rejects a query or fragment, including an empty `?` or `#`.
    - **D48:** `AliasPolicy.BUILT_IN_RESERVED_WORDS` plus `shortener.alias.additional-reserved-words`; the old property is removed.
    - **R8:** a UTF-8 `canEncode` check, with a new encoder per call.
    - **US-003 N7:** the `@throws` Javadoc is restored.
    - **D49:** regression tests.
  - The orchestrator confirmed each claim on disk.
- **Senior review, round 3: APPROVE.**
  - The mid-engineer had concluded that only the underscore env-var form binds a list property. The reviewer showed from the Boot 3.5.16 bytecode that this is **false in production**, because the tests' property sources are not named `systemEnvironment`. Both forms bind. The orchestrator added a correction to the story.
  - Open findings:
    - **R11 (SHOULD):** name the test sources correctly and test both forms.
    - **R12 (SHOULD):** `@throws` on the `UrlValidator` constructor.
    - **R14, R15 (NIT).**
  - Three more proposed review rules.
- **Validation:** the orchestrator ran `./mvnw -q clean verify`: exit 0, Surefire 263/0, Failsafe 18/0 (run/failed), merged LINE coverage 118/119. **US-004 set to Done.**

## Entry 18 — Commit C2b (G4)

- **Date:** 2026-09-29
- **Task:** Commit C2b: US-003, US-004, and the engineer-approved `CLAUDE.md` review rules.
- **Engineer decision:** "approve all" (relayed; it means the orchestrator's recommendations, not the main session's comments-only alternative). **Accepted:**
  - Commit C2b exactly as staged. **Committed as `7d441b7`** (parent `34489d5`, 28 files). Not pushed; the main session verifies and pushes.
  - R11, R12, R14 and R15 carry into US-005. Its design note must make both env-var forms the tested contract, and correct the false comment in `AppPropertiesTest`.
  - US-004 round-3 review rules 1 and 3 are **Accepted**; rule 2 is **Rejected**. The main session added the two rules to `CLAUDE.md` after C2b was staged. At the engineer's direction they were left out of C2b, which committed the index only, and they go into C3.
- **Validation:** the staging checks passed. After the commit, `git status --short` showed only ` M CLAUDE.md`.
- **Rationale:** *(engineer to add)*

## Entry 19 — US-005 security foundation (design)

- **Date:** 2026-09-29
- **Task:** Architect design note for US-005. The story's frontmatter has `requires_design_approval: true`, so it stops at G2.
- **Inputs:** D3, D4, D30, D31 and D32. Carry-over from US-004: R11, R12, R14 and R15, plus the env-var forms as the tested contract. The `created_by`/`deleted_by` `VARCHAR(100)` truncation question from US-002. The CLAUDE.md rule on security-sensitive beans, which applies to the password encoder.
- **AI recommendation (architect):**
  - **Dependencies:** `spring-boot-starter-security` and `spring-security-test`.
  - **Filter chain:** HTTP Basic, stateless, CSRF off, with ordered rules:
    1. Error dispatch permitted.
    2. `GET /actuator/health` public.
    3. springdoc paths public.
    4. `/actuator/**` authenticated.
    5. `DELETE /api/v1/urls/*` ADMIN only, declared now so US-009 gets "403 before lookup".
    6. `/api/**` requires USER.
    7. `GET`/`HEAD` on any single segment public. It is deliberately not restricted to the code format, so US-008 AC6 can return 404 for malformed codes.
    8. Everything else authenticated.
  - **Method security:** none.
  - **Users:** a list at `app.security.users[n]`. The only published security bean is a concrete `DaoAuthenticationProvider`; the BCrypt encoder (cost 10) and the user store are built inside it, per the security-sensitive-bean rule.
  - **Errors:** `api/error/ErrorCode` (the D31 list) and a single `ProblemDetails.of` factory, shared with US-006. 401 carries `realm="url-shortener"` and the same body for missing, wrong or unknown credentials. 403 is `ACCESS_DENIED`.
  - **Hash validation:** done after binding and never logs the value. A plaintext password would otherwise leak through Boot's failure report.
  - **Carry-over:** R11, R12, R14 and R15 are specified. Env-binding tests use a source named `systemEnvironment` and cover both env-var forms.
- **Engineer decisions requested:**
  - **S1:** user configuration shape (list recommended).
  - **S2:** `created_by`/`deleted_by` length. Option A (recommended) bounds usernames at startup plus an entity guard, with no migration.
  - **S3:** no usernames or IPs in authentication-failure logs.
  - **S4:** BCrypt cost fixed at 10.
  - **S5:** redefine the reserved-words "invalid-value" test.
  - **S6:** strip the `.env` variables from the test JVMs.
  - **Q2–Q5:** open questions, each with a recommendation.
- **Engineer decision:** "approve all" (relayed). The main session offered "approve all" (the orchestrator's recommendations) or "approve all, with Option B and placeholder hashes" (the main session's alternatives), and the engineer chose "approve all". **Accepted:**
  - **S1 → D50:** list-shaped users.
  - **S2 → D51:** Option A, username bounds plus an entity guard, no migration.
  - **S3 → D52:** no usernames or IPs logged on 401/403.
  - **S4 → D53:** BCrypt cost 10.
  - **S5:** the reserved-words binding test becomes "binds but cannot remove a built-in word".
  - **S6:** strip the `.env` variables from the test JVMs.
  - **Q2 → D54:** case-insensitive username matching at login.
  - **Q3 → D55:** invalid credentials get 401 on public paths.
  - **Q4 → D56:** AC8's "sole extension" applies to security errors only. The planner adjusted AC8 and its test row. The planner also found that design note §7.2 ("same key set") conflicted with Q4; the orchestrator added an alignment note to the design note header rather than rewrite the architect's text.
  - **Q5:** the OpenAPI Basic scheme goes in US-006.
  - **The rest of the design as written**, including the matcher order and working local BCrypt hashes in `.env.example` (K10 accepted as designed).
- **Main-session suggestions not adopted:**
  - **S2:** the main session recommended Option B (migrate `created_by`/`deleted_by` to `TEXT` plus a CHECK, for consistency with D47). The engineer chose Option A. Rationale: *(engineer to add)*.
  - **K10:** the main session recommended placeholder hashes in `.env.example`, because the repository is public. The engineer chose working local hashes. Rationale: *(engineer to add)*.
- **Additional engineer instruction:** because the repository is public, `.env.example` comments must state plainly that its credentials are for local development only and must never be used in any shared or deployed environment.
- **Rationale:** *(engineer to add)*
- **Validation:** the architect cited the Spring Security 6.5.11, Boot 3.5.16, Spring Framework 6.2.19 and springdoc 2.8.17 source and docs, and OWASP. The orchestrator confirmed that only the US-005 Design note and `architecture.md` were changed.
- **Mid-engineer:**
  - Built `security/` (`SecurityConfig`, `UserAccountsConfig`, `UserAccountsProperties`, `UserAccounts`, `Role`, the two ProblemDetail handlers and a response writer), plus `api/error/ErrorCode` and `ProblemDetails`.
  - Also delivered: the `ShortUrl` actor guard, the pom and yml changes, the `.env.example` public-credential warning, the S6 env excludes, and the full US-004 carry-over. That carry-over includes both env-var forms tested through a source named `systemEnvironment`, and the false comment corrected.
  - Reported no deviations. The orchestrator confirmed on disk that the only security beans are `DaoAuthenticationProvider`, `RoleHierarchy` and `SecurityFilterChain`, with no `PasswordEncoder` or `UserDetailsService` bean.
- **QA-tester:**
  - Added `SecurityIT` (real Tomcat, JDK `HttpClient`), `security.feature` and `SecuritySteps`. No defects.
  - Observation: as USER, `DELETE /api/v1/urls/abc/` (trailing slash) is not caught by the ADMIN rule.
- **Senior review, round 1: APPROVE.**

  | ID | Severity | Finding |
  |---|---|---|
  | R1 | SHOULD | Bad-hash tests rejected for length, not for the reason they name |
  | R2 | SHOULD | Hash-erasure test bypassed `ProviderManager` |
  | R3 | SHOULD | "Not logged" test could pass vacuously |
  | R4 | SHOULD | Error-dispatch claim was untrue |
  | R5–R14 | NIT | Various |

  The mid-engineer fixed R1–R3, R5, R7, R9, R10 and R12–R14. The qa-tester fixed R4, R6 and R8, recording the error-dispatch rule as defence-in-depth. The orchestrator fixed R11.
- **Senior review, round 2: APPROVE.**
  - **R15 (SHOULD, security hardening):** the trailing-slash or nested DELETE variants fall through to the USER rule. Not exploitable today, but a latent privilege escalation. The reviewer recommends `DELETE /api/v1/urls/**` ADMIN plus a probe-route test, and the change goes to the engineer because it alters the approved matcher.
  - **R16, R17:** QA comment NITs.
  - **R18, R19:** doc NITs, fixed or superseded by the orchestrator.
- **Proposed review rules:** six, recorded verbatim in the story.
- **Engineer decision:** "approve all" (relayed). **Accepted:**
  - **R15, option 1:** `DELETE /api/v1/urls/**` requires ADMIN, the pinned trailing-slash row flips to 403, and a web-slice probe route is added at `/api/v1/urls/{code}/`.
  - **Final fix round:** R15 plus R16 and R17.
  - **Actuator:** ADMIN-only exposure of anything beyond health goes to US-014's carry-over.
  - **HSTS behind the load balancer:** `forward-headers-strategy` with a trusted proxy and a test also go to US-014's carry-over.
  - **`architecture.md` notes:** the `/error` exception and never using security DEBUG/TRACE in shared environments.
  - **Review rules:** 1–5 go into `CLAUDE.md` (the main session added them, uncommitted, for C3). Rule 6 is **Rejected**. The new rule 5 applies to R15's tests, including a case-variant test that names the story that must revisit it.
  - **C3:** not pre-approved.
  - **K10:** the engineer **confirmed** publishing the known local passwords (`local-admin-password`, `local-user-password`) in `.env.example`, for local development only.
- **Rationale:** *(engineer to add)*
- **Validation:** the orchestrator ran `./mvnw -q clean verify`: exit 0, Surefire 395/0, Failsafe 78/0 (run/failed), including 15 Cucumber scenarios, merged LINE coverage 231/232. The build log contains no generated security password.
- **Final fix round (in progress):**
  - The mid-engineer changed the ADMIN rule to `DELETE /api/v1/urls/**` and added web-slice probe routes at `/{code}/` and `/{code}/x`. A USER gets 403 with the probe counter unchanged, and an ADMIN reaches the probe.
  - The qa-tester flipped the `SecurityIT` trailing-slash row to 403, re-verified the other rows, fixed R16 and R17, and commented the `/API/` row as pending.
  - **ESCALATION:** following the new CLAUDE.md case-variant rule, the mid-engineer mapped a temporary probe at `DELETE /API/v1/urls/{code}`. A **USER got 204 and reached the handler**, because the case-sensitive matchers miss both `/api` rules and `anyRequest().authenticated()` admits any USER. It is not exploitable today (there is no upper-case route, and MVC mapping is case-sensitive). As instructed, the mid-engineer stopped, removed the temporary probe, and pinned nothing that shows the escalation passing. The orchestrator ran `./mvnw -q clean verify`: exit 0, Surefire 400/0, Failsafe 78/0 (run/failed). The senior re-review is held until the engineer decides.
- **Engineer decision on the escalation:** "option a, approve third round" (relayed). **Accepted:** option A.
  - The final rule becomes `anyRequest().denyAll()`, recorded as **D57**.
  - A probe test: `DELETE /API/v1/urls/{code}` as USER gets 403, with the counter unchanged.
  - Affected rows re-pinned: anonymous gets 401, authenticated gets 403 on unmatched paths.
  - The comment, the design note (amendment line) and `architecture.md` (rules 5 and 8) are updated, and the `/API/` row's pending comment is removed.
  - A **third fix round is explicitly authorised**, beyond the two-round limit, and limited to option A and its re-pins.
  - Options B (DELETE-only `denyAll`), C (case-insensitive matchers) and D (accept and pin) were **not adopted**.
- **Main-session observation:** before the fix, the comment said "Deny by default" while the code was `anyRequest().authenticated()`, so the comment misdescribed the rule.
- **Process-validation finding:**
  - The engineer approved the review rule requiring role-rule variant tests (trailing slash, nested, case) one round earlier.
  - Applying it found a latent privilege-escalation gap before any route existed to exploit it.
  - The two-round limit forced the fix up to the engineer, rather than letting agents change an approved security rule themselves.
- **Fix round 3 results:**
  - The mid-engineer changed the rule to `anyRequest().denyAll()`, citing D57 in the comment. It added web-slice probes at `DELETE /API/v1/urls/{code}`, `POST /{code}` and `GET /{a}/{b}`: USER and ADMIN get 403, anonymous gets 401 with the challenge, and the counter stays 0.
  - The mid-engineer confirmed that `ExceptionTranslationFilter` sends anonymous callers to the entry point.
  - The qa-tester flipped the `SecurityIT` `/API/` row to 403. No other expectation changed.
  - QA reported "Surefire 400" against the mid-engineer's 404. The reviewer's and the orchestrator's clean builds both show **404**, so QA's figure was out of date.
- **Senior review, final: APPROVE.** R15, R16 and R17 are resolved, and D57 blocks nothing a planned story needs.
  - R21 (fixed by the orchestrator) and R25 (superseded by the orchestrator's verification) are closed.
  - R20, R22, R23 and R24 are NITs left open, because no fix rounds remain.
  - Engineer attention: anonymous `HEAD /actuator/health` returns 401, and CORS preflight would need handling. Both were added to the US-014 carry-over.
  - Two more proposed review rules.
- **Validation:** the orchestrator ran `./mvnw -q clean verify`: exit 0, Surefire 404/0, Failsafe 78/0 (run/failed), merged LINE coverage 231/232, and no generated password. **US-005 set to Done.**

## Entry 20 — Commit C3 (G4)

- **Date:** 2026-09-29
- **Task:** Commit C3: US-005 plus the engineer-approved `CLAUDE.md` review rules and the related doc updates.
- **Engineer decision:** "approve all" (relayed). **Accepted:**
  - Commit C3 exactly as staged. **Committed as `9ad1945`** (parent `7d441b7`, 38 files).
  - The main session verifies and pushes (engineer-approved).
  - R20, R22, R23 and R24 carry into US-006.
  - The final re-review's rule 1 (each new endpoint names its filter-chain rule) is **Accepted**. The main session added it to `CLAUDE.md` unstaged, for C4. Rule 2 is **Rejected**.
- **Validation:** after the commit, `git status --short` showed only ` M CLAUDE.md`.
- **Rationale:** *(engineer to add)*

## Entry 21 — US-006 create short URL API (design)

- **Date:** 2026-09-29
- **Task:** Architect design note for US-006. The story needs design approval, so it stops at G2.
- **Inputs:**
  - The US-004 design inputs.
  - D56's validation extension.
  - The OpenAPI Basic scheme (Q5 from US-005).
  - Per-attempt transactions with a bounded retry on `uk_short_url_short_code`: 409 for a taken alias, 503 when attempts run out.
  - Ownership (D4).
  - The `SecurityConfig` rule that admits each new endpoint.
  - The open response-field question.
  - The R20–R24 carry-over from US-005.
- **AI recommendation (architect):**
  - **Security and ownership:** `POST /api/v1/urls` is admitted by the existing `/api/**` USER rule (no `SecurityConfig` change). `created_by` is `Authentication.getName()` (D4, D54).
  - **Retry and errors:**
    - Each attempt runs in its own REQUIRES_NEW `TransactionTemplate`; `create` itself is not transactional.
    - A reserved-word match on a generated code uses up an attempt without touching the database.
    - Only a violation of `uk_short_url_short_code` is retried. Any other constraint violation is a 500.
    - A new production helper, `repository/PostgresServerErrors`, reads the SQLSTATE and constraint name, and the test helper delegates to it.
    - One `GlobalExceptionHandler` extends `ResponseEntityExceptionHandler`, and every body goes through `ProblemDetails.of`. It adds an `errors: [{field, message}]` extension (D56) that never echoes the rejected value.
  - **OpenAPI:** the HTTP Basic scheme is applied per controller, with a punycode note (D49).
  - **Test seam:** a scripted `@Primary` test-only generator, imported by `IntegrationTestBase` itself (one shared context), and reset for each test and scenario.
  - **AC9 test:** a repeated race plus a deterministic version using a lock held on another connection.
- **Engineer decisions requested:**
  - **A1:** the response field set, shared with US-007.
  - **A2:** reject unknown JSON fields and duplicate keys (global Jackson setting).
  - **A3:** an empty or whitespace alias gives 400 `INVALID_ALIAS`; `null` or absent means a generated code.
  - **A4:** add `RESOURCE_NOT_FOUND`, `METHOD_NOT_ALLOWED`, `NOT_ACCEPTABLE` and `UNSUPPORTED_MEDIA_TYPE` to D31.
  - **A5:** test-profile `app.base-url` = `https://short.example`.
  - **A6:** report `INVALID_URL` before `INVALID_ALIAS`.
  - **A7:** set `logServerErrorDetail=false` and silence `SqlExceptionHelper`, so a PostgreSQL "Failing row contains" message never logs the URL or username. The orchestrator had already seen that message in the US-002 truncation check.
  - **A8:** give the PostgreSQL driver compile scope.
  - **A9:** a Failsafe-only JVM option that lets tests forge the `Host` header.
  - **Open questions:** Q2–Q4.
- **Engineer decision:** "approve all" (relayed). **Accepted:** A1–A9, Q2–Q4 and the rest of the design note as written. They are recorded as D58–D69.
- **Engineer guardrails:**
  - `create` is never `@Transactional`.
  - The AC9 lock-based test is never weakened into a sleep.
  - The A9 flag goes in the Failsafe `argLine` only.
  - A7 is verified by a test proving "Failing row contains" never reaches the logs, with a positive log-capture assertion.
  - C4 is not pre-approved.
- **C3 push:** the main session verified `9ad1945` (parent `7d441b7`, 38 files, no forbidden files, trailer present, the new CLAUDE.md endpoint rule correctly excluded) and **pushed it; `origin/main` is now `9ad1945`**.
- **Rationale:** *(engineer to add)*
- **Validation:** the architect cited the Spring Framework 6.2.19, Boot 3.5.16, Hibernate 6.6.53, pgjdbc 42.7.11 and springdoc 2.8.17 sources and docs. The orchestrator confirmed that only the US-006 Design note and `architecture.md` changed.
- **Mid-engineer (implementation):**
  - Implemented the approved design and kept every guardrail: no `@Transactional` (checked by reflection), per-attempt `REQUIRES_NEW`, and only `uk_short_url_short_code` retried.
  - D64 is proven by `DatabaseErrorLoggingTest`: a real CHECK violation, a positive capture of the ERROR line, and "Failing row contains" absent. Mutation checks confirm each setting matters.
  - D66 is in the Failsafe `argLine` only, D65 gives the driver compile scope, and the R23/R24 carry-overs are done.
  - Surefire 510/0 (run/failed). Failsafe has 2 expected failures in QA-owned files, from D61 and D62.
  - Deviation: a public test helper, `SecuritySliceTestConfiguration`, because the security configuration classes are package-private.
- **ESCALATION:**
  - The mid-engineer found, with a temporary real-PostgreSQL IT that was later deleted, that `POST` with `Accept: application/xml` **commits the row and then returns 406**. It did not fix this, because design risk K5 forbids `produces`.
  - The architect then analysed it without editing anything. From the Spring 6.2.19 source, it confirmed that content negotiation happens after the handler commits. It also found that **K5 was wrong**: `DispatcherServlet.processHandlerException` clears `PRODUCIBLE_MEDIA_TYPES_ATTRIBUTE` before any exception resolver runs, so `produces` does not break problem+json error bodies.
  - A malformed `Accept` header has the same commit-then-406 effect.
  - The architect recommends class-level `produces = application/json` on `ShortUrlController`, never on the redirect. It rejects an interceptor, accepting and documenting the behaviour, ignoring `Accept`, and deduplication.
  - Awaiting the engineer.
- **Engineer decision on the escalation:** "approve all" (relayed). **Accepted:**
  - **Option (a) → D70:** class-level `produces = application/json`. K5, the design-note §2 bullet and the Javadoc are corrected, and the precedence 401 > 405 > 415 > 406 > 400 is documented.
  - **Unparseable `Accept`:** returns 406 with an empty body, recorded as a known deviation from D61.
  - **DELETE:** returns 406 too; recorded as a US-009 design input.
  - **Redirect:** never declares `produces`; recorded as a US-008 design input.
  - **New AC:** the planner adds "an unacceptable `Accept` returns 406 and creates nothing", with a Cucumber scenario.
  - **Classification:** this proceeds as part of implementation, not a fix round. The fix-round count for US-006 starts at zero with the senior review.
  - Options (b), (c) and (d1) were **not adopted**.
- **Process-validation finding:**
  - An empirical real-PostgreSQL test by the mid-engineer showed that the approved design's risk K5 was wrong and caused a commit-then-error bug.
  - The mid-engineer stopped rather than deviate from the approved design.
  - The architect confirmed the root cause from the Spring 6.2.19 source (`processHandlerException` clears the producible attribute) before any fix was made.
- **Implementation completed after D70:**
  - The mid-engineer added class-level `produces`, a 406 `@ApiResponse` using the `Problem` schema, and web-slice 406 tests with `verifyNoInteractions(service)`.
  - The architect corrected K5, §2, §4 and §5 in the design note, plus `architecture.md`.
  - The planner added AC17.
  - The qa-tester delivered the scripted `@Primary` generator seam through `IntegrationTestBase` (one context), 47 create Cucumber scenarios, `CreateShortUrlIT`, `ShortCodeCollisionIT`, `CreateShortUrlConcurrencyIT` and the `OpenApiDocsIT` additions. `CreateShortUrlConcurrencyIT` includes a race repeated 10 times and a lock-based proof that polls `pg_stat_activity` with Awaitility; there is no sleep.
  - The qa-tester also re-pinned the D61 and D62 cases and completed R20 and R22. No defects.
- **Senior review, round 1: APPROVE.**
  - **R1 (SHOULD):** framework 5xx errors went through `handleExceptionInternal` without being logged. `HttpMessageNotWritableException` is the Q5 case of a committed row followed by a failed write.
  - **R2 (SHOULD):** an unreachable branch in the handler.
  - **R3–R10:** NITs.

  The mid-engineer fixed R1–R4, the qa-tester fixed R5–R9, and the orchestrator fixed R10. In the process the qa-tester broke the build twice with a bulk replace and repaired both.
- **Senior review, round 2: APPROVE.** R1 was confirmed: each 5xx is logged exactly once, and the log line never includes a query string. N1–N3 are NITs left open.
  - The reviewer's view on Q5 is to accept and document it. After a 409, a client can use `GET /api/v1/urls/{alias}` (US-007) to check whether the alias is its own.
- **Proposed review rules:** four, recorded in the story.
- **Engineer decision:** "approve all" (relayed). **Accepted:**
  - US-006 is Done.
  - **Q5 → D71:** accepted and documented. The OpenAPI 409 description and the US-007 note are to be added, and the idempotency key goes on the US-014/US-015 roadmap.
  - **N1–N3:** carried into US-007.
  - **Review rules:** 1, 2 and 3 go into `CLAUDE.md` (the main session added them). Rule 4 is **Rejected**.
  - **Commit plan:** **Modified**. US-006 is committed alone as **C4a**, and US-007 becomes **C4b**. C4a is not pre-approved.
- **Independent verification by the main session:** it re-ran the G3 build: exit 0, Surefire 515/0, Failsafe 216/0 (run/failed), and 0 "Failing row contains" lines. It confirmed:
  - `@Transactional` appears in `src/main` only in two Javadoc warnings;
  - `produces` appears only on `ShortUrlController`;
  - `allowRestrictedHeaders` appears only in the Failsafe `argLine`;
  - there is no `Thread.sleep` in the tests.
- **Rationale:** *(engineer to add)*
- **Validation:**
  - The orchestrator ran `./mvnw -q clean verify`: exit 0, Surefire 515/0 and Failsafe 216/0 (run/failed), including 59 Cucumber scenarios, merged LINE coverage 380/382.
  - The build log has no "Failing row contains" line.
  - The guardrails were confirmed on disk.
- **Post-G3 change (D71):** the mid-engineer changed only the OpenAPI 409 description string on `POST /api/v1/urls`, adding the "unexpected 409, check with GET /api/v1/urls/{alias}" guidance. No test asserted the old text. The orchestrator confirmed the diff is limited to that string and rebuilt: `./mvnw -q clean verify` gave exit 0, Surefire 515/0, Failsafe 216/0 (run/failed), LINE 380/382, and 0 "Failing row contains" lines.

## Entry 22 — Commit C4a (G4)

- **Date:** 2026-09-29
- **Task:** Commit C4a: US-006, the related docs (D58–D71, design inputs for US-007, US-008, US-009 and US-014), and the `CLAUDE.md` review rules (the endpoint rule plus the three US-006 rules).
- **Engineer decision:** *(pending G4; not pre-approved)*
- **Rationale:** *(engineer to add)*
