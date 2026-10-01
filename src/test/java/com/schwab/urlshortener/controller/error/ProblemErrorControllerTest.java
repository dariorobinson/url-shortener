package com.schwab.urlshortener.controller.error;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.RequestDispatcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

/** US-014 H9 (D82) and AC1: what /error answers, directly and for a forwarded error. */
class ProblemErrorControllerTest {

    private final ProblemErrorController controller = new ProblemErrorController();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void shouldAnswer404ResourceNotFoundWhenErrorIsRequestedDirectly() {
        ResponseEntity<ProblemDetail> response = controller.error(new MockHttpServletRequest("GET", "/error"));

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody().getProperties()).containsEntry("errorCode", "RESOURCE_NOT_FOUND");
        assertThat(response.getBody().getInstance()).hasToString("/error");
    }

    @Test
    void shouldAnswerAForwarded400AsMalformedRequestForTheOriginalPath() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/error");
        request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, 400);
        request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, "/abc%25");

        ResponseEntity<ProblemDetail> response = controller.error(request);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().getProperties()).containsEntry("errorCode", "MALFORMED_REQUEST");
        assertThat(response.getBody().getInstance()).hasToString("/abc%25");
        assertThat(response.getBody().getProperties()).doesNotContainKey("requestId");
    }

    @Test
    void shouldAnswerAForwarded500AsInternalErrorWithTheRequestId() {
        MDC.put(ProblemDetails.REQUEST_ID, "req-500");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/error");
        request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, 500);

        ResponseEntity<ProblemDetail> response = controller.error(request);

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getBody().getProperties()).containsEntry("errorCode", "INTERNAL_ERROR")
                .containsEntry("requestId", "req-500");
    }
}
