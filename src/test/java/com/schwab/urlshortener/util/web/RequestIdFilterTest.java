package com.schwab.urlshortener.util.web;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** US-014 AC1: the request ID is accepted only as a plain token, is in the MDC during the request, and is cleared. */
class RequestIdFilterTest {

    private static final String UUID_PATTERN = "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$";

    private final RequestIdFilter filter = new RequestIdFilter();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    /** Runs the filter, capturing the MDC value seen by the rest of the chain. */
    private String run(MockHttpServletRequest request, MockHttpServletResponse response) throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        filter.doFilter(request, response, new MockFilterChain() {
            @Override
            public void doFilter(ServletRequest req, ServletResponse res) {
                seen.set(MDC.get(RequestIdFilter.MDC_KEY));
            }
        });
        return seen.get();
    }

    @Test
    void shouldGenerateAUuidWhenNoIdIsSuppliedAndPutItInTheMdcAndTheResponse() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        String inChain = run(new MockHttpServletRequest("GET", "/abc1234"), response);

        assertThat(inChain).matches(UUID_PATTERN);
        assertThat(response.getHeader(RequestIdFilter.HEADER)).isEqualTo(inChain);
        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void shouldReuseAPlainSuppliedId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/abc1234");
        request.addHeader(RequestIdFilter.HEADER, "client-Req-42");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(run(request, response)).isEqualTo("client-Req-42");
        assertThat(response.getHeader(RequestIdFilter.HEADER)).isEqualTo("client-Req-42");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "has space", "a\tb", "semi;colon", "x=1", "élan", "id_with_underscore"})
    void shouldReplaceASuppliedIdThatIsNotAPlainToken(String supplied) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/abc1234");
        request.addHeader(RequestIdFilter.HEADER, supplied);
        MockHttpServletResponse response = new MockHttpServletResponse();

        String used = run(request, response);

        assertThat(used).matches(UUID_PATTERN).isNotEqualTo(supplied);
        assertThat(response.getHeader(RequestIdFilter.HEADER)).isEqualTo(used);
    }

    @Test
    void shouldAcceptSixtyFourCharactersAndReplaceSixtyFive() throws Exception {
        MockHttpServletRequest ok = new MockHttpServletRequest("GET", "/");
        ok.addHeader(RequestIdFilter.HEADER, "a".repeat(64));
        MockHttpServletRequest tooLong = new MockHttpServletRequest("GET", "/");
        tooLong.addHeader(RequestIdFilter.HEADER, "a".repeat(65));

        assertThat(run(ok, new MockHttpServletResponse())).isEqualTo("a".repeat(64));
        assertThat(run(tooLong, new MockHttpServletResponse())).matches(UUID_PATTERN);
    }

    @Test
    void shouldReuseTheSameIdOnTheErrorDispatchOfTheSameRequest() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/abc1234");
        String first = run(request, new MockHttpServletResponse());
        request.setDispatcherType(DispatcherType.ERROR);

        String onError = run(request, new MockHttpServletResponse());

        assertThat(onError).isEqualTo(first);
    }

    @Test
    void shouldRestoreAnOuterMdcValueAfterTheRequest() throws Exception {
        MDC.put(RequestIdFilter.MDC_KEY, "outer");

        String inChain = run(new MockHttpServletRequest("GET", "/"), new MockHttpServletResponse());

        assertThat(inChain).isNotEqualTo("outer");
        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isEqualTo("outer");
    }
}
