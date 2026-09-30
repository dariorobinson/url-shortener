package com.schwab.urlshortener.api;

import com.schwab.urlshortener.api.dto.CreateShortUrlRequest;
import com.schwab.urlshortener.api.dto.ShortUrlResponse;
import com.schwab.urlshortener.api.error.ErrorResponseSchema;
import com.schwab.urlshortener.config.OpenApiConfig;
import com.schwab.urlshortener.service.CreateShortUrlCommand;
import com.schwab.urlshortener.service.ShortUrlService;
import com.schwab.urlshortener.service.ShortUrlView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Short URL management API. {@code POST /api/v1/urls} is admitted by the filter-chain rule
 * {@code /api, /api/** -> hasRole(USER)} (rule 6 in the architecture access table; ADMIN passes through the
 * role hierarchy, D3). Anonymous callers get 401 from the entry point (D30).
 *
 * <p>Never annotate this class or its methods with {@code @Transactional}: the service owns one
 * {@code REQUIRES_NEW} transaction per insert attempt.
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
@Tag(name = "Short URLs")
@SecurityRequirement(name = OpenApiConfig.BASIC_AUTH)
class ShortUrlController {

    static final String BASE_PATH = "/api/v1/urls";

    private static final String PROBLEM_JSON = "application/problem+json";

    private final ShortUrlService service;
    private final ShortUrlLinks links;

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Create a short URL",
            description = """
                    Creates a short code for originalUrl. originalUrl must be an absolute http or https URL of at \
                    most 2048 characters, without embedded credentials, and not on this service's own host. \
                    Non-ASCII (IDN) hosts are rejected with 400 INVALID_URL: clients must submit the punycode \
                    (xn--) form. An optional alias is used as the code and gives customAlias true; an alias that \
                    already exists in any status gives 409 ALIAS_ALREADY_EXISTS. Without an alias a random code \
                    is generated. Submitting the same URL twice creates two different codes. Errors are RFC 7807 \
                    problem documents with an errorCode; 400 responses for VALIDATION_FAILED, INVALID_URL and \
                    INVALID_ALIAS also carry an errors array naming the field.""")
    @ApiResponse(responseCode = "201", description = "Created",
            headers = @Header(name = "Location", description = "The management resource, /api/v1/urls/{shortCode}",
                    schema = @Schema(type = "string")),
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ShortUrlResponse.class)))
    @ApiResponse(responseCode = "400",
            description = "VALIDATION_FAILED, MALFORMED_REQUEST, INVALID_URL or INVALID_ALIAS",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ErrorResponseSchema.class)))
    @ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ErrorResponseSchema.class)))
    @ApiResponse(responseCode = "406",
            description = "NOT_ACCEPTABLE. The only response type is application/json; an unacceptable Accept is "
                    + "rejected before anything is created. An unparseable Accept also gets 406, with no body.",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ErrorResponseSchema.class)))
    @ApiResponse(responseCode = "409",
            description = "ALIAS_ALREADY_EXISTS. If this 409 is unexpected because an earlier create's 201 was "
                    + "lost (the response failed to arrive or the client disconnected after the commit), call "
                    + "GET /api/v1/urls/{alias}. It returns 200 only if the alias belongs to the caller, which "
                    + "confirms the earlier create succeeded.",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ErrorResponseSchema.class)))
    @ApiResponse(responseCode = "415", description = "UNSUPPORTED_MEDIA_TYPE",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ErrorResponseSchema.class)))
    @ApiResponse(responseCode = "503", description = "SHORT_CODE_UNAVAILABLE",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ErrorResponseSchema.class)))
    ResponseEntity<ShortUrlResponse> create(@Valid @RequestBody CreateShortUrlRequest request,
            @Parameter(hidden = true) Authentication authentication) {
        // authentication.getName() is the configured lowercase username, whatever case the client typed (D54).
        ShortUrlView view = service.create(
                new CreateShortUrlCommand(request.originalUrl(), request.alias(), authentication.getName()));
        return ResponseEntity.created(links.location(view.shortCode())).body(ShortUrlResponse.from(view, links));
    }
}
