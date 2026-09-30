---
id: US-007
title: Get short URL details API
status: Done
plan_task: 6
depends_on: [US-002, US-005, US-006]
requirements: [FR-12, D4, D13, D31, D58, D67, D71, D72, D73, D74]
requires_design_approval: true
---

# US-007: Get short URL details API

## User story
As the owner of a short URL (or an ADMIN), I want to retrieve its details, so that I can confirm what was created and see its current status.

## Acceptance criteria
- **AC1:** Given the caller is the owner (`created_by` matches the authenticated principal) and the link is `ACTIVE` or `DEACTIVATED`, when `GET /api/v1/urls/{code}` is called, then the response is `200 OK` with a body containing exactly the D58 field set, identical to the US-006 create response: `shortCode`, `shortUrl`, `originalUrl`, `status`, `customAlias`, `clickCount`, `createdAt` (ISO-8601 UTC) and `lastAccessedAt` (always present; `null` until the first click). `createdBy`, `id`, `updatedAt` and `version` are not returned (D58, D67).
- **AC2:** Given the caller has role `ADMIN`, when `GET /api/v1/urls/{code}` is called for any link regardless of owner, then the response is `200 OK` with the same shape.
- **AC3:** Given the caller is an authenticated `USER` who does not own the link, when `GET /api/v1/urls/{code}` is called, then the response is `404 Not Found` with `errorCode: "SHORT_URL_NOT_FOUND"` — ownership is never revealed by a 403 (D4).
- **AC4:** Given `{code}` does not exist, when `GET /api/v1/urls/{code}` is called, then the response is `404 Not Found` with `errorCode: "SHORT_URL_NOT_FOUND"`.
- **AC5:** Given `{code}` belongs to a `DELETED` link, when `GET /api/v1/urls/{code}` is called by any caller including `ADMIN`, then the response is `404 Not Found` (D13 — deleted links are invisible to everyone in the management API).
- **AC6:** Given no credentials are supplied, when `GET /api/v1/urls/{code}` is called, then the response is `401` with `errorCode: "AUTHENTICATION_REQUIRED"` (D31).

## Tests required
| Type | Behaviour proven | Owner |
|---|---|---|
| Unit | Ownership check logic: owner vs non-owner vs ADMIN vs deleted (AC1–AC5) | mid-engineer |
| Web slice | 200 body has exactly the D58 field set (`shortCode`, `shortUrl`, `originalUrl`, `status`, `customAlias`, `clickCount`, `createdAt`, `lastAccessedAt`), with `lastAccessedAt` present as `null` before any click and `createdBy`, `id`, `updatedAt`, `version` absent; status codes for AC4 and AC6 wired through the controller (AC1, AC4, AC6) | mid-engineer |
| Cucumber | Owner retrieves their own link and gets 200 with exactly the D58 field set (no extra fields; `createdBy`, `id`, `updatedAt`, `version` absent; `lastAccessedAt` is `null` before the first click; shape matches the US-006 create response) (AC1) | qa-tester |
| Cucumber | ADMIN retrieves any link and gets 200 (AC2) | qa-tester |
| Cucumber | Non-owner USER retrieves someone else's link and gets 404 `SHORT_URL_NOT_FOUND` (AC3) | qa-tester |
| Cucumber | Unknown `{code}` returns 404 `SHORT_URL_NOT_FOUND` (AC4) | qa-tester |
| Cucumber | A `DELETED` link returns 404 for any caller including ADMIN (AC5) | qa-tester |
| Cucumber | No credentials returns 401 `AUTHENTICATION_REQUIRED` (AC6) | qa-tester |
| Integration (`*IT`) | Real ownership scenarios against Testcontainers PostgreSQL: owner, non-owner USER, ADMIN, deleted link (AC1–AC5) | qa-tester |

## Out of scope
- Statistics (click counts over time, timezone bucketing) — see US-011. This story returns only the aggregate `clickCount` already stored on the row, not a daily breakdown.
- Lifecycle changes (PATCH/DELETE) — see US-009.

## Risks
- None beyond those already flagged in US-006 (shared response shape). The `errorCode` catalogue itself is fixed by D31.

## Open questions
- None. The response shape is resolved by D58 (shared with US-006) and D67 (AC1 lists `shortUrl` and the full D58 field set).

## Carry-over from US-006 (engineer-approved at US-006 G3)
- **N1 (NIT, qa-tester):** move `import java.util.UUID` into order in `SecurityIT`.
- **N2 (NIT, qa-tester):** import the inline fully qualified `java.util.ArrayList` (`OpenApiDocsIT`) and `java.util.Spliterators` (`SecurityIT`). Reuse `ApiClient.keys` for the duplicated key-set extraction in `SecurityIT`.
- **N3 (NIT, qa-tester):** the `Host: bad host` positive control in `CreateShortUrlIT` must also assert that the 400 came from Tomcat (no `errorCode` and no `application/problem+json`).
- **D71 note (product):** after an unexpected `409 ALIAS_ALREADY_EXISTS` on create, a client can call `GET /api/v1/urls/{alias}` to check. It returns 200 only if the alias is the caller's own (D4), which confirms that an earlier create whose 201 was lost did succeed. The US-007 OpenAPI description for `GET /api/v1/urls/{code}` should mention this use.

## Design note

*Architect, 2026-09-29. Status: **approved at G2 (2026-09-29)**, with Q1–Q3 recorded as D72–D74 and the rest approved as written. Gate IDs B1–B5 and question IDs Q1–Q4 are for this note only. Once the engineer decides, the orchestrator records the outcomes as D72 onward. Code, tests and SQL must cite those `Dnn` IDs, never `B1`, `Q1` or section numbers (CLAUDE.md review rule). **No migration**: V1 already has everything this story reads.*

### 0. Engineer decisions required at G2

| # | Decision | Recommendation | Blocking? |
|---|---|---|---|
| **B1** | `{code}` values that can never exist (`ab`, `a-b`, 33 characters, `abc%20`). | **Check the D6 format in the service before any DB call, and return the same `404 SHORT_URL_NOT_FOUND` as for an unknown code.** The check is format-only and never uses reserved words (§2.4). | **Yes.** It decides whether malformed input reaches the database, and US-008's redirect will reuse the same helper. |
| **B2** | Ownership lookup strategy. | **Load by code, then check in Java**: missing, then `DELETED`, then owner or ADMIN. There is no owner-filtered query (§2.5). | **Yes.** US-009 PATCH reuses it. |
| **B3** | `Cache-Control` on the management GET. | **Rely on Spring Security's default** (`no-cache, no-store, max-age=0, must-revalidate`, plus `Pragma` and `Expires`). Set nothing in the controller, and have QA pin the exact value (§1.6). | No |
| **B4** | Transaction for the read. | A **read-only `TransactionTemplate`** built in the service constructor, the same pattern as create's `REQUIRES_NEW` template. No `@Transactional`, so the existing reflection guard keeps passing (§3.2). | No |
| **B5** | Where the D6 format rule lives. | A new `shortcode/ShortCodeFormat.isWellFormed(String)`. `AliasPolicy.isValid` delegates to it for the format part (the CLAUDE.md single-shared-constant rule) (§2.4). | No |

### 1. API contract

#### 1.1 Endpoint

`GET /api/v1/urls/{code}` on `ShortUrlController`:

```java
@GetMapping("/{code}")
@Operation(...) @ApiResponse(...)                                   // section 5
ShortUrlResponse get(@PathVariable("code") String code,
                     @Parameter(hidden = true) Authentication authentication) {
    return ShortUrlResponse.from(service.get(code, callerOf(authentication)), links);
}
```

- **Content negotiation (D70).** The method inherits the class-level `produces = application/json`. It declares no `produces` or `consumes` of its own. An unacceptable `Accept` (`application/xml`, `text/plain`, `application/problem+json`) gets `406 NOT_ACCEPTABLE` at mapping lookup, and the service is never called. An unparseable `Accept` gets 406 with an empty body (the known D70 deviation). Precedence stays **401 > 405 > 415 > 406 > 400/404**, so an unknown code with `Accept: application/xml` gets 406, not 404. That reveals nothing, because the same happens for every code.
- **`@PathVariable("code")` names the variable explicitly**, so the mapping doesn't depend on the `-parameters` compiler flag.
- **Response:** `200 OK`, `Content-Type: application/json`, and the body is `ShortUrlResponse.from(view, links)`. That is the **same record and factory as create** (D58, D67), so the two bodies cannot drift. There is no `Location`, no `ETag` and no `Last-Modified`.
- **The service returns `ShortUrlView`, which has no `createdBy`** (D58). So the controller *cannot* check ownership even by mistake: the data isn't there (see risk K1).

#### 1.2 Filter-chain rule that admits it (CLAUDE.md rule)

**Rule 6** of the architecture access table, `.requestMatchers("/api", "/api/**").hasRole(Role.USER.name())`. ADMIN passes through the `ADMIN > USER` `RoleHierarchy` (D3). Rule 5 (`DELETE /api/v1/urls/**`) doesn't match GET or HEAD. Rule 7 (`GET`/`HEAD /*`) matches single segments only. **`SecurityConfig` needs no change.** Anonymous callers get `401 AUTHENTICATION_REQUIRED` with `WWW-Authenticate: Basic` from the entry point (AC6, D30). The `ShortUrlController` class Javadoc, which today names rule 6 for POST only, is updated to name it for GET too.

#### 1.3 Path variants (covered by existing rules; QA pins them)

| Request (as alice) | Result | Why |
|---|---|---|
| `GET /api/v1/urls/{code}/` | `404 RESOURCE_NOT_FOUND` | Rule 6 admits it. Spring MVC 6 doesn't match an optional trailing slash, so no handler matches and `NoResourceFoundException` is raised (D61). The result is the same for every code, so it reveals nothing |
| `GET /api/v1/urls/{code}/x` | `404 RESOURCE_NOT_FOUND` | Same reason |
| `GET /API/v1/urls/{code}` | `403 ACCESS_DENIED` | The final `denyAll` (D57); matchers are case-sensitive |
| `GET /api/v1/urls/abc;x=1`, `/api/v1/urls/%2e%2e`, `%0a` variants | `400` (firewall) | `StrictHttpFirewall`, unchanged (existing `SecurityIT` pins) |
| `GET /api/v1/urls` | `405` with `Allow` containing `POST` | Unchanged. `/{code}` doesn't match the collection path |
| `PUT`/`POST`/`PATCH /api/v1/urls/{code}` | `405 METHOD_NOT_ALLOWED`, `Allow` contains `GET` | The GET mapping now matches the path. PATCH becomes 200/409 in US-009 |
| `DELETE /api/v1/urls/{code}` as admin | `405` (previously 404) | The existing `SecurityIT.shouldNotReturn403WhenAdminDeletes` accepts 404 or 405, so it still passes. US-009 revisits it |

Spring MVC 6 matches paths case-sensitively, and suffix pattern matching is off by default (since 5.3), so `abc.json` is the literal code `abc.json`. It fails D6 and gets 404.

#### 1.4 Case sensitivity (D6)

- `findByShortCode` generates `WHERE short_code = ?` on `TEXT` with the database default collation. PostgreSQL's standard collations are **deterministic**: two strings are equal only if their bytes are identical. So `AbC123` and `abc123` are different rows.
- The service never case-folds the code, and no `lower(...)` or `IgnoreCase` derived query may be introduced.
- The path variable arrives percent-decoded, so `/api/v1/urls/Ab%43` resolves to code `AbC`. That is the same resource under the same rules, so it's harmless. `instance` still echoes the raw request URI (§4.3).

#### 1.5 HEAD

Spring MVC maps HEAD onto `@GetMapping` implicitly. The servlet's HEAD wrapper runs the handler and writes no body. *Correction (orchestrator, from the QA run):* on this stack (Boot 3.5.16, embedded Tomcat), `HEAD` returns 200 with `Content-Type: application/json`, no body, and **no** `Content-Length` header. The Spring reference's statement that `Content-Length` is set does not hold here; see the recorded behaviour in the QA notes.

For this endpoint that is harmless:
- The handler is a read-only lookup with no side effects.
- D9 and D18 ("HEAD not counted") are about the public redirect, not the management API.
- The ownership rules apply exactly as for GET: owner or ADMIN gets 200, everyone else gets 404, and anonymous callers get 401. All of these come without a body.
- Rule 6 has no method restriction, so it covers HEAD.

QA pins this behaviour (§6.3). No explicit `@RequestMapping(method = HEAD)` is added.

#### 1.6 Caching headers (B3)

Spring Security's `CacheControlHeadersWriter`, which is on by default and which we don't disable, writes:

```
Cache-Control: no-cache, no-store, max-age=0, must-revalidate
Pragma: no-cache
Expires: 0
```

It does this on **every** response, 200, 404 and 401 included, unless the application has already set `Cache-Control`, `Expires` or `Pragma`, or the status is 304. `HeaderWriterFilter` writes lazily (on commit or after the chain), so an application-set header would win.

```
Recommendation: set nothing in the controller; rely on the Spring Security default, and have QA pin the exact
                Cache-Control value on the 200 and the 404.
Reason:         the default already includes no-store, which is what a response carrying an owner's originalUrl
                needs; one mechanism for every endpoint; no code. The pin turns an accidental
                headers().cacheControl().disable(), or an app-level header that makes Security back off entirely,
                into a failing test.
Alternative:    ResponseEntity.ok().cacheControl(CacheControl.noStore()) in the handler.
Trade-off:      the explicit form documents intent at the call site, but it replaces Security's full value with
                "no-store" only and suppresses Pragma/Expires for HTTP/1.0 caches, and each later management
                endpoint must remember to repeat it.
```

### 2. Ownership (D4, D13)

#### 2.1 `service/Caller`: the reusable caller value

```java
package com.schwab.urlshortener.service;

/**
 * Who is calling, as the service needs it (D4). Built by the API layer from the Authentication, so the
 * service has no Spring Security dependency. Reused by US-009 (PATCH).
 *
 * @param username the configured lowercase username (D51, D54); never blank
 * @param admin    true only if the caller holds ROLE_ADMIN itself (not through the hierarchy)
 */
public record Caller(String username, boolean admin) {
    /**
     * @throws NullPointerException if username is null
     * @throws IllegalArgumentException if username is blank
     */
    public Caller { ... }
}
```

- It is a record: a value object, and it cannot be logged by accident because it is never logged. It holds a username, which is personal data.
- It deliberately has no ownership method. The rule lives in one place, `ShortUrlService.loadVisible` (§2.3).

#### 2.2 Building it in the controller

```java
// ShortUrlController
static Caller callerOf(Authentication authentication) {
    boolean admin = authentication.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .anyMatch(Role.ADMIN.authority()::equals);
    return new Caller(authentication.getName(), admin);
}
```

- **Detect ADMIN with an exact authority check, never through `hasRole` semantics and never as "is not USER".** In Spring Security 6.5 the `RoleHierarchy` is applied **at authorization-decision time**. `AuthoritiesAuthorizationManager.getGrantedAuthorities` calls `roleHierarchy.getReachableGrantedAuthorities(authentication.getAuthorities())` and doesn't change the `Authentication`. `DaoAuthenticationProvider`'s default `authoritiesMapper` is `NullAuthoritiesMapper`, so `getAuthorities()` is exactly what `UserAccounts` granted: `User.withUsername(...).roles(role)` gives `ROLE_ADMIN` **only** for the admin and `ROLE_USER` only for users (verified in the source, §11).
  - So `getAuthorities()` contains `ROLE_ADMIN` for the admin and never contains the implied `ROLE_USER`.
  - Two mistakes follow from getting this wrong. Checking for `"ADMIN"` without the prefix is always false. Checking "has `ROLE_USER`, therefore not admin" would make the admin a non-admin, *or* would lock the admin out of their own links if the check were written as "must have `ROLE_USER`". See risk K2.
- **`Role.authority()`** is a new method on `security/Role`: `public String authority() { return "ROLE_" + name(); }`. It is the one shared definition of the prefixed name. A unit test ties it to Spring by asserting that `User.withUsername("x").password("p").roles(Role.ADMIN.name()).build().getAuthorities()` contains exactly `Role.ADMIN.authority()`.
- **The username** is `authentication.getName()`, the **configured** lowercase name, whatever case the client typed (D54, as in create). It is the same value that create stored in `created_by`.
- A `HandlerMethodArgumentResolver` for `Caller` was considered and rejected. It would be a new abstraction for two call sites on one controller (GET now, PATCH in US-009). A static method is enough and is directly unit-testable.

#### 2.3 Service rules: `ShortUrlService.get` and `loadVisible`

```java
/**
 * @return the short URL, if the caller may see it
 * @throws ShortUrlNotFoundException if the code is malformed or unknown, the link is DELETED (even for ADMIN,
 *         D13), or the caller is neither its creator nor ADMIN (D4); the four cases are indistinguishable
 */
public ShortUrlView get(String code, Caller caller) {
    return readOnly.execute(status -> ShortUrlView.from(loadVisible(code, caller)));
}

/** D4, D6, D13. Must run inside a transaction; US-009 calls it inside its read-write transaction. */
private ShortUrl loadVisible(String code, Caller caller) {
    if (!ShortCodeFormat.isWellFormed(code)) {                        // D6: cannot exist; no DB call
        log.debug("Short URL not visible: reason=MALFORMED");         // never log a malformed value
        throw new ShortUrlNotFoundException();
    }
    ShortUrl url = repository.findByShortCode(code).orElse(null);    // case-sensitive (D6)
    String reason = url == null ? "NOT_FOUND"
            : url.getStatus() == ShortUrlStatus.DELETED ? "DELETED"      // D13: before the ADMIN check
            : !caller.admin() && !url.getCreatedBy().equals(caller.username()) ? "NOT_OWNER"   // D4
            : null;
    if (reason != null) {
        log.debug("Short URL not visible: code={} reason={}", code, reason);
        throw new ShortUrlNotFoundException();
    }
    return url;                                                       // ACTIVE or DEACTIVATED
}
```

| Case | Caller | Result |
|---|---|---|
| Malformed code | anyone | 404, with **no repository call** |
| No row | anyone | 404 |
| `DELETED` | anyone, **ADMIN included** | 404 (D13, AC5) |
| `ACTIVE` or `DEACTIVATED`, `createdBy` ≠ username | USER | 404 (D4, AC3) |
| `ACTIVE` or `DEACTIVATED`, `createdBy` = username | USER | 200 (AC1) |
| `ACTIVE` or `DEACTIVATED`, any owner | ADMIN | 200 (AC2) |

- **The `DELETED` check runs before the ADMIN shortcut.** Otherwise ADMIN would see deleted links, which breaks D13.
- **The comparison is exact `String.equals`.** Stored and principal names are both the configured lowercase name (D51, D54). No `equalsIgnoreCase` and no `Locale` folding: a row whose `created_by` differs only in case can only come from raw SQL, and it is not the caller's.
- **`DEACTIVATED` is visible to its owner and to ADMIN**, with `status: "DEACTIVATED"`. D2 (404 for deactivated links) is about the public redirect only.
- The `ShortUrlView` is built **inside** the read-only transaction, while the entity is still managed. The entity never leaves the service.
- **Logging:** DEBUG only. A successful read logs nothing at INFO (reads are frequent). The reason is server-side only. It helps support and never reaches the client. **No username is logged** (D52 caution, as in create). A malformed value is never logged: it is arbitrary client text, so logging it risks log injection.

#### 2.4 D6 format check before the lookup (B1, B5)

```java
package com.schwab.urlshortener.shortcode;

/** The D6 code format: [A-Za-z0-9], MIN_LENGTH..MAX_LENGTH characters. Mirrors ck_short_url_code_format. */
public final class ShortCodeFormat {
    public static boolean isWellFormed(String code) { ... }   // null -> false; explicit ASCII loop, no regex
}
```

`AliasPolicy.isValid(alias)` becomes `ShortCodeFormat.isWellFormed(alias) && !reserved(alias)`, with the loop moved rather than copied. The existing `AliasPolicyTest` guards the refactor.

```
Recommendation: validate the D6 format in the service before the repository call; a malformed code gets the same
                404 SHORT_URL_NOT_FOUND, with the same body shape, as an unknown code.
Reason:         such a code can never exist (ck_short_url_code_format), so the database round trip is waste for
                scanners and typos; the query parameter is bounded to 32 Base62 characters whatever the firewall
                or container settings are (for example, if StrictHttpFirewall ever allowed %00, PostgreSQL would reject a NUL in
                text with SQLSTATE 22021 and the client would see a 500 that is distinguishable from 404); and US-008
                AC6 needs exactly the same "malformed means 404" rule on the redirect, so the helper has a second
                user already planned.
Alternative:    just look it up and let the empty result give 404.
Trade-off:      one more place that knows D6, mitigated by one shared helper that AliasPolicy also uses. The
                alternative is simpler by a few lines but depends on the firewall for input hygiene.
```

- **Format only, never `AliasPolicy.isValid`.** D48 lets configuration *add* reserved words at any time. An existing alias that later becomes reserved must stay visible to its owner, so the reserved-word check applies to creation only. A unit test pins it: an existing code `Health` is returned.
- **Not `@Pattern` on the `@PathVariable`.** In Spring 6.2 that raises `HandlerMethodValidationException`, which becomes `400 MALFORMED_REQUEST` (D69), a response distinguishable from 404. See risk K4.
- **Timing.** The malformed path is faster: it makes no query. That reveals nothing, because the D6 format is public (it is in the OpenAPI docs).

#### 2.5 Lookup strategy (B2)

```
Recommendation: one query by code (findByShortCode, the existing unique-index lookup), then the missing / DELETED /
                owner-or-ADMIN checks in Java inside the service (the loadVisible method above).
Reason:         the whole D4/D13 rule is in one method, in one order, unit-testable with a mocked repository;
                ADMIN and USER use the same query (no branching SQL); US-009 needs the loaded entity anyway to call
                deactivate()/reactivate()/softDelete() under @Version, so it reuses loadVisible unchanged; and the
                timing of the three 404 cases is the same query either way.
Alternative:    an owner-filtered derived query, findByShortCodeAndCreatedByAndStatusNot(code, username, DELETED) for
                USER and findByShortCodeAndStatusNot(code, DELETED) for ADMIN.
Trade-off:      load-then-check reads a row the caller may not see (it is discarded inside the service and never
                logged beyond its code). The filtered query never materialises foreign rows, but it splits the rule
                across two queries and the caller, can be proven only against a database, and a later third query
                (US-009, US-011) can forget a predicate.
```

#### 2.6 Enumeration and timing posture

- **Identical responses.** Malformed, unknown, `DELETED` and "not yours" all throw the one `ShortUrlNotFoundException` (no-argument, fixed message, no code, no reason). It goes to one advice handler and gives one `ProblemDetails.of(SHORT_URL_NOT_FOUND, fixed detail, requestUri)`. For the same request path the status, headers (Security's defaults, `Content-Type: application/problem+json`, equal `Content-Length`) and body bytes are identical, `instance` included. QA proves this byte-for-byte (§6.3).
- **What the 404 hides, and what it doesn't.** It hides **ownership and details** (the `originalUrl`, status and click data) of other users' links, and whether a code was deleted. It does **not** hide whether a code exists, and it isn't designed to. Existence is already observable by design: any authenticated user gets `409 ALIAS_ALREADY_EXISTS` for a taken code in any status (D1, US-006 AC3), and anyone gets a 302 for an `ACTIVE` code on the public redirect (US-008). This is recorded so that no reviewer mistakes the 404 for an existence oracle defence.
- **Timing.** Every well-formed code costs exactly one indexed `SELECT`. "Row found but hidden" differs from "no row" only by hydrating one entity in memory, microseconds against a cost-10 BCrypt check (tens of milliseconds) on every request, plus network jitter. The design is **not constant-time**, and it doesn't need to be, given the point above.

### 3. Service and transaction

#### 3.1 Types

| Type | Package | Change |
|---|---|---|
| `Caller` | `service` | New record (§2.1) |
| `ShortUrlNotFoundException` | `service/exception` | New. `RuntimeException`, no-argument constructor, fixed message `"Short URL not found"`. It carries no code (the value may be malformed) and no reason |
| `ShortUrlService` | `service` | Adds `get(String, Caller)`, private `loadVisible`, and a `readOnly` `TransactionTemplate` field |
| `ShortCodeFormat` | `shortcode` | New (§2.4) |
| `AliasPolicy` | `validation` | Delegates the format check to `ShortCodeFormat`. Behaviour is unchanged |
| `Role` | `security` | Adds `authority()` (§2.2) |
| `ShortUrlRepository` | `repository` | **Unchanged.** `findByShortCode` is reused |

#### 3.2 Transaction boundary (B4)

```java
// ShortUrlService constructor, next to requiresNew
this.readOnly = new TransactionTemplate(transactionManager);
this.readOnly.setReadOnly(true);                       // propagation REQUIRED (default)
```

```
Recommendation: get() runs in a read-only TransactionTemplate built in the constructor; no @Transactional anywhere.
Reason:         findByShortCode is a declared query method, and Spring Data JPA applies no transaction to those by default
                (verified), so without this the read would run in auto-commit with a throwaway EntityManager.
                readOnly gives Hibernate FlushMode.MANUAL (no dirty checking) and passes the read-only hint to the
                driver; the entity stays managed while it is mapped to the view. Building the template here matches
                create's pattern and keeps the existing reflection guard
                (shouldNotBeTransactionalAtClassOrMethodLevelSoEveryAttemptOwnsItsTransaction) meaningful: it
                forbids @Transactional on every method and must not be weakened.
Alternative:    @Transactional(readOnly = true) on get(), plus an exemption in the guard test.
Trade-off:      one more constructor line and a mocked PlatformTransactionManager in unit tests (already present).
                The annotation is more idiomatic, but it would put @Transactional back on a class whose create()
                must never have it, and the guard could then no longer tell a legitimate exemption from a mistake.
```

- `open-in-view=false` and a non-transactional controller mean there is no outer transaction, so the read uses exactly one connection.
- **Concurrency:** the read is a single statement under READ COMMITTED, with no locks. A delete or deactivate committed after the `SELECT` isn't reflected. The client gets the state as of the read, which is correct for a GET. No row is ever written, so `version`, `updated_at` and `click_count` stay unchanged (QA pins this).
- **Failures:** a database outage surfaces as a Spring `DataAccessException` or `CannotCreateTransactionException`. It goes to the advice catch-all as `500 INTERNAL_ERROR`, logged **once** at ERROR (the CLAUDE.md 5xx rule; existing behaviour, no new path).

### 4. Errors

#### 4.1 Mapping

`GlobalExceptionHandler` gains one handler and one `DETAIL` entry:

```java
texts.put(ErrorCode.SHORT_URL_NOT_FOUND, "The short URL was not found.");

@ExceptionHandler(ShortUrlNotFoundException.class)
ResponseEntity<ProblemDetail> handleShortUrlNotFound(HttpServletRequest request) {
    return respond(plain(ErrorCode.SHORT_URL_NOT_FOUND, request));   // no log: the service logged at DEBUG
}
```

- The body has **exactly the base keys** (`type` `about:blank`, `title` `Not Found`, `status` 404, `detail`, `instance`, `errorCode`), with no `errors` extension (D56). `Content-Type` is `application/problem+json` (the mapping's producible types are cleared before the advice runs, D70).
- **Not reused:** `ShortUrlDeletedException` (domain). It means "a lifecycle transition was attempted on a deleted link" (D46), and its message contains the code. Using a second type for one of the four cases would invite divergent handling. US-009 will map `ShortUrlDeletedException` to the **same** `plain(SHORT_URL_NOT_FOUND, request)` body (D46). Its design must reuse this handler's body construction.

#### 4.2 `SHORT_URL_NOT_FOUND` compared with `RESOURCE_NOT_FOUND` (D61)

| | `SHORT_URL_NOT_FOUND` | `RESOURCE_NOT_FOUND` |
|---|---|---|
| Raised by | The service, after the handler matched `GET /api/v1/urls/{code}` | Spring MVC: no handler matched the path (`NoResourceFoundException`) |
| Means | "This code is not visible to you" | "No such endpoint" |
| `detail` | "The short URL was not found." | "The requested resource was not found." |

They can't be confused on the wire, because the `errorCode` values differ. **They can be confused in tests**: a test that asserts only `404` passes even if the GET mapping is missing or unreachable (a trailing slash in a test path, for example), because Spring then answers `404 RESOURCE_NOT_FOUND`. **Rule for this story: every 404 assertion also asserts `errorCode`** (risk K5).

#### 4.3 `instance` echoes the path

`instance` is `request.getRequestURI()`, for example `/api/v1/urls/Promo2026`. It is the raw, not-decoded path, without a query string.

This is **acceptable**:
- It echoes only what the caller sent.
- It is identical for all four 404 cases on the same path, so it adds no information.
- A code is Base62, not a secret, and not personal data.

Two cautions:
- **Never** build `instance` from the entity or the decoded variable.
- The existing US-006 note still applies: `ProblemDetails.of` calls `URI.create(requestUri)`, and it relies on Tomcat and the firewall keeping illegal characters out of the path. Characters that survive, such as `%20`, stay percent-encoded in `getRequestURI()`, so `URI.create` accepts them. QA includes `/api/v1/urls/ab%20c` in the malformed set to prove it (expect 404 `SHORT_URL_NOT_FOUND`, not 500).

### 5. OpenAPI

On the `get` method (springdoc 2.8.17; the class already has `@SecurityRequirement(basicAuth)` and `@Tag`):

- `@Operation(summary = "Get a short URL", description = …)`. The description says:
  - it returns the link only to its creator or to an ADMIN;
  - a link that is unknown, deleted or owned by someone else gives the same `404 SHORT_URL_NOT_FOUND`, never 403 (D4, D13);
  - `DEACTIVATED` links are returned, with their status;
  - the code is case-sensitive (D6);
  - **the D71 use**: "After an unexpected `409 ALIAS_ALREADY_EXISTS` on create, call this operation with the alias. It returns 200 only if the alias is the caller's own, which confirms that an earlier create whose 201 was lost did succeed; a 404 means the alias belongs to someone else."
- `@Parameter(name = "code", in = PATH, description = "The short code: Base62, 3 to 32 characters, case-sensitive. A value that can never be a code gets 404.")`. There is no `pattern` in the schema: a pattern would suggest a 400 for mismatches.
- `@ApiResponse` entries:
  - `200`: `ShortUrlResponse`, `mediaType = application/json`
  - `401`: `AUTHENTICATION_REQUIRED`
  - `404`: `SHORT_URL_NOT_FOUND`. Unknown, deleted, or not the caller's; the responses are indistinguishable
  - `406`: `NOT_ACCEPTABLE`, with the same wording as create (only `application/json`; an unparseable `Accept` gets 406 with no body)
  - Every error `@Content` sets **`mediaType = "application/problem+json"`** explicitly with `ErrorResponseSchema`. Otherwise springdoc falls back to the class `produces` and documents errors as `application/json` (D70, US-006).
- **Not documented:** 405, because it concerns other methods on the path, not this operation (OpenAPI documents responses per operation). Also not documented: 403 (only path variants reach `denyAll`), 400 (a GET has no body, and malformed codes get 404), and 500 (consistent with create).
- Hidden: the `Authentication` parameter (`@Parameter(hidden = true)`), so the operation has exactly one parameter, `code`.

### 6. Tests

#### 6.1 AC → component → test → owner

| AC | Component | mid-engineer (`*Test`, Surefire) | qa-tester (Cucumber + `*IT`, Failsafe) |
|---|---|---|---|
| AC1 | Controller, `ShortUrlResponse`, `loadVisible` | Service: the owner gets `ACTIVE` and `DEACTIVATED` views. Web slice: 200 `application/json`, the exact 8-key set, `lastAccessedAt` present and `null`, no `createdBy`/`id`/`updatedAt`/`version`; `shortUrl` is built from `https://short.example` | Scenario plus `GetShortUrlIT`: owner gets 200 for ACTIVE and DEACTIVATED; exact key set; values equal the DB row; **round trip** (§6.3) |
| AC2 | `callerOf`, `loadVisible` | Service: an ADMIN caller gets another user's ACTIVE and DEACTIVATED links. Controller: `callerOf` for an `ROLE_ADMIN` token gives `admin=true` | Scenario plus IT: admin gets 200 for alice's and bob's links |
| AC3 | `loadVisible` | Service: a non-owner USER gets `ShortUrlNotFoundException` for ACTIVE and DEACTIVATED | Scenario plus IT: bob on alice's link gets 404 `SHORT_URL_NOT_FOUND`; **identical bodies** (§6.3) |
| AC4 | Format check, `loadVisible`, advice | Service: unknown code gives the exception; malformed codes give the exception with `verifyNoInteractions(repository)`. Web slice: exception → 404, problem+json, base keys only, `instance` = path | Scenario Outline: unknown and malformed codes (`ab`, `a-b`, `a_b`, 33 characters, `ab%20c`, `abc%C3%A9`) give 404 `SHORT_URL_NOT_FOUND` |
| AC5 | `loadVisible` (DELETED before ADMIN) | Service: DELETED gives the exception for the owner, a non-owner and ADMIN | Scenario Outline over alice, bob and admin on a DELETED link, all 404 `SHORT_URL_NOT_FOUND` |
| AC6 | Rule 6, entry point | Web slice: anonymous gets 401 `AUTHENTICATION_REQUIRED` with `WWW-Authenticate`, and `verifyNoInteractions(service)` | Scenario plus IT: 401 with no credentials, and 401 with wrong credentials too (D30) |

Beyond the ACs: the D70 406, HEAD, case sensitivity, Cache-Control, D71 and OpenAPI (§6.3).

#### 6.2 mid-engineer

- **`ShortUrlServiceTest`** (Mockito repository and `PlatformTransactionManager`, `Clock.fixed`):
  - The §2.3 matrix row by row. The owner is `alice`, the other user `bob`, and the admin `Caller("admin", true)`. Each 404 row asserts the exception type **and** that the repository was called once with the exact code.
  - Malformed codes: `null`, `""`, `ab` (2), 33 characters, `a-b`, `a_b`, `abc ` (trailing space), `abcé`, `ＡＢＣ` (full-width letters, which `Character.isLetterOrDigit` would accept). Each gives the exception and `verifyNoInteractions(repository)`. The positive boundary is 3 and 32 characters, which query the repository.
  - Case: `createdBy = "Alice"` and caller `alice` give the exception (exact `equals`).
  - A reserved-word code that exists (`Health`, owned by alice) is returned to alice (format only, not `AliasPolicy`, per D48).
  - All four 404 causes throw exceptions with equal `getMessage()` and equal type (no distinguishing data).
  - Transaction: a captor on `getTransaction(def)` sees `isReadOnly() == true` and `PROPAGATION_REQUIRED` for `get`. The existing create captor still sees `REQUIRES_NEW` with `readOnly == false`. The existing reflection guard stays as it is.
  - Logging: the DEBUG line for `NOT_OWNER` contains the code and `NOT_OWNER` and **not** the username `bob` (the "not logged" check also asserts the line was captured). The malformed line does not contain the submitted value.
- **`ShortCodeFormatTest`**: boundaries 2/3/32/33, every non-Base62 ASCII class, non-ASCII letters and digits, `null`. `AliasPolicyTest` is unchanged and must still pass.
- **`CallerTest`**: `null` username gives `NullPointerException`, a blank one gives `IllegalArgumentException`, and a valid value round-trips.
- **`RoleTest`** (or an addition to `RoleHierarchyTest`): `Role.ADMIN.authority()` equals the authority that `User.withUsername(...).roles("ADMIN")` produces, and likewise for USER.
- **`ShortUrlControllerWebMvcTest`** (existing slice: real filter chain through `SecuritySliceTestConfiguration`, real advice, `@MockitoBean ShortUrlService`):
  - `GET /api/v1/urls/aB3dE9x` as alice: 200, the exact 8 keys, `lastAccessedAt` null. A captor shows the service received `("aB3dE9x", Caller("alice", false))`.
  - Logging in as `ALICE` (uppercase) gives `Caller("alice", false)` (D54). Admin gives `Caller("admin", true)`.
  - The service throws `ShortUrlNotFoundException`: 404 `SHORT_URL_NOT_FOUND`, `application/problem+json`, exactly the base keys, `instance == "/api/v1/urls/aB3dE9x"`, `detail` is the fixed text.
  - Anonymous: 401, service not called.
  - `Accept: application/xml`, `text/plain` and `application/problem+json` each give 406 `NOT_ACCEPTABLE` with `verifyNoInteractions(service)`. Positive control: `application/json` and `*/*` give 200.
  - `GET /api/v1/urls/aB3dE9x/` gives 404 **`RESOURCE_NOT_FOUND`**, service not called (this pins the §4.2 distinction).
  - `PUT /api/v1/urls/aB3dE9x` gives 405 with `Allow` containing `GET`.
  - The existing `Cache-Control` value is present on the 200 (Security's headers writer runs in the slice).
- **`GlobalExceptionHandlerTest`**: the new handler gives exactly the base keys, and nothing is logged at WARN or ERROR.

#### 6.3 qa-tester

**Seeding (test data rules):**
- `ShortUrlTestData` gains two methods:
  - `seed(code, status, originalUrl, owner)`. For `DELETED`, it sets `deleted_at = now()` and `deleted_by = 'admin'` to satisfy D44.
  - `seedClicks(code, clickCount, lastAccessedAt)`, a raw `UPDATE` of `click_count` and `last_accessed_at`. It is the only way to get non-zero click data before US-010, and it proves that GET returns live DB values.
- The existing `seed(code, status)` keeps `qa-seed` as its owner.
- Owners are `TestUsers.ALICE`, `BOB` and `ADMIN`, never literals.
- ACTIVE links owned by alice, bob and admin are created **through the API** where realism matters (the round-trip and D71 checks). DEACTIVATED and DELETED rows use raw SQL, because no lifecycle API exists until US-009.
- **Truncation**: each IT class calls `ShortUrlTestData.truncate()` in `@BeforeEach` only, and the Cucumber `ShortCodeGeneratorHooks` already truncates before every scenario. No test relies on rows from another class, and execution is serial (CLAUDE.md rules).
- **No context changes**: `GetShortUrlIT extends IntegrationTestBase` and adds nothing context-affecting. Autowire `JdbcTemplate` and `ObjectMapper`, and use `ApiClient.send("GET", "/api/v1/urls/" + code, user, null, null, headers...)`. `ApiClient` may gain a `get(path, user, headers...)` convenience overload.

**`GetShortUrlIT`** (real HTTP, Testcontainers PostgreSQL):
1. **Ownership matrix:**
   - Seed:

     | Row | Owner | Status | Notes |
     |---|---|---|---|
     | `AliceAct1` | alice | ACTIVE | with seeded clicks, for example 5 clicks and a fixed `lastAccessedAt` |
     | `AliceDea1` | alice | DEACTIVATED | |
     | `AliceDel1` | alice | DELETED | |
     | `BobAct1` | bob | ACTIVE | |
     | `AdminAct1` | admin | ACTIVE | |

   - Expected:
     - Owner reads give 200 for ACTIVE and DEACTIVATED, with `status` matching.
     - Admin gets 200 on all ACTIVE and DEACTIVATED rows.
     - Bob gets 404 on alice's rows, and alice gets 404 on bob's and admin's rows.
     - Everyone, admin included, gets 404 on `AliceDel1`.
   - Every 404 asserts `errorCode == SHORT_URL_NOT_FOUND`.
   - The 200 body values equal the DB row: `clickCount` 5, `lastAccessedAt` equal to the seeded instant, and `createdAt` equal to the DB `created_at`.
2. **Identical 404s, byte-for-byte on one path.** Use code `Same1234`:
   - (a) with no row, GET as bob: body B1;
   - (b) seed it as alice ACTIVE, GET as bob: B2;
   - (c) `UPDATE` it to DELETED (with the D44 fields), GET as alice: B3, and as admin: B4.
   - Assert that B1 through B4 are **equal strings**, and that the header maps are equal except for `Date`: the same `Content-Type`, `Content-Length` and `Cache-Control`, and no `Allow` or `WWW-Authenticate`.
   - Malformed codes get the same body except for `instance`, which is compared after asserting it equals the request path.
3. **Case sensitivity:**
   - Seed `Mixed1` as alice and `mixed1` as bob (two distinct rows).
   - Alice: `Mixed1` gives 200 with alice's `originalUrl`; `mixed1` and `MIXED1` give 404 `SHORT_URL_NOT_FOUND`.
   - Bob: `mixed1` gives 200.
4. **Round trip:**
   - Alice POSTs with and without an alias, then GETs the `Location` path. The GET JSON tree **equals** the create JSON tree exactly, all 8 fields (`clickCount` 0, `lastAccessedAt` null, the same `createdAt` string).
   - Admin's GET of the same code equals it too.
5. **D71 check-after-409:**
   - Alice creates alias `Lost201x` (201), and alice creates it again (409 `ALIAS_ALREADY_EXISTS`).
   - Alice GETs `/api/v1/urls/Lost201x`: 200, and `originalUrl` equals the first request's marker URL.
   - Bob tries to create `Lost201x` (409) and then GETs it: 404 `SHORT_URL_NOT_FOUND`, so bob learns the alias isn't his.
6. **D70 406:**
   - On alice's own code, each of `application/xml`, `text/plain` and `application/problem+json` gives 406 `NOT_ACCEPTABLE` with a problem+json body.
   - Positive control: `application/json` gives 200.
   - An unparseable `Accept` (`foo`) gives 406 with an **empty body**.
   - An unknown code with `Accept: application/xml` gives 406, not 404 (precedence, D70).
7. **HEAD:**
   - Alice on her own code: 200, empty body, `Content-Type` `application/json`. If a `Content-Length` header is present, it equals the GET body's byte length. Record the actual behaviour.
   - Bob on alice's code: 404, empty body. Anonymous: 401, empty body, with `WWW-Authenticate`.
8. **No writes:**
   - After a GET and a HEAD by the owner and by admin, the row's `version`, `updated_at`, `click_count` and `last_accessed_at` are unchanged (read with `JdbcTemplate` before and after).
   - The 200 status of each request is the non-vacuity check: the handler really ran.
9. **Cache-Control:** the 200 and the 404 both carry exactly `no-cache, no-store, max-age=0, must-revalidate`, plus `Pragma: no-cache` and `Expires: 0`.
10. **Path variants (§1.3):**
    - The trailing slash gives 404 `RESOURCE_NOT_FOUND`, and `/API/v1/urls/x` gives 403 `ACCESS_DENIED`.
    - `PUT` gives 405, with `Allow` containing `GET`.
11. **Anonymous and bad credentials:** each gives 401 `AUTHENTICATION_REQUIRED` with exactly the base keys (D56).

**Cucumber** (`features/get-short-url.feature`, glue in `cucumber/GetShortUrlSteps`): one scenario per AC (AC1–AC6), plus the round trip, D71, the 406 outline, case sensitivity and HEAD. CLAUDE.md requires a scenario for every API AC. The steps reuse `ApiClient` and `ShortUrlTestData`. Suggested steps: `Given alice owns a short URL "X" with status "DEACTIVATED"`, `When bob requests the details of "X"`, `Then the response is 404 with errorCode "SHORT_URL_NOT_FOUND"`, `And the response body equals the create response`.

**`OpenApiDocsIT`** (extend):
- `paths./api/v1/urls/{code}.get` exists.
- Its `parameters` are exactly one path parameter, `code`.
- The `200` content is `application/json` only and resolves to the same 8 properties as the create `201`.
- `401`, `404` and `406` each have `application/problem+json` only, with the `Problem` schema.
- `security` contains `basicAuth`.
- The description contains `ALIAS_ALREADY_EXISTS` (D71).

**Existing tests to recheck:** `SecurityIT.shouldNotReturn403WhenAdminDeletes` now gets 405 (still inside its `isIn(404, 405)`). Leave it for US-009 to tighten.

### 7. Implementation plan

**mid-engineer**
1. `shortcode/ShortCodeFormat`. `AliasPolicy` delegates its format check to it, with no behaviour change.
2. `security/Role.authority()`.
3. `service/Caller`, `service/exception/ShortUrlNotFoundException`. `ShortUrlService` gets the `readOnly` template, `get` and the private `loadVisible` (§2.3, §3.2). Update the class Javadoc: it now also reads, and `@Transactional` is still forbidden.
4. `api/ShortUrlController`: `@GetMapping("/{code}")`, `callerOf`, the OpenAPI annotations (§5), and the class Javadoc naming rule 6 for GET.
5. `api/error/GlobalExceptionHandler`: the `DETAIL` entry and the `ShortUrlNotFoundException` handler.
6. The tests in §6.2.

**qa-tester**
1. `ShortUrlTestData.seed(code, status, originalUrl, owner)` and `seedClicks(...)`. Optionally `ApiClient.get(path, user, headers...)`.
2. `support/GetShortUrlIT`, `features/get-short-url.feature`, `cucumber/GetShortUrlSteps`, and the `OpenApiDocsIT` extension (§6.3).
3. **Carry-over from US-006:**
   - **N1:** move `import java.util.UUID` into order in `SecurityIT`.
   - **N2:** import `java.util.ArrayList` in `OpenApiDocsIT` and `java.util.Spliterators` in `SecurityIT` instead of the inline fully qualified names, and replace the duplicated key-set extraction in `SecurityIT` (for example `shouldReturn403AccessDeniedWhenUserDeletes`) with `ApiClient.keys`.
   - **N3:** the `Host: bad host` positive control in `CreateShortUrlIT` also asserts that the 400 came from Tomcat: no `errorCode` in the body and a `Content-Type` that isn't `application/problem+json`.
   - The comments cite no finding IDs (CLAUDE.md rule). They describe the behaviour instead.

### 8. Other decisions

**The ADMIN flag comes from the controller, not from the service reading Spring Security**
```
Recommendation: the controller maps Authentication to Caller(username, admin); the service takes Caller.
Reason:         the service stays free of Spring Security (as in create), so the ownership matrix is plain unit tests;
                one mapping point for GET and US-009 PATCH.
Alternative:    the service reads SecurityContextHolder, or takes Authentication.
Trade-off:      the controller must build Caller correctly (risk K2, pinned by callerOf tests); the alternative
                couples the service to the security framework and to thread-local state.
```

**One not-found exception for all four causes**
```
Recommendation: a single message-free ShortUrlNotFoundException, one advice handler, one body.
Reason:         identical responses by construction, not by four handlers kept in sync.
Alternative:    distinct exceptions (NotFound, Deleted, NotOwner) mapped to the same code.
Trade-off:      the reason is visible only in the DEBUG log; the alternative makes it easy for a later change to
                give one cause a different detail or status.
```

### 9. Open questions (for the engineer)

- **Q1 (B1):** a malformed `{code}` gets `404 SHORT_URL_NOT_FOUND`, found by a format check without a DB call, rather than a `400`. FR-8 says unknown codes are 404, and D6 fixes the format, but neither says what an *impossible* code on the management API should get. **Recommend 404**: it is indistinguishable from unknown, and it matches US-008 AC6.
- **Q2 (B3):** rely on Spring Security's default `Cache-Control` (which includes `no-store`) and pin it in tests, instead of setting `no-store` explicitly. **Recommend relying on the default.**
- **Q3 (information, confirm):** the 404 hides ownership and details, not existence. Existence is already observable through create's 409 (D1, US-006 AC3) and the public redirect. The requirements don't ask for existence hiding, and adding it would conflict with D1 and AC3. **Recommend accepting this and recording it** alongside D4.
- **Q4 (US-009 scope, non-blocking):** US-009 AC8 needs a `USER` calling DELETE on any code to get 403 before any lookup. Rule 5 already guarantees that. This note only confirms that `loadVisible` is never reached by a USER DELETE, so there is nothing to decide now.

### 10. Risks for the implementer and the reviewer

- **K1 Ownership checked in the controller.** If the service returned `createdBy`, or the entity, and the controller compared owners, the rule would be bypassed by every future caller of the service (US-009, US-011), and the ownership matrix could no longer be unit-tested in one place. `ShortUrlView` has no `createdBy` (D58), which makes this structurally hard. Reviewer: `createdBy` must never be added to `ShortUrlView` or `ShortUrlResponse`, and no ownership logic may appear in `api/`.
- **K2 ADMIN detection through `hasRole` semantics on `getAuthorities()`.** The hierarchy is applied only by the authorization managers, so `getAuthorities()` holds `ROLE_ADMIN` alone for the admin. Mistakes to watch for:
  - checking `"ADMIN"` without the prefix (always false: the admin sees only their own links, and AC2 fails);
  - treating "has `ROLE_USER`" as "is a USER";
  - requiring `ROLE_USER` for any access, which would lock the admin out;
  - using `authorities.contains(new SimpleGrantedAuthority(...))`, which only works by `equals` luck.

  Reviewer: exactly one `Role.ADMIN.authority()` equality check, covered by a `callerOf` unit test and by the AC2 IT.
- **K3 DELETED after the ADMIN shortcut.** If the order is "admin → allow" before the `DELETED` check, ADMIN sees deleted links (D13, AC5). The unit matrix has an admin-on-DELETED row, and the IT has one too.
- **K4 `@Pattern` on the path variable, or `AliasPolicy.isValid` as the format check.** The first gives 400 `MALFORMED_REQUEST` (D69), which is distinguishable. The second hides existing aliases that become reserved later (D48). Use `ShortCodeFormat.isWellFormed` in the service.
- **K5 Vacuous 404 tests.** An unmapped path also gives 404, but with `RESOURCE_NOT_FOUND`. Every 404 assertion in this story also asserts `errorCode`, and the web slice pins the trailing-slash `RESOURCE_NOT_FOUND` as a control.
- **K6 Case folding creeping in.** Watch for `findByShortCodeIgnoreCase`, `lower(short_code)`, `code.toLowerCase(...)`, or `equalsIgnoreCase` on the owner. The `Mixed1`/`mixed1` IT and the `Alice`/`alice` unit test catch these.
- **K7 `@Transactional` on `get`.** It breaks the existing reflection guard, which must not be weakened, and it blurs create's "never transactional" rule. Use the `readOnly` template.
- **K8 Logging.** Never log `Caller` (username), the view or the response (`originalUrl`), or a malformed code (arbitrary client text). Log only the well-formed code and the reason, at DEBUG.
- **K9 `instance` built from the wrong source.** It must stay `request.getRequestURI()`. Building it from the decoded variable or the entity would make bodies differ between causes or leak data.
- **K10 Shared test database.** Seeded codes must be unique within a class, truncation happens in `@BeforeEach` only, and nothing is added to `IntegrationTestBase` subclasses that changes the context.
- **K11 HEAD `Content-Length`.** Its presence depends on the servlet container's HEAD wrapper. QA records the actual behaviour instead of assuming it, so a Tomcat upgrade that changes it shows up as a deliberate diff, not a flaky test.

### 11. Sources checked (2026-09-29)

- **Spring Security 6.5.x source** (GitHub, branch `6.5.x`):
  - `AuthoritiesAuthorizationManager.getGrantedAuthorities`: `roleHierarchy.getReachableGrantedAuthorities(authentication.getAuthorities())` at decision time; the `Authentication` isn't modified.
  - `AbstractUserDetailsAuthenticationProvider`: `authoritiesMapper = new NullAuthoritiesMapper()` by default, and `createSuccessAuthentication` uses `authoritiesMapper.mapAuthorities(user.getAuthorities())`.
  - `CacheControlHeadersWriter`: writes `Cache-Control: no-cache, no-store, max-age=0, must-revalidate`, `Pragma: no-cache` and `Expires: 0`, unless `Cache-Control`, `Expires` or `Pragma` is already set or the status is 304.
  - `HeaderWriterFilter`: `shouldWriteHeadersEagerly = false` by default, and headers are written on commit or after the chain.
  - `StrictHttpFirewall`: `FORBIDDEN_NULL` (`\0`, `%00`) is blocked in both the encoded and the decoded blocklists; the raw URI must be printable ASCII; `%20` isn't blocked.
- **Spring Security 6.5 reference:** *Authorization Architecture*: the role hierarchy is supported in `authorizeHttpRequests` and in method security; the `ROLE_` prefix is the default for role rules. *Security HTTP Response Headers*: cache-control headers are included by default, and the application can override them per response.
- **Spring Framework 6.2 reference:** *Mapping Requests*, "HTTP HEAD, OPTIONS": `@GetMapping` supports HEAD transparently; a servlet response wrapper sets `Content-Length` without writing the body. Suffix pattern matching is off since 5.3.
- **Spring Data JPA 3.5 reference:** *Transactionality*: inherited CRUD methods are read-only transactional, but "declared query methods (including default methods) do not get any transaction configuration applied by default". `readOnly` gives Hibernate `FlushMode.MANUAL` and a driver hint.
- **PostgreSQL 18 docs:** *Collation Support*: a deterministic collation treats strings as equal only if their bytes are identical, and all standard and predefined collations are deterministic.
- **Code checked at `a34d010`:** `ShortUrlController`, `ShortUrlService` (including the reflection guard in `ShortUrlServiceTest`), `ShortUrlView`, `ShortUrlResponse`, `ShortUrlLinks`, `GlobalExceptionHandler`, `ErrorCode`, `ProblemDetails`, `SecurityConfig`, `Role`, `UserAccounts` (`.roles(...)`), `ShortUrl`, `ShortUrlRepository`, `AliasPolicy`, `IntegrationTestBase`, `ApiClient`, `ShortUrlTestData`, `TestUsers`, `ShortCodeGeneratorHooks`, and `SecurityIT`.

## Implementation notes
*(mid-engineer, 2026-09-29)*

**Files changed (main):** `shortcode/ShortCodeFormat` (new), `validation/AliasPolicy` (delegates format, behaviour unchanged), `security/Role` (`authority()`), `service/Caller` (new), `service/exception/ShortUrlNotFoundException` (new), `service/ShortUrlService` (`readOnly` template, `get`, private `loadVisible`, Javadoc), `api/ShortUrlController` (`GET /{code}`, `callerOf`, OpenAPI incl. D71 text, Javadoc), `api/error/GlobalExceptionHandler` (`DETAIL` entry and handler).

**Files changed (tests, `*Test`):** `shortcode/ShortCodeFormatTest` (new), `service/CallerTest` (new), `security/RoleTest` (new), `service/ShortUrlServiceTest` (get matrix, check order, D72, transactions, logging; existing reflection guard untouched), `api/ShortUrlControllerWebMvcTest` (GET slice, `callerOf`), `api/error/GlobalExceptionHandlerTest` (new handler). `AliasPolicyTest` unchanged and passing.

**Decisions:** none beyond the approved design. `ShortCodeFormat` reuses the `MIN_LENGTH`/`MAX_LENGTH` constants of `SecureRandomShortCodeGenerator`, as `AliasPolicy` did. No new dependencies, no migration, `SecurityConfig` untouched. QA-owned work (seeding helpers, `GetShortUrlIT`, Cucumber, `OpenApiDocsIT` extension, N1-N3) not touched.

**For human review:** `ShortUrlService.loadVisible` check order and the exact-`equals` owner comparison; `ShortUrlController.callerOf` (exact `ROLE_ADMIN` authority check); the read-only `TransactionTemplate` (the reflection guard still forbids `@Transactional`).

**Command and result:** `./mvnw -q verify` exit 0. Surefire 605 tests, 0 failures, 0 errors, 0 skipped. Failsafe 216 tests, 0 failures, 0 errors, 0 skipped (existing QA tests only; no US-007 QA tests exist yet). Merged JaCoCo LINE coverage 99.5%.

## QA notes
*(qa-tester, 2026-09-29)*

**Written from the ACs and the design test plan before the implementation was read.**

**Files (all under `src/test/**`):**
- `resources/features/get-short-url.feature` (new, 36 scenarios once outlines are expanded) and `cucumber/GetShortUrlSteps` (new).
- `support/GetShortUrlIT` (new, 53 test cases).
- `support/ShortUrlTestData`: `seed(code, status, originalUrl, owner)` (DELETED rows carry `deleted_at` and `deleted_by = admin`, D44), `seedClicks`, `markDeleted`, `rowState`, `createdAt`. The old `seed(code, status[, url])` still uses `qa-seed` as owner; the only change for it is that a seeded DELETED row now has `deleted_by = admin` instead of `qa-seed` (no test asserts it).
- `support/OpenApiDocsIT`: five new tests for the GET operation.
- Carry-over: N1 and N2 in `SecurityIT` (imports in order, `Spliterators` inline name gone, `ApiClient.keys` reused) and `OpenApiDocsIT` (`ArrayList` imported); N3 in `CreateShortUrlIT` (the `Host: bad host` 400 is asserted to have no `errorCode` and a non-problem+json content type).

**AC to test map**

| AC | Cucumber (`get-short-url.feature`) | `GetShortUrlIT` |
|---|---|---|
| AC1 | owner ACTIVE, owner DEACTIVATED, live click data, round trip (generated code and alias) | ownership matrix (owner rows), `shouldReturnValuesEqualToTheDatabaseRow`, `shouldReturnLastAccessedAtAsExplicitNullBeforeTheFirstClick`, both round-trip tests |
| AC2 | outline: admin on alice, bob and admin rows, ACTIVE and DEACTIVATED | matrix (admin rows) |
| AC3 | outline (ACTIVE, DEACTIVATED) and alice on bob's link, both with `SHORT_URL_NOT_FOUND` and no data leaked | matrix (alice/bob cross rows), byte-identical 404 test |
| AC4 | unknown code, 6 malformed codes, unknown vs foreign identical | byte-identical 404 test, malformed-code test (8 values incl. `abc.json`, `%41%42`), reserved-word-that-exists test |
| AC5 | outline alice, bob, admin on a DELETED link | matrix (`AliceDel1` for all three callers), byte-identical test (deleted for owner and admin) |
| AC6 | anonymous, wrong password | `shouldReturn401WithBaseProblemKeysForAnonymousAndForBadCredentials` |
| D4/D6 | case sensitivity | `Mixed1`/`mixed1` test |
| D58 | round trip | both round-trip tests |
| D70 | outline of 3 Accept values, 200 control | 406 parameterised (row state and row count unchanged), 200 controls, unparseable `Accept` gives empty 406, unknown code with xml gives 406 |
| D71 | 2 scenarios | check-after-409 test (owner 200, other user 409 then 404) |
| D72/D74 | malformed and foreign look the same | byte-identical (body and headers except `Date`), malformed body equal except `instance` |
| D73 | no-cache scenario | exact `Cache-Control`, `Pragma`, `Expires` on 200 and 404 |
| HEAD | scenario | HEAD owner 200 empty body; foreign 404, deleted-for-admin 404, anonymous 401 with challenge, all empty |
| No writes | scenario | GET and HEAD by owner and admin leave `version`, `updated_at`, `click_count`, `last_accessed_at` unchanged; positive control: every request is 200 and a seeded change is visible on the next read |
| Path variants | | trailing slash and extra segment give `RESOURCE_NOT_FOUND`; `/API/...` gives 403 `ACCESS_DENIED`; PUT, POST and PATCH give 405 with `Allow` containing GET and change nothing; firewall paths give 400 |
| OpenAPI | | GET exists; one `code` path parameter; 200 is `application/json` only with the create 201's 8 properties; 401, 404 and 406 are `application/problem+json` only with `Problem`; `basicAuth`; description has `ALIAS_ALREADY_EXISTS` and `SHORT_URL_NOT_FOUND` |

**Recorded behaviour:** `HEAD /api/v1/urls/{code}` returns 200 with `Content-Type: application/json`, no body and **no `Content-Length` header** today. The pin in `shouldAnswerHeadLikeGetWithoutABody` is now "if present, equals GET length"; the status, content type and empty body are still asserted.

**Results:** `./mvnw -q verify` exit 0. Surefire 605 run, 0 failures, 0 errors, 0 skipped. Failsafe 310 run, 0 failures, 0 errors, 0 skipped (was 216): Cucumber 95 scenarios (36 new), `GetShortUrlIT` 53, `OpenApiDocsIT` 14 (5 new).

**Defects:** none. **Ambiguous or untestable ACs:** none.

## Review log
*(senior-engineer findings per round, and how each was resolved)*

| Round | ID | Severity | Finding | Resolution |
|---|---|---|---|---|
| 1 | R3 | SHOULD | Owner step wrote the Gherkin word straight into `created_by`; a typo would seed an unowned row and the 404 scenarios would pass for the wrong reason | Fixed (QA): added `TestUsers.require` (throws on unknown names); the owner step and the wrong-password step resolve through it. Other steps already fail via `ApiClient.passwordOf`. |
| 1 | R6 | NIT | HEAD 404s cannot assert `errorCode` | Fixed (QA): comment added in `GetShortUrlIT` and the feature. The foreign-link case already had a same-path 200 (`adminOk`); the deleted case now has one (`HeadDel1` owner HEAD 200, then deleted, then admin HEAD 404). The HEAD scenario already had one. |
| 1 | R7 | NIT | Non-vacuity check only saw the last response | Fixed (QA): every GET/HEAD status since the state was remembered is recorded, and the unchanged step asserts all are 200. |
| 1 | R9 | NIT | Needless `TreeSet` wrapping | Fixed (QA): removed in `SecurityIT` (two places) and `GetShortUrlIT` and `GetShortUrlSteps`; `ApiClient.keys` is compared to the sets directly. |
| 1 | R10 | NIT | Long lines in `GetShortUrlIT` | Fixed (QA): wrapped; no line over 120 characters. |
| 1 | R8 | NIT | Fully qualified `eq`, `startsWith`, `containsString` inline in `ShortUrlControllerWebMvcTest` | Fixed (mid-engineer): replaced with static imports. |
| 1 | R10 (mid-engineer part) | NIT | `ShortUrlServiceTest` class Javadoc (line 55, 150 chars) over width | Fixed (mid-engineer): wrapped; also wrapped the over-120 line I added in the `ShortUrlController` class Javadoc. The 123-char line at `ShortUrlControllerWebMvcTest` line 450 predates this story and was left alone. |
| 1 | R4 | NIT | Move length constants into `ShortCodeFormat` | Awaiting engineer decision (conflicts with the US-004 G3 decision that the constants stay on the generator). |
| 1 | R5 | - | The HEAD test pins "no `Content-Length`", which is a Tomcat detail no requirement depends on. The alternative is "if present, equals the GET length". | Fixed (round 2, engineer-approved): the HEAD test now pins "if a `Content-Length` is present, it equals the GET body byte length". |
| 2 | R1–R3, R6–R10 | — | Re-review of fix round 1 | **Resolved**. Verdict **APPROVE**. Confirmed that no Gherkin word can reach `created_by`/`deleted_by` unresolved |
| 2 | R4 | NIT | The length constants live on the generator, not `ShortCodeFormat` | **Open (engineer)**. The reviewer considers leaving it as is acceptable, and says it is worth revisiting only if a second generator appears |
| 2 | R5 | NIT | HEAD pin strictness | **Fixed** (round 2): relaxed to "if present, equals GET length" |
| 2 | R11 | NIT | Two lines over 120 characters in `GetShortUrlSteps` | Open |
| 2 | R12 | NIT | Unsorted `java.util` imports in `GetShortUrlSteps` | Open |
| 2 | R13 | NIT | `ApiClient.passwordOf` lowercases while `TestUsers.require` does not (two lookups with different case rules) | Open |

**Orchestrator verification (2026-09-29):**
- `./mvnw -q clean verify` passed (exit 0).
  - Surefire: 605 run, 0 failed, 0 errors, 0 skipped.
  - Failsafe: 310 run, 0 failed, 0 errors, 0 skipped (includes 95 Cucumber scenarios).
  - Merged LINE coverage: 414/416 (99.5%), with fresh exec files.
- The build log has 0 "Failing row contains" lines.
- There is no `@Transactional` annotation in `src/main` and no `Thread.sleep` in `src/test`.

### Proposed review rules (senior-engineer, US-007; for the engineer to decide)
Round 1:
1. "Test code must not use inline fully qualified class names (for example `org.mockito.ArgumentMatchers.eq` or `java.util.ArrayList`); use imports or static imports."
2. "Cucumber steps that turn a Gherkin word into a test user or owner must resolve it through `TestUsers` and fail on unknown names. They must never write the raw word into the database."
3. "A HEAD test that expects a 404 has no body to assert `errorCode` on, so it must include a 200 on the same path in the same test as proof that the mapping was reached."

Round 2:

4. "Test-support lookups that turn a Gherkin or test-data word into a user, owner or other database value must throw on unknown values (no default branch), and there must be one lookup per concept with a single case rule."

**Fix round 2 (engineer-approved, R5 only):** the qa-tester relaxed the HEAD pin to "if `Content-Length` is present, it equals the GET body's byte length". A comment records today's behaviour (no `Content-Length` on embedded Tomcat, Boot 3.5.16).

**Orchestrator final verification (2026-09-29):** `./mvnw -q clean verify` passed (exit 0). Surefire: 605 run, 0 failed. Failsafe: 310 run, 0 failed (includes 95 Cucumber scenarios). Merged LINE coverage: 414/416. The build log has 0 "Failing row contains" lines. G3 was approved by the engineer. Status: **Done**.

## Post-completion change (US-010 carry-over N1; mid-engineer, fix round 1)
- `service/ShortUrlServiceTest`: the `stored` fixture now creates the entity at `EARLIER` instead of `NOW_MICROS`, so "updatedAt unchanged" assertions on ACTIVE rows can fail. The only US-007 test that reads the creation time is `shouldReturnTheViewToTheOwnerForActiveAndDeactivatedLinks`, whose `createdAt` assertion changes from `NOW_MICROS` to `EARLIER`. The other `stored` users (`shouldReturnAnotherUsersLinkToAnAdminForActiveAndDeactivatedLinks`, `shouldThrowNotFoundForANonOwnerUserOnActiveAndDeactivatedLinks`) assert nothing time-dependent and are unchanged. Status stays **Done**.

## Post-completion change (US-011; mid-engineer)
- `ShortUrlService` gains a `ClickEventRepository` constructor argument (second position), for the stats endpoint. Status stays **Done**.
- **`service/ShortUrlServiceTest`** (covers US-006, US-007 and US-009 behaviour):
  - **Updated:** the three `new ShortUrlService(...)` call sites (`serviceWithMaxAttempts`, `shouldTakeTheTimestampFromTheClockExactlyOncePerRequest`, `shouldTakeTheTimestampFromTheClockExactlyOncePerDeleteRequest`) pass a new `ClickEventRepository` mock (field `clickEvents`) as the second argument. The two clock-count call sites are re-wrapped to stay within 120 characters. No assertion changed.
  - **Added (US-011, not a change to an existing test):** the stats tests in a new section "US-011: stats". `shouldRunGetInAReadOnlyRequiredTransactionAndCreateInARequiresNewWriteTransaction` and every other existing test are untouched.
- **QA-owned test edits made in US-011 (qa-tester)**
  - **`support/GetShortUrlIT`:** helper `stableHeaders` renamed `headersExceptDate` (its Javadoc now says Content-Length is kept, unlike `ApiClient.stableHeaders`); the call sites follow in `shouldReturnByteIdentical404ForAbsentForeignAndDeletedCodeOnTheSamePath` and `shouldReturnTheSame404BodyExceptInstanceForMalformedCodes` (two tests, four calls). Renamed helper only, no assertion changed.
  - **`support/OpenApiDocsIT`:** added a `STATS_PATH` constant, helpers `stats` and `statsParameter`, and six US-011 tests (`shouldDocumentOnlyGetOnTheStatsPathWithExactlyTheFourDeclaredParameters`, `shouldDocumentTheTimezoneRuleTheSignWarningAndThePlusEncoding`, `shouldDocumentFromAndToAsOptionalDatesWithTheirDefaultsAndLimits`, `shouldDocumentTheStatsResponsesAsExactlyTheFiveExpectedStatuses`, `shouldDocumentTheStats200AsJsonOnlyWithTheD101FieldsAndTheDailyEntryShape`, `shouldApplyBasicAuthenticationAndDescribeLocalDaysAndTheUtcLastAccess`). Every existing test is unchanged.
