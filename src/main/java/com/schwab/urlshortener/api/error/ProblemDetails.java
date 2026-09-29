package com.schwab.urlshortener.api.error;

import java.net.URI;
import java.util.Objects;
import org.springframework.http.ProblemDetail;

/**
 * The one factory for error bodies (D30, D31). Every error response has the base shape {@code type}
 * ({@code about:blank}), {@code title}, {@code status}, {@code detail}, {@code instance} and
 * {@code errorCode}. Security errors (401, 403) add nothing else; other errors may add documented
 * extensions on top of the same base.
 */
public final class ProblemDetails {

    public static final String ERROR_CODE = "errorCode";

    private ProblemDetails() {
    }

    /**
     * @param code the catalogue entry, which also fixes the HTTP status
     * @param detail a generic, non-leaking message
     * @param requestUri the request path, without a query string
     * @throws NullPointerException if any argument is null
     * @throws IllegalArgumentException if requestUri is not a valid URI reference
     */
    public static ProblemDetail of(ErrorCode code, String detail, String requestUri) {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(detail, "detail");
        Objects.requireNonNull(requestUri, "requestUri");
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(code.status(), detail);
        // Relies on Tomcat's default path-character rules and the strict firewall, which keep characters that
        // are illegal in a URI out of getRequestURI(); otherwise URI.create throws. instance is a D56 base key.
        problem.setInstance(URI.create(requestUri));
        problem.setProperty(ERROR_CODE, code.name());
        return problem;
    }
}
