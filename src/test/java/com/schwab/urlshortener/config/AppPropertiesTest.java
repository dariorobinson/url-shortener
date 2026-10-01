package com.schwab.urlshortener.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.schwab.urlshortener.util.validation.AliasPolicy;
import com.schwab.urlshortener.util.validation.UrlValidator;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.validation.FieldError;

class AppPropertiesTest {

    private static final String PROP = "app.base-url=";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(ValidationConfig.class);

    @ParameterizedTest
    @ValueSource(strings = {"http://localhost:8080", "https://short.example", "HTTPS://Short.Example./", "https://short.example/base"})
    void shouldBindValidBaseUrlAndWireValidators(String baseUrl) {
        runner.withPropertyValues(PROP + baseUrl).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(AppProperties.class).baseUrl()).isEqualTo(baseUrl);
            assertThat(ctx).hasSingleBean(UrlValidator.class).hasSingleBean(AliasPolicy.class);
        });
    }

    @Test
    void shouldUseBaseUrlHostAsOwnHostInWiredValidator() {
        runner.withPropertyValues(PROP + "https://short.example").run(ctx -> {
            var validator = ctx.getBean(UrlValidator.class);
            assertThat(validator.isValid("https://short.example/x")).isFalse();
            assertThat(validator.isValid("https://other.example/x")).isTrue();
        });
    }

    @Test
    void shouldFailStartupWhenBaseUrlIsMissing() {
        runner.run(ctx -> assertBaseUrlViolation(ctx.getStartupFailure()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "localhost:8080", "ftp://short.example", "/relative", "//short.example",
            "http://", "https://user@short.example", "https://user:pw@short.example", "not a url",
            "javascript:alert(1)", "short.example",
            // The base URL may not carry a query or fragment, an empty one included (D28, D33)
            "https://short.example/?x=1", "https://short.example/#f", "https://short.example/?",
            "https://short.example/#", "https://short.example/base?x=1#f"})
    void shouldFailStartupWhenBaseUrlIsBlankOrNotAbsoluteHttpUrlWithHost(String value) {
        runner.withPropertyValues(PROP + value)
                .run(ctx -> assertBaseUrlViolation(ctx.getStartupFailure(), value.strip()));
    }

    /**
     * Adds variables the way the OS environment does. The source is named "systemEnvironment", so Spring Boot
     * applies SystemEnvironmentPropertyMapper and both the canonical (APP_BASEURL) and the legacy (APP_BASE_URL)
     * forms bind, as in production. addFirst replaces the real OS source of this context, so the developer's own
     * variables cannot leak in. Pass ALL variables in one call: a second source with the same name replaces the first.
     */
    private ApplicationContextRunner withEnvironment(Map<String, Object> variables) {
        return runner.withInitializer(ctx -> ctx.getEnvironment().getPropertySources().addFirst(
                new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                        Map.copyOf(variables))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"APP_BASE_URL", "APP_BASEURL"})
    void shouldBindBaseUrlFromBothEnvironmentForms(String variable) {
        withEnvironment(Map.of(variable, "https://env.example")).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(AppProperties.class).baseUrl()).isEqualTo("https://env.example");
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"APP_BASE_URL", "APP_BASEURL"})
    void shouldFailStartupWhenBaseUrlFromEitherEnvironmentFormIsInvalid(String variable) {
        withEnvironment(Map.of(variable, "ftp://x.example"))
                .run(ctx -> assertBaseUrlViolation(ctx.getStartupFailure(), "ftp://x.example"));
    }

    @Test
    void shouldPreferCanonicalBaseUrlEnvironmentFormWhenBothAreSet() {
        withEnvironment(Map.of("APP_BASEURL", "https://canonical.example", "APP_BASE_URL", "https://legacy.example"))
                .run(ctx -> assertThat(ctx.getBean(AppProperties.class).baseUrl())
                        .isEqualTo("https://canonical.example"));
    }

    // D29, D48: the built-in words are always reserved; the property only adds.
    @Test
    void shouldDefaultAdditionalReservedWordsToEmptyAndKeepBuiltInsReserved() {
        runner.withPropertyValues(PROP + "http://localhost:8080").run(ctx -> {
            assertThat(ctx.getBean(AliasProperties.class).additionalReservedWords()).isEmpty();
            assertThat(ctx.getBean(AliasPolicy.class).isValid("api")).isFalse();
            assertThat(ctx.getBean(AliasPolicy.class).isValid("promo")).isTrue();
        });
    }

    @Test
    void shouldBindAdditionalReservedWordsAndKeepBuiltIns() {
        runner.withPropertyValues(PROP + "http://localhost:8080", "shortener.alias.additional-reserved-words=promo,Sale")
                .run(ctx -> {
                    assertThat(ctx.getBean(AliasProperties.class).additionalReservedWords())
                            .containsExactly("promo", "Sale");
                    var policy = ctx.getBean(AliasPolicy.class);
                    assertThat(policy.isValid("SALE")).isFalse();
                    assertThat(policy.isValid("promo")).isFalse();
                    assertThat(policy.isValid("api")).isFalse();
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"SHORTENER_ALIAS_ADDITIONAL_RESERVED_WORDS", "SHORTENER_ALIAS_ADDITIONALRESERVEDWORDS"})
    void shouldBindAdditionalReservedWordsFromBothEnvironmentForms(String variable) {
        // Both the dash-to-underscore and the dash-removed form bind in production. A comma-separated
        // environment value is split into a list.
        withEnvironment(Map.of("APP_BASE_URL", "http://localhost:8080", variable, "promo,Sale")).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(AliasProperties.class).additionalReservedWords()).containsExactly("promo", "Sale");
            assertThat(ctx.getBean(AliasPolicy.class).isValid("sale")).isFalse();
            assertThat(ctx.getBean(AliasPolicy.class).isValid("api")).isFalse();
        });
    }

    // D48: the property binds but can never remove a built-in word.
    @ParameterizedTest
    @ValueSource(strings = {"SHORTENER_ALIAS_ADDITIONAL_RESERVED_WORDS", "SHORTENER_ALIAS_ADDITIONALRESERVEDWORDS"})
    void shouldBindButNotRemoveBuiltInWordWhenEnvironmentValueNamesOne(String variable) {
        withEnvironment(Map.of("APP_BASE_URL", "http://localhost:8080", variable, "api,promo")).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(AliasProperties.class).additionalReservedWords()).containsExactly("api", "promo");
            assertThat(ctx.getBean(AliasPolicy.class).isValid("api")).isFalse();
            assertThat(ctx.getBean(AliasPolicy.class).isValid("promo")).isFalse();
            assertThat(ctx.getBean(AliasPolicy.class).isValid("other")).isTrue();
        });
    }

    @Test
    void shouldNotBindAdditionalReservedWordsFromWrongEnvironmentStyleName() {
        // Negative counterpart: a key that maps to no property (here the removed name) binds nothing.
        withEnvironment(Map.of("APP_BASE_URL", "http://localhost:8080", "SHORTENER_ALIAS_RESERVEDWORDS", "promo"))
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx.getBean(AliasProperties.class).additionalReservedWords()).isEmpty();
                    assertThat(ctx.getBean(AliasPolicy.class).isValid("promo")).isTrue();
                });
    }

    @Test
    void shouldIgnoreRemovedReservedWordsPropertyWithNoFallback() {
        runner.withPropertyValues(PROP + "http://localhost:8080", "shortener.alias.reserved-words=promo")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx.getBean(AliasProperties.class).additionalReservedWords()).isEmpty();
                    assertThat(ctx.getBean(AliasPolicy.class).isValid("promo")).isTrue();
                    assertThat(ctx.getBean(AliasPolicy.class).isValid("api")).isFalse();
                });
    }

    @Test
    void shouldIgnoreBlankEntriesInBoundAdditionalReservedWordList() {
        runner.withPropertyValues(PROP + "http://localhost:8080", "shortener.alias.additional-reserved-words[0]=",
                "shortener.alias.additional-reserved-words[1]=promo").run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx.getBean(AliasPolicy.class).isValid("promo")).isFalse();
                    assertThat(ctx.getBean(AliasPolicy.class).isValid("abc")).isTrue();
                });
    }

    private static void assertBaseUrlViolation(Throwable failure) {
        assertBaseUrlViolation(failure, null);
    }

    /** With a non-null rejectedValue, also proves the supplied value (not a missing one) was rejected. */
    private static void assertBaseUrlViolation(Throwable failure, String rejectedValue) {
        assertThat(failure).isNotNull();
        BindException bind = null;
        BindValidationException validation = null;
        for (Throwable c = failure; c != null; c = c.getCause()) {
            if (bind == null && c instanceof BindException b) {
                bind = b;
            }
            if (validation == null && c instanceof BindValidationException v) {
                validation = v;
            }
        }
        assertThat(bind).as("BindException in cause chain of %s", failure).isNotNull();
        assertThat(bind.getName().toString()).isEqualTo("app");
        assertThat(validation).as("BindValidationException in cause chain").isNotNull();
        assertThat(validation.getValidationErrors().getAllErrors())
                .filteredOn(e -> e instanceof FieldError)
                .extracting(e -> ((FieldError) e).getField())
                .contains("baseUrl");
        if (rejectedValue != null) {
            assertThat(validation.getValidationErrors().getAllErrors())
                    .filteredOn(e -> e instanceof FieldError fe && fe.getField().equals("baseUrl"))
                    .extracting(e -> ((FieldError) e).getRejectedValue())
                    .contains(rejectedValue);
        }
    }
}
