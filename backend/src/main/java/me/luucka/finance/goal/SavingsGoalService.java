package me.luucka.finance.goal;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.SortedSet;

import me.luucka.finance.cashflow.CashEntryRepository;
import me.luucka.finance.common.ApiException;
import me.luucka.finance.core.Currencies;
import me.luucka.finance.core.EntryKind;
import me.luucka.finance.core.fx.FxTable;
import me.luucka.finance.core.goal.GoalCalculator;
import me.luucka.finance.core.valuation.PositionHistory;
import me.luucka.finance.fx.FxService;
import me.luucka.finance.position.PositionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SavingsGoalService {

    /** Plenty for a household; keeps the per-request computation bounded. */
    static final int MAX_GOALS = 50;

    public record GoalData(String name, GoalCalculator.Kind kind, BigDecimal targetAmount, String currency,
                           LocalDate targetDate, Set<Long> positionIds) {
    }

    /** A goal as saved, plus its progress in the base currency. */
    public record GoalResponse(long id, String name, GoalCalculator.Kind kind, BigDecimal targetAmount,
                               String currency, LocalDate targetDate, List<Long> positionIds, String baseCurrency,
                               BigDecimal target, BigDecimal current, BigDecimal remaining, BigDecimal percent,
                               GoalCalculator.State state, BigDecimal monthlyPace, LocalDate projectedDate,
                               BigDecimal requiredMonthly, Integer monthsLeft, Integer year,
                               SortedSet<String> unconvertedCurrencies) {
    }

    private final SavingsGoalRepository goals;
    private final PositionService positions;
    private final CashEntryRepository entries;
    private final FxService fx;
    private final Clock clock;

    public SavingsGoalService(SavingsGoalRepository goals, PositionService positions, CashEntryRepository entries,
                              FxService fx, Clock clock) {
        this.goals = goals;
        this.positions = positions;
        this.entries = entries;
        this.fx = fx;
        this.clock = clock;
    }

    /** Every goal of the user with its progress today. */
    @Transactional(readOnly = true)
    public List<GoalResponse> list(long userId) {
        List<SavingsGoal> own = goals.findByUserIdOrderByNameAsc(userId);
        if (own.isEmpty()) {
            return List.of();
        }
        LocalDate today = LocalDate.now(clock);
        FxTable table = fx.table(userId);
        List<PositionHistory> histories = positions.histories(userId);
        List<GoalCalculator.Transfer> transfers = transfersThisYear(userId, today);
        return own.stream().map(g -> response(g, histories, transfers, table, today)).toList();
    }

    @Transactional
    public GoalResponse create(long userId, GoalData data) {
        if (goals.countByUserId(userId) >= MAX_GOALS) {
            throw ApiException.badRequest("too_many_goals", "At most " + MAX_GOALS + " goals");
        }
        return save(userId, new SavingsGoal(userId), data);
    }

    @Transactional
    public GoalResponse update(long userId, long id, GoalData data) {
        return save(userId, load(userId, id), data);
    }

    @Transactional
    public void delete(long userId, long id) {
        goals.delete(load(userId, id));
    }

    private GoalResponse save(long userId, SavingsGoal goal, GoalData data) {
        goal.setName(data.name().strip());
        goal.setKind(data.kind());
        goal.setTargetAmount(data.targetAmount());
        goal.setCurrency(Currencies.normalize(data.currency()));
        // A yearly goal starts again every 1 January: a deadline does not apply
        goal.setTargetDate(data.kind() == GoalCalculator.Kind.YEARLY ? null : data.targetDate());
        goal.setPositionIds(positions.requireOwned(userId, data.positionIds()));
        SavingsGoal saved = goals.save(goal);
        LocalDate today = LocalDate.now(clock);
        return response(saved, positions.histories(userId), transfersThisYear(userId, today), fx.table(userId), today);
    }

    private SavingsGoal load(long userId, long id) {
        return goals.findByIdAndUserId(id, userId).orElseThrow(() -> ApiException.notFound("Goal"));
    }

    /** Transfers into a position this year: what yearly goals count. */
    private List<GoalCalculator.Transfer> transfersThisYear(long userId, LocalDate today) {
        return entries.findByUserIdAndDateBetween(userId, today.withDayOfYear(1), today).stream()
                .filter(e -> e.getKind() == EntryKind.TRANSFER && e.getToPositionId() != null)
                .map(e -> new GoalCalculator.Transfer(e.getDate(), e.getAmount(), e.getCurrency(), e.getToPositionId()))
                .toList();
    }

    private static GoalResponse response(SavingsGoal goal, List<PositionHistory> histories,
                                         List<GoalCalculator.Transfer> transfers, FxTable table, LocalDate today) {
        GoalCalculator.Status s = GoalCalculator.status(new GoalCalculator.Goal(goal.getKind(), goal.getTargetAmount(),
                goal.getCurrency(), goal.getTargetDate(), goal.getPositionIds()), histories, transfers, table, today);
        return new GoalResponse(goal.getId(), goal.getName(), goal.getKind(), goal.getTargetAmount(),
                goal.getCurrency(), goal.getTargetDate(), goal.getPositionIds().stream().sorted().toList(),
                table.baseCurrency(), s.target(), s.current(), s.remaining(), s.percent(), s.state(), s.monthlyPace(),
                s.projectedDate(), s.requiredMonthly(), s.monthsLeft(), s.year(), s.unconvertedCurrencies());
    }
}
