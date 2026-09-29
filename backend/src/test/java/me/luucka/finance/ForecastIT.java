package me.luucka.finance;

import static me.luucka.finance.support.ApiClient.json;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDate;
import java.time.YearMonth;
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
class ForecastIT {

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

    private static void entry(ApiClient client, LocalDate date, String kind, long categoryId, int amount, String tags)
            throws Exception {
        assertEquals(201, client.post("/api/cash-entries", """
                {"date":"%s","kind":"%s","categoryId":%d,"amount":%d,"currency":"CHF","tags":%s}"""
                .formatted(date, kind, categoryId, amount, tags)).getResponse().getStatus());
    }

    private static double number(MvcResult result, String path) throws Exception {
        return ((Number) json(result, path)).doubleValue();
    }

    private static String scenario(String name, int year, String excludedTags, String excludedCategories,
                                   String items) {
        return """
                {"name":%s,"year":%d,"incomeGrowth":10,"expenseGrowth":0,"excludedTagIds":%s,
                 "excludedCategoryIds":%s,"items":%s}""".formatted(name, year, excludedTags, excludedCategories, items);
    }

    @Test
    void theForecastGrowsTheLastTwelveMonthsAndAddsTheExtraItems() throws Exception {
        ApiClient client = login(testUsers.create("forecast", Role.USER));
        long salary = category(client, "Stipendio");
        long food = category(client, "Spesa alimentare");
        long travel = category(client, "Viaggi");
        long fun = category(client, "Svago");
        long home = category(client, "Casa");
        LocalDate today = LocalDate.now();
        YearMonth last = YearMonth.from(today).minusMonths(1);
        LocalDate lastMonth = last.atDay(10);
        entry(client, lastMonth, "INCOME", salary, 5000, "[]");
        entry(client, lastMonth, "EXPENSE", food, 600, "[]");
        entry(client, lastMonth, "EXPENSE", travel, 2000, "[\"Viaggio unico\"]");
        entry(client, lastMonth, "EXPENSE", fun, 300, "[]");
        // The current month is not complete: not in the base
        entry(client, today, "EXPENSE", food, 999, "[]");
        List<Integer> tagIds = json(client.get("/api/tags"), "$.tags[?(@.name == 'Viaggio unico')].id");
        long oneOff = tagIds.getFirst();
        int year = today.getYear() + 1;
        String rent = """
                [{"description":"Affitto","kind":"EXPENSE","categoryId":%d,"amount":1500,"schedule":"MONTHLY",
                  "startMonth":1}]""".formatted(home);

        // Preview, not saved: the name is not needed
        MvcResult plain = client.post("/api/forecasts/preview", scenario("null", year, "[]", "[]", "[]"));
        assertEquals(200, plain.getResponse().getStatus());
        assertEquals(last.minusMonths(11).toString(), json(plain, "$.baseFrom"));
        assertEquals(last.toString(), json(plain, "$.baseTo"));
        assertEquals(2900.0, number(plain, "$.baseTotals.expense"));

        MvcResult result = client.post("/api/forecasts/preview",
                scenario("null", year, "[" + oneOff + "]", "[" + fun + "]", rent));
        assertEquals(5000.0, number(result, "$.baseTotals.income"));
        assertEquals(600.0, number(result, "$.baseTotals.expense"));
        assertEquals(5500.0, number(result, "$.totals.income"));
        assertEquals(18600.0, number(result, "$.totals.expense"));
        assertEquals(500.0, number(result, "$.fromGrowth.income"));
        assertEquals(18000.0, number(result, "$.fromItems.expense"));
        assertEquals(List.of(18000.0), ((List<?>) json(result, "$.itemTotals")).stream()
                .map(v -> ((Number) v).doubleValue()).toList());
        // The calendar month of the base month carries its amounts, grown
        int month = last.getMonthValue();
        assertEquals(5500.0, number(result, "$.months[" + (month - 1) + "].forecast.income"));
        assertEquals(2100.0, number(result, "$.months[" + (month - 1) + "].forecast.expense"));
        assertEquals("Casa", json(result, "$.categories[0].name"));
        assertEquals(18000.0, number(result, "$.categories[0].forecast"));

        // Saved, listed, changed, deleted
        assertEquals("invalid_name", json(client.post("/api/forecasts",
                scenario("\" \"", year, "[]", "[]", rent)), "$.code"));
        MvcResult created = client.post("/api/forecasts",
                scenario("\"Con affitto\"", year, "[" + oneOff + "]", "[" + fun + "]", rent));
        assertEquals(201, created.getResponse().getStatus());
        long id = ((Number) json(created, "$.id")).longValue();
        assertEquals(List.of((int) oneOff), json(created, "$.excludedTagIds"));
        assertEquals("Affitto", json(created, "$.items[0].description"));
        assertEquals(List.of("Con affitto"), json(client.get("/api/forecasts"), "$[*].name"));
        MvcResult updated = client.put("/api/forecasts/" + id, scenario("\"Senza affitto\"", year, "[]", "[]", "[]"));
        assertEquals(200, updated.getResponse().getStatus());
        assertEquals(List.of(), json(updated, "$.items"));
        assertEquals(204, client.delete("/api/forecasts/" + id).getResponse().getStatus());
        assertEquals(List.of(), json(client.get("/api/forecasts"), "$"));
    }

    @Test
    void badItemsAndOtherUsersDataAreRefused() throws Exception {
        ApiClient alice = login(testUsers.create("forecast-alice", Role.USER));
        ApiClient bob = login(testUsers.create("forecast-bob", Role.USER));
        long aliceHome = category(alice, "Casa");
        long aliceSalary = category(alice, "Stipendio");
        long bobHome = category(bob, "Casa");
        int year = LocalDate.now().getYear() + 1;
        String item = """
                [{"description":"Voce","kind":"%s","categoryId":%s,"amount":%s,"schedule":"MONTHLY",
                  "startMonth":%d,"endMonth":%d}]""";

        assertEquals("invalid_item_kind", json(alice.post("/api/forecasts/preview", scenario("null", year, "[]", "[]",
                item.formatted("TRANSFER", "null", "100", 1, 12))), "$.code"));
        assertEquals("invalid_months", json(alice.post("/api/forecasts/preview", scenario("null", year, "[]", "[]",
                item.formatted("EXPENSE", "null", "100", 6, 3))), "$.code"));
        assertEquals("invalid_amount", json(alice.post("/api/forecasts/preview", scenario("null", year, "[]", "[]",
                item.formatted("EXPENSE", "null", "0", 1, 12))), "$.code"));
        assertEquals("category_kind_mismatch", json(alice.post("/api/forecasts/preview", scenario("null", year, "[]",
                "[]", item.formatted("EXPENSE", aliceSalary, "100", 1, 12))), "$.code"));
        assertEquals(400, alice.post("/api/forecasts/preview", """
                {"year":%d,"incomeGrowth":5000,"expenseGrowth":0}""".formatted(year)).getResponse().getStatus());

        // Bob's category, as an item or an exclusion, is not Alice's to use
        assertEquals(404, alice.post("/api/forecasts/preview", scenario("null", year, "[]", "[]",
                item.formatted("EXPENSE", bobHome, "100", 1, 12))).getResponse().getStatus());
        assertEquals(404, alice.post("/api/forecasts/preview", scenario("null", year, "[]", "[" + bobHome + "]", "[]"))
                .getResponse().getStatus());

        // Nor are Alice's scenarios Bob's
        long id = ((Number) json(alice.post("/api/forecasts", scenario("\"Mio\"", year, "[]", "[]",
                item.formatted("EXPENSE", aliceHome, "100", 1, 12))), "$.id")).longValue();
        assertEquals(List.of(), json(bob.get("/api/forecasts"), "$"));
        assertEquals(404, bob.put("/api/forecasts/" + id, scenario("\"Suo\"", year, "[]", "[]", "[]"))
                .getResponse().getStatus());
        assertEquals(404, bob.delete("/api/forecasts/" + id).getResponse().getStatus());
        assertEquals(List.of("Mio"), json(alice.get("/api/forecasts"), "$[*].name"));
    }
}
