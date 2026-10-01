package me.luucka.finance.rule;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import me.luucka.finance.cashflow.CashEntryRepository;
import me.luucka.finance.category.Category;
import me.luucka.finance.category.CategoryService;
import me.luucka.finance.common.ApiException;
import me.luucka.finance.core.EntryKind;
import me.luucka.finance.core.rules.CategoryRules;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CategoryRuleService {

    /** Plenty for one household, and a bound on what one account can create. */
    static final int MAX_RULES = 500;
    /** Shorter texts would match almost any description. */
    static final int MIN_PATTERN = 2;

    public record RuleResponse(long id, String pattern, long categoryId) {
        static RuleResponse of(CategoryRule r) {
            return new RuleResponse(r.getId(), r.getPattern(), r.getCategoryId());
        }
    }

    /**
     * The category suggested for a description: from the rule {@code ruleId} (with its pattern), or
     * from the past entries when both are null.
     */
    public record SuggestionResponse(long categoryId, Long ruleId, String pattern) {
    }

    private final CategoryRuleRepository rules;
    private final CategoryService categories;
    private final CashEntryRepository entries;

    public CategoryRuleService(CategoryRuleRepository rules, CategoryService categories, CashEntryRepository entries) {
        this.rules = rules;
        this.categories = categories;
        this.entries = entries;
    }

    @Transactional(readOnly = true)
    public List<RuleResponse> list(long userId) {
        return rules.findByUserIdOrderByPatternAsc(userId).stream().map(RuleResponse::of).toList();
    }

    @Transactional
    public RuleResponse create(long userId, String pattern, long categoryId) {
        List<CategoryRule> own = rules.findByUserIdOrderByPatternAsc(userId);
        if (own.size() >= MAX_RULES) {
            throw ApiException.badRequest("too_many_rules", "At most " + MAX_RULES + " rules");
        }
        String text = check(userId, pattern, categoryId, own, null);
        return RuleResponse.of(rules.save(new CategoryRule(userId, text, categoryId)));
    }

    @Transactional
    public RuleResponse update(long userId, long id, String pattern, long categoryId) {
        CategoryRule rule = rules.findByIdAndUserId(id, userId).orElseThrow(() -> ApiException.notFound("Rule"));
        rule.setPattern(check(userId, pattern, categoryId, rules.findByUserIdOrderByPatternAsc(userId), id));
        rule.setCategoryId(categoryId);
        return RuleResponse.of(rule);
    }

    @Transactional
    public void delete(long userId, long id) {
        rules.delete(rules.findByIdAndUserId(id, userId).orElseThrow(() -> ApiException.notFound("Rule")));
    }

    /** The category for a description, from the rules or the past entries, of the kind when given. */
    @Transactional(readOnly = true)
    public SuggestionResponse suggest(long userId, String description, EntryKind kind) {
        return matcher(userId).suggest(description, kind)
                .map(s -> new SuggestionResponse(s.categoryId(), s.fromRule() ? s.rule().id() : null,
                        s.fromRule() ? s.rule().pattern() : null))
                .orElse(null);
    }

    /** The user's rules and category history, ready to categorize descriptions (an import's rows). */
    @Transactional(readOnly = true)
    public CategoryRules matcher(long userId) {
        Map<Long, Category> own = categories.owned(userId).stream()
                .collect(Collectors.toMap(Category::getId, Function.identity()));
        List<CategoryRules.Rule> list = rules.findByUserIdOrderByPatternAsc(userId).stream()
                .filter(r -> own.containsKey(r.getCategoryId()))
                .map(r -> new CategoryRules.Rule(r.getId(), r.getPattern(), r.getCategoryId(),
                        own.get(r.getCategoryId()).getKind()))
                .toList();
        List<CategoryRules.Past> past = entries.findDescribedEntries(userId).stream()
                .filter(e -> own.containsKey(e.categoryId()))
                .map(e -> new CategoryRules.Past(e.description(), e.categoryId(), e.kind(), e.date()))
                .toList();
        return new CategoryRules(list, past);
    }

    /** The pattern as stored, after checking it and the category; {@code self} is the rule being changed. */
    private String check(long userId, String pattern, long categoryId, List<CategoryRule> own, Long self) {
        String text = pattern == null ? "" : pattern.strip().replaceAll("\\s+", " ");
        String key = CategoryRules.normalize(text);
        if (key.length() < MIN_PATTERN) {
            throw ApiException.badRequest("rule_pattern_too_short", "The text must have at least " + MIN_PATTERN
                    + " letters or digits");
        }
        categories.get(userId, categoryId);
        if (own.stream().anyMatch(r -> !r.getId().equals(self) && CategoryRules.normalize(r.getPattern()).equals(key))) {
            throw ApiException.conflict("rule_exists", "A rule with this text already exists");
        }
        return text;
    }
}
