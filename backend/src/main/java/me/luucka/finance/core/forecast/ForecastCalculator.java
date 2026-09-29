package me.luucka.finance.core.forecast;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import me.luucka.finance.core.EntryKind;
import me.luucka.finance.core.Money;
import me.luucka.finance.core.cashflow.CashflowTotals;

/**
 * Income and expenses of a coming year, month by month: each month of a base period (twelve months)
 * with a growth in percent, plus extra items such as a new rent. Everything in the base currency;
 * transfers are not part of it, as in the cash flow.
 */
public final class ForecastCalculator {

    /** How an extra item repeats within the forecast year. */
    public enum Schedule {
        /** Every month from {@code startMonth} to {@code endMonth} (December when null) */
        MONTHLY,
        /** Once, in {@code startMonth} */
        ONCE
    }

    /**
     * One income or expense of the base period, already converted.
     *
     * @param value positive, as entries are
     */
    public record BaseAmount(YearMonth month, long categoryId, EntryKind kind, BigDecimal value) {
    }

    /**
     * An amount added to the forecast; negative to take something away (a subscription cancelled).
     *
     * @param categoryId null when the item stands on its own
     * @param startMonth 1 to 12
     * @param endMonth   1 to 12, not before {@code startMonth}; null for December; ignored for {@code ONCE}
     */
    public record Item(EntryKind kind, Long categoryId, BigDecimal amount, Schedule schedule, int startMonth,
                       Integer endMonth) {

        boolean fallsIn(int month) {
            return schedule == Schedule.ONCE
                    ? month == startMonth
                    : month >= startMonth && month <= (endMonth == null ? 12 : endMonth);
        }
    }

    /** Growth in percent over the base period: 3 for +3%. */
    public record Scenario(int year, BigDecimal incomeGrowth, BigDecimal expenseGrowth, List<Item> items) {
    }

    /** Twelve consecutive months, both included. */
    public record Period(YearMonth from, YearMonth to) {

        /** The month of the period that falls in the given calendar month (1 to 12). */
        YearMonth monthOf(int month) {
            return from.getMonthValue() <= month ? from.withMonth(month) : to.withMonth(month);
        }

        public boolean contains(YearMonth month) {
            return !month.isBefore(from) && !month.isAfter(to);
        }
    }

    /** One month of the forecast next to the same calendar month of the base period. */
    public record MonthResult(int month, CashflowTotals base, CashflowTotals forecast) {
    }

    /** A category over the year: the base, and the forecast with its growth and its items. */
    public record CategoryResult(long categoryId, EntryKind kind, BigDecimal base, BigDecimal forecast) {
    }

    /**
     * @param fromGrowth   what the percentages add over the base, income and expenses (transferred 0)
     * @param fromItems    what the extra items add, income and expenses
     * @param categories   largest forecast first
     * @param itemTotals   each item's total in the year, in the order given
     */
    public record Result(Period base, List<MonthResult> months, CashflowTotals baseTotals,
                         CashflowTotals forecastTotals, CashflowTotals fromGrowth, CashflowTotals fromItems,
                         List<CategoryResult> categories, List<BigDecimal> itemTotals) {
    }

    private ForecastCalculator() {
    }

    /**
     * The year before the forecast once it is over; until then the last twelve complete months,
     * which is the same year at its end.
     */
    public static Period basePeriod(int year, LocalDate today) {
        if (today.getYear() >= year) {
            return new Period(YearMonth.of(year - 1, 1), YearMonth.of(year - 1, 12));
        }
        YearMonth last = YearMonth.from(today).minusMonths(1);
        return new Period(last.minusMonths(11), last);
    }

    public static Result forecast(Scenario scenario, Period base, List<BaseAmount> amounts) {
        BigDecimal incomeFactor = factor(scenario.incomeGrowth());
        BigDecimal expenseFactor = factor(scenario.expenseGrowth());

        Map<YearMonth, BigDecimal[]> baseByMonth = new HashMap<>();
        Map<CategoryKey, BigDecimal> baseByCategory = new HashMap<>();
        for (BaseAmount a : amounts) {
            if (a.kind() == EntryKind.TRANSFER || !base.contains(a.month())) {
                continue;
            }
            BigDecimal[] sums = baseByMonth.computeIfAbsent(a.month(), m -> zeros());
            int index = a.kind() == EntryKind.INCOME ? 0 : 1;
            sums[index] = sums[index].add(a.value(), Money.CONTEXT);
            baseByCategory.merge(new CategoryKey(a.categoryId(), a.kind()), a.value(), ForecastCalculator::add);
        }

        List<MonthResult> months = new ArrayList<>(12);
        BigDecimal[] baseYear = zeros();
        BigDecimal[] forecastYear = zeros();
        BigDecimal[] itemsYear = zeros();
        BigDecimal[] itemTotals = new BigDecimal[scenario.items().size()];
        Arrays.fill(itemTotals, BigDecimal.ZERO);
        Map<CategoryKey, BigDecimal> itemsByCategory = new HashMap<>();
        for (int month = 1; month <= 12; month++) {
            BigDecimal[] b = baseByMonth.getOrDefault(base.monthOf(month), zeros());
            BigDecimal[] items = zeros();
            for (int i = 0; i < scenario.items().size(); i++) {
                Item item = scenario.items().get(i);
                if (item.kind() == EntryKind.TRANSFER || !item.fallsIn(month)) {
                    continue;
                }
                int index = item.kind() == EntryKind.INCOME ? 0 : 1;
                items[index] = items[index].add(item.amount(), Money.CONTEXT);
                itemTotals[i] = itemTotals[i].add(item.amount(), Money.CONTEXT);
                if (item.categoryId() != null) {
                    itemsByCategory.merge(new CategoryKey(item.categoryId(), item.kind()), item.amount(),
                            ForecastCalculator::add);
                }
            }
            BigDecimal income = b[0].multiply(incomeFactor, Money.CONTEXT).add(items[0], Money.CONTEXT);
            BigDecimal expense = b[1].multiply(expenseFactor, Money.CONTEXT).add(items[1], Money.CONTEXT);
            months.add(new MonthResult(month, CashflowTotals.of(b[0], b[1], BigDecimal.ZERO),
                    CashflowTotals.of(income, expense, BigDecimal.ZERO)));
            for (int k = 0; k < 2; k++) {
                baseYear[k] = baseYear[k].add(b[k], Money.CONTEXT);
                itemsYear[k] = itemsYear[k].add(items[k], Money.CONTEXT);
            }
            forecastYear[0] = forecastYear[0].add(income, Money.CONTEXT);
            forecastYear[1] = forecastYear[1].add(expense, Money.CONTEXT);
        }

        BigDecimal growthIncome = baseYear[0].multiply(incomeFactor.subtract(BigDecimal.ONE), Money.CONTEXT);
        BigDecimal growthExpense = baseYear[1].multiply(expenseFactor.subtract(BigDecimal.ONE), Money.CONTEXT);

        Map<CategoryKey, BigDecimal[]> categories = new HashMap<>();
        baseByCategory.forEach((key, value) -> {
            BigDecimal[] row = categories.computeIfAbsent(key, k -> zeros());
            row[0] = value;
            row[1] = value.multiply(key.kind() == EntryKind.INCOME ? incomeFactor : expenseFactor, Money.CONTEXT);
        });
        itemsByCategory.forEach((key, value) -> {
            BigDecimal[] row = categories.computeIfAbsent(key, k -> zeros());
            row[1] = row[1].add(value, Money.CONTEXT);
        });
        List<CategoryResult> categoryResults = categories.entrySet().stream()
                .map(e -> new CategoryResult(e.getKey().categoryId(), e.getKey().kind(), Money.round(e.getValue()[0]),
                        Money.round(e.getValue()[1])))
                .sorted(Comparator.comparing(CategoryResult::forecast).reversed()
                        .thenComparing(CategoryResult::categoryId))
                .toList();

        return new Result(base, months, CashflowTotals.of(baseYear[0], baseYear[1], BigDecimal.ZERO),
                CashflowTotals.of(forecastYear[0], forecastYear[1], BigDecimal.ZERO),
                CashflowTotals.of(growthIncome, growthExpense, BigDecimal.ZERO),
                CashflowTotals.of(itemsYear[0], itemsYear[1], BigDecimal.ZERO), categoryResults,
                Arrays.stream(itemTotals).map(Money::round).toList());
    }

    private record CategoryKey(long categoryId, EntryKind kind) {
    }

    /** 1 + growth / 100 */
    private static BigDecimal factor(BigDecimal growth) {
        return BigDecimal.ONE.add(growth.divide(BigDecimal.valueOf(100), Money.CONTEXT), Money.CONTEXT);
    }

    private static BigDecimal[] zeros() {
        return new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO};
    }

    private static BigDecimal add(BigDecimal a, BigDecimal b) {
        return a.add(b, Money.CONTEXT);
    }
}
