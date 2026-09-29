---
id: US-003
title: Short-code generator
status: Done
plan_task: 3
depends_on: [US-001]
requirements: [FR-10, D6, D29]
requires_design_approval: false
---

# US-003: Short-code generator

## User story
As an engineer, I want a configurable, cryptographically random Base62 short-code generator behind an interface, so that the create-URL flow (US-006) can request candidate codes without depending on a concrete implementation.

## Acceptance criteria
- **AC1:** Given `ShortCodeGenerator` is called with default configuration, when `generate()` is invoked, then it returns a string of length 7 matching `^[A-Za-z0-9]{7}$` (D6 charset).
- **AC2:** Given `shortener.code.length` is configured to a value between 3 and 32 inclusive, when `generate()` is invoked, then the returned code has exactly that length.
- **AC3:** Given `shortener.code.length` is configured outside `[3, 32]` (consistent with the `ck_short_url_code_format` bound in US-002), when the application starts, then startup fails with a validation error rather than silently generating codes the database will reject.
- **AC4:** Given `shortener.code.max-attempts` is configured to a value `< 1`, when the application starts, then startup fails with a validation error.
- **AC5:** Given the default implementation uses `java.security.SecureRandom`, when 10,000 codes are generated, then all match the charset/length pattern and no two are identical (statistical uniqueness check, not a correctness guarantee — the DB unique constraint is the guarantee per US-002).

## Tests required
| Type | Behaviour proven | Owner |
|---|---|---|
| Unit | Generated codes match charset and configured length (AC1, AC2) | mid-engineer |
| Unit | Config validation rejects out-of-bounds length and max-attempts at startup (AC3, AC4) | mid-engineer |
| Unit | Statistical sampling shows no collisions across a large batch and full charset coverage over many samples (AC5) | mid-engineer |

## Out of scope
- The retry-on-collision loop and per-attempt transaction boundary — that is service-layer logic in US-006.
- Custom alias handling — see US-004.
- Treating a generated code that matches a reserved word (D29) as a collision and retrying — the generator itself stays a pure function with no knowledge of reserved words; the retry-on-reserved-word-match behaviour is service-layer logic in US-006, alongside the unique-constraint retry loop.

## Risks
- None significant; this is a self-contained, side-effect-free component.

## Open questions
- None.

## Design note
*(architect)*

## Implementation notes
**Files added** (all under `src/main/java/com/schwab/urlshortener/` unless noted)
- `shortcode/ShortCodeGenerator.java` (interface, `String generate()`)
- `shortcode/SecureRandomShortCodeGenerator.java` (Base62 alphabet, D6; constructor `(RandomGenerator random, int length)`; rejects null random with `NullPointerException` and length outside 3-32 with `IllegalArgumentException`; public constants `MIN_LENGTH` = 3 and `MAX_LENGTH` = 32 are the only literals of the bound)
- `config/ShortCodeProperties.java` (`@Validated @ConfigurationProperties("shortener.code")` record; `length` default 7, `@Min(SecureRandomShortCodeGenerator.MIN_LENGTH) @Max(SecureRandomShortCodeGenerator.MAX_LENGTH)`; `maxAttempts` default 5, `@Min(1)`)
- `config/ShortCodeConfig.java` (package-private; no `SecureRandom` bean: the generator bean method takes only `ShortCodeProperties` and builds `new SecureRandom()` inside, enables the properties). The `shortcode` package does not import `config`; the dependency is one-way, config to shortcode
- Tests: `src/test/.../shortcode/SecureRandomShortCodeGeneratorTest.java`, `src/test/.../config/ShortCodePropertiesTest.java`

**Decisions (not covered by a recorded D-id, please review)**
1. Properties record lives in `config/`, next to `ClockConfig`, with the bean wiring; the generator lives in `shortcode/`. Not `application.yml`: defaults come from `@DefaultValue`, so no yml change.
2. `new SecureRandom()` (platform default algorithm), not `getInstanceStrong()` (which can block). It is built inside the generator bean method and not published as a bean (round 3, R1).
3. The generator class is not a Lombok class and not a `@Component`: it is built in `ShortCodeConfig` so the random source and length are plain constructor arguments. `@RequiredArgsConstructor` was not used because the constructor validates its arguments.
4. `max-attempts` is only bound and validated here; nothing consumes it until US-006.
5. Blank (`length=`) and non-numeric values fail startup (binding error); missing values use the defaults. Tests pin this, and they assert the Boot `BindException` for the exact property (and, for range violations, the `BindValidationException` field error) in the cause chain.
6. Round 1: the generator's own length check duplicates the properties bound on purpose, so it is safe when built outside Spring. Null random gives `NullPointerException` (`Objects.requireNonNull`), not `IllegalArgumentException`; say if you prefer the latter.
7. Test strategy for AC5: real `SecureRandom` for the 10,000-code pattern/uniqueness sample (statistical, about 1.4e-5 clash chance, noted in the test); a seeded `java.util.Random(42)` for the 62-character coverage test so it is deterministic; a scripted RNG test proves the index-to-character mapping.
8. Round 3: the 3-32 bound now lives as literals only in `SecureRandomShortCodeGenerator.MIN_LENGTH` / `MAX_LENGTH`; the properties record references them. The class name and its `RandomGenerator` constructor are kept so unit tests can inject a deterministic source.

**Deviations from a design note:** none (story has no design note).

**Human review:** the uniqueness test is theoretically flaky at about 1 in 70,000 runs.

**Command:** `./mvnw -q verify` passed (round 1). Surefire 92 tests, 0 failures/errors; Failsafe 13 tests, 0 failures/errors; merged JaCoCo LINE coverage 100% (67/67 lines, gate 70%).
**Command (round 3):** `./mvnw -q verify` passed. Surefire 92 tests, 0 failures/errors/skips; Failsafe 13 tests, 0 failures/errors/skips; merged JaCoCo LINE coverage 100% (66/66 lines, gate 70%).

## QA notes
**Scope assessment.** No API and no qa-tester rows, so no Cucumber feature and no concurrency or transaction IT. AC1 to AC5 are proven by the mid-engineer's unit tests, and AC3/AC4 (startup failure) by `ApplicationContextRunner` tests, so I wrote no startup-failure scenarios. One gap remained that unit tests cannot cover: the real application context, and the real PostgreSQL constraint. I added one small IT for it.

**Files**
- Added `src/test/java/com/schwab/urlshortener/shortcode/ShortCodeGeneratorWiringIT.java` (extends `IntegrationTestBase`, adds nothing that changes the Spring context, so the single shared context and container are kept).
  - `shouldExposeGeneratorWithDefaultConfigurationInApplicationContext`: the `ShortCodeGenerator` bean exists, is the SecureRandom implementation, and the default properties are length 7 and max-attempts 5 (AC1 default, wiring). The app starts with default configuration.
  - `shouldGenerateCodesAcceptedByTheDatabaseCodeFormatConstraint`: 50 generated codes are inserted into `short_url` and all are accepted by `ck_short_url_code_format` and the unique constraint. This ties the generator to the US-002 schema (the cross-story link named in AC3). The test deletes its own rows afterwards.
- Housekeeping: `src/test/java/com/schwab/urlshortener/support/JvmAgentIT.java` now explains the separate Surefire and Failsafe forks and cites D43 instead of pointing at a design-note section. Behaviour is unchanged.

**AC mapping**
| AC | Proven by |
|---|---|
| AC1 | Unit tests (mid-engineer); default wiring: `ShortCodeGeneratorWiringIT` |
| AC2 | Unit tests (mid-engineer) |
| AC3, AC4 | `ShortCodePropertiesTest` (mid-engineer); no IT by design |
| AC5 | Unit sampling test (mid-engineer) |

**Results.** `./mvnw -q verify` passed. Surefire 87 run, 0 failures/errors/skips. Failsafe 13 run (11 before, plus 2 new), 0 failures/errors/skips. The log shows one application context start for the ITs.

**Fix round 1 (R7).** Corrected the `ShortCodeGeneratorWiringIT` Javadoc (no longer claims AC2). `./mvnw -q verify` passed: Surefire 92 run, Failsafe 13 run, 0 failures/errors/skips.

**Defects:** none.

**Ambiguities:** none. Note: `ShortCodeConfig.shortCodeGenerator` takes a `RandomGenerator` parameter that resolves to the `SecureRandom` bean by type. It works now, but a second `RandomGenerator` bean would make it ambiguous (informational, for the senior-engineer).

## Review log
*(senior-engineer findings per round, and how each was resolved)*

| Round | ID | Severity | Finding | Resolution |
|---|---|---|---|---|
| 1 | R1 | (senior) | How the random source is exposed | awaiting engineer decision (G3); not changed |
| 1 | R2 | SHOULD | Package cycle `shortcode` <-> `config` | Fixed: constructor is `(RandomGenerator, int length)`; `ShortCodeConfig` passes `properties.length()` |
| 1 | R3 | SHOULD | No length validation in the generator | Fixed: rejects length outside 3-32 (`IllegalArgumentException`) and null random (`NullPointerException`); tests for 2, 33, 0, -1 and null |
| 1 | R4 | SHOULD | Startup-failure tests only asserted `hasFailed()` | Fixed: tests walk the cause chain for the Boot `BindException` on the exact property; range tests also require the `BindValidationException` field error (`length` / `maxAttempts`) |
| 1 | R5 | SHOULD | Javadoc lacked decision and constraint reference | Partly fixed: `ShortCodeProperties` Javadoc cites D6 and `ck_short_url_code_format` at the bound. No new D-id created; whether to record one is the engineer's call |
| 1 | R6 | (senior) | Rename the generator class | awaiting engineer decision (G3); depends on R1; not changed |
| 1 | R7 | NIT | `ShortCodeGeneratorWiringIT` Javadoc claimed AC2 | Fixed: Javadoc now states it proves AC1 (defaults), bean wiring and `ck_short_url_code_format`, and that AC2 is not exercised there. No behaviour change; IT compiles and passes with the `(RandomGenerator, int)` constructor |
| 1 | R8 | NIT | Fully qualified `RandomGenerator` in test | Fixed: imported |
| 1 | R9 | NIT | `ShortCodeConfig` visibility | Fixed: package-private; compiles and tests pass |
| 2 | R2, R3, R4, R5 (code), R7, R8, R9 | — | Re-review of round-1 fixes | **Resolved**. Verdict **APPROVE**. R4 was confirmed to fail on any unrelated startup failure |
| 2 | R1, R6, R5 (D-id) | — | How the random source is exposed; class rename; whether code length gets its own D-id | **Open**: engineer decision (G3) |
| 2 | N1 | NIT | The 3–32 bound is duplicated as literals in the constructor and in the properties record | Open: decision at G3 |
| 2 | N2–N5 | NIT | Javadoc line wrap, constant ordering, test helper used inconsistently, Implementation notes numbering and coverage figure | Open: decision at G3 |
| 3 | R1 | (senior) | How the random source is exposed | Fixed per engineer decision: `SecureRandom` bean removed; `shortCodeGenerator(ShortCodeProperties)` builds `new SecureRandom()` inside. `ShortCodePropertiesTest` now asserts exactly one `ShortCodeGenerator` and no `SecureRandom` and no `RandomGenerator` bean. `ShortCodeGeneratorWiringIT` does not use a `SecureRandom` bean (checked, unchanged) |
| 3 | R6 | (senior) | Rename the generator class | Per engineer decision: class name `SecureRandomShortCodeGenerator` kept; constructor still takes `RandomGenerator` for deterministic tests |
| 3 | N1 | NIT | 3-32 bound duplicated as literals | Fixed: `public static final MIN_LENGTH` / `MAX_LENGTH` in the generator; `ShortCodeProperties` uses them in `@Min` / `@Max`; no reverse import |
| 3 | N2 | NIT | Overlong Javadoc line | Fixed: reflowed; D6 and `ck_short_url_code_format` citations kept |
| 3 | N3 | NIT | Constant ordering | Fixed: `ALPHABET`, `MIN_LENGTH`, `MAX_LENGTH` together above the instance fields |
| 3 | N4 | NIT | Test helper used inconsistently | Open by engineer decision; not changed |
| 3 | N5 | NIT | Implementation notes numbering and coverage figure | Fixed: decisions numbered 1-8 consecutively; coverage 66/66 recorded |

**Orchestrator verification (2026-09-29):**
- `./mvnw -q clean verify` passed (exit 0).
- Surefire: 92 run, 0 failed, 0 errors, 0 skipped.
- Failsafe: 13 run, 0 failed, 0 errors, 0 skipped (includes 3 Cucumber scenarios).
- Merged LINE coverage: 66/66, with fresh exec files.
- No `§` or E-id references in `src/`.

### Proposed review rules (senior-engineer, US-003; for the engineer to decide)
Round 1:
1. "Security-sensitive dependencies (random sources, password encoders, key material) are injected by their concrete secure type or built inside the consuming bean method. They are never published as beans of a broad type such as `RandomGenerator`, where a later `@Primary` bean could replace them silently."
2. "Startup-failure tests (`ApplicationContextRunner`) must check the failure cause and the offending property name, not just `hasFailed()`."
3. "Any configuration bound that mirrors a database constraint cites both the D-id and the constraint name at the point where the bound is declared."

Round 2:

4. "Startup-failure tests (`ApplicationContextRunner` or full context) must assert the specific exception type in the cause chain and the exact property or field name, never only `hasFailed()`." (Refines rule 2.)
5. "When a domain bound is checked in more than one application layer, both checks reference one shared constant, not repeated literals."
| 3 | R1, N1, N2, N3, N5 | — | Final re-review (fix round 2) | **Resolved**. Verdict **APPROVE** |
| 3 | N6 | NIT | Recorded coverage was 66/66; the final build is 65/65 | Fixed: see final verification below |
| 3 | N7 | NIT | The public constructor lost its `@throws` Javadoc | Open: fix rounds are used up; proposed as a US-004 carry-over |
| 3 | N8 | NIT | The QA notes "Ambiguities" text about a `RandomGenerator` parameter is out of date | Resolved by the addendum below |

**Addendum to the QA notes (orchestrator, N8):** the `RandomGenerator` injection ambiguity QA noted was resolved by R1 in round 3. `ShortCodeConfig.shortCodeGenerator(ShortCodeProperties)` builds `new SecureRandom()` inside the method, and no random-source bean exists.

**Process note:** in round 3, the mid-engineer reported that `SecureRandomShortCodeGenerator.java` was a stale pre-round-1 version and rewrote it. That report was inaccurate. The orchestrator had read the file before the round and saw the round-1 shape, and a clean build had passed against it. The senior-engineer confirmed that the current file keeps every approved round-1 behaviour. Nothing was lost.

**Orchestrator final verification (2026-09-29):**
- `./mvnw -q clean verify` passed (exit 0).
  - Surefire: 92 run, 0 failed, 0 errors, 0 skipped.
  - Failsafe: 13 run, 0 failed, 0 errors, 0 skipped.
  - Merged LINE coverage: **65/65**.
- The only `SecureRandom` in `src/main` is created inside `ShortCodeConfig.shortCodeGenerator`.
- G3 approved by the engineer; the re-review is APPROVE and the build passed. Status: **Done**.

### Proposed review rules (senior-engineer, US-003 round 3)
1. "Coverage and test-count figures recorded in a story come from the most recent `clean verify` of the final code, not from an earlier round."
2. "Public constructors or factory methods that validate their arguments document each thrown exception with `@throws`."
