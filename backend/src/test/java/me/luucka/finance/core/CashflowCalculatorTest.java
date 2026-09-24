package me.luucka.finance.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import me.luucka.finance.core.cashflow.CashflowCalculator;
import me.luucka.finance.core.cashflow.CashflowEntry;
import me.luucka.finance.core.fx.FxTable;
import org.junit.jupiter.api.Test;

class CashflowCalculatorTest {

    private static final long SALARY = 1;
    private static final long RENT = 2;
    private static final long FOOD = 3;

    private final FxTable fx = new FxTable("CHF").put("EUR", LocalDate.of(2026, 1, 1), new BigDecimal("0.95"));

    private final List<CashflowEntry> entries = List.of(
            new CashflowEntry(LocalDate.of(2026, 1, 25), EntryKind.INCOME, new BigDecimal("6000"), "CHF", SALARY),
            new CashflowEntry(LocalDate.of(2026, 1, 1), EntryKind.EXPENSE, new BigDecimal("1800"), "CHF", RENT),
            new CashflowEntry(LocalDate.of(2026, 1, 10), EntryKind.EXPENSE, new BigDecimal("200"), "EUR", FOOD),
            new CashflowEntry(LocalDate.of(2026, 2, 25), EntryKind.INCOME, new BigDecimal("6000"), "CHF", SALARY),
            new CashflowEntry(LocalDate.of(2026, 2, 1), EntryKind.EXPENSE, new BigDecimal("1800"), "CHF", RENT),
            new CashflowEntry(LocalDate.of(2026, 3, 3), EntryKind.EXPENSE, new BigDecimal("50"), "USD", FOOD),
            new CashflowEntry(LocalDate.of(2025, 12, 25), EntryKind.INCOME, new BigDecimal("5500"), "CHF", SALARY));

    @Test
    void monthlyBreakdownConvertsToBaseCurrency() {
        CashflowCalculator.YearResult result = CashflowCalculator.year(entries, fx, 2026);

        assertEquals(12, result.months().size());
        var january = result.months().get(0).totals();
        assertEquals(new BigDecimal("6000.00"), january.income());
        assertEquals(new BigDecimal("1990.00"), january.expense()); // 1800 + 200 * 0.95
        assertEquals(new BigDecimal("4010.00"), january.net());
        assertEquals(new BigDecimal("66.8"), january.savingsRate());

        var march = result.months().get(2).totals();
        assertEquals(new BigDecimal("0.00"), march.expense()); // USD has no rate
        assertNull(march.savingsRate());

        assertEquals(new BigDecimal("12000.00"), result.totals().income());
        assertEquals(new BigDecimal("3790.00"), result.totals().expense());
        assertTrue(result.unconvertedCurrencies().contains("USD"));
    }

    @Test
    void categoriesAreSortedByAmount() {
        CashflowCalculator.YearResult result = CashflowCalculator.year(entries, fx, 2026);

        assertEquals(SALARY, result.byCategory().get(0).categoryId());
        assertEquals(new BigDecimal("12000.00"), result.byCategory().get(0).amount());
        assertEquals(RENT, result.byCategory().get(1).categoryId());
        assertEquals(new BigDecimal("3600.00"), result.byCategory().get(1).amount());
        assertEquals(new BigDecimal("190.00"), result.byCategory().get(2).amount());
    }

    @Test
    void yearlySummaryCoversEveryYear() {
        CashflowCalculator.MultiYearResult result = CashflowCalculator.years(entries, fx);

        assertEquals(2, result.years().size());
        assertEquals(2025, result.years().get(0).year());
        assertEquals(new BigDecimal("5500.00"), result.years().get(0).totals().income());
        assertEquals(2026, result.years().get(1).year());
        assertEquals(new BigDecimal("8210.00"), result.years().get(1).totals().net());
    }
}
