package com.schwab.urlshortener.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import org.junit.jupiter.api.Test;

class ClockConfigTest {

    @Test
    void shouldProvideUtcSystemClock() {
        Clock clock = new ClockConfig().clock();

        assertThat(clock).isEqualTo(Clock.systemUTC());
    }
}
