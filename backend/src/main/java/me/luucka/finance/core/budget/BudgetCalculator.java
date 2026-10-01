package me.luucka.finance.core.budget;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

import me.luucka.finance.core.Money;
import me.luucka.finance.core.fx.FxTable;

/**
 * Compares a month's expenses with the budgets, in the base currency.
 * <p>
 * A monthly budget is compared with the month's spending; a quarterly or yearly one (calendar
 * quarters and years) with the spending of its period up to the end of the month, so a yearly
 * payment counts against the year rather than the month it falls in. Only expenses count (income
 * and transfers are left out by the caller). A budget in another currency is converted at the end
 * of the month, or today for the current month.
 */
public final class BudgetCalculator {

    /** Share of the budget from which a category is flagged as close to its limit. */
    public static final BigDecimal WARNING_PERCENT = BigDecimal.valueOf(80);
    /** Full months before the selected one used for the spending average (budget suggestion). */
    public static final int AVERAGE_MONTHS = 3;
    /**
     * Without spending in the previous {@link #AVERAGE_MONTHS} months, no projection before this day
     * of the month: a few days say too little to scale to a whole month.
     */
    public static final int FIRST_PROJECTION_DAY = 7;

    public enum State { OK, WARNING, OVER }

    /** How often a budget's limit starts again: every month, calendar quarter or calendar year. */
    public enum Period {
        MONTHLY(1), QUARTERLY(3), YEARLY(12);

        private final int months;

        Period(int months) {
            this.months = months;
        }

        public int months() {
            return months;
        }

        /** The first month of the period {@code month} falls in. */
        public YearMonth start(YearMonth month) {
            return YearMonth.of(month.getYear(), (month.getMonthValue() - 1) / months * months + 1);
        }

        /** The last month of the period {@code month} falls in. */
        public YearMonth end(YearMonth month) {
            return start(month).plusMonths(months - 1L);
        }
    }

    public record BudgetLine(long categoryId, BigDecimal amount, String currency, Period period) {
        public BudgetLine(long categoryId, BigDecimal amount, String currency) {
            this(categoryId, amount, currency, Period.MONTHLY);
        }
    }

    /**
     * An expense; {@code recurring} when a recurring rule created it (rent, subscriptions): such
     * amounts are not extrapolated in the end-of-month projection.
     */
    public record Expense(LocalDate date, BigDecimal amount, String currency, long categoryId, boolean recurring) {
    }

    /**
     * @param from      first month of the budget's period (the month itself for a monthly budget)
     * @param to        last month of the period
     * @param budget    limit in the base currency, null when its currency cannot be converted
     * @param spent     spending from the start of the period to the end of the month
     * @param monthSpent spending of the month alone (the same as {@code spent} for a monthly budget)
     * @param percent   spent / budget × 100, null without a convertible budget
     * @param projected monthly budgets in the current month only: recurring expenses as booked plus
     *                  the other spending carried to the end of the month at a pace that starts
     *                  from the previous months and follows the month's own as it goes by; null in
     *                  the first days of a category without history
     * @param average   average monthly spending of the previous {@link #AVERAGE_MONTHS} months
     * @param previous  spending of the whole period before (last month, quarter or year)
     */
    public record CategoryStatus(long categoryId, Period period, YearMonth from, YearMonth to, BigDecimal budget,
                                 BigDecimal spent, BigDecimal monthSpent, BigDecimal remaining,
                                 BigDecimal percent, State state, BigDecimal projected, BigDecimal average,
                                 BigDecimal previous) {
    }

    /** Spending in a category without a budget. */
    public record Unbudgeted(long categoryId, BigDecimal spent, BigDecimal average) {
    }

    /**
     * @param budgeted   sum of the convertible monthly budgets (quarterly and yearly ones are not
     *                   comparable with a month, so they stay out of the totals)
     * @param spent      the month's spending in categories with a monthly budget
     * @param unbudgeted the month's spending in categories without a budget
     */
    public record MonthStatus(YearMonth month, BigDecimal budgeted, BigDecimal spent, BigDecimal remaining,
                              BigDecimal unbudgeted, List<CategoryStatus> categories, List<Unbudgeted> others,
                              SortedSet<String> unconvertedCurrencies) {
    }

    private BudgetCalculator() {
    }

    /** The first month whose expenses {@link #month} needs: January of the year before. */
    public static YearMonth firstMonth(YearMonth month) {
        return YearMonth.of(month.getYear() - 1, 1);
    }

    /**
     * @param expenses expenses from {@link #firstMonth} to the end of the month (others are ignored)
     */
    public static MonthStatus month(Collection<BudgetLine> budgets, Collection<Expense> expenses, FxTable fx,
                                    YearMonth month, LocalDate today) {
        YearMonth averageFrom = month.minusMonths(AVERAGE_MONTHS);
        YearMonth first = firstMonth(month);
        // Older months only matter to the categories a quarter or a year looks back on
        Set<Long> longer = new HashSet<>();
        budgets.stream().filter(b -> b.period() != Period.MONTHLY).forEach(b -> longer.add(b.categoryId()));
        Map<Long, Map<YearMonth, BigDecimal>> byMonth = new HashMap<>();
        Map<Long, BigDecimal> spentRecurring = new HashMap<>();
        // Spending not created by recurring rules, by month: the usual pace of a projection
        Map<Long, Map<YearMonth, BigDecimal>> variableByMonth = new HashMap<>();
        SortedSet<String> unconverted = new TreeSet<>();

        for (Expense entry : expenses) {
            YearMonth entryMonth = YearMonth.from(entry.date());
            if (entryMonth.isAfter(month) || entryMonth.isBefore(first)
                    || entryMonth.isBefore(averageFrom) && !longer.contains(entry.categoryId())) {
                continue;
            }
            Optional<BigDecimal> value = fx.toBase(entry.amount(), entry.currency(), entry.date());
            if (value.isEmpty()) {
                unconverted.add(entry.currency());
                continue;
            }
            byMonth.computeIfAbsent(entry.categoryId(), k -> new HashMap<>())
                    .merge(entryMonth, value.get(), (a, b) -> a.add(b, Money.CONTEXT));
            if (entryMonth.equals(month) && entry.recurring()) {
                spentRecurring.merge(entry.categoryId(), value.get(), (a, b) -> a.add(b, Money.CONTEXT));
            }
            if (!entry.recurring()) {
                variableByMonth.computeIfAbsent(entry.categoryId(), k -> new HashMap<>())
                        .merge(entryMonth, value.get(), (a, b) -> a.add(b, Money.CONTEXT));
            }
        }

        boolean current = month.equals(YearMonth.from(today));
        LocalDate rateDate = current ? today : month.atEndOfMonth();
        BigDecimal totalBudget = BigDecimal.ZERO;
        BigDecimal totalSpent = BigDecimal.ZERO;
        Set<Long> budgeted = new HashSet<>();
        List<CategoryStatus> categories = new ArrayList<>();

        for (BudgetLine line : budgets) {
            budgeted.add(line.categoryId());
            Map<YearMonth, BigDecimal> months = byMonth.getOrDefault(line.categoryId(), Map.of());
            Period period = line.period();
            YearMonth from = period.start(month);
            BigDecimal categorySpent = sum(months, from, month);
            BigDecimal previous = sum(months, from.minusMonths(period.months()), from.minusMonths(1));
            BigDecimal limit = fx.toBase(line.amount(), line.currency(), rateDate).orElse(null);
            if (limit == null) {
                unconverted.add(line.currency());
            }
            if (period == Period.MONTHLY) {
                totalSpent = totalSpent.add(categorySpent, Money.CONTEXT);
                if (limit != null) {
                    totalBudget = totalBudget.add(limit, Money.CONTEXT);
                }
            }
            BigDecimal percent = limit == null ? null : percent(categorySpent, limit);
            BigDecimal history = sum(months, averageFrom, month.minusMonths(1));
            BigDecimal usual = history.signum() == 0 ? null
                    : sum(variableByMonth.getOrDefault(line.categoryId(), Map.of()), averageFrom, month.minusMonths(1))
                    .divide(BigDecimal.valueOf(AVERAGE_MONTHS), Money.CONTEXT);
            BigDecimal projected = current && period == Period.MONTHLY
                    ? project(categorySpent, spentRecurring.getOrDefault(line.categoryId(), BigDecimal.ZERO), usual, today)
                    : null;
            categories.add(new CategoryStatus(line.categoryId(), period, from, period.end(month),
                    limit == null ? null : Money.round(limit),
                    Money.round(categorySpent),
                    Money.round(months.getOrDefault(month, BigDecimal.ZERO)),
                    limit == null ? null : Money.round(limit.subtract(categorySpent)),
                    percent,
                    state(percent),
                    projected == null ? null : Money.round(projected),
                    average(history),
                    Money.round(previous)));
        }
        // Most used budgets first, so what needs attention is on top
        categories.sort((a, b) -> compareNullsLast(b.percent(), a.percent()));

        BigDecimal totalUnbudgeted = BigDecimal.ZERO;
        List<Unbudgeted> others = new ArrayList<>();
        for (Map.Entry<Long, Map<YearMonth, BigDecimal>> category : byMonth.entrySet()) {
            if (budgeted.contains(category.getKey())) {
                continue;
            }
            BigDecimal categorySpent = category.getValue().getOrDefault(month, BigDecimal.ZERO);
            totalUnbudgeted = totalUnbudgeted.add(categorySpent, Money.CONTEXT);
            others.add(new Unbudgeted(category.getKey(), Money.round(categorySpent),
                    average(sum(category.getValue(), averageFrom, month.minusMonths(1)))));
        }
        others.sort((a, b) -> b.spent().compareTo(a.spent()) != 0 ? b.spent().compareTo(a.spent())
                : b.average().compareTo(a.average()));

        return new MonthStatus(month, Money.round(totalBudget), Money.round(totalSpent),
                Money.round(totalBudget.subtract(totalSpent)), Money.round(totalUnbudgeted), categories, others,
                unconverted);
    }

    /** Spending of the months from {@code from} to {@code to}, both included. */
    private static BigDecimal sum(Map<YearMonth, BigDecimal> months, YearMonth from, YearMonth to) {
        BigDecimal total = BigDecimal.ZERO;
        for (Map.Entry<YearMonth, BigDecimal> m : months.entrySet()) {
            if (!m.getKey().isBefore(from) && !m.getKey().isAfter(to)) {
                total = total.add(m.getValue(), Money.CONTEXT);
            }
        }
        return total;
    }

    static State state(BigDecimal percent) {
        if (percent == null || percent.compareTo(WARNING_PERCENT) < 0) {
            return State.OK;
        }
        return percent.compareTo(BigDecimal.valueOf(100)) > 0 ? State.OVER : State.WARNING;
    }

    private static BigDecimal percent(BigDecimal part, BigDecimal total) {
        return part.multiply(BigDecimal.valueOf(100)).divide(total, 1, RoundingMode.HALF_EVEN);
    }

    /**
     * Recurring spending as booked (it will not repeat this month) plus the rest carried over the
     * days left. The pace of the rest counts a usual month (the average spending outside recurring
     * rules) as if it were as many days as the month has, beside the days already gone: on the 1st
     * it is almost the usual pace, by the end of the month almost the month's own, and a month spent
     * at the usual pace projects to the usual amount. Without history ({@code usual} null) the rest
     * is scaled to the whole month, from {@link #FIRST_PROJECTION_DAY} on.
     */
    public static BigDecimal project(BigDecimal spent, BigDecimal recurring, BigDecimal usual, LocalDate today) {
        BigDecimal variable = spent.subtract(recurring, Money.CONTEXT);
        int day = today.getDayOfMonth();
        int length = today.lengthOfMonth();
        if (usual == null) {
            if (day < FIRST_PROJECTION_DAY) {
                return null;
            }
            return recurring.add(variable.multiply(BigDecimal.valueOf(length))
                    .divide(BigDecimal.valueOf(day), Money.CONTEXT), Money.CONTEXT);
        }
        BigDecimal pace = variable.add(usual, Money.CONTEXT).divide(BigDecimal.valueOf((long) day + length), Money.CONTEXT);
        return spent.add(pace.multiply(BigDecimal.valueOf((long) length - day)), Money.CONTEXT);
    }

    private static BigDecimal average(BigDecimal total) {
        return Money.round(total.divide(BigDecimal.valueOf(AVERAGE_MONTHS), Money.CONTEXT));
    }

    private static int compareNullsLast(BigDecimal a, BigDecimal b) {
        if (a == null || b == null) {
            return a == null ? (b == null ? 0 : -1) : 1;
        }
        return a.compareTo(b);
    }
}
