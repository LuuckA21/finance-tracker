package me.luucka.finance.core.cashflow;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedSet;
import java.util.TreeSet;

import me.luucka.finance.core.Money;
import me.luucka.finance.core.fx.FxTable;

/**
 * One category's income or expenses month by month in a year and in the year before, in the base
 * currency, split by the categories the entries are on (a macro's details and its own entries).
 * An entry whose currency cannot be converted is left out and its currency reported.
 */
public final class CategoryTrend {

    /** An entry of the category (or of one of its details), in its own currency. */
    public record Entry(LocalDate date, long categoryId, BigDecimal amount, String currency) {
    }

    /** A month: the year's amount, the same month a year earlier, and the year's amount per category. */
    public record Month(int month, BigDecimal amount, BigDecimal previous, Map<Long, BigDecimal> parts) {
    }

    /** One of the categories the entries are on, over the year and the year before. */
    public record Part(long categoryId, BigDecimal amount, BigDecimal previous) {
    }

    /**
     * @param lastMonth      the last month the year has had so far: 12 for a past year, the current
     *                       month for this year, 0 for a future one
     * @param toDate         the year's amount up to {@code lastMonth}
     * @param previousToDate the year before's amount over the same months
     * @param parts          largest first
     */
    public record Result(List<Month> months, List<Part> parts, BigDecimal total, BigDecimal previousTotal,
                         int lastMonth, BigDecimal toDate, BigDecimal previousToDate,
                         SortedSet<String> unconvertedCurrencies) {
    }

    private CategoryTrend() {
    }

    public static Result of(Collection<Entry> entries, FxTable fx, int year, LocalDate today) {
        BigDecimal[] current = zeros();
        BigDecimal[] previous = zeros();
        List<Map<Long, BigDecimal>> monthParts = new ArrayList<>();
        for (int m = 0; m < 12; m++) {
            monthParts.add(new LinkedHashMap<>());
        }
        Map<Long, BigDecimal[]> parts = new HashMap<>();
        SortedSet<String> unconverted = new TreeSet<>();
        for (Entry entry : entries) {
            int entryYear = entry.date().getYear();
            if (entryYear != year && entryYear != year - 1) {
                continue;
            }
            Optional<BigDecimal> value = fx.toBase(entry.amount(), entry.currency(), entry.date());
            if (value.isEmpty()) {
                unconverted.add(entry.currency());
                continue;
            }
            int m = entry.date().getMonthValue() - 1;
            BigDecimal[] part = parts.computeIfAbsent(entry.categoryId(), id -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
            if (entryYear == year) {
                current[m] = current[m].add(value.get(), Money.CONTEXT);
                monthParts.get(m).merge(entry.categoryId(), value.get(), (a, b) -> a.add(b, Money.CONTEXT));
                part[0] = part[0].add(value.get(), Money.CONTEXT);
            } else {
                previous[m] = previous[m].add(value.get(), Money.CONTEXT);
                part[1] = part[1].add(value.get(), Money.CONTEXT);
            }
        }

        int lastMonth = year < today.getYear() ? 12 : year == today.getYear() ? today.getMonthValue() : 0;
        List<Month> months = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal previousTotal = BigDecimal.ZERO;
        BigDecimal toDate = BigDecimal.ZERO;
        BigDecimal previousToDate = BigDecimal.ZERO;
        for (int m = 0; m < 12; m++) {
            Map<Long, BigDecimal> rounded = new LinkedHashMap<>();
            monthParts.get(m).forEach((id, amount) -> rounded.put(id, Money.round(amount)));
            months.add(new Month(m + 1, Money.round(current[m]), Money.round(previous[m]), rounded));
            total = total.add(current[m], Money.CONTEXT);
            previousTotal = previousTotal.add(previous[m], Money.CONTEXT);
            if (m < lastMonth) {
                toDate = toDate.add(current[m], Money.CONTEXT);
                previousToDate = previousToDate.add(previous[m], Money.CONTEXT);
            }
        }
        List<Part> partList = parts.entrySet().stream()
                .map(e -> new Part(e.getKey(), Money.round(e.getValue()[0]), Money.round(e.getValue()[1])))
                .sorted(Comparator.comparing(Part::amount).reversed()
                        .thenComparing(Comparator.comparing(Part::previous).reversed())
                        .thenComparingLong(Part::categoryId))
                .toList();
        return new Result(months, partList, Money.round(total), Money.round(previousTotal), lastMonth,
                Money.round(toDate), Money.round(previousToDate), unconverted);
    }

    private static BigDecimal[] zeros() {
        BigDecimal[] values = new BigDecimal[12];
        Arrays.fill(values, BigDecimal.ZERO);
        return values;
    }
}
