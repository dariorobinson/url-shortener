package com.schwab.urlshortener.api;

import static com.schwab.urlshortener.support.TestUsers.ALICE;
import static com.schwab.urlshortener.support.TestUsers.ALICE_PASSWORD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.schwab.urlshortener.config.AppProperties;
import com.schwab.urlshortener.domain.ShortUrlStatus;
import com.schwab.urlshortener.security.SecuritySliceTestConfiguration;
import com.schwab.urlshortener.service.Caller;
import com.schwab.urlshortener.service.CreateShortUrlCommand;
import com.schwab.urlshortener.service.ShortUrlService;
import com.schwab.urlshortener.service.ShortUrlView;
import com.schwab.urlshortener.service.UpdateShortUrlCommand;
import com.schwab.urlshortener.service.exception.InvalidExpirationException;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * US-016 through the real filter chain, Jackson settings and advice, with the service mocked: how {@code expiresAt}
 * is parsed and handed to the service on create and PATCH (D114, D122, D123), the unchanged body for an empty PATCH,
 * the VALIDATION_FAILED mapping (D124), and the two new response fields (D118). Admitted by rule 6 (unchanged).
 */
@WebMvcTest(ShortUrlController.class)
@Import({SecuritySliceTestConfiguration.class, ShortUrlLinks.class})
@ActiveProfiles("test")
class ShortUrlExpiryWebMvcTest {

    private static final String CODE = "aB3dE9x";
    private static final Instant CREATED_AT = Instant.parse("2026-09-29T14:03:12.123456Z");
    private static final Instant EXPIRY = Instant.parse("2027-01-31T23:59:59Z");
    private static final Caller ALICE_CALLER = new Caller(ALICE, false);

    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AppProperties.class)
    static class PropertiesConfiguration {
    }

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ShortUrlService service;

    private static ShortUrlView view(Instant expiresAt, boolean expired) {
        return new ShortUrlView(CODE, "https://example.com/page", ShortUrlStatus.ACTIVE, false, 0L, CREATED_AT, null,
                expiresAt, expired);
    }

    private static MockHttpServletRequestBuilder create(String body) {
        return post("/api/v1/urls").with(httpBasic(ALICE, ALICE_PASSWORD)).contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private static MockHttpServletRequestBuilder patchBody(String body) {
        return patch("/api/v1/urls/" + CODE).with(httpBasic(ALICE, ALICE_PASSWORD))
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }

    // ---- create (AC1, AC2, AC7)

    @Test
    void shouldPassTheExpiryInstantToTheServiceAndReturnItInTheResource() throws Exception {
        when(service.create(any())).thenReturn(view(EXPIRY, false));

        mockMvc.perform(create(
                        "{\"originalUrl\":\"https://example.com/page\",\"expiresAt\":\"2027-02-01T01:59:59+02:00\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.expiresAt").value("2027-01-31T23:59:59Z"))
                .andExpect(jsonPath("$.expired").value(false));

        ArgumentCaptor<CreateShortUrlCommand> command = ArgumentCaptor.forClass(CreateShortUrlCommand.class);
        verify(service).create(command.capture());
        assertThat(command.getValue().expiresAt()).isEqualTo(EXPIRY);
    }

    @Test
    void shouldReturnANullExpiresAtAndExpiredFalseForANeverExpiringLink() throws Exception {
        when(service.create(any())).thenReturn(view(null, false));

        mockMvc.perform(create("{\"originalUrl\":\"https://example.com/page\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.expiresAt").isEmpty())
                .andExpect(jsonPath("$.expired").value(false));

        ArgumentCaptor<CreateShortUrlCommand> command = ArgumentCaptor.forClass(CreateShortUrlCommand.class);
        verify(service).create(command.capture());
        assertThat(command.getValue().expiresAt()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"1893456000", "\"2027-01-31T23:59:59\"", "\"2027-01-31\"", "\"\"", "{}"})
    void shouldReturn400MalformedRequestForANonStrictExpiryOnCreateAndNeverCallTheService(String value)
            throws Exception {
        mockMvc.perform(create("{\"originalUrl\":\"https://example.com/page\",\"expiresAt\":" + value + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errorCode").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.errors").doesNotExist());

        verifyNoInteractions(service);
    }

    @Test
    void shouldMapAnInvalidExpiryToValidationFailedNamingTheFieldWithoutTheValue() throws Exception {
        when(service.create(any())).thenThrow(new InvalidExpirationException(
                "must be in the future and at most 10 years ahead"));

        String body = mockMvc.perform(create(
                        "{\"originalUrl\":\"https://example.com/page\",\"expiresAt\":\"2001-02-03T04:05:06Z\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors.length()").value(1))
                .andExpect(jsonPath("$.errors[0].field").value("expiresAt"))
                .andExpect(jsonPath("$.errors[0].message").value("must be in the future and at most 10 years ahead"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("2001");
    }

    // ---- PATCH (AC5)

    @Test
    void shouldHandAbsentNullAndValueExpiriesToTheServiceDistinctly() throws Exception {
        when(service.update(any(), any(), any())).thenReturn(view(EXPIRY, false));

        mockMvc.perform(patchBody("{\"expiresAt\":\"2027-01-31T23:59:59Z\"}")).andExpect(status().isOk());
        mockMvc.perform(patchBody("{\"expiresAt\":null}")).andExpect(status().isOk());
        mockMvc.perform(patchBody("{\"active\":false}")).andExpect(status().isOk());
        mockMvc.perform(patchBody("{\"active\":true,\"expiresAt\":null}")).andExpect(status().isOk());

        verify(service).update(eq(CODE), eq(new UpdateShortUrlCommand(null, true, EXPIRY)), eq(ALICE_CALLER));
        verify(service).update(eq(CODE), eq(new UpdateShortUrlCommand(null, true, null)), eq(ALICE_CALLER));
        verify(service).update(eq(CODE), eq(new UpdateShortUrlCommand(false, false, null)), eq(ALICE_CALLER));
        verify(service).update(eq(CODE), eq(new UpdateShortUrlCommand(true, true, null)), eq(ALICE_CALLER));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"active\":null}", "{\"active\":null,\"expiresAt\":\"2027-01-31T23:59:59Z\"}"})
    void shouldReturnTheUnchangedActiveMustNotBeNullBodyAndNeverCallTheService(String body) throws Exception {
        mockMvc.perform(patchBody(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.detail").value("The request body failed validation."))
                .andExpect(jsonPath("$.errors.length()").value(1))
                .andExpect(jsonPath("$.errors[0].field").value("active"))
                .andExpect(jsonPath("$.errors[0].message").value("must not be null"));

        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1893456000", "\"2027-01-31T23:59:59\"", "\"soon\""})
    void shouldReturn400MalformedRequestForANonStrictExpiryOnPatch(String value) throws Exception {
        mockMvc.perform(patchBody("{\"expiresAt\":" + value + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("MALFORMED_REQUEST"));

        verifyNoInteractions(service);
    }
}
