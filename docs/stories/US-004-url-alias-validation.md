---
id: US-004
title: URL and alias validation
status: Done
plan_task: 4
depends_on: [US-001]
requirements: [FR-3, FR-7, D6, D11, D28, D29]
requires_design_approval: false
---

# US-004: URL and alias validation

## User story
As an engineer, I want a `UrlValidator` and an `AliasPolicy` that enforce D11 and D6 respectively, so that the create-URL flow (US-006) can reject invalid input before touching the database.

## Acceptance criteria
- **AC1:** Given an absolute URL with scheme `http` or `https` and a host, and length ≤ 2048 characters, when validated, then `UrlValidator` reports it valid.
- **AC2:** Given a URL with a scheme other than `http`/`https` (e.g. `ftp://`, `javascript:`, or a scheme-relative/relative URL with no scheme), when validated, then `UrlValidator` reports it invalid.
- **AC3:** Given a URL longer than 2048 characters, when validated, then `UrlValidator` reports it invalid.
- **AC4:** Given a URL with embedded credentials (e.g. `https://user:pass@host/path`), when validated, then `UrlValidator` reports it invalid.
- **AC5:** Given a URL whose host equals the host of the configured `APP_BASE_URL`, compared case-insensitively and ignoring a trailing dot (D28), when validated, then `UrlValidator` reports it invalid (D11 "no links to this service's own host"). Table-driven cases include: `https://short.example/x` rejected when `APP_BASE_URL=https://short.example`; `HTTPS://SHORT.EXAMPLE./x` rejected (case-insensitive, trailing dot ignored); `https://short.example.evil.com/x` accepted (host is not an exact match, just a suffix); `https://other.example/x` accepted.
- **AC6:** Given an alias of length 3–32 using only `[A-Za-z0-9]`, when validated, then `AliasPolicy` reports it valid.
- **AC7:** Given an alias shorter than 3, longer than 32, or containing any character outside `[A-Za-z0-9]`, when validated, then `AliasPolicy` reports it invalid.
- **AC8:** Given an alias that matches a reserved word from the configurable list (default: `api, actuator, v3, error, health, admin, login, logout, static, assets, docs` — D29), compared case-insensitively, when validated, then `AliasPolicy` reports it invalid. Table-driven cases include: `api` rejected, `API` rejected (case-insensitive match), `admin` rejected, `myapi` accepted (not an exact match), a custom-configured word rejected.

## Tests required
| Type | Behaviour proven | Owner |
|---|---|---|
| Unit | Table-driven cases for AC1–AC5 (`UrlValidator`), including the D28 own-host case-insensitive/trailing-dot table above | mid-engineer |
| Unit | Table-driven cases for AC6–AC8 (`AliasPolicy`), including the D29 case-insensitive reserved-word table above | mid-engineer |

Note: HTTP status codes and `errorCode` values for validation failures are wired and tested in US-006 (create), not here — this story tests the validator/policy components in isolation.

## Out of scope
- Wiring validation failures to HTTP 400 responses — see US-006.
- Treating a reserved-word match on a *generated* code as a collision and retrying — the generator/`AliasPolicy` stay pure lookup/validation components; that retry behaviour is service-layer logic in US-006 (D29).

## Risks
- None beyond what D28/D29 already resolve. If `APP_BASE_URL` is misconfigured at runtime (wrong host), self-referencing links could still pass validation — an operational/configuration risk, not a design ambiguity.

## Open questions
- None. D28 defines "own host" (the `APP_BASE_URL` host, case-insensitive, trailing dot ignored) and D29 defines the reserved-word list, its configurability, and case-insensitive matching, including that a match against a *generated* code is treated as a collision and retried (implemented in US-006).

## Design inputs carried from US-002 (engineer-approved at US-002 G3)
- `AliasPolicy` must reject any alias that doesn't match `^[A-Za-z0-9]{3,32}$` **as submitted**. It must never trim or normalise the input. Include a test for a 32-character alias followed by a trailing space, which must be rejected. The same no-trim rule applies to `UrlValidator` for the 2048-character limit (D11, D47).

## Design note
*(architect)*

## Implementation notes
No design note (engineer approved skipping it); built from the ACs and D6, D11, D24, D28, D29, D33, D47.

**Files (all under `src/main/java/com/schwab/urlshortener/` unless noted)**
- New `validation/HttpUris` (strict parse + host normalisation, shared), `validation/UrlValidator`, `validation/AliasPolicy`.
- New `config/AppProperties` (`app.base-url`), `config/HttpBaseUrl` (package-private constraint), `config/AliasProperties` (`shortener.alias.additional-reserved-words`, D48), `config/ValidationConfig` (wires the two beans).
- Edited `src/main/resources/application-local.yml` and `src/test/resources/application-test.yml`: `app.base-url: http://localhost:8080`. `application.yml` has no default (D24).
- New tests in `src/test/java/com/schwab/urlshortener/`: `validation/UrlValidatorTest`, `validation/AliasPolicyTest`, `config/AppPropertiesTest`.

**Flagged decisions for G3 review**
1. Placement: plain classes in `validation/`, wired as beans in `config/ValidationConfig`. Pure, no DB, no retry (D29 retry stays in US-006).
2. Result type: `boolean isValid(String)`; `null` returns false. No Bean Validation annotations or HTTP mapping on the validators. The only constraint annotation is `@HttpBaseUrl` on the config property.
3. `app.base-url`: validated `@ConfigurationProperties` record; `@NotBlank` plus `@HttpBaseUrl`. The base URL must also have no userinfo (added by me, so a credential cannot leak into `shortUrl`). Paths are allowed, but a query or fragment is rejected, an empty `?` or `#` included (round 3 R3, modified). Missing or invalid values fail startup with a `BindException` on `app` and a `FieldError` on `baseUrl`. Tests cover the exact names.
4. Parsing: strict `java.net.URI`, no trimming. Scheme is case-insensitive. `URI.getHost()` must be non-null and non-empty. Any userinfo is rejected, including `https://@host` and `user@host`.
5. Length: `UrlValidator.MAX_LENGTH = 2048` counted with `codePointCount` on the raw input before parsing. Tests: 2048 accepted, 2049 rejected, 2048 plus a space rejected, a 2048-character URL that is over 2048 bytes accepted, and supplementary code points counted as one.
6. Own host: normalised host = lower-case (Locale.ROOT) with ONE trailing dot removed; port and scheme ignored; exact match. A Turkish default-locale test is included.
7. Alias: no regex. An explicit ASCII loop plus `SecureRandomShortCodeGenerator.MIN_LENGTH/MAX_LENGTH`, so 3-32 lives only in the generator. No regex, so no regex-engine or Unicode class semantics: only `[A-Za-z0-9]` can pass, by construction. (Round 1 R2: an earlier comment wrongly claimed `$` would accept a trailing newline; `String.matches` is a whole-string match and would not.)
8. Reserved words (modified in round 3, D48): the D29 words are a built-in constant, `AliasPolicy.BUILT_IN_RESERVED_WORDS`, always reserved. `shortener.alias.additional-reserved-words` (default empty) only ADDS words and can never remove one. The old `shortener.alias.reserved-words` property is removed with no alias or fallback. Additional words are stripped and lower-cased once (Locale.ROOT) in the `AliasPolicy` constructor; matching is case-insensitive.
9. Blank or null entries in the additional list are ignored (modified in round 3). Non-blank entries are `strip()`ped. An empty additional list is the default and leaves the built-ins active, so D29 can no longer be disabled by configuration. That resolves the old "empty list fails startup?" question (D48).
10. (Round 3, R8) `UrlValidator` rejects input that cannot be encoded as UTF-8 (unpaired surrogates) with a per-call `StandardCharsets.UTF_8.newEncoder().canEncode(url)`; the encoder is created per call because it is not thread-safe.
11. (Round 3, D49) IP-literal, `localhost` and private hosts stay accepted, and IDN hosts stay rejected; both are pinned by regression tests.

**Findings worth knowing**
- `java.net.URI` rejects non-ASCII hosts (IDN) and hosts with an empty label such as `short.example..`. `getHost()` returns null for both, so they are invalid. Punycode hosts are accepted.
- Non-ASCII characters in the path are accepted, because `java.net.URI` allows the "other" character class.
- `ApplicationContextRunner.withPropertyValues` trims values, so a leading-space base URL cannot be tested at startup. It is covered by the `UrlValidator` constructor test, which uses the same parser.

**Open questions (not decided, nothing rejects these today)**
- All three were decided by the engineer: D49 (IP-literal, localhost and private hosts accepted; IDN hosts rejected) and D48 (built-in reserved words, so the list cannot be emptied).

**Human review**
- `HttpUris.parseHttpUri`, and the fact that `UrlValidator` rejects everything `java.net.URI` does.

**Command:** `JAVA_HOME=<JDK 25> ./mvnw -q verify`. Result: BUILD SUCCESS.
- Round 1 result: Surefire 224 run, Failsafe 13 run, merged line coverage 99.1%.
- After round 3 (read from the reports): Surefire 264 run, 0 failed, 0 errors, 0 skipped. Failsafe 18 run, 0 failed, 0 errors, 0 skipped. Merged JaCoCo LINE coverage 120 covered, 1 missed, 99.2% (`target/site/jacoco-merged/jacoco.csv`).

## QA notes
**Assessment.** No API and no Cucumber feature (the story has no HTTP surface; status and `errorCode` wiring is US-006). The unit tables already cover AC1-AC8 exhaustively. One gap they cannot close: the beans are wired from real configuration (`app.base-url`, the default D29 list via `@DefaultValue`). One small IT covers that.

**Added:** `src/test/java/com/schwab/urlshortener/validation/ValidationWiringIT.java` (extends `IntegrationTestBase`, no context changes, 5 tests).

| AC | Black-box proof in the full context |
|---|---|
| AC1 | `shouldApplyUrlRulesInFullContext` (2048-char URL accepted) |
| AC2 | `shouldApplyUrlRulesInFullContext` (`ftp://` rejected) |
| AC3 | `shouldApplyUrlRulesInFullContext` (2048 + trailing space rejected, no trim) |
| AC4 | `shouldApplyUrlRulesInFullContext` (userinfo rejected) |
| AC5 | `shouldRejectOwnHostFromConfiguredBaseUrl...` (host from the test profile, `HTTPS://LOCALHOST./x`, port and scheme ignored); `shouldAcceptHostsThatOnlyResembleTheConfiguredOwnHost` (`localhost.evil.com`, `sub.localhost`) |
| AC6, AC7 | `shouldNotTrimAliasesInFullContext` (32 chars valid, 33 and 2 invalid, 32 + space and `abc\n` invalid) |
| AC8 | `shouldRejectEveryDefaultReservedWordCaseInsensitively` (all 11 default words, lower and upper case; `myapi` accepted) |

The custom-configured reserved word case (AC8) stays unit-only. Testing it here would need a property override and a second context, which the CLAUDE.md rule forbids.

**Results** (`./mvnw -q verify`, JDK 25.0.2, read from the reports): Surefire 224 run, 0 failures, 0 errors, 0 skipped. Failsafe 18 run (13 previous + 5 new), 0 failures, 0 errors, 0 skipped.

**Fix round 1 (R6):** `ValidationWiringIT` now uses `toUpperCase(Locale.ROOT)`; no other locale-default case conversions in `src/test`.

**Defects:** none. Every AC I checked is met (`https://short.example.evil.com/x` accepted, 32 chars + trailing space rejected, trailing newline rejected).

**Ambiguities for the engineer (not interpreted by QA):** the three open questions in the Implementation notes (IP-literal, `localhost` and private hosts accepted; IDN hosts rejected only incidentally; an empty reserved-words list silently disables D29). Behaviour is untested by design because no requirement decides it.

## Review log
*(senior-engineer findings per round, and how each was resolved)*

| Round | ID | Severity | Finding | Resolution |
|---|---|---|---|---|
| 1 | R1 | BLOCKING | `AppPropertiesTest` env-style invalid-value test passed vacuously: `withPropertyValues("APP_BASE_URL=...")` never binds outside a `SystemEnvironmentPropertySource`. | Fixed. Test now uses a `SystemEnvironmentPropertySource` with `ftp://x.example` and asserts cause type, `app`/`baseUrl` and the rejected value; positive counterpart binds a valid `APP_BASE_URL`. The parameterized invalid-value test now also asserts the rejected value. `ShortCodePropertiesTest` checked: every property key is a real dotted key, no env-style keys, and each failure asserts exact property name; not vacuous. |
| 1 | R2 | SHOULD | `AliasPolicy` comment falsely claimed regex `$` accepts a trailing newline. | Fixed. Comment now states the true reason (no regex engine, ASCII-only by construction); flagged decision 7 corrected. |
| 1 | R3 | SHOULD | Base URL with query or fragment accepted. | awaiting engineer decision (G3) |
| 1 | R4 | SHOULD | Bypass inputs not pinned. | Fixed. Added `shouldRejectKnownBypassInputs` (8 inputs) and 3 own-host rejections to the D28 table. |
| 1 | R5 | NIT | MIN_LENGTH / MAX_LENGTH constant placement. | awaiting engineer decision (G3) |
| 1 | R6 | NIT | `ValidationWiringIT` used default-locale `toUpperCase()`; under `tr-TR` `admin` becomes `ADMİN` (non-ASCII, rejected), so the case-insensitivity check proved nothing. | Fixed. Now `toUpperCase(Locale.ROOT)`. Grepped all of `src/test` for `toUpperCase`, `toLowerCase`, `equalsIgnoreCase`, `String.format`: no other locale-default conversions. |
| 1 | R7 | NIT | `https://short.example../x` rejected for null host, not the trailing-dot rule. | Fixed. Moved to the strict-parse table with a comment. |
| 1 | R8 | NIT | Lone surrogates. | awaiting engineer decision (G3) |
| 1 | R9 | NIT | The private constructor of `HttpUris` is the only uncovered line | no action, per reviewer |
| 2 | R1, R2, R4, R6, R7 | — | Re-review of round-1 fixes | **Resolved**. Verdict **APPROVE**. R1 is no longer vacuous: the rejected value is asserted, and a positive env-binding counterpart exists |
| 2 | R3, R5, R8 | SHOULD / NIT / NIT | See round 1 | R3 and R8 fixed in round 3 (below). R5: engineer decided to leave the constants where they are. |
| 3 | R3 | SHOULD | Base URL with query or fragment accepted. | Fixed (engineer-approved). Only the `HttpBaseUrl` validator changed: it rejects `getRawQuery() != null` or `getRawFragment() != null`. Verified with `java.net.URI`: `https://s.example/?` gives raw query `""` and no fragment, `https://s.example/#` gives raw fragment `""` and no query, so a null check catches the empty forms. `HttpUris` and `UrlValidator` parsing of submitted URLs are unchanged. `AppPropertiesTest` invalid table gained `/?x=1`, `/#f`, `/?`, `/#`, `/base?x=1#f` (cause type, `app`/`baseUrl` and rejected value asserted); `https://short.example/base` stays valid. |
| 3 | D48 | decision | Built-in reserved words plus additions. | Done. `AliasPolicy.BUILT_IN_RESERVED_WORDS` holds the D29 list; `shortener.alias.additional-reserved-words` (default empty) only adds; old property removed everywhere (main, yml, tests; no fallback, and a test pins that it is ignored). Tests: built-ins rejected with no config; built-ins rejected with additions; additional word rejected case-insensitively; a built-in listed as an addition is harmless; empty list keeps built-ins. Env binding via `SystemEnvironmentPropertySource`: positive (`SHORTENER_ALIAS_ADDITIONAL_RESERVED_WORDS=promo,Sale` binds) and negative (`SHORTENER_ALIAS_RESERVEDWORDS` binds nothing). Finding: `SHORTENER_ALIAS_ADDITIONALRESERVEDWORDS` (no underscores between words) did NOT bind in the test runner, and neither did an indexed `_0` form; the working name has one underscore per dash. |
| 3 | R8 | NIT | Lone surrogates. | Fixed (engineer-approved). Verified that `java.net.URI` accepts a lone surrogate. `UrlValidator` now rejects strings that fail `newEncoder().canEncode` (new encoder per call). Tests: lone high, lone low, reversed pair, in path and query (rejected); valid supplementary character, both forms (accepted). |
| 3 | US-003 N7 | NIT | `@throws` Javadoc missing on the `SecureRandomShortCodeGenerator(RandomGenerator, int)` constructor. | Fixed. Javadoc restored, nothing else changed in US-003 files. |
| 3 | D49 | regression | IP-literal, localhost and private hosts accepted; IDN rejected. | No production change. Added `shouldAcceptIpLiteralLocalhostAndPrivateHostsPerD49` (`127.0.0.1`, `10.0.0.1`, `192.168.1.1:8080`, `[::1]`, `localhost`, `localhost.test`) and `shouldRejectNonAsciiIdnHostsPerD49` (`bücher.example`, `münchen.example`). |
| 2 | R10 | NIT | Round-1 severities were lost in the log | Fixed by the orchestrator (severities restored above) |

**Orchestrator verification (2026-09-29):**
- `./mvnw -q clean verify` passed (exit 0).
  - Surefire: 235 run, 0 failed, 0 errors, 0 skipped.
  - Failsafe: 18 run, 0 failed, 0 errors, 0 skipped (includes 3 Cucumber scenarios).
  - Merged LINE coverage: 110/111 (99.1%). The only miss is the private constructor of `HttpUris`.
- No `§` or E-id references in `src/`.
- The senior-engineer's round-1 claim that CLAUDE.md lacks the startup-failure rule was wrong. The rule is at line 78, and the reviewer accepted the correction in round 2.

### Proposed review rules (senior-engineer, US-004; for the engineer to decide)
Round 1:
1. "Configuration-binding failure tests assert the exception type and the property or field name. Each negative test also has a positive test that uses the same property-source mechanism, so a key that never binds cannot pass silently."
2. "Tests of environment-variable binding use a `SystemEnvironmentPropertySource`. `withPropertyValues("UPPER_SNAKE=…")` does not apply environment-style name mapping."
3. "When security depends on a library's strictness (URL parsing, for example), the known bypass inputs are pinned as explicit regression cases."
4. "Every case conversion, in production code and tests, uses `Locale.ROOT`."

Round 2:

5. "Tests that bind a property from an environment-style key must add it through a `SystemEnvironmentPropertySource` and assert the bound value (or, for invalid input, the rejected value). `withPropertyValues("UPPER_SNAKE=...")` never maps to the dotted property." (Refines rule 2.)
6. "The review log keeps each finding's original severity even after it is fixed or deferred."
| 3 | R3, D48, R8, US-003 N7, D49 tests | — | Final re-review (fix round 2) | **Resolved**. Verdict **APPROVE** |
| 3 | R11 | SHOULD | Env-binding tests name their sources `env`/`env2`, which bypasses Boot's `SystemEnvironmentPropertyMapper`. The claim that `SHORTENER_ALIAS_ADDITIONALRESERVEDWORDS` does not bind is **false in production** | **Open**: fix rounds used up; engineer decides at G4. See the correction below |
| 3 | R12 | SHOULD | The public `UrlValidator` constructor lacks `@throws` Javadoc (CLAUDE.md rule) | **Open**: engineer decides at G4 |
| 3 | R13 | NIT | The Implementation notes' figures (264, 120/121) came from a build without `clean` | Fixed: see the final verification below |
| 3 | R14 | NIT | Test comments cite review IDs `R3` and `R8` | Open |
| 3 | R15 | NIT | Overlong Javadoc line in `AliasPolicy` | Open |

**Correction (orchestrator, from the senior-engineer's check of the Spring Boot 3.5.16 bytecode):** the Round 3 statement above that only `SHORTENER_ALIAS_ADDITIONAL_RESERVED_WORDS` binds is wrong for production. In the real `systemEnvironment` source, Boot's `SystemEnvironmentPropertyMapper` binds both forms:
- `SHORTENER_ALIAS_ADDITIONALRESERVEDWORDS`: canonical, dashes removed.
- `SHORTENER_ALIAS_ADDITIONAL_RESERVED_WORDS`: legacy, dashes become underscores.

Likewise `APP_BASEURL` (canonical) and `APP_BASE_URL` (legacy) both bind `app.base-url`. The tests saw only one form because their property sources are not named `systemEnvironment`. Deployment docs (US-014, US-015) must not rely on the incorrect statement.

**Orchestrator final verification (2026-09-29):**
- `./mvnw -q clean verify` passed (exit 0).
  - Surefire: 263 run, 0 failed, 0 errors, 0 skipped.
  - Failsafe: 18 run, 0 failed, 0 errors, 0 skipped (includes 3 Cucumber scenarios).
  - Merged LINE coverage: 118/119 (99.2%). The only miss is the private constructor of `HttpUris`.
- G3 approved by the engineer. The final fix round passed its re-review with APPROVE and the build passed. Status: **Done**.

### Proposed review rules (senior-engineer, US-004 round 3)
1. "A `SystemEnvironmentPropertySource` used in a test must be named `systemEnvironment` or end with `-systemEnvironment`. Any other name bypasses Spring Boot's `SystemEnvironmentPropertyMapper`, so the test doesn't reflect production binding."
2. "Test results in story files and post-task reports come from a `clean verify` run."
3. "Code and test comments don't cite review finding IDs (`Rn`, `Nn`). Describe the behaviour or cite a `Dnn`."

## Post-completion change (engineer-approved at the US-008 escalation, D84)
- D84 extends D11: a URL whose D75-encoded form exceeds 2048 bytes is now rejected (400 `INVALID_URL`), even if it is at most 2048 characters.
- `UrlValidatorTest.shouldCountCharactersNotBytesForMultibyteUrlAtLimit` is updated to the new rule in US-008 fix round 2. Previously it accepted 2048 multibyte characters. The encoder is moved to a shared helper used by both `UrlValidator` and the redirect.
- **Test changes, one by one (US-008 fix round 2):**
  - **Updated and renamed:** `shouldCountCharactersNotBytesForMultibyteUrlAtLimit` is now `shouldRejectUrlOfMaxCharactersWhenItsEncodedFormExceedsMaxBytes`. 2048-character é, CJK and emoji URLs are now rejected.
  - **Removed:** `shouldCountSupplementaryCodePointsAsOneCharacter`. Its 2048-emoji input is now rejected under D84. The senior-engineer confirmed nothing observable was lost, because D84 makes the difference between code points and UTF-16 units unobservable for D11.
  - **Added:** `shouldAcceptAtExactlyMaxEncodedBytesAndRejectOneOver` (ASCII, CJK, emoji and é at 2048/2049 encoded bytes) and `shouldCountCodePointsNotUtf16UnitsOrBytesForShortMultibyteUrls`. The reviewer flagged the latter's name as overclaiming (US-008 N1, open).
  - **Moved:** the encoder test rows went from `RedirectControllerTest` to the new `LocationEncoderTest`, with no case lost.
- The story's status stays **Done**. The change is recorded here for traceability.

- **Follow-up made in US-009 (engineer-approved at US-008 G3, carried as N1), test by test:**
  - **Renamed:** `UrlValidatorTest.shouldCountCodePointsNotUtf16UnitsOrBytesForShortMultibyteUrls` is now `shouldAcceptAShortUrlOfSupplementaryCharacters`. The body is unchanged (100 emoji after the prefix, expected valid). It passes under every counting method, because D84 makes the difference between code points, UTF-16 units and bytes unobservable, so the old name overclaimed. It is kept, not deleted: it is not a duplicate of `shouldAcceptValidSupplementaryCharacterInPath`, which uses a single emoji, and it covers a longer run of supplementary characters.
  - **Production comment only:** `UrlValidator.isValid` now says that D84 implies the D11 count and that the D11 check stays as the cheap limit before encoding. No behaviour changed.
