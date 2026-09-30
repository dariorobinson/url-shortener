package com.schwab.urlshortener.api;

import static com.schwab.urlshortener.support.TestUsers.ADMIN;
import static com.schwab.urlshortener.support.TestUsers.ADMIN_PASSWORD;
import static com.schwab.urlshortener.support.TestUsers.ALICE;
import static com.schwab.urlshortener.support.TestUsers.ALICE_PASSWORD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.schwab.urlshortener.config.AppProperties;
import com.schwab.urlshortener.security.SecuritySliceTestConfiguration;
import com.schwab.urlshortener.service.Caller;
import com.schwab.urlshortener.service.DailyClicks;
import com.schwab.urlshortener.service.ShortUrlService;
import com.schwab.urlshortener.service.ShortUrlStats;
import com.schwab.urlshortener.service.StatsPeriod;
import com.schwab.urlshortener.service.exception.InvalidStatsQueryException;
import com.schwab.urlshortener.service.exception.InvalidStatsQueryException.Violation;
import com.schwab.urlshortener.service.exception.ShortUrlNotFoundException;
import com.schwab.urlshortener.service.exception.StatsParameter;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * {@code GET} and {@code HEAD /api/v1/urls/{code}/stats} through the real filter chain and the real
 * {@code GlobalExceptionHandler}, with the service mocked (US-011 AC1 to AC7, AC14, AC15; D31, D56, D70, D73, D99,
 * D100, D101, D104). The stats path is admitted by rule 6 like the rest of {@code /api/**}.
 */
@WebMvcTest(ShortUrlController.class)
@Import({SecuritySliceTestConfiguration.class, ShortUrlLinks.class})
@ActiveProfiles("test")
class ShortUrlStatsWebMvcTest {

    private static final String CODE = "aB3dE9x";
    private static final String PATH = "/api/v1/urls/" + CODE + "/stats";
    private static final Set<String> BASE_KEYS = Set.of("type", "title", "status", "detail", "instance", "errorCode");
    private static final Set<String> STATS_KEYS = Set.of("shortCode", "timezone", "from", "to", "totalClicks",
            "clicksInRange", "lastAccessedAt", "daily");
    private static final Instant LAST = Instant.parse("2026-03-09T03:59:59.999999Z");

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

    private static ShortUrlStats stats(Instant lastAccessedAt) {
        List<DailyClicks> daily = List.of(new DailyClicks(LocalDate.of(2026, 3, 7), 1),
                new DailyClicks(LocalDate.of(2026, 3, 8), 4), new DailyClicks(LocalDate.of(2026, 3, 9), 1));
        return new ShortUrlStats(CODE, "America/New_York", LocalDate.of(2026, 3, 7), LocalDate.of(2026, 3, 9), 1234L,
                6L, lastAccessedAt, daily);
    }

    private void givenStats() {
        when(service.stats(any(), any(), any(), any(), any())).thenReturn(stats(LAST));
    }

    private Set<String> keys(MvcResult result) throws Exception {
        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
        Set<String> names = new TreeSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    // ---- AC1, D101

    @Test
    void shouldReturn200WithExactlyTheD101FieldsAndFormats() throws Exception {
        givenStats();

        MvcResult result = mockMvc.perform(get(PATH).queryParam("timezone", "America/New_York")
                        .with(httpBasic(ALICE, ALICE_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.shortCode").value(CODE))
                .andExpect(jsonPath("$.timezone").value("America/New_York"))
                .andExpect(jsonPath("$.from").value("2026-03-07"))
                .andExpect(jsonPath("$.to").value("2026-03-09"))
                .andExpect(jsonPath("$.totalClicks").value(1234))
                .andExpect(jsonPath("$.clicksInRange").value(6))
                .andExpect(jsonPath("$.lastAccessedAt").value("2026-03-09T03:59:59.999999Z"))
                .andExpect(jsonPath("$.daily.length()").value(3))
                .andExpect(jsonPath("$.daily[0].date").value("2026-03-07"))
                .andExpect(jsonPath("$.daily[0].clicks").value(1))
                .andExpect(jsonPath("$.daily[1].date").value("2026-03-08"))
                .andExpect(jsonPath("$.daily[1].clicks").value(4))
                .andExpect(jsonPath("$.daily[2].date").value("2026-03-09"))
                .andReturn();

        assertThat(keys(result)).isEqualTo(new TreeSet<>(STATS_KEYS));
        JsonNode first = objectMapper.readTree(result.getResponse().getContentAsString()).get("daily").get(0);
        Set<String> dailyKeys = new TreeSet<>();
        first.fieldNames().forEachRemaining(dailyKeys::add);
        assertThat(dailyKeys).containsExactly("clicks", "date");
    }

    @Test
    void shouldWriteLastAccessedAtAsAPresentNullBeforeTheFirstClick() throws Exception {
        when(service.stats(any(), any(), any(), any(), any())).thenReturn(stats(null));

        MvcResult result = mockMvc.perform(get(PATH).with(httpBasic(ALICE, ALICE_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lastAccessedAt").value((Object) null))
                .andReturn();

        assertThat(keys(result)).isEqualTo(new TreeSet<>(STATS_KEYS));
    }

    // ---- AC2: raw strings are passed through untouched

    @Test
    void shouldPassNullsWhenNoParameterIsSupplied() throws Exception {
        givenStats();

        mockMvc.perform(get(PATH).with(httpBasic(ALICE, ALICE_PASSWORD))).andExpect(status().isOk());

        verify(service).stats(eq(CODE), eq(null), eq(null), eq(null), any(Caller.class));
    }

    @Test
    void shouldPassTheRawDecodedStringsIncludingAnEmptyTimezone() throws Exception {
        givenStats();

        mockMvc.perform(get(PATH + "?timezone=&from=2026-02-30&to=x").with(httpBasic(ALICE, ALICE_PASSWORD)))
                .andExpect(status().isOk());

        verify(service).stats(eq(CODE), eq(""), eq("2026-02-30"), eq("x"), any(Caller.class));
    }

    @Test
    void shouldPassAPlusInTheTimezoneThroughUntouched() throws Exception {
        givenStats();

        // MockMvc takes queryParam values as already decoded (the %2B decoding itself is proven over real HTTP).
        mockMvc.perform(get(PATH).queryParam("timezone", "Etc/GMT+5").with(httpBasic(ALICE, ALICE_PASSWORD)))
                .andExpect(status().isOk());

        verify(service).stats(eq(CODE), eq("Etc/GMT+5"), eq(null), eq(null), any(Caller.class));
    }

    // ---- AC4, AC5: who is calling

    @Test
    void shouldPassTheConfiguredUsernameAsANonAdminCaller() throws Exception {
        givenStats();

        mockMvc.perform(get(PATH).with(httpBasic("ALICE", ALICE_PASSWORD))).andExpect(status().isOk());

        ArgumentCaptor<Caller> caller = ArgumentCaptor.forClass(Caller.class);
        verify(service).stats(any(), any(), any(), any(), caller.capture());
        assertThat(caller.getValue()).isEqualTo(new Caller("alice", false));
    }

    @Test
    void shouldPassAnAdminCallerWithTheAdminFlagSet() throws Exception {
        givenStats();

        mockMvc.perform(get(PATH).with(httpBasic(ADMIN, ADMIN_PASSWORD))).andExpect(status().isOk());

        ArgumentCaptor<Caller> caller = ArgumentCaptor.forClass(Caller.class);
        verify(service).stats(any(), any(), any(), any(), caller.capture());
        assertThat(caller.getValue()).isEqualTo(new Caller("admin", true));
    }

    // ---- AC4, AC6, AC16: one 404

    @Test
    void shouldReturn404ShortUrlNotFoundWithTheBaseKeysOnly() throws Exception {
        when(service.stats(any(), any(), any(), any(), any())).thenThrow(new ShortUrlNotFoundException());

        MvcResult result = mockMvc.perform(get(PATH).with(httpBasic(ALICE, ALICE_PASSWORD)))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errorCode").value("SHORT_URL_NOT_FOUND"))
                .andExpect(jsonPath("$.instance").value(PATH))
                .andReturn();

        assertThat(keys(result)).isEqualTo(new TreeSet<>(BASE_KEYS));
    }

    // ---- AC7

    @Test
    void shouldReturn401AuthenticationRequiredAndNotCallTheServiceWhenAnonymousOrWrongPassword() throws Exception {
        MvcResult anonymous = mockMvc.perform(get(PATH))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, startsWith("Basic")))
                .andExpect(jsonPath("$.errorCode").value("AUTHENTICATION_REQUIRED"))
                .andReturn();
        mockMvc.perform(get(PATH).with(httpBasic(ALICE, "wrong-password")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTHENTICATION_REQUIRED"));

        assertThat(keys(anonymous)).isEqualTo(new TreeSet<>(BASE_KEYS));
        verifyNoInteractions(service);
    }

    // ---- AC3, AC11, AC12, AC13: the advice

    @Test
    void shouldReturn400ValidationFailedWithSortedErrorsAndTheQueryDetailAndNoEchoedValue() throws Exception {
        when(service.stats(any(), any(), any(), any(), any())).thenThrow(new InvalidStatsQueryException(List.of(
                new Violation(StatsParameter.TO, StatsPeriod.DATE_RULE),
                new Violation(StatsParameter.TIMEZONE, StatsPeriod.TIMEZONE_RULE),
                new Violation(StatsParameter.FROM, StatsPeriod.DATE_RULE))));

        MvcResult result = mockMvc.perform(get(PATH + "?timezone=secret-zone-marker&from=secret-from-marker"
                        + "&to=secret-to-marker").with(httpBasic(ALICE, ALICE_PASSWORD)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.detail").value("The query parameters failed validation."))
                .andExpect(jsonPath("$.instance").value(PATH))
                .andExpect(jsonPath("$.errors.length()").value(3))
                .andExpect(jsonPath("$.errors[0].field").value("from"))
                .andExpect(jsonPath("$.errors[0].message").value(StatsPeriod.DATE_RULE))
                .andExpect(jsonPath("$.errors[1].field").value("timezone"))
                .andExpect(jsonPath("$.errors[1].message").value(StatsPeriod.TIMEZONE_RULE))
                .andExpect(jsonPath("$.errors[2].field").value("to"))
                .andReturn();

        assertThat(keys(result)).isEqualTo(new TreeSet<>(Set.of("type", "title", "status", "detail", "instance",
                "errorCode", "errors")));
        assertThat(result.getResponse().getContentAsString()).doesNotContain("marker").doesNotContain("?");
    }

    @Test
    void shouldReturn400OnTheFromFieldForRangeErrors() throws Exception {
        when(service.stats(any(), any(), any(), any(), any())).thenThrow(
                new InvalidStatsQueryException(List.of(new Violation(StatsParameter.FROM, StatsPeriod.LENGTH_RULE))));

        mockMvc.perform(get(PATH).with(httpBasic(ALICE, ALICE_PASSWORD)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.length()").value(1))
                .andExpect(jsonPath("$.errors[0].field").value("from"))
                .andExpect(jsonPath("$.errors[0].message").value(StatsPeriod.LENGTH_RULE));
    }

    // ---- AC14, D100

    @ParameterizedTest
    @ValueSource(strings = {"tz=UTC", "timeZone=UTC", "Timezone=UTC", "TIMEZONE=UTC", "unknown=1", "from2=2026-01-01",
            "timezone=UTC&extra=1", "timezone=UTC&timezone=UTC", "from=2026-01-01&from=2026-01-02",
            "to=2026-01-01&to=", "timezone=UTC&timezone=America/New_York", "=x", "to=2026-01-02&tz-marker="})
    void shouldReturn400MalformedRequestWithBaseKeysAndNoEchoedNameForUnknownOrRepeatedParameters(String query)
            throws Exception {
        MvcResult result = mockMvc.perform(get(PATH + "?" + query).with(httpBasic(ALICE, ALICE_PASSWORD)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errorCode").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.detail").value("The request could not be read."))
                .andExpect(jsonPath("$.instance").value(PATH))
                .andReturn();

        assertThat(keys(result)).isEqualTo(new TreeSet<>(BASE_KEYS));
        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain("timeZone").doesNotContain("Timezone").doesNotContain("unknown")
                .doesNotContain("extra").doesNotContain("marker").doesNotContain("Unexpected");
        verifyNoInteractions(service);
    }

    @Test
    void shouldAcceptEachKnownParameterExactlyOnceInAnyOrder() throws Exception {
        givenStats();

        mockMvc.perform(get(PATH + "?to=2026-03-09&timezone=UTC&from=2026-03-07")
                        .with(httpBasic(ALICE, ALICE_PASSWORD)))
                .andExpect(status().isOk());

        verify(service, times(1)).stats(eq(CODE), eq("UTC"), eq("2026-03-07"), eq("2026-03-09"), any(Caller.class));
    }

    @Test
    void shouldRejectUnknownParametersBeforeTheServiceForAnyCodeSoNothingIsLearnedAboutTheLink() throws Exception {
        when(service.stats(any(), any(), any(), any(), any())).thenThrow(new ShortUrlNotFoundException());

        // The same unknown parameter gives 400 on a code the caller may not see; the valid request gives the 404.
        mockMvc.perform(get("/api/v1/urls/NoSuch1/stats?tz=UTC").with(httpBasic(ALICE, ALICE_PASSWORD)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("MALFORMED_REQUEST"));
        verify(service, never()).stats(any(), any(), any(), any(), any());
        mockMvc.perform(get("/api/v1/urls/NoSuch1/stats").with(httpBasic(ALICE, ALICE_PASSWORD)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("SHORT_URL_NOT_FOUND"));
    }

    // ---- negotiation and methods (D70)

    @ParameterizedTest
    @ValueSource(strings = {"application/xml", "text/plain", "application/problem+json"})
    void shouldReturn406BeforeValidationAndNotCallTheServiceForAnUnacceptableAccept(String accept) throws Exception {
        MvcResult result = mockMvc.perform(get(PATH + "?tz=UTC").with(httpBasic(ALICE, ALICE_PASSWORD))
                        .header(HttpHeaders.ACCEPT, accept))
                .andExpect(status().isNotAcceptable())
                .andExpect(jsonPath("$.errorCode").value("NOT_ACCEPTABLE"))
                .andReturn();

        assertThat(keys(result)).isEqualTo(new TreeSet<>(BASE_KEYS));
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"application/json", "*/*"})
    void shouldReturn200ForAnAcceptableAccept(String accept) throws Exception {
        givenStats();

        mockMvc.perform(get(PATH).with(httpBasic(ALICE, ALICE_PASSWORD)).header(HttpHeaders.ACCEPT, accept))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
    }

    @Test
    void shouldReturn405WithAllowContainingGetForPostAndNotCallTheService() throws Exception {
        mockMvc.perform(post(PATH).with(httpBasic(ALICE, ALICE_PASSWORD)))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string(HttpHeaders.ALLOW, containsString("GET")))
                .andExpect(jsonPath("$.errorCode").value("METHOD_NOT_ALLOWED"));

        verifyNoInteractions(service);
    }

    @Test
    void shouldReturn403ForAUserAnd405ForAnAdminOnDeleteOfTheStatsPath() throws Exception {
        mockMvc.perform(delete(PATH).with(httpBasic(ALICE, ALICE_PASSWORD)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("ACCESS_DENIED"));
        mockMvc.perform(delete(PATH).with(httpBasic(ADMIN, ADMIN_PASSWORD)))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.errorCode").value("METHOD_NOT_ALLOWED"));

        verifyNoInteractions(service);
    }

    @Test
    void shouldReturn404ResourceNotFoundForATrailingSlashAndNotCallTheService() throws Exception {
        mockMvc.perform(get(PATH + "/").with(httpBasic(ALICE, ALICE_PASSWORD)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("RESOURCE_NOT_FOUND"));

        verifyNoInteractions(service);
    }

    @Test
    void shouldCarrySecuritysDefaultCacheControlOnThe200() throws Exception {
        givenStats();

        mockMvc.perform(get(PATH).with(httpBasic(ALICE, ALICE_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-cache, no-store, max-age=0, must-revalidate"))
                .andExpect(header().string(HttpHeaders.PRAGMA, "no-cache"))
                .andExpect(header().string(HttpHeaders.EXPIRES, "0"));
    }

    @Test
    void shouldReturn500WithAGenericBodyWhenTheServiceFailsUnexpectedly() throws Exception {
        when(service.stats(any(), any(), any(), any(), any())).thenThrow(new IllegalStateException("secret-marker"));

        MvcResult result = mockMvc.perform(get(PATH).with(httpBasic(ALICE, ALICE_PASSWORD)))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.errorCode").value("INTERNAL_ERROR"))
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).doesNotContain("secret-marker");
    }

    // ---- HEAD: same status as GET

    @Test
    void shouldAnswerHeadWith200JsonAndRunTheQuery() throws Exception {
        givenStats();

        mockMvc.perform(head(PATH).with(httpBasic(ALICE, ALICE_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));

        // MockMvc does not strip the HEAD body (Tomcat does), so the empty body is checked in the integration tests.
        verify(service, times(1)).stats(eq(CODE), eq(null), eq(null), eq(null), any(Caller.class));
    }

    @Test
    void shouldAnswerHeadWith404And400LikeGetWithAHead200ControlOnTheSamePath() throws Exception {
        givenStats();
        // Control: the same path answers HEAD 200 for an authorised caller.
        mockMvc.perform(head(PATH).with(httpBasic(ALICE, ALICE_PASSWORD))).andExpect(status().isOk());

        when(service.stats(any(), any(), any(), any(), any())).thenThrow(new ShortUrlNotFoundException());
        mockMvc.perform(head(PATH).with(httpBasic(ALICE, ALICE_PASSWORD))).andExpect(status().isNotFound());
        mockMvc.perform(get(PATH).with(httpBasic(ALICE, ALICE_PASSWORD)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("SHORT_URL_NOT_FOUND"));

        mockMvc.perform(head(PATH + "?tz=UTC").with(httpBasic(ALICE, ALICE_PASSWORD)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get(PATH + "?tz=UTC").with(httpBasic(ALICE, ALICE_PASSWORD)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("MALFORMED_REQUEST"));
    }

    @Test
    void shouldAnswerHeadWith401ForAnonymousCallers() throws Exception {
        mockMvc.perform(head(PATH)).andExpect(status().isUnauthorized());

        verifyNoInteractions(service);
    }
}
