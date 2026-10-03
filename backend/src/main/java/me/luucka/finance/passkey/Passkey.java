package me.luucka.finance.passkey;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/** A passkey of a user: the public key of a credential held by one of their devices. */
@Entity
@Table(name = "passkey")
public class Passkey {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "credential_id", nullable = false, updatable = false, unique = true)
    private byte[] credentialId;

    /** AAGUID, credential id and COSE public key, as sent at registration. */
    @Column(name = "credential_data", nullable = false, updatable = false)
    private byte[] credentialData;

    @Column(name = "sign_count", nullable = false)
    private long signCount;

    @Column(name = "uv_initialized", nullable = false)
    private boolean uvInitialized;

    @Column(name = "backup_eligible", nullable = false)
    private boolean backupEligible;

    @Column(name = "backed_up", nullable = false)
    private boolean backedUp;

    /** Comma separated (usb, nfc, ble, hybrid, internal), as the browser reported them. */
    @Column(length = 100)
    private String transports;

    @Column(nullable = false, length = 64)
    private String name;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    protected Passkey() {
    }

    Passkey(long userId, byte[] credentialId, byte[] credentialData, long signCount, boolean uvInitialized,
            boolean backupEligible, boolean backedUp, String transports, String name) {
        this.userId = userId;
        this.credentialId = credentialId;
        this.credentialData = credentialData;
        this.signCount = signCount;
        this.uvInitialized = uvInitialized;
        this.backupEligible = backupEligible;
        this.backedUp = backedUp;
        this.transports = transports;
        this.name = name;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    /** Records a sign-in: the authenticator's counter, whether it is now synced, and when. */
    void used(long newSignCount, boolean nowBackedUp, Instant at) {
        signCount = newSignCount;
        backedUp = nowBackedUp;
        lastUsedAt = at;
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public byte[] getCredentialId() {
        return credentialId;
    }

    byte[] getCredentialData() {
        return credentialData;
    }

    long getSignCount() {
        return signCount;
    }

    boolean isUvInitialized() {
        return uvInitialized;
    }

    boolean isBackupEligible() {
        return backupEligible;
    }

    public boolean isBackedUp() {
        return backedUp;
    }

    String getTransports() {
        return transports;
    }

    public String getName() {
        return name;
    }

    void setName(String name) {
        this.name = name;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastUsedAt() {
        return lastUsedAt;
    }
}
