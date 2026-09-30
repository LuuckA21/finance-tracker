package me.luucka.finance.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import me.luucka.finance.core.category.DefaultCategories;
import me.luucka.finance.core.category.DefaultCategories.ExistingMacro;
import me.luucka.finance.core.category.DefaultCategories.MissingDetail;
import org.junit.jupiter.api.Test;

class DefaultCategoriesTest {

    @Test
    void namesAreUniquePerLevelInEveryLanguage() {
        for (String language : List.of("IT", "EN", "DE", "FR")) {
            Set<String> macros = new HashSet<>();
            for (DefaultCategories.Macro macro : DefaultCategories.MACROS) {
                assertTrue(macros.add(macro.kind() + macro.name().in(language).toLowerCase()), macro.name().in(language));
                assertTrue(macro.name().in(language).length() <= 64);
                Set<String> details = new HashSet<>();
                for (DefaultCategories.Name detail : macro.details()) {
                    assertTrue(details.add(detail.in(language).toLowerCase()), detail.in(language));
                    assertTrue(detail.in(language).length() <= 64);
                }
            }
        }
    }

    @Test
    void swissGermanHasNoSharpS() {
        for (DefaultCategories.Macro macro : DefaultCategories.MACROS) {
            assertFalse(macro.name().german().contains("ß"));
            macro.details().forEach(d -> assertFalse(d.german().contains("ß"), d.german()));
        }
    }

    @Test
    void missingDetailsGoUnderDefaultMacrosFoundByNameInAnyLanguage() {
        List<ExistingMacro> macros = List.of(
                new ExistingMacro(1, EntryKind.EXPENSE, "casa", "#2563eb"),
                new ExistingMacro(2, EntryKind.EXPENSE, "Groceries", "#ea580c"),
                new ExistingMacro(3, EntryKind.EXPENSE, "Viaggi e vacanze", "#0284c7"),
                new ExistingMacro(4, EntryKind.INCOME, "Stipendio", "#16a34a"),
                // A default name under the other kind is not the default macro
                new ExistingMacro(5, EntryKind.INCOME, "Salute", "#db2777"));
        List<MissingDetail> missing = DefaultCategories.missingDetails("DE", macros,
                Map.of(1L, Set.of(" MIETE ", "Garage")));

        List<String> housing = missing.stream().filter(d -> d.parentId() == 1).map(MissingDetail::name).toList();
        assertEquals(List.of("Energie", "Einrichtung", "Unterhalt"), housing);
        List<String> groceries = missing.stream().filter(d -> d.parentId() == 2).map(MissingDetail::name).toList();
        assertEquals(List.of("Supermarkt", "Läden und Markt"), groceries);
        // Renamed, created or without default details: left alone
        assertTrue(missing.stream().noneMatch(d -> d.parentId() >= 3));
        // Details take the kind and colour of their macro
        assertEquals(EntryKind.EXPENSE, missing.getFirst().kind());
        assertEquals("#2563eb", missing.getFirst().color());
    }

    @Test
    void anUnknownLanguageFallsBackToItalian() {
        List<MissingDetail> missing = DefaultCategories.missingDetails("XX",
                List.of(new ExistingMacro(1, EntryKind.EXPENSE, "Tasse", "#dc2626")), Map.of());
        assertEquals(List.of("Imposte sul reddito", "Tasse e canoni"), missing.stream().map(MissingDetail::name).toList());
    }
}
