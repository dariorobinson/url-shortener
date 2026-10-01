package com.schwab.urlshortener.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * US-014 AC6, the untrusted half: when the client is not a configured internal proxy, a forged
 * {@code X-Forwarded-Proto: https} is ignored and no HSTS header is sent. Deliberately not an
 * {@link IntegrationTestBase} subclass: it needs its own Tomcat configuration (only 192.0.2.1, a documentation
 * address, is trusted), which would otherwise change the shared context. It costs one extra context.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.tomcat.remoteip.internal-proxies=192\\.0\\.2\\.1")
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class UntrustedProxyHstsIT {

    @LocalServerPort
    private int port;

    private HttpResponse<String> health(boolean forwardedHttps) throws Exception {
        HttpRequest.Builder builder =
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/health"));
        if (forwardedHttps) {
            builder.header("X-Forwarded-Proto", "https");
        }
        return HttpClient.newHttpClient().send(builder.GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void shouldIgnoreAForwardedHttpsHeaderFromAnUntrustedClient() throws Exception {
        HttpResponse<String> forged = health(true);

        assertThat(forged.statusCode()).isEqualTo(200);
        assertThat(forged.headers().firstValue("Strict-Transport-Security")).isEmpty();
        assertThat(health(false).headers().firstValue("Strict-Transport-Security")).isEmpty();
    }
}
