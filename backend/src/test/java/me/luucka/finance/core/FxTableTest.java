package me.luucka.finance.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;

import me.luucka.finance.core.fx.FxTable;
import org.junit.jupiter.api.Test;

class FxTableTest {

    private final FxTable fx = new FxTable("CHF")
            .put("EUR", LocalDate.of(2026, 1, 1), new BigDecimal("0.94"))
            .put("EUR", LocalDate.of(2026, 6, 1), new BigDecimal("0.96"));

    @Test
    void baseCurrencyAlwaysConvertsOneToOne() {
        assertEquals(BigDecimal.ONE, fx.rate("CHF", LocalDate.of(1990, 1, 1)).orElseThrow());
    }

    @Test
    void usesLatestRateOnOrBeforeDate() {
        assertEquals(new BigDecimal("0.94"), fx.rate("EUR", LocalDate.of(2026, 5, 31)).orElseThrow());
        assertEquals(new BigDecimal("0.96"), fx.rate("EUR", LocalDate.of(2026, 6, 1)).orElseThrow());
        assertEquals(new BigDecimal("0.96"), fx.rate("EUR", LocalDate.of(2030, 1, 1)).orElseThrow());
    }

    @Test
    void fallsBackToEarliestRateForOlderDates() {
        assertEquals(new BigDecimal("0.94"), fx.rate("EUR", LocalDate.of(2020, 1, 1)).orElseThrow());
    }

    @Test
    void unknownCurrencyIsEmpty() {
        assertTrue(fx.rate("USD", LocalDate.of(2026, 1, 1)).isEmpty());
    }

    @Test
    void convertsAmounts() {
        BigDecimal converted = fx.toBase(new BigDecimal("100"), "EUR", LocalDate.of(2026, 7, 1)).orElseThrow();
        assertEquals(0, new BigDecimal("96").compareTo(converted));
    }

    @Test
    void rejectsNonPositiveRates() {
        assertThrows(IllegalArgumentException.class,
                () -> fx.put("USD", LocalDate.of(2026, 1, 1), BigDecimal.ZERO));
    }

    @Test
    void currencyValidation() {
        assertTrue(Currencies.isValid("chf"));
        assertTrue(Currencies.isValid(" EUR "));
        assertEquals(false, Currencies.isValid("XYZ1"));
        assertEquals(false, Currencies.isValid("QQQ"));
        assertEquals(false, Currencies.isValid(null));
    }
}
