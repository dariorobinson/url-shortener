package com.schwab.urlshortener.repository;

import com.schwab.urlshortener.util.domain.ClickEvent;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Append-only store of click events (FR-5, D8). It carries no transaction annotation: the click recorder owns the
 * write transaction, so the counter update and the event insert commit together, and the stats read joins the
 * caller's snapshot transaction (D102).
 */
public interface ClickEventRepository extends JpaRepository<ClickEvent, Long> {

    /** The most day starts one query accepts: the 366-day window limit (D98). */
    int MAX_DAY_STARTS = 366;

    /**
     * Clicks per local day (D95): {@code width_bucket} over the Java-computed day starts. No zone ever reaches
     * PostgreSQL, and {@code clicked_at} is compared bare, so the range is served by
     * {@code ix_click_event_short_url_id_clicked_at} (D103).
     */
    String CLICKS_PER_DAY_SQL = "SELECT width_bucket(clicked_at,"
            + " CAST(string_to_array(:dayStarts, ',') AS timestamptz[])) AS day_index,"
            + " count(*) AS clicks FROM click_event"
            + " WHERE short_url_id = :shortUrlId AND clicked_at >= :rangeStart AND clicked_at < :rangeEnd"
            + " GROUP BY day_index ORDER BY day_index";

    /** Raw rows {@code Object[]{Integer dayIndex, Long clicks}}; use {@link #countClicksPerDay}. */
    @Query(value = CLICKS_PER_DAY_SQL, nativeQuery = true)
    List<Object[]> countClicksPerDayRows(@Param("shortUrlId") long shortUrlId, @Param("rangeStart") Instant rangeStart,
            @Param("rangeEnd") Instant rangeEnd, @Param("dayStarts") String dayStarts);

    /**
     * Rows only for days with clicks; {@code dayIndex} is the 1-based position in {@code dayStarts}. The default
     * method joins the caller's transaction.
     *
     * @param dayStarts 1 to {@value #MAX_DAY_STARTS} non-decreasing instants; the first is the inclusive range start
     * @param end       the exclusive range end, after the last day start
     * @throws IllegalArgumentException if a precondition is violated
     */
    default List<DayCount> countClicksPerDay(long shortUrlId, List<Instant> dayStarts, Instant end) {
        if (dayStarts.isEmpty() || dayStarts.size() > MAX_DAY_STARTS) {
            throw new IllegalArgumentException("dayStarts must hold 1 to " + MAX_DAY_STARTS + " instants");
        }
        for (int i = 1; i < dayStarts.size(); i++) {
            if (dayStarts.get(i).isBefore(dayStarts.get(i - 1))) {
                throw new IllegalArgumentException("dayStarts must be non-decreasing");
            }
        }
        if (!end.isAfter(dayStarts.getLast())) {
            throw new IllegalArgumentException("end must be after the last day start");
        }
        String csv = dayStarts.stream().map(Instant::toString).collect(Collectors.joining(","));
        return countClicksPerDayRows(shortUrlId, dayStarts.getFirst(), end, csv).stream()
                .map(r -> new DayCount(((Number) r[0]).intValue(), ((Number) r[1]).longValue())).toList();
    }

    /**
     * @param dayIndex 1-based bucket number from {@code width_bucket}
     * @param clicks   the clicks in that bucket
     */
    record DayCount(int dayIndex, long clicks) {
    }
}
