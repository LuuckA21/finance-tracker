package me.luucka.finance.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import me.luucka.finance.core.fx.FxTable;
import me.luucka.finance.core.report.AnnualReportCalculator;
import me.luucka.finance.core.report.AnnualReportCalculator.Entry;
import me.luucka.finance.core.valuation.PositionHistory;
import me.luucka.finance.core.valuation.ValuationSnapshot;
import org.junit.jupiter.api.Test;

class AnnualReportCalculatorTest {

    private static final long SALARY = 1;
    private static final long RENT = 2;
    private static final long TRAVEL = 3;
    private static final long BANK = 10;
    private static final long PILLAR = 11;
    private static final long HOLIDAY = 20;

    private final FxTable fx = new FxTable("CHF").put("EUR", LocalDate.of(2024, 1, 1), new BigDecimal("0.95"));

    private long ids;

    private Entry entry(String date, EntryKind kind, String amount, String currency, Long category) {
        return new Entry(++ids, LocalDate.parse(date), kind, new BigDecimal(amount), currency, category, null, null,
                null, Set.of());
    }

    private Entry transfer(String date, String amount, Long from, Long to) {
        return new Entry(++ids, LocalDate.parse(date), EntryKind.TRANSFER, new BigDecimal(amount), "CHF", null, from,
                to, null, Set.of());
    }

    private static PositionHistory position(long id, AssetClass assetClass, String currency, Object... snapshots) {
        List<ValuationSnapshot> list = new ArrayList<>();
        for (int i = 0; i < snapshots.length; i += 2) {
            list.add(new ValuationSnapshot(LocalDate.parse((String) snapshots[i]), BigDecimal.ONE,
                    new BigDecimal((String) snapshots[i + 1])));
        }
        return new PositionHistory(id, assetClass, currency, list);
    }

    @Test
    void aClosedYearAgainstTheWholeYearBefore() {
        List<Entry> entries = List.of(
                entry("2025-01-25", EntryKind.INCOME, "6000", "CHF", SALARY),
                entry("2025-12-25", EntryKind.INCOME, "6000", "CHF", SALARY),
                entry("2025-03-01", EntryKind.EXPENSE, "1800", "CHF", RENT),
                new Entry(++ids, LocalDate.parse("2025-07-10"), EntryKind.EXPENSE, new BigDecimal("1000"), "EUR",
                        TRAVEL, null, null, "Traghetto", Set.of(HOLIDAY)),
                entry("2025-08-01", EntryKind.EXPENSE, "40", "ARS", TRAVEL),
                transfer("2025-06-30", "7000", BANK, PILLAR),
                entry("2024-02-01", EntryKind.EXPENSE, "1700", "CHF", RENT),
                entry("2024-05-01", EntryKind.INCOME, "5000", "CHF", SALARY),
                entry("2023-05-01", EntryKind.INCOME, "999", "CHF", SALARY));
        List<PositionHistory> positions = List.of(
                position(BANK, AssetClass.CASH, "CHF", "2024-12-31", "20000", "2025-12-31", "18000"),
                position(PILLAR, AssetClass.PENSION, "CHF", "2025-06-30", "7000", "2025-12-31", "7300"));

        var report = AnnualReportCalculator.compute(2025, entries, positions, fx, LocalDate.of(2026, 9, 29));

        assertEquals(LocalDate.of(2025, 12, 31), report.periodEnd());
        assertEquals(12, report.months());
        assertEquals(new BigDecimal("12000.00"), report.totals().income());
        assertEquals(new BigDecimal("2750.00"), report.totals().expense()); // 1800 + 1000 * 0.95
        assertEquals(new BigDecimal("7000.00"), report.totals().transferred());
        assertEquals(new BigDecimal("5000.00"), report.previousTotals().income());
        assertEquals(Set.of("ARS"), report.unconvertedCurrencies());

        // Income first, then expenses; within a kind the largest first; a category of last year only stays
        assertEquals(List.of(SALARY, RENT, TRAVEL), report.categories().stream()
                .map(AnnualReportCalculator.CategoryChange::categoryId).toList());
        assertEquals(new BigDecimal("1700.00"), report.categories().get(1).previousAmount());

        assertEquals(new BigDecimal("20000.00"), report.netWorthStart());
        assertEquals(new BigDecimal("25300.00"), report.netWorthEnd());
        var pillar = report.positions().stream().filter(p -> p.positionId() == PILLAR).findFirst().orElseThrow();
        assertNull(pillar.start(), "opened during the year");
        assertEquals(new BigDecimal("7300.00"), pillar.end());
        assertEquals(new BigDecimal("7000.00"), pillar.transfersIn());
        var bank = report.positions().getFirst();
        assertEquals(BANK, bank.positionId());
        assertEquals(new BigDecimal("7000.00"), bank.transfersOut());
        assertEquals(List.of(AssetClass.CASH, AssetClass.PENSION), report.classes().stream()
                .map(AnnualReportCalculator.ClassChange::assetClass).toList());

        assertEquals(1, report.tags().size());
        assertEquals(new BigDecimal("950.00"), report.tags().getFirst().expense());
        assertEquals("Traghetto", report.largestExpenses().get(1).description());
        assertEquals(new BigDecimal("950.00"), report.largestExpenses().get(1).amountBase());
        assertEquals(new BigDecimal("1800.00"), report.largestExpenses().getFirst().amountBase());
    }

    @Test
    void theCurrentYearIsComparedWithTheSamePeriodOfTheYearBefore() {
        List<Entry> entries = List.of(
                entry("2026-03-01", EntryKind.EXPENSE, "100", "CHF", RENT),
                entry("2025-03-01", EntryKind.EXPENSE, "80", "CHF", RENT),
                // After 29 September last year: not in the comparison
                entry("2025-11-01", EntryKind.EXPENSE, "500", "CHF", RENT));

        var report = AnnualReportCalculator.compute(2026, entries, List.of(), fx, LocalDate.of(2026, 9, 29));

        assertEquals(LocalDate.of(2026, 9, 29), report.periodEnd());
        assertEquals(9, report.months());
        assertEquals(new BigDecimal("80.00"), report.previousTotals().expense());
        assertEquals(new BigDecimal("0.00"), report.netWorthEnd());
    }

    @Test
    void onlyTheTenLargestExpensesAreListed() {
        List<Entry> entries = new ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            entries.add(entry("2025-01-%02d".formatted(i), EntryKind.EXPENSE, String.valueOf(i * 10), "CHF", RENT));
        }
        var report = AnnualReportCalculator.compute(2025, entries, List.of(), fx, LocalDate.of(2026, 1, 5));
        assertEquals(AnnualReportCalculator.LARGEST_EXPENSES, report.largestExpenses().size());
        assertEquals(new BigDecimal("120.00"), report.largestExpenses().getFirst().amountBase());
        assertEquals(new BigDecimal("30.00"), report.largestExpenses().getLast().amountBase());
    }
}
