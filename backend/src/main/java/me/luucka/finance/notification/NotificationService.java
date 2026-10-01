package me.luucka.finance.notification;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.stream.Collectors;

import me.luucka.finance.budget.BudgetService;
import me.luucka.finance.category.CategoryService;
import me.luucka.finance.core.budget.BudgetCalculator;
import me.luucka.finance.core.cashflow.CashflowTotals;
import me.luucka.finance.core.category.CategoryTree;
import me.luucka.finance.core.goal.GoalCalculator;
import me.luucka.finance.dashboard.DashboardService;
import me.luucka.finance.goal.SavingsGoalService;
import me.luucka.finance.user.AppUser;
import me.luucka.finance.user.AppUserRepository;
import me.luucka.finance.user.Language;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The email alerts: budgets at 80 % and over their limit (once each per category and month), goals
 * reached (once, or once a year for a yearly goal) and the summary of the previous month (on the 1st).
 * <p>
 * Each alert has a key recorded in {@link SentAlerts} once it is sent, so a new check never repeats it.
 * When notifications are switched on, what is already true is recorded without sending ("from now on").
 */
@Service
public class NotificationService {

    public enum Kind { BUDGET, GOAL, MONTHLY }

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
    private static final int TOP_EXPENSES = 3;

    /** One line of an alert email; sending it also settles every key in {@code keys}. */
    private record Alert(Kind kind, String key, List<String> keys, MailContent.Row row) {
    }

    private final NotificationSettingsRepository settings;
    private final SentAlerts sent;
    private final AppUserRepository users;
    private final BudgetService budgets;
    private final SavingsGoalService goals;
    private final DashboardService dashboards;
    private final CategoryService categories;
    private final Mailer mailer;
    private final Clock clock;

    public NotificationService(NotificationSettingsRepository settings, SentAlerts sent, AppUserRepository users,
                               BudgetService budgets, SavingsGoalService goals, DashboardService dashboards,
                               CategoryService categories, Mailer mailer, Clock clock) {
        this.settings = settings;
        this.sent = sent;
        this.users = users;
        this.budgets = budgets;
        this.goals = goals;
        this.dashboards = dashboards;
        this.categories = categories;
        this.mailer = mailer;
        this.clock = clock;
    }

    /** Checks every user with a confirmed address; one failing user does not stop the others. */
    public void runAll() {
        if (!mailer.enabled()) {
            return;
        }
        for (Long userId : settings.findUserIdsWithEmail()) {
            try {
                run(userId);
            } catch (RuntimeException e) {
                log.warn("Notifications for user {} not sent: {}", userId, e.getMessage());
            }
        }
    }

    /**
     * Sends the user's new alerts and, from the 1st, last month's summary. Not one transaction on
     * purpose: each email is recorded as soon as it has gone, so a later failure (the summary, say)
     * cannot make an alert already sent go out again.
     */
    public void run(long userId) {
        NotificationSettings s = settings.findById(userId).orElse(null);
        AppUser user = users.findById(userId).orElse(null);
        if (s == null || s.getEmail() == null || user == null || !user.isEnabled() || !mailer.enabled()) {
            return;
        }
        LocalDate today = LocalDate.now(clock);
        Language language = user.getLanguage();

        List<Alert> alerts = alerts(userId, enabled(s), language, today);
        Set<String> handled = sent.handled(userId, alerts.stream().map(Alert::key).toList());
        List<Alert> fresh = alerts.stream().filter(a -> !handled.contains(a.key())).toList();
        if (!fresh.isEmpty()) {
            mailer.send(s.getEmail(), alertsEmail(user, fresh, YearMonth.from(today)));
            sent.markHandled(userId, fresh.stream().flatMap(a -> a.keys().stream()).toList());
        }

        if (s.isMonthlySummary()) {
            YearMonth month = YearMonth.from(today).minusMonths(1);
            String key = monthlyKey(month);
            if (sent.handled(userId, List.of(key)).isEmpty()) {
                MailContent summary = monthlyEmail(user, month);
                if (summary != null) {
                    mailer.send(s.getEmail(), summary);
                }
                sent.markHandled(userId, List.of(key));
            }
        }
    }

    /** Records the current alerts of {@code kinds} as handled without sending them. */
    @Transactional
    public void baseline(long userId, Set<Kind> kinds) {
        if (kinds.isEmpty()) {
            return;
        }
        Language language = users.findById(userId).map(AppUser::getLanguage).orElse(Language.IT);
        LocalDate today = LocalDate.now(clock);
        List<String> keys = new ArrayList<>(alerts(userId, kinds, language, today).stream()
                .flatMap(a -> a.keys().stream()).toList());
        if (kinds.contains(Kind.MONTHLY)) {
            keys.add(monthlyKey(YearMonth.from(today).minusMonths(1)));
        }
        sent.markHandled(userId, keys);
    }

    static Set<Kind> enabled(NotificationSettings s) {
        Set<Kind> kinds = EnumSet.noneOf(Kind.class);
        if (s.isBudgetAlerts()) {
            kinds.add(Kind.BUDGET);
        }
        if (s.isGoalAlerts()) {
            kinds.add(Kind.GOAL);
        }
        if (s.isMonthlySummary()) {
            kinds.add(Kind.MONTHLY);
        }
        return kinds;
    }

    private List<Alert> alerts(long userId, Set<Kind> kinds, Language language, LocalDate today) {
        List<Alert> alerts = new ArrayList<>();
        if (kinds.contains(Kind.BUDGET)) {
            BudgetService.StatusResponse status = budgets.status(userId, null);
            String currency = status.baseCurrency();
            for (BudgetService.CategoryStatusResponse c : status.categories()) {
                if (c.state() == BudgetCalculator.State.OK || c.budget() == null || c.percent() == null) {
                    continue;
                }
                String prefix = "budget:" + c.categoryId() + ":" + periodKey(c) + ":";
                boolean over = c.state() == BudgetCalculator.State.OVER;
                String percent = MailTexts.percent(c.percent(), language);
                MailContent.Row row = new MailContent.Row(budgetLabel(c, language),
                        amountOf(c.spent(), c.budget(), currency, language),
                        MailTexts.text(language, over ? "alerts.budgetOver" : "alerts.budgetWarning",
                                Map.of("percent", percent)),
                        over ? MailContent.Tone.BAD : MailContent.Tone.WARN,
                        new MailContent.Bar(c.percent().doubleValue(), null));
                alerts.add(over
                        ? new Alert(Kind.BUDGET, prefix + "100", List.of(prefix + "100", prefix + "80"), row)
                        : new Alert(Kind.BUDGET, prefix + "80", List.of(prefix + "80"), row));
            }
        }
        if (kinds.contains(Kind.GOAL)) {
            for (SavingsGoalService.GoalResponse g : goals.list(userId)) {
                if (g.state() != GoalCalculator.State.REACHED) {
                    continue;
                }
                String key = g.kind() == GoalCalculator.Kind.YEARLY
                        ? "goal:" + g.id() + ":" + (g.year() == null ? today.getYear() : g.year())
                        : "goal:" + g.id();
                alerts.add(new Alert(Kind.GOAL, key, List.of(key), new MailContent.Row(g.name(),
                        amountOf(g.current(), g.target(), g.baseCurrency(), language),
                        MailTexts.text(language, "alerts.goalReached"), MailContent.Tone.GOOD,
                        new MailContent.Bar(100, null))));
            }
        }
        return alerts;
    }

    private MailContent alertsEmail(AppUser user, List<Alert> alerts, YearMonth month) {
        Language language = user.getLanguage();
        List<MailContent.Block> blocks = new ArrayList<>();
        blocks.add(new MailContent.Paragraph(MailTexts.text(language, "alerts.intro")));
        List<MailContent.Row> budgetRows = alerts.stream().filter(a -> a.kind() == Kind.BUDGET).map(Alert::row).toList();
        if (!budgetRows.isEmpty()) {
            blocks.add(new MailContent.Section(MailTexts.text(language, "alerts.budgetHeading",
                    Map.of("month", MailTexts.month(month, language))), budgetRows));
        }
        List<MailContent.Row> goalRows = alerts.stream().filter(a -> a.kind() == Kind.GOAL).map(Alert::row).toList();
        if (!goalRows.isEmpty()) {
            blocks.add(new MailContent.Section(MailTexts.text(language, "alerts.goalsHeading"), goalRows));
        }
        String preheader = alerts.stream().map(a -> a.row().label() + ": " + a.row().detail())
                .collect(Collectors.joining(" · "));
        return new MailContent(language, MailTexts.text(language, "subject.alerts"), preheader, greeting(user), blocks);
    }

    /** Last month in numbers; null when there is nothing to tell (no entries, no positions). */
    private MailContent monthlyEmail(AppUser user, YearMonth month) {
        long userId = user.getId();
        Language language = user.getLanguage();
        DashboardService.CashflowYearResponse year = dashboards.cashflowYear(userId, month.getYear());
        CashflowTotals totals = year.months().get(month.getMonthValue() - 1).totals();
        BudgetService.StatusResponse status = budgets.status(userId, month);
        DashboardService.NetWorthDetailResponse end = dashboards.netWorthAt(userId, month.atEndOfMonth());
        DashboardService.NetWorthDetailResponse start = dashboards.netWorthAt(userId, month.minusMonths(1).atEndOfMonth());
        boolean noCashflow = totals.income().signum() == 0 && totals.expense().signum() == 0;
        if (noCashflow && end.positions().isEmpty()) {
            return null;
        }
        String currency = year.baseCurrency();
        String monthName = MailTexts.month(month, language);
        List<MailContent.Block> blocks = new ArrayList<>();
        blocks.add(new MailContent.Paragraph(MailTexts.text(language, "monthly.intro", Map.of("month", monthName))));

        List<MailContent.Stat> stats = new ArrayList<>();
        stats.add(new MailContent.Stat(MailTexts.text(language, "monthly.income"),
                MailTexts.money(totals.income(), currency, language), null, MailContent.Tone.NEUTRAL));
        stats.add(new MailContent.Stat(MailTexts.text(language, "monthly.expense"),
                MailTexts.money(totals.expense(), currency, language), null, MailContent.Tone.NEUTRAL));
        stats.add(new MailContent.Stat(MailTexts.text(language, "monthly.net"),
                MailTexts.money(totals.net(), currency, language),
                totals.savingsRate() == null ? null : MailTexts.text(language, "monthly.rate",
                        Map.of("percent", MailTexts.percent(totals.savingsRate(), language))),
                totals.net().signum() < 0 ? MailContent.Tone.BAD : MailContent.Tone.GOOD));
        if (!end.positions().isEmpty()) {
            BigDecimal change = end.total().subtract(start.total());
            stats.add(new MailContent.Stat(MailTexts.text(language, "monthly.netWorth"),
                    MailTexts.money(end.total(), currency, language),
                    MailTexts.text(language, "monthly.netWorthChange",
                            Map.of("change", MailTexts.signedMoney(change, currency, language))),
                    change.signum() < 0 ? MailContent.Tone.BAD : MailContent.Tone.NEUTRAL));
        }
        blocks.add(new MailContent.Stats(stats));

        record Spent(String name, String color, BigDecimal amount) {
        }
        // By macro category, as on the Income & expenses page: a detail's budget row adds up to its macro
        CategoryTree tree = categories.tree(userId);
        Map<Long, BigDecimal> byMacro = new LinkedHashMap<>();
        status.categories().forEach(c -> byMacro.merge(tree.macroId(c.categoryId()), c.monthSpent(), BigDecimal::add));
        status.others().forEach(o -> byMacro.merge(tree.macroId(o.categoryId()), o.spent(), BigDecimal::add));
        List<Spent> spent = byMacro.entrySet().stream().map(e -> {
            CategoryTree.Node macro = tree.node(e.getKey());
            return new Spent(macro == null ? "?" : macro.name(), macro == null ? "#6b7280" : macro.color(), e.getValue());
        }).toList();
        List<Spent> top = spent.stream()
                .filter(x -> x.amount().signum() > 0)
                .sorted(Comparator.comparing(Spent::amount).reversed())
                .limit(TOP_EXPENSES)
                .toList();
        if (!top.isEmpty()) {
            double largest = top.getFirst().amount().doubleValue();
            blocks.add(new MailContent.Section(MailTexts.text(language, "monthly.topHeading"), top.stream()
                    .map(x -> new MailContent.Row(x.name(), MailTexts.money(x.amount(), currency, language), null,
                            MailContent.Tone.NEUTRAL, new MailContent.Bar(x.amount().doubleValue() * 100 / largest, x.color())))
                    .toList()));
        }
        List<MailContent.Row> over = status.categories().stream()
                .filter(c -> c.state() == BudgetCalculator.State.OVER && c.budget() != null)
                .map(c -> new MailContent.Row(budgetLabel(c, language), amountOf(c.spent(), c.budget(), currency, language), null,
                        MailContent.Tone.BAD, new MailContent.Bar(100, null)))
                .toList();
        if (!over.isEmpty()) {
            blocks.add(new MailContent.Section(MailTexts.text(language, "monthly.overHeading"), over));
        }

        SortedSet<String> unconverted = new TreeSet<>(year.unconvertedCurrencies());
        unconverted.addAll(status.unconvertedCurrencies());
        unconverted.addAll(end.unconvertedCurrencies());
        if (!unconverted.isEmpty()) {
            blocks.add(new MailContent.Note(MailTexts.text(language, "monthly.unconverted",
                    Map.of("currencies", String.join(", ", unconverted)))));
        }
        String preheader = MailTexts.text(language, "monthly.net") + ": " + MailTexts.money(totals.net(), currency, language);
        return new MailContent(language, MailTexts.text(language, "subject.monthly", Map.of("month", monthName)),
                preheader, greeting(user), blocks);
    }

    /**
     * What a budget alert is sent once for: the category and its period (the month, as before, for
     * a monthly budget; the quarter or the year for the others).
     */
    static String periodKey(BudgetService.CategoryStatusResponse c) {
        return switch (c.period()) {
            case MONTHLY -> c.from().toString();
            case QUARTERLY -> c.from().getYear() + "-Q" + quarter(c.from());
            case YEARLY -> String.valueOf(c.from().getYear());
        };
    }

    /** A budget's name, with its quarter or year when it is not a monthly one. */
    static String budgetLabel(BudgetService.CategoryStatusResponse c, Language language) {
        String year = String.valueOf(c.from().getYear());
        return switch (c.period()) {
            case MONTHLY -> c.name();
            case QUARTERLY -> MailTexts.text(language, "budget.quarter",
                    Map.of("name", c.name(), "quarter", String.valueOf(quarter(c.from())), "year", year));
            case YEARLY -> MailTexts.text(language, "budget.year", Map.of("name", c.name(), "year", year));
        };
    }

    private static int quarter(YearMonth month) {
        return (month.getMonthValue() + 2) / 3;
    }

    static String greeting(AppUser user) {
        return MailTexts.text(user.getLanguage(), "greeting", Map.of("user", user.getUsername()));
    }

    private static String amountOf(BigDecimal amount, BigDecimal total, String currency, Language language) {
        return MailTexts.text(language, "amountOf", Map.of("amount", MailTexts.money(amount, currency, language),
                "total", MailTexts.money(total, currency, language)));
    }

    private static String monthlyKey(YearMonth month) {
        return "monthly:" + month;
    }

    /** Kinds switched on by an update: the ones to baseline. */
    static Set<Kind> switchedOn(Set<Kind> before, Set<Kind> after) {
        return after.stream().filter(k -> !before.contains(k))
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(Kind.class)));
    }
}
