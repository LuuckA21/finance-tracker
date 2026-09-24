package me.luucka.finance.dashboard;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

import me.luucka.finance.cashflow.CashEntry;
import me.luucka.finance.cashflow.CashEntryRepository;
import me.luucka.finance.category.Category;
import me.luucka.finance.category.CategoryRepository;
import me.luucka.finance.core.AssetClass;
import me.luucka.finance.core.EntryKind;
import me.luucka.finance.core.cashflow.CashflowCalculator;
import me.luucka.finance.core.cashflow.CashflowEntry;
import me.luucka.finance.core.cashflow.CashflowTotals;
import me.luucka.finance.core.fx.FxTable;
import me.luucka.finance.core.valuation.NetWorthCalculator;
import me.luucka.finance.core.valuation.PositionHistory;
import me.luucka.finance.core.valuation.ValuationDates;
import me.luucka.finance.fx.FxService;
import me.luucka.finance.position.AssetPosition;
import me.luucka.finance.position.AssetPositionRepository;
import me.luucka.finance.position.PositionSnapshot;
import me.luucka.finance.position.PositionSnapshotRepository;
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

    public record CategoryRow(long categoryId, String name, String color, EntryKind kind, BigDecimal amount,
                              BigDecimal share) {
    }

    public record CashflowYearResponse(String baseCurrency, int year, List<MonthRow> months, CashflowTotals totals,
                                       List<CategoryRow> categories, List<Integer> availableYears,
                                       SortedSet<String> unconvertedCurrencies) {
    }

    public record YearRow(int year, CashflowTotals totals) {
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
    private final FxService fxService;
    private final Clock clock;

    public DashboardService(CashEntryRepository entries, CategoryRepository categories,
                            AssetPositionRepository positions, PositionSnapshotRepository snapshots,
                            FxService fxService, Clock clock) {
        this.entries = entries;
        this.categories = categories;
        this.positions = positions;
        this.snapshots = snapshots;
        this.fxService = fxService;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ cash flow

    public CashflowYearResponse cashflowYear(long userId, int year) {
        FxTable fx = fxService.table(userId);
        List<CashflowEntry> data = entries.findByUserIdAndDateBetween(userId,
                        LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31)).stream()
                .map(DashboardService::toCashflow)
                .toList();
        CashflowCalculator.YearResult result = CashflowCalculator.year(data, fx, year);

        Map<Long, Category> byId = categories.findByUserIdOrderByKindAscNameAsc(userId).stream()
                .collect(Collectors.toMap(Category::getId, Function.identity()));
        List<CategoryRow> rows = new ArrayList<>();
        for (CashflowCalculator.CategoryResult c : result.byCategory()) {
            Category category = byId.get(c.categoryId());
            BigDecimal total = c.kind() == EntryKind.INCOME ? result.totals().income() : result.totals().expense();
            rows.add(new CategoryRow(c.categoryId(),
                    category == null ? "?" : category.getName(),
                    category == null ? "#6b7280" : category.getColor(),
                    c.kind(), c.amount(), percentage(c.amount(), total)));
        }

        List<MonthRow> months = result.months().stream()
                .map(m -> new MonthRow(m.month(), m.totals()))
                .toList();
        List<Integer> years = new ArrayList<>(entries.findYearsWithEntries(userId));
        int currentYear = LocalDate.now(clock).getYear();
        if (!years.contains(currentYear)) {
            years.add(currentYear);
        }
        years.sort(null);
        return new CashflowYearResponse(fx.baseCurrency(), year, months, result.totals(), rows, years,
                result.unconvertedCurrencies());
    }

    public CashflowYearsResponse cashflowYears(long userId) {
        FxTable fx = fxService.table(userId);
        List<CashflowEntry> data = entries.findByUserId(userId).stream().map(DashboardService::toCashflow).toList();
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

    private static CashflowEntry toCashflow(CashEntry e) {
        return new CashflowEntry(e.getDate(), e.getKind(), e.getAmount(), e.getCurrency(), e.getCategoryId());
    }

    private static BigDecimal percentage(BigDecimal part, BigDecimal total) {
        if (total == null || total.signum() == 0) {
            return null;
        }
        return part.multiply(BigDecimal.valueOf(100)).divide(total, 1, RoundingMode.HALF_EVEN);
    }
}
