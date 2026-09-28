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
 * Compares a month's expenses with the monthly budgets, in the base currency.
 * <p>
 * Only expenses count (income and transfers are left out by the caller). A budget in another
 * currency is converted at the end of the month, or today for the current month.
 */
public final class BudgetCalculator {

    /** Share of the budget from which a category is flagged as close to its limit. */
    public static final BigDecimal WARNING_PERCENT = BigDecimal.valueOf(80);
    /** Full months before the selected one used for the spending average (budget suggestion). */
    public static final int AVERAGE_MONTHS = 3;

    public enum State { OK, WARNING, OVER }

    public record BudgetLine(long categoryId, BigDecimal amount, String currency) {
    }

    /**
     * An expense; {@code recurring} when a recurring rule created it (rent, subscriptions): such
     * amounts are not extrapolated in the end-of-month projection.
     */
    public record Expense(LocalDate date, BigDecimal amount, String currency, long categoryId, boolean recurring) {
    }

    /**
     * @param budget    limit in the base currency, null when its currency cannot be converted
     * @param percent   spent / budget × 100, null without a convertible budget
     * @param projected current month only: recurring expenses as booked plus the other spending
     *                  extrapolated to the end of the month
     * @param average   average monthly spending of the previous {@link #AVERAGE_MONTHS} months
     */
    public record CategoryStatus(long categoryId, BigDecimal budget, BigDecimal spent, BigDecimal remaining,
                                 BigDecimal percent, State state, BigDecimal projected, BigDecimal average) {
    }

    /** Spending in a category without a budget. */
    public record Unbudgeted(long categoryId, BigDecimal spent, BigDecimal average) {
    }

    /**
     * @param budgeted   sum of the convertible budgets
     * @param spent      spending in categories with a budget
     * @param unbudgeted spending in categories without one
     */
    public record MonthStatus(YearMonth month, BigDecimal budgeted, BigDecimal spent, BigDecimal remaining,
                              BigDecimal unbudgeted, List<CategoryStatus> categories, List<Unbudgeted> others,
                              SortedSet<String> unconvertedCurrencies) {
    }

    private BudgetCalculator() {
    }

    /**
     * @param expenses expenses of the month and of the {@link #AVERAGE_MONTHS} months before it
     *                 (others are ignored)
     */
    public static MonthStatus month(Collection<BudgetLine> budgets, Collection<Expense> expenses, FxTable fx,
                                    YearMonth month, LocalDate today) {
        YearMonth averageFrom = month.minusMonths(AVERAGE_MONTHS);
        Map<Long, BigDecimal> spent = new HashMap<>();
        Map<Long, BigDecimal> spentRecurring = new HashMap<>();
        Map<Long, BigDecimal> previous = new HashMap<>();
        SortedSet<String> unconverted = new TreeSet<>();

        for (Expense entry : expenses) {
            YearMonth entryMonth = YearMonth.from(entry.date());
            Map<Long, BigDecimal> target = entryMonth.equals(month) ? spent
                    : !entryMonth.isBefore(averageFrom) && entryMonth.isBefore(month) ? previous : null;
            if (target == null) {
                continue;
            }
            Optional<BigDecimal> value = fx.toBase(entry.amount(), entry.currency(), entry.date());
            if (value.isEmpty()) {
                unconverted.add(entry.currency());
                continue;
            }
            target.merge(entry.categoryId(), value.get(), (a, b) -> a.add(b, Money.CONTEXT));
            if (target == spent && entry.recurring()) {
                spentRecurring.merge(entry.categoryId(), value.get(), (a, b) -> a.add(b, Money.CONTEXT));
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
            BigDecimal categorySpent = spent.getOrDefault(line.categoryId(), BigDecimal.ZERO);
            totalSpent = totalSpent.add(categorySpent, Money.CONTEXT);
            BigDecimal limit = fx.toBase(line.amount(), line.currency(), rateDate).orElse(null);
            if (limit == null) {
                unconverted.add(line.currency());
            } else {
                totalBudget = totalBudget.add(limit, Money.CONTEXT);
            }
            BigDecimal percent = limit == null ? null : percent(categorySpent, limit);
            BigDecimal projected = current
                    ? project(categorySpent, spentRecurring.getOrDefault(line.categoryId(), BigDecimal.ZERO), today)
                    : null;
            categories.add(new CategoryStatus(line.categoryId(),
                    limit == null ? null : Money.round(limit),
                    Money.round(categorySpent),
                    limit == null ? null : Money.round(limit.subtract(categorySpent)),
                    percent,
                    state(percent),
                    projected == null ? null : Money.round(projected),
                    average(previous.get(line.categoryId()))));
        }
        // Most used budgets first, so what needs attention is on top
        categories.sort((a, b) -> compareNullsLast(b.percent(), a.percent()));

        BigDecimal totalUnbudgeted = BigDecimal.ZERO;
        List<Unbudgeted> others = new ArrayList<>();
        Set<Long> seen = new HashSet<>(spent.keySet());
        seen.addAll(previous.keySet());
        for (Long categoryId : seen) {
            if (budgeted.contains(categoryId)) {
                continue;
            }
            BigDecimal categorySpent = spent.getOrDefault(categoryId, BigDecimal.ZERO);
            totalUnbudgeted = totalUnbudgeted.add(categorySpent, Money.CONTEXT);
            others.add(new Unbudgeted(categoryId, Money.round(categorySpent), average(previous.get(categoryId))));
        }
        others.sort((a, b) -> b.spent().compareTo(a.spent()) != 0 ? b.spent().compareTo(a.spent())
                : b.average().compareTo(a.average()));

        return new MonthStatus(month, Money.round(totalBudget), Money.round(totalSpent),
                Money.round(totalBudget.subtract(totalSpent)), Money.round(totalUnbudgeted), categories, others,
                unconverted);
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
     * Recurring spending as booked (it will not repeat this month) plus the rest scaled to the
     * whole month (days elapsed, today included).
     */
    private static BigDecimal project(BigDecimal spent, BigDecimal recurring, LocalDate today) {
        BigDecimal variable = spent.subtract(recurring, Money.CONTEXT);
        return recurring.add(variable.multiply(BigDecimal.valueOf(today.lengthOfMonth()))
                .divide(BigDecimal.valueOf(today.getDayOfMonth()), Money.CONTEXT), Money.CONTEXT);
    }

    private static BigDecimal average(BigDecimal total) {
        return total == null ? Money.round(BigDecimal.ZERO)
                : Money.round(total.divide(BigDecimal.valueOf(AVERAGE_MONTHS), Money.CONTEXT));
    }

    private static int compareNullsLast(BigDecimal a, BigDecimal b) {
        if (a == null || b == null) {
            return a == null ? (b == null ? 0 : -1) : 1;
        }
        return a.compareTo(b);
    }
}
