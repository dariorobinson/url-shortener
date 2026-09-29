package com.schwab.urlshortener.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;

/**
 * Writes a {@link ProblemDetail} outside Spring MVC. It must use the context's {@link ObjectMapper}:
 * only that one carries the mixin that renders {@code errorCode} as a top-level field.
 */
@RequiredArgsConstructor
class ProblemDetailResponseWriter {

    private final ObjectMapper objectMapper;

    void write(HttpServletResponse response, ProblemDetail problem) throws IOException {
        response.setStatus(problem.getStatus());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getOutputStream().write(objectMapper.writeValueAsBytes(problem));
    }
}
