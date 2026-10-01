---
id: US-008
title: Redirect endpoint
status: Done
plan_task: 7
depends_on: [US-002, US-005, US-006]
requirements: [FR-2, FR-8, D2, D7, D18, D31, D32, D75, D76, D77, D78, D79, D80, D81, D82, D83, D84, D85]
requires_design_approval: true
---

# US-008: Redirect endpoint

## User story
As any visitor, I want to follow a short link, so that I am redirected to the original URL.

## Acceptance criteria
- **AC1:** Given `{code}` belongs to an `ACTIVE` link, when `GET /{code}` is called, then the response is `302 Found` with `Location` set to exactly the stored `original_url`, except that characters outside printable ASCII are percent-encoded as UTF-8 (D75), and `Cache-Control` is exactly `no-store`, with no `Pragma` or `Expires` header (D76).
- **AC2:** Given `{code}` does not exist, when `GET /{code}` is called, then the response is `404 Not Found` with `errorCode: "SHORT_URL_NOT_FOUND"` (D31).
- **AC3:** Given `{code}` belongs to a `DEACTIVATED` link, when `GET /{code}` is called, then the response is `404 Not Found` with the same `errorCode` as AC2 — indistinguishable from unknown, per D2.
- **AC4:** Given `{code}` belongs to a `DELETED` link, when `GET /{code}` is called, then the response is `404 Not Found` with the same `errorCode` as AC2.
- **AC5:** Given requests to `/api/**`, `/actuator/**`, `/v3/api-docs/**`, and Swagger UI paths, when they are made, then they are routed to their own handlers and are never matched by the `/{code}` redirect mapping — verified with a case where a reserved prefix segment (e.g. `/api`) would otherwise look like a valid short code. An authenticated `GET /api` reaches the redirect mapping and gets `404 SHORT_URL_NOT_FOUND`, never a 302, because `api` is a built-in reserved word (D78). An anonymous `/api` gets 401 through the security rules.
- **AC6:** Given `{code}` does not match the short-code shape `^[A-Za-z0-9]{3,32}$`, when `GET /{code}` is called, then the response is `404 Not Found` without a database round trip: the D6 format is checked with `ShortCodeFormat` before any lookup (D72, D77), and the 404 is identical to AC2's `SHORT_URL_NOT_FOUND` (D74).
- **AC7:** Given `{code}` belongs to an `ACTIVE` link, when `HEAD /{code}` is called instead of `GET`, then the response is `302 Found` with the same `Location` and `Cache-Control: no-store` headers as AC1 (D18, D32 — public, same redirect semantics as GET). Whether the click is counted is out of scope here and is proved by US-010.
- **AC8:** Given `{code}` belongs to an `ACTIVE` link, when `GET /{code}?x=1` is called, then the response is `302 Found` with `Location` equal to exactly the stored `original_url`, and `x=1` is not appended or merged (D79).

## Tests required
| Type | Behaviour proven | Owner |
|---|---|---|
| Cucumber | `GET /{code}` on an active link returns 302 with correct `Location`/`Cache-Control` (AC1) | qa-tester |
| Cucumber | `GET /{code}` on unknown, deactivated, and deleted codes all return 404 `SHORT_URL_NOT_FOUND`, indistinguishable (AC2–AC4) | qa-tester |
| Cucumber | `HEAD /{code}` on an active link returns 302 with the same `Location`/`Cache-Control` as `GET` (AC7) | qa-tester |
| Integration (`*IT`) | 302 with correct `Location` and `Cache-Control` for an active link (AC1) | qa-tester |
| Integration (`*IT`) | 404 for unknown, deactivated, and deleted codes, all indistinguishable (AC2–AC4) | qa-tester |
| Web slice/Integration (`*IT`) | Routing precedence: `/api`, `/actuator/health`, OpenAPI paths are not captured by the redirect mapping (AC5) | mid-engineer / qa-tester |
| Unit/Web slice | Path-pattern rejection for malformed codes returns 404 without a repository call (AC6) | mid-engineer |
| Cucumber | `GET /{code}?x=1` on an active link redirects to exactly the stored URL, with `x=1` not appended (AC8) | qa-tester |
| Integration (`*IT`) | `GET /{code}?x=1` gives a `Location` equal to the stored URL, with no `x=1` (AC8) | qa-tester |

## Out of scope
- Click counting/analytics — added to this endpoint's flow by US-010, not by this story.

## Risks
- If the `/{code}` mapping is registered with too broad a pattern or too high a priority relative to `/api/**`, it can accidentally shadow the management API. This must be verified with an explicit routing-precedence test, not just manual spot-checking.

## Open questions
- None. D18/D32 fix `HEAD /{code}`'s status (302, same as GET) and public access; whether it is counted is proved by US-010, not this story.

## Design inputs carried from US-006 (engineer-approved at the US-006 escalation)
- The redirect controller must **never** declare `produces` (D70). It must answer any `Accept` (browsers, `<img>`, `text/html`). Only the management API (`ShortUrlController`) declares `produces = application/json`.
- The redirect must not share a base class or meta-annotation that carries `produces`.

## Design inputs carried from US-007 (engineer-approved at US-007 G2)
- A malformed `{code}` (fails D6) returns the same 404 as an unknown code, checked before any DB call (D72). Reuse the `ShortCodeFormat` helper from US-007, format only, never the reserved-word check (D48).

## Carry-over from US-007 (engineer-approved at US-007 G3)
- **R11 (NIT, qa-tester):** wrap the two lines over 120 characters in `GetShortUrlSteps` (around lines 170 and 220).
- **R12 (NIT, qa-tester):** sort the `java.util` imports in `GetShortUrlSteps`.
- **R13 (NIT, qa-tester):** `ApiClient.passwordOf` lowercases the name but `TestUsers.require` does not. Make `passwordOf` call `TestUsers.require` and drop the `toLowerCase`, so there is one strict lookup per concept (CLAUDE.md rule).
- **Design input, raw paths in logs:** for anonymous requests, `ProblemDetailAuthenticationEntryPoint` logs `Authentication failed: <method> <raw path>` at INFO, and the catch-all logs the path at ERROR. The redirect is anonymous traffic, so a malformed or arbitrary path appears as client text in logs. The firewall blocks CR/LF and the path stays percent-encoded. The US-008 design must decide whether redirect-path logging needs further limits (for example no path for public routes, or path length caps).

## Design note

*Architect, 2026-09-29. Status: **approved at G2 (2026-09-29)**. C1–C8 and Q2–Q7 are recorded as D75–D83, and the rest (including the C5 click seam) is approved as written. Gate IDs C1–C8 and question IDs Q1–Q7 are for this note only. Once the engineer decides, the orchestrator records the outcomes as D75 onward. Code, tests and SQL must cite those `Dnn` IDs, never `C1`, `Q1` or section numbers (CLAUDE.md review rule). **No migration**: V1 already has everything the redirect reads. **`SecurityConfig` is unchanged.***

### 0. Engineer decisions required at G2

| # | Decision | Recommendation | Blocking? |
|---|---|---|---|
| **C1** | A stored `originalUrl` can contain non-ASCII characters in its path, query or fragment. `java.net.URI` accepts them (D11), and D49 rejects them only in the host. Tomcat 10.1.55 cannot send them in a header: characters U+0080–U+00FF go out as single Latin-1 bytes, and anything above U+00FF makes Tomcat **drop the `Location` header and log its full value at WARN** (§1.4). | **At redirect time, percent-encode every code point outside printable ASCII (`0x21`–`0x7E`) as its UTF-8 bytes (`%XX`, upper-case hex)**, following the RFC 3987 §3.1 IRI-to-URI mapping. Everything else is copied byte for byte, so a URL that is all ASCII (the usual case) is sent exactly as stored. There is no NFC normalisation and no other change. | **Yes.** AC1 says "exactly". It needs a planner edit: "exactly, except that non-ASCII characters are percent-encoded as UTF-8". |
| **C2** | `Cache-Control` on the 302 (D7, AC1, US-005 risk K9). | **The controller sets exactly `no-store`.** Spring Security's cache writer then writes nothing, so the 302 has **exactly** `Cache-Control: no-store` and **no** `Pragma` or `Expires`. Tests check for an exact match, not "contains". The redirect's 404 keeps Security's default. | **Yes.** It decides the test pins. |
| **C3** | AC6 says "defensive path-pattern constraint at the routing layer", but D72 puts the check in the service. | **Check in the service, with `ShortCodeFormat`, before the transaction opens. No regex in the mapping.** A regex mapping would make a malformed code get `404 RESOURCE_NOT_FOUND` while an unknown code gets `SHORT_URL_NOT_FOUND`, which breaks D72 and D74. | **Yes.** It needs a planner edit to the wording of AC6 (§2.2). |
| **C4** | Reuse `ShortUrlService` or add a service. | **A new `service/RedirectService`** (§2.3). | No |
| **C5** | The US-010 click-recording seam. | **No `ClickRecorder` and no no-op bean in US-008.** Define the seam (one handler, one response builder, the read transaction closed before the handler returns) and let US-010 add the method check and the recorder (§4). | No, but US-010 inherits it. |
| **C6** | Raw request paths in logs on public routes. | The redirect logs at **DEBUG only**: the well-formed code and a reason, never the target URL or its host, and never a malformed value. **The entry-point and catch-all log lines are unchanged** (§5). | No |
| **C7** | OpenAPI. | **Document `GET /{code}`**: 302 with `Location` and `Cache-Control` headers, and 404 as problem+json. No security requirement (§6). | No |
| **C8** | For an **authenticated** caller, bare `GET /api` reaches the `/{code}` mapping, because no `/api` handler exists. | **Accept it.** The request gets `404 SHORT_URL_NOT_FOUND` after one lookup, and it can never be a 302, because `api` is a built-in reserved word (D29, D48). Anonymous callers get 401 from rule 6 before any handler runs. AC5's wording needs a planner edit (§1.3). | **Yes.** AC5 wording. |

### 1. API contract

#### 1.1 Endpoint

New `api/RedirectController`. It is its own class: no class-level `@RequestMapping`, no base class, and no composed annotation.

```java
/**
 * The public redirect (FR-2, D2, D7, D18, D32, D72). Admitted by filter-chain rule 7 (GET and HEAD on any
 * single segment, D32). Never declares produces and never inherits it (D70), so any Accept gets the 302.
 * HEAD is served by this GET mapping (Spring MVC) and is never counted as a click (D9, D18).
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "Redirect")
class RedirectController {

    private final RedirectService service;

    @GetMapping("/{code}")
    // @Operation / @ApiResponse / @Parameter: section 6
    ResponseEntity<Void> redirect(@PathVariable("code") String code) {
        return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, locationHeaderValue(service.resolve(code)))
                .cacheControl(CacheControl.noStore())                    // D7: exactly "no-store"
                .build();
    }

    /** C1 rule, see 1.4. Package-private for unit tests. */
    static String locationHeaderValue(String target) { ... }
}
```

- **`produces` is forbidden** on this class and method, and on anything they could inherit (D70). Without it, `ProducesRequestCondition` is empty and never parses `Accept`. So any `Accept` value, including an unparseable one, gets the 302 (§3).
- **`@PathVariable("code")`** names the variable explicitly, as in US-007.
- **The return type is `ResponseEntity<Void>`**, built with `.header(LOCATION, String)`. `HttpHeaders.add` and `set` store the string unchanged (verified). `ServletServerHttpResponse` copies it into `HttpServletResponse.addHeader`. Tomcat's `addHeader` does nothing special for `Location`: only `sendRedirect` makes URLs absolute (US-006 §1.4).
- **Never use** any of these:
  - `ResponseEntity.location(URI)` or `HttpHeaders.setLocation(URI)`. They write `URI.toASCIIString()` (verified), so the text is re-derived from a parsed `URI`.
  - `"redirect:" + url` or `RedirectView`. These expand `{…}` as URI template variables and use `sendRedirect`.
  - `HttpServletResponse.sendRedirect`.
  - A `RedirectAttributes` parameter. With one, `HttpEntityMethodProcessor.saveFlashAttributes` puts the 3xx `Location` through the flash-map machinery (verified).
  - `HttpHeaders.getLocation()` on the test side, because it calls `URI.create`.

#### 1.2 Filter-chain rule that admits it (CLAUDE.md rule)

**Rule 7** of the architecture access table admits the endpoint: `.requestMatchers(HttpMethod.GET, "/*").permitAll()` and `.requestMatchers(HttpMethod.HEAD, "/*").permitAll()` (D32). It is deliberately not narrowed to the code regex (US-005 §3.2), so malformed codes get 404, not 401. `SecurityConfig` does not change.

| Request | Result | Why |
|---|---|---|
| `GET`/`HEAD /{code}`, no credentials | 302 or 404 | Rule 7 |
| `GET /{code}` with **valid** Basic credentials (any user) | 302 or 404, the same as anonymous | Rule 7 ignores identity. The redirect never uses the principal |
| `GET /{code}` with **invalid** Basic credentials | **401 `AUTHENTICATION_REQUIRED`** with `WWW-Authenticate: Basic`. The service is never called | **D55.** `BasicAuthenticationFilter` fails the request before authorization. Browsers don't send Basic credentials to `/{code}` unless a user types them into the URL, so this is harmless |
| `POST`/`PUT`/`DELETE`/`PATCH`/`OPTIONS /{code}` | 401 anonymous, 403 authenticated (existing pins) | Rule 8 `denyAll` (D57). The redirect never answers 405, because security refuses first |
| `GET /{code}/` (trailing slash) | 401 with a Basic challenge anonymous, 403 authenticated | Neither the security matcher nor Spring MVC 6 matches an optional trailing slash, so `denyAll` applies. See Q3 |

**Reserved prefixes are caught earlier.** Rules are evaluated first match wins. Rule 4 (`/actuator`, `/actuator/**` → authenticated) and rule 6 (`/api`, `/api/**` → `hasRole(USER)`) come before rule 7. So anonymous `GET /api` and `GET /actuator` get 401 and never reach a handler, even if a row with that code existed. The matchers are case-sensitive, so `/API` falls through to rule 7 and is public. It then reaches the redirect, and gets 404 because the reserved words are checked case-insensitively (D29), so no such code can be created.

#### 1.3 Routing precedence (AC5)

**How Spring MVC 6.2.19 chooses a handler (verified in the source):**
- `DispatcherServlet` asks each handler mapping in `order`:
  - Actuator's `WebMvcEndpointHandlerMapping` is `-100` (`setOrder(-100)` in `AbstractWebMvcEndpointHandlerMapping`).
  - `RequestMappingHandlerMapping` is `0`.
  - The static-resource `/**` mapping is last.
- Inside `RequestMappingHandlerMapping`, `lookupHandlerMethod` first collects **direct-path** matches: mappings with no pattern syntax, such as `/error` and `/swagger-ui.html`. It scans pattern mappings such as `/{code}` **only if no direct path matched**.
- Among pattern matches, `PathPattern.SPECIFICITY_COMPARATOR` prefers fewer URI variables and wildcards. Two equally specific matches raise `IllegalStateException("Ambiguous handler methods …")`, which becomes a 500. That is one more reason for the "no other single-segment handler" rule.
- `{code}` captures exactly one **non-empty** segment (`CaptureVariablePathElement`: "There must be at least one character"). So it never matches `/` and never matches more than one segment.

| Request | Rule | Handler that wins | Anonymous | Authenticated (alice) |
|---|---|---|---|---|
| `GET /error` | 7 | Boot's `BasicErrorController` (direct path) | **500** Boot error JSON (`timestamp`, `status`, `error`; no `errorCode`). A direct request has no error attributes, and `AbstractErrorController.getStatus` then returns 500 (verified). `Accept: text/html` gets the whitelabel page, also 500 | same |
| `GET /swagger-ui.html` | 3 | springdoc `SwaggerWelcomeWebMvc` (`@Controller`, `@GetMapping` literal, direct path) | 302, `Location` `/swagger-ui/index.html` | same |
| `GET /v3/api-docs`, `/v3/api-docs/swagger-config`, `/swagger-ui/index.html` | 3 | springdoc and resources. These have two or more segments, so `/{code}` cannot match them | 200 | 200 |
| `GET /actuator/health` | 2 | Actuator mapping (order −100); two segments | 200 `UP` | 200 |
| `GET /actuator` | 4 | Actuator's discovery ("links") mapping at the base path, order −100 | 401 | **Record it.** It is expected to be 200 `_links` (US-005 §3.3 predicted 404). **Pin: never 302** |
| `GET /api` | 6 | No `/api` handler exists, so the redirect handles it (C8) | 401 | 404 `SHORT_URL_NOT_FOUND`: one lookup, never 302 (reserved word) |
| `GET /api/v1/urls/{code}` | 6 | `ShortUrlController` | 401 | 200 or 404, as in US-007 |
| `GET /favicon.ico` | 7 | The redirect. There are no static resources, and the resource mapping is last | 404 `SHORT_URL_NOT_FOUND` (malformed, no DB call) | same |
| `GET /v3`, `/ab`, `/a_b` | 7 | The redirect | 404 `SHORT_URL_NOT_FOUND` (malformed) | same |
| `GET /` | 7 or 8 | No handler: `{code}` needs at least one character | Record it: expected 404 `RESOURCE_NOT_FOUND` or 401. **Never 302** | Record it |

HEAD gives the same status and headers as GET for every row, with no body. *Erratum (orchestrator, from the QA run):* this holds for single-segment paths only. HEAD on the multi-segment infrastructure paths behaves differently, because rules 2 and 3 permit GET only there: `/v3/api-docs`, `/v3/api-docs/swagger-config` and `/swagger-ui/index.html` get 401 anonymous and 403 authenticated (D57), and `/actuator/health` gets 401 anonymous and 200 authenticated. The actual values are pinned in `RedirectIT.shouldGiveHeadTheRecordedStatusOnEveryRoutingPath`.

**AC5 wording (C8, planner edit).** "Never matched by the `/{code}` mapping" holds for `/api/**`, `/actuator/**`, `/v3/api-docs/**` and the Swagger UI paths. It does **not** hold for bare `/api` from an authenticated caller. For that request the mapping runs, does one lookup, and returns 404. A built-in reserved word can never become a code: `AliasPolicy` rejects it as an alias (D48), and the generator discards it (D29). Proposed wording: "…are routed to their own handlers; a reserved single segment (`/api`, `/actuator`) is never redirected: anonymous callers get 401 from the filter chain, and authenticated callers get either the framework handler or `404 SHORT_URL_NOT_FOUND`." A raw-SQL row named `api` would redirect for authenticated callers only. Closing that gap would need a CHECK constraint on the built-in words, which this note rejects (§9, Q7).

#### 1.4 The 302 response

**`Location` (C1).**

| Stored `original_url` | `Location` sent |
|---|---|
| Printable ASCII only (all characters `0x21`–`0x7E`) | **Byte-for-byte the stored string.** No parsing, no normalisation: case, `%`-escape case, dot segments, an empty path, `?` or `#` with nothing after them, and `+` are all kept |
| Contains non-ASCII (for example `https://example.com/café?q=ü`) | Each such code point becomes the `%XX` form of its UTF-8 bytes: `https://example.com/caf%C3%A9?q=%C3%BC`. ASCII characters, existing escapes included, are copied unchanged. There is **no** NFC normalisation, so a decomposed `é` becomes `e%CC%81` |
| ASCII controls or space (only possible through raw SQL, because `java.net.URI` rejects them) | Percent-encoded the same way, so CR and LF can never reach a header |

Why the C1 rule is needed (Tomcat 10.1.55, which Boot 3.5.16 manages, verified in the source):
- `Http11OutputBuffer.write(MessageBytes)` converts header strings with `MessageBytes.toBytes()`. With the default charset, `toBytesSimple` casts each `char` to a byte, so `é` goes out as the single byte `0xE9`, not UTF-8. For any character above `0xFF` it throws `IllegalArgumentException("messageBytes.illegalCharacter")`.
- `Http11Processor.prepareResponse` catches that exception, **logs `The HTTP response header [Location] with value [<full URL>] has been removed from the response because it is invalid` at WARN**, removes the header and sends the 302 anyway.
- The result is a broken redirect *and* a full URL in the logs, which breaks CLAUDE.md.
- Tomcat replaces bytes `0x00`–`0x1F` (except TAB) and `0x7F` with spaces, so header injection was already impossible. The C1 rule makes that independent of Tomcat.

The encoder is a code-point loop of about 10 lines:

```java
static String locationHeaderValue(String target) {
    StringBuilder out = new StringBuilder(target.length());
    target.codePoints().forEach(cp -> {
        if (cp > 0x20 && cp < 0x7F) {
            out.append((char) cp);
        } else {
            for (byte b : Character.toString(cp).getBytes(StandardCharsets.UTF_8)) {
                out.append('%').append(HEX[(b >> 4) & 0xF]).append(HEX[b & 0xF]);
            }
        }
    });
    return out.toString();
}
```

It is not `URI.toASCIIString()`. The Javadoc of that method doesn't promise to leave "other" characters unnormalised, and I couldn't confirm OpenJDK's internal `encode` behaviour. It also needs a parse. The explicit loop has no hidden rules.

**Body and content type.** The body is empty, and there is no `Content-Type`. `HttpEntityMethodProcessor` calls `writeWithMessageConverters(null, …)`. With a null body it never raises 406: an unparseable `Accept` is ignored ("Ignoring error response content"), and when no media type is compatible it returns without error. It writes nothing ("Nothing to write: null body") and then flushes the headers (verified). QA records `Content-Length`. If present, it must be `0` on GET. On HEAD it may be absent (US-007 recorded this for Tomcat).

**Caching headers (C2).** Spring Security's `CacheControlHeadersWriter` returns without writing if the response already has `Cache-Control`, `Expires` or `Pragma`, or if the status is 304. `HeaderWriterFilter` (lazy by default) writes its headers when the response commits or after the chain returns, which is after the controller has set its headers (verified). The final responses are:

| Response | `Cache-Control` | `Pragma` | `Expires` |
|---|---|---|---|
| 302 (GET and HEAD) | exactly `no-store` (one value) | absent | absent |
| 404 `SHORT_URL_NOT_FOUND`, 401, 500 | `no-cache, no-store, max-age=0, must-revalidate` (Security default, D73 style) | `no-cache` | `0` |

```
Recommendation: set CacheControl.noStore() in the controller; pin Cache-Control == "no-store" exactly, and
                Pragma/Expires absent, on GET and HEAD 302s.
Reason:         D7 names the value; no-store stops browsers and shared caches from storing the 302, so a
                deactivation (D2) takes effect on the next click and every GET reaches the server (US-010 counts
                it). Setting it explicitly also makes Security back off entirely, so the pin is on one
                deterministic value, not on a framework default.
Alternative:    set nothing and rely on Security's default (it contains no-store), as D73 does for the
                management API; pin "contains no-store".
Trade-off:      the explicit value drops Pragma/Expires, which only HTTP/1.0 caches read, and a 302 without
                explicit freshness is not heuristically cacheable anyway. Relying on the default saves a line,
                but AC1 would then be satisfied only by "contains", and a later headers() change would silently
                change the redirect.
```

**Other security headers.** Security's defaults are added to every response, the 302 included: `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY` and `X-XSS-Protection: 0`. They are harmless on a redirect. **HSTS** is written only when `request.isSecure()` (verified in `HstsHeaderWriter`), so it is never present in the plain-HTTP tests. Behind the TLS-terminating load balancer it appears only after the US-014 forwarded-header setup (D37), and there it is useful: it pins the short domain to HTTPS. HSTS on our domain has no effect on an `http://` target (D11 allows those). QA records that `Strict-Transport-Security` is absent over HTTP.

#### 1.5 Query string and fragment of the short link

`GET /abc1234?x=1` redirects to the stored URL **unchanged**. The short link's query is never appended or merged, so the shortener cannot be used as a parameterised open redirect. Fragments never reach the server. Under RFC 9110 §10.2.2 a user agent keeps the original fragment only when `Location` has none. This is current behaviour by construction; Q2 asks the engineer to confirm it as product behaviour.

### 2. Resolution (D2, D6, D72, D74)

#### 2.1 `service/RedirectService`

```java
/**
 * Resolves a public short link (FR-2). Only ACTIVE links redirect; malformed (D72), unknown, DEACTIVATED (D2)
 * and DELETED codes all throw the one ShortUrlNotFoundException. Never annotate this class with
 * {@code @Transactional}: the read runs in a read-only TransactionTemplate and is closed before resolve
 * returns, so click recording (US-010) can never share it (D12).
 */
@Slf4j
@Service
public class RedirectService {

    private final ShortUrlRepository repository;
    private final TransactionTemplate readOnly;          // built here, never a bean (US-006, US-007 pattern)

    public RedirectService(ShortUrlRepository repository, PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);                  // PROPAGATION_REQUIRED
    }

    /**
     * @return the stored original URL of an ACTIVE link, exactly as stored
     * @throws ShortUrlNotFoundException if the code is malformed or unknown, or the link is DEACTIVATED or DELETED;
     *         the cases are indistinguishable (D2, D72, D74)
     */
    public String resolve(String code) {
        if (!ShortCodeFormat.isWellFormed(code)) {       // D72: before any transaction or connection
            log.debug("Redirect not found: reason=MALFORMED");   // never the submitted value
            throw new ShortUrlNotFoundException();
        }
        return readOnly.execute(status -> {
            ShortUrl url = repository.findByShortCode(code).orElse(null);   // case-sensitive (D6)
            String reason = url == null ? "NOT_FOUND"
                    : url.getStatus() != ShortUrlStatus.ACTIVE ? url.getStatus().name() : null;
            if (reason != null) {
                log.debug("Redirect not found: code={} reason={}", code, reason);
                throw new ShortUrlNotFoundException();
            }
            return url.getOriginalUrl();
        });
    }
}
```

- **The format check comes before the template.** US-007 checks inside the template, because it is a management read. The redirect is public scanner traffic, so a malformed code must not even take a pooled connection. A unit test proves it with `verifyNoInteractions(repository, transactionManager)`. It also bounds the catch-all log: only a well-formed code of at most 32 characters can ever reach the database and cause a 500 (§5).
- **Format only, never `AliasPolicy`** (D48). An existing code that later becomes reserved (for example `Health` seeded by raw SQL, or a newly added word) still redirects.
- **Status is checked in Java after `findByShortCode`**, the existing unique-index lookup. The repository doesn't change, and the DEBUG reason distinguishes causes on the server only.
- **`ShortUrlNotFoundException` is reused unchanged.** Its Javadoc gains "or, on the redirect, DEACTIVATED (D2)". The existing advice handler produces the identical body, so no new error code or handler is needed.
- Entities never leave the service: it returns a `String`.

#### 2.2 Where the D6 check lives (C3)

```
Recommendation: the D72 service check above is the only format check; the mapping stays "/{code}" with no regex.
Reason:         D72 requires malformed and unknown codes to get the same 404 SHORT_URL_NOT_FOUND. With
                "/{code:[A-Za-z0-9]{3,32}}" a malformed code matches no mapping, falls to the resource handler and
                gets 404 RESOURCE_NOT_FOUND (D61), which is distinguishable; it would also make /favicon.ico and
                future root resources behave differently from codes. AC6's actual requirement ("404 without a
                database round trip") is met, and more strictly: no connection is taken.
Alternative:    the routing regex, as AC6's parenthesis and US-005 section 3.2 suggested before D72 existed.
Trade-off:      the redirect handler runs for every single-segment GET (a cheap in-memory check), and the
                AC6 parenthesis needs a planner edit: "(checked against the D6 format before any database
                access, D72; consistent with ck_short_url_code_format)".
```

`@Pattern` on the path variable is rejected as well: it gives `400 MALFORMED_REQUEST` (D69, US-007 risk K4).

#### 2.3 New service or `ShortUrlService` (C4)

```
Recommendation: a new service/RedirectService (repository + transaction manager only).
Reason:         the redirect is public, hot-path and identity-free; US-010 adds a ClickRecorder and Clock that
                only it needs; ShortUrlService (seven dependencies, owner rules, create retry) and its
                "never @Transactional" reflection guard stay untouched; unit tests are small.
Alternative:    ShortUrlService.resolveForRedirect(code).
Trade-off:      one more class and a second read-only template; the D6 call is one shared helper line, so
                nothing is duplicated.
```

#### 2.4 Transaction, concurrency and failures

- **Transaction:** a read-only `TransactionTemplate` (`PROPAGATION_REQUIRED`), built in the constructor. There is no outer transaction (`open-in-view=false`, and the controller is not transactional). The template has committed or rolled back before `resolve` returns. A `NOT_FOUND` exception thrown inside it rolls back a transaction that has no writes, which is harmless.
- **Concurrency:** one indexed `SELECT` under READ COMMITTED, with no locks. A deactivation or delete that commits after the `SELECT` is seen by the next request. `no-store` guarantees that the next request reaches the server. No row is written, so `version`, `updated_at`, `click_count` and `last_accessed_at` stay unchanged (QA pins this until US-010).
- **Failures:** a database outage surfaces as `CannotCreateTransactionException` or `DataAccessException`. The advice catch-all turns it into `500 INTERNAL_ERROR`, logged **once** at ERROR with method and path only (the CLAUDE.md 5xx rule; existing behaviour).

### 3. The 404 for browsers and any `Accept`

The advice's `ResponseEntity<ProblemDetail>` goes through `AbstractMessageConverterMethodProcessor.writeWithMessageConverters` (Spring 6.2.19, verified):
- `DispatcherServlet.processHandlerException` has already cleared `PRODUCIBLE_MEDIA_TYPES_ATTRIBUTE` (D70). The redirect sets none in any case.
- For a `ProblemDetail`, Jackson's converter produces `application/problem+json`.
- When no acceptable type is compatible, the fallback is `if (compatibleMediaTypes.isEmpty() && ProblemDetail.class.isAssignableFrom(valueType))`, which retries with `problemMediaTypes = [application/problem+json, application/problem+xml]`. No XML converter is on the classpath.
- When the `Accept` header cannot be parsed, the `HttpMediaTypeNotAcceptableException` is caught, and for a 4xx or 5xx status the method returns without writing a body. The status was set earlier by `HttpEntityMethodProcessor`.

| `Accept` | Unknown, deactivated, deleted or malformed code | Active code |
|---|---|---|
| none | 404, `application/problem+json`, D56 base keys, `SHORT_URL_NOT_FOUND` | 302 |
| `*/*` | same 404 | 302 |
| `text/html` (browser navigation) | same 404: the problem+json fallback, **not 406** | 302 |
| `image/png` (`<img>`) | same 404 | 302 |
| `application/xml` | same 404 | 302 |
| `application/problem+json`, `application/json` | same 404 | 302 |
| unparseable (`foo`) | **404 with an empty body** and no `Content-Type` (the known D70 deviation; the status stays 404) | 302 |

**The status is always 404, never 406.** Nothing on the redirect declares `produces`, and the ProblemDetail fallback covers every parseable `Accept`. A browser shows the JSON text (or offers to download it). Whether visitors should get an HTML page is a product question (Q4).

### 4. HEAD, and the seam for US-010 (D9, D12, D18, D32)

**HEAD today.** The `@GetMapping` also matches HEAD (`RequestMethodsRequestCondition`, Spring reference "HTTP HEAD, OPTIONS"). The handler runs in full, one indexed read included, and the container discards the body. `HttpServletRequest.getMethod()` still returns `"HEAD"`, and Spring MVC resolves an `HttpMethod` handler argument from it (the Spring 6.2 reference argument table lists `HttpMethod`). HEAD gets the same 302, the same `Location` and the same `Cache-Control: no-store` (AC7). Nothing is written for either method in US-008.

**The seam US-010 will use.** It needs no change to the status or headers:
1. **One handler, one response builder.** US-010 adds an `HttpMethod method` parameter. springdoc ignores it (`PARAM_TYPES_TO_IGNORE.add(HttpMethod.class)`, verified). It passes `HttpMethod.GET.equals(method)` down, and that is the only method check. The `ResponseEntity` construction stays exactly as it is in §1.1, so every header pin in this story keeps passing unchanged, and that proves the "no change to the redirect" requirement.
2. **Recording happens only after a successful resolution**, never for 404s, and only for GET (D9). US-010 decides whether `RedirectService` calls the `ClickRecorder`, as in the architecture diagram, or the controller does. This note recommends the service, with a boolean from the controller, so that the service stays HTTP-agnostic.
3. **Separate transactions (D12).** `resolve`'s read-only transaction has already ended, so a recorder transaction (`REQUIRED` or `REQUIRES_NEW`) cannot join it and cannot mark it rollback-only. US-010's risk section is met by construction. The `RedirectServiceTest` reflection guard (no `@Transactional`) must stay.
4. **Fail open (D12).** The recorder call is wrapped so that any exception is logged with the code only and swallowed. That wrapper is designed in US-010.
5. **Clock.** US-010 injects `Clock` into whichever class records. The redirect's status and headers never depend on it.

```
Recommendation: US-008 adds no ClickRecorder interface, no no-op bean and no HttpMethod parameter; it documents
                the seam above and pins "GET and HEAD write nothing".
Reason:         the recorder's signature (id or code, Instant), its transaction and its fail-open wrapper are
                US-010 design decisions not yet approved; a no-op bean would be dead production code and an unused
                parameter would be flagged in review; the HEAD-not-counted proof needs real writes (US-010 AC2),
                which US-008 cannot supply.
Alternative:    add ClickRecorder plus a NoOpClickRecorder bean and the GET check now.
Trade-off:      US-010 touches the controller signature (one parameter, one call), a small reviewed change whose
                safety is shown by this story's header ITs staying green. The alternative fixes an interface
                before its design gate.
```

### 5. Logging (C6)

| Where | Level | Content |
|---|---|---|
| `RedirectService`, 302 | none | Nothing. It is the hot path, and there is no success log at INFO or DEBUG |
| `RedirectService`, 404 for a well-formed code | DEBUG | `Redirect not found: code={} reason={}`, where the reason is `NOT_FOUND`, `DEACTIVATED` or `DELETED`. Never the URL or host |
| `RedirectService`, malformed code | DEBUG | `reason=MALFORMED`, **without the value** (arbitrary client text) |
| `RedirectController` | none | Nothing |
| Entry point, 401 caused by bad credentials on `/{code}` | INFO (unchanged) | `Authentication failed: GET <raw path> (BadCredentialsException)`. No username or IP (D52) |
| Catch-all, 500 | ERROR, once (unchanged) | Method and raw path, plus the stack trace |

```
Recommendation: no change to the entry-point or catch-all path logging in US-008.
Reason:         the logged path is bounded: StrictHttpFirewall allows only printable ASCII (0x20-0x7E) in the raw
                URI and blocks %0a/%0d/%00 (verified), so no CR/LF log injection; the path stays percent-encoded;
                Tomcat caps the request line and headers (8 KB default). On the redirect, the ERROR line can
                only follow a well-formed code of at most 32 characters, because malformed codes never touch the
                database. The INFO line needs a client that sends bad credentials, which is equally possible on
                /api today, so it is not specific to public routes.
Alternative:    one shared helper that caps logged paths (for example at 256 characters with a length suffix) in
                both classes, or omits the path on single-segment routes.
Trade-off:      a client that sends bad credentials can still write INFO lines of up to about 8 KB at will (log
                volume, not injection); rate limiting belongs at the edge (US-014). The helper would be a new
                class for two call sites without a demonstrated need.
```

The target URL, its host and query are never logged anywhere on the redirect path (CLAUDE.md). C1 also removes the one place where Tomcat itself would have logged a full URL.

### 6. OpenAPI (C7)

- **Documented.** `@Tag(name = "Redirect")` on the class. There is **no `@SecurityRequirement`**, and none is global (`OpenApiConfig` applies security per controller), so the operation shows no lock.
- **`@Operation(summary = "Follow a short link")`**, with a description that states:
  - the endpoint is public;
  - it answers 302 with the stored URL (non-ASCII percent-encoded as UTF-8) and `Cache-Control: no-store`;
  - HEAD returns the same status and headers, is never counted and is not listed separately;
  - unknown, deactivated, deleted and never-valid codes give the same 404;
  - the short link's query string is ignored;
  - invalid Basic credentials get 401 (D55);
  - Swagger UI's "Try it out" follows the redirect in the browser, where the target's CORS policy usually makes it fail, so `curl -i` is the better tool.
- **`@Parameter(name = "code", in = PATH)`**: Base62, 3–32 characters, case-sensitive. There is **no `pattern`**, because a pattern would suggest a 400. springdoc documents path variables as `required: true`, `in: path` (verified in `AbstractRequestService`).
- **`@ApiResponse` entries:**
  - `302`, with `headers = {@Header(name = "Location"), @Header(name = "Cache-Control")}` and **no content**.
  - `404`, with `@Content(mediaType = "application/problem+json", schema = @Schema(implementation = ErrorResponseSchema.class))`.
  - 401, 405 and 500 are not documented.
- **A single-segment path in springdoc** is just the path `/{code}` with one parameter. OpenAPI requires concrete paths to be matched before templated ones, and springdoc omits its own endpoints, actuator endpoints and the `@Operation(hidden)` Swagger welcome controller. HEAD is not emitted, because springdoc documents only the declared method.
- QA pins the exact `responses` keys `{302, 404}`. If springdoc adds an implicit `200` (not verified), the mid-engineer adds `@ResponseStatus(HttpStatus.FOUND)` to the method, and the IT confirms the fix.

### 7. Tests

#### 7.1 AC → component → test → owner

| AC | Component | mid-engineer (`*Test`) | qa-tester (Cucumber + `RedirectIT`) |
|---|---|---|---|
| AC1 | Controller, `locationHeaderValue`, `RedirectService` | Service: ACTIVE returns the exact stored string. Slice: 302, exact `Location`, `Cache-Control` equals `no-store`, no `Pragma` or `Expires`, empty body, no `Content-Type`. Encoder unit tests | Scenario; IT URL matrix (§7.3 item 1), exact caching headers |
| AC2–AC4 | `resolve`, advice | Service: unknown, DEACTIVATED and DELETED throw exceptions with equal type and message. Slice: exception → 404, problem+json, base keys, `instance` equal to the path | Outline (unknown, deactivated, deleted); IT byte-identical bodies on one path |
| AC5 | Mapping precedence, rules 2–7 | – | IT table from §1.3 plus the mapping inventory; Cucumber outline over the infrastructure paths |
| AC6 | D72 check before the template | Service: the malformed set with `verifyNoInteractions(repository, transactionManager)`. Slice: 404 `SHORT_URL_NOT_FOUND` | Outline of malformed codes: same body as unknown except `instance` |
| AC7 | GET mapping serving HEAD | Slice: HEAD gives 302 with the same `Location` and `Cache-Control`, and no body | Scenario; IT header maps of HEAD and GET equal except `Date` |

#### 7.2 mid-engineer

- **`RedirectServiceTest`** (Mockito repository and `PlatformTransactionManager`):
  - ACTIVE returns `getOriginalUrl()` unchanged, including a query, a fragment and `%2F`.
  - DEACTIVATED, DELETED and empty all throw `ShortUrlNotFoundException`, with equal messages.
  - The repository is called once with the exact code (no case folding).
  - Malformed inputs: `null`, `""`, `ab`, 33 characters, `a-b`, `a_b`, `abc ` (trailing space), `abc.json`, `favicon.ico`, `abcé`, and full-width `ＡＢＣ`. Each throws and gets `verifyNoInteractions(repository, transactionManager)`.
  - The positive boundaries, 3 and 32 characters, do query the repository.
  - An existing ACTIVE code `Health` is returned (D48, format only).
  - A captor on `getTransaction` sees `readOnly == true` and `PROPAGATION_REQUIRED`.
  - The reflection guard: no `@Transactional` from Spring or Jakarta on the class or any method, and non-vacuous (`resolve` is among the declared methods).
  - Logging with `OutputCaptureExtension` and the level set to DEBUG:
    - the `DEACTIVATED` line contains the code and the reason, and the output does not contain the URL marker;
    - the malformed line does not contain the submitted value;
    - each "not logged" assertion also asserts that its line was captured.
- **`RedirectControllerTest`** (plain unit test of `locationHeaderValue`):
  - ASCII is unchanged: `https://Example.COM:8443/a/../b;p?q=a+b&c=%2f%2F#frag`, `https://example.com`, a trailing `?` and a trailing `#`, and a 2048-character URL.
  - Non-ASCII is encoded: `é` → `%C3%A9`, an emoji → `%F0%9F%98%80`, decomposed `e` plus U+0301 → `e%CC%81` (no NFC), and an existing `%C3%A9` is kept.
  - CR, LF, TAB, space and DEL are encoded.
  - Every output matches `^[\x21-\x7E]*$`.
  - A reflection check for D70: `AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class).produces()` is empty, there is no class-level `@RequestMapping`, and the superclass is `Object`.
- **`RedirectControllerWebMvcTest`**: `@WebMvcTest(RedirectController.class)`, `@Import(SecuritySliceTestConfiguration.class)`, `@MockitoBean RedirectService`, the real advice and the real filter chain.
  - Anonymous GET and HEAD give 302 with an exact `Location`, `Cache-Control` equal to `no-store`, `Pragma` and `Expires` null, an empty body and a null content type.
  - Valid alice credentials also give 302.
  - Bad credentials give 401 `AUTHENTICATION_REQUIRED` with the challenge, and `verifyNoInteractions(service)` (D55).
  - `Accept` values `text/html`, `image/png`, `application/xml`, `*/*`, none and `foo` all give 302.
  - The service throws `ShortUrlNotFoundException`:
    - 404 `SHORT_URL_NOT_FOUND` as `application/problem+json`, with exactly the base keys, for the parseable `Accept` values;
    - `foo` gives 404 with an empty body;
    - the 404 carries Security's default `Cache-Control`.
  - The service throws `DataAccessResourceFailureException`: 500 `INTERNAL_ERROR`, logged exactly once at ERROR, and the output does not contain the stubbed URL.
  - `POST /x1234` gives 401 anonymous and 403 authenticated, and the service is not called.
- **Existing slices are unaffected.** `SecurityConfigWebMvcTest` and `ShortUrlControllerWebMvcTest` both restrict their controllers.

#### 7.3 qa-tester

**Rules.**
- `RedirectIT extends IntegrationTestBase` and adds nothing that changes the context.
- Each test truncates in `@BeforeEach` only and resets the scripted generator before and after each test.
- Execution is serial, and no test relies on another class's rows.
- Use `ApiClient`, whose client never follows redirects (`Redirect.NEVER`). Read `Location` as a raw string.
- `ShortUrlTestData` gains `setStatus(code, status)` for DEACTIVATED (`markDeleted` already exists).
- ACTIVE links are created **through the API** as alice when the exact-`Location` round trip matters.

**`RedirectIT`:**
1. **Exact `Location` round trip.** POST each URL, then GET `/` plus the code. `Location` equals the submitted string, the create response's `originalUrl` and the DB value. The URLs:
   - a query with `&`, `=` and `+`;
   - a fragment;
   - mixed-case escapes `%2f` and `%2F`;
   - `%20`;
   - an upper-case scheme and host;
   - dot segments;
   - `https://example.com` with no path, which must not gain a slash;
   - a port, and an IPv6 literal;
   - a trailing `?`;
   - a 2048-character URL.

   With C1 approved, add non-ASCII cases: `https://example.com/café?q=ü#ß` gives `…caf%C3%A9?q=%C3%BC#%C3%9F`, and an emoji path. Capture the output as well: there is **no** Tomcat "has been removed from the response because it is invalid" line and no URL marker. The positive control is the `Location` header being present.
2. **Caching headers.** GET and HEAD 302s have exactly one `Cache-Control` value, `no-store`, and no `Pragma` or `Expires`. The 404 has `no-cache, no-store, max-age=0, must-revalidate`, `Pragma: no-cache` and `Expires: 0`. `Strict-Transport-Security` is absent over HTTP.
3. **Body.** The 302 body is empty with no `Content-Type`. Record `Content-Length`: if present, it is `0`.
4. **HEAD (AC7).** The header map equals GET's apart from `Date` (and `Content-Length`, if recorded differently), and there is no body.
5. **404 identity (AC2–AC4).** On path `/Same1234`:
   - no row gives B1;
   - seed it ACTIVE, and the response is 302 (control);
   - `setStatus DEACTIVATED` gives B2;
   - `markDeleted` gives B3.

   B1, B2 and B3 are equal strings, and their headers are equal except `Date`. For HEAD, repeat the sequence, with the 302 as the same-path proof (CLAUDE.md HEAD rule). Malformed paths (`/ab`, 33 characters, `/a_b`, `/ab%20c`, `/caf%C3%A9`, `/abc.json`, `/favicon.ico`) give 404 `SHORT_URL_NOT_FOUND`, with the same body apart from `instance`, which equals the raw request path.
6. **`Accept` matrix.** Run §3's table on one active and one unknown code. The status is never 406.
7. **No writes.** After GET and HEAD, `rowState` (`version`, `updated_at`, `click_count`, `last_accessed_at`) is unchanged, and every recorded status is 302 (the non-vacuity check). **US-010 deliberately re-pins the GET half of this.**
8. **Identity.** Anonymous gets 302. alice gets 302 on bob's link (the owner doesn't matter). Bad credentials get 401 `AUTHENTICATION_REQUIRED` with the challenge, not 302 (D55).
9. **Case sensitivity.** `Mixed1` and `mixed1` redirect to their own URLs, and `MIXED1` gives 404.
10. **D48.** A raw-SQL ACTIVE row `Health` gives 302.
11. **Routing precedence (AC5).**
    - Run every row of §1.3, anonymous and as alice, with exact statuses. `/actuator` and `/` are recorded, with the pin that neither is ever 302.
    - `/swagger-ui.html` gives 302 with `Location` `/swagger-ui/index.html`, which is springdoc's redirect, not ours.
    - Seed raw-SQL ACTIVE rows `api` and `actuator`: anonymous GET and HEAD `/api` and `/actuator` still give 401.
    - alice's `GET /api/v1/urls/{her code}` still gives 200 JSON.
12. **Mapping inventory.** Autowire `@Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping`. Collect the patterns of all GET-capable mappings that have exactly one segment. The set must equal `{"/{code}", "/error", "/swagger-ui.html"}`. This enforces the architecture rule that no other single-segment handler exists.
13. **Hostile paths are never a 500.**
    - The raw characters `{` and `|` in the path get 400 from Tomcat.
    - `%2F`, `%25`, `%5C`, `;` and `%0a` get 400 from the firewall.
    - Record `/%C3` (invalid UTF-8): 400 or 404, never 500.
14. **Query string.** `GET /{code}?x=1` gives a `Location` that equals the stored URL (pending Q2).

**Cucumber** (`features/redirect.feature`, glue in `cucumber/RedirectSteps`):
- one scenario for each of AC1–AC4 and AC7;
- an AC5 outline over the infrastructure paths;
- an AC6 malformed outline (the no-DB-call half is the unit test's job);
- an `Accept` outline.

Steps reuse `ApiClient`, `ShortUrlTestData` and `TestUsers`, and never write a Gherkin word to the database unresolved.

**`OpenApiDocsIT` (extend):**
- `paths./{code}` has exactly `get`;
- exactly one parameter, `code`, in `path`;
- `responses` keys are exactly `302` and `404`;
- `302` has `Location` and `Cache-Control` headers and no `content`;
- `404` is `application/problem+json` only, with `Problem`;
- no `security` on the operation or at the root.

**Existing tests to re-pin (expected changes):**
- `SecurityIT.shouldNotBlockShortCodePathsForAnonymousGetAndHead`: GET on `/abc1234`, `/ab`, `/a_b` and `/v3` changes from `RESOURCE_NOT_FOUND` to **`SHORT_URL_NOT_FOUND`**. `/abc1234` has no row, and the others are malformed. Update the "no redirect controller until US-008" comment.
- `SecurityIT.shouldSetNoCookieOnAnyResponse`: its `GET /abc1234` now gets 404 `SHORT_URL_NOT_FOUND`, and the test still passes. Add a 302 response to the set.
- `security.feature`'s "short-code-shaped path" outline only asserts "not 401/403", so it still passes. There is no change.
- `SecurityConfigWebMvcTest` uses its own probe controller, so it doesn't change.

#### 7.4 Carry-over for qa-tester (from US-007 G3)

- **R11:** wrap the two lines over 120 characters in `GetShortUrlSteps` (around lines 170 and 220).
- **R12:** sort the `java.util` imports in `GetShortUrlSteps`.
- **R13:** `ApiClient.passwordOf` calls `TestUsers.require(user)` and drops the `toLowerCase`, so there is one strict lookup with one case rule. All current `ApiClient` callers pass lowercase constants. `SecurityIT`'s upper-case login test uses its own `send` with an explicit password, so it is unaffected. Re-check the callers when making the change.
- Comments cite no finding IDs (CLAUDE.md rule).

### 8. Implementation plan

**mid-engineer**
1. `service/RedirectService` (§2.1). Update the `ShortUrlNotFoundException` Javadoc for D2.
2. `api/RedirectController`, with `locationHeaderValue` and the OpenAPI annotations (§1.1, §1.4, §6). The class Javadoc names rule 7.
3. The tests in §7.2.

No changes to `SecurityConfig`, `GlobalExceptionHandler`, `ShortUrlService`, `ShortUrlRepository`, the migrations or `application.yml`.

**qa-tester**
1. `ShortUrlTestData.setStatus`.
2. `support/RedirectIT`, `features/redirect.feature`, `cucumber/RedirectSteps`, the `OpenApiDocsIT` extension and the re-pins (§7.3).
3. R11–R13 (§7.4).

### 9. Open questions (for the engineer)

- **Q1 (C1):** non-ASCII targets are percent-encoded in `Location`. **Recommend accepting this and editing the AC1 wording.** The alternative is to reject non-ASCII URLs at create, which changes D11 and the finished US-004 tests (`shouldCountCharactersNotBytesForMultibyteUrlAtLimit` and `shouldAcceptValidSupplementaryCharacterInPath` accept `é` and emoji).
- **Q2:** the short link's query string is ignored and never forwarded (§1.5). Requirements say nothing about it. **Recommend confirming this** as product behaviour: forwarding would turn links into parameterised open redirects.
- **Q3:** `GET /{code}/` (trailing slash) gets 401 with `WWW-Authenticate: Basic` from `denyAll` (D57), so a browser shows a login prompt. **Recommend accepting this for now and recording it.** Revisit at US-014, for example with no challenge on non-API paths, which would refine D30.
- **Q4:** browsers receive the problem+json 404, not an HTML page. **Recommend keeping it** (one advice, D31). A branded HTML 404 is a roadmap item.
- **Q5 (existing behaviour, surfaced by AC5):** a direct `GET /error` returns 500 (Boot's error JSON). Scanners can inflate 5xx metrics without any exception being logged. **Recommend recording it** and treating it as a US-014 item (for example alerting that excludes `/error`).
- **Q6 (planner edits):** the AC1 wording (C1, C2 "exactly `no-store`"), the AC5 wording (C8) and the AC6 parenthesis (C3).
- **Q7:** add a DB CHECK that forbids the built-in reserved words as codes, so a raw-SQL `api` row cannot redirect authenticated callers? **Recommend no.** It needs a migration, the V2 slot is planned for `click_event`, and the application is the only writer.

### 10. Risks for the implementer and the reviewer

- **K1 `produces` creeping in** (D70): on the class, on a shared base class or annotation, or on an `@ExceptionHandler`. Any of these would give 406 instead of 302 for `image/png`. It is caught by the reflection test and the `Accept` matrix.
- **K2 Header rewriting.** Watch for `location(URI)`, `setLocation`, `UriComponentsBuilder`, `"redirect:"`, `sendRedirect`, `URI.toASCIIString()` or a `RedirectAttributes` parameter. Each re-derives or re-routes the stored string. It is caught by the exact-`Location` matrix, especially the rows with mixed-case escapes, dot segments and no path.
- **K3 Cache header drift.** Adding `.cacheControl(...)` elsewhere, or setting `Pragma`/`Expires`, changes what Security writes. The exact pins catch it.
- **K4 A format check inside the template or after the lookup** opens a connection for scanner traffic, and it lets long junk paths reach the 500 log. `verifyNoInteractions(transactionManager)` catches it.
- **K5 `AliasPolicy.isValid` used instead of `ShortCodeFormat`** would hide existing codes that later become reserved (D48). The `Health` tests catch it.
- **K6 Vacuous 404s.** Every 404 assertion also checks `errorCode`, because `RESOURCE_NOT_FOUND` also comes with 404. Every HEAD 404 has a 302 on the same path.
- **K7 Logging.** The target URL, its host, `ShortUrl.toString()` beyond its explicit fields, and malformed values are never logged. A Tomcat invalid-header WARN would contain the full URL, and C1 prevents it.
- **K8 Single-segment shadowing.** `/{code}` takes any root path that has no direct mapping: a future static `index.html` or `favicon.ico`, or `GET /admin`. Rule 7 would also make such a handler public. The mapping-inventory IT fails if one appears.
- **K9 US-010 regression.** When recording is added, the header ITs in this story must stay unchanged and green. The read transaction must stay closed before recording, and `@Transactional` stays forbidden on `RedirectService`.
- **K10 Open redirect and abuse.** Redirecting to user-supplied targets is the product itself. D11 limits them to absolute `http`/`https` URLs with a host, with no userinfo (which blocks `https://trusted@evil` deception) and not on our own host (no loops or chains through us). Targets are validated at create only, and the redirect does not re-validate: a changed `APP_BASE_URL` must not break existing links, and a raw-SQL row is trusted. `https` short links can point to `http` targets (a downgrade the product allows). **Roadmap:** phishing and malware screening at create (for example Safe Browsing), abuse reporting and takedown, rate limits on create, an optional interstitial preview page, a domain blocklist, and possibly a DB CHECK on the scheme.
- **K11 Tomcat and Security upgrades.** The C1 rationale and the `Cache-Control` back-off are verified for Tomcat 10.1.55 and Security 6.5.11. The ITs turn any change in either into a failing test.

### 11. Sources checked (2026-09-29)

- **Spring Framework 6.2.x source:**
  - `AbstractHandlerMethodMapping.lookupHandlerMethod`: direct paths first; the ambiguity `IllegalStateException`.
  - `AbstractMessageConverterMethodProcessor.writeWithMessageConverters`: the `problemMediaTypes` fallback; unparseable `Accept` ignored for a null body or a 4xx/5xx status; a null body never gives 406; "Nothing to write: null body".
  - `HttpEntityMethodProcessor.handleReturnValue`: the status is set first; a null body is still written, then flushed; 3xx `saveFlashAttributes` applies only with `RedirectAttributes`.
  - `HttpHeaders`: `setLocation` uses `toASCIIString()`; `add`/`set` store values unchanged; `getLocation` calls `URI.create`.
  - `CaptureVariablePathElement`: at least one character is required.
- **Spring Framework 6.2 reference:** *Mapping Requests* (HEAD through `@GetMapping`; pattern comparison with `SPECIFICITY_COMPARATOR`; `{name:regex}`), and *Method Arguments* (`HttpMethod`).
- **Spring Security 6.5.x source:**
  - `CacheControlHeadersWriter`: skipped when `Cache-Control`, `Expires` or `Pragma` is present, or the status is 304.
  - `HeaderWriterFilter`: lazy writing, on commit or in a `finally`.
  - `HstsHeaderWriter`: secure requests only; one year; `includeSubDomains`.
  - `StrictHttpFirewall`: printable ASCII `0x20`–`0x7E`; the encoded blocklist `%25 %2E %2F %3B %5C %00 %0A %0D`.
- **Tomcat 10.1.x source** (Boot 3.5.16 manages Tomcat 10.1.55, Framework 6.2.19 and Security 6.5.11, according to the Boot 3.5.16 dependency-versions page):
  - `MessageBytes.toBytesSimple`: `IllegalArgumentException` for characters above `0xFF`, otherwise a byte cast.
  - `Http11OutputBuffer.write(MessageBytes)`: CTL bytes become spaces.
  - `Http11Processor.prepareResponse`: the invalid header is logged with its value at WARN and removed.
  - `LocalStrings`: the text of `http11processor.response.invalidHeader`.
  - `AbstractProcessor` COMMIT: catches `IOException` only.
- **Spring Boot 3.5.16 source:**
  - `AbstractErrorController.getStatus`: 500 when no status attribute is set.
  - `BasicErrorController`: `@RequestMapping("${server.error.path:${error.path:/error}}")`, with `error()` and `errorHtml()`.
  - `AbstractWebMvcEndpointHandlerMapping`: `setOrder(-100)` and the links mapping at the base path.
- **springdoc-openapi 2.8.17 source:**
  - `SwaggerWelcomeWebMvc`: `@Controller` with `@GetMapping(SWAGGER_UI_PATH)`.
  - `AbstractRequestService`: `PARAM_TYPES_TO_IGNORE.add(HttpMethod.class)`; path variables are required.
  - **Not verified:** whether springdoc adds an implicit `200` next to a declared `302`. QA pins the response keys.
- **Java SE 25 `java.net.URI` Javadoc:** the "other" category (non-ASCII, not an ISO control, not a space character) is allowed; `toString` returns the original input; `toASCIIString` encodes "other" characters. Whether OpenJDK applies NFC inside it was **not verified**, and the design avoids that method.
- **RFC 3987 §3.1** (IRI-to-URI mapping: UTF-8 bytes of non-ASCII characters, percent-encoded) and **RFC 9110 §10.2.2** (`Location` and fragment inheritance). Cited from the standards; not fetched.
- **Code checked at `a94d41c`:**
  - Main code: `SecurityConfig`, `ProblemDetailAuthenticationEntryPoint`, `GlobalExceptionHandler`, `ProblemDetails`, `ShortUrlService`, `ShortCodeFormat`, `ShortUrlRepository`, `ShortUrl`, `ShortUrlNotFoundException`, `UrlValidator`, `HttpUris`, `OpenApiConfig`, `ShortUrlController` (annotations) and `application.yml`.
  - Test code: `UrlValidatorTest` (non-ASCII paths accepted), `IntegrationTestBase`, `ApiClient`, `ShortUrlTestData`, `TestUsers`, `SecurityIT`, `SecurityConfigWebMvcTest`, `ShortUrlControllerWebMvcTest`, `OpenApiDocsIT`, `security.feature`, and the `ShortUrlServiceTest` reflection guard.

## Implementation notes
**Files changed**
- New: `src/main/java/com/schwab/urlshortener/api/RedirectController.java`, `src/main/java/com/schwab/urlshortener/service/RedirectService.java`.
- Edited: `service/exception/ShortUrlNotFoundException.java` (Javadoc only, adds DEACTIVATED on the redirect, D2).
- New tests: `api/RedirectControllerTest` (encoder table, D70 reflection, source guardrails), `api/RedirectControllerWebMvcTest` (slice), `service/RedirectServiceTest` (unit).
- Not touched: `SecurityConfig`, `GlobalExceptionHandler`, `ShortUrlService`, repository, migrations, `application.yml`, any `*IT`, Cucumber file or QA support class.

**Decisions**
- Built exactly as the approved design: encoder is `RedirectController.locationHeaderValue` (package-private static); format check runs before the read-only `TransactionTemplate`; no `ClickRecorder`, no `HttpMethod` parameter (the seam is documented in the controller Javadoc).
- One extra test beyond the design list: `shouldBuildLocationAsARawStringHeaderAndNeverReadTheQueryString` reads the controller source (comments stripped) and fails if a forbidden API name appears. It depends on the working directory being the project root (true under Surefire). Reviewers may prefer to drop it as brittle.
- The unparseable `Accept: foo` case is asserted as 302 for a hit and 404 with an empty body for a miss, as the design predicted; Spring behaved as described.

**Extra human review**
- The forbidden-API source scan test (above).
- springdoc's implicit `200` next to the declared `302` was not verified here: the design assigns the `responses` key pin to QA (`OpenApiDocsIT`). If it fails, add `@ResponseStatus(HttpStatus.FOUND)` as the design says.

**Test command and result**
`JAVA_HOME=/usr/local/opt/openjdk/libexec/openjdk.jdk/Contents/Home ./mvnw -q verify`: Surefire 689 run, 0 failed (84 of them new: 30 service, 25 encoder/reflection, 29 slice). Failsafe 310 run, 1 failed, and it is the expected QA-owned re-pin: `SecurityIT.shouldNotBlockShortCodePathsForAnonymousGetAndHead(String)[1]` (`SecurityIT.java:243`, `GET /abc1234` expected `RESOURCE_NOT_FOUND`, got `SHORT_URL_NOT_FOUND`). Merged JaCoCo LINE coverage 99.55% (447 of 449; `RedirectController` 13/13, `RedirectService` 20/20). The build exits non-zero only because of that failure.

**Fix round 2 (R1, D84)**
- Location of the shared encoder: `validation/LocationEncoder` (public final class, static `encode`). `api` already depends on `validation` (`GlobalExceptionHandler` uses `UrlValidator`), so `RedirectController` and `UrlValidator` both use it with no layering violation, and no new package is needed.
- Edited: `RedirectController` (encoder removed, calls `LocationEncoder.encode`), `UrlValidator` (`MAX_ENCODED_BYTES`, encoded check, Javadoc). New: `LocationEncoder`, `LocationEncoderTest` (encoder tests moved out of `RedirectControllerTest`). Edited tests: `RedirectControllerTest` (source guardrail now expects `LocationEncoder.encode(`), `UrlValidatorTest`, `ShortUrlServiceTest` (+1).
- `shouldCountCharactersNotBytesForMultibyteUrlAtLimit` is replaced by `shouldRejectUrlOfMaxCharactersWhenItsEncodedFormExceedsMaxBytes` (meaning changed: 2048 multibyte characters are now rejected) and `shouldCountCodePointsNotUtf16UnitsOrBytesForShortMultibyteUrls`. `shouldCountSupplementaryCodePointsAsOneCharacter` is removed for the same reason (its 2048-emoji input is now rejected). Under D84 no non-ASCII URL can reach the 2048-character limit (each non-ASCII character encodes to at least 6 bytes), so the D11 character count can only be distinguished from UTF-16 units and bytes on shorter URLs. ASCII at exactly 2048 and 2049 characters still pins D11.
- Review note: `GlobalExceptionHandler.URL_RULE` (the 400 detail text) says "at most 2048 characters" and does not mention the byte rule. Updated afterwards to include the byte rule (see the Round 2 R1 row).
- Test command and result: `JAVA_HOME=/usr/local/opt/openjdk/libexec/openjdk.jdk/Contents/Home ./mvnw -q verify` exit 0. Surefire 702 run, 0 failed, 0 errors, 0 skipped. Failsafe 441 run, 0 failed, 0 errors, 0 skipped. No existing QA IT failed.

## QA notes

**Written**: `features/redirect.feature` + `cucumber/RedirectSteps` (15 scenarios/outlines, 31 executions); `support/RedirectIT` (91 tests); `support/TomcatLocationHeaderProbeIT` (3 tests, bare embedded Tomcat, no Spring context); `OpenApiDocsIT` (+6); `SecurityIT` re-pin plus a per-test truncate; `ShortUrlTestData.setStatus`; R11-R13.

| AC | Cucumber | RedirectIT / other |
|---|---|---|
| AC1 | "redirected to exactly the stored URL", non-ASCII outline | `shouldRedirectToTheStoredAsciiUrlByteForByte` (13 URLs), `shouldPercentEncodeNonAscii...` (6), `shouldSendExactlyNoStore...`, `shouldSend302WithAnEmptyBody...`, control-char test |
| AC2-AC4 | unknown; deactivated/deleted outline | `shouldReturnByteIdentical404...SameGetPath`, `...SameHeadPath` (302 control on the same path) |
| AC5 | infrastructure outline, authenticated `/api`, management API not shadowed | routing tests, raw-SQL `api`/`actuator` rows, mapping inventory pin, `SecurityIT` re-pin |
| AC6 | malformed outline | `shouldReturnTheSame404BodyExceptInstance...`, HEAD variant (9 codes) |
| AC7 | HEAD equals GET headers; HEAD 404 | `shouldAnswerHeadWithTheSameStatusAndHeaders...` |
| AC8 | query not forwarded | `shouldNotForwardTheShortLinkQuery...` |
| D55/D70/D80/D48 | wrong password; Accept outline | credentials, `Accept` matrix (8 values, 302 and 404), trailing slash, `Health` row |
| OpenAPI | - | `OpenApiDocsIT`: only `get`, one `code` path param, responses exactly `302`+`404` (no implicit 200), 302 headers `Location`+`Cache-Control` and no content, 404 problem+json/`Problem`, no security |

**Recorded behaviour (real run)**
- Routing (GET, anonymous / alice): `/error` 500 / 500 (Boot JSON, no errorCode); `/swagger-ui.html` 302 to `/swagger-ui/index.html` both; `/v3/api-docs`, `/v3/api-docs/swagger-config`, `/swagger-ui/index.html`, `/actuator/health` 200 / 200; `/actuator` 401 / 200 (links); `/api` 401 / 404 SHORT_URL_NOT_FOUND; `/favicon.ico`, `/v3`, `/ab`, `/a_b`, `/API`, `/Actuator` 404 SHORT_URL_NOT_FOUND both; `/` 404 RESOURCE_NOT_FOUND both.
- HEAD differs from GET on multi-segment paths (the design table said "same for every row"): `/v3/api-docs`, `/v3/api-docs/swagger-config`, `/swagger-ui/index.html` are 401 anonymous / 403 alice (D57 denyAll); `/actuator/health` HEAD is 401 anonymous / 200 alice. Single-segment paths match GET. Not a US-008 defect; US-014 note: an anonymous HEAD health probe gets 401.
- D80: `/{code}/` GET and HEAD are 401 with a Basic challenge anonymous, 403 alice.
- A raw-SQL `api` row: anonymous 401 (pinned). Authenticated redirect for such a row is the accepted D83 gap and is not pinned.
- Mapping inventory of GET-capable single-segment patterns is exactly `{/{code}, /error, /swagger-ui.html}`.
- 404 carries the Security default caching headers; 302 has exactly `Cache-Control: no-store`, no Pragma/Expires, no HSTS over HTTP, no Content-Type, and Content-Length absent or 0.

**C1 (Tomcat 10.1.55), bare embedded Tomcat probe, matches the architect's reading**: U+00E9 goes out as the single byte 0xE9 (not UTF-8); U+4E2D gives a 302 with the `Location` header removed (other header sent) and a WARN from `Http11Processor` containing the "has been removed from the response because it is invalid" text and the full URL (the log also carries a stack trace); a percent-encoded value passes unchanged. After D75, via the real app: `%E4%B8%AD`, `%C3%A9`, `caf%C3%A9?q=%C3%BC#%C3%9F`, emoji `%F0%9F%98%80`, decomposed `e%CC%81` are present exactly, and captured output has no "removed from the response" line and no URL text. The create API accepts these URLs.

**Test-support changes**: R13 as specified broke `CreateShortUrlIT` and the create feature, which sign in as "ALICE" and "Bob" (case-insensitive login, US-006 AC10; the design's "all callers pass lowercase" was wrong). `ApiClient.passwordOf` now uses `TestUsers.require` strictly, and `ApiClient.basicHeader(login, user)` sends the typed login with the account's password; those two tests use it.

**Defects**: none.

## Review log
*(senior-engineer findings per round, and how each was resolved)*

| Round | ID | Severity | Finding | Resolution |
|---|---|---|---|---|
| 1 | R1 | BLOCKING | 2048-character CJK URL: redirect behaviour unknown | Measured; awaiting engineer decision. Nothing pinned, nothing fixed. Findings: a 2048-character URL of `https://example.com/` plus 2028 `中` is accepted by the create API (201) and stored. Its encoded `Location` is about 18 KB, so `GET` and `HEAD /{code}` return a bare 500 (no body, no `Content-Type`, no `Cache-Control`, no `Location`; headers are only `Connection: close` and `Date`). Tomcat logs one `ERROR` from `org.apache.coyote.http11.Http11Processor` ("Error processing request", `HeadersTooLargeException`, with stack trace); the URL does not appear in it, and the app's own catch-all logs nothing. Threshold is the 8192-byte header buffer: 880 `中` (900 characters, Location 7940 bytes) is 302; 900 `中` (920 characters, about 8 KB of Location) is 500. A pure-ASCII 2048-character URL is 302. |
| 1 | R2 | BLOCKING | HEAD 404 scenario had no same-path 302 control and no errorCode | Fixed. `redirect.feature` seeds `Gone5678` ACTIVE, HEAD gives 302, `setStatus DEACTIVATED` (new step over `ShortUrlTestData.setStatus`), HEAD gives 404, then GET on the same path asserts `SHORT_URL_NOT_FOUND`. |
| 1 | R3 | BLOCKING | 404 half of the Accept outline asserted only the status | Fixed. Added `has error code "SHORT_URL_NOT_FOUND"`. |
| 1 | R4 | BLOCKING | HEAD routing table 404 rows had no errorCode check; `/%C3` used `isIn(400, 404)` | Fixed. `shouldGiveHeadTheRecordedStatusOnEveryRoutingPath` is a plain `for` loop over `entrySet()`, and every 404 row (anonymous and alice) also GETs the same path and asserts `SHORT_URL_NOT_FOUND` (`RESOURCE_NOT_FOUND` for `/`). `/%C3` measured as 400 (Tomcat, before Spring, HTML body, no errorCode), and the test now asserts 400 exactly. |
| 1 | R9 | SHOULD | Scripted generator not reset in `RedirectIT` | Fixed. Autowired; `reset()` in `@BeforeEach` and `@AfterEach`. |
| 1 | R11 | NIT | Tomcat probe registry | Fixed. `Registry.disableRegistry()` before `new Tomcat()`, imported. |
| 1 | R13 | NIT | Hand-built Basic tokens, `new ShortUrlTestData(jdbc)`, "again" step | Fixed. Added `ApiClient.rawBasicHeader(login, password)` (named so, not `basicHeader`, because `basicHeader(String, String)` already exists as login plus test user and the two signatures clash); used in `RedirectIT`, `RedirectSteps` and `SecurityIT` (both `send` helpers). `SecurityIT` reuses `data`. The "again" step calls `postAsCaller` directly. |
| 1 | R5 | SHOULD | Source-scan test brittle (relative path, comment guessing, `doesNotContain("URI")`) | Fixed. Went with reflection as the primary proof (`shouldTakeOnlyTheCodeAndReturnAResponseEntity...`: parameter types are exactly `String`, one parameter with one annotation, return type `ResponseEntity`), so the handler cannot read the query string or take `RedirectAttributes`/`HttpServletRequest`. The scan is kept as a secondary guard, renamed `shouldNotUseAnyLocationRewritingApiInTheControllerSource`; it looks only for `java.net.URI`, `.location(`, `setLocation`, `sendRedirect`, `"redirect:`, `RedirectView`, `RedirectAttributes` and `toASCIIString`, and resolves the path from `basedir` (falling back to `user.dir`) with an existence assertion. |
| 1 | R6 | SHOULD | Inline fully qualified names | Fixed. `RequestMethod` and `Mockito.verifyNoMoreInteractions` are imported; `verifyNoMoreRepositoryCalls` is deleted. The `jakarta.transaction.Transactional` FQN stays (clashes with Spring's). |
| 1 | R10 | NIT | Lone surrogate encodes to `%3F` | Pinned. New encoder row `a\uD800b` gives `a%3Fb`, with a comment that D11 makes it unreachable. Behaviour unchanged. |
| 1 | R12 | NIT | Decision IDs in public OpenAPI text; duplicated `PROBLEM_JSON` | Fixed. D75, D76, D79 and D55 are removed from the `@Operation` description and kept in the class Javadoc. `PROBLEM_JSON` is removed from both controllers in favour of `MediaType.APPLICATION_PROBLEM_JSON_VALUE` (long `@Content` lines wrapped in `ShortUrlController`, no other change there). No `OpenApiDocsIT` assertion depends on the description text. |
| 1 | R14 | NIT | Service test gaps | Fixed. The not-found test verifies `rollback(transactionStatus)` once and no `commit`. The reason-log test is parameterised over `NOT_FOUND`, `DEACTIVATED` and `DELETED`. The redundant `getDeclaredMethods().length > 0` clause is removed. |
| 2 | R1 | BLOCKING | D84: reject a URL whose D75-encoded form exceeds 2048 bytes | Fixed. New public `validation/LocationEncoder.encode` (D75 code moved unchanged from `RedirectController.locationHeaderValue`); `RedirectController` calls it. `UrlValidator.MAX_ENCODED_BYTES = 2048` (one shared constant, D84) is checked after the D11 length and encodability checks and before parsing, so it can only reject more. `ShortUrlService.create` already maps any `UrlValidator` rejection to 400 `INVALID_URL`, so it is unchanged. Tomcat's `max-http-response-header-size` is unchanged. Tests: `LocationEncoderTest` (all encoder rows moved, none lost), `UrlValidatorTest` (table: ASCII, CJK, emoji, e-acute at exactly 2048 accepted and 2049 rejected, with `hasSize` on the encoded form), `ShortUrlServiceTest` (encoded-over-limit gives `InvalidUrlException` with no repository or generator access). Follow-up: `GlobalExceptionHandler.URL_RULE` (client-facing 400 detail) now also states the byte limit, built from `UrlValidator.MAX_LENGTH` and `UrlValidator.MAX_ENCODED_BYTES`, with no decision IDs; no test asserted the literal text. |
| 2 | R1 (QA, create) | BLOCKING | D84 at the create API | Tested, passing. `support/CreateShortUrlEncodedLimitIT` (5 tests): CJK and emoji URLs at exactly 2048 encoded bytes give 201 and one row; the same at 2049 give 400 `INVALID_URL` (field `originalUrl`, no `Location`, zero rows in the table and by value); 900 CJK characters (over 8000 encoded bytes) gives 400. Each first asserts `LocationEncoder.encode(url)` `hasSize` and that the URL is at most 2048 characters, so only D84 can be the cause. `create-short-url.feature`: two outlines (accepted at 2048, refused at 2049, CJK and emoji) with new steps in `CreateShortUrlSteps`. New helper `support/EncodedUrls`.
| 2 | R1 (QA, redirect) | BLOCKING | Redirect at the D84 maximum | Tested, passing. `RedirectIT` +4 (91 to 95): GET and HEAD of a 2048-encoded-byte URL (CJK, 225 characters plus ASCII padding) created through the API give 302, one `Location` of length 2048 equal to `LocationEncoder.encode(stored)` (string and ASCII bytes), `Cache-Control` exactly `no-store`, and no `HeadersTooLargeException` or ` ERROR ` in the captured output. Positive control: a raw-SQL-seeded 2028-CJK row (D84 bypassed) gives 500 and the same capture contains `HeadersTooLargeException`. A 900-CJK-character create gives 400 (replaces the pin at 900 characters). No `Location` or URL is printed.
| 2 | R1 (QA, existing tests) | INFO | Existing QA tests that D84 could reject | Checked, no change needed. The QA tests that create URLs through the API use ASCII (2048-character `a` padding in `CreateShortUrlIT`, `RedirectIT`, the create feature) or short non-ASCII (`RedirectIT` `nonAsciiUrls`), all at most 2048 encoded bytes. The only non-ASCII test near the limit (`ShortUrlConstraintsTest`, 2028 `é`) is a mid-engineer repository test that inserts through JPA, not the API, so D84 does not apply. No expectation changed.
| 1 | R7 | SHOULD | Docs didn't match the code: architecture markers, a stale "pending G2" note, and the design's claim that HEAD matches GET on every row | Fixed by the orchestrator: markers set to "implemented (US-008)", D75 wording updated, and an erratum added to the design note (HEAD on multi-segment infrastructure paths gets 401/403, because rules 2 and 3 permit GET only) |
| 1 | R8 | SHOULD | An authenticated `GET /actuator` (the discovery links page) returns 200 to any USER | Fixed by the orchestrator: added to US-014's carry-over (disable discovery or require ADMIN on the exact path), together with the HEAD-on-health note |
| 1 | R15 | NIT | No blank line before the US-014 carry-over heading | Fixed by the orchestrator |
| 3 | R1–R15 | — | Final re-review of the whole story (fix rounds 1 and 2) | **Resolved**. Verdict **APPROVE**. Every original guardrail holds |
| 3 | N1 | SHOULD | `UrlValidatorTest.shouldCountCodePointsNotUtf16UnitsOrBytesForShortMultibyteUrls` passes under every counting method; D84 makes the difference unobservable, so the name overclaims | Open: no fix rounds left; engineer decides (it touches a Done story's test) |
| 3 | N2 | SHOULD | The raw-SQL positive control doesn't assert that the oversized URL is absent from Tomcat's ERROR output | Open. The orchestrator checked the final build log by hand: 1 `HeadersTooLargeException`, and 0 occurrences of the URL text |
| 3 | N3 | SHOULD | Traceability gaps: R7/R8/R15 rows, the US-004 per-test list, final counts, frontmatter | Fixed by the orchestrator (these rows, the US-004 note, the counts below, frontmatter D80–D84) |
| 3 | N4–N7 | NIT | A long Javadoc line in `RedirectController`; `verify(service).resolve` on two body-less slice 404s; the `LocationEncoderTest` 2048-character case builds 2024, and `String.format` lacks `Locale.ROOT`; `@throws NullPointerException` on `LocationEncoder.encode` | Open |

**Orchestrator final verification (2026-09-29):**
- `./mvnw -q clean verify` passed (exit 0).
  - Surefire: **702** run, 0 failed, 0 errors, 0 skipped.
  - Failsafe: **454** run, 0 failed (includes 130 Cucumber scenarios).
  - Merged LINE coverage: **449/451 (99.56%)**.
- The build log has exactly one `HeadersTooLargeException`, from QA's intentional raw-SQL control, and the oversized URL text does not appear in it.
- Tomcat's `max-http-response-header-size` is unchanged.

### Proposed review rules (senior-engineer, US-008; for the engineer to decide)
Round 1:
1. "Header budget: any value the application writes into a response header from stored or user data must have a proven maximum encoded size below `server.max-http-response-header-size`. The proof is a test at the D11 length limit using the worst-case encoding."
2. "HEAD 404s in Cucumber: a Cucumber HEAD scenario that expects 404 must include a 302 on the same path and a GET on the same path that asserts `errorCode`, the same as the `*IT` rule."
3. "Recorded status tables: a table of recorded statuses that includes 404 rows must also assert the `errorCode` of each 404 (via GET for HEAD rows). Allowing more than one status (`isIn(...)`) is only allowed while the status is being recorded, and must be replaced by the observed value before review."
4. "Guardrails on the code itself: prefer reflection or behaviour tests over reading `.java` source files. Source scans are allowed only as a secondary check, must look for specific tokens, and must not depend on the working directory."

Final:

5. "A test whose name claims to tell two behaviours apart (for example code points versus UTF-16 units) must include an input on which they differ. If a later rule makes the difference unobservable, rename or remove the test, and record that."
6. "Any change to a Done story's tests is listed test by test (updated, renamed, removed, added) in that story's post-completion section."
7. "A stored value that is echoed into a response header needs a create-time byte limit on its exact wire form, tested at the boundary through the real servlet container. A mocked slice is not enough."
8. "Every review-finding ID raised in a round has a row in the story's Review log with its resolution, including the ones the orchestrator fixes."

**G3 (2026-09-30):** approved by the engineer. D84 stays application-only (D85). The `URL_RULE` text update is accepted as part of R1. N1, N2 and N4–N7 are carried into US-009. Status: **Done**.

## Post-completion change (engineer-approved at the US-010 G2, Q2; D9, D12, D93)
- US-010 makes GET record a click. The redirect handler gains an `HttpMethod` parameter: GET calls `RedirectService.resolveAndRecordClick`, and HEAD keeps calling `resolve`, which never records. `RedirectService` gains `ClickRecorder` and `Clock` constructor arguments. The 302, `Location` and `Cache-Control: no-store` are unchanged.
- The story's status stays **Done**. The test changes are listed here one by one (mid-engineer, US-010). QA-owned US-008 tests (`RedirectIT`, `redirect.feature`) are changed by the qa-tester and listed in US-010's QA notes.
- **`api/RedirectControllerTest`**
  - **Updated:** `shouldNeverDeclareProducesOnTheClassOrTheMethodOrInheritOne` and `shouldCarryNoMetaAnnotationThatDeclaresProduces` look the handler up as `getDeclaredMethod("redirect", String.class, HttpMethod.class)`. Their assertions are unchanged.
  - **Updated and renamed:** `shouldTakeOnlyTheCodeAndReturnAResponseEntitySoTheQueryStringIsNeverReachable` is now `shouldTakeOnlyTheCodeAndTheHttpMethodAndReturnAResponseEntitySoTheQueryStringIsNeverReachable`. It asserts parameter types exactly `[String, HttpMethod]` and a count of 2, parameter 0 carries exactly one annotation and it is `@PathVariable`, parameter 1 carries none, and the return type is `ResponseEntity`.
  - **Updated:** `shouldNotUseAnyLocationRewritingApiInTheControllerSource` also asserts the code does not contain `HttpServletRequest`.
- **`api/RedirectControllerWebMvcTest`**
  - **Updated (GET stubs and verifies move from `resolve` to `resolveAndRecordClick`):** `shouldReturn302WithTheExactLocationAndNoStoreForAnonymousGet`, `shouldPercentEncodeNonAsciiCharactersInTheLocationHeader`, `shouldReturn302ForAValidAuthenticatedCallerToo`, `shouldIgnoreTheQueryStringOfTheShortLink` (stub and verify), `shouldReturn404ProblemJsonWithSecurityDefaultCacheControlWhenTheServiceThrowsNotFound`, `shouldReturn404ProblemJsonForEveryParseableAcceptNeverNotAcceptable`, `shouldReturn404ProblemJsonWhenNoAcceptHeaderIsSent`, `shouldReturn404WithAnEmptyBodyForAnUnparseableAccept` (stub and verify), `shouldPassEveryMalformedSingleSegmentToTheServiceAndReturnItsNotFound` (stub and three verifies), `shouldReturn404ShortUrlNotFoundForAuthenticatedApiNeverA302`, `shouldReturn500LogOnceAtErrorAndNeverLogTheTargetWhenTheDatabaseFails`.
  - **Updated (both halves stubbed):** `shouldReturn302ForAnyAcceptHeaderOnGetAndHead` stubs `resolveAndRecordClick` for the GET half and keeps `resolve` for the HEAD half.
  - **Unchanged (HEAD only):** `shouldReturn302WithTheSameHeadersForAnonymousHead`, `shouldReturn404ForHeadWhenTheServiceThrowsNotFoundWhileTheSamePathRedirectsOtherwise`, and the tests that never reach the service.
  - **Added:** `shouldCallOnlyResolveAndRecordClickForGet` and `shouldCallOnlyResolveForHeadAndNeverRecordAClick` (`verify` plus `never()` through real Spring HEAD routing).
- **`service/RedirectServiceTest`**
  - **Updated:** the constructor call gains a `ClickRecorder` mock and a fixed `Clock`. The `stored` fixture now sets the entity `id` with `ReflectionTestUtils`, because `create` leaves it null and the lookup needs it. `shouldNotBeTransactionalAtClassOrMethodLevel` adds `resolveAndRecordClick` to its non-vacuity names. No existing assertion changed.
  - **Added:** `shouldRecordTheResolvedIdAndTheClockInstantOnlyAfterTheReadTransactionCommits`, `shouldNeverTouchTheRecorderOrTheClockWhenResolvingWithoutRecording`, `shouldNotRecordAClickWhenTheLinkDoesNotRedirect` (4 cases), `shouldFailOpenAndLogOnlyCodeIdClassAndSqlStateWhenTheRecorderFails`, `shouldLogSqlStateNoneWhenNoPostgresExceptionIsInTheChain`, `shouldFailOpenWhenTheClockItselfFails`, `shouldNotSwallowAnErrorFromTheRecorder`, `shouldNotLogAnythingWhenARecordedClickSucceeds`.
- **QA-owned tests changed by the qa-tester (US-010)**
  - **`support/RedirectIT`:** `shouldWriteNothingOnGetOrHead` is replaced by `shouldWriteNothingOnHeadWhileTheSamePathGetIsCounted`. Two HEADs leave the whole row (`rowState`) equal and 0 `click_event` rows (both 302, so the handler ran). A GET on the same path then gives `click_count` 1, `last_accessed_at` set and 1 event, while `version`, `updated_at`, `created_at` and `status` stay equal. The old positive control (`seedClicks`) is gone because the GET is now the control. No other `RedirectIT` test changed.
  - **`features/redirect.feature`:** the scenario "Following a link changes nothing in the stored row" (comment "until click counting in US-010") is renamed "Sending HEAD for a link changes nothing in the stored row, while a GET is counted". It sends HEAD twice and asserts the row unchanged, then follows the link with GET and asserts 1 click with `version` and `updated_at` unchanged. New step `RedirectSteps.theStoredRowHasOneClick...`. The step `theStoredRowIsUnchanged` is untouched.
  - **`support/NoTransactionalAnnotationIT`:** the non-vacuity list adds `JpaClickRecorder`.
  - **Shared support (no US-008 assertion changed):** `IntegrationTestBase` imports `TestClockConfiguration` and registers `TestClockResetExtension`; `ShortUrlTestData` gains click helpers; `GetShortUrlSteps` gains one step ("the details response shows {int} clicks"). `RedirectIT` timing windows and every other US-008 test pass unchanged against real time.
- **QA-owned test edits made in US-011 (qa-tester)**
  - **`support/RedirectIT`:** helper `stableHeaders` renamed `headersExceptDate` (Javadoc now says Content-Length is kept, unlike `ApiClient.stableHeaders`). The call sites follow in `shouldAnswerHeadWithTheSameStatusAndHeadersAsGetApartFromDate` (its one assertion line is also wrapped to stay within 120 characters), `shouldReturnByteIdentical404ForAbsentDeactivatedAndDeletedCodeOnTheSameGetPath`, `shouldReturnIdentical404ForAbsentDeactivatedAndDeletedCodeOnTheSameHeadPath` and `shouldReturnTheSame404BodyExceptInstanceForMalformedCodes`. Renamed helper and line wrap only, no assertion changed.
  - **`cucumber/RedirectSteps`:** the same rename (private helper plus its call sites) in the steps `isTheSameProblemAsForAnUnknownCode` and `hasTheSameHeadersAsTheRememberedOne`; the helper Javadoc is the same one-liner. No assertion changed.

## Post-completion change (engineer-approved at the US-016 G2; D106–D127; main session, US-016)

- `OpenApiDocsIT.shouldDocumentExactlyThe302And404ResponsesWithNoImplicit200` — **renamed** to `shouldDocumentExactlyThe302404And410ResponsesWithNoImplicit200` and **updated**: the 410 response is documented as problem+json.

## Post-completion change (engineer-approved at the US-014 G2; D129–D131; main session, US-014)

- `RedirectIT.shouldRecordTheTrailingSlashBehaviourAs401AnonymousAnd403Authenticated` — **removed** and **replaced** by `shouldReturn404ResourceNotFoundWithoutALoginPromptForTheTrailingSlashVariant` (D80, US-014 H8).
- `RedirectIT.shouldRouteInfrastructurePathsToTheirOwnHandlersForAnonymousAndAuthenticatedCallers` — **updated**: direct `GET /error` is 404 `RESOURCE_NOT_FOUND`, no longer 500 (D82, H9).
- `RedirectIT.shouldRefuseAnonymousReservedPrefixesAndSendAuthenticatedApiToTheRedirectNotFound` — **updated**: a USER's `GET /actuator` is 403 `ACCESS_DENIED`; an ADMIN still gets the links page (H6).
- `RedirectIT.shouldGiveHeadTheRecordedStatusOnEveryRoutingPath` — **updated** rows: `/error` 404/404 (H9), `/actuator/health` 200/200 (H7), `/actuator` 401/403 (H6); `/error` 404s assert `RESOURCE_NOT_FOUND`.
- `RedirectIT.headersExceptDate` and `RedirectSteps.headersExceptDate` (helpers) — **updated**: also ignore `X-Request-Id`.
- `RedirectServiceTest` — **updated**: the `RedirectService` constructor takes a `MeterRegistry` (H13) in `setUp`, `shouldNeverTouchTheRecorderAndReadTheClockOnceWhenResolvingWithoutRecording` and `shouldFailTheRedirectWithoutRecordingOrLoggingTheUrlWhenTheClockFails`; a `SimpleMeterRegistry` field was added.
