package me.luucka.finance.core.report;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.function.LongUnaryOperator;

import me.luucka.finance.core.AssetClass;
import me.luucka.finance.core.EntryKind;
import me.luucka.finance.core.Money;
import me.luucka.finance.core.cashflow.CashflowTotals;
import me.luucka.finance.core.fx.FxTable;
import me.luucka.finance.core.valuation.NetWorthCalculator;
import me.luucka.finance.core.valuation.PositionHistory;

/**
 * The summary of one calendar year, in the base currency.
 * <p>
 * The period runs from 1 January to 31 December, or to {@code today} for the current year. It is
 * compared with the same period one year earlier (1 January to the same day), so a year in progress
 * is not measured against a whole year. Net worth is valued on 31 December of the previous year and
 * at the end of the period; transfers into and out of each position during the period tell what was
 * moved there (e.g. pension contributions) apart from what its value did.
 */
public final class AnnualReportCalculator {

    /** Largest expenses listed. */
    public static final int LARGEST_EXPENSES = 10;

    private AnnualReportCalculator() {
    }

    /** An entry of the user; {@code tagIds} may be empty. */
    public record Entry(long id, LocalDate date, EntryKind kind, BigDecimal amount, String currency, Long categoryId,
                        Long fromPositionId, Long toPositionId, String description, Set<Long> tagIds) {
    }

    /** Income or expense of one category in the period and in the same period a year earlier. */
    public record CategoryChange(long categoryId, EntryKind kind, BigDecimal amount, BigDecimal previousAmount) {
    }

    /** Value of one asset class at the start and at the end of the period. */
    public record ClassChange(AssetClass assetClass, BigDecimal start, BigDecimal end) {
    }

    /**
     * One position: value at the start and at the end (null before its first snapshot, or when its
     * currency cannot be converted) and the transfers it received and sent during the period.
     */
    public record PositionChange(long positionId, BigDecimal start, BigDecimal end, BigDecimal transfersIn,
                                 BigDecimal transfersOut) {
    }

    /** What the entries of one tag add up to in the period. */
    public record TagTotals(long tagId, int entryCount, BigDecimal income, BigDecimal expense,
                            BigDecimal transferred) {
    }

    public record LargeExpense(long entryId, LocalDate date, Long categoryId, String description, BigDecimal amount,
                               String currency, BigDecimal amountBase) {
    }

    /**
     * @param periodEnd       last day counted: 31 December, or today for the current year
     * @param months          months in the period, for monthly averages (1-12)
     * @param previousTotals  same period one year earlier
     * @param netWorthStart   value of every position on 31 December of the previous year
     * @param netWorthEnd     value on {@code periodEnd}
     */
    public record Report(int year, LocalDate periodEnd, int months, CashflowTotals totals,
                         CashflowTotals previousTotals, List<CategoryChange> categories, BigDecimal netWorthStart,
                         BigDecimal netWorthEnd, List<ClassChange> classes, List<PositionChange> positions,
                         List<TagTotals> tags, List<LargeExpense> largestExpenses,
                         SortedSet<String> unconvertedCurrencies) {
    }

    /**
     * @param entries   the user's entries; those outside the period and its comparison are ignored
     * @param positions snapshot history of every position of the user
     * @param today     the current date: a year in progress ends today, a future year is empty
     */
    public static Report compute(int year, Collection<Entry> entries, Collection<PositionHistory> positions,
                                 FxTable fx, LocalDate today) {
        LocalDate start = LocalDate.of(year, 1, 1);
        LocalDate yearEnd = LocalDate.of(year, 12, 31);
        LocalDate periodEnd = today.isBefore(yearEnd) && !today.isBefore(start) ? today : yearEnd;
        LocalDate previousStart = start.minusYears(1);
        LocalDate previousEnd = periodEnd.minusYears(1);
        SortedSet<String> unconverted = new TreeSet<>();

        Sums current = new Sums();
        Sums previous = new Sums();
        Map<Long, BigDecimal[]> transfers = new HashMap<>();
        Map<Long, TagSums> tags = new HashMap<>();
        List<LargeExpense> expenses = new ArrayList<>();
        for (Entry entry : entries) {
            boolean inPeriod = !entry.date().isBefore(start) && !entry.date().isAfter(periodEnd);
            boolean inPrevious = !entry.date().isBefore(previousStart) && !entry.date().isAfter(previousEnd);
            if (!inPeriod && !inPrevious) {
                continue;
            }
            Optional<BigDecimal> converted = fx.toBase(entry.amount(), entry.currency(), entry.date());
            if (converted.isEmpty()) {
                unconverted.add(entry.currency());
                continue;
            }
            BigDecimal value = converted.get();
            if (inPrevious) {
                previous.add(entry, value);
                continue;
            }
            current.add(entry, value);
            if (entry.kind() == EntryKind.TRANSFER) {
                if (entry.toPositionId() != null) {
                    add(transfers.computeIfAbsent(entry.toPositionId(), id -> zeros()), 0, value);
                }
                if (entry.fromPositionId() != null) {
                    add(transfers.computeIfAbsent(entry.fromPositionId(), id -> zeros()), 1, value);
                }
            }
            for (Long tagId : entry.tagIds()) {
                tags.computeIfAbsent(tagId, TagSums::new).add(entry.kind(), value);
            }
            if (entry.kind() == EntryKind.EXPENSE) {
                expenses.add(new LargeExpense(entry.id(), entry.date(), entry.categoryId(), entry.description(),
                        entry.amount(), entry.currency(), Money.round(value)));
            }
        }

        NetWorthCalculator.NetWorthDetail opening = NetWorthCalculator.valueAt(positions, fx, start.minusDays(1));
        NetWorthCalculator.NetWorthDetail closing = NetWorthCalculator.valueAt(positions, fx, periodEnd);
        unconverted.addAll(opening.point().unconvertedCurrencies());
        unconverted.addAll(closing.point().unconvertedCurrencies());

        expenses.sort(Comparator.comparing(LargeExpense::amountBase).reversed()
                .thenComparing(LargeExpense::date).thenComparing(LargeExpense::entryId));
        List<TagTotals> tagRows = tags.values().stream().map(TagSums::totals)
                .sorted(Comparator.comparing((TagTotals t) -> t.expense().add(t.income())).reversed()
                        .thenComparing(TagTotals::tagId))
                .toList();
        return new Report(year, periodEnd, periodEnd.getMonthValue(),
                current.totals(), previous.totals(), categories(current, previous),
                opening.point().total(), closing.point().total(),
                classes(opening.point().byClass(), closing.point().byClass()),
                positions(opening, closing, transfers), tagRows,
                List.copyOf(expenses.subList(0, Math.min(LARGEST_EXPENSES, expenses.size()))), unconverted);
    }

    /**
     * A macro category's change: {@code total} under the macro's id and, when any of its details has
     * entries, the {@code details} (the macro's own entries under its id).
     */
    public record MacroChange(CategoryChange total, List<CategoryChange> details) {
    }

    /** The categories' changes rolled up by macro, in the same order as the categories. */
    public static List<MacroChange> byMacro(List<CategoryChange> categories, LongUnaryOperator macroOf) {
        Map<Long, List<CategoryChange>> parts = new LinkedHashMap<>();
        for (CategoryChange c : categories) {
            parts.computeIfAbsent(macroOf.applyAsLong(c.categoryId()), id -> new ArrayList<>()).add(c);
        }
        List<MacroChange> rows = new ArrayList<>();
        parts.forEach((macroId, changes) -> {
            BigDecimal amount = changes.stream().map(CategoryChange::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal previous = changes.stream().map(CategoryChange::previousAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            boolean detailed = changes.stream().anyMatch(c -> c.categoryId() != macroId);
            rows.add(new MacroChange(new CategoryChange(macroId, changes.getFirst().kind(), amount, previous),
                    detailed ? changes.stream().sorted(ORDER).toList() : List.of()));
        });
        rows.sort(Comparator.comparing(MacroChange::total, ORDER));
        return rows;
    }

    /** Income before expenses, then largest first (this year, then the year before). */
    private static final Comparator<CategoryChange> ORDER = Comparator.comparing(CategoryChange::kind)
            .thenComparing(Comparator.comparing(CategoryChange::amount).reversed())
            .thenComparing(Comparator.comparing(CategoryChange::previousAmount).reversed())
            .thenComparing(CategoryChange::categoryId);

    private static List<CategoryChange> categories(Sums current, Sums previous) {
        Set<Long> ids = new HashSet<>(current.byCategory.keySet());
        ids.addAll(previous.byCategory.keySet());
        List<CategoryChange> rows = new ArrayList<>();
        for (Long id : ids) {
            EntryKind kind = current.kinds.getOrDefault(id, previous.kinds.get(id));
            rows.add(new CategoryChange(id, kind,
                    Money.round(current.byCategory.getOrDefault(id, BigDecimal.ZERO)),
                    Money.round(previous.byCategory.getOrDefault(id, BigDecimal.ZERO))));
        }
        rows.sort(ORDER);
        return rows;
    }

    private static List<ClassChange> classes(Map<AssetClass, BigDecimal> start, Map<AssetClass, BigDecimal> end) {
        Map<AssetClass, ClassChange> rows = new EnumMap<>(AssetClass.class);
        for (AssetClass c : AssetClass.values()) {
            if (start.containsKey(c) || end.containsKey(c)) {
                rows.put(c, new ClassChange(c, start.getOrDefault(c, Money.round(BigDecimal.ZERO)),
                        end.getOrDefault(c, Money.round(BigDecimal.ZERO))));
            }
        }
        return rows.values().stream()
                .sorted(Comparator.comparing(ClassChange::end).reversed().thenComparing(ClassChange::assetClass))
                .toList();
    }

    private static List<PositionChange> positions(NetWorthCalculator.NetWorthDetail opening,
                                                  NetWorthCalculator.NetWorthDetail closing,
                                                  Map<Long, BigDecimal[]> transfers) {
        Map<Long, BigDecimal> start = values(opening);
        Map<Long, BigDecimal> end = values(closing);
        Set<Long> ids = new HashSet<>(start.keySet());
        ids.addAll(end.keySet());
        ids.addAll(transfers.keySet());
        List<PositionChange> rows = new ArrayList<>();
        for (Long id : ids) {
            BigDecimal[] moved = transfers.getOrDefault(id, zeros());
            rows.add(new PositionChange(id, start.get(id), end.get(id), Money.round(moved[0]),
                    Money.round(moved[1])));
        }
        rows.sort(Comparator.comparing(PositionChange::end, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(PositionChange::start, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(PositionChange::positionId));
        return rows;
    }

    /** Converted value of each position (null when its currency has no rate). */
    private static Map<Long, BigDecimal> values(NetWorthCalculator.NetWorthDetail detail) {
        Map<Long, BigDecimal> values = new HashMap<>();
        detail.positions().forEach(p -> values.put(p.positionId(), p.valueBase()));
        return values;
    }

    private static BigDecimal[] zeros() {
        return new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO};
    }

    private static void add(BigDecimal[] sums, int index, BigDecimal value) {
        sums[index] = sums[index].add(value, Money.CONTEXT);
    }

    /** Income, expenses and transfers of a period, with income and expenses by category. */
    private static final class Sums {
        private BigDecimal income = BigDecimal.ZERO;
        private BigDecimal expense = BigDecimal.ZERO;
        private BigDecimal transferred = BigDecimal.ZERO;
        private final Map<Long, BigDecimal> byCategory = new HashMap<>();
        private final Map<Long, EntryKind> kinds = new HashMap<>();

        void add(Entry entry, BigDecimal value) {
            switch (entry.kind()) {
                case INCOME -> income = income.add(value, Money.CONTEXT);
                case EXPENSE -> expense = expense.add(value, Money.CONTEXT);
                case TRANSFER -> transferred = transferred.add(value, Money.CONTEXT);
            }
            if (entry.kind() != EntryKind.TRANSFER && entry.categoryId() != null) {
                byCategory.merge(entry.categoryId(), value, (a, b) -> a.add(b, Money.CONTEXT));
                kinds.put(entry.categoryId(), entry.kind());
            }
        }

        CashflowTotals totals() {
            return CashflowTotals.of(income, expense, transferred);
        }
    }

    private static final class TagSums {
        private final long tagId;
        private int count;
        private BigDecimal income = BigDecimal.ZERO;
        private BigDecimal expense = BigDecimal.ZERO;
        private BigDecimal transferred = BigDecimal.ZERO;

        TagSums(long tagId) {
            this.tagId = tagId;
        }

        void add(EntryKind kind, BigDecimal value) {
            count++;
            switch (kind) {
                case INCOME -> income = income.add(value, Money.CONTEXT);
                case EXPENSE -> expense = expense.add(value, Money.CONTEXT);
                case TRANSFER -> transferred = transferred.add(value, Money.CONTEXT);
            }
        }

        TagTotals totals() {
            return new TagTotals(tagId, count, Money.round(income), Money.round(expense), Money.round(transferred));
        }
    }
}
