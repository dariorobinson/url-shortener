package com.schwab.urlshortener.controller;

import com.schwab.urlshortener.controller.error.ErrorResponseSchema;
import com.schwab.urlshortener.service.RedirectService;
import com.schwab.urlshortener.util.validation.LocationEncoder;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * The public redirect (FR-2, D2, D7, D18, D32, D72). Admitted by filter-chain rule 7 (GET and HEAD on any single
 * segment, D32). Never declares {@code produces} and never inherits it (D70), so any {@code Accept} gets the 302.
 * HEAD is served by this GET mapping (Spring MVC) and is never counted as a click (D9, D18).
 *
 * <p>The {@code Location} value is a raw string header: it is never built from a parsed URI, a redirect view or
 * the servlet redirect helper, so the stored text is not re-derived. It is encoded by the shared
 * {@link LocationEncoder} (D75, D84). The query string of the short link is never read and never forwarded (D79).
 *
 * <p>Public API text deliberately omits decision IDs: D75 (encoding), D76 (no-store), D79 (query ignored) and
 * D55 (bad credentials give 401) are recorded here instead.
 *
 * <p>Click recording: only GET counts (D9), so GET calls {@link RedirectService#resolveAndRecordClick} and every
 * other method that reaches this handler (HEAD) calls {@link RedirectService#resolve}, which never records. The
 * service records after its read transaction has closed and fails open (D12, D93). The response builder is the same
 * for both methods.
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "Redirect")
class RedirectController {

    private final RedirectService service;

    @GetMapping("/{code}")
    @Operation(summary = "Follow a short link",
            description = """
                    Public: no credentials are needed. An ACTIVE link answers 302 with Location set to the stored \
                    URL (characters outside printable ASCII are percent-encoded as UTF-8) and \
                    Cache-Control: no-store. HEAD returns the same status and headers, is never counted and is \
                    not listed separately. A code that is unknown, deactivated, deleted or can never be a code gives \
                    the same 404 SHORT_URL_NOT_FOUND. An ACTIVE link whose expiry has passed gives 410 \
                    SHORT_URL_EXPIRED with Cache-Control: no-store, and is not counted; a deactivated or deleted \
                    link gives 404 even if it has also expired. The query string of the short link is ignored and \
                    never \
                    forwarded. Invalid Basic credentials get 401. Swagger UI's Try it out follows the \
                    redirect in the browser, where the target's CORS policy usually makes it fail, so curl -i is the \
                    better tool.""",
            parameters = @Parameter(name = "code", in = ParameterIn.PATH,
                    description = "The short code: Base62, 3 to 32 characters, case-sensitive."))
    @ApiResponse(responseCode = "302", description = "Redirect to the stored original URL",
            headers = {
                    @Header(name = "Location", description = "The stored original URL, non-ASCII percent-encoded",
                            schema = @Schema(type = "string")),
                    @Header(name = "Cache-Control", description = "Always no-store", schema = @Schema(type = "string"))
            },
            content = @Content)
    @ApiResponse(responseCode = "404",
            description = "SHORT_URL_NOT_FOUND. The code is unknown, malformed, deactivated or deleted; the "
                    + "responses are indistinguishable.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ErrorResponseSchema.class)))
    @ApiResponse(responseCode = "410",
            description = "SHORT_URL_EXPIRED. The link is active but its expiry has passed; its owner can extend or "
                    + "clear the expiry to make it redirect again.",
            headers = @Header(name = "Cache-Control", description = "Always no-store",
                    schema = @Schema(type = "string")),
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ErrorResponseSchema.class)))
    ResponseEntity<Void> redirect(@PathVariable("code") String code, HttpMethod method) {
        String target = HttpMethod.GET.equals(method) ? service.resolveAndRecordClick(code) : service.resolve(code);
        return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, LocationEncoder.encode(target))
                .cacheControl(CacheControl.noStore())                    // D76: exactly "no-store"
                .build();
    }
}
