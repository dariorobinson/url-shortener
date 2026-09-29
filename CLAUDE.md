# CLAUDE.md

URL shortener built as a Charles Schwab interview assignment. The goal is to demonstrate disciplined, AI-assisted engineering in which the human engineer approves every major decision.

## Workflow: agent team

Development runs through the agents in `.claude/agents/`:

| Agent | Role |
|---|---|
| `orchestrator` | Runs the SDLC, delegates to the others, stops at approval gates |
| `planner` | User stories in `docs/stories/` |
| `architect` | `docs/architecture.md` and per-story design notes |
| `mid-engineer` | Implements stories with unit, web-slice, and repository tests; fixes review findings and QA defects |
| `qa-tester` | Integration testing: Cucumber acceptance scenarios and `*IT` tests; defect reports |
| `senior-engineer` | Read-only review of code and tests |

**Main session rules:**
- For SDLC work (planning, stories, implementation, review), invoke the `orchestrator` agent in the foreground rather than doing the work directly.
- The orchestrator ends each turn at an approval gate with a `⏸ CHECKPOINT`. Relay it to the engineer faithfully and completely — do not summarize away risks, findings, or decisions needed.
- Pass the engineer's decision back with `SendMessage` to the same orchestrator, quoting it exactly. Never approve on the engineer's behalf.
- If the orchestrator's context is lost, start a new one; it rebuilds state from `docs/stories/`, `docs/requirements.md`, and `docs/ai-usage-log.md`.

## Tech stack

Java 25 · Spring Boot 3.5.x · Spring Web · Spring Data JPA · Spring Security · PostgreSQL · Flyway · Bean Validation · Lombok · springdoc-openapi · JUnit 5 · Mockito · Testcontainers · Cucumber · JaCoCo · Docker Compose · Maven Wrapper

Base package: `com.schwab.urlshortener`. `JAVA_HOME` must point to JDK 25.

## Commands

- Build and all tests: `./mvnw -q verify`
- Start the database: `docker compose up -d` (the app service is added to Compose in US-014)
- Run the app locally: `./mvnw spring-boot:run -Dspring-boot.run.profiles=local`

## Code conventions

**Structure**
- Layers: `api` (controllers, DTOs, error handling) → `service` → `repository`. Entities never leave the service layer.
- DTOs and value objects are Java **records**, validated with Bean Validation.
- Inject `Clock` wherever the current time is needed; never call `Instant.now()` directly.

**Lombok**
- `@Slf4j` for logging; `@RequiredArgsConstructor` with `private final` fields for dependency injection (no field injection).
- Entities: `@Getter`, `@NoArgsConstructor(access = AccessLevel.PROTECTED)`, and intention-revealing methods for state changes (`deactivate()`, `softDelete(by, at)`).
- Entities must **not** use `@Data`, blanket `@Setter`, `@AllArgsConstructor`, `@EqualsAndHashCode`, or `@ToString` over lazy associations.

**Errors**
- RFC 7807 `ProblemDetail` with an `errorCode` extension, from a single `@RestControllerAdvice`.
- Never expose stack traces, exception messages from internals, or SQL details.

**Logging**
- Log short codes, IDs, and hosts — never full URLs (query strings may hold tokens), credentials, or personal data.

**Data integrity**
- Database constraints are the final guarantee. Application-level checks exist only to produce friendlier errors.
- Migrations are forward-only (`src/main/resources/db/migration/V<n>__<desc>.sql`).

**Tests**
- `*Test.java` (Surefire): unit, `@WebMvcTest`, and `@DataJpaTest` tests — owned by `mid-engineer`.
- `*IT.java` and Cucumber features in `src/test/resources/features/` (Failsafe): full-stack integration tests through HTTP — owned by `qa-tester`.
- Every test that touches a database uses Testcontainers PostgreSQL, never H2.
- Test names describe behaviour: `shouldReturn409WhenAliasAlreadyExists`.
- Every acceptance criterion has a test; every API acceptance criterion has a Cucumber scenario.
- JaCoCo line coverage ≥ 70% (unit + integration, merged) is enforced by the build.

**Review rules** (proposed by `senior-engineer`, approved by the engineer)
- Coverage data must be fresh on every build: never use JaCoCo `append=true` without deleting old `.exec` files first, or the merged gate is inflated by stale runs. Re-check whenever the gate configuration changes.
- JaCoCo `merge` and `check` skip silently when an exec file is missing, so the build must fail loudly if merged coverage data is absent (keep the `requireFilesExist` enforcer check).
- `IntegrationTestBase` subclasses must add nothing that changes the Spring context (`@MockitoBean`, `@TestPropertySource`, `@DynamicPropertySource`, extra `@Import`, etc.); each change starts a second context and a second PostgreSQL container.

## Post-task report (required at every story completion)

1. What changed
2. Files modified
3. Key implementation decisions
4. AI-generated code that needs extra human review
5. Assumptions
6. Risks and trade-offs
7. Review for correctness, security, concurrency, error handling, database integrity, maintainability, and performance
8. Tests run and results
9. Items requiring manual verification

## Git

- Commit locally at the end of major features, only after the engineer approves. Never push without explicit approval.
- Only the orchestrator commits.

## Key documents

`docs/requirements.md` (all decisions, D1 onward) · `docs/architecture.md` · `docs/stories/` · `docs/scenarios.md` · `docs/ai-usage-log.md`
