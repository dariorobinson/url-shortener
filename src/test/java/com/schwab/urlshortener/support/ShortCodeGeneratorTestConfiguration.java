package com.schwab.urlshortener.support;

import com.schwab.urlshortener.util.shortcode.ShortCodeGenerator;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Registers the scripted generator once, as {@code @Primary}, for every integration test and
 * Cucumber scenario. Imported by {@link IntegrationTestBase} only, so all of them share one
 * Spring context and one PostgreSQL container.
 */
@TestConfiguration(proxyBeanMethods = false)
public class ShortCodeGeneratorTestConfiguration {

    @Bean
    @Primary
    ScriptedShortCodeGenerator scriptedShortCodeGenerator(
            @Qualifier("shortCodeGenerator") ShortCodeGenerator production) {
        return new ScriptedShortCodeGenerator(production);
    }
}
