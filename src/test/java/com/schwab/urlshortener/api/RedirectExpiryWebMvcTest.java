package com.schwab.urlshortener.api;

import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.schwab.urlshortener.security.SecuritySliceTestConfiguration;
import com.schwab.urlshortener.service.RedirectService;
import com.schwab.urlshortener.service.exception.ShortUrlExpiredException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** US-016 AC3: the 410 mapping of an expired link on the public redirect (D109, D110, D119). Rule 7 (unchanged). */
@WebMvcTest(RedirectController.class)
@Import(SecuritySliceTestConfiguration.class)
@ActiveProfiles("test")
class RedirectExpiryWebMvcTest {

    private static final String CODE = "aB3dE9x";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RedirectService service;

    @Test
    void shouldReturn410ProblemJsonWithExactlyNoStoreForAnExpiredLinkOnGet() throws Exception {
        when(service.resolveAndRecordClick(CODE)).thenThrow(new ShortUrlExpiredException());

        mockMvc.perform(get("/" + CODE))
                .andExpect(status().isGone())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().stringValues(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().doesNotExist(HttpHeaders.LOCATION))
                .andExpect(jsonPath("$.status").value(410))
                .andExpect(jsonPath("$.errorCode").value("SHORT_URL_EXPIRED"))
                .andExpect(jsonPath("$.detail").value("The short URL has expired."))
                .andExpect(jsonPath("$.instance").value("/" + CODE));
    }

    @Test
    void shouldReturn410WithNoStoreForAnExpiredLinkOnHeadWhileTheSamePathRedirectsOtherwise() throws Exception {
        // MockMvc does not strip a HEAD body; the empty HEAD body is proven over real Tomcat in ExpirationIT.
        when(service.resolve(CODE)).thenThrow(new ShortUrlExpiredException());

        mockMvc.perform(head("/" + CODE))
                .andExpect(status().isGone())
                .andExpect(header().stringValues(HttpHeaders.CACHE_CONTROL, "no-store"));

        // Same-path control: the mapping was reached, and a non-expired link answers 302 there.
        reset(service);
        when(service.resolve(CODE)).thenReturn("https://example.com/x");
        mockMvc.perform(head("/" + CODE)).andExpect(status().isFound());
        // The 410's errorCode, asserted on GET since HEAD has no body (CLAUDE.md).
        when(service.resolveAndRecordClick(CODE)).thenThrow(new ShortUrlExpiredException());
        mockMvc.perform(get("/" + CODE)).andExpect(jsonPath("$.errorCode").value("SHORT_URL_EXPIRED"));
    }
}
