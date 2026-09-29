package me.luucka.finance.forecast;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

import me.luucka.finance.cashflow.CashEntry;
import me.luucka.finance.cashflow.CashEntryRepository;
import me.luucka.finance.cashflow.EntryTag;
import me.luucka.finance.category.Category;
import me.luucka.finance.category.CategoryRepository;
import me.luucka.finance.common.ApiException;
import me.luucka.finance.core.EntryKind;
import me.luucka.finance.core.cashflow.CashflowTotals;
import me.luucka.finance.core.forecast.ForecastCalculator;
import me.luucka.finance.core.fx.FxTable;
import me.luucka.finance.fx.FxService;
import me.luucka.finance.tag.TagService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ForecastService {

    /** Plenty for one household, and a bound on what one account can store. */
    static final int MAX_SCENARIOS = 50;

    /** What a scenario is made of, saved or only previewed. */
    public record ScenarioData(String name, int year, BigDecimal incomeGrowth, BigDecimal expenseGrowth,
                               Set<Long> excludedTagIds, Set<Long> excludedCategoryIds, List<ItemData> items) {
    }

    public record ItemData(String description, EntryKind kind, Long categoryId, BigDecimal amount,
                           ForecastCalculator.Schedule schedule, int startMonth, Integer endMonth) {
    }

    public record ScenarioResponse(long id, String name, int year, BigDecimal incomeGrowth, BigDecimal expenseGrowth,
                                   List<Long> excludedTagIds, List<Long> excludedCategoryIds, List<ItemData> items,
                                   Instant updatedAt) {
    }

    public record MonthRow(int month, CashflowTotals base, CashflowTotals forecast) {
    }

    public record CategoryRow(long categoryId, String name, String color, EntryKind kind, BigDecimal base,
                              BigDecimal forecast) {
    }

    /**
     * The forecast in the base currency.
     *
     * @param baseFrom   first month of the base period ({@code yyyy-MM})
     * @param baseTo     last month of the base period
     * @param fromGrowth what the percentages add over the base
     * @param fromItems  what the extra items add
     * @param categories each category, base and forecast; items without a category are not in it
     * @param itemTotals each item's total in the year, in the scenario's order
     */
    public record ForecastResponse(String baseCurrency, int year, String baseFrom, String baseTo,
                                   List<MonthRow> months, CashflowTotals baseTotals, CashflowTotals totals,
                                   CashflowTotals fromGrowth, CashflowTotals fromItems, List<CategoryRow> categories,
                                   List<BigDecimal> itemTotals, SortedSet<String> unconvertedCurrencies) {
    }

    private final ForecastScenarioRepository scenarios;
    private final CashEntryRepository entries;
    private final CategoryRepository categories;
    private final TagService tags;
    private final FxService fx;
    private final Clock clock;

    public ForecastService(ForecastScenarioRepository scenarios, CashEntryRepository entries,
                           CategoryRepository categories, TagService tags, FxService fx, Clock clock) {
        this.scenarios = scenarios;
        this.entries = entries;
        this.categories = categories;
        this.tags = tags;
        this.fx = fx;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<ScenarioResponse> list(long userId) {
        return scenarios.findByUserIdOrderByYearAscNameAsc(userId).stream().map(ForecastService::response).toList();
    }

    @Transactional
    public ScenarioResponse create(long userId, ScenarioData data) {
        if (scenarios.countByUserId(userId) >= MAX_SCENARIOS) {
            throw ApiException.badRequest("too_many_scenarios", "At most " + MAX_SCENARIOS + " scenarios");
        }
        return save(userId, new ForecastScenario(userId), data);
    }

    @Transactional
    public ScenarioResponse update(long userId, long id, ScenarioData data) {
        return save(userId, load(userId, id), data);
    }

    @Transactional
    public void delete(long userId, long id) {
        scenarios.delete(load(userId, id));
    }

    /** The forecast of a scenario as given, saved or not. */
    @Transactional(readOnly = true)
    public ForecastResponse preview(long userId, ScenarioData data) {
        validate(userId, data);
        FxTable table = fx.table(userId);
        ForecastCalculator.Period base = ForecastCalculator.basePeriod(data.year(), LocalDate.now(clock));
        SortedSet<String> unconverted = new TreeSet<>();
        List<ForecastCalculator.BaseAmount> amounts = baseAmounts(userId, base, data, table, unconverted);
        ForecastCalculator.Result r = ForecastCalculator.forecast(new ForecastCalculator.Scenario(data.year(),
                data.incomeGrowth(), data.expenseGrowth(), data.items().stream()
                .map(i -> new ForecastCalculator.Item(i.kind(), i.categoryId(), i.amount(), i.schedule(),
                        i.startMonth(), i.endMonth()))
                .toList()), base, amounts);

        Map<Long, Category> byId = categories.findByUserIdOrderByKindAscNameAsc(userId).stream()
                .collect(Collectors.toMap(Category::getId, Function.identity()));
        List<CategoryRow> rows = r.categories().stream()
                .map(c -> {
                    Category category = byId.get(c.categoryId());
                    return new CategoryRow(c.categoryId(), category == null ? "?" : category.getName(),
                            category == null ? "#6b7280" : category.getColor(), c.kind(), c.base(), c.forecast());
                })
                .toList();
        return new ForecastResponse(table.baseCurrency(), data.year(), base.from().toString(), base.to().toString(),
                r.months().stream().map(m -> new MonthRow(m.month(), m.base(), m.forecast())).toList(),
                r.baseTotals(), r.forecastTotals(), r.fromGrowth(), r.fromItems(), rows, r.itemTotals(), unconverted);
    }

    /** Income and expenses of the base period, converted, without the excluded tags and categories. */
    private List<ForecastCalculator.BaseAmount> baseAmounts(long userId, ForecastCalculator.Period base,
                                                            ScenarioData data, FxTable table,
                                                            SortedSet<String> unconverted) {
        LocalDate from = base.from().atDay(1);
        LocalDate to = base.to().atEndOfMonth();
        Set<Long> excludedEntries = data.excludedTagIds().isEmpty() ? Set.of()
                : entries.findEntryTagsBetween(userId, from, to).stream()
                        .filter(t -> data.excludedTagIds().contains(t.tagId()))
                        .map(EntryTag::entryId)
                        .collect(Collectors.toSet());
        List<ForecastCalculator.BaseAmount> amounts = new ArrayList<>();
        for (CashEntry e : entries.findByUserIdAndDateBetween(userId, from, to)) {
            if (e.getKind() == EntryKind.TRANSFER || e.getCategoryId() == null
                    || data.excludedCategoryIds().contains(e.getCategoryId()) || excludedEntries.contains(e.getId())) {
                continue;
            }
            Optional<BigDecimal> value = table.toBase(e.getAmount(), e.getCurrency(), e.getDate());
            if (value.isEmpty()) {
                unconverted.add(e.getCurrency());
                continue;
            }
            amounts.add(new ForecastCalculator.BaseAmount(YearMonth.from(e.getDate()), e.getCategoryId(), e.getKind(),
                    value.get()));
        }
        return amounts;
    }

    private ScenarioResponse save(long userId, ForecastScenario scenario, ScenarioData data) {
        if (data.name() == null || data.name().isBlank()) {
            throw ApiException.badRequest("invalid_name", "A scenario needs a name");
        }
        validate(userId, data);
        scenario.setName(data.name().strip());
        scenario.setYear(data.year());
        scenario.setIncomeGrowth(data.incomeGrowth());
        scenario.setExpenseGrowth(data.expenseGrowth());
        scenario.setExcludedTagIds(data.excludedTagIds());
        scenario.setExcludedCategoryIds(data.excludedCategoryIds());
        scenario.setItems(data.items().stream()
                .map(i -> new ForecastItem(i.description().strip(), i.kind(), i.categoryId(), i.amount(), i.schedule(),
                        i.startMonth(), i.schedule() == ForecastCalculator.Schedule.ONCE ? null : i.endMonth()))
                .toList());
        return response(scenarios.saveAndFlush(scenario));
    }

    /**
     * @throws ApiException 400 for an item that is not income or expense, has no amount or ends before it
     *                      starts, or whose category is of the other kind; 404 for a tag or category of
     *                      someone else
     */
    private void validate(long userId, ScenarioData data) {
        Map<Long, Category> own = categories.findByUserIdOrderByKindAscNameAsc(userId).stream()
                .collect(Collectors.toMap(Category::getId, Function.identity()));
        requireAll(own.keySet(), data.excludedCategoryIds(), "Category");
        requireAll(tags.names(userId).keySet(), data.excludedTagIds(), "Tag");
        for (ItemData item : data.items()) {
            if (item.kind() == EntryKind.TRANSFER) {
                throw ApiException.badRequest("invalid_item_kind", "Extra items are income or expenses");
            }
            if (item.amount().signum() == 0) {
                throw ApiException.badRequest("invalid_amount", "An extra item needs an amount");
            }
            if (item.schedule() == ForecastCalculator.Schedule.MONTHLY && item.endMonth() != null
                    && item.endMonth() < item.startMonth()) {
                throw ApiException.badRequest("invalid_months", "An extra item cannot end before it starts");
            }
            if (item.categoryId() != null) {
                Category category = own.get(item.categoryId());
                if (category == null) {
                    throw ApiException.notFound("Category");
                }
                if (category.getKind() != item.kind()) {
                    throw ApiException.badRequest("category_kind_mismatch",
                            "The category of an extra item must be of the same kind");
                }
            }
        }
    }

    private static void requireAll(Set<Long> owned, Collection<Long> ids, String what) {
        if (!owned.containsAll(ids)) {
            throw ApiException.notFound(what);
        }
    }

    private ForecastScenario load(long userId, long id) {
        return scenarios.findByIdAndUserId(id, userId).orElseThrow(() -> ApiException.notFound("Scenario"));
    }

    private static ScenarioResponse response(ForecastScenario s) {
        return new ScenarioResponse(s.getId(), s.getName(), s.getYear(), s.getIncomeGrowth(), s.getExpenseGrowth(),
                s.getExcludedTagIds().stream().sorted().toList(),
                s.getExcludedCategoryIds().stream().sorted().toList(),
                s.getItems().stream()
                        .map(i -> new ItemData(i.getDescription(), i.getKind(), i.getCategoryId(), i.getAmount(),
                                i.getSchedule(), i.getStartMonth(), i.getEndMonth()))
                        .toList(),
                s.getUpdatedAt());
    }
}
