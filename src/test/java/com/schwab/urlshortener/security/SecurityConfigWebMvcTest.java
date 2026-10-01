package com.schwab.urlshortener.security;

import static com.schwab.urlshortener.support.TestUsers.ADMIN;
import static com.schwab.urlshortener.support.TestUsers.ADMIN_PASSWORD;
import static com.schwab.urlshortener.support.TestUsers.ALICE;
import static com.schwab.urlshortener.support.TestUsers.ALICE_PASSWORD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.firewall.RequestRejectedException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Filter-chain rules against the real provider and the test users, through a test-only secured
 * controller (AC1 to AC5, AC7, D3, D30, D32, D37, D52, D54, D55). No business endpoint exists yet.
 */
@WebMvcTest(controllers = SecurityConfigWebMvcTest.SecurityProbeController.class)
@Import({SecuritySliceTestConfiguration.class, SecurityConfigWebMvcTest.SecurityProbeController.class})
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class SecurityConfigWebMvcTest {

    @RestController
    static class SecurityProbeController {

        static final AtomicInteger HANDLER_CALLS = new AtomicInteger();

        @GetMapping("/api/test-probe")
        Map<String, Object> probeGet(Authentication authentication) {
            return probe(authentication);
        }

        @PostMapping("/api/test-probe")
        Map<String, Object> probePost(Authentication authentication) {
            return probe(authentication);
        }

        @DeleteMapping("/api/v1/urls/{code}")
        @ResponseStatus(HttpStatus.NO_CONTENT)
        void delete(@PathVariable String code) {
            HANDLER_CALLS.incrementAndGet();
        }

        @DeleteMapping("/api/v1/urls/{code}/")
        @ResponseStatus(HttpStatus.NO_CONTENT)
        void deleteTrailingSlash(@PathVariable String code) {
            HANDLER_CALLS.incrementAndGet();
        }

        @DeleteMapping("/api/v1/urls/{code}/x")
        @ResponseStatus(HttpStatus.NO_CONTENT)
        void deleteNested(@PathVariable String code) {
            HANDLER_CALLS.incrementAndGet();
        }

        /** Test-only upper-case probe: an unmatched path must never reach it (D57). */
        @DeleteMapping("/API/v1/urls/{code}")
        @ResponseStatus(HttpStatus.NO_CONTENT)
        void deleteUpperCase(@PathVariable String code) {
            HANDLER_CALLS.incrementAndGet();
        }

        @PostMapping("/{code}")
        @ResponseStatus(HttpStatus.NO_CONTENT)
        void postSingleSegment(@PathVariable String code) {
            HANDLER_CALLS.incrementAndGet();
        }

        @GetMapping("/{a}/{b}")
        @ResponseStatus(HttpStatus.NO_CONTENT)
        void getTwoSegments(@PathVariable String a, @PathVariable String b) {
            HANDLER_CALLS.incrementAndGet();
        }

        private static Map<String, Object> probe(Authentication authentication) {
            List<String> authorities = authentication.getAuthorities().stream()
                    .map(GrantedAuthority::getAuthority).sorted().toList();
            return Map.of("name", authentication.getName(), "authorities", authorities);
        }
    }

    private static final String CHALLENGE = "Basic realm=\"url-shortener\"";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ApplicationContext context;

    @BeforeEach
    void resetCounter() {
        SecurityProbeController.HANDLER_CALLS.set(0);
    }

    private void assertAuthenticationRequired(MockHttpServletRequestBuilder request) throws Exception {
        mockMvc.perform(request).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTHENTICATION_REQUIRED"))
                .andExpect(jsonPath("$.status").value(401));
    }

    // ---- AC1

    @Test
    void shouldReturn401ProblemDetailWithBasicChallengeWhenCredentialsMissing() throws Exception {
        mockMvc.perform(get("/api/test-probe"))
                .andExpect(status().isUnauthorized())
                .andExpect(result -> assertThat(result.getResponse().getHeader(HttpHeaders.WWW_AUTHENTICATE))
                        .isEqualTo(CHALLENGE))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errorCode").value("AUTHENTICATION_REQUIRED"))
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.instance").value("/api/test-probe"));
    }

    @Test
    void shouldReturnIdentical401ForWrongPasswordUnknownUserMalformedHeaderAndNoCredentials() throws Exception {
        List<MvcResult> results = List.of(
                mockMvc.perform(get("/api/test-probe")).andReturn(),
                mockMvc.perform(get("/api/test-probe").with(httpBasic(ALICE, "wrong-password"))).andReturn(),
                mockMvc.perform(get("/api/test-probe").with(httpBasic("nobody", ALICE_PASSWORD))).andReturn(),
                mockMvc.perform(get("/api/test-probe").header(HttpHeaders.AUTHORIZATION, "Basic !!not-base64!!"))
                        .andReturn());

        MvcResult first = results.get(0);
        assertThat(first.getResponse().getStatus()).isEqualTo(401);
        for (MvcResult other : results) {
            assertThat(other.getResponse().getStatus()).isEqualTo(401);
            assertThat(other.getResponse().getContentAsByteArray()).isEqualTo(first.getResponse().getContentAsByteArray());
            assertThat(headers(other)).isEqualTo(headers(first));
        }
    }

    private static Map<String, List<String>> headers(MvcResult result) {
        Map<String, List<String>> headers = new HashMap<>();
        result.getResponse().getHeaderNames().forEach(n -> headers.put(n, result.getResponse().getHeaders(n)));
        return headers;
    }

    // ---- AC2, D54

    @Test
    void shouldAuthenticateUserAndPopulateRole() throws Exception {
        mockMvc.perform(get("/api/test-probe").with(httpBasic(ALICE, ALICE_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value(ALICE))
                .andExpect(jsonPath("$.authorities.length()").value(1))
                .andExpect(jsonPath("$.authorities[0]").value("ROLE_USER"));
    }

    @Test
    void shouldAuthenticateAdminWithAdminRoleOnly() throws Exception {
        mockMvc.perform(get("/api/test-probe").with(httpBasic(ADMIN, ADMIN_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authorities.length()").value(1))
                .andExpect(jsonPath("$.authorities[0]").value("ROLE_ADMIN"));
    }

    @Test
    void shouldAuthenticateCaseInsensitivelyButKeepConfiguredUsername() throws Exception {
        mockMvc.perform(get("/api/test-probe").with(httpBasic("ALICE", ALICE_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value(ALICE));
    }

    @Test
    void shouldStillRequireTheExactPasswordWhenUsernameCaseDiffers() throws Exception {
        assertAuthenticationRequired(get("/api/test-probe").with(httpBasic("ALICE", ALICE_PASSWORD.toUpperCase(Locale.ROOT))));
    }

    // ---- AC3

    @Test
    void shouldLetAdminPassUserOnlyRule() throws Exception {
        mockMvc.perform(get("/api/test-probe").with(httpBasic(ADMIN, ADMIN_PASSWORD))).andExpect(status().isOk());
    }

    // ---- AC4

    @Test
    void shouldReturn403AccessDeniedWhenUserDeletes() throws Exception {
        mockMvc.perform(delete("/api/v1/urls/abc1234").with(httpBasic(ALICE, ALICE_PASSWORD)))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errorCode").value("ACCESS_DENIED"))
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.instance").value("/api/v1/urls/abc1234"));
        assertThat(SecurityProbeController.HANDLER_CALLS).hasValue(0);
    }

    @Test
    void shouldLetAdminReachDeleteHandler() throws Exception {
        mockMvc.perform(delete("/api/v1/urls/abc1234").with(httpBasic(ADMIN, ADMIN_PASSWORD)))
                .andExpect(status().isNoContent());
        assertThat(SecurityProbeController.HANDLER_CALLS).hasValue(1);
    }

    @Test
    void shouldReturn401NotHandlerForAnonymousDelete() throws Exception {
        assertAuthenticationRequired(delete("/api/v1/urls/abc1234"));
        assertThat(SecurityProbeController.HANDLER_CALLS).hasValue(0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/urls/abc1234", "/api/v1/urls/abc1234/", "/api/v1/urls/abc1234/x"})
    void shouldReturn403AndNotReachHandlerWhenUserDeletesAnyMappedVariant(String path) throws Exception {
        mockMvc.perform(delete(path).with(httpBasic(ALICE, ALICE_PASSWORD)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("ACCESS_DENIED"));
        assertThat(SecurityProbeController.HANDLER_CALLS).hasValue(0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/urls/abc1234", "/api/v1/urls/abc1234/", "/api/v1/urls/abc1234/x"})
    void shouldLetAdminReachHandlerOnEveryMappedVariant(String path) throws Exception {
        mockMvc.perform(delete(path).with(httpBasic(ADMIN, ADMIN_PASSWORD))).andExpect(status().isNoContent());
        assertThat(SecurityProbeController.HANDLER_CALLS).hasValue(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/API/v1/urls/abc", "/api/v1/urls/abc;x=1", "/api/v1//urls/abc",
            "/api/v1/urls/%2e%2e"})
    void shouldNotReachDeleteHandlerThroughPathVariantsAsUser(String path) throws Exception {
        // The servlet path is given as a URI so that MockMvc does not re-encode the percent signs.
        // The strict firewall may reject the request outright; either way the handler is not reached.
        try {
            MvcResult result = mockMvc.perform(delete(URI.create(path)).with(httpBasic(ALICE, ALICE_PASSWORD)))
                    .andReturn();
            assertThat(result.getResponse().getStatus()).isNotEqualTo(204);
        } catch (RequestRejectedException rejected) {
            assertThat(rejected).isNotNull();
        }
        assertThat(SecurityProbeController.HANDLER_CALLS).hasValue(0);
    }

    // D57: no explicit rule matches the upper-case path, so the final denyAll rule refuses it for every role.
    @Test
    void shouldReturn403AndNotReachHandlerWhenUserDeletesUpperCaseApiPath() throws Exception {
        mockMvc.perform(delete("/API/v1/urls/abc1234").with(httpBasic(ALICE, ALICE_PASSWORD)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("ACCESS_DENIED"));
        assertThat(SecurityProbeController.HANDLER_CALLS).hasValue(0);
    }

    // D57: even ADMIN is refused, because only explicitly listed paths can reach a handler.
    @Test
    void shouldReturn403AndNotReachHandlerWhenAdminDeletesUpperCaseApiPath() throws Exception {
        mockMvc.perform(delete("/API/v1/urls/abc1234").with(httpBasic(ADMIN, ADMIN_PASSWORD)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("ACCESS_DENIED"));
        assertThat(SecurityProbeController.HANDLER_CALLS).hasValue(0);
    }

    // D57: an anonymous caller is routed to the entry point (401 with the Basic challenge), not 403.
    @Test
    void shouldReturn401WithChallengeWhenAnonymousDeletesUpperCaseApiPath() throws Exception {
        mockMvc.perform(delete("/API/v1/urls/abc1234"))
                .andExpect(status().isUnauthorized())
                .andExpect(result -> assertThat(result.getResponse().getHeader(HttpHeaders.WWW_AUTHENTICATE))
                        .isEqualTo(CHALLENGE))
                .andExpect(jsonPath("$.errorCode").value("AUTHENTICATION_REQUIRED"));
        assertThat(SecurityProbeController.HANDLER_CALLS).hasValue(0);
    }

    // D57: authenticated callers are refused on unmatched requests, and the mapped handlers are not reached.
    @Test
    void shouldReturn403ForAuthenticatedUnmatchedMethodsAndPaths() throws Exception {
        for (String[] who : new String[][] {{ALICE, ALICE_PASSWORD}, {ADMIN, ADMIN_PASSWORD}}) {
            mockMvc.perform(post("/abc1234").with(httpBasic(who[0], who[1])))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.errorCode").value("ACCESS_DENIED"));
            mockMvc.perform(put("/abc1234").with(httpBasic(who[0], who[1])))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.errorCode").value("ACCESS_DENIED"));
            mockMvc.perform(delete("/abc1234").with(httpBasic(who[0], who[1])))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.errorCode").value("ACCESS_DENIED"));
            mockMvc.perform(get("/a/b").with(httpBasic(who[0], who[1])))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.errorCode").value("ACCESS_DENIED"));
        }
        assertThat(SecurityProbeController.HANDLER_CALLS).hasValue(0);
    }

    // ---- AC5, D32

    @ParameterizedTest
    @ValueSource(strings = {"/abc1234", "/ab", "/v3", "/a_b"})
    void shouldPermitAnonymousGetAndHeadOnAnySingleSegmentPath(String path) throws Exception {
        for (MockHttpServletRequestBuilder request : List.of(get(path), head(path))) {
            MvcResult result = mockMvc.perform(request).andReturn();
            assertThat(result.getResponse().getStatus()).isNotIn(401, 403);
            assertThat(result.getResponse().getHeader(HttpHeaders.WWW_AUTHENTICATE)).isNull();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/health", "/v3/api-docs", "/v3/api-docs/swagger-config", "/v3/api-docs.yaml",
            "/swagger-ui.html", "/swagger-ui/index.html"})
    void shouldPermitAnonymousGetOnInfrastructurePaths(String path) throws Exception {
        MvcResult result = mockMvc.perform(get(path)).andReturn();

        assertThat(result.getResponse().getStatus()).isNotIn(401, 403);
        assertThat(result.getResponse().getHeader(HttpHeaders.WWW_AUTHENTICATE)).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api", "/actuator", "/actuator/env", "/a/b", "/api/anything", "/actuator/health/x"})
    void shouldRequireAuthenticationForReservedPrefixesAndNestedPaths(String path) throws Exception {
        assertAuthenticationRequired(get(path));
    }

    @Test
    void shouldRequireAuthenticationForNonReadMethodsOnASingleSegment() throws Exception {
        assertAuthenticationRequired(post("/abc1234"));
        assertAuthenticationRequired(put("/abc1234"));
        assertAuthenticationRequired(delete("/abc1234"));
        assertAuthenticationRequired(options("/abc1234"));
    }

    @Test
    void shouldNotPermitPostOnInfrastructurePaths() throws Exception {
        assertAuthenticationRequired(post("/actuator/health"));
        assertAuthenticationRequired(post("/v3/api-docs"));
    }

    // ---- D55

    @Test
    void shouldReturn401ForBadCredentialsEvenOnAPublicPath() throws Exception {
        mockMvc.perform(get("/abc1234").with(httpBasic(ALICE, "wrong-password")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTHENTICATION_REQUIRED"));
        mockMvc.perform(get("/actuator/health").with(httpBasic("nobody", "x")))
                .andExpect(status().isUnauthorized());
    }

    // ---- AC7

    @Test
    void shouldCreateNoSessionOrCookieOnSuccessUnauthorizedAndForbidden() throws Exception {
        List<MvcResult> results = List.of(
                mockMvc.perform(get("/api/test-probe").with(httpBasic(ALICE, ALICE_PASSWORD))).andReturn(),
                mockMvc.perform(get("/api/test-probe")).andReturn(),
                mockMvc.perform(delete("/api/v1/urls/abc1234").with(httpBasic(ALICE, ALICE_PASSWORD))).andReturn());

        assertThat(results).extracting(r -> r.getResponse().getStatus()).containsExactly(200, 401, 403);
        for (MvcResult result : results) {
            assertThat(result.getResponse().getHeader(HttpHeaders.SET_COOKIE)).isNull();
            assertThat(result.getResponse().getCookies()).isEmpty();
            assertThat(result.getRequest().getSession(false)).isNull();
        }
    }

    @Test
    void shouldAcceptAuthenticatedPostWithoutCsrfToken() throws Exception {
        mockMvc.perform(post("/api/test-probe").with(httpBasic(ALICE, ALICE_PASSWORD)))
                .andExpect(status().isOk());
    }

    // ---- D37

    @Test
    void shouldSendHstsOnlyOverHttps() throws Exception {
        MvcResult secure = mockMvc.perform(get("/api/test-probe").secure(true)
                .with(httpBasic(ALICE, ALICE_PASSWORD))).andReturn();
        MvcResult plain = mockMvc.perform(get("/api/test-probe").with(httpBasic(ALICE, ALICE_PASSWORD))).andReturn();

        assertThat(secure.getResponse().getHeader("Strict-Transport-Security"))
                .isEqualTo("max-age=31536000 ; includeSubDomains");
        assertThat(plain.getResponse().getHeader("Strict-Transport-Security")).isNull();
    }

    // ---- wiring

    @Test
    void shouldNotCreateBootDefaultUserOrPublishEncoderUserStoreOrSecondRoleHierarchy(CapturedOutput output) {
        assertThat(context.containsBean("inMemoryUserDetailsManager")).isFalse();
        assertThat(context.getBeansOfType(UserDetailsService.class)).isEmpty();
        assertThat(context.getBeansOfType(PasswordEncoder.class)).isEmpty();
        assertThat(context.getBeansOfType(RoleHierarchy.class)).hasSize(1).containsKey("roleHierarchy");
        assertThat(output.getAll()).doesNotContain("Using generated security password");
    }

    // ---- D52

    @Test
    void shouldNotLogUsernamesOrPasswordsOnRejectedRequests(CapturedOutput output) throws Exception {
        mockMvc.perform(get("/api/test-probe").with(httpBasic("mallory-attempt", "pw-attempt-marker")));
        mockMvc.perform(delete("/api/v1/urls/abc1234").with(httpBasic(ALICE, ALICE_PASSWORD)));

        assertThat(output.getAll()).doesNotContain("mallory-attempt").doesNotContain("pw-attempt-marker")
                .doesNotContain(ALICE_PASSWORD).doesNotContain("Authorization");
        assertThat(output.getAll()).doesNotContain("alice");
        // The handlers did run and log, so the absence checks above are not vacuous.
        assertThat(output.getAll())
                .contains("Authentication failed: GET /api/test-probe (BadCredentialsException)")
                .contains("Access denied: DELETE /api/v1/urls/abc1234");
    }
}
