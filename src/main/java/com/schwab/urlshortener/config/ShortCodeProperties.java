package com.schwab.urlshortener.config;

import com.schwab.urlshortener.util.shortcode.SecureRandomShortCodeGenerator;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Short-code generation settings. The length bounds come from the generator's shared constants
 * and match the code-format check {@code ck_short_url_code_format} on the short_url table (D6).
 * maxAttempts is only validated here; the retry loop that uses it lives in the create-URL
 * service.
 */
@Validated
@ConfigurationProperties(prefix = "shortener.code")
public record ShortCodeProperties(
        @DefaultValue("7") @Min(SecureRandomShortCodeGenerator.MIN_LENGTH) @Max(SecureRandomShortCodeGenerator.MAX_LENGTH)
                int length,
        @DefaultValue("5") @Min(1) int maxAttempts) {
}
