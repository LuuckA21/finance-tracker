package me.luucka.finance;

import static me.luucka.finance.support.ApiClient.json;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import me.luucka.finance.support.ApiClient;
import me.luucka.finance.support.IntegrationTest;
import me.luucka.finance.support.TestUsers;
import me.luucka.finance.user.AppUser;
import me.luucka.finance.user.Role;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@IntegrationTest
class DashboardIT {

    @Autowired
    MockMvc mvc;

    @Autowired
    TestUsers testUsers;

    @Test
    void cashflowAndNetWorthInBaseCurrency() throws Exception {
        AppUser user = testUsers.create("dash", Role.USER);
        ApiClient c = new ApiClient(mvc);
        assertEquals(200, c.login(user.getUsername(), TestUsers.PASSWORD));

        List<Integer> income = json(c.get("/api/categories"), "$[?(@.kind == 'INCOME')].id");
        List<Integer> expense = json(c.get("/api/categories"), "$[?(@.kind == 'EXPENSE')].id");

        c.post("/api/fx-rates", """
                {"currency":"EUR","date":"2025-01-01","rate":0.95}""");
        c.post("/api/cash-entries", """
                {"date":"2025-01-25","kind":"INCOME","categoryId":%d,"amount":6000,"currency":"CHF"}
                """.formatted(income.getFirst()));
        c.post("/api/cash-entries", """
                {"date":"2025-01-10","kind":"EXPENSE","categoryId":%d,"amount":200,"currency":"EUR"}
                """.formatted(expense.getFirst()));
        c.post("/api/cash-entries", """
                {"date":"2025-02-10","kind":"EXPENSE","categoryId":%d,"amount":50,"currency":"ARS"}
                """.formatted(expense.getFirst()));

        MvcResult year = c.get("/api/dashboard/cashflow?year=2025");
        assertEquals(200, year.getResponse().getStatus());
        assertEquals("CHF", json(year, "$.baseCurrency"));
        assertEquals(6000.0, ((Number) json(year, "$.totals.income")).doubleValue());
        assertEquals(190.0, ((Number) json(year, "$.months[0].totals.expense")).doubleValue());
        // No manual rate and not published by the ECB: left out of the totals
        assertEquals(List.of("ARS"), json(year, "$.unconvertedCurrencies"));

        // Net worth: bank account in CHF + ETF priced in EUR
        int bank = json(c.post("/api/positions", """
                {"name":"Conto corrente","assetClass":"CASH","currency":"CHF"}"""), "$.id");
        int etf = json(c.post("/api/positions", """
                {"name":"VWCE","symbol":"VWCE","assetClass":"ETF","currency":"EUR"}"""), "$.id");
        c.post("/api/positions/" + bank + "/snapshots", """
                {"date":"2025-01-31","quantity":10000,"unitPrice":1}""");
        c.post("/api/positions/" + etf + "/snapshots", """
                {"date":"2025-02-15","quantity":10,"unitPrice":100}""");

        MvcResult detail = c.get("/api/dashboard/net-worth/detail?date=2025-03-01");
        assertEquals(10950.0, ((Number) json(detail, "$.total")).doubleValue());
        assertEquals(950.0, ((Number) json(detail, "$.byClass.ETF")).doubleValue());

        MvcResult series = c.get("/api/dashboard/net-worth?granularity=MONTH&from=2025-01&to=2025-02");
        assertEquals("2025-01", json(series, "$.points[0].period"));
        assertEquals(10000.0, ((Number) json(series, "$.points[0].total")).doubleValue());
        assertEquals(10950.0, ((Number) json(series, "$.points[1].total")).doubleValue());

        // Monthly update of all positions at once, then the old value is carried forward
        MvcResult bulk = c.post("/api/positions/snapshots/bulk", """
                {"date":"2025-03-31","items":[
                  {"positionId":%d,"quantity":12000,"unitPrice":1},
                  {"positionId":%d,"quantity":10,"unitPrice":110}]}""".formatted(bank, etf));
        assertEquals(200, bulk.getResponse().getStatus());
        MvcResult march = c.get("/api/dashboard/net-worth/detail?date=2025-04-15");
        assertEquals(13045.0, ((Number) json(march, "$.total")).doubleValue());

        MvcResult yearly = c.get("/api/dashboard/net-worth?granularity=YEAR&from=2025-01&to=2025-12");
        assertEquals("2025", json(yearly, "$.points[0].period"));
    }
}
