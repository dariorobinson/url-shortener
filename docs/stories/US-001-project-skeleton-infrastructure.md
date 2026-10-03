---
id: US-001
title: Project skeleton and infrastructure
status: Done
plan_task: 1
depends_on: []
requirements: [FR-11, D21, D22, D23, D24, D25, D38, D39, D40, D41, D42, D43]
requires_design_approval: true
---

# US-001: Project skeleton and infrastructure

## User story
As an engineer, I want a buildable Spring Boot project skeleton with Flyway, Testcontainers, and Docker Compose wired up, so that every later story has a working build, database, and test harness to build on.

## Acceptance criteria
- **AC1:** Given a clean checkout with JDK 25 as `JAVA_HOME`, when `./mvnw -q verify` is run, then the build succeeds, including a context-loads smoke test.
- **AC2:** Given the Maven enforcer plugin is configured to require JDK ≥ 25, when the build runs under a JDK < 25, then the build fails fast with a clear enforcer message (no partial compilation).
- **AC3:** Given `docker compose up` starts a `postgres:18.6-alpine` service (D23) with its data volume mounted at `/var/lib/postgresql` (not `/var/lib/postgresql/data` — PostgreSQL 18's data directory layout changed), when the Spring Boot application starts against it with the `local` profile (D24), then `GET /actuator/health` returns `200` with body `{"status":"UP"}`.
- **AC4:** Given a Testcontainers-based integration test extending the shared base class, when it runs, then a real PostgreSQL container starts, Flyway runs against it (even with zero application migrations at this point), and the `flyway_schema_history` table exists.
- **AC5:** Given springdoc-openapi is on the classpath, when `GET /v3/api-docs` is called, then it returns `200` with a valid OpenAPI document (proves wiring; no endpoints are documented yet).
- **AC6:** Given `application.yml` defines exactly the `local` (Docker Compose) and `test` profiles and no `prod` profile (D24 — production configuration comes from environment variables only), when tests run, then they activate the `test` profile, use the Testcontainers-backed database, and never connect to a developer's local Postgres by accident.
- **AC7:** Given the build depends on `cucumber-java`, `cucumber-spring`, `cucumber-junit-platform-engine`, and `junit-platform-suite` (D25), when `./mvnw -q verify` runs, then Surefire executes `*Test` classes and Failsafe separately executes `*IT` classes and a JUnit Platform suite that runs the Cucumber features, including a smoke scenario (e.g. "the application is healthy") that asserts `GET /actuator/health` is `UP`.
- **AC8:** Given the shared Testcontainers PostgreSQL base class introduced for AC4, when a Cucumber step definition class is loaded via `cucumber-spring`'s Spring context, then it reuses the same base/container configuration as `*IT` tests (single container lifecycle, no duplicate context configuration between `*IT` and Cucumber).
- **AC9:** Given the JaCoCo plugin configured with `prepare-agent` (unit) and `prepare-agent-integration` (integration) executions, when `./mvnw -q verify` runs, then Surefire's unit execution data and Failsafe's integration execution data (including Cucumber) are merged into a single `.exec` file and a merged JaCoCo HTML report is produced under the build output directory (D38).
- **AC10:** Given the merged JaCoCo report from AC9, when merged **LINE** coverage is below 70%, then `./mvnw -q verify` fails at the `verify` phase; only the main `UrlShortenerApplication` class is excluded from the coverage calculation, and Lombok-generated code is ignored via `lombok.config`'s `lombok.addLombokGeneratedAnnotation = true` setting (D42).
- **AC11:** Given the Surefire and Failsafe plugin configurations, when test JVMs are forked to run `*Test` or `*IT`/Cucumber classes, then Mockito is loaded via an explicit `-javaagent:` argument pointing at the Mockito jar, not dynamic self-attachment (D43).
- **AC12:** Given `TestcontainersConfiguration`, when a PostgreSQL container is started for any test (Surefire slice, Failsafe, or Cucumber), then it uses the `postgres:18.6-alpine` image, the same image `docker-compose.yml` uses (D41).

## Tests required
| Type | Behaviour proven | Owner |
|---|---|---|
| Integration (`*IT`) | Context loads; `/actuator/health` returns UP against a Testcontainers PostgreSQL instance (AC3) | qa-tester |
| Integration (`*IT`) | Flyway runs successfully against the Testcontainers database (schema history table present) (AC4) | qa-tester |
| Unit/build | Maven enforcer rejects a sub-25 JDK (verified by pipeline/build configuration; documented, exercised manually by the engineer if not practically automatable) (AC2) | mid-engineer |
| Cucumber | Health smoke scenario runs under Failsafe via the JUnit Platform suite and asserts `UP` (AC7) | qa-tester |
| Integration (`*IT`) | A Cucumber step class and an `*IT` class both extend/use the shared Testcontainers base without starting a second container (AC8) | qa-tester |
| Build | `./mvnw -q verify` passes from a clean checkout, including the context-loads smoke test (AC1) — run independently by the orchestrator | mid-engineer |
| Integration (`*IT`) | `GET /v3/api-docs` returns 200 with a valid OpenAPI document (AC5) | qa-tester |
| Integration (`*IT`) | Tests run with the `test` profile active and a Testcontainers JDBC URL, never a localhost datasource (AC6) | qa-tester |
| Build | Merged JaCoCo report (`target/site/jacoco-merged/index.html`) is produced by `./mvnw -q verify`, combining Surefire and Failsafe (including Cucumber) execution data (AC9) | mid-engineer |
| Build | Build fails when merged LINE coverage drops below 70%, verified by a throwaway local spike (temporarily lowering coverage) that is not committed; only `UrlShortenerApplication` is excluded and Lombok-generated code is filtered via `lombok.config` (AC10) | mid-engineer |
| Unit/build | Surefire and Failsafe `argLine` includes an explicit `-javaagent:` pointing at the Mockito jar (AC11) | mid-engineer |
| Unit/build | `TestcontainersConfiguration`'s configured image resolves to `postgres:18.6-alpine`, matching `docker-compose.yml` (AC12) | mid-engineer |

## Out of scope
- Any domain schema (V1 migration) — see US-002.
- Security configuration — see US-005.
- Production Dockerfile hardening (multi-stage, non-root) and the app service in Compose — see US-014.

## Risks
- JDK 25 is very new; some third-party libraries (e.g. springdoc, Lombok) may lag in official support and need a specific minimum version. D22 records the versions chosen (Lombok 1.18.46 etc.), mitigating this, but first-build friction is still possible.
- PostgreSQL 18's data directory layout change (D23) means any Compose volume or backup tooling written against the old `/var/lib/postgresql/data` path will silently miss data; the Compose file must use the new path consistently.

## Open questions
- None.

## Design note

*Architect, 2026-09-29. Status: **approved at G2 (2026-09-29)**. The engineer approved E1–E5 and D-R1 to D-R7 as written, recorded as D39–D43 in `docs/requirements.md`. E2 is applied: see US-001 AC9–AC12; US-014 AC5/AC7 have moved here.*

### 0. Engineer decisions required at G2

| # | Decision | Recommendation | Blocking? |
|---|---|---|---|
| **E1** | Cucumber 7.34.9 (D22) is **incompatible** with the JUnit version Boot 3.5.16 manages (see §1). | Override `junit-jupiter.version` to **5.14.4**, the same JUnit that Spring Framework 6.2.19 (Boot 3.5.16's Framework) builds against. | **Yes.** Without a fix, the Cucumber engine fails during discovery and AC7 cannot pass. |
| **E2** | Move the JaCoCo plugin and the 70% gate from US-014 into US-001 (§9). | Move it. | No, but the gate affects every story's lifecycle. |
| **E3** | Which image Testcontainers uses (the story's existing open question). | `postgres:18.6-alpine`, same as Compose. | No. The default is in one constant. |
| **E4** | Let JaCoCo ignore Lombok-generated methods (`lombok.addLombokGeneratedAnnotation`). This can be read as a second exclusion beyond D38. | Enable it. | No. |
| **E5** | Explicit Mockito Java agent on JDK 25 (§3.6). | Enable it. | No. |

If approved, E2 changes story scope: US-001 would gain a coverage-gate AC, and US-014 AC5/AC7 would become regression checks. The planner/orchestrator owns those edits; the architect doesn't edit ACs.

### 1. Version verification (D22)

Checked on 2026-09-29 against the published `spring-boot-dependencies-3.5.16.pom`, Maven Central metadata, and the vendors' own release notes (sources in §13).

| Item | D22 value | Verified | Result |
|---|---|---|---|
| Spring Boot | 3.5.16 | Exists. System requirements: "Java 17 … compatible up to and including Java 25", Maven ≥ 3.6.3 | OK |
| Lombok (Boot-managed) | 1.18.46 | `lombok.version=1.18.46`. JDK 25 support since 1.18.40; 1.18.46 (2026-04-22) adds JDK 26 | OK |
| Hibernate | 6.6.53 | `hibernate.version=6.6.53.Final` | OK |
| Spring Security | 6.5.11 | `spring-security.version=6.5.11` (not used until US-005) | OK |
| Flyway | 11.7.2 | `flyway.version=11.7.2`, which manages `flyway-core` and `flyway-database-postgresql` | OK |
| PostgreSQL JDBC | 42.7.11 | `postgresql.version=42.7.11` | OK |
| Testcontainers | 1.21.4 | `testcontainers.version=1.21.4`. Release note: "makes version 1.21.x work with recent Docker Engine changes" | OK |
| springdoc-openapi | 2.8.17 | Present on Maven Central. Its own POM's parent is `spring-boot-starter-parent:3.5.13`, i.e. built for the Boot 3.5 line (springdoc 3.x targets Boot 4) | OK |
| Cucumber BOM | 7.34.9 | Exists (released 2026-09-22). **Its `cucumber-junit-platform-engine` imports `org.junit.platform.engine.support.discovery.DiscoveryIssueReporter`, which is `@API(since = "1.13")`. It is built against JUnit 5.14.2.** Boot 3.5.16 manages `junit-jupiter.version=5.12.2` (Platform 1.12.2), and that managed version takes precedence over Cucumber's transitive version | **DISCREPANCY → E1** |
| JaCoCo | 0.8.15 | Released 2026-06-04. Official Java 25 support since 0.8.14. **Not managed by Boot**, so it needs an explicit version (matches D22's "explicit") | OK |
| Maven | 3.9.16 | Present in `org/apache/maven/apache-maven` metadata. Maven Wrapper plugin current release: 3.3.4 | OK |
| `postgres:18.6-alpine` | D23 | Tag exists on Docker Hub (updated 2026-09-21, multi-arch incl. arm64). PG 18 image sets `PGDATA=/var/lib/postgresql/18/docker`, so mounting `/var/lib/postgresql` is correct | OK |

Other versions Boot 3.5.16 manages that this design relies on: Spring Framework 6.2.19, JUnit 5.12.2 (see E1), Mockito 5.17.0, Byte Buddy 1.17.8 (Java 25 class files since 1.17.5), REST Assured 5.5.7 (not used), Surefire/Failsafe 3.5.6, Enforcer 3.5.0, Compiler 3.14.1. Boot does **not** manage JaCoCo, Cucumber, or springdoc.

**E1 options.** I have not substituted any version. The choice is the engineer's.

```
Recommendation: Option A — add <junit-jupiter.version>5.14.4</junit-jupiter.version> to pom.xml <properties>
                (Boot's supported mechanism for overriding a managed version). Keep every D22 version unchanged.
Reason:         Spring Framework 6.2.19 (the Framework Boot 3.5.16 ships) itself builds against
                org.junit:junit-bom:5.14.4 (framework-platform.gradle at tag v6.2.19), and Cucumber 7.34.9 needs
                Platform ≥ 1.13. 5.14.4 is the one version that matches both.
Alternative:    Option B — downgrade Cucumber to the last release on JUnit Platform 1.12.x (7.22.x/7.23.0; 7.24.0
                moved to Platform 1.13.3). This changes a D22 version and loses ~18 months of Cucumber fixes.
Trade-off:      Option A diverges from Boot's tested JUnit (5.12.2), but only towards the version Spring Framework
                itself tests with. The US-001 smoke tests exercise SpringExtension, Testcontainers, and the
                Cucumber engine, so an incompatibility would show up at once.
```

### 2. Files to create

| Path | Owner | Notes |
|---|---|---|
| `pom.xml` | mid-engineer | §3 |
| `mvnw`, `mvnw.cmd`, `.mvn/wrapper/maven-wrapper.properties` | mid-engineer | §4. `mvnw` must be executable in git |
| `lombok.config` (repo root) | mid-engineer | Only if E4 is approved (§9.3) |
| `src/main/java/com/schwab/urlshortener/UrlShortenerApplication.java` | mid-engineer | `@SpringBootApplication` + `main`. No other logic |
| `src/main/java/com/schwab/urlshortener/config/ClockConfig.java` | mid-engineer | §5 |
| `src/main/resources/application.yml`, `application-local.yml` | mid-engineer | §6 |
| `src/main/resources/db/migration/.gitkeep` | mid-engineer | So the Flyway location exists before US-002 adds `V1__…` |
| `docker-compose.yml` | mid-engineer | §7 |
| `README.md`: fill "Quick start" and "Running tests" | mid-engineer | §7 commands. Remove the *(pending — Task 1)* markers |
| `src/test/resources/application-test.yml` | mid-engineer | §6 |
| `src/test/java/com/schwab/urlshortener/support/TestcontainersConfiguration.java` | mid-engineer | §8.1, shared by Surefire and Failsafe tests |
| `src/test/java/com/schwab/urlshortener/support/RepositoryTest.java` | mid-engineer | §8.3, meta-annotation for later `@DataJpaTest` classes |
| `src/test/java/com/schwab/urlshortener/config/ClockConfigTest.java` | mid-engineer | Unit test |
| `src/test/java/com/schwab/urlshortener/support/PostgresRepositorySliceTest.java` | mid-engineer | §8.3, proves the Surefire slice runs against Testcontainers |
| `src/test/java/com/schwab/urlshortener/support/IntegrationTestBase.java` | qa-tester | §8.2 |
| `src/test/java/com/schwab/urlshortener/cucumber/CucumberSpringConfiguration.java` | qa-tester | §8.4 |
| `src/test/java/com/schwab/urlshortener/cucumber/CucumberIT.java` | qa-tester | §8.4, suite class |
| `src/test/java/com/schwab/urlshortener/cucumber/*Steps.java` | qa-tester | Step definitions |
| `src/test/resources/features/smoke.feature` | qa-tester | §8.4 |
| `src/test/resources/junit-platform.properties` | qa-tester | §8.4 |
| `src/test/java/com/schwab/urlshortener/**/*IT.java` | qa-tester | §11 |

Ownership follows `CLAUDE.md`: `*IT.java` and Cucumber are qa-tester's; `*Test.java` and the shared container config are mid-engineer's. The mid-engineer implements first. The build must pass with the Cucumber dependencies present and no suite class yet.

**Not added in US-001:** `spring-boot-starter-security` (D-R1), `spring-boot-docker-compose`, `spring-boot-devtools`, `rest-assured` (D-R3), `org.testcontainers:junit-jupiter` (not needed, because containers are Spring beans rather than `@Container` fields), H2.

### 3. `pom.xml`

#### 3.1 Coordinates, parent, properties

```xml
<parent>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-parent</artifactId>
  <version>3.5.16</version>
  <relativePath/>
</parent>
<groupId>com.schwab</groupId>
<artifactId>url-shortener</artifactId>
<version>0.0.1-SNAPSHOT</version>
<name>url-shortener</name>

<properties>
  <java.version>25</java.version>                       <!-- parent maps this to maven.compiler.release -->
  <springdoc.version>2.8.17</springdoc.version>
  <cucumber.version>7.34.9</cucumber.version>
  <jacoco.version>0.8.15</jacoco.version>               <!-- only if E2 approved -->
  <junit-jupiter.version>5.14.4</junit-jupiter.version> <!-- only if E1 option A approved -->
  <argLine></argLine>                                   <!-- empty default so @{argLine} is always defined -->
</properties>

<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>io.cucumber</groupId>
      <artifactId>cucumber-bom</artifactId>
      <version>${cucumber.version}</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>
```

#### 3.2 Dependencies (versions omitted = managed by Boot or the Cucumber BOM)

| Scope | groupId:artifactId |
|---|---|
| compile | `org.springframework.boot:spring-boot-starter-web` |
| compile | `org.springframework.boot:spring-boot-starter-data-jpa` |
| compile | `org.springframework.boot:spring-boot-starter-validation` |
| compile | `org.springframework.boot:spring-boot-starter-actuator` |
| compile | `org.flywaydb:flyway-core` |
| compile | `org.flywaydb:flyway-database-postgresql` (Flyway 10+ moved PostgreSQL support to its own module; without it, startup fails with "Unsupported Database") |
| compile | `org.springdoc:springdoc-openapi-starter-webmvc-ui:${springdoc.version}` |
| runtime | `org.postgresql:postgresql` |
| provided/optional | `org.projectlombok:lombok` with `<optional>true</optional>` |
| test | `org.springframework.boot:spring-boot-starter-test` |
| test | `org.springframework.boot:spring-boot-testcontainers` |
| test | `org.testcontainers:postgresql` |
| test | `io.cucumber:cucumber-java`, `io.cucumber:cucumber-spring`, `io.cucumber:cucumber-junit-platform-engine` |
| test | `org.junit.platform:junit-platform-suite` (managed through Boot's `junit-bom` import) |

#### 3.3 Enforcer (AC2)

`enforce` binds to `validate` by default. That is the first lifecycle phase, so the build fails before any compilation.

```xml
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-enforcer-plugin</artifactId>
  <executions>
    <execution>
      <id>enforce-toolchain</id>
      <goals><goal>enforce</goal></goals>
      <configuration>
        <rules>
          <requireJavaVersion>
            <version>[25,)</version>
            <message>This project requires JDK 25 or newer. Point JAVA_HOME at a JDK 25 installation.</message>
          </requireJavaVersion>
          <requireMavenVersion>
            <version>[3.9.16,)</version>
            <message>Maven 3.9.16 or newer is required. Use ./mvnw, which pins the version.</message>
          </requireMavenVersion>
        </rules>
      </configuration>
    </execution>
  </executions>
</plugin>
```

#### 3.4 Lombok annotation processing (required on JDK 25)

Since JDK 23 (JDK-8321314), javac no longer runs annotation processors found implicitly on the classpath. Processors run only when a processor path is given, `-processor` is set, or `-proc:full` is used. The Boot 3.5.16 parent sets only `<parameters>true</parameters>`. **Without the configuration below, Lombok silently does nothing** and every `@Getter`/`@Slf4j`/`@RequiredArgsConstructor` fails to compile from US-002 on.

```xml
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-compiler-plugin</artifactId>
  <configuration>
    <annotationProcessorPaths>
      <path>
        <groupId>org.projectlombok</groupId>
        <artifactId>lombok</artifactId>
        <version>${lombok.version}</version>
      </path>
    </annotationProcessorPaths>
  </configuration>
</plugin>
```

`spring-boot-maven-plugin`: add `<excludes><exclude><groupId>org.projectlombok</groupId><artifactId>lombok</artifactId></exclude></excludes>` so Lombok is not packaged in the fat jar.

#### 3.5 Surefire vs Failsafe (D25)

The Boot parent already binds Failsafe `integration-test` and `verify`, and sets `classesDirectory` to `target/classes`, so tests run against classes rather than the repackaged jar. Only configuration is added here. Includes are set explicitly so the convention is exact: a class named `FooTests` is **not** run.

```xml
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-surefire-plugin</artifactId>
  <configuration>
    <includes><include>**/*Test.java</include></includes>
    <argLine>@{argLine} -javaagent:${org.mockito:mockito-core:jar}</argLine>   <!-- drop the -javaagent part if E5 declined -->
    <excludedEnvironmentVariables>
      <excludedEnvironmentVariable>SPRING_PROFILES_ACTIVE</excludedEnvironmentVariable>
      <excludedEnvironmentVariable>SPRING_DATASOURCE_URL</excludedEnvironmentVariable>
      <excludedEnvironmentVariable>SPRING_DATASOURCE_USERNAME</excludedEnvironmentVariable>
      <excludedEnvironmentVariable>SPRING_DATASOURCE_PASSWORD</excludedEnvironmentVariable>
    </excludedEnvironmentVariables>
  </configuration>
</plugin>
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-failsafe-plugin</artifactId>
  <configuration>
    <includes><include>**/*IT.java</include></includes>
    <argLine>@{argLine} -javaagent:${org.mockito:mockito-core:jar}</argLine>
    <excludedEnvironmentVariables>  <!-- same four entries as Surefire --> </excludedEnvironmentVariables>
  </configuration>
</plugin>
```

`excludedEnvironmentVariables` (Surefire/Failsafe ≥ 3.0.0-M4) strips a developer's exported datasource or profile variables from the forked test JVM. This is the build-level half of AC6.

#### 3.6 Mockito agent (E5)

Mockito's documentation (§0.3) says that from Java 21 the inline mock maker may not work without explicit instrumentation. On JDK 25, self-attach still works but prints a warning, and a future JDK may block it. Add `maven-dependency-plugin` with an execution of goal `properties` (default phase `initialize`). This exposes `${org.mockito:mockito-core:jar}` for the `argLine`s above. Expect a harmless JVM "Sharing is only supported for boot loader classes" CDS warning.

#### 3.7 JaCoCo

See §9. Its `prepare-agent` goals set the `argLine` property that `@{argLine}` picks up.

### 4. Maven Wrapper

Generate once with any locally installed Maven (the plugin version is pinned so the output is reproducible):

```
mvn -N org.apache.maven.plugins:maven-wrapper-plugin:3.3.4:wrapper -Dmaven=3.9.16
```

- Use the default **`only-script`** type. No `maven-wrapper.jar` binary gets committed. The existing `.gitignore` line `!.mvn/wrapper/maven-wrapper.jar` becomes unused but harmless.
- `.mvn/wrapper/maven-wrapper.properties` must contain `distributionUrl=…/apache-maven/3.9.16/apache-maven-3.9.16-bin.zip`.
- `mvnw` needs the executable bit in git (`git update-index --chmod=+x mvnw`). This is the orchestrator's job at commit time.
- Optional hardening: add `distributionSha256Sum` to `maven-wrapper.properties`, computed from the downloaded zip. Flag it for human review if added.

### 5. Application code

- `UrlShortenerApplication`: `@SpringBootApplication` with `SpringApplication.run(...)`. Nothing else, because it is the only class excluded from coverage (D38).
- `config/ClockConfig` (D-R4):

```java
@Configuration(proxyBeanMethods = false)
class ClockConfig {
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
```

`ClockConfigTest` (unit) asserts the bean is a UTC system clock. Later stories needing controlled time should add a `@Primary` mutable clock to the **shared** integration test configuration rather than using per-class `@MockitoBean`/`@TestBean` (see risk R6).

### 6. Configuration (D24)

Production reads datasource settings **only** from Spring Boot's standard relaxed-binding environment variables: `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`. `application.yml` therefore has **no** datasource URL and no defaults. If the variables are missing and no profile supplies them, startup fails loudly ("Failed to configure a DataSource") instead of silently connecting somewhere.

`src/main/resources/application.yml`:

```yaml
# Base configuration, used as-is in production. The datasource is supplied only through
# SPRING_DATASOURCE_URL / SPRING_DATASOURCE_USERNAME / SPRING_DATASOURCE_PASSWORD.
# Profiles: 'local' (Docker Compose) and 'test'. There is deliberately no 'prod' profile (D24).
spring:
  application:
    name: url-shortener
  jpa:
    open-in-view: false
    hibernate:
      ddl-auto: validate
  flyway:
    enabled: true
    locations: classpath:db/migration
server:
  error:
    include-stacktrace: never
    include-message: never
    include-binding-errors: never
    include-exception: false
management:
  endpoints:
    web:
      exposure:
        include: health
  endpoint:
    health:
      show-details: never
      show-components: never
```

`spring.profiles.active` must **not** be set in any file. `show-details`/`show-components: never` are Boot's defaults, but they are stated explicitly because AC3 asserts the exact body `{"status":"UP"}`.

`src/main/resources/application-local.yml` (the only place with defaults; they match `.env.example`):

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/${POSTGRES_DB:urlshortener}
    username: ${POSTGRES_USER:urlshortener}
    password: ${POSTGRES_PASSWORD:change-me}
```

`src/test/resources/application-test.yml` lives in the test classpath so it never ships in the jar. It holds **no datasource properties**. The datasource comes only from the `@ServiceConnection` container.

```yaml
# 'test' profile. The datasource is provided exclusively by Testcontainers @ServiceConnection.
spring:
  main:
    banner-mode: off
```

`APP_BASE_URL` is not bound in US-001 (D-R5). `.env.example` needs no change in this story.

### 7. Docker Compose and running locally (D23)

`docker-compose.yml` (Postgres only; the `app` service arrives in US-014):

```yaml
name: url-shortener
services:
  postgres:
    image: postgres:18.6-alpine
    environment:
      POSTGRES_DB: ${POSTGRES_DB:?set POSTGRES_DB in .env}
      POSTGRES_USER: ${POSTGRES_USER:?set POSTGRES_USER in .env}
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD:?set POSTGRES_PASSWORD in .env}
    ports:
      - "127.0.0.1:5432:5432"          # loopback only; the DB is not exposed on the LAN
    volumes:
      - pgdata:/var/lib/postgresql     # PG 18 layout (D23). NOT /var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U \"$${POSTGRES_USER}\" -d \"$${POSTGRES_DB}\""]
      interval: 5s
      timeout: 5s
      retries: 10
      start_period: 10s
volumes:
  pgdata:
```

Values come from `.env` through Compose's automatic variable interpolation. `env_file:` is deliberately not used, so unrelated variables such as `APP_BASE_URL` don't leak into the database container.

How to run in US-001 (goes in README "Quick start"):

```
cp .env.example .env                 # then set POSTGRES_PASSWORD
docker compose up -d postgres        # wait for "healthy": docker compose ps
set -a; . ./.env; set +a             # export the same credentials to the app
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
curl -s http://localhost:8080/actuator/health    # {"status":"UP"}
```

**Flag:** `CLAUDE.md` "Run locally: `docker compose up`" works only after US-014 adds the `app` service. Until then it starts just the database. I recommend the orchestrator raise a CLAUDE.md wording change with the engineer; it is not the architect's file.

### 8. Test infrastructure (D21, D25, AC4, AC6–AC8)

The Testcontainers approach is decision D-R2. The mechanism: one `@TestConfiguration` declares the PostgreSQL container as a Spring bean with `@ServiceConnection`. Test classes reach it only through two carriers: `IntegrationTestBase` for full-stack tests and `@RepositoryTest` for slices.

#### 8.1 `support/TestcontainersConfiguration` (mid-engineer)

```java
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    /** Pending E3. Must match docker-compose.yml. */
    public static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse("postgres:18.6-alpine");

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgresContainer() {
        return new PostgreSQLContainer<>(POSTGRES_IMAGE);
    }
}
```

Do not use `withReuse(true)`: it depends on a per-developer `~/.testcontainers.properties` and leaks state between runs.

#### 8.2 `support/IntegrationTestBase` (qa-tester)

It carries annotations only: no fields, no `@Bean` methods (Boot asserts that a class declaring `@Import` has no bean methods).

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
public abstract class IntegrationTestBase {
}
```

The name matches neither `*Test` nor `*IT`, so neither plugin tries to run it.

**Rule for every subclass (reviewer checks this):** add no context-affecting annotations or fields. That means no `@MockitoBean`/`@MockBean`/`@TestBean`, `@TestPropertySource`, extra `@Import`/`@ActiveProfiles`, `@DirtiesContext`, or `@SpringBootTest(properties=…)`. Any of these changes Spring's context cache key, which starts a second context and a second container.

#### 8.3 Surefire repository tests: `support/RepositoryTest` (mid-engineer)

```java
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@DataJpaTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
public @interface RepositoryTest {
}
```

- In Boot ≥ 3.4 the default for `@AutoConfigureTestDatabase` is `replace = NON_TEST`, which keeps `@ServiceConnection` datasources. So no `replace = NONE` is needed.
- With no H2 on the classpath, a `@DataJpaTest` that forgets `@RepositoryTest` fails loudly rather than falling back to an embedded database. That enforces D21.
- `PostgresRepositorySliceTest` (`@RepositoryTest`): `shouldRunRepositorySliceAgainstTestcontainersPostgres` runs `SHOW server_version` through the injected `DataSource`/`JdbcTemplate` and asserts it starts with `18.` (adjust if E3 changes the image). This proves the slice wiring in US-001 instead of surfacing it as a failure in US-002.

**Container count per build:** Surefire and Failsafe run in separate forked JVMs, so they cannot share a container. The expected steady state is **one container per JVM**:
- **Surefire:** all `@RepositoryTest` classes share one slice context, and so one container.
- **Failsafe:** all `*IT` classes and Cucumber share one full-stack context, and so one container (AC8).

Each additional distinct context configuration adds a container.

#### 8.4 Cucumber (qa-tester)

- **Glue package:** `com.schwab.urlshortener.cucumber`. Step classes must **not** be `@Component` (cucumber-spring rejects that). Exactly one class may carry `@CucumberContextConfiguration`.

```java
@CucumberContextConfiguration
public class CucumberSpringConfiguration extends IntegrationTestBase {
}
```

  `@SpringBootTest` is `@Inherited`. Boot resolves `@Import` through `TestContextAnnotationUtils.findAnnotationDescriptor`, which searches superclasses. So this class and every `*IT` produce the same `MergedContextConfiguration`, and Spring's JVM-wide context cache hands both the same context and the same container bean (AC8).

- **Suite class.** It is named `*IT` so Failsafe's include picks it up.

```java
@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("features")
@ConfigurationParameter(key = Constants.GLUE_PROPERTY_NAME, value = "com.schwab.urlshortener.cucumber")
public class CucumberIT {
}
```

- `src/test/resources/junit-platform.properties`:

```properties
cucumber.publish.quiet=true
cucumber.junit-platform.naming-strategy=long
cucumber.plugin=html:target/cucumber-reports/cucumber.html
```

  Cucumber's README says to use `long` with Surefire/Failsafe ≥ 3.5.4 (Boot manages 3.5.6). Do **not** set `cucumber.features`: combined with the suite engine it runs the features twice.

- **HTTP client:** `TestRestTemplate` (D-R3), autowired into step classes. Under `RANDOM_PORT` it is pre-configured with the server's root URI and does not throw on 4xx/5xx.
- **`smoke.feature`** (business language; QA owns the wording). At minimum:
  - "The application is healthy": `GET /actuator/health` → 200, body exactly `{"status":"UP"}` (parse the JSON and compare; don't compare raw strings).
  - Per the CLAUDE.md rule "every API acceptance criterion has a Cucumber scenario", I also recommend a scenario for `GET /v3/api-docs` → 200.

#### 8.5 Proving AC8 (single context, single container)

This is a suggested mechanism; QA owns the implementation. A test-only `support/SharedContainerProbe` has a static `AtomicReference<String>` and a method `recordOrVerify(String containerId)`. The method sets the value if it is empty, then asserts that the given ID equals the stored one.

Both a `*IT` (`SharedTestEnvironmentIT`) and a Cucumber step inject the `PostgreSQLContainer<?>` bean and call the probe with `getContainerId()`. Surefire/Failsafe default to `forkCount=1, reuseForks=true`, so Jupiter and the suite engine run in one JVM and whichever runs second checks the first. The check holds regardless of execution order. A second context would own a second container bean with a different ID, and the assertion would fail.

### 9. JaCoCo coverage gate (E2)

#### 9.1 Decision

```
Recommendation: Move the JaCoCo plugin and the ≥70% merged LINE gate (D20/D38) into US-001.
Reason:         CLAUDE.md, D38, the orchestrator lifecycle ("orchestrator-run build (incl. 70% coverage gate)"),
                and the senior-engineer checklist all treat the gate as active at every story. With it in US-014,
                twelve stories would claim a gate that doesn't exist, and US-014 would inherit any shortfall it
                can't fix within its own scope (as that story's own risk section notes).
Alternative:    Keep it in US-014 as planned, and have reviewers read coverage manually until then.
Trade-off:      US-001 grows by one plugin block and its scope changes, which requires amending US-001 and
                US-014 ACs (planner). In exchange, coverage regressions are caught in the story that caused them.
```

#### 9.2 Behaviour when there is almost no code

After excluding `UrlShortenerApplication`, US-001 has only `ClockConfig` (about 2 lines, both executed by any context start), so coverage is 100%. If D-R4 is declined, there are **zero** coverable lines. JaCoCo 0.8.15's `Limit.check` then returns no violation when the ratio is `NaN` (0/0) (`if (Double.isNaN(d)) return null;`), so the gate **passes vacuously**. No `haltOnFailure` change is needed; keep its default `true`.

The real hazard runs the other way: if the merged `.exec` file is missing, for example because a misconfigured merge matched nothing, the check does not fail loudly. The gate is then effectively off. So the orchestrator should confirm at each story that `target/site/jacoco-merged/index.html` exists (§12, R4).

#### 9.3 Exact configuration (if E2 approved)

`prepare-agent` (default phase `initialize`) sets `argLine` → `target/jacoco.exec` for Surefire. `prepare-agent-integration` (default phase `pre-integration-test`) resets `argLine` → `target/jacoco-it.exec` for Failsafe. Merge and report are bound to `post-integration-test`, so they run after Failsafe executes, and `check` runs in `verify`. The class exclusion goes only on `report`/`check`: the agent's `excludes` uses a different, dotted-name syntax, and D38 excludes the class from the *calculation*, not from instrumentation.

```xml
<plugin>
  <groupId>org.jacoco</groupId>
  <artifactId>jacoco-maven-plugin</artifactId>
  <version>${jacoco.version}</version>
  <executions>
    <execution>
      <id>prepare-agent-unit</id>
      <goals><goal>prepare-agent</goal></goals>
    </execution>
    <execution>
      <id>prepare-agent-integration</id>
      <goals><goal>prepare-agent-integration</goal></goals>
    </execution>
    <execution>
      <id>merge-unit-and-integration</id>
      <phase>post-integration-test</phase>
      <goals><goal>merge</goal></goals>
      <configuration>
        <fileSets>
          <fileSet>
            <directory>${project.build.directory}</directory>
            <includes>
              <include>jacoco.exec</include>
              <include>jacoco-it.exec</include>
            </includes>
          </fileSet>
        </fileSets>
        <destFile>${project.build.directory}/jacoco-merged.exec</destFile>
      </configuration>
    </execution>
    <execution>
      <id>report-merged</id>
      <phase>post-integration-test</phase>
      <goals><goal>report</goal></goals>
      <configuration>
        <dataFile>${project.build.directory}/jacoco-merged.exec</dataFile>
        <outputDirectory>${project.reporting.outputDirectory}/jacoco-merged</outputDirectory>
        <excludes>
          <exclude>com/schwab/urlshortener/UrlShortenerApplication.class</exclude>
        </excludes>
      </configuration>
    </execution>
    <execution>
      <id>check-merged-line-coverage</id>
      <goals><goal>check</goal></goals>          <!-- default phase: verify -->
      <configuration>
        <dataFile>${project.build.directory}/jacoco-merged.exec</dataFile>
        <excludes>
          <exclude>com/schwab/urlshortener/UrlShortenerApplication.class</exclude>
        </excludes>
        <rules>
          <rule>
            <element>BUNDLE</element>
            <limits>
              <limit>
                <counter>LINE</counter>
                <value>COVEREDRATIO</value>
                <minimum>0.70</minimum>
              </limit>
            </limits>
          </rule>
        </rules>
      </configuration>
    </execution>
  </executions>
</plugin>
```

**E4, Lombok-generated code.** Recommended `lombok.config` at the repo root:

```
config.stopBubbling = true
lombok.addLombokGeneratedAnnotation = true
```

Lombok then marks generated methods `@lombok.Generated`, and JaCoCo filters those out. Without it, generated getters and constructors count as uncovered lines and lower the ratio for reasons unrelated to test quality. This is a filter on generated methods, not a class exclusion. It could still be read as going beyond D38's "only the main Application class is excluded", which is why it needs the engineer's decision.

### 10. Design decisions

**D-R1: No Spring Security in US-001**
```
Recommendation: Leave spring-boot-starter-security out until US-005.
Reason:         Boot 3.5.16 docs: with Security on the classpath and no SecurityFilterChain bean, "all actuators
                other than /health are secured". /actuator/health (AC3) would still return 200, but /v3/api-docs
                (AC5) would be secured by the default chain and return 401 (plus a generated password in the logs),
                so AC5 would fail. Writing a permit-all chain now would pre-empt US-005's design.
Alternative:    Add the starter now with a temporary permitAll SecurityFilterChain.
Trade-off:      US-005 adds the dependency and must re-verify that health and OpenAPI stay public (its AC5 already
                requires this). No throwaway security code.
```

**D-R2: Container as a Spring bean with `@ServiceConnection`, reached through a base class and a meta-annotation**
```
Recommendation: @TestConfiguration + @Bean @ServiceConnection PostgreSQLContainer, imported by IntegrationTestBase
                (full stack) and @RepositoryTest (slices).
Reason:         Boot 3.5 reference docs recommend bean-managed containers when a cached context must stay usable:
                beans are "started before all other beans" and "stopped after the destruction of all other beans",
                "once per application context". With static @Container fields, the JUnit extension stops the
                container after the class while Spring may still cache the context. That is the exact failure mode
                of sharing a context between *IT and Cucumber.
Alternative:    Abstract base class with a static singleton container started in a static initialiser, plus
                @DynamicPropertySource/@ServiceConnection on the static field (one container per JVM regardless of
                context count).
Trade-off:      The bean approach gives one container per distinct context, so container sharing depends on
                subclasses not varying the context (§8.2 rule). The singleton gives one container per JVM even if
                contexts multiply, but the container outlives Spring's shutdown ordering (Ryuk stops it) and hides
                accidental context proliferation instead of making it visible.
```

**D-R3: `TestRestTemplate` rather than REST Assured**
```
Recommendation: TestRestTemplate for *IT and Cucumber HTTP calls.
Reason:         Auto-configured by @SpringBootTest(RANDOM_PORT) with the random port as its root URI; no extra
                dependency; returns 4xx/5xx as ResponseEntity instead of throwing, which suits ProblemDetail
                assertions.
Alternative:    REST Assured 5.5.7 (Boot-managed): fluent given/when/then that reads well in step definitions.
Trade-off:      TestRestTemplate is more verbose. REST Assured pulls in Groovy, whose runtime compatibility with
                JDK 25 I have not verified and would need its own check.
```

**D-R4: Add the `Clock` bean now**
```
Recommendation: Add config/ClockConfig providing Clock.systemUTC() in US-001.
Reason:         CLAUDE.md and the NFRs require an injectable Clock everywhere. Providing it in the skeleton gives
                every later story (US-002 timestamps onward) one agreed bean, not a per-story decision.
Alternative:    Add it in the first story that needs time (US-006 or US-009).
Trade-off:      It is two lines with no consumer yet (slight YAGNI). Deferring risks two stories each defining a Clock.
```

**D-R5: Do not bind `APP_BASE_URL` yet**
```
Recommendation: Defer binding APP_BASE_URL until US-004 (own-host check, D28), its first consumer.
Reason:         No US-001 AC uses it. Binding it now would fix the shape of a properties record (name, validation,
                fail-fast on missing values) before the stories that define its semantics (D28/D33) are designed.
Alternative:    Bind it now as a validated @ConfigurationProperties record that fails fast if missing.
Trade-off:      Until US-004, a missing APP_BASE_URL goes unnoticed. Nothing reads it, so that has no effect.
```

**D-R6: Boot-native datasource environment variables**
```
Recommendation: Production uses SPRING_DATASOURCE_URL/USERNAME/PASSWORD directly; application.yml has no datasource
                entries; defaults exist only in application-local.yml.
Reason:         Zero custom mapping. A missing value fails startup instead of defaulting to localhost. Tests can't
                pick up a developer's local DB: no URL exists outside 'local', those env vars are stripped from test
                JVMs, and @ServiceConnection takes precedence over properties.
Alternative:    Custom names (DB_URL, …) mapped in application.yml via ${DB_URL}.
Trade-off:      Longer variable names in deployment manifests (US-014), in exchange for one fewer indirection.
```

**D-R7: Test harness ownership split**
```
Recommendation: mid-engineer owns TestcontainersConfiguration, @RepositoryTest and the *Test classes;
                qa-tester owns IntegrationTestBase, the Cucumber configuration/suite (CucumberIT), features, steps,
                and every *IT.
Reason:         Follows CLAUDE.md and the agent definitions (*IT and Cucumber belong to QA; mid-engineer must not
                write *IT). The shared container config must exist before the mid-engineer's slice test.
Alternative:    mid-engineer builds the whole harness, including the suite class.
Trade-off:      AC7/AC8 are provable only after QA's pass, so the mid-engineer's build proves AC1, AC2 and the Surefire
                side only.
```

### 11. Acceptance criteria → component → test

| AC | Component(s) | Test / verification | Owner |
|---|---|---|---|
| AC1 | `pom.xml`, wrapper, `UrlShortenerApplication` | Context-loads smoke: `HealthEndpointIT` (full context on Testcontainers) and `PostgresRepositorySliceTest` (Surefire). Clean-checkout `./mvnw -q verify` run independently | qa-tester / mid-engineer; orchestrator runs the build |
| AC2 | Enforcer (§3.3) | Manual: `JAVA_HOME=<JDK 21> ./mvnw -q verify` → fails at `validate` with "This project requires JDK 25…", and `target/classes` is not created. Record the output in Implementation notes | mid-engineer (config); engineer (manual) |
| AC3 | `docker-compose.yml`, `application-local.yml`, actuator config | Automated: `HealthEndpointIT` + `smoke.feature` (`test` profile, Testcontainers). The Compose + `local` part is **manual** (§7 commands) because it is outside `./mvnw verify` | qa-tester; engineer/orchestrator (manual) |
| AC4 | Flyway config, `db/migration/.gitkeep`, `IntegrationTestBase` | `FlywaySchemaHistoryIT`: `SELECT to_regclass('public.flyway_schema_history')` is not null (Flyway 11.7.2 creates the history table even with zero migrations; `Flyway.migrate` calls `schemaHistory.create(false)` whenever the table is absent) | qa-tester |
| AC5 | springdoc dependency | `OpenApiDocsIT`: 200, JSON, `openapi` field starts with `3.`, `info` present. Recommended matching Cucumber scenario | qa-tester |
| AC6 | No datasource in base yml; `application-test.yml`; `excludedEnvironmentVariables`; `@ActiveProfiles("test")` on both carriers | `TestProfileDatasourceIT`: `Environment.getActiveProfiles()` equals `["test"]`, and `((HikariDataSource) dataSource).getJdbcUrl()` equals the injected container's `getJdbcUrl()` | qa-tester (test); mid-engineer (build config) |
| AC7 | Cucumber dependencies, Surefire/Failsafe includes, `CucumberIT`, `junit-platform.properties`, `smoke.feature` | Failsafe runs `CucumberIT` → the "application is healthy" scenario passes. The build log shows Surefire running only `*Test`, and Failsafe running `*IT` plus Cucumber | qa-tester; mid-engineer (plugin config) |
| AC8 | `IntegrationTestBase`, `CucumberSpringConfiguration`, `TestcontainersConfiguration` | `SharedTestEnvironmentIT` + Cucumber step through `SharedContainerProbe` (§8.5) | qa-tester |
| (E2) | JaCoCo (§9.3) | The build writes `target/site/jacoco-merged/index.html`, and `check` passes. Failing below 70% is verified once in a throwaway change that is not committed | mid-engineer; orchestrator |

### 12. Risks for implementer and reviewer

- **R1: E1 unresolved.** If `junit-jupiter.version` stays at Boot's 5.12.2, Failsafe errors with `NoClassDefFoundError: org/junit/platform/engine/support/discovery/DiscoveryIssueReporter` during Cucumber discovery. Don't work around this silently (for example by excluding the engine). Escalate.
- **R2: Lombok silently not running** (§3.4). The symptom is "cannot find symbol" on getters or `log` from US-002 on, not in US-001. Reviewer: confirm `annotationProcessorPaths` is present.
- **R3: Context proliferation.** Any `@MockitoBean`, `@TestPropertySource`, or extra annotation on an `IntegrationTestBase` subclass or on the Cucumber configuration starts another context and another container. That silently breaks AC8's intent and slows the build. Reviewer checks §8.2 in every later story.
- **R4: A silently disabled coverage gate.** With a missing or empty merged `.exec` file, `check` does no useful work. `-q` also hides JaCoCo's "All coverage checks have been met" line. The orchestrator should confirm the merged report exists at each story.
- **R5: The `@{argLine}` chain.** Surefire, Failsafe, JaCoCo, and Mockito all share `argLine`. Use `@{argLine}` (late replacement), never `${argLine}`, and keep the empty `<argLine></argLine>` property. Otherwise dropping JaCoCo could leave a literal `@{argLine}` on the JVM command line (Mockito docs warn that this crashes the fork).
- **R6: Clock overrides in later ITs.** Per-class `@MockitoBean Clock` creates new contexts (R3). Use one shared `@Primary` mutable clock in the shared integration test configuration when a story first needs it.
- **R7: Tool support for PostgreSQL 18.** Flyway 11.7.2 and Hibernate 6.6 may log "newer than tested" warnings for PG 18. These are warnings, not failures. The QA suite on the real image is the mitigation.
- **R8: JDK 25 warnings.** Warnings about Mockito/Byte Buddy agents and CDS sharing are expected and harmless. Treat new "Unsupported class file major version 69" errors as a version defect and escalate them.
- **R9: Docker needed for `./mvnw verify`.** Surefire (`PostgresRepositorySliceTest`) and Failsafe both need a Docker daemon. On machines without Docker the build fails, and that is intended (D21, no H2 fallback).
- **R10: Health body exactness.** Adding health groups, `show-details`, or probes in later stories changes the body AC3 asserts. Keep `{"status":"UP"}` as a regression check.
- **R11: The Compose volume path.** A reviewer must reject `/var/lib/postgresql/data` (D23). With PG 18 the data would sit in `/var/lib/postgresql/18/docker`, outside that mount, and be lost when the container is recreated.

### 13. Open questions and sources

**Open questions (engineer):**
1. E1–E5 above (E1 blocking).
2. E3 is the story's existing Testcontainers image question. The architect recommends `postgres:18.6-alpine`, pending the engineer.
3. For the planner/orchestrator, not the architect: if E2 is approved, amend US-001 (add a coverage-gate AC) and US-014 (AC5/AC7 become regression checks).
4. For the orchestrator, to raise with the engineer: `CLAUDE.md` "Run locally: `docker compose up`" is accurate only after US-014. Also, `CLAUDE.md` "Key documents" still says "decisions D1–D14", but `requirements.md` now runs to D38.

**Sources checked (2026-09-29):**
- Spring Boot 3.5.16 BOM: https://repo1.maven.org/maven2/org/springframework/boot/spring-boot-dependencies/3.5.16/spring-boot-dependencies-3.5.16.pom
- Boot 3.5.16 starter parent: https://repo1.maven.org/maven2/org/springframework/boot/spring-boot-starter-parent/3.5.16/spring-boot-starter-parent-3.5.16.pom
- Boot 3.5.16 system requirements: https://docs.spring.io/spring-boot/3.5.16/system-requirements.html
- Boot 3.5.16 Testcontainers reference: https://docs.spring.io/spring-boot/3.5.16/reference/testing/testcontainers.html
- Boot 3.5.16 actuator endpoints (security, exposure, health details): https://docs.spring.io/spring-boot/3.5.16/reference/actuator/endpoints.html
- `@AutoConfigureTestDatabase` default `NON_TEST`: https://docs.spring.io/spring-boot/3.5.16/api/java/org/springframework/boot/test/autoconfigure/jdbc/AutoConfigureTestDatabase.html
- Boot `ImportsContextCustomizerFactory` (v3.5.16 source): https://github.com/spring-projects/spring-boot/blob/v3.5.16/spring-boot-project/spring-boot-test/src/main/java/org/springframework/boot/test/context/ImportsContextCustomizerFactory.java
- Spring Framework 6.2.19 JUnit BOM: https://github.com/spring-projects/spring-framework/blob/v6.2.19/framework-platform/framework-platform.gradle
- Cucumber BOM 7.34.9: https://repo1.maven.org/maven2/io/cucumber/cucumber-bom/7.34.9/cucumber-bom-7.34.9.pom
- cucumber-junit-platform-engine 7.34.9 POM: https://repo1.maven.org/maven2/io/cucumber/cucumber-junit-platform-engine/7.34.9/cucumber-junit-platform-engine-7.34.9.pom
- Cucumber changelog (v7.34.9): https://github.com/cucumber/cucumber-jvm/blob/v7.34.9/CHANGELOG.md
- `CucumberTestEngine` (v7.34.9): https://github.com/cucumber/cucumber-jvm/blob/v7.34.9/cucumber-junit-platform-engine/src/main/java/io/cucumber/junit/platform/engine/CucumberTestEngine.java
- Cucumber engine README (v7.34.9): https://github.com/cucumber/cucumber-jvm/blob/v7.34.9/cucumber-junit-platform-engine/README.md
- cucumber-spring `SpringFactory` (v7.34.9): https://github.com/cucumber/cucumber-jvm/blob/v7.34.9/cucumber-spring/src/main/java/io/cucumber/spring/SpringFactory.java
- JUnit `DiscoveryIssueReporter` since 1.13: https://docs.junit.org/5.13.0/api/org.junit.platform.engine/org/junit/platform/engine/support/discovery/DiscoveryIssueReporter.html
- JUnit BOM versions: https://repo1.maven.org/maven2/org/junit/junit-bom/maven-metadata.xml
- springdoc 2.8.17 on Central: https://repo1.maven.org/maven2/org/springdoc/springdoc-openapi-starter-webmvc-ui/maven-metadata.xml
- springdoc v2.8.17 POM: https://github.com/springdoc/springdoc-openapi/blob/v2.8.17/pom.xml
- springdoc site/FAQ: https://springdoc.org/ and https://springdoc.org/faq.html
- JaCoCo change history: https://www.jacoco.org/jacoco/trunk/doc/changes.html
- JaCoCo `check` goal: https://www.jacoco.org/jacoco/trunk/doc/check-mojo.html
- JaCoCo `Limit.java` (v0.8.15): https://github.com/jacoco/jacoco/blob/v0.8.15/org.jacoco.report/src/org/jacoco/report/check/Limit.java
- Lombok changelog: https://projectlombok.org/changelog
- JDK-8321314 (annotation processing default, JDK 23): https://bugs.openjdk.org/browse/JDK-8321314
- Mockito 5.17.0 javadoc §0.3: https://javadoc.io/static/org.mockito/mockito-core/5.17.0/org.mockito/org/mockito/Mockito.html
- Byte Buddy release notes: https://github.com/raphw/byte-buddy/blob/master/release-notes.md
- Maven versions: https://repo1.maven.org/maven2/org/apache/maven/apache-maven/maven-metadata.xml
- Maven Wrapper: https://maven.apache.org/tools/wrapper/
- Failsafe `integration-test` parameters: https://maven.apache.org/surefire/maven-failsafe-plugin/integration-test-mojo.html
- Testcontainers 1.21.4 release: https://github.com/testcontainers/testcontainers-java/releases/tag/1.21.4
- Flyway 11.7.2 `Flyway.java`: https://github.com/flyway/flyway/blob/flyway-11.7.2/flyway-core/src/main/java/org/flywaydb/core/Flyway.java
- Docker Hub `postgres:18.6-alpine`: https://hub.docker.com/_/postgres (tag API) and image docs https://github.com/docker-library/docs/blob/master/postgres/content.md

## Implementation notes
*(mid-engineer: files changed, decisions, items needing human review, test command and result)*

**Files created** (mid-engineer scope only; qa-tester still owns `IntegrationTestBase`, `CucumberSpringConfiguration`, `CucumberIT`, step classes, `smoke.feature`, `junit-platform.properties`, and every `*IT.java`):
- `pom.xml` — exactly per Design note §3 (parent 3.5.16, `junit-jupiter.version=5.14.4`, `jacoco.version=0.8.15`, Cucumber BOM 7.34.9, enforcer JDK `[25,)`/Maven `[3.9.16,)`, Lombok `annotationProcessorPaths`, Surefire/Failsafe includes + `excludedEnvironmentVariables` + Mockito `-javaagent`, `maven-dependency-plugin:properties` execution, JaCoCo `prepare-agent`/`prepare-agent-integration`/`merge`/`report`/`check` per §9.3).
- `mvnw`, `mvnw.cmd`, `.mvn/wrapper/maven-wrapper.properties` — generated with `mvn -N org.apache.maven.plugins:maven-wrapper-plugin:3.3.4:wrapper -Dmaven=3.9.16` (only-script type, no jar committed). `mvnw` was already executable (`-rwxr-xr-x`) after generation; verified with `ls -la`.
- `lombok.config` (repo root) — `config.stopBubbling = true`, `lombok.addLombokGeneratedAnnotation = true` (E4).
- `src/main/java/com/schwab/urlshortener/UrlShortenerApplication.java` — `@SpringBootApplication` + `main` only.
- `src/main/java/com/schwab/urlshortener/config/ClockConfig.java` — package-private `@Configuration`, `Clock.systemUTC()` bean, exactly as Design note §5.
- `src/main/resources/application.yml`, `application-local.yml` — exactly per Design note §6 (no datasource in the base file; `local` defaults match `.env.example`).
- `src/main/resources/db/migration/.gitkeep`.
- `docker-compose.yml` — Postgres-only, `postgres:18.6-alpine`, volume at `/var/lib/postgresql`, loopback-bound port, `pg_isready` healthcheck, exactly per Design note §7.
- `README.md` — filled in "Quick start" and "Running tests" with the exact commands from Design note §7; removed the *(pending — Task 1)* markers.
- `src/test/resources/application-test.yml` — `spring.main.banner-mode: off`, no datasource properties.
- `src/test/java/com/schwab/urlshortener/support/TestcontainersConfiguration.java` — `@TestConfiguration` + `@Bean @ServiceConnection PostgreSQLContainer` on `postgres:18.6-alpine` (E3), `POSTGRES_IMAGE` constant public for later reuse/verification.
- `src/test/java/com/schwab/urlshortener/support/RepositoryTest.java` — meta-annotation (`@DataJpaTest` + `@ActiveProfiles("test")` + `@Import(TestcontainersConfiguration.class)`).
- `src/test/java/com/schwab/urlshortener/support/PostgresRepositorySliceTest.java` — asserts `SHOW server_version` starts with `18.` via an injected `DataSource`.
- `src/test/java/com/schwab/urlshortener/config/ClockConfigTest.java` — asserts the bean's zone is UTC and its instant is close to `Clock.systemUTC()`.

**Deviation from the Design note (small, self-corrected):** §8.3's `PostgresRepositorySliceTest` sketch takes `DataSource`/`JdbcTemplate` as a bare test-method parameter. Plain JUnit 5 has no `ParameterResolver` for arbitrary Spring beans on method parameters unless they carry `@Autowired` (the `SpringExtension` only resolves annotated parameters). Added `@Autowired` to the `DataSource` parameter; no other change. Confirmed by first running the un-annotated version, which failed with `ParameterResolutionException`, then fixing it.

**Decisions/notes:**
- `ClockConfig` and its `@Bean` are package-private, matching the Design note's snippet; `ClockConfigTest` lives in the same package (`com.schwab.urlshortener.config`) so it can construct `new ClockConfig().clock()` directly without a Spring context, keeping it a true unit test.
- Did not add `spring-boot-starter-security`, `spring-boot-docker-compose`, `spring-boot-devtools`, `rest-assured`, `org.testcontainers:junit-jupiter`, or H2 — all explicitly out of scope per Design note §2 "Not added in US-001". No dependencies were added beyond what the Design note lists.
- `.env` was created locally from `.env.example` only to exercise the Docker Compose + local-profile run below, then deleted; it was never committed (`.gitignore` already excludes `.env`).

**JaCoCo gate behaviour with no `*IT` classes yet (explicitly requested verification):**
- A clean `./mvnw -q verify` produces `target/jacoco.exec` (Surefire) but **no** `target/jacoco-it.exec`, because `prepare-agent-integration` only sets the `argLine` property; the agent never writes a file unless a forked JVM actually runs (there are zero `*IT` classes yet, so Failsafe forks no JVM). The `merge` execution's `fileSet` `<includes>` lists both `jacoco.exec` and `jacoco-it.exec`; when the second is absent, the merge silently uses only what exists (no error) and still produces `target/jacoco-merged.exec` and `target/site/jacoco-merged/index.html`. `check` then runs against that merged file. This matches Design note §9.2's stated risk/behaviour (R4): the gate is not "off", it just measures only what data exists.
- I verified the gate is *not* silently disabled once integration data exists: I temporarily added a throwaway `com.schwab.urlshortener.scratch.ScratchSmokeIT` (never committed, deleted immediately after the check) and reran `./mvnw -q verify`. `target/jacoco-it.exec` was created (32,696 bytes) and `target/jacoco-merged.exec` grew accordingly (441,611 → 442,111 bytes), confirming Failsafe execution data is captured and merged as soon as a `*IT` class exists. Deleted the scratch class before the final build.
- I also verified AC10's failure path with a second throwaway spike: added an uncovered package-private class (`ScratchUncoveredSpike`, 10 trivial uncovered methods, never committed) alongside `ClockConfig`. `./mvnw -q verify` then failed at `verify` with `Failed to execute goal org.jacoco:jacoco-maven-plugin:0.8.15:check (check-merged-line-coverage) on project url-shortener: Coverage checks have not been met.` Removed the spike and reran; the clean build passes again with 100% line coverage on the only coverable class (`ClockConfig`, 2/2 lines — `UrlShortenerApplication` is excluded per D38, and there is no other application code yet).

**Test command and result:**
```
./mvnw -q verify
```
Exit code 0. Surefire: `Tests run: 2, Failures: 0, Errors: 0, Skipped: 0` (`ClockConfigTest`, `PostgresRepositorySliceTest`). `target/site/jacoco-merged/index.html` exists; merged line coverage 2/2 (100%) on `ClockConfig`, the only non-excluded, non-Lombok-generated coverable class at this point. Confirmed via `mvn -X` that the effective Surefire/Failsafe `argLine` is `-javaagent:.../org.jacoco.agent-0.8.15-runtime.jar=... -javaagent:.../mockito-core-5.17.0.jar` (AC9, AC11).

**Manual local run (Design note §7 / AC3, AC5), performed once and then torn down:**
```
cp .env.example .env                 # set POSTGRES_PASSWORD (kept default from .env.example)
docker compose up -d postgres        # → healthy after ~1 attempt
set -a; . ./.env; set +a
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
curl -s http://localhost:8080/actuator/health   # {"status":"UP"}
curl -s http://localhost:8080/v3/api-docs       # {"openapi":"3.1.0","info":{...},"paths":{},"components":{}}
```
Both returned 200 with the expected bodies. App stopped, `docker compose down` removed the container/network, `.env` deleted afterward (never committed).

**AC2 (enforcer, manual/documented per Tests-required table):** not exercised with an actual sub-25 JDK in this environment (only JDK 25 is installed); the `requireJavaVersion` rule binds to `validate` (first lifecycle phase) as required by AC2, and its wording matches the story text exactly. Recommend the engineer or orchestrator run `JAVA_HOME=<JDK 21> ./mvnw -q verify` once, if a JDK < 25 is available, to confirm the exact failure message and that `target/classes` is not created; I could not do this here since no older JDK is installed on this machine.

**Items needing extra human review:**
- The `maven-wrapper-plugin:3.3.4:wrapper` output was reviewed but `distributionSha256Sum` was **not** added to `.mvn/wrapper/maven-wrapper.properties` (Design note §4 "optional hardening... flag it for human review if added" — I did not add it, so no flag needed, but noting the option was left out).
- AC2's enforcer behavior under a real sub-25 JDK is unverified on this machine (see above) — needs manual confirmation by whoever has a JDK < 25 available, or can be skipped if the engineer accepts the config-only proof.
- The throwaway JaCoCo spikes (scratch `*IT` class and scratch uncovered class) were both deleted before the final build; `git status` was checked afterward to confirm neither is present in the working tree.

### Fix round 1 (mid-engineer, senior-engineer CHANGES_REQUIRED)

**Files changed:**
- `pom.xml` — R1 (`maven-clean-plugin` execution deleting stale `jacoco*.exec` at `initialize`), R3 (`maven-enforcer-plugin` second execution `require-jacoco-merged-exec` at `verify`, plus `coverage.gate.skip` property and a profile keyed on `-Dmaven.test.skip=true`), R6 (six more `excludedEnvironmentVariable` entries on Surefire and Failsafe), R7 (`<includes>com.schwab.urlshortener.*</includes>` on both JaCoCo `prepare-agent*` executions). See Review log rows R1/R3/R6/R7 above for exact configuration and verification.
- `src/test/java/com/schwab/urlshortener/support/TestcontainersImageTest.java` — new (R2/AC12).
- `src/test/java/com/schwab/urlshortener/support/JvmAgentTest.java` — new (R4/AC11).
- `src/test/java/com/schwab/urlshortener/config/ClockConfigTest.java` — simplified assertion (R10).
- `README.md` — prerequisites wording (R12).

**Not changed:** `src/test/java/com/schwab/urlshortener/support/TestcontainersConfiguration.java` — no code change needed for R2; only a new test reads its `POSTGRES_IMAGE` constant.

**Declined findings:** none. Every BLOCKING and every SHOULD/NIT assigned to the mid-engineer was fixed; see the Review log table for how each was resolved and verified.

**Test command and result (after all fixes):**
```
./mvnw -q verify
```
Run three times during this fix round (once to confirm the fix, once to prove no `.exec` accumulation per R1, once after the R3 ordering/failure proof). Final run: exit code 0.
- Surefire (`*Test`): 4 run, 0 failures, 0 errors, 0 skipped — `ClockConfigTest`, `JvmAgentTest`, `TestcontainersImageTest`, `PostgresRepositorySliceTest`.
- Failsafe (`*IT` + Cucumber): 9 run, 0 failures, 0 errors, 0 skipped — `CucumberIT` (3 scenarios), `FlywaySchemaHistoryIT` (1), `HealthEndpointIT` (1), `OpenApiDocsIT` (1), `SharedTestEnvironmentIT` (1), `TestProfileDatasourceIT` (2).
- `target/site/jacoco-merged/index.html`/`jacoco.csv` present; merged LINE coverage 2/2 (100%) on `ClockConfig`, gate passes.
- `target/site/jacoco-merged/jacoco-sessions.html` contains exactly 2 sessions per run (Surefire fork + Failsafe fork), confirming R1's fix.

**Open questions / items for the engineer:** none new. R5 (Cucumber `@SelectPackage` vs `@SelectClasspathResource`) remains routed to the engineer per the reviewer's instruction — not touched here (`CucumberIT` is qa-tester's file).

## QA notes
*(qa-tester: feature files and IT classes, AC-to-test mapping, test results, defects D-<story>-<n>)*

Scenarios and tests were written from the ACs and Design note §8/§11 before reading the mid-engineer's implementation; the implementation was read afterward only to confirm package names, bean types (`PostgreSQLContainer<?>`, `TestcontainersConfiguration`), and to wire step definitions.

**Files created (qa-tester scope, per Design note §2):**
- `src/test/java/com/schwab/urlshortener/support/IntegrationTestBase.java` — exactly per §8.2 (annotations only, no fields/beans).
- `src/test/java/com/schwab/urlshortener/support/SharedContainerProbe.java` — test-only helper implementing the §8.5 mechanism for AC8 (records/verifies a single container ID across the whole Failsafe JVM).
- `src/test/java/com/schwab/urlshortener/support/HealthEndpointIT.java` — AC1 (full-context smoke)/AC3.
- `src/test/java/com/schwab/urlshortener/support/FlywaySchemaHistoryIT.java` — AC4.
- `src/test/java/com/schwab/urlshortener/support/OpenApiDocsIT.java` — AC5.
- `src/test/java/com/schwab/urlshortener/support/TestProfileDatasourceIT.java` — AC6.
- `src/test/java/com/schwab/urlshortener/support/SharedTestEnvironmentIT.java` — AC8 (JUnit side of the shared-container proof).
- `src/test/java/com/schwab/urlshortener/cucumber/CucumberSpringConfiguration.java` — §8.4, extends `IntegrationTestBase`, the sole `@CucumberContextConfiguration` class.
- `src/test/java/com/schwab/urlshortener/cucumber/CucumberIT.java` — §8.4 suite class, exactly the prescribed annotations (`@Suite`, `@IncludeEngines("cucumber")`, `@SelectClasspathResource("features")`, glue `com.schwab.urlshortener.cucumber`).
- `src/test/java/com/schwab/urlshortener/cucumber/SmokeSteps.java` — step definitions for `smoke.feature` (health, OpenAPI, shared-container scenarios); plain class, not `@Component`, per cucumber-spring's requirement.
- `src/test/resources/features/smoke.feature` — three scenarios (health, OpenAPI docs, shared test database container).
- `src/test/resources/junit-platform.properties` — exactly per §8.4 (`cucumber.publish.quiet=true`, `naming-strategy=long`, HTML plugin to `target/cucumber-reports/cucumber.html`).

**AC → test mapping (qa-tester rows only):**

| AC | Test(s) | Result |
|---|---|---|
| AC1 (full-context half) | `HealthEndpointIT.shouldReturnUpStatusFromHealthEndpoint` | Pass |
| AC3 | `HealthEndpointIT.shouldReturnUpStatusFromHealthEndpoint`; Cucumber scenario "The application is healthy" | Pass |
| AC4 | `FlywaySchemaHistoryIT.shouldCreateFlywaySchemaHistoryTable` | Pass |
| AC5 | `OpenApiDocsIT.shouldReturnValidOpenApiDocument`; Cucumber scenario "The API documentation is published" | Pass |
| AC6 | `TestProfileDatasourceIT.shouldActivateOnlyTheTestProfile`, `.shouldUseTheTestcontainersJdbcUrlNotLocalhost` | Pass |
| AC7 | `CucumberIT` (JUnit Platform suite, run by Failsafe) — 3 scenarios, all pass | Pass |
| AC8 | `SharedTestEnvironmentIT.shouldShareTheSameContainerAsCucumberSteps` + Cucumber scenario "The test database container is shared with other integration tests", both via `SharedContainerProbe`; independently confirmed by grepping the build log for `Creating container for image: postgres` — exactly one occurrence in the Surefire JVM (the mid-engineer's `PostgresRepositorySliceTest`) and exactly one in the Failsafe JVM (shared by every `*IT` and by Cucumber) | Pass |

**Test command and results:**
```
./mvnw -q verify
```
Exit code 0.

- Surefire (`*Test`, mid-engineer's scope, unaffected by this work): `ClockConfigTest` 1, `PostgresRepositorySliceTest` 1 → 2 run, 0 failures, 0 errors, 0 skipped.
- Failsafe (`*IT` + Cucumber, qa-tester's scope): `CucumberIT` 3 (Cucumber scenarios: "The application is healthy", "The API documentation is published", "The test database container is shared with other integration tests" — all pass), `FlywaySchemaHistoryIT` 1, `HealthEndpointIT` 1, `OpenApiDocsIT` 1, `SharedTestEnvironmentIT` 1, `TestProfileDatasourceIT` 2 → **9 run, 0 failures, 0 errors, 0 skipped** (6 JUnit + 3 Cucumber scenarios).
- `target/jacoco.exec`, `target/jacoco-it.exec`, and `target/jacoco-merged.exec` all present; `target/site/jacoco-merged/index.html` present, merged LINE coverage 100% (5/5 lines — `ClockConfig` plus the two trivial branches JaCoCo now sees from this story's code), gate passes (AC9/AC10 unaffected by qa-tester's own test code, since only `*Test`/main classes are counted, not `*IT`/Cucumber glue).
- `target/cucumber-reports/cucumber.html` produced.

**Defects found:** none. The implementation matches the approved Design note and all qa-tester-owned ACs pass on the first run.

**Observations (not defects):**
- Both the `cucumber` engine and the `junit-platform-suite` engine log a non-critical discovery warning: "The classpath resource selector 'features' should not be used to select features in a package. Use the package selector with 'features' instead." This is produced by the exact `@SelectClasspathResource("features")` the Design note §8.4 prescribes; the build still passes and all scenarios run and pass. Flagging for awareness only — changing to `@SelectPackage` would silence it, but I kept the design as approved rather than deviating unilaterally.

**Ambiguous/untestable ACs:** none. AC2 (enforcer under a sub-25 JDK), AC9/AC10 (JaCoCo gate mechanics under low coverage), AC11 (Mockito `-javaagent`), and AC12 (Testcontainers image constant) are mid-engineer/build-owned per the Tests-required table and were not re-verified here, other than confirming (as a side effect of a passing `./mvnw -q verify`) that the merged JaCoCo report and gate still work with qa-tester's added test code.

### Fix round 1 (qa-tester, senior-engineer NIT findings R8/R9/R11)

**Files changed:**
- `src/test/java/com/schwab/urlshortener/support/SharedContainerProbe.java` — added `getRecordedContainerId()` (synchronized, read-only) so callers can make an independent assertion against the shared state instead of calling `recordOrVerify` a second time with a value already known to match.
- `src/test/java/com/schwab/urlshortener/cucumber/SmokeSteps.java` — R8: `itIsTheSameTestDatabaseContainer()` now asserts `SharedContainerProbe.getRecordedContainerId()` is non-null and equals this step's own container ID, rather than re-calling `recordOrVerify` with the same value from the same bean. R9: added the `com.fasterxml.jackson.databind.ObjectMapper` import and an `@Autowired private ObjectMapper objectMapper` field (the context's own Jackson bean); both `Then` steps that parse JSON (`theHealthStatusIsExactly`, `theOpenApiDocumentIsAValidOpenApiDocument`) now use it instead of `new ObjectMapper()`.
- `src/test/java/com/schwab/urlshortener/support/FlywaySchemaHistoryIT.java` — R11: replaced the `@Autowired DataSource` field and `new JdbcTemplate(dataSource)` with `@Autowired private JdbcTemplate jdbcTemplate` (Boot auto-configures this bean whenever a `DataSource` is present).

**Files added (optional item, qa-tester's call):**
- `src/test/java/com/schwab/urlshortener/support/JvmAgentIT.java` — `shouldLoadMockitoAsExplicitJavaAgentInTheFailsafeFork()`. The mid-engineer's `JvmAgentTest` (R4) only proves the Surefire fork loads Mockito via an explicit `-javaagent:`; AC11 names both `*Test` and `*IT`/Cucumber forks, and Surefire/Failsafe run in separate forked JVMs, so the Surefire-side proof says nothing about the Failsafe fork that actually runs `*IT` and Cucumber. Added the mirror assertion under Failsafe instead of relying on "both forks share the same `pom.xml` `argLine` configuration" as an implicit guarantee — a future edit to only one plugin's `argLine` would now be caught. Deliberately does not extend `IntegrationTestBase` (inspects only `ManagementFactory.getRuntimeMXBean().getInputArguments()`; needs no Spring context or database, so it cannot add a second container per AC8/§8.2).

**No changes to:** `CucumberIT.java` (R5, `@SelectClasspathResource` vs `@SelectPackage`, explicitly left for the engineer per the instruction for this fix round), `IntegrationTestBase.java`, `TestcontainersConfiguration.java`, or any Cucumber/Spring context configuration — no new `@MockitoBean`, `@TestPropertySource`, or extra `@Import`/`@ActiveProfiles` was added anywhere.

**Declined findings:** none. All three assigned NITs (R8, R9, R11) were fixed as recommended by the reviewer.

**Test command and result:**
```
./mvnw -q verify
```
Run twice after the fixes (including once after adding `JvmAgentIT`). Both runs: exit code 0.
- Surefire (`*Test`): **4 run, 0 failures, 0 errors, 0 skipped** — `ClockConfigTest` 1, `JvmAgentTest` 1, `PostgresRepositorySliceTest` 1, `TestcontainersImageTest` 1.
- Failsafe (`*IT` + Cucumber): **10 run, 0 failures, 0 errors, 0 skipped** — `CucumberIT` 3 (Cucumber scenarios, including "The test database container is shared with other integration tests", which now exercises the fixed R8 assertion), `FlywaySchemaHistoryIT` 1 (now via the autowired `JdbcTemplate`, R11), `HealthEndpointIT` 1, `OpenApiDocsIT` 1, `SharedTestEnvironmentIT` 1, `TestProfileDatasourceIT` 2, `JvmAgentIT` 1 (new).
- `target/site/jacoco-merged/index.html` present; merged LINE coverage gate still passes.

**Defects found in this round:** none. All three fixes are test-code-only clarity/hygiene changes; no production behaviour was exercised differently.

## Review log
*(senior-engineer findings per round, and how each was resolved)*

| Round | ID | Severity | Finding | Resolution |
|---|---|---|---|---|
| 1 | R1 | BLOCKING | JaCoCo `.exec` files accumulate across builds (append=true, no clean), so deleted/weakened test coverage stays "covered" | Fixed with the reviewer's preferred option: added a `maven-clean-plugin` execution (`clean-stale-jacoco-exec-files`) bound to `initialize`, `excludeDefaultDirectories=true`, fileset deleting `target/jacoco*.exec`. Declared before the JaCoCo plugin block so it runs before `prepare-agent-unit` within the `initialize` phase. Verified by running `./mvnw -q verify` twice: `target/site/jacoco-merged/jacoco-sessions.html` contained exactly 2 sessions (one Surefire fork, one Failsafe fork) after each run, with different session IDs each time (`cc373c14`/`bd7c244` → `710fce50`/`7b0358e9`), proving no accumulation |
| 1 | R2 | BLOCKING | AC12 (Testcontainers image matches Compose) had no test | Added `src/test/java/com/schwab/urlshortener/support/TestcontainersImageTest.java` (`shouldUseTheSamePostgresImageAsDockerCompose`): parses `docker-compose.yml` with SnakeYAML (already on the test classpath transitively via `spring-boot-starter`, no new dependency added), reads `services.postgres.image`, asserts it equals `"postgres:18.6-alpine"` and equals `TestcontainersConfiguration.POSTGRES_IMAGE.asCanonicalNameString()` |
| 1 | R3 | SHOULD | Merge/check goals skip silently when `jacoco-merged.exec` is missing — gate can be silently off | Added a second execution to the existing `maven-enforcer-plugin` block: `require-jacoco-merged-exec`, bound to `verify`, rule `requireFilesExist` on `target/jacoco-merged.exec`. `skip` is bound to a new `coverage.gate.skip` property (default `${skipTests}`, overridden to `true` by a profile activated on `-Dmaven.test.skip=true`). Ordering verified with `./mvnw verify` (non-`-q`, plugin goals logged): `jacoco:merge` → `jacoco:report` → **`enforcer:enforce (require-jacoco-merged-exec)`** → `failsafe:verify` → `jacoco:check`, i.e. the enforcer check runs after merge and before check, as required, because `maven-enforcer-plugin` is declared before `jacoco-maven-plugin` in `pom.xml` and both executions bind to `verify`. Proved the failure path with a throwaway command (no file/pom change committed): deleted `target/jacoco-merged.exec` and ran `./mvnw org.apache.maven.plugins:maven-enforcer-plugin:enforce@require-jacoco-merged-exec` directly — build failed with `RequireFilesExist` reporting the missing file |
| 1 | R4 | SHOULD | AC11 (explicit Mockito `-javaagent`) only proven by a one-off `mvn -X` inspection | Added `src/test/java/com/schwab/urlshortener/support/JvmAgentTest.java` (`shouldLoadMockitoAsExplicitJavaAgent`): asserts `ManagementFactory.getRuntimeMXBean().getInputArguments()` contains an entry matching `-javaagent:.*mockito-core-.*\.jar`. Failsafe-side equivalent not added (qa-tester's file per ownership rule); recommend QA add an analogous check in an `*IT` if they want the Failsafe fork covered too — not strictly required since both forks use the same `argLine` configuration in `pom.xml` |
| 1 | R6 | NIT | Harden `excludedEnvironmentVariables` (AC6) | Added `SPRING_FLYWAY_URL`, `SPRING_FLYWAY_USER`, `SPRING_FLYWAY_PASSWORD`, `SPRING_APPLICATION_JSON`, `SPRING_CONFIG_LOCATION`, `SPRING_CONFIG_ADDITIONAL_LOCATION` to both Surefire's and Failsafe's `excludedEnvironmentVariables` |
| 1 | R7 | NIT | Scope JaCoCo agent instrumentation to `com.schwab.urlshortener.*` | Added `<includes><include>com.schwab.urlshortener.*</include></includes>` to both `prepare-agent-unit` and `prepare-agent-integration` executions. Confirmed coverage still reported correctly afterward: `target/site/jacoco-merged/jacoco.csv` still shows `ClockConfig` at 2/2 lines covered (100%), and `check` still passes — report/check operate against `target/classes` (main classes only) regardless of the agent's instrumentation scope, so this is instrumentation-only as intended and doesn't change what `report`/`check` analyze |
| 1 | R10 | NIT | `ClockConfigTest` used a time-window assertion instead of direct equality | Verified `Clock.systemUTC().equals(Clock.systemUTC())` returns `true` (both are `java.time.Clock$SystemClock` instances compared by zone, per the JDK's `java.time.Clock` spec, not JVM-specific behaviour). Simplified the test to `assertThat(clock).isEqualTo(Clock.systemUTC())` |
| 1 | R12 | NIT | README prerequisites said "Maven 3.9+" | Changed to "No local Maven needed — `./mvnw` downloads Maven 3.9.16". Left `./mvnw -q verify` as the documented command; R1's fix uses a `clean`-plugin *execution* bound to `initialize`, not the `clean` lifecycle phase, so the documented command doesn't need to change to include `clean` |
| 1 | R5 | — | `@SelectPackage` in `CucumberIT` (Failsafe discovery warning) | Routed to engineer for a design decision — not mid-engineer's file (`CucumberIT` is qa-tester-owned). Not touched by qa-tester's fix round 1 per explicit instruction |
| 1 | R8 | NIT | `SmokeSteps`'s `Then` step for the shared-container scenario called `SharedContainerProbe.recordOrVerify(...)` a second time with the same value from the same bean in the same scenario, comparing the container to itself and adding nothing beyond the `When` step | Fixed: added `SharedContainerProbe.getRecordedContainerId()` (a synchronized getter, no mutation) and changed the `Then` step (`itIsTheSameTestDatabaseContainer`) to assert the probe's recorded ID is non-null and equals this scenario's own container ID, instead of calling `recordOrVerify` again. The cross-check with `SharedTestEnvironmentIT`/AC8 still holds regardless of run order: if a second context/container exists, either the `When` step's own `recordOrVerify` call throws immediately (order: `*IT` first), or the mismatch surfaces when `SharedTestEnvironmentIT` calls `recordOrVerify` with its own (different) ID later in the same JVM (order: Cucumber first) — the build fails either way, just not always inside this exact step. Verified: `./mvnw -q verify`, `CucumberIT` still 3/3 passing |
| 1 | R9 | NIT | `SmokeSteps.java` used inline fully-qualified `new com.fasterxml.jackson.databind.ObjectMapper()` at two call sites instead of an import | Fixed: added the `ObjectMapper` import and replaced both inline constructions with an `@Autowired private ObjectMapper objectMapper` field sourced from the shared Spring context (Boot's auto-configured Jackson bean), used in both `Then` steps that parse JSON. Verified: `./mvnw -q verify`, `CucumberIT` still 3/3 passing |
| 1 | R11 | NIT | `FlywaySchemaHistoryIT` built `new JdbcTemplate(dataSource)` even though the context provides a `JdbcTemplate` bean (Boot auto-configures one whenever a `DataSource` is present and `spring-jdbc` is on the classpath) | Fixed: replaced the `@Autowired DataSource` field and manual `new JdbcTemplate(dataSource)` with `@Autowired private JdbcTemplate jdbcTemplate`. Verified: `./mvnw -q verify`, `FlywaySchemaHistoryIT.shouldCreateFlywaySchemaHistoryTable` still passes |
| 2 | R1–R4, R6–R12 | — | Re-review of the round-1 fixes | **Resolved**: all verified against the code and builds (clean verify, then verify again without clean; no session buildup; `-Djacoco.skip=true` fails loudly with `RequireFilesExist`). Verdict **APPROVE** |
| 2 | R5 | SHOULD | `@SelectClasspathResource("features")` causes a Cucumber discovery warning | **Open**: waits for the engineer's decision (G3) |
| 2 | N1 | NIT | `coverage.gate.skip` defaults to `${skipTests}`, which is undefined unless set, so it relies on null meaning false | Open: engineer to decide whether it goes into a follow-up |
| 2 | N2 | NIT | Neither the enforcer message nor the README says how to skip the gate on purpose (`-Dcoverage.gate.skip=true`) | Open: engineer to decide whether it goes into a follow-up |
| 2 | N3 | NIT | `TestcontainersImageTest` mixes `File` and `Files` | Open: acceptable as is |
| 2 | N4 | NIT | The Mockito agent regex is duplicated in `JvmAgentTest` and `JvmAgentIT` | Open: acceptable as is |

**Orchestrator verification (2026-09-29):**
- `./mvnw -q clean verify` passed (exit 0): Surefire 4 run, 0 failed; Failsafe 10 run, 0 failed (includes 3 Cucumber scenarios).
- Fresh `jacoco.exec`, `jacoco-it.exec` and `jacoco-merged.exec`; merged LINE coverage 2/2 (100%); one session file.
- Manual check of AC3 and AC5: `docker compose up -d --wait postgres` showed `postgres:18.6-alpine` healthy with its mount at `/var/lib/postgresql`. `./mvnw spring-boot:run -Dspring-boot.run.profiles=local` returned `{"status":"UP"}` 200 for `/actuator/health`, 200 for `/v3/api-docs`, and 404 for `/actuator/env`. The app was stopped and `docker compose down` run afterwards.

**Engineer decision at G3 (2026-09-29, "approve all"):**
- US-001 is accepted and set to Done.
- **AC2** (the enforcer rejects a JDK below 25) is accepted on the configuration as proof (`requireJavaVersion [25,)` bound to `validate`). It was not run on a JDK 21 toolchain.
- R5 (`@SelectPackage("features")`), N1 and N2 are carried into US-002. N3 and N4 are accepted as they are.

### Proposed review rules

These came from the senior-engineer's review output. Its memory notes were never written to a file, so they are copied here verbatim. The engineer decides at G4 whether any of them go into `CLAUDE.md`.

From round 1:
1. "JaCoCo `append=true` without `clean` inflates the merged gate. Check for this whenever the gate config is touched."
2. "`check` and `merge` skip silently when the exec file is missing."
3. "Enforce the §8.2 rule that `IntegrationTestBase` subclasses add nothing that changes the context."
4. "The AC8 probe only compares the Cucumber context with `SharedTestEnvironmentIT`."

From round 2:
5. "To check JaCoCo fixes read-only, run verify twice and count sessions in `jacoco-sessions.html`, and use `-Djacoco.skip=true` to prove the gate fails loudly."
6. "The Flyway/PostgreSQL 18 warning is an accepted risk; don't raise it again as a new finding."

## Post-completion change (engineer-approved; D135; main session, API documentation removal)

- Cucumber scenario `smoke.feature` "The API documentation is published" — **removed**, with the `SmokeSteps` steps `iRequestTheOpenApiDocument`, `theOpenApiDocumentRespondsWithStatus` and `theOpenApiDocumentIsAValidOpenApiDocument` and the `openApiResponse` field.
- The springdoc-openapi dependency was removed from `pom.xml`; every OpenAPI statement in this story is historical.
