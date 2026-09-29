package com.schwab.urlshortener.security;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(UserAccountsProperties.class)
class UserAccountsConfig {

    /**
     * The encoder and the user store are built here and never published as beans, so a later
     * {@code @Primary} bean of a broad type cannot replace them (CLAUDE.md security-sensitive-bean rule).
     * Login matching is case-insensitive (D54): the store looks users up by lower-cased name.
     *
     * @throws IllegalArgumentException if a configured hash is not a cost-10 BCrypt hash or a username is
     *         duplicated (D50, D53)
     */
    @Bean
    DaoAuthenticationProvider authenticationProvider(UserAccountsProperties properties) {
        var users = new InMemoryUserDetailsManager(UserAccounts.toUserDetails(properties.users()));
        var provider = new DaoAuthenticationProvider(users);
        provider.setPasswordEncoder(new BCryptPasswordEncoder(UserAccounts.BCRYPT_STRENGTH));
        return provider;
    }
}
