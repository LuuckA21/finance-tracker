package me.luucka.finance.notification;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.stream.Collectors;

import me.luucka.finance.budget.BudgetService;
import me.luucka.finance.core.budget.BudgetCalculator;
import me.luucka.finance.core.cashflow.CashflowTotals;
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
    private record Alert(Kind kind, String key, List<String> keys, String line) {
    }

    private final NotificationSettingsRepository settings;
    private final SentAlerts sent;
    private final AppUserRepository users;
    private final BudgetService budgets;
    private final SavingsGoalService goals;
    private final DashboardService dashboards;
    private final Mailer mailer;
    private final Clock clock;

    public NotificationService(NotificationSettingsRepository settings, SentAlerts sent, AppUserRepository users,
                               BudgetService budgets, SavingsGoalService goals, DashboardService dashboards,
                               Mailer mailer, Clock clock) {
        this.settings = settings;
        this.sent = sent;
        this.users = users;
        this.budgets = budgets;
        this.goals = goals;
        this.dashboards = dashboards;
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
            mailer.send(s.getEmail(), MailTexts.text(language, "subject.alerts"),
                    alertsEmail(user, fresh, YearMonth.from(today)));
            sent.markHandled(userId, fresh.stream().flatMap(a -> a.keys().stream()).toList());
        }

        if (s.isMonthlySummary()) {
            YearMonth month = YearMonth.from(today).minusMonths(1);
            String key = monthlyKey(month);
            if (sent.handled(userId, List.of(key)).isEmpty()) {
                String body = monthlyEmail(user, month);
                if (body != null) {
                    mailer.send(s.getEmail(), MailTexts.text(language, "subject.monthly",
                            Map.of("month", MailTexts.month(month, language))), body);
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
                String prefix = "budget:" + c.categoryId() + ":" + status.month() + ":";
                Map<String, String> values = Map.of("category", c.name(),
                        "spent", MailTexts.money(c.spent(), currency, language),
                        "budget", MailTexts.money(c.budget(), currency, language),
                        "percent", MailTexts.percent(c.percent(), language));
                if (c.state() == BudgetCalculator.State.OVER) {
                    alerts.add(new Alert(Kind.BUDGET, prefix + "100", List.of(prefix + "100", prefix + "80"),
                            MailTexts.text(language, "alerts.budgetOver", values)));
                } else {
                    alerts.add(new Alert(Kind.BUDGET, prefix + "80", List.of(prefix + "80"),
                            MailTexts.text(language, "alerts.budgetWarning", values)));
                }
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
                alerts.add(new Alert(Kind.GOAL, key, List.of(key), MailTexts.text(language, "alerts.goalReached",
                        Map.of("goal", g.name(),
                                "current", MailTexts.money(g.current(), g.baseCurrency(), language),
                                "target", MailTexts.money(g.target(), g.baseCurrency(), language)))));
            }
        }
        return alerts;
    }

    private String alertsEmail(AppUser user, List<Alert> alerts, YearMonth month) {
        Language language = user.getLanguage();
        StringBuilder body = new StringBuilder();
        body.append(MailTexts.text(language, "greeting", Map.of("user", user.getUsername()))).append("\n\n")
                .append(MailTexts.text(language, "alerts.intro")).append("\n");
        section(body, MailTexts.text(language, "alerts.budgetHeading", Map.of("month", MailTexts.month(month, language))),
                alerts.stream().filter(a -> a.kind() == Kind.BUDGET).map(Alert::line).toList());
        section(body, MailTexts.text(language, "alerts.goalsHeading"),
                alerts.stream().filter(a -> a.kind() == Kind.GOAL).map(Alert::line).toList());
        return footer(body, language);
    }

    /** Last month in numbers; null when there is nothing to tell (no entries, no positions). */
    private String monthlyEmail(AppUser user, YearMonth month) {
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
        StringBuilder body = new StringBuilder();
        body.append(MailTexts.text(language, "greeting", Map.of("user", user.getUsername()))).append("\n\n")
                .append(MailTexts.text(language, "monthly.intro", Map.of("month", MailTexts.month(month, language))))
                .append("\n\n");
        body.append(MailTexts.text(language, "monthly.income",
                Map.of("amount", MailTexts.money(totals.income(), currency, language)))).append('\n');
        body.append(MailTexts.text(language, "monthly.expense",
                Map.of("amount", MailTexts.money(totals.expense(), currency, language)))).append('\n');
        body.append(totals.savingsRate() == null
                ? MailTexts.text(language, "monthly.net", Map.of("amount", MailTexts.money(totals.net(), currency, language)))
                : MailTexts.text(language, "monthly.netRate", Map.of(
                        "amount", MailTexts.money(totals.net(), currency, language),
                        "percent", MailTexts.percent(totals.savingsRate(), language)))).append('\n');

        record Spent(String name, BigDecimal amount) {
        }
        List<Spent> spent = new ArrayList<>();
        status.categories().forEach(c -> spent.add(new Spent(c.name(), c.spent())));
        status.others().forEach(o -> spent.add(new Spent(o.name(), o.spent())));
        section(body, MailTexts.text(language, "monthly.topHeading"), spent.stream()
                .filter(x -> x.amount().signum() > 0)
                .sorted(Comparator.comparing(Spent::amount).reversed())
                .limit(TOP_EXPENSES)
                .map(x -> x.name() + ": " + MailTexts.money(x.amount(), currency, language))
                .toList());
        section(body, MailTexts.text(language, "monthly.overHeading"), status.categories().stream()
                .filter(c -> c.state() == BudgetCalculator.State.OVER && c.budget() != null)
                .map(c -> MailTexts.text(language, "monthly.overLine", Map.of("category", c.name(),
                        "spent", MailTexts.money(c.spent(), currency, language),
                        "budget", MailTexts.money(c.budget(), currency, language))))
                .toList());

        if (!end.positions().isEmpty()) {
            body.append('\n').append(MailTexts.text(language, "monthly.netWorth", Map.of(
                    "amount", MailTexts.money(end.total(), currency, language),
                    "change", MailTexts.signedMoney(end.total().subtract(start.total()), currency, language))))
                    .append('\n');
        }
        SortedSet<String> unconverted = new TreeSet<>(year.unconvertedCurrencies());
        unconverted.addAll(status.unconvertedCurrencies());
        unconverted.addAll(end.unconvertedCurrencies());
        if (!unconverted.isEmpty()) {
            body.append('\n').append(MailTexts.text(language, "monthly.unconverted",
                    Map.of("currencies", String.join(", ", unconverted)))).append('\n');
        }
        return footer(body, language);
    }

    private static void section(StringBuilder body, String heading, List<String> lines) {
        if (lines.isEmpty()) {
            return;
        }
        body.append('\n').append(heading).append('\n');
        lines.forEach(line -> body.append("- ").append(line).append('\n'));
    }

    private String footer(StringBuilder body, Language language) {
        body.append("\n--\n");
        if (!mailer.appUrl().isEmpty()) {
            body.append(MailTexts.text(language, "footer.link", Map.of("url", mailer.appUrl()))).append('\n');
        }
        body.append(MailTexts.text(language, "footer.settings")).append('\n');
        return body.toString();
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
