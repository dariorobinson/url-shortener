package com.schwab.urlshortener.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CallerTest {

    @Test
    void shouldRoundTripAValidUsernameAndAdminFlag() {
        Caller caller = new Caller("alice", false);

        assertThat(caller.username()).isEqualTo("alice");
        assertThat(caller.admin()).isFalse();
        assertThat(new Caller("admin", true).admin()).isTrue();
    }

    @Test
    void shouldThrowNullPointerExceptionForANullUsername() {
        assertThatThrownBy(() -> new Caller(null, false)).isInstanceOf(NullPointerException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "   ", "\t"})
    void shouldThrowIllegalArgumentExceptionForABlankUsername(String username) {
        assertThatThrownBy(() -> new Caller(username, false)).isInstanceOf(IllegalArgumentException.class);
    }
}
