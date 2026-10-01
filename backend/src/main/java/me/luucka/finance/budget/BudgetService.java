package me.luucka.finance.budget;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.function.Function;
import java.util.stream.Collectors;

import me.luucka.finance.cashflow.CashEntryRepository;
import me.luucka.finance.category.Category;
import me.luucka.finance.category.CategoryService;
import me.luucka.finance.common.ApiException;
import me.luucka.finance.core.Currencies;
import me.luucka.finance.core.EntryKind;
import me.luucka.finance.core.budget.BudgetCalculator;
import me.luucka.finance.core.category.CategoryTree;
import me.luucka.finance.core.fx.FxTable;
import me.luucka.finance.fx.FxService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BudgetService {

    public record BudgetResponse(long categoryId, BigDecimal amount, String currency,
                                 BudgetCalculator.Period period) {
        static BudgetResponse of(Budget b) {
            return new BudgetResponse(b.getCategoryId(), b.getAmount(), b.getCurrency(), b.getPeriod());
        }
    }

    /** Budget status of a category, with its name and colour for display. */
    public record CategoryStatusResponse(long categoryId, String name, String color, BigDecimal amount,
                                         String currency, BudgetCalculator.Period period, YearMonth from,
                                         YearMonth to, BigDecimal budget, BigDecimal spent, BigDecimal monthSpent,
                                         BigDecimal remaining,
                                         BigDecimal percent, BudgetCalculator.State state, BigDecimal projected,
                                         BigDecimal average, BigDecimal previous) {
    }

    public record UnbudgetedResponse(long categoryId, String name, String color, BigDecimal spent,
                                     BigDecimal average) {
    }

    public record StatusResponse(String baseCurrency, YearMonth month, boolean currentMonth, BigDecimal budgeted,
                                 BigDecimal spent, BigDecimal remaining, BigDecimal unbudgeted,
                                 List<CategoryStatusResponse> categories, List<UnbudgetedResponse> others,
                                 SortedSet<String> unconvertedCurrencies) {
    }

    private final BudgetRepository budgets;
    private final CategoryService categories;
    private final CashEntryRepository entries;
    private final FxService fx;
    private final Clock clock;

    public BudgetService(BudgetRepository budgets, CategoryService categories, CashEntryRepository entries,
                         FxService fx, Clock clock) {
        this.budgets = budgets;
        this.categories = categories;
        this.entries = entries;
        this.fx = fx;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<BudgetResponse> list(long userId) {
        return budgets.findByUserId(userId).stream()
                .sorted(Comparator.comparing(Budget::getCategoryId))
                .map(BudgetResponse::of)
                .toList();
    }

    /**
     * Creates or changes the budget of one of the user's expense categories, for each month, quarter
     * or year. A macro's budget covers its details too, so a macro and its details never both have one.
     */
    @Transactional
    public BudgetResponse save(long userId, long categoryId, BigDecimal amount, String currency,
                               BudgetCalculator.Period period) {
        Category category = categories.get(userId, categoryId);
        if (category.getKind() != EntryKind.EXPENSE) {
            throw ApiException.badRequest("budget_expense_only", "Budgets apply to expense categories only");
        }
        CategoryTree tree = categories.tree(userId);
        Set<Long> related = new HashSet<>(tree.withDetails(tree.macroId(categoryId)));
        related.remove(categoryId);
        if (!category.isMacro()) {
            // Sibling details may have budgets of their own; only the macro clashes
            related.retainAll(Set.of(category.getParentId()));
        }
        if (budgets.findByUserId(userId).stream().anyMatch(b -> related.contains(b.getCategoryId()))) {
            throw ApiException.conflict("budget_conflict", "A macro category and its details cannot both have a budget");
        }
        Budget budget = budgets.findByUserIdAndCategoryId(userId, categoryId)
                .orElseGet(() -> new Budget(userId, category.getId()));
        budget.setAmount(amount);
        budget.setCurrency(Currencies.normalize(currency));
        budget.setPeriod(period);
        return BudgetResponse.of(budgets.save(budget));
    }

    @Transactional
    public void delete(long userId, long categoryId) {
        budgets.delete(budgets.findByUserIdAndCategoryId(userId, categoryId)
                .orElseThrow(() -> ApiException.notFound("Budget")));
    }

    /**
     * Spending of {@code month} (current month when null) against the budgets: the month for a
     * monthly one, its quarter or year so far for the others.
     */
    @Transactional(readOnly = true)
    public StatusResponse status(long userId, YearMonth month) {
        LocalDate today = LocalDate.now(clock);
        YearMonth selected = month == null ? YearMonth.from(today) : month;
        FxTable table = fx.table(userId);

        List<Budget> own = budgets.findByUserId(userId);
        List<BudgetCalculator.BudgetLine> lines = own.stream()
                .map(b -> new BudgetCalculator.BudgetLine(b.getCategoryId(), b.getAmount(), b.getCurrency(),
                        b.getPeriod()))
                .toList();
        LocalDate from = BudgetCalculator.firstMonth(selected).atDay(1);
        CategoryTree tree = categories.tree(userId);
        Set<Long> withBudget = own.stream().map(Budget::getCategoryId).collect(Collectors.toSet());
        List<BudgetCalculator.Expense> expenses = entries.findByUserIdAndDateBetween(userId, from,
                        selected.atEndOfMonth()).stream()
                .filter(e -> e.getKind() == EntryKind.EXPENSE)
                .map(e -> new BudgetCalculator.Expense(e.getDate(), e.getAmount(), e.getCurrency(),
                        budgetCategory(tree, withBudget, e.getCategoryId()), e.getRecurringEntryId() != null))
                .toList();
        BudgetCalculator.MonthStatus result = BudgetCalculator.month(lines, expenses, table, selected, today);

        Map<Long, Budget> budgetById = own.stream().collect(Collectors.toMap(Budget::getCategoryId, Function.identity()));
        List<CategoryStatusResponse> rows = result.categories().stream().map(c -> {
            CategoryTree.Node category = tree.node(c.categoryId());
            Budget budget = budgetById.get(c.categoryId());
            return new CategoryStatusResponse(c.categoryId(), name(tree, c.categoryId()), color(category),
                    budget.getAmount(), budget.getCurrency(), c.period(), c.from(), c.to(), c.budget(), c.spent(),
                    c.monthSpent(), c.remaining(), c.percent(), c.state(), c.projected(), c.average(), c.previous());
        }).toList();
        List<UnbudgetedResponse> others = result.others().stream().map(o -> {
            return new UnbudgetedResponse(o.categoryId(), name(tree, o.categoryId()), color(tree.node(o.categoryId())),
                    o.spent(), o.average());
        }).toList();
        return new StatusResponse(table.baseCurrency(), selected, selected.equals(YearMonth.from(today)),
                result.budgeted(), result.spent(), result.remaining(), result.unbudgeted(), rows, others,
                result.unconvertedCurrencies());
    }

    /**
     * Where an expense counts: its detail when that has a budget, else its macro (whose budget, if
     * any, covers the details); spending without a budget is grouped by macro.
     */
    static long budgetCategory(CategoryTree tree, Set<Long> withBudget, long categoryId) {
        return withBudget.contains(categoryId) ? categoryId : tree.macroId(categoryId);
    }

    private static String name(CategoryTree tree, long categoryId) {
        String path = tree.path(categoryId);
        return path.isEmpty() ? "?" : path;
    }

    private static String color(CategoryTree.Node category) {
        return category == null ? "#6b7280" : category.color();
    }
}
