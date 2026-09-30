package com.schwab.urlshortener.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.schwab.urlshortener.domain.ClickEvent;
import com.schwab.urlshortener.domain.ShortUrl;
import com.schwab.urlshortener.repository.ClickEventRepository.DayCount;
import com.schwab.urlshortener.service.StatsPeriod;
import com.schwab.urlshortener.support.RepositoryTest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * US-010 AC1: {@code ClickEvent} round-trips {@code clicked_at} to the microsecond, in UTC (D45). US-011: the per-day
 * count with Java-computed day starts (D95, AC1, AC8), its raw row types and its index use (D103).
 */
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

    @Autowired
    private NamedParameterJdbcTemplate namedJdbc;

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

    // ---- US-011: clicks per local day (D95, D103)

    private static final String NEW_YORK = "America/New_York";

    private long newLink(String code) {
        return shortUrls.saveAndFlush(ShortUrl.create(code, "https://example.com/", false, "alice", CREATED)).getId();
    }

    /** UTC-bound, never a Timestamp, so the JVM and session zones cannot shift the fall-back-hour fixtures. */
    private void insertClick(long shortUrlId, String instant) {
        jdbcTemplate.update("INSERT INTO click_event (short_url_id, clicked_at) VALUES (?, ?)", shortUrlId,
                OffsetDateTime.ofInstant(Instant.parse(instant), ZoneOffset.UTC));
    }

    private void insertDstFixtures(long id) {
        for (String instant : List.of(
                "2026-03-08T04:59:59.999999Z", "2026-03-08T05:00:00Z", "2026-03-08T06:59:59.999999Z",
                "2026-03-08T07:00:00Z", "2026-03-09T03:59:59.999999Z", "2026-03-09T04:00:00Z",
                "2026-11-01T03:59:59.999999Z", "2026-11-01T04:00:00Z", "2026-11-01T05:30:00Z",
                "2026-11-01T06:30:00Z", "2026-11-02T04:30:00Z", "2026-11-02T05:00:00Z")) {
            insertClick(id, instant);
        }
    }

    private List<DayCount> count(long id, String zone, String from, String to) {
        StatsPeriod period = StatsPeriod.resolve(zone, from, to, CREATED);
        return events.countClicksPerDay(id, period.dayStarts(), period.end());
    }

    @Test
    void shouldCountTheNewYorkSpringForwardDayAsOneLocalDayAtEveryEdge() {
        long id = newLink("abc1234");
        insertDstFixtures(id);

        assertThat(count(id, NEW_YORK, "2026-03-07", "2026-03-09"))
                .containsExactly(new DayCount(1, 1), new DayCount(2, 4), new DayCount(3, 1));
        // The default zone buckets by UTC day: a fixed offset would give a different answer.
        assertThat(count(id, null, "2026-03-07", "2026-03-09"))
                .containsExactly(new DayCount(2, 4), new DayCount(3, 2));
    }

    @Test
    void shouldCountTheNewYorkFallBackDayAsOneLocalDayAtEveryEdge() {
        long id = newLink("abc1234");
        insertDstFixtures(id);

        assertThat(count(id, NEW_YORK, "2026-10-31", "2026-11-02"))
                .containsExactly(new DayCount(1, 1), new DayCount(2, 4), new DayCount(3, 1));
        assertThat(count(id, null, "2026-10-31", "2026-11-02"))
                .containsExactly(new DayCount(2, 4), new DayCount(3, 2));
    }

    @Test
    void shouldExcludeTheNeighbouringClicksWhenTheWindowIsTheDstDayItself() {
        long id = newLink("abc1234");
        insertDstFixtures(id);

        assertThat(count(id, NEW_YORK, "2026-03-08", "2026-03-08")).containsExactly(new DayCount(1, 4));
        assertThat(count(id, NEW_YORK, "2026-11-01", "2026-11-01")).containsExactly(new DayCount(1, 4));
    }

    @Test
    void shouldCountTheSydneyTwentyThreeAndTwentyFiveHourDays() {
        long id = newLink("abc1234");
        // DST starts on 2026-10-04 (a 23-hour day): that date runs from 2026-10-03T14:00:00Z to 2026-10-04T13:00:00Z.
        insertClick(id, "2026-10-03T13:59:59.999999Z");
        insertClick(id, "2026-10-03T14:00:00Z");
        insertClick(id, "2026-10-04T12:59:59.999999Z");
        insertClick(id, "2026-10-04T13:00:00Z");
        // DST ends on 2026-04-05 (a 25-hour day): that date runs from 2026-04-04T13:00:00Z to 2026-04-05T14:00:00Z.
        insertClick(id, "2026-04-04T12:59:59.999999Z");
        insertClick(id, "2026-04-04T13:00:00Z");
        insertClick(id, "2026-04-05T13:59:59.999999Z");
        insertClick(id, "2026-04-05T14:00:00Z");

        assertThat(count(id, "Australia/Sydney", "2026-10-03", "2026-10-05"))
                .containsExactly(new DayCount(1, 1), new DayCount(2, 2), new DayCount(3, 1));
        assertThat(count(id, "Australia/Sydney", "2026-04-04", "2026-04-06"))
                .containsExactly(new DayCount(1, 1), new DayCount(2, 2), new DayCount(3, 1));
    }

    @Test
    void shouldSendAClickAtTheSharedThresholdOfASkippedSamoaDateToTheLastBucketAndLeaveTheSkippedOneEmpty() {
        long id = newLink("abc1234");
        insertClick(id, "2011-12-29T10:00:00Z");          // start of 2011-12-29 (UTC-10)
        insertClick(id, "2011-12-30T09:59:59.999999Z");   // last instant of 2011-12-29
        insertClick(id, "2011-12-30T10:00:00Z");          // start of 2011-12-31 (UTC+14); 12-30 never existed

        List<DayCount> rows = count(id, "Pacific/Apia", "2011-12-29", "2011-12-31");

        assertThat(rows).containsExactly(new DayCount(1, 2), new DayCount(3, 1));
    }

    @Test
    void shouldCountTheKathmanduHalfHourOffsetEdge() {
        long id = newLink("abc1234");
        insertClick(id, "2026-03-01T18:14:59Z");
        insertClick(id, "2026-03-01T18:15:00Z");

        assertThat(count(id, "Asia/Kathmandu", "2026-03-01", "2026-03-02"))
                .containsExactly(new DayCount(1, 1), new DayCount(2, 1));
    }

    @Test
    void shouldNotDependOnTheSessionTimeZone() {
        long id = newLink("abc1234");
        insertDstFixtures(id);
        List<DayCount> before = count(id, NEW_YORK, "2026-03-07", "2026-03-09");

        jdbcTemplate.execute("SET LOCAL TimeZone = 'Pacific/Kiritimati'");
        List<DayCount> after = count(id, NEW_YORK, "2026-03-07", "2026-03-09");

        assertThat(jdbcTemplate.queryForObject("SHOW TimeZone", String.class)).isEqualTo("Pacific/Kiritimati");
        assertThat(after).isEqualTo(before).containsExactly(new DayCount(1, 1), new DayCount(2, 4),
                new DayCount(3, 1));
    }

    @Test
    void shouldExcludeOtherLinksAndReturnNothingWithoutEvents() {
        long mine = newLink("abc1234");
        long other = newLink("def5678");
        insertClick(other, "2026-03-08T12:00:00Z");

        assertThat(count(mine, NEW_YORK, "2026-03-07", "2026-03-09")).isEmpty();

        insertClick(mine, "2026-03-08T12:00:00Z");
        assertThat(count(mine, NEW_YORK, "2026-03-07", "2026-03-09")).containsExactly(new DayCount(2, 1));
        assertThat(count(other, NEW_YORK, "2026-03-07", "2026-03-09")).containsExactly(new DayCount(2, 1));
    }

    @Test
    void shouldReturnRawRowsAsIntegerAndLongOrderedByDayIndex() {
        long id = newLink("abc1234");
        insertClick(id, "2026-03-03T12:00:00Z");
        insertClick(id, "2026-03-01T12:00:00Z");
        insertClick(id, "2026-03-01T13:00:00Z");
        StatsPeriod period = StatsPeriod.resolve("UTC", "2026-03-01", "2026-03-03", CREATED);
        String csv = period.dayStarts().stream().map(Instant::toString).collect(Collectors.joining(","));

        List<Object[]> rows = events.countClicksPerDayRows(id, period.dayStarts().getFirst(), period.end(), csv);

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).containsExactly(1, 2L);
        assertThat(rows.get(1)).containsExactly(3, 1L);
        assertThat(rows.get(0)[0]).isInstanceOf(Integer.class);
        assertThat(rows.get(0)[1]).isInstanceOf(Long.class);
    }

    @Test
    void shouldCountAtTheLastSupportedDateWithoutTextualYearTenThousand() {
        long id = newLink("abc1234");
        insertClick(id, "9999-12-30T09:59:59Z");
        insertClick(id, "9999-12-30T10:00:00Z");
        insertClick(id, "9999-12-31T00:00:00Z");
        insertClick(id, "9999-12-31T09:59:59.999999Z");
        insertClick(id, "9999-12-31T10:00:00Z");
        insertClick(id, "9999-12-31T23:59:59.999999Z");

        // Kiritimati (+14): the day is [9999-12-30T10:00Z, 9999-12-31T10:00Z).
        assertThat(count(id, "Pacific/Kiritimati", "9999-12-31", "9999-12-31")).containsExactly(new DayCount(1, 3));
        // The exclusive end is in year 10000 (UTC) and is bound as a typed parameter, never as text.
        assertThat(count(id, "UTC", "9999-12-31", "9999-12-31")).containsExactly(new DayCount(1, 4));
    }

    @Test
    void shouldCountAFullLeapYearWindowOf366DayStarts() {
        long id = newLink("abc1234");
        insertClick(id, "2028-01-01T00:00:00Z");
        insertClick(id, "2028-12-31T23:59:59.999999Z");

        assertThat(count(id, "UTC", "2028-01-01", "2028-12-31"))
                .containsExactly(new DayCount(1, 1), new DayCount(366, 1));
    }

    @Test
    void shouldRejectInvalidDayStartArgumentsBeforeQuerying() {
        Instant t = Instant.parse("2026-03-01T00:00:00Z");

        // The repository proxy translates the IllegalArgumentException into Spring's data access exception.
        assertRejected(() -> events.countClicksPerDay(1L, List.of(), t));
        assertRejected(() -> events.countClicksPerDay(1L, Collections.nCopies(367, t), t.plusSeconds(1)));
        assertRejected(() -> events.countClicksPerDay(1L, List.of(t.plusSeconds(10), t), t.plusSeconds(20)));
        assertRejected(() -> events.countClicksPerDay(1L, List.of(t), t));
        // Positive control: a valid call with the same kind of arguments runs.
        assertThat(events.countClicksPerDay(1L, List.of(t), t.plusSeconds(1))).isEmpty();
    }

    private static void assertRejected(ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOf(InvalidDataAccessApiUsageException.class)
                .hasCauseInstanceOf(IllegalArgumentException.class);
    }

    // ---- D103: the statement can use ix_click_event_short_url_id_clicked_at

    private static final String INDEX = "ix_click_event_short_url_id_clicked_at";

    private List<String> explain(String sql, long id) {
        Instant start = Instant.parse("2026-03-01T00:00:00Z");
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("shortUrlId", id)
                .addValue("rangeStart", OffsetDateTime.ofInstant(start, ZoneOffset.UTC))
                .addValue("rangeEnd", OffsetDateTime.ofInstant(start.plusSeconds(86_400), ZoneOffset.UTC))
                .addValue("dayStarts", start.toString());
        return namedJdbc.queryForList("EXPLAIN " + sql, params, String.class);
    }

    private static List<String> indexConditions(List<String> plan) {
        return plan.stream().filter(line -> line.contains("Index Cond")).toList();
    }

    /** Sets the planner switch and proves it took effect, so the plan assertions cannot pass on a seq-scan plan. */
    private void disableSeqScan() {
        jdbcTemplate.execute("SET LOCAL enable_seqscan = off");
        assertThat(jdbcTemplate.queryForObject("SHOW enable_seqscan", String.class)).isEqualTo("off");
    }

    @Test
    void shouldUseTheCompositeIndexWithIndexConditionsOnBothColumnsForTheStatsSql() {
        long id = newLink("abc1234");
        disableSeqScan();

        List<String> plan = explain(ClickEventRepository.CLICKS_PER_DAY_SQL, id);

        assertThat(plan).anySatisfy(line -> assertThat(line).contains(INDEX));
        assertThat(indexConditions(plan)).anySatisfy(line -> assertThat(line)
                .contains("short_url_id").contains("clicked_at"));
        assertThat(String.join("\n", plan)).doesNotContain("Seq Scan");
    }

    @Test
    void shouldShowNoIndexConditionOnClickedAtWhenTheColumnIsWrappedInAFunction() {
        long id = newLink("abc1234");
        disableSeqScan();
        String wrapped = ClickEventRepository.CLICKS_PER_DAY_SQL.replace("AND clicked_at >= :rangeStart AND "
                + "clicked_at < :rangeEnd", "AND date_trunc('day', clicked_at) >= :rangeStart AND "
                + "date_trunc('day', clicked_at) < :rangeEnd");
        assertThat(wrapped).isNotEqualTo(ClickEventRepository.CLICKS_PER_DAY_SQL);

        List<String> plan = explain(wrapped, id);

        // Negative control for the detector above: the index may still serve short_url_id, but not clicked_at.
        assertThat(indexConditions(plan)).isNotEmpty().noneSatisfy(line -> assertThat(line).contains("clicked_at"));
    }
}
