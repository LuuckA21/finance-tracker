package me.luucka.finance.recurring;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Creates the entries of every active rule once they fall due: shortly after midnight and at
 * startup, so days missed while the server was down are caught up.
 */
@Component
public class RecurringEntryScheduler {

    private static final Logger log = LoggerFactory.getLogger(RecurringEntryScheduler.class);

    private final RecurringEntryRepository rules;
    private final RecurringEntryService service;

    public RecurringEntryScheduler(RecurringEntryRepository rules, RecurringEntryService service) {
        this.rules = rules;
        this.service = service;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        generateAll();
    }

    @Scheduled(cron = "0 5 0 * * *")
    public void generateAll() {
        int created = 0;
        for (Long id : rules.findActiveIds()) {
            // One transaction per rule: a failing rule does not block the others
            try {
                created += service.generateDue(id);
            } catch (RuntimeException e) {
                log.error("Recurring entry {} could not be generated", id, e);
            }
        }
        if (created > 0) {
            log.info("Created {} entries from recurring rules", created);
        }
    }
}
