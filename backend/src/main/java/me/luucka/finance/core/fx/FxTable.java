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
 * In-memory table of manually entered exchange rates towards a single base currency.
 * <p>
 * A rate {@code r} for currency {@code C} on date {@code d} means: {@code 1 C = r BASE}.
 * Lookup uses the most recent rate on or before the requested date; when the date
 * precedes every known rate, the earliest known rate is used instead (so a rate entered
 * "today" still converts older entries). A currency with no rates at all cannot be converted.
 */
public final class FxTable {

    private final String baseCurrency;
    private final Map<String, NavigableMap<LocalDate, BigDecimal>> rates = new HashMap<>();

    public FxTable(String baseCurrency) {
        this.baseCurrency = Objects.requireNonNull(baseCurrency, "baseCurrency");
    }

    public String baseCurrency() {
        return baseCurrency;
    }

    /**
     * Registers a rate. Later calls for the same currency and date overwrite earlier ones.
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
     * Returns the rate to convert one unit of {@code currency} into the base currency.
     *
     * @return the rate, or empty when no rate is known for that currency
     */
    public Optional<BigDecimal> rate(String currency, LocalDate date) {
        if (baseCurrency.equals(currency)) {
            return Optional.of(BigDecimal.ONE);
        }
        NavigableMap<LocalDate, BigDecimal> series = rates.get(currency);
        if (series == null || series.isEmpty()) {
            return Optional.empty();
        }
        Map.Entry<LocalDate, BigDecimal> entry = series.floorEntry(date);
        if (entry == null) {
            entry = series.firstEntry();
        }
        return Optional.of(entry.getValue());
    }

    /**
     * Converts an amount into the base currency.
     *
     * @return the converted amount (full precision), or empty when no rate is known
     */
    public Optional<BigDecimal> toBase(BigDecimal amount, String currency, LocalDate date) {
        return rate(currency, date).map(r -> Money.multiply(amount, r));
    }
}
