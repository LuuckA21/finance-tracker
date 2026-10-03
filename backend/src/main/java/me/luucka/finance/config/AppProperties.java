package me.luucka.finance.config;

import java.net.URI;
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
 * @param fx             exchange rate settings
 * @param mail           outgoing email (notifications); disabled without a host
 * @param publicUrl      the address users open the app at (passkeys are bound to its domain);
 *                       passkeys are off while it is empty
 */
@ConfigurationProperties("app")
public record AppProperties(
        String totpIssuer,
        String encryptionKey,
        BootstrapAdmin bootstrapAdmin,
        Login login,
        Session session,
        Fx fx,
        Mail mail,
        String publicUrl) {

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

    public record Fx(Ecb ecb) {
    }

    /**
     * @param enabled automatic download of the ECB reference rates (schedule: {@code app.fx.ecb.cron})
     * @param baseUrl directory of the ECB feeds
     */
    public record Ecb(boolean enabled, URI baseUrl) {
    }

    /**
     * SMTP server for the notifications. Everything mail related is off while {@code host} is empty.
     *
     * @param from     sender address, e.g. {@code Finanze <finanze@example.com>}
     * @param security {@code STARTTLS} (usually port 587), {@code SSL} (465) or {@code NONE} (local relay)
     * @param appUrl   public address of the app, linked from the emails (optional)
     */
    public record Mail(String host, int port, String username, String password, String from, Security security,
                       String appUrl) {

        public enum Security { STARTTLS, SSL, NONE }

        public boolean enabled() {
            return host != null && !host.isBlank();
        }
    }
}
