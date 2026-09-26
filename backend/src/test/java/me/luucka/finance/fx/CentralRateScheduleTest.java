package me.luucka.finance.fx;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;

import org.junit.jupiter.api.Test;

class CentralRateScheduleTest {

    private static ZonedDateTime at(String dateTime) {
        return LocalDateTime.parse(dateTime).atZone(CentralRateService.ECB_ZONE);
    }

    @Test
    void expectsTodaysRatesOnlyAfterPublication() {
        // Friday 2026-09-25
        assertEquals(LocalDate.of(2026, 9, 24), CentralRateService.expectedLatest(at("2026-09-25T16:00")));
        assertEquals(LocalDate.of(2026, 9, 25), CentralRateService.expectedLatest(at("2026-09-25T16:15")));
    }

    @Test
    void weekendsExpectFridaysRates() {
        assertEquals(LocalDate.of(2026, 9, 25), CentralRateService.expectedLatest(at("2026-09-26T20:00")));
        assertEquals(LocalDate.of(2026, 9, 25), CentralRateService.expectedLatest(at("2026-09-27T10:00")));
        // Monday morning: still Friday's
        assertEquals(LocalDate.of(2026, 9, 25), CentralRateService.expectedLatest(at("2026-09-28T09:00")));
    }

    @Test
    void picksTheSmallestFeedCoveringTheGap() {
        LocalDate today = LocalDate.of(2026, 9, 28);
        assertEquals(EcbClient.Feed.HISTORY, CentralRateService.feedFor(null, today));
        assertEquals(EcbClient.Feed.DAILY, CentralRateService.feedFor(today.minusDays(3), today));
        assertEquals(EcbClient.Feed.LAST_90_DAYS, CentralRateService.feedFor(today.minusDays(6), today));
        assertEquals(EcbClient.Feed.LAST_90_DAYS, CentralRateService.feedFor(today.minusDays(85), today));
        assertEquals(EcbClient.Feed.HISTORY, CentralRateService.feedFor(today.minusDays(86), today));
    }
}
