package me.luucka.finance.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.Set;

import me.luucka.finance.core.category.CategoryTree;
import me.luucka.finance.core.category.CategoryTree.Node;
import org.junit.jupiter.api.Test;

class CategoryTreeTest {

    private final CategoryTree tree = new CategoryTree(List.of(
            new Node(1, null, "Casa", EntryKind.EXPENSE, "#2563eb"),
            new Node(2, 1L, "Affitto", EntryKind.EXPENSE, "#2563eb"),
            new Node(3, 1L, "Energia", EntryKind.EXPENSE, "#1d4ed8"),
            new Node(4, null, "Svago", EntryKind.EXPENSE, "#9333ea")));

    @Test
    void detailsRollUpToTheirMacro() {
        assertEquals(1, tree.macroId(2));
        assertEquals(1, tree.macroId(1));
        assertEquals(4, tree.macroId(4));
        assertEquals("Casa", tree.macro(3).name());
        // Not one of the user's: itself, without a node
        assertEquals(99, tree.macroId(99));
        assertNull(tree.macro(99));
    }

    @Test
    void aMacroTakesInItsDetails() {
        assertEquals(Set.of(1L, 2L, 3L), tree.withDetails(1));
        assertEquals(Set.of(2L), tree.withDetails(2));
        assertEquals(List.of("Affitto", "Energia"), tree.details(1).stream().map(Node::name).toList());
        assertEquals(List.of("Casa", "Svago"), tree.macros().stream().map(Node::name).toList());
    }

    @Test
    void pathsNameTheMacroAndTheDetail() {
        assertEquals("Casa › Affitto", tree.path(2));
        assertEquals("Svago", tree.path(4));
        assertEquals("", tree.path(99));
    }
}
