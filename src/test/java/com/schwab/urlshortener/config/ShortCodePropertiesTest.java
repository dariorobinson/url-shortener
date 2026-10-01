package com.schwab.urlshortener.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.schwab.urlshortener.util.shortcode.ShortCodeGenerator;
import java.security.SecureRandom;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.validation.FieldError;

class ShortCodePropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(ShortCodeConfig.class);

    @Test
    void shouldApplyDefaultsWhenPropertiesAreMissing() {
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            var props = ctx.getBean(ShortCodeProperties.class);
            assertThat(props.length()).isEqualTo(7);
            assertThat(props.maxAttempts()).isEqualTo(5);
            assertThat(ctx.getBean(ShortCodeGenerator.class).generate()).matches("^[A-Za-z0-9]{7}$");
            assertThat(ctx).hasSingleBean(ShortCodeGenerator.class);
            assertThat(ctx).doesNotHaveBean(SecureRandom.class);
            assertThat(ctx).doesNotHaveBean(RandomGenerator.class);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"3", "32"})
    void shouldBindBoundaryLengthsAndGenerateMatchingCodes(String length) {
        runner.withPropertyValues("shortener.code.length=" + length).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(ShortCodeGenerator.class).generate())
                    .hasSize(Integer.parseInt(length));
        });
    }

    @Test
    void shouldBindMaxAttemptsOfOne() {
        runner.withPropertyValues("shortener.code.max-attempts=1").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(ShortCodeProperties.class).maxAttempts()).isEqualTo(1);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"2", "33", "0", "-1"})
    void shouldFailStartupWhenLengthIsOutOfBounds(String length) {
        runner.withPropertyValues("shortener.code.length=" + length).run(ctx -> {
            assertThat(ctx).hasFailed();
            assertRangeViolation(ctx.getStartupFailure(), "length");
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1"})
    void shouldFailStartupWhenMaxAttemptsIsBelowOne(String attempts) {
        runner.withPropertyValues("shortener.code.max-attempts=" + attempts).run(ctx -> {
            assertThat(ctx).hasFailed();
            assertRangeViolation(ctx.getStartupFailure(), "maxAttempts");
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "7.5"})
    void shouldFailStartupWhenLengthIsNotNumeric(String value) {
        runner.withPropertyValues("shortener.code.length=" + value)
                .run(ctx -> assertBindFailureFor(ctx.getStartupFailure(), "shortener.code.length"));
    }

    @Test
    void shouldFailStartupWhenMaxAttemptsIsNotNumeric() {
        runner.withPropertyValues("shortener.code.max-attempts=many")
                .run(ctx -> assertBindFailureFor(ctx.getStartupFailure(), "shortener.code.max-attempts"));
    }

    @Test
    void shouldFailStartupWhenLengthIsBlank() {
        // A blank value is an explicit misconfiguration, not "missing": it must not silently
        // fall back to the default.
        runner.withPropertyValues("shortener.code.length=")
                .run(ctx -> assertBindFailureFor(ctx.getStartupFailure(), "shortener.code.length"));
    }

    @Test
    void shouldFailStartupWhenMaxAttemptsIsBlank() {
        runner.withPropertyValues("shortener.code.max-attempts=")
                .run(ctx -> assertBindFailureFor(ctx.getStartupFailure(), "shortener.code.max-attempts"));
    }

    private static <T extends Throwable> T findInChain(Throwable failure, Class<T> type) {
        for (Throwable c = failure; c != null; c = c.getCause()) {
            if (type.isInstance(c)) {
                return type.cast(c);
            }
        }
        return null;
    }

    /** Startup failed because binding this exact property failed (not for an unrelated reason). */
    private static BindException assertBindFailureFor(Throwable failure, String propertyName) {
        assertThat(failure).isNotNull();
        var bindException = findInChain(failure, BindException.class);
        assertThat(bindException).as("Boot BindException in cause chain of %s", failure).isNotNull();
        assertThat(bindException.getName().toString()).isEqualTo(propertyName);
        return bindException;
    }

    /** Startup failed with a bean-validation range violation on the given record component. */
    private static void assertRangeViolation(Throwable failure, String field) {
        var bindException = assertBindFailureFor(failure, "shortener.code");
        var validation = findInChain(bindException, BindValidationException.class);
        assertThat(validation).as("BindValidationException in cause chain").isNotNull();
        assertThat(validation.getValidationErrors().getAllErrors())
                .filteredOn(e -> e instanceof FieldError)
                .extracting(e -> ((FieldError) e).getField())
                .contains(field);
    }
}
