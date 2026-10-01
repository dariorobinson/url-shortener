package com.schwab.urlshortener.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import java.time.Period;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Expiration settings (D107). {@code maxHorizon} is how far ahead of "now" an {@code expiresAt} may be; the default
 * is ten years. Checked at startup: a zero or negative period would make every expiry invalid.
 */
@Validated
@ConfigurationProperties(prefix = "shortener.expiration")
public record ExpirationProperties(@DefaultValue("P10Y") @NotNull Period maxHorizon) {

    /** Bean Validation reports this as the {@code maxHorizonPositive} property. */
    @AssertTrue(message = "must be a positive period")
    public boolean isMaxHorizonPositive() {
        return maxHorizon == null || (!maxHorizon.isNegative() && !maxHorizon.isZero());
    }
}
