package me.luucka.finance.notification;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Checks the alerts every hour ({@code app.mail.cron}); does nothing while email is not configured. */
@Component
public class NotificationScheduler {

    private final NotificationService notifications;

    public NotificationScheduler(NotificationService notifications) {
        this.notifications = notifications;
    }

    @Scheduled(cron = "${app.mail.cron}")
    public void check() {
        notifications.runAll();
    }
}
