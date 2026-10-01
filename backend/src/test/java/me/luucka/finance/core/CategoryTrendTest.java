package me.luucka.finance.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import me.luucka.finance.core.cashflow.CategoryTrend;
import me.luucka.finance.core.cashflow.CategoryTrend.Entry;
import me.luucka.finance.core.fx.FxTable;
import org.junit.jupiter.api.Test;

class CategoryTrendTest {

    private final FxTable fx = new FxTable("CHF").put("EUR", LocalDate.of(2025, 1, 1), new BigDecimal("0.95"));

    private static Entry entry(String date, long categoryId, String amount, String currency) {
        return new Entry(LocalDate.parse(date), categoryId, new BigDecimal(amount), currency);
    }

    private static BigDecimal chf(String amount) {
        return new BigDecimal(amount);
    }

    @Test
    void monthsOfTheYearAndTheYearBeforeSplitByCategory() {
        CategoryTrend.Result r = CategoryTrend.of(List.of(
                entry("2026-01-05", 2, "1800", "CHF"),
                entry("2026-01-20", 3, "100", "EUR"),
                entry("2026-03-01", 2, "1800", "CHF"),
                entry("2025-01-05", 2, "1700", "CHF"),
                entry("2025-12-05", 1, "40", "CHF"),
                entry("2024-12-31", 2, "999", "CHF"),
                entry("2026-02-01", 3, "10", "USD")), fx, 2026, LocalDate.of(2026, 9, 30));

        CategoryTrend.Month january = r.months().getFirst();
        assertEquals(chf("1895.00"), january.amount());
        assertEquals(chf("1700.00"), january.previous());
        assertEquals(Map.of(2L, chf("1800.00"), 3L, chf("95.00")), january.parts());
        assertEquals(chf("0.00"), r.months().get(1).amount());
        assertEquals(chf("40.00"), r.months().get(11).previous());
        assertEquals(12, r.months().size());

        assertEquals(chf("3695.00"), r.total());
        assertEquals(chf("1740.00"), r.previousTotal());
        assertEquals(List.of(2L, 3L, 1L), r.parts().stream().map(CategoryTrend.Part::categoryId).toList());
        assertEquals(chf("1700.00"), r.parts().getFirst().previous());
        assertEquals(Set.of("USD"), r.unconvertedCurrencies());
    }

    @Test
    void thisYearComparesWithTheSameMonthsOfTheYearBefore() {
        List<Entry> entries = List.of(entry("2026-02-01", 1, "50", "CHF"), entry("2025-02-01", 1, "40", "CHF"),
                entry("2025-11-01", 1, "500", "CHF"));
        CategoryTrend.Result current = CategoryTrend.of(entries, fx, 2026, LocalDate.of(2026, 9, 30));
        assertEquals(9, current.lastMonth());
        assertEquals(chf("50.00"), current.toDate());
        assertEquals(chf("40.00"), current.previousToDate());

        CategoryTrend.Result past = CategoryTrend.of(entries, fx, 2025, LocalDate.of(2026, 9, 30));
        assertEquals(12, past.lastMonth());
        assertEquals(chf("540.00"), past.toDate());

        assertEquals(0, CategoryTrend.of(entries, fx, 2027, LocalDate.of(2026, 9, 30)).lastMonth());
    }
}
