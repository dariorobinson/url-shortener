---
id: US-005
title: Security foundation (USER/ADMIN, HTTP Basic)
status: Done
plan_task: 5
depends_on: [US-001]
requirements: [FR-13, D3, D30, D31, D32, D50, D51, D52, D53, D54, D55, D56, D57]
requires_design_approval: true
---

# US-005: Security foundation (USER/ADMIN, HTTP Basic)

## User story
As an engineer, I want stateless HTTP Basic authentication with `USER`/`ADMIN` roles (`ADMIN` inheriting `USER`), so that later API stories can declare access rules and get consistent 401/403 `ProblemDetail` responses.

## Acceptance criteria
- **AC1:** Given a request to a secured path with no credentials, when it is processed, then the response is `401` with a `ProblemDetail` body containing `errorCode: "AUTHENTICATION_REQUIRED"` (D31) and a `WWW-Authenticate: Basic` header, produced by a custom `AuthenticationEntryPoint` (D30).
- **AC2:** Given a request with valid HTTP Basic credentials for a configured user, when it is processed, then Spring Security authenticates the principal and populates its granted role(s) (`USER` or `ADMIN`).
- **AC3:** Given the role hierarchy `ADMIN > USER`, when an endpoint is restricted to `hasRole('USER')`, then a principal with role `ADMIN` also passes the check.
- **AC4:** Given a request that fails an authorization check (authenticated but insufficient role), when it is processed, then the response is `403` with a `ProblemDetail` body containing `errorCode: "ACCESS_DENIED"` (D31), produced by a custom `AccessDeniedHandler` (D30).
- **AC5:** Given the public path patterns (`GET /{code}` and `HEAD /{code}` as a single path segment — D32, `/actuator/health`, OpenAPI/Swagger paths), when accessed with no credentials, then the security filter chain permits the request through to its handler (no 401).
- **AC6:** Given users are configured via BCrypt-hashed passwords sourced from environment variables (never plaintext), when the application starts, then the `UserDetailsService`/`PasswordEncoder` combination authenticates using `BCryptPasswordEncoder.matches`, not a plaintext comparison.
- **AC7:** Given the API is stateless and uses no cookies, when a request is processed, then no session is created (`SessionCreationPolicy.STATELESS`) and CSRF protection is disabled.
- **AC8:** Given both the 401 (AC1) and 403 (AC4) responses, when their bodies are inspected, then both use the shared `ProblemDetail` base shape used by every error response in the API: `type` is `about:blank`, `detail` is a generic, non-leaking message, and `errorCode` is the only extension on these security errors (401/403) and the only field that differs between them (D30). Other error types, for example the validation errors in US-006, may add documented extensions (such as a field-level errors list) on top of the same base shape; the "only extension" rule applies to security errors only. This proves the security error responses are not a special case.
- **AC9:** Given the full `ErrorCode` catalogue is defined in this story (D31), when the enum is inspected, then it contains exactly: `VALIDATION_FAILED`, `MALFORMED_REQUEST`, `INVALID_URL`, `INVALID_ALIAS`, `ALIAS_ALREADY_EXISTS`, `SHORT_URL_NOT_FOUND`, `SHORT_URL_ALREADY_DEACTIVATED`, `SHORT_URL_ALREADY_ACTIVE`, `CONCURRENT_MODIFICATION`, `SHORT_CODE_UNAVAILABLE`, `AUTHENTICATION_REQUIRED`, `ACCESS_DENIED`, `INTERNAL_ERROR` — every later story reuses these values verbatim rather than inventing new ones.

## Tests required
| Type | Behaviour proven | Owner |
|---|---|---|
| Web slice | 401 with `AUTHENTICATION_REQUIRED` errorCode and `WWW-Authenticate: Basic` for missing credentials on a secured test endpoint (AC1) | mid-engineer |
| Web slice | 403 with `ACCESS_DENIED` errorCode when role is insufficient (AC4) | mid-engineer |
| Unit | Role hierarchy: `ADMIN` satisfies a `USER`-only rule (AC3) | mid-engineer |
| Web slice | Public paths (health, OpenAPI, single-segment redirect pattern for both `GET` and `HEAD`) are reachable unauthenticated (AC5) | mid-engineer |
| Unit | Password matching goes through `BCryptPasswordEncoder`, not plaintext (AC6) | mid-engineer |
| Web slice | No `Set-Cookie`/session is created on a successful authenticated request (AC7) | mid-engineer |
| Unit | Both 401 and 403 bodies match the shared `ProblemDetail` base shape (`type: about:blank`, generic `detail`, `errorCode` as their only extension, and the only field that differs between them); the test does not assert that other error types lack extensions (AC8) | mid-engineer |
| Unit | `ErrorCode` enum contains exactly the D31 catalogue, no more, no fewer (AC9) | mid-engineer |
| Cucumber | `GET /actuator/health` and `GET /v3/api-docs` are reachable with no credentials (AC5), proven end-to-end against the running application | qa-tester |

Since no business endpoint requiring authentication/authorization exists yet at this point in the plan, AC1–AC4, AC6, AC7, and AC8 are proved end-to-end by Cucumber starting in US-006 (the first story with a real secured endpoint), not here — here they are proved by web-slice tests against a minimal test-only secured controller, per the note below. AC5 and AC9 are the only ACs testable end-to-end in this story: AC5 because `/actuator/health` and `/v3/api-docs` are already real, public endpoints; AC9 is a catalogue check, not an HTTP behaviour, so it is unit-tested only.

Web-slice tests use a minimal test-only secured controller or direct `SecurityFilterChain`/`MockMvc` configuration to exercise the rules; US-006 onward re-verifies the same rules against real endpoints with Cucumber.

## Out of scope
- The actual list of configured users/passwords for a real deployment (env-var driven; `.env.example` already exists at the repo root and should be kept in sync, but populating real secrets is a deployment concern, not this story).
- Business endpoints — see US-006 onward.

## Risks
- HTTP Basic sends credentials on every request; this is acceptable only behind TLS. This project runs over plain HTTP in local Docker Compose — already flagged as a trade-off in `docs/architecture.md`, not a new risk, but worth restating here since this story is where it becomes concrete.

## Open questions
- None. D30 fixes the security error-handler shapes and `errorCode`s, D31 fixes the full `ErrorCode` catalogue (defined once, here), and D32 fixes `HEAD /{code}` as public.

## Carry-over (engineer-approved at C2b G4)
- **From US-004, R11 (SHOULD):** `AppPropertiesTest`'s env-binding tests name their `SystemEnvironmentPropertySource` `env`/`env2`, which bypasses Spring Boot's `SystemEnvironmentPropertyMapper`.
  - Rename the sources to `systemEnvironment`, or a name ending in `-systemEnvironment`, per CLAUDE.md.
  - Make **both** env-var forms the tested contract, each with a positive binding test and an invalid-value test:
    - `APP_BASE_URL` and `APP_BASEURL`.
    - `SHORTENER_ALIAS_ADDITIONAL_RESERVED_WORDS` and `SHORTENER_ALIAS_ADDITIONALRESERVEDWORDS`.
  - Correct the false comment in `AppPropertiesTest` (around line 110) that says the dash-removed form does not bind.
- **From US-004, R12 (SHOULD):** add `@throws` Javadoc to the public `UrlValidator` constructor (NPE for null, IAE for an invalid base URL).
- **From US-004, R14 (NIT):** test comments citing review IDs (`R3`, `R8`) must describe the behaviour or cite a `Dnn`.
- **From US-004, R15 (NIT):** re-wrap the overlong class Javadoc line in `AliasPolicy`.
- **From US-002 (open question):** `created_by` and `deleted_by` are `VARCHAR(100)`, which silently truncates trailing spaces (the D47 rationale). Their values come from the authenticated principal, which US-005 introduces. The design must decide or escalate: bound the username length at the user-configuration layer, or change the columns in a forward migration.

## Design note

*Architect, 2026-09-29. Status: **approved at G2 (2026-09-29)**. The engineer approved S1–S6, Q2–Q5 and the rest of the note as written, recorded as D50–D56. **Amendment (engineer-approved at US-005 G3 and the escalation):** in the filter chain, the ADMIN rule is `DELETE /api/v1/urls/**` (R15 option 1), and the final rule is `anyRequest().denyAll()` (D57), replacing `anyRequest().authenticated()`. The §3 snippet below predates both changes. **Alignment note (orchestrator, applying the approved Q4/D56):** where §7.2 says every later error must match the fixed shape or have "the same key set", read it as the same *base* keys (`type`, `title`, `status`, `detail`, `instance`, `errorCode`) plus documented extensions for non-security errors. Security errors (401/403) have `errorCode` as their only extension.*

*Original status line: The gate IDs below (S1–S6) are for this note only. Once the engineer approves, the orchestrator records the outcomes as D50 onward. Code, tests and SQL must cite those `Dnn` IDs, never `S1`–`S6` or section numbers (CLAUDE.md review rule).*

### 0. Engineer decisions required at G2

| # | Decision | Recommendation | Blocking? |
|---|---|---|---|
| **S1** | Shape of the user configuration (not in requirements; D3/D24 only say "env vars, BCrypt, USER/ADMIN"). | A **list** `app.security.users[n].{username, password-hash, role}` (§5.1). | **Yes.** It fixes the env-var names that US-014/US-015 document. |
| **S2** | `created_by`/`deleted_by` are `VARCHAR(100)` (US-002 carry-over). | **Option A:** bound the username at the configuration layer, and add an actor guard in `ShortUrl`. Both use one shared constant. No migration (§8). | **Yes.** Option B adds a migration to this story. |
| **S3** | Should 401/403 logs include the attempted or authenticated username? | **No username and no client IP** in the application log for now (§7.6). | No |
| **S4** | BCrypt cost. | **Fixed at 10**, and every configured hash must have cost 10 (§5.4). | No |
| **S5** | What the R11 "invalid-value test" means for `additional-reserved-words`, which has no invalid values. | Use a "binds but cannot remove a built-in" test for each env-var form, plus the existing wrong-key test (§9.1). | No |
| **S6** | Strip the documented `.env` variable names from the forked test JVMs. | Yes: add them to Surefire/Failsafe `excludedEnvironmentVariables` (§5.6). | No |

### 1. Dependencies and US-001 AC5

| Scope | Artifact | Version |
|---|---|---|
| compile | `org.springframework.boot:spring-boot-starter-security` | Boot-managed, Spring Security **6.5.11** (D22) |
| test | `org.springframework.security:spring-security-test` | Boot-managed (Spring Security BOM), 6.5.11 |

Nothing else is added. No OAuth2, and no `spring-boot-docker-compose`.

**Why US-001 AC5 stays green.** D-R1 (US-001) kept Security out because Boot's *default* chain would secure `/v3/api-docs`. That default chain (`SpringBootWebSecurityConfiguration`) is conditional on there being **no** `SecurityFilterChain` bean, and this story defines one. Its permit list (§3) contains `GET /v3/api-docs`, `/v3/api-docs/**`, `/v3/api-docs.yaml`, `/swagger-ui.html`, `/swagger-ui/**` and `GET /actuator/health`. So `HealthEndpointIT`, `OpenApiDocsIT` and the two `smoke.feature` scenarios should pass **unchanged**. QA must confirm that they do, without editing them (§10.2). The other ITs (`FlywaySchemaHistoryIT`, `TestProfileDatasourceIT`, `SharedTestEnvironmentIT`, the wiring ITs) do no HTTP, but they need the context to start. After this story the context fails to start unless users are configured, so `application-test.yml` **must** carry the test users (§5.5).

### 2. Files

| Path | Owner | Notes |
|---|---|---|
| `pom.xml` | mid-engineer | §1 dependencies; §5.6 env exclusions |
| `security/SecurityConfig.java` | mid-engineer | Filter chain and `RoleHierarchy` (§3, §6) |
| `security/UserAccountsConfig.java` | mid-engineer | `@EnableConfigurationProperties(UserAccountsProperties.class)`; the `DaoAuthenticationProvider` bean (§5.3) |
| `security/UserAccountsProperties.java` | mid-engineer | `@ConfigurationProperties("app.security")` record (§5.1) |
| `security/UserAccounts.java` | mid-engineer | Pure: hash and uniqueness checks, then `UserDetails` creation (§5.2) |
| `security/Role.java` | mid-engineer | `enum Role { USER, ADMIN }` |
| `security/ProblemDetailAuthenticationEntryPoint.java` | mid-engineer | 401 (§7.3) |
| `security/ProblemDetailAccessDeniedHandler.java` | mid-engineer | 403 (§7.4) |
| `security/ProblemDetailResponseWriter.java` | mid-engineer | Writes a `ProblemDetail` with the context `ObjectMapper` (§7.5) |
| `api/error/ErrorCode.java` | mid-engineer | Full D31 catalogue (§7.1) |
| `api/error/ProblemDetails.java` | mid-engineer | Shared factory; US-006's `@RestControllerAdvice` reuses it (§7.2) |
| `domain/ShortUrl.java` | mid-engineer | Both S2 options: `public static final int MAX_ACTOR_LENGTH = 100`, used by `@Column(length = …)` on `created_by`/`deleted_by` and by `UserAccount.username` `@Size`. S2 = A only: the actor guard (§8) |
| `src/test/resources/application-test.yml` | mid-engineer | Test users (§5.5) |
| `.env.example` | mid-engineer | §5.5 |
| `src/test/java/.../support/TestUsers.java` | mid-engineer | Plaintext test passwords, shared with QA (§5.5) |
| Carry-over files | mid-engineer | `AppPropertiesTest`, `UrlValidator`, `UrlValidatorTest`, `AliasPolicy` (§9) |
| `*IT.java`, `features/security.feature` | qa-tester | §10.2 |

`application.yml` and `application-local.yml` gain **no** users. Per D24 there are no defaults: `local` users come from the exported `.env`.

### 3. SecurityFilterChain

The whole chain goes in one bean method. The entry point, access-denied handler and writer are built **inside** the method, so they are not beans. `@WebMvcTest` then needs only `@Import({SecurityConfig.class, UserAccountsConfig.class})`. `@EnableWebSecurity` isn't needed, because Boot's `WebSecurityEnablerConfiguration` supplies it.

```java
@Configuration(proxyBeanMethods = false)
class SecurityConfig {

    static final String REALM = "url-shortener";

    @Bean
    static RoleHierarchy roleHierarchy() {                       // §6
        return RoleHierarchyImpl.withDefaultRolePrefix()
                .role(Role.ADMIN.name()).implies(Role.USER.name())
                .build();
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
            DaoAuthenticationProvider authenticationProvider,   // concrete type (§5.3)
            ObjectMapper objectMapper) throws Exception {
        var writer = new ProblemDetailResponseWriter(objectMapper);
        var entryPoint = new ProblemDetailAuthenticationEntryPoint(writer);
        var accessDenied = new ProblemDetailAccessDeniedHandler(writer);
        http
            .authenticationManager(new ProviderManager(authenticationProvider))
            .httpBasic(basic -> basic.authenticationEntryPoint(entryPoint))   // do NOT also call realmName()
            .exceptionHandling(ex -> ex.authenticationEntryPoint(entryPoint).accessDeniedHandler(accessDenied))
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .requestCache(c -> c.requestCache(new NullRequestCache()))
            .csrf(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable)
            .logout(AbstractHttpConfigurer::disable)
            .authorizeHttpRequests(auth -> auth
                // 1. Error rendering after an already-authorized request (see below)
                .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                // 2. Public infrastructure (AC5)
                .requestMatchers(HttpMethod.GET, "/actuator/health").permitAll()
                .requestMatchers(HttpMethod.GET, "/v3/api-docs", "/v3/api-docs/**", "/v3/api-docs.yaml",
                        "/swagger-ui.html", "/swagger-ui/**").permitAll()
                // 3. Reserved prefixes, declared BEFORE the public single-segment rule so that
                //    /actuator and /api are never treated as a short code
                .requestMatchers("/actuator", "/actuator/**").authenticated()
                .requestMatchers(HttpMethod.DELETE, "/api/v1/urls/*").hasRole(Role.ADMIN.name())   // D3
                .requestMatchers("/api", "/api/**").hasRole(Role.USER.name())
                // 4. Public redirect: GET and HEAD on any single path segment (D32)
                .requestMatchers(HttpMethod.GET, "/*").permitAll()
                .requestMatchers(HttpMethod.HEAD, "/*").permitAll()
                // 5. Deny by default
                .anyRequest().authenticated());
        return http.build();
    }
}
```

Notes on the configuration:
- **First match wins.** The Spring Security 6.5 reference says `AuthorizationFilter` "processes these pairs in the order listed, applying only the first match". The order above therefore matters, and a test pins each boundary (§10.1).
- **`/api` and `/actuator` are listed alongside `/**`**, so the design doesn't depend on whether `/**` matches the bare prefix.
- **Matcher type.** With Spring MVC on the classpath, `requestMatchers(String…)` builds an `MvcRequestMatcher` (6.5 reference). That matcher uses the same path matching as MVC routing, which removes the classic mismatch between a security matcher and a handler mapping. Spring Security 7 switches to `PathPatternRequestMatcher`. The pinned bypass tests (§10) are what would catch a difference at that upgrade.
- **`realmName()` is not called.** `HttpBasicConfigurer` documents that `realmName()` conflicts with a custom entry point. The realm is written by our entry point (§7.3).
- **The entry point is set in two places.** `httpBasic` registers its entry point only as a *default* for a "non-HTML" request matcher. Setting it on `exceptionHandling` as well makes it the entry point for every request, including browser `Accept: text/html` requests.
- **Stateless.** In 6.5.11, `SessionManagementConfigurer.init()` with `STATELESS` already sets `RequestAttributeSecurityContextRepository` and `NullRequestCache`. The explicit `NullRequestCache` stays anyway, so that a later edit to the session policy cannot silently bring back `HttpSessionRequestCache`. That cache creates a session on every 401.
- **Headers stay at Spring defaults.** This gives HSTS over HTTPS only, `max-age` of one year, and `includeSubDomains` (D37). It also adds `X-Content-Type-Options`, `X-Frame-Options: DENY` and the cache-control headers. See risk K9 about `Cache-Control` for US-008.
- **Anonymous authentication stays enabled** (the default). An anonymous request to a secured path raises `AccessDeniedException`, which `ExceptionTranslationFilter` turns into our entry point (401), not our access-denied handler.

#### 3.1 Why the ERROR dispatch is permitted

Boot 3.5 registers the security filter for **all** dispatcher types (`SecurityProperties.Filter.dispatcherTypes = EnumSet.allOf(DispatcherType.class)`). The 6.5 reference also says `AuthorizationFilter` "runs not just on every request, but on every dispatch". Its own example is `.dispatcherTypeMatchers(DispatcherType.FORWARD, DispatcherType.ERROR).permitAll()`.

An ERROR dispatch only happens after the original REQUEST dispatch has passed authorization. Our entry point and handler write the response directly and never call `sendError`, so a rejected request never reaches `/error`. Re-authorizing `/error` adds no protection. It does risk turning an authenticated caller's 404 or 500 into a 401 if the security context isn't restored on the error dispatch. FORWARD is **not** permitted, because nothing forwards.

#### 3.2 Public single-segment rule: any segment, not the code regex

```
Recommendation: permit GET and HEAD on "/*" (any single path segment), with /api and /actuator matched earlier.
Reason:         US-008 AC6 requires a malformed code (e.g. GET /ab, GET /a_b) to return 404 without a DB
                round trip. A security matcher constrained to [A-Za-z0-9]{3,32} would answer 401 to an
                anonymous visitor for those paths. That contradicts US-008 AC6, and it would make the
                public redirect answer 401 or 404 depending on the code's shape. The code-format check
                belongs to the routing layer (US-008's {code:[A-Za-z0-9]{3,32}} mapping) and to the DB
                (ck_short_url_code_format), not to security.
Alternative:    regexMatcher(GET, "^/[A-Za-z0-9]{3,32}$").permitAll().
Trade-off:      any future single-segment GET handler (e.g. a hypothetical GET /admin) would be public by
                default. Mitigation: architecture rule "no single-segment handler other than the redirect".
                The D29/D48 reserved words cover the obvious names. QA's routing-precedence test in US-008
                (AC5) also enumerates the single-segment GET mappings.
```

#### 3.3 What each class of request returns (after this story)

| Request | Anonymous | Authenticated `USER` | Authenticated `ADMIN` |
|---|---|---|---|
| `GET /actuator/health` | 200 `{"status":"UP"}` | 200 | 200 |
| `GET /v3/api-docs`, `/v3/api-docs/swagger-config`, `/v3/api-docs.yaml`, `/swagger-ui/index.html` | 200 | 200 | 200 |
| `GET /swagger-ui.html` | 302 → `/swagger-ui/index.html` (springdoc) | same | same |
| `GET` or `HEAD /{anything-single-segment}` (e.g. `/abc1234`, `/ab`, `/v3`) | 404 until US-008 (Boot error JSON, see §10.2) | 404 | 404 |
| `GET /api`, `/api/**` | **401** `AUTHENTICATION_REQUIRED` | passes (404 until US-006) | passes (hierarchy) |
| `DELETE /api/v1/urls/{x}` | 401 | **403** `ACCESS_DENIED`, before any handler or lookup | passes (404/405 until US-009) |
| `GET /actuator`, `/actuator/env` | 401 | 404 (not exposed) | 404 |
| `POST /abc1234`, `GET /a/b`, any other path | 401 | **403** `ACCESS_DENIED` (D57, `denyAll`) | **403** `ACCESS_DENIED` (D57) |
| Any request carrying **invalid** Basic credentials, even to a public path | 401 (`BasicAuthenticationFilter` fails the request whenever the header is present) | — | — |
| Paths the `StrictHttpFirewall` rejects (`;`, `//`, `%2F`, `%2e`, `\`, …) | 400 (Spring default, empty body; not ProblemDetail) | 400 | 400 |

**Does an unknown path reveal anything?** No. Every non-public path answers 401 to an anonymous caller, whether or not a handler exists. Every single-segment path answers 404 whatever its shape. An authenticated caller sees 404 for unknown paths under an explicit rule (`/api/**`, `/actuator/**`). Any path no explicit rule matches gets 403 `ACCESS_DENIED` (D57).

#### 3.4 DELETE rule and method security

```
Recommendation: declare DELETE /api/v1/urls/* -> hasRole(ADMIN) in this story's URL rules. Use NO method security
                (no @EnableMethodSecurity).
Reason:         D3 and the architecture access table fix the rule. Enforcing it in the filter chain makes US-009's
                "403 before lookup" (AC6, AC8) true by construction: the request never reaches a controller, so no
                repository call can happen. One place (SecurityConfig) holds every role rule. Ownership (D4) is a
                data-level rule and belongs in the service, not in @PreAuthorize.
Alternative:    @EnableMethodSecurity with @PreAuthorize("hasRole('ADMIN')") on US-009's delete method.
Trade-off:      URL rules are coarser than annotations. But an AccessDeniedException thrown from a controller or
                service method passes through MVC, where US-006's @RestControllerAdvice catch-all would turn it
                into a 500 unless the advice rethrows it: a classic pitfall. If method security is added later, the
                RoleHierarchy bean (§6) must also be applied to it.
```

### 4. Public paths verified against springdoc 2.8.17

From `org.springdoc.core.utils.Constants` at tag `v2.8.17`:
- `DEFAULT_API_DOCS_URL = "/v3/api-docs"`
- `DEFAULT_API_DOCS_URL_YAML` = `/v3/api-docs.yaml`
- `SWAGGER_CONFIG_URL` = `/v3/api-docs/swagger-config`
- `DEFAULT_SWAGGER_UI_PATH = "/swagger-ui.html"`
- `SWAGGER_UI_PREFIX` = `/swagger-ui`; `SWAGGER_UI_URL` = `/swagger-ui/index.html`
- `SWAGGER_INITIALIZER_URL` = `/swagger-ui/swagger-initializer.js`

The springdoc properties page confirms the defaults for `springdoc.api-docs.path`, `springdoc.swagger-ui.path` and `configUrl`. `/v3/api-docs.yaml` does **not** match `/v3/api-docs/**`, so it is listed explicitly. Only `GET` is permitted. Nothing in `application*.yml` changes these paths. If `springdoc.*.path` is ever changed, the permit list must change with it (risk K7).

### 5. Users and credentials (D3, D24)

#### 5.1 Configuration shape (S1)

```java
@Validated
@ConfigurationProperties(prefix = "app.security")
public record UserAccountsProperties(@NotEmpty List<@Valid UserAccount> users) {

    public record UserAccount(
            @NotNull @Size(min = 1, max = ShortUrl.MAX_ACTOR_LENGTH) @Pattern(regexp = USERNAME_PATTERN) String username,
            @NotBlank String passwordHash,   // format checked in UserAccounts, never by Bean Validation (§5.2)
            @NotNull Role role) {

        public static final String USERNAME_PATTERN = "^[a-z0-9][a-z0-9._-]*$";

        @Override
        public String toString() {           // never render the hash
            return "UserAccount[username=" + username + ", role=" + role + ", passwordHash=<redacted>]";
        }
    }
}
```

| Property | Env var: canonical | Env var: legacy (dashes become `_`) |
|---|---|---|
| `app.security.users[n].username` | `APP_SECURITY_USERS_<n>_USERNAME` | same (no dash) |
| `app.security.users[n].password-hash` | `APP_SECURITY_USERS_<n>_PASSWORDHASH` | `APP_SECURITY_USERS_<n>_PASSWORD_HASH` |
| `app.security.users[n].role` | `APP_SECURITY_USERS_<n>_ROLE` | same |

Numeric env-name elements map to list indexes (`SystemEnvironmentPropertyMapper`: "`HOST_0` is mapped to `host[0]`"). Both forms of `password-hash` are the tested contract (§9.1). When both are set, the **canonical** form wins, because `SystemEnvironmentPropertyMapper.map` returns the canonical name first. Operators should set only one. A list is replaced as a whole by the highest-precedence source that defines it; env entries never merge with YAML entries.

Validation rules and where each one runs:

| Rule | Where | Failure |
|---|---|---|
| `users` present and non-empty | Bean Validation | `BindValidationException`, field `users` |
| `username`: 1 to 100 chars, `^[a-z0-9][a-z0-9._-]*$` (lowercase ASCII; no whitespace, `:` or `@`) | Bean Validation | field `users[n].username` |
| `role` present and `USER` or `ADMIN` | binder / Bean Validation | `users[n].role` (`@NotNull`), or a `BindException` on `app.security.users[n].role` for an unknown value (`ConversionFailedException` cause) |
| `password-hash` present | Bean Validation (`@NotBlank`) | field `users[n].passwordHash` |
| `password-hash` is a BCrypt hash with cost exactly 10 | `UserAccounts` (programmatic) | `IllegalArgumentException` naming `app.security.users[n].password-hash`, **never the value** |
| usernames unique | `UserAccounts` (programmatic) | `IllegalArgumentException` naming `app.security.users[n].username` and the index it duplicates |

Why the pattern is this strict:
- **`:`** can never log in over HTTP Basic, because the credentials split on the first colon.
- **Whitespace** could reach `created_by` and meet the VARCHAR truncation edge (§8).
- **Lowercase only**: `InMemoryUserDetailsManager` keys users by `username.toLowerCase(Locale.ROOT)` (verified in 6.5.11). With lowercase-only names, stored keys equal configured names and uniqueness is plain equality. Note that login is **case-insensitive**: `ADMIN` authenticates as `admin`. The principal name is always the configured spelling, because `loadUserByUsername` returns `new User(user.getUsername(), …)` from the stored entry. So `created_by` is stable (open question Q2).

#### 5.2 Hash validation without leaking the value

`@Pattern` on `passwordHash` is deliberately **not** used. Boot's `BindValidationFailureAnalyzer` prints `Value: "<rejected value>"` for every field error, and `BindFailureAnalyzer` does the same for conversion errors. An operator who pastes a **plaintext password** into `…_PASSWORD_HASH` would therefore have it written to the startup log. The hash format is instead checked by `UserAccounts` after binding:

```java
final class UserAccounts {
    static final int BCRYPT_STRENGTH = 10;
    /** $2a$, $2b$ or $2y$, cost exactly BCRYPT_STRENGTH, 53 salt and hash chars. No {bcrypt} prefix. */
    static final Pattern BCRYPT_HASH =
            Pattern.compile("\\A\\$2[aby]\\$" + BCRYPT_STRENGTH + "\\$[./0-9A-Za-z]{53}\\z");

    /**
     * @throws IllegalArgumentException if a hash is not a cost-10 BCrypt hash, or two usernames are equal;
     *         the message names the property path (e.g. app.security.users[1].password-hash), never the value
     */
    static List<UserDetails> toUserDetails(List<UserAccountsProperties.UserAccount> accounts) { … }
}
```

`toUserDetails` builds `User.withUsername(u).password(hash).roles(role.name()).build()`, which gives authorities `ROLE_USER` or `ROLE_ADMIN`. The regex is stricter than `BCryptPasswordEncoder.BCRYPT_PATTERN` (`\A\$2(a|y|b)?\$(\d\d)\$[./0-9A-Za-z]{53}`, verified in 6.5.11): the minor version is required and the cost is fixed. It must be checked at startup, because `matches()` on a malformed hash only logs "Encoded password does not look like BCrypt" and returns `false`. Without the startup check, a bad hash gives a user who silently can never log in.

#### 5.3 PasswordEncoder, UserDetailsService and the security-sensitive-bean rule

Verified in the 6.5.11 and Boot 3.5.16 sources:
- `InitializeUserDetailsBeanManagerConfigurer` finds a `UserDetailsService` **bean** and then looks up `PasswordEncoder` with `getBeanProvider(type).getIfUnique()`. A later `@Primary PasswordEncoder` would silently win that lookup. This is exactly the hazard the CLAUDE.md rule describes. So **no `PasswordEncoder` bean and no `UserDetailsService` bean are published.**
- `DaoAuthenticationProvider` defaults to `PasswordEncoderFactories.createDelegatingPasswordEncoder()` unless `setPasswordEncoder` is called. The `DaoAuthenticationProvider()` and `DaoAuthenticationProvider(PasswordEncoder)` constructors are `@Deprecated` in 6.5. `DaoAuthenticationProvider(UserDetailsService)` is the supported one.
- Boot's `UserDetailsServiceAutoConfiguration` (which creates the `user` account with a generated password and logs "Using generated security password…") is `@ConditionalOnMissingBean(AuthenticationManager, AuthenticationProvider, UserDetailsService, AuthenticationManagerResolver, JwtDecoder)`. **Publishing one `AuthenticationProvider` bean turns it off.** No `exclude` is needed; slice tests may not honour an exclude anyway.
- `InitializeAuthenticationProviderBeanManagerConfigurer` registers exactly one `AuthenticationProvider` bean with the *global* `AuthenticationManager`. With two or more beans it registers none. So the global manager, if anything ever builds it, uses the **same** provider. There is no second authentication path.

```java
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(UserAccountsProperties.class)
class UserAccountsConfig {

    /** The encoder and user store are built here and never published as beans (CLAUDE.md security-sensitive-bean rule). */
    @Bean
    DaoAuthenticationProvider authenticationProvider(UserAccountsProperties properties) {
        var users = new InMemoryUserDetailsManager(UserAccounts.toUserDetails(properties.users()));
        var provider = new DaoAuthenticationProvider(users);
        provider.setPasswordEncoder(new BCryptPasswordEncoder(UserAccounts.BCRYPT_STRENGTH));
        return provider;
    }
}
```

The bean is published by its **concrete** type, and `SecurityConfig` injects it by that type. The encoder is reachable only inside the provider. No `UserDetailsPasswordService` is set, so `upgradeEncoding` never rewrites hashes. No `CompromisedPasswordChecker` is set. `ProviderManager` keeps `eraseCredentialsAfterAuthentication = true`. That is safe: `InMemoryUserDetailsManager` wraps users in `MutableUser`, which is **not** a `CredentialsContainer`, so `loadUserByUsername` returns a fresh `User` copy and erasing it never clears the stored hash (verified). **Warning for any future custom `UserDetailsService`:** returning the stored `User` instance would let the first login erase its hash.

#### 5.4 BCrypt strength (S4), enumeration and timing

```
Recommendation: strength 10 (Spring's default), as the constant UserAccounts.BCRYPT_STRENGTH. Every configured hash
                must carry cost 10.
Reason:         OWASP Password Storage Cheat Sheet: "as large as verification server performance will allow, with a
                minimum of 10". The API is stateless HTTP Basic, so EVERY authenticated request pays one BCrypt
                verification. Cost 12 is 4x cost 10 on every API call, and every request with bogus credentials can
                force that work (a CPU amplification vector). Cost equality matters for enumeration: on an unknown
                username, DaoAuthenticationProvider runs mitigateAgainstTimingAttack, i.e. matches() against a dummy
                hash encoded with the provider's encoder, so at cost 10. A cost-12 real hash would make known users
                about 4x slower than unknown ones and reveal which usernames exist.
Alternative:    configurable strength (app.security.bcrypt-strength, e.g. 10..14) with the same equality check.
Trade-off:      raising the cost later needs a code change and re-hashing every user. The right production fix is to
                stop paying BCrypt per request (token exchange / OAuth2 resource server, per the architecture's
                Security trade-off), at which point the cost can rise.
```

**Enumeration.** Several properties together make an unknown user look the same as a known one:
- `hideUserNotFoundExceptions` stays at its default `true`, so `UsernameNotFoundException` becomes `BadCredentialsException`.
- The dummy-hash `matches` equalizes the timing.
- The entry point writes one fixed body (§7.3).

The first request after startup also pays one `encode` of the dummy password, because `prepareTimingAttackProtection` is lazy.

**Passwords over 72 bytes.** 6.5.11 contains the CVE-2025-22228 fix (fixed from 6.4.4). `BCrypt.hashpw` throws for more than 72 bytes when *encoding*. The *check* path (`checkpw`, `for_check = true`) does not throw and returns true or false, so long presented passwords give 401, not 500. Operators must hash passwords of 72 bytes or fewer. Some external tools truncate silently, and the full password then never matches.

#### 5.5 Profiles, `.env.example` and test users

- **`application.yml`**: nothing (D24). With no users, startup fails on `users` `@NotEmpty`. That is the intended fail-fast in production.
- **`application-local.yml`**: nothing. `local` reads the exported `.env` (US-001 workflow `set -a; . ./.env; set +a`).
- **`application-test.yml`** (test classpath only) adds the following. The mid-engineer generates the real hashes at cost 10 (`htpasswd -bnBC 10 "" '<pw>' | tr -d ':\n'`, or jshell with `new BCryptPasswordEncoder(10).encode(..)`).

```yaml
app:
  security:
    users:
      - username: admin
        password-hash: "$2a$10$…"   # admin-test-password
        role: ADMIN
      - username: alice
        password-hash: "$2a$10$…"   # alice-test-password
        role: USER
      - username: bob
        password-hash: "$2a$10$…"   # bob-test-password
        role: USER
```

  There are two `USER` accounts so that D4 ownership can be tested USER-against-USER in US-007 and US-009. `support/TestUsers` holds the usernames and plaintext passwords as constants for mid-engineer and QA tests. `$2a$10$…` has no `${`, so Spring placeholder resolution doesn't touch it.
- **`.env.example`**: replace the "added in Task 5" line with the following.

```
# HTTP Basic users (D3, D24). password-hash is a BCrypt hash with cost 10, never a plaintext password,
# and has no "{bcrypt}" prefix. Generate one with:   htpasswd -bnBC 10 "" 'your-password' | tr -d ':\n'
# Single-quote hash values: they contain '$', which the shell and Docker Compose would otherwise expand.
# Usernames: lowercase letters, digits, '.', '_', '-'; at most 100 characters.
# ..._PASSWORD_HASH and ..._PASSWORDHASH both bind; set only one. LOCAL USE ONLY.
APP_SECURITY_USERS_0_USERNAME=admin
APP_SECURITY_USERS_0_PASSWORD_HASH='<cost-10 hash of the local admin password documented in README>'
APP_SECURITY_USERS_0_ROLE=ADMIN
APP_SECURITY_USERS_1_USERNAME=user
APP_SECURITY_USERS_1_PASSWORD_HASH='<cost-10 hash of the local user password documented in README>'
APP_SECURITY_USERS_1_ROLE=USER
```

  Following the `POSTGRES_PASSWORD=change-me` precedent, the mid-engineer puts **working** hashes of documented local-only passwords here, so that `cp .env.example .env` works. These values need human review (risk K10). US-014 must keep the single quotes in the Compose `.env` path.

#### 5.6 Keep developer env vars out of the test JVMs (S6)

The documented local workflow exports `.env` into the shell. Running `./mvnw verify` in that same shell would let `APP_SECURITY_USERS_0_*`, which outranks `application-test.yml`, **replace the whole test user list**. Every authenticated IT would then fail with a confusing 401. The same applies to `APP_BASE_URL`, which today is harmless only because the values happen to match.

Add these to both `excludedEnvironmentVariables` lists:
- `APP_BASE_URL`, `APP_BASEURL`
- `APP_SECURITY_USERS_{0,1}_USERNAME`, `…_PASSWORD_HASH`, `…_PASSWORDHASH`, `…_ROLE` (8 entries)

This covers the documented names only. Higher indexes are a documented residual risk.

### 6. Role hierarchy (AC3)

The `RoleHierarchy` bean is shown in §3. It is `static`, as in the Spring docs, so that it doesn't force early initialisation of `SecurityConfig`. Verified in 6.5.11: `AuthorizeHttpRequestsConfigurer` resolves `context.getBean(RoleHierarchy.class)` when at least one bean of that type exists. Every `hasRole(..)` calls `manager.setRoleHierarchy(…)`, so the hierarchy reaches **every** URL rule with no extra wiring. `/api/**` uses `hasRole(USER)`, so an `ADMIN` passes only through the hierarchy. That makes AC3 provable end-to-end from US-006 on. Method security isn't used (§3.4), so there is nothing else to wire. If it is added later, it needs the same bean.

Things implementers must know:
- The hierarchy applies at **authorization time only**. An `ADMIN` principal's `getAuthorities()` holds only `ROLE_ADMIN` (AC2 asserts exactly that). Service code in US-007 and US-009 must decide "is ADMIN" with `ROLE_ADMIN`, and must never decide "is USER" from `getAuthorities()` alone.
- `RoleHierarchy` is a broad-type bean consumed by Spring through a type lookup, so a later `@Primary RoleHierarchy` would replace it. The rule's named categories (random sources, encoders, key material) don't include it, but a test pins it (§10.1). With two beans and no `@Primary`, startup fails loudly.

### 7. Errors (D30, D31)

#### 7.1 `api/error/ErrorCode`

The D31 names are declared in D31 order. Each carries the HTTP status already fixed elsewhere, so the factory needs no second mapping table:

| Constant | Status | Fixed by |
|---|---|---|
| `VALIDATION_FAILED` | 400 | US-006 AC12 |
| `MALFORMED_REQUEST` | 400 | US-006 AC14 |
| `INVALID_URL` | 400 | US-006 AC5 |
| `INVALID_ALIAS` | 400 | US-006 AC4 |
| `ALIAS_ALREADY_EXISTS` | 409 | US-006 AC3 |
| `SHORT_URL_NOT_FOUND` | 404 | FR-8, D2, D13, D46 |
| `SHORT_URL_ALREADY_DEACTIVATED` | 409 | D26 |
| `SHORT_URL_ALREADY_ACTIVE` | 409 | D26 |
| `CONCURRENT_MODIFICATION` | 409 | D35 |
| `SHORT_CODE_UNAVAILABLE` | 503 | architecture (short-code generation), US-006 AC7 |
| `AUTHENTICATION_REQUIRED` | 401 | D30 |
| `ACCESS_DENIED` | 403 | D30 |
| `INTERNAL_ERROR` | 500 | architecture (error handling) |

```java
public enum ErrorCode {
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST), /* … */ INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR);
    private final HttpStatus status;
    ErrorCode(HttpStatus status) { this.status = status; }
    public HttpStatus status() { return status; }
}
```

Detail texts are **not** in the enum. Each producer supplies a generic `detail`, so this story doesn't have to write copy for later stories.

#### 7.2 `api/error/ProblemDetails`, the one shared factory

```java
public final class ProblemDetails {
    public static final String ERROR_CODE = "errorCode";

    /**
     * @throws NullPointerException if any argument is null
     * @throws IllegalArgumentException if requestUri is not a valid URI reference
     */
    public static ProblemDetail of(ErrorCode code, String detail, String requestUri) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(code.status(), Objects.requireNonNull(detail));
        problem.setInstance(URI.create(Objects.requireNonNull(requestUri)));
        problem.setProperty(ERROR_CODE, code.name());
        return problem;
    }
}
```

The fixed shape, which AC8 defines and every later error must match, is: `type` (`"about:blank"`, the default), `title` (the status reason phrase, left null so `ProblemDetail.getTitle()` falls back to it), `status`, `detail`, `instance` (the request path, no query), and `errorCode`.

`instance` is included because Spring MVC fills it in anyway. In 6.2.19, `HttpEntityMethodProcessor` does `if (detail.getInstance() == null) detail.setInstance(URI.create(request.getRequestURI()))`. A `ProblemDetail` returned by US-006's advice would therefore carry `instance`, and the security responses must match. `getRequestURI()` never includes the query string, which CLAUDE.md's logging and URL rule requires.

`errorCode` is top-level only because `Jackson2ObjectMapperBuilder`, which builds Boot's `ObjectMapper`, registers `ProblemDetailJacksonMixin` (verified in 6.2.19). With a bare `new ObjectMapper()` it would render under `"properties"`. A test pins this.

**US-006 must build every error body through `ProblemDetails.of`**, or at minimum set `errorCode` through `ProblemDetails.ERROR_CODE`. It must also add a test that an advice-produced body has the same key set. Any future extension, such as a request ID, is added in `ProblemDetails.of`, so it reaches the security responses too.

#### 7.3 `ProblemDetailAuthenticationEntryPoint` (AC1)

- Status 401. Header `WWW-Authenticate: Basic realm="url-shortener"` (realm = `SecurityConfig.REALM`, the application name). No `charset` parameter: usernames are ASCII, and Spring's `BasicAuthenticationConverter` decodes UTF-8 anyway.
- Body: `ProblemDetails.of(AUTHENTICATION_REQUIRED, "Authentication is required to access this resource.", request.getRequestURI())`.
- **The same body and header for every cause:** no header, malformed header (not Base64, no colon), unknown user, wrong password. `authException.getMessage()` is never used. `UsernameNotFoundException`'s message contains the username, and although it is hidden by default it must never be relied on.
- If `response.isCommitted()`, it writes nothing and logs at DEBUG.

#### 7.4 `ProblemDetailAccessDeniedHandler` (AC4)

Status 403. Body: `ProblemDetails.of(ACCESS_DENIED, "You do not have permission to perform this action.", request.getRequestURI())`. No header. The exception message is never used.

#### 7.5 `ProblemDetailResponseWriter`

It is built with the **context's** `ObjectMapper` (Boot's), so the mixin applies.

```java
void write(HttpServletResponse response, ProblemDetail problem) throws IOException {
    response.setStatus(problem.getStatus());
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);   // "application/problem+json"
    response.getOutputStream().write(objectMapper.writeValueAsBytes(problem));
}
```

Jackson writes UTF-8, and no `charset` parameter is added (this matches MVC's Jackson converter output). Tests use `contentTypeCompatibleWith(APPLICATION_PROBLEM_JSON)`.

#### 7.6 Logging (S3)

| Event | Level | Logged | Never logged |
|---|---|---|---|
| Anonymous request to a secured path (`InsufficientAuthenticationException`) | DEBUG | method, `getRequestURI()` (path only), exception simple class name | — |
| Failed Basic authentication (any other `AuthenticationException`) | INFO | method, path, exception simple class name (e.g. `BadCredentialsException`) | attempted username, password, `Authorization` header, exception message, query string, client IP |
| Access denied | INFO | method, path | username (pending S3), exception message |

```
Recommendation (S3): log no usernames (attempted or authenticated) and no client IP in these events for now.
Reason:         CLAUDE.md forbids credentials and personal data in logs. An attempted username is a known credential
                leak: users type passwords into the username field. Usernames like "alice" can be personal data, and
                so can IP addresses. Brute-force detection belongs at the load balancer/WAF (D37 already puts TLS there).
Alternative:    log the AUTHENTICATED principal name on 403 (it is verified and is the same value stored in created_by),
                and/or the client IP taken from trusted forwarded headers.
Trade-off:      less forensic detail in application logs until an observability/request-ID story adds a correlation ID.
```

`StrictHttpFirewall` rejects CR/LF in URLs before these handlers run, so logging the path can't inject log lines.

### 8. `created_by` / `deleted_by` VARCHAR(100) (S2, US-002 carry-over)

Today, the only source of these values is `Authentication.getName()` of a configured user. §5.1 already constrains that to 1–100 lowercase ASCII characters with no whitespace, so the VARCHAR truncation edge (excess made only of trailing spaces, per D47) **cannot occur**.

```
Recommendation (S2): Option A: no migration. Bound the username in configuration (§5.1) AND add an actor guard in the
                domain. Both reference one constant, ShortUrl.MAX_ACTOR_LENGTH = 100, which the entity's
                @Column(length = MAX_ACTOR_LENGTH) on created_by/deleted_by also uses.
                The guard in ShortUrl.create(..createdBy..) and softDelete(deletedBy, ..) throws
                IllegalArgumentException when the value is blank, is not equal to its strip(), or has more than
                MAX_ACTOR_LENGTH code points. It keeps the existing order: arguments, then deleted check, then redundant
                check. Document it with @throws.
Reason:         Satisfies CLAUDE.md "application-layer validation must reject values that exceed a length-limited
                VARCHAR column before insert" at the point of insert, whatever the principal's source. It also satisfies
                "one shared constant" for a bound checked in two layers. The DB still rejects over-length non-space
                input on its own. Unlike D47's columns, which hold untrusted client input, these values are
                operator-configured and validated at startup. Smallest change; no schema churn.
Alternative:    Option B: forward migration V2__widen_actor_columns.sql (click_event moves to V3):
                  ALTER TABLE short_url ALTER COLUMN created_by TYPE TEXT;
                  ALTER TABLE short_url ALTER COLUMN deleted_by TYPE TEXT;
                  ALTER TABLE short_url ADD CONSTRAINT ck_short_url_created_by_length CHECK (char_length(created_by) <= 100);
                  ALTER TABLE short_url ADD CONSTRAINT ck_short_url_deleted_by_length CHECK (char_length(deleted_by) <= 100);
                varchar -> text is binary-coercible (no table rewrite). Existing rows satisfy the CHECKs, because
                VARCHAR(100) already bounded them, so the migration is backward-compatible with running code.
                Hibernate validate accepts String <-> text (D47 note). This still needs the config bound for fail-fast,
                and it needs ShortUrlSchemaTest updates plus PostgresErrors-based constraint tests at 100 / 101 /
                100 + trailing space / multibyte.
Trade-off:      A keeps VARCHAR(100), which is inconsistent in style with D47, and relies on the app layer for the
                trailing-space edge. When identity moves to an external IdP (the architecture's JWT path), usernames
                arrive unvalidated, and Option B becomes mandatory in that story. B is the stricter DB guarantee now,
                but it adds a migration and about six schema tests to a security story, for a case the current design
                cannot produce.
```

Tests for Option A follow the CLAUDE.md length rule:
- `ShortUrlTest`: exactly 100 accepted; 101 rejected; 100 + trailing space rejected; a leading space rejected; blank rejected; a 100-code-point multibyte actor (e.g. 100 × `é`) accepted and 101 rejected. The multibyte case pins code-point counting, which matches PostgreSQL `char_length`.
- `ShortUrlRepositoryTest`: persist `created_by` and `deleted_by` at exactly 100 characters, including the multibyte case, and read them back through `JdbcTemplate` unchanged.

### 9. Carry-over from US-004 (engineer-approved)

#### 9.1 R11: env-binding tests in `AppPropertiesTest`

**Verified (Boot 3.5.16 source).** `SpringConfigurationPropertySource.from()` uses `SYSTEM_ENVIRONMENT_MAPPERS` (`SystemEnvironmentPropertyMapper`, then `DefaultPropertyMapper`) only when the source is a `SystemEnvironmentPropertySource` **and** its name equals `systemEnvironment` or ends with `-systemEnvironment`. Otherwise only `DefaultPropertyMapper` applies.

`SystemEnvironmentPropertyMapper.map(name)` returns the canonical form (dashes removed, e.g. `APP_BASEURL`) **before** the legacy form (dash becomes underscore, e.g. `APP_BASE_URL`).

With the old source names `env`/`env2`, only `DefaultPropertyMapper` ran. It asked for `app.base-url`, and Spring Framework's own `SystemEnvironmentPropertySource` fallback (`.` and `-` to `_`, then upper case) found `APP_BASE_URL`. That is why only the legacy form appeared to bind.

**The pattern that actually exercises production binding** is one helper that adds **one** source named exactly `StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME`:

```java
/**
 * Adds variables the way the OS environment does. The source is named "systemEnvironment", so Spring Boot
 * applies SystemEnvironmentPropertyMapper and both the canonical (APP_BASEURL) and the legacy (APP_BASE_URL)
 * forms bind, as in production. addFirst replaces the real OS source of this context, so the developer's own
 * variables cannot leak in. Pass ALL variables in one call: a second source with the same name replaces the first.
 */
private ApplicationContextRunner withEnvironment(Map<String, Object> variables) {
    return runner.withInitializer(ctx -> ctx.getEnvironment().getPropertySources().addFirst(
            new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                    Map.copyOf(variables))));
}
```

`MutablePropertySources.addFirst` removes any existing source with the same name. So chaining two such initializers, as the old `env` + `env2` test did, keeps only the last. The existing `shouldBindAdditionalReservedWordsFromEnvironmentStyleName` must merge its two maps into one.

Tests required (each positive test has a negative counterpart using the same mechanism):

| Property | Positive (binds and the wired bean reflects it) | Negative / invalid |
|---|---|---|
| `app.base-url` via `APP_BASE_URL` | `https://env.example` binds | `ftp://x.example` fails startup: `BindException` `app`, `BindValidationException` field `baseUrl`, rejected value asserted |
| `app.base-url` via `APP_BASEURL` | same | same |
| `shortener.alias.additional-reserved-words` via `SHORTENER_ALIAS_ADDITIONAL_RESERVED_WORDS` | `promo,Sale` binds and `sale` is rejected by `AliasPolicy` | see below |
| same via `SHORTENER_ALIAS_ADDITIONALRESERVEDWORDS` | same | see below |
| `app.security.users[0].password-hash` via `…_PASSWORD_HASH` and via `…_PASSWORDHASH` | a known cost-10 hash binds and `alice-test-password` authenticates through the wired provider | `not-a-hash` fails startup: `IllegalArgumentException` naming `app.security.users[0].password-hash`, and the message does **not** contain `not-a-hash` |
| `…_USERNAME`, `…_ROLE` (single form) | bind | `Alice` fails (`users[0].username`); `ROOT` fails (`app.security.users[0].role`) |

Optional: pin precedence with one test in which both `APP_BASEURL` and `APP_BASE_URL` are set and the canonical one wins, so US-014/US-015 docs can state it.

**S5, reserved words.** `AliasProperties` has no constraints, so no value is "invalid". The D48 invariant is that the property can never remove a built-in. For **each** env form, the negative test sets the variable to `api,promo` and asserts that `api` is still rejected and `promo` is now rejected. The existing wrong-key test (`SHORTENER_ALIAS_RESERVEDWORDS` binds nothing) moves to the `systemEnvironment` helper. Adding real validation to reserved words would be new behaviour and is out of scope.

**Comment corrections:**
- Lines 63–64: replace the rationale with the helper's Javadoc above.
- Lines 109–110: must say that **both** `SHORTENER_ALIAS_ADDITIONAL_RESERVED_WORDS` and `SHORTENER_ALIAS_ADDITIONALRESERVEDWORDS` bind in production.

#### 9.2 R12, R14, R15

- **R12:** `UrlValidator(String)` Javadoc gains `@throws NullPointerException if appBaseUrl is null` and `@throws IllegalArgumentException if appBaseUrl is not an absolute http or https URL with a host`.
- **R14:** `AppPropertiesTest:55` (`// R3: …`) becomes `// The base URL may not carry a query or fragment, an empty one included (D28, D33)`. `UrlValidatorTest:208` (`// R8: …`) describes the behaviour, which is lone surrogates rejected because they cannot be encoded as UTF-8. Grep `src/` for `\bR[0-9]+\b` and `\bN[0-9]+\b` in comments; none may remain.
- **R15:** re-wrap `AliasPolicy`'s class Javadoc (line 14) to the width of the surrounding lines. Text unchanged.

### 10. Tests

All `ApplicationContextRunner` failure tests assert the exception **type** in the cause chain and the exact property or field name, never only `hasFailed()`. Every case conversion in tests uses `Locale.ROOT`. Web-slice tests authenticate with `SecurityMockMvcRequestPostProcessors.httpBasic(user, pw)` against the **real** provider and test users. `@WithMockUser` bypasses authentication, so it is used only where a test is explicitly about authorization alone.

#### 10.1 Mid-engineer (`*Test`, Surefire)

**`security/SecurityConfigWebMvcTest`** uses `@WebMvcTest(controllers = SecurityProbeController.class)`, `@Import({SecurityConfig.class, UserAccountsConfig.class})` and `@ActiveProfiles("test")`. `SecurityProbeController` is a test-only `@RestController`, a static nested class. `@Import` it if the slice doesn't pick it up. It has:
- `GET` and `POST /api/test-probe`, returning `{name, authorities}` from `Authentication`.
- `DELETE /api/v1/urls/{code}`, returning 204 and incrementing a static counter. It stands in for US-009's handler **only in this slice**.

| Test | AC |
|---|---|
| `shouldReturn401ProblemDetailWithBasicChallengeWhenCredentialsMissing`: status, `WWW-Authenticate` exactly `Basic realm="url-shortener"`, content type compatible with `application/problem+json`, `$.errorCode`, `$.type == about:blank`, `$.status == 401`, `$.instance == /api/test-probe` | AC1 |
| `shouldReturnIdentical401ForWrongPasswordUnknownUserMalformedHeaderAndNoCredentials`: all four bodies byte-equal, and all headers equal | AC1, §5.4 |
| `shouldAuthenticateUserAndPopulateRole` (alice → `ROLE_USER` only) and `shouldAuthenticateAdminWithAdminRoleOnly` (admin → exactly `[ROLE_ADMIN]`) | AC2 |
| `shouldAuthenticateCaseInsensitivelyButKeepConfiguredUsername` (`ALICE` → name `alice`) | AC2, Q2 |
| `shouldLetAdminPassUserOnlyRule` (admin → 200 on `/api/test-probe`) | AC3 |
| `shouldReturn403AccessDeniedWhenUserDeletes`: 403 `ACCESS_DENIED` and the probe counter unchanged, proving the role check runs before any handler (US-009 AC8) | AC4 |
| `shouldLetAdminReachDeleteHandler` (204, counter incremented) | AC4 counterpart |
| `shouldNotReachDeleteHandlerThroughPathVariants`, run as USER: `/api/v1/urls/abc/`, `/API/v1/urls/abc`, `/api/v1/urls/abc;x=1`, `/api/v1//urls/abc`, `/api/v1/urls/%2e%2e`. Each is never 204 and the counter is unchanged | CLAUDE.md bypass-pin rule |
| `shouldPermitPublicPathsAnonymously`: GET and HEAD `/abc1234`, `/ab` (malformed code), `/v3`; GET `/actuator/health`, `/v3/api-docs`, `/v3/api-docs/swagger-config`, `/v3/api-docs.yaml`, `/swagger-ui.html`, `/swagger-ui/index.html`. Each is **not 401/403 and has no `WWW-Authenticate`** (handlers aren't in the slice, so exact 200s are QA's) | AC5 |
| `shouldRequireAuthenticationForReservedPrefixesAndEverythingElse`: GET `/api`, `/actuator`, `/actuator/env`, `/a/b`; POST, PUT and DELETE `/abc1234`; OPTIONS `/abc1234`. Each is 401 `AUTHENTICATION_REQUIRED` | AC5 boundary |
| `shouldCreateNoSessionOrCookie`: on an authenticated 200, a 401 and a 403, no `Set-Cookie` header and `result.getRequest().getSession(false) == null` | AC7 |
| `shouldAcceptAuthenticatedPostWithoutCsrfToken` (POST `/api/test-probe` → 200, not 403) | AC7 |
| `shouldSendHstsOnlyOverHttps`: with `secure(true)`, `Strict-Transport-Security` is present; without it, the header is absent (D37 guard) | D37 |
| `shouldNotCreateBootDefaultUser`: no bean named `inMemoryUserDetailsManager`, no `UserDetailsService` or `PasswordEncoder` bean, exactly one `RoleHierarchy` bean and it is `SecurityConfig`'s. With `OutputCaptureExtension`, output does not contain `Using generated security password` | §5.3, §6 |

**`security/ProblemDetailHandlersTest`** (unit; `MockHttpServletRequest`/`Response`, Boot-built `ObjectMapper` from `Jackson2ObjectMapperBuilder.json().build()`):
- **AC8 shape:** the 401 and 403 bodies each have exactly the key set `{type, title, status, detail, instance, errorCode}`, with no `properties` key. `type` is `about:blank`. `title` is `Unauthorized` or `Forbidden`. `detail` is the fixed generic text. Only `status`, `title`, `detail` and `errorCode` differ between the two.
- Each body equals `objectMapper.writeValueAsString(ProblemDetails.of(code, detail, uri))`, which proves both go through the shared factory.
- **No leakage:** an `AuthenticationException` or `AccessDeniedException` with message `secret-marker alice` never puts `secret-marker` or `alice` in the body, the headers or the captured logs.
- A committed response is left untouched.

**`api/error/ErrorCodeTest`:** names are exactly the D31 list, `containsExactly` in D31 order. Each status matches §7.1 (AC9).

**`api/error/ProblemDetailsTest`:** instance, `errorCode` and defaults are set, and NPE is thrown on each null argument.

**`security/RoleHierarchyTest`:** `getReachableGrantedAuthorities([ROLE_ADMIN])` contains `ROLE_USER`. `[ROLE_USER]` does not reach `ROLE_ADMIN` (AC3).

**`security/UserAccountsTest`** (pure unit):
- Valid accounts give `User`s with the right authority.
- A cost-12 hash is rejected, and so are a `{bcrypt}`-prefixed hash, a plaintext value, a 59-character truncated hash and `$2x$`. Each gives IAE with the property path, and the message never contains the value.
- A duplicate username is rejected with both indexes in the message.
- `UserAccount.toString()` never contains the hash.

**`security/UserAccountsConfigTest`** (`ApplicationContextRunner` + `ValidationAutoConfiguration` + `UserAccountsConfig`):

*Positive:* the provider bean exists, and `provider.authenticate(UsernamePasswordAuthenticationToken.unauthenticated("alice", pw))` succeeds with `ROLE_USER`.

*AC6 (BCrypt, not plaintext):*
- Authenticating with the stored **hash string** as the password throws `BadCredentialsException`.
- An unknown user throws `BadCredentialsException`, not `UsernameNotFoundException`.

*Startup failures* (type + field or property asserted), each with its positive counterpart:
- `users` missing
- `users` empty
- `username` blank, 101 characters (100 accepted), 100 + trailing space, uppercase, containing `:`, multibyte `é`
- `role` missing, and `role` `ROOT`
- `passwordHash` missing
- `passwordHash` plaintext (IAE, value not in the message and **not in the captured output**)
- duplicate usernames

*Env binding:* the §9.1 rows for `app.security.*`, through the `systemEnvironment` helper.

**`config/AppPropertiesTest`:** the §9.1 changes. **`domain/ShortUrlTest`** and **`repository/ShortUrlRepositoryTest`:** the §8 actor-guard tests, if S2 = A.

#### 10.2 QA (`*IT`, Cucumber; Failsafe, shared `IntegrationTestBase` context)

Every class extends `IntegrationTestBase` with **no** context-affecting additions (CLAUDE.md rule). Credentials come from `support/TestUsers`. `TestRestTemplate.withBasicAuth(..)` returns a copy that doesn't change the context.

- **Unchanged regression:** `HealthEndpointIT`, `OpenApiDocsIT` and `smoke.feature` pass unedited. So do all other existing ITs, whose context now needs the test users.
- **`features/security.feature`** (AC5 end to end), with scenarios for:
  - anonymous `GET /actuator/health` → 200 `UP`
  - anonymous `GET /v3/api-docs` → 200 valid document
  - anonymous `GET /swagger-ui/index.html` → 200
  - anonymous `GET /v3/api-docs/swagger-config` → 200
  - anonymous GET and HEAD of a short-code-shaped path → **not 401 and no `WWW-Authenticate`**; today it returns 404
  - anonymous request to a management path → 401 with `errorCode` `AUTHENTICATION_REQUIRED` and a Basic challenge (a preview of AC1; the full AC1–AC8 Cucumber suite starts in US-006, per this story's test plan)
- **`SecurityIT`** runs against real Tomcat, which MockMvc can't cover (error dispatch, real URL decoding):
  - Anonymous `GET /api/v1/urls` → 401: full ProblemDetail shape, `Content-Type: application/problem+json`, exact `WWW-Authenticate`.
  - Wrong password and unknown user → the byte-identical body.
  - Authenticated alice `GET /api/v1/does-not-exist` → **404, not 401**. This proves the ERROR-dispatch rule (§3.1).
  - Anonymous `GET /abc1234` and `HEAD /abc1234` → 404 with Boot's default error JSON (`timestamp`, `status`, `error`, `path`; no message). **This is not a ProblemDetail. The body shape for unknown codes is owned by US-008 (`SHORT_URL_NOT_FOUND`), and the generic 404 by US-006's advice.** QA asserts only status 404, no `WWW-Authenticate`, and no ProblemDetail `errorCode`. Don't pin the Boot body.
  - Anonymous `GET /api` and `/actuator` → 401.
  - Bypass pins, anonymous: `/%61pi/v1/urls`, `/api/v1/urls/`, `/API/v1/urls`, `//api/v1/urls`, `/api;x=1/v1/urls`, `/api/v1/urls/%2e%2e/x`. Each is **400 or 401, never 2xx or 404** (a 404 would mean the request passed security).
  - No response in this class carries `Set-Cookie`.
  - `/swagger-ui.html` → final 200 after the springdoc redirect.

### 11. AC → component → test

| AC | Component | Mid-engineer test | QA test |
|---|---|---|---|
| AC1 | `ProblemDetailAuthenticationEntryPoint`, `SecurityConfig` | `SecurityConfigWebMvcTest` 401 tests | `SecurityIT` 401; `security.feature` preview; Cucumber from US-006 |
| AC2 | `UserAccountsConfig`, `UserAccounts`, `InMemoryUserDetailsManager` | `shouldAuthenticate…`, `UserAccountsConfigTest` positive | from US-006 |
| AC3 | `SecurityConfig.roleHierarchy`, `/api/**` `hasRole(USER)` | `RoleHierarchyTest`, `shouldLetAdminPassUserOnlyRule` | from US-006 |
| AC4 | `ProblemDetailAccessDeniedHandler`, DELETE rule | `shouldReturn403AccessDeniedWhenUserDeletes` | US-009 |
| AC5 | permit rules | `shouldPermitPublicPathsAnonymously`, boundary test | `security.feature`, `SecurityIT`, existing ITs |
| AC6 | `DaoAuthenticationProvider` + in-method `BCryptPasswordEncoder(10)`, `UserAccounts` | `UserAccountsConfigTest` AC6 and startup failures, `UserAccountsTest` | from US-006 |
| AC7 | `STATELESS`, `NullRequestCache`, CSRF off | `shouldCreateNoSessionOrCookie`, CSRF test | `SecurityIT` no `Set-Cookie` |
| AC8 | `ProblemDetails`, `ProblemDetailResponseWriter` | `ProblemDetailHandlersTest` | US-006 cross-check |
| AC9 | `ErrorCode` | `ErrorCodeTest` | n/a (unit only, per this story) |

### 12. Other decisions

**User configuration shape (S1)**
```
Recommendation: a list app.security.users[n].{username, password-hash, role} (§5.1).
Reason:         D4 ownership is proved most directly USER-against-USER (US-007 AC3, US-009 AC3), which needs two USER
                accounts. The same code serves any number of users, and roles are explicit per user.
Alternative:    exactly two fixed accounts: app.security.admin.{username,password-hash} and
                app.security.user.{username,password-hash} (APP_SECURITY_ADMIN_USERNAME, …_PASSWORD_HASH, …).
Trade-off:      the list uses indexed env names (APP_SECURITY_USERS_0_…), which are clunkier in Compose. They also can't
                be excluded from test JVMs by pattern (§5.6), and a list from env replaces the YAML list as a whole. The
                fixed shape is simpler to document, but it tests ownership only as USER against an ADMIN-created link.
```

**Provider as the only published security bean**
```
Recommendation: publish exactly one DaoAuthenticationProvider bean (concrete type), with the BCryptPasswordEncoder and
                InMemoryUserDetailsManager built inside it. Set http.authenticationManager(new ProviderManager(provider)).
Reason:         Satisfies the CLAUDE.md security-sensitive-bean rule: no PasswordEncoder bean exists for a @Primary to
                replace. It also switches off Boot's generated-password user (verified condition list) without an exclude,
                and it keeps Spring's global AuthenticationManager on the same provider (verified single-bean rule).
Alternative:    publish a concrete BCryptPasswordEncoder bean plus a UserDetailsService bean, and let Spring wire them.
Trade-off:      the alternative still exposes a PasswordEncoder-typed bean to Spring's getIfUnique() lookup, which
                a @Primary would win. The chosen design is a few lines more explicit.
```

**Validating hashes outside Bean Validation**
```
Recommendation: check the password-hash format in UserAccounts (IAE naming the property, never the value). Not @Pattern.
Reason:         Boot's bind failure analyzers print the rejected value, so a plaintext password pasted into the hash
                variable would be written to the startup log.
Alternative:    @Pattern on passwordHash.
Trade-off:      two failure types for configuration (BindValidationException, and IAE inside BeanCreationException).
                Both are asserted by type and property name.
```

### 13. Open questions (for the engineer)

- **Q1 (S1–S6):** see §0.
- **Q2:** login usernames are matched case-insensitively (`InMemoryUserDetailsManager` behaviour), while the principal and `created_by` always use the configured lowercase name. This isn't in the requirements. **Recommend accepting it.**
- **Q3:** a request carrying *invalid* Basic credentials gets 401 even on a public path, such as a redirect or health (Spring default; `BasicAuthenticationFilter` fails any request with a bad header). **Recommend accepting it.** It reveals nothing, and real visitors don't send Basic credentials to `/{code}`.
- **Q4:** the D30/AC8 phrase "`errorCode` is the sole distinguishing extension" conflicts with US-006 AC4, whose validation errors must identify the field and so need an extra extension such as `errors`. **Recommend reading AC8 as "security errors carry no extension besides `errorCode`"**, with US-006 defining the validation extension. The planner or engineer should confirm at US-006.
- **Q5:** should the OpenAPI document declare an HTTP Basic security scheme? **Recommend yes, in US-006**, which owns the documented operations (US-006 AC13). Not in this story.

### 14. Risks for the implementer and the reviewer

- **K1 Matcher order.** Moving `GET /*` above the `/api` or `/actuator` lines would make `/api` and `/actuator` public (the actuator discovery page would leak). Boundary tests pin this, and the reviewer checks the order against §3.
- **K2 Credential leakage.** The following must never appear in bodies or logs:
  - `authException.getMessage()` (a `UsernameNotFoundException` message holds the username)
  - the `Authorization` header or query strings
  - a `@Pattern` on `passwordHash`
  - the record's default `toString()`

  Reviewer: grep `security/` for `getMessage(` and `getHeader("Authorization")`.
- **K3 Hash cost ≠ 10** reopens timing-based user enumeration. The startup check is the only guard, so keep the `UserAccountsTest` cost-12 case.
- **K4 `ObjectMapper`.** Writing with a bare `new ObjectMapper()` nests `errorCode` under `properties`. It must be the context's mapper, and the AC8 key-set test catches a mistake.
- **K5 MockMvc blind spots.** MockMvc does no ERROR dispatch and no Tomcat URL decoding. The §3.1 rule and the encoded-path pins are only proven by `SecurityIT`.
- **K6 `IntegrationTestBase` purity.** QA must not add `@TestPropertySource` or `@MockitoBean` to vary users or security. Use `TestUsers` and `withBasicAuth`.
- **K7 springdoc path drift.** Any `springdoc.*.path` property added later must update the permit list.
- **K8 BCrypt cost per request.** At cost 10, authenticated tests and requests pay tens of milliseconds each. Attackers can force this work with bogus credentials, and rate limiting belongs at the load balancer. Don't "fix" slow tests by lowering the cost in `application-test.yml`: the cost-equality check would reject it, and it must.
- **K9 US-008 forward risk.** Spring Security's `CacheControlHeadersWriter` adds `Cache-Control: no-cache, no-store, max-age=0, must-revalidate`, `Pragma` and `Expires` unless the response already set them. US-008 must assert the exact `Cache-Control: no-store` (D7) on its 302 and `HEAD` responses.
- **K10 Committed local credentials.** `.env.example` hashes are of documented local-only passwords, and a human must review them. Deployment docs (US-014/US-015) must say never to reuse them, and must keep single-quoting.
- **K11 Env override in test JVMs.** Only the §5.6 names are stripped. A developer exporting `APP_SECURITY_USERS_2_…` still changes the test user list.
- **K12 Future custom `UserDetailsService`** must return a copy on every load (§5.3), or credential erasure will blank the stored hash after the first login.
- **K13 `RoleHierarchy` replaceability.** A `@Primary RoleHierarchy` in any later config silently changes authorization. Keep the single-bean assertion test.
- **K14 Boot 4 / Security 7 upgrade** (D22 roadmap) changes the matcher type (`PathPatternRequestMatcher`), removes the deprecated `DaoAuthenticationProvider` setters, and changes defaults. The bypass pins and boundary tests are the regression net.

### 15. Sources checked (2026-09-29)

- Spring Security 6.5.11 source (tag `6.5.11`): `DaoAuthenticationProvider` (constructors and deprecations, delegating default, timing mitigation), `InMemoryUserDetailsManager` (`toLowerCase(Locale.ROOT)`, copy on load), `MutableUser` (not a `CredentialsContainer`), `ProviderManager` (credential erasure, `NullEventPublisher`), `BCryptPasswordEncoder` (pattern, strength 4–31, default 10, `matches` warning), `BCrypt` (72-byte check on encode only), `SessionManagementConfigurer` (STATELESS gives `RequestAttributeSecurityContextRepository` and `NullRequestCache`), `AuthorizeHttpRequestsConfigurer` (`RoleHierarchy` bean lookup), `HttpBasicConfigurer` (realm/entry-point conflict, default entry-point matcher), `InitializeUserDetailsBeanManagerConfigurer` (`getIfUnique`), `InitializeAuthenticationProviderBeanManagerConfigurer` (single-bean rule), `RoleHierarchyImpl` (`withDefaultRolePrefix` builder; no-arg constructor deprecated).
- Spring Security 6.5 reference, *Authorize HttpServletRequests*: all dispatcher types authorized, the ERROR/FORWARD `permitAll` example, `MvcRequestMatcher` chosen when MVC is present, first-match ordering.
- Spring Boot 3.5.16 source (tag `v3.5.16`): `UserDetailsServiceAutoConfiguration` conditions and the generated-password message, `SecurityProperties.Filter` (all dispatcher types), `SpringConfigurationPropertySource` (mapper selection by type and name), `SystemEnvironmentPropertyMapper` (canonical before legacy; numeric index mapping).
- Spring Boot 3.5 reference, *Testing Spring Boot Applications*: the `@WebMvcTest` scan list, with `WebSecurityConfigurer` scanned but `@Configuration` security classes not.
- Spring Framework 6.2.19 source: `ProblemDetail` (`about:blank`, title fallback, mixin note), `HttpEntityMethodProcessor` (instance set to the request URI), `Jackson2ObjectMapperBuilder` (`ProblemDetailJacksonMixin` registration).
- springdoc-openapi 2.8.17 `Constants.java` (tag `v2.8.17`) and springdoc.org properties page.
- OWASP Password Storage Cheat Sheet (BCrypt work factor ≥ 10; 72-byte limit). Spring advisory CVE-2025-22228 (fixed in 6.4.4, so included in 6.5.11).

## Implementation notes

**Files (main).** `pom.xml` (starter-security, spring-security-test, env exclusions for Surefire and Failsafe); `security/` (`SecurityConfig`, `UserAccountsConfig`, `UserAccountsProperties`, `UserAccounts`, `Role`, `ProblemDetailAuthenticationEntryPoint`, `ProblemDetailAccessDeniedHandler`, `ProblemDetailResponseWriter`); `api/error/ErrorCode`, `api/error/ProblemDetails`; `domain/ShortUrl` (`MAX_ACTOR_LENGTH`, actor guard); `validation/UrlValidator` (`@throws`), `validation/AliasPolicy` (Javadoc re-wrap); `.env.example`.

**Files (test).** `src/test/resources/application-test.yml` (users admin/alice/bob); `support/TestUsers`; `security/SecurityConfigWebMvcTest`, `ProblemDetailHandlersTest`, `RoleHierarchyTest`, `UserAccountsTest`, `UserAccountsConfigTest`; `api/error/ErrorCodeTest`, `ProblemDetailsTest`; `config/AppPropertiesTest`, `domain/ShortUrlTest`, `repository/ShortUrlRepositoryTest`, `validation/UrlValidatorTest` (comment only).

**Decisions.**
- Followed the approved design (D50 to D56) as written, matcher order included. No deviation was needed: no `PasswordEncoder` or `UserDetailsService` bean is published, and Boot's generated user does not appear (asserted by bean lookup).
- Test password hashes use `$2y$10$` (htpasswd output). The hash pattern accepts `$2a$`, `$2b$` and `$2y$` at cost 10 only.
- `ShortUrl` guard: `IllegalArgumentException` for a blank value, surrounding whitespace, or more than `MAX_ACTOR_LENGTH` code points. In `softDelete` it runs after the null checks and before the deleted check.
- `.env.example` documents that when both `..._PASSWORD_HASH` and `..._PASSWORDHASH` are set, the `PASSWORDHASH` (canonical) form wins. `UserAccountsConfigTest` pins this.
- Padded-username startup tests use a `MapPropertySource`, because `withPropertyValues` trims values.

**Carry-over.** Env tests in `AppPropertiesTest` use one source named `systemEnvironment`. Both forms of `APP_BASE_URL` and `SHORTENER_ALIAS_ADDITIONAL_RESERVED_WORDS` each have a positive and a negative test (reserved words use the "binds but cannot remove a built-in" variant), plus a canonical-wins test for the base URL. The false comment is removed. `@throws` added to `UrlValidator`, review-ID comments removed, `AliasPolicy` Javadoc re-wrapped.

**Needs extra human review.**
- `.env.example` hashes (public repo). They are hashes of `local-admin-password` and `local-user-password`, and the file says so plainly.
- `SecurityConfig` matcher order and the permit list.
- The credential-leak assertions in `ProblemDetailHandlersTest` and `SecurityConfigWebMvcTest`.
- `shouldNotCreateBootDefaultUser...` asserts the log text through `CapturedOutput`, but the context is cached and may start before capture begins. The bean-lookup assertions are the real guard.
- `shouldNotReachDeleteHandlerThroughPathVariantsAsUser` accepts either a rejected request (firewall exception) or any status other than 204.

**Command and result.** `./mvnw -q verify` (JDK 25 default on PATH): exit 0. Surefire 392 run, 0 failed, 0 errors, 0 skipped. Failsafe 18 run, 0 failed. Merged LINE coverage from `target/site/jacoco-merged/jacoco.csv` is 231 of 232 lines (99.6%).

## QA notes

**Written before reading the implementation**, from the ACs and the approved design (D30-D32, D50-D56).

**Files (all under `src/test/**`).**
- `resources/features/security.feature` (12 scenarios: 4 single, plus outlines of 4 short-code-shaped and 4 management paths)
- `java/.../cucumber/SecuritySteps.java` (JDK `HttpClient`, anonymous only, no credentials)
- `java/.../support/SecurityIT.java` (`IntegrationTestBase` subclass, no context-affecting additions; JDK `HttpClient` so raw paths reach Tomcat unchanged; credentials from `TestUsers`, never printed; every response is checked for no `Set-Cookie`)
- `smoke.feature`, `HealthEndpointIT`, `OpenApiDocsIT` and all other ITs are unedited and pass.

**AC to test.**

| AC | Proven by (QA, end to end) |
|---|---|
| AC1 | `SecurityIT.shouldReturn401ProblemDetailWithBasicChallengeWhenAnonymousCallsApi`, `shouldReturnIdentical401BodyForWrongPasswordUnknownUserAndNoCredentials`, `shouldReturn401ForMalformedAuthorizationHeaderWithSameBody`, `shouldReturn401ForInvalidCredentialsEvenOnPublicPath` (D55), `shouldNotIncludeQueryStringInProblemInstance`; `security.feature` "A management path demands authentication" (4 paths) |
| AC2 / AC3 | Only partly reachable: `shouldPassSecurityForUserAndAdminOnApiPaths` (alice and admin both pass `/api/**`, which is `hasRole(USER)`, so admin passes through the hierarchy; 404 proves security passed) and `shouldAuthenticateUsernameCaseInsensitively` (D54). Principal name and authorities are not observable over HTTP until US-006; the web-slice tests own them. |
| AC4 | `SecurityIT.shouldReturn403AccessDeniedWhenUserDeletes` (full shape, no challenge header), `shouldNotReturn403WhenAdminDeletes`, `shouldReturn401ForAnonymousDelete`. "Before any handler" is only provable in the web slice (counter) until US-009 adds a real handler. |
| AC5 | `security.feature` (health, api-docs, swagger-config, swagger-ui index; GET and HEAD of `/abc1234` and `/ab` not 401/403, no challenge); `SecurityIT.shouldNotBlockShortCodePathsForAnonymousGetAndHead`, `shouldServePublicInfrastructurePathsAnonymously`, `shouldRedirectSwaggerUiHtmlAnonymouslyToUiIndex`, `shouldRequireAuthenticationForNonRedirectMethodsOnSingleSegment`, `shouldRequireAuthenticationForApiAndActuatorPrefixes` |
| AC7 | `SecurityIT.shouldSetNoCookieOnAnyResponse` plus the per-response `Set-Cookie` check inside every request helper; `shouldAcceptAuthenticatedPostWithoutCsrfToken` |
| AC8 | `SecurityIT` 401 and 403 tests assert exactly the keys `{type,title,status,detail,instance,errorCode}` with `type` `about:blank` |
| Bypass pins | `shouldNeverLetEncodedOrAlteredApiAndActuatorPathsPassSecurityAnonymously` (16 paths, each 400 or 401), `shouldNotLetUserReachDeleteHandlerThroughPathVariants` (9 paths, exact status each: 403 or 400; the trailing-slash and case-variant rows are 403 under D3 and D57), `shouldRejectEncodedLineBreaksInApiPathsBeforeRouting` (3 encoded CR/LF paths, 400) |
| Authenticated 404 | `shouldReturn404NotUnauthorizedForAuthenticatedCallerOnMissingResource`, `shouldReturn404ForAuthenticatedCallerOnUnexposedActuatorEndpoint`. These show an authenticated caller gets 404, not 401. They do **not** isolate the ERROR-dispatch permit rule (design 3.1), which is defence-in-depth and not directly tested: `/error` is already public via `GET /*`, and the context is restored on error dispatch for other methods. |
| AC6, AC9 | Not HTTP-observable; unit tests only (as the story states). |

**Recorded behaviour.** Anonymous `GET` and `HEAD` on `/abc1234`, `/ab`, `/a_b`, `/v3` return **404**, no `WWW-Authenticate`, no `errorCode` (no redirect controller until US-008). Authenticated `GET /actuator/env` returns 404. Encoded or altered `/api` and `/actuator` paths all end in 400 or 401 under real Tomcat.

**Results.** `./mvnw -q verify`, exit 0. Surefire 392 run at first pass (400 after fix rounds), 0 failures, 0 errors, 0 skipped. Failsafe 73 run at first pass, 78 after fix round 1 and unchanged after round 2, 0 failures, 0 errors, 0 skipped (`SecurityIT` 43 then 48, `CucumberIT` 15 = 3 smoke + 12 security scenarios, the rest existing ITs).

**Defects.** None found.

**Observation (resolved in fix round 2).** `DELETE /api/v1/urls/abc/` as USER now returns 403 (ADMIN rule is `DELETE /api/v1/urls/**`, D3), pinned in `SecurityIT`. Case variant `/API/v1/urls/abc` misses the ADMIN rule (matchers are case-sensitive). Resolved in fix round 3 (D57): the final rule is `anyRequest().denyAll()`, so USER gets 403 `ACCESS_DENIED` for `DELETE /API/v1/urls/abc` (pinned in `SecurityIT`), and anonymous gets 401.

**Ambiguities / notes for escalation.**
- The design's AC2/AC3 end-to-end coverage is deferred to US-006 as the story says; this story cannot prove the granted role over HTTP.
- `ProblemDetailAccessDeniedHandler` logs the raw (undecoded) request URI, so an encoded path such as `/%61pi/...` appears as written. This is by design (path only), but a log reader should know it.

## Review log
*(senior-engineer findings per round, and how each was resolved)*

| Round | ID | Severity | Finding | Resolution |
|---|---|---|---|---|
| 1 | R1 | SHOULD | Bad-hash test values all had the wrong length | Fixed. Each value is derived from a real cost-10 hash by one change (`$2x`, missing minor version, `{bcrypt}` prefix, cost 04, cost 12, 59 chars, 61 chars, plus the plaintext marker). Same-length cases assert equal length. Added a test that the source hash is itself accepted. |
| 1 | R2 | SHOULD | Erasure test bypassed `ProviderManager` | Fixed. `shouldNotEraseTheStoredHashAfterALogin` logs in twice through `new ProviderManager(provider)` and asserts both succeed. |
| 1 | R3 | SHOULD | Log-leak test only checked absence | Fixed. It also asserts the captured lines `Authentication failed: GET /api/test-probe (BadCredentialsException)` and `Access denied: DELETE /api/v1/urls/abc1234`. |
| 1 | R4 | SHOULD | `SecurityIT` comment claimed the 404-for-authenticated-caller test proves the ERROR-dispatch permit rule. It does not: `GET /error` is a single public segment (`GET /*`), and for other methods the security context is restored from the request attribute, so the test passes with or without the rule. | Fixed. Comment reworded to claim only "an authenticated caller gets 404, not 401". QA notes corrected. No real-Tomcat test can fail without the rule (a rejected request never reaches `/error`; see design 3.1), so the rule is stated as defence-in-depth and not directly tested. |
| 1 | R5 | NIT | Dead `catch (Exception)` | Fixed. Now catches only `RequestRejectedException`. |
| 1 | R6 | NIT | `isNotIn(200, 202, 204)` on altered DELETE paths cannot fail until US-009 adds a delete handler. | Fixed. Now a `@CsvSource` pinning the exact status per path, each verified from a run: trailing slash 404, `/API/` 404, `;x=1` 400, `//` 400, `%2e%2e` 400, `%2F` 400, `/%61pi/` 403, plus the two CR/LF rows at 400. Revisit each row deliberately when US-009 lands. |
| 1 | R7 | NIT | `URI.create(requestUri)` depends on Tomcat path rules | Comment added in `ProblemDetails.of`. No behaviour change (`instance` is a D56 base key). |
| 1 | R8 | NIT | Encoded CR/LF path inputs were not pinned as regression cases. | Fixed. `/api/v1/urls/%0d%0aX`, `%0aX` and `%0dX` are pinned at 400 for anonymous and USER GET (`shouldRejectEncodedLineBreaksInApiPathsBeforeRouting`), and `%0d%0aX`, `%0aX` for DELETE in the R6 table. Values observed from a run: 400. |
| 1 | R9 | NIT | HSTS test asserted only `max-age` | Fixed. Asserts the exact value `max-age=31536000 ; includeSubDomains` (D37). |
| 1 | R10 | NIT | No rejection counterpart for 101 supplementary code points | Added `shouldRejectHundredAndOneSupplementaryCharacterActor` (create and softDelete). |
| 1 | R11 | NIT | (orchestrator finding) | Fixed by orchestrator. |
| 1 | R12 | NIT | `.env.example` warning not at top | Added a one-line WARNING pointer on line 1. |
| 1 | R13 | NIT | Unexplained temporary in `ShortUrl.create` | Ordering kept; added a comment explaining why `created` is computed before the guard. |
| 1 | R14 | NIT | Inline fully-qualified types | Imported in `SecurityConfigWebMvcTest` (`Locale`) and `UserAccountsConfigTest` (`Arrays`, `PasswordEncoder`, `UserDetailsService`). |

Round 1 build: `./mvnw -q verify` exit 0. Surefire 395 run, 0 failures, 0 errors, 0 skipped. Failsafe 73 run, 0 failures. Merged LINE coverage 232 of 233 (99.6%).
| 2 | R1–R14 | — | Re-review of round-1 fixes | **Resolved**. Verdict **APPROVE** (R4 verified, with the class-Javadoc leftover tracked as R17) |
| 2 | R15 | SHOULD | The ADMIN-only `DELETE /api/v1/urls/*` misses path variants (trailing slash, nested), which fall through to the `/api/**` USER rule. Not exploitable today (no route), but it becomes a privilege escalation if a `/{code}/` route or Spring Security 7's `PathPatternRequestMatcher` arrives | **Open**: changes the approved matcher design; engineer decides at G3 |
| 3 | R15 | SHOULD | Engineer approved option 1. `SecurityConfig` ADMIN rule is now `DELETE /api/v1/urls/**` (D3), same position. `SecurityConfigWebMvcTest` maps probes at `/{code}`, `/{code}/` and `/{code}/x`: USER gets 403 `ACCESS_DENIED` with the call counter at 0, ADMIN reaches the probe on each. Surefire 400 run, 0 failed. | Fixed for trailing-slash and nested variants. QA `SecurityIT` row `DELETE /api/v1/urls/abc/` now returns 403 (pinned 404): QA to flip. |
| 3 | R15 | ESCALATION | Case variant found NOT covered. With a probe mapped at `DELETE /API/v1/urls/{code}`, a USER `DELETE /API/v1/urls/abc1234` returned 204 and the probe counter went to 1. The rule matchers are case-sensitive, so the request misses both the ADMIN and the `/api/**` USER rules and falls to `anyRequest().authenticated()`. Not exploitable today (no such route, and MVC mapping is case-sensitive), but any handler mapped at an upper-case `/API/...` path is reachable by a USER. Not fixed (option 2 territory). No pin test left in the tree. | **Open**: engineer decides; US-009 (real delete handler) must revisit it. |
| 2 | R16 | NIT | The `SecurityIT` comment says MVC "ignores trailing slashes"; the reverse is true | Fixed. Comment now says Spring MVC 6 does not match trailing slashes, and that the `abc/` form is stopped by the ADMIN rule (D3). |
| 2 | R17 | NIT | The `SecurityIT` class Javadoc still lists "ERROR dispatch" | Fixed. Reworded to "error rendering through the real container". |
| 3 | R15 (QA follow-up) | — | `SecurityIT` row `DELETE /api/v1/urls/abc/` as USER pinned at 404 | Flipped to 403 (ADMIN rule `DELETE /api/v1/urls/**`, D3). The other 8 rows re-verified against a real run and unchanged (`/API/v1/urls/abc` 404 with a comment: case variant misses the ADMIN rule, pending engineer decision, US-009 must revisit; `;x=1`, `//`, `%2e%2e`, `%2F`, `%0d%0a`, `%0a` 400; `/%61pi/...` 403). |
| 4 | R15 escalation (fix round 3, engineer-authorised) | SHOULD | Engineer approved option A (D57). `SecurityConfig` final rule changed from `anyRequest().authenticated()` to `anyRequest().denyAll()`; the "Deny by default" comment rewritten and cites D57. Verified in a run: an anonymous unmatched request gets 401 `AUTHENTICATION_REQUIRED` with the Basic challenge through the entry point (Spring's `ExceptionTranslationFilter` sends `AccessDeniedException` for an anonymous user to the entry point), not 403. Authenticated callers get 403 `ACCESS_DENIED`. | Fixed. Only explicitly listed paths reach a handler. |
| 4 | R15 escalation (fix round 3, engineer-authorised) | — | `SecurityConfigWebMvcTest`: test-only probe `DELETE /API/v1/urls/{code}` added, plus test-only `POST /{code}` and `GET /{a}/{b}` probes. New tests (D57): USER `DELETE /API/v1/urls/abc1234` gets 403 `ACCESS_DENIED`, counter 0; ADMIN also 403, counter 0; anonymous gets 401 with challenge, counter 0; USER and ADMIN get 403 `ACCESS_DENIED` on `POST`/`PUT`/`DELETE /abc1234` and `GET /a/b`, counter 0. | Surefire 404 run (400 + 4 new), 0 failed. No existing Surefire expectation changed: every existing pinned unmatched-path case is anonymous (still 401) or asserts only `isNotEqualTo(204)`. |
| 4 | R15 escalation (fix round 3, engineer-authorised) | — | QA `SecurityIT` (not edited): `shouldNotLetUserReachDeleteHandlerThroughPathVariants[2]` (`DELETE /API/v1/urls/abc` as USER) now fails, expected 404 pinned, now 403 `ACCESS_DENIED`. Failsafe 78 run, 1 failed. | QA to flip that row and its comment. |
| 4 | R15 escalation (QA) | — | QA re-pin for D57 | Done. The one changed expectation: `SecurityIT.shouldNotLetUserReachDeleteHandlerThroughPathVariants` row `DELETE /API/v1/urls/abc` as USER, 404 to 403 `ACCESS_DENIED`; the pending-decision comment is replaced by one citing D57 (`denyAll` refuses the case variant). Every other pinned row was re-verified in a full run and is unchanged: the other 8 delete-variant rows, the authenticated 404s (`/api/v1/does-not-exist`, `/actuator/env`), anonymous 400/401 bypass rows. `security.feature` and `SecuritySteps` are anonymous-only and correct under D57 (no edit). `./mvnw -q verify` exit 0: Surefire 400 run, 0 failed; Failsafe 78 run, 0 failed, 0 errors, 0 skipped. |
| 2 | R18 | NIT | `architecture.md` still said the column-width decision was pending G2 | Fixed by the orchestrator (now cites D51) |
| 2 | R19 | NIT | The round-1 build line said Failsafe 73 and 232/233 | Superseded by the orchestrator's verification below |

**Orchestrator verification (2026-09-29):**
- `./mvnw -q clean verify` passed (exit 0).
  - Surefire: 395 run, 0 failed, 0 errors, 0 skipped.
  - Failsafe: 78 run, 0 failed, 0 errors, 0 skipped (includes 15 Cucumber scenarios).
  - Merged LINE coverage: 231/232 (99.6%), with fresh exec files.
- "Using generated security password" appears 0 times in the build log.
- No S-ids, R-ids or `§` references in `src/`.

### Proposed review rules (senior-engineer, US-005; for the engineer to decide)
Round 1:
1. "A negative validation test's input must differ from a known-valid value in exactly the property under test (derive it from a real valid value), so it cannot be rejected for an unrelated reason."
2. "Every "X is not logged" test also asserts that the expected log line was captured, so it cannot pass vacuously."
3. "Behaviour implemented by a wrapper (for example credential erasure in `ProviderManager`) is tested through that wrapper, not the inner component."
4. "Security tests never catch a broad `Exception`; they catch the specific expected type or none."

Round 2:

5. "Every role-restricting URL rule has a test for its trailing-slash, nested-path and case variants against a probe route mapped at that variant. The rule's pattern covers the variants (`/**`), or a method-wide deny rule follows it."
6. "A test pinning an exact status for a handler that doesn't exist yet names the story that must revisit it."
| 5 | R15, R16, R17 | — | Final re-review of fix rounds 2 and 3 (option 1 plus D57) | **Resolved**. Verdict **APPROVE** |
| 5 | R20 | NIT | A `SecurityIT` comment around lines 318–321 misstates what the 403 and 404 rows mean | Open (no fix rounds left) |
| 5 | R21 | NIT | The §3.3 table still showed "404 or 405" for authenticated unmatched paths | Fixed by the orchestrator (table row and sentence updated for D57) |
| 5 | R22 | NIT | The `/API/` row asserts only the 403 status, not the `errorCode` | Open |
| 5 | R23 | NIT | The `SecurityConfig` class Javadoc doesn't list D57 | Open |
| 5 | R24 | NIT | `DELETE_CALLS` also counts the POST and GET probes | Open |
| 5 | R25 | NIT | The QA notes and the round-4 QA row said "Surefire 400"; the real count is 404 | Superseded by the orchestrator's final verification below |

**Orchestrator final verification (2026-09-29):**
- `./mvnw -q clean verify` passed (exit 0).
  - Surefire: **404** run, 0 failed, 0 errors, 0 skipped.
  - Failsafe: 78 run, 0 failed, 0 errors, 0 skipped (includes 15 Cucumber scenarios).
  - Merged LINE coverage: 231/232 (99.6%), with fresh exec files.
- 0 generated-password log lines, and no S-ids, R-ids or `§` references in `src/`.
- The engineer approved G3 and the escalation (option A plus an authorised third round). The final re-review is APPROVE and the build passes. Status: **Done**.

### Proposed review rules (senior-engineer, final re-review)
1. "Every new endpoint in a story names the filter-chain rule that admits it. With `denyAll` as the default (D57), an unlisted path is refused even to ADMIN."
2. "Test counts in story logs come from the Surefire and Failsafe XML reports of a `clean verify` run, never carried over from an earlier round."
