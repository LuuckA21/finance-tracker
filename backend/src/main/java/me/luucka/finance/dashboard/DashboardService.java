package me.luucka.finance.dashboard;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

import me.luucka.finance.cashflow.CashEntry;
import me.luucka.finance.cashflow.CashEntryRepository;
import me.luucka.finance.cashflow.EntryTag;
import me.luucka.finance.category.CategoryRepository;
import me.luucka.finance.category.CategoryService;
import me.luucka.finance.common.ApiException;
import me.luucka.finance.core.AssetClass;
import me.luucka.finance.core.EntryKind;
import me.luucka.finance.core.cashflow.CashflowCalculator;
import me.luucka.finance.core.cashflow.CashflowEntry;
import me.luucka.finance.core.cashflow.CashflowTotals;
import me.luucka.finance.core.cashflow.CategoryTrend;
import me.luucka.finance.core.cashflow.DatedAmount;
import me.luucka.finance.core.category.CategoryTree;
import me.luucka.finance.core.fx.FxTable;
import me.luucka.finance.core.valuation.NetWorthCalculator;
import me.luucka.finance.core.valuation.PositionHistory;
import me.luucka.finance.core.valuation.ValuationDates;
import me.luucka.finance.fx.FxService;
import me.luucka.finance.position.AssetPosition;
import me.luucka.finance.position.AssetPositionRepository;
import me.luucka.finance.position.PositionSnapshot;
import me.luucka.finance.position.PositionSnapshotRepository;
import me.luucka.finance.tag.TagService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only aggregations for the dashboards. Data is loaded per user and computed in memory
 * by the pure calculators in {@code me.luucka.finance.core}.
 */
@Service
@Transactional(readOnly = true)
public class DashboardService {

    public enum Granularity {
        MONTH,
        YEAR
    }

    public record MonthRow(int month, CashflowTotals totals) {
    }

    /**
     * A macro category's income or expenses in the year, with its {@code details} largest first when
     * any detail has entries; entries on the macro itself are then the detail with the macro's id.
     */
    public record CategoryRow(long categoryId, String name, String color, EntryKind kind, BigDecimal amount,
                              BigDecimal share, List<DetailRow> details) {
    }

    /** A detail category's part of its macro; {@code share} of the year's income or expenses. */
    public record DetailRow(long categoryId, String name, String color, BigDecimal amount, BigDecimal share) {
    }

    /** Transfers by the asset class they went to; {@code destination} null when not given. */
    public record TransferRow(AssetClass destination, BigDecimal amount, BigDecimal share) {
    }

    /**
     * The income or the expenses of one tag in the year. An entry with several tags counts for each,
     * so the shares (of the year's income or expenses) can add up to more than 100%.
     */
    public record TagRow(long tagId, String name, EntryKind kind, BigDecimal amount, BigDecimal share,
                         int entryCount) {
    }

    /**
     * One category's income or expenses split by tag: {@code tags} by tag id (only those with an
     * amount), then the other tags together and no tag; {@code total} is the category's total.
     */
    public record MatrixRow(long categoryId, Map<Long, BigDecimal> tags, BigDecimal otherTags, BigDecimal untagged,
                            BigDecimal total) {
    }

    /**
     * Categories against tags for one kind: the columns are the largest tags ({@code tagIds}, their
     * names and totals in {@code tags} of the response), then the other tags and no tag. An entry with
     * several shown tags is in each of their columns, so a row's cells can add up to more than its
     * total; "other tags" and "no tag" count each entry once.
     */
    public record TagMatrix(EntryKind kind, List<Long> tagIds, List<MatrixRow> rows, BigDecimal otherTags,
                            BigDecimal untagged) {
    }

    public record CashflowYearResponse(String baseCurrency, int year, List<MonthRow> months, CashflowTotals totals,
                                       List<CategoryRow> categories, List<TransferRow> transfers, List<TagRow> tags,
                                       List<TagMatrix> tagMatrices, List<Integer> availableYears,
                                       SortedSet<String> unconvertedCurrencies) {
    }

    public record YearRow(int year, CashflowTotals totals) {
    }

    /** A month of a category: the year's amount, the same month a year earlier, the year's amount per detail. */
    public record TrendMonth(int month, BigDecimal amount, BigDecimal previous, Map<Long, BigDecimal> details) {
    }

    /** A detail of the category (the macro's own entries under the macro's id) over the year and the year before. */
    public record TrendDetail(long categoryId, String name, BigDecimal amount, BigDecimal previous) {
    }

    /**
     * One category's income or expenses month by month (a macro with its details), against the year
     * before; {@code lastMonth} is the last month the year has had so far, {@code completedMonths} the
     * months already over, {@code toDate} and {@code previousToDate} compare the year up to today with
     * the year before up to the same day.
     */
    public record CategoryTrendResponse(String baseCurrency, int year, long categoryId, String name, String color,
                                        EntryKind kind, Long parentId, int lastMonth, int completedMonths,
                                        List<TrendMonth> months,
                                        List<TrendDetail> details, BigDecimal total, BigDecimal previousTotal,
                                        BigDecimal toDate, BigDecimal previousToDate, List<Integer> availableYears,
                                        SortedSet<String> unconvertedCurrencies) {
    }

    public record CashflowYearsResponse(String baseCurrency, List<YearRow> years,
                                        SortedSet<String> unconvertedCurrencies) {
    }

    public record NetWorthPointResponse(LocalDate date, String period, BigDecimal total,
                                        Map<AssetClass, BigDecimal> byClass) {
    }

    public record NetWorthSeriesResponse(String baseCurrency, Granularity granularity,
                                         List<NetWorthPointResponse> points, LocalDate firstSnapshotDate,
                                         SortedSet<String> unconvertedCurrencies) {
    }

    public record PositionValueRow(long positionId, String name, String symbol, AssetClass assetClass,
                                   String currency, boolean archived, LocalDate asOf, BigDecimal quantity,
                                   BigDecimal unitPrice, BigDecimal valueLocal, BigDecimal valueBase,
                                   BigDecimal share) {
    }

    public record NetWorthDetailResponse(String baseCurrency, LocalDate date, BigDecimal total,
                                         Map<AssetClass, BigDecimal> byClass, List<PositionValueRow> positions,
                                         SortedSet<String> unconvertedCurrencies) {
    }

    private final CashEntryRepository entries;
    private final CategoryRepository categories;
    private final AssetPositionRepository positions;
    private final PositionSnapshotRepository snapshots;
    private final TagService tags;
    private final FxService fxService;
    private final Clock clock;

    public DashboardService(CashEntryRepository entries, CategoryRepository categories,
                            AssetPositionRepository positions, PositionSnapshotRepository snapshots,
                            TagService tags, FxService fxService, Clock clock) {
        this.entries = entries;
        this.categories = categories;
        this.positions = positions;
        this.snapshots = snapshots;
        this.tags = tags;
        this.fxService = fxService;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ cash flow

    public CashflowYearResponse cashflowYear(long userId, int year) {
        FxTable fx = fxService.table(userId);
        Map<Long, AssetClass> classes = assetClasses(userId);
        LocalDate from = LocalDate.of(year, 1, 1);
        LocalDate to = LocalDate.of(year, 12, 31);
        List<CashEntry> yearEntries = entries.findByUserIdAndDateBetween(userId, from, to);
        List<CashflowEntry> data = yearEntries.stream().map(e -> toCashflow(e, classes)).toList();
        CashflowCalculator.YearResult result = CashflowCalculator.year(data, fx, year);

        CategoryTree tree = CategoryService.tree(categories.findByUserIdOrderByKindAscNameAsc(userId));
        List<CategoryRow> rows = categoryRows(tree, result.byCategory(), result.totals());

        List<TransferRow> transfers = result.transfers().stream()
                .map(t -> new TransferRow(t.destination(), t.amount(),
                        percentage(t.amount(), result.totals().transferred())))
                .toList();

        List<MonthRow> months = result.months().stream()
                .map(m -> new MonthRow(m.month(), m.totals()))
                .toList();
        List<Integer> years = availableYears(userId);
        TagBreakdown.Result byTag = tagBreakdown(userId, tree, yearEntries, from, to, fx, result.totals());
        return new CashflowYearResponse(fx.baseCurrency(), year, months, result.totals(), rows, transfers,
                byTag.tags(), byTag.matrices(), years, result.unconvertedCurrencies());
    }

    /** The year's totals per category rolled up by macro, largest first, each with its details. */
    static List<CategoryRow> categoryRows(CategoryTree tree, List<CashflowCalculator.CategoryResult> byCategory,
                                          CashflowTotals totals) {
        Map<Long, List<CashflowCalculator.CategoryResult>> byMacro = new LinkedHashMap<>();
        for (CashflowCalculator.CategoryResult c : byCategory) {
            byMacro.computeIfAbsent(tree.macroId(c.categoryId()), id -> new ArrayList<>()).add(c);
        }
        List<CategoryRow> rows = new ArrayList<>();
        for (Map.Entry<Long, List<CashflowCalculator.CategoryResult>> e : byMacro.entrySet()) {
            List<CashflowCalculator.CategoryResult> parts = e.getValue();
            EntryKind kind = parts.getFirst().kind();
            BigDecimal total = kind == EntryKind.INCOME ? totals.income() : totals.expense();
            BigDecimal amount = parts.stream().map(CashflowCalculator.CategoryResult::amount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            CategoryTree.Node macro = tree.node(e.getKey());
            // Only the macro's own entries: nothing to detail
            boolean detailed = parts.stream().anyMatch(p -> p.categoryId() != e.getKey());
            List<DetailRow> details = !detailed ? List.of() : parts.stream().map(p -> {
                CategoryTree.Node node = tree.node(p.categoryId());
                return new DetailRow(p.categoryId(), node == null ? "?" : node.name(), color(node), p.amount(),
                        percentage(p.amount(), total));
            }).toList();
            rows.add(new CategoryRow(e.getKey(), macro == null ? "?" : macro.name(), color(macro), kind, amount,
                    percentage(amount, total), details));
        }
        rows.sort(Comparator.comparing(CategoryRow::amount).reversed());
        return rows;
    }

    private static String color(CategoryTree.Node node) {
        return node == null ? "#6b7280" : node.color();
    }

    /** A category (with its details, for a macro) month by month in a year and the year before. */
    public CategoryTrendResponse categoryTrend(long userId, long categoryId, int year) {
        CategoryTree tree = CategoryService.tree(categories.findByUserIdOrderByKindAscNameAsc(userId));
        CategoryTree.Node category = tree.node(categoryId);
        if (category == null) {
            throw ApiException.notFound("Category");
        }
        FxTable fx = fxService.table(userId);
        Set<Long> ids = tree.withDetails(categoryId);
        List<CategoryTrend.Entry> data = entries.findByUserIdAndDateBetween(userId, LocalDate.of(year - 1, 1, 1),
                        LocalDate.of(year, 12, 31)).stream()
                .filter(e -> e.getKind() != EntryKind.TRANSFER && e.getCategoryId() != null
                        && ids.contains(e.getCategoryId()))
                .map(e -> new CategoryTrend.Entry(e.getDate(), e.getCategoryId(), e.getAmount(), e.getCurrency()))
                .toList();
        CategoryTrend.Result r = CategoryTrend.of(data, fx, year, LocalDate.now(clock));
        List<TrendDetail> details = r.parts().stream().map(p -> {
            CategoryTree.Node node = tree.node(p.categoryId());
            return new TrendDetail(p.categoryId(), node == null ? "?" : node.name(), p.amount(), p.previous());
        }).toList();
        return new CategoryTrendResponse(fx.baseCurrency(), year, categoryId, category.name(), category.color(),
                category.kind(), category.parentId(), r.lastMonth(), r.completedMonths(),
                r.months().stream().map(m -> new TrendMonth(m.month(), m.amount(), m.previous(), m.parts())).toList(),
                details, r.total(), r.previousTotal(), r.toDate(), r.previousToDate(), availableYears(userId),
                r.unconvertedCurrencies());
    }

    /** Income and expenses of the year by tag, categories rolled up by macro; transfers stay out, as in the totals. */
    private TagBreakdown.Result tagBreakdown(long userId, CategoryTree tree, List<CashEntry> yearEntries,
                                             LocalDate from, LocalDate to, FxTable fx, CashflowTotals totals) {
        Map<Long, Set<Long>> entryTags = entries.findEntryTagsBetween(userId, from, to).stream()
                .collect(Collectors.groupingBy(EntryTag::entryId,
                        Collectors.mapping(EntryTag::tagId, Collectors.toSet())));
        if (entryTags.isEmpty()) {
            return new TagBreakdown.Result(List.of(), List.of());
        }
        List<TagBreakdown.Item> items = new ArrayList<>();
        for (CashEntry e : yearEntries) {
            if (e.getKind() == EntryKind.TRANSFER || e.getCategoryId() == null) {
                continue;
            }
            // Without a rate the entry is left out of the totals too (its currency is reported there)
            fx.toBase(e.getAmount(), e.getCurrency(), e.getDate()).ifPresent(value -> items.add(new TagBreakdown.Item(
                    tree.macroId(e.getCategoryId()), e.getKind(), value, entryTags.getOrDefault(e.getId(), Set.of()))));
        }
        return TagBreakdown.of(items, tags.names(userId), totals);
    }

    /** Years with entries, and the current one. */
    private List<Integer> availableYears(long userId) {
        List<Integer> years = new ArrayList<>(entries.findYearsWithEntries(userId));
        int currentYear = LocalDate.now(clock).getYear();
        if (!years.contains(currentYear)) {
            years.add(currentYear);
        }
        years.sort(null);
        return years;
    }

    public CashflowYearsResponse cashflowYears(long userId) {
        FxTable fx = fxService.table(userId);
        // A day's sum converts like its entries: the rate is the same for one currency on one day
        List<DatedAmount> data = entries.sumByDay(userId).stream()
                .map(s -> new DatedAmount(s.date(), s.kind(), s.amount(), s.currency()))
                .toList();
        CashflowCalculator.MultiYearResult result = CashflowCalculator.years(data, fx);
        List<YearRow> rows = result.years().stream().map(y -> new YearRow(y.year(), y.totals())).toList();
        return new CashflowYearsResponse(fx.baseCurrency(), rows, result.unconvertedCurrencies());
    }

    // ------------------------------------------------------------------ net worth

    public NetWorthSeriesResponse netWorthSeries(long userId, Granularity granularity, YearMonth from, YearMonth to) {
        LocalDate today = LocalDate.now(clock);
        LocalDate first = snapshots.findEarliestDate(userId).orElse(null);
        YearMonth start = from != null ? from : first != null ? YearMonth.from(first) : YearMonth.from(today);
        YearMonth end = to != null ? to : YearMonth.from(today);
        if (start.isAfter(end)) {
            start = end;
        }
        List<LocalDate> dates = granularity == Granularity.YEAR
                ? ValuationDates.yearEnds(start.getYear(), end.getYear(), today)
                : ValuationDates.monthEnds(start, end, today);

        FxTable fx = fxService.table(userId);
        List<PositionHistory> histories = histories(userId);
        List<NetWorthCalculator.NetWorthPoint> points = NetWorthCalculator.series(histories, fx, dates);

        SortedSet<String> unconverted = new TreeSet<>();
        List<NetWorthPointResponse> rows = new ArrayList<>(points.size());
        for (NetWorthCalculator.NetWorthPoint p : points) {
            unconverted.addAll(p.unconvertedCurrencies());
            String period = granularity == Granularity.YEAR
                    ? String.valueOf(p.date().getYear())
                    : YearMonth.from(p.date()).toString();
            rows.add(new NetWorthPointResponse(p.date(), period, p.total(), p.byClass()));
        }
        return new NetWorthSeriesResponse(fx.baseCurrency(), granularity, rows, first, unconverted);
    }

    public NetWorthDetailResponse netWorthAt(long userId, LocalDate date) {
        LocalDate at = date != null ? date : LocalDate.now(clock);
        FxTable fx = fxService.table(userId);
        Map<Long, AssetPosition> byId = positions.findByUserIdOrderByArchivedAscNameAsc(userId).stream()
                .collect(Collectors.toMap(AssetPosition::getId, Function.identity()));
        NetWorthCalculator.NetWorthDetail detail = NetWorthCalculator.valueAt(histories(userId, byId), fx, at);
        BigDecimal total = detail.point().total();

        List<PositionValueRow> rows = new ArrayList<>();
        for (NetWorthCalculator.PositionValue v : detail.positions()) {
            AssetPosition p = byId.get(v.positionId());
            if (p == null) {
                continue;
            }
            rows.add(new PositionValueRow(p.getId(), p.getName(), p.getSymbol(), p.getAssetClass(), p.getCurrency(),
                    p.isArchived(), v.asOf(), v.quantity(), v.unitPrice(), v.valueLocal(), v.valueBase(),
                    v.valueBase() == null ? null : percentage(v.valueBase(), total)));
        }
        return new NetWorthDetailResponse(fx.baseCurrency(), at, total, detail.point().byClass(), rows,
                detail.point().unconvertedCurrencies());
    }

    private List<PositionHistory> histories(long userId) {
        Map<Long, AssetPosition> byId = positions.findByUserIdOrderByArchivedAscNameAsc(userId).stream()
                .collect(Collectors.toMap(AssetPosition::getId, Function.identity()));
        return histories(userId, byId);
    }

    private List<PositionHistory> histories(long userId, Map<Long, AssetPosition> byId) {
        Map<Long, List<PositionSnapshot>> grouped = snapshots.findByUserId(userId).stream()
                .collect(Collectors.groupingBy(PositionSnapshot::getPositionId));
        List<PositionHistory> result = new ArrayList<>();
        grouped.forEach((positionId, list) -> {
            AssetPosition p = byId.get(positionId);
            if (p != null) {
                result.add(new PositionHistory(positionId, p.getAssetClass(), p.getCurrency(),
                        list.stream().map(PositionSnapshot::toValuation).toList()));
            }
        });
        return result;
    }

    private static CashflowEntry toCashflow(CashEntry e, Map<Long, AssetClass> classes) {
        AssetClass destination = e.getToPositionId() == null ? null : classes.get(e.getToPositionId());
        return new CashflowEntry(e.getDate(), e.getKind(), e.getAmount(), e.getCurrency(), e.getCategoryId(),
                destination);
    }

    /** Asset class of every position of the user, for the destination of transfers. */
    private Map<Long, AssetClass> assetClasses(long userId) {
        return positions.findByUserIdOrderByArchivedAscNameAsc(userId).stream()
                .collect(Collectors.toMap(AssetPosition::getId, AssetPosition::getAssetClass));
    }

    static BigDecimal percentage(BigDecimal part, BigDecimal total) {
        if (total == null || total.signum() == 0) {
            return null;
        }
        return part.multiply(BigDecimal.valueOf(100)).divide(total, 1, RoundingMode.HALF_EVEN);
    }
}
