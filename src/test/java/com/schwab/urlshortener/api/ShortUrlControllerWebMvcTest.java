package com.schwab.urlshortener.api;

import static com.schwab.urlshortener.support.TestUsers.ADMIN;
import static com.schwab.urlshortener.support.TestUsers.ADMIN_PASSWORD;
import static com.schwab.urlshortener.support.TestUsers.ALICE;
import static com.schwab.urlshortener.support.TestUsers.ALICE_PASSWORD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.schwab.urlshortener.config.AppProperties;
import com.schwab.urlshortener.domain.ShortUrlStatus;
import com.schwab.urlshortener.security.SecuritySliceTestConfiguration;
import com.schwab.urlshortener.service.CreateShortUrlCommand;
import com.schwab.urlshortener.service.ShortUrlService;
import com.schwab.urlshortener.service.ShortUrlView;
import com.schwab.urlshortener.service.exception.AliasAlreadyExistsException;
import com.schwab.urlshortener.service.exception.InvalidAliasException;
import com.schwab.urlshortener.service.exception.InvalidUrlException;
import com.schwab.urlshortener.service.exception.ShortCodeUnavailableException;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * {@code POST /api/v1/urls} through the real filter chain and the real {@code GlobalExceptionHandler}, with
 * the service mocked. Covers AC1, AC2, AC4, AC5, AC8, AC10, AC12, AC14, AC16 and the D31, D56, D58, D59, D61
 * error contract. The filter-chain rule that admits the endpoint is rule 6 (D3).
 */
@WebMvcTest(ShortUrlController.class)
@Import({SecuritySliceTestConfiguration.class, ShortUrlLinks.class})
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class ShortUrlControllerWebMvcTest {

    private static final String PATH = "/api/v1/urls";
    private static final Instant CREATED_AT = Instant.parse("2026-09-29T14:03:12.123456Z");
    private static final Set<String> BASE_KEYS = Set.of("type", "title", "status", "detail", "instance", "errorCode");
    private static final Set<String> RESOURCE_KEYS = Set.of("shortCode", "shortUrl", "originalUrl", "status",
            "customAlias", "clickCount", "createdAt", "lastAccessedAt");

    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AppProperties.class)
    static class PropertiesConfiguration {
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private ShortUrlService service;

    private static ShortUrlView view(String code, boolean custom) {
        return new ShortUrlView(code, "https://example.com/page", ShortUrlStatus.ACTIVE, custom, 0L, CREATED_AT, null);
    }

    private static MockHttpServletRequestBuilder create(String body) {
        return post(PATH).with(httpBasic(ALICE, ALICE_PASSWORD)).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private Set<String> keys(MvcResult result) throws Exception {
        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
        Set<String> names = new TreeSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static Set<String> plus(Set<String> base, String... more) {
        Set<String> all = new TreeSet<>(base);
        all.addAll(List.of(more));
        return all;
    }

    // ---- AC1, D58

    @Test
    void shouldReturn201WithRelativeLocationAndTheExactEightFieldResource() throws Exception {
        when(service.create(any())).thenReturn(view("aB3dE9x", false));

        MvcResult result = mockMvc.perform(create("{\"originalUrl\":\"https://example.com/page\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION, "/api/v1/urls/aB3dE9x"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.shortCode").value("aB3dE9x"))
                .andExpect(jsonPath("$.shortUrl").value("https://short.example/aB3dE9x"))
                .andExpect(jsonPath("$.originalUrl").value("https://example.com/page"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.customAlias").value(false))
                .andExpect(jsonPath("$.clickCount").value(0))
                .andExpect(jsonPath("$.createdAt").value("2026-09-29T14:03:12.123456Z"))
                .andExpect(jsonPath("$.lastAccessedAt").value((Object) null))
                .andReturn();

        assertThat(keys(result)).isEqualTo(new TreeSet<>(RESOURCE_KEYS));
    }

    @Test
    void shouldNotReturnCreatedByIdUpdatedAtOrVersion() throws Exception {
        when(service.create(any())).thenReturn(view("aB3dE9x", false));

        String body = mockMvc.perform(create("{\"originalUrl\":\"https://example.com/page\"}"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("createdBy").doesNotContain("\"id\"").doesNotContain("updatedAt")
                .doesNotContain("version").doesNotContain("alice");
    }

    // ---- AC2

    @Test
    void shouldPassTheAliasToTheServiceAndReturnCustomAliasTrue() throws Exception {
        when(service.create(any())).thenReturn(view("promo2026", true));

        mockMvc.perform(create("{\"originalUrl\":\"https://example.com/page\",\"alias\":\"promo2026\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION, "/api/v1/urls/promo2026"))
                .andExpect(jsonPath("$.shortCode").value("promo2026"))
                .andExpect(jsonPath("$.customAlias").value(true));

        ArgumentCaptor<CreateShortUrlCommand> command = ArgumentCaptor.forClass(CreateShortUrlCommand.class);
        verify(service).create(command.capture());
        assertThat(command.getValue().alias()).isEqualTo("promo2026");
        assertThat(command.getValue().originalUrl()).isEqualTo("https://example.com/page");
    }

    @Test
    void shouldTreatAJsonNullAliasAsAbsent() throws Exception {
        when(service.create(any())).thenReturn(view("aB3dE9x", false));

        mockMvc.perform(create("{\"originalUrl\":\"https://example.com/page\",\"alias\":null}"))
                .andExpect(status().isCreated());

        ArgumentCaptor<CreateShortUrlCommand> command = ArgumentCaptor.forClass(CreateShortUrlCommand.class);
        verify(service).create(command.capture());
        assertThat(command.getValue().alias()).isNull();
    }

    @Test
    void shouldPassAnEmptyAliasThroughUntrimmedSoThePolicyCanRejectIt() throws Exception {
        when(service.create(any())).thenThrow(new InvalidAliasException());

        mockMvc.perform(create("{\"originalUrl\":\"https://example.com/page\",\"alias\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_ALIAS"));

        ArgumentCaptor<CreateShortUrlCommand> command = ArgumentCaptor.forClass(CreateShortUrlCommand.class);
        verify(service).create(command.capture());
        assertThat(command.getValue().alias()).isEmpty();
    }

    // ---- AC10, D54

    @Test
    void shouldPassTheConfiguredLowercaseUsernameWhateverCaseTheClientTyped() throws Exception {
        when(service.create(any())).thenReturn(view("aB3dE9x", false));

        mockMvc.perform(post(PATH).with(httpBasic("ALICE", ALICE_PASSWORD)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"originalUrl\":\"https://example.com/page\"}")).andExpect(status().isCreated());

        ArgumentCaptor<CreateShortUrlCommand> command = ArgumentCaptor.forClass(CreateShortUrlCommand.class);
        verify(service).create(command.capture());
        assertThat(command.getValue().createdBy()).isEqualTo("alice");
    }

    @Test
    void shouldLetAdminCreateThroughTheRoleHierarchy() throws Exception {
        when(service.create(any())).thenReturn(view("aB3dE9x", false));

        mockMvc.perform(post(PATH).with(httpBasic(ADMIN, ADMIN_PASSWORD)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"originalUrl\":\"https://example.com/page\"}")).andExpect(status().isCreated());

        ArgumentCaptor<CreateShortUrlCommand> command = ArgumentCaptor.forClass(CreateShortUrlCommand.class);
        verify(service).create(command.capture());
        assertThat(command.getValue().createdBy()).isEqualTo("admin");
    }

    // ---- AC16, D33

    @Test
    void shouldBuildShortUrlFromTheConfiguredBaseAndIgnoreSpoofedHostAndForwardedHeaders() throws Exception {
        when(service.create(any())).thenReturn(view("aB3dE9x", false));

        MvcResult result = mockMvc.perform(create("{\"originalUrl\":\"https://example.com/page\"}")
                        .header(HttpHeaders.HOST, "attacker.example")
                        .header("X-Forwarded-Host", "attacker.example")
                        .header("Forwarded", "host=attacker.example"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.shortUrl").value("https://short.example/aB3dE9x"))
                .andExpect(header().string(HttpHeaders.LOCATION, "/api/v1/urls/aB3dE9x"))
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).doesNotContain("attacker.example");
        assertThat(result.getResponse().getHeaderNames()).allSatisfy(name ->
                assertThat(result.getResponse().getHeaders(name)).allSatisfy(v ->
                        assertThat(v).doesNotContain("attacker.example")));
    }

    // ---- AC8

    @Test
    void shouldReturn401WithExactlyTheBaseKeysAndNotCallTheServiceWhenAnonymous() throws Exception {
        MvcResult result = mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"originalUrl\":\"https://example.com/page\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errorCode").value("AUTHENTICATION_REQUIRED"))
                .andReturn();

        assertThat(keys(result)).isEqualTo(new TreeSet<>(BASE_KEYS));
        verifyNoInteractions(service);
    }

    // ---- AC4, AC5, D56, D63: service exceptions

    @Test
    void shouldReturn400InvalidAliasNamingTheFieldWithoutEchoingTheRejectedValue() throws Exception {
        when(service.create(any())).thenThrow(new InvalidAliasException());

        MvcResult result = mockMvc.perform(create(
                        "{\"originalUrl\":\"https://example.com/page\",\"alias\":\"bad-alias-marker\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errorCode").value("INVALID_ALIAS"))
                .andExpect(jsonPath("$.instance").value(PATH))
                .andExpect(jsonPath("$.errors.length()").value(1))
                .andExpect(jsonPath("$.errors[0].field").value("alias"))
                .andExpect(jsonPath("$.errors[0].message").isNotEmpty())
                .andReturn();

        assertThat(keys(result)).isEqualTo(plus(BASE_KEYS, "errors"));
        assertThat(result.getResponse().getContentAsString()).doesNotContain("bad-alias-marker");
    }

    @Test
    void shouldReturn400InvalidUrlNamingTheFieldWithoutEchoingTheRejectedValue() throws Exception {
        when(service.create(any())).thenThrow(new InvalidUrlException());

        MvcResult result = mockMvc.perform(create("{\"originalUrl\":\"ftp://example.com/secret-path-marker\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_URL"))
                .andExpect(jsonPath("$.errors.length()").value(1))
                .andExpect(jsonPath("$.errors[0].field").value("originalUrl"))
                .andReturn();

        assertThat(keys(result)).isEqualTo(plus(BASE_KEYS, "errors"));
        assertThat(result.getResponse().getContentAsString()).doesNotContain("secret-path-marker");
    }

    @Test
    void shouldReturn409WithExactlyTheBaseKeysWhenTheAliasExists() throws Exception {
        when(service.create(any())).thenThrow(new AliasAlreadyExistsException("promo2026", new RuntimeException()));

        MvcResult result = mockMvc.perform(create(
                        "{\"originalUrl\":\"https://example.com/page\",\"alias\":\"promo2026\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("ALIAS_ALREADY_EXISTS"))
                .andReturn();

        assertThat(keys(result)).isEqualTo(new TreeSet<>(BASE_KEYS));
        assertThat(result.getResponse().getContentAsString()).doesNotContain("promo2026");
    }

    @Test
    void shouldReturn503WithExactlyTheBaseKeysWhenNoCodeCouldBeAllocated() throws Exception {
        when(service.create(any())).thenThrow(new ShortCodeUnavailableException(5));

        MvcResult result = mockMvc.perform(create("{\"originalUrl\":\"https://example.com/page\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.errorCode").value("SHORT_CODE_UNAVAILABLE"))
                .andReturn();

        assertThat(keys(result)).isEqualTo(new TreeSet<>(BASE_KEYS));
    }

    // ---- AC12: structural validation

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"originalUrl\":null}", "{\"originalUrl\":\"\"}", "{\"originalUrl\":\"  \"}",
            "{\"alias\":\"promo2026\"}"})
    void shouldReturn400ValidationFailedForAMissingNullOrBlankOriginalUrl(String body) throws Exception {
        MvcResult result = mockMvc.perform(create(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors.length()").value(1))
                .andExpect(jsonPath("$.errors[0].field").value("originalUrl"))
                .andExpect(jsonPath("$.errors[0].message").value("must not be blank"))
                .andReturn();

        assertThat(keys(result)).isEqualTo(plus(BASE_KEYS, "errors"));
        assertThat(result.getResponse().getContentAsString()).doesNotContain("Exception").doesNotContain("at com.")
                .doesNotContain("rejectedValue").doesNotContain("promo2026");
        verifyNoInteractions(service);
    }

    // ---- AC14, D59: unreadable bodies

    @ParameterizedTest
    @ValueSource(strings = {"{\"originalUrl\":", "", "[]", "null", "{\"originalUrl\":{}}", "{\"originalUrl\":[\"x\"]}",
            "{\"originalUrl\":\"https://example.com/page\",\"alais\":\"promo\"}",
            "{\"originalUrl\":\"https://example.com/page\",\"alias\":\"a\",\"alias\":\"b\"}",
            "{\"originalUrl\":\"https://example.com/page\",\"originalUrl\":\"https://example.com/other\"}"})
    void shouldReturn400MalformedRequestWithoutParserDetailsForUnreadableBodies(String body) throws Exception {
        MvcResult result = mockMvc.perform(create(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errorCode").value("MALFORMED_REQUEST"))
                .andReturn();

        assertThat(keys(result)).isEqualTo(new TreeSet<>(BASE_KEYS));
        assertThat(result.getResponse().getContentAsString()).doesNotContain("JSON").doesNotContain("parse")
                .doesNotContain("line").doesNotContain("column").doesNotContain("com.fasterxml")
                .doesNotContain("Exception").doesNotContain("alais");
        verifyNoInteractions(service);
    }

    // ---- framework errors (D61)

    @ParameterizedTest
    @ValueSource(strings = {"GET", "PUT"})
    void shouldReturn405MethodNotAllowedWithAllowPostForOtherMethods(String method) throws Exception {
        MockHttpServletRequestBuilder request = "GET".equals(method) ? get(PATH) : put(PATH);

        MvcResult result = mockMvc.perform(request.with(httpBasic(ALICE, ALICE_PASSWORD)))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string(HttpHeaders.ALLOW, "POST"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errorCode").value("METHOD_NOT_ALLOWED"))
                .andReturn();

        assertThat(keys(result)).isEqualTo(new TreeSet<>(BASE_KEYS));
    }

    @Test
    void shouldReturn415UnsupportedMediaTypeForATextPlainBody() throws Exception {
        MvcResult result = mockMvc.perform(post(PATH).with(httpBasic(ALICE, ALICE_PASSWORD))
                        .contentType(MediaType.TEXT_PLAIN).content("https://example.com/page"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errorCode").value("UNSUPPORTED_MEDIA_TYPE"))
                .andReturn();

        assertThat(keys(result)).isEqualTo(new TreeSet<>(BASE_KEYS));
        verifyNoInteractions(service);
    }

    @Test
    void shouldReturn415WhenTheContentTypeIsMissing() throws Exception {
        mockMvc.perform(post(PATH).with(httpBasic(ALICE, ALICE_PASSWORD)).content("{}"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.errorCode").value("UNSUPPORTED_MEDIA_TYPE"));
    }

    // ---- AC17, D70

    private static final String VALID_BODY = "{\"originalUrl\":\"https://example.com/page\",\"alias\":\"promo\"}";

    @ParameterizedTest
    @ValueSource(strings = {"application/xml", "text/plain", "application/problem+json"})
    void shouldReturn406WithAProblemJsonBodyAndCreateNothingForAnUnacceptableAccept(String accept) throws Exception {
        MvcResult result = mockMvc.perform(create(VALID_BODY).header(HttpHeaders.ACCEPT, accept))
                .andExpect(status().isNotAcceptable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().doesNotExist(HttpHeaders.LOCATION))
                .andExpect(jsonPath("$.errorCode").value("NOT_ACCEPTABLE"))
                .andReturn();

        assertThat(keys(result)).isEqualTo(new TreeSet<>(BASE_KEYS));
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"application/json", "*/*"})
    void shouldReturn201AndCallTheServiceOnceForAnAcceptableAccept(String accept) throws Exception {
        when(service.create(any())).thenReturn(view("promo", true));

        mockMvc.perform(create(VALID_BODY).header(HttpHeaders.ACCEPT, accept))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION, "/api/v1/urls/promo"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.shortCode").value("promo"));

        verify(service).create(any());
    }

    @Test
    void shouldReturn406NotBadRequestForMalformedJsonWithAnUnacceptableAccept() throws Exception {
        mockMvc.perform(create("{not json").accept(MediaType.APPLICATION_XML))
                .andExpect(status().isNotAcceptable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errorCode").value("NOT_ACCEPTABLE"));

        verifyNoInteractions(service);
    }

    @Test
    void shouldReturn404ResourceNotFoundForAnUnmappedApiPath() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/nope").with(httpBasic(ALICE, ALICE_PASSWORD)))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errorCode").value("RESOURCE_NOT_FOUND"))
                .andReturn();

        assertThat(keys(result)).isEqualTo(new TreeSet<>(BASE_KEYS));
    }

    @Test
    void shouldReturn404AndCallNothingForATrailingSlashOnThePostPath() throws Exception {
        mockMvc.perform(post(PATH + "/").with(httpBasic(ALICE, ALICE_PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"originalUrl\":\"https://example.com/page\"}"))
                .andExpect(status().isNotFound());

        verify(service, never()).create(any());
    }

    // ---- 500 (D64: nothing internal in the body, the detail only in the log)

    @Test
    void shouldReturn500WithAGenericBodyAndLogTheFailureWhenTheServiceThrowsUnexpectedly(CapturedOutput output)
            throws Exception {
        when(service.create(any())).thenThrow(new RuntimeException("secret-marker"));

        MvcResult result = mockMvc.perform(create("{\"originalUrl\":\"https://example.com/page\"}"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errorCode").value("INTERNAL_ERROR"))
                .andReturn();

        assertThat(keys(result)).isEqualTo(new TreeSet<>(BASE_KEYS));
        assertThat(result.getResponse().getContentAsString()).doesNotContain("secret-marker")
                .doesNotContain("RuntimeException");
        // Positive capture: the handler did log, so the body check above is about the body, not a silent handler.
        assertThat(output.getAll()).contains("Unhandled exception: method=POST path=/api/v1/urls");
    }
}
