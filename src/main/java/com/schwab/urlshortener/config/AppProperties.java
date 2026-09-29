package com.schwab.urlshortener.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Application-level settings. {@code baseUrl} is filled from the APP_BASE_URL environment
 * variable by relaxed binding (D28, D33). It has no default: production config comes only from
 * the environment (D24), so a missing or invalid value fails startup.
 */
@Validated
@ConfigurationProperties(prefix = "app")
public record AppProperties(@NotBlank @HttpBaseUrl String baseUrl) {
}
