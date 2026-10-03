package com.schwab.urlshortener.controller;

import com.schwab.urlshortener.model.Caller;
import com.schwab.urlshortener.model.CreateShortUrlCommand;
import com.schwab.urlshortener.model.ShortUrlView;
import com.schwab.urlshortener.model.UpdateShortUrlCommand;
import com.schwab.urlshortener.model.dto.CreateShortUrlRequest;
import com.schwab.urlshortener.model.dto.ShortUrlResponse;
import com.schwab.urlshortener.model.dto.ShortUrlStatsResponse;
import com.schwab.urlshortener.model.dto.UpdateShortUrlRequest;
import com.schwab.urlshortener.security.Role;
import com.schwab.urlshortener.service.ShortUrlService;
import com.schwab.urlshortener.service.exception.InvalidUpdateRequestException;
import com.schwab.urlshortener.service.exception.StatsParameter;
import com.schwab.urlshortener.util.link.ShortUrlLinks;
import jakarta.validation.Valid;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Short URL management API. {@code POST /api/v1/urls}, {@code GET /api/v1/urls/{code}},
 * {@code PATCH /api/v1/urls/{code}} and {@code GET /api/v1/urls/{code}/stats} are admitted by the filter-chain rule
 * {@code /api, /api/** -> hasRole(USER)} (rule 6 in the architecture access table; ADMIN passes through the role
 * hierarchy, D3).
 * {@code DELETE /api/v1/urls/**} is admitted by rule 5, {@code hasRole(ADMIN)}, which comes first: a USER gets 403
 * before any handler runs, whatever the code (D3). Anonymous callers get 401 from the entry point (D30).
 *
 * <p>Never annotate this class or its methods with {@code @Transactional}: the service owns its transactions
 * (one {@code REQUIRES_NEW} per insert attempt, a read-only template for reads, a read-write template for
 * PATCH and DELETE). Ownership rules (D4, D13) live
 * only in the service; the controller merely tells it who is calling.
 *
 * <p>The class-level {@code produces = application/json} (D70) makes an unacceptable {@code Accept} header fail
 * with 406 at mapping lookup, before the body is read or the service runs, so nothing is created. It must stay
 * exactly {@code application/json}: adding {@code application/problem+json} would let that header match and
 * label a 201 body as a problem document. It must never appear on the redirect controller. The advice still
 * writes errors as {@code application/problem+json}, because {@code produces} does not restrict its output.
 */
@RestController
// D70: 406 at mapping lookup for an unacceptable Accept, before anything is created. application/json only.
@RequestMapping(path = ShortUrlController.BASE_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
class ShortUrlController {

    static final String BASE_PATH = ShortUrlLinks.MANAGEMENT_PATH;

    /** D100: the only query parameter names the stats endpoint accepts, each at most once. */
    static final Set<String> STATS_PARAMETERS = Arrays.stream(StatsParameter.values())
            .map(StatsParameter::wireName).collect(Collectors.toUnmodifiableSet());

    private final ShortUrlService service;
    private final ShortUrlLinks links;

    /**
     * Creates a short code for {@code originalUrl} (rules in {@link CreateShortUrlRequest}). An alias is used as the
     * code and gives {@code customAlias} true; one that already exists in any status gives 409
     * {@code ALIAS_ALREADY_EXISTS}. Without an alias a random code is generated, so the same URL twice gives two
     * codes. Answers 201 with a {@code Location} header naming the management resource. Other outcomes: 400
     * ({@code VALIDATION_FAILED}, {@code MALFORMED_REQUEST}, {@code INVALID_URL}, {@code INVALID_ALIAS}), 401, 406
     * (the only response type is application/json, rejected before anything is created), 415 and 503
     * {@code SHORT_CODE_UNAVAILABLE}. After an unexpected 409 caused by a lost 201, {@code GET /api/v1/urls/{alias}}
     * returns 200 only if the alias is the caller's own, which confirms the earlier create succeeded.
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<ShortUrlResponse> create(@Valid @RequestBody CreateShortUrlRequest request,
            Authentication authentication) {
        // authentication.getName() is the configured lowercase username, whatever case the client typed (D54).
        ShortUrlView view = service.create(new CreateShortUrlCommand(request.originalUrl(), request.alias(),
                authentication.getName(), instantOf(request.expiresAt())));
        return ResponseEntity.created(links.location(view.shortCode())).body(ShortUrlResponse.from(view, links));
    }

    /**
     * Returns the short URL only to its creator or to an ADMIN. A code that is unknown, malformed, deleted or owned
     * by someone else gives the same 404 {@code SHORT_URL_NOT_FOUND}, never 403 (D4, D13). DEACTIVATED links are
     * returned, with their status. The code is case-sensitive (D6).
     */
    @GetMapping("/{code}")
    ShortUrlResponse get(@PathVariable("code") String code, Authentication authentication) {
        return ShortUrlResponse.from(service.get(code, callerOf(authentication)), links);
    }

    /**
     * Returns the all-time click count, the latest click and a per-day breakdown with one entry for every local date
     * from {@code from} to {@code to}, including days with 0 clicks. Days are local calendar days in the requested
     * time zone (IANA IDs only; offsets and abbreviations get 400). {@code to} defaults to today and {@code from} to
     * 29 days before {@code to}; the window holds at most 366 days. Only {@code timezone}, {@code from} and
     * {@code to} are accepted, each at most once (D100). Only the creator or an ADMIN may read the stats; anyone else
     * gets the same 404 as for an unknown code (D4, D13). Parameters are validated before the link is looked up, so
     * invalid parameters give 400 even for a code the caller cannot see.
     */
    @GetMapping("/{code}/stats")
    ShortUrlStatsResponse stats(@PathVariable("code") String code,
            @RequestParam MultiValueMap<String, String> query,
            Authentication authentication) throws ServletRequestBindingException {
        requireOnlyKnownSingleParameters(query);
        return ShortUrlStatsResponse.from(service.stats(code, query.getFirst(StatsParameter.TIMEZONE.wireName()),
                query.getFirst(StatsParameter.FROM.wireName()), query.getFirst(StatsParameter.TO.wireName()),
                callerOf(authentication)));
    }

    /**
     * D100: an unknown or repeated query parameter name is a malformed request. The name is never echoed, so the
     * exception text is fixed.
     */
    private static void requireOnlyKnownSingleParameters(MultiValueMap<String, String> query)
            throws ServletRequestBindingException {
        for (var entry : query.entrySet()) {
            if (!STATS_PARAMETERS.contains(entry.getKey()) || entry.getValue().size() != 1) {
                throw new ServletRequestBindingException("Unexpected or repeated query parameter");
            }
        }
    }

    /**
     * Deactivates or reactivates a short URL, or changes its expiry (D34, D114, D122). {@code {"active": false}}
     * deactivates and {@code {"active": true}} reactivates; {@code {"expiresAt": "..."}} sets, extends or shortens
     * the expiry; {@code {"expiresAt": null}} clears it; omitting {@code expiresAt} leaves it unchanged. A body with
     * neither field gives 400 {@code VALIDATION_FAILED}; a non-boolean {@code active} gives 400
     * {@code MALFORMED_REQUEST}. A redundant {@code active} change gives 409 {@code SHORT_URL_ALREADY_DEACTIVATED}
     * or {@code SHORT_URL_ALREADY_ACTIVE} and nothing is applied; 409 {@code CONCURRENT_MODIFICATION} means another
     * request changed the link at the same moment. Only the creator or an ADMIN may update; anyone else gets the
     * same 404 as for an unknown code. Only application/json is accepted as the request type (415 otherwise).
     */
    @PatchMapping(path = "/{code}", consumes = MediaType.APPLICATION_JSON_VALUE)   // D88: application/json only
    ShortUrlResponse update(@PathVariable("code") String code, @Valid @RequestBody UpdateShortUrlRequest request,
            Authentication authentication) {
        if ((!request.hasActive() && !request.hasExpiresAt()) || (request.hasActive() && request.getActive() == null)) {
            throw new InvalidUpdateRequestException();                      // D114: checked before any lookup
        }
        UpdateShortUrlCommand command = new UpdateShortUrlCommand(request.getActive(), request.hasExpiresAt(),
                instantOf(request.getExpiresAt()));
        return ShortUrlResponse.from(service.update(code, command, callerOf(authentication)), links);
    }

    /**
     * Soft-deletes a short URL (ADMIN only, D3). The row is kept for audit; afterwards the code gets 404 everywhere,
     * for ADMIN too, and can never be reused (a create with that alias gets 409 {@code ALIAS_ALREADY_EXISTS}).
     * Deleting a DEACTIVATED link is allowed. A USER always gets 403 {@code ACCESS_DENIED} from the filter chain,
     * whatever the code. 409 {@code CONCURRENT_MODIFICATION} means another request changed the link at the same
     * moment.
     */
    @DeleteMapping("/{code}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable("code") String code, Authentication authentication) {
        service.delete(code, callerOf(authentication));
    }

    /**
     * Exact authority check. The role hierarchy is applied only by the authorization managers, so
     * {@code getAuthorities()} holds {@code ROLE_ADMIN} alone for the admin (never the implied {@code ROLE_USER}).
     * The username is the configured lowercase name, whatever case the client typed (D54).
     */
    private static Instant instantOf(OffsetDateTime dateTime) {
        return dateTime == null ? null : dateTime.toInstant();
    }

    static Caller callerOf(Authentication authentication) {
        boolean admin = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(Role.ADMIN.authority()::equals);
        return new Caller(authentication.getName(), admin);
    }
}
