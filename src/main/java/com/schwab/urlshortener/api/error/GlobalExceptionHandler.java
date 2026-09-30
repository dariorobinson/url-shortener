package com.schwab.urlshortener.api.error;

import com.schwab.urlshortener.service.exception.AliasAlreadyExistsException;
import com.schwab.urlshortener.service.exception.InvalidAliasException;
import com.schwab.urlshortener.service.exception.InvalidUrlException;
import com.schwab.urlshortener.service.exception.ShortCodeUnavailableException;
import com.schwab.urlshortener.service.exception.ShortUrlNotFoundException;
import com.schwab.urlshortener.shortcode.SecureRandomShortCodeGenerator;
import com.schwab.urlshortener.validation.UrlValidator;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * The single {@code @RestControllerAdvice} (D31, D56, D61, D63). Every body is built by
 * {@link ProblemDetails}, so Spring's default {@code detail} texts, parser messages and exception
 * messages never reach clients. Bodies are written by Spring MVC's converters with the context
 * {@code ObjectMapper}, which keeps {@code errorCode} at the top level, exactly as the security handlers do.
 *
 * <p>Out of reach of this advice: {@code StrictHttpFirewall} rejections and errors raised in filters before
 * the {@code DispatcherServlet}.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    static final String URL_RULE = "must be an absolute http or https URL with an ASCII host (use punycode for "
            + "internationalised domains), at most " + UrlValidator.MAX_LENGTH
            + " characters, without user info, and not on this service's host";

    static final String ALIAS_RULE = "must be " + SecureRandomShortCodeGenerator.MIN_LENGTH + " to "
            + SecureRandomShortCodeGenerator.MAX_LENGTH + " characters from A-Z, a-z and 0-9, and not a reserved word";

    private static final Map<ErrorCode, String> DETAIL = detailTexts();

    private static Map<ErrorCode, String> detailTexts() {
        Map<ErrorCode, String> texts = new EnumMap<>(ErrorCode.class);
        texts.put(ErrorCode.VALIDATION_FAILED, "The request body failed validation.");
        texts.put(ErrorCode.MALFORMED_REQUEST, "The request could not be read.");
        texts.put(ErrorCode.INVALID_URL, "The originalUrl is not acceptable.");
        texts.put(ErrorCode.INVALID_ALIAS, "The alias is not acceptable.");
        texts.put(ErrorCode.ALIAS_ALREADY_EXISTS, "The alias is already in use.");
        texts.put(ErrorCode.SHORT_URL_NOT_FOUND, "The short URL was not found.");
        texts.put(ErrorCode.SHORT_CODE_UNAVAILABLE, "A short code could not be allocated. Retry later.");
        texts.put(ErrorCode.RESOURCE_NOT_FOUND, "The requested resource was not found.");
        texts.put(ErrorCode.METHOD_NOT_ALLOWED, "The request method is not supported for this resource.");
        texts.put(ErrorCode.NOT_ACCEPTABLE, "The requested response format is not supported.");
        texts.put(ErrorCode.UNSUPPORTED_MEDIA_TYPE, "The request content type is not supported.");
        texts.put(ErrorCode.INTERNAL_ERROR, "An unexpected error occurred.");
        return texts;
    }

    // ---- Spring MVC exceptions (the base class maps the types and keeps headers such as Allow and Accept)

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<FieldViolation> violations = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> new FieldViolation(e.getField(), e.getDefaultMessage() == null ? "is invalid"
                        : e.getDefaultMessage()))
                .sorted(Comparator.comparing(FieldViolation::field).thenComparing(FieldViolation::message))
                .toList();
        ProblemDetail body = ProblemDetails.of(ErrorCode.VALIDATION_FAILED,
                DETAIL.get(ErrorCode.VALIDATION_FAILED), path(request), violations);
        return super.handleExceptionInternal(ex, body, headers, ErrorCode.VALIDATION_FAILED.status(), request);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        // Never the parser's message, location or class name.
        ProblemDetail body = ProblemDetails.of(ErrorCode.MALFORMED_REQUEST,
                DETAIL.get(ErrorCode.MALFORMED_REQUEST), path(request));
        return super.handleExceptionInternal(ex, body, headers, ErrorCode.MALFORMED_REQUEST.status(), request);
    }

    /**
     * Every other Spring MVC exception ends up here. The body is always rebuilt, so no default Spring text is
     * ever returned. A framework failure that maps to 500 is logged here, once, in the same format as the
     * catch-all (method and path only, never the query string or body).
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
            HttpStatusCode statusCode, WebRequest request) {
        ErrorCode code = codeFor(statusCode);
        if (code == ErrorCode.INTERNAL_ERROR) {
            log.error("Unhandled exception: method={} path={}", ((ServletWebRequest) request).getRequest().getMethod(),
                    path(request), ex);
        }
        ProblemDetail replacement = ProblemDetails.of(code, DETAIL.get(code), path(request));
        return super.handleExceptionInternal(ex, replacement, headers, code.status(), request);
    }

    private static ErrorCode codeFor(HttpStatusCode status) {
        return switch (status.value()) {
            case 404 -> ErrorCode.RESOURCE_NOT_FOUND;
            case 405 -> ErrorCode.METHOD_NOT_ALLOWED;
            case 406 -> ErrorCode.NOT_ACCEPTABLE;
            case 415 -> ErrorCode.UNSUPPORTED_MEDIA_TYPE;
            default -> status.is5xxServerError() ? ErrorCode.INTERNAL_ERROR : ErrorCode.MALFORMED_REQUEST;
        };
    }

    // ---- Service exceptions

    @ExceptionHandler(InvalidUrlException.class)
    ResponseEntity<ProblemDetail> handleInvalidUrl(HttpServletRequest request) {
        return respond(ProblemDetails.of(ErrorCode.INVALID_URL, DETAIL.get(ErrorCode.INVALID_URL),
                request.getRequestURI(), List.of(new FieldViolation("originalUrl", URL_RULE))));
    }

    @ExceptionHandler(InvalidAliasException.class)
    ResponseEntity<ProblemDetail> handleInvalidAlias(HttpServletRequest request) {
        return respond(ProblemDetails.of(ErrorCode.INVALID_ALIAS, DETAIL.get(ErrorCode.INVALID_ALIAS),
                request.getRequestURI(), List.of(new FieldViolation("alias", ALIAS_RULE))));
    }

    @ExceptionHandler(AliasAlreadyExistsException.class)
    ResponseEntity<ProblemDetail> handleAliasAlreadyExists(HttpServletRequest request) {
        return respond(plain(ErrorCode.ALIAS_ALREADY_EXISTS, request));
    }

    @ExceptionHandler(ShortCodeUnavailableException.class)
    ResponseEntity<ProblemDetail> handleShortCodeUnavailable(HttpServletRequest request) {
        return respond(plain(ErrorCode.SHORT_CODE_UNAVAILABLE, request));
    }

    /** D72, D13, D4, D74: malformed, unknown, deleted and not-yours all give this one body. The service logged. */
    @ExceptionHandler(ShortUrlNotFoundException.class)
    ResponseEntity<ProblemDetail> handleShortUrlNotFound(HttpServletRequest request) {
        return respond(plain(ErrorCode.SHORT_URL_NOT_FOUND, request));
    }

    // ---- Catch-all

    /**
     * Security exceptions are rethrown so that a future method-security failure still reaches
     * {@code ExceptionTranslationFilter} and becomes 401 or 403, not 500. The stack trace goes to the server
     * log only.
     */
    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> handleUnexpected(Exception ex, HttpServletRequest request) throws Exception {
        if (ex instanceof AccessDeniedException || ex instanceof AuthenticationException) {
            throw ex;
        }
        log.error("Unhandled exception: method={} path={}", request.getMethod(), request.getRequestURI(), ex);
        return respond(plain(ErrorCode.INTERNAL_ERROR, request));
    }

    private static ProblemDetail plain(ErrorCode code, HttpServletRequest request) {
        return ProblemDetails.of(code, DETAIL.get(code), request.getRequestURI());
    }

    private static ResponseEntity<ProblemDetail> respond(ProblemDetail problem) {
        return ResponseEntity.status(problem.getStatus()).body(problem);
    }

    private static String path(WebRequest request) {
        return ((ServletWebRequest) request).getRequest().getRequestURI();
    }
}
