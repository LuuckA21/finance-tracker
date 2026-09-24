package me.luucka.finance.user;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/**
 * Audit record of a login attempt, successful or not.
 */
@Entity
@Table(name = "login_event")
public class LoginEvent {

    /** Outcome of an attempt. Stored as text. */
    public enum Reason {
        SUCCESS,
        MFA_REQUIRED,
        BAD_CREDENTIALS,
        BAD_MFA_CODE,
        RECOVERY_CODE_USED,
        UNKNOWN_USER,
        LOCKED,
        DISABLED,
        RATE_LIMITED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "username_attempted", nullable = false, length = 64)
    private String usernameAttempted;

    @Column(name = "ip_address", length = 64)
    private String ipAddress;

    @Column(name = "user_agent", length = 255)
    private String userAgent;

    @Column(nullable = false)
    private boolean success;

    @Column(nullable = false, length = 32)
    private String reason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected LoginEvent() {
    }

    public LoginEvent(Long userId, String usernameAttempted, String ipAddress, String userAgent,
                      boolean success, Reason reason) {
        this.userId = userId;
        this.usernameAttempted = truncate(usernameAttempted, 64);
        this.ipAddress = truncate(ipAddress, 64);
        this.userAgent = truncate(userAgent, 255);
        this.success = success;
        this.reason = reason.name();
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public boolean isSuccess() {
        return success;
    }

    public String getReason() {
        return reason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
