package com.schwab.urlshortener.cucumber;

import com.schwab.urlshortener.support.IntegrationTestBase;
import io.cucumber.spring.CucumberContextConfiguration;

/**
 * Exactly one class in the glue package must carry {@code @CucumberContextConfiguration}.
 * Extending {@link IntegrationTestBase} means {@code @SpringBootTest} (which is
 * {@code @Inherited}) and the {@code @Import}/{@code @ActiveProfiles} on the base class produce
 * the same {@code MergedContextConfiguration} as every {@code *IT} test. Spring's JVM-wide
 * context cache therefore hands both the same context and the same Testcontainers PostgreSQL
 * container bean (AC8).
 */
@CucumberContextConfiguration
public class CucumberSpringConfiguration extends IntegrationTestBase {
}
