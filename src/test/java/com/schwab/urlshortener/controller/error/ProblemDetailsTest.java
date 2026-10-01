package com.schwab.urlshortener.controller.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.schwab.urlshortener.model.dto.FieldViolation;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
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

    @Test
    void shouldAddErrorsExtensionAfterErrorCodeWhenViolationsGiven() {
        ProblemDetail problem = ProblemDetails.of(ErrorCode.INVALID_ALIAS, "generic", "/api/x",
                List.of(new FieldViolation("alias", "rule")));

        assertThat(problem.getProperties()).containsOnlyKeys(ProblemDetails.ERROR_CODE, ProblemDetails.ERRORS);
        assertThat(problem.getProperties()).containsEntry("errorCode", "INVALID_ALIAS");
        assertThat(problem.getProperties().get(ProblemDetails.ERRORS))
                .isEqualTo(List.of(new FieldViolation("alias", "rule")));
    }

    @Test
    void shouldCopyTheViolationsDefensively() {
        List<FieldViolation> violations = new ArrayList<>(List.of(new FieldViolation("alias", "rule")));

        ProblemDetail problem = ProblemDetails.of(ErrorCode.INVALID_ALIAS, "generic", "/api/x", violations);
        violations.add(new FieldViolation("originalUrl", "later"));

        assertThat((List<?>) problem.getProperties().get(ProblemDetails.ERRORS)).hasSize(1);
    }

    @Test
    void shouldThrowNullPointerExceptionWhenViolationsAreNull() {
        assertThatThrownBy(() -> ProblemDetails.of(ErrorCode.INVALID_ALIAS, "d", "/x", null))
                .isInstanceOf(NullPointerException.class);
    }
}
