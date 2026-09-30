package com.schwab.urlshortener.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.schwab.urlshortener.domain.ClickEvent;
import com.schwab.urlshortener.domain.ShortUrl;
import com.schwab.urlshortener.support.RepositoryTest;
import java.time.Instant;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.jdbc.core.JdbcTemplate;

/** AC1: {@code ClickEvent} round-trips {@code clicked_at} to the microsecond, in UTC (D45). */
@RepositoryTest
class ClickEventRepositoryTest {

    private static final Instant CREATED = Instant.parse("2026-01-01T10:00:00Z");
    private static final Instant NANOS = Instant.parse("2026-03-01T10:15:30.123456789Z");
    private static final Instant MICROS = Instant.parse("2026-03-01T10:15:30.123456Z");

    @Autowired
    private ClickEventRepository events;

    @Autowired
    private ShortUrlRepository shortUrls;

    @Autowired
    private TestEntityManager testEntityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void shouldRoundTripClickedAtToTheMicrosecondWithoutRoundingUp() {
        long shortUrlId = shortUrls.saveAndFlush(
                ShortUrl.create("abc1234", "https://example.com/", false, "alice", CREATED)).getId();

        ClickEvent saved = events.saveAndFlush(ClickEvent.of(shortUrlId, NANOS));
        testEntityManager.clear();

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getClickedAt()).isEqualTo(MICROS);
        ClickEvent reloaded = events.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getShortUrlId()).isEqualTo(shortUrlId);
        assertThat(reloaded.getClickedAt()).isEqualTo(MICROS);
        assertThat(jdbcTemplate.queryForObject("SELECT clicked_at FROM click_event WHERE id = ?",
                OffsetDateTime.class, saved.getId()).toInstant()).isEqualTo(MICROS);
    }
}
