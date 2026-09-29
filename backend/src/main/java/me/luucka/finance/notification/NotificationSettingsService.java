package me.luucka.finance.notification;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import me.luucka.finance.common.ApiException;
import me.luucka.finance.user.AppUser;
import me.luucka.finance.user.AppUserRepository;
import me.luucka.finance.user.Language;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.mail.MailException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The user's side of the notifications: the address (confirmed with a 6-digit code sent to it, so
 * mail only ever goes to an address its owner controls), the alerts wanted and a test email.
 */
@Service
public class NotificationSettingsService {

    static final Duration CODE_VALIDITY = Duration.ofMinutes(15);
    static final Duration RESEND_INTERVAL = Duration.ofSeconds(60);
    static final int MAX_CODE_ATTEMPTS = 5;

    private static final Logger log = LoggerFactory.getLogger(NotificationSettingsService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * @param mailEnabled  false when the server has no SMTP settings: nothing can be sent
     * @param email        confirmed address, null when none
     * @param pendingEmail address waiting for its code
     */
    public record SettingsResponse(boolean mailEnabled, String email, String pendingEmail, boolean budgetAlerts,
                                   boolean goalAlerts, boolean monthlySummary) {
    }

    private final NotificationSettingsRepository settings;
    private final NotificationService notifications;
    private final AppUserRepository users;
    private final Mailer mailer;
    private final Clock clock;

    public NotificationSettingsService(NotificationSettingsRepository settings, NotificationService notifications,
                                       AppUserRepository users, Mailer mailer, Clock clock) {
        this.settings = settings;
        this.notifications = notifications;
        this.users = users;
        this.mailer = mailer;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public SettingsResponse get(long userId) {
        return response(settings.findById(userId).orElseGet(() -> new NotificationSettings(userId)));
    }

    /** Partial update of the alerts wanted; alerts switched on start from now (nothing already true is sent). */
    @Transactional
    public SettingsResponse update(long userId, Boolean budgetAlerts, Boolean goalAlerts, Boolean monthlySummary) {
        NotificationSettings s = load(userId);
        Set<NotificationService.Kind> before = NotificationService.enabled(s);
        if (budgetAlerts != null) {
            s.setBudgetAlerts(budgetAlerts);
        }
        if (goalAlerts != null) {
            s.setGoalAlerts(goalAlerts);
        }
        if (monthlySummary != null) {
            s.setMonthlySummary(monthlySummary);
        }
        settings.save(s);
        if (s.getEmail() != null) {
            notifications.baseline(userId, NotificationService.switchedOn(before, NotificationService.enabled(s)));
        }
        return response(s);
    }

    /** Sends a confirmation code to {@code address}; the current address keeps working meanwhile. */
    @Transactional
    public SettingsResponse requestCode(long userId, String address) {
        requireMail();
        String normalized = normalize(address);
        NotificationSettings s = load(userId);
        Instant now = clock.instant();
        if (s.getCodeSentAt() != null && s.getCodeSentAt().plus(RESEND_INTERVAL).isAfter(now)) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "code_too_soon",
                    "Wait a minute before asking for another code");
        }
        String code = "%06d".formatted(RANDOM.nextInt(1_000_000));
        s.startConfirmation(normalized, hash(userId, code), now.plus(CODE_VALIDITY), now);
        settings.save(s);
        AppUser user = user(userId);
        Language language = user.getLanguage();
        send(normalized, new MailContent(language, MailTexts.text(language, "subject.code"), code,
                NotificationService.greeting(user), List.of(
                        new MailContent.Paragraph(MailTexts.text(language, "code.intro")),
                        new MailContent.Code(code),
                        new MailContent.Paragraph(MailTexts.text(language, "code.validity")))));
        return response(s);
    }

    /** Confirms the pending address; wrong codes count even though the request fails. */
    @Transactional(noRollbackFor = ApiException.class)
    public SettingsResponse confirm(long userId, String code) {
        NotificationSettings s = load(userId);
        if (s.getPendingEmail() == null) {
            throw ApiException.badRequest("no_pending_email", "Ask for a code first");
        }
        if (s.getCodeExpiresAt().isBefore(clock.instant()) || s.getCodeAttempts() >= MAX_CODE_ATTEMPTS) {
            s.clearPending();
            settings.save(s);
            throw ApiException.badRequest("code_expired", "The code has expired: ask for a new one");
        }
        String given = code == null ? "" : code.strip();
        if (!MessageDigest.isEqual(hash(userId, given).getBytes(StandardCharsets.US_ASCII),
                s.getCodeHash().getBytes(StandardCharsets.US_ASCII))) {
            s.countAttempt();
            settings.save(s);
            throw ApiException.badRequest("invalid_code", "Wrong code");
        }
        boolean first = s.getEmail() == null;
        s.confirm();
        settings.save(s);
        if (first) {
            // From now on: budgets already over, goals already reached, last month are not sent
            notifications.baseline(userId, NotificationService.enabled(s));
        }
        return response(s);
    }

    @Transactional
    public SettingsResponse removeEmail(long userId) {
        NotificationSettings s = load(userId);
        s.removeEmail();
        settings.save(s);
        return response(s);
    }

    /** One test email to the confirmed address, at most once a minute. */
    @Transactional
    public void sendTest(long userId) {
        requireMail();
        NotificationSettings s = load(userId);
        if (s.getEmail() == null) {
            throw ApiException.badRequest("no_email", "Confirm an email address first");
        }
        Instant now = clock.instant();
        if (s.getTestSentAt() != null && s.getTestSentAt().plus(RESEND_INTERVAL).isAfter(now)) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "code_too_soon", "Wait a minute");
        }
        s.setTestSentAt(now);
        settings.save(s);
        AppUser user = user(userId);
        Language language = user.getLanguage();
        send(s.getEmail(), new MailContent(language, MailTexts.text(language, "subject.test"),
                MailTexts.text(language, "test.body"), NotificationService.greeting(user),
                List.of(new MailContent.Paragraph(MailTexts.text(language, "test.body")))));
    }

    private void send(String to, MailContent content) {
        try {
            mailer.send(to, content);
        } catch (MailException e) {
            log.warn("Email to the notification address failed: {}", e.getMessage());
            throw new ApiException(HttpStatus.BAD_GATEWAY, "mail_failed", "The mail server did not accept the email");
        }
    }

    private void requireMail() {
        if (!mailer.enabled()) {
            throw ApiException.conflict("mail_disabled", "Email is not configured on this server");
        }
    }

    private SettingsResponse response(NotificationSettings s) {
        return new SettingsResponse(mailer.enabled(), s.getEmail(), s.getPendingEmail(), s.isBudgetAlerts(),
                s.isGoalAlerts(), s.isMonthlySummary());
    }

    /** The user's settings, created if missing and locked for the rest of the transaction. */
    private NotificationSettings load(long userId) {
        settings.createIfMissing(userId);
        return settings.findForUpdate(userId).orElseThrow();
    }

    private AppUser user(long userId) {
        return users.findById(userId).orElseThrow(() -> ApiException.notFound("User"));
    }

    /** A bare address (no display name), trimmed; the domain lower-cased. */
    static String normalize(String address) {
        String trimmed = address == null ? "" : address.strip();
        try {
            InternetAddress parsed = new InternetAddress(trimmed, true);
            parsed.validate();
            String value = parsed.getAddress();
            int at = value.lastIndexOf('@');
            if (at <= 0 || parsed.getPersonal() != null || !value.equals(trimmed) || value.length() > 254) {
                throw new AddressException(trimmed);
            }
            return value.substring(0, at) + value.substring(at).toLowerCase(Locale.ROOT);
        } catch (AddressException e) {
            throw ApiException.badRequest("invalid_email", "Not a valid email address");
        }
    }

    private static String hash(long userId, String code) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest((userId + ":" + code).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
