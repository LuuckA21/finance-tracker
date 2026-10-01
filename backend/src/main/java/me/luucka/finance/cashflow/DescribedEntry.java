package me.luucka.finance.cashflow;

import java.time.LocalDate;

import me.luucka.finance.core.EntryKind;

/** An income or expense with a description and its category (a query projection, for the category history). */
public record DescribedEntry(String description, long categoryId, EntryKind kind, LocalDate date) {
}
