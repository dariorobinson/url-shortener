package com.schwab.urlshortener.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Period;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.FieldError;

/** US-016: {@code shortener.expiration.max-horizon} (D107), bound and validated at startup. */
class ExpirationPropertiesTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ExpirationProperties.class)
    static class Enabled {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(Enabled.class);

    @Test
    void shouldDefaultToTenYears() {
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(ExpirationProperties.class).maxHorizon()).isEqualTo(Period.ofYears(10));
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"P2Y", "P6M", "P30D", "2y"})
    void shouldBindAPositivePeriod(String value) {
        runner.withPropertyValues("shortener.expiration.max-horizon=" + value).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            Period bound = ctx.getBean(ExpirationProperties.class).maxHorizon();
            assertThat(bound.isNegative() || bound.isZero()).isFalse();
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"P0D", "P-1Y", "-1d"})
    void shouldFailStartupForAZeroOrNegativePeriodNamingTheProperty(String value) {
        runner.withPropertyValues("shortener.expiration.max-horizon=" + value).run(ctx -> {
            assertThat(ctx).hasFailed();
            BindValidationException validation = findInChain(ctx.getStartupFailure(), BindValidationException.class);
            assertThat(validation).isNotNull();
            assertThat(validation.getValidationErrors().getAllErrors()).singleElement()
                    .isInstanceOfSatisfying(FieldError.class, e -> assertThat(e.getField())
                            .isEqualTo("maxHorizonPositive"));
        });
    }

    @Test
    void shouldFailStartupForAValueThatIsNotAPeriodNamingTheProperty() {
        runner.withPropertyValues("shortener.expiration.max-horizon=ten years").run(ctx -> {
            assertThat(ctx).hasFailed();
            BindException bind = findInChain(ctx.getStartupFailure(), BindException.class);
            assertThat(bind).isNotNull();
            assertThat(bind.getName().toString()).isEqualTo("shortener.expiration.max-horizon");
        });
    }

    private static <T extends Throwable> T findInChain(Throwable failure, Class<T> type) {
        for (Throwable c = failure; c != null; c = c.getCause()) {
            if (type.isInstance(c)) {
                return type.cast(c);
            }
        }
        return null;
    }
}
