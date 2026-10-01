package com.schwab.urlshortener.controller.error;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.MDC;

/** US-014 AC1: only a 500 carries the request ID, and only when a request is in progress. */
class ProblemDetailsRequestIdTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void shouldAddTheRequestIdToAnInternalError() {
        MDC.put(ProblemDetails.REQUEST_ID, "abc-123");

        assertThat(ProblemDetails.of(ErrorCode.INTERNAL_ERROR, "x", "/p").getProperties())
                .containsEntry("requestId", "abc-123");
    }

    @Test
    void shouldOmitTheRequestIdFromAnInternalErrorOutsideARequest() {
        assertThat(ProblemDetails.of(ErrorCode.INTERNAL_ERROR, "x", "/p").getProperties())
                .doesNotContainKey("requestId");
    }

    @ParameterizedTest
    @EnumSource(value = ErrorCode.class, names = "INTERNAL_ERROR", mode = EnumSource.Mode.EXCLUDE)
    void shouldNeverAddTheRequestIdToAnyOtherError(ErrorCode code) {
        MDC.put(ProblemDetails.REQUEST_ID, "abc-123");

        assertThat(ProblemDetails.of(code, "x", "/p").getProperties()).doesNotContainKey("requestId");
    }
}
