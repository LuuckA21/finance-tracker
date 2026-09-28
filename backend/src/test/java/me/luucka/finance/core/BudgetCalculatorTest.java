package me.luucka.finance.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Set;

import me.luucka.finance.core.budget.BudgetCalculator;
import me.luucka.finance.core.budget.BudgetCalculator.BudgetLine;
import me.luucka.finance.core.budget.BudgetCalculator.Expense;
import me.luucka.finance.core.budget.BudgetCalculator.State;
import me.luucka.finance.core.fx.FxTable;
import org.junit.jupiter.api.Test;

class BudgetCalculatorTest {

    private static final long FOOD = 1;
    private static final long RENT = 2;
    private static final long FUN = 3;
    private static final long TRAVEL = 4;
    private static final YearMonth SEPTEMBER = YearMonth.of(2026, 9);
    private static final FxTable FX = new FxTable("CHF").put("EUR", LocalDate.of(2026, 1, 1), new BigDecimal("0.95"));

    private static Expense expense(String date, String amount, String currency, long category) {
        return new Expense(LocalDate.parse(date), new BigDecimal(amount), currency, category, false);
    }

    private static final List<Expense> ENTRIES = List.of(
            expense("2026-09-02", "300", "CHF", FOOD),
            expense("2026-09-12", "100", "EUR", FOOD),
            expense("2026-09-01", "1900", "CHF", RENT),
            expense("2026-09-20", "50", "CHF", FUN),
            // Previous months: average for suggestions
            expense("2026-08-10", "450", "CHF", FOOD),
            expense("2026-07-10", "420", "CHF", FOOD),
            expense("2026-06-10", "390", "CHF", FOOD),
            expense("2026-08-15", "90", "CHF", FUN),
            expense("2026-05-10", "999", "CHF", FUN), // older than 3 months: ignored
            expense("2026-10-01", "999", "CHF", FOOD)); // after the month: ignored

    @Test
    void comparesSpendingWithBudgets() {
        var status = BudgetCalculator.month(List.of(
                new BudgetLine(FOOD, new BigDecimal("450"), "CHF"),
                new BudgetLine(RENT, new BigDecimal("1800"), "CHF"),
                new BudgetLine(TRAVEL, new BigDecimal("200"), "EUR")), ENTRIES, FX, SEPTEMBER, LocalDate.of(2026, 10, 5));

        var rent = status.categories().get(0);
        assertEquals(RENT, rent.categoryId());
        assertEquals(State.OVER, rent.state());
        assertEquals(new BigDecimal("105.6"), rent.percent());
        assertEquals(new BigDecimal("-100.00"), rent.remaining());

        // 300 CHF + 100 EUR × 0.95 = 395 of 450
        var food = status.categories().get(1);
        assertEquals(new BigDecimal("395.00"), food.spent());
        assertEquals(new BigDecimal("87.8"), food.percent());
        assertEquals(State.WARNING, food.state());
        assertEquals(new BigDecimal("420.00"), food.average());
        assertNull(food.projected(), "no projection for a past month");

        // A budget in euro is converted: 200 EUR = 190 CHF, nothing spent
        var travel = status.categories().get(2);
        assertEquals(new BigDecimal("190.00"), travel.budget());
        assertEquals(State.OK, travel.state());

        assertEquals(new BigDecimal("2440.00"), status.budgeted());
        assertEquals(new BigDecimal("2295.00"), status.spent());
        assertEquals(new BigDecimal("145.00"), status.remaining());
        // FUN has no budget
        assertEquals(new BigDecimal("50.00"), status.unbudgeted());
        assertEquals(1, status.others().size());
        assertEquals(FUN, status.others().getFirst().categoryId());
        assertEquals(new BigDecimal("30.00"), status.others().getFirst().average());
    }

    @Test
    void projectsTheCurrentMonthAndReportsUnconvertibleBudgets() {
        var status = BudgetCalculator.month(List.of(
                new BudgetLine(FUN, new BigDecimal("100"), "CHF"),
                new BudgetLine(TRAVEL, new BigDecimal("100"), "USD")), ENTRIES, FX, SEPTEMBER, LocalDate.of(2026, 9, 15));

        var fun = status.categories().stream().filter(c -> c.categoryId() == FUN).findFirst().orElseThrow();
        // 50 spent in 15 of 30 days
        assertEquals(new BigDecimal("100.00"), fun.projected());

        // A recurring expense (rent) is counted once, only the rest is extrapolated
        var withRent = BudgetCalculator.month(List.of(new BudgetLine(RENT, new BigDecimal("2000"), "CHF")),
                List.of(new Expense(LocalDate.of(2026, 9, 1), new BigDecimal("1800"), "CHF", RENT, true),
                        expense("2026-09-10", "30", "CHF", RENT)), FX, SEPTEMBER, LocalDate.of(2026, 9, 15));
        assertEquals(new BigDecimal("1860.00"), withRent.categories().getFirst().projected());
        var travel = status.categories().stream().filter(c -> c.categoryId() == TRAVEL).findFirst().orElseThrow();
        assertNull(travel.budget());
        assertNull(travel.percent());
        assertEquals(State.OK, travel.state());
        assertEquals(Set.of("USD"), status.unconvertedCurrencies());
        // Unconvertible budgets are listed last
        assertEquals(TRAVEL, status.categories().getLast().categoryId());
    }

    @Test
    void statesChangeAtEightyAndHundredPercent() {
        var lines = List.of(new BudgetLine(FUN, new BigDecimal("62.5"), "CHF"));
        var entries = List.of(expense("2026-09-01", "50", "CHF", FUN));
        assertEquals(State.WARNING, BudgetCalculator.month(lines, entries, FX, SEPTEMBER, LocalDate.of(2026, 10, 1))
                .categories().getFirst().state());
        lines = List.of(new BudgetLine(FUN, new BigDecimal("50"), "CHF"));
        assertEquals(State.WARNING, BudgetCalculator.month(lines, entries, FX, SEPTEMBER, LocalDate.of(2026, 10, 1))
                .categories().getFirst().state(), "exactly 100 % is not over");
        lines = List.of(new BudgetLine(FUN, new BigDecimal("63"), "CHF"));
        assertEquals(State.OK, BudgetCalculator.month(lines, entries, FX, SEPTEMBER, LocalDate.of(2026, 10, 1))
                .categories().getFirst().state());
    }
}
