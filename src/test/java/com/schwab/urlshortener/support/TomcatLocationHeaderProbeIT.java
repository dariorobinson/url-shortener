package com.schwab.urlshortener.support;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.apache.catalina.Context;
import org.apache.catalina.startup.Tomcat;
import org.apache.tomcat.util.modeler.Registry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Evidence for D75: what the Tomcat that Spring Boot manages does with a non-ASCII {@code Location} value
 * when the application does not encode it. It starts a bare embedded Tomcat with one servlet on an
 * ephemeral port, with no Spring context, so it cannot change the shared context or start another
 * PostgreSQL container. The servlet is the "before" behaviour that the production redirect avoids by
 * percent-encoding; the raw socket reads the header bytes exactly as sent.
 */
class TomcatLocationHeaderProbeIT {

    private static final String DROPPED_MESSAGE = "has been removed from the response because it is invalid";
    private static final String PROCESSOR_LOGGER = "org.apache.coyote.http11.Http11Processor";

    private Tomcat tomcat;
    private Path baseDir;
    private volatile String location;
    private Logger processorLogger;
    private Level originalLevel;
    private final List<String> warnings = new ArrayList<>();
    private final Handler capture = new Handler() {
        @Override
        public void publish(LogRecord record) {
            warnings.add(record.getLevel() + " " + record.getMessage());
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    };

    @BeforeEach
    void startBareTomcat() throws Exception {
        baseDir = Files.createTempDirectory("tomcat-location-probe");
        Registry.disableRegistry();
        tomcat = new Tomcat();
        tomcat.setBaseDir(baseDir.toString());
        tomcat.setPort(0);
        Context context = tomcat.addContext("", baseDir.toString());
        Tomcat.addServlet(context, "probe", new HttpServlet() {
            @Override
            protected void doGet(HttpServletRequest request, HttpServletResponse response) {
                response.setStatus(HttpServletResponse.SC_FOUND);
                response.addHeader("Location", location);
                response.addHeader("X-Probe", "reached");
            }
        });
        context.addServletMappingDecoded("/*", "probe");
        tomcat.getConnector();
        processorLogger = Logger.getLogger(PROCESSOR_LOGGER);
        originalLevel = processorLogger.getLevel();
        processorLogger.setLevel(Level.ALL);
        processorLogger.addHandler(capture);
        tomcat.start();
    }

    @AfterEach
    void stopTomcat() throws Exception {
        processorLogger.removeHandler(capture);
        processorLogger.setLevel(originalLevel);
        tomcat.stop();
        tomcat.destroy();
        try (var walk = Files.walk(baseDir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    /** Raw response head, one character per byte (ISO-8859-1), so header bytes are visible exactly. */
    private String rawResponseHead() throws IOException {
        try (Socket socket = new Socket("localhost", tomcat.getConnector().getLocalPort())) {
            socket.setSoTimeout(10_000);
            socket.getOutputStream().write("GET /probe HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"
                    .getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            socket.getInputStream().transferTo(bytes);
            String response = bytes.toString(StandardCharsets.ISO_8859_1);
            return response.substring(0, response.indexOf("\r\n\r\n"));
        }
    }

    @Test
    void shouldSendLatin1RangeCharactersAsASingleNonUtf8ByteWithoutEncoding() throws Exception {
        location = "https://example.com/é";

        String head = rawResponseHead();

        assertThat(head).startsWith("HTTP/1.1 302");
        assertThat(head).contains("X-Probe: reached");
        // Latin-1, not UTF-8: one byte 0xE9, which any client will misread.
        assertThat(head).contains("Location: https://example.com/é\r\n");
        assertThat(head).doesNotContain("%C3%A9").doesNotContain("Ã©");
        assertThat(warnings).noneMatch(line -> line.contains(DROPPED_MESSAGE));
    }

    @Test
    void shouldDropTheLocationHeaderSendThe302AnywayAndLogTheFullUrlForCharactersAboveLatin1() throws Exception {
        location = "https://example.com/中";

        String head = rawResponseHead();

        // The redirect is sent (positive control: the servlet ran and its other header arrived) ...
        assertThat(head).startsWith("HTTP/1.1 302");
        assertThat(head).contains("X-Probe: reached");
        // ... but the Location header is gone, and the full URL is logged at WARN.
        assertThat(head.toLowerCase(Locale.ROOT)).doesNotContain("location:");
        assertThat(warnings).anyMatch(line -> line.startsWith("WARNING") && line.contains(DROPPED_MESSAGE)
                && line.contains("https://example.com/\u4e2d"));
    }

    @Test
    void shouldSendAPercentEncodedLocationUnchanged() throws Exception {
        location = "https://example.com/%E4%B8%AD";

        String head = rawResponseHead();

        assertThat(head).startsWith("HTTP/1.1 302");
        assertThat(head).contains("Location: https://example.com/%E4%B8%AD\r\n");
        assertThat(warnings).noneMatch(line -> line.contains(DROPPED_MESSAGE));
    }
}
