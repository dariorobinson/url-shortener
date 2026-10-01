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
- US-001–US-011 were delivered through the agent team above.
- **From US-012 onward (engineer direction, 2026-09-30), the main session does the work directly and does not invoke the orchestrator or any subagent.** The same discipline still applies: approval gates with the engineer (backlog/design/story completion/commit), the Recommendation / Reason / Alternative / Trade-off format, tests for every behaviour change, the post-task report, and AI usage log entries.
- Never approve on the engineer's behalf.

## Tech stack

Java 25 · Spring Boot 3.5.x · Spring Web · Spring Data JPA · Spring Security · PostgreSQL · Flyway · Bean Validation · Lombok · springdoc-openapi · JUnit 5 · Mockito · Testcontainers · Cucumber · JaCoCo · Docker Compose · Maven Wrapper

Base package: `com.schwab.urlshortener`. `JAVA_HOME` must point to JDK 25.

## Commands

- Build and all tests: `./mvnw -q verify`
- Run the full stack (app + PostgreSQL): `docker compose up -d --build` (needs a `.env` copied from `.env.example`)
- Or start only the database (`docker compose up -d postgres`) and run the app with `./mvnw spring-boot:run -Dspring-boot.run.profiles=local`

## Code conventions

**Structure**
- Layers: `controller` (controllers, error handling) → `service` → `repository`. Entities never leave the service layer. `entity` holds only classes persisted to the database; everything else the system passes around (commands, views, DTOs) lives in `model` and `model.dto`. Supporting packages (`analytics`, `shortcode`, `validation`, `link`, `web`) live under `util` (D132, D133).
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
- Every database constraint-violation test (raw SQL or JPA) asserts both the SQLSTATE and the constraint name via `PostgresErrors`; asserting only the exception type is not enough.
- Every "column is not written" / "value is not overwritten" test also asserts that the write actually happened (a version bump or a row count), so it cannot pass vacuously.
- Code comments, Javadoc, test comments, and SQL migrations cite durable decision IDs (`Dnn`) — never design-gate IDs (`E1`, `G2-…`) or design-note section numbers (`§x.y`). This includes `src/main/resources/db/migration`.
- Application-layer validation must reject values that exceed a length-limited `VARCHAR` column before insert; PostgreSQL silently truncates trailing spaces instead of raising an error.
- Length-limit tests cover the limit, the limit plus one, and the limit plus a trailing space, and read the stored value back.
- Character limits use `char_length` (characters, not bytes), pinned by a multibyte test.
- Security-sensitive dependencies (random sources, password encoders, key material) are injected by their concrete secure type or built inside the consuming bean method — never published as beans of a broad type (e.g. `RandomGenerator`) that a later `@Primary` bean could silently replace.
- Startup-failure tests (`ApplicationContextRunner` or full context) assert the specific exception type in the cause chain and the exact property/field name — never only `hasFailed()`.
- When a domain bound is checked in more than one application layer, all checks reference one shared constant, not repeated literals.
- Public constructors and factory methods that validate their arguments document each thrown exception with `@throws`.
- Configuration-binding failure tests assert the exception type and the property/field name, and each negative test has a positive counterpart using the same property-source mechanism, so a key that never binds cannot pass silently.
- Tests that bind a property from an environment-style key add it through a `SystemEnvironmentPropertySource` and assert the bound (or rejected) value; `withPropertyValues("UPPER_SNAKE=...")` never maps to the dotted property.
- When security depends on a library's strictness (e.g. URL parsing), known bypass inputs are pinned as explicit regression tests.
- Every case conversion, in production code and tests, uses `Locale.ROOT`.
- A `SystemEnvironmentPropertySource` used in a test must be named `systemEnvironment` (or end with `-systemEnvironment`); any other name bypasses Spring Boot's `SystemEnvironmentPropertyMapper`, so the test does not reflect production binding.
- Code and test comments don't cite review finding IDs (`Rn`, `Nn`); describe the behaviour or cite a `Dnn`.
- A negative validation test's input differs from a known-valid value in exactly the property under test (derive it from a real valid value), so it cannot be rejected for an unrelated reason.
- Every "X is not logged" test also asserts that the expected log line was captured, so it cannot pass vacuously.
- Behaviour implemented by a wrapper (e.g. credential erasure in `ProviderManager`) is tested through that wrapper, not the inner component.
- Security tests never catch a broad `Exception`; they catch the specific expected type or none.
- Every role-restricting URL rule has tests for its trailing-slash, nested-path, and case variants against a probe route mapped at that variant; the rule's pattern covers the variants (`/**`) or a method-wide deny rule follows it.
- Every new endpoint in a story names the `SecurityConfig` filter-chain rule that admits it. With `denyAll` as the default (D57), an unlisted path is refused even to ADMIN.
- Every 5xx produced by the exception advice is logged at ERROR exactly once with the exception — including framework exceptions routed through `handleExceptionInternal`, not only the `Exception` catch-all.
- Tests truncate shared tables only in per-test/per-scenario setup, never rely on rows created by another class, and assume serial execution. Enabling parallel test execution requires revisiting truncation and the short-code generator seam together.
- HTTP tests that forge a restricted header (e.g. `Host`) include a positive control proving the header reached the server.
- Test code does not use inline fully qualified class names (e.g. `org.mockito.ArgumentMatchers.eq`, `java.util.ArrayList`); use imports or static imports.
- A HEAD test expecting a 404 has no body to assert `errorCode` on, so it includes a 200 on the same path in the same test as proof the mapping was reached.
- Test-support lookups that turn a Gherkin or test-data word into a user, owner, or other database value throw on unknown values (no default branch), with one lookup per concept and a single case rule.
- Header budget: any value written into a response header from stored or user data has a proven maximum encoded size below `server.max-http-response-header-size`, shown by a test at the D11 length limit with the worst-case encoding.
- A stored value echoed into a response header needs a create-time byte limit on its exact wire form, tested at the boundary through the real servlet container — a mocked slice is not enough.
- A table of recorded statuses that includes 404 rows also asserts each 404's `errorCode` (via GET for HEAD rows). Multi-status assertions (`isIn(...)`) are allowed only while recording and are replaced by the observed value before review.
- A test whose name claims to distinguish two behaviours (e.g. code points vs UTF-16 units) includes an input on which they differ; if a later rule makes the difference unobservable, rename or remove the test and record it.
- Any change to a Done story's tests is listed test by test (updated, renamed, removed, added) in that story's post-completion section.
- Every review-finding ID raised in a round has a row in the story's Review log with its resolution, including those the orchestrator fixes.
- Review-finding labels (`Rn`, `Nn`) are never cited in code, tests, or SQL.
- Every edit to a Done story's tests is listed by exact method name in the owning story, with a pointer from any other affected story.
- A test that relies on a database session setting asserts it (e.g. with `SHOW`) before relying on it.
- Error types that must never echo client input name their fields with an enum, not free-form strings.
- Build counts quoted in story notes state which review round (or final build) they come from.
- Every `@WebMvcTest` imports `SecuritySliceTestConfiguration`, so it gets the real security filter chain and production Jackson settings (D89); slices never import `SecurityConfig`, `UserAccountsConfig`, or `JacksonConfig` individually.
- A "value is never logged" assertion checks every form the value takes on the way out: raw and encoded (e.g. D75 percent-encoding).
- Test fixtures that seed prior state (transitions, audit fields) use a timestamp and actor different from those the code under test will write, so "unchanged" assertions can fail.
- Test code that creates shared database objects (triggers, functions, roles) defines the DDL and its cleanup once, in shared test support; every user calls that single definition.
- Comments and Javadoc don't use design-note test labels (`R1`, `§9.2`-style) as identifiers — describe the scenario and cite `Dnn` (extends the durable-ID rule).
- A runtime test that claims to prove a specific annotation flag (e.g. `flushAutomatically`) is shown to fail without that flag, or is named for the behaviour it actually proves.
- Every reflection-based "no annotation X" guard includes a positive control proving its detector returns true for a directly annotated sample and for a composed-annotation sample.

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
- Commits were made by the orchestrator through US-011; from US-012 onward the main session commits, still only after the engineer approves.

## Key documents

`docs/requirements.md` (all decisions, D1 onward) · `docs/architecture.md` · `docs/stories/` · `docs/scenarios.md` · `docs/ai-usage-log.md`
