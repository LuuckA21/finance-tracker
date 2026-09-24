package me.luucka.finance.core.valuation;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedSet;
import java.util.TreeSet;

import me.luucka.finance.core.AssetClass;
import me.luucka.finance.core.Money;
import me.luucka.finance.core.fx.FxTable;

/**
 * Computes the value of a set of positions at given dates, converted to the base currency.
 * <p>
 * Position prices are carried forward from the latest snapshot, while the exchange rate is
 * the one valid on the valuation date. Positions whose currency cannot be converted are
 * excluded from totals and listed in {@code unconvertedCurrencies}.
 */
public final class NetWorthCalculator {

    private NetWorthCalculator() {
    }

    /** Value of one position on a date. {@code valueBase} is null when no FX rate is known. */
    public record PositionValue(
            long positionId,
            AssetClass assetClass,
            LocalDate asOf,
            BigDecimal quantity,
            BigDecimal unitPrice,
            BigDecimal valueLocal,
            BigDecimal valueBase) {
    }

    /** Total net worth on a date, split by asset class. */
    public record NetWorthPoint(
            LocalDate date,
            BigDecimal total,
            Map<AssetClass, BigDecimal> byClass,
            SortedSet<String> unconvertedCurrencies) {
    }

    /** Net worth on a date including the value of each position. */
    public record NetWorthDetail(NetWorthPoint point, List<PositionValue> positions) {
    }

    /**
     * Values every position on {@code date}. Positions without a snapshot on or before
     * the date are omitted.
     */
    public static NetWorthDetail valueAt(Collection<PositionHistory> positions, FxTable fx, LocalDate date) {
        List<PositionValue> values = new ArrayList<>();
        Map<AssetClass, BigDecimal> byClass = new EnumMap<>(AssetClass.class);
        BigDecimal total = BigDecimal.ZERO;
        SortedSet<String> unconverted = new TreeSet<>();

        for (PositionHistory position : positions) {
            Optional<ValuationSnapshot> maybeSnapshot = position.snapshotAt(date);
            if (maybeSnapshot.isEmpty()) {
                continue;
            }
            ValuationSnapshot snapshot = maybeSnapshot.get();
            BigDecimal local = Money.multiply(snapshot.quantity(), snapshot.unitPrice());
            Optional<BigDecimal> base = fx.toBase(local, position.currency(), date);
            if (base.isEmpty()) {
                unconverted.add(position.currency());
            } else {
                byClass.merge(position.assetClass(), base.get(), (a, b) -> a.add(b, Money.CONTEXT));
                total = total.add(base.get(), Money.CONTEXT);
            }
            values.add(new PositionValue(
                    position.positionId(),
                    position.assetClass(),
                    snapshot.date(),
                    snapshot.quantity(),
                    snapshot.unitPrice(),
                    Money.round(local),
                    base.map(Money::round).orElse(null)));
        }

        Map<AssetClass, BigDecimal> rounded = new EnumMap<>(AssetClass.class);
        byClass.forEach((k, v) -> rounded.put(k, Money.round(v)));
        values.sort((a, b) -> compareNullsLast(b.valueBase(), a.valueBase()));
        return new NetWorthDetail(new NetWorthPoint(date, Money.round(total), rounded, unconverted), values);
    }

    /**
     * Values all positions on each of the given dates (in the given order).
     */
    public static List<NetWorthPoint> series(Collection<PositionHistory> positions, FxTable fx,
                                             List<LocalDate> dates) {
        List<NetWorthPoint> points = new ArrayList<>(dates.size());
        for (LocalDate date : dates) {
            points.add(valueAt(positions, fx, date).point());
        }
        return points;
    }

    private static int compareNullsLast(BigDecimal a, BigDecimal b) {
        if (a == null && b == null) {
            return 0;
        }
        if (a == null) {
            return -1;
        }
        if (b == null) {
            return 1;
        }
        return a.compareTo(b);
    }
}
