---
id: US-009
title: Deactivate, reactivate, and soft delete
status: Done
plan_task: 8
depends_on: [US-002, US-005, US-006, US-007]
requirements: [FR-6, D1, D3, D4, D13, D26, D31, D34, D35, D36]
requires_design_approval: true
---

# US-009: Deactivate, reactivate, and soft delete

## User story
As the owner of a short URL, I want to deactivate or reactivate it, and as an ADMIN, I want to delete any short URL, so that links can be managed over their lifetime without losing audit history.

## Acceptance criteria
- **AC1:** Given the caller owns an `ACTIVE` link, when `PATCH /api/v1/urls/{code}` is called with body `{"active": false}` (D34), then the response is `200 OK` with `status: "DEACTIVATED"`.
- **AC2:** Given the caller owns a `DEACTIVATED` link, when `PATCH /api/v1/urls/{code}` is called with body `{"active": true}` (D34), then the response is `200 OK` with `status: "ACTIVE"`.
- **AC3:** Given the caller is an authenticated `USER` who does not own the link, when `PATCH /api/v1/urls/{code}` is called, then the response is `404 Not Found` with `errorCode: "SHORT_URL_NOT_FOUND"` (D4 — same ownership-hides-existence rule as GET).
- **AC4:** Given the caller has role `ADMIN`, when `PATCH /api/v1/urls/{code}` is called for any link regardless of owner, then the request is processed normally (`200 OK`).
- **AC5:** Given the caller has role `ADMIN`, when `DELETE /api/v1/urls/{code}` is called on an existing, non-deleted link (`ACTIVE` or `DEACTIVATED` — D36 explicitly permits deleting a `DEACTIVATED` link), then the response is `204 No Content`, the row's `status` becomes `DELETED`, and `deleted_at`/`deleted_by` are set.
- **AC6:** Given the caller has role `USER` (whether or not they own the link), when `DELETE /api/v1/urls/{code}` is called, then the response is `403 Forbidden` with `errorCode: "ACCESS_DENIED"` — per D3, only `ADMIN` may delete, regardless of ownership.
- **AC7:** Given `{code}` does not exist, when `PATCH /api/v1/urls/{code}` is called by an authenticated caller, or `DELETE /api/v1/urls/{code}` is called by `ADMIN`, then the response is `404 Not Found` with `errorCode: "SHORT_URL_NOT_FOUND"`.
- **AC8:** Given `{code}` belongs to an already-`DELETED` link, when `PATCH` is called by any caller including `ADMIN`, or `DELETE` is called by `ADMIN`, then the response is `404 Not Found` (D13, D36 — a deleted link accepts no further lifecycle transitions). The 404 outcomes in AC7 and AC8 for `DELETE` apply only to an `ADMIN` caller: because the role check precedes the existence lookup, a `USER` calling `DELETE` on any `{code}` — whether it exists, is missing, is deactivated, or is already deleted — always receives `403 Forbidden` with `errorCode: "ACCESS_DENIED"` (per AC6), never `404`. The 403 therefore reveals nothing about whether the code exists.
- **AC9:** Given `{code}` belongs to an already-`DEACTIVATED` link, when the owner (or `ADMIN`) calls `PATCH /api/v1/urls/{code}` with body `{"active": false}`, then the response is `409 Conflict` with `errorCode: "SHORT_URL_ALREADY_DEACTIVATED"` (D26).
- **AC10:** Given `{code}` belongs to an already-`ACTIVE` link, when the owner (or `ADMIN`) calls `PATCH /api/v1/urls/{code}` with body `{"active": true}`, then the response is `409 Conflict` with `errorCode: "SHORT_URL_ALREADY_ACTIVE"` (D26).
- **AC11:** Given two concurrent `PATCH /api/v1/urls/{code}` requests both attempt to deactivate the same `ACTIVE` link (no client-supplied version/`If-Match` — D35 uses server-side `@Version` only), when both execute concurrently, then exactly one succeeds with `200 OK` (`status: "DEACTIVATED"`) and the other fails with `409 Conflict` (see Open questions for which `errorCode` the loser may receive).
- **AC12:** Given no credentials are supplied, when `PATCH /api/v1/urls/{code}` or `DELETE /api/v1/urls/{code}` is called, then the response is `401 Unauthorized` with `errorCode: "AUTHENTICATION_REQUIRED"` (D31, per US-005).

## Tests required
| Type | Behaviour proven | Owner |
|---|---|---|
| Unit | State-transition and ownership/role authorization logic (AC1–AC6, AC9, AC10) | mid-engineer |
| Web slice | Status codes and errorCodes wired through the controller for all AC1–AC10 and AC12 | mid-engineer |
| Cucumber | Owner deactivates their own `ACTIVE` link: 200, `status: "DEACTIVATED"` (AC1) | qa-tester |
| Cucumber | Owner reactivates their own `DEACTIVATED` link: 200, `status: "ACTIVE"` (AC2) | qa-tester |
| Cucumber | Non-owner `USER` PATCHes another's link: 404 `SHORT_URL_NOT_FOUND` (AC3) | qa-tester |
| Cucumber | `ADMIN` PATCHes any link regardless of owner: 200 (AC4) | qa-tester |
| Cucumber | `ADMIN` deletes an `ACTIVE` link and a `DEACTIVATED` link: both 204 (AC5) | qa-tester |
| Cucumber | `USER` calls `DELETE`: 403 `ACCESS_DENIED` (AC6) | qa-tester |
| Cucumber | `PATCH`/`DELETE` on a nonexistent `{code}`: 404 `SHORT_URL_NOT_FOUND` (AC7) | qa-tester |
| Cucumber | `PATCH`/`DELETE` on an already-`DELETED` link: 404 (AC8) | qa-tester |
| Cucumber | `PATCH {"active": false}` on an already-`DEACTIVATED` link: 409 `SHORT_URL_ALREADY_DEACTIVATED` (AC9) | qa-tester |
| Cucumber | `PATCH {"active": true}` on an already-`ACTIVE` link: 409 `SHORT_URL_ALREADY_ACTIVE` (AC10) | qa-tester |
| Cucumber | No credentials on `PATCH`/`DELETE`: 401 `AUTHENTICATION_REQUIRED` (AC12) | qa-tester |
| Integration (`*IT`) | Real ownership/role/deleted-visibility scenarios against Testcontainers PostgreSQL (AC3, AC4, AC6, AC8) | qa-tester |
| Integration (`*IT`) | Two simultaneous `PATCH` deactivation requests on the same row produce exactly one 200 and one 409 via optimistic locking (AC11) | qa-tester |
| Web slice | A `USER` calling `DELETE` on a missing or already-deleted `{code}` still receives `403`/`ACCESS_DENIED`, not `404`, proving the role check precedes the existence lookup (AC8) | mid-engineer |

## Out of scope
- Hard delete / data purge — never implemented per D1.
- Expiration as a lifecycle state — deferred to Scenario 2/3 (US-012, US-013).
- Client-supplied conflict detection (`If-Match`/`ETag`) — explicitly deferred by D35 until `expiresAt` becomes editable.

## Risks
- Correctness interaction with US-010: US-010 increments `click_count`/`last_accessed_at` via an atomic SQL `UPDATE` that does not touch `version` (D27), while this story's `PATCH` loads the `ShortUrl` entity and flushes it back. Because `click_count`/`last_accessed_at` are mapped `insertable=false, updatable=false` (D27, US-002), a `PATCH`'s save cannot clobber a concurrent click's values, and a concurrent click cannot cause a spurious `409` on an in-flight `PATCH`. This story's `PATCH` implementation must not reintroduce a full-column overwrite that bypasses that mapping.

## Open questions
- In the two-concurrent-deactivations test (AC11), the losing request may receive `409 CONCURRENT_MODIFICATION` (true optimistic-lock conflict) or `409 SHORT_URL_ALREADY_DEACTIVATED` (if the requests serialise and the second sees the committed state). D35 requires one `200` and one `409` but does not say which `errorCode`. Proposed: the test asserts status `409` and accepts either `errorCode` — engineer to confirm.
- Note on something intentionally *not* flagged as open: whether a `USER` calling `DELETE` on their *own* link should get `403` or `404` is already decided — `docs/architecture.md`'s REST table lists `403` as a `DELETE` error and D3 states only `ADMIN` may delete, with no ownership exception. AC6 reflects this; it is not ambiguous.

## Design inputs carried from US-006 (engineer-approved at the US-006 escalation)
- PATCH and DELETE live on `ShortUrlController` and inherit its class-level `produces = application/json` (D70). An unacceptable `Accept` returns 406 **before** any state change. DELETE returns 406 too, even though it has no body.
- PATCH returns a body, so it needs a real-HTTP IT that asserts 406 **and** no state change (the version is unchanged), with a positive control.

## Carry-over from US-008 (engineer-approved at US-008 G3)
- **N1 (SHOULD, mid-engineer; the engineer approved changing a Done-story test):** `UrlValidatorTest.shouldCountCodePointsNotUtf16UnitsOrBytesForShortMultibyteUrls` passes under every counting method, because D84 makes the difference unobservable. Rename it to what it proves, for example `shouldAcceptAShortUrlOfSupplementaryCharacters`, or delete it as a duplicate of `shouldAcceptValidSupplementaryCharacterInPath`. Add a comment in `UrlValidator.isValid` saying that D84 implies the D11 count, and that the D11 check stays as the cheap limit before encoding. List the change test by test in US-004's post-completion section.
- **N2 (SHOULD, qa-tester):** the raw-SQL positive control in `RedirectIT` (around lines 242–252) must also assert that the oversized URL is absent from the captured output. Its comment should say it pins the D85 gap.
- **N4 (NIT, mid-engineer):** wrap the 171-column Javadoc line in `RedirectController` (around line 30).
- **N5 (NIT, mid-engineer):** add `verify(service).resolve(CODE)` to the body-less unparseable-`Accept` and HEAD 404 slice tests in `RedirectControllerWebMvcTest`.
- **N6 (NIT, mid-engineer):** `LocationEncoderTest.shouldLeaveA2048CharacterAsciiUrlByteIdentical` builds 2024 characters. Make it exactly 2048 with `hasSize(2048)`, and use `String.format(Locale.ROOT, …)`.
- **N7 (NIT, mid-engineer):** add `@throws NullPointerException if target is null` to `LocationEncoder.encode`.

## Design note

*Architect, 2026-09-30. Status: **approved at G2 (2026-09-30), with Q1–Q5 recorded as D86–D90. Q4 uses the orchestrator's strict-boolean option (D89), not the Jackson default. *Amendment (orchestrator, from implementation and QA):* §1.1's statement that `"false"`/`0`/`1` are coerced is superseded by D89 (they get 400 `MALFORMED_REQUEST`). §1.3's `Allow` header is actually `GET, DELETE, PATCH` (Spring leaves out the implicit HEAD). §13's jackson-databind 2.19 citation: 2.21.4 is resolved from the Boot BOM, and the behaviour is pinned by tests on that version.**. Gate IDs L1–L8 and question IDs Q1–Q5 are for this note only. Once the engineer decides, the orchestrator records the outcomes as D86 onward. Code, tests and SQL must cite those `Dnn` IDs, never `L1`, `Q1` or section numbers (CLAUDE.md review rule). **No migration**: V1 already has `status`, `deleted_at`, `deleted_by`, `updated_at`, `version` and `ck_short_url_deleted_consistency` (D44). **`SecurityConfig` is unchanged**: rules 5 and 6 already admit exactly what this story needs.*

### 0. Engineer decisions required at G2

| # | Decision | Recommendation | Blocking? |
|---|---|---|---|
| **L1** | Which `errorCode` the loser of two concurrent deactivations gets (story open question, AC11). | **Either `CONCURRENT_MODIFICATION` or `SHORT_URL_ALREADY_DEACTIVATED`, depending on timing.** The race test accepts both. Two deterministic tests pin each path (§3). | **Yes.** It fixes what AC11's tests assert. |
| **L2** | Where the optimistic-lock failure becomes 409. | **In the service.** Catch Spring's `OptimisticLockingFailureException` **outside** the read-write template, then throw a new `ShortUrlConcurrentModificationException`, which the advice maps (§2.3, §4). | **Yes.** It fixes the exception contract. |
| **L3** | Where the flush happens. | **An explicit `repository.flush()` inside the template callback**, so a conflict surfaces there, before commit. The catch also covers a commit-time failure (§2.3). | No |
| **L4** | DELETE also returns `409 CONCURRENT_MODIFICATION` when it races with another change (D35). | **Yes, and it is documented.** This adds 409 to DELETE's documented responses, which the task list did not include (§3.4, §5). | **Yes.** It changes the DELETE contract. |
| **L5** | A service-side ADMIN guard on `delete` (defence in depth behind rule 5). | **`IllegalStateException` (so 500) if `!caller.admin()`**, checked before any lookup. It cannot be reached over HTTP (§2.4). | No |
| **L6** | PATCH `consumes = application/json`, so other types (including `application/merge-patch+json`) get 415. | **Yes.** Document 415, as create does (§1.1). | No |
| **L7** | The PATCH request type. | **`UpdateShortUrlRequest(@NotNull Boolean active)`**, a named resource-update record that US-013 can extend with `expiresAt` (§1.1). | No |
| **L8** | `@Transactional` stays forbidden. | **A third template, `readWrite` (REQUIRED, read-write), built in the `ShortUrlService` constructor.** The existing reflection guard stays unchanged (§2.2). | No |

### 1. API contract

#### 1.1 `PATCH /api/v1/urls/{code}` (D34)

```java
// api/dto/UpdateShortUrlRequest.java
/**
 * Body of PATCH /api/v1/urls/{code} (D34). Named for the resource, not the field, so that expiresAt can be
 * added later (US-013) without a second endpoint or type.
 *
 * @param active false deactivates the link, true reactivates it. Required: missing or JSON null gives
 *        400 VALIDATION_FAILED. A wrapper type, so that "missing" can be seen at all.
 */
public record UpdateShortUrlRequest(
        @Schema(description = "false deactivates the short URL, true reactivates it",
                requiredMode = Schema.RequiredMode.REQUIRED, example = "false")
        @NotNull(message = "must not be null") Boolean active) {
}
```

```java
// ShortUrlController
@PatchMapping(path = "/{code}", consumes = MediaType.APPLICATION_JSON_VALUE)
@Operation(...) @ApiResponse(...)                                       // section 5
ShortUrlResponse update(@PathVariable("code") String code, @Valid @RequestBody UpdateShortUrlRequest request,
        @Parameter(hidden = true) Authentication authentication) {
    return ShortUrlResponse.from(service.setActive(code, request.active(), callerOf(authentication)), links);
}
```

- **Filter-chain rule (CLAUDE.md rule):** **rule 6** (`/api`, `/api/**` → `hasRole(USER)`), with ADMIN passing through the hierarchy. Rule 5 matches DELETE only. Anonymous callers get `401 AUTHENTICATION_REQUIRED` from the entry point (AC12).
- **Content negotiation (D70):** the method inherits the class-level `produces = application/json` and declares no `produces` of its own. An unacceptable `Accept` gets 406 at mapping lookup, before the body is read or the service runs, so nothing changes. An unparseable `Accept` gets 406 with an empty body (the known D70 deviation).
- **Request content type (L6):** `consumes = application/json`. A missing or different `Content-Type` gets `415 UNSUPPORTED_MEDIA_TYPE`. That includes `application/merge-patch+json` (RFC 7396) and `application/json-patch+json` (RFC 6902). The body is a plain partial document, with D34's shape only.
- **Validation:**

  | Body | Result |
  |---|---|
  | `{"active": false}` / `{"active": true}` | Goes to the service |
  | `{}`, `{"active": null}` | `400 VALIDATION_FAILED`, `errors: [{"field": "active", "message": "must not be null"}]` (D56) |
  | `{"active": false, "x": 1}`, `{"expiresAt": "…"}` (until US-013), duplicate `active` key | `400 MALFORMED_REQUEST` (D59) |
  | Empty body, `null`, `[]`, `{"active": "maybe"}`, `{"active": {}}`, a syntax error | `400 MALFORMED_REQUEST`, with no parser text |
  | `{"active": "false"}`, `{"active": 0}`, `{"active": 1}` | **Coerced by Jackson's default scalar coercion** (`"false"` → false; `0` → false, any other integer → true). This is the same leniency US-006 accepted (US-006 K9). QA pins the actual behaviour. See Q4 |

  - Keep the constraint on the record component, and use `@Valid @RequestBody` only. A constraint on the parameter would switch to `HandlerMethodValidationException` (US-006 §1.2).
  - The message is a literal string, never a `{…}` key.
- **Response:** `200 OK`, `application/json`, `ShortUrlResponse.from(view, links)`. That is exactly the D58 body of create and GET, with `status` `DEACTIVATED` or `ACTIVE`. `clickCount` and `lastAccessedAt` are the values read in this transaction (D27: the entity never re-reads them). A click committed after the read isn't reflected, which is correct for a response describing this change.
- **Rules**, applied by the service in this order: D72 format → lookup → `DELETED` → owner or ADMIN (D4, D13, via `loadVisible`), then the domain transition (D26, D46):

  | Row state (visible to the caller) | `active: false` | `active: true` |
  |---|---|---|
  | `ACTIVE` | 200 `DEACTIVATED` (AC1, AC4) | 409 `SHORT_URL_ALREADY_ACTIVE` (AC10) |
  | `DEACTIVATED` | 409 `SHORT_URL_ALREADY_DEACTIVATED` (AC9) | 200 `ACTIVE` (AC2, AC4) |
  | `DELETED`, unknown, malformed, or not the caller's (USER) | 404 `SHORT_URL_NOT_FOUND` (AC3, AC7, AC8) | same |

- **Time:** `updated_at` comes from `clock.instant()`, read once per request and truncated to microseconds by the entity (D45).
- **Effect on the redirect (D2):** a deactivated link gets 404 on `/{code}`, and a reactivated one gets 302 again. The redirect reads the committed state, so no cache is involved.

#### 1.2 `DELETE /api/v1/urls/{code}` (D1, D3, D36, D46)

```java
@DeleteMapping("/{code}")
@ResponseStatus(HttpStatus.NO_CONTENT)
@Operation(...) @ApiResponse(...)                                       // section 5
void delete(@PathVariable("code") String code, @Parameter(hidden = true) Authentication authentication) {
    service.delete(code, callerOf(authentication));
}
```

- **Filter-chain rule:** **rule 5** (`DELETE /api/v1/urls/**` → `hasRole(ADMIN)`), which comes before rule 6. A USER gets `403 ACCESS_DENIED` from the access-denied handler **before the `DispatcherServlet`**, so no handler runs, no lookup happens, and the service is never called (AC6, AC8). The 403 is identical for existing, missing, deactivated and deleted codes, and for the USER's own links. It reveals nothing. Anonymous callers get 401 (AC12).
- **Response:** `204 No Content`, no body and no `Content-Type`. `@ResponseStatus` on a `void` handler is the simplest form, and springdoc reads it as the default response code.
- **Content negotiation (D70):** inherited. `Accept: application/xml` as ADMIN gets 406 at lookup, and the row is unchanged. As a USER, it gets 403, because security comes first.
- **Effect:** `softDelete(caller.username(), now)`:
  - `status = DELETED`;
  - `deleted_by` is `Authentication.getName()`, the configured lowercase admin username (D51, D54), guarded by `ShortUrl.MAX_ACTOR_LENGTH`;
  - `deleted_at = updated_at = clock.instant()` (D45);
  - `version + 1`.

  The single UPDATE writes all three D44 columns together, so `ck_short_url_deleted_consistency` holds.
- **Outcomes for ADMIN:**

  | Row | Result |
  |---|---|
  | `ACTIVE` or `DEACTIVATED` (any owner) | 204 (AC5, D36) |
  | `DELETED`, unknown, malformed | 404 `SHORT_URL_NOT_FOUND` (AC7, AC8, D46), the same body as every other 404 (D74) |
  | Changed concurrently | 409 `CONCURRENT_MODIFICATION` (§3.4, L4) |

- **After a delete (D1, D13):**
  - `GET` and `PATCH /api/v1/urls/{code}` give 404 for everyone. `DELETE` gives 404 for ADMIN.
  - `GET` and `HEAD /{code}` give 404 (US-008).
  - `POST /api/v1/urls` with `alias` equal to the code gives `409 ALIAS_ALREADY_EXISTS`, because the row, and so `uk_short_url_short_code`, is retained. The generator can never reuse the code either (US-006 AC6 variant). **No code change is needed for this.** QA proves it end to end.

#### 1.3 Precedence and path variants

**Precedence: 401 > 403 (rule 5, D57) > 405 > 415 > 406 > 400 (body) > 404 > 409.**
- Security runs in the filter chain first.
- `handleNoMatch` checks method, then consumes, then produces.
- Body parsing and validation happen during argument resolution, before the service runs. So a non-owner who sends `{}` gets `400 VALIDATION_FAILED`, not 404. That reveals nothing, because it is the same for every code.
- 404 and 409 come from the service.

| Request | Result | Why |
|---|---|---|
| `PUT`/`POST /api/v1/urls/{code}` | 405, `Allow` lists `GET`, `HEAD`, `PATCH`, `DELETE` (QA records the exact value) | The three mappings now share the path |
| `PATCH /api/v1/urls/{code}/` (alice) | 404 `RESOURCE_NOT_FOUND`, no change | Rule 6 admits it; there is no trailing-slash match |
| `DELETE /api/v1/urls/{code}/` (admin) | 404 `RESOURCE_NOT_FOUND`, no change | Rule 5 admits it; there is no trailing-slash match |
| `DELETE /api/v1/urls/{code}/` (alice) | 403 `ACCESS_DENIED` | Rule 5 (existing `SecurityIT` row, unchanged) |
| `DELETE /api/v1/urls` (admin / alice) | 405 with `Allow: POST` / 403 | `/**` matches zero segments (PathPattern); QA pins both |
| `PATCH` or `DELETE /API/v1/urls/{code}` | 403 `ACCESS_DENIED` | `denyAll` (D57) |

### 2. Service and transactions

#### 2.1 Types

| Type | Package | Change |
|---|---|---|
| `UpdateShortUrlRequest` | `api/dto` | New (§1.1) |
| `ShortUrlController` | `api` | `update` (PATCH), `delete` (DELETE), OpenAPI, and a class Javadoc naming rule 6 for PATCH and rule 5 for DELETE |
| `GlobalExceptionHandler` | `api/error` | Three new handlers, one extended handler, three `DETAIL` texts (§4) |
| `ShortUrlService` | `service` | `setActive(String, boolean, Caller)`, `delete(String, Caller)`, and a `readWrite` template. `loadVisible` is **reused unchanged** |
| `ShortUrlConcurrentModificationException` | `service/exception` | New. `RuntimeException(Throwable cause)` with the fixed message `"Short URL was modified concurrently"`. It carries no code. The name avoids `java.util.ConcurrentModificationException` |
| `ShortUrlResponse` | `api/dto` | Javadoc only ("shared by create, details and update") |
| `ShortUrl`, domain exceptions, `ShortUrlRepository`, `Caller`, `SecurityConfig`, `ErrorCode` | | **Unchanged.** The three 409 codes are already in the D31 catalogue |

The service takes a `boolean` rather than a command record. Entities don't leave the service, and one flag doesn't need a type. US-013 introduces a command record when `expiresAt` arrives. That change stays inside `api` → `service`.

#### 2.2 Transaction boundary (L8)

```java
// ShortUrlService constructor, next to requiresNew and readOnly
this.readWrite = new TransactionTemplate(transactionManager);   // PROPAGATION_REQUIRED, read-write, default isolation
```

```
Recommendation: PATCH and DELETE each run in one read-write TransactionTemplate (REQUIRED) built in the
                ShortUrlService constructor; no @Transactional anywhere.
Reason:         the load, the domain transition and the versioned UPDATE must share one transaction and one
                persistence context, so the entity is managed and Hibernate issues UPDATE ... WHERE version = ?.
                open-in-view=false and the non-transactional controller mean there is never an outer transaction,
                so REQUIRED always starts a fresh one; REQUIRES_NEW would add nothing. It matches create's and
                get's pattern and keeps the existing reflection guard (no @Transactional on any method) meaningful.
Alternative:    @Transactional on setActive/delete, with an exemption in the guard test; or a separate
                @Service writer bean.
Trade-off:      three templates on one service (requiresNew, readOnly, readWrite); a mocked
                PlatformTransactionManager in unit tests (already present). The alternatives either weaken the
                guard or add a class whose only job is an annotation.
```

Isolation is PostgreSQL's default, **READ COMMITTED**. That is what the §3 analysis assumes, and nothing configures it otherwise.

#### 2.3 Algorithm, flush point and the optimistic-lock catch (L2, L3)

```java
public ShortUrlView setActive(String code, boolean active, Caller caller) {
    Instant now = clock.instant();                                            // D45: once per request
    ShortUrlView view;
    try {
        view = readWrite.execute(status -> {
            ShortUrl url = loadVisible(code, caller);                         // D72, D13, D4 (404)
            if (active) {
                url.reactivate(now);                                          // D26: SHORT_URL_ALREADY_ACTIVE
            } else {
                url.deactivate(now);                                          // D26: SHORT_URL_ALREADY_DEACTIVATED
            }
            repository.flush();                                               // the versioned UPDATE runs HERE
            return ShortUrlView.from(url);
        });
    } catch (OptimisticLockingFailureException e) {                           // D35: flush-time or commit-time
        log.info("Short URL changed concurrently: code={} action={}", code, active ? "REACTIVATE" : "DEACTIVATE");
        throw new ShortUrlConcurrentModificationException(e);
    }
    log.info("Short URL {}: code={}", active ? "reactivated" : "deactivated", code);   // after commit only
    return view;
}
```

**Where the conflict surfaces (verified, §13):**
1. `repository.flush()` goes through the Spring Data proxy (`SimpleJpaRepository.flush`, `@Transactional`, joining ours) into `SessionImpl.flush`.
2. Hibernate executes `update short_url set deleted_at=?, deleted_by=?, status=?, updated_at=?, version=? where id=? and version=?`. It contains **no** `click_count`, `last_accessed_at` or creation-time columns (§2.5).
3. If 0 rows match, `ModelMutationHelper.identifiedResultsCheck` throws `StaleObjectStateException`. `SessionImpl.doFlush` converts it to `jakarta.persistence.OptimisticLockException`, with the Hibernate exception as its cause.
4. The repository proxy's exception translation (`HibernateJpaDialect.translateExceptionIfPossible`) unwraps the `PersistenceException` with a `HibernateException` cause and maps `StaleObjectStateException` to **`ObjectOptimisticLockingFailureException`**.
5. `TransactionTemplate.execute` catches the `RuntimeException`, rolls back and rethrows. The service catches it **outside** the template, after the rollback.

**Why flush explicitly (L3):**
- Without it, the UPDATE runs at commit, in Hibernate's managed flush. `SessionImpl.flushBeforeTransactionCompletion` sends failures through `ExceptionMapperStandardImpl.mapManagedFlushFailure`, which logs **`HHH000346: Error during managed flush` at ERROR**. An expected D35 conflict would then page on-call.
- The commit path's translation is also more roundabout: `RollbackException`, then `JpaTransactionManager.doCommit`, then `translateExceptionIfPossible`. It still yields an `OptimisticLockingFailureException`, but the explicit flush makes the flush point visible in the code and testable with a mock.
- Commit then has nothing left to flush.

**Why catch `OptimisticLockingFailureException`**, Spring's base class, and not the subclass:
- It covers `ObjectOptimisticLockingFailureException` (the Hibernate path).
- It also covers `JpaOptimisticLockingFailureException`, which `EntityManagerFactoryUtils` produces for a bare `jakarta.persistence.OptimisticLockException`.
- It covers the commit-time path in case something is ever dirtied after the flush.
- Never catch `jakarta.persistence.OptimisticLockException` in the service. The repository proxy and `JpaTransactionManager` always translate it first.

**Domain exceptions** (`ShortUrlAlreadyDeactivatedException`, `ShortUrlAlreadyActiveException`, `ShortUrlDeletedException`) are thrown inside the callback, before any UPDATE. The template rolls back an empty transaction and rethrows them unchanged to the advice. The row, `version` and `updated_at` are untouched.

**`ShortUrlDeletedException` in practice:**
- `loadVisible` already turns a `DELETED` row into `ShortUrlNotFoundException` before any transition, so the domain exception is unreachable through this service today.
- A concurrent delete is caught by `@Version`, not by the in-memory status (§3.4).
- The advice still maps it (D46). A future write path that skips `loadVisible` must still give the same 404.

#### 2.4 Delete

```java
public void delete(String code, Caller caller) {
    if (!caller.admin()) {
        // D3: filter-chain rule 5 makes this unreachable over HTTP; a failure here is a security misconfiguration.
        throw new IllegalStateException("delete requires ADMIN");
    }
    Instant now = clock.instant();
    try {
        readWrite.executeWithoutResult(status -> {
            ShortUrl url = loadVisible(code, caller);                        // ADMIN: malformed/unknown/DELETED -> 404
            url.softDelete(caller.username(), now);                          // D1, D36, D46, D51
            repository.flush();
        });
    } catch (OptimisticLockingFailureException e) {
        log.info("Short URL changed concurrently: code={} action={}", code, "DELETE");
        throw new ShortUrlConcurrentModificationException(e);
    }
    log.info("Short URL deleted: code={}", code);
}
```

```
Recommendation (L5): a service-side "caller must be ADMIN" guard that throws IllegalStateException before any lookup.
Reason:         D3 is then true of the service contract itself, not only of one URL rule. loadVisible alone would let an
                owner delete their own link if rule 5 were ever reordered or narrowed. The guard is unreachable over
                HTTP, so it adds no status to the contract; if it ever fires, the advice logs it once at ERROR (a
                misconfiguration that must be noticed) and returns a generic 500.
Alternative:    (a) no guard, relying on rule 5 alone; (b) a service exception mapped to 403 ACCESS_DENIED.
Trade-off:      a misconfiguration shows up as 500, not 403. (a) is one line shorter but lets a filter-chain regression
                silently widen delete to owners. (b) adds a type and an advice mapping for a path no request can reach.
```

#### 2.5 D16/D27: what PATCH and DELETE never write

- `click_count` and `last_accessed_at` are `insertable = false, updatable = false`. Per the Jakarta Persistence `@Column.updatable` contract, the column is not "included in SQL UPDATE statements generated by the persistence provider". Hibernate 6.6 builds its static UPDATE from the updatable columns only. There is no `@DynamicUpdate`, and none may be added.
- The creation-time columns (`short_code`, `original_url`, `custom_alias`, `created_by`, `created_at`) are `updatable = false` too.
- The existing `ShortUrlRepositoryTest.shouldNotOverwriteSqlUpdatedAnalyticsWhenEntityIsSaved` proves the mapping at repository level: a raw click-style UPDATE between load and flush survives, and `version` goes to 1.
- **Forbidden:** `save(...)`/`merge` of a hand-built or detached entity, any JPQL/SQL `UPDATE` in this story, `@DynamicUpdate`, and changes to the two column mappings.
- **US-010 interaction:**
  - US-010's click UPDATE (`SET click_count = click_count + 1, last_accessed_at = ?`, per D27 and US-010 AC6) never changes `version`. So a click committed between PATCH's read and its UPDATE doesn't break `WHERE version = N`, and **a click never causes a 409**.
  - If a click holds the row lock when PATCH's UPDATE arrives, PATCH waits. When the click commits, PostgreSQL re-evaluates `version = N` against the new row version, which still matches, and applies PATCH's SET to that new version. **The click's `click_count` is kept**, because PATCH doesn't write it. The reverse order is symmetric.
  - The concurrency IT proves both halves with a click-shaped raw UPDATE, ahead of US-010 (§7.3).
  - **Condition for US-010:** its UPDATE must stay a single atomic statement that doesn't touch `version` or `updated_at`.

### 3. Concurrency (AC11, D35)

PostgreSQL READ COMMITTED (verified in the PG 18 docs, §13):
- A `SELECT` sees only rows committed before it began, and never waits for writers.
- An `UPDATE` that finds its target row locked by an in-progress transaction **waits**. If that transaction rolls back, the UPDATE proceeds on the original row. If it commits, PostgreSQL re-evaluates the `WHERE` clause on **the updated version of the row** and applies the UPDATE only if it still matches.

#### 3.1 Two `PATCH {"active": false}` on an ACTIVE link (version N)

| Interleaving | Loser's read | Loser's UPDATE `… WHERE id = ? AND version = N` | Loser's response |
|---|---|---|---|
| **(a) Overlap.** Both SELECTs happen before the winner commits. Either both read before either UPDATE, or the loser reads after the winner's UPDATE but before its commit (the snapshot still shows N) | `ACTIVE`, version N | It waits on the winner's row lock. After the winner commits, it re-evaluates against version N+1, matches 0 rows, and gets `StaleObjectStateException` | **409 `CONCURRENT_MODIFICATION`** |
| **(b) Serialised.** The loser's SELECT starts after the winner commits | `DEACTIVATED`, version N+1 | Never issued: `deactivate` throws first | **409 `SHORT_URL_ALREADY_DEACTIVATED`** |

In both cases exactly one request gets 200. The row ends `DEACTIVATED` with version **N+1**: one write. `updated_at` is the winner's clock value. D35's "one 200 and one 409" holds either way. Only the `errorCode` depends on timing.

```
Recommendation (L1): the loser's errorCode is timing-dependent and both are correct; the AC11 race test asserts
                     statuses {200, 409} and errorCode in {CONCURRENT_MODIFICATION, SHORT_URL_ALREADY_DEACTIVATED};
                     two deterministic tests pin each path separately.
Reason:         both outcomes are truthful: (a) is a real optimistic conflict (D35), (b) is a redundant transition
                (D26). Forcing one code needs either a pessimistic lock or a retry, both of which D35 rules out.
                The deterministic tests make sure each mapping really works, so the accepting race test can't hide a
                broken path.
Alternative:    (1) SELECT ... FOR UPDATE on the write-path load: the loser always waits, then reads DEACTIVATED, so it
                always gets SHORT_URL_ALREADY_DEACTIVATED and CONCURRENT_MODIFICATION becomes unreachable for these
                operations. (2) On an optimistic failure, retry the whole load-and-transition once: the retry sees
                the committed state, so same-intent races give the D26 code.
Trade-off:      clients must treat both 409 codes as "someone else already changed it; re-read with GET". (1)
                contradicts D35 (optimistic @Version) and holds row locks across Java code. (2) quietly turns genuine
                conflicts into last-writer-wins, for example a DELETE racing a PATCH, which D35 says must be 409.
```

#### 3.2 Deterministic test (a): a lock held by a separate connection (the US-006 AC9 technique)

1. Seed alice's ACTIVE row and read its `version` N.
2. On a **separate** `DataSource` connection with `autoCommit=false`, simulate the winner:
   `UPDATE short_url SET status='DEACTIVATED', updated_at=now(), version=version+1 WHERE short_code=?`. **Don't commit.** The row is now locked.
3. `sendAsync` `PATCH {"active": false}` as alice. Its SELECT reads the committed version N without blocking. Its UPDATE then blocks.
4. Poll `pg_stat_activity` (up to 10 s) until another backend of this database shows `wait_event_type = 'Lock'`. Assert the future isn't done.
5. `commit()`. The request completes with **409 `CONCURRENT_MODIFICATION`**, with exactly the base keys. The row has version N+1 (the seed's single write) and status `DEACTIVATED`.
6. **Twin:** the same steps, but `rollback()`. The request completes with **200 `DEACTIVATED`**, and the row has version N+1. This proves the 409 came from the version predicate at UPDATE time, not from an application pre-check.

#### 3.3 Deterministic test (b): serialised

Send two `PATCH {"active": false}` one after the other: 200, then **409 `SHORT_URL_ALREADY_DEACTIVATED`**. The row has version N+1. This is AC9 reached through the API rather than raw SQL.

#### 3.4 DELETE racing with PATCH (L4)

| Race | Overlap (a) | Serialised (b) |
|---|---|---|
| PATCH commits first, DELETE loses | DELETE read version N, so its UPDATE matches 0 rows: **409 `CONCURRENT_MODIFICATION`** | DELETE reads `DEACTIVATED`: **204** (D36) |
| DELETE commits first, PATCH loses | PATCH read version N: **409 `CONCURRENT_MODIFICATION`** | PATCH reads `DELETED`: **404** |
| DELETE and DELETE | **409 `CONCURRENT_MODIFICATION`** | **404** |

- The in-memory status cannot tell that a delete has committed: the SELECT's snapshot shows the old status. `@Version` is what catches it. So the `DELETED` row never reaches `deactivate`, and `ShortUrlDeletedException` stays unreachable here (§2.3).
- A PATCH that loses to a DELETE gets **409, not 404**. Re-reading gives 404. See Q2.
- QA proves both overlap rows deterministically with the §3.2 technique: a held raw soft-delete UPDATE (with the three D44 columns) against PATCH, and a held raw deactivate UPDATE against an ADMIN DELETE.

#### 3.5 What cannot happen

- **No deadlocks.** Every write transaction locks exactly one row.
- **No lost updates.** Every entity write is versioned, and clicks are atomic increments.
- **No partial state.** The domain methods either throw before any change or change every affected field. The single UPDATE is atomic, and `ck_short_url_deleted_consistency` backstops the deleted fields (D44).
- `lock_timeout` is not set, so a PATCH waits as long as a lock is held. Writers hold row locks only for one short statement plus commit, and US-014 may add a statement timeout.

### 4. Errors

`GlobalExceptionHandler` changes (every body is built by `ProblemDetails.of`, `instance = request.getRequestURI()`, the raw path without a query):

```java
texts.put(ErrorCode.SHORT_URL_ALREADY_DEACTIVATED, "The short URL is already deactivated.");
texts.put(ErrorCode.SHORT_URL_ALREADY_ACTIVE, "The short URL is already active.");
texts.put(ErrorCode.CONCURRENT_MODIFICATION,
        "The short URL was changed by another request. Read it again and retry if still needed.");

/** D72, D13, D4, D46, D74: malformed, unknown, deleted, not-yours and a transition on a deleted link: one body. */
@ExceptionHandler({ShortUrlNotFoundException.class, ShortUrlDeletedException.class})
ResponseEntity<ProblemDetail> handleShortUrlNotFound(HttpServletRequest request) {
    return respond(plain(ErrorCode.SHORT_URL_NOT_FOUND, request));                // unchanged body construction
}

@ExceptionHandler(ShortUrlAlreadyDeactivatedException.class)                       // D26
ResponseEntity<ProblemDetail> handleAlreadyDeactivated(HttpServletRequest request) {
    return respond(plain(ErrorCode.SHORT_URL_ALREADY_DEACTIVATED, request));
}

@ExceptionHandler(ShortUrlAlreadyActiveException.class)                            // D26
ResponseEntity<ProblemDetail> handleAlreadyActive(HttpServletRequest request) {
    return respond(plain(ErrorCode.SHORT_URL_ALREADY_ACTIVE, request));
}

@ExceptionHandler(ShortUrlConcurrentModificationException.class)                   // D35; the service logged at INFO
ResponseEntity<ProblemDetail> handleConcurrentModification(HttpServletRequest request) {
    return respond(plain(ErrorCode.CONCURRENT_MODIFICATION, request));
}
```

- **D74 byte-identity for `ShortUrlDeletedException`:**
  - It is added to the **existing** handler's `@ExceptionHandler` list, so there is one method, one `plain(SHORT_URL_NOT_FOUND, request)` call and one fixed `detail`.
  - The handler takes no exception parameter, so the domain exception's message, which contains the code, cannot reach the body.
  - For the same path, a 404 from PATCH, DELETE or GET is byte-identical, whatever the cause (§7).
- **No handler for Spring's `OptimisticLockingFailureException`** (L2). It is translated in the service, like `DataIntegrityViolationException` in create, so the advice maps only domain and service types. If a future write path forgot the catch, the conflict would reach the catch-all as a logged 500. The service tests and the §3.2 IT make that visible, and the reviewer checks it (risk K3).
- **Bodies:** every new 409 has exactly the base keys (D56), with no `errors`, and `Content-Type: application/problem+json`. The advice logs none of them (they are client-visible outcomes, not faults), so nothing is logged at WARN or ERROR.
- **Unchanged and inherited:** 400 `VALIDATION_FAILED` (with `errors`) and `MALFORMED_REQUEST`; 405 `METHOD_NOT_ALLOWED` (keeps `Allow`); 406 `NOT_ACCEPTABLE`; 415 `UNSUPPORTED_MEDIA_TYPE`; 401 and 403 from the security writers; 500 `INTERNAL_ERROR` for anything else (logged once at ERROR, no internals).
- `ErrorCode` needs no change. `ErrorCodeTest` still pins the catalogue.

### 5. OpenAPI

Every error `@Content` uses `mediaType = "application/problem+json"` explicitly, with `ErrorResponseSchema` (D70, US-006 §5). The `code` parameter is documented as in GET (no `pattern`), and `Authentication` is hidden.

- **PATCH** `@Operation(summary = "Deactivate or reactivate a short URL")`. The description says:
  - body `{"active": false}` deactivates and `{"active": true}` reactivates;
  - only the creator or an ADMIN may do it, and anyone else gets the same 404 as for an unknown or deleted code;
  - a deactivated link's public redirect returns 404 until it is reactivated;
  - a redundant change gets 409;
  - `CONCURRENT_MODIFICATION` means another request changed the link at the same moment; read it with GET and retry if still needed.
  - `requestBody`: `application/json`, `UpdateShortUrlRequest`, `active` required.
  - Responses:
    - `200`: `ShortUrlResponse`, `application/json`
    - `400`: `VALIDATION_FAILED` or `MALFORMED_REQUEST`
    - `401`: `AUTHENTICATION_REQUIRED`
    - `404`: `SHORT_URL_NOT_FOUND` (indistinguishable causes)
    - `406`: `NOT_ACCEPTABLE` (create's wording)
    - `409`: `SHORT_URL_ALREADY_DEACTIVATED`, `SHORT_URL_ALREADY_ACTIVE` or `CONCURRENT_MODIFICATION`
    - `415`: `UNSUPPORTED_MEDIA_TYPE` (L6; the task list had no 415, but `consumes` makes it reachable)
- **DELETE** `@Operation(summary = "Delete a short URL (ADMIN only)")`. The description says:
  - the delete is soft: the row is kept for audit;
  - afterwards the code gets 404 everywhere, for ADMIN too, and can never be reused (a create with that alias gets 409);
  - deleting a DEACTIVATED link is allowed;
  - a USER always gets 403, whatever the code.
  - Responses:
    - `204`: no content
    - `401`
    - `403`: `ACCESS_DENIED`
    - `404`: `SHORT_URL_NOT_FOUND`
    - `406`
    - `409`: `CONCURRENT_MODIFICATION` (L4; the task list had no 409, but D35 makes it reachable)
- **Not documented:** 405 (it concerns other methods), and 500 (consistent with create and GET).

### 6. Logging

| Event | Level | Content |
|---|---|---|
| Deactivated or reactivated (after commit) | INFO | `code`, and the action in the message |
| Deleted (after commit) | INFO | `code` |
| Concurrent modification | INFO | `code`, `action` (`DEACTIVATE`, `REACTIVATE`, `DELETE`). Expected under D35, so never WARN or ERROR |
| 404 (not visible) | DEBUG | Unchanged `loadVisible` line: well-formed `code` plus the reason; a malformed value is never logged |
| D26 409 | none | A client outcome with no operational signal |
| ADMIN guard fired (§2.4) | ERROR, once, by the advice catch-all | method and path |

- **Never logged:** usernames (`Caller`, the principal, `deleted_by`), URLs (`ShortUrlView`, `ShortUrlResponse`, the entity's `originalUrl`), request bodies or records.
- **`deleted_by` is audit data** kept in the database, not in the log (D1, D52 caution).
- **Success is logged after `execute` returns**, so a commit failure never leaves a false "deleted" line.
- **There is no `id` in the success lines.** The view has no id, the code is unique, and logging inside the callback would log before commit.

### 7. Tests

#### 7.1 AC → component → test → owner

| AC | Component | mid-engineer (`*Test`, Surefire) | qa-tester (Cucumber + `*IT`, Failsafe) |
|---|---|---|---|
| AC1 | `setActive(false)`, controller | Service: owner, ACTIVE → view `DEACTIVATED`, `updatedAt` = fixed clock truncated, `flush` called once. Slice: 200, exact 8 keys, `status` `DEACTIVATED`, captor `("Abc1234", false, Caller("alice", false))` | Scenario plus `ShortUrlLifecycleIT`: 200 body equals GET after; DB `status`, `updated_at` inside the request window, version N+1; redirect then gives 404 (D2) |
| AC2 | `setActive(true)` | Service: owner, DEACTIVATED → `ACTIVE` | Scenario plus IT: 200 `ACTIVE`; redirect gives 302 again |
| AC3 | `loadVisible` | Service: bob on alice's ACTIVE and DEACTIVATED rows gives `ShortUrlNotFoundException` and no `flush` | Scenario plus IT ownership matrix; row unchanged; 404 body byte-equal to GET's 404 on the same path |
| AC4 | `callerOf`, `loadVisible` | Service: ADMIN on alice's and bob's rows, both directions | Scenario Outline plus matrix |
| AC5 | `delete`, `softDelete` | Service: ADMIN on ACTIVE and DEACTIVATED: `deletedBy == "admin"`, `deletedAt == updatedAt ==` clock, flush once. Slice: 204, empty body, no `Content-Type` | Scenario plus IT with the DB verified (§7.3) |
| AC6 | Rule 5 | Slice: alice DELETE gives 403 `ACCESS_DENIED`, base keys, `verifyNoInteractions(service)` | Scenario plus IT "403 changes nothing" (§7.3) |
| AC7 | `loadVisible`, advice | Service: unknown and malformed (`verifyNoInteractions(repository)` for malformed) for PATCH and for ADMIN DELETE | Scenario Outline: unknown and malformed codes, PATCH and DELETE, `SHORT_URL_NOT_FOUND` |
| AC8 | `loadVisible`, rule 5, advice | Service: DELETED row, PATCH by owner, bob and ADMIN, and DELETE by ADMIN, give the exception with no flush. Slice: USER DELETE on codes the mocked service would 404 still gets 403, service not called. Slice: service throws `ShortUrlDeletedException` gives a 404 **byte-identical** to `ShortUrlNotFoundException` on the same path | Scenario plus IT: USER DELETE gives 403 across existing, missing, deleted and deactivated rows |
| AC9 | `deactivate`, advice | Service: DEACTIVATED + `false` gives `ShortUrlAlreadyDeactivatedException`, rollback, no flush, no commit. Slice: 409 base keys | Scenario plus IT: row and version unchanged, and the positive control (a valid PATCH bumps the version) |
| AC10 | `reactivate`, advice | Mirror of AC9 | Mirror of AC9 |
| AC11 | `@Version`, catch, advice | Service: `flush` throws `ObjectOptimisticLockingFailureException` → `ShortUrlConcurrentModificationException`, rollback, no commit; `commit` throws it → same mapping. Repository: version conflict at flush gives `OptimisticLockingFailureException`. Slice: 409 `CONCURRENT_MODIFICATION` | `ShortUrlLifecycleConcurrencyIT` (§7.3) plus a scenario |
| AC12 | Entry point | Slice: anonymous PATCH and DELETE give 401 with `WWW-Authenticate`, service not called | Scenario plus IT, including wrong credentials |

CLAUDE.md requires a Cucumber scenario for **every** API AC, AC11 included, even where the story's table lists only an IT.

#### 7.2 mid-engineer

- **`ShortUrlServiceTest`** (Mockito repository and `PlatformTransactionManager`, `Clock.fixed` with nanoseconds):
  - The `setActive` matrix: {owner, bob, ADMIN} × {ACTIVE, DEACTIVATED, DELETED, unknown} × {true, false}, plus malformed codes with `verifyNoInteractions(repository)`.
  - Transitions: the entity status and `updatedAt`; `InOrder`: `getTransaction` → `findByShortCode` → `flush` → `commit`.
  - Redundant or not visible: no `flush`, `rollback` called and `commit` never.
  - The transaction definition (captor): `PROPAGATION_REQUIRED` and `isReadOnly() == false` for both `setActive` and `delete`. The existing captors for create (REQUIRES_NEW) and get (read-only) are unchanged.
  - `ObjectOptimisticLockingFailureException` from `repository.flush()` and, separately, from `transactionManager.commit(...)` both give `ShortUrlConcurrentModificationException`, with the original as cause. `JpaOptimisticLockingFailureException` does too, which proves the base-class catch.
  - Delete:
    - a non-admin `Caller` gives `IllegalStateException` with `verifyNoInteractions(repository, transactionManager)`;
    - ADMIN on ACTIVE and DEACTIVATED sets `deletedBy` to exactly `caller.username()`;
    - DELETED and unknown give `ShortUrlNotFoundException`.
  - Logging (OutputCapture):
    - The success lines contain the code and **not** the username or the URL marker. The "not logged" check also asserts that the success line was captured.
    - The conflict line is INFO, not WARN or ERROR.
    - No success line is logged when `commit` throws.
  - The existing reflection guard `shouldNotBeTransactionalAtClassOrMethodLevelSoEveryAttemptOwnsItsTransaction` is **unchanged** and must still pass.
- **`ShortUrlRepositoryTest`** additions (`@RepositoryTest`, real PostgreSQL):
  - `shouldThrowOptimisticLockingFailureWhenTheVersionChangedAfterLoad`:
    - load the entity, then `JdbcTemplate` `UPDATE … SET version = version + 1`, which joins the test transaction;
    - `deactivate` + `repository.flush()` throws `OptimisticLockingFailureException` (assert the concrete `ObjectOptimisticLockingFailureException` too);
    - the row's version is the bumped value and its status is still `ACTIVE` (the raw write happened; the entity write didn't).
    - This ties the service's catch type to the real stack.
  - `shouldNotOverwriteSqlUpdatedAnalyticsWhenEntityIsSoftDeleted`: the `softDelete` twin of the existing D27 test, asserting `click_count` and `last_accessed_at` are kept **and** version is 1 (review rule: the write happened).
- **`ShortUrlControllerWebMvcTest`** (existing slice):
  - PATCH: 200 shape; the argument captor; ADMIN gives `Caller("admin", true)`; `{}`/`{"active":null}` give `VALIDATION_FAILED` with `errors == [{field: active, message: "must not be null"}]`; the MALFORMED rows of §1.1; `text/plain` and `application/merge-patch+json` give 415; `Accept: application/xml`, `text/plain` and `application/problem+json` give 406 with the service not called; positive control `application/json` gives 200.
  - Each service exception gives its mapping, with exactly the base keys and `instance` equal to the path.
  - DELETE: ADMIN gives 204 with an empty body and no `Content-Type`; USER gives 403 with the service not called; ADMIN `Accept: application/xml` gives 406 with the service not called; ADMIN plus `ShortUrlNotFoundException` gives 404.
  - `PUT` gives 405, with `Allow` containing `GET`, `PATCH` and `DELETE`.
  - Anonymous PATCH and DELETE give 401.
  - Precedence: malformed JSON with `Accept: application/xml` gives 406, not 400.
  - The byte-identical 404 for `ShortUrlDeletedException` vs `ShortUrlNotFoundException` (AC8).
- **`GlobalExceptionHandlerTest`:** the three new handlers and the extended one give exactly the base keys, with nothing logged at WARN or ERROR.
- **Carry-over from US-008:** N1, N4, N5, N6 and N7 (§8).

#### 7.3 qa-tester

**Test data.** `ShortUrlTestData` gains `lifecycleState(code)`: `status, version, updated_at, deleted_at, deleted_by, click_count, last_accessed_at`, read with explicit types. The existing `rowState` stays as it is, because `GetShortUrlIT` compares it. ACTIVE rows for owner-realism checks are created through the API; DEACTIVATED and DELETED rows through the API too where the story now allows it, and raw SQL otherwise. Owners come from `TestUsers`. Each class truncates in `@BeforeEach`, uses unique codes, and adds nothing context-affecting to `IntegrationTestBase` subclasses.

**`ShortUrlLifecycleIT`** (real HTTP, Testcontainers PostgreSQL):
1. **Ownership matrix.** Rows `AliceAct1`, `AliceDea1`, `AliceDel1` (alice), `BobAct1` (bob) and `AdminAct1` (admin). PATCH in both directions by alice, bob and admin:
   - owner or admin on a visible row gives 200 or the D26 409;
   - a non-owner gives 404;
   - `AliceDel1` gives 404 for all three.

   Every 404 asserts `SHORT_URL_NOT_FOUND`. Every non-200 leaves `lifecycleState` unchanged, **and** the same row then accepts a valid PATCH by its owner (version + 1), so the "unchanged" check can't pass vacuously.
2. **D26 409s (AC9, AC10).** Exactly the base keys; version, status and `updated_at` unchanged; then the opposite PATCH gives 200 and version + 1.
3. **USER DELETE gives 403 and changes nothing (AC6, AC8).** As alice, DELETE her own ACTIVE row, bob's ACTIVE row, a missing code, her DEACTIVATED row and a DELETED row:
   - each gives 403 `ACCESS_DENIED` with exactly the base keys and no `WWW-Authenticate`;
   - the bodies are identical apart from `instance`;
   - `lifecycleState` and the row count are unchanged.

   **Positive control:** the same DELETE as admin on alice's ACTIVE row gives 204 and version + 1.
4. **ADMIN DELETE gives 204 with the DB verified (AC5).** For an ACTIVE row and a DEACTIVATED row (each owned by alice):
   - an empty body and no `Content-Type`;
   - `status = 'DELETED'`, `deleted_by = 'admin'` (exactly `TestUsers.ADMIN`), `deleted_at` not null and equal to `updated_at`, both inside the window [test instant before, test instant after];
   - version = N + 1, and `created_by` still alice.
5. **After a delete.**
   - For alice and admin: `GET /api/v1/urls/{code}` gives 404; `PATCH` true and false give 404; a second admin `DELETE` gives 404, and the row is unchanged (`deleted_at` and `deleted_by` still the first delete's values, version N + 1).
   - For anonymous callers: `GET /{code}` gives 404 `SHORT_URL_NOT_FOUND`, and `HEAD /{code}` gives 404.
   - `POST /api/v1/urls` with `alias = code`, by alice and by admin, gives **409 `ALIAS_ALREADY_EXISTS`**, and `countByCode == 1`.
   - All of these 404 bodies on the management path are byte-identical to an unknown code's 404 on the same path shape.
6. **D2 cross-check.** Deactivate gives redirect 404; reactivate gives redirect 302 with the original `Location`.
7. **D70: 406 changes nothing.**
   - PATCH `{"active": false}` on alice's ACTIVE row with `Accept` `application/xml`, `text/plain` or `application/problem+json` gives 406 `NOT_ACCEPTABLE` with a problem+json body. An unparseable `Accept` (`foo`) gives 406 with an **empty body**. Each time, `lifecycleState` is unchanged (version too).
   - Admin DELETE with the same `Accept` values: 406, and the row is unchanged (still ACTIVE, `deleted_at` null, same version).
   - **Positive controls:** the same PATCH with `application/json` gives 200 and version + 1; the same DELETE gives 204 and version + 1.
   - Precedence: a PATCH with a malformed body and `Accept: application/xml` gives 406; `Content-Type: text/plain` with `Accept: application/xml` gives 415.
8. **D16/D27: PATCH and DELETE never write click data.**
   - `seedClicks(code, 7, T)` with a fixed `T`.
   - Deactivate, reactivate, then delete. After each: `click_count == 7`, `last_accessed_at == T`, the version bumped by one (the write happened) and `updated_at` changed.
   - The PATCH response's `clickCount` is 7.
9. **Validation and parsing.** `{}` and `{"active":null}` give `VALIDATION_FAILED` with an `errors` field of `active`; unknown field, duplicate key and `"maybe"` give `MALFORMED_REQUEST`; `text/plain` gives 415. Nothing changes in any of these cases. **Record** the actual outcome of `{"active":"false"}`, `0` and `1` (Q4).
10. **Path variants (§1.3)** with exact statuses, and no row change for the trailing-slash PATCH or DELETE.
11. **Anonymous and bad credentials:** PATCH and DELETE give 401 `AUTHENTICATION_REQUIRED` with exactly the base keys and nothing changed.

**`ShortUrlLifecycleConcurrencyIT`** (L1, §3):
1. **Race (AC11).** Repeat 10 times, each with a fresh code alice creates through the API:
   - Two `PATCH {"active": false}` requests as alice, released by a `CyclicBarrier(2)` with 10-second await timeouts; `invokeAll` with a 30-second timeout; the executor is shut down in `finally`.
   - Sorted statuses equal `[200, 409]`. The 409 has exactly the base keys and an `errorCode` in {`CONCURRENT_MODIFICATION`, `SHORT_URL_ALREADY_DEACTIVATED`}.
   - The row is `DEACTIVATED` with version exactly N + 1.
   - The distribution of the two codes is recorded in the QA notes and **not** asserted.
2. **Forced overlap (a)** and its rollback twin, exactly as in §3.2. Assert `CONCURRENT_MODIFICATION` after commit and 200 after rollback.
3. **Serialised (b):** §3.3.
4. **DELETE and PATCH (§3.4):**
   - A held raw soft-delete UPDATE (with the three D44 columns and `version + 1`), then commit: PATCH gets 409 `CONCURRENT_MODIFICATION`, and a follow-up PATCH gets 404.
   - A held raw deactivate UPDATE, then commit: an admin DELETE gets 409 `CONCURRENT_MODIFICATION`, the row has `deleted_at IS NULL`, and a follow-up DELETE gets 204.
5. **Click racing with PATCH (D16):**
   - A held click-shaped raw UPDATE (`click_count = click_count + 1, last_accessed_at = ?`, version untouched), then `sendAsync` a PATCH, wait for the lock, and commit.
   - PATCH gets **200**, not 409, and the row has `click_count` = seeded + 1, status `DEACTIVATED` and version N + 1.
6. **No ERROR noise:** OutputCapture over (2) and (4) shows no `ERROR` line, no `HHH000346` and no stack trace, **and** does contain the service's INFO "changed concurrently" line (non-vacuous).
7. **Technique rules:**
   - Poll `pg_stat_activity` for a waiting backend rather than using `Thread.sleep`.
   - The seed connection is closed in `finally`, and the async future is awaited with a timeout.
   - Hikari's default pool of 10 is enough: 2 request connections plus 1 seed connection.

**`NoTransactionalAnnotationIT`** (task item):
- From the running context, for every bean whose target class (`AopUtils.getTargetClass`) is in `com.schwab.urlshortener`, assert that neither the class nor any declared method carries Spring's or Jakarta's `@Transactional`.
- **Non-vacuous:** the scanned set must include `ShortUrlService`, `RedirectService` and `ShortUrlController`.
- This complements the unit-level reflection guards.

**Cucumber** (`features/short-url-lifecycle.feature`, glue in `cucumber/LifecycleSteps`, reusing `ApiClient`, `ShortUrlTestData` and `TestUsers.require`):
- one scenario per AC, AC1–AC12;
- AC5 checks the stored `deleted_by`;
- AC11 uses a two-request step asserting one 200 and one 409, with the errorCode in the L1 set;
- plus "a deleted code cannot be reused", the 406 outline for PATCH and DELETE, and "PATCH keeps click data".

**`OpenApiDocsIT`** (extend):
- `paths./api/v1/urls/{code}.patch`:
  - the request body is `application/json` only, and its schema has `active` in `required`;
  - `200` is `application/json` with the 8 properties;
  - `400`, `401`, `404`, `406`, `409` and `415` are problem+json only;
  - `security` contains `basicAuth`.
- `.delete`:
  - the response keys are exactly {`204`, `401`, `403`, `404`, `406`, `409`};
  - `204` has no `content`;
  - errors are problem+json only.
  - If springdoc adds a default `200`, the implementer fixes the annotations; the test does not accept it.

**Existing tests to update (qa-tester, deliberately):**
- `GetShortUrlIT.shouldReturn405WithAllowGetForOtherMethodsOnACodeAndChangeNothing` drops `PATCH` from its `@ValueSource`, because PATCH is now mapped.
- `SecurityIT.shouldNotReturn403WhenAdminDeletes` is tightened to `404 SHORT_URL_NOT_FOUND` (unknown code `abc1234`).
- `SecurityIT.shouldNotLetUserReachDeleteHandlerThroughPathVariants`: re-verify every row against a real run. As a USER each should be unchanged (403 or 400). Update the comment so it no longer says "no delete handler until US-009".
- `SecurityIT.shouldAcceptAuthenticatedPostWithoutCsrfToken` is unaffected.
- **N2** (§8).

### 8. Carry-over from US-008

| Item | Owner | What |
|---|---|---|
| N1 | mid-engineer | Rename `UrlValidatorTest.shouldCountCodePointsNotUtf16UnitsOrBytesForShortMultibyteUrls` to what it proves (for example `shouldAcceptAShortUrlOfSupplementaryCharacters`), or delete it as a duplicate of `shouldAcceptValidSupplementaryCharacterInPath`. Add the comment in `UrlValidator.isValid`: D84 implies the D11 count, and the D11 check stays as the cheap limit before encoding. **Engineer-approved change to a Done story's test.** List it test by test in US-004's post-completion section, which the mid-engineer edits for this purpose only |
| N2 | qa-tester | `RedirectIT` raw-SQL positive control (around lines 242–252): also assert the oversized URL is absent from the captured output. Its comment says it pins the D85 gap |
| N4 | mid-engineer | Wrap the 171-column Javadoc line in `RedirectController` (around line 30) |
| N5 | mid-engineer | Add `verify(service).resolve(CODE)` to the body-less unparseable-`Accept` and HEAD 404 slice tests in `RedirectControllerWebMvcTest` |
| N6 | mid-engineer | `LocationEncoderTest.shouldLeaveA2048CharacterAsciiUrlByteIdentical`: build exactly 2048 characters, assert `hasSize(2048)`, and use `String.format(Locale.ROOT, …)` |
| N7 | mid-engineer | Add `@throws NullPointerException if target is null` to `LocationEncoder.encode` |

Comments cite behaviour and `Dnn` IDs, never finding IDs.

### 9. Implementation plan

**mid-engineer**
1. `service/exception/ShortUrlConcurrentModificationException`.
2. `ShortUrlService`: the `readWrite` template, `setActive` and `delete` (§2.3, §2.4). Update the class Javadoc: it now also writes, and `@Transactional` is still forbidden.
3. `api/dto/UpdateShortUrlRequest`; `ShortUrlController` `update` and `delete`, with OpenAPI annotations (§5) and a class Javadoc naming rules 5 and 6. The `ShortUrlResponse` Javadoc.
4. `GlobalExceptionHandler` (§4).
5. The tests in §7.2, then N1, N4–N7.

**qa-tester**
1. `ShortUrlTestData.lifecycleState`.
2. `ShortUrlLifecycleIT`, `ShortUrlLifecycleConcurrencyIT`, `NoTransactionalAnnotationIT`, the feature and steps, and the `OpenApiDocsIT` extension.
3. The existing-test updates (§7.3), then N2.

### 10. Other decisions

**`loadVisible` reused unchanged, format check inside the transaction**
```
Recommendation: call loadVisible unchanged inside readWrite; a malformed code therefore opens and rolls back an empty
                transaction, exactly as GET does today.
Reason:         one visibility rule, one order, already proven by US-007's matrix; no change to Done-story code.
Alternative:    hoist the D72 format check before the template for the write paths (as RedirectService does, D77).
Trade-off:      a malformed PATCH or DELETE borrows a pool connection for an empty transaction. The alternative saves
                that but duplicates the check or reshapes loadVisible for three callers.
```

**`boolean` parameter instead of a command record**
```
Recommendation: ShortUrlService.setActive(String code, boolean active, Caller caller).
Reason:         one flag; the controller unboxes after @Valid has guaranteed non-null; nothing speculative.
Alternative:    UpdateShortUrlCommand(Boolean active) now, ready for expiresAt.
Trade-off:      US-013 changes the signature when expiresAt arrives, a change internal to api -> service. A command now
                would be an abstraction for a field that does not exist yet.
```

**Explicit flush over commit-time flush (L3):** see §2.3. The alternative, relying on the managed flush at commit, is shorter by one line. Its cost is an `HHH000346` ERROR log on every D35 conflict and an optimistic failure that surfaces from `commit` rather than from a visible line.

### 11. Open questions (for the engineer)

- **Q1 (L1, AC11, the story's open question):** the loser gets `CONCURRENT_MODIFICATION` (overlap) or `SHORT_URL_ALREADY_DEACTIVATED` (serialised). **Recommend:** the race test accepts either, and the two deterministic tests pin each path (§3.2, §3.3).
- **Q2 (L4):** under D35, DELETE also gets `409 CONCURRENT_MODIFICATION` when it overlaps another change, and a PATCH that overlaps a DELETE gets 409, not 404 (re-reading gives 404). requirements.md doesn't state either case explicitly. **Recommend** accepting both and documenting 409 on DELETE. The alternative, re-reading after a conflict to return 404 for a now-deleted row, adds a second transaction for a rare case.
- **Q3 (L6):** PATCH accepts `application/json` only. `application/merge-patch+json` gets 415. **Recommend** accepting this, since D34 defines a plain JSON body.
- **Q4:** Jackson's default scalar coercion means `{"active":"false"}` deactivates, and `0`/`1` are read as false/true. D59 made unknown fields and duplicate keys strict, but not types. **Recommend** keeping the global default (as US-006 K9 did for `alias`), with QA pinning the behaviour. Strict booleans would need a global `CoercionConfig` change that also affects create.
- **Q5 (information):** the PATCH 200 body reports `clickCount` and `lastAccessedAt` as read in its transaction. A click committed during the PATCH isn't reflected until the next GET. **Recommend** accepting this. D27 forbids re-reading through the entity's columns, and a refresh would add a query.

### 12. Risks for the implementer and the reviewer

- **K1: the flush in the wrong place, or none.** Without `repository.flush()` inside the callback, the conflict surfaces at commit with an `HHH000346` ERROR log. If the `try` is inside the callback, the catch runs before rollback. Reviewer: exactly one `flush()` as the callback's last write, and the `catch` around `readWrite.execute…`.
- **K2: catching the wrong type.** `ObjectOptimisticLockingFailureException` alone misses `JpaOptimisticLockingFailureException`. `jakarta.persistence.OptimisticLockException` is never what reaches the service. `DataAccessException` would turn outages and CHECK violations into 409s. Catch exactly `OptimisticLockingFailureException`.
- **K3: an uncaught conflict becomes a 500.** Any new write path (US-013's `expiresAt`) must use the same template-plus-catch. The §3.2 IT and the service tests catch a regression here.
- **K4: D27 regressions.** `@DynamicUpdate`, `save(detached)`, a JPQL `UPDATE`, or changing the analytics mapping reintroduces click clobbering or spurious 409s. The §7.3 item 8 and click-race tests catch it.
- **K5: ownership outside `loadVisible`.** No ownership or status logic may appear in `api/`, and `ShortUrlView` must not gain `createdBy` (US-007 K1). Check `DELETED` before the ADMIN shortcut (US-007 K3); the reused method already does.
- **K6: the 404 bodies diverging.** `ShortUrlDeletedException` must be added to the **existing** handler, not given its own. The handler must not take the exception as a parameter (its message has the code).
- **K7: vacuous tests.**
  - Every "unchanged" assertion (403, 406, 409, 404) needs a same-row positive control that bumps `version`.
  - Every 404 asserts `errorCode` (`RESOURCE_NOT_FOUND` vs `SHORT_URL_NOT_FOUND`).
  - The race test must also assert version N + 1, or two "successes" could hide a lost update.
- **K8: flaky concurrency tests.** No `Thread.sleep`; wait with timeouts on barriers, polls and futures; always release the seed connection. BCrypt makes the race test's overlap uncertain, so (a) is proven only by the held-lock test (US-006 K10).
- **K9: logging.** Never log `Caller`, `deleted_by`, the request, the view or the response. Success lines go after commit. Conflicts are INFO, not WARN or ERROR.
- **K10: the admin guard mistaken for authorization.** Rule 5 is the authorization. The service guard is a tripwire, and it must never be relaxed to "owner may delete" (D3).
- **K11: existing tests.** The `GetShortUrlIT` PATCH 405 case and the `SecurityIT` admin-delete case will fail until they are updated (§7.3). Update them deliberately, and never loosen them to `isIn(...)`.
- **K12: `produces`.** Don't add `produces` or `consumes` to `delete`, or any `produces` to `update`. Both inherit D70 from the class.

### 13. Sources checked (2026-09-30)

- **Spring Framework 6.2.19 source (tag `v6.2.19`):**
  - `HibernateJpaDialect.translateExceptionIfPossible`: a `HibernateException`, or a `PersistenceException` whose cause is a `HibernateException`, goes to `convertHibernateAccessException`. `StaleObjectStateException`, `StaleStateException` and `OptimisticEntityLockException` become `ObjectOptimisticLockingFailureException`.
  - `EntityManagerFactoryUtils.convertJpaAccessExceptionIfPossible`: `jakarta.persistence.OptimisticLockException` becomes `JpaOptimisticLockingFailureException`.
  - `JpaTransactionManager.doCommit`: a `RollbackException` with a `RuntimeException` cause is translated through the dialect; other `RuntimeException`s go through `DataAccessUtils.translateIfNecessary`.
  - `TransactionTemplate.execute`: a `RuntimeException` from the callback triggers `rollbackOnException` and is rethrown; `commit` runs only after the callback returns.
- **Spring Data JPA 3.5.x `SimpleJpaRepository`:** class-level `@Transactional(readOnly = true)`; `flush()` and `saveAndFlush()` are `@Transactional` (read-write), so they join the caller's transaction.
- **Hibernate ORM 6.6.53 source:**
  - `ModelMutationHelper.identifiedResultsCheck`: 0 affected rows on a non-optional table throws `StaleObjectStateException`.
  - `SessionImpl.doFlush`: `RuntimeException` → `getExceptionConverter().convert(e)`. `flushBeforeTransactionCompletion`: managed-flush failures go to `ExceptionMapperStandardImpl.mapManagedFlushFailure`.
  - `ExceptionConverterImpl`: `wrapStaleStateException` creates `OptimisticLockException(message, cause)`, keeping the Hibernate exception as the cause. `convertCommitException` rolls back and wraps in `RollbackException`.
  - `HHH000346 "Error during managed flush"` is logged at ERROR by `ExceptionMapperStandardImpl`: confirmed from Hibernate forum and vendor reports ([Hibernate Discourse](https://discourse.hibernate.org/t/hhh000346-error-during-managed-flush-org-hibernate-exception-sqlgrammarexception-could-not-execute-statement/3021), [Red Hat](https://access.redhat.com/solutions/6997218)). The class source was not retrievable at the tag path, which is why the §7.3 IT asserts the absence of the log line directly.
- **Jakarta Persistence 3.1 `@Column` Javadoc:** `updatable` means "Whether the column is included in SQL UPDATE statements generated by the persistence provider", and `insertable` means the same for INSERT.
- **PostgreSQL 18 docs, §13.2.1 Read Committed:** a `SELECT` sees only data committed before it began. `UPDATE`/`DELETE` wait for a concurrent updater. On rollback they proceed with the original row; on commit they re-evaluate the `WHERE` on the updated version and skip the row if it no longer matches.
- **jackson-databind 2.19 `StdDeserializer`:** `Boolean` accepts `VALUE_NUMBER_INT` through `_coerceBooleanFromInt` (non-zero → true, subject to `CoercionAction`) and the strings `true`/`false` in any case. `null` → `null`. The defaults are pinned by QA (Q4) rather than assumed.
- **Code checked at `2ef7a20`:** `ShortUrl`, `ShortUrlStatus`, the domain exceptions, `ShortUrlService` (including the reflection guard in `ShortUrlServiceTest`), `RedirectService`, `Caller`, `ShortUrlView`, `ShortUrlController`, `ShortUrlResponse`, `CreateShortUrlRequest`, `GlobalExceptionHandler`, `ErrorCode`, `ProblemDetails`, `SecurityConfig`, `ShortUrlRepository`, `ShortUrlRepositoryTest`, `IntegrationTestBase`, `ShortUrlTestData`, `SecurityIT`, `GetShortUrlIT` and `ShortUrlControllerWebMvcTest`.

## Implementation notes

**Files changed (production).** New: `api/dto/UpdateShortUrlRequest`, `config/JacksonConfig`, `service/exception/ShortUrlConcurrentModificationException`. Changed: `ShortUrlService` (`readWrite` template, `setActive`, `delete`), `ShortUrlController` (`update` PATCH, `delete` DELETE, OpenAPI), `GlobalExceptionHandler` (three handlers, `ShortUrlDeletedException` added to the existing not-found handler, three `DETAIL` texts), `ShortUrlResponse` (Javadoc), and the US-008 carry-over in `RedirectController` (N4), `LocationEncoder` (N7) and `UrlValidator` (N1 comment). No migration, no `SecurityConfig` change.

**Files changed (tests).** `ShortUrlServiceTest`, `ShortUrlControllerWebMvcTest`, `GlobalExceptionHandlerTest`, `ShortUrlRepositoryTest`, new `JacksonConfigTest`; N1 in `UrlValidatorTest`, N5 in `RedirectControllerWebMvcTest`, N6 in `LocationEncoderTest`. `docs/stories/US-004-url-alias-validation.md`: the "Planned follow-up" line is replaced by the actual N1 change.

**Decisions.**
- Flush and catch as in the design note: one `repository.flush()` as the callback's last write; the `catch` is exactly `OptimisticLockingFailureException`, around `readWrite.execute` (`executeWithoutResult` for delete). The unit tests pin this: flush-time and commit-time failures, `JpaOptimisticLockingFailureException` and the base class map to the new exception, while `DataAccessResourceFailureException` and a `DataIntegrityViolationException` pass through unchanged.
- D89 mechanism: `JacksonConfig` publishes a `Jackson2ObjectMapperBuilderCustomizer` that calls `coercionConfigFor(LogicalType.Boolean).setCoercion(shape, CoercionAction.Fail)` for the explicit shapes `String`, `EmptyString`, `Integer`, `Float`, `Array` and `EmptyArray` (after R9). I read jackson-databind source for the version on the classpath (2.21.4; 2.19.4 has the identical Boolean code paths). Boolean coercion is consulted in exactly three places: `_checkFromStringCoercion(..., LogicalType.Boolean, ...)` (string, empty string, blank string), `_coerceBooleanFromInt` (integers; a float falls through to `handleUnexpectedToken` and already fails) and the array path (`_deserializeFromArray`). Setting every shape covers all of them, for both `Boolean` and `boolean`. `MapperFeature.ALLOW_COERCION_OF_SCALARS` was not used, because it is global and would change create. Scope evidence is in the tests: `JacksonConfigTest` (int/long/BigDecimal/Double still read from strings, String still read from numbers and booleans, D59 properties intact, booleans still serialize as JSON booleans) and two slice tests (create with numeric `alias` and `originalUrl` is coerced as before; PATCH response keeps `"customAlias":true`).
- The customizer lives in a `@Configuration` in `config`. `@WebMvcTest` slices do not scan `@Configuration` classes, so `ShortUrlControllerWebMvcTest` imports `JacksonConfig` explicitly. `JacksonConfigTest` uses Boot's real `JacksonAutoConfiguration` to prove that the customizer reaches the context `ObjectMapper`.
- The service-level admin guard is the first statement of `delete`. Its test checks `verifyNoInteractions` on the transaction manager and that no lookup happens, for owner and non-owner USER callers.
- N1: the test is renamed (`shouldAcceptAShortUrlOfSupplementaryCharacters`), not deleted, because it covers a longer run of supplementary characters than the single-emoji test.

**Needs human review.**
- `JacksonConfig` sets `Fail` for all input shapes, including `Array`/`EmptyArray`. That is stricter than the string/int shapes D89 names, but a Boolean property never had a legitimate array input. Confirm you accept it.
- Version drift: the design note cites jackson-databind 2.19; the resolved version is 2.21.4 (see `./mvnw dependency:list`).
- Two `readWrite` behaviours are only unit-tested with mocks here: the real Hibernate exception type from a stale flush is covered by the new repository test, and the real HTTP 409 by QA's `ShortUrlLifecycleConcurrencyIT`.

**Test command and result.** `JAVA_HOME=/usr/local/opt/openjdk/libexec/openjdk.jdk/Contents/Home ./mvnw -q clean verify`. Surefire: 840 tests, 0 failures, 0 errors. Failsafe: 454 tests, 1 failure, the QA test `GetShortUrlIT.shouldReturn405WithAllowGetForOtherMethodsOnACodeAndChangeNothing` `[3]` (PATCH is now mapped, so `{}` gives 400 `VALIDATION_FAILED`; the design note assigns the re-pin to QA). The build therefore stops at `failsafe:verify`, before the JaCoCo `check` goal. The merged JaCoCo report (`target/site/jacoco-merged/jacoco.csv`) was still written: line coverage 496 covered, 2 missed, 99.6%. `SecurityIT.shouldNotReturn403WhenAdminDeletes` still passes (`isIn(404, 405)` now sees 404) but its comment and assertion are stale; QA re-pins it.

## QA notes

**Files (all under `src/test/**`).** New: `features/short-url-lifecycle.feature`, `cucumber/LifecycleSteps`, `support/LifecycleIT`, `support/LifecycleConcurrencyIT`, `support/NoTransactionalAnnotationIT`. Extended: `support/ShortUrlTestData` (`LifecycleState` record, `lifecycleState(code)`, `rowCount()`), `support/OpenApiDocsIT` (PATCH and DELETE). Re-pinned: `GetShortUrlIT` (PATCH dropped from the 405 `@ValueSource`), `SecurityIT` (`shouldNotReturn403WhenAdminDeletes` is now a strict 404 `SHORT_URL_NOT_FOUND`; the DELETE path-variant rows were re-run and are unchanged, comment updated; a stray finding-ID comment removed), `RedirectIT` (N2: the D85-gap positive control now also asserts the oversized URL is absent from the captured output). No `src/main/**` and no mid-engineer `*Test` file was touched. Scenarios and the IT matrix were written from the ACs and the design note before the implementation was read.

**AC to test**

| AC | Cucumber (`short-url-lifecycle.feature`) | `*IT` |
|---|---|---|
| AC1 | "The owner deactivates an active short URL" (also redirect 404, version +1) | `LifecycleIT.shouldApplyOwnershipDeletionAndTransitionRulesToPatch...` (matrix), `shouldDeactivateThenReactivateRecordTheChangeAndToggleTheRedirect` |
| AC2 | "The owner reactivates a deactivated short URL" (redirect 302 with exact Location) | same two |
| AC3 | "A user cannot deactivate someone else's short URL..." | matrix (bob/alice on foreign rows, 404 body equals an unknown code's 404 apart from `instance`, then the owner's write as control) |
| AC4 | Outline "An administrator changes any user's short URL" | matrix (admin on alice, bob, admin rows, both directions) |
| AC5 | Outline "...deletes a short URL and the audit trail is kept" (ACTIVE and DEACTIVATED; `deleted_by`, `deleted_at = updated_at`) | `shouldSoftDeleteAsAdminAndRecordWhoAndWhenInTheDatabase` (4 rows: window, exact `admin`, version +1, `created_by` kept), `shouldStoreTheConfiguredLowercaseUsername...` (login typed `Admin`) |
| AC6 | Outline "A user can never delete, not even their own" (alice, bob; admin control) | `shouldReturn403ForAUserDeleteWhateverTheCodeAndChangeNothing` (7 codes x 2 users, bodies identical but for `instance`, rows and count unchanged, admin control) |
| AC7 | Outline "Changing a short URL that does not exist is a 404" | `shouldReturn404ForAnUnknownAndAMalformedCodeOnPatchAndAdminDelete`, `shouldReturnByteIdentical404Bodies...` |
| AC8 | Outlines "A deleted short URL accepts no further lifecycle change" and "A user deleting a code in any state... gets 403" | `shouldTreatADeletedCodeAsGoneEverywhereYetNeverAllowItsReuse`, USER-DELETE test, matrix `AliceDel1` rows |
| AC9 | "Deactivating an already deactivated short URL is a conflict" | `shouldReturn409ForARedundantChange...` (alice, admin; base keys; unchanged; opposite PATCH control), matrix |
| AC10 | "Reactivating an already active short URL is a conflict" | same |
| AC11 | "Two simultaneous deactivations..." (barrier, one 200 and one 409 in the D86 set, version +1) | `LifecycleConcurrencyIT` (below) |
| AC12 | Outline "Without credentials... 401" | `shouldReturn401ForAnonymousAndWrongCredentialsAndChangeNothing` (PATCH, DELETE; anonymous, wrong password, unknown code; control) |

Other rows: D1 reuse (Cucumber "A deleted short code cannot be reused"; IT: alice and admin both get 409 `ALIAS_ALREADY_EXISTS`, `countByCode == 1`), D70 (Cucumber outline; IT: xml, text/plain, problem+json, unparseable `foo` with empty body, precedence 406 over 400/404, 415 over 406, USER DELETE with a bad `Accept` gets 403, each with a version-bump control), D88 (415 for merge-patch, json-patch, text/plain, xml and no Content-Type; `application/json; charset=UTF-8` accepted), D89 (20 malformed bodies give 400 `MALFORMED_REQUEST`, `{}` and `{"active":null}` give 400 `VALIDATION_FAILED` with `errors[0]` = `active`/`must not be null`; each with a control; create with a numeric `alias` is still coerced: 201, `shortCode` `12345`, `customAlias` a JSON boolean), D27/D90 (click data untouched by deactivate, reactivate and delete, with version and `updated_at` bumps; PATCH body shows 7 clicks; Cucumber scenario), logging (positive capture of the three success lines; no username, no URL marker, no query token, no `ERROR` or `WARN`), path variants (PUT/POST 405, trailing slash 404 `RESOURCE_NOT_FOUND` for PATCH and admin DELETE, USER trailing-slash DELETE 403, `DELETE /api/v1/urls` 405 `Allow: POST` / 403, `/API/...` 403), OpenAPI (PATCH 7 statuses, DELETE exactly 204/401/403/404/406/409 with no implicit 200, problem+json only, request schema with `active` required, basicAuth), no `@Transactional` in the running context (`NoTransactionalAnnotationIT`, non-vacuous).

**Concurrency (`LifecycleConcurrencyIT`, 12 tests).**
- Race x10 (barrier, `invokeAll` with timeouts): every round gave `[200, 409]` and version N+1. Loser distribution over 4 full executions (40 rounds): **`CONCURRENT_MODIFICATION` 40, `SHORT_URL_ALREADY_DEACTIVATED` 0**. The overlap is real on this machine, so the race test alone would not have exercised the serialised path; that path is pinned by the serialised test. Not asserted (D86). No `HHH000346`, `ERROR` or stack trace in the captured output.
- Held row lock (separate connection, `pg_stat_activity` poll for `wait_event_type = 'Lock'` on an `update short_url` statement, asserted to contain the version predicate): commit gives 409 `CONCURRENT_MODIFICATION`; the rollback twin gives 200 (so the 409 comes from `WHERE version = N` at UPDATE time, not from a pre-check). The INFO line `Short URL changed concurrently: code=... action=...` is asserted as the positive control for the "no ERROR, no `HHH000346`" assertions. Also REACTIVATE overlap.
- D87: PATCH vs committed soft delete gives 409 then 404; admin DELETE vs committed deactivation gives 409, row not deleted, retry gives 204; DELETE vs DELETE gives 409 then 404; DELETE whose blocker rolls back gives 204; serialised deactivate-then-delete gives 204, and patch or delete after delete gives 404 (version +2).
- D27: a click-shaped UPDATE held under a PATCH (and under an admin DELETE): 200 / 204, never 409; the click is kept (`click_count` +1, new `last_accessed_at`), version +1; the PATCH body shows the pre-click count and the next GET shows the click (D90).

**Results.** `JAVA_HOME=/usr/local/opt/openjdk/libexec/openjdk.jdk/Contents/Home ./mvnw -q clean verify` exits 0. Surefire 840 run, 0 failed. Failsafe 611 run, 0 failed, 0 skipped (Cucumber 167 of them, 37 from the lifecycle feature; `LifecycleIT` 97, `LifecycleConcurrencyIT` 12, `NoTransactionalAnnotationIT` 1, `OpenApiDocsIT` 31). Merged JaCoCo lines 496 covered, 2 missed. The concurrency class was re-run three more times in isolation: all green.

**Defects.** None found (no `D-US-009-n`).

**Observations and escalations.**
1. *Allow header (design says HEAD is listed).* The 405 for PUT/POST on `/api/v1/urls/{code}` returns `Allow: GET, DELETE, PATCH`. HEAD is not listed although it is served (200, used as the control). Pinned as observed. Engineer to confirm that this is acceptable; it is Spring's behaviour, not a story requirement.
2. *No controllable Clock.* The context's `Clock` is `systemUTC`, and replacing it in a subclass would create a second context (review rule). The tests therefore assert `deleted_at == updated_at` exactly and both inside the [before, after] window of the test JVM's clock, not against a fixed instant. If the engineer wants an exact fixed-time assertion, the Clock has to be swapped in `IntegrationTestBase` or its imported test configuration for every IT (a cross-story change).
3. *Beyond the wording of D89.* `1.0`, `[]`, `[true]`, `""` and a bare `false` / `"false"` document also give 400 `MALFORMED_REQUEST`. This matches "only real JSON true/false" and the `JacksonConfig` comment; pinned.
4. The CLAUDE.md-mandated wording of the story table says AC11 is proven by "an IT"; the Cucumber AC11 scenario proves the outcome only (the loser code is not deterministic), the mapping of each code is proven by the deterministic ITs.

## Review log
*(senior-engineer findings per round, and how each was resolved)*

| Round | ID | Severity | Finding | Resolution |
|---|---|---|---|---|
| 1 | R1 | SHOULD | `JacksonConfig` reached `@WebMvcTest` only through one class's import | Fixed. Chose the shared configuration: `JacksonConfig` is now imported by `SecuritySliceTestConfiguration`, which every slice already imports for the real filter chain (a meta-annotation would be one more thing to forget). `SecurityConfigWebMvcTest` now imports `SecuritySliceTestConfiguration` instead of the two classes; `ShortUrlControllerWebMvcTest` no longer imports `JacksonConfig`. Proof: `RedirectControllerWebMvcTest.shouldApplyTheD89StrictBooleanSettingToTheSliceObjectMapper` (a slice that never imported `JacksonConfig`); it fails when the import is removed from the shared configuration (checked). |
| 1 | R2 | SHOULD | `stored()` seeded the transition values at `NOW_MICROS` / `"admin"` | Fixed. Deactivated and deleted fixtures now use `EARLIER` (`NOW_MICROS - 60 s`) and `first-admin`. `shouldRollBackWithoutFlushingWhenTheTransitionIsRedundant` failed on the first run (it asserted `NOW_MICROS` for a deactivated row), proving the assertion can now fail. It compares with the pre-call value. The deleted-row test asserts `deletedBy`, `deletedAt` and `updatedAt` are the first delete's. |
| 1 | R3 | SHOULD | N2 log check did not cover the D75-encoded form | Fixed in `RedirectIT.shouldLogTomcatHeadersTooLargeErrorWhenAnOversizedRowBypassesD84`: also asserts `.doesNotContain(LocationEncoder.encode(oversized))` and `.doesNotContain("%E4%B8%AD".repeat(3))`. |
| 1 | R4 | SHOULD | Inline fully qualified names | Fixed in `ShortUrlServiceTest`, `GlobalExceptionHandlerTest`, `JacksonConfigTest` and the `ShortUrlService` `@throws` (imports). `@io.swagger...RequestBody` kept. |
| 1 | R5 | SHOULD | `K9` cited in a test comment | Fixed. Removed. |
| 1 | R6 | | Docs | Orchestrator. |
| 1 | R7 | NIT | Lines over 120 characters | Fixed in `ShortUrlServiceTest`, `ShortUrlControllerWebMvcTest`, `GlobalExceptionHandlerTest`, `JacksonConfigTest`. One older line remains at `ShortUrlControllerWebMvcTest` (POST trailing-slash test), not introduced by this story. QA part: wrapped one line each in `LifecycleSteps` and `LifecycleConcurrencyIT`; `LifecycleIT`, `NoTransactionalAnnotationIT` and `ShortUrlTestData` had none over 120. |
| 1 | R8 | NIT | Positive control used `isIn(200, 204)` | Fixed in `LifecycleIT`: asserts exactly 200 for PATCH and 204 for DELETE. |
| 1 | R9 | NIT | `JacksonConfig` iterated `values()` | Fixed. Explicit list: `String`, `EmptyString`, `Integer`, `Float`, `Array`, `EmptyArray`. `JacksonConfigTest` passes unchanged (including `{}`, which is `Object`, still rejected by Jackson itself). No shape changed behaviour, so the loop was replaced. |
| 1 | R10 | NIT | Clock-once test covered `setActive` only | Fixed. Added `shouldTakeTheTimestampFromTheClockExactlyOncePerDeleteRequest`. |
| 1 | R11 | NIT | Class Javadoc omitted `readWrite` | Fixed. |
| 1 | R12 | NIT | Import order | Fixed in `ShortUrlServiceTest`, `ShortUrlControllerWebMvcTest`, `ShortUrlRepositoryTest`. |
| 1 | R13 | NIT | "commits first" comment | Fixed. Now says "another writer's version bump, in the same transaction". |
| 1 | R14 | | Docs | Orchestrator. |
| 2 | R1–R14 | — | Re-review of fix round 1 | **Resolved**. Verdict **APPROVE**. R1 confirmed: every `@WebMvcTest` imports `SecuritySliceTestConfiguration`, and so gets `JacksonConfig`. R9 confirmed from the 2.21.4 bytecode: no behaviour change |
| 2 | N1 | NIT | An ACTIVE fixture is still created at `NOW_MICROS`, so the `ACTIVE,true` redundant-transition `updatedAt` assertion can't fail. The flush and rollback checks still catch a real write | Open: engineer decides |
| 2 | N2 | NIT | The `JacksonConfig` Javadoc says every listed shape is consulted for Boolean; `Float` and `Array` are kept as precautions. The Implementation note still said "every `CoercionInputShape`" | Doc half fixed by the orchestrator (Implementation note). The Javadoc half is open |

**Orchestrator final verification (2026-09-30):**
- `./mvnw -q clean verify` passed (exit 0).
  - Surefire: 842 run, 0 failed.
  - Failsafe: 611 run, 0 failed (includes 167 Cucumber scenarios).
  - Merged LINE coverage: 498/500 (99.6%).
- 0 `HHH000346` lines in the build log.

### Proposed review rules (senior-engineer, US-009; for the engineer to decide)
Round 1:
1. "Any `@Configuration` that customises MVC-visible infrastructure (the `ObjectMapper`, converters, validation) must reach every `@WebMvcTest` through one shared slice configuration or meta-annotation, never through a per-test `@Import`. Otherwise slices silently test different parsing than production."
2. "A unit-test fixture that seeds timestamps or actors must use values that differ from the fixed Clock and the calling user. Otherwise "unchanged" assertions on those fields cannot fail."
3. "A "value is never logged" assertion must check every form the value takes on the way out: the raw form, and the encoded form such as D75 percent-encoding."

Round 2:

4. "Every `@WebMvcTest` imports `SecuritySliceTestConfiguration`, so it gets both the real security filter chain and production Jackson settings (D89). Slices must not import `SecurityConfig`, `UserAccountsConfig` or `JacksonConfig` one by one."
5. "Test fixtures that set up earlier state (transitions, audit fields) use a timestamp and actor different from the ones the code under test will write. Otherwise "unchanged" assertions cannot fail."

**G3 (2026-09-30):** approved by the engineer. QA observations (a)–(d) are accepted. N1 and the Javadoc half of N2 are carried into US-010. Status: **Done**.
