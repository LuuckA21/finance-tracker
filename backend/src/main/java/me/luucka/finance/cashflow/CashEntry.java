package me.luucka.finance.cashflow;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import me.luucka.finance.core.EntryKind;

/**
 * A single income or expense.
 */
@Entity
@Table(name = "cash_entry")
public class CashEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "entry_date", nullable = false)
    private LocalDate date;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private EntryKind kind;

    /** Income and expense only; null for transfers. */
    @Column(name = "category_id")
    private Long categoryId;

    /** Transfers only: the user's position the money left (optional). */
    @Column(name = "from_position_id")
    private Long fromPositionId;

    /** Transfers only: the user's position the money went to (optional). */
    @Column(name = "to_position_id")
    private Long toPositionId;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(length = 500)
    private String description;

    /** Rule that created the entry, if any (set to null when the rule is deleted). */
    @Column(name = "recurring_entry_id")
    private Long recurringEntryId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected CashEntry() {
    }

    public CashEntry(Long userId) {
        this.userId = userId;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
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

    public EntryKind getKind() {
        return kind;
    }

    public void setKind(EntryKind kind) {
        this.kind = kind;
    }

    public Long getCategoryId() {
        return categoryId;
    }

    public void setCategoryId(Long categoryId) {
        this.categoryId = categoryId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Long getRecurringEntryId() {
        return recurringEntryId;
    }

    public void setRecurringEntryId(Long recurringEntryId) {
        this.recurringEntryId = recurringEntryId;
    }

    public Long getFromPositionId() {
        return fromPositionId;
    }

    public void setFromPositionId(Long fromPositionId) {
        this.fromPositionId = fromPositionId;
    }

    public Long getToPositionId() {
        return toPositionId;
    }

    public void setToPositionId(Long toPositionId) {
        this.toPositionId = toPositionId;
    }
}
