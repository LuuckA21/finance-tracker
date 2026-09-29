package me.luucka.finance.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

class TagNamesTest {

    @Test
    void namesAreTrimmedAndInnerSpacesCollapsed() {
        assertEquals(Optional.of("Vacanze Sardegna"), TagNames.normalize("  Vacanze   Sardegna "));
        assertEquals(Optional.of("x".repeat(40)), TagNames.normalize("x".repeat(40)));
    }

    @Test
    void badNamesAreRefused() {
        for (String bad : List.of("", "   ", "x".repeat(41), "a,b", "tab\there?".replace("?", "\u0007"), "bidi\u202e")) {
            assertTrue(TagNames.normalize(bad).isEmpty(), bad);
        }
        assertTrue(TagNames.normalize(null).isEmpty());
    }

    @Test
    void duplicatesDifferingOnlyInCaseCollapseToTheFirstSpelling() {
        assertEquals(Optional.of(List.of("Vacanze", "casa")), TagNames.normalizeAll(List.of("Vacanze", "casa", "VACANZE", "Casa")));
        assertEquals("vacanze", TagNames.key("VaCanze"));
    }

    @Test
    void atMostTenDistinctTagsPerEntry() {
        List<String> ten = java.util.stream.IntStream.rangeClosed(1, 10).mapToObj(i -> "t" + i).toList();
        assertEquals(10, TagNames.normalizeAll(ten).orElseThrow().size());
        List<String> eleven = new java.util.ArrayList<>(ten);
        eleven.add("t11");
        assertTrue(TagNames.normalizeAll(eleven).isEmpty());
        // Repetitions do not count twice
        List<String> repeated = new java.util.ArrayList<>(ten);
        repeated.addAll(Collections.nCopies(5, "T1"));
        assertEquals(10, TagNames.normalizeAll(repeated).orElseThrow().size());
    }

    @Test
    void aCsvCellHoldsTagsSeparatedByCommas() {
        assertEquals(Optional.of(List.of("Vacanze", "Sardegna 2026")), TagNames.parseCell(" Vacanze , Sardegna 2026,, "));
        assertEquals(Optional.of(List.of()), TagNames.parseCell(""));
        assertTrue(TagNames.parseCell("ok, " + "x".repeat(41)).isEmpty());
    }
}
