package me.luucka.finance.forecast;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import me.luucka.finance.core.EntryKind;
import me.luucka.finance.core.forecast.ForecastCalculator;

/** An extra item of a scenario, in the base currency. */
@Embeddable
public class ForecastItem {

    @Column(nullable = false, length = 100)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private EntryKind kind;

    @Column(name = "category_id")
    private Long categoryId;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ForecastCalculator.Schedule schedule;

    @Column(name = "start_month", nullable = false)
    private int startMonth;

    @Column(name = "end_month")
    private Integer endMonth;

    protected ForecastItem() {
    }

    public ForecastItem(String description, EntryKind kind, Long categoryId, BigDecimal amount,
                        ForecastCalculator.Schedule schedule, int startMonth, Integer endMonth) {
        this.description = description;
        this.kind = kind;
        this.categoryId = categoryId;
        this.amount = amount;
        this.schedule = schedule;
        this.startMonth = startMonth;
        this.endMonth = endMonth;
    }

    public String getDescription() {
        return description;
    }

    public EntryKind getKind() {
        return kind;
    }

    public Long getCategoryId() {
        return categoryId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public ForecastCalculator.Schedule getSchedule() {
        return schedule;
    }

    public int getStartMonth() {
        return startMonth;
    }

    public Integer getEndMonth() {
        return endMonth;
    }
}
