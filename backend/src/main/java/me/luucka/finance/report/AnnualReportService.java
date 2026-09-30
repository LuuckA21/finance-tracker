package me.luucka.finance.report;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

import me.luucka.finance.cashflow.CashEntry;
import me.luucka.finance.cashflow.CashEntryRepository;
import me.luucka.finance.category.CategoryRepository;
import me.luucka.finance.category.CategoryService;
import me.luucka.finance.core.AssetClass;
import me.luucka.finance.core.EntryKind;
import me.luucka.finance.core.cashflow.CashflowTotals;
import me.luucka.finance.core.category.CategoryTree;
import me.luucka.finance.core.fx.FxTable;
import me.luucka.finance.core.report.AnnualReportCalculator;
import me.luucka.finance.fx.FxService;
import me.luucka.finance.position.AssetPosition;
import me.luucka.finance.position.AssetPositionRepository;
import me.luucka.finance.position.PositionService;
import me.luucka.finance.position.PositionSnapshotRepository;
import me.luucka.finance.tag.TagService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The yearly summary: cash flow against the year before, net worth, positions, tags, largest expenses. */
@Service
@Transactional(readOnly = true)
public class AnnualReportService {

    /**
     * A macro category's income or expenses in the year and the year before, with its {@code details}
     * when any has entries (the macro's own entries under the macro's id).
     */
    public record CategoryRow(long categoryId, String name, String color, EntryKind kind, BigDecimal amount,
                              BigDecimal previousAmount, List<DetailRow> details) {
    }

    public record DetailRow(long categoryId, String name, String color, BigDecimal amount,
                            BigDecimal previousAmount) {
    }

    public record PositionRow(long positionId, String name, AssetClass assetClass, String currency, boolean archived,
                              BigDecimal start, BigDecimal end, BigDecimal transfersIn, BigDecimal transfersOut) {
    }

    public record TagRow(long tagId, String name, int entryCount, BigDecimal income, BigDecimal expense,
                         BigDecimal transferred) {
    }

    public record ExpenseRow(long entryId, LocalDate date, Long categoryId, String description,
                             BigDecimal amount, String currency, BigDecimal amountBase) {
    }

    /**
     * @param periodEnd      31 December, or today for the current year
     * @param months         months in the period, for monthly averages
     * @param previousTotals 1 January to the same day one year earlier
     */
    public record AnnualReport(String baseCurrency, int year, LocalDate periodEnd, int months,
                               List<Integer> availableYears, CashflowTotals totals, CashflowTotals previousTotals,
                               List<CategoryRow> categories, BigDecimal netWorthStart, BigDecimal netWorthEnd,
                               List<AnnualReportCalculator.ClassChange> classes, List<PositionRow> positions,
                               List<TagRow> tags, List<ExpenseRow> largestExpenses,
                               SortedSet<String> unconvertedCurrencies) {
    }

    private final CashEntryRepository entries;
    private final CategoryRepository categories;
    private final AssetPositionRepository positions;
    private final PositionSnapshotRepository snapshots;
    private final PositionService positionService;
    private final TagService tags;
    private final FxService fx;
    private final Clock clock;

    public AnnualReportService(CashEntryRepository entries, CategoryRepository categories,
                               AssetPositionRepository positions, PositionSnapshotRepository snapshots,
                               PositionService positionService, TagService tags, FxService fx, Clock clock) {
        this.entries = entries;
        this.categories = categories;
        this.positions = positions;
        this.snapshots = snapshots;
        this.positionService = positionService;
        this.tags = tags;
        this.fx = fx;
        this.clock = clock;
    }

    public AnnualReport annual(long userId, int year) {
        LocalDate today = LocalDate.now(clock);
        FxTable table = fx.table(userId);
        List<AnnualReportCalculator.Entry> data = entries
                .findByUserIdAndDateBetween(userId, LocalDate.of(year - 1, 1, 1), LocalDate.of(year, 12, 31)).stream()
                .map(AnnualReportService::toEntry)
                .toList();
        AnnualReportCalculator.Report r = AnnualReportCalculator.compute(year, data, positionService.histories(userId),
                table, today);

        CategoryTree tree = CategoryService.tree(categories.findByUserIdOrderByKindAscNameAsc(userId));
        Map<Long, AssetPosition> positionById = positions.findByUserIdOrderByArchivedAscNameAsc(userId).stream()
                .collect(Collectors.toMap(AssetPosition::getId, Function.identity()));
        Map<Long, String> tagNames = tags.names(userId);

        List<CategoryRow> categoryRows = AnnualReportCalculator.byMacro(r.categories(), tree::macroId).stream()
                .map(m -> {
                    AnnualReportCalculator.CategoryChange c = m.total();
                    CategoryTree.Node macro = tree.node(c.categoryId());
                    List<DetailRow> details = m.details().stream().map(d -> {
                        CategoryTree.Node node = tree.node(d.categoryId());
                        return new DetailRow(d.categoryId(), name(node), color(node), d.amount(), d.previousAmount());
                    }).toList();
                    return new CategoryRow(c.categoryId(), name(macro), color(macro), c.kind(), c.amount(),
                            c.previousAmount(), details);
                }).toList();
        List<PositionRow> positionRows = r.positions().stream()
                .filter(p -> positionById.containsKey(p.positionId()))
                .map(p -> {
                    AssetPosition position = positionById.get(p.positionId());
                    return new PositionRow(p.positionId(), position.getName(), position.getAssetClass(),
                            position.getCurrency(), position.isArchived(), p.start(), p.end(), p.transfersIn(),
                            p.transfersOut());
                }).toList();
        List<TagRow> tagRows = r.tags().stream()
                .filter(t -> tagNames.containsKey(t.tagId()))
                .map(t -> new TagRow(t.tagId(), tagNames.get(t.tagId()), t.entryCount(), t.income(), t.expense(),
                        t.transferred()))
                .toList();
        List<ExpenseRow> expenseRows = r.largestExpenses().stream()
                .map(e -> new ExpenseRow(e.entryId(), e.date(), e.categoryId(), e.description(), e.amount(),
                        e.currency(), e.amountBase()))
                .toList();

        return new AnnualReport(table.baseCurrency(), year, r.periodEnd(), r.months(), years(userId, today),
                r.totals(), r.previousTotals(), categoryRows, r.netWorthStart(), r.netWorthEnd(), r.classes(),
                positionRows, tagRows, expenseRows, r.unconvertedCurrencies());
    }

    private static String name(CategoryTree.Node node) {
        return node == null ? "?" : node.name();
    }

    private static String color(CategoryTree.Node node) {
        return node == null ? "#6b7280" : node.color();
    }

    /** Years with entries or position values, up to the current one. */
    private List<Integer> years(long userId, LocalDate today) {
        SortedSet<Integer> years = new TreeSet<>(entries.findYearsWithEntries(userId));
        snapshots.findEarliestDate(userId).ifPresent(first -> {
            for (int y = first.getYear(); y <= today.getYear(); y++) {
                years.add(y);
            }
        });
        years.add(today.getYear());
        return List.copyOf(years);
    }

    private static AnnualReportCalculator.Entry toEntry(CashEntry e) {
        return new AnnualReportCalculator.Entry(e.getId(), e.getDate(), e.getKind(), e.getAmount(), e.getCurrency(),
                e.getCategoryId(), e.getFromPositionId(), e.getToPositionId(), e.getDescription(), e.getTagIds());
    }
}
