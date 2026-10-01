package com.schwab.urlshortener.controller.error;

import com.schwab.urlshortener.model.dto.FieldViolation;
import java.net.URI;
import java.util.List;
import java.util.Objects;
import org.slf4j.MDC;
import org.springframework.http.ProblemDetail;

/**
 * The one factory for error bodies (D30, D31). Every error response has the base shape {@code type}
 * ({@code about:blank}), {@code title}, {@code status}, {@code detail}, {@code instance} and
 * {@code errorCode}. Security errors (401, 403) add nothing else; other errors may add documented
 * extensions on top of the same base: {@code errors} (D56) and, on 500 only, {@code requestId}.
 */
public final class ProblemDetails {

    public static final String ERROR_CODE = "errorCode";

    /** Field-level violations, present only on VALIDATION_FAILED, INVALID_URL and INVALID_ALIAS (D56, D63). */
    public static final String ERRORS = "errors";

    /**
     * The request ID (US-014 AC1): the MDC key set by the request-ID filter, and the extension carried by 500 bodies
     * only, so a client can quote it to support.
     */
    public static final String REQUEST_ID = "requestId";

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
        String requestId = MDC.get(REQUEST_ID);
        if (code == ErrorCode.INTERNAL_ERROR && requestId != null) {
            problem.setProperty(REQUEST_ID, requestId);
        }
        return problem;
    }

    /**
     * Same as {@link #of(ErrorCode, String, String)}, plus the {@code errors} extension (D56).
     *
     * @param errors the violations, already sorted; copied defensively; never carry the rejected value
     * @throws NullPointerException if any argument is null
     * @throws IllegalArgumentException if requestUri is not a valid URI reference
     */
    public static ProblemDetail of(ErrorCode code, String detail, String requestUri, List<FieldViolation> errors) {
        Objects.requireNonNull(errors, "errors");
        ProblemDetail problem = of(code, detail, requestUri);
        problem.setProperty(ERRORS, List.copyOf(errors));
        return problem;
    }
}
