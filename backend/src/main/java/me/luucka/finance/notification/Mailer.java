package me.luucka.finance.notification;

import java.nio.charset.StandardCharsets;
import java.util.Properties;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import me.luucka.finance.config.AppProperties;
import org.springframework.mail.MailException;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/**
 * Sends plain-text email through the configured SMTP server ({@code app.mail.*}). Without a host
 * it is disabled and every mail feature of the app says so instead of failing.
 */
@Component
public class Mailer {

    /** Connect, read and write timeouts: a slow server must not hold a request or the scheduler. */
    private static final String TIMEOUT_MS = "10000";

    private final AppProperties.Mail settings;
    private final JavaMailSenderImpl sender;

    public Mailer(AppProperties properties) {
        this.settings = properties.mail();
        this.sender = settings != null && settings.enabled() ? sender(settings) : null;
    }

    public boolean enabled() {
        return sender != null;
    }

    /** Public address of the app for links in the emails, without a trailing slash; empty when not set. */
    public String appUrl() {
        String url = settings == null || settings.appUrl() == null ? "" : settings.appUrl().strip();
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /**
     * @throws IllegalStateException when mail is not configured
     * @throws MailException         when the server refuses or cannot be reached
     */
    public void send(String to, String subject, String text) {
        if (sender == null) {
            throw new IllegalStateException("Mail is not configured");
        }
        MimeMessage message = sender.createMimeMessage();
        try {
            MimeMessageHelper helper = new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
            helper.setFrom(from());
            helper.setTo(new InternetAddress(to, true));
            // Subjects never carry user text, but keep them on one line whatever happens
            helper.setSubject(subject.replaceAll("[\\r\\n]+", " "));
            helper.setText(text, false);
        } catch (MessagingException e) {
            throw new MailPreparationException("Invalid message", e);
        }
        sender.send(message);
    }

    private InternetAddress from() throws MessagingException {
        String from = settings.from() == null || settings.from().isBlank() ? settings.username() : settings.from();
        if (from == null || from.isBlank()) {
            throw new MessagingException("Set MAIL_FROM (or MAIL_USERNAME) to the sender address");
        }
        InternetAddress[] parsed = InternetAddress.parse(from.strip(), true);
        if (parsed.length != 1) {
            throw new MessagingException("MAIL_FROM must be a single address");
        }
        return parsed[0];
    }

    private static JavaMailSenderImpl sender(AppProperties.Mail settings) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(settings.host().strip());
        sender.setPort(settings.port());
        sender.setDefaultEncoding(StandardCharsets.UTF_8.name());
        boolean auth = settings.username() != null && !settings.username().isBlank();
        if (auth) {
            sender.setUsername(settings.username());
            sender.setPassword(settings.password());
        }
        AppProperties.Mail.Security security = settings.security() == null
                ? AppProperties.Mail.Security.STARTTLS : settings.security();
        Properties props = sender.getJavaMailProperties();
        props.put("mail.smtp.auth", String.valueOf(auth));
        props.put("mail.smtp.connectiontimeout", TIMEOUT_MS);
        props.put("mail.smtp.timeout", TIMEOUT_MS);
        props.put("mail.smtp.writetimeout", TIMEOUT_MS);
        switch (security) {
            case STARTTLS -> {
                props.put("mail.smtp.starttls.enable", "true");
                // Never fall back to plain text when the server does not offer STARTTLS
                props.put("mail.smtp.starttls.required", "true");
                props.put("mail.smtp.ssl.checkserveridentity", "true");
            }
            case SSL -> {
                props.put("mail.smtp.ssl.enable", "true");
                props.put("mail.smtp.ssl.checkserveridentity", "true");
            }
            case NONE -> {
                // Local relay only (documented): no encryption
            }
        }
        return sender;
    }
}
