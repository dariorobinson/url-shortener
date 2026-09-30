---
id: US-006
title: Create short URL API
status: Done
plan_task: 6
depends_on: [US-002, US-003, US-004, US-005]
requirements: [FR-1, FR-3, FR-7, FR-10, FR-11, D4, D5, D6, D11, D17, D28, D29, D30, D31, D33, D70]
requires_design_approval: true
---

# US-006: Create short URL API

## User story
As an authenticated USER (or ADMIN), I want to submit a long URL, optionally with a custom alias, and receive a short code, so that I can share a shortened link.

## Acceptance criteria
- **AC1:** Given an authenticated caller submits `{"originalUrl": "https://example.com/page"}` with no alias, when `POST /api/v1/urls` is called, then the response is `201 Created` with a `Location` header of `/api/v1/urls/{code}` (D33 — the management resource, not the public redirect) and a body including at least `shortCode`, `shortUrl`, `originalUrl`, `status: "ACTIVE"`, `customAlias: false`, `createdAt`.
- **AC2:** Given the caller also submits a valid, unused `alias` (D17 — the request field is named `alias`), when `POST /api/v1/urls` is called, then the response is `201 Created`, `shortCode` equals the submitted alias, and `customAlias: true`.
- **AC3:** Given the submitted alias already exists (any status), when `POST /api/v1/urls` is called, then the response is `409 Conflict` with `errorCode: "ALIAS_ALREADY_EXISTS"` (D31); no retry is attempted for custom aliases (per architecture).
- **AC4:** Given the submitted alias violates `AliasPolicy` (bad length, bad charset, or reserved word), when `POST /api/v1/urls` is called, then the response is `400 Bad Request` with `errorCode: "INVALID_ALIAS"` and a validation-error body identifying the field.
- **AC5:** Given `originalUrl` fails `UrlValidator` (wrong scheme, too long, embedded credentials, or self-referencing host per D28), when `POST /api/v1/urls` is called, then the response is `400 Bad Request` with `errorCode: "INVALID_URL"`.
- **AC6:** Given no alias is submitted and the generator produces a code that collides with an existing row on the first attempt (forced via a test seam that pre-inserts a colliding row), when `POST /api/v1/urls` is called, then the service retries in a fresh transaction, succeeds on a subsequent attempt (within the configured max), and returns `201 Created`.
- **AC7:** Given no alias is submitted and every attempt up to the configured maximum collides (forced via the same test seam), when `POST /api/v1/urls` is called, then the response is `503 Service Unavailable` with `errorCode: "SHORT_CODE_UNAVAILABLE"` (D31), and no row is committed.
- **AC8:** Given no credentials are supplied, when `POST /api/v1/urls` is called, then the response is `401` with `errorCode: "AUTHENTICATION_REQUIRED"` (D31, per US-005).
- **AC9:** Given two concurrent `POST` requests both submit the same custom alias, when both execute concurrently, then exactly one succeeds with `201` and the other fails with `409`/`ALIAS_ALREADY_EXISTS` — the database unique constraint (US-002) is the final guarantee, not an application-level check-then-act.
- **AC10:** Given a successful create, when the row is persisted, then `created_by` is set to the authenticated principal's username (foundation for ownership, D4); it is not returned in the response body to other callers (enforced together with US-007).
- **AC11:** Given the same `originalUrl` is submitted twice by the same caller in two separate requests, when `POST /api/v1/urls` is called each time, then both responses are `201 Created` and the two `shortCode` values are different (D5 — no deduplication; a new code is always created).
- **AC12:** Given the request body is missing `originalUrl` or has a blank `originalUrl`, when `POST /api/v1/urls` is called, then the response is `400 Bad Request` with a `ProblemDetail` body and `errorCode: "VALIDATION_FAILED"` (D31), and the body contains no stack trace and no internal exception message.
- **AC13:** Given the application is running, when `GET /v3/api-docs` is requested, then the generated OpenAPI document includes the `POST /api/v1/urls` operation with its request and response schemas (Task 6 requires OpenAPI docs).
- **AC14:** Given the request body is syntactically malformed JSON (e.g. an unterminated object), when `POST /api/v1/urls` is called, then the response is `400 Bad Request` with `errorCode: "MALFORMED_REQUEST"` (D31, distinct from AC12's `VALIDATION_FAILED`), and the body contains no internal parser exception message.
- **AC15:** Given no alias is submitted and the generator's first attempt produces a code that matches a configured reserved word (forced via the test seam — D29), when `POST /api/v1/urls` is called, then the service treats the match as a collision, retries in a fresh transaction, and succeeds within the configured max attempts, returning `201 Created` with a `shortCode` that is not a reserved word.
- **AC16:** Given `APP_BASE_URL=https://short.example` is configured, when `POST /api/v1/urls` is called with a request header `Host: attacker.example`, then the response body's `shortUrl` is built from `APP_BASE_URL` (`https://short.example/{code}`) and never reflects the spoofed `Host` header (D33 — prevents host-header injection).
- **AC17:** Given an authenticated caller sends a valid create request with an unacceptable `Accept` header (for example `application/xml`, `text/plain` or `application/problem+json`), when `POST /api/v1/urls` is called, then:
  - the response is `406 Not Acceptable` with `errorCode: "NOT_ACCEPTABLE"` in an `application/problem+json` body and no `Location` header (D61, D70);
  - **no short URL is created**, so a follow-up JSON request with the same alias succeeds with `201` rather than `409` (D70, D61);
  - an unparseable `Accept` header also gets `406` and creates nothing, but its body is empty (the D70 known deviation).

## Tests required
| Type | Behaviour proven | Owner |
|---|---|---|
| Unit | Service create logic: alias path vs generated-code path, retry loop bounds, mapping to DTO | mid-engineer |
| Web slice | Request validation (400s for malformed JSON, missing required fields), auth wiring (401), response shape (AC1, AC2, AC4, AC5, AC8, AC12, AC14) | mid-engineer |
| Cucumber | Create with no alias returns 201, `Location: /api/v1/urls/{code}`, and the documented body shape (AC1) | qa-tester |
| Cucumber | Create with a valid custom alias returns 201 with `shortCode` equal to the alias and `customAlias: true` (AC2) | qa-tester |
| Cucumber | Create with a duplicate alias returns 409 `ALIAS_ALREADY_EXISTS` (AC3) | qa-tester |
| Cucumber | Create with an invalid alias (bad length/charset/reserved word) returns 400 `INVALID_ALIAS` (AC4) | qa-tester |
| Cucumber | Create with an invalid `originalUrl` (bad scheme, too long, credentials, self-referencing host) returns 400 `INVALID_URL` (AC5) | qa-tester |
| Cucumber | Create with no credentials returns 401 `AUTHENTICATION_REQUIRED` (AC8) | qa-tester |
| Cucumber | Submitting the same `originalUrl` twice produces two 201s with distinct `shortCode`s (AC11) | qa-tester |
| Cucumber | Missing/blank `originalUrl` returns 400 `VALIDATION_FAILED`; malformed JSON returns 400 `MALFORMED_REQUEST` (AC12, AC14) | qa-tester |
| Cucumber | Create with an unacceptable `Accept` returns 406 `NOT_ACCEPTABLE`, and no short URL is created (AC17) | qa-tester |
| Cucumber | Create with a spoofed `Host` header still returns `shortUrl` built from `APP_BASE_URL` (AC16) | qa-tester |
| Integration (`*IT`) | Forced real unique-constraint collision against Testcontainers PostgreSQL proves retry-in-fresh-transaction works (AC6) and exhaustion returns 503 `SHORT_CODE_UNAVAILABLE` with no committed row (AC7) | qa-tester |
| Integration (`*IT`) | Duplicate custom alias against a pre-existing row returns 409 (AC3) | qa-tester |
| Integration (`*IT`) | Two simultaneous requests with the same alias resolve to exactly one 201 and one 409 (AC9) | qa-tester |
| Integration (`*IT`) | A forced reserved-word-matching generated code is treated as a collision and retried, succeeding with a non-reserved `shortCode` (AC15) | qa-tester |
| Integration (`*IT`) | The generated OpenAPI document at `/v3/api-docs` includes the `POST /api/v1/urls` operation (AC13) | qa-tester |
| Integration (`*IT`) | Real-HTTP test asserts an unacceptable `Accept` returns 406 and zero rows, counted by a marker URL. It includes a positive control (the same request with `Accept: application/json` returns 201 and one row) and the alias-reuse proof (406 with alias X, then JSON with alias X returns 201). It also asserts an unparseable `Accept` returns 406 with zero rows and an empty body (AC17) | qa-tester |
| Web slice | `Accept: application/xml` returns 406 with `verifyNoInteractions(service)` (AC17) | mid-engineer |

## Out of scope
- `GET /api/v1/urls/{code}` — see US-007.
- Redirect endpoint — see US-008.
- The single `@RestControllerAdvice`/`ProblemDetail` infrastructure is introduced by this story (first API endpoint) and reused by every later API story; changing its shape later affects all of them.

## Risks
- This story establishes the created-resource JSON field set; every later API story inherits this convention, so getting it wrong here is expensive to fix later. The `errorCode` catalogue itself is already fixed by US-005/D31, so that risk is retired.
- The forced-collision test seam (a way to make the generator return a predetermined/colliding code in a test) needs a clean design that doesn't leak test-only code paths into production logic (e.g. an injectable `ShortCodeGenerator` test double is preferable to a "test mode" flag). The same seam is reused for AC15's reserved-word-collision test.

## Open questions
- The response JSON field set for a created/detail resource is still not fully specified. D33 fixes that the body includes `shortUrl` (built from `APP_BASE_URL`, never the `Host` header) and that `Location` is `/api/v1/urls/{code}`, but the complete field list (e.g. whether `clickCount`/`lastAccessedAt` appear on create, exact field names/casing) still needs design-gate confirmation and must match US-007's response shape exactly.

## Carry-over from US-005 (engineer-approved at C3 G4)
- **R20 (NIT):** the `SecurityIT` comment at about lines 318–321 misstates what the 403 and 404 rows mean. Reword it: "403: the ADMIN rule (D3) or the final denyAll (D57) refused the USER; 400: the firewall rejected the path." (qa-tester)
- **R22 (NIT):** the `SecurityIT` `/API/` 403 rows assert only the status. Also assert `errorCode` `ACCESS_DENIED`. (qa-tester)
- **R23 (NIT):** add D57 to the `SecurityConfig` class Javadoc. (mid-engineer)
- **R24 (NIT):** rename the `DELETE_CALLS` counter in `SecurityConfigWebMvcTest` to `HANDLER_CALLS`, because it also counts the POST and GET probes. (mid-engineer)

## Design inputs carried from US-004 (engineer-approved at US-004 G3)
- Store exactly the `originalUrl` string that `UrlValidator` validated. Never store `URI.toString()` or a normalised form (D11, D47).
- Call `AliasPolicy.isValid` on each **generated** code; a reserved-word match counts as a collision and triggers a retry (D29, D48).
- The OpenAPI documentation for `POST /api/v1/urls` must state that non-ASCII (IDN) hosts are rejected and that clients must submit punycode (D49).

## Design note

*Architect, 2026-09-29. Status: **proposed, approved at G2 (D58–D69); amended for D70 at the US-006 escalation** (§2, §4.2, §4.3, §5, §7.1, §7.5, K5, §12). Gate IDs A1–A9 and question IDs Q1–Q4 are for this note only. Once the engineer decides, the orchestrator records the outcomes as D58 onward. Code, tests and SQL must cite those `Dnn` IDs, never `A1`, `Q1` or section numbers (CLAUDE.md review rule).*

### 0. Engineer decisions required at G2

| # | Decision | Recommendation | Blocking? |
|---|---|---|---|
| **A1** | Response field set, shared with US-007 (story open question). | One `ShortUrlResponse`: `shortCode`, `shortUrl`, `originalUrl`, `status`, `customAlias`, `clickCount`, `createdAt`, `lastAccessedAt`. It is returned on create too, with `clickCount: 0` and `lastAccessedAt: null`. There is no `createdBy`, `id`, `updatedAt` or `version` (§1.3). | **Yes.** Every later API story inherits it. |
| **A2** | Unknown JSON fields and duplicate keys in request bodies. | **Reject both** with `400 MALFORMED_REQUEST` (`spring.jackson.deserialization.fail-on-unknown-properties: true`, `spring.jackson.parser.strict-duplicate-detection: true`) (§1.2). | **Yes.** This is global Jackson configuration, so it applies to every later endpoint (US-009 PATCH). |
| **A3** | `alias: ""` or whitespace-only. | **Invalid**: `400 INVALID_ALIAS`, never trimmed (D47). A missing `alias` or JSON `null` means absent, so a code is generated (§1.2). | **Yes.** This is product behaviour. |
| **A4** | Framework errors with no D31 code: 404 on unmapped paths, 405, 406 and 415. | **Extend D31** with `RESOURCE_NOT_FOUND` (404), `METHOD_NOT_ALLOWED` (405), `NOT_ACCEPTABLE` (406) and `UNSUPPORTED_MEDIA_TYPE` (415) (§4.3). | **Yes.** It changes the D31 catalogue. |
| **A5** | The test profile's `app.base-url`. | Change it from `http://localhost:8080` to **`https://short.example`**. AC16 then holds literally in the one shared IT context, and every create test proves `shortUrl` is not derived from the request (§7.4). | No |
| **A6** | Precedence and body shape when `originalUrl` and `alias` are both bad. | Check `INVALID_URL` first. `INVALID_URL` also carries the `errors` extension naming `originalUrl` (§4.2). | No |
| **A7** | Row data in database error messages and logs. | pgjdbc `logServerErrorDetail=false`, and Hibernate `SqlExceptionHelper` logging `OFF` (§3.6). | No, but it is security-relevant. |
| **A8** | How the specific unique constraint is recognised. | Read pgjdbc's `ServerErrorMessage` (SQLSTATE plus protocol constraint field) in a production helper. The PostgreSQL driver moves from `runtime` to `compile` scope (§3.4). | No |
| **A9** | Forging the `Host` header in HTTP tests. | Failsafe `argLine` gets `-Djdk.httpclient.allowRestrictedHeaders=host` (§7.4). | No |

### 1. API contract

#### 1.1 Endpoint and security

`POST /api/v1/urls`, `Content-Type: application/json`.

- **Filter-chain rule that admits it (CLAUDE.md rule):** rule 6 in the `docs/architecture.md` access table, `.requestMatchers("/api", "/api/**").hasRole(Role.USER.name())`. ADMIN passes through the `ADMIN > USER` `RoleHierarchy` bean (D3). Rule 5 (`DELETE /api/v1/urls/**`, ADMIN) does not match POST. Anonymous callers get `401 AUTHENTICATION_REQUIRED` from the entry point (AC8, D30). `SecurityConfig` needs no change.
- **Path variants** (pinned by QA): `POST /API/v1/urls` matches no explicit rule, so it gets `403 ACCESS_DENIED` (D57). `POST /api/v1/urls/` passes rule 6 but no handler matches it (Spring MVC 6 does not match trailing slashes), so it gets 404 and no row is created.
- **Principal:** the controller takes `Authentication authentication` and passes `authentication.getName()` to the service. For the `DaoAuthenticationProvider`/`InMemoryUserDetailsManager` setup this is the **configured** lowercase username, whatever case the client typed (D54, pinned in US-005 by `shouldAuthenticateCaseInsensitivelyButKeepConfiguredUsername`). It is stored unchanged in `created_by` (D4, D51). The service never touches `SecurityContextHolder`, so it has no Spring Security dependency and is easy to unit-test.

#### 1.2 Request: `api/dto/CreateShortUrlRequest`

```java
public record CreateShortUrlRequest(
        @Schema(description = "...", maxLength = 2048, example = "https://example.com/page")
        @NotBlank(message = "must not be blank") String originalUrl,
        @Schema(description = "...", pattern = "^[A-Za-z0-9]{3,32}$", nullable = true, example = "promo2026")
        String alias) {
}
```

- **Bean Validation is structural only.** `@NotBlank` on `originalUrl` covers AC12: a missing value, JSON `null`, `""` and whitespace-only all give `400 VALIDATION_FAILED`. All other rules belong to `UrlValidator`/`AliasPolicy` and are called from the service (§3.1), so AC5 gets `INVALID_URL` and AC4 gets `INVALID_ALIAS`, not `VALIDATION_FAILED`. Constraint messages are **literal** strings, never `{…}` keys, so the body doesn't vary with the request's `Accept-Language`.
- **Do not put constraints directly on the `@RequestBody` parameter.** In Spring 6.2 that switches the error to `HandlerMethodValidationException`. `@Valid @RequestBody` alone raises `MethodArgumentNotValidException`.
- **Nothing is trimmed** (D47). `" https://example.com"` passes `@NotBlank` and then fails `UrlValidator`, giving `INVALID_URL`.
- **`alias` (A3):**
  - Missing or JSON `null` means absent, so a code is generated. The record can't tell the two apart, and treating both as absent is the usual JSON convention.
  - `""`, `"  "` or any other string goes to `AliasPolicy.isValid` as submitted. An empty or whitespace-only value fails the length or charset check, giving `400 INVALID_ALIAS`.
  - Reason: a client that sends an explicit but empty alias has made a mistake. Silently generating a random code would hide it.
- **Unknown fields and duplicate keys (A2):**
  - Both are rejected: `{"originalUrl":"…","alais":"promo"}` and `{"alias":"a","alias":"b"}` give `400 MALFORMED_REQUEST`.
  - Reason: with Boot's default (`FAIL_ON_UNKNOWN_PROPERTIES` disabled, verified), a misspelled `alias` silently creates a random code, which the client then shares.
  - Duplicate keys are a parser-differential risk: a proxy or WAF may read the first value while the application reads the last.
  - Both properties go in `application.yml`. springdoc and Actuator use their own mappers, and the security writer only serialises, so none of them is affected.
- **Other unreadable bodies** give `400 MALFORMED_REQUEST` through `HttpMessageNotReadableException`. That covers an empty body, the literal `null`, a JSON array, an object or array where a string is expected, and a syntax error (AC14).
- **Scalar coercion** stays at Jackson's default: `{"alias": 12345}` binds as `"12345"`. Validation then runs on that string, so this carries no integrity risk (see risk K9).

#### 1.3 Response: `api/dto/ShortUrlResponse` (A1, shared with US-007)

`201 Created`, `Content-Type: application/json`, `Location: /api/v1/urls/{shortCode}`.

```json
{
  "shortCode": "aB3dE9x",
  "shortUrl": "https://short.example/aB3dE9x",
  "originalUrl": "https://example.com/page",
  "status": "ACTIVE",
  "customAlias": false,
  "clickCount": 0,
  "createdAt": "2026-09-29T14:03:12.123456Z",
  "lastAccessedAt": null
}
```

| Field | Java type | JSON | Notes |
|---|---|---|---|
| `shortCode` | `String` | string | Base62, 3–32 characters (D6) |
| `shortUrl` | `String` | string, absolute URL | Built from `APP_BASE_URL` (D33, §1.4) |
| `originalUrl` | `String` | string | Exactly the stored value, which is exactly the submitted value (US-004 input) |
| `status` | `String` | `"ACTIVE"` or `"DEACTIVATED"` | `ShortUrlStatus.name()`. `DELETED` is never returned (D13) |
| `customAlias` | `boolean` | boolean | |
| `clickCount` | `long` | integer (int64) | `0` on create |
| `createdAt` | `Instant` | string, RFC 3339 `date-time`, UTC `Z` | Boot's Jackson has `WRITE_DATES_AS_TIMESTAMPS` disabled (verified), so `Instant` renders as ISO-8601 via `ISO_INSTANT`. Precision is microseconds (D45), and the fraction has 0, 3, 6 or 9 digits depending on the value, so clients must parse ISO-8601 rather than match fixed-width strings |
| `lastAccessedAt` | `Instant` or null | string or `null` | Always present, `null` until the first click (US-010) |

**Not returned:** `createdBy` (AC10: ownership data never leaves the server; US-007 enforces ownership in the service), `id` (surrogate key; the code is the public identifier), `updatedAt`, `version`, `deletedAt`/`deletedBy`. These can be added later without breaking clients. Removing a field would break them.

```
Recommendation: one ShortUrlResponse record for create (US-006) and details (US-007), with the eight fields above,
                always all present (lastAccessedAt serialized as null, not omitted).
Reason:         US-007 AC1 says "the same resource shape produced by US-006" and lists clickCount/lastAccessedAt; a single
                record makes drift impossible. A stable key set is simpler for clients and for key-set assertions.
                createdBy is excluded so it cannot leak to a non-owner through any future reuse (AC10).
Alternative:    a slim create response (shortCode, shortUrl, originalUrl, status, customAlias, createdAt) and a richer
                detail response; or omit null fields (@JsonInclude(NON_NULL)).
Trade-off:      create returns two fields that are always 0/null. The alternative saves a few bytes but gives two
                shapes for one resource and makes clients branch on key presence.
```

**US-007 alignment:** US-007 AC1 lists the fields without `shortUrl`, but also says "the same resource shape produced by US-006". Under A1, US-007 returns `shortUrl` too. The planner should add it to US-007 AC1 when A1 is approved.

#### 1.4 `shortUrl` and `Location`: `api/ShortUrlLinks`

A `@Component` in `api`, used by US-006 and US-007:

```java
@Component
public class ShortUrlLinks {
    private final String publicBase;   // APP_BASE_URL with every trailing '/' removed, computed once

    public ShortUrlLinks(AppProperties properties) { … }

    public String publicUrl(String shortCode)  { return publicBase + "/" + shortCode; }
    public URI location(String shortCode)      { return URI.create(ShortUrlController.BASE_PATH + "/" + shortCode); }
}
```

- **Joining.** Remove all trailing `/` characters from the configured base, then append `/` and the code. So `https://short.example` and `https://short.example/` both give `https://short.example/{code}`, and `https://short.example/base` and `https://short.example/base/` both give `https://short.example/base/{code}`. `@HttpBaseUrl` already guarantees no query and no fragment, so the code is always appended to the path. The base is otherwise used exactly as configured (no case folding).
- **No encoding** is needed: a code is always `[A-Za-z0-9]{3,32}` (D6, `ck_short_url_code_format`).
- **`Location` is relative:** `/api/v1/urls/{code}`, exactly as written in D33. RFC 9110 allows a relative URI reference. `ResponseEntity.created(uri)` writes the header as given, and Tomcat rewrites only `sendRedirect` targets, not a `Location` set directly.
- **Never use** `ServletUriComponentsBuilder`, `UriComponentsBuilder.fromCurrentRequest*`, `HttpServletRequest.getServerName()/getRequestURL()` or `X-Forwarded-*`: all of them read `Host` or forwarded headers (D33). The reviewer should grep `api/` for these.

### 2. Controller: `api/ShortUrlController`

```java
@RestController
@RequestMapping(path = ShortUrlController.BASE_PATH, produces = MediaType.APPLICATION_JSON_VALUE)   // D70
@RequiredArgsConstructor
@Tag(name = "Short URLs")
@SecurityRequirement(name = OpenApiConfig.BASIC_AUTH)
class ShortUrlController {
    static final String BASE_PATH = "/api/v1/urls";

    private final ShortUrlService service;
    private final ShortUrlLinks links;

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(...) @ApiResponse(...)                      // section 5
    ResponseEntity<ShortUrlResponse> create(@Valid @RequestBody CreateShortUrlRequest request,
                                            @Parameter(hidden = true) Authentication authentication) {
        ShortUrlView view = service.create(
                new CreateShortUrlCommand(request.originalUrl(), request.alias(), authentication.getName()));
        return ResponseEntity.created(links.location(view.shortCode())).body(ShortUrlResponse.from(view, links));
    }
}
```

- `consumes = application/json`: any other or missing `Content-Type` gives 415 (§4.3), and the OpenAPI request body is documented as JSON.
- **Class-level `produces = application/json` (D70), and only `application/json`.**
  - *Why.* Without `produces`, content negotiation happens only when the return value is written, which is after the service has committed the row. The implementer showed this against a real database: `Accept: application/xml` committed the row and then returned 406, so the client never saw its code, and a retry with the same alias got 409. With `produces`, `ProducesRequestCondition` rejects an unacceptable `Accept` **at mapping lookup**, before the body is read, validated or passed to the service. Nothing is created.
  - *Why error bodies still serialize (verified in the Spring 6.2.19 source).* On a match, `RequestMappingInfoHandlerMapping.handleMatch` stores the producible types (`application/json`) in `HandlerMapping.PRODUCIBLE_MEDIA_TYPES_ATTRIBUTE`. When any handler exception occurs, `DispatcherServlet.processHandlerException` **removes that attribute** before the exception resolvers run ("Success and error responses may use different content types"). The advice's `ProblemDetail` therefore goes through `AbstractMessageConverterMethodProcessor` with no mapping restriction, and the `application/problem+json` fallback applies as before. On a produces mismatch no handler matched, so the attribute was never set.
  - *So the earlier "No `produces`" rule in this note was wrong.* It read the intersection in `AbstractMessageConverterMethodProcessor` correctly but missed the removal in `processHandlerException` (see K5).
  - *Never add `application/problem+json` to `produces`.* A client sending `Accept: application/problem+json` would then match the mapping and get its 201 body labelled `application/problem+json`. AC17 requires 406 for that header.
  - *Scope.* `produces` is on `ShortUrlController` only, so every later management mapping on it (US-007 GET, US-009 PATCH and DELETE) inherits it. DELETE with an unacceptable `Accept` therefore also gets 406 (D70). It must never be on the redirect controller (US-008) or on any shared base class or meta-annotation the redirect could inherit (K5).
  - The 201 body is still `application/json`, and requests with no `Accept` or with `*/*` are unaffected.

```
Recommendation: (D70) class-level produces = MediaType.APPLICATION_JSON_VALUE on ShortUrlController; application/json only.
Reason:         406 is decided at mapping lookup, before the body is read or the service runs, so an unacceptable Accept
                can never commit a row. Error bodies are unaffected because DispatcherServlet.processHandlerException
                clears the producible-types attribute before the advice runs (verified, 6.2.19).
Alternative:    no produces, accepting that 406 is sent after the commit; or an explicit Accept check at the top of the
                handler method.
Trade-off:      an unparseable Accept gets 406 with an empty body instead of problem+json (a D70 deviation from D61); every
                mapping on this controller, including US-009 DELETE, inherits 406 for an unacceptable Accept. The first
                alternative loses the client's code and turns its retry into 409. The second duplicates Spring's
                negotiation rules by hand and still runs after the body has been parsed.
```
- The controller is the only place where the entity-free `ShortUrlView` becomes the DTO. `ShortUrlResponse.from(view, links)` is a static factory on the record.

### 3. Service, transactions and collision handling (FR-10)

#### 3.1 Types (package `service`)

| Type | Kind | Content |
|---|---|---|
| `ShortUrlService` | `@Service` | `ShortUrlView create(CreateShortUrlCommand command)` |
| `CreateShortUrlCommand` | record | `(String originalUrl, String alias /* nullable */, String createdBy)`. A record, so the three `String`s can't be passed in the wrong order |
| `ShortUrlView` | record | `(String shortCode, String originalUrl, ShortUrlStatus status, boolean customAlias, long clickCount, Instant createdAt, Instant lastAccessedAt)`, with a static `from(ShortUrl)`. Entities never leave the service layer. `createdBy` is deliberately absent |
| `service/exception/InvalidUrlException` | `RuntimeException` | No-argument constructor. The message never contains the URL |
| `service/exception/InvalidAliasException` | `RuntimeException` | No-argument constructor. The message never contains the alias (it may be arbitrary text) |
| `service/exception/AliasAlreadyExistsException` | `RuntimeException` | `(String alias, Throwable cause)`. The alias has passed `AliasPolicy`, so it is Base62 and safe to put in the message |
| `service/exception/ShortCodeUnavailableException` | `RuntimeException` | `(int attempts)` |

There is no common base exception: the advice maps each type explicitly (§4.2). The service must not depend on `api.error.ErrorCode`, because the layers go `api → service`, never the reverse.

#### 3.2 Transaction boundary

```
Recommendation: ShortUrlService.create is NOT @Transactional. Each insert attempt runs in its own
                TransactionTemplate (PROPAGATION_REQUIRES_NEW) built in the service constructor from the
                PlatformTransactionManager; repository.saveAndFlush runs inside the callback; the service catches
                DataIntegrityViolationException OUTSIDE the template, after it has rolled back.
Reason:         PostgreSQL aborts a transaction after a failed statement, so a retry must use a fresh physical
                transaction (architecture "Short-code generation"). TransactionTemplate rolls back on RuntimeException
                and rethrows, and exceptions thrown by commit also propagate (both verified in 6.2.19 source), so one
                catch covers flush-time and commit-time violations. The boundary is visible in the retry loop, and there
                is no proxy or self-invocation trap. REQUIRES_NEW guarantees a fresh transaction even if a future caller
                wraps create() in a transaction.
Alternative:    a separate @Service ShortUrlWriter with @Transactional(propagation = REQUIRES_NEW) insert(...), called
                through its proxy.
Trade-off:      the service needs an explicit constructor instead of @RequiredArgsConstructor (it builds the template),
                and unit tests pass a Mockito PlatformTransactionManager. The writer bean keeps Lombok and is
                trivially mockable, but adds a class whose only job is an annotation, and "fresh transaction per attempt"
                would then depend on proxying working correctly.
```

- **Why the template is built, not injected.** Boot's own `transactionTemplate` bean is `@ConditionalOnMissingBean(TransactionOperations.class)` (verified). Publishing a second, REQUIRES_NEW-configured `TransactionTemplate` bean would replace Boot's default, and any other class injecting `TransactionTemplate` would silently get REQUIRES_NEW. Instances are thread-safe, with no conversational state (Spring reference, verified), so one instance per service is correct.
- **Pool caveat (Spring reference, verified):** REQUIRES_NEW inside an outer transaction needs a second connection, and it can exhaust the pool. `create` is called from a non-transactional controller with `open-in-view=false`, so there is never an outer transaction, and each attempt uses exactly one connection. Rule for the implementer: **never** annotate `create` or the controller with `@Transactional`.
- **`saveAndFlush`, not `save`.** With `IDENTITY` the INSERT already runs at persist time, but `saveAndFlush` makes the timing independent of the id strategy.
- **A fresh entity for each attempt.** Never reuse a `ShortUrl` instance whose persist failed, because Hibernate may have changed its state.

#### 3.3 Algorithm

```java
public ShortUrlView create(CreateShortUrlCommand cmd) {
    if (!urlValidator.isValid(cmd.originalUrl())) throw new InvalidUrlException();          // AC5, first (A6)
    Instant now = clock.instant();                                                          // D45: once per request
    if (cmd.alias() != null) {
        if (!aliasPolicy.isValid(cmd.alias())) throw new InvalidAliasException();           // AC4 (D6, D29, D48)
        try {
            return created(insert(cmd.alias(), cmd, true, now));                             // no retry (AC3)
        } catch (DataIntegrityViolationException e) {
            if (isShortCodeConflict(e)) throw new AliasAlreadyExistsException(cmd.alias(), e);
            throw e;                                                                         // any other constraint -> 500
        }
    }
    for (int attempt = 1; attempt <= maxAttempts; attempt++) {
        String code = generator.generate();
        if (!aliasPolicy.isValid(code)) {                                                    // D29, D48: counts as a collision,
            log.debug(...); continue;                                                        // no DB round trip (AC15)
        }
        try {
            return created(insert(code, cmd, false, now));
        } catch (DataIntegrityViolationException e) {
            if (!isShortCodeConflict(e)) throw e;                                            // only the UNIQUE is retried
            log.info(...);                                                                   // AC6
        }
    }
    log.warn(...);
    throw new ShortCodeUnavailableException(maxAttempts);                                    // AC7: every attempt rolled back
}

private ShortUrl insert(String code, CreateShortUrlCommand cmd, boolean custom, Instant now) {
    return requiresNew.execute(status ->
            repository.saveAndFlush(ShortUrl.create(code, cmd.originalUrl(), custom, cmd.createdBy(), now)));
}

private static boolean isShortCodeConflict(DataIntegrityViolationException e) {
    return PostgresServerErrors.isUniqueViolation(e, ShortUrlRepository.SHORT_CODE_UNIQUE_CONSTRAINT);
}
```

- `maxAttempts` comes from `ShortCodeProperties.maxAttempts()` (`shortener.code.max-attempts`, default 5, `@Min(1)`), and a reserved-word rejection counts as an attempt. AC7's "no row committed" follows from the structure: an attempt either commits and returns, or rolls back.
- `originalUrl` is stored exactly as validated: the same `String` goes to `UrlValidator.isValid` and `ShortUrl.create`. It is never `URI.toString()` and never normalised (US-004 input, D11, D47).
- `createdAt` comes from the injected `Clock`, read once per request (D45). The entity truncates it to microseconds. A retried request keeps the time it arrived.
- The alias check comes after the URL check (A6), so each response has a single, deterministic `errorCode`.
- A `DELETED` row keeps its code (D1), so an alias equal to a deleted code gets 409 (AC3, "any status") through the same constraint.
- AC9 needs no application code: two concurrent inserts of the same alias serialise on `uk_short_url_short_code`. The second either blocks until the first commits and then gets `23505`, or fails immediately. Either way it maps to 409. There is **no** `existsByShortCode` pre-check. A pre-check would add a query and would still need the catch.

#### 3.4 Recognising `uk_short_url_short_code`: `repository/PostgresServerErrors`

Verified facts:
- Spring 6.2.19 `HibernateJpaDialect` translates Hibernate's `ConstraintViolationException` to a plain `DataIntegrityViolationException`, not `DuplicateKeyException`. The constraint name appears only inside the message text.
- Hibernate 6.6.53 `PostgreSQLDialect` gets `getConstraintName()` by **parsing the English message** (`"violates unique constraint \""`). It sets no constraint kind for `23505`. That breaks if the server's `lc_messages` isn't English.
- pgjdbc's `ServerErrorMessage.getConstraint()` reads the protocol field `n`, which does not depend on the locale, and `getSQLState()` reads field `C`.

```java
package com.schwab.urlshortener.repository;

/** Reads PostgreSQL's structured error fields from any exception chain (Spring -> Hibernate -> pgjdbc). */
public final class PostgresServerErrors {
    public static final String UNIQUE_VIOLATION = "23505";
    private static final int MAX_CAUSE_DEPTH = 32;                 // guards against cyclic cause chains

    public static Optional<ServerErrorMessage> serverError(Throwable t) {
        Throwable current = t;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++, current = current.getCause()) {
            if (current instanceof PSQLException psql) {
                return Optional.ofNullable(psql.getServerErrorMessage());
            }
        }
        return Optional.empty();
    }

    public static boolean isUniqueViolation(Throwable t, String constraintName) {
        return serverError(t)
                .filter(m -> UNIQUE_VIOLATION.equals(m.getSQLState()) && constraintName.equals(m.getConstraint()))
                .isPresent();
    }
}
```

- `ShortUrlRepository` gets `String SHORT_CODE_UNIQUE_CONSTRAINT = "uk_short_url_short_code";`. This is the one shared constant, used by the service and by tests (CLAUDE.md shared-constant rule).
- **Anything else is not retried.** A CHECK violation (`23514`, for example `ck_short_url_original_url_length` if validation ever regressed), a NOT NULL violation, a missing PSQLException, or a unique violation on another constraint is rethrown and becomes a 500 `INTERNAL_ERROR` (§4.2). Returning 409 or retrying on those would hide a bug.
- **pom:** `org.postgresql:postgresql` moves from `runtime` to the default `compile` scope (A8). Only `repository/PostgresServerErrors` may import `org.postgresql.*`, and the reviewer should grep for it.
- **The test helper reuses it.** `support/PostgresErrors.serverError(t)` delegates to `PostgresServerErrors.serverError(t)`. Its `sqlState`/`constraintName` keep their `fail(...)` behaviour, and the name stays `PostgresErrors`, as the CLAUDE.md rule names it. One unwrapping algorithm then serves both, and every existing constraint test also exercises the production helper. A bug in the helper fails loudly (the `fail(...)` path), never silently.

```
Recommendation: production helper on pgjdbc ServerErrorMessage (SQLSTATE + protocol constraint field); pgjdbc to compile scope;
                test PostgresErrors delegates to it.
Reason:         locale-independent and exact (verified: Hibernate's constraint name is message-parsed); recognises exactly
                one constraint, so CHECK violations can never be mistaken for collisions.
Alternative:    java.sql.SQLException.getSQLState() plus Hibernate ConstraintViolationException.getConstraintName(), with
                no driver import.
Trade-off:      compile-time coupling to the PostgreSQL driver, confined to one class. The alternative misclassifies
                every conflict as a 500 on a non-English server.
```

#### 3.5 Logging (CLAUDE.md: codes, IDs and hosts only)

| Event | Level | Content |
|---|---|---|
| Created | INFO | `id`, `code`, `customAlias`, and the `host` of `originalUrl` (`URI.create(url).getHost()`, which is safe after validation). **Never the URL** |
| Alias conflict (409) | INFO | `code` (the alias) |
| Generated collision (retry) | INFO | `attempt`, `maxAttempts`, `code` |
| Generated code rejected by `AliasPolicy` | DEBUG | `attempt`, `maxAttempts` |
| Exhausted (503) | WARN | `maxAttempts`. This is an operational signal: the keyspace or the generator is failing |
| Invalid URL or alias (400) | DEBUG | The `errorCode` only, never the value |

No username is logged: D52 covers 401/403, and the same caution applies here because a username is personal data.

#### 3.6 Row data in database errors (A7)

pgjdbc appends the server's `Detail` to exception messages by default (verified in `ServerErrorMessage.toString`; `logServerErrorDetail` defaults to `true`). For a CHECK violation that detail is `Failing row contains (…)`, which is the **full `original_url` and `created_by`**. It would appear in the advice's ERROR log and in Hibernate's `SqlExceptionHelper` log, which breaks the CLAUDE.md logging rule. With `logServerErrorDetail=false`, the message is the non-sensitive form, while `getServerErrorMessage()` still carries every field, including the constraint (verified in `PSQLException`).

```yaml
# application.yml
spring:
  datasource:
    hikari:
      data-source-properties:
        logServerErrorDetail: false     # no row values in exception messages or logs
logging:
  level:
    org.hibernate.engine.jdbc.spi.SqlExceptionHelper: "off"   # expected 23505s are handled and logged by the service
```

```
Recommendation: both settings above.
Reason:         the first removes row data (URLs, usernames) from every JDBC exception message; the second stops an ERROR log
                line for every expected alias conflict and code collision, which would drown real errors and page on-call.
Alternative:    keep defaults and rely on validation to keep CHECK violations unreachable.
Trade-off:      Hibernate's own SQL-error line disappears for all statements; unexpected DB errors are still logged once, with
                stack trace, by the advice's 500 handler. Hikari passes data-source-properties to the driver for URL-based
                configuration, so production (env-supplied URL) and Testcontainers (@ServiceConnection) both get it.
```

### 4. Errors: `api/error/GlobalExceptionHandler`

#### 4.1 Structure

- It is the single `@RestControllerAdvice` (CLAUDE.md), and it **extends `ResponseEntityExceptionHandler`**. That base class already maps the 20 Spring MVC exception types (verified list, including `HttpMessageNotReadableException`, `MethodArgumentNotValidException`, `HttpRequestMethodNotSupportedException`, `HttpMediaTypeNotSupportedException`, `HttpMediaTypeNotAcceptableException`, `NoResourceFoundException`) to the right status. It carries their headers (`Allow`, `Accept`), and `handleExceptionInternal` skips a response that is already committed.
- **Every body is built by `ProblemDetails.of`.** The advice overrides `handleExceptionInternal(ex, body, headers, statusCode, request)`. If `body` is already a `ProblemDetail` carrying `errorCode`, it is kept. Otherwise the advice replaces it with `ProblemDetails.of(codeFor(statusCode), DETAIL.get(code), path)` and calls `super` with `code.status()`. None of Spring's default `detail` texts reach clients.
- `instance` is the request path without the query string: `((ServletWebRequest) request).getRequest().getRequestURI()`, or `HttpServletRequest.getRequestURI()` in `@ExceptionHandler` methods.
- `ProblemDetails` gets:
  - `public static final String ERRORS = "errors";`
  - an overload `of(ErrorCode, String detail, String requestUri, List<FieldViolation> errors)`. It calls the base factory, then sets `errors` to `List.copyOf(errors)`.
- New record `api/error/FieldViolation(String field, String message)`.

#### 4.2 Mappings

| Exception | Status / `errorCode` | `detail` (fixed text) | Extension |
|---|---|---|---|
| `MethodArgumentNotValidException` (override `handleMethodArgumentNotValid`) | 400 `VALIDATION_FAILED` | "The request body failed validation." | `errors` from `getFieldErrors()` |
| `HttpMessageNotReadableException` (override `handleHttpMessageNotReadable`) | 400 `MALFORMED_REQUEST` | "The request body could not be read." | none. **No parser message**, location or class name |
| `InvalidUrlException` | 400 `INVALID_URL` | "The originalUrl is not acceptable." | `errors: [{field: "originalUrl", message: URL_RULE}]` |
| `InvalidAliasException` | 400 `INVALID_ALIAS` | "The alias is not acceptable." | `errors: [{field: "alias", message: ALIAS_RULE}]` (AC4 "identifying the field") |
| `AliasAlreadyExistsException` | 409 `ALIAS_ALREADY_EXISTS` | "The alias is already in use." | none |
| `ShortCodeUnavailableException` | 503 `SHORT_CODE_UNAVAILABLE` | "A short code could not be allocated. Retry later." | none |
| `HttpRequestMethodNotSupportedException` | 405 `METHOD_NOT_ALLOWED` (A4) | generic | none; the `Allow` header is kept |
| `HttpMediaTypeNotSupportedException` | 415 `UNSUPPORTED_MEDIA_TYPE` (A4) | generic | none; the `Accept` header is kept |
| `HttpMediaTypeNotAcceptableException` | 406 `NOT_ACCEPTABLE` (A4, D70) | generic | none. It is raised by `RequestMappingInfoHandlerMapping.handleNoMatch` at **mapping lookup** (class-level `produces`, §2), before the body is read or the service runs, so a 406 never creates a row. The body is `application/problem+json`: Spring falls back to it for `ProblemDetail` when `Accept` doesn't match, and `processHandlerException` has cleared the mapping's producible types (verified). **Exception:** an unparseable `Accept` gets 406 with an **empty body** (§4.3, D70) |
| `NoResourceFoundException`, `NoHandlerFoundException` | 404 `RESOURCE_NOT_FOUND` (A4) | generic | none |
| Any other `ResponseEntityExceptionHandler` type | 4xx gives 400 `MALFORMED_REQUEST`; 5xx gives 500 `INTERNAL_ERROR` | generic | none. Later stories (for example US-011 query parameters) may add specific overrides |
| `Exception` (catch-all) | 500 `INTERNAL_ERROR` | "An unexpected error occurred." | none. Logged at ERROR with method, path and exception (the stack trace goes to the server log only). **`AccessDeniedException` and `AuthenticationException` are rethrown**, so a future method-security failure still reaches `ExceptionTranslationFilter` and becomes 403/401, not 500 |

- `URL_RULE` and `ALIAS_RULE` are built from the shared constants (`UrlValidator.MAX_LENGTH`, `SecureRandomShortCodeGenerator.MIN_LENGTH`/`MAX_LENGTH`), never from repeated literals:
  - "must be an absolute http or https URL with an ASCII host (use punycode for internationalised domains), at most 2048 characters, without user info, and not on this service's host"
  - "must be 3 to 32 characters from A-Z, a-z and 0-9, and not a reserved word"
- **The `errors` extension (D56):**
  - Shape: a JSON array of `{"field": string, "message": string}`, sorted by `field` then `message`.
  - `field` is the JSON property name, which for these records equals the record component name.
  - `message` is the literal constraint message or rule text.
  - It **never includes the rejected value**: no `rejectedValue`, and no echo of the URL or alias.
  - It appears only on `VALIDATION_FAILED`, `INVALID_URL` and `INVALID_ALIAS`.
  - `US-006` defines no class-level constraints. If a later story adds one, it must extend this shape deliberately, for example with a `field` of `null`.
- **Shape parity with security errors (D30, D56).** The entry point and access-denied handler write `ProblemDetails.of(...)` with the context `ObjectMapper`. The advice returns `ResponseEntity<ProblemDetail>` through Spring MVC's converter, which uses the same context mapper and so the same `ProblemDetailJacksonMixin`. Both paths therefore render `errorCode` at the top level with `Content-Type: application/problem+json`. Security bodies have exactly the base keys. Advice bodies have the base keys, plus `errors` only on the three rows above. QA pins this with key-set assertions (§7).
- **Out of reach of the advice** (unchanged, documented in architecture): `StrictHttpFirewall` rejections (plain 400) and errors raised in filters before the `DispatcherServlet` (Boot's `/error` JSON). US-014 may revisit these.

#### 4.3 Framework 404/405/406/415 (A4)

```
Recommendation: add RESOURCE_NOT_FOUND(404), METHOD_NOT_ALLOWED(405), NOT_ACCEPTABLE(406), UNSUPPORTED_MEDIA_TYPE(415)
                to ErrorCode (a D31 extension) and map them as in 4.2.
Reason:         D56 requires every error body to carry the base keys including errorCode, and ErrorCode fixes one status
                per code, so no existing code can carry 405/406/415. They are reachable today on this endpoint:
                GET/PUT /api/v1/urls (405), text/plain body (415), Accept: application/xml (406), /api/v1/nope (404).
                SHORT_URL_NOT_FOUND on an unmapped path would misreport the cause.
Alternative:    (b) map 405/406/415 to 400 MALFORMED_REQUEST and unmapped paths to 404 SHORT_URL_NOT_FOUND - no catalogue
                change, but wrong HTTP semantics and a lost Allow header meaning; (c) leave Spring/Boot defaults - bodies
                without errorCode, violating D56.
Trade-off:      four more catalogue entries to document (US-015) and keep in ErrorCodeTest.
```

If the engineer rejects A4, the fallback is option (b), and the §4.2 rows change accordingly.

**Where 406 comes from (D70).** The 406 is produced at handler-mapping lookup, not when the response is written. `ProducesRequestCondition` fails to match, and `RequestMappingInfoHandlerMapping.handleNoMatch` throws `HttpMediaTypeNotAcceptableException`. No handler runs, no body is read, `@Valid` is not evaluated and `ShortUrlService` is never called, so no row can be created. (The earlier text in this note implied that 406 came from response negotiation after the handler. Without `produces` it did, and that path committed the row first.)

**Precedence (D70): 401 > 405 > 415 > 406 > 400.**
- **401** (and 403 for path variants, D57) comes from the security filter chain, before the `DispatcherServlet`.
- **405, 415, 406** come from `handleNoMatch`, which checks in this order: method mismatch (405), then consumes mismatch (415), then produces mismatch (406), then parameter mismatch (400).
- **400** for the body (`MALFORMED_REQUEST`, `VALIDATION_FAILED`, `INVALID_URL`, `INVALID_ALIAS`), then 409 and 503, can only happen once a handler has matched, so after all of the above.
- Examples: `PUT` with `Accept: application/xml` gives 405; `POST` with `Content-Type: text/plain` and `Accept: application/xml` gives 415; `POST` with a malformed JSON body and `Accept: application/xml` gives 406, not 400.

**Unparseable `Accept` (known deviation from D61, recorded in D70).** For a header that can't be parsed (for example `Accept: foo`), `ProducesRequestCondition.getMatchingCondition` catches the `HttpMediaTypeException` and returns no match, so lookup still gives `HttpMediaTypeNotAcceptableException`, 406 and no row. The advice builds its `ProblemDetail`, but `AbstractMessageConverterMethodProcessor.writeWithMessageConverters` parses the same header again, fails, and, because the status is 4xx, drops the body ("Ignoring error response content"). The client gets **406 with an empty body**, not the problem+json body D61 describes. This is accepted: the request is still rejected before anything is created, and the only alternative is overriding Spring's header parsing. QA pins the empty body so that a framework change that alters it is noticed.

### 5. OpenAPI (AC13, US-005 Q5, D49)

Verified for springdoc 2.8.17:
- `@SecurityScheme` and `@OpenAPIDefinition` are picked up from a `@Configuration` class.
- `@SecurityRequirement` is applied from a class or operation.
- `Principal`-type parameters are excluded, and `@Parameter(hidden = true)` hides a parameter explicitly.
- `springdoc.override-with-generic-response` (default `true`) adds `@ControllerAdvice` responses only for handler methods that have `@ResponseStatus`. `GenericResponseService.evaluateResponseStatus` returns `null` for generic handlers without it, so they are **skipped**. Our `ResponseEntity`-returning handlers therefore add nothing, and every error response must be declared on the operation.
- The docs path stays at the default `/v3/api-docs`.

- **`config/OpenApiConfig`** (`@Configuration(proxyBeanMethods = false)`):
  - `@OpenAPIDefinition(info = @Info(title = "URL Shortener API", version = "v1"))`
  - `@SecurityScheme(name = BASIC_AUTH, type = SecuritySchemeType.HTTP, scheme = "basic")`
  - `public static final String BASIC_AUTH = "basicAuth"`
- **Scope of the security requirement: per controller, not global.** `ShortUrlController` carries the class-level `@SecurityRequirement(name = BASIC_AUTH)`. The redirect controller (US-008) is public and must not show a lock. A global requirement would need an explicit opt-out on every public operation, and forgetting one would document the public redirect as secured.
- **Operation `POST /api/v1/urls`:**
  - `@Operation(summary = "Create a short URL", description = …)`. The description says:
    - the `originalUrl` rules (D11);
    - **non-ASCII (IDN) hosts are rejected with `400 INVALID_URL`, and clients must submit the punycode (`xn--…`) form** (D49);
    - an optional `alias` gives `customAlias: true`, and an existing alias in any status gives 409;
    - the same URL twice gives two codes (D5).
  - The `originalUrl` `@Schema` description repeats the punycode sentence.
  - `@ApiResponse` entries:
    - `201`: `ShortUrlResponse`, `application/json`, with a `@Header(name = "Location")`
    - `400`: lists `VALIDATION_FAILED`, `MALFORMED_REQUEST`, `INVALID_URL`, `INVALID_ALIAS`
    - `401`, `406`, `409`, `415`, `503`
    - `406` (D61, D70) is documented because it is now a deliberate part of the contract: it is rejected at lookup and creates nothing. Its description says the only response type is `application/json`, and that an unparseable `Accept` gets 406 with no body
    - every error response uses `application/problem+json` with the `ErrorResponseSchema` below. **Set `mediaType = "application/problem+json"` explicitly on every error `@Content`.** With class-level `produces`, springdoc otherwise takes the mapping's `application/json` for responses that don't name a media type, and the errors would be documented as `application/json`
- **`api/error/ErrorResponseSchema`**: a documentation-only record, `@Schema(name = "Problem")`, with components `type`, `title`, `status`, `detail`, `instance`, `errorCode`, and `errors` (an array of `FieldViolation`, marked as present only on the three 400 codes). It is never instantiated. The reason: springdoc models `org.springframework.http.ProblemDetail` with a nested `properties` map (springdoc issue #2398, unresolved), which misdocuments the top-level `errorCode`. QA's OpenAPI IT compares this schema's property names with a real error body, so the two cannot drift silently.
- **`/v3/api-docs` stays public.** Rule 3 of the access table already permits it, and no `springdoc.*.path` property is added (US-005 risk K7). `OpenApiDocsIT` keeps its anonymous 200 assertion.

### 6. Test seam for forced collisions (AC6, AC7, AC15)

```
Recommendation: a hand-written ScriptedShortCodeGenerator in src/test, registered ONCE as a @Primary bean in a new
                @TestConfiguration imported by IntegrationTestBase itself (not by any subclass). It delegates to the real
                production generator unless a test has queued codes; tests queue codes and reset it.
Reason:         one shared context and container for every *IT and Cucumber (the base class changes once, for all of
                them, so the context-cache key stays single); no @MockitoBean on subclasses (CLAUDE.md rule); no test
                mode in production - production depends only on the ShortCodeGenerator interface, which the NFRs already
                require to be injectable.
Alternative:    @MockitoSpyBean on IntegrationTestBase (also one context), stubbing generate() per test.
Trade-off:      a small hand-written class to maintain; in exchange, deterministic thread-safe behaviour, explicit reset
                that works identically in JUnit and Cucumber, and no dependence on Mockito's reset listeners running for
                Cucumber scenarios or on Mockito stubbing being safe while request threads call the spy.
```

Files (qa-tester owns them; they are integration test infrastructure):

```java
// support/ScriptedShortCodeGenerator.java
public final class ScriptedShortCodeGenerator implements ShortCodeGenerator {
    private final ShortCodeGenerator delegate;
    private final ConcurrentLinkedQueue<String> queued = new ConcurrentLinkedQueue<>();
    private final AtomicInteger calls = new AtomicInteger();

    public ScriptedShortCodeGenerator(ShortCodeGenerator delegate) { this.delegate = delegate; }

    /** The next generate() calls return these codes, in order; afterwards the real generator is used again. */
    public void willReturn(String... codes) { queued.addAll(List.of(codes)); }
    public int calls() { return calls.get(); }
    public void reset() { queued.clear(); calls.set(0); }

    @Override public String generate() {
        calls.incrementAndGet();
        String next = queued.poll();
        return next != null ? next : delegate.generate();
    }
}

// support/ShortCodeGeneratorTestConfiguration.java
@TestConfiguration(proxyBeanMethods = false)
public class ShortCodeGeneratorTestConfiguration {
    @Bean @Primary
    ScriptedShortCodeGenerator scriptedShortCodeGenerator(@Qualifier("shortCodeGenerator") ShortCodeGenerator real) {
        return new ScriptedShortCodeGenerator(real);
    }
}

// support/IntegrationTestBase.java: @Import({TestcontainersConfiguration.class, ShortCodeGeneratorTestConfiguration.class})
```

- **Wiring.** `@Qualifier("shortCodeGenerator")` selects the production bean by name (the `ShortCodeConfig` method name), so the `@Primary` bean never wraps itself. `ShortUrlService` then gets the scripted bean through `@Primary`. The `@RepositoryTest` slice is not affected, because only `IntegrationTestBase` imports the new configuration.
- **Reset.** Each IT class that uses the seam calls `generator.reset()` in both `@BeforeEach` and `@AfterEach`, so leftovers are cleared even if an earlier test failed mid-way. For Cucumber, a glue class `cucumber/ShortCodeGeneratorHooks` calls `reset()` in `@Before` and `@After` for **every** scenario, so no feature can inherit a queue.
- **Thread safety.** `ConcurrentLinkedQueue` and `AtomicInteger` make `generate()` safe under concurrent requests. AC9 uses custom aliases, so it doesn't call the generator at all. JUnit and Cucumber parallel execution are **off** (`junit-platform.properties` sets no parallel mode). If parallel execution is ever enabled, every test using the seam must hold `@ResourceLock("shortCodeGenerator")`, or the seam must become per-thread. Record this in the class Javadoc.
- **Existing test affected.** `ShortCodeGeneratorWiringIT.shouldExposeGeneratorWithDefaultConfigurationInApplicationContext` autowires `ShortCodeGenerator` and asserts `SecureRandomShortCodeGenerator`. It must inject `@Qualifier("shortCodeGenerator")` instead. QA also adds an assertion that the unqualified bean is the `ScriptedShortCodeGenerator`, which proves the seam is actually in the context.
- **Unit tests (mid-engineer)** stub `ShortCodeGenerator` with Mockito and never use the seam.

### 7. Tests

#### 7.1 AC → component → test → owner

| AC | Component | mid-engineer (`*Test`, Surefire) | qa-tester (Cucumber + `*IT`, Failsafe) |
|---|---|---|---|
| AC1 | Controller, `ShortUrlLinks`, `ShortUrlResponse` | Web slice: 201, `Location` exactly `/api/v1/urls/{code}`, exact key set of the 8 fields, `clickCount` 0, `lastAccessedAt` null | Scenario plus `CreateShortUrlIT`: 201, exact `Location`, exact key set, `createdAt` parses and equals the DB `created_at`, `status` ACTIVE |
| AC2 | Service alias path | Service: `customAlias=true`, `shortCode=alias`, generator never called | Scenario plus IT |
| AC3 | Service alias path, `PostgresServerErrors` | Service: a unique violation gives `AliasAlreadyExistsException` with **one** save; a non-unique DIVE is rethrown as-is | Scenario plus IT against pre-existing rows in **ACTIVE, DEACTIVATED and DELETED** status (D1), inserted via `JdbcTemplate` |
| AC4 | `AliasPolicy` via the service, advice | Service: no repository call. Web slice: `errors[0].field == "alias"`, no rejected value in the body | Scenario Outline: too short, too long, `promo_1`, `API` (a reserved word in another case), `""`, `"   "` |
| AC5 | `UrlValidator` via the service, advice | Web slice: 400 `INVALID_URL` with `errors[0].field == "originalUrl"` | Scenario Outline: `ftp://`, 2049 characters, `https://u:p@x.com`, self host (`https://short.example/x` under A5), IDN `https://bücher.example` (D49) |
| AC6 | Retry loop, seam | Service: collision then success means 2 generator calls, 2 saves, 1 rollback, 1 commit, and every `getTransaction` call receives `PROPAGATION_REQUIRES_NEW` (captor) | `ShortCodeCollisionIT` plus scenario (§7.3) |
| AC7 | Retry loop, seam | Service: `maxAttempts` collisions give `ShortCodeUnavailableException`, the generator is called exactly `maxAttempts` times, and there are no commits | `ShortCodeCollisionIT` plus scenario: **no row committed** (§7.3) |
| AC8 | `SecurityConfig` rule 6 | Web slice: 401 `AUTHENTICATION_REQUIRED`, service not called | Scenario plus IT |
| AC9 | DB unique constraint | n/a | `CreateShortUrlConcurrencyIT` plus scenario (§7.2) |
| AC10 | Controller principal | Web slice: login as `ALICE` means the service receives `createdBy == "alice"` (D54). The response has no `createdBy` key | IT: DB `created_by` is `alice` after a login as `ALICE`; ADMIN creates give `admin`; the body has no `createdBy` |
| AC11 | No deduplication (D5) | Service: two calls with the same URL give two saves | Scenario plus IT: two 201s, different codes, two rows |
| AC12 | `@NotBlank`, advice | Web slice: `{}`, `{"originalUrl":null}`, `""`, `"  "` give `VALIDATION_FAILED` with `errors[0].field == "originalUrl"`; the service is not called | Scenario plus IT |
| AC13 | `OpenApiConfig`, annotations | n/a | `OpenApiDocsIT` extended (§7.5) plus scenario |
| AC14 | Advice | Web slice: `{"originalUrl":`, empty body, `[]`, `{"originalUrl":{}}`, an unknown field and a duplicate key (A2) all give `MALFORMED_REQUEST`. The body contains none of `JSON`, `parse`, `line`, `column`, `com.fasterxml`, `Exception` | Scenario plus IT |
| AC15 | Retry loop, `AliasPolicy` | Service: generator returns `"Health"` then `"Abc1234"`, giving **one** save (no DB round trip for the reserved code) with code `Abc1234` | `ShortCodeCollisionIT` plus scenario (§7.3) |
| AC16 | `ShortUrlLinks` | Web slice: `.header("Host", "attacker.example")` still gives `shortUrl` from the configured base. `ShortUrlLinksTest`: 5 base forms (§1.4) | Scenario plus IT over real HTTP (§7.4) |
| AC17 | Class-level `produces` (D70), advice | Web slice: `Accept: application/xml`, `text/plain` and `application/problem+json` (a valid body with an alias) each give 406 `NOT_ACCEPTABLE`, `Content-Type: application/problem+json`, no `Location`, and **`verifyNoInteractions(service)`**. Positive control in the same class: `Accept: application/json` and `*/*` give 201 and the service is called once. Precedence (§4.3): malformed JSON with `Accept: application/xml` gives 406, not 400, and the service is not called | **`CreateShortUrlIT` over real HTTP against PostgreSQL**, each with its own marker URL: (1) each unacceptable `Accept` gives 406 `NOT_ACCEPTABLE`, a problem+json body, no `Location`, and `SELECT count(*) FROM short_url WHERE original_url = ?` (the marker) is **0**; (2) positive control: the same request with `Accept: application/json` gives 201 and a count of exactly **1**, which makes the zero-row check non-vacuous; (3) alias reuse: 406 with alias X, then the same request with `Accept: application/json` and alias X gives **201, not 409**, and exactly one row has code X; (4) unparseable `Accept` (for example `foo`) gives 406, an **empty body** (the D70 deviation, pinned), and 0 rows for its marker. **Cucumber**: a Scenario Outline over the three unacceptable types asserting 406, `NOT_ACCEPTABLE` and no short URL for the marker, plus the alias-reuse scenario |

Also for the mid-engineer:
- `PostgresServerErrorsTest` (unit): null; a direct PSQLException; the chain DIVE → Hibernate CVE → PSQLException; a cyclic cause chain terminates; a PSQLException without a server message; the right name with the wrong SQLSTATE (`23514`); the right SQLSTATE with the wrong name. It builds `new PSQLException(new ServerErrorMessage("SERROR\0C23505\0nuk_short_url_short_code\0Mx\0"), true)`.
- `ShortUrlRepositoryTest` additions (`@RepositoryTest`, real PostgreSQL):
  - A duplicate `saveAndFlush` gives `DataIntegrityViolationException`, `PostgresServerErrors.isUniqueViolation(e, SHORT_CODE_UNIQUE_CONSTRAINT)` is true, and `PostgresErrors` sees `23505` and `uk_short_url_short_code`. This ties the constant to the V1 schema.
  - A `JdbcTemplate` insert of a 2049-character URL containing a marker gives SQLSTATE `23514` and constraint `ck_short_url_original_url_length`, `isUniqueViolation` is false, and the exception message chain does **not** contain the marker (A7). The constraint assertion is what makes the absence check non-vacuous.
- `GlobalExceptionHandler` through the web slice:
  - A `RuntimeException("secret-marker")` thrown from the mocked service gives 500 `INTERNAL_ERROR`. The body lacks the marker, and the captured log **contains** the ERROR line (non-vacuous).
  - GET and PUT `/api/v1/urls` give 405 with `Allow: POST`.
  - `text/plain` gives 415.
  - `Accept: application/xml` gives 406 with a `problem+json` body and `verifyNoInteractions(service)` (AC17, D70). The rest of AC17's slice tests are in the §7.1 row.
  - Base key sets: a 401 has exactly the base keys; 409 and 503 have exactly the base keys; the three 400 codes have the base keys plus `errors`.
- `ShortUrlServiceTest` also covers:
  - `createdAt` equals `Clock.fixed(...)` truncated to microseconds;
  - `originalUrl` is stored as the identical string (for example `HTTPS://Example.COM/a?b=1#f`);
  - `maxAttempts=1`;
  - a mixed run (reserved, collision, reserved, collision, collision with `maxAttempts=5`) gives 503 after exactly 5 generator calls;
  - logging: the success line contains the code and the host but not the path or query marker, and the "not logged" check also asserts that the success line was captured.
- Web-slice wiring: `@WebMvcTest(ShortUrlController.class)` plus `@Import({SecurityConfig.class, UserAccountsConfig.class, ShortUrlLinks.class})`, a nested `@TestConfiguration @EnableConfigurationProperties(AppProperties.class)` (`ValidationConfig` is package-private), and `@MockitoBean ShortUrlService`. The advice is picked up by the slice automatically.

#### 7.2 AC9 concurrency (`CreateShortUrlConcurrencyIT`)

**(a) Two racing requests.** Repeat 10 times, each time with a fresh alias `"Race" + 8 random Base62 characters`:

```
CyclicBarrier barrier = new CyclicBarrier(2);
Callable<HttpResponse<String>> alice = () -> { barrier.await(10, SECONDS); return post(alias, ALICE); };
Callable<HttpResponse<String>> bob   = () -> { barrier.await(10, SECONDS); return post(alias, BOB); };
List<Future<HttpResponse<String>>> f = Executors.newFixedThreadPool(2).invokeAll(List.of(alice, bob), 30, SECONDS);
```

- Assert the statuses, sorted, are exactly `[201, 409]`.
- The 409 body has `errorCode` `ALIAS_ALREADY_EXISTS`.
- `SELECT count(*) FROM short_url WHERE short_code = ?` is 1, and that row's `created_by` belongs to the caller that got 201.
- The executor is shut down in `finally`.
- Limitation: BCrypt runs before the insert, so real overlap at the INSERT isn't guaranteed. This test proves the outcome under load, and (b) proves the overlap case deterministically.

**(b) Deterministic overlap.**
- Open a separate connection from the `DataSource`, set `autoCommit=false`, and INSERT the alias row as `created_by = 'qa-seed'` **without committing**.
- `sendAsync` a create request for the same alias as `alice`.
- Poll `pg_stat_activity` (up to 10 s) until another backend of the current database shows `wait_event_type = 'Lock'`. Assert the future is not done.
- `commit()` the seed connection. The request completes with `409 ALIAS_ALREADY_EXISTS`.
- **Twin test:** the same, but `rollback()`. The request completes with **201**, and the row belongs to `alice`.
- Together they prove the result comes from the constraint at insert time. No application pre-check is involved.

#### 7.3 AC6, AC7 and AC15 (`ShortCodeCollisionIT`, plus Cucumber scenarios through the same seam)

- **AC6:**
  - Seed a row with code `Coll1de` via `JdbcTemplate` (`created_by = 'qa-seed'`). Call `willReturn("Coll1de")`.
  - POST as alice with a unique marker URL. Expect 201 with `shortCode != "Coll1de"`, `generator.calls() == 2`, and a new row owned by `alice`.
  - The seeded row is unchanged, and there is still exactly one row with code `Coll1de`.
  - Variant: the seeded row is `DELETED` (D1: codes are never reused).
- **AC7:**
  - Seed `maxAttempts` rows (codes `Full001`…`Full005`, using `ShortCodeProperties.maxAttempts()`, not a literal) and queue the same codes.
  - POST with the marker URL `https://example.com/ac7/<uuid>`. Expect 503 `SHORT_CODE_UNAVAILABLE`, and `calls() == maxAttempts`, which proves the bound (never more).
  - **No row committed:** `SELECT count(*) FROM short_url WHERE original_url = ?` (the marker) is 0. Each seeded code still has exactly one row, owned by `qa-seed`.
  - Also check that a follow-up create with an empty queue succeeds (201). This proves the connection pool and state are clean after exhaustion.
- **AC15:**
  - `willReturn("Health")`, a built-in word in another case (D29 matching is case-insensitive). Expect 201 with `shortCode != "Health"` and `calls() == 2`.
  - `SELECT count(*) FROM short_url WHERE lower(short_code) = 'health'` is 0.
  - The lack of a DB round trip for the reserved attempt is proven by the unit test.
- **Cucumber steps:**
  - `Given the next generated short codes are "Coll1de"`
  - `Given a short URL with code "Coll1de" already exists`
  - `Then no short URL exists for original URL "<marker>"`
  - The step classes autowire `ScriptedShortCodeGenerator` and `JdbcTemplate`.

#### 7.4 AC16 Host header (`CreateShortUrlIT` plus scenario) (A5, A9)

- `application-test.yml`: `app.base-url: https://short.example` (A5).
  - `ValidationWiringIT` changes from `http://localhost:8080/x` to `https://short.example/x`.
  - Nothing else references the old value (grep checked).
  - The server runs on `localhost:<random port>`, so every create test now shows that `shortUrl` doesn't come from the request.
- The JDK `HttpClient` refuses to set `Host` unless `jdk.httpclient.allowRestrictedHeaders=host` is set (JDK 25 `java.net.http` docs; the property is intended for testing). The mid-engineer adds `-Djdk.httpclient.allowRestrictedHeaders=host` to the **Failsafe** `argLine` only, so it is set before any client class loads.
  - The test is not vacuous: if the property is missing, `HttpRequest.Builder.header("Host", …)` throws `IllegalArgumentException` and the test fails.
- Send `Host: attacker.example`, and in a second request `X-Forwarded-Host: attacker.example` (`server.forward-headers-strategy` is not set, so it has no effect).
- Assert:
  - `shortUrl` equals `"https://short.example/" + shortCode`
  - `Location` equals `"/api/v1/urls/" + shortCode` (relative, no host)
  - the raw body and headers do not contain `attacker.example`

#### 7.5 AC13 (`OpenApiDocsIT` extended)

`GET /v3/api-docs` anonymously gives 200 (unchanged). Then assert:
- `paths./api/v1/urls.post` exists, with `requestBody.content.application/json`, whose schema has `originalUrl` in `required` and `alias` not required.
- The `201` schema resolves to the 8 properties of §1.3, and there is a `Location` header.
- Responses `400`, `401`, `406`, `409`, `415` and `503` exist, each with `application/problem+json` (and no `application/json` entry) and the `Problem` schema. The `201` content is `application/json` only.
- Its property names equal the key set of a real 400 `INVALID_ALIAS` body from the same test run.
- `components.securitySchemes.basicAuth` is `{type: http, scheme: basic}`, and the operation's `security` contains `basicAuth`.
- The operation has no `parameters` (the `Authentication` parameter is hidden).
- The operation description contains `punycode` (D49).

#### 7.6 Other QA items

- `SecurityIT.shouldNotBlockShortCodePathsForAnonymousGetAndHead` asserts `body doesNotContain("errorCode")` for `GET /abc1234`. Once the advice handles `NoResourceFoundException`, that body is `404 RESOURCE_NOT_FOUND` (A4). Change the check to "no `AUTHENTICATION_REQUIRED` or `ACCESS_DENIED`", as `security.feature` already does. The other `SecurityIT` 404 rows stay 404.
- New `SecurityIT` rows: `POST /API/v1/urls` as alice gives 403 `ACCESS_DENIED`; `POST /api/v1/urls/` as alice gives 404 and no row is created.
- Test data hygiene: the database is shared, so every test uses unique aliases and marker URLs and asserts only on its own rows, never on global counts.

### 8. Implementation plan

**mid-engineer**

1. `pom.xml`: `org.postgresql:postgresql` scope changes from `runtime` to compile (A8). The Failsafe `argLine` gets `-Djdk.httpclient.allowRestrictedHeaders=host` (A9).
2. `application.yml`: the Jackson strictness settings (A2) and the §3.6 settings (A7). `application-test.yml`: `app.base-url: https://short.example` (A5).
3. `repository/PostgresServerErrors`; `ShortUrlRepository.SHORT_CODE_UNIQUE_CONSTRAINT`; `support/PostgresErrors` delegates to the production helper.
4. `service/`: `ShortUrlService`, `CreateShortUrlCommand`, `ShortUrlView`, and `service/exception/*` (§3).
5. `api/`: `ShortUrlController` (class-level `produces = MediaType.APPLICATION_JSON_VALUE`, D70), `ShortUrlLinks`, `dto/CreateShortUrlRequest`, `dto/ShortUrlResponse`, `error/GlobalExceptionHandler`, `error/FieldViolation`, `error/ErrorResponseSchema`. `ProblemDetails` gains `ERRORS` and the `of(...)` overload. `ErrorCode` gains the A4 codes, and `ErrorCodeTest` is updated.
6. `config/OpenApiConfig`.
7. Tests from §7.1 (mid-engineer column).
8. **Carry-over R23:** add D57 to the `SecurityConfig` class Javadoc. **R24:** rename `DELETE_CALLS` to `HANDLER_CALLS` in `SecurityConfigWebMvcTest`.

**qa-tester**

1. `support/ScriptedShortCodeGenerator`, `support/ShortCodeGeneratorTestConfiguration`, and the `@Import` addition on `IntegrationTestBase` (§6). Update `ShortCodeGeneratorWiringIT` (§6) and `ValidationWiringIT` (A5).
2. `features/create-short-url.feature` covering **every API AC**: AC1–AC17, including AC6/AC7/AC15 through the seam, AC9 through a two-thread step, and AC13. CLAUDE.md requires a Cucumber scenario for every API AC, even where the story's table lists only an IT. Add `cucumber/CreateShortUrlSteps` and `cucumber/ShortCodeGeneratorHooks`.
3. `CreateShortUrlIT`, `ShortCodeCollisionIT`, `CreateShortUrlConcurrencyIT`, and the `OpenApiDocsIT` extension (§7.2–§7.5); `SecurityIT` adjustments (§7.6).
4. **Carry-over R20:** reword the `SecurityIT` comment at about lines 318–321 to "403: the ADMIN rule (D3) or the final denyAll (D57) refused the USER; 400: the firewall rejected the path." **R22:** the `/API/` 403 rows also assert `errorCode` `ACCESS_DENIED`.

### 9. Other decisions

**Validation split between the DTO and the service**
```
Recommendation: Bean Validation only for "present and not blank" (AC12 -> VALIDATION_FAILED); UrlValidator and AliasPolicy
                called in the service (AC5 -> INVALID_URL, AC4 -> INVALID_ALIAS).
Reason:         the ACs require distinct errorCodes; AliasPolicy is a configured bean (additional reserved words, D48), and
                the business rule then holds for any future caller of the service, not just this controller.
Alternative:    custom @ValidUrl/@ValidAlias constraints with Spring-injected validators, mapped to their codes by
                inspecting the violated constraint type in the advice.
Trade-off:      two validation mechanisms instead of one; the alternative couples errorCode selection to annotation types
                and moves business rules into the API layer.
```

**Service returns a view record, not the DTO or the entity**
```
Recommendation: ShortUrlView (service) -> ShortUrlResponse (api), with shortUrl added in the api layer by ShortUrlLinks.
Reason:         entities never leave the service layer; the service must not depend on api types; the public link is
                presentation, derived from APP_BASE_URL.
Alternative:    return the entity to the controller, or have the service return ShortUrlResponse.
Trade-off:      one extra small record; both alternatives break a CLAUDE.md layering rule.
```

### 10. Open questions (for the engineer)

- **Q1 (A1–A9):** see §0. A1–A4 block implementation.
- **Q2:** under A1, US-007 AC1 should list `shortUrl` too. **Recommend** that the planner align US-007 when A1 is approved (this note doesn't edit US-007).
- **Q3:** a request body can be up to Tomcat's and Jackson's default limits. Jackson's `StreamReadConstraints` caps strings at 20 M characters, so a multi-megabyte `originalUrl` is parsed before it is rejected. **Recommend** leaving this to US-014 (production hardening). Put a body-size limit at the load balancer or in a servlet filter. `server.tomcat.max-http-form-post-size` does not apply to JSON bodies.
- **Q4:** `HandlerMethodValidationException` and query-parameter type mismatches map to `400 MALFORMED_REQUEST` by the §4.2 fallback. **Recommend** that US-011 (stats query parameters) decide whether those become `VALIDATION_FAILED` with `errors`.
- **Q5 (raised with D70, not blocking):** a create can still commit and then fail to reach the client, for example if the response write fails or the client disconnects. A client that retries with the same alias then gets 409 for its own row. The requirements don't define idempotent create, such as an `Idempotency-Key` header. **Recommend** accepting and documenting this for now; the planner may raise it for US-014 or later. This note doesn't decide it.

### 11. Risks for the implementer and the reviewer

- **K1 Wrong transaction boundary.** A `@Transactional` on `create` or on the controller makes every retry reuse an aborted PostgreSQL transaction (`25P02`). AC6 would then fail only in the IT. Reviewer: no `@Transactional` in `ShortUrlService` or the controller, and the catch must sit outside `requiresNew.execute`.
- **K2 Retrying the wrong thing.** Catching `DataIntegrityViolationException` without `isUniqueViolation(…, SHORT_CODE_UNIQUE_CONSTRAINT)` would turn CHECK violations into retries or 409s. Reviewer: both catch sites call the helper, and anything else is rethrown.
- **K3 Host-derived links.** Any `ServletUriComponentsBuilder` or `request.getServerName()` in `api/` breaks D33. AC16 catches `shortUrl`, and the exact-`Location` assertion catches the header.
- **K4 Full URLs in logs.** Watch for:
  - `log.*(…, request)` or `log.*(…, view)`: record `toString()` contains `originalUrl`;
  - a `@ToString` on the DTOs;
  - logging `e.getMessage()` of a DB exception without A7.

  Reviewer: grep for logging of `originalUrl`, `request` and `command`. Records must never be logged whole.
- **K5 Content negotiation (rewritten for D70).** The original K5 said that `produces` on the mapping would break error-body serialization. **That was wrong.** `DispatcherServlet.processHandlerException` removes `PRODUCIBLE_MEDIA_TYPES_ATTRIBUTE` before any exception resolver runs, so a mapping's `produces` never restricts the advice's `ProblemDetail`. The actual defect was the reverse: without `produces`, 406 was decided after the service had committed the row (§2, §4.3). The risks that remain:
  - **Adding `application/problem+json` to `produces`** mislabels success bodies: `Accept: application/problem+json` would match and the 201 would be sent as `application/problem+json`. Reviewer: `produces` is exactly `MediaType.APPLICATION_JSON_VALUE`. AC17 includes that header, so its tests catch this.
  - **`@ExceptionHandler(produces = …)`** in the advice would set the attribute again. `ExceptionHandlerExceptionResolver.getExceptionHandlerMethod` stores the handler's producible types in `PRODUCIBLE_MEDIA_TYPES_ATTRIBUTE`, and a value that excludes `application/problem+json` leaves the 4xx body empty. Reviewer: no `produces` on any `@ExceptionHandler`.
  - **`produces` must never reach the redirect controller (US-008)** or any shared base class, composed annotation or `@RequestMapping` it could inherit. A client whose `Accept` doesn't include `application/json` or `*/*` would get 406 instead of 302. Reviewer: grep for `produces` outside `ShortUrlController`. US-008 should have a test that sends a redirect request with `Accept: image/png` and expects a 302.
  - **Post-commit failures are still possible.** `produces` removes the only failure after commit that could be avoided. The row can still be committed while the client never sees the 201, for example if serializing or writing the response fails, or the client disconnects. A retry with the same alias then gets 409. This is inherent to a non-idempotent POST, and D70 does not cover it. See open question Q5.
  - **Unparseable `Accept` returns an empty 406** (D70 deviation). Don't "fix" it by catching and rewriting in a filter unless a decision changes D70.
- **K6 Seam hygiene.** A test that queues codes but doesn't reset leaks them into the next test. Every seam user resets before and after each test, and the Cucumber hook resets every scenario. The seam also assumes serial execution (§6).
- **K7 Shared-context rule.** QA must add the seam through `IntegrationTestBase`'s `@Import` only. Any per-class `@Import`, `@MockitoBean` or `@TestPropertySource` starts a second context and container. The US-001 `SharedTestEnvironmentIT` guard should keep passing.
- **K8 A4 side effects.** The advice now renders 404s for unmapped paths and for every public single-segment `GET` (until US-008 adds the redirect). `SecurityIT` expectations that assumed Boot's error JSON must be updated (§7.6). The ERROR-dispatch rule is unchanged.
- **K9 Jackson leniency that stays.** Scalars are coerced to strings (`{"alias": 12345}` becomes `"12345"`). Validation still applies to the string, but a reviewer shouldn't take the A2 strictness to mean type-strict binding.
- **K10 BCrypt in the concurrency test.** Both requests pay cost-10 BCrypt, so (a) may not overlap at the INSERT. (b) is the deterministic proof. Don't weaken (b) into a sleep-based test.
- **K11 Error-body ObjectMapper.** Advice bodies must go through Spring MVC's converter, never a hand-built `ObjectMapper`, or `errorCode` nests under `properties` (US-005 K4). The key-set tests catch this.
- **K12 Tests sharing the database.** Global row counts are unsafe. Use marker URLs and unique aliases.
- **K13 `jdk.httpclient.allowRestrictedHeaders`** is test-only (JDK docs) and must never appear in `spring-boot-maven-plugin`, Surefire, Compose or Dockerfile JVM options.

### 12. Sources checked (2026-09-29)

- **Spring Framework 6.2.19 source (tag `v6.2.19`):**
  - `HibernateJpaDialect.convertHibernateAccessException`: `ConstraintViolationException` becomes `DataIntegrityViolationException`, with the constraint name only in the message; no `DuplicateKeyException` for SQL unique violations.
  - `TransactionTemplate.execute`: rollback on `RuntimeException`/`Error`, then rethrow; `commit` exceptions propagate.
  - `ResponseEntityExceptionHandler`: the 20 handled types, the method signatures, and the committed-response check in `handleExceptionInternal`.
  - `AbstractMessageConverterMethodProcessor.writeWithMessageConverters`: `ProblemDetail` falls back to `application/problem+json`/`+xml`, intersected with the producible types. `getProducibleMediaTypes` reads `PRODUCIBLE_MEDIA_TYPES_ATTRIBUTE`. If `getAcceptableMediaTypes` throws `HttpMediaTypeNotAcceptableException` while the status is 4xx or 5xx, the body is dropped ("Ignoring error response content"), which explains the empty 406 for an unparseable `Accept` (D70).
  - *Added for D70:*
    - `DispatcherServlet.processHandlerException`: `request.removeAttribute(HandlerMapping.PRODUCIBLE_MEDIA_TYPES_ATTRIBUTE)`, with the comment "Success and error responses may use different content types".
    - `ProducesRequestCondition.getMatchingCondition`: an `HttpMediaTypeException` from parsing `Accept` returns no match (`null`). `getAcceptedMediaTypes` resolves the types through the `ContentNegotiationManager` and caches them on the request.
    - `RequestMappingInfoHandlerMapping.handleMatch` sets `PRODUCIBLE_MEDIA_TYPES_ATTRIBUTE` from the matched `produces`. `handleNoMatch` checks methods (405), consumes (415), produces (406), then params (400), in that order.
    - `ExceptionHandlerExceptionResolver.getExceptionHandlerMethod` sets `PRODUCIBLE_MEDIA_TYPES_ATTRIBUTE` from `@ExceptionHandler(produces = …)`, for both controller-local and advice handlers. `doResolveHandlerMethodException` logs "Failure in @ExceptionHandler" and falls back to default processing when the handler itself throws.
- **Spring Framework 6.2 reference:**
  - *Programmatic transaction management*: `TransactionTemplate` instances are thread-safe with configuration state, and settings are made per instance.
  - *Transaction propagation*: REQUIRES_NEW uses an independent physical transaction; pool sizing caveat.
- **Spring Boot 3.5.16:**
  - `TransactionAutoConfiguration`: `transactionTemplate` is `@ConditionalOnMissingBean(TransactionOperations.class)`.
  - `JacksonProperties`: the `parser` feature map exists and is not deprecated.
  - Boot 3.5 how-to *Customize the Jackson ObjectMapper*: `FAIL_ON_UNKNOWN_PROPERTIES` and `WRITE_DATES_AS_TIMESTAMPS` are disabled by default, and the `spring.jackson.deserialization.*` syntax.
- **Hibernate ORM 6.6.53 `PostgreSQLDialect`:** the constraint name for `23505`/`23514` comes from message-template parsing, and no constraint kind is set in the dialect.
- **pgjdbc 42.7.11:**
  - `ServerErrorMessage`: `getConstraint()` reads field `n`, `getSQLState()` reads field `C`, and `toString()` always appends `Detail`.
  - `PSQLException(ServerErrorMessage, boolean detail)`: `detail=false` uses `getNonSensitiveErrorMessage()` and keeps the full `ServerErrorMessage`.
  - jdbc.postgresql.org *Using the driver*: `logServerErrorDetail`, default `true`.
- **springdoc-openapi 2.8.17:**
  - `GenericResponseService` (tag `v2.8.17`): advice handlers without `@ResponseStatus` are skipped, and the `override-with-generic-response` check. *Added for D70:* `buildContentFromDoc` passes `methodAttributes.getMethodProduces()` as the fallback media types for an `@ApiResponse` whose `@Content` has no `mediaType`, and an explicit `mediaType` takes precedence.
  - springdoc.org properties and FAQ: `@SecurityScheme`, `@SecurityRequirement` on operations, `Principal` exclusion, `@Parameter(hidden = true)`, `@Configuration` placement, the default `/v3/api-docs` path.
  - GitHub issue springdoc/springdoc-openapi#2398: the `ProblemDetail` schema renders a `properties` map, and the issue is unresolved.
- **JDK 25 `java.net.http` module summary:** `jdk.httpclient.allowRestrictedHeaders`; `host` is restricted by default; the property is intended for testing only.

## Implementation notes

**Files changed (production, `src/main`)**
- `pom.xml`: PostgreSQL driver to compile scope (D65); `-Djdk.httpclient.allowRestrictedHeaders=host` in the Failsafe `argLine` only (D66).
- `resources/application.yml`: strict Jackson (D59), `logServerErrorDetail: false` and `SqlExceptionHelper` logging `off` (D64).
- `api/`: `ShortUrlController`, `ShortUrlLinks`, `dto/CreateShortUrlRequest`, `dto/ShortUrlResponse`, `error/GlobalExceptionHandler`, `error/FieldViolation`, `error/ErrorResponseSchema`; `error/ErrorCode` (+4 codes, D61); `error/ProblemDetails` (`ERRORS`, `of(..., errors)`).
- `service/`: `ShortUrlService`, `CreateShortUrlCommand`, `ShortUrlView`, `exception/{InvalidUrl,InvalidAlias,AliasAlreadyExists,ShortCodeUnavailable}Exception`.
- `repository/`: `PostgresServerErrors` (new), `ShortUrlRepository.SHORT_CODE_UNIQUE_CONSTRAINT`.
- `config/OpenApiConfig`; `security/SecurityConfig` Javadoc (R23, D57).

**Files changed (tests, `src/test`)**
- New: `service/ShortUrlServiceTest`, `api/ShortUrlControllerWebMvcTest`, `api/ShortUrlLinksTest`, `api/error/GlobalExceptionHandlerTest`, `api/error/DatabaseErrorLoggingTest`, `repository/PostgresServerErrorsTest`, `security/SecuritySliceTestConfiguration`.
- Changed: `ErrorCodeTest`, `ProblemDetailsTest`, `ShortUrlRepositoryTest` (2 tests), `support/PostgresErrors` (delegates to `PostgresServerErrors`), `security/SecurityConfigWebMvcTest` (R24: `HANDLER_CALLS`), `resources/application-test.yml` (`app.base-url: https://short.example`, D62).
- Not touched (QA-owned): the `ScriptedShortCodeGenerator` seam, `ShortCodeGeneratorTestConfiguration`, the `IntegrationTestBase` `@Import`, all `*IT`, Cucumber.

**Decisions**
- `ShortUrlService` has an explicit constructor (it builds the `REQUIRES_NEW` `TransactionTemplate`), as the Design note says, instead of `@RequiredArgsConstructor`. No `@Transactional` anywhere in `service` or `api` (guarded by a reflection test). The catch of `DataIntegrityViolationException` is outside `requiresNew.execute`.
- Only `uk_short_url_short_code` (SQLSTATE 23505 plus constraint name) is retried or mapped to 409. Everything else is rethrown and reaches the catch-all as 500.
- The success log line takes the host through `HttpUris.parseHttpUri` (falls back to `unknown`), not a raw `URI.create`.
- `handleExceptionInternal` always rebuilds the body, so no Spring default text reaches a client. A framework failure mapped to 500 is logged there once at ERROR (`Unhandled exception: method={} path={}`, with the exception); the catch-all never sees these, so nothing is logged twice. `MALFORMED_REQUEST` has one detail text, in `DETAIL`. Fallback: other 4xx give 400 `MALFORMED_REQUEST`, 5xx give 500 `INTERNAL_ERROR` (D69).
- `SecuritySliceTestConfiguration` (test) is new: `SecurityConfig` and `UserAccountsConfig` are package-private, so a slice test in `api` cannot `@Import` them by class literal, as the Design note's wiring assumed. The helper is public and lives in the `security` test package.
- The D64 log test forces a real CHECK violation through JPA, Hibernate, pgjdbc and PostgreSQL, then feeds the exception to the real advice catch-all, which logs it with its cause chain. It positively asserts the ERROR line and the driver's constraint name, then asserts the absence of "Failing row contains", the URL marker and the username marker. Mutation-checked: `logServerErrorDetail: true` fails it (and the `ShortUrlRepositoryTest` message check); `SqlExceptionHelper` set to `error` fails it.

**Needs human review**
1. **Resolved by D70 (engineer-approved):** the earlier design gap (`Accept: application/xml` committed the row and then returned 406) is fixed by class-level `produces = application/json` on `ShortUrlController` (application/json only, never problem+json). 406 now happens at mapping lookup, so nothing is created. Known deviation: an unparseable `Accept` gets 406 with an empty body. The 406 response is documented in OpenAPI, and every error `@Content` sets `mediaType = application/problem+json` explicitly.
2. AC17 web-slice tests: `application/xml`, `text/plain` and `application/problem+json` each give 406 `NOT_ACCEPTABLE` with a problem+json body, no `Location` and `verifyNoInteractions(service)`. Positive controls with `application/json` and `*/*` give 201. Malformed JSON with an XML `Accept` gives 406, not 400. The zero-row proof against the database is QA's `*IT`.
3. `ProblemDetails.of` still calls `URI.create(requestUri)`; the advice now feeds it `getRequestURI()` for every error, including unmapped 404 paths. Tomcat and the strict firewall keep illegal characters out (verified for the paths tested), but the advice has no fallback if that ever fails.

**QA-owned tests that fail with this change (not edited)**
- `SecurityIT.shouldNotBlockShortCodePathsForAnonymousGetAndHead(String)[1]` (line 230): `GET /abc1234` now returns 404 `RESOURCE_NOT_FOUND` (D61), and the test asserts the body does not contain `errorCode`.
- `ValidationWiringIT.shouldRejectOwnHostFromConfiguredBaseUrlIgnoringCaseTrailingDotPortAndScheme` (line 25): it uses `http://localhost:8080/x` as the own host, and D62 changes the base to `https://short.example`.
- `ShortCodeGeneratorWiringIT` still passes today. It will need the qualifier change once QA adds the `@Primary` seam.

**Command and result:** `JAVA_HOME=/usr/local/opt/openjdk/libexec/openjdk.jdk/Contents/Home ./mvnw -q verify`. Surefire: 510 run, 0 failures, 0 errors. Failsafe: 78 run, 2 failures (the two QA tests above), 0 errors. Failsafe verify fails before `jacoco:check` runs, so the gate itself was not evaluated; the merged report (`target/site/jacoco-merged/jacoco.csv`) shows LINE 378/382 covered (98.95%).

**Fix round 1 (R1-R4):** `GlobalExceptionHandler` now logs framework 5xx once and always rebuilds the body; one `MALFORMED_REQUEST` detail text; `DatabaseErrorLoggingTest` uses AssertJ `catchThrowable`. Result after the round: `./mvnw -q verify` exit 0, Surefire 515 run, 0 failures, 0 errors; Failsafe 215 run, 0 failures, 0 errors; merged LINE 380 of 382 covered (99.48%), gate passed.

## QA notes

**Seam (test-only, no production test mode).** `support/ScriptedShortCodeGenerator` (thread-safe: `ConcurrentLinkedQueue` and `AtomicInteger`), `support/ShortCodeGeneratorTestConfiguration` (`@Primary`, wraps `@Qualifier("shortCodeGenerator")`), imported once by `IntegrationTestBase`. Reset before and after every test (`@BeforeEach`/`@AfterEach` in each IT) and every Cucumber scenario (`cucumber/ShortCodeGeneratorHooks`, which also truncates `short_url` before each scenario). Helpers: `support/ApiClient` (real HTTP, credentials resolved from `TestUsers`, never printed) and `support/ShortUrlTestData` (seed rows, count by marker).

**Files written**
- Feature: `src/test/resources/features/create-short-url.feature` (47 scenarios/examples).
- Glue: `cucumber/CreateShortUrlSteps`, `cucumber/ShortCodeGeneratorHooks`.
- IT: `support/CreateShortUrlIT`, `support/ShortCodeCollisionIT`, `support/CreateShortUrlConcurrencyIT`; extended `support/OpenApiDocsIT`.
- Changed: `support/SecurityIT` (D61 re-pin, R20, R22, two new path-variant rows), `validation/ValidationWiringIT` (D62), `shortcode/ShortCodeGeneratorWiringIT` (qualifier plus seam assertion), `support/IntegrationTestBase` (`@Import`).

**AC to test**

| AC | Cucumber (`create-short-url.feature`) | `*IT` |
|---|---|---|
| AC1 | Creating a short URL without an alias | `CreateShortUrlIT.shouldReturn201WithDocumentedResourceWhenNoAliasIsGiven`, `shouldStoreAndReturnTheOriginalUrlExactlyAsSubmitted` |
| AC2 | Creating a short URL with a custom alias | `shouldUseTheSubmittedAliasAsShortCodeAndFlagItCustom`, `shouldAcceptAliasesAtTheLengthLimits`, `shouldLetAdminCreateThroughTheRoleHierarchy` |
| AC3 | Outline over ACTIVE, DEACTIVATED, DELETED | `shouldReturn409WhenAliasAlreadyExistsInAnyStatus` |
| AC4 | Outline (too short, too long, charset, reserved, cased, empty, blank) | `shouldReturn400InvalidAliasWithFieldAndNoEchoedValue` |
| AC5 | Outline (scheme, credentials, own host, IDN) plus 2049 chars | `shouldReturn400InvalidUrlWithFieldAndStoreNothing`, `shouldReturn400InvalidUrlWhenUrlExceeds2048Characters`, `shouldReportInvalidUrlFirstWhenUrlAndAliasAreBothInvalid` |
| AC6 | Generated code that collides is retried | `ShortCodeCollisionIT.shouldRetryInFreshTransactionAndReturn201WhenFirstGeneratedCodeCollides` (3 statuses), `shouldSucceedOnTheLastAllowedAttemptWhenEarlierAttemptsCollide` |
| AC7 | Every attempt collides, nothing committed | `ShortCodeCollisionIT.shouldReturn503AndCommitNothingWhenEveryAttemptCollides` (marker count 0, then follow-up 201 and count 1) |
| AC8 | Creating without credentials | `CreateShortUrlIT.shouldReturn401AndCreateNothingWhenNoCredentialsAreSent` |
| AC9 | Two callers racing (barrier, two threads) | `CreateShortUrlConcurrencyIT` race x10, plus the two lock-based tests (commit gives 409, rollback gives 201) |
| AC10 | Outline alice, ALICE, admin | `shouldRecordConfiguredUsernameAsCreatorAndNeverReturnIt` |
| AC11 | Same URL twice | `shouldCreateTwoDifferentShortCodesForTheSameUrl` |
| AC12 | Outline (5 bodies) | `shouldReturn400ValidationFailedWhenOriginalUrlIsMissingOrBlank` |
| AC13 | API documentation scenario | `OpenApiDocsIT` (7 new tests, see below) |
| AC14 | Outline (5 bodies) | `shouldReturn400MalformedRequestWithoutParserDetails` (9 bodies) |
| AC15 | Outline Health, LOGIN, v3 | `ShortCodeCollisionIT.shouldTreatReservedWordAsCollisionAndRetryWithoutTouchingTheDatabase` (5 words), `shouldReturn503WhenEveryGeneratedCodeIsReservedAndInsertNothing` |
| AC16 | Forged Host header | `shouldBuildShortUrlFromConfiguredBaseUrlNotFromForgedHostHeader`, `shouldIgnoreForwardedHostHeaderWhenBuildingShortUrl` |
| AC17 | Outline (xml, text, problem+json), alias-reuse scenario | `shouldReturn406AndCreateNothingWhenAcceptIsUnacceptable` (4 types, with positive control), `shouldFreeTheAliasWhenTheFirstRequestWasRefusedForItsAcceptHeader`, `shouldReturn406WithEmptyBodyAndCreateNothingWhenAcceptIsUnparseable`, `shouldCreateExactlyOneRowWhenAcceptAllowsJson`; precedence rows (401, 415, 406 before 400) |

Extras beyond the ACs: 405 with `Allow`, 415, precedence 401 > 415 > 406 > 400 (D70), `SecurityIT` `POST /API/v1/urls` (403 `ACCESS_DENIED`) and `POST /api/v1/urls/` (404 `RESOURCE_NOT_FOUND`), both with zero rows.

**AC9 lock test.** No `Thread.sleep` anywhere in the new tests. The wait is an Awaitility condition poll (10 s deadline, 20 ms poll) on `pg_stat_activity` for a backend with `wait_event_type = 'Lock'` running `insert into short_url`, followed by `assertThat(request).isNotDone()`, then commit or rollback.

**Results** (`JAVA_HOME=/usr/local/opt/openjdk/libexec/openjdk.jdk/Contents/Home ./mvnw -q verify`, run twice, same result): exit 0. Surefire 515 run, 0 failures, 0 errors, 0 skipped. Failsafe 215 run, 0 failures, 0 errors, 0 skipped, of which Cucumber 59 scenarios (47 create, 12 existing), `CreateShortUrlIT` 68, `ShortCodeCollisionIT` 11, `CreateShortUrlConcurrencyIT` 3, `OpenApiDocsIT` 9, `SecurityIT` 50. JaCoCo merged gate passed (378 of 382 lines covered).

**Defects:** none found.

**Ambiguities:** none blocking. Note that AC3 (409 for a DELETED row) and the unparseable-`Accept` empty body are pinned as documented (D1, D70).

## Review log
*(senior-engineer findings per round, and how each was resolved)*

| Round | ID | Severity | Finding | Resolution |
|---|---|---|---|---|
| 1 | R1 | SHOULD | Framework 5xx through `handleExceptionInternal` returned `INTERNAL_ERROR` without a log line | Fixed: logged once at ERROR (method and path only, with the exception). `GlobalExceptionHandlerTest` asserts exactly one ERROR event, exact message, exception class, no query string |
| 1 | R2 | SHOULD | Unreachable "keep body with `errorCode`" branch | Fixed: removed, the body is always rebuilt |
| 1 | R3 | NIT | Two `MALFORMED_REQUEST` detail texts | Fixed: one text in `DETAIL` ("The request could not be read."), used in both places. No test asserted either text |
| 1 | R4 | NIT | Hand-written `catchThrowable` in `DatabaseErrorLoggingTest` | Fixed: AssertJ `catchThrowable` |
| 1 | R5 | NIT | Dead code and duplication in test support: `ApiClient.pairs(Map)` unused; `SecurityIT` used inline fully qualified `java.util.*` and `JdbcTemplate` names; `SecurityIT.rowsFor` duplicated `ShortUrlTestData.countByOriginalUrl` | Fixed: `pairs` deleted; imports added (also in `ApiClient.keys`); `rowsFor` removed and `ShortUrlTestData.countByOriginalUrl` reused |
| 1 | R6 | NIT | `OpenApiDocsIT` had an unused `JdbcTemplate jdbc` field | Fixed: field and import removed |
| 1 | R7 | NIT (optional) | AC7 scenario hard-coded 5 codes and 5 calls, so it would not follow `ShortCodeProperties.maxAttempts()` | Fixed: new steps seed and queue `maxAttempts()` colliding codes and assert the generator was asked for `maxAttempts()` codes; the old hard-coded seeding step was removed as unused |
| 1 | R8 | NIT | `CreateShortUrlIT` and `ShortUrlTestData.truncate()` Javadocs said isolation is by marker URLs, but three IT classes and the Cucumber hook also truncate `short_url` | Fixed: both Javadocs now say truncation assumes serial execution, as the generator seam does, and that enabling parallel execution means revisiting truncation and the seam together |
| 1 | R9 | NIT | AC16 forged-`Host` test proved the JVM property is set but not that the forged `Host` reaches Tomcat | Fixed: positive control `shouldReachTomcatWithForgedHostHeaderSoTheAc16TestIsNotVacuous` sends `Host: bad host`; a real run showed Tomcat answers 400, pinned, and no row is created |
| 1 | R10 | - | Orchestrator item | orchestrator at commit |
| 2 | R1–R9 | — | Re-review of fix round 1 | **Resolved**. Verdict **APPROVE**. R1 was checked: every 5xx is logged exactly once, with method and path only |
| 2 | R10 | NIT | Architecture markers still said "designed (US-006), pending G2" | Fixed by the orchestrator |
| 2 | N1 | NIT | `import java.util.UUID` is out of order in `SecurityIT` | Open |
| 2 | N2 | NIT | Inline fully qualified `java.util.ArrayList` and `Spliterators`, plus duplicated key-set extraction, in `OpenApiDocsIT` and `SecurityIT` | Open |
| 2 | N3 | NIT | The `bad host` control asserts only 400, not that it came from Tomcat (no `errorCode`) | Open |

**Orchestrator verification (2026-09-29):**
- `./mvnw -q clean verify` passed (exit 0).
  - Surefire: 515 run, 0 failed, 0 errors, 0 skipped.
  - Failsafe: 216 run, 0 failed, 0 errors, 0 skipped (includes 59 Cucumber scenarios).
  - Merged LINE coverage: 380/382 (99.5%), with fresh exec files.
- The build log contains 0 "Failing row contains" lines (D64) and no generated security password.
- Guardrails confirmed on disk:
  - No `@Transactional` anywhere in `src/main`.
  - `produces` appears only on `ShortUrlController` (D70).
  - `allowRestrictedHeaders` appears only in the Failsafe `argLine` (`pom.xml:316`; the Surefire `argLine` is at `:281`).
  - `Thread.sleep` appears nowhere in `src/test`.

### Proposed review rules (senior-engineer, US-006; for the engineer to decide)
Round 1:
1. "Every 5xx the advice produces is logged at ERROR exactly once with the exception. This includes framework exceptions that go through `handleExceptionInternal`, not only the `Exception` catch-all."
2. "Tests may truncate shared tables only in per-test or per-scenario setup, must never rely on rows created by another class, and assume serial execution. Enabling parallel execution requires revisiting truncation and the generator seam together."
3. "HTTP tests that forge a restricted header (`Host`) include a positive control proving the header reached the server."

Round 2:

4. "After any bulk search-and-replace in tests, reviewers check the `git diff` of each touched file for broken or out-of-order imports and for leftover inline fully qualified names, not just a passing build."

## Post-completion change (US-011; mid-engineer)
- Status stays **Done**. `GlobalExceptionHandler` gains one handler for `InvalidStatsQueryException` (stats query validation, D99), so its Done-story test class is extended.
- **`api/error/GlobalExceptionHandlerTest`**
  - **Edited, no assertion changed:** new imports (`StatsPeriod`, `InvalidStatsQueryException`, `StatsParameter`, `ServletRequestBindingException`).
  - **Added:** `shouldMapInvalidStatsQueryToSortedFieldViolationsWithTheQueryDetailAndNoValue`, `shouldKeepTheBodyTextOfValidationFailedForRequestBodies`, `shouldMapAnUnexpectedOrRepeatedParameterExceptionToMalformedRequestWithoutItsMessage`.
  - **Unchanged:** every other test in the class.
- **QA-owned support edit made in US-011 (qa-tester)**
  - **`support/ApiClient`:** added `headersExceptFraming(HttpResponse)` (drops Date, Content-Length, Transfer-Encoding and Connection; a static constant set and `Locale.ROOT` are used) and the `Locale` import. No existing method changed, so no US-006 test is affected.
