package me.luucka.finance.core.goal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.Year;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

import me.luucka.finance.core.Money;
import me.luucka.finance.core.fx.FxTable;
import me.luucka.finance.core.valuation.NetWorthCalculator;
import me.luucka.finance.core.valuation.PositionHistory;

/**
 * Progress of a savings goal, in the base currency.
 * <ul>
 *   <li>{@link Kind#BALANCE}: the value of the linked positions today against the target. The pace
 *       is their average monthly change over the last {@value #PACE_MONTHS} months (deposits and
 *       market moves alike); it gives the month the target is expected to be reached.</li>
 *   <li>{@link Kind#YEARLY}: the transfers into the linked positions during the current calendar
 *       year against the target, which starts again every 1 January.</li>
 * </ul>
 * Everything is counted in whole months, the current one included: money moved later this month
 * still counts, and a target is expected in the month (at its end) the pace reaches it.
 */
public final class GoalCalculator {

    public static final int PACE_MONTHS = 6;

    /** Below this much history the pace would say more about noise than about a trend. */
    private static final long MIN_PACE_DAYS = 28;
    private static final BigDecimal DAYS_PER_MONTH = new BigDecimal("30.436875");
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final int MAX_PROJECTION_MONTHS = 1200;

    public enum Kind { BALANCE, YEARLY }

    /**
     * REACHED: target met. ON_TRACK / BEHIND: compared with the deadline (BALANCE with a date) or
     * with the share of the year gone by (YEARLY). IN_PROGRESS: a BALANCE goal without a date.
     * NO_RATE: the target's currency cannot be converted to the base currency.
     */
    public enum State { REACHED, ON_TRACK, BEHIND, IN_PROGRESS, NO_RATE }

    public record Goal(Kind kind, BigDecimal target, String currency, LocalDate targetDate, Set<Long> positionIds) {
    }

    /** A transfer into one of the user's positions. */
    public record Transfer(LocalDate date, BigDecimal amount, String currency, long toPositionId) {
    }

    /**
     * @param target          target in the base currency (null with state NO_RATE)
     * @param current         value (BALANCE) or amount put in this year (YEARLY)
     * @param remaining       what is still missing, never negative (null with NO_RATE)
     * @param percent         current / target × 100, one decimal (null with NO_RATE)
     * @param monthlyPace     BALANCE: average monthly change over the last months, null without
     *                        enough history; YEARLY: average per month so far this year
     * @param projectedDate   BALANCE: end of the month the target is reached at that pace (null if
     *                        already reached, pace not positive or unknown)
     * @param requiredMonthly what is needed each month to make it in time: BALANCE with a date,
     *                        or YEARLY (null otherwise, or when reached)
     * @param monthsLeft      months available including the current one (null without a deadline)
     * @param year            YEARLY: the calendar year measured
     */
    public record Status(BigDecimal target, BigDecimal current, BigDecimal remaining, BigDecimal percent,
                         State state, BigDecimal monthlyPace, LocalDate projectedDate, BigDecimal requiredMonthly,
                         Integer monthsLeft, Integer year, SortedSet<String> unconvertedCurrencies) {
    }

    private GoalCalculator() {
    }

    /**
     * @param positions every position history of the user (only the goal's are used)
     * @param transfers the user's transfers (only those into the goal's positions are used)
     */
    public static Status status(Goal goal, Collection<PositionHistory> positions, Collection<Transfer> transfers,
                                FxTable fx, LocalDate today) {
        SortedSet<String> unconverted = new TreeSet<>();
        BigDecimal target = fx.toBase(goal.target(), goal.currency(), today).orElse(null);
        if (target == null) {
            unconverted.add(goal.currency());
        }
        List<PositionHistory> linked = positions.stream()
                .filter(p -> goal.positionIds().contains(p.positionId()))
                .toList();
        return goal.kind() == Kind.BALANCE
                ? balance(goal, target, linked, fx, today, unconverted)
                : yearly(goal, target, transfers, fx, today, unconverted);
    }

    private static Status balance(Goal goal, BigDecimal target, List<PositionHistory> linked, FxTable fx,
                                  LocalDate today, SortedSet<String> unconverted) {
        NetWorthCalculator.NetWorthPoint now = NetWorthCalculator.valueAt(linked, fx, today).point();
        unconverted.addAll(now.unconvertedCurrencies());
        BigDecimal current = now.total();
        BigDecimal pace = pace(linked, fx, today, current);

        Integer monthsLeft = goal.targetDate() == null ? null : monthsLeft(today, YearMonth.from(goal.targetDate()));
        if (target == null) {
            return new Status(null, current, null, null, State.NO_RATE, round(pace), null, null, monthsLeft, null,
                    unconverted);
        }
        BigDecimal remaining = target.subtract(current, Money.CONTEXT).max(BigDecimal.ZERO);
        boolean reached = remaining.signum() == 0;
        LocalDate projected = reached || pace == null || pace.signum() <= 0 ? null : projected(today, remaining, pace);
        BigDecimal required = reached || monthsLeft == null ? null : divide(remaining, monthsLeft);

        State state;
        if (reached) {
            state = State.REACHED;
        } else if (goal.targetDate() == null) {
            state = State.IN_PROGRESS;
        } else {
            YearMonth deadline = YearMonth.from(goal.targetDate());
            boolean inTime = !deadline.isBefore(YearMonth.from(today)) && projected != null
                    && !YearMonth.from(projected).isAfter(deadline);
            state = inTime ? State.ON_TRACK : State.BEHIND;
        }
        return new Status(Money.round(target), current, Money.round(remaining), percent(current, target), state,
                round(pace), projected, round(required), monthsLeft, null, unconverted);
    }

    private static Status yearly(Goal goal, BigDecimal target, Collection<Transfer> transfers, FxTable fx,
                                 LocalDate today, SortedSet<String> unconverted) {
        int year = today.getYear();
        BigDecimal current = BigDecimal.ZERO;
        for (Transfer transfer : transfers) {
            if (transfer.date().getYear() != year || transfer.date().isAfter(today)
                    || !goal.positionIds().contains(transfer.toPositionId())) {
                continue;
            }
            Optional<BigDecimal> value = fx.toBase(transfer.amount(), transfer.currency(), transfer.date());
            if (value.isPresent()) {
                current = current.add(value.get(), Money.CONTEXT);
            } else {
                unconverted.add(transfer.currency());
            }
        }
        int monthsLeft = monthsLeft(today, YearMonth.of(year, 12));
        BigDecimal pace = divide(current, today.getMonthValue());
        if (target == null) {
            return new Status(null, Money.round(current), null, null, State.NO_RATE, round(pace), null, null,
                    monthsLeft, year, unconverted);
        }
        BigDecimal remaining = target.subtract(current, Money.CONTEXT).max(BigDecimal.ZERO);
        State state;
        if (remaining.signum() == 0) {
            state = State.REACHED;
        } else {
            // Expected so far: the share of the year gone by, today included
            BigDecimal elapsed = BigDecimal.valueOf(today.getDayOfYear())
                    .divide(BigDecimal.valueOf(Year.of(year).length()), Money.CONTEXT);
            state = current.compareTo(target.multiply(elapsed, Money.CONTEXT)) >= 0 ? State.ON_TRACK : State.BEHIND;
        }
        BigDecimal required = remaining.signum() == 0 ? null : divide(remaining, monthsLeft);
        return new Status(Money.round(target), Money.round(current), Money.round(remaining), percent(current, target),
                state, round(pace), null, round(required), monthsLeft, year, unconverted);
    }

    /**
     * Average monthly change of the linked positions since {@value #PACE_MONTHS} months ago, or since
     * the first record when they are younger. Null with less than four weeks of history.
     */
    private static BigDecimal pace(List<PositionHistory> linked, FxTable fx, LocalDate today, BigDecimal current) {
        LocalDate first = linked.stream()
                .map(PositionHistory::firstDate)
                .flatMap(Optional::stream)
                .min(LocalDate::compareTo)
                .orElse(null);
        if (first == null) {
            return null;
        }
        LocalDate from = first.isAfter(today.minusMonths(PACE_MONTHS)) ? first : today.minusMonths(PACE_MONTHS);
        if (ChronoUnit.DAYS.between(from, today) < MIN_PACE_DAYS) {
            return null;
        }
        BigDecimal before = NetWorthCalculator.valueAt(linked, fx, from).point().total();
        return current.subtract(before, Money.CONTEXT).divide(monthsBetween(from, today), Money.CONTEXT);
    }

    /** Months from the current one to {@code last}, both included; at least 1 (the current month). */
    static int monthsLeft(LocalDate today, YearMonth last) {
        long months = ChronoUnit.MONTHS.between(YearMonth.from(today), last) + 1;
        return (int) Math.max(1, Math.min(months, Integer.MAX_VALUE));
    }

    /**
     * Calendar months between two dates: whole months plus the rest as a fraction. Counted back from
     * {@code to}, so "six months before 31 August" (28 February) is exactly 6.
     */
    static BigDecimal monthsBetween(LocalDate from, LocalDate to) {
        long whole = ChronoUnit.MONTHS.between(from, to);
        while (!to.minusMonths(whole + 1).isBefore(from)) {
            whole++;
        }
        long rest = ChronoUnit.DAYS.between(from, to.minusMonths(whole));
        return BigDecimal.valueOf(whole).add(BigDecimal.valueOf(rest).divide(DAYS_PER_MONTH, Money.CONTEXT));
    }

    /** End of the month the remaining amount is covered, counting the current month as the first. */
    private static LocalDate projected(LocalDate today, BigDecimal remaining, BigDecimal pace) {
        BigDecimal months = remaining.divide(pace, 0, RoundingMode.CEILING);
        if (months.compareTo(BigDecimal.valueOf(MAX_PROJECTION_MONTHS)) > 0) {
            return null;
        }
        return YearMonth.from(today).plusMonths(Math.max(0, months.longValue() - 1)).atEndOfMonth();
    }

    private static BigDecimal divide(BigDecimal amount, int months) {
        return amount.divide(BigDecimal.valueOf(months), Money.CONTEXT);
    }

    private static BigDecimal percent(BigDecimal current, BigDecimal target) {
        return current.multiply(HUNDRED, Money.CONTEXT).divide(target, 1, RoundingMode.HALF_EVEN);
    }

    private static BigDecimal round(BigDecimal value) {
        return value == null ? null : Money.round(value);
    }
}
