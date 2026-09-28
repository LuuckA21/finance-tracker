package me.luucka.finance.cashflow;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import jakarta.persistence.criteria.Predicate;
import me.luucka.finance.category.Category;
import me.luucka.finance.category.CategoryService;
import me.luucka.finance.common.ApiException;
import me.luucka.finance.common.PageResponse;
import me.luucka.finance.core.Currencies;
import me.luucka.finance.core.EntryKind;
import me.luucka.finance.position.AssetPosition;
import me.luucka.finance.position.AssetPositionRepository;
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
                            String description, Long fromPositionId, Long toPositionId) {
    }

    /** Optional list filters; {@code null} means "no filter". */
    public record Filter(LocalDate from, LocalDate to, EntryKind kind, Long categoryId, String text) {
    }

    /** {@code recurringEntryId} is set when a recurring rule created the entry. */
    public record EntryResponse(long id, LocalDate date, EntryKind kind, Long categoryId, BigDecimal amount,
                                String currency, String description, Long recurringEntryId,
                                Long fromPositionId, Long toPositionId) {
        static EntryResponse of(CashEntry e) {
            return new EntryResponse(e.getId(), e.getDate(), e.getKind(), e.getCategoryId(), e.getAmount(),
                    e.getCurrency(), e.getDescription(), e.getRecurringEntryId(), e.getFromPositionId(),
                    e.getToPositionId());
        }
    }

    private final CashEntryRepository entries;
    private final CategoryService categories;
    private final AssetPositionRepository positions;

    public CashEntryService(CashEntryRepository entries, CategoryService categories,
                            AssetPositionRepository positions) {
        this.entries = entries;
        this.categories = categories;
        this.positions = positions;
    }

    @Transactional(readOnly = true)
    public PageResponse<EntryResponse> list(long userId, Filter filter, int page, int size) {
        int safeSize = Math.clamp(size, 1, MAX_PAGE_SIZE);
        int safePage = Math.max(page, 0);
        var pageable = PageRequest.of(safePage, safeSize,
                Sort.by(Sort.Order.desc("date"), Sort.Order.desc("id")));
        return PageResponse.of(entries.findAll(specification(userId, filter), pageable), EntryResponse::of);
    }

    @Transactional
    public EntryResponse create(long userId, EntryData data) {
        CashEntry entry = new CashEntry(userId);
        apply(userId, entry, data);
        return EntryResponse.of(entries.save(entry));
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
        return EntryResponse.of(entry);
    }

    @Transactional
    public void delete(long userId, long id) {
        CashEntry entry = entries.findByIdAndUserId(id, userId).orElseThrow(() -> ApiException.notFound("Entry"));
        entries.delete(entry);
    }

    private void apply(long userId, CashEntry entry, EntryData data) {
        fill(entry, EntryTargets.resolve(data.kind(), data.categoryId(), data.fromPositionId(),
                data.toPositionId(), id -> categories.find(userId, id),
                id -> positions.findByIdAndUserId(id, userId)), data);
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
    private static Specification<CashEntry> specification(long userId, Filter filter) {
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
                predicates.add(cb.equal(root.get("categoryId"), filter.categoryId()));
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
