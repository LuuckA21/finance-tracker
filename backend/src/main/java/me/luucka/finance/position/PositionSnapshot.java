package me.luucka.finance.position;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import me.luucka.finance.core.valuation.ValuationSnapshot;

/**
 * Quantity and unit price of a position on a date. At most one snapshot per position and day.
 */
@Entity
@Table(name = "position_snapshot")
public class PositionSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "position_id", nullable = false, updatable = false)
    private Long positionId;

    /** Denormalised owner, so every query can be scoped by user without a join. */
    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "snapshot_date", nullable = false)
    private LocalDate date;

    @Column(nullable = false, precision = 38, scale = 12)
    private BigDecimal quantity;

    @Column(name = "unit_price", nullable = false, precision = 38, scale = 12)
    private BigDecimal unitPrice;

    @Column(length = 500)
    private String note;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected PositionSnapshot() {
    }

    public PositionSnapshot(Long positionId, Long userId) {
        this.positionId = positionId;
        this.userId = userId;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public ValuationSnapshot toValuation() {
        return new ValuationSnapshot(date, quantity, unitPrice);
    }

    public Long getId() {
        return id;
    }

    public Long getPositionId() {
        return positionId;
    }

    public Long getUserId() {
        return userId;
    }

    public LocalDate getDate() {
        return date;
    }

    public void setDate(LocalDate date) {
        this.date = date;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public void setQuantity(BigDecimal quantity) {
        this.quantity = quantity;
    }

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    public void setUnitPrice(BigDecimal unitPrice) {
        this.unitPrice = unitPrice;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }
}
