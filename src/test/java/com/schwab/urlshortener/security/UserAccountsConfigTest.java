package com.schwab.urlshortener.security;

import static com.schwab.urlshortener.support.TestUsers.ALICE;
import static com.schwab.urlshortener.support.TestUsers.ALICE_PASSWORD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.schwab.urlshortener.domain.ShortUrl;
import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.validation.FieldError;

/** D50 to D54: configured users, their startup validation, and BCrypt authentication (AC2, AC6). */
@ExtendWith(OutputCaptureExtension.class)
class UserAccountsConfigTest {

    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder(10);
    private static final String ALICE_HASH = ENCODER.encode(ALICE_PASSWORD);
    private static final String BOB_HASH = ENCODER.encode("bob-test-password");

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(UserAccountsConfig.class);

    private static String[] user(int index, String username, String hash, String role) {
        String prefix = "app.security.users[" + index + "].";
        return new String[] {prefix + "username=" + username, prefix + "password-hash=" + hash,
                prefix + "role=" + role};
    }

    private static String[] concat(String[]... parts) {
        return Arrays.stream(parts).flatMap(Arrays::stream).toArray(String[]::new);
    }

    private ApplicationContextRunner withAlice() {
        return runner.withPropertyValues(user(0, ALICE, ALICE_HASH, "USER"));
    }

    private static <T extends Throwable> T cause(Throwable failure, Class<T> type) {
        assertThat(failure).isNotNull();
        for (Throwable c = failure; c != null; c = c.getCause()) {
            if (type.isInstance(c)) {
                return type.cast(c);
            }
        }
        throw new AssertionError("No " + type.getSimpleName() + " in cause chain of " + failure);
    }

    private static void assertFieldViolation(Throwable failure, String field) {
        assertThat(cause(failure, BindException.class).getName().toString()).isEqualTo("app.security");
        assertThat(cause(failure, BindValidationException.class).getValidationErrors().getAllErrors())
                .filteredOn(e -> e instanceof FieldError)
                .extracting(e -> ((FieldError) e).getField())
                .contains(field);
    }

    private static Authentication login(AssertableApplicationContext ctx, String user, String password) {
        return ctx.getBean(DaoAuthenticationProvider.class)
                .authenticate(UsernamePasswordAuthenticationToken.unauthenticated(user, password));
    }

    // ---- positive and AC6

    @Test
    void shouldAuthenticateConfiguredUserWithPlaintextPasswordAgainstBcryptHash() {
        runner.withPropertyValues(concat(user(0, ALICE, ALICE_HASH, "USER"), user(1, "admin", BOB_HASH, "ADMIN")))
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed().hasSingleBean(DaoAuthenticationProvider.class);
                    Authentication auth = login(ctx, ALICE, ALICE_PASSWORD);
                    assertThat(auth.isAuthenticated()).isTrue();
                    assertThat(auth.getName()).isEqualTo(ALICE);
                    assertThat(auth.getAuthorities()).extracting(Object::toString).containsExactly("ROLE_USER");
                    assertThat(login(ctx, "admin", "bob-test-password").getAuthorities())
                            .extracting(Object::toString).containsExactly("ROLE_ADMIN");
                });
    }

    @Test
    void shouldRejectTheStoredHashStringUsedAsThePassword() {
        withAlice().run(ctx -> assertThatThrownBy(() -> login(ctx, ALICE, ALICE_HASH))
                .isInstanceOf(BadCredentialsException.class));
    }

    @Test
    void shouldRejectWrongPasswordAndReportUnknownUserAsBadCredentials() {
        withAlice().run(ctx -> {
            assertThatThrownBy(() -> login(ctx, ALICE, "wrong")).isInstanceOf(BadCredentialsException.class);
            assertThatThrownBy(() -> login(ctx, "nobody", ALICE_PASSWORD)).isInstanceOf(BadCredentialsException.class);
        });
    }

    @Test
    void shouldMatchLoginCaseInsensitivelyButReturnConfiguredUsername() {
        withAlice().run(ctx -> assertThat(login(ctx, "ALICE", ALICE_PASSWORD).getName()).isEqualTo(ALICE));
    }

    @Test
    void shouldNotEraseTheStoredHashAfterALogin() {
        withAlice().run(ctx -> {
            // Credential erasure happens in ProviderManager, so log in through one, as SecurityConfig does.
            var manager = new ProviderManager(ctx.getBean(DaoAuthenticationProvider.class));
            var token = UsernamePasswordAuthenticationToken.unauthenticated(ALICE, ALICE_PASSWORD);

            assertThat(manager.authenticate(token).isAuthenticated()).isTrue();
            assertThat(manager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(ALICE, ALICE_PASSWORD)).isAuthenticated())
                    .isTrue();
        });
    }

    @Test
    void shouldPublishNoPasswordEncoderOrUserDetailsServiceBean() {
        withAlice().run(ctx -> {
            assertThat(ctx.getBeansOfType(PasswordEncoder.class)).isEmpty();
            assertThat(ctx.getBeansOfType(UserDetailsService.class))
                    .isEmpty();
        });
    }

    // ---- startup failures, each with the positive counterpart above

    @Test
    void shouldFailStartupWhenUsersAreMissing() {
        runner.run(ctx -> assertFieldViolation(ctx.getStartupFailure(), "users"));
    }

    @Test
    void shouldFailStartupWhenUsersAreEmpty() {
        runner.withPropertyValues("app.security.users=").run(ctx ->
                assertFieldViolation(ctx.getStartupFailure(), "users"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "Alice", "ALICE", "al ice", "al:ice", "al@ice", "élise",
            "-alice", ".alice", "aliceé"})
    void shouldFailStartupWhenUsernameBreaksTheD51Rules(String username) {
        runner.withPropertyValues(user(0, username, ALICE_HASH, "USER"))
                .run(ctx -> assertFieldViolation(ctx.getStartupFailure(), "users[0].username"));
    }

    @Test
    void shouldFailStartupWhenUsernameExceedsMaxLengthButAcceptExactlyMaxLength() {
        String max = "a".repeat(ShortUrl.MAX_ACTOR_LENGTH);
        runner.withPropertyValues(user(0, max, ALICE_HASH, "USER")).run(ctx -> assertThat(ctx).hasNotFailed());
        runner.withPropertyValues(user(0, max + "a", ALICE_HASH, "USER"))
                .run(ctx -> assertFieldViolation(ctx.getStartupFailure(), "users[0].username"));
    }

    /**
     * withPropertyValues trims the value, so padded usernames are supplied through a map source. The
     * positive counterpart uses the same mechanism with an unpadded name.
     */
    private ApplicationContextRunner withUsername(String username) {
        return runner.withInitializer(ctx -> ctx.getEnvironment().getPropertySources().addFirst(
                new MapPropertySource("padded-username", Map.of(
                        "app.security.users[0].username", username,
                        "app.security.users[0].password-hash", ALICE_HASH,
                        "app.security.users[0].role", "USER"))));
    }

    @Test
    void shouldAcceptMaxLengthUsernameFromAMapSource() {
        withUsername("a".repeat(ShortUrl.MAX_ACTOR_LENGTH)).run(ctx -> assertThat(ctx).hasNotFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"alice ", " alice", "alice\t", "alice\n"})
    void shouldFailStartupWhenUsernameHasSurroundingWhitespace(String username) {
        withUsername(username).run(ctx -> assertFieldViolation(ctx.getStartupFailure(), "users[0].username"));
    }

    @Test
    void shouldFailStartupWhenUsernameIsMaxLengthPlusTrailingSpace() {
        withUsername("a".repeat(ShortUrl.MAX_ACTOR_LENGTH) + " ")
                .run(ctx -> assertFieldViolation(ctx.getStartupFailure(), "users[0].username"));
    }

    @Test
    void shouldAcceptUsernameWithAllowedPunctuation() {
        runner.withPropertyValues(user(0, "a.b_c-d9", ALICE_HASH, "USER")).run(ctx -> assertThat(ctx).hasNotFailed());
    }

    @Test
    void shouldFailStartupWhenRoleIsMissing() {
        runner.withPropertyValues("app.security.users[0].username=alice",
                        "app.security.users[0].password-hash=" + ALICE_HASH)
                .run(ctx -> assertFieldViolation(ctx.getStartupFailure(), "users[0].role"));
    }

    @Test
    void shouldFailStartupWhenRoleIsUnknown() {
        runner.withPropertyValues(user(0, ALICE, ALICE_HASH, "ROOT")).run(ctx -> {
            BindException bind = cause(ctx.getStartupFailure(), BindException.class);
            assertThat(bind.getName().toString()).isEqualTo("app.security.users[0].role");
        });
    }

    @Test
    void shouldFailStartupWhenPasswordHashIsMissing() {
        runner.withPropertyValues("app.security.users[0].username=alice", "app.security.users[0].role=USER")
                .run(ctx -> assertFieldViolation(ctx.getStartupFailure(), "users[0].passwordHash"));
    }

    @Test
    void shouldFailStartupWithoutEchoingAPlaintextPasswordUsedAsTheHash(CapturedOutput output) {
        String plaintext = "hunter2-secret-marker";
        runner.withPropertyValues(user(0, ALICE, plaintext, "USER")).run(ctx -> {
            IllegalArgumentException failure = cause(ctx.getStartupFailure(), IllegalArgumentException.class);
            assertThat(failure).hasMessageContaining("app.security.users[0].password-hash");
            assertThat(stackText(ctx.getStartupFailure())).doesNotContain(plaintext);
        });
        assertThat(output.getAll()).doesNotContain(plaintext);
    }

    @Test
    void shouldFailStartupWhenHashHasCostTwelve() {
        String cost12 = new BCryptPasswordEncoder(12).encode("pw");
        runner.withPropertyValues(user(0, ALICE, cost12, "USER")).run(ctx -> {
            assertThat(cause(ctx.getStartupFailure(), IllegalArgumentException.class))
                    .hasMessageContaining("app.security.users[0].password-hash");
            assertThat(stackText(ctx.getStartupFailure())).doesNotContain(cost12);
        });
    }

    @Test
    void shouldFailStartupWhenUsernamesAreDuplicated() {
        runner.withPropertyValues(concat(user(0, ALICE, ALICE_HASH, "USER"), user(1, ALICE, BOB_HASH, "ADMIN")))
                .run(ctx -> assertThat(cause(ctx.getStartupFailure(), IllegalArgumentException.class))
                        .hasMessageContaining("app.security.users[1].username")
                        .hasMessageContaining("app.security.users[0].username"));
    }

    private static String stackText(Throwable failure) {
        StringBuilder text = new StringBuilder();
        for (Throwable c = failure; c != null; c = c.getCause()) {
            text.append(c).append('\n');
        }
        return text.toString();
    }

    // ---- environment binding: production maps names through a source called "systemEnvironment"

    /**
     * Adds variables the way the OS environment does. The source is named "systemEnvironment", so Spring
     * Boot applies SystemEnvironmentPropertyMapper and both the canonical (PASSWORDHASH) and the legacy
     * (PASSWORD_HASH) forms bind, as in production. Pass all variables in one call: a second source with
     * the same name replaces the first.
     */
    private ApplicationContextRunner withEnvironment(Map<String, Object> variables) {
        return runner.withInitializer(ctx -> ctx.getEnvironment().getPropertySources().addFirst(
                new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                        Map.copyOf(variables))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"APP_SECURITY_USERS_0_PASSWORD_HASH", "APP_SECURITY_USERS_0_PASSWORDHASH"})
    void shouldBindPasswordHashFromBothEnvironmentForms(String hashVariable) {
        withEnvironment(Map.of("APP_SECURITY_USERS_0_USERNAME", ALICE, hashVariable, ALICE_HASH,
                "APP_SECURITY_USERS_0_ROLE", "USER")).run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(login(ctx, ALICE, ALICE_PASSWORD).getAuthorities())
                            .extracting(Object::toString).containsExactly("ROLE_USER");
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"APP_SECURITY_USERS_0_PASSWORD_HASH", "APP_SECURITY_USERS_0_PASSWORDHASH"})
    void shouldRejectNonHashFromBothEnvironmentFormsWithoutEchoingIt(String hashVariable) {
        withEnvironment(Map.of("APP_SECURITY_USERS_0_USERNAME", ALICE, hashVariable, "not-a-hash",
                "APP_SECURITY_USERS_0_ROLE", "USER")).run(ctx -> {
                    assertThat(cause(ctx.getStartupFailure(), IllegalArgumentException.class))
                            .hasMessageContaining("app.security.users[0].password-hash");
                    assertThat(stackText(ctx.getStartupFailure())).doesNotContain("not-a-hash");
                });
    }

    @Test
    void shouldPreferTheCanonicalPasswordHashFormWhenBothAreSet() {
        withEnvironment(Map.of("APP_SECURITY_USERS_0_USERNAME", ALICE,
                "APP_SECURITY_USERS_0_PASSWORDHASH", ALICE_HASH,
                "APP_SECURITY_USERS_0_PASSWORD_HASH", BOB_HASH,
                "APP_SECURITY_USERS_0_ROLE", "USER")).run(ctx -> {
                    assertThat(login(ctx, ALICE, ALICE_PASSWORD).isAuthenticated()).isTrue();
                    assertThatThrownBy(() -> login(ctx, ALICE, "bob-test-password"))
                            .isInstanceOf(BadCredentialsException.class);
                });
    }

    @Test
    void shouldBindUsernameAndRoleFromEnvironment() {
        withEnvironment(Map.of("APP_SECURITY_USERS_0_USERNAME", "carol", "APP_SECURITY_USERS_0_PASSWORD_HASH", ALICE_HASH,
                "APP_SECURITY_USERS_0_ROLE", "ADMIN")).run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(login(ctx, "carol", ALICE_PASSWORD).getAuthorities())
                            .extracting(Object::toString).containsExactly("ROLE_ADMIN");
                });
    }

    @Test
    void shouldRejectInvalidUsernameAndRoleFromEnvironment() {
        withEnvironment(Map.of("APP_SECURITY_USERS_0_USERNAME", "Alice", "APP_SECURITY_USERS_0_PASSWORD_HASH", ALICE_HASH,
                "APP_SECURITY_USERS_0_ROLE", "USER"))
                .run(ctx -> assertFieldViolation(ctx.getStartupFailure(), "users[0].username"));
        withEnvironment(Map.of("APP_SECURITY_USERS_0_USERNAME", ALICE, "APP_SECURITY_USERS_0_PASSWORD_HASH", ALICE_HASH,
                "APP_SECURITY_USERS_0_ROLE", "ROOT")).run(ctx ->
                assertThat(cause(ctx.getStartupFailure(), BindException.class).getName().toString())
                        .isEqualTo("app.security.users[0].role"));
    }

    @Test
    void shouldBindTwoUsersFromIndexedEnvironmentVariables() {
        withEnvironment(Map.of(
                "APP_SECURITY_USERS_0_USERNAME", ALICE, "APP_SECURITY_USERS_0_PASSWORDHASH", ALICE_HASH,
                "APP_SECURITY_USERS_0_ROLE", "USER",
                "APP_SECURITY_USERS_1_USERNAME", "bob", "APP_SECURITY_USERS_1_PASSWORDHASH", BOB_HASH,
                "APP_SECURITY_USERS_1_ROLE", "USER")).run(ctx -> {
                    assertThat(ctx.getBean(UserAccountsProperties.class).users()).hasSize(2);
                    assertThat(login(ctx, "bob", "bob-test-password").isAuthenticated()).isTrue();
                });
    }
}
