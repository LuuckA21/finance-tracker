package me.luucka.finance.position;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import me.luucka.finance.common.ApiException;
import me.luucka.finance.core.AssetClass;
import me.luucka.finance.core.Currencies;
import me.luucka.finance.core.Iban;
import me.luucka.finance.core.Money;
import me.luucka.finance.core.valuation.PositionHistory;
import me.luucka.finance.core.valuation.ValuationSnapshot;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PositionService {

    /** {@code iban}: any spacing or case, checked; blank or null for none. */
    public record PositionData(String name, String symbol, String iban, AssetClass assetClass, String currency,
                               String notes, boolean archived) {
    }

    public record SnapshotData(LocalDate date, BigDecimal quantity, BigDecimal unitPrice, String note) {
    }

    public record BulkItem(long positionId, BigDecimal quantity, BigDecimal unitPrice) {
    }

    public record SnapshotResponse(long id, LocalDate date, BigDecimal quantity, BigDecimal unitPrice,
                                   BigDecimal value, String note) {
        static SnapshotResponse of(PositionSnapshot s) {
            return new SnapshotResponse(s.getId(), s.getDate(), Money.plain(s.getQuantity()), Money.plain(s.getUnitPrice()),
                    Money.round(Money.multiply(s.getQuantity(), s.getUnitPrice())), s.getNote());
        }
    }

    /** A position with its latest snapshot ({@code null} when it has none yet). */
    public record PositionResponse(long id, String name, String symbol, String iban, AssetClass assetClass,
                                   String currency, String notes, boolean archived, SnapshotResponse latest) {
        static PositionResponse of(AssetPosition p, PositionSnapshot latest) {
            return new PositionResponse(p.getId(), p.getName(), p.getSymbol(), p.getIban(), p.getAssetClass(),
                    p.getCurrency(),
                    p.getNotes(), p.isArchived(), latest == null ? null : SnapshotResponse.of(latest));
        }
    }

    private final AssetPositionRepository positions;
    private final PositionSnapshotRepository snapshots;

    public PositionService(AssetPositionRepository positions, PositionSnapshotRepository snapshots) {
        this.positions = positions;
        this.snapshots = snapshots;
    }

    @Transactional(readOnly = true)
    public List<PositionResponse> list(long userId) {
        Map<Long, PositionSnapshot> latest = latestByPosition(userId);
        return positions.findByUserIdOrderByArchivedAscNameAsc(userId).stream()
                .map(p -> PositionResponse.of(p, latest.get(p.getId())))
                .toList();
    }

    /** Snapshot history of every position of the user, for valuations at any date. */
    @Transactional(readOnly = true)
    public List<PositionHistory> histories(long userId) {
        Map<Long, List<ValuationSnapshot>> grouped = snapshots.findByUserId(userId).stream()
                .collect(Collectors.groupingBy(PositionSnapshot::getPositionId,
                        Collectors.mapping(PositionSnapshot::toValuation, Collectors.toList())));
        return positions.findByUserIdOrderByArchivedAscNameAsc(userId).stream()
                .map(p -> new PositionHistory(p.getId(), p.getAssetClass(), p.getCurrency(),
                        grouped.getOrDefault(p.getId(), List.of())))
                .toList();
    }

    /** Ids of the user's own positions among {@code ids}; throws 404 when one is not theirs. */
    @Transactional(readOnly = true)
    public Set<Long> requireOwned(long userId, Set<Long> ids) {
        Set<Long> owned = positions.findByUserIdOrderByArchivedAscNameAsc(userId).stream()
                .map(AssetPosition::getId)
                .collect(Collectors.toSet());
        if (!owned.containsAll(ids)) {
            throw ApiException.notFound("Position");
        }
        return Set.copyOf(ids);
    }

    @Transactional(readOnly = true)
    public PositionResponse get(long userId, long id) {
        AssetPosition position = load(userId, id);
        PositionSnapshot latest = snapshots.findByPositionIdAndUserIdOrderByDateDesc(id, userId).stream()
                .findFirst().orElse(null);
        return PositionResponse.of(position, latest);
    }

    @Transactional
    public PositionResponse create(long userId, PositionData data) {
        AssetPosition position = new AssetPosition(userId);
        apply(position, data);
        requireUniqueIban(userId, position);
        return PositionResponse.of(positions.save(position), null);
    }

    @Transactional
    public PositionResponse update(long userId, long id, PositionData data) {
        AssetPosition position = load(userId, id);
        apply(position, data);
        requireUniqueIban(userId, position);
        return get(userId, id);
    }

    /** Deletes the position and (via FK cascade) its snapshots. */
    @Transactional
    public void delete(long userId, long id) {
        positions.delete(load(userId, id));
    }

    @Transactional(readOnly = true)
    public List<SnapshotResponse> snapshots(long userId, long positionId) {
        load(userId, positionId);
        return snapshots.findByPositionIdAndUserIdOrderByDateDesc(positionId, userId).stream()
                .map(SnapshotResponse::of)
                .toList();
    }

    /** Creates a snapshot, or overwrites the one already existing on the same date. */
    @Transactional
    public SnapshotResponse upsertSnapshot(long userId, long positionId, SnapshotData data) {
        load(userId, positionId);
        PositionSnapshot snapshot = snapshots.findByPositionIdAndDate(positionId, data.date())
                .orElseGet(() -> new PositionSnapshot(positionId, userId));
        applySnapshot(snapshot, data);
        return SnapshotResponse.of(snapshots.save(snapshot));
    }

    @Transactional
    public SnapshotResponse updateSnapshot(long userId, long positionId, long snapshotId, SnapshotData data) {
        PositionSnapshot snapshot = snapshots.findByIdAndPositionIdAndUserId(snapshotId, positionId, userId)
                .orElseThrow(() -> ApiException.notFound("Snapshot"));
        if (!snapshot.getDate().equals(data.date())
                && snapshots.findByPositionIdAndDate(positionId, data.date()).isPresent()) {
            throw ApiException.conflict("snapshot_exists", "A snapshot for this date already exists");
        }
        applySnapshot(snapshot, data);
        return SnapshotResponse.of(snapshot);
    }

    @Transactional
    public void deleteSnapshot(long userId, long positionId, long snapshotId) {
        PositionSnapshot snapshot = snapshots.findByIdAndPositionIdAndUserId(snapshotId, positionId, userId)
                .orElseThrow(() -> ApiException.notFound("Snapshot"));
        snapshots.delete(snapshot);
    }

    /**
     * Records the same date for many positions at once (the typical "monthly update").
     * All positions must belong to the user; the operation is atomic.
     */
    @Transactional
    public int bulkSnapshot(long userId, LocalDate date, List<BulkItem> items) {
        Set<Long> owned = positions.findByUserIdOrderByArchivedAscNameAsc(userId).stream()
                .map(AssetPosition::getId)
                .collect(Collectors.toSet());
        Set<Long> seen = new HashSet<>();
        for (BulkItem item : items) {
            if (!owned.contains(item.positionId())) {
                throw ApiException.notFound("Position");
            }
            if (!seen.add(item.positionId())) {
                throw ApiException.badRequest("duplicate_position", "Each position may appear only once");
            }
        }
        for (BulkItem item : items) {
            upsertSnapshot(userId, item.positionId(),
                    new SnapshotData(date, item.quantity(), item.unitPrice(), null));
        }
        return items.size();
    }

    /** Latest snapshot per position id. */
    Map<Long, PositionSnapshot> latestByPosition(long userId) {
        Map<Long, PositionSnapshot> result = new HashMap<>();
        for (PositionSnapshot s : snapshots.findLatestByUserId(userId)) {
            result.put(s.getPositionId(), s);
        }
        return result;
    }

    /** An account statement must name one position only. */
    private void requireUniqueIban(long userId, AssetPosition position) {
        if (position.getIban() != null && positions.findByUserIdAndIban(userId, position.getIban())
                .filter(other -> !other.getId().equals(position.getId())).isPresent()) {
            throw ApiException.conflict("iban_taken", "Another position has this IBAN");
        }
    }

    private AssetPosition load(long userId, long id) {
        return positions.findByIdAndUserId(id, userId).orElseThrow(() -> ApiException.notFound("Position"));
    }

    private static void apply(AssetPosition position, PositionData data) {
        position.setName(data.name().trim());
        position.setSymbol(blankToNull(data.symbol()));
        String iban = blankToNull(data.iban());
        position.setIban(iban == null ? null : Iban.normalize(iban)
                .orElseThrow(() -> ApiException.badRequest("invalid_iban", "Not a valid IBAN")));
        position.setAssetClass(data.assetClass());
        position.setCurrency(Currencies.normalize(data.currency()));
        position.setNotes(blankToNull(data.notes()));
        position.setArchived(data.archived());
    }

    private static void applySnapshot(PositionSnapshot snapshot, SnapshotData data) {
        snapshot.setDate(data.date());
        snapshot.setQuantity(data.quantity());
        snapshot.setUnitPrice(data.unitPrice());
        snapshot.setNote(blankToNull(data.note()));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
