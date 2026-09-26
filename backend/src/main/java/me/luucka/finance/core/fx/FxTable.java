package me.luucka.finance.core.fx;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

import me.luucka.finance.core.Money;

/**
 * Exchange rates towards a single base currency: the user's manual rates, backed by central
 * reference rates (ECB).
 * <p>
 * A rate {@code r} for currency {@code C} on date {@code d} means: {@code 1 C = r BASE}.
 * Lookup order for a date:
 * <ol>
 *   <li>the most recent manual rate on or before the date (manual rates take priority);</li>
 *   <li>the most recent central rate on or before the date;</li>
 *   <li>for dates before every known rate, the earliest manual rate, then the earliest central
 *       rate (so a rate entered "today" still converts older entries).</li>
 * </ol>
 * A currency with neither manual nor central rates cannot be converted.
 */
public final class FxTable {

    public enum Source { BASE, MANUAL, CENTRAL }

    /** The rate used for a conversion and where it comes from. */
    public record Quote(BigDecimal rate, LocalDate date, Source source) {
    }

    private final String baseCurrency;
    private final CentralRates central;
    private final Map<String, NavigableMap<LocalDate, BigDecimal>> rates = new HashMap<>();

    public FxTable(String baseCurrency) {
        this(baseCurrency, CentralRates.EMPTY);
    }

    public FxTable(String baseCurrency, CentralRates central) {
        this.baseCurrency = Objects.requireNonNull(baseCurrency, "baseCurrency");
        this.central = Objects.requireNonNull(central, "central");
    }

    public String baseCurrency() {
        return baseCurrency;
    }

    /**
     * Registers a manual rate. Later calls for the same currency and date overwrite earlier ones.
     */
    public FxTable put(String currency, LocalDate date, BigDecimal rate) {
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(date, "date");
        if (rate == null || rate.signum() <= 0) {
            throw new IllegalArgumentException("Exchange rate must be positive");
        }
        rates.computeIfAbsent(currency, c -> new TreeMap<>()).put(date, rate);
        return this;
    }

    /**
     * Returns the rate to convert one unit of {@code currency} into the base currency on {@code date}.
     *
     * @return the rate and its origin, or empty when no rate is known for that currency
     */
    public Optional<Quote> quote(String currency, LocalDate date) {
        if (baseCurrency.equals(currency)) {
            return Optional.of(new Quote(BigDecimal.ONE, date, Source.BASE));
        }
        NavigableMap<LocalDate, BigDecimal> manual = rates.get(currency);
        Map.Entry<LocalDate, BigDecimal> entry = manual == null ? null : manual.floorEntry(date);
        if (entry != null) {
            return Optional.of(new Quote(entry.getValue(), entry.getKey(), Source.MANUAL));
        }
        Optional<Quote> centralQuote = central.onOrBefore(currency, baseCurrency, date).map(FxTable::fromCentral);
        if (centralQuote.isPresent()) {
            return centralQuote;
        }
        if (manual != null && !manual.isEmpty()) {
            entry = manual.firstEntry();
            return Optional.of(new Quote(entry.getValue(), entry.getKey(), Source.MANUAL));
        }
        return central.earliest(currency, baseCurrency).map(FxTable::fromCentral);
    }

    /**
     * Returns the rate to convert one unit of {@code currency} into the base currency.
     *
     * @return the rate, or empty when no rate is known for that currency
     */
    public Optional<BigDecimal> rate(String currency, LocalDate date) {
        return quote(currency, date).map(Quote::rate);
    }

    /**
     * Converts an amount into the base currency.
     *
     * @return the converted amount (full precision), or empty when no rate is known
     */
    public Optional<BigDecimal> toBase(BigDecimal amount, String currency, LocalDate date) {
        return rate(currency, date).map(r -> Money.multiply(amount, r));
    }

    private static Quote fromCentral(CentralRates.Quote quote) {
        return new Quote(quote.rate(), quote.date(), Source.CENTRAL);
    }
}
