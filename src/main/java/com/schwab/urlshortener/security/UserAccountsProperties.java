package com.schwab.urlshortener.security;

import com.schwab.urlshortener.util.domain.ShortUrl;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configured users (D3, D24, D50): {@code app.security.users[n].{username, password-hash, role}}. There
 * are no defaults, so a missing list fails startup.
 */
@Validated
@ConfigurationProperties(prefix = "app.security")
public record UserAccountsProperties(@NotEmpty List<@Valid UserAccount> users) {

    /**
     * One configured user. The username bound uses {@link ShortUrl#MAX_ACTOR_LENGTH} because the name
     * is stored as {@code created_by}/{@code deleted_by} (D51). The hash format is checked in
     * {@link UserAccounts}, not by Bean Validation, because Spring Boot's bind failure report prints
     * the rejected value and a plaintext password must never reach the log (D53).
     */
    public record UserAccount(
            @NotNull @Size(min = 1, max = ShortUrl.MAX_ACTOR_LENGTH) @Pattern(regexp = USERNAME_PATTERN) String username,
            @NotBlank String passwordHash,
            @NotNull Role role) {

        /** Lowercase ASCII letters, digits, '.', '_' and '-'; no whitespace, ':' or '@' (D51). */
        public static final String USERNAME_PATTERN = "^[a-z0-9][a-z0-9._-]*$";

        /** Never renders the hash. */
        @Override
        public String toString() {
            return "UserAccount[username=" + username + ", role=" + role + ", passwordHash=<redacted>]";
        }
    }
}
