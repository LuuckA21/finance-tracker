package me.luucka.finance.core.cashflow;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

import me.luucka.finance.core.AssetClass;
import me.luucka.finance.core.EntryKind;
import me.luucka.finance.core.Money;
import me.luucka.finance.core.fx.FxTable;

/**
 * Aggregates cash-flow entries into monthly and yearly totals in the base currency.
 * <p>
 * Entries whose currency has no known exchange rate are skipped and reported in
 * {@code unconvertedCurrencies}, so the UI can ask the user to add a rate.
 */
public final class CashflowCalculator {

    private CashflowCalculator() {
    }

    /** Totals for one month of a year. */
    public record MonthResult(int month, CashflowTotals totals) {
    }

    /** Totals for one category. */
    public record CategoryResult(long categoryId, EntryKind kind, BigDecimal amount) {
    }

    /** Transfers of a year by the asset class they went to; {@code destination} null when not given. */
    public record TransferResult(AssetClass destination, BigDecimal amount) {
    }

    /** Monthly breakdown of one year. */
    public record YearResult(
            int year,
            List<MonthResult> months,
            CashflowTotals totals,
            List<CategoryResult> byCategory,
            List<TransferResult> transfers,
            SortedSet<String> unconvertedCurrencies) {
    }

    /** Totals of a single year, for multi-year comparisons. */
    public record YearSummary(int year, CashflowTotals totals) {
    }

    /** Totals for every year that has entries. */
    public record MultiYearResult(List<YearSummary> years, SortedSet<String> unconvertedCurrencies) {
    }

    /**
     * Computes the monthly breakdown of {@code year}. Entries outside the year are ignored.
     */
    public static YearResult year(Collection<CashflowEntry> entries, FxTable fx, int year) {
        BigDecimal[] income = zeros(12);
        BigDecimal[] expense = zeros(12);
        BigDecimal[] transferred = zeros(12);
        Map<Long, BigDecimal> incomeByCategory = new HashMap<>();
        Map<Optional<AssetClass>, BigDecimal> transfersByDestination = new HashMap<>();
        Map<Long, BigDecimal> expenseByCategory = new HashMap<>();
        SortedSet<String> unconverted = new TreeSet<>();

        for (CashflowEntry entry : entries) {
            if (entry.date().getYear() != year) {
                continue;
            }
            Optional<BigDecimal> converted = fx.toBase(entry.amount(), entry.currency(), entry.date());
            if (converted.isEmpty()) {
                unconverted.add(entry.currency());
                continue;
            }
            BigDecimal value = converted.get();
            int idx = entry.date().getMonthValue() - 1;
            switch (entry.kind()) {
                case INCOME -> {
                    income[idx] = income[idx].add(value, Money.CONTEXT);
                    incomeByCategory.merge(entry.categoryId(), value, (a, b) -> a.add(b, Money.CONTEXT));
                }
                case EXPENSE -> {
                    expense[idx] = expense[idx].add(value, Money.CONTEXT);
                    expenseByCategory.merge(entry.categoryId(), value, (a, b) -> a.add(b, Money.CONTEXT));
                }
                case TRANSFER -> {
                    transferred[idx] = transferred[idx].add(value, Money.CONTEXT);
                    transfersByDestination.merge(Optional.ofNullable(entry.destination()), value,
                            (a, b) -> a.add(b, Money.CONTEXT));
                }
            }
        }

        List<MonthResult> months = new ArrayList<>(12);
        BigDecimal totalIncome = BigDecimal.ZERO;
        BigDecimal totalExpense = BigDecimal.ZERO;
        BigDecimal totalTransferred = BigDecimal.ZERO;
        for (int m = 0; m < 12; m++) {
            months.add(new MonthResult(m + 1, CashflowTotals.of(income[m], expense[m], transferred[m])));
            totalIncome = totalIncome.add(income[m], Money.CONTEXT);
            totalExpense = totalExpense.add(expense[m], Money.CONTEXT);
            totalTransferred = totalTransferred.add(transferred[m], Money.CONTEXT);
        }

        List<CategoryResult> byCategory = new ArrayList<>();
        incomeByCategory.forEach((id, amount) ->
                byCategory.add(new CategoryResult(id, EntryKind.INCOME, Money.round(amount))));
        expenseByCategory.forEach((id, amount) ->
                byCategory.add(new CategoryResult(id, EntryKind.EXPENSE, Money.round(amount))));
        byCategory.sort((a, b) -> b.amount().compareTo(a.amount()));

        List<TransferResult> transfers = new ArrayList<>();
        transfersByDestination.forEach((destination, amount) ->
                transfers.add(new TransferResult(destination.orElse(null), Money.round(amount))));
        transfers.sort((a, b) -> b.amount().compareTo(a.amount()));

        return new YearResult(year, months, CashflowTotals.of(totalIncome, totalExpense, totalTransferred),
                byCategory, transfers, unconverted);
    }

    /**
     * Computes yearly totals for every year that has at least one entry, oldest first.
     */
    public static MultiYearResult years(Collection<DatedAmount> entries, FxTable fx) {
        TreeMap<Integer, BigDecimal[]> perYear = new TreeMap<>();
        SortedSet<String> unconverted = new TreeSet<>();
        for (DatedAmount entry : entries) {
            LocalDate date = entry.date();
            BigDecimal[] sums = perYear.computeIfAbsent(date.getYear(), y -> zeros(3));
            Optional<BigDecimal> converted = fx.toBase(entry.amount(), entry.currency(), date);
            if (converted.isEmpty()) {
                unconverted.add(entry.currency());
                continue;
            }
            int idx = switch (entry.kind()) {
                case INCOME -> 0;
                case EXPENSE -> 1;
                case TRANSFER -> 2;
            };
            sums[idx] = sums[idx].add(converted.get(), Money.CONTEXT);
        }
        List<YearSummary> years = new ArrayList<>(perYear.size());
        perYear.forEach((year, sums) ->
                years.add(new YearSummary(year, CashflowTotals.of(sums[0], sums[1], sums[2]))));
        return new MultiYearResult(years, unconverted);
    }

    private static BigDecimal[] zeros(int size) {
        BigDecimal[] array = new BigDecimal[size];
        java.util.Arrays.fill(array, BigDecimal.ZERO);
        return array;
    }
}
