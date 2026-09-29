package com.schwab.urlshortener.support;

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
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
public abstract class IntegrationTestBase {
}
