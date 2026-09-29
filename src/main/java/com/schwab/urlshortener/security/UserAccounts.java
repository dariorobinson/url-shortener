package com.schwab.urlshortener.security;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;

/** Turns validated user configuration into {@link UserDetails}. Pure: no Spring context needed. */
final class UserAccounts {

    /** Every configured hash carries this BCrypt cost, so known and unknown users cost the same to check (D53). */
    static final int BCRYPT_STRENGTH = 10;

    /** $2a$, $2b$ or $2y$, cost exactly {@link #BCRYPT_STRENGTH}, then 53 salt and hash characters. No {bcrypt} prefix. */
    static final Pattern BCRYPT_HASH =
            Pattern.compile("\\A\\$2[aby]\\$" + BCRYPT_STRENGTH + "\\$[./0-9A-Za-z]{53}\\z");

    private static final String USERS = "app.security.users[";

    private UserAccounts() {
    }

    /**
     * @throws IllegalArgumentException if a hash is not a cost-10 BCrypt hash, or two usernames are equal;
     *         the message names the property path (for example {@code app.security.users[1].password-hash})
     *         and never the value (D50, D53)
     */
    static List<UserDetails> toUserDetails(List<UserAccountsProperties.UserAccount> accounts) {
        List<UserDetails> result = new ArrayList<>(accounts.size());
        Map<String, Integer> firstIndex = new HashMap<>();
        for (int i = 0; i < accounts.size(); i++) {
            UserAccountsProperties.UserAccount account = accounts.get(i);
            Integer duplicateOf = firstIndex.putIfAbsent(account.username(), i);
            if (duplicateOf != null) {
                throw new IllegalArgumentException(USERS + i + "].username duplicates " + USERS + duplicateOf
                        + "].username");
            }
            if (!BCRYPT_HASH.matcher(account.passwordHash()).matches()) {
                throw new IllegalArgumentException(USERS + i + "].password-hash must be a BCrypt hash with cost "
                        + BCRYPT_STRENGTH);
            }
            result.add(User.withUsername(account.username())
                    .password(account.passwordHash())
                    .roles(account.role().name())
                    .build());
        }
        return List.copyOf(result);
    }
}
