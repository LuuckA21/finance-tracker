package me.luucka.finance;

import static me.luucka.finance.support.ApiClient.json;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.LocalDate;
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
class GoalsIT {

    private static final LocalDate TODAY = LocalDate.now();

    @Autowired
    MockMvc mvc;

    @Autowired
    TestUsers testUsers;

    private ApiClient login(AppUser user) throws Exception {
        ApiClient client = new ApiClient(mvc);
        assertEquals(200, client.login(user.getUsername(), TestUsers.PASSWORD));
        return client;
    }

    private static long position(ApiClient client, String name, String assetClass) throws Exception {
        MvcResult created = client.post("/api/positions", """
                {"name":"%s","symbol":"","assetClass":"%s","currency":"CHF","notes":"","archived":false}"""
                .formatted(name, assetClass));
        assertEquals(201, created.getResponse().getStatus());
        return ((Number) json(created, "$.id")).longValue();
    }

    private static void balance(ApiClient client, long positionId, LocalDate date, int amount) throws Exception {
        // Snapshots are upserted by date: 200 either way
        assertEquals(200, client.post("/api/positions/%d/snapshots".formatted(positionId), """
                {"date":"%s","quantity":%d,"unitPrice":1,"note":""}""".formatted(date, amount))
                .getResponse().getStatus());
    }

    private static void transfer(ApiClient client, long toPositionId, LocalDate date, int amount) throws Exception {
        assertEquals(201, client.post("/api/cash-entries", """
                {"date":"%s","kind":"TRANSFER","toPositionId":%d,"amount":%d,"currency":"CHF"}"""
                .formatted(date, toPositionId, amount)).getResponse().getStatus());
    }

    private static String goal(String name, String kind, int target, String targetDate, long... positions) {
        StringBuilder ids = new StringBuilder();
        for (long id : positions) {
            ids.append(ids.isEmpty() ? "" : ",").append(id);
        }
        return """
                {"name":"%s","kind":"%s","targetAmount":%d,"currency":"CHF","targetDate":%s,"positionIds":[%s]}"""
                .formatted(name, kind, target, targetDate == null ? "null" : "\"" + targetDate + "\"", ids);
    }

    @Test
    void goalsMeasureBalancesAndYearlyTransfers() throws Exception {
        ApiClient client = login(testUsers.create("goals", Role.USER));
        long savings = position(client, "Conto risparmio", "CASH");
        long pillar = position(client, "3a", "PENSION");
        balance(client, savings, TODAY.minusMonths(6), 5000);
        balance(client, savings, TODAY, 8000);
        transfer(client, pillar, TODAY.withDayOfYear(1), 2000);
        transfer(client, pillar, TODAY.withDayOfYear(1).minusDays(1), 7000); // last year: not counted
        transfer(client, savings, TODAY, 300);                              // another position

        LocalDate deadline = TODAY.plusMonths(11).withDayOfMonth(1);
        MvcResult created = client.post("/api/goals", goal(" Fondo emergenza ", "BALANCE", 20000,
                deadline.toString(), savings));
        assertEquals(201, created.getResponse().getStatus());
        assertEquals("Fondo emergenza", json(created, "$.name"));
        assertEquals(8000.0, ((Number) json(created, "$.current")).doubleValue());
        assertEquals(12000.0, ((Number) json(created, "$.remaining")).doubleValue());
        assertEquals(40.0, ((Number) json(created, "$.percent")).doubleValue());
        assertEquals(500.0, ((Number) json(created, "$.monthlyPace")).doubleValue());
        // 12 months including the current one: 1000 a month needed, 500 a month saved
        assertEquals(12, (int) json(created, "$.monthsLeft"));
        assertEquals(1000.0, ((Number) json(created, "$.requiredMonthly")).doubleValue());
        assertEquals("BEHIND", json(created, "$.state"));
        assertEquals("CHF", json(created, "$.baseCurrency"));

        // A yearly goal ignores a deadline and counts this year's transfers into its positions
        MvcResult yearly = client.post("/api/goals", goal("Pilastro 3a", "YEARLY", 7258, "2030-01-01", pillar));
        assertEquals(201, yearly.getResponse().getStatus());
        assertNull(json(yearly, "$.targetDate"));
        assertEquals(2000.0, ((Number) json(yearly, "$.current")).doubleValue());
        assertEquals(TODAY.getYear(), (int) json(yearly, "$.year"));

        List<Map<String, Object>> goals = json(client.get("/api/goals"), "$");
        assertEquals(List.of("Fondo emergenza", "Pilastro 3a"), goals.stream().map(g -> g.get("name")).toList());

        // Editing: a lower target is reached
        long id = ((Number) json(created, "$.id")).longValue();
        MvcResult edited = client.put("/api/goals/" + id, goal("Fondo emergenza", "BALANCE", 6000, null, savings));
        assertEquals(200, edited.getResponse().getStatus());
        assertEquals("REACHED", json(edited, "$.state"));
        assertNull(json(edited, "$.requiredMonthly"));

        // Deleting a position just removes it from its goals
        assertEquals(204, client.delete("/api/positions/" + savings).getResponse().getStatus());
        assertEquals(List.of(), json(client.get("/api/goals"), "$[?(@.id == " + id + ")].positionIds[*]"));

        assertEquals(204, client.delete("/api/goals/" + id).getResponse().getStatus());
        assertEquals(1, ((List<?>) json(client.get("/api/goals"), "$")).size());
    }

    @Test
    void invalidGoalsAreRejected() throws Exception {
        ApiClient client = login(testUsers.create("goals-invalid", Role.USER));
        long savings = position(client, "Conto", "CASH");
        assertEquals(400, client.post("/api/goals", goal("", "BALANCE", 100, null, savings)).getResponse().getStatus());
        assertEquals(400, client.post("/api/goals", goal("Senza posizioni", "BALANCE", 100, null))
                .getResponse().getStatus());
        assertEquals(400, client.post("/api/goals", goal("Zero", "BALANCE", 0, null, savings)).getResponse().getStatus());
        assertEquals(400, client.post("/api/goals", goal("Troppo lontano", "BALANCE", 100, "2300-01-01", savings))
                .getResponse().getStatus());
        assertEquals(400, client.post("/api/goals", """
                {"name":"Tipo","kind":"MONTHLY","targetAmount":100,"currency":"CHF","positionIds":[%d]}"""
                .formatted(savings)).getResponse().getStatus());
        assertEquals(404, client.post("/api/goals", goal("Posizione inesistente", "BALANCE", 100, null, 999_999_999L))
                .getResponse().getStatus());
    }

    @Test
    void goalsAndTheirPositionsStayPrivate() throws Exception {
        ApiClient alice = login(testUsers.create("goals-alice", Role.USER));
        ApiClient bob = login(testUsers.create("goals-bob", Role.USER));
        long alicePosition = position(alice, "Conto Alice", "CASH");
        long bobPosition = position(bob, "Conto Bob", "CASH");
        long goalId = ((Number) json(alice.post("/api/goals", goal("Casa", "BALANCE", 100000, null, alicePosition)),
                "$.id")).longValue();

        // Bob cannot see, change or delete Alice's goal, nor link her position to his own goal
        assertEquals(List.of(), json(bob.get("/api/goals"), "$"));
        assertEquals(404, bob.put("/api/goals/" + goalId, goal("Mia", "BALANCE", 1, null, bobPosition))
                .getResponse().getStatus());
        assertEquals(404, bob.delete("/api/goals/" + goalId).getResponse().getStatus());
        assertEquals(404, bob.post("/api/goals", goal("Furbo", "BALANCE", 1, null, bobPosition, alicePosition))
                .getResponse().getStatus());
        assertEquals("Casa", json(alice.get("/api/goals"), "$[0].name"));
    }
}
