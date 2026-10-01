package me.luucka.finance.category;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import me.luucka.finance.budget.BudgetRepository;
import me.luucka.finance.cashflow.CashEntryRepository;
import me.luucka.finance.common.ApiException;
import me.luucka.finance.core.EntryKind;
import me.luucka.finance.core.category.CategoryTree;
import me.luucka.finance.core.category.DefaultCategories;
import me.luucka.finance.recurring.RecurringEntryRepository;
import me.luucka.finance.user.Language;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CategoryService {

    /** A category; {@code parentId} is the macro of a detail, null for a macro. */
    public record CategoryResponse(long id, String name, EntryKind kind, String color, Long parentId) {
        static CategoryResponse of(Category c) {
            return new CategoryResponse(c.getId(), c.getName(), c.getKind(), c.getColor(), c.getParentId());
        }
    }

    /** Categories an account can have, macros and details together (the defaults are about 50). */
    static final int MAX_CATEGORIES = 500;

    private final CategoryRepository categories;
    private final CashEntryRepository entries;
    private final RecurringEntryRepository recurring;
    private final BudgetRepository budgets;

    public CategoryService(CategoryRepository categories, CashEntryRepository entries,
                           RecurringEntryRepository recurring, BudgetRepository budgets) {
        this.categories = categories;
        this.entries = entries;
        this.recurring = recurring;
        this.budgets = budgets;
    }

    /** The categories every new user starts with, named in their language; afterwards they are the user's own. */
    @Transactional
    public void createDefaults(long userId, Language language) {
        for (DefaultCategories.Macro d : DefaultCategories.MACROS) {
            Category macro = categories.save(new Category(userId, d.name().in(language.name()), d.kind(), d.color(),
                    null));
            for (DefaultCategories.Name detail : d.details()) {
                categories.save(new Category(userId, detail.in(language.name()), d.kind(), d.color(),
                        macro.getId()));
            }
        }
    }

    @Transactional(readOnly = true)
    public List<CategoryResponse> list(long userId) {
        return categories.findByUserIdOrderByKindAscNameAsc(userId).stream().map(CategoryResponse::of).toList();
    }

    /** Creates a macro category or, with {@code parentId}, a detail under one of the user's macros. */
    @Transactional
    public CategoryResponse create(long userId, String name, EntryKind kind, String color, Long parentId) {
        if (!kind.hasCategory()) {
            throw ApiException.badRequest("transfer_category", "Transfers have no categories");
        }
        if (categories.countByUserId(userId) >= MAX_CATEGORIES) {
            throw ApiException.badRequest("too_many_categories", "At most " + MAX_CATEGORIES + " categories");
        }
        if (parentId != null) {
            parent(userId, parentId, kind, null);
        }
        String trimmed = name.trim();
        if (nameTaken(userId, kind, parentId, trimmed)) {
            throw ApiException.conflict("category_exists", "A category with this name already exists");
        }
        return CategoryResponse.of(categories.save(new Category(userId, trimmed, kind, color, parentId)));
    }

    /**
     * Renames and recolours a category and places it: under the macro {@code parentId}, or as a macro
     * when null. A macro with details cannot become a detail, and a macro and its details never both
     * have a budget.
     */
    @Transactional
    public CategoryResponse update(long userId, long id, String name, String color, Long parentId) {
        Category category = get(userId, id);
        String trimmed = name.trim();
        if (!Objects.equals(category.getParentId(), parentId)) {
            if (parentId != null) {
                Category parent = parent(userId, parentId, category.getKind(), category.getId());
                if (categories.existsByParentId(category.getId())) {
                    throw ApiException.conflict("category_has_details", "The category has detail categories");
                }
                if (budgets.existsByCategoryId(category.getId()) && budgets.existsByCategoryId(parent.getId())) {
                    throw ApiException.conflict("budget_conflict",
                            "A macro category and its details cannot both have a budget");
                }
            }
        } else if (category.getName().equalsIgnoreCase(trimmed)) {
            category.setName(trimmed);
            category.setColor(color);
            return CategoryResponse.of(category);
        }
        if (nameTaken(userId, category.getKind(), parentId, trimmed)) {
            throw ApiException.conflict("category_exists", "A category with this name already exists");
        }
        category.setName(trimmed);
        category.setColor(color);
        category.setParentId(parentId);
        return CategoryResponse.of(category);
    }

    @Transactional
    public void delete(long userId, long id) {
        Category category = get(userId, id);
        if (categories.existsByParentId(category.getId())) {
            throw ApiException.conflict("category_has_details", "The category has detail categories");
        }
        if (entries.existsByCategoryId(category.getId()) || recurring.existsByCategoryId(category.getId())) {
            throw ApiException.conflict("category_in_use", "The category is used by existing entries");
        }
        categories.delete(category);
    }

    /** The user's categories in their two levels. */
    @Transactional(readOnly = true)
    public CategoryTree tree(long userId) {
        return tree(categories.findByUserIdOrderByKindAscNameAsc(userId));
    }

    public static CategoryTree tree(List<Category> categories) {
        return new CategoryTree(categories.stream()
                .map(c -> new CategoryTree.Node(c.getId(), c.getParentId(), c.getName(), c.getKind(), c.getColor()))
                .toList());
    }

    /** A macro of the user of the given kind that {@code child} (when not null) may be placed under. */
    private Category parent(long userId, long parentId, EntryKind kind, Long child) {
        Category parent = categories.findByIdAndUserId(parentId, userId)
                .orElseThrow(() -> ApiException.badRequest("category_parent_invalid",
                        "The parent category does not exist"));
        if (!parent.isMacro() || parent.getKind() != kind || parent.getId().equals(child)) {
            throw ApiException.badRequest("category_parent_invalid",
                    "The parent must be a macro category of the same kind");
        }
        return parent;
    }

    private boolean nameTaken(long userId, EntryKind kind, Long parentId, String name) {
        return parentId == null
                ? categories.existsByUserIdAndKindAndParentIdIsNullAndNameIgnoreCase(userId, kind, name)
                : categories.existsByParentIdAndNameIgnoreCase(parentId, name);
    }

    /** A category owned by the user, if any. */
    @Transactional(readOnly = true)
    public Optional<Category> find(long userId, long id) {
        return categories.findByIdAndUserId(id, userId);
    }

    /** Every category owned by the user. */
    @Transactional(readOnly = true)
    public List<Category> owned(long userId) {
        return categories.findByUserIdOrderByKindAscNameAsc(userId);
    }

    /** Loads a category owned by the user or fails with 404 (never reveals other users' data). */
    @Transactional(readOnly = true)
    public Category get(long userId, long id) {
        return categories.findByIdAndUserId(id, userId).orElseThrow(() -> ApiException.notFound("Category"));
    }
}
