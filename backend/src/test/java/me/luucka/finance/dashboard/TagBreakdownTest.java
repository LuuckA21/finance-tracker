package me.luucka.finance.dashboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.LongStream;

import me.luucka.finance.core.EntryKind;
import me.luucka.finance.core.cashflow.CashflowTotals;
import me.luucka.finance.dashboard.DashboardService.MatrixRow;
import me.luucka.finance.dashboard.DashboardService.TagMatrix;
import org.junit.jupiter.api.Test;

class TagBreakdownTest {

    private static final long TRAVEL = 1;
    private static final long FOOD = 2;
    private static final long SALARY = 3;

    private static TagBreakdown.Item expense(long category, int value, Long... tags) {
        return new TagBreakdown.Item(category, EntryKind.EXPENSE, BigDecimal.valueOf(value), Set.of(tags));
    }

    private static CashflowTotals totals(int income, int expense) {
        return new CashflowTotals(BigDecimal.valueOf(income), BigDecimal.valueOf(expense),
                BigDecimal.valueOf(income - expense), null, BigDecimal.ZERO);
    }

    private static double amount(BigDecimal value) {
        return value.doubleValue();
    }

    @Test
    void rowsAddUpToTheCategoryAndEntriesCountOnceOutsideTheShownTags() {
        Map<Long, String> names = Map.of(10L, "Vacanze", 11L, "Famiglia");
        List<TagBreakdown.Item> items = List.of(
                expense(TRAVEL, 800, 10L, 11L),
                expense(FOOD, 120, 10L),
                expense(FOOD, 80),
                new TagBreakdown.Item(SALARY, EntryKind.INCOME, BigDecimal.valueOf(6000), Set.of(11L)));

        TagBreakdown.Result result = TagBreakdown.of(items, names, totals(6000, 1000));

        assertEquals(List.of("Famiglia/INCOME", "Vacanze/EXPENSE", "Famiglia/EXPENSE"),
                result.tags().stream().map(t -> t.name() + "/" + t.kind()).toList());
        TagMatrix expenses = result.matrices().getFirst();
        assertEquals(EntryKind.EXPENSE, expenses.kind());
        assertEquals(List.of(10L, 11L), expenses.tagIds());
        // Largest category first; the ferry is in both columns, the pizza only in "no tag"
        MatrixRow travel = expenses.rows().get(0);
        assertEquals(TRAVEL, travel.categoryId());
        assertEquals(Map.of(10L, new BigDecimal("800.00"), 11L, new BigDecimal("800.00")), travel.tags());
        assertEquals(800.0, amount(travel.total()));
        MatrixRow food = expenses.rows().get(1);
        assertEquals(Map.of(10L, new BigDecimal("120.00")), food.tags());
        assertEquals(80.0, amount(food.untagged()));
        assertEquals(200.0, amount(food.total()));
        assertEquals(0.0, amount(expenses.otherTags()));
        assertEquals(80.0, amount(expenses.untagged()));
        assertEquals(EntryKind.INCOME, result.matrices().get(1).kind());
    }

    @Test
    void tagsPastTheLargestEightShareOneColumnThatCountsAnEntryOnce() {
        Map<Long, String> names = new HashMap<>();
        List<TagBreakdown.Item> items = new ArrayList<>();
        // Tags 1..10, tag n on one expense of 100 × n: the largest are 10..3
        LongStream.rangeClosed(1, 10).forEach(n -> {
            names.put(n, "t" + n);
            items.add(expense(FOOD, (int) (100 * n), n));
        });
        // One more entry with the two smallest tags and a shown one
        items.add(expense(TRAVEL, 50, 1L, 2L, 10L));

        TagMatrix matrix = TagBreakdown.of(items, names, totals(0, 5550)).matrices().getFirst();

        assertEquals(List.of(10L, 9L, 8L, 7L, 6L, 5L, 4L, 3L), matrix.tagIds());
        // 100 + 200 of tags 1 and 2, and the 50 once although it has both
        assertEquals(350.0, amount(matrix.otherTags()));
        MatrixRow travel = matrix.rows().stream().filter(r -> r.categoryId() == TRAVEL).findFirst().orElseThrow();
        assertEquals(Map.of(10L, new BigDecimal("50.00")), travel.tags());
        assertEquals(50.0, amount(travel.otherTags()));
    }

    @Test
    void aTagDeletedMeanwhileIsLeftOutAndItsEntriesCountAsUntagged() {
        TagBreakdown.Result result = TagBreakdown.of(List.of(expense(FOOD, 40, 99L), expense(FOOD, 60, 7L)),
                Map.of(7L, "Casa"), totals(0, 100));

        assertEquals(List.of("Casa"), result.tags().stream().map(DashboardService.TagRow::name).toList());
        TagMatrix matrix = result.matrices().getFirst();
        assertEquals(40.0, amount(matrix.untagged()));
        assertTrue(matrix.rows().getFirst().tags().containsKey(7L));
    }

    @Test
    void noTaggedEntriesNoMatrix() {
        TagBreakdown.Result result = TagBreakdown.of(List.of(expense(FOOD, 40)), Map.of(), totals(0, 40));
        assertEquals(List.of(), result.tags());
        assertEquals(List.of(), result.matrices());
    }
}
