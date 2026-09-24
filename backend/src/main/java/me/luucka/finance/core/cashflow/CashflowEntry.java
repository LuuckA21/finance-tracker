package me.luucka.finance.core.cashflow;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

import me.luucka.finance.core.EntryKind;

/**
 * A single income or expense, in its original currency.
 *
 * @param date       booking date
 * @param kind       income or expense
 * @param amount     positive amount in {@code currency}
 * @param currency   ISO 4217 code
 * @param categoryId category the entry belongs to
 */
public record CashflowEntry(LocalDate date, EntryKind kind, BigDecimal amount, String currency, long categoryId) {

    public CashflowEntry {
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
    }
}
