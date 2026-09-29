package me.luucka.finance.core.cashflow;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

import me.luucka.finance.core.EntryKind;

/**
 * An amount of one kind on one day, in its original currency: one entry, or several of the same day
 * and currency added up (they convert alike, since the rate is per currency and day).
 */
public record DatedAmount(LocalDate date, EntryKind kind, BigDecimal amount, String currency) {

    public DatedAmount {
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
    }
}
