package me.luucka.finance.cashflow;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import jakarta.persistence.criteria.Predicate;
import me.luucka.finance.category.Category;
import me.luucka.finance.category.CategoryService;
import me.luucka.finance.common.ApiException;
import me.luucka.finance.common.PageResponse;
import me.luucka.finance.core.Currencies;
import me.luucka.finance.core.EntryKind;
import me.luucka.finance.core.TagNames;
import me.luucka.finance.position.AssetPosition;
import me.luucka.finance.position.AssetPositionRepository;
import me.luucka.finance.tag.TagService;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CashEntryService {

    public static final int MAX_PAGE_SIZE = 200;
    /** Rows accepted by one CSV import. */
    public static final int MAX_IMPORT_ROWS = 5000;

    /**
     * Fields of a new or updated entry (already validated for shape by the controller).
     * {@code categoryId} applies to income/expense, the positions to transfers only.
     */
    public record EntryData(LocalDate date, EntryKind kind, Long categoryId, BigDecimal amount, String currency,
                            String description, Long fromPositionId, Long toPositionId, List<String> tags) {
    }

    /** Optional list filters; {@code null} means "no filter". */
    public record Filter(LocalDate from, LocalDate to, EntryKind kind, Long categoryId, String text, Long tagId) {
    }

    /** Most entries changed or deleted together (and ids listed for a filter). */
    public static final int MAX_BULK = MAX_IMPORT_ROWS;

    public enum BulkAction { UPDATE, DELETE }

    /** {@code categoryId}, {@code addTags} and {@code removeTags} apply to {@code UPDATE} only. */
    public record BulkData(List<Long> ids, BulkAction action, Long categoryId, List<String> addTags,
                           List<String> removeTags) {
    }

    /**
     * @param updated entries changed (or deleted)
     * @param skipped entries the category does not fit: transfers and entries of the other kind
     */
    public record BulkResult(int updated, int skipped) {
    }

    /** At most {@link #MAX_BULK} ids, and how many entries match in all. */
    public record Ids(List<Long> ids, long total) {
    }

    /**
     * {@code recurringEntryId} is set when a recurring rule created the entry; {@code tags} are the
     * names of its tags, sorted.
     */
    public record EntryResponse(long id, LocalDate date, EntryKind kind, Long categoryId, BigDecimal amount,
                                String currency, String description, Long recurringEntryId,
                                Long fromPositionId, Long toPositionId, List<String> tags) {
        static EntryResponse of(CashEntry e, Map<Long, String> tagNames) {
            return new EntryResponse(e.getId(), e.getDate(), e.getKind(), e.getCategoryId(), e.getAmount(),
                    e.getCurrency(), e.getDescription(), e.getRecurringEntryId(), e.getFromPositionId(),
                    e.getToPositionId(), tagNames(e, tagNames));
        }
    }

    /** Names of an entry's tags in alphabetical order, regardless of case. */
    public static List<String> tagNames(CashEntry entry, Map<Long, String> tagNames) {
        return entry.getTagIds().stream()
                .map(tagNames::get)
                .filter(Objects::nonNull)
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    private final CashEntryRepository entries;
    private final CategoryService categories;
    private final AssetPositionRepository positions;
    private final TagService tags;

    public CashEntryService(CashEntryRepository entries, CategoryService categories,
                            AssetPositionRepository positions, TagService tags) {
        this.entries = entries;
        this.categories = categories;
        this.positions = positions;
        this.tags = tags;
    }

    @Transactional(readOnly = true)
    public PageResponse<EntryResponse> list(long userId, Filter filter, int page, int size) {
        int safeSize = Math.clamp(size, 1, MAX_PAGE_SIZE);
        int safePage = Math.max(page, 0);
        var pageable = PageRequest.of(safePage, safeSize,
                Sort.by(Sort.Order.desc("date"), Sort.Order.desc("id")));
        Map<Long, String> tagNames = tags.names(userId);
        return PageResponse.of(entries.findAll(specification(userId, filter), pageable),
                e -> EntryResponse.of(e, tagNames));
    }

    @Transactional
    public EntryResponse create(long userId, EntryData data) {
        CashEntry entry = new CashEntry(userId);
        apply(userId, entry, data);
        return EntryResponse.of(entries.save(entry), tags.names(userId));
    }

    /**
     * Creates every entry or none: one invalid row (a category or position that is not the user's,
     * a category of the other kind) rolls the whole import back and names the row.
     *
     * @return entries created
     */
    @Transactional
    public int importEntries(long userId, List<EntryData> rows) {
        if (rows.isEmpty() || rows.size() > MAX_IMPORT_ROWS) {
            throw ApiException.badRequest("import_row_count",
                    "An import must contain between 1 and " + MAX_IMPORT_ROWS + " entries");
        }
        Map<Long, Category> ownCategories = categories.owned(userId).stream()
                .collect(Collectors.toMap(Category::getId, Function.identity()));
        Map<Long, AssetPosition> ownPositions = positions.findByUserIdOrderByArchivedAscNameAsc(userId).stream()
                .collect(Collectors.toMap(AssetPosition::getId, Function.identity()));
        TagService.Resolver tagResolver = tags.resolver(userId);
        List<CashEntry> created = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            EntryData data = rows.get(i);
            EntryTargets.Targets targets;
            try {
                targets = EntryTargets.resolve(data.kind(), data.categoryId(), data.fromPositionId(),
                        data.toPositionId(), id -> Optional.ofNullable(ownCategories.get(id)),
                        id -> Optional.ofNullable(ownPositions.get(id)));
            } catch (ApiException e) {
                ApiException error = e.status() != HttpStatus.NOT_FOUND ? e : ApiException.badRequest(
                        data.kind().hasCategory() ? "import_unknown_category" : "import_unknown_position",
                        "Entry " + (i + 1) + ": the category or position is not one of yours");
                throw error.withProperty("row", i);
            }
            CashEntry entry = new CashEntry(userId);
            fill(entry, targets, data);
            try {
                entry.setTagIds(tagResolver.ids(data.tags()));
            } catch (ApiException e) {
                throw e.withProperty("row", i);
            }
            created.add(entry);
        }
        entries.saveAll(created);
        return created.size();
    }

    /** Entries matching the filter, newest first, without paging (CSV export). */
    @Transactional(readOnly = true)
    public List<CashEntry> all(long userId, Filter filter) {
        return entries.findAll(specification(userId, filter), Sort.by(Sort.Order.desc("date"), Sort.Order.desc("id")));
    }

    @Transactional
    public EntryResponse update(long userId, long id, EntryData data) {
        CashEntry entry = entries.findByIdAndUserId(id, userId).orElseThrow(() -> ApiException.notFound("Entry"));
        apply(userId, entry, data);
        return EntryResponse.of(entry, tags.names(userId));
    }

    @Transactional
    public void delete(long userId, long id) {
        CashEntry entry = entries.findByIdAndUserId(id, userId).orElseThrow(() -> ApiException.notFound("Entry"));
        entries.delete(entry);
    }

    /** The ids of the entries matching the filter, newest first, at most {@link #MAX_BULK} of them. */
    @Transactional(readOnly = true)
    public Ids ids(long userId, Filter filter) {
        var first = PageRequest.of(0, MAX_BULK, Sort.by(Sort.Order.desc("date"), Sort.Order.desc("id")));
        var page = entries.findAll(specification(userId, filter), first);
        return new Ids(page.getContent().stream().map(CashEntry::getId).toList(), page.getTotalElements());
    }

    /**
     * Changes or deletes the given entries together, all or none. A category goes to the entries
     * of its kind only (the others are counted as skipped); tags are added to and removed from all
     * of them, new names becoming new tags.
     *
     * @throws ApiException 404 when an entry or the category is not the user's, 400 when there is
     *                      nothing to change or an entry would get more tags than allowed
     */
    @Transactional
    public BulkResult bulk(long userId, BulkData data) {
        Set<Long> ids = new LinkedHashSet<>(data.ids());
        if (ids.isEmpty() || ids.size() > MAX_BULK) {
            throw ApiException.badRequest("bulk_count", "Between 1 and " + MAX_BULK + " entries at a time");
        }
        List<CashEntry> found = entries.findByUserIdAndIdIn(userId, ids);
        if (found.size() != ids.size()) {
            throw ApiException.notFound("Entry");
        }
        if (data.action() == BulkAction.DELETE) {
            entries.deleteAll(found);
            return new BulkResult(found.size(), 0);
        }

        Category category = data.categoryId() == null ? null : categories.find(userId, data.categoryId())
                .orElseThrow(() -> ApiException.notFound("Category"));
        List<String> add = data.addTags() == null ? List.of() : data.addTags();
        List<String> remove = data.removeTags() == null ? List.of() : data.removeTags();
        if (category == null && add.isEmpty() && remove.isEmpty()) {
            throw ApiException.badRequest("bulk_nothing", "Choose a category, or tags to add or remove");
        }
        Set<Long> addIds = tags.resolver(userId).ids(add);
        Set<String> removeKeys = TagNames.normalizeAll(remove).orElseThrow(TagService::invalid).stream()
                .map(TagNames::key).collect(Collectors.toSet());
        Set<Long> removeIds = tags.names(userId).entrySet().stream()
                .filter(e -> removeKeys.contains(TagNames.key(e.getValue())))
                .map(Map.Entry::getKey).collect(Collectors.toSet());

        int updated = 0;
        int skipped = 0;
        for (CashEntry entry : found) {
            boolean changed = false;
            if (category != null) {
                if (entry.getKind() != category.getKind()) {
                    skipped++;
                } else if (!category.getId().equals(entry.getCategoryId())) {
                    entry.setCategoryId(category.getId());
                    changed = true;
                }
            }
            Set<Long> next = new LinkedHashSet<>(entry.getTagIds());
            next.removeAll(removeIds);
            next.addAll(addIds);
            if (!next.equals(entry.getTagIds())) {
                if (next.size() > TagNames.MAX_PER_ENTRY) {
                    throw TagService.invalid();
                }
                entry.setTagIds(next);
                changed = true;
            }
            if (changed) {
                updated++;
            }
        }
        return new BulkResult(updated, skipped);
    }

    private void apply(long userId, CashEntry entry, EntryData data) {
        fill(entry, EntryTargets.resolve(data.kind(), data.categoryId(), data.fromPositionId(),
                data.toPositionId(), id -> categories.find(userId, id),
                id -> positions.findByIdAndUserId(id, userId)), data);
        entry.setTagIds(tags.resolver(userId).ids(data.tags()));
    }

    private static void fill(CashEntry entry, EntryTargets.Targets targets, EntryData data) {
        entry.setDate(data.date());
        entry.setKind(data.kind());
        entry.setCategoryId(targets.categoryId());
        entry.setFromPositionId(targets.fromPositionId());
        entry.setToPositionId(targets.toPositionId());
        entry.setAmount(data.amount());
        entry.setCurrency(Currencies.normalize(data.currency()));
        entry.setDescription(data.description() == null || data.description().isBlank()
                ? null : data.description().trim());
    }

    /** Every predicate is combined with the owner check, so filters can never widen access. */
    private Specification<CashEntry> specification(long userId, Filter filter) {
        // A macro takes in its details; ids that are not the user's still match only their entries
        Set<Long> categoryIds = filter.categoryId() == null ? null
                : categories.tree(userId).withDetails(filter.categoryId());
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("userId"), userId));
            if (filter.from() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.<LocalDate>get("date"), filter.from()));
            }
            if (filter.to() != null) {
                predicates.add(cb.lessThanOrEqualTo(root.<LocalDate>get("date"), filter.to()));
            }
            if (filter.kind() != null) {
                predicates.add(cb.equal(root.get("kind"), filter.kind()));
            }
            if (filter.categoryId() != null) {
                predicates.add(root.get("categoryId").in(categoryIds));
            }
            if (filter.tagId() != null) {
                // Another user's tag id matches nothing: the owner check above still applies
                predicates.add(cb.isMember(filter.tagId(), root.<Set<Long>>get("tagIds")));
            }
            if (filter.text() != null && !filter.text().isBlank()) {
                String pattern = "%" + escapeLike(filter.text().trim().toLowerCase(Locale.ROOT)) + "%";
                predicates.add(cb.like(cb.lower(root.<String>get("description")), pattern, '\\'));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
