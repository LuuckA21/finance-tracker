package me.luucka.finance.core.recurrence;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Dates on which a recurring entry falls.
 *
 * <p>The n-th occurrence is always computed from the start date ({@code start + n * period}), never
 * from the previous occurrence: a monthly entry starting on 31 January falls on 28/29 February and
 * back on 31 March, instead of drifting to the 28th for good.
 *
 * <p>Generation remembers only the last date it produced, so occurrences missed while the server was
 * down are caught up, and entries the user deleted are not created again.
 */
public record RecurrenceSchedule(LocalDate start, LocalDate end, Frequency frequency) {

    public RecurrenceSchedule {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(frequency, "frequency");
        if (end != null && end.isBefore(start)) {
            throw new IllegalArgumentException("end before start");
        }
    }

    /** The n-th occurrence (0 = the start date). */
    public LocalDate occurrence(long n) {
        return start.plus(n * frequency.step(), frequency.unit());
    }

    /** First occurrence strictly after {@code after} (or the first one when null); null past the end. */
    public LocalDate nextAfter(LocalDate after) {
        long n = 0;
        if (after != null && !after.isBefore(start)) {
            // Jump close to the answer instead of walking a long daily history one day at a time
            n = Math.max(0, frequency.unit().between(start, after) / frequency.step() - 1);
        }
        LocalDate date = occurrence(n);
        while (after != null && !date.isAfter(after)) {
            date = occurrence(++n);
        }
        return end != null && date.isAfter(end) ? null : date;
    }

    /** Occurrences after {@code lastGenerated} up to and including {@code today}, oldest first. */
    public List<LocalDate> due(LocalDate lastGenerated, LocalDate today) {
        List<LocalDate> dates = new ArrayList<>();
        LocalDate date = nextAfter(lastGenerated);
        while (date != null && !date.isAfter(today)) {
            dates.add(date);
            date = nextAfter(date);
        }
        return dates;
    }
}
