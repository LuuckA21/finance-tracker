package me.luucka.finance.core.recurrence;

import java.time.temporal.ChronoUnit;

/**
 * How often a recurring entry repeats.
 */
public enum Frequency {
    DAILY(ChronoUnit.DAYS, 1),
    WEEKLY(ChronoUnit.WEEKS, 1),
    MONTHLY(ChronoUnit.MONTHS, 1),
    /** Every 3 months. */
    QUARTERLY(ChronoUnit.MONTHS, 3),
    /** Every 4 months. */
    FOUR_MONTHLY(ChronoUnit.MONTHS, 4),
    /** Every 6 months. */
    SEMIANNUAL(ChronoUnit.MONTHS, 6),
    YEARLY(ChronoUnit.MONTHS, 12);

    private final ChronoUnit unit;
    private final int step;

    Frequency(ChronoUnit unit, int step) {
        this.unit = unit;
        this.step = step;
    }

    ChronoUnit unit() {
        return unit;
    }

    int step() {
        return step;
    }
}
