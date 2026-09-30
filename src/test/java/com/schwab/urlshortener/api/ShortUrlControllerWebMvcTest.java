package com.schwab.urlshortener.api;

import static com.schwab.urlshortener.support.TestUsers.ADMIN;
import static com.schwab.urlshortener.support.TestUsers.ADMIN_PASSWORD;
import static com.schwab.urlshortener.support.TestUsers.ALICE;
import static com.schwab.urlshortener.support.TestUsers.ALICE_PASSWORD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
import com.schwab.urlshortener.domain.exception.ShortUrlAlreadyActiveException;
import com.schwab.urlshortener.domain.exception.ShortUrlAlreadyDeactivatedException;
import com.schwab.urlshortener.domain.exception.ShortUrlDeletedException;
import com.schwab.urlshortener.security.SecuritySliceTestConfiguration;
import com.schwab.urlshortener.service.Caller;
import com.schwab.urlshortener.service.CreateShortUrlCommand;
import com.schwab.urlshortener.service.ShortUrlService;
import com.schwab.urlshortener.service.ShortUrlView;
import com.schwab.urlshortener.service.exception.AliasAlreadyExistsException;
import com.schwab.urlshortener.service.exception.InvalidAliasException;
import com.schwab.urlshortener.service.exception.InvalidUrlException;
import com.schwab.urlshortener.service.exception.ShortCodeUnavailableException;
import com.schwab.urlshortener.service.exception.ShortUrlConcurrentModificationException;
import com.schwab.urlshortener.service.exception.ShortUrlNotFoundException;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * {@code POST}, {@code GET}, {@code PATCH} and {@code DELETE} on {@code /api/v1/urls} through the real filter
 * chain and the real {@code GlobalExceptionHandler}, with the service mocked. Covers AC1, AC2, AC4, AC5, AC8, AC10, AC12, AC14, AC16 and the D31, D56, D58, D59, D61
 * error contract; US-009 adds PATCH and DELETE (AC1 to AC10, AC12, D88, D89). POST, GET and PATCH are admitted by
 * rule 6 and DELETE by rule 5 (D3).
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

    // ---- US-007: GET /api/v1/urls/{code} (AC1, AC2, AC4, AC6, D4, D54, D70, D72)

    private static final String CODE = "aB3dE9x";
    private static final String CODE_PATH = PATH + "/" + CODE;

    @Test
    void shouldReturn200WithTheExactEightFieldResourceAndANullLastAccessedAt() throws Exception {
        when(service.get(any(), any())).thenReturn(view(CODE, false));

        MvcResult result = mockMvc.perform(get(CODE_PATH).with(httpBasic(ALICE, ALICE_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().doesNotExist(HttpHeaders.LOCATION))
                .andExpect(jsonPath("$.shortCode").value(CODE))
                .andExpect(jsonPath("$.shortUrl").value("https://short.example/aB3dE9x"))
                .andExpect(jsonPath("$.originalUrl").value("https://example.com/page"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.customAlias").value(false))
                .andExpect(jsonPath("$.clickCount").value(0))
                .andExpect(jsonPath("$.createdAt").value("2026-09-29T14:03:12.123456Z"))
                .andExpect(jsonPath("$.lastAccessedAt").value((Object) null))
                .andReturn();

        assertThat(keys(result)).isEqualTo(new TreeSet<>(RESOURCE_KEYS));
        assertThat(result.getResponse().getContentAsString()).doesNotContain("createdBy").doesNotContain("\"id\"")
                .doesNotContain("updatedAt").doesNotContain("version").doesNotContain("alice");
    }

    @Test
    void shouldReturnADeactivatedLinkWithItsStatus() throws Exception {
        when(service.get(any(), any())).thenReturn(new ShortUrlView(CODE, "https://example.com/page",
                ShortUrlStatus.DEACTIVATED, true, 5L, CREATED_AT, CREATED_AT));

        mockMvc.perform(get(CODE_PATH).with(httpBasic(ALICE, ALICE_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DEACTIVATED"))
                .andExpect(jsonPath("$.customAlias").value(true))
                .andExpect(jsonPath("$.clickCount").value(5))
                .andExpect(jsonPath("$.lastAccessedAt").value("2026-09-29T14:03:12.123456Z"));
    }

    @Test
    void shouldPassTheCodeAndANonAdminCallerToTheService() throws Exception {
        when(service.get(any(), any())).thenReturn(view(CODE, false));

        mockMvc.perform(get(CODE_PATH).with(httpBasic(ALICE, ALICE_PASSWORD))).andExpect(status().isOk());

        ArgumentCaptor<Caller> caller = ArgumentCaptor.forClass(Caller.class);
        verify(service).get(eq(CODE), caller.capture());
        assertThat(caller.getValue()).isEqualTo(new Caller("alice", false));
    }

    @Test
    void shouldPassTheConfiguredLowercaseUsernameWhateverCaseTheClientTypedOnGet() throws Exception {
        when(service.get(any(), any())).thenReturn(view(CODE, false));

        mockMvc.perform(get(CODE_PATH).with(httpBasic("ALICE", ALICE_PASSWORD))).andExpect(status().isOk());

        ArgumentCaptor<Caller> caller = ArgumentCaptor.forClass(Caller.class);
        verify(service).get(any(), caller.capture());
        assertThat(caller.getValue()).isEqualTo(new Caller("alice", false));
    }

    @Test
    void shouldPassAnAdminCallerWithTheAdminFlagSetThroughTheRealFilterChain() throws Exception {
        when(service.get(any(), any())).thenReturn(view(CODE, false));

        mockMvc.perform(get(CODE_PATH).with(httpBasic(ADMIN, ADMIN_PASSWORD))).andExpect(status().isOk());

        ArgumentCaptor<Caller> caller = ArgumentCaptor.forClass(Caller.class);
        verify(service).get(any(), caller.capture());
        assertThat(caller.getValue()).isEqualTo(new Caller("admin", true));
    }

    @Test
    void shouldBuildTheCallerFromTheExactRoleAdminAuthorityOnly() {
        Caller admin = ShortUrlController.callerOf(new UsernamePasswordAuthenticationToken("admin", "n/a",
                AuthorityUtils.createAuthorityList("ROLE_ADMIN")));
        Caller user = ShortUrlController.callerOf(new UsernamePasswordAuthenticationToken("alice", "n/a",
                AuthorityUtils.createAuthorityList("ROLE_USER")));
        Caller bareName = ShortUrlController.callerOf(new UsernamePasswordAuthenticationToken("mallory", "n/a",
                AuthorityUtils.createAuthorityList("ADMIN")));
        Caller none = ShortUrlController.callerOf(new UsernamePasswordAuthenticationToken("nobody", "n/a",
                AuthorityUtils.NO_AUTHORITIES));
        Caller both = ShortUrlController.callerOf(new UsernamePasswordAuthenticationToken("both", "n/a",
                AuthorityUtils.createAuthorityList("ROLE_USER", "ROLE_ADMIN")));

        assertThat(admin).isEqualTo(new Caller("admin", true));
        assertThat(user).isEqualTo(new Caller("alice", false));
        assertThat(bareName.admin()).isFalse();
        assertThat(none.admin()).isFalse();
        assertThat(both.admin()).isTrue();
    }

    @Test
    void shouldReturn404ShortUrlNotFoundWithExactlyTheBaseKeysAndTheRequestPathAsInstance() throws Exception {
        when(service.get(any(), any())).thenThrow(new ShortUrlNotFoundException());

        MvcResult result = mockMvc.perform(get(CODE_PATH).with(httpBasic(ALICE, ALICE_PASSWORD)))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errorCode").value("SHORT_URL_NOT_FOUND"))
                .andExpect(jsonPath("$.instance").value(CODE_PATH))
                .andExpect(jsonPath("$.detail").value("The short URL was not found."))
                .andExpect(jsonPath("$.title").value("Not Found"))
                .andExpect(jsonPath("$.status").value(404))
                .andReturn();

        assertThat(keys(result)).isEqualTo(new TreeSet<>(BASE_KEYS));
    }

    @Test
    void shouldReturn401WithAuthenticationRequiredAndNotCallTheServiceWhenAnonymousOnGet() throws Exception {
        MvcResult result = mockMvc.perform(get(CODE_PATH))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, startsWith("Basic")))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errorCode").value("AUTHENTICATION_REQUIRED"))
                .andReturn();

        assertThat(keys(result)).isEqualTo(new TreeSet<>(BASE_KEYS));
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"application/xml", "text/plain", "application/problem+json"})
    void shouldReturn406NotAcceptableAndNotCallTheServiceForAnUnacceptableAcceptOnGet(String accept)
            throws Exception {
        MvcResult result = mockMvc.perform(get(CODE_PATH).with(httpBasic(ALICE, ALICE_PASSWORD))
                        .header(HttpHeaders.ACCEPT, accept))
                .andExpect(status().isNotAcceptable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errorCode").value("NOT_ACCEPTABLE"))
                .andReturn();

        assertThat(keys(result)).isEqualTo(new TreeSet<>(BASE_KEYS));
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"application/json", "*/*"})
    void shouldReturn200ForAnAcceptableAcceptOnGet(String accept) throws Exception {
        when(service.get(any(), any())).thenReturn(view(CODE, false));

        mockMvc.perform(get(CODE_PATH).with(httpBasic(ALICE, ALICE_PASSWORD)).header(HttpHeaders.ACCEPT, accept))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));

        verify(service).get(any(), any());
    }

    @Test
    void shouldReturn404ResourceNotFoundNotShortUrlNotFoundForATrailingSlashAndNotCallTheService() throws Exception {
        mockMvc.perform(get(CODE_PATH + "/").with(httpBasic(ALICE, ALICE_PASSWORD)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("RESOURCE_NOT_FOUND"));

        verifyNoInteractions(service);
    }

    @Test
    void shouldReturn405WithAllowContainingGetForPutOnTheCodePath() throws Exception {
        mockMvc.perform(put(CODE_PATH).with(httpBasic(ALICE, ALICE_PASSWORD)))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string(HttpHeaders.ALLOW, containsString("GET")))
                .andExpect(jsonPath("$.errorCode").value("METHOD_NOT_ALLOWED"));

        verifyNoInteractions(service);
    }

    @Test
    void shouldCarrySecuritysDefaultCacheControlOnTheGet200() throws Exception {
        when(service.get(any(), any())).thenReturn(view(CODE, false));

        mockMvc.perform(get(CODE_PATH).with(httpBasic(ALICE, ALICE_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-cache, no-store, max-age=0, must-revalidate"))
                .andExpect(header().string(HttpHeaders.PRAGMA, "no-cache"))
                .andExpect(header().string(HttpHeaders.EXPIRES, "0"));
    }

    @Test
    void shouldReturn500WithAGenericBodyWhenTheServiceFailsUnexpectedlyOnGet() throws Exception {
        when(service.get(any(), any())).thenThrow(new RuntimeException("secret-marker"));

        MvcResult result = mockMvc.perform(get(CODE_PATH).with(httpBasic(ALICE, ALICE_PASSWORD)))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.errorCode").value("INTERNAL_ERROR"))
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).doesNotContain("secret-marker");
    }

    // ---- US-009: PATCH /api/v1/urls/{code} (AC1, AC2, AC4, D34, D88, D89)

    private static MockHttpServletRequestBuilder patchAs(String user, String password, String body) {
        return patch(CODE_PATH).with(httpBasic(user, password)).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static MockHttpServletRequestBuilder patchAsAlice(String body) {
        return patchAs(ALICE, ALICE_PASSWORD, body);
    }

    private static ShortUrlView viewWithStatus(ShortUrlStatus status) {
        return new ShortUrlView(CODE, "https://example.com/page", status, false, 7L, CREATED_AT, CREATED_AT);
    }

    @Test
    void shouldReturn200WithTheExactEightFieldResourceAndDeactivatedStatusOnPatchFalse() throws Exception {
        when(service.setActive(any(), anyBoolean(), any())).thenReturn(viewWithStatus(ShortUrlStatus.DEACTIVATED));

        MvcResult result = mockMvc.perform(patchAsAlice("{\"active\":false}"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value("DEACTIVATED"))
                .andExpect(jsonPath("$.shortCode").value(CODE))
                .andExpect(jsonPath("$.shortUrl").value("https://short.example/aB3dE9x"))
                .andExpect(jsonPath("$.clickCount").value(7))
                .andExpect(jsonPath("$.lastAccessedAt").value("2026-09-29T14:03:12.123456Z"))
                .andReturn();

        assertThat(keys(result)).isEqualTo(new TreeSet<>(RESOURCE_KEYS));
        assertThat(result.getResponse().getContentAsString()).doesNotContain("createdBy").doesNotContain("updatedAt")
                .doesNotContain("version").doesNotContain("alice");
        ArgumentCaptor<Caller> caller = ArgumentCaptor.forClass(Caller.class);
        verify(service).setActive(eq(CODE), eq(false), caller.capture());
        assertThat(caller.getValue()).isEqualTo(new Caller("alice", false));
    }

    @Test
    void shouldReturn200WithActiveStatusAndPassTrueOnPatchTrue() throws Exception {
        when(service.setActive(any(), anyBoolean(), any())).thenReturn(viewWithStatus(ShortUrlStatus.ACTIVE));

        mockMvc.perform(patchAsAlice("{\"active\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        verify(service).setActive(eq(CODE), eq(true), eq(new Caller("alice", false)));
    }

    @Test
    void shouldPassAnAdminCallerWithTheAdminFlagOnPatch() throws Exception {
        when(service.setActive(any(), anyBoolean(), any())).thenReturn(viewWithStatus(ShortUrlStatus.DEACTIVATED));

        mockMvc.perform(patchAs(ADMIN, ADMIN_PASSWORD, "{\"active\":false}")).andExpect(status().isOk());

        verify(service).setActive(eq(CODE), eq(false), eq(new Caller("admin", true)));
    }

    @Test
    void shouldStillSerializeResponseBooleansAsJsonBooleansSoD89DoesNotTouchResponses() throws Exception {
        when(service.setActive(any(), anyBoolean(), any())).thenReturn(
                new ShortUrlView(CODE, "https://example.com/page", ShortUrlStatus.ACTIVE, true, 0L, CREATED_AT, null));

        String body = mockMvc.perform(patchAsAlice("{\"active\":true}")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("\"customAlias\":true");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"active\":null}"})
    void shouldReturn400ValidationFailedWithTheFieldForAMissingOrNullActive(String body) throws Exception {
        MvcResult result = mockMvc.perform(patchAsAlice(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors.length()").value(1))
                .andExpect(jsonPath("$.errors[0].field").value("active"))
                .andExpect(jsonPath("$.errors[0].message").value("must not be null"))
                .andReturn();

        assertThat(keys(result)).isEqualTo(plus(BASE_KEYS, "errors"));
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "null", "[]", "{\"active\":", "{\"active\":\"maybe\"}", "{\"active\":{}}",
            "{\"active\":[]}", "{\"active\":[true]}",
            "{\"active\":false,\"x\":1}", "{\"expiresAt\":\"2030-01-01T00:00:00Z\"}",
            "{\"active\":true,\"active\":false}"})
    void shouldReturn400MalformedRequestWithoutParserDetailsForUnreadablePatchBodies(String body) throws Exception {
        MvcResult result = mockMvc.perform(patchAsAlice(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errorCode").value("MALFORMED_REQUEST"))
                .andReturn();

        assertThat(keys(result)).isEqualTo(new TreeSet<>(BASE_KEYS));
        assertThat(result.getResponse().getContentAsString()).doesNotContain("JSON").doesNotContain("parse")
                .doesNotContain("com.fasterxml").doesNotContain("Exception").doesNotContain("expiresAt");
        verifyNoInteractions(service);
    }

    // D89: only a real JSON boolean is accepted for active.
    @ParameterizedTest
    @ValueSource(strings = {"\"false\"", "\"true\"", "\"FALSE\"", "\"\"", "\" \"", "0", "1", "2", "-1", "1.0", "0.0",
            "1.5"})
    void shouldReturn400MalformedRequestForANonBooleanScalarActiveAndNeverCallTheService(String value)
            throws Exception {
        MvcResult result = mockMvc.perform(patchAsAlice("{\"active\":" + value + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("MALFORMED_REQUEST"))
                .andReturn();

        assertThat(keys(result)).isEqualTo(new TreeSet<>(BASE_KEYS));
        verifyNoInteractions(service);
    }

    @Test
    void shouldStillAcceptRealJsonBooleansSoTheStrictRowsAboveAreNotVacuous() throws Exception {
        when(service.setActive(any(), anyBoolean(), any())).thenReturn(viewWithStatus(ShortUrlStatus.ACTIVE));

        mockMvc.perform(patchAsAlice("{\"active\":true}")).andExpect(status().isOk());
        mockMvc.perform(patchAsAlice("{\"active\":false}")).andExpect(status().isOk());

        verify(service).setActive(CODE, true, new Caller("alice", false));
        verify(service).setActive(CODE, false, new Caller("alice", false));
    }

    // D89 scope: create is unchanged. Numbers given for the string properties are still coerced, as before.
    @Test
    void shouldStillCoerceJsonNumbersForCreateStringPropertiesSoD89IsScopedToBooleans() throws Exception {
        when(service.create(any())).thenReturn(view("12345", true));

        mockMvc.perform(create("{\"originalUrl\":98765,\"alias\":12345}")).andExpect(status().isCreated());

        ArgumentCaptor<CreateShortUrlCommand> command = ArgumentCaptor.forClass(CreateShortUrlCommand.class);
        verify(service).create(command.capture());
        assertThat(command.getValue().alias()).isEqualTo("12345");
        assertThat(command.getValue().originalUrl()).isEqualTo("98765");
    }

    @ParameterizedTest
    @ValueSource(strings = {"text/plain", "application/merge-patch+json", "application/json-patch+json",
            "application/xml"})
    void shouldReturn415ForAnyPatchContentTypeOtherThanApplicationJson(String contentType) throws Exception {
        MvcResult result = mockMvc.perform(patch(CODE_PATH).with(httpBasic(ALICE, ALICE_PASSWORD))
                        .contentType(contentType).content("{\"active\":false}"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errorCode").value("UNSUPPORTED_MEDIA_TYPE"))
                .andReturn();

        assertThat(keys(result)).isEqualTo(new TreeSet<>(BASE_KEYS));
        verifyNoInteractions(service);
    }

    @Test
    void shouldReturn415WhenThePatchContentTypeIsMissing() throws Exception {
        mockMvc.perform(patch(CODE_PATH).with(httpBasic(ALICE, ALICE_PASSWORD)).content("{\"active\":false}"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.errorCode").value("UNSUPPORTED_MEDIA_TYPE"));

        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"application/xml", "text/plain", "application/problem+json"})
    void shouldReturn406AndNotCallTheServiceForAnUnacceptablePatchAccept(String accept) throws Exception {
        MvcResult result = mockMvc.perform(patchAsAlice("{\"active\":false}").header(HttpHeaders.ACCEPT, accept))
                .andExpect(status().isNotAcceptable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errorCode").value("NOT_ACCEPTABLE"))
                .andReturn();

        assertThat(keys(result)).isEqualTo(new TreeSet<>(BASE_KEYS));
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"application/json", "*/*"})
    void shouldReturn200ForAnAcceptablePatchAcceptAsThePositiveControl(String accept) throws Exception {
        when(service.setActive(any(), anyBoolean(), any())).thenReturn(viewWithStatus(ShortUrlStatus.DEACTIVATED));

        mockMvc.perform(patchAsAlice("{\"active\":false}").header(HttpHeaders.ACCEPT, accept))
                .andExpect(status().isOk());

        verify(service).setActive(any(), anyBoolean(), any());
    }

    @Test
    void shouldReturn406NotBadRequestForMalformedPatchJsonWithAnUnacceptableAccept() throws Exception {
        mockMvc.perform(patchAsAlice("{not json").accept(MediaType.APPLICATION_XML))
                .andExpect(status().isNotAcceptable())
                .andExpect(jsonPath("$.errorCode").value("NOT_ACCEPTABLE"));

        verifyNoInteractions(service);
    }

    @Test
    void shouldReturn401WithAuthenticationRequiredAndNotCallTheServiceWhenAnonymousOnPatch() throws Exception {
        MvcResult result = mockMvc.perform(patch(CODE_PATH).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\":false}"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, startsWith("Basic")))
                .andExpect(jsonPath("$.errorCode").value("AUTHENTICATION_REQUIRED"))
                .andReturn();

        assertThat(keys(result)).isEqualTo(new TreeSet<>(BASE_KEYS));
        verifyNoInteractions(service);
    }

    @Test
    void shouldReturn404ResourceNotFoundForATrailingSlashOnPatchAndNotCallTheService() throws Exception {
        mockMvc.perform(patch(CODE_PATH + "/").with(httpBasic(ALICE, ALICE_PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"active\":false}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("RESOURCE_NOT_FOUND"));

        verifyNoInteractions(service);
    }

    @Test
    void shouldReturn405WithAllowListingGetPatchAndDeleteForPutOnTheCodePath() throws Exception {
        mockMvc.perform(put(CODE_PATH).with(httpBasic(ALICE, ALICE_PASSWORD)))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string(HttpHeaders.ALLOW, containsString("GET")))
                .andExpect(header().string(HttpHeaders.ALLOW, containsString("PATCH")))
                .andExpect(header().string(HttpHeaders.ALLOW, containsString("DELETE")))
                .andExpect(jsonPath("$.errorCode").value("METHOD_NOT_ALLOWED"));

        verifyNoInteractions(service);
    }

    // ---- US-009: service exceptions on PATCH (AC3, AC7, AC8, AC9, AC10, AC11)

    @Test
    void shouldReturn404ShortUrlNotFoundWithTheBaseKeysWhenThePatchTargetIsNotVisible() throws Exception {
        when(service.setActive(any(), anyBoolean(), any())).thenThrow(new ShortUrlNotFoundException());

        MvcResult result = mockMvc.perform(patchAsAlice("{\"active\":false}"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errorCode").value("SHORT_URL_NOT_FOUND"))
                .andExpect(jsonPath("$.instance").value(CODE_PATH))
                .andReturn();

        assertThat(keys(result)).isEqualTo(new TreeSet<>(BASE_KEYS));
        verify(service).setActive(CODE, false, new Caller("alice", false));
    }

    @Test
    void shouldReturnAByteIdentical404WhenTheServiceThrowsShortUrlDeletedInsteadOfNotFound() throws Exception {
        when(service.setActive(any(), anyBoolean(), any())).thenThrow(new ShortUrlNotFoundException());
        MvcResult notFound = mockMvc.perform(patchAsAlice("{\"active\":false}")).andReturn();
        doThrow(new ShortUrlDeletedException(CODE)).when(service).setActive(any(), anyBoolean(), any());
        MvcResult deleted = mockMvc.perform(patchAsAlice("{\"active\":false}")).andReturn();

        assertThat(notFound.getResponse().getStatus()).isEqualTo(404);
        assertThat(deleted.getResponse().getStatus()).isEqualTo(404);
        assertThat(deleted.getResponse().getContentAsString()).isEqualTo(notFound.getResponse().getContentAsString());
        assertThat(deleted.getResponse().getContentAsString()).contains("SHORT_URL_NOT_FOUND")
                .doesNotContain("is deleted");
        assertThat(deleted.getResponse().getContentType()).isEqualTo(notFound.getResponse().getContentType());
    }

    @Test
    void shouldReturn409AlreadyDeactivatedWithExactlyTheBaseKeys() throws Exception {
        when(service.setActive(any(), anyBoolean(), any())).thenThrow(new ShortUrlAlreadyDeactivatedException(CODE));

        MvcResult result = mockMvc.perform(patchAsAlice("{\"active\":false}"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errorCode").value("SHORT_URL_ALREADY_DEACTIVATED"))
                .andExpect(jsonPath("$.instance").value(CODE_PATH))
                .andReturn();

        assertThat(keys(result)).isEqualTo(new TreeSet<>(BASE_KEYS));
        assertThat(result.getResponse().getContentAsString()).doesNotContain(CODE + " ");
    }

    @Test
    void shouldReturn409AlreadyActiveWithExactlyTheBaseKeys() throws Exception {
        when(service.setActive(any(), anyBoolean(), any())).thenThrow(new ShortUrlAlreadyActiveException(CODE));

        MvcResult result = mockMvc.perform(patchAsAlice("{\"active\":true}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("SHORT_URL_ALREADY_ACTIVE"))
                .andExpect(jsonPath("$.instance").value(CODE_PATH))
                .andReturn();

        assertThat(keys(result)).isEqualTo(new TreeSet<>(BASE_KEYS));
    }

    @Test
    void shouldReturn409ConcurrentModificationWithExactlyTheBaseKeysOnPatch() throws Exception {
        when(service.setActive(any(), anyBoolean(), any()))
                .thenThrow(new ShortUrlConcurrentModificationException(new RuntimeException("internal-marker")));

        MvcResult result = mockMvc.perform(patchAsAlice("{\"active\":false}"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errorCode").value("CONCURRENT_MODIFICATION"))
                .andExpect(jsonPath("$.instance").value(CODE_PATH))
                .andReturn();

        assertThat(keys(result)).isEqualTo(new TreeSet<>(BASE_KEYS));
        assertThat(result.getResponse().getContentAsString()).doesNotContain("internal-marker")
                .doesNotContain("modified concurrently");
    }

    // ---- US-009: DELETE /api/v1/urls/{code} (AC5, AC6, AC7, AC8, AC12, D3)

    private static MockHttpServletRequestBuilder deleteAsAdmin() {
        return delete(CODE_PATH).with(httpBasic(ADMIN, ADMIN_PASSWORD));
    }

    @Test
    void shouldReturn204WithAnEmptyBodyAndNoContentTypeWhenAdminDeletes() throws Exception {
        MvcResult result = mockMvc.perform(deleteAsAdmin())
                .andExpect(status().isNoContent())
                .andExpect(header().doesNotExist(HttpHeaders.CONTENT_TYPE))
                .andReturn();

        assertThat(result.getResponse().getContentAsByteArray()).isEmpty();
        verify(service).delete(CODE, new Caller("admin", true));
    }

    @ParameterizedTest
    @ValueSource(strings = {"aB3dE9x", "Missing1", "Deleted1", "Deact123", "bad-code", "ab"})
    void shouldReturn403AccessDeniedForAUserOnAnyCodeAndNeverCallTheService(String code) throws Exception {
        // A mocked service that would answer 404 for these codes: the 403 must come first (AC8).
        doThrow(new ShortUrlNotFoundException()).when(service).delete(any(), any());

        MvcResult result = mockMvc.perform(delete(PATH + "/" + code).with(httpBasic(ALICE, ALICE_PASSWORD)))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().doesNotExist(HttpHeaders.WWW_AUTHENTICATE))
                .andExpect(jsonPath("$.errorCode").value("ACCESS_DENIED"))
                .andReturn();

        assertThat(keys(result)).isEqualTo(new TreeSet<>(BASE_KEYS));
        verifyNoInteractions(service);
    }

    @Test
    void shouldReachTheServiceForAdminOnTheSameCodeThatGaveAUserA403AsThePositiveControl() throws Exception {
        doThrow(new ShortUrlNotFoundException()).when(service).delete(any(), any());

        mockMvc.perform(delete(CODE_PATH).with(httpBasic(ALICE, ALICE_PASSWORD))).andExpect(status().isForbidden());
        mockMvc.perform(deleteAsAdmin())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("SHORT_URL_NOT_FOUND"));

        verify(service, times(1)).delete(any(), any());
    }

    @Test
    void shouldReturn404ShortUrlNotFoundForAdminWhenTheServiceThrowsDeletedOrNotFound() throws Exception {
        doThrow(new ShortUrlNotFoundException()).when(service).delete(any(), any());
        MvcResult notFound = mockMvc.perform(deleteAsAdmin()).andReturn();
        doThrow(new ShortUrlDeletedException(CODE)).when(service).delete(any(), any());
        MvcResult deleted = mockMvc.perform(deleteAsAdmin()).andReturn();

        assertThat(notFound.getResponse().getStatus()).isEqualTo(404);
        assertThat(deleted.getResponse().getContentAsString()).isEqualTo(notFound.getResponse().getContentAsString());
        assertThat(notFound.getResponse().getContentAsString()).contains("\"errorCode\":\"SHORT_URL_NOT_FOUND\"");
    }

    @Test
    void shouldReturn409ConcurrentModificationWhenTheServiceReportsAConflictOnDelete() throws Exception {
        doThrow(new ShortUrlConcurrentModificationException(new RuntimeException()))
                .when(service).delete(any(), any());

        MvcResult result = mockMvc.perform(deleteAsAdmin())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("CONCURRENT_MODIFICATION"))
                .andExpect(jsonPath("$.instance").value(CODE_PATH))
                .andReturn();

        assertThat(keys(result)).isEqualTo(new TreeSet<>(BASE_KEYS));
    }

    @Test
    void shouldReturn401AndNotCallTheServiceWhenAnonymousOnDelete() throws Exception {
        MvcResult result = mockMvc.perform(delete(CODE_PATH))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, startsWith("Basic")))
                .andExpect(jsonPath("$.errorCode").value("AUTHENTICATION_REQUIRED"))
                .andReturn();

        assertThat(keys(result)).isEqualTo(new TreeSet<>(BASE_KEYS));
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"application/xml", "text/plain", "application/problem+json"})
    void shouldReturn406AndNotCallTheServiceForAnUnacceptableDeleteAcceptFromAdmin(String accept) throws Exception {
        MvcResult result = mockMvc.perform(deleteAsAdmin().header(HttpHeaders.ACCEPT, accept))
                .andExpect(status().isNotAcceptable())
                .andExpect(jsonPath("$.errorCode").value("NOT_ACCEPTABLE"))
                .andReturn();

        assertThat(keys(result)).isEqualTo(new TreeSet<>(BASE_KEYS));
        verifyNoInteractions(service);
    }

    @Test
    void shouldReturn204ForAnAcceptableDeleteAcceptAsThePositiveControl() throws Exception {
        mockMvc.perform(deleteAsAdmin().header(HttpHeaders.ACCEPT, "application/json"))
                .andExpect(status().isNoContent());

        verify(service).delete(CODE, new Caller("admin", true));
    }

    @Test
    void shouldReturn403NotNotAcceptableForAUserDeleteWithAnUnacceptableAcceptBecauseSecurityComesFirst()
            throws Exception {
        mockMvc.perform(delete(CODE_PATH).with(httpBasic(ALICE, ALICE_PASSWORD))
                        .header(HttpHeaders.ACCEPT, "application/xml"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("ACCESS_DENIED"));

        verifyNoInteractions(service);
    }

    @Test
    void shouldReturn404ResourceNotFoundForATrailingSlashOnDeleteAndNotCallTheService() throws Exception {
        mockMvc.perform(delete(CODE_PATH + "/").with(httpBasic(ADMIN, ADMIN_PASSWORD)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("RESOURCE_NOT_FOUND"));

        verifyNoInteractions(service);
    }
}
