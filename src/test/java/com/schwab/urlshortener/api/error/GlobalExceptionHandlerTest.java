package com.schwab.urlshortener.api.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import org.junit.jupiter.api.Test;
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
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;

/** Handler branches that the controller slice cannot reach: security rethrow and the D69 status fallbacks. */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();
    private final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/urls");

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
            assertThat(event.getThrowableProxy().getClassName()).isEqualTo(AsyncRequestTimeoutException.class.getName());
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
}
