package me.luucka.finance.core;

/**
 * Kind of asset a position represents. Used to group net worth in dashboards.
 */
public enum AssetClass {
    /** Bank and cash accounts: quantity is the balance, unit price is 1. */
    CASH,
    CRYPTO,
    ETF,
    STOCK,
    BOND,
    FUND,
    /** Retirement savings (e.g. pillar 2/3a, pension funds). */
    PENSION,
    COMMODITY,
    REAL_ESTATE,
    OTHER
}
