package me.luucka.finance.category;

import java.util.List;

import me.luucka.finance.cashflow.CashEntryRepository;
import me.luucka.finance.common.ApiException;
import me.luucka.finance.core.EntryKind;
import me.luucka.finance.user.Language;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CategoryService {

    public record CategoryResponse(long id, String name, EntryKind kind, String color) {
        static CategoryResponse of(Category c) {
            return new CategoryResponse(c.getId(), c.getName(), c.getKind(), c.getColor());
        }
    }

    private record Default(String italian, String english, EntryKind kind, String color) {
        String name(Language language) {
            return language == Language.EN ? english : italian;
        }
    }

    /** Categories every new user starts with, named in their language; afterwards they are the user's own. */
    private static final List<Default> DEFAULTS = List.of(
            new Default("Stipendio", "Salary", EntryKind.INCOME, "#16a34a"),
            new Default("Bonus", "Bonus", EntryKind.INCOME, "#22c55e"),
            new Default("Interessi e dividendi", "Interest & dividends", EntryKind.INCOME, "#0d9488"),
            new Default("Altre entrate", "Other income", EntryKind.INCOME, "#65a30d"),
            new Default("Casa", "Housing", EntryKind.EXPENSE, "#2563eb"),
            new Default("Spesa alimentare", "Groceries", EntryKind.EXPENSE, "#ea580c"),
            new Default("Trasporti", "Transport", EntryKind.EXPENSE, "#7c3aed"),
            new Default("Assicurazioni", "Insurance", EntryKind.EXPENSE, "#0891b2"),
            new Default("Salute", "Health", EntryKind.EXPENSE, "#db2777"),
            new Default("Ristoranti", "Restaurants", EntryKind.EXPENSE, "#d97706"),
            new Default("Svago", "Leisure", EntryKind.EXPENSE, "#9333ea"),
            new Default("Viaggi", "Travel", EntryKind.EXPENSE, "#0284c7"),
            new Default("Abbonamenti", "Subscriptions", EntryKind.EXPENSE, "#4f46e5"),
            new Default("Tasse", "Taxes", EntryKind.EXPENSE, "#dc2626"),
            new Default("Altre uscite", "Other expenses", EntryKind.EXPENSE, "#6b7280"));

    private final CategoryRepository categories;
    private final CashEntryRepository entries;

    public CategoryService(CategoryRepository categories, CashEntryRepository entries) {
        this.categories = categories;
        this.entries = entries;
    }

    @Transactional
    public void createDefaults(long userId, Language language) {
        for (Default d : DEFAULTS) {
            categories.save(new Category(userId, d.name(language), d.kind(), d.color()));
        }
    }

    @Transactional(readOnly = true)
    public List<CategoryResponse> list(long userId) {
        return categories.findByUserIdOrderByKindAscNameAsc(userId).stream().map(CategoryResponse::of).toList();
    }

    @Transactional
    public CategoryResponse create(long userId, String name, EntryKind kind, String color) {
        String trimmed = name.trim();
        if (categories.existsByUserIdAndKindAndNameIgnoreCase(userId, kind, trimmed)) {
            throw ApiException.conflict("category_exists", "A category with this name already exists");
        }
        return CategoryResponse.of(categories.save(new Category(userId, trimmed, kind, color)));
    }

    @Transactional
    public CategoryResponse update(long userId, long id, String name, String color) {
        Category category = get(userId, id);
        String trimmed = name.trim();
        if (!category.getName().equalsIgnoreCase(trimmed)
                && categories.existsByUserIdAndKindAndNameIgnoreCase(userId, category.getKind(), trimmed)) {
            throw ApiException.conflict("category_exists", "A category with this name already exists");
        }
        category.setName(trimmed);
        category.setColor(color);
        return CategoryResponse.of(category);
    }

    @Transactional
    public void delete(long userId, long id) {
        Category category = get(userId, id);
        if (entries.existsByCategoryId(category.getId())) {
            throw ApiException.conflict("category_in_use", "The category is used by existing entries");
        }
        categories.delete(category);
    }

    /** Loads a category owned by the user or fails with 404 (never reveals other users' data). */
    @Transactional(readOnly = true)
    public Category get(long userId, long id) {
        return categories.findByIdAndUserId(id, userId).orElseThrow(() -> ApiException.notFound("Category"));
    }
}
