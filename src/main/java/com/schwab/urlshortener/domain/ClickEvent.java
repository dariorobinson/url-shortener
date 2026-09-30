package com.schwab.urlshortener.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.hibernate.annotations.Immutable;

/**
 * One counted click (FR-5). Append-only: the row is never updated. It stores the click timestamp only (D8).
 * {@code shortUrlId} is a plain id with no association, so nothing lazy can be loaded or printed; the foreign key
 * {@code fk_click_event_short_url} is the integrity guarantee.
 */
@Entity
@Table(name = "click_event")
@Immutable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@ToString(onlyExplicitlyIncluded = true)
public class ClickEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @ToString.Include
    private Long id;

    @Column(name = "short_url_id", nullable = false, updatable = false)
    @ToString.Include
    private long shortUrlId;

    @Column(name = "clicked_at", nullable = false, updatable = false)
    @ToString.Include
    private Instant clickedAt;

    /**
     * @param clickedAt truncated to microseconds (D45), idempotently
     * @throws NullPointerException if {@code clickedAt} is null
     */
    public static ClickEvent of(long shortUrlId, Instant clickedAt) {
        ClickEvent event = new ClickEvent();
        event.shortUrlId = shortUrlId;
        event.clickedAt = Objects.requireNonNull(clickedAt, "clickedAt").truncatedTo(ChronoUnit.MICROS);
        return event;
    }
}
