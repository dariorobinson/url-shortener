package com.schwab.urlshortener.config;

import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import java.util.List;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * D89: strict booleans. Jackson's scalar coercion is disabled for {@link LogicalType#Boolean} only, so a
 * {@code Boolean} or {@code boolean} property accepts nothing but a real JSON {@code true} or {@code false}
 * ({@code "false"}, {@code 0} and {@code 1} are rejected and become 400 MALFORMED_REQUEST).
 *
 * <p>The scope is deliberate. {@code MapperFeature.ALLOW_COERCION_OF_SCALARS} would be global and would also
 * change create ({@code alias} or {@code originalUrl} given as JSON numbers), so it is not used. A per-type
 * {@code CoercionConfig} touches no other type, and serialization does not consult coercion at all.
 *
 * <p>Every input shape that the Boolean deserializers consult is set to {@code Fail}: a string (including
 * {@code "true"}/{@code "false"}) and an empty string, an integer, a float, and an array or empty array. A JSON
 * {@code null} is not a coercion, so it still reaches {@code @NotNull} as VALIDATION_FAILED.
 */
@Configuration(proxyBeanMethods = false)
public class JacksonConfig {

    private static final List<CoercionInputShape> FAILING_SHAPES = List.of(
            CoercionInputShape.String, CoercionInputShape.EmptyString, CoercionInputShape.Integer,
            CoercionInputShape.Float, CoercionInputShape.Array, CoercionInputShape.EmptyArray);

    @Bean
    Jackson2ObjectMapperBuilderCustomizer strictBooleanCoercion() {
        return builder -> builder.postConfigurer(mapper -> {
            var booleans = mapper.coercionConfigFor(LogicalType.Boolean);
            for (CoercionInputShape shape : FAILING_SHAPES) {
                booleans.setCoercion(shape, CoercionAction.Fail);
            }
        });
    }
}
