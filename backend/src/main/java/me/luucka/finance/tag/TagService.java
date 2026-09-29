package me.luucka.finance.tag;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
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
import me.luucka.finance.common.ApiException;
import me.luucka.finance.core.EntryKind;
import me.luucka.finance.core.Money;
import me.luucka.finance.core.TagNames;
import me.luucka.finance.core.fx.FxTable;
import me.luucka.finance.fx.FxService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TagService {

    /** Plenty for one household, and a bound on what one account can create. */
    static final int MAX_TAGS = 500;

    /**
     * A tag with the totals of its entries in the base currency, all dates.
     *
     * @param firstDate  null for a tag without entries
     * @param categories income and expenses split by category, largest first (transfers have none)
     */
    public record TagSummary(long id, String name, int entryCount, BigDecimal income, BigDecimal expense,
                             BigDecimal transferred, LocalDate firstDate, LocalDate lastDate,
                             List<CategoryAmount> categories) {
    }

    /** What the entries of a tag add up to in one category, in the base currency. */
    public record CategoryAmount(long categoryId, EntryKind kind, BigDecimal amount) {
    }

    public record TagsResponse(String baseCurrency, List<TagSummary> tags, SortedSet<String> unconvertedCurrencies) {
    }

    private final TagRepository tags;
    private final CashEntryRepository entries;
    private final FxService fx;

    public TagService(TagRepository tags, CashEntryRepository entries, FxService fx) {
        this.tags = tags;
        this.entries = entries;
        this.fx = fx;
    }

    /** Names of the user's tags by id. */
    @Transactional(readOnly = true)
    public Map<Long, String> names(long userId) {
        return tags.findByUserIdOrderByNameAsc(userId).stream().collect(Collectors.toMap(Tag::getId, Tag::getName));
    }

    /** Looks up tags by name for one user, creating the missing ones; reusable for a whole import. */
    public Resolver resolver(long userId) {
        return new Resolver(userId);
    }

    @Transactional(readOnly = true)
    public TagsResponse list(long userId) {
        FxTable table = fx.table(userId);
        Map<Long, Totals> totals = new HashMap<>();
        SortedSet<String> unconverted = new TreeSet<>();
        for (CashEntry entry : entries.findTaggedByUserId(userId)) {
            Optional<BigDecimal> value = table.toBase(entry.getAmount(), entry.getCurrency(), entry.getDate());
            if (value.isEmpty()) {
                unconverted.add(entry.getCurrency());
            }
            for (Long tagId : entry.getTagIds()) {
                totals.computeIfAbsent(tagId, id -> new Totals()).add(entry, value.orElse(null));
            }
        }
        List<TagSummary> rows = tags.findByUserIdOrderByNameAsc(userId).stream()
                .sorted(Comparator.comparing(t -> TagNames.key(t.getName())))
                .map(t -> totals.getOrDefault(t.getId(), new Totals()).summary(t))
                .toList();
        return new TagsResponse(table.baseCurrency(), rows, unconverted);
    }

    @Transactional
    public TagSummary rename(long userId, long id, String name) {
        Tag tag = load(userId, id);
        String normalized = TagNames.normalize(name).orElseThrow(TagService::invalid);
        boolean taken = tags.findByUserIdOrderByNameAsc(userId).stream()
                .anyMatch(t -> !t.getId().equals(id) && TagNames.key(t.getName()).equals(TagNames.key(normalized)));
        if (taken) {
            throw ApiException.conflict("tag_exists", "A tag with this name already exists");
        }
        tag.setName(normalized);
        return list(userId).tags().stream().filter(t -> t.id() == id).findFirst().orElseThrow();
    }

    /** Deletes the tag; its entries lose it and stay as they are. */
    @Transactional
    public void delete(long userId, long id) {
        tags.delete(load(userId, id));
    }

    private Tag load(long userId, long id) {
        return tags.findByIdAndUserId(id, userId).orElseThrow(() -> ApiException.notFound("Tag"));
    }

    static ApiException invalid() {
        return ApiException.badRequest("invalid_tags", "Tags: at most " + TagNames.MAX_PER_ENTRY + " per entry, "
                + TagNames.MAX_LENGTH + " characters each, without commas");
    }

    /** Resolves names to the user's tag ids; not thread-safe, meant for one request. */
    public final class Resolver {

        private final long userId;
        private final Map<String, Tag> byKey;
        private long count;

        private Resolver(long userId) {
            this.userId = userId;
            List<Tag> own = tags.findByUserIdOrderByNameAsc(userId);
            this.byKey = own.stream().collect(Collectors.toMap(t -> TagNames.key(t.getName()), Function.identity(),
                    (a, b) -> a, HashMap::new));
            this.count = own.size();
        }

        /**
         * @throws ApiException 400 {@code invalid_tags} for a bad name or too many tags on one entry,
         *                      400 {@code too_many_tags} when the account would exceed its limit
         */
        public Set<Long> ids(List<String> names) {
            if (names == null || names.isEmpty()) {
                return Set.of();
            }
            List<String> normalized = TagNames.normalizeAll(names).orElseThrow(TagService::invalid);
            Set<Long> ids = new LinkedHashSet<>();
            for (String name : normalized) {
                Tag tag = byKey.get(TagNames.key(name));
                if (tag == null) {
                    if (count >= MAX_TAGS) {
                        throw ApiException.badRequest("too_many_tags", "At most " + MAX_TAGS + " tags");
                    }
                    tag = tags.save(new Tag(userId, name));
                    byKey.put(TagNames.key(name), tag);
                    count++;
                }
                ids.add(tag.getId());
            }
            return ids;
        }
    }

    /** Running totals of one tag's entries. */
    private static final class Totals {
        private int count;
        private BigDecimal income = BigDecimal.ZERO;
        private BigDecimal expense = BigDecimal.ZERO;
        private BigDecimal transferred = BigDecimal.ZERO;
        private LocalDate first;
        private LocalDate last;
        private final Map<Long, BigDecimal> byCategory = new HashMap<>();
        private final Map<Long, EntryKind> categoryKinds = new HashMap<>();

        void add(CashEntry entry, BigDecimal value) {
            count++;
            first = first == null || entry.getDate().isBefore(first) ? entry.getDate() : first;
            last = last == null || entry.getDate().isAfter(last) ? entry.getDate() : last;
            if (value == null) {
                return;
            }
            switch (entry.getKind()) {
                case INCOME -> income = income.add(value, Money.CONTEXT);
                case EXPENSE -> expense = expense.add(value, Money.CONTEXT);
                case TRANSFER -> transferred = transferred.add(value, Money.CONTEXT);
            }
            if (entry.getCategoryId() != null) {
                byCategory.merge(entry.getCategoryId(), value, (a, b) -> a.add(b, Money.CONTEXT));
                categoryKinds.put(entry.getCategoryId(), entry.getKind());
            }
        }

        TagSummary summary(Tag tag) {
            List<CategoryAmount> categories = byCategory.entrySet().stream()
                    .map(e -> new CategoryAmount(e.getKey(), categoryKinds.get(e.getKey()), Money.round(e.getValue())))
                    .sorted(Comparator.comparing(CategoryAmount::amount).reversed()
                            .thenComparing(CategoryAmount::categoryId))
                    .toList();
            return new TagSummary(tag.getId(), tag.getName(), count, Money.round(income), Money.round(expense),
                    Money.round(transferred), first, last, categories);
        }
    }
}
