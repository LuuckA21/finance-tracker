package me.luucka.finance.forecast;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

/** A saved forecast of one user: the year, the growth, what to leave out of the base and the extra items. */
@Entity
@Table(name = "forecast_scenario")
public class ForecastScenario {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "forecast_year", nullable = false)
    private int year;

    @Column(name = "income_growth", nullable = false, precision = 7, scale = 2)
    private BigDecimal incomeGrowth;

    @Column(name = "expense_growth", nullable = false, precision = 7, scale = 2)
    private BigDecimal expenseGrowth;

    @ElementCollection
    @CollectionTable(name = "forecast_excluded_tag", joinColumns = @JoinColumn(name = "scenario_id"))
    @Column(name = "tag_id", nullable = false)
    private Set<Long> excludedTagIds = new HashSet<>();

    @ElementCollection
    @CollectionTable(name = "forecast_excluded_category", joinColumns = @JoinColumn(name = "scenario_id"))
    @Column(name = "category_id", nullable = false)
    private Set<Long> excludedCategoryIds = new HashSet<>();

    @ElementCollection
    @CollectionTable(name = "forecast_item", joinColumns = @JoinColumn(name = "scenario_id"))
    @OrderColumn(name = "sort_order")
    private List<ForecastItem> items = new ArrayList<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ForecastScenario() {
    }

    public ForecastScenario(Long userId) {
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

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int getYear() {
        return year;
    }

    public void setYear(int year) {
        this.year = year;
    }

    public BigDecimal getIncomeGrowth() {
        return incomeGrowth;
    }

    public void setIncomeGrowth(BigDecimal incomeGrowth) {
        this.incomeGrowth = incomeGrowth;
    }

    public BigDecimal getExpenseGrowth() {
        return expenseGrowth;
    }

    public void setExpenseGrowth(BigDecimal expenseGrowth) {
        this.expenseGrowth = expenseGrowth;
    }

    public Set<Long> getExcludedTagIds() {
        return excludedTagIds;
    }

    public void setExcludedTagIds(Set<Long> ids) {
        excludedTagIds.clear();
        excludedTagIds.addAll(ids);
    }

    public Set<Long> getExcludedCategoryIds() {
        return excludedCategoryIds;
    }

    public void setExcludedCategoryIds(Set<Long> ids) {
        excludedCategoryIds.clear();
        excludedCategoryIds.addAll(ids);
    }

    public List<ForecastItem> getItems() {
        return items;
    }

    public void setItems(List<ForecastItem> items) {
        this.items.clear();
        this.items.addAll(items);
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
