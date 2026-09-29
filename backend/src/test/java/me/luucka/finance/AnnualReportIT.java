package me.luucka.finance;

import static me.luucka.finance.support.ApiClient.json;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

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
class AnnualReportIT {

    @Autowired
    MockMvc mvc;

    @Autowired
    TestUsers testUsers;

    private ApiClient login(AppUser user) throws Exception {
        ApiClient client = new ApiClient(mvc);
        assertEquals(200, client.login(user.getUsername(), TestUsers.PASSWORD));
        return client;
    }

    private static int category(ApiClient client, String name) throws Exception {
        List<Integer> ids = json(client.get("/api/categories"), "$[?(@.name == '" + name + "')].id");
        return ids.getFirst();
    }

    @Test
    @SuppressWarnings("unchecked")
    void aYearWithItsComparisonNetWorthPositionsTagsAndLargestExpenses() throws Exception {
        ApiClient client = login(testUsers.create("report", Role.USER));
        int salary = category(client, "Stipendio");
        int rent = category(client, "Casa");
        int travel = category(client, "Viaggi");
        int bank = json(client.post("/api/positions", """
                {"name":"Conto corrente","assetClass":"CASH","currency":"CHF"}"""), "$.id");
        int pillar = json(client.post("/api/positions", """
                {"name":"Pilastro 3a","assetClass":"PENSION","currency":"CHF"}"""), "$.id");
        client.post("/api/positions/" + bank + "/snapshots", """
                {"date":"2024-12-31","quantity":20000,"unitPrice":1}""");
        client.post("/api/positions/" + bank + "/snapshots", """
                {"date":"2025-12-31","quantity":22000,"unitPrice":1}""");
        client.post("/api/positions/" + pillar + "/snapshots", """
                {"date":"2025-12-31","quantity":7300,"unitPrice":1}""");

        String entry = """
                {"date":"%s","kind":"%s","categoryId":%d,"amount":%d,"currency":"CHF","description":"%s","tags":%s}""";
        client.post("/api/cash-entries", entry.formatted("2025-01-25", "INCOME", salary, 70000, "Stipendio", "[]"));
        client.post("/api/cash-entries", entry.formatted("2025-03-01", "EXPENSE", rent, 21600, "Affitto", "[]"));
        client.post("/api/cash-entries", entry.formatted("2025-07-10", "EXPENSE", travel, 800, "Traghetto",
                "[\"Vacanze\"]"));
        client.post("/api/cash-entries", entry.formatted("2024-03-01", "EXPENSE", rent, 20400, "Affitto", "[]"));
        client.post("/api/cash-entries", """
                {"date":"2025-06-30","kind":"TRANSFER","fromPositionId":%d,"toPositionId":%d,"amount":7000,
                 "currency":"CHF"}""".formatted(bank, pillar));

        MvcResult result = client.get("/api/reports/annual?year=2025");
        assertEquals(200, result.getResponse().getStatus());
        assertEquals("CHF", json(result, "$.baseCurrency"));
        assertEquals("2025-12-31", json(result, "$.periodEnd"));
        assertEquals(12, (int) json(result, "$.months"));
        assertTrue(((List<Integer>) json(result, "$.availableYears")).containsAll(List.of(2024, 2025)));
        assertEquals(70000.0, ((Number) json(result, "$.totals.income")).doubleValue());
        assertEquals(22400.0, ((Number) json(result, "$.totals.expense")).doubleValue());
        assertEquals(20400.0, ((Number) json(result, "$.previousTotals.expense")).doubleValue());

        List<Map<String, Object>> categories = json(result, "$.categories");
        assertEquals(List.of("Stipendio", "Casa", "Viaggi"), categories.stream().map(c -> c.get("name")).toList());
        assertEquals(20400.0, ((Number) categories.get(1).get("previousAmount")).doubleValue());

        assertEquals(20000.0, ((Number) json(result, "$.netWorthStart")).doubleValue());
        assertEquals(29300.0, ((Number) json(result, "$.netWorthEnd")).doubleValue());
        List<Map<String, Object>> positions = json(result, "$.positions");
        assertEquals(List.of("Conto corrente", "Pilastro 3a"), positions.stream().map(p -> p.get("name")).toList());
        assertNull(positions.get(1).get("start"));
        assertEquals(7000.0, ((Number) positions.get(1).get("transfersIn")).doubleValue());
        assertEquals(7000.0, ((Number) positions.get(0).get("transfersOut")).doubleValue());

        assertEquals(List.of("Vacanze"), json(result, "$.tags[*].name"));
        assertEquals(List.of("Affitto", "Traghetto"), json(result, "$.largestExpenses[*].description"));

        assertEquals(400, client.get("/api/reports/annual?year=1800").getResponse().getStatus());
    }

    @Test
    void anotherUsersDataNeverShows() throws Exception {
        ApiClient alice = login(testUsers.create("report-alice", Role.USER));
        ApiClient bob = login(testUsers.create("report-bob", Role.USER));
        alice.post("/api/cash-entries", """
                {"date":"2025-05-01","kind":"EXPENSE","categoryId":%d,"amount":999,"currency":"CHF",
                 "tags":["Segreto"]}""".formatted(category(alice, "Svago")));

        MvcResult report = bob.get("/api/reports/annual?year=2025");
        assertEquals(0.0, ((Number) json(report, "$.totals.expense")).doubleValue());
        assertEquals(List.of(), json(report, "$.tags"));
        assertEquals(List.of(), json(report, "$.largestExpenses"));
        assertEquals(401, new ApiClient(mvc).get("/api/reports/annual?year=2025").getResponse().getStatus());
    }
}
