package me.luucka.finance.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.List;

import me.luucka.finance.core.rules.CategoryRules;
import me.luucka.finance.core.rules.CategoryRules.Past;
import me.luucka.finance.core.rules.CategoryRules.Rule;
import org.junit.jupiter.api.Test;

class CategoryRulesTest {

    private static final LocalDate DAY = LocalDate.of(2026, 8, 1);

    @Test
    void aRuleMatchesTheDescriptionsContainingItsTextIgnoringCaseAccentsAndPunctuation() {
        CategoryRules rules = new CategoryRules(List.of(new Rule(1, "Migros", 10, EntryKind.EXPENSE),
                new Rule(2, "café", 11, EntryKind.EXPENSE)), List.of());
        assertEquals(1, rules.rule("MIGROS ZÜRICH 1234 * carta 12.08", null).orElseThrow().id());
        assertEquals(2, rules.rule("Bar-Cafe' centrale", EntryKind.EXPENSE).orElseThrow().id());
        assertTrue(rules.rule("Coop", null).isEmpty());
        assertTrue(rules.rule("", null).isEmpty());
        assertTrue(rules.rule(null, null).isEmpty());
        // Of another kind: not this one
        assertTrue(rules.rule("Migros", EntryKind.INCOME).isEmpty());
    }

    @Test
    void theMostSpecificRuleWins() {
        CategoryRules rules = new CategoryRules(List.of(new Rule(1, "migros", 10, EntryKind.EXPENSE),
                new Rule(2, "migrol", 12, EntryKind.EXPENSE), new Rule(3, "Migros Restaurant", 11, EntryKind.EXPENSE)),
                List.of());
        assertEquals(3, rules.rule("Migros Restaurant Bern", null).orElseThrow().id());
        assertEquals(1, rules.rule("Migros Bern", null).orElseThrow().id());
        assertEquals(2, rules.rule("Migrol tankstelle", null).orElseThrow().id());
    }

    @Test
    void withoutARuleThePastProposesTheCategoryMostOftenGivenToTheSameDescription() {
        CategoryRules rules = new CategoryRules(List.of(), List.of(
                new Past("Coop 1234 Zürich 01.08", 20, EntryKind.EXPENSE, DAY),
                new Past("COOP 9876 zurich 15.08", 20, EntryKind.EXPENSE, DAY.plusDays(14)),
                new Past("Coop 5555 Zurich", 21, EntryKind.EXPENSE, DAY.plusDays(20)),
                new Past("Rimborso Coop Zurich", 30, EntryKind.INCOME, DAY)));
        CategoryRules.Suggestion suggestion = rules.suggest("coop 4444 zürich 03.09", null).orElseThrow();
        assertEquals(20, suggestion.categoryId());
        assertFalse(suggestion.fromRule());
        assertNull(suggestion.rule());
        assertTrue(rules.suggest("coop zurich", EntryKind.INCOME).isEmpty());
        assertTrue(rules.suggest("Coop Basel", null).isEmpty());
        // Only digits: nothing to compare
        assertTrue(rules.suggest("1234 5678", null).isEmpty());
    }

    @Test
    void onATieTheMostRecentCategoryWinsAndARuleBeatsThePast() {
        List<Past> past = List.of(new Past("Netflix", 1, EntryKind.EXPENSE, DAY),
                new Past("Netflix", 2, EntryKind.EXPENSE, DAY.plusDays(30)));
        assertEquals(2, new CategoryRules(List.of(), past).suggest("NETFLIX", null).orElseThrow().categoryId());
        // The past compares whole descriptions: an extra word is another one
        assertTrue(new CategoryRules(List.of(), past).suggest("Netflix.com", null).isEmpty());
        CategoryRules withRule = new CategoryRules(List.of(new Rule(9, "netflix", 3, EntryKind.EXPENSE)), past);
        CategoryRules.Suggestion suggestion = withRule.suggest("Netflix", null).orElseThrow();
        assertEquals(3, suggestion.categoryId());
        assertTrue(suggestion.fromRule());
    }

    @Test
    void textsAreNormalized() {
        assertEquals("cafe de la gare 12", CategoryRules.normalize("  Café-de la GARE, #12! "));
        assertEquals("", CategoryRules.normalize(" *** "));
    }
}
