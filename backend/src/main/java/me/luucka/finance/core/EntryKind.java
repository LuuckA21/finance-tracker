package me.luucka.finance.core;

/**
 * Kind of a cash-flow entry. Amounts are always stored as positive numbers.
 */
public enum EntryKind {
    INCOME,
    EXPENSE,
    /**
     * Money moved between the user's own accounts or investments: neither income nor expense.
     * Has no category; may name the positions it moved from and to.
     */
    TRANSFER;

    /** Income and expense have a category; transfers do not. */
    public boolean hasCategory() {
        return this != TRANSFER;
    }
}
