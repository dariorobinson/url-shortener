package com.schwab.urlshortener.model.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * US-016: strict {@code expiresAt} parsing (D123) and the PATCH body's absent / null / value distinction (D114,
 * D122), against a mapper with the JSR-310 module registered, so the strictness comes from our deserializer and not
 * from a missing module. Over HTTP the same shapes are pinned by the web slice and the IT.
 */
class ExpiresAtParsingTest {

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    @Test
    void shouldAcceptZAndExplicitOffsetsAsTheSameInstant() throws Exception {
        CreateShortUrlRequest z = mapper.readValue(
                "{\"originalUrl\":\"https://example.com\",\"expiresAt\":\"2027-01-31T23:59:59Z\"}",
                CreateShortUrlRequest.class);
        CreateShortUrlRequest offset = mapper.readValue(
                "{\"originalUrl\":\"https://example.com\",\"expiresAt\":\"2027-02-01T01:59:59+02:00\"}",
                CreateShortUrlRequest.class);

        assertThat(z.expiresAt().toInstant()).isEqualTo(Instant.parse("2027-01-31T23:59:59Z"));
        assertThat(offset.expiresAt().toInstant()).isEqualTo(z.expiresAt().toInstant());
    }

    @Test
    void shouldLeaveExpiresAtNullWhenAbsentOrNullOnCreate() throws Exception {
        assertThat(mapper.readValue("{\"originalUrl\":\"https://example.com\"}", CreateShortUrlRequest.class)
                .expiresAt()).isNull();
        assertThat(mapper.readValue("{\"originalUrl\":\"https://example.com\",\"expiresAt\":null}",
                CreateShortUrlRequest.class).expiresAt()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"1893456000", "1893456000.5", "\"2027-01-31T23:59:59\"", "\"2027-01-31\"", "\"\"",
            "\"tomorrow\"", "{}", "[]", "true", "\"2027-01-31 23:59:59Z\""})
    void shouldRejectNumbersLocalDateTimesAndOtherShapes(String value) {
        String create = "{\"originalUrl\":\"https://example.com\",\"expiresAt\":" + value + "}";
        String patch = "{\"expiresAt\":" + value + "}";

        assertThatThrownBy(() -> mapper.readValue(create, CreateShortUrlRequest.class))
                .isInstanceOf(MismatchedInputException.class);
        assertThatThrownBy(() -> mapper.readValue(patch, UpdateShortUrlRequest.class))
                .isInstanceOf(MismatchedInputException.class);
    }

    @Test
    void shouldReportNeitherFieldForAnEmptyPatchBody() throws Exception {
        UpdateShortUrlRequest request = mapper.readValue("{}", UpdateShortUrlRequest.class);

        assertThat(request.hasActive()).isFalse();
        assertThat(request.hasExpiresAt()).isFalse();
    }

    @Test
    void shouldTellAnExplicitNullExpiryFromAnAbsentOne() throws Exception {
        UpdateShortUrlRequest cleared = mapper.readValue("{\"expiresAt\":null}", UpdateShortUrlRequest.class);
        UpdateShortUrlRequest untouched = mapper.readValue("{\"active\":false}", UpdateShortUrlRequest.class);

        assertThat(cleared.hasExpiresAt()).isTrue();
        assertThat(cleared.getExpiresAt()).isNull();
        assertThat(cleared.hasActive()).isFalse();
        assertThat(untouched.hasExpiresAt()).isFalse();
        assertThat(untouched.hasActive()).isTrue();
        assertThat(untouched.getActive()).isFalse();
    }

    @Test
    void shouldRecordAnExplicitNullActiveAsPresent() throws Exception {
        UpdateShortUrlRequest request = mapper.readValue("{\"active\":null}", UpdateShortUrlRequest.class);

        assertThat(request.hasActive()).isTrue();
        assertThat(request.getActive()).isNull();
    }

    @Test
    void shouldReadBothFieldsTogether() throws Exception {
        UpdateShortUrlRequest request = mapper.readValue(
                "{\"active\":true,\"expiresAt\":\"2027-01-31T23:59:59Z\"}", UpdateShortUrlRequest.class);

        assertThat(request.getActive()).isTrue();
        assertThat(request.getExpiresAt().toInstant()).isEqualTo(Instant.parse("2027-01-31T23:59:59Z"));
    }

    @Test
    void shouldStillRejectUnknownFieldsInThePatchBody() {
        assertThatThrownBy(() -> mapper.readValue("{\"expiresAt\":null,\"x\":1}", UpdateShortUrlRequest.class))
                .isInstanceOf(MismatchedInputException.class);
    }
}
