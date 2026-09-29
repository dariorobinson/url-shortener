package com.schwab.urlshortener.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.schwab.urlshortener.validation.AliasPolicy;
import com.schwab.urlshortener.validation.UrlValidator;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
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
            // R3: the base URL may not carry a query or fragment, an empty one included
            "https://short.example/?x=1", "https://short.example/#f", "https://short.example/?",
            "https://short.example/#", "https://short.example/base?x=1#f"})
    void shouldFailStartupWhenBaseUrlIsBlankOrNotAbsoluteHttpUrlWithHost(String value) {
        runner.withPropertyValues(PROP + value)
                .run(ctx -> assertBaseUrlViolation(ctx.getStartupFailure(), value.strip()));
    }

    // withPropertyValues("APP_BASE_URL=...") would never bind: environment-style name mapping only
    // applies to a SystemEnvironmentPropertySource, so that key is added through one here.
    private ApplicationContextRunner withEnvironmentVariable(String name, String value) {
        return runner.withInitializer(ctx -> ctx.getEnvironment().getPropertySources().addFirst(
                new SystemEnvironmentPropertySource("env", Map.of(name, value))));
    }

    @Test
    void shouldFailStartupWhenBaseUrlIsSetFromEnvironmentStyleKeyWithInvalidValue() {
        withEnvironmentVariable("APP_BASE_URL", "ftp://x.example")
                .run(ctx -> assertBaseUrlViolation(ctx.getStartupFailure(), "ftp://x.example"));
    }

    @Test
    void shouldBindBaseUrlFromEnvironmentStyleName() {
        withEnvironmentVariable("APP_BASE_URL", "https://env.example").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(AppProperties.class).baseUrl()).isEqualTo("https://env.example");
        });
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

    @Test
    void shouldBindAdditionalReservedWordsFromEnvironmentStyleName() {
        // Verified: each dash of shortener.alias.additional-reserved-words becomes an underscore
        // (SHORTENER_ALIAS_ADDITIONAL_RESERVED_WORDS); SHORTENER_ALIAS_ADDITIONALRESERVEDWORDS does not bind.
        // A comma-separated environment value is split into a list.
        withEnvironmentVariable("APP_BASE_URL", "http://localhost:8080")
                .withInitializer(ctx -> ctx.getEnvironment().getPropertySources().addFirst(
                        new SystemEnvironmentPropertySource("env2",
                                Map.of("SHORTENER_ALIAS_ADDITIONAL_RESERVED_WORDS", "promo,Sale"))))
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx.getBean(AliasProperties.class).additionalReservedWords())
                            .containsExactly("promo", "Sale");
                    assertThat(ctx.getBean(AliasPolicy.class).isValid("sale")).isFalse();
                    assertThat(ctx.getBean(AliasPolicy.class).isValid("api")).isFalse();
                });
    }

    @Test
    void shouldNotBindAdditionalReservedWordsFromWrongEnvironmentStyleName() {
        // Negative counterpart: a key that maps to no property (here the removed name) binds nothing.
        withEnvironmentVariable("APP_BASE_URL", "http://localhost:8080")
                .withInitializer(ctx -> ctx.getEnvironment().getPropertySources().addFirst(
                        new SystemEnvironmentPropertySource("env2",
                                Map.of("SHORTENER_ALIAS_RESERVEDWORDS", "promo"))))
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
