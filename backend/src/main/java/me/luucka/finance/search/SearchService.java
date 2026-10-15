package me.luucka.finance.search;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import me.luucka.finance.cashflow.CashEntryService;
import me.luucka.finance.category.Category;
import me.luucka.finance.category.CategoryService;
import me.luucka.finance.core.AssetClass;
import me.luucka.finance.core.EntryKind;
import me.luucka.finance.core.recurrence.Frequency;
import me.luucka.finance.goal.SavingsGoalRepository;
import me.luucka.finance.position.AssetPosition;
import me.luucka.finance.position.AssetPositionRepository;
import me.luucka.finance.recurring.RecurringEntryRepository;
import me.luucka.finance.tag.TagRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * One search across the user's own data: entries (by description, or by amount when the text is
 * one), categories, tags, positions (by name, ticker or IBAN), recurring entries and goals.
 * Text is matched anywhere in a name, regardless of case; names starting with it come first.
 */
@Service
public class SearchService {

    /** Shorter texts find nothing: one letter would match almost everything. */
    public static final int MIN_LENGTH = 2;
    public static final int MAX_LENGTH = 100;
    static final int MAX_ENTRIES = 8;
    static final int MAX_PER_GROUP = 5;

    /** An amount as people type it: 45.20, 45,20, 1'200.50 (the sign is ignored). */
    private static final Pattern AMOUNT = Pattern.compile("[+-]?\\d{1,15}([.,]\\d{1,4})?");

    /** {@code parentName}: the macro category of a detail, null for a macro. */
    public record CategoryHit(long id, String name, EntryKind kind, String color, String parentName) {
    }

    public record TagHit(long id, String name) {
    }

    public record PositionHit(long id, String name, String symbol, AssetClass assetClass, String currency,
                              boolean archived) {
    }

    public record RecurringHit(long id, String description, EntryKind kind, BigDecimal amount, String currency,
                               Frequency frequency, boolean active) {
    }

    public record GoalHit(long id, String name) {
    }

    /** {@code entriesTotal}: how many entries match in all; only the newest are listed. */
    public record Result(List<CashEntryService.EntryResponse> entries, long entriesTotal,
                         List<CategoryHit> categories, List<TagHit> tags, List<PositionHit> positions,
                         List<RecurringHit> recurring, List<GoalHit> goals) {

        static final Result EMPTY = new Result(List.of(), 0, List.of(), List.of(), List.of(), List.of(), List.of());
    }

    private final CashEntryService entries;
    private final CategoryService categories;
    private final TagRepository tags;
    private final AssetPositionRepository positions;
    private final RecurringEntryRepository recurring;
    private final SavingsGoalRepository goals;

    public SearchService(CashEntryService entries, CategoryService categories, TagRepository tags,
                         AssetPositionRepository positions, RecurringEntryRepository recurring,
                         SavingsGoalRepository goals) {
        this.entries = entries;
        this.categories = categories;
        this.tags = tags;
        this.positions = positions;
        this.recurring = recurring;
        this.goals = goals;
    }

    @Transactional(readOnly = true)
    public Result search(long userId, String rawText) {
        String text = rawText == null ? "" : rawText.strip().replaceAll("\\s+", " ");
        if (text.length() < MIN_LENGTH) {
            return Result.EMPTY;
        }
        String key = text.toLowerCase(Locale.ROOT);

        CashEntryService.Found found = entries.search(userId, text, amount(text), MAX_ENTRIES);

        List<Category> own = categories.owned(userId);
        Map<Long, String> names = own.stream().collect(Collectors.toMap(Category::getId, Category::getName));
        List<CategoryHit> categoryHits = best(own.stream(), Category::getName, key)
                .map(c -> new CategoryHit(c.getId(), c.getName(), c.getKind(), c.getColor(),
                        c.getParentId() == null ? null : names.get(c.getParentId())))
                .toList();

        List<TagHit> tagHits = best(tags.findByUserIdOrderByNameAsc(userId).stream(), t -> t.getName(), key)
                .map(t -> new TagHit(t.getId(), t.getName()))
                .toList();

        // A position also by its ticker or IBAN; the IBAN regardless of the spaces in it
        String compact = key.replace(" ", "");
        List<PositionHit> positionHits = positions.findByUserIdOrderByArchivedAscNameAsc(userId).stream()
                .filter(p -> contains(p.getName(), key) || contains(p.getSymbol(), key)
                        || (compact.length() >= MIN_LENGTH && contains(p.getIban(), compact)))
                .sorted(Comparator.comparing(AssetPosition::isArchived)
                        .thenComparing(p -> !startsWith(p.getName(), key))
                        .thenComparing(p -> p.getName().toLowerCase(Locale.ROOT)))
                .limit(MAX_PER_GROUP)
                .map(p -> new PositionHit(p.getId(), p.getName(), p.getSymbol(), p.getAssetClass(), p.getCurrency(),
                        p.isArchived()))
                .toList();

        List<RecurringHit> recurringHits = best(
                recurring.findByUserIdOrderByKindAscStartDateAsc(userId).stream().filter(r -> r.getDescription() != null),
                r -> r.getDescription(), key)
                .map(r -> new RecurringHit(r.getId(), r.getDescription(), r.getKind(), r.getAmount(), r.getCurrency(),
                        r.getFrequency(), r.isActive()))
                .toList();

        List<GoalHit> goalHits = best(goals.findByUserIdOrderByNameAsc(userId).stream(), g -> g.getName(), key)
                .map(g -> new GoalHit(g.getId(), g.getName()))
                .toList();

        return new Result(found.entries(), found.total(), categoryHits, tagHits, positionHits, recurringHits,
                goalHits);
    }

    /** The items whose name contains the key: those starting with it first, then alphabetically. */
    private static <T> Stream<T> best(Stream<T> items, Function<T, String> name, String key) {
        return items.filter(item -> contains(name.apply(item), key))
                .sorted(Comparator.comparing((T item) -> !startsWith(name.apply(item), key))
                        .thenComparing(item -> name.apply(item).toLowerCase(Locale.ROOT)))
                .limit(MAX_PER_GROUP);
    }

    private static boolean contains(String value, String key) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(key);
    }

    private static boolean startsWith(String value, String key) {
        return value != null && value.toLowerCase(Locale.ROOT).startsWith(key);
    }

    /** The amount the text stands for, or null when it is not one. */
    static BigDecimal amount(String text) {
        String cleaned = text.replace("'", "").replace("’", "").replace(" ", "");
        if (!AMOUNT.matcher(cleaned).matches()) {
            return null;
        }
        BigDecimal value = new BigDecimal(cleaned.replace(',', '.')).abs();
        return value.signum() == 0 ? null : value;
    }
}
