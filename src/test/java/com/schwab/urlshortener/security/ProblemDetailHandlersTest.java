package com.schwab.urlshortener.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.schwab.urlshortener.api.error.ErrorCode;
import com.schwab.urlshortener.api.error.ProblemDetails;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.AuthenticationException;

/** AC1, AC4, AC8: both security errors use the shared ProblemDetail base shape (D30, D31, D56). */
@ExtendWith(OutputCaptureExtension.class)
class ProblemDetailHandlersTest {

    private static final String LEAK = "secret-marker alice";
    private static final Set<String> BASE_KEYS = Set.of("type", "title", "status", "detail", "instance", "errorCode");

    private final ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json().build();
    private final ProblemDetailResponseWriter writer = new ProblemDetailResponseWriter(objectMapper);
    private final ProblemDetailAuthenticationEntryPoint entryPoint = new ProblemDetailAuthenticationEntryPoint(writer);
    private final ProblemDetailAccessDeniedHandler accessDenied = new ProblemDetailAccessDeniedHandler(writer);

    private static MockHttpServletRequest request() {
        var request = new MockHttpServletRequest("GET", "/api/v1/urls");
        request.setQueryString("token=query-secret");
        return request;
    }

    private MockHttpServletResponse unauthorized(AuthenticationException cause) throws Exception {
        var response = new MockHttpServletResponse();
        entryPoint.commence(request(), response, cause);
        return response;
    }

    private MockHttpServletResponse forbidden() throws Exception {
        var response = new MockHttpServletResponse();
        accessDenied.handle(request(), response, new AccessDeniedException(LEAK));
        return response;
    }

    private Map<String, Object> body(MockHttpServletResponse response) throws Exception {
        return objectMapper.readValue(response.getContentAsByteArray(), new TypeReference<>() { });
    }

    @Test
    void shouldWrite401WithBaseShapeBasicChallengeAndProblemContentType() throws Exception {
        MockHttpServletResponse response = unauthorized(new InsufficientAuthenticationException("x"));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE)).isEqualTo("Basic realm=\"url-shortener\"");
        assertThat(MediaType.parseMediaType(response.getContentType()).isCompatibleWith(
                MediaType.APPLICATION_PROBLEM_JSON)).isTrue();
        Map<String, Object> body = body(response);
        assertThat(body).containsOnlyKeys(BASE_KEYS);
        assertThat(body).containsEntry("type", "about:blank").containsEntry("title", "Unauthorized")
                .containsEntry("status", 401).containsEntry("instance", "/api/v1/urls")
                .containsEntry("errorCode", "AUTHENTICATION_REQUIRED")
                .containsEntry("detail", ProblemDetailAuthenticationEntryPoint.DETAIL);
    }

    @Test
    void shouldWrite403WithBaseShapeAndNoChallenge() throws Exception {
        MockHttpServletResponse response = forbidden();

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE)).isNull();
        Map<String, Object> body = body(response);
        assertThat(body).containsOnlyKeys(BASE_KEYS);
        assertThat(body).containsEntry("type", "about:blank").containsEntry("title", "Forbidden")
                .containsEntry("status", 403).containsEntry("instance", "/api/v1/urls")
                .containsEntry("errorCode", "ACCESS_DENIED")
                .containsEntry("detail", ProblemDetailAccessDeniedHandler.DETAIL);
    }

    @Test
    void shouldDifferBetween401And403OnlyInStatusTitleDetailAndErrorCode() throws Exception {
        Map<String, Object> first = new LinkedHashMap<>(body(unauthorized(new BadCredentialsException("x"))));
        Map<String, Object> second = new LinkedHashMap<>(body(forbidden()));

        Set<String> differing = new java.util.TreeSet<>();
        for (String key : BASE_KEYS) {
            if (!first.get(key).equals(second.get(key))) {
                differing.add(key);
            }
        }
        assertThat(differing).containsExactlyInAnyOrder("status", "title", "detail", "errorCode");
    }

    @Test
    void shouldBuildBothBodiesThroughTheSharedFactory() throws Exception {
        assertThat(unauthorized(new BadCredentialsException("x")).getContentAsString()).isEqualTo(
                objectMapper.writeValueAsString(ProblemDetails.of(ErrorCode.AUTHENTICATION_REQUIRED,
                        ProblemDetailAuthenticationEntryPoint.DETAIL, "/api/v1/urls")));
        assertThat(forbidden().getContentAsString()).isEqualTo(
                objectMapper.writeValueAsString(ProblemDetails.of(ErrorCode.ACCESS_DENIED,
                        ProblemDetailAccessDeniedHandler.DETAIL, "/api/v1/urls")));
    }

    @Test
    void shouldNotIncludeQueryStringInInstance() throws Exception {
        assertThat(unauthorized(new BadCredentialsException("x")).getContentAsString()).doesNotContain("query-secret");
    }

    @Test
    void shouldNeverLeakExceptionMessageInBodyHeadersOrLogs(CapturedOutput output) throws Exception {
        MockHttpServletResponse first = unauthorized(new BadCredentialsException(LEAK));
        MockHttpServletResponse second = unauthorized(new InsufficientAuthenticationException(LEAK));
        MockHttpServletResponse third = forbidden();

        for (MockHttpServletResponse response : new MockHttpServletResponse[] {first, second, third}) {
            assertThat(response.getContentAsString()).doesNotContain("secret-marker").doesNotContain("alice");
            assertThat(response.getHeaderNames()).allSatisfy(name ->
                    assertThat(response.getHeader(name)).doesNotContain("secret-marker").doesNotContain("alice"));
        }
        assertThat(output.getAll()).doesNotContain("secret-marker").doesNotContain("alice")
                .doesNotContain("query-secret");
    }

    @Test
    void shouldLogFailedAuthenticationAndAccessDeniedWithPathAndExceptionTypeOnly(CapturedOutput output)
            throws Exception {
        unauthorized(new BadCredentialsException(LEAK));
        forbidden();

        assertThat(output.getAll()).contains("BadCredentialsException", "/api/v1/urls");
        assertThat(output.getAll()).doesNotContain("127.0.0.1").doesNotContain("Authorization");
    }

    @Test
    void shouldGiveIdenticalBodyAndHeadersForEveryAuthenticationFailureCause() throws Exception {
        MockHttpServletResponse anonymous = unauthorized(new InsufficientAuthenticationException("a"));
        MockHttpServletResponse bad = unauthorized(new BadCredentialsException("b"));

        assertThat(bad.getContentAsString()).isEqualTo(anonymous.getContentAsString());
        assertThat(bad.getHeader(HttpHeaders.WWW_AUTHENTICATE)).isEqualTo(anonymous.getHeader(HttpHeaders.WWW_AUTHENTICATE));
        assertThat(bad.getStatus()).isEqualTo(anonymous.getStatus());
    }

    @Test
    void shouldLeaveACommittedResponseUntouched() throws Exception {
        var response = new MockHttpServletResponse();
        response.setCommitted(true);

        entryPoint.commence(request(), response, new BadCredentialsException("x"));
        accessDenied.handle(request(), response, new AccessDeniedException("x"));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsByteArray()).isEmpty();
        assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE)).isNull();
    }
}
