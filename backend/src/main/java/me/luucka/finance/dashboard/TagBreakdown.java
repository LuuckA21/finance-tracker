package me.luucka.finance.dashboard;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import me.luucka.finance.core.EntryKind;
import me.luucka.finance.core.Money;
import me.luucka.finance.core.TagNames;
import me.luucka.finance.core.cashflow.CashflowTotals;
import me.luucka.finance.dashboard.DashboardService.MatrixRow;
import me.luucka.finance.dashboard.DashboardService.TagMatrix;
import me.luucka.finance.dashboard.DashboardService.TagRow;

/**
 * The year's income and expenses by tag, and by category and tag. Works on the year's entries
 * already converted to the base currency; transfers and entries without a rate are not given.
 */
final class TagBreakdown {

    /** Tags shown as columns of a matrix, the largest; the others share one column. */
    static final int MATRIX_TAGS = 8;

    /** One income or expense of the year, in the base currency, with its tags. */
    record Item(long categoryId, EntryKind kind, BigDecimal value, Set<Long> tagIds) {
    }

    /**
     * @param tags     every tag with income or expenses, largest first
     * @param matrices one per kind that has tagged entries
     */
    record Result(List<TagRow> tags, List<TagMatrix> matrices) {
    }

    private TagBreakdown() {
    }

    /**
     * @param names the user's tags by id: a tag missing here (just deleted) is left out, and an entry
     *              with only such tags counts as untagged
     */
    static Result of(List<Item> items, Map<Long, String> names, CashflowTotals totals) {
        List<TagRow> rows = new ArrayList<>();
        List<TagMatrix> matrices = new ArrayList<>();
        for (EntryKind kind : List.of(EntryKind.EXPENSE, EntryKind.INCOME)) {
            List<Item> ofKind = items.stream()
                    .filter(i -> i.kind() == kind)
                    .map(i -> new Item(i.categoryId(), kind, i.value(), known(i.tagIds(), names)))
                    .toList();
            List<TagRow> ranked = ranked(ofKind, kind, names,
                    kind == EntryKind.INCOME ? totals.income() : totals.expense());
            if (!ranked.isEmpty()) {
                rows.addAll(ranked);
                matrices.add(matrix(kind, ofKind, ranked));
            }
        }
        rows.sort(Comparator.comparing(TagRow::amount).reversed().thenComparing(r -> TagNames.key(r.name())));
        return new Result(rows, matrices);
    }

    private static Set<Long> known(Set<Long> tagIds, Map<Long, String> names) {
        return tagIds.stream().filter(names::containsKey).collect(Collectors.toUnmodifiableSet());
    }

    /** Totals per tag, largest first; an entry with several tags counts for each. */
    private static List<TagRow> ranked(List<Item> items, EntryKind kind, Map<Long, String> names, BigDecimal total) {
        Map<Long, BigDecimal> sums = new HashMap<>();
        Map<Long, Integer> counts = new HashMap<>();
        for (Item item : items) {
            for (long tagId : item.tagIds()) {
                sums.merge(tagId, item.value(), (a, b) -> a.add(b, Money.CONTEXT));
                counts.merge(tagId, 1, Integer::sum);
            }
        }
        return sums.entrySet().stream()
                .map(e -> {
                    BigDecimal amount = Money.round(e.getValue());
                    return new TagRow(e.getKey(), names.get(e.getKey()), kind, amount,
                            DashboardService.percentage(amount, total), counts.get(e.getKey()));
                })
                .sorted(Comparator.comparing(TagRow::amount).reversed().thenComparing(r -> TagNames.key(r.name())))
                .toList();
    }

    /**
     * Every category of the kind (so a row adds up to the category's total) against the largest tags,
     * the other tags together and no tag. An entry counts once in "other tags" whatever number of
     * them it has, and once in each shown tag it has.
     */
    private static TagMatrix matrix(EntryKind kind, List<Item> items, List<TagRow> ranked) {
        List<Long> columns = ranked.stream().limit(MATRIX_TAGS).map(TagRow::tagId).toList();
        Set<Long> shown = new HashSet<>(columns);
        final class Cells {
            final Map<Long, BigDecimal> tags = new HashMap<>();
            BigDecimal otherTags = BigDecimal.ZERO;
            BigDecimal untagged = BigDecimal.ZERO;
            BigDecimal total = BigDecimal.ZERO;
        }
        Map<Long, Cells> byCategory = new HashMap<>();
        for (Item item : items) {
            Cells cells = byCategory.computeIfAbsent(item.categoryId(), id -> new Cells());
            cells.total = cells.total.add(item.value(), Money.CONTEXT);
            if (item.tagIds().isEmpty()) {
                cells.untagged = cells.untagged.add(item.value(), Money.CONTEXT);
                continue;
            }
            for (long tagId : item.tagIds()) {
                if (shown.contains(tagId)) {
                    cells.tags.merge(tagId, item.value(), (a, b) -> a.add(b, Money.CONTEXT));
                }
            }
            if (!shown.containsAll(item.tagIds())) {
                cells.otherTags = cells.otherTags.add(item.value(), Money.CONTEXT);
            }
        }
        BigDecimal otherTags = BigDecimal.ZERO;
        BigDecimal untagged = BigDecimal.ZERO;
        List<MatrixRow> rows = new ArrayList<>();
        for (Map.Entry<Long, Cells> e : byCategory.entrySet()) {
            Cells cells = e.getValue();
            otherTags = otherTags.add(cells.otherTags, Money.CONTEXT);
            untagged = untagged.add(cells.untagged, Money.CONTEXT);
            Map<Long, BigDecimal> tags = new LinkedHashMap<>();
            for (long tagId : columns) {
                BigDecimal amount = cells.tags.get(tagId);
                if (amount != null) {
                    tags.put(tagId, Money.round(amount));
                }
            }
            rows.add(new MatrixRow(e.getKey(), tags, Money.round(cells.otherTags), Money.round(cells.untagged),
                    Money.round(cells.total)));
        }
        rows.sort(Comparator.comparing(MatrixRow::total).reversed().thenComparing(MatrixRow::categoryId));
        return new TagMatrix(kind, columns, rows, Money.round(otherTags), Money.round(untagged));
    }
}
