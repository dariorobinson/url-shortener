package com.schwab.urlshortener.security;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;

/**
 * Lets web-slice tests outside this package import the real filter chain and user store, whose
 * configuration classes are package-private. Import it with {@code @Import(SecuritySliceTestConfiguration.class)}.
 */
@TestConfiguration(proxyBeanMethods = false)
@Import({SecurityConfig.class, UserAccountsConfig.class})
public class SecuritySliceTestConfiguration {
}
