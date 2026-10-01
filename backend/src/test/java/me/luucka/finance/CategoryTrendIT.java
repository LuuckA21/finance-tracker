package me.luucka.finance;

import static me.luucka.finance.support.ApiClient.json;
import static org.junit.jupiter.api.Assertions.assertEquals;

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
class CategoryTrendIT {

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

    private static void expense(ApiClient client, String date, long categoryId, int amount) throws Exception {
        assertEquals(201, client.post("/api/cash-entries", """
                {"date":"%s","kind":"EXPENSE","categoryId":%d,"amount":%d,"currency":"CHF"}"""
                .formatted(date, categoryId, amount)).getResponse().getStatus());
    }

    private static double number(MvcResult result, String path) throws Exception {
        return ((Number) json(result, path)).doubleValue();
    }

    @Test
    void aMacroMonthByMonthWithItsDetailsAgainstTheYearBefore() throws Exception {
        ApiClient client = login(testUsers.create("trend", Role.USER));
        long housing = category(client, "Casa");
        long rent = category(client, "Affitto");
        long energy = category(client, "Energia");
        expense(client, "2025-01-05", rent, 1800);
        expense(client, "2025-01-20", energy, 120);
        expense(client, "2025-03-05", housing, 60);
        expense(client, "2024-01-05", rent, 1700);
        expense(client, "2025-02-01", category(client, "Svago"), 99);

        MvcResult trend = client.get("/api/dashboard/category-trend?categoryId=" + housing + "&year=2025");
        assertEquals(200, trend.getResponse().getStatus());
        assertEquals("Casa", json(trend, "$.name"));
        assertEquals(Integer.valueOf(12), json(trend, "$.lastMonth"));
        assertEquals(12, ((List<?>) json(trend, "$.months")).size());
        assertEquals(1920.0, number(trend, "$.months[0].amount"));
        assertEquals(1700.0, number(trend, "$.months[0].previous"));
        Map<String, Number> january = json(trend, "$.months[0].details");
        assertEquals(1800.0, january.get(String.valueOf(rent)).doubleValue());
        assertEquals(1980.0, number(trend, "$.total"));
        assertEquals(1700.0, number(trend, "$.previousTotal"));
        List<String> details = json(trend, "$.details[*].name");
        assertEquals(List.of("Affitto", "Energia", "Casa"), details);

        // A detail: only its own entries
        MvcResult detail = client.get("/api/dashboard/category-trend?categoryId=" + rent + "&year=2025");
        assertEquals(1800.0, number(detail, "$.total"));
        assertEquals(housing, ((Number) json(detail, "$.parentId")).longValue());
        assertEquals(1, ((List<?>) json(detail, "$.details")).size());

        // Someone else's category, a year out of range
        ApiClient other = login(testUsers.create("trend-other", Role.USER));
        assertEquals(404, other.get("/api/dashboard/category-trend?categoryId=" + housing + "&year=2025")
                .getResponse().getStatus());
        assertEquals(400, client.get("/api/dashboard/category-trend?categoryId=" + housing + "&year=1800")
                .getResponse().getStatus());
    }
}
