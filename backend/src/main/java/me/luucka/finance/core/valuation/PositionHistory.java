package me.luucka.finance.core.valuation;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

import me.luucka.finance.core.AssetClass;

/**
 * All snapshots of one position, indexed by date.
 * <p>
 * The value of a position on a date is taken from the latest snapshot on or before that
 * date ("carry forward"). Before its first snapshot a position does not exist yet.
 * A snapshot with quantity 0 marks a position as closed from that date on.
 */
public final class PositionHistory {

    private final long positionId;
    private final AssetClass assetClass;
    private final String currency;
    private final NavigableMap<LocalDate, ValuationSnapshot> snapshots = new TreeMap<>();

    public PositionHistory(long positionId, AssetClass assetClass, String currency,
                           Collection<ValuationSnapshot> snapshots) {
        this.positionId = positionId;
        this.assetClass = Objects.requireNonNull(assetClass, "assetClass");
        this.currency = Objects.requireNonNull(currency, "currency");
        for (ValuationSnapshot s : snapshots) {
            this.snapshots.put(s.date(), s);
        }
    }

    public long positionId() {
        return positionId;
    }

    public AssetClass assetClass() {
        return assetClass;
    }

    public String currency() {
        return currency;
    }

    /**
     * @return the snapshot in effect on {@code date}, or empty if the position did not exist yet
     */
    public Optional<ValuationSnapshot> snapshotAt(LocalDate date) {
        Map.Entry<LocalDate, ValuationSnapshot> entry = snapshots.floorEntry(date);
        return entry == null ? Optional.empty() : Optional.of(entry.getValue());
    }
}
