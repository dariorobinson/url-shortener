package com.schwab.urlshortener.api.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.schwab.urlshortener.domain.exception.ShortUrlDeletedException;
import com.schwab.urlshortener.service.exception.ShortUrlNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;

/** Handler branches that the controller slice cannot reach: security rethrow and the D69 status fallbacks. */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();
    private final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/urls");

    @Test
    void shouldMapShortUrlNotFoundToExactlyTheBaseKeysAndLogNothingAtWarnOrError() {
        MockHttpServletRequest getRequest = new MockHttpServletRequest("GET", "/api/v1/urls/aB3dE9x");
        Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        try {
            ResponseEntity<ProblemDetail> response = handler.handleShortUrlNotFound(getRequest);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            ProblemDetail body = response.getBody();
            assertThat(body.getProperties()).containsOnlyKeys("errorCode");
            assertThat(body.getProperties()).containsEntry("errorCode", "SHORT_URL_NOT_FOUND");
            assertThat(body.getType().toString()).isEqualTo("about:blank");
            assertThat(body.getTitle()).isEqualTo("Not Found");
            assertThat(body.getStatus()).isEqualTo(404);
            assertThat(body.getDetail()).isEqualTo("The short URL was not found.");
            assertThat(body.getInstance().toString()).isEqualTo("/api/v1/urls/aB3dE9x");
            assertThat(logs.list).noneSatisfy(e -> assertThat(e.getLevel().isGreaterOrEqual(Level.WARN)).isTrue());
        } finally {
            logger.detachAppender(logs);
        }
    }

    @Test
    void shouldRethrowAccessDeniedSoTheSecurityFilterCanMapItTo403() {
        AccessDeniedException denied = new AccessDeniedException("denied");

        assertThatThrownBy(() -> handler.handleUnexpected(denied, request)).isSameAs(denied);
    }

    @Test
    void shouldRethrowAuthenticationExceptionsSoTheSecurityFilterCanMapThemTo401() {
        BadCredentialsException bad = new BadCredentialsException("bad");

        assertThatThrownBy(() -> handler.handleUnexpected(bad, request)).isSameAs(bad);
    }

    @Test
    void shouldMapAnyOtherFrameworkClientErrorToMalformedRequestWithAGenericDetail() throws Exception {
        var ex = new MissingServletRequestParameterException("secret-parameter-name", "String");

        ResponseEntity<Object> response = handler.handleException(ex, new ServletWebRequest(request));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        ProblemDetail body = (ProblemDetail) response.getBody();
        assertThat(body.getProperties()).containsEntry("errorCode", "MALFORMED_REQUEST");
        assertThat(body.getDetail()).doesNotContain("secret-parameter-name");
        assertThat(body.getProperties()).doesNotContainKey("errors");
    }

    @Test
    void shouldMapAFrameworkServerErrorToInternalErrorAndLogItOnceWithoutTheQueryString() throws Exception {
        MockHttpServletRequest withQuery = new MockHttpServletRequest("GET", "/api/v1/urls");
        withQuery.setQueryString("token=secret-query-marker");
        Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        try {
            AsyncRequestTimeoutException ex = new AsyncRequestTimeoutException();

            ResponseEntity<Object> response = handler.handleException(ex, new ServletWebRequest(withQuery));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(((ProblemDetail) response.getBody()).getProperties())
                    .containsEntry("errorCode", "INTERNAL_ERROR");
            assertThat(logs.list).hasSize(1);
            ILoggingEvent event = logs.list.get(0);
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            assertThat(event.getFormattedMessage())
                    .isEqualTo("Unhandled exception: method=GET path=/api/v1/urls");
            assertThat(event.getThrowableProxy().getClassName())
                    .isEqualTo(AsyncRequestTimeoutException.class.getName());
        } finally {
            logger.detachAppender(logs);
        }
    }

    @Test
    void shouldKeepTheAcceptHeaderOfA415AndReplaceSpringsDetail() throws Exception {
        var ex = new HttpMediaTypeNotSupportedException(MediaType.TEXT_PLAIN, List.of(MediaType.APPLICATION_JSON));

        ResponseEntity<Object> response = handler.handleException(ex, new ServletWebRequest(request));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        ProblemDetail body = (ProblemDetail) response.getBody();
        assertThat(body.getProperties()).containsEntry("errorCode", "UNSUPPORTED_MEDIA_TYPE");
        assertThat(body.getDetail()).isEqualTo("The request content type is not supported.");
        assertThat(response.getHeaders().getFirst(HttpHeaders.ACCEPT)).isEqualTo("application/json");
    }

    @Test
    void shouldSetTheInstanceToThePathWithoutTheQueryString() throws Exception {
        MockHttpServletRequest withQuery = new MockHttpServletRequest("GET", "/api/v1/urls");
        withQuery.setQueryString("token=secret-query-marker");

        ResponseEntity<Object> response = handler.handleException(
                new MissingServletRequestParameterException("p", "String"), new ServletWebRequest(withQuery));

        ProblemDetail body = (ProblemDetail) response.getBody();
        assertThat(body.getInstance().toString()).isEqualTo("/api/v1/urls");
    }

    private enum Lifecycle {
        DELETED(HttpStatus.NOT_FOUND, "SHORT_URL_NOT_FOUND", "The short URL was not found."),
        ALREADY_DEACTIVATED(HttpStatus.CONFLICT, "SHORT_URL_ALREADY_DEACTIVATED",
                "The short URL is already deactivated."),
        ALREADY_ACTIVE(HttpStatus.CONFLICT, "SHORT_URL_ALREADY_ACTIVE", "The short URL is already active."),
        CONCURRENT(HttpStatus.CONFLICT, "CONCURRENT_MODIFICATION",
                "The short URL was changed by another request. Read it again and retry if still needed.");

        final HttpStatus status;
        final String errorCode;
        final String detail;

        Lifecycle(HttpStatus status, String errorCode, String detail) {
            this.status = status;
            this.errorCode = errorCode;
            this.detail = detail;
        }

        ResponseEntity<ProblemDetail> handle(GlobalExceptionHandler handler, MockHttpServletRequest request) {
            return switch (this) {
                case DELETED -> handler.handleShortUrlNotFound(request);
                case ALREADY_DEACTIVATED -> handler.handleAlreadyDeactivated(request);
                case ALREADY_ACTIVE -> handler.handleAlreadyActive(request);
                case CONCURRENT -> handler.handleConcurrentModification(request);
            };
        }
    }

    @ParameterizedTest
    @EnumSource(Lifecycle.class)
    void shouldMapEachLifecycleOutcomeToExactlyTheBaseKeysAndLogNothingAtWarnOrError(Lifecycle outcome) {
        MockHttpServletRequest patch = new MockHttpServletRequest("PATCH", "/api/v1/urls/aB3dE9x");
        Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        try {
            ResponseEntity<ProblemDetail> response = outcome.handle(handler, patch);

            assertThat(response.getStatusCode()).isEqualTo(outcome.status);
            ProblemDetail body = response.getBody();
            assertThat(body.getProperties()).containsOnlyKeys("errorCode");
            assertThat(body.getProperties()).containsEntry("errorCode", outcome.errorCode);
            assertThat(body.getDetail()).isEqualTo(outcome.detail);
            assertThat(body.getStatus()).isEqualTo(outcome.status.value());
            assertThat(body.getInstance().toString()).isEqualTo("/api/v1/urls/aB3dE9x");
            assertThat(body.getType().toString()).isEqualTo("about:blank");
            assertThat(logs.list).isEmpty();
        } finally {
            logger.detachAppender(logs);
        }
    }

    @Test
    void shouldGiveShortUrlDeletedTheSameHandlerAndTheSameBodyAsNotFoundWithoutReadingTheException()
            throws Exception {
        var handlerMethod = GlobalExceptionHandler.class.getDeclaredMethod("handleShortUrlNotFound",
                HttpServletRequest.class);
        var mapped = handlerMethod.getAnnotation(ExceptionHandler.class);

        // One method serves both exceptions, and it takes only the request, so the exception's message (which
        // contains the code) cannot reach the body (D74).
        assertThat(mapped.value()).containsExactlyInAnyOrder(
                ShortUrlNotFoundException.class,
                ShortUrlDeletedException.class);
        assertThat(handlerMethod.getParameterCount()).isEqualTo(1);
    }
}
