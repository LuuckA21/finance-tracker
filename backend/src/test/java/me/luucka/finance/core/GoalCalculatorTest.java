package me.luucka.finance.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import me.luucka.finance.core.fx.FxTable;
import me.luucka.finance.core.goal.GoalCalculator;
import me.luucka.finance.core.goal.GoalCalculator.Goal;
import me.luucka.finance.core.goal.GoalCalculator.Kind;
import me.luucka.finance.core.goal.GoalCalculator.State;
import me.luucka.finance.core.goal.GoalCalculator.Status;
import me.luucka.finance.core.goal.GoalCalculator.Transfer;
import me.luucka.finance.core.valuation.PositionHistory;
import me.luucka.finance.core.valuation.ValuationSnapshot;
import org.junit.jupiter.api.Test;

class GoalCalculatorTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 29);
    private static final FxTable FX = new FxTable("CHF").put("EUR", LocalDate.of(2026, 1, 1), new BigDecimal("0.95"));
    private static final long SAVINGS = 1;
    private static final long PILLAR = 2;
    private static final long BROKER = 3;

    private static ValuationSnapshot snapshot(String date, String balance) {
        return new ValuationSnapshot(LocalDate.parse(date), new BigDecimal(balance), BigDecimal.ONE);
    }

    /** Savings account growing by 500 a month from 5000 on 29 March to 8000 today. */
    private static final PositionHistory SAVINGS_ACCOUNT = new PositionHistory(SAVINGS, AssetClass.CASH, "CHF", List.of(
            snapshot("2026-01-31", "4000"), snapshot("2026-03-29", "5000"), snapshot("2026-06-29", "6500"),
            snapshot("2026-09-29", "8000")));
    private static final PositionHistory EURO_ACCOUNT = new PositionHistory(BROKER, AssetClass.CASH, "EUR", List.of(
            snapshot("2026-03-29", "1000")));

    private static Status balance(String target, String targetDate, Set<Long> positions) {
        Goal goal = new Goal(Kind.BALANCE, new BigDecimal(target), "CHF",
                targetDate == null ? null : LocalDate.parse(targetDate), positions);
        return GoalCalculator.status(goal, List.of(SAVINGS_ACCOUNT, EURO_ACCOUNT), List.of(), FX, TODAY);
    }

    private static BigDecimal chf(String value) {
        return new BigDecimal(value);
    }

    @Test
    void balanceGoalProjectsTheMonthAtTheRecentPace() {
        Status status = balance("20000", null, Set.of(SAVINGS));
        assertEquals(chf("8000.00"), status.current());
        assertEquals(chf("12000.00"), status.remaining());
        assertEquals(chf("40.0"), status.percent());
        assertEquals(State.IN_PROGRESS, status.state());
        // 3000 more in the last 6 months (29 March -> 29 September)
        assertEquals(chf("500.00"), status.monthlyPace());
        // 12000 at 500 a month: 24 months counting September 2026 as the first
        assertEquals(LocalDate.of(2028, 8, 31), status.projectedDate());
        assertNull(status.requiredMonthly());
        assertNull(status.monthsLeft());
    }

    @Test
    void balanceGoalWithADeadlineSaysWhatIsNeededEachMonth() {
        // September to December 2026: 4 months for 2000
        Status onTrack = balance("10000", "2026-12-31", Set.of(SAVINGS));
        assertEquals(State.ON_TRACK, onTrack.state());
        assertEquals(4, onTrack.monthsLeft());
        assertEquals(chf("500.00"), onTrack.requiredMonthly());
        assertEquals(LocalDate.of(2026, 12, 31), onTrack.projectedDate());

        Status behind = balance("20000", "2027-03-31", Set.of(SAVINGS));
        assertEquals(State.BEHIND, behind.state());
        assertEquals(7, behind.monthsLeft());
        assertEquals(chf("1714.29"), behind.requiredMonthly());

        // A deadline already gone by is behind, and everything missing is needed now
        Status late = balance("10000", "2026-06-30", Set.of(SAVINGS));
        assertEquals(State.BEHIND, late.state());
        assertEquals(1, late.monthsLeft());
        assertEquals(chf("2000.00"), late.requiredMonthly());
    }

    @Test
    void balanceGoalAddsPositionsInTheBaseCurrency() {
        Status status = balance("8000", null, Set.of(SAVINGS, BROKER));
        assertEquals(chf("8950.00"), status.current());
        assertEquals(State.REACHED, status.state());
        assertEquals(chf("0.00"), status.remaining());
        assertEquals(chf("111.9"), status.percent());
        assertNull(status.projectedDate());
        assertNull(status.requiredMonthly());
    }

    @Test
    void balanceGoalWithoutHistoryHasNoPace() {
        PositionHistory fresh = new PositionHistory(SAVINGS, AssetClass.CASH, "CHF", List.of(snapshot("2026-09-20", "100")));
        Goal goal = new Goal(Kind.BALANCE, chf("1000"), "CHF", null, Set.of(SAVINGS));
        Status status = GoalCalculator.status(goal, List.of(fresh), List.of(), FX, TODAY);
        assertEquals(chf("100.00"), status.current());
        assertNull(status.monthlyPace());
        assertNull(status.projectedDate());

        Status empty = GoalCalculator.status(goal, List.of(), List.of(), FX, TODAY);
        assertEquals(chf("0.00"), empty.current());
        assertNull(empty.monthlyPace());
    }

    @Test
    void aFallingBalanceHasNoProjectedDate() {
        PositionHistory falling = new PositionHistory(SAVINGS, AssetClass.CASH, "CHF",
                List.of(snapshot("2026-03-29", "5000"), snapshot("2026-09-29", "4000")));
        Goal goal = new Goal(Kind.BALANCE, chf("10000"), "CHF", LocalDate.of(2027, 12, 31), Set.of(SAVINGS));
        Status status = GoalCalculator.status(goal, List.of(falling), List.of(), FX, TODAY);
        assertEquals(-1, status.monthlyPace().signum());
        assertNull(status.projectedDate());
        assertEquals(State.BEHIND, status.state());
    }

    @Test
    void yearlyGoalCountsThisYearsTransfersIntoTheLinkedPositions() {
        List<Transfer> transfers = List.of(
                new Transfer(LocalDate.of(2025, 12, 20), chf("7000"), "CHF", PILLAR),  // last year
                new Transfer(LocalDate.of(2026, 1, 15), chf("2000"), "CHF", PILLAR),
                new Transfer(LocalDate.of(2026, 6, 15), chf("1000"), "EUR", PILLAR),   // 950 CHF
                new Transfer(LocalDate.of(2026, 7, 1), chf("5000"), "CHF", SAVINGS),   // another position
                new Transfer(LocalDate.of(2026, 10, 1), chf("500"), "CHF", PILLAR));   // not yet
        Goal goal = new Goal(Kind.YEARLY, chf("7258"), "CHF", null, Set.of(PILLAR));
        Status status = GoalCalculator.status(goal, List.of(), transfers, FX, TODAY);
        assertEquals(2026, status.year());
        assertEquals(chf("2950.00"), status.current());
        assertEquals(chf("4308.00"), status.remaining());
        assertEquals(chf("40.6"), status.percent());
        // 75 % of the year is gone, only 41 % paid in
        assertEquals(State.BEHIND, status.state());
        // October, November, December and what is left of September
        assertEquals(4, status.monthsLeft());
        assertEquals(chf("1077.00"), status.requiredMonthly());
        assertEquals(chf("327.78"), status.monthlyPace());
    }

    @Test
    void yearlyGoalIsOnTrackAheadOfTheCalendar() {
        List<Transfer> transfers = List.of(new Transfer(LocalDate.of(2026, 1, 10), chf("6000"), "CHF", PILLAR));
        Goal goal = new Goal(Kind.YEARLY, chf("7258"), "CHF", null, Set.of(PILLAR));
        assertEquals(State.ON_TRACK, GoalCalculator.status(goal, List.of(), transfers, FX, TODAY).state());
        Goal done = new Goal(Kind.YEARLY, chf("6000"), "CHF", null, Set.of(PILLAR));
        Status status = GoalCalculator.status(done, List.of(), transfers, FX, TODAY);
        assertEquals(State.REACHED, status.state());
        assertNull(status.requiredMonthly());
    }

    @Test
    void thePaceCountsCalendarMonthsEvenAtTheEndOfAMonth() {
        // 31 August minus 6 months is 28 February: still exactly 6 months, 300 a month
        LocalDate endOfAugust = LocalDate.of(2026, 8, 31);
        PositionHistory history = new PositionHistory(SAVINGS, AssetClass.CASH, "CHF",
                List.of(snapshot("2026-02-28", "1000"), snapshot("2026-08-31", "2800")));
        Goal goal = new Goal(Kind.BALANCE, chf("5000"), "CHF", null, Set.of(SAVINGS));
        assertEquals(chf("300.00"), GoalCalculator.status(goal, List.of(history), List.of(), FX, endOfAugust).monthlyPace());
    }

    @Test
    void aTargetInAnUnknownCurrencyCannotBeMeasured() {
        Goal goal = new Goal(Kind.BALANCE, chf("5000"), "GBP", null, Set.of(SAVINGS));
        Status status = GoalCalculator.status(goal, List.of(SAVINGS_ACCOUNT), List.of(), FX, TODAY);
        assertEquals(State.NO_RATE, status.state());
        assertNull(status.target());
        assertNull(status.percent());
        assertEquals(chf("8000.00"), status.current());
        assertEquals(java.util.Set.of("GBP"), status.unconvertedCurrencies());
    }
}
