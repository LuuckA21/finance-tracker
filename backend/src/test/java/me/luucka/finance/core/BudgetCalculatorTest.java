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
import me.luucka.finance.core.budget.BudgetCalculator.Period;
import me.luucka.finance.core.budget.BudgetCalculator.State;
import me.luucka.finance.core.fx.FxTable;
import org.junit.jupiter.api.Test;

class BudgetCalculatorTest {

    private static final long FOOD = 1;
    private static final long RENT = 2;
    private static final long FUN = 3;
    private static final long TRAVEL = 4;
    private static final long INSURANCE = 5;
    private static final long TAXES = 6;
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
        // 50 spent in 15 of 30 days, 30 a month usually: (50 + 30) / (15 + 30) a day for 15 more days
        assertEquals(new BigDecimal("76.67"), fun.projected());

        // A recurring expense (rent) is counted once, only the rest is extrapolated (no history: by days)
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

    @Test
    void periodsAreCalendarQuartersAndYears() {
        assertEquals(YearMonth.of(2026, 7), Period.QUARTERLY.start(SEPTEMBER));
        assertEquals(YearMonth.of(2026, 9), Period.QUARTERLY.end(YearMonth.of(2026, 7)));
        assertEquals(YearMonth.of(2026, 10), Period.QUARTERLY.start(YearMonth.of(2026, 12)));
        assertEquals(YearMonth.of(2026, 1), Period.YEARLY.start(SEPTEMBER));
        assertEquals(YearMonth.of(2026, 12), Period.YEARLY.end(SEPTEMBER));
        assertEquals(SEPTEMBER, Period.MONTHLY.start(SEPTEMBER));
        assertEquals(SEPTEMBER, Period.MONTHLY.end(SEPTEMBER));
    }

    @Test
    void quarterlyAndYearlyBudgetsCountTheirPeriodSoFar() {
        List<Expense> entries = List.of(
                // The yearly premium paid in January, a top-up in September; last year's for comparison
                expense("2026-01-15", "3600", "CHF", INSURANCE),
                expense("2026-09-10", "100", "CHF", INSURANCE),
                expense("2025-01-20", "3500", "CHF", INSURANCE),
                expense("2024-12-20", "999", "CHF", INSURANCE), // before last year: ignored
                // Third quarter so far, and the second quarter for comparison
                expense("2026-07-05", "600", "CHF", TAXES),
                expense("2026-08-05", "300", "CHF", TAXES),
                expense("2026-06-30", "999", "CHF", TAXES),
                expense("2026-09-02", "300", "CHF", FOOD),
                expense("2026-01-10", "999", "CHF", FOOD)); // monthly budget: only the last 3 months matter
        List<BudgetLine> lines = List.of(
                new BudgetLine(INSURANCE, new BigDecimal("4000"), "CHF", Period.YEARLY),
                new BudgetLine(TAXES, new BigDecimal("1000"), "CHF", Period.QUARTERLY),
                new BudgetLine(FOOD, new BigDecimal("450"), "CHF"));
        var status = BudgetCalculator.month(lines, entries, FX, SEPTEMBER, LocalDate.of(2026, 10, 5));

        var insurance = status.categories().stream().filter(c -> c.categoryId() == INSURANCE).findFirst().orElseThrow();
        assertEquals(YearMonth.of(2026, 1), insurance.from());
        assertEquals(YearMonth.of(2026, 12), insurance.to());
        assertEquals(new BigDecimal("3700.00"), insurance.spent());
        assertEquals(new BigDecimal("100.00"), insurance.monthSpent());
        assertEquals(new BigDecimal("92.5"), insurance.percent());
        assertEquals(State.WARNING, insurance.state());
        assertEquals(new BigDecimal("3500.00"), insurance.previous());
        assertNull(insurance.projected());

        var taxes = status.categories().stream().filter(c -> c.categoryId() == TAXES).findFirst().orElseThrow();
        assertEquals(YearMonth.of(2026, 7), taxes.from());
        assertEquals(new BigDecimal("900.00"), taxes.spent());
        assertEquals(new BigDecimal("0.00"), taxes.monthSpent());
        assertEquals(new BigDecimal("999.00"), taxes.previous());
        assertEquals(new BigDecimal("100.00"), taxes.remaining());

        // Totals compare months with months: only the monthly budget is in them
        assertEquals(new BigDecimal("450.00"), status.budgeted());
        assertEquals(new BigDecimal("300.00"), status.spent());
        assertEquals(new BigDecimal("0.00"), status.unbudgeted());

        // In January the yearly premium is not "over" a monthly share: it is 90 % of the year
        var january = BudgetCalculator.month(lines, entries, FX, YearMonth.of(2026, 1), LocalDate.of(2026, 10, 5));
        var paid = january.categories().stream().filter(c -> c.categoryId() == INSURANCE).findFirst().orElseThrow();
        assertEquals(new BigDecimal("3600.00"), paid.spent());
        assertEquals(State.WARNING, paid.state());
        assertEquals(new BigDecimal("3500.00"), paid.previous());
    }

    @Test
    void theProjectionStartsFromTheUsualPaceAndFollowsTheMonth() {
        BigDecimal none = BigDecimal.ZERO;
        // A large shop on 1 October, 400 a month usually: not 380 × 31, close to a usual month plus it
        assertEquals(0, new BigDecimal("1111.25").compareTo(
                BudgetCalculator.project(new BigDecimal("380"), none, new BigDecimal("400"), LocalDate.of(2026, 10, 1))));
        // Spending at the usual pace projects to the usual amount
        assertEquals(0, new BigDecimal("300").compareTo(
                BudgetCalculator.project(new BigDecimal("150"), none, new BigDecimal("300"), LocalDate.of(2026, 9, 15))));
        // On the last day the projection is what was spent
        assertEquals(0, new BigDecimal("420").compareTo(
                BudgetCalculator.project(new BigDecimal("420"), none, new BigDecimal("300"), LocalDate.of(2026, 9, 30))));
        // Without history: nothing in the first days, then the month's own pace
        assertNull(BudgetCalculator.project(new BigDecimal("380"), none, null, LocalDate.of(2026, 10, 1)));
        assertNull(BudgetCalculator.project(new BigDecimal("60"), none, null, LocalDate.of(2026, 9, 6)));
        assertEquals(0, new BigDecimal("300").compareTo(
                BudgetCalculator.project(new BigDecimal("70"), none, null, LocalDate.of(2026, 9, 7))));
        // Recurring spending is not carried forward
        assertEquals(0, new BigDecimal("2100").compareTo(BudgetCalculator.project(new BigDecimal("1950"),
                new BigDecimal("1800"), new BigDecimal("300"), LocalDate.of(2026, 9, 15))));
    }
}
