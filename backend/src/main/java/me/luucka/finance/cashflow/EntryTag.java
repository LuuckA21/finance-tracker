package me.luucka.finance.cashflow;

/** One tag of one entry (a query projection: no entity is loaded). */
public record EntryTag(long entryId, long tagId) {
}
