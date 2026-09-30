package me.luucka.finance.category;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import me.luucka.finance.core.EntryKind;

/**
 * User-defined type of income or expense: a macro category ("Casa") or, when it has a parent, a
 * detail category under one ("Affitto"). Details have their macro's kind and no details of their own.
 */
@Entity
@Table(name = "category")
public class Category {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(nullable = false, length = 64)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16, updatable = false)
    private EntryKind kind;

    @Column(nullable = false, length = 7)
    private String color;

    @Column(name = "parent_id")
    private Long parentId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Category() {
    }

    public Category(Long userId, String name, EntryKind kind, String color, Long parentId) {
        this.userId = userId;
        this.name = name;
        this.kind = kind;
        this.color = color;
        this.parentId = parentId;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public EntryKind getKind() {
        return kind;
    }

    public String getColor() {
        return color;
    }

    public void setColor(String color) {
        this.color = color;
    }

    /** The macro this detail belongs to; null for a macro. */
    public Long getParentId() {
        return parentId;
    }

    public void setParentId(Long parentId) {
        this.parentId = parentId;
    }

    public boolean isMacro() {
        return parentId == null;
    }
}
