package me.luucka.finance.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import me.luucka.finance.core.forecast.ForecastCalculator;
import me.luucka.finance.core.forecast.ForecastCalculator.BaseAmount;
import me.luucka.finance.core.forecast.ForecastCalculator.Item;
import me.luucka.finance.core.forecast.ForecastCalculator.Period;
import me.luucka.finance.core.forecast.ForecastCalculator.Result;
import me.luucka.finance.core.forecast.ForecastCalculator.Scenario;
import me.luucka.finance.core.forecast.ForecastCalculator.Schedule;
import org.junit.jupiter.api.Test;

class ForecastCalculatorTest {

    private static final long SALARY = 1;
    private static final long FOOD = 2;
    private static final long HOME = 3;

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    private static BaseAmount income(String month, String value) {
        return new BaseAmount(YearMonth.parse(month), SALARY, EntryKind.INCOME, bd(value));
    }

    private static BaseAmount expense(String month, long category, String value) {
        return new BaseAmount(YearMonth.parse(month), category, EntryKind.EXPENSE, bd(value));
    }

    @Test
    void theBaseIsTheYearBeforeOnceOverAndUntilThenTheLastTwelveCompleteMonths() {
        assertEquals(new Period(YearMonth.of(2026, 1), YearMonth.of(2026, 12)),
                ForecastCalculator.basePeriod(2027, LocalDate.of(2027, 1, 1)));
        assertEquals(new Period(YearMonth.of(2025, 9), YearMonth.of(2026, 8)),
                ForecastCalculator.basePeriod(2027, LocalDate.of(2026, 9, 29)));
        // 31 December: December is not complete yet
        assertEquals(new Period(YearMonth.of(2025, 12), YearMonth.of(2026, 11)),
                ForecastCalculator.basePeriod(2027, LocalDate.of(2026, 12, 31)));
        // Two years ahead: still the last twelve complete months
        assertEquals(new Period(YearMonth.of(2025, 9), YearMonth.of(2026, 8)),
                ForecastCalculator.basePeriod(2028, LocalDate.of(2026, 9, 29)));
    }

    @Test
    void eachMonthGrowsFromTheSameCalendarMonthOfTheBase() {
        Period base = new Period(YearMonth.of(2025, 9), YearMonth.of(2026, 8));
        List<BaseAmount> amounts = List.of(
                income("2025-12", "10000"),                // 13th salary in December 2025
                income("2026-01", "5000"),
                expense("2026-01", FOOD, "600"),
                expense("2026-07", FOOD, "400"),
                expense("2025-08", FOOD, "999"),           // before the base: ignored
                new BaseAmount(YearMonth.of(2026, 1), 9, EntryKind.TRANSFER, bd("3000")));

        Result r = ForecastCalculator.forecast(new Scenario(2027, bd("2"), bd("10"), List.of()), base, amounts);

        assertEquals(12, r.months().size());
        assertEquals(bd("10200.00"), r.months().get(11).forecast().income());
        assertEquals(bd("10000.00"), r.months().get(11).base().income());
        assertEquals(bd("5100.00"), r.months().get(0).forecast().income());
        assertEquals(bd("660.00"), r.months().get(0).forecast().expense());
        assertEquals(bd("440.00"), r.months().get(6).forecast().expense());
        assertEquals(bd("0.00"), r.months().get(7).forecast().expense());
        assertNull(r.months().get(1).forecast().savingsRate());
        assertEquals(bd("15300.00"), r.forecastTotals().income());
        assertEquals(bd("1100.00"), r.forecastTotals().expense());
        assertEquals(bd("300.00"), r.fromGrowth().income());
        assertEquals(bd("100.00"), r.fromGrowth().expense());
        assertEquals(bd("0.00"), r.forecastTotals().transferred());
    }

    @Test
    void extraItemsAddMonthlyOrOnceAndCanTakeAway() {
        Period base = new Period(YearMonth.of(2026, 1), YearMonth.of(2026, 12));
        List<BaseAmount> amounts = List.of(income("2026-03", "6000"), expense("2026-03", FOOD, "500"));
        List<Item> items = List.of(
                new Item(EntryKind.EXPENSE, HOME, bd("1800"), Schedule.MONTHLY, 1, null),   // rent all year
                new Item(EntryKind.EXPENSE, null, bd("25000"), Schedule.ONCE, 3, null),      // a car in March
                new Item(EntryKind.EXPENSE, FOOD, bd("-70"), Schedule.MONTHLY, 7, 9),        // gym cancelled Jul-Sep
                new Item(EntryKind.INCOME, SALARY, bd("5000"), Schedule.ONCE, 12, 5));      // end ignored for ONCE

        Result r = ForecastCalculator.forecast(new Scenario(2027, BigDecimal.ZERO, BigDecimal.ZERO, items), base,
                amounts);

        assertEquals(bd("27300.00"), r.months().get(2).forecast().expense());   // 500 + 1800 + 25000
        assertEquals(bd("1730.00"), r.months().get(7).forecast().expense());    // August: 1800 - 70
        assertEquals(bd("1800.00"), r.months().get(9).forecast().expense());    // October: rent only
        assertEquals(bd("5000.00"), r.months().get(11).forecast().income());
        assertEquals(List.of(bd("21600.00"), bd("25000.00"), bd("-210.00"), bd("5000.00")), r.itemTotals());
        assertEquals(bd("46390.00"), r.fromItems().expense());
        assertEquals(bd("5000.00"), r.fromItems().income());

        // Categories: the base with its growth plus the items that have one
        var home = r.categories().stream().filter(c -> c.categoryId() == HOME).findFirst().orElseThrow();
        assertEquals(bd("0.00"), home.base());
        assertEquals(bd("21600.00"), home.forecast());
        var food = r.categories().stream().filter(c -> c.categoryId() == FOOD).findFirst().orElseThrow();
        assertEquals(bd("500.00"), food.base());
        assertEquals(bd("290.00"), food.forecast());
        assertTrue(r.categories().stream().noneMatch(c -> c.categoryId() == 0));
        assertFalse(r.categories().isEmpty());
    }
}
