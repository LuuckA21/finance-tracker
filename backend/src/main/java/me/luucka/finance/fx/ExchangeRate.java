package me.luucka.finance.fx;

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

/**
 * Manually entered exchange rate: {@code 1 currency = rate baseCurrency} on {@code date}.
 * Rates are tied to the base currency they were entered for, so switching the base
 * currency does not silently reuse wrong rates.
 */
@Entity
@Table(name = "exchange_rate")
public class ExchangeRate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "base_currency", nullable = false, length = 3, updatable = false)
    private String baseCurrency;

    @Column(nullable = false, length = 3, updatable = false)
    private String currency;

    @Column(name = "rate_date", nullable = false, updatable = false)
    private LocalDate date;

    @Column(nullable = false, precision = 28, scale = 12)
    private BigDecimal rate;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ExchangeRate() {
    }

    public ExchangeRate(Long userId, String baseCurrency, String currency, LocalDate date) {
        this.userId = userId;
        this.baseCurrency = baseCurrency;
        this.currency = currency;
        this.date = date;
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

    public String getBaseCurrency() {
        return baseCurrency;
    }

    public String getCurrency() {
        return currency;
    }

    public LocalDate getDate() {
        return date;
    }

    public BigDecimal getRate() {
        return rate;
    }

    public void setRate(BigDecimal rate) {
        this.rate = rate;
    }
}
