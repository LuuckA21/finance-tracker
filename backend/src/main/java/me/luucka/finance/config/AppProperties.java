package me.luucka.finance.config;

import java.time.Duration;

import me.luucka.finance.user.Language;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Application specific settings ({@code app.*} in application.yml).
 *
 * @param totpIssuer     issuer label shown in authenticator apps
 * @param encryptionKey  Base64 32-byte key for encrypting secrets at rest
 * @param bootstrapAdmin first administrator created on an empty database
 * @param login          brute-force protection settings
 * @param session        login session settings
 */
@ConfigurationProperties("app")
public record AppProperties(
        String totpIssuer,
        String encryptionKey,
        BootstrapAdmin bootstrapAdmin,
        Login login,
        Session session) {

    public record BootstrapAdmin(String username, String password, Language language) {
    }

    /**
     * @param maxFailedAttempts failed logins before an account is temporarily locked
     * @param lockDuration      how long an account stays locked
     * @param ipMaxAttempts     failed logins allowed from one IP address per window
     * @param ipWindow          length of the per-IP window
     * @param mfaTimeout        time allowed between password and second-factor step
     */
    public record Login(
            int maxFailedAttempts,
            Duration lockDuration,
            int ipMaxAttempts,
            Duration ipWindow,
            Duration mfaTimeout) {
    }

    /**
     * @param maxLifetime how long a login lasts at most, even when the session stays active
     */
    public record Session(Duration maxLifetime) {
    }
}
