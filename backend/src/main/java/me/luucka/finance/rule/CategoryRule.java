package me.luucka.finance.rule;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/** A rule of one user: entries whose description contains {@code pattern} get the category. */
@Entity
@Table(name = "category_rule")
public class CategoryRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(nullable = false, length = 100)
    private String pattern;

    @Column(name = "category_id", nullable = false)
    private Long categoryId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected CategoryRule() {
    }

    public CategoryRule(Long userId, String pattern, Long categoryId) {
        this.userId = userId;
        this.pattern = pattern;
        this.categoryId = categoryId;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getPattern() {
        return pattern;
    }

    public void setPattern(String pattern) {
        this.pattern = pattern;
    }

    public Long getCategoryId() {
        return categoryId;
    }

    public void setCategoryId(Long categoryId) {
        this.categoryId = categoryId;
    }
}
