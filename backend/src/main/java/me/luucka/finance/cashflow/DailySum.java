package me.luucka.finance.cashflow;

import java.math.BigDecimal;
import java.time.LocalDate;

import me.luucka.finance.core.EntryKind;

/** What the user's entries of one kind and currency add up to on one day (a query projection). */
public record DailySum(LocalDate date, EntryKind kind, String currency, BigDecimal amount) {
}
