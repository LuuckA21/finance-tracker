package me.luucka.finance.core.valuation;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds the list of dates at which net worth is sampled for charts.
 */
public final class ValuationDates {

    /** Hard cap on the number of points a single request may produce. */
    public static final int MAX_POINTS = 600;

    private ValuationDates() {
    }

    /**
     * One date per month from {@code from} to {@code to} (inclusive): the last day of the
     * month, or {@code today} for the current month. Months after {@code today} are skipped.
     */
    public static List<LocalDate> monthEnds(YearMonth from, YearMonth to, LocalDate today) {
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("'from' must not be after 'to'");
        }
        List<LocalDate> dates = new ArrayList<>();
        for (YearMonth ym = from; !ym.isAfter(to) && dates.size() < MAX_POINTS; ym = ym.plusMonths(1)) {
            LocalDate first = ym.atDay(1);
            if (first.isAfter(today)) {
                break;
            }
            LocalDate end = ym.atEndOfMonth();
            dates.add(end.isAfter(today) ? today : end);
        }
        return dates;
    }

    /**
     * One date per year from {@code fromYear} to {@code toYear} (inclusive): December 31st,
     * or {@code today} for the current year. Years after {@code today} are skipped.
     */
    public static List<LocalDate> yearEnds(int fromYear, int toYear, LocalDate today) {
        if (fromYear > toYear) {
            throw new IllegalArgumentException("'fromYear' must not be after 'toYear'");
        }
        List<LocalDate> dates = new ArrayList<>();
        for (int y = fromYear; y <= toYear && dates.size() < MAX_POINTS; y++) {
            if (y > today.getYear()) {
                break;
            }
            LocalDate end = LocalDate.of(y, 12, 31);
            dates.add(end.isAfter(today) ? today : end);
        }
        return dates;
    }
}
