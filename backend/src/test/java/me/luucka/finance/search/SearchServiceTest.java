package me.luucka.finance.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

class SearchServiceTest {

    @Test
    void amountsAsPeopleTypeThem() {
        assertEquals(new BigDecimal("45.20"), SearchService.amount("45.20"));
        assertEquals(new BigDecimal("45.20"), SearchService.amount("45,20"));
        assertEquals(new BigDecimal("1200.50"), SearchService.amount("1'200.50"));
        assertEquals(new BigDecimal("1200"), SearchService.amount("1 200"));
        assertEquals(new BigDecimal("8"), SearchService.amount("-8"));
    }

    @Test
    void textIsNoAmount() {
        assertNull(SearchService.amount("coop"));
        assertNull(SearchService.amount("12abc"));
        assertNull(SearchService.amount("0"));
        assertNull(SearchService.amount("1.23456"));
        assertNull(SearchService.amount("1234567890123456"));
    }
}
