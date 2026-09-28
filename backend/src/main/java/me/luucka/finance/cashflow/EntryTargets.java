package me.luucka.finance.cashflow;

import java.util.Optional;
import java.util.function.Function;

import me.luucka.finance.category.Category;
import me.luucka.finance.common.ApiException;
import me.luucka.finance.core.EntryKind;
import me.luucka.finance.position.AssetPosition;

/**
 * What an entry points to, checked against the user's own data: a category of the same kind for
 * income and expense; for transfers no category and, optionally, two different positions.
 * Shared by single entries, recurring rules and CSV imports.
 */
public final class EntryTargets {

    /** Values to store; fields that do not apply to the kind are null. */
    public record Targets(Long categoryId, Long fromPositionId, Long toPositionId) {
    }

    private EntryTargets() {
    }

    /**
     * @param categories lookup among the user's categories only
     * @param positions  lookup among the user's positions only
     * @throws ApiException 404 for a category or position that is not the user's, 400 for a
     *                      missing category, a category of the other kind or the same position twice
     */
    public static Targets resolve(EntryKind kind, Long categoryId, Long fromPositionId, Long toPositionId,
                                  Function<Long, Optional<Category>> categories,
                                  Function<Long, Optional<AssetPosition>> positions) {
        if (kind == EntryKind.TRANSFER) {
            Long from = position(fromPositionId, positions);
            Long to = position(toPositionId, positions);
            if (from != null && from.equals(to)) {
                throw ApiException.badRequest("transfer_same_position",
                        "A transfer needs two different positions");
            }
            return new Targets(null, from, to);
        }
        if (categoryId == null) {
            throw ApiException.badRequest("category_required", "Income and expenses need a category");
        }
        Category category = categories.apply(categoryId).orElseThrow(() -> ApiException.notFound("Category"));
        if (category.getKind() != kind) {
            throw ApiException.badRequest("category_kind_mismatch",
                    "The category does not match the entry type (income/expense)");
        }
        return new Targets(category.getId(), null, null);
    }

    private static Long position(Long id, Function<Long, Optional<AssetPosition>> positions) {
        if (id == null) {
            return null;
        }
        return positions.apply(id).map(AssetPosition::getId).orElseThrow(() -> ApiException.notFound("Position"));
    }
}
