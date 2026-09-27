package me.luucka.finance;

import static me.luucka.finance.support.ApiClient.json;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpServer;
import me.luucka.finance.support.ApiClient;
import me.luucka.finance.support.IntegrationTest;
import me.luucka.finance.support.TestUsers;
import me.luucka.finance.user.AppUser;
import me.luucka.finance.user.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * ECB rates end to end: download from a local stand-in for the ECB server, conversion in the
 * dashboard and priority of manual rates.
 */
@IntegrationTest
class EcbRatesIT {

    @Autowired
    MockMvc mvc;

    @Autowired
    TestUsers testUsers;

    private HttpServer ecb;
    private final AtomicInteger requests = new AtomicInteger();

    @BeforeEach
    void startEcb() throws Exception {
        byte[] feed;
        try (InputStream in = getClass().getResourceAsStream("/ecb/eurofxref-hist-sample.xml")) {
            feed = in.readAllBytes();
        }
        ecb = HttpServer.create(new InetSocketAddress("127.0.0.1", IntegrationTest.ECB_PORT), 0);
        // Same content for every feed: the choice of feed depends on today's date
        ecb.createContext("/ecb/", exchange -> {
            requests.incrementAndGet();
            exchange.getResponseHeaders().add("Content-Type", "text/xml");
            exchange.sendResponseHeaders(200, feed.length);
            exchange.getResponseBody().write(feed);
            exchange.close();
        });
        ecb.start();
    }

    @AfterEach
    void stopEcb() {
        ecb.stop(0);
    }

    private ApiClient login(AppUser user) throws Exception {
        ApiClient client = new ApiClient(mvc);
        assertEquals(200, client.login(user.getUsername(), TestUsers.PASSWORD));
        return client;
    }

    @Test
    void importedRatesConvertAndManualRatesTakePriority() throws Exception {
        ApiClient admin = login(testUsers.create("fxadmin", Role.ADMIN));
        MvcResult refreshed = admin.post("/api/admin/fx/refresh", null);
        assertEquals(200, refreshed.getResponse().getStatus());
        assertEquals(Boolean.TRUE, json(refreshed, "$.downloaded"));
        assertEquals(Integer.valueOf(8), json(refreshed, "$.received"));
        assertEquals("2026-09-25", json(refreshed, "$.latestDate"));
        assertEquals(1, requests.get());
        MvcResult status = admin.get("/api/admin/fx");
        assertEquals("2026-09-25", json(status, "$.latestDate"));
        assertNull(json(status, "$.lastError"));

        // A user with base CHF sees ECB rates converted to CHF
        ApiClient user = login(testUsers.create("fxuser", Role.USER));
        MvcResult central = user.get("/api/fx-rates/central");
        assertEquals("CHF", json(central, "$.baseCurrency"));
        assertEquals("2026-09-25", json(central, "$.latestDate"));
        List<Map<String, Object>> usd = json(central, "$.rates[?(@.currency == 'USD')]");
        assertEquals(0.79653051, ((Number) usd.getFirst().get("rate")).doubleValue(), 1e-9);
        assertNull(usd.getFirst().get("manualRate"));
        List<String> currencies = json(central, "$.rates[*].currency");
        assertEquals(List.of("EUR", "GBP", "JPY", "USD"), currencies);

        // Cash flow in USD is converted with the ECB rate: 100 USD = 79.65 CHF
        List<Integer> expense = json(user.get("/api/categories"), "$[?(@.kind == 'EXPENSE')].id");
        user.post("/api/cash-entries", """
                {"date":"2026-09-25","kind":"EXPENSE","categoryId":%d,"amount":100,"currency":"USD"}"""
                .formatted(expense.getFirst()));
        MvcResult year = user.get("/api/dashboard/cashflow?year=2026");
        assertEquals(79.65, ((Number) json(year, "$.totals.expense")).doubleValue());
        assertEquals(List.of(), json(year, "$.unconvertedCurrencies"));

        // A manual rate replaces the ECB rate for that currency
        assertEquals(200, user.post("/api/fx-rates", "{\"currency\":\"USD\",\"date\":\"2026-09-01\",\"rate\":0.8}")
                .getResponse().getStatus());
        year = user.get("/api/dashboard/cashflow?year=2026");
        assertEquals(80.0, ((Number) json(year, "$.totals.expense")).doubleValue());
        usd = json(user.get("/api/fx-rates/central"), "$.rates[?(@.currency == 'USD')]");
        assertEquals(0.8, ((Number) usd.getFirst().get("manualRate")).doubleValue());
    }

    @Test
    void failedDownloadIsReportedToAdminsOnly() throws Exception {
        ApiClient user = login(testUsers.create("fxplain", Role.USER));
        assertEquals(403, user.post("/api/admin/fx/refresh", null).getResponse().getStatus());
        assertEquals(403, user.get("/api/admin/fx").getResponse().getStatus());

        ecb.stop(0);
        ApiClient admin = login(testUsers.create("fxadmin2", Role.ADMIN));
        MvcResult failed = admin.post("/api/admin/fx/refresh", null);
        assertEquals(502, failed.getResponse().getStatus());
        assertEquals("ecb_unavailable", json(failed, "$.code"));
        assertNotNull(json(admin.get("/api/admin/fx"), "$.lastError"));
    }
}
