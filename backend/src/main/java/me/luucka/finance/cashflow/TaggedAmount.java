package me.luucka.finance.cashflow;

import java.math.BigDecimal;
import java.time.LocalDate;

import me.luucka.finance.core.EntryKind;

/** One tag of one entry with the entry's figures (a query projection: no entity is loaded). */
public record TaggedAmount(long tagId, LocalDate date, EntryKind kind, Long categoryId, BigDecimal amount,
                           String currency) {
}
