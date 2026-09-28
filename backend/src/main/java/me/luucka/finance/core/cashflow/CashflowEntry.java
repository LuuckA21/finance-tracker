package me.luucka.finance.core.cashflow;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

import me.luucka.finance.core.AssetClass;
import me.luucka.finance.core.EntryKind;

/**
 * A single income, expense or transfer, in its original currency.
 *
 * @param date        booking date
 * @param kind        income, expense or transfer
 * @param amount      positive amount in {@code currency}
 * @param currency    ISO 4217 code
 * @param categoryId  category of an income or expense; null for transfers
 * @param destination transfers: asset class of the position the money went to, null if not given
 */
public record CashflowEntry(LocalDate date, EntryKind kind, BigDecimal amount, String currency, Long categoryId,
                            AssetClass destination) {

    public CashflowEntry {
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
        if (kind.hasCategory()) {
            Objects.requireNonNull(categoryId, "categoryId");
        }
    }

    /** An income or expense. */
    public CashflowEntry(LocalDate date, EntryKind kind, BigDecimal amount, String currency, long categoryId) {
        this(date, kind, amount, currency, categoryId, null);
    }
}
