package com.schwab.urlshortener.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.schwab.urlshortener.security.UserAccountsProperties.UserAccount;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class UserAccountsTest {

    private static final String HASH = new BCryptPasswordEncoder(10).encode("pw");

    @Test
    void shouldBuildUsersWithRoleAuthorityAndKeepTheHash() {
        var users = UserAccounts.toUserDetails(List.of(
                new UserAccount("alice", HASH, Role.USER), new UserAccount("admin", HASH, Role.ADMIN)));

        assertThat(users).hasSize(2);
        assertThat(users.get(0).getUsername()).isEqualTo("alice");
        assertThat(users.get(0).getPassword()).isEqualTo(HASH);
        assertThat(users.get(0).getAuthorities()).extracting(Object::toString).containsExactly("ROLE_USER");
        assertThat(users.get(1).getAuthorities()).extracting(Object::toString).containsExactly("ROLE_ADMIN");
    }

    @Test
    void shouldAcceptEachBcryptMinorVersion() {
        for (String minor : new String[] {"2a", "2b", "2y"}) {
            String hash = "$" + minor + HASH.substring(3);
            assertThat(UserAccounts.toUserDetails(List.of(new UserAccount("alice", hash, Role.USER)))).hasSize(1);
        }
    }

    /** Each value differs from a valid cost-10 hash in exactly one respect; the flag says whether the length is kept. */
    static Stream<Arguments> badHashes() {
        return Stream.of(
                Arguments.of("plaintext-secret-marker", false),
                Arguments.of("$2x" + HASH.substring(3), true),
                Arguments.of("$2" + HASH.substring(3), false),
                Arguments.of("{bcrypt}" + HASH, false),
                Arguments.of(HASH.replace("$10$", "$04$"), true),
                Arguments.of(HASH.replace("$10$", "$12$"), true),
                Arguments.of(HASH.substring(0, 59), false),
                Arguments.of(HASH + "a", false));
    }

    @ParameterizedTest
    @MethodSource("badHashes")
    void shouldRejectNonCostTenBcryptHashNamingPropertyButNeverTheValue(String bad, boolean sameLengthAsValid) {
        if (sameLengthAsValid) {
            assertThat(bad).hasSameSizeAs(HASH).isNotEqualTo(HASH);
        }
        var accounts = List.of(new UserAccount("alice", HASH, Role.USER), new UserAccount("bob", bad, Role.USER));

        assertThatThrownBy(() -> UserAccounts.toUserDetails(accounts))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("app.security.users[1].password-hash")
                .message().doesNotContain(bad).doesNotContain("secret-marker");
    }

    @Test
    void shouldAcceptTheValidHashTheBadValuesAreDerivedFrom() {
        assertThat(HASH).hasSize(60);
        assertThat(UserAccounts.toUserDetails(List.of(new UserAccount("alice", HASH, Role.USER)))).hasSize(1);
    }

    @Test
    void shouldRejectCostTwelveHashFromARealEncoder() {
        String cost12 = new BCryptPasswordEncoder(12).encode("pw");

        assertThatThrownBy(() -> UserAccounts.toUserDetails(List.of(new UserAccount("alice", cost12, Role.USER))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("app.security.users[0].password-hash")
                .message().doesNotContain(cost12);
    }

    @Test
    void shouldRejectTrailingNewlineOnHash() {
        assertThatThrownBy(() -> UserAccounts.toUserDetails(List.of(new UserAccount("alice", HASH + "\n", Role.USER))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectDuplicateUsernameNamingBothIndexes() {
        var accounts = List.of(new UserAccount("alice", HASH, Role.USER), new UserAccount("bob", HASH, Role.USER),
                new UserAccount("alice", HASH, Role.ADMIN));

        assertThatThrownBy(() -> UserAccounts.toUserDetails(accounts))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("app.security.users[2].username")
                .hasMessageContaining("app.security.users[0].username");
    }

    @Test
    void shouldNeverRenderTheHashInToString() {
        String text = new UserAccount("alice", HASH, Role.USER).toString();

        assertThat(text).contains("alice", "USER").doesNotContain(HASH).doesNotContain("$2");
    }
}
