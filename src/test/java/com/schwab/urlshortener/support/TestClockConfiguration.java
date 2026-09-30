package com.schwab.urlshortener.support;

import java.time.Clock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Registers the controllable {@link TestClock} once, as {@code @Primary}, for every integration test and
 * Cucumber scenario. Imported by {@link IntegrationTestBase} only, so all of them share one Spring context and
 * one PostgreSQL container. Real time until a test fixes the instant.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestClockConfiguration {

    @Bean
    @Primary
    TestClock testClock(@Qualifier("clock") Clock production) {
        return new TestClock(production);
    }
}
