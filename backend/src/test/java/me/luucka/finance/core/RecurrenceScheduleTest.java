package me.luucka.finance.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalDate;
import java.util.List;

import me.luucka.finance.core.recurrence.Frequency;
import me.luucka.finance.core.recurrence.RecurrenceSchedule;
import org.junit.jupiter.api.Test;

class RecurrenceScheduleTest {

    private static LocalDate d(String iso) {
        return LocalDate.parse(iso);
    }

    @Test
    void monthlyOnThe31stFollowsShortMonthsWithoutDrifting() {
        var s = new RecurrenceSchedule(d("2026-01-31"), null, Frequency.MONTHLY);
        assertEquals(List.of(d("2026-01-31"), d("2026-02-28"), d("2026-03-31"), d("2026-04-30")),
                s.due(null, d("2026-04-30")));
    }

    @Test
    void everyFrequencyStepsFromTheStart() {
        LocalDate start = d("2026-01-15");
        assertEquals(d("2026-01-16"), new RecurrenceSchedule(start, null, Frequency.DAILY).occurrence(1));
        assertEquals(d("2026-01-22"), new RecurrenceSchedule(start, null, Frequency.WEEKLY).occurrence(1));
        assertEquals(d("2026-02-15"), new RecurrenceSchedule(start, null, Frequency.MONTHLY).occurrence(1));
        assertEquals(d("2026-04-15"), new RecurrenceSchedule(start, null, Frequency.QUARTERLY).occurrence(1));
        assertEquals(d("2026-05-15"), new RecurrenceSchedule(start, null, Frequency.FOUR_MONTHLY).occurrence(1));
        assertEquals(d("2026-07-15"), new RecurrenceSchedule(start, null, Frequency.SEMIANNUAL).occurrence(1));
        assertEquals(d("2027-01-15"), new RecurrenceSchedule(start, null, Frequency.YEARLY).occurrence(1));
    }

    @Test
    void yearlyOnLeapDayComesBackInLeapYears() {
        var s = new RecurrenceSchedule(d("2028-02-29"), null, Frequency.YEARLY);
        assertEquals(List.of(d("2028-02-29"), d("2029-02-28"), d("2030-02-28"), d("2031-02-28"), d("2032-02-29")),
                s.due(null, d("2032-12-31")));
    }

    @Test
    void catchesUpMissedDatesAfterTheLastGenerated() {
        var s = new RecurrenceSchedule(d("2026-01-01"), null, Frequency.WEEKLY);
        assertEquals(List.of(d("2026-01-15"), d("2026-01-22")), s.due(d("2026-01-08"), d("2026-01-25")));
        assertEquals(List.of(), s.due(d("2026-01-22"), d("2026-01-25")));
    }

    @Test
    void stopsAtTheEndDate() {
        var s = new RecurrenceSchedule(d("2026-01-10"), d("2026-03-10"), Frequency.MONTHLY);
        assertEquals(List.of(d("2026-01-10"), d("2026-02-10"), d("2026-03-10")), s.due(null, d("2026-12-31")));
        assertNull(s.nextAfter(d("2026-03-10")));
    }

    @Test
    void futureStartHasNothingDueYet() {
        var s = new RecurrenceSchedule(d("2026-06-01"), null, Frequency.MONTHLY);
        assertEquals(List.of(), s.due(null, d("2026-05-31")));
        assertEquals(d("2026-06-01"), s.nextAfter(null));
    }

    @Test
    void nextAfterAnArbitraryDateAfterALongDailyHistory() {
        var s = new RecurrenceSchedule(d("2016-03-01"), null, Frequency.DAILY);
        assertEquals(d("2026-09-27"), s.nextAfter(d("2026-09-26")));
        var monthly = new RecurrenceSchedule(d("2020-01-31"), null, Frequency.MONTHLY);
        assertEquals(d("2026-02-28"), monthly.nextAfter(d("2026-02-10")));
        assertEquals(d("2026-03-31"), monthly.nextAfter(d("2026-02-28")));
    }

    @Test
    void endBeforeStartIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new RecurrenceSchedule(d("2026-02-01"), d("2026-01-01"), Frequency.MONTHLY));
    }
}
