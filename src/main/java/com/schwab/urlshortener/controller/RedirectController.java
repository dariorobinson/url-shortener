package com.schwab.urlshortener.controller;

import com.schwab.urlshortener.service.RedirectService;
import com.schwab.urlshortener.util.validation.LocationEncoder;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
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
 * <p>Click recording: only GET counts (D9), so GET calls {@link RedirectService#resolveAndRecordClick} and every
 * other method that reaches this handler (HEAD) calls {@link RedirectService#resolve}, which never records. The
 * service records after its read transaction has closed and fails open (D12, D93). The response builder is the same
 * for both methods.
 */
@RestController
@RequiredArgsConstructor
class RedirectController {

    private final RedirectService service;

    /**
     * Public: no credentials are needed. An ACTIVE link answers 302 with {@code Location} set to the stored URL
     * (characters outside printable ASCII are percent-encoded as UTF-8, D75) and {@code Cache-Control: no-store}
     * (D76). A code that is unknown, deactivated, deleted or can never be a code gives the same 404
     * {@code SHORT_URL_NOT_FOUND}. An ACTIVE link whose expiry has passed gives 410 {@code SHORT_URL_EXPIRED} with
     * {@code Cache-Control: no-store} and is not counted; a deactivated or deleted link gives 404 even if it has
     * also expired. The query string of the short link is ignored (D79). Invalid Basic credentials get 401 (D55).
     */
    @GetMapping("/{code}")
    ResponseEntity<Void> redirect(@PathVariable("code") String code, HttpMethod method) {
        String target = HttpMethod.GET.equals(method) ? service.resolveAndRecordClick(code) : service.resolve(code);
        return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, LocationEncoder.encode(target))
                .cacheControl(CacheControl.noStore())                    // D76: exactly "no-store"
                .build();
    }
}
