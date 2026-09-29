package me.luucka.finance.notification;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

/** A user's email address for notifications, its pending confirmation and the alerts they want. */
@Entity
@Table(name = "notification_settings")
public class NotificationSettings {

    @Id
    @Column(name = "user_id")
    private Long userId;

    /** Confirmed address: the only one notifications are sent to. */
    @Column(length = 254)
    private String email;

    @Column(name = "pending_email", length = 254)
    private String pendingEmail;

    @Column(name = "code_hash", length = 100)
    private String codeHash;

    @Column(name = "code_expires_at")
    private Instant codeExpiresAt;

    @Column(name = "code_attempts", nullable = false)
    private int codeAttempts;

    @Column(name = "code_sent_at")
    private Instant codeSentAt;

    @Column(name = "test_sent_at")
    private Instant testSentAt;

    @Column(name = "budget_alerts", nullable = false)
    private boolean budgetAlerts = true;

    @Column(name = "goal_alerts", nullable = false)
    private boolean goalAlerts = true;

    @Column(name = "monthly_summary", nullable = false)
    private boolean monthlySummary = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected NotificationSettings() {
    }

    public NotificationSettings(Long userId) {
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

    /** Starts confirming a new address; the current one keeps working until the code is entered. */
    void startConfirmation(String address, String hash, Instant expiresAt, Instant now) {
        pendingEmail = address;
        codeHash = hash;
        codeExpiresAt = expiresAt;
        codeAttempts = 0;
        codeSentAt = now;
    }

    void confirm() {
        email = pendingEmail;
        clearPending();
    }

    void clearPending() {
        pendingEmail = null;
        codeHash = null;
        codeExpiresAt = null;
        codeAttempts = 0;
    }

    void removeEmail() {
        email = null;
        clearPending();
    }

    void countAttempt() {
        codeAttempts++;
    }

    public Long getUserId() {
        return userId;
    }

    public String getEmail() {
        return email;
    }

    public String getPendingEmail() {
        return pendingEmail;
    }

    String getCodeHash() {
        return codeHash;
    }

    Instant getCodeExpiresAt() {
        return codeExpiresAt;
    }

    int getCodeAttempts() {
        return codeAttempts;
    }

    Instant getCodeSentAt() {
        return codeSentAt;
    }

    Instant getTestSentAt() {
        return testSentAt;
    }

    void setTestSentAt(Instant testSentAt) {
        this.testSentAt = testSentAt;
    }

    public boolean isBudgetAlerts() {
        return budgetAlerts;
    }

    public void setBudgetAlerts(boolean budgetAlerts) {
        this.budgetAlerts = budgetAlerts;
    }

    public boolean isGoalAlerts() {
        return goalAlerts;
    }

    public void setGoalAlerts(boolean goalAlerts) {
        this.goalAlerts = goalAlerts;
    }

    public boolean isMonthlySummary() {
        return monthlySummary;
    }

    public void setMonthlySummary(boolean monthlySummary) {
        this.monthlySummary = monthlySummary;
    }
}
