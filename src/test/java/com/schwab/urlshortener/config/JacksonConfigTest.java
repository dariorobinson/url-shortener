package com.schwab.urlshortener.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * D89: Jackson scalar coercion is disabled for Boolean only. Built through Boot's real
 * {@code JacksonAutoConfiguration}, with the same D59 properties as {@code application.yml}, so it proves that the
 * customizer is applied to the context {@code ObjectMapper} that Spring MVC uses.
 */
class JacksonConfigTest {

    private record Flag(Boolean active) {
    }

    private record PrimitiveFlag(boolean active) {
    }

    private record Text(String value) {
    }

    private record Numbers(int i, long l, BigDecimal d, Double f) {
    }

    private record Holder(Instant at, Flag flag) {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
            .withUserConfiguration(JacksonConfig.class)
            .withPropertyValues("spring.jackson.deserialization.fail-on-unknown-properties=true",
                    "spring.jackson.parser.strict-duplicate-detection=true");

    private void withMapper(Consumer<ObjectMapper> test) {
        runner.run(context -> test.accept(context.getBean(ObjectMapper.class)));
    }

    @Test
    void shouldAcceptRealJsonBooleansAndJsonNullForTheWrapper() {
        withMapper(mapper -> {
            assertThat(read(mapper, "{\"active\":true}", Flag.class).active()).isTrue();
            assertThat(read(mapper, "{\"active\":false}", Flag.class).active()).isFalse();
            assertThat(read(mapper, "{\"active\":null}", Flag.class).active()).isNull();
            assertThat(read(mapper, "{}", Flag.class).active()).isNull();
            assertThat(read(mapper, "{\"active\":true}", PrimitiveFlag.class).active()).isTrue();
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"false\"", "\"true\"", "\"TRUE\"", "\"\"", "\" \"", "0", "1", "2", "-1", "1.0", "0.5",
            "[]", "[true]", "{}"})
    void shouldRejectEveryNonBooleanShapeForTheWrapperAndThePrimitive(String value) {
        withMapper(mapper -> {
            assertThatThrownBy(() -> mapper.readValue("{\"active\":" + value + "}", Flag.class))
                    .isInstanceOf(MismatchedInputException.class);
            assertThatThrownBy(() -> mapper.readValue("{\"active\":" + value + "}", PrimitiveFlag.class))
                    .isInstanceOf(MismatchedInputException.class);
        });
    }

    @Test
    void shouldRejectABooleanRootValueGivenAsAStringOrNumber() {
        withMapper(mapper -> {
            assertThatThrownBy(() -> mapper.readValue("\"true\"", Boolean.class))
                    .isInstanceOf(JsonMappingException.class);
            assertThatThrownBy(() -> mapper.readValue("1", Boolean.class)).isInstanceOf(JsonMappingException.class);
            assertThatThrownBy(() -> mapper.readValue("[\"true\"]",
                    new TypeReference<List<Boolean>>() { }))
                    .isInstanceOf(JsonMappingException.class);
            assertThatThrownBy(() -> mapper.readValue("{\"a\":\"true\"}",
                    new TypeReference<Map<String, Boolean>>() { }))
                    .isInstanceOf(JsonMappingException.class);
        });
    }

    @Test
    void shouldLeaveEveryOtherScalarCoercionAsItWasSoCreateIsUnchanged() {
        withMapper(mapper -> {
            // Strings from numbers and booleans (create's originalUrl and alias).
            assertThat(read(mapper, "{\"value\":12345}", Text.class).value()).isEqualTo("12345");
            assertThat(read(mapper, "{\"value\":true}", Text.class).value()).isEqualTo("true");
            // Numbers from strings.
            Numbers numbers = read(mapper, "{\"i\":\"5\",\"l\":\"6\",\"d\":\"1.5\",\"f\":\"2.5\"}", Numbers.class);
            assertThat(numbers.i()).isEqualTo(5);
            assertThat(numbers.l()).isEqualTo(6L);
            assertThat(numbers.d()).isEqualByComparingTo("1.5");
            assertThat(numbers.f()).isEqualTo(2.5);
        });
    }

    @Test
    void shouldStillHonourTheD59StrictnessProperties() {
        withMapper(mapper -> {
            assertThat(mapper.isEnabled(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)).isTrue();
            assertThatThrownBy(() -> mapper.readValue("{\"active\":true,\"x\":1}", Flag.class))
                    .isInstanceOf(JsonMappingException.class);
            assertThatThrownBy(() -> mapper.readValue("{\"active\":true,\"active\":false}", Flag.class))
                    .isInstanceOf(JsonProcessingException.class);
        });
    }

    @Test
    void shouldSerializeBooleansAsJsonBooleansAndTimestampsAsBefore() {
        withMapper(mapper -> {
            assertThat(write(mapper, new Flag(true))).isEqualTo("{\"active\":true}");
            assertThat(write(mapper, new PrimitiveFlag(false))).isEqualTo("{\"active\":false}");
            assertThat(write(mapper, new Flag(null))).isEqualTo("{\"active\":null}");
            assertThat(write(mapper, new Holder(Instant.parse("2026-09-29T14:03:12.123456Z"), new Flag(true))))
                    .isEqualTo("{\"at\":\"2026-09-29T14:03:12.123456Z\",\"flag\":{\"active\":true}}");
        });
    }

    private static <T> T read(ObjectMapper mapper, String json, Class<T> type) {
        try {
            return mapper.readValue(json, type);
        } catch (Exception e) {
            throw new AssertionError("Expected to read " + json, e);
        }
    }

    private static String write(ObjectMapper mapper, Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
