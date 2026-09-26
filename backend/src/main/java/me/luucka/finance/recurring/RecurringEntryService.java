package me.luucka.finance.recurring;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

import me.luucka.finance.cashflow.CashEntry;
import me.luucka.finance.cashflow.CashEntryRepository;
import me.luucka.finance.category.Category;
import me.luucka.finance.category.CategoryService;
import me.luucka.finance.common.ApiException;
import me.luucka.finance.core.Currencies;
import me.luucka.finance.core.EntryKind;
import me.luucka.finance.core.recurrence.Frequency;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RecurringEntryService {

    public record RuleData(EntryKind kind, long categoryId, BigDecimal amount, String currency, String description,
                           Frequency frequency, LocalDate startDate, LocalDate endDate, boolean active) {
    }

    public record RuleResponse(long id, EntryKind kind, long categoryId, BigDecimal amount, String currency,
                               String description, Frequency frequency, LocalDate startDate, LocalDate endDate,
                               boolean active, LocalDate lastGenerated, LocalDate nextDate) {
        static RuleResponse of(RecurringEntry r) {
            return new RuleResponse(r.getId(), r.getKind(), r.getCategoryId(), r.getAmount(), r.getCurrency(),
                    r.getDescription(), r.getFrequency(), r.getStartDate(), r.getEndDate(), r.isActive(),
                    r.getLastGenerated(), r.nextDate());
        }
    }

    private final RecurringEntryRepository rules;
    private final CashEntryRepository entries;
    private final CategoryService categories;
    private final Clock clock;

    public RecurringEntryService(RecurringEntryRepository rules, CashEntryRepository entries,
                                 CategoryService categories, Clock clock) {
        this.rules = rules;
        this.entries = entries;
        this.categories = categories;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<RuleResponse> list(long userId) {
        return rules.findByUserIdOrderByKindAscStartDateAsc(userId).stream().map(RuleResponse::of).toList();
    }

    /** Creates the rule and, when the start date is today or earlier, the entries already due. */
    @Transactional
    public RuleResponse create(long userId, RuleData data) {
        RecurringEntry rule = new RecurringEntry(userId);
        apply(userId, rule, data);
        rule.setActive(data.active());
        rule = rules.save(rule);
        generateDue(rule);
        return RuleResponse.of(rule);
    }

    /**
     * Changes apply to future occurrences only: entries already created stay as they are.
     * Resuming a paused rule skips what fell due while it was paused.
     */
    @Transactional
    public RuleResponse update(long userId, long id, RuleData data) {
        RecurringEntry rule = get(userId, id);
        boolean resuming = !rule.isActive() && data.active();
        apply(userId, rule, data);
        rule.setActive(data.active());
        if (resuming) {
            LocalDate yesterday = today().minusDays(1);
            if (rule.getLastGenerated() == null || rule.getLastGenerated().isBefore(yesterday)) {
                rule.setLastGenerated(yesterday.isBefore(rule.getStartDate()) ? null : yesterday);
            }
        }
        generateDue(rule);
        return RuleResponse.of(rule);
    }

    /** Deletes the rule; the entries it created are kept (their link is cleared by the database). */
    @Transactional
    public void delete(long userId, long id) {
        rules.delete(get(userId, id));
    }

    /** Creates the entries due for one rule; called for every active rule by the scheduler. */
    @Transactional
    public int generateDue(long ruleId) {
        return rules.findById(ruleId).map(this::generateDue).orElse(0);
    }

    private int generateDue(RecurringEntry rule) {
        if (!rule.isActive()) {
            return 0;
        }
        List<LocalDate> due = rule.schedule().due(rule.getLastGenerated(), today());
        int created = 0;
        for (LocalDate date : due) {
            // An entry may exist already (schedule changed back and forth): never create a duplicate
            if (!entries.existsByRecurringEntryIdAndDate(rule.getId(), date)) {
                CashEntry entry = new CashEntry(rule.getUserId());
                entry.setDate(date);
                entry.setKind(rule.getKind());
                entry.setCategoryId(rule.getCategoryId());
                entry.setAmount(rule.getAmount());
                entry.setCurrency(rule.getCurrency());
                entry.setDescription(rule.getDescription());
                entry.setRecurringEntryId(rule.getId());
                entries.save(entry);
                created++;
            }
            rule.setLastGenerated(date);
        }
        return created;
    }

    private void apply(long userId, RecurringEntry rule, RuleData data) {
        Category category = categories.get(userId, data.categoryId());
        if (category.getKind() != data.kind()) {
            throw ApiException.badRequest("category_kind_mismatch",
                    "The category does not match the entry type (income/expense)");
        }
        if (data.endDate() != null && data.endDate().isBefore(data.startDate())) {
            throw ApiException.badRequest("end_before_start", "The end date is before the start date");
        }
        rule.setKind(data.kind());
        rule.setCategoryId(category.getId());
        rule.setAmount(data.amount());
        rule.setCurrency(Currencies.normalize(data.currency()));
        rule.setDescription(data.description() == null || data.description().isBlank()
                ? null : data.description().trim());
        rule.setFrequency(data.frequency());
        rule.setStartDate(data.startDate());
        rule.setEndDate(data.endDate());
    }

    private RecurringEntry get(long userId, long id) {
        return rules.findByIdAndUserId(id, userId).orElseThrow(() -> ApiException.notFound("Recurring entry"));
    }

    private LocalDate today() {
        return LocalDate.now(clock);
    }
}
