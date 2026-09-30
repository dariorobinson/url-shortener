package com.schwab.urlshortener.api;

import static com.schwab.urlshortener.support.TestUsers.ALICE;
import static com.schwab.urlshortener.support.TestUsers.ALICE_PASSWORD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.schwab.urlshortener.security.SecuritySliceTestConfiguration;
import com.schwab.urlshortener.service.RedirectService;
import com.schwab.urlshortener.service.exception.ShortUrlNotFoundException;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * {@code GET} and {@code HEAD /{code}} through the real filter chain and the real advice, with the service mocked.
 * Covers US-008 AC1 to AC4, AC6, AC7, AC8 and D55, D70, D75, D76, D79. The filter-chain rule that admits the
 * endpoint is rule 7 (D32).
 */
@WebMvcTest(RedirectController.class)
@Import(SecuritySliceTestConfiguration.class)
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class RedirectControllerWebMvcTest {

    private static final String CODE = "aB3dE9x";
    private static final String PATH = "/" + CODE;
    private static final String TARGET = "https://Example.com/a/../b?q=a+b&c=%2f%2F#frag";
    private static final Set<String> BASE_KEYS = Set.of("type", "title", "status", "detail", "instance", "errorCode");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private RedirectService service;

    /** D89 reaches every slice through {@code SecuritySliceTestConfiguration}, not through a per-class import. */
    @Test
    void shouldApplyTheD89StrictBooleanSettingToTheSliceObjectMapper() throws Exception {
        record Flag(Boolean active) {
        }

        assertThat(objectMapper.readValue("{\"active\":true}", Flag.class).active()).isTrue();
        for (String value : List.of("\"false\"", "0", "1", "1.0", "[]")) {
            assertThatThrownBy(() -> objectMapper.readValue("{\"active\":" + value + "}", Flag.class))
                    .isInstanceOf(MismatchedInputException.class);
        }
    }

    private void assertExact302(MvcResult result, String expectedLocation) {
        var response = result.getResponse();
        assertThat(response.getStatus()).isEqualTo(302);
        assertThat(response.getHeaders(HttpHeaders.LOCATION)).containsExactly(expectedLocation);
        assertThat(response.getHeaders(HttpHeaders.CACHE_CONTROL)).containsExactly("no-store");
        assertThat(response.getHeader(HttpHeaders.PRAGMA)).isNull();
        assertThat(response.getHeader(HttpHeaders.EXPIRES)).isNull();
        assertThat(response.getContentAsByteArray()).isEmpty();
        assertThat(response.getContentType()).isNull();
    }

    private void assertNotFoundProblem(MvcResult result, String path) throws Exception {
        var response = result.getResponse();
        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(response.getContentType()).startsWith("application/problem+json");
        JsonNode body = objectMapper.readTree(response.getContentAsString());
        Set<String> names = new TreeSet<>();
        body.fieldNames().forEachRemaining(names::add);
        assertThat(names).isEqualTo(new TreeSet<>(BASE_KEYS));
        assertThat(body.get("errorCode").asText()).isEqualTo("SHORT_URL_NOT_FOUND");
        assertThat(body.get("status").asInt()).isEqualTo(404);
        assertThat(body.get("instance").asText()).isEqualTo(path);
    }

    // ---- AC1, AC7

    @Test
    void shouldReturn302WithTheExactLocationAndNoStoreForAnonymousGet() throws Exception {
        when(service.resolveAndRecordClick(CODE)).thenReturn(TARGET);

        assertExact302(mockMvc.perform(get(PATH)).andReturn(), TARGET);
    }

    @Test
    void shouldReturn302WithTheSameHeadersForAnonymousHead() throws Exception {
        when(service.resolve(CODE)).thenReturn(TARGET);

        assertExact302(mockMvc.perform(head(PATH)).andReturn(), TARGET);
    }

    @Test
    void shouldPercentEncodeNonAsciiCharactersInTheLocationHeader() throws Exception {
        when(service.resolveAndRecordClick(CODE)).thenReturn("https://example.com/café?q=中#😀");

        assertExact302(mockMvc.perform(get(PATH)).andReturn(),
                "https://example.com/caf%C3%A9?q=%E4%B8%AD#%F0%9F%98%80");
    }

    @Test
    void shouldReturn302ForAValidAuthenticatedCallerToo() throws Exception {
        when(service.resolveAndRecordClick(CODE)).thenReturn(TARGET);

        assertExact302(mockMvc.perform(get(PATH).with(httpBasic(ALICE, ALICE_PASSWORD))).andReturn(), TARGET);
    }

    // ---- AC2, D9: GET counts, HEAD never does, through real Spring HEAD routing

    @Test
    void shouldCallOnlyResolveAndRecordClickForGet() throws Exception {
        when(service.resolveAndRecordClick(CODE)).thenReturn(TARGET);

        assertExact302(mockMvc.perform(get(PATH)).andReturn(), TARGET);

        verify(service, times(1)).resolveAndRecordClick(CODE);
        verify(service, never()).resolve(any());
    }

    @Test
    void shouldCallOnlyResolveForHeadAndNeverRecordAClick() throws Exception {
        when(service.resolve(CODE)).thenReturn(TARGET);

        assertExact302(mockMvc.perform(head(PATH)).andReturn(), TARGET);

        verify(service, times(1)).resolve(CODE);
        verify(service, never()).resolveAndRecordClick(any());
    }

    // ---- AC8, D79

    @Test
    void shouldIgnoreTheQueryStringOfTheShortLink() throws Exception {
        when(service.resolveAndRecordClick(CODE)).thenReturn("https://example.com/target");

        assertExact302(mockMvc.perform(get(PATH + "?x=1&y=2")).andReturn(), "https://example.com/target");
        verify(service).resolveAndRecordClick(CODE);
    }

    // ---- D70: any Accept gets the 302

    @ParameterizedTest
    @ValueSource(strings = {"text/html", "image/png", "application/xml", "*/*", "application/json",
            "application/problem+json", "foo"})
    void shouldReturn302ForAnyAcceptHeaderOnGetAndHead(String accept) throws Exception {
        when(service.resolveAndRecordClick(CODE)).thenReturn(TARGET);
        when(service.resolve(CODE)).thenReturn(TARGET);

        assertExact302(mockMvc.perform(get(PATH).header(HttpHeaders.ACCEPT, accept)).andReturn(), TARGET);
        assertExact302(mockMvc.perform(head(PATH).header(HttpHeaders.ACCEPT, accept)).andReturn(), TARGET);
    }

    // ---- AC2 to AC4, AC6: the 404

    @Test
    void shouldReturn404ProblemJsonWithSecurityDefaultCacheControlWhenTheServiceThrowsNotFound() throws Exception {
        when(service.resolveAndRecordClick(CODE)).thenThrow(new ShortUrlNotFoundException());

        MvcResult result = mockMvc.perform(get(PATH)).andReturn();

        assertNotFoundProblem(result, PATH);
        assertThat(result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL))
                .isEqualTo("no-cache, no-store, max-age=0, must-revalidate");
    }

    @ParameterizedTest
    @ValueSource(strings = {"text/html", "image/png", "application/xml", "*/*", "application/json",
            "application/problem+json"})
    void shouldReturn404ProblemJsonForEveryParseableAcceptNeverNotAcceptable(String accept) throws Exception {
        when(service.resolveAndRecordClick(CODE)).thenThrow(new ShortUrlNotFoundException());

        assertNotFoundProblem(mockMvc.perform(get(PATH).header(HttpHeaders.ACCEPT, accept)).andReturn(), PATH);
    }

    @Test
    void shouldReturn404ProblemJsonWhenNoAcceptHeaderIsSent() throws Exception {
        when(service.resolveAndRecordClick(CODE)).thenThrow(new ShortUrlNotFoundException());

        assertNotFoundProblem(mockMvc.perform(get(PATH)).andReturn(), PATH);
    }

    @Test
    void shouldReturn404WithAnEmptyBodyForAnUnparseableAccept() throws Exception {
        when(service.resolveAndRecordClick(CODE)).thenThrow(new ShortUrlNotFoundException());

        MvcResult result = mockMvc.perform(get(PATH).header(HttpHeaders.ACCEPT, "foo")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(404);
        assertThat(result.getResponse().getContentAsByteArray()).isEmpty();
        verify(service).resolveAndRecordClick(CODE);
    }

    @Test
    void shouldReturn404ForHeadWhenTheServiceThrowsNotFoundWhileTheSamePathRedirectsOtherwise() throws Exception {
        when(service.resolve(CODE)).thenReturn(TARGET);
        assertExact302(mockMvc.perform(head(PATH)).andReturn(), TARGET);

        when(service.resolve(CODE)).thenThrow(new ShortUrlNotFoundException());
        MvcResult result = mockMvc.perform(head(PATH)).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(404);
        verify(service, times(2)).resolve(CODE);
    }

    @Test
    void shouldPassEveryMalformedSingleSegmentToTheServiceAndReturnItsNotFound() throws Exception {
        when(service.resolveAndRecordClick(any())).thenThrow(new ShortUrlNotFoundException());

        assertNotFoundProblem(mockMvc.perform(get("/ab")).andReturn(), "/ab");
        assertNotFoundProblem(mockMvc.perform(get("/a_b")).andReturn(), "/a_b");
        assertNotFoundProblem(mockMvc.perform(get("/favicon.ico")).andReturn(), "/favicon.ico");
        verify(service).resolveAndRecordClick("ab");
        verify(service).resolveAndRecordClick("a_b");
        verify(service).resolveAndRecordClick("favicon.ico");
    }

    // ---- AC5: bare /api for an authenticated caller reaches the mapping (D78); anonymous never does

    @Test
    void shouldReturn401AndNeverCallTheServiceForAnonymousApi() throws Exception {
        mockMvc.perform(get("/api")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTHENTICATION_REQUIRED"));

        verifyNoInteractions(service);
    }

    @Test
    void shouldReturn404ShortUrlNotFoundForAuthenticatedApiNeverA302() throws Exception {
        when(service.resolveAndRecordClick("api")).thenThrow(new ShortUrlNotFoundException());

        assertNotFoundProblem(mockMvc.perform(get("/api").with(httpBasic(ALICE, ALICE_PASSWORD))).andReturn(), "/api");
    }

    // ---- D55, method rules

    @Test
    void shouldReturn401AuthenticationRequiredAndNeverCallTheServiceForBadCredentials() throws Exception {
        MvcResult result = mockMvc.perform(get(PATH).with(httpBasic(ALICE, "wrong-password")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTHENTICATION_REQUIRED"))
                .andReturn();

        assertThat(result.getResponse().getHeader(HttpHeaders.WWW_AUTHENTICATE)).startsWith("Basic");
        verifyNoInteractions(service);
    }

    @Test
    void shouldReturn401ForAnonymousPostAndNeverCallTheService() throws Exception {
        mockMvc.perform(post("/x1234")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTHENTICATION_REQUIRED"));

        verifyNoInteractions(service);
    }

    @Test
    void shouldReturn403ForAuthenticatedPostAndNeverCallTheService() throws Exception {
        mockMvc.perform(post("/x1234").with(httpBasic(ALICE, ALICE_PASSWORD))).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("ACCESS_DENIED"));

        verifyNoInteractions(service);
    }

    // ---- 500

    @Test
    void shouldReturn500LogOnceAtErrorAndNeverLogTheTargetWhenTheDatabaseFails(CapturedOutput output)
            throws Exception {
        when(service.resolveAndRecordClick(CODE))
                .thenThrow(new DataAccessResourceFailureException("db down marker-secret"));

        MockHttpServletRequestBuilder request = get(PATH);
        MvcResult result = mockMvc.perform(request).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.errorCode").value("INTERNAL_ERROR"))
                .andReturn();

        assertThat(result.getResponse().getContentType()).startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        assertThat(result.getResponse().getContentAsString()).doesNotContain("marker-secret");
        String all = output.getAll();
        assertThat(all).contains("Unhandled exception: method=GET path=/aB3dE9x");
        assertThat(all.split("Unhandled exception: method=GET", -1)).hasSize(2);
        assertThat(all).doesNotContain("https://");
    }
}
