package me.luucka.finance.fx;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

import me.luucka.finance.config.AppProperties;
import me.luucka.finance.core.fx.CentralRates;
import me.luucka.finance.core.fx.EcbXmlParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Keeps the ECB reference rates up to date and serves them from memory.
 * <p>
 * The ECB publishes around 16:00 CET on TARGET working days. An hourly check downloads only
 * when a newer publication is due, choosing the smallest feed that covers the gap: the full
 * history on the first run, the last 90 days after a longer downtime, otherwise the daily file.
 */
@Service
public class CentralRateService {

    private static final Logger log = LoggerFactory.getLogger(CentralRateService.class);

    static final ZoneId ECB_ZONE = ZoneId.of("Europe/Berlin");
    /** Publication is around 16:00; a little margin avoids asking too early. */
    static final LocalTime PUBLICATION_TIME = LocalTime.of(16, 15);

    public record Status(boolean autoUpdate, LocalDate latestDate, Instant lastAttempt, Instant lastSuccess,
                         String lastError) {
    }

    public record RefreshResult(boolean downloaded, EcbClient.Feed feed, int received, int changed,
                                LocalDate latestDate) {
    }

    private final CentralRateRepository repository;
    private final EcbClient client;
    private final Clock clock;
    private final boolean autoUpdate;

    private volatile CentralRates rates = CentralRates.EMPTY;
    private volatile Instant lastAttempt;
    private volatile Instant lastSuccess;
    private volatile String lastError;

    public CentralRateService(CentralRateRepository repository, EcbClient client, AppProperties properties,
                              Clock clock) {
        this.repository = repository;
        this.client = client;
        this.clock = clock;
        this.autoUpdate = properties.fx().ecb().enabled();
    }

    /** Current rates (1 EUR = r CURRENCY); empty until the first import. */
    public CentralRates rates() {
        return rates;
    }

    public Status status() {
        return new Status(autoUpdate, rates.latestDate().orElse(null), lastAttempt, lastSuccess, lastError);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        reload();
        if (autoUpdate) {
            // The first import downloads the full history: do not delay startup for it
            Thread.ofVirtual().name("ecb-startup").start(this::scheduledRefresh);
        }
    }

    @Scheduled(cron = "${app.fx.ecb.cron}", zone = "Europe/Berlin")
    public void scheduledRefresh() {
        if (!autoUpdate) {
            return;
        }
        try {
            refresh(false);
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("ECB exchange rates not updated: {}", e.toString());
        }
    }

    /**
     * Downloads new rates when a publication is due, or always with {@code force}.
     *
     * @throws Exception when the download or the import fails (recorded in {@link #status()})
     */
    public synchronized RefreshResult refresh(boolean force) throws Exception {
        LocalDate latest = repository.latestDate().orElse(null);
        ZonedDateTime now = clock.instant().atZone(ECB_ZONE);
        if (!force && latest != null && !latest.isBefore(expectedLatest(now))) {
            return new RefreshResult(false, null, 0, 0, latest);
        }
        EcbClient.Feed feed = feedFor(latest, now.toLocalDate());
        lastAttempt = clock.instant();
        try {
            List<EcbXmlParser.Rate> fetched = client.fetch(feed);
            int changed = repository.upsert(fetched);
            reload();
            lastSuccess = clock.instant();
            lastError = null;
            LocalDate newest = rates.latestDate().orElse(null);
            if (changed > 0) {
                log.info("ECB exchange rates updated from {}: {} rate(s) stored, latest {}", feed, changed, newest);
            }
            return new RefreshResult(true, feed, fetched.size(), changed, newest);
        } catch (Exception e) {
            lastError = abbreviate(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            throw e;
        }
    }

    private void reload() {
        rates = repository.loadAll();
    }

    /** Date of the newest publication that should exist at {@code now} (ECB time). */
    static LocalDate expectedLatest(ZonedDateTime now) {
        LocalDate date = now.toLocalTime().isBefore(PUBLICATION_TIME) ? now.toLocalDate().minusDays(1)
                : now.toLocalDate();
        while (date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY) {
            date = date.minusDays(1);
        }
        return date;
    }

    /** Smallest feed that covers the days since {@code latest}. */
    static EcbClient.Feed feedFor(LocalDate latest, LocalDate today) {
        if (latest == null) {
            return EcbClient.Feed.HISTORY;
        }
        long days = ChronoUnit.DAYS.between(latest, today);
        if (days > 85) {
            return EcbClient.Feed.HISTORY;
        }
        return days > 5 ? EcbClient.Feed.LAST_90_DAYS : EcbClient.Feed.DAILY;
    }

    private static String abbreviate(String message) {
        return message.length() <= 300 ? message : message.substring(0, 300) + "…";
    }
}
