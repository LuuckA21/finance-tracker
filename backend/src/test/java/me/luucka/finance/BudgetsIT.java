package me.luucka.finance;

import static me.luucka.finance.support.ApiClient.json;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.YearMonth;
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
class BudgetsIT {

    @Autowired
    MockMvc mvc;

    @Autowired
    TestUsers testUsers;

    private ApiClient login(AppUser user) throws Exception {
        ApiClient client = new ApiClient(mvc);
        assertEquals(200, client.login(user.getUsername(), TestUsers.PASSWORD));
        return client;
    }

    private static long category(ApiClient client, String name) throws Exception {
        List<Integer> ids = json(client.get("/api/categories"), "$[?(@.name == '" + name + "')].id");
        return ids.getFirst();
    }

    private static void entry(ApiClient client, String date, String kind, Long categoryId, int amount) throws Exception {
        assertEquals(201, client.post("/api/cash-entries", """
                {"date":"%s","kind":"%s","categoryId":%s,"amount":%d,"currency":"CHF"}"""
                .formatted(date, kind, categoryId, amount)).getResponse().getStatus());
    }

    @Test
    void monthlyStatusComparesExpensesWithBudgets() throws Exception {
        ApiClient client = login(testUsers.create("budget", Role.USER));
        long food = category(client, "Spesa alimentare");
        long rent = category(client, "Casa");
        long fun = category(client, "Svago");
        assertEquals(200, client.put("/api/budgets/" + food, "{\"amount\":500,\"currency\":\"CHF\"}")
                .getResponse().getStatus());
        client.put("/api/budgets/" + rent, "{\"amount\":1800,\"currency\":\"CHF\"}");
        // Saving again changes the amount instead of adding a second budget
        client.put("/api/budgets/" + food, "{\"amount\":450,\"currency\":\"chf\"}");
        List<Map<String, Object>> budgets = json(client.get("/api/budgets"), "$");
        assertEquals(2, budgets.size());

        entry(client, "2026-08-03", "EXPENSE", food, 400);
        entry(client, "2026-08-01", "EXPENSE", rent, 1900);
        entry(client, "2026-08-20", "EXPENSE", fun, 70);
        entry(client, "2026-08-25", "INCOME", category(client, "Stipendio"), 6000);
        entry(client, "2026-08-28", "TRANSFER", null, 1000);

        MvcResult status = client.get("/api/budgets/status?month=2026-08");
        assertEquals(200, status.getResponse().getStatus());
        assertEquals("2026-08", json(status, "$.month"));
        assertEquals(Boolean.FALSE, json(status, "$.currentMonth"));
        assertEquals(2250.0, ((Number) json(status, "$.budgeted")).doubleValue());
        assertEquals(2300.0, ((Number) json(status, "$.spent")).doubleValue());
        assertEquals(70.0, ((Number) json(status, "$.unbudgeted")).doubleValue());
        assertEquals("Casa", json(status, "$.categories[0].name"));
        assertEquals("OVER", json(status, "$.categories[0].state"));
        assertEquals("WARNING", json(status, "$.categories[1].state"));
        assertEquals(1800.0, ((Number) json(status, "$.categories[0].amount")).doubleValue());
        assertEquals("Svago", json(status, "$.others[0].name"));

        // The current month by default
        MvcResult current = client.get("/api/budgets/status");
        assertEquals(YearMonth.from(LocalDate.now()).toString(), json(current, "$.month"));
        assertEquals(Boolean.TRUE, json(current, "$.currentMonth"));

        assertEquals(204, client.delete("/api/budgets/" + rent).getResponse().getStatus());
        assertEquals(1, ((List<?>) json(client.get("/api/budgets"), "$")).size());
    }

    @Test
    void budgetsAreValidatedAndPrivate() throws Exception {
        ApiClient alice = login(testUsers.create("budget-alice", Role.USER));
        ApiClient bob = login(testUsers.create("budget-bob", Role.USER));
        long aliceFood = category(alice, "Spesa alimentare");
        alice.put("/api/budgets/" + aliceFood, "{\"amount\":300,\"currency\":\"CHF\"}");

        assertEquals("budget_expense_only", json(alice.put("/api/budgets/" + category(alice, "Stipendio"),
                "{\"amount\":300,\"currency\":\"CHF\"}"), "$.code"));
        MvcResult invalid = alice.put("/api/budgets/" + aliceFood, "{\"amount\":0,\"currency\":\"QQQ\"}");
        Map<String, String> errors = json(invalid, "$.errors");
        assertTrue(errors.keySet().containsAll(List.of("amount", "currency")), errors.toString());

        // Another user's category: not found, and nothing leaks into their status
        assertEquals(404, bob.put("/api/budgets/" + aliceFood, "{\"amount\":1,\"currency\":\"CHF\"}")
                .getResponse().getStatus());
        assertEquals(404, bob.delete("/api/budgets/" + aliceFood).getResponse().getStatus());
        assertEquals(List.of(), json(bob.get("/api/budgets"), "$"));
        assertEquals(List.of(), json(bob.get("/api/budgets/status"), "$.categories"));
        assertEquals(1, ((List<?>) json(alice.get("/api/budgets"), "$")).size());

        assertEquals(400, alice.get("/api/budgets/status?month=2026-13").getResponse().getStatus());
        assertEquals("month_out_of_range", json(alice.get("/api/budgets/status?month=1800-01"), "$.code"));
        assertEquals(401, new ApiClient(mvc).get("/api/budgets/status").getResponse().getStatus());
    }
}
