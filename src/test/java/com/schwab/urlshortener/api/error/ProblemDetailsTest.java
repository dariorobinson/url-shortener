package com.schwab.urlshortener.api.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import org.junit.jupiter.api.Test;
import org.springframework.http.ProblemDetail;

class ProblemDetailsTest {

    @Test
    void shouldSetStatusDetailInstanceAndErrorCodeWithDefaultTypeAndTitle() {
        ProblemDetail problem = ProblemDetails.of(ErrorCode.ACCESS_DENIED, "generic", "/api/x");

        assertThat(problem.getStatus()).isEqualTo(403);
        assertThat(problem.getDetail()).isEqualTo("generic");
        assertThat(problem.getInstance()).isEqualTo(URI.create("/api/x"));
        assertThat(problem.getType()).isEqualTo(URI.create("about:blank"));
        assertThat(problem.getTitle()).isEqualTo("Forbidden");
        assertThat(problem.getProperties()).containsOnlyKeys(ProblemDetails.ERROR_CODE);
        assertThat(problem.getProperties()).containsEntry("errorCode", "ACCESS_DENIED");
    }

    @Test
    void shouldThrowNullPointerExceptionOnEachNullArgument() {
        assertThatThrownBy(() -> ProblemDetails.of(null, "d", "/x")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ProblemDetails.of(ErrorCode.INTERNAL_ERROR, null, "/x"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ProblemDetails.of(ErrorCode.INTERNAL_ERROR, "d", null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldThrowIllegalArgumentExceptionForInvalidRequestUri() {
        assertThatThrownBy(() -> ProblemDetails.of(ErrorCode.INTERNAL_ERROR, "d", "/a b"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
