package com.schwab.urlshortener.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.management.ManagementFactory;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * AC11 (D43): Mockito must be loaded via an explicit -javaagent argument on the forked test JVM,
 * not dynamic self-attachment.
 */
class JvmAgentTest {

    private static final Pattern MOCKITO_JAVAAGENT = Pattern.compile("-javaagent:.*mockito-core-.*\\.jar");

    @Test
    void shouldLoadMockitoAsExplicitJavaAgent() {
        var inputArguments = ManagementFactory.getRuntimeMXBean().getInputArguments();

        assertThat(inputArguments).anyMatch(argument -> MOCKITO_JAVAAGENT.matcher(argument).matches());
    }
}
