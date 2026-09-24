package me.luucka.finance.core.valuation;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Quantity held and unit price of a position on a given date, in the position's currency.
 */
public record ValuationSnapshot(LocalDate date, BigDecimal quantity, BigDecimal unitPrice) {

    public ValuationSnapshot {
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(quantity, "quantity");
        Objects.requireNonNull(unitPrice, "unitPrice");
    }
}
