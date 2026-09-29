package com.schwab.urlshortener.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.management.ManagementFactory;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * AC11 (D43): Mockito must be loaded via an explicit -javaagent argument on the forked test JVM,
 * not dynamic self-attachment.
 *
 * <p>{@code JvmAgentTest} (mid-engineer, Surefire) already proves this for {@code *Test} forks.
 * AC11 explicitly names both {@code *Test} and {@code *IT}/Cucumber forks, and Surefire and
 * Failsafe are separate forked JVMs, each configured with its own {@code argLine} (D43), so
 * a passing Surefire assertion does not prove anything about the Failsafe fork that actually runs
 * {@code *IT} and Cucumber. This class is the Failsafe-side half of that proof.
 *
 * <p>Deliberately does not extend {@link IntegrationTestBase}: it only inspects the current JVM's
 * process arguments and needs no Spring context or database, so it starts neither (no risk to
 * AC8's single-container rule).
 */
class JvmAgentIT {

    private static final Pattern MOCKITO_JAVAAGENT = Pattern.compile("-javaagent:.*mockito-core-.*\\.jar");

    @Test
    void shouldLoadMockitoAsExplicitJavaAgentInTheFailsafeFork() {
        var inputArguments = ManagementFactory.getRuntimeMXBean().getInputArguments();

        assertThat(inputArguments).anyMatch(argument -> MOCKITO_JAVAAGENT.matcher(argument).matches());
    }
}
