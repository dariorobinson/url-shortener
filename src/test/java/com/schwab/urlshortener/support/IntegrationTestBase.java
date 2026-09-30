package com.schwab.urlshortener.support;

import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Shared base for every full-stack integration test ({@code *IT}) and for the Cucumber Spring
 * configuration. Carries annotations only: no fields, no {@code @Bean} methods.
 *
 * <p>Rule for every subclass: add no context-affecting annotations or fields
 * ({@code @MockitoBean}/{@code @MockBean}/{@code @TestBean}, {@code @TestPropertySource}, extra
 * {@code @Import}/{@code @ActiveProfiles}, {@code @DirtiesContext}, or
 * {@code @SpringBootTest(properties=...)}). Any of these changes Spring's context cache key and
 * starts a second context and a second Testcontainers PostgreSQL container, breaking AC8.
 *
 * <p>The base itself imports {@link ShortCodeGeneratorTestConfiguration} (the scripted, {@code @Primary}
 * short-code generator seam) and {@link TestClockConfiguration} (the controllable, {@code @Primary}
 * {@link TestClock}, real time unless a test fixes it), so every {@code *IT} and Cucumber shares one context.
 * {@link TestClockResetExtension} resets the clock before and after every test; a JUnit extension is not part of
 * Spring's context cache key.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, ShortCodeGeneratorTestConfiguration.class, TestClockConfiguration.class})
@ExtendWith(TestClockResetExtension.class)
public abstract class IntegrationTestBase {
}
