package com.schwab.urlshortener.security;

import com.schwab.urlshortener.config.JacksonConfig;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;

/**
 * Lets web-slice tests outside this package import the real filter chain and user store, whose
 * configuration classes are package-private. Import it with {@code @Import(SecuritySliceTestConfiguration.class)}.
 *
 * <p>It also imports {@link JacksonConfig} (D89), so every web slice parses request bodies exactly as production
 * does. A slice that uses the real security chain therefore cannot forget the Jackson setting.
 */
@TestConfiguration(proxyBeanMethods = false)
@Import({SecurityConfig.class, UserAccountsConfig.class, JacksonConfig.class})
public class SecuritySliceTestConfiguration {
}
