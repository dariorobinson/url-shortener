package com.schwab.urlshortener.util.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.schwab.urlshortener.controller.error.PayloadTooLargeException;
import com.schwab.urlshortener.security.ProblemDetailResponseWriter;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** US-014 H10 (D68, D130): declared and undeclared body lengths over the limit are refused before parsing. */
class RequestBodyLimitFilterTest {

    private static final int LIMIT = 1024;

    private final ObjectMapper mapper = Jackson2ObjectMapperBuilder.json().build();
    private final RequestBodyLimitFilter filter = new RequestBodyLimitFilter(LIMIT,
            new ProblemDetailResponseWriter(mapper));

    private static MockHttpServletRequest post(byte[] body, boolean declareLength) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/urls") {
            @Override
            public long getContentLengthLong() {
                return declareLength ? body.length : -1;
            }
        };
        request.setContent(body);
        return request;
    }

    @Test
    void shouldAnswer413ProblemWithoutCallingTheChainWhenTheDeclaredLengthIsOverTheLimit() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean reached = new AtomicBoolean();

        filter.doFilter(post(new byte[LIMIT + 1], true), response, new MockFilterChain() {
            @Override
            public void doFilter(ServletRequest req, ServletResponse res) {
                reached.set(true);
            }
        });

        assertThat(reached).isFalse();
        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentType()).isEqualTo("application/problem+json");
        JsonNode body = mapper.readTree(response.getContentAsByteArray());
        assertThat(body.path("errorCode").asText()).isEqualTo("PAYLOAD_TOO_LARGE");
        assertThat(body.path("instance").asText()).isEqualTo("/api/v1/urls");
    }

    @Test
    void shouldPassABodyOfExactlyTheLimit() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean reached = new AtomicBoolean();

        filter.doFilter(post(new byte[LIMIT], true), response, new MockFilterChain() {
            @Override
            public void doFilter(ServletRequest req, ServletResponse res) {
                reached.set(true);
            }
        });

        assertThat(reached).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void shouldFailReadingAnUndeclaredBodyOnceItPassesTheLimit() throws Exception {
        AtomicReference<ServletRequest> seen = new AtomicReference<>();
        filter.doFilter(post(new byte[LIMIT + 1], false), new MockHttpServletResponse(), new MockFilterChain() {
            @Override
            public void doFilter(ServletRequest req, ServletResponse res) {
                seen.set(req);
            }
        });

        assertThatThrownBy(() -> seen.get().getInputStream().readAllBytes())
                .isExactlyInstanceOf(PayloadTooLargeException.class);
    }

    @Test
    void shouldReadAnUndeclaredBodyWithinTheLimitCompletely() throws Exception {
        AtomicReference<ServletRequest> seen = new AtomicReference<>();
        filter.doFilter(post(new byte[LIMIT], false), new MockHttpServletResponse(), new MockFilterChain() {
            @Override
            public void doFilter(ServletRequest req, ServletResponse res) {
                seen.set(req);
            }
        });

        assertThat(seen.get().getInputStream().readAllBytes()).hasSize(LIMIT);
    }
}
