package com.schwab.urlshortener.service;

import com.schwab.urlshortener.repository.ClickEventRepository.DayCount;
import com.schwab.urlshortener.service.exception.InvalidStatsQueryException;
import com.schwab.urlshortener.service.exception.InvalidStatsQueryException.Violation;
import com.schwab.urlshortener.service.exception.StatsParameter;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.zone.ZoneRulesProvider;
import java.util.ArrayList;
import java.util.List;

/**
 * The validated, resolved window of a stats request (D95 to D98): the zone, the inclusive local dates, the start
 * instant of every local day and the exclusive end. Java does all the time-zone arithmetic; no zone string ever
 * reaches PostgreSQL (D95).
 *
 * @param zoneId    the zone ID exactly as accepted ({@code UTC} when the parameter was absent, D96)
 * @param from      first local date, inclusive
 * @param to        last local date, inclusive
 * @param dayStarts the start instant of each local date in {@code [from, to]}, non-decreasing; two neighbours are
 *                  equal only for a date that does not exist in the zone
 * @param end       the start instant of {@code to + 1}, exclusive
 */
public record StatsPeriod(String zoneId, LocalDate from, LocalDate to, List<Instant> dayStarts, Instant end) {

    public static final String DEFAULT_TIMEZONE = "UTC";
    public static final LocalDate MIN_DATE = LocalDate.of(1970, 1, 1);
    public static final LocalDate MAX_DATE = LocalDate.of(9999, 12, 31);
    /** Default window: the last 30 days ending on {@code to} (D98). */
    public static final int DEFAULT_DAYS = 30;
    public static final int MAX_DAYS = 366;

    public static final String TIMEZONE_RULE = "must be an IANA time zone ID such as America/New_York, or UTC; IDs are "
            + "case-sensitive and UTC offsets such as +05:00 are not accepted";
    public static final String DATE_RULE = "must be a date in the form yyyy-MM-dd between 1970-01-01 and 9999-12-31";
    public static final String ORDER_RULE = "must not be after to";
    public static final String LENGTH_RULE = "must be at most 365 days before to (at most 366 days in total)";

    /**
     * Validates the raw parameters and resolves the window. Every format and bound failure is collected; the range
     * checks run only when all three values are valid. Nothing here touches the database (D104).
     *
     * @param timezone the raw {@code timezone} parameter, or null when absent
     * @param from     the raw {@code from} parameter, or null when absent
     * @param to       the raw {@code to} parameter, or null when absent
     * @param now      the current instant; "today" is taken in the resolved zone, never in the clock's own zone (D98)
     * @throws InvalidStatsQueryException if any value is invalid; it never carries a submitted value (D99)
     */
    public static StatsPeriod resolve(String timezone, String from, String to, Instant now) {
        List<Violation> violations = new ArrayList<>();
        String id = timezone == null ? DEFAULT_TIMEZONE : timezone;
        if (!isKnownZoneId(id)) {
            violations.add(new Violation(StatsParameter.TIMEZONE, TIMEZONE_RULE));
        }
        LocalDate fromDate = from == null ? null : parseDate(from);
        LocalDate toDate = to == null ? null : parseDate(to);
        if (from != null && fromDate == null) {
            violations.add(new Violation(StatsParameter.FROM, DATE_RULE));
        }
        if (to != null && toDate == null) {
            violations.add(new Violation(StatsParameter.TO, DATE_RULE));
        }
        if (!violations.isEmpty()) {
            throw new InvalidStatsQueryException(violations);
        }
        ZoneId zone = ZoneId.of(id);
        LocalDate resolvedTo = toDate != null ? toDate : LocalDate.ofInstant(now, zone);   // D98: never clock.withZone
        LocalDate resolvedFrom = fromDate != null ? fromDate
                : latest(resolvedTo.minusDays(DEFAULT_DAYS - 1L), MIN_DATE);
        if (resolvedFrom.isAfter(resolvedTo)) {
            throw new InvalidStatsQueryException(List.of(new Violation(StatsParameter.FROM, ORDER_RULE)));
        }
        if (resolvedTo.toEpochDay() - resolvedFrom.toEpochDay() + 1 > MAX_DAYS) {
            throw new InvalidStatsQueryException(List.of(new Violation(StatsParameter.FROM, LENGTH_RULE)));
        }
        List<Instant> dayStarts = resolvedFrom.datesUntil(resolvedTo.plusDays(1))
                .map(d -> d.atStartOfDay(zone).toInstant()).toList();
        Instant end = resolvedTo.plusDays(1).atStartOfDay(zone).toInstant();
        return new StatsPeriod(zone.getId(), resolvedFrom, resolvedTo, dayStarts, end);
    }

    /**
     * One entry per local date in {@code [from, to]}, ascending, with 0 for dates without rows (D19). Bucket indexes
     * are 1-based positions in {@link #dayStarts()}.
     *
     * @throws IllegalStateException if a row's index lies outside {@code 1..N}: the query's range filter makes that
     *                               impossible, so it would be a bug
     */
    public List<DailyClicks> densify(List<DayCount> rows) {
        long[] counts = new long[dayStarts.size()];
        for (DayCount row : rows) {
            if (row.dayIndex() < 1 || row.dayIndex() > counts.length) {
                throw new IllegalStateException("day bucket index out of range");
            }
            counts[row.dayIndex() - 1] += row.clicks();
        }
        List<DailyClicks> daily = new ArrayList<>(counts.length);
        for (int i = 0; i < counts.length; i++) {
            daily.add(new DailyClicks(from.plusDays(i), counts[i]));
        }
        return List.copyOf(daily);
    }

    /** D96: exactly {@code UTC} or exactly an ID in the JDK's tzdb set; case-sensitive, no trimming. */
    static boolean isKnownZoneId(String id) {
        return DEFAULT_TIMEZONE.equals(id) || ZoneRulesProvider.getAvailableZoneIds().contains(id);
    }

    /** D98: strict ISO date between 1970-01-01 and 9999-12-31, or null. */
    private static LocalDate parseDate(String text) {
        try {
            LocalDate date = LocalDate.parse(text);
            return date.isBefore(MIN_DATE) || date.isAfter(MAX_DATE) ? null : date;
        } catch (DateTimeException e) {
            return null;
        }
    }

    private static LocalDate latest(LocalDate a, LocalDate b) {
        return a.isAfter(b) ? a : b;
    }
}
