package com.schwab.urlshortener.api.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** D31 catalogue, no more and no fewer. */
class ErrorCodeTest {

    @Test
    void shouldContainExactlyTheD31CatalogueInOrder() {
        assertThat(Arrays.stream(ErrorCode.values()).map(Enum::name)).containsExactly(
                "VALIDATION_FAILED", "MALFORMED_REQUEST", "INVALID_URL", "INVALID_ALIAS",
                "ALIAS_ALREADY_EXISTS", "SHORT_URL_NOT_FOUND", "SHORT_URL_ALREADY_DEACTIVATED",
                "SHORT_URL_ALREADY_ACTIVE", "CONCURRENT_MODIFICATION", "SHORT_CODE_UNAVAILABLE",
                "AUTHENTICATION_REQUIRED", "ACCESS_DENIED", "INTERNAL_ERROR");
    }

    @Test
    void shouldMapEachCodeToItsFixedHttpStatus() {
        assertThat(ErrorCode.VALIDATION_FAILED.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ErrorCode.MALFORMED_REQUEST.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ErrorCode.INVALID_URL.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ErrorCode.INVALID_ALIAS.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ErrorCode.ALIAS_ALREADY_EXISTS.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ErrorCode.SHORT_URL_NOT_FOUND.status()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ErrorCode.SHORT_URL_ALREADY_DEACTIVATED.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ErrorCode.SHORT_URL_ALREADY_ACTIVE.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ErrorCode.CONCURRENT_MODIFICATION.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ErrorCode.SHORT_CODE_UNAVAILABLE.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(ErrorCode.AUTHENTICATION_REQUIRED.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(ErrorCode.ACCESS_DENIED.status()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(ErrorCode.INTERNAL_ERROR.status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
