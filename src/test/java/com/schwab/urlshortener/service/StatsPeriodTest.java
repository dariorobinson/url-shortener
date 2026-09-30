package com.schwab.urlshortener.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.schwab.urlshortener.repository.ClickEventRepository.DayCount;
import com.schwab.urlshortener.service.exception.InvalidStatsQueryException;
import com.schwab.urlshortener.service.exception.InvalidStatsQueryException.Violation;
import com.schwab.urlshortener.service.exception.StatsParameter;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Stats parameter validation, defaults, day starts and densify (D95 to D98, D19; AC2, AC3, AC8 to AC13). Pure unit
 * tests: the only input that varies the clock is the {@code Instant} handed to {@code resolve}.
 */
class StatsPeriodTest {

    /** 2026-03-09 22:30 in New York, already 2026-03-10 in UTC. */
    private static final Instant NOW = Instant.parse("2026-03-10T03:30:00Z");

    private static StatsPeriod resolve(String timezone, String from, String to) {
        return StatsPeriod.resolve(timezone, from, to, NOW);
    }

    private static InvalidStatsQueryException rejected(String timezone, String from, String to) {
        Throwable thrown = catchThrowable(() -> resolve(timezone, from, to));
        assertThat(thrown).isExactlyInstanceOf(InvalidStatsQueryException.class);
        return (InvalidStatsQueryException) thrown;
    }

    private static LocalDate date(String iso) {
        return LocalDate.parse(iso);
    }

    // ---- timezone (D96, AC3)

    @ParameterizedTest
    @ValueSource(strings = {"UTC", "America/New_York", "Europe/London", "Asia/Kathmandu", "Australia/Lord_Howe",
            "Asia/Calcutta", "US/Eastern", "GMT", "Etc/UTC", "EST5EDT", "Etc/GMT+5", "Etc/GMT-14", "Pacific/Apia"})
    void shouldAcceptUtcAndEveryIanaIdExactly(String id) {
        StatsPeriod period = resolve(id, "2026-03-01", "2026-03-01");

        assertThat(period.zoneId()).isEqualTo(id);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "Z", "+05:00", "-03", "+0530", "+5", "UTC+5", "UTC+05:00", "GMT-3", "UT+1", "UT",
            "PST", "IST", "CTT", "america/new_york", "AMERICA/NEW_YORK", "utc", "Utc", "gmt", " UTC", "UTC ",
            "America/New_York ", "America/New York", "Mars/Olympus_Mons", "../../etc/passwd", "etc/gmt+5",
            "America/", "/", "\u0000"})
    void shouldRejectEveryNonIanaFormWithExactlyOneTimezoneViolation(String id) {
        InvalidStatsQueryException e = rejected(id, null, null);

        assertThat(e.violations()).containsExactly(new Violation(StatsParameter.TIMEZONE, StatsPeriod.TIMEZONE_RULE));
    }

    @Test
    void shouldRejectAVeryLongTimezoneWithoutEchoingIt() {
        String huge = "A".repeat(10_000);

        InvalidStatsQueryException e = rejected(huge, null, null);

        assertThat(e.violations()).containsExactly(new Violation(StatsParameter.TIMEZONE, StatsPeriod.TIMEZONE_RULE));
        assertThat(e.getMessage()).doesNotContain("AAAA");
    }

    @Test
    void shouldDefaultToUtcWhenTheTimezoneIsAbsentAndTreatEmptyAsInvalid() {
        assertThat(resolve(null, null, null).zoneId()).isEqualTo("UTC");
        assertThat(rejected("", null, null).violations()).extracting(Violation::parameter).containsExactly(StatsParameter.TIMEZONE);
    }

    @Test
    void shouldReadEtcGmtPlusFiveAsFiveHoursBehindUtc() {
        StatsPeriod period = resolve("Etc/GMT+5", "2026-03-01", "2026-03-01");

        assertThat(period.dayStarts()).containsExactly(Instant.parse("2026-03-01T05:00:00Z"));
        assertThat(period.end()).isEqualTo(Instant.parse("2026-03-02T05:00:00Z"));
    }

    // ---- dates (D98, AC12)

    @ParameterizedTest
    @ValueSource(strings = {"2026-02-30", "2026-2-3", "20260203", "2026-02-03T00:00", "+2026-02-03", " 2026-02-03",
            "2026-02-03 ", "", "2026/02/03", "٢٠٢٦-٠٢-٠٣", "1969-12-31",
            "0000-01-01", "10000-01-01", "+10000-01-01", "-0001-01-01", "2026-13-01", "abc"})
    void shouldRejectMalformedOrOutOfBoundsDatesOnTheOffendingParameter(String value) {
        assertThat(rejected("UTC", value, null).violations())
                .containsExactly(new Violation(StatsParameter.FROM, StatsPeriod.DATE_RULE));
        assertThat(rejected("UTC", null, value).violations())
                .containsExactly(new Violation(StatsParameter.TO, StatsPeriod.DATE_RULE));
    }

    @Test
    void shouldAcceptTheBoundaryDates() {
        assertThat(resolve("UTC", "1970-01-01", "1970-01-01").from()).isEqualTo(date("1970-01-01"));
        assertThat(resolve("UTC", "9999-12-31", "9999-12-31").to()).isEqualTo(date("9999-12-31"));
        assertThat(resolve("UTC", "2024-02-29", "2024-02-29").from()).isEqualTo(date("2024-02-29"));
    }

    @Test
    void shouldCollectEveryFormatFailureAndSkipRangeChecksWhileAnyFieldIsInvalid() {
        assertThat(rejected("nope", "2026-02-30", "bad").violations()).containsExactlyInAnyOrder(
                new Violation(StatsParameter.TIMEZONE, StatsPeriod.TIMEZONE_RULE),
                new Violation(StatsParameter.FROM, StatsPeriod.DATE_RULE),
                new Violation(StatsParameter.TO, StatsPeriod.DATE_RULE));
        // from is after to, but the bad timezone is the only reported error: the range checks did not run.
        assertThat(rejected("nope", "2026-03-09", "2026-03-01").violations())
                .containsExactly(new Violation(StatsParameter.TIMEZONE, StatsPeriod.TIMEZONE_RULE));
        // a too-long window next to a bad date: only the date is reported.
        assertThat(rejected("UTC", "1970-01-01", "bad").violations())
                .containsExactly(new Violation(StatsParameter.TO, StatsPeriod.DATE_RULE));
    }

    @Test
    void shouldNeverPutASubmittedValueInTheMessageOrTheRuleTexts() {
        InvalidStatsQueryException e = rejected("tz-marker", "from-marker", "to-marker");

        assertThat(e.violations()).hasSize(3);
        assertThat(e.getMessage()).doesNotContain("marker");
        assertThat(e.violations()).allSatisfy(v -> {
            assertThat(v.rule()).doesNotContain("marker");
            assertThat(v.parameter().wireName()).doesNotContain("marker");
        });
        assertThat(e.violations()).extracting(Violation::rule).containsOnly(StatsPeriod.TIMEZONE_RULE,
                StatsPeriod.DATE_RULE);
    }

    @Test
    void shouldPinTheRuleTexts() {
        assertThat(StatsPeriod.TIMEZONE_RULE).isEqualTo("must be an IANA time zone ID such as America/New_York, or "
                + "UTC; IDs are case-sensitive and UTC offsets such as +05:00 are not accepted");
        assertThat(StatsPeriod.DATE_RULE)
                .isEqualTo("must be a date in the form yyyy-MM-dd between 1970-01-01 and 9999-12-31");
        assertThat(StatsPeriod.ORDER_RULE).isEqualTo("must not be after to");
        assertThat(StatsPeriod.LENGTH_RULE)
                .isEqualTo("must be at most 365 days before to (at most 366 days in total)");
    }

    // ---- defaults (D98, AC2, AC9)

    @Test
    void shouldDefaultToTodayInTheRequestedZoneAndThirtyDaysEndingThere() {
        StatsPeriod newYork = resolve("America/New_York", null, null);
        StatsPeriod utc = resolve(null, null, null);

        assertThat(newYork.to()).isEqualTo(date("2026-03-09"));
        assertThat(newYork.from()).isEqualTo(date("2026-02-08"));
        assertThat(newYork.dayStarts()).hasSize(30);
        assertThat(utc.zoneId()).isEqualTo("UTC");
        assertThat(utc.to()).isEqualTo(date("2026-03-10"));
        assertThat(utc.from()).isEqualTo(date("2026-02-09"));
        assertThat(utc.dayStarts()).hasSize(30);
    }

    @Test
    void shouldApplyEachDefaultIndependently() {
        StatsPeriod onlyTo = resolve("UTC", null, "2026-01-31");
        StatsPeriod onlyFrom = resolve("UTC", "2026-03-01", null);

        assertThat(onlyTo.from()).isEqualTo(date("2026-01-02"));
        assertThat(onlyTo.to()).isEqualTo(date("2026-01-31"));
        assertThat(onlyFrom.from()).isEqualTo(date("2026-03-01"));
        assertThat(onlyFrom.to()).isEqualTo(date("2026-03-10"));
        assertThat(onlyFrom.dayStarts()).hasSize(10);
    }

    @Test
    void shouldClampTheDefaultFromAtTheEpochDate() {
        StatsPeriod period = resolve("UTC", null, "1970-01-10");

        assertThat(period.from()).isEqualTo(date("1970-01-01"));
        assertThat(period.dayStarts()).hasSize(10);
        assertThat(resolve("UTC", null, "1970-01-01").dayStarts()).hasSize(1);
    }

    @Test
    void shouldRejectAnExplicitFromWhenTheDefaultedToPrecedesIt() {
        // from is after the defaulted to (today, 2026-03-10): a range error on from, never on the defaulted to.
        assertThat(rejected("UTC", "2026-03-11", null).violations())
                .containsExactly(new Violation(StatsParameter.FROM, StatsPeriod.ORDER_RULE));
    }

    // ---- range (D97, D98, AC10, AC11, AC13)

    @Test
    void shouldAcceptASingleDayAndFutureDates() {
        StatsPeriod single = resolve("UTC", "2026-03-08", "2026-03-08");
        StatsPeriod future = resolve("UTC", "2030-01-01", "2030-01-03");

        assertThat(single.dayStarts()).hasSize(1);
        assertThat(single.from()).isEqualTo(single.to());
        assertThat(future.dayStarts()).hasSize(3);
    }

    @Test
    void shouldAcceptExactly366DaysAndRejectThe367th() {
        StatsPeriod leapYear = resolve("UTC", "2028-01-01", "2028-12-31");

        assertThat(leapYear.dayStarts()).hasSize(366);
        assertThat(resolve("UTC", "2025-12-31", "2026-12-31").dayStarts()).hasSize(366);   // 365 days before to
        assertThat(rejected("UTC", "2025-12-30", "2026-12-31").violations())
                .containsExactly(new Violation(StatsParameter.FROM, StatsPeriod.LENGTH_RULE));   // 366 days before to
        assertThat(rejected("UTC", "2025-01-01", "2026-01-02").violations())
                .containsExactly(new Violation(StatsParameter.FROM, StatsPeriod.LENGTH_RULE));
    }

    @Test
    void shouldRejectFromAfterToOnFromAndAcceptEqualDates() {
        assertThat(rejected("UTC", "2026-03-09", "2026-03-07").violations())
                .containsExactly(new Violation(StatsParameter.FROM, StatsPeriod.ORDER_RULE));
        assertThat(resolve("UTC", "2026-03-09", "2026-03-09").dayStarts()).hasSize(1);
    }

    @Test
    void shouldNameTheThreeQueryParametersExactlyAsOnTheWire() {
        assertThat(StatsParameter.values()).extracting(StatsParameter::wireName)
                .containsExactly("timezone", "from", "to");
    }

    @Test
    void shouldResolveTheBoundDatesAndALongWindowInTheExtremeZonesIncludingTheYear10000End() {
        assertThatCode(() -> resolve("Etc/GMT+12", "1970-01-01", "1970-01-01")).doesNotThrowAnyException();
        StatsPeriod top = resolve("Pacific/Kiritimati", "9999-12-31", "9999-12-31");

        assertThat(top.dayStarts()).hasSize(1);
        assertThat(top.end()).isAfter(top.dayStarts().getFirst());
        assertThat(resolve("Pacific/Kiritimati", "9999-01-01", "9999-12-31").dayStarts()).hasSize(365);

        // Negative offset: the exclusive end of 9999-12-31 in UTC-12 falls in year 10000 (D98).
        StatsPeriod behind = resolve("Etc/GMT+12", "9999-12-31", "9999-12-31");
        assertThat(behind.dayStarts()).containsExactly(Instant.parse("9999-12-31T12:00:00Z"));
        assertThat(behind.end()).isEqualTo(Instant.parse("+10000-01-01T12:00:00Z"));

        // The longest window (366 days) ending on the last supported date.
        StatsPeriod longest = resolve("Etc/GMT+12", "9998-12-31", "9999-12-31");
        assertThat(longest.dayStarts()).hasSize(366);
        assertThat(longest.dayStarts().getFirst()).isEqualTo(Instant.parse("9998-12-31T12:00:00Z"));
        assertThat(longest.end()).isEqualTo(Instant.parse("+10000-01-01T12:00:00Z"));
    }

    // ---- day starts: 23-hour, 25-hour and skipped days (D95, AC1)

    private static Duration lengthOfDay(StatsPeriod period, int index) {
        Instant next = index + 1 < period.dayStarts().size() ? period.dayStarts().get(index + 1) : period.end();
        return Duration.between(period.dayStarts().get(index), next);
    }

    @Test
    void shouldComputeTheNewYorkSpringForwardDayAs23HoursFromTheZonesOwnRules() {
        StatsPeriod period = resolve("America/New_York", "2026-03-07", "2026-03-09");

        assertThat(period.dayStarts()).containsExactly(Instant.parse("2026-03-07T05:00:00Z"),
                Instant.parse("2026-03-08T05:00:00Z"), Instant.parse("2026-03-09T04:00:00Z"));
        assertThat(period.end()).isEqualTo(Instant.parse("2026-03-10T04:00:00Z"));
        assertThat(lengthOfDay(period, 0)).isEqualTo(Duration.ofHours(24));
        assertThat(lengthOfDay(period, 1)).isEqualTo(Duration.ofHours(23));
        assertThat(lengthOfDay(period, 2)).isEqualTo(Duration.ofHours(24));
    }

    @Test
    void shouldComputeTheNewYorkFallBackDayAs25Hours() {
        StatsPeriod period = resolve("America/New_York", "2026-10-31", "2026-11-02");

        assertThat(period.dayStarts()).containsExactly(Instant.parse("2026-10-31T04:00:00Z"),
                Instant.parse("2026-11-01T04:00:00Z"), Instant.parse("2026-11-02T05:00:00Z"));
        assertThat(period.end()).isEqualTo(Instant.parse("2026-11-03T05:00:00Z"));
        assertThat(lengthOfDay(period, 1)).isEqualTo(Duration.ofHours(25));
    }

    @Test
    void shouldComputeTheSouthernHemisphereSydneyDstDays() {
        StatsPeriod spring = resolve("Australia/Sydney", "2026-10-03", "2026-10-05");
        StatsPeriod autumn = resolve("Australia/Sydney", "2026-04-04", "2026-04-06");

        // DST starts on 2026-10-04 (02:00 +10 to 03:00 +11): a 23-hour day.
        assertThat(spring.dayStarts()).containsExactly(Instant.parse("2026-10-02T14:00:00Z"),
                Instant.parse("2026-10-03T14:00:00Z"), Instant.parse("2026-10-04T13:00:00Z"));
        assertThat(lengthOfDay(spring, 1)).isEqualTo(Duration.ofHours(23));
        // DST ends on 2026-04-05 (03:00 +11 to 02:00 +10): a 25-hour day.
        assertThat(autumn.dayStarts()).containsExactly(Instant.parse("2026-04-03T13:00:00Z"),
                Instant.parse("2026-04-04T13:00:00Z"), Instant.parse("2026-04-05T14:00:00Z"));
        assertThat(lengthOfDay(autumn, 1)).isEqualTo(Duration.ofHours(25));
    }

    @Test
    void shouldStartADayWhoseMidnightIsInAGapWhenTheGapEnds() {
        // Sao Paulo, 2018-11-04: clocks jumped from 00:00 -03 to 01:00 -02, so the day starts at 01:00 -02.
        StatsPeriod saoPaulo = resolve("America/Sao_Paulo", "2018-11-03", "2018-11-05");
        // Santiago, 2026-09-06: clocks jump from 00:00 -04 to 01:00 -03.
        StatsPeriod santiago = resolve("America/Santiago", "2026-09-05", "2026-09-07");

        assertThat(saoPaulo.dayStarts().get(1)).isEqualTo(Instant.parse("2018-11-04T03:00:00Z"));
        assertThat(lengthOfDay(saoPaulo, 1)).isEqualTo(Duration.ofHours(23));
        assertThat(lengthOfDay(santiago, 1)).isEqualTo(Duration.ofHours(23));
    }

    @Test
    void shouldGiveTheSkippedSamoaDateTheSameStartAsTheNextDate() {
        StatsPeriod period = resolve("Pacific/Apia", "2011-12-29", "2011-12-31");

        assertThat(period.dayStarts()).hasSize(3);
        // 2011-12-30 does not exist in Samoa: its start equals the start of 2011-12-31.
        assertThat(period.dayStarts().get(1)).isEqualTo(period.dayStarts().get(2));
        assertThat(period.dayStarts().get(2)).isEqualTo(Instant.parse("2011-12-30T10:00:00Z"));
        assertThat(lengthOfDay(period, 1)).isZero();
        assertThat(lengthOfDay(period, 0)).isEqualTo(Duration.ofHours(24));
    }

    @Test
    void shouldComputeFractionalOffsetDayStartsInKathmandu() {
        StatsPeriod period = resolve("Asia/Kathmandu", "2026-03-01", "2026-03-02");

        assertThat(period.dayStarts()).containsExactly(Instant.parse("2026-02-28T18:15:00Z"),
                Instant.parse("2026-03-01T18:15:00Z"));
        assertThat(period.end()).isEqualTo(Instant.parse("2026-03-02T18:15:00Z"));
    }

    @Test
    void shouldKeepDayStartsNonDecreasingWithOneEntryPerDateAndAnEndAfterTheLast() {
        for (String zone : List.of("UTC", "America/New_York", "Australia/Lord_Howe", "Pacific/Apia",
                "America/Sao_Paulo", "Europe/London")) {
            StatsPeriod period = resolve(zone, "2011-01-01", "2011-12-31");

            assertThat(period.dayStarts()).hasSize(365).isSorted();
            assertThat(period.end()).isAfter(period.dayStarts().getLast());
        }
    }

    // ---- densify (D19, AC8)

    @Test
    void shouldFillEveryDayWithZeroWhenThereAreNoRows() {
        StatsPeriod period = resolve("UTC", "2026-03-01", "2026-03-03");

        assertThat(period.densify(List.of())).containsExactly(new DailyClicks(date("2026-03-01"), 0),
                new DailyClicks(date("2026-03-02"), 0), new DailyClicks(date("2026-03-03"), 0));
    }

    @Test
    void shouldFillTheGapDayBetweenTwoDaysWithClicksInAscendingOrder() {
        StatsPeriod period = resolve("UTC", "2026-03-01", "2026-03-03");

        List<DailyClicks> daily = period.densify(List.of(new DayCount(3, 5), new DayCount(1, 1)));

        assertThat(daily).containsExactly(new DailyClicks(date("2026-03-01"), 1),
                new DailyClicks(date("2026-03-02"), 0), new DailyClicks(date("2026-03-03"), 5));
    }

    @Test
    void shouldListASkippedDateWithZeroClicks() {
        StatsPeriod period = resolve("Pacific/Apia", "2011-12-29", "2011-12-31");

        // The equal thresholds send a click to the last of them (index 3), so index 2 is absent.
        List<DailyClicks> daily = period.densify(List.of(new DayCount(1, 2), new DayCount(3, 4)));

        assertThat(daily).containsExactly(new DailyClicks(date("2011-12-29"), 2),
                new DailyClicks(date("2011-12-30"), 0), new DailyClicks(date("2011-12-31"), 4));
    }

    @Test
    void shouldThrowWhenABucketIndexIsOutsideOneToN() {
        StatsPeriod period = resolve("UTC", "2026-03-01", "2026-03-03");

        assertThatThrownBy(() -> period.densify(List.of(new DayCount(0, 1))))
                .isExactlyInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> period.densify(List.of(new DayCount(4, 1))))
                .isExactlyInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> period.densify(List.of(new DayCount(-1, 1))))
                .isExactlyInstanceOf(IllegalStateException.class);
        // Positive control: the edge indexes 1 and N are accepted.
        assertThat(period.densify(List.of(new DayCount(1, 1), new DayCount(3, 1))))
                .extracting(DailyClicks::clicks).containsExactly(1L, 0L, 1L);
    }

    @Test
    void shouldSumTheDailyCountsIntoClicksInRange() {
        StatsPeriod period = resolve("UTC", "2026-03-01", "2026-03-03");
        List<DailyClicks> daily = period.densify(List.of(new DayCount(1, 1), new DayCount(2, 4), new DayCount(3, 1)));

        ShortUrlStats stats = ShortUrlStats.of("Abc1234", period, 100L, null, daily);

        assertThat(stats.clicksInRange()).isEqualTo(6L);
        assertThat(stats.totalClicks()).isEqualTo(100L);
        assertThat(stats.zoneId()).isEqualTo("UTC");
        assertThat(stats.from()).isEqualTo(date("2026-03-01"));
        assertThat(stats.to()).isEqualTo(date("2026-03-03"));
        assertThat(stats.lastAccessedAt()).isNull();
    }
}
