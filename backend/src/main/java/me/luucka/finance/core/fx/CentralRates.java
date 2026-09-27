package me.luucka.finance.core.fx;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;

import me.luucka.finance.core.Money;

/**
 * Immutable reference rates quoted against one anchor currency, as published by a central bank
 * (the ECB publishes {@code 1 EUR = r CURRENCY}). The rate between any two known currencies is
 * derived through the anchor.
 */
public final class CentralRates {

    public static final CentralRates EMPTY = new CentralRates("EUR", Map.of());

    /** {@code 1 from = rate to}; {@code date} is the publication date of the older rate used. */
    public record Quote(BigDecimal rate, LocalDate date) {
    }

    private record Leg(BigDecimal rate, LocalDate date) {
    }

    /** One currency's rates sorted by date, for binary search without per-entry objects. */
    private record Series(long[] days, BigDecimal[] rates) {

        /** Index of the last rate on or before {@code day}, or -1. */
        int floor(long day) {
            int index = Arrays.binarySearch(days, day);
            return index >= 0 ? index : -index - 2;
        }

        Leg leg(int index) {
            return new Leg(rates[index], LocalDate.ofEpochDay(days[index]));
        }
    }

    private final String anchor;
    private final Map<String, Series> series;

    private CentralRates(String anchor, Map<String, Series> series) {
        this.anchor = anchor;
        this.series = series;
    }

    public static Builder builder(String anchor) {
        return new Builder(anchor);
    }

    public String anchor() {
        return anchor;
    }

    public boolean isEmpty() {
        return series.isEmpty();
    }

    /** Most recent publication date, if any rate is known. */
    public Optional<LocalDate> latestDate() {
        return series.values().stream()
                .map(s -> s.days()[s.days().length - 1])
                .max(Long::compare)
                .map(LocalDate::ofEpochDay);
    }

    /** Every currency that can be converted, the anchor included (empty when there are no rates). */
    public SortedSet<String> currencies() {
        if (series.isEmpty()) {
            return Collections.emptySortedSet();
        }
        SortedSet<String> all = new TreeSet<>(series.keySet());
        all.add(anchor);
        return Collections.unmodifiableSortedSet(all);
    }

    /**
     * Converts with the latest rates published on or before {@code date}.
     *
     * @return empty when either currency has no rate by that date
     */
    public Optional<Quote> onOrBefore(String from, String to, LocalDate date) {
        long day = date.toEpochDay();
        return quote(from, to, s -> {
            int index = s.floor(day);
            return index < 0 ? null : s.leg(index);
        });
    }

    /** Converts with the first rate known for each currency (for dates before the first publication). */
    public Optional<Quote> earliest(String from, String to) {
        return quote(from, to, s -> s.leg(0));
    }

    private Optional<Quote> quote(String from, String to, Function<Series, Leg> pick) {
        Leg fromLeg = leg(from, pick);
        Leg toLeg = leg(to, pick);
        if (fromLeg == null || toLeg == null) {
            return Optional.empty();
        }
        LocalDate date = fromLeg.date() == null ? toLeg.date()
                : toLeg.date() == null || fromLeg.date().isBefore(toLeg.date()) ? fromLeg.date() : toLeg.date();
        if (date == null) {
            // Anchor to anchor: nothing to convert
            return Optional.of(new Quote(BigDecimal.ONE, null));
        }
        return Optional.of(new Quote(toLeg.rate().divide(fromLeg.rate(), Money.CONTEXT), date));
    }

    private Leg leg(String currency, Function<Series, Leg> pick) {
        if (anchor.equals(currency)) {
            return series.isEmpty() ? null : new Leg(BigDecimal.ONE, null);
        }
        Series s = series.get(currency);
        return s == null ? null : pick.apply(s);
    }

    public static final class Builder {

        private final String anchor;
        private final Map<String, TreeMap<LocalDate, BigDecimal>> rates = new HashMap<>();

        private Builder(String anchor) {
            this.anchor = Objects.requireNonNull(anchor, "anchor");
        }

        /** {@code 1 anchor = rate currency} on {@code date}; a later call for the same day wins. */
        public Builder put(String currency, LocalDate date, BigDecimal rate) {
            Objects.requireNonNull(currency, "currency");
            Objects.requireNonNull(date, "date");
            if (rate == null || rate.signum() <= 0) {
                throw new IllegalArgumentException("Exchange rate must be positive");
            }
            if (!anchor.equals(currency)) {
                rates.computeIfAbsent(currency, c -> new TreeMap<>()).put(date, rate);
            }
            return this;
        }

        public CentralRates build() {
            Map<String, Series> series = new HashMap<>();
            rates.forEach((currency, byDate) -> {
                long[] days = new long[byDate.size()];
                BigDecimal[] values = new BigDecimal[byDate.size()];
                int i = 0;
                for (Map.Entry<LocalDate, BigDecimal> entry : byDate.entrySet()) {
                    days[i] = entry.getKey().toEpochDay();
                    values[i++] = entry.getValue();
                }
                series.put(currency, new Series(days, values));
            });
            return new CentralRates(anchor, Map.copyOf(series));
        }
    }
}
