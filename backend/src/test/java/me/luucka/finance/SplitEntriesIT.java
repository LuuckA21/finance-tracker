package me.luucka.finance;

import static me.luucka.finance.support.ApiClient.body;
import static me.luucka.finance.support.ApiClient.json;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import me.luucka.finance.support.ApiClient;
import me.luucka.finance.support.IntegrationTest;
import me.luucka.finance.support.TestUsers;
import me.luucka.finance.user.Role;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@IntegrationTest
class SplitEntriesIT {

    @Autowired
    MockMvc mvc;

    @Autowired
    TestUsers testUsers;

    private ApiClient login(String prefix) throws Exception {
        ApiClient client = new ApiClient(mvc);
        assertEquals(200, client.login(testUsers.create(prefix, Role.USER).getUsername(), TestUsers.PASSWORD));
        return client;
    }

    private static long category(ApiClient client, String name) throws Exception {
        List<Integer> ids = json(client.get("/api/categories"), "$[?(@.name == '" + name + "')].id");
        return ids.getFirst();
    }

    private static String split(String kind, String parts) {
        return """
                {"date":"2026-09-12","kind":"%s","currency":"CHF","description":"Migros Zürich","tags":["Spesa"],
                 "parts":%s}""".formatted(kind, parts);
    }

    private static String part(long categoryId, String amount) {
        return "{\"categoryId\":%d,\"amount\":%s}".formatted(categoryId, amount);
    }

    @Test
    void aReceiptSplitAmongCategoriesBecomesOneEntryPerPart() throws Exception {
        ApiClient client = login("split");
        long food = category(client, "Supermercato");
        long home = category(client, "Arredamento");

        MvcResult created = client.post("/api/cash-entries/split",
                split("EXPENSE", "[%s,%s]".formatted(part(food, "90.50"), part(home, "29.50"))));
        assertEquals(201, created.getResponse().getStatus(), body(created));
        String group = json(created, "$[0].splitGroup");
        assertNotNull(group);
        assertEquals(group, json(created, "$[1].splitGroup"));
        assertEquals(List.of((int) food, (int) home), json(created, "$[*].categoryId"));
        assertEquals(List.of(List.of("Spesa"), List.of("Spesa")), json(created, "$[*].tags"));

        // Ordinary entries for everything else: the list, the parts of the group
        assertEquals(2, (int) json(client.get("/api/cash-entries"), "$.totalElements"));
        MvcResult parts = client.get("/api/cash-entries/split/" + group);
        assertEquals(List.of(90.5, 29.5), ((List<Number>) json(parts, "$[*].amount")).stream()
                .map(Number::doubleValue).toList());

        // Three parts, then one: back to an ordinary entry
        MvcResult three = client.put("/api/cash-entries/split/" + group, split("EXPENSE", "[%s,%s,%s]"
                .formatted(part(food, "60"), part(food, "30.50"), part(home, "29.50"))));
        assertEquals(200, three.getResponse().getStatus(), body(three));
        assertEquals(3, ((List<?>) json(three, "$")).size());
        assertEquals(group, json(three, "$[2].splitGroup"));
        MvcResult one = client.put("/api/cash-entries/split/" + group, split("EXPENSE", "[%s]".formatted(part(food, "120"))));
        assertNull(json(one, "$[0].splitGroup"));
        assertEquals(1, (int) json(client.get("/api/cash-entries"), "$.totalElements"));
        assertEquals(404, client.get("/api/cash-entries/split/" + group).getResponse().getStatus());
    }

    @Test
    void anOrdinaryEntryCanBeSplitInPlace() throws Exception {
        ApiClient client = login("splitreplace");
        long food = category(client, "Supermercato");
        long home = category(client, "Arredamento");
        int id = json(client.post("/api/cash-entries", """
                {"date":"2026-09-12","kind":"EXPENSE","categoryId":%d,"amount":120,"currency":"CHF"}"""
                .formatted(food)), "$.id");
        String body = split("EXPENSE", "[%s,%s]".formatted(part(food, "90"), part(home, "30")));
        MvcResult created = client.post("/api/cash-entries/split",
                body.substring(0, body.length() - 1) + ",\"replaces\":" + id + "}");
        assertEquals(201, created.getResponse().getStatus(), body(created));
        assertEquals(2, (int) json(client.get("/api/cash-entries"), "$.totalElements"));
        assertEquals(List.of(), json(client.get("/api/cash-entries"), "$.content[?(@.id == " + id + ")]"));
        // A part is not replaced again this way
        int part = json(created, "$[0].id");
        assertEquals("split_replace", json(client.post("/api/cash-entries/split",
                body.substring(0, body.length() - 1) + ",\"replaces\":" + part + "}"), "$.code"));
    }

    @Test
    void theWholeSplitEntryIsDeletedTogether() throws Exception {
        ApiClient client = login("splitdel");
        long food = category(client, "Supermercato");
        String group = json(client.post("/api/cash-entries/split",
                split("EXPENSE", "[%s,%s]".formatted(part(food, "1"), part(food, "2")))), "$[0].splitGroup");
        assertEquals(204, client.delete("/api/cash-entries/split/" + group).getResponse().getStatus());
        assertEquals(0, (int) json(client.get("/api/cash-entries"), "$.totalElements"));
    }

    @Test
    void invalidSplitsAreRefusedAndChangeNothing() throws Exception {
        ApiClient client = login("splitbad");
        long food = category(client, "Supermercato");
        long salary = category(client, "Stipendio");
        assertEquals("split_transfer", json(client.post("/api/cash-entries/split",
                split("TRANSFER", "[%s,%s]".formatted(part(food, "1"), part(food, "2")))), "$.code"));
        assertEquals("split_parts", json(client.post("/api/cash-entries/split",
                split("EXPENSE", "[%s]".formatted(part(food, "1")))), "$.code"));
        // An income category on an expense
        assertEquals(400, client.post("/api/cash-entries/split",
                split("EXPENSE", "[%s,%s]".formatted(part(food, "1"), part(salary, "2")))).getResponse().getStatus());
        assertEquals(0, (int) json(client.get("/api/cash-entries"), "$.totalElements"));

        // Another user's group or category: not found, and the parts stay as they were
        MvcResult created = client.post("/api/cash-entries/split",
                split("EXPENSE", "[%s,%s]".formatted(part(food, "1"), part(food, "2"))));
        String group = json(created, "$[0].splitGroup");
        ApiClient other = login("splitother");
        assertEquals(404, other.get("/api/cash-entries/split/" + group).getResponse().getStatus());
        assertEquals(404, other.delete("/api/cash-entries/split/" + group).getResponse().getStatus());
        assertEquals(404, client.put("/api/cash-entries/split/" + group, split("EXPENSE", "[%s,%s]"
                .formatted(part(food, "5"), part(category(other, "Ristoranti"), "5")))).getResponse().getStatus());
        assertEquals(List.of(1.0, 2.0), ((List<Number>) json(client.get("/api/cash-entries/split/" + group),
                "$[*].amount")).stream().map(Number::doubleValue).toList());

        // A part cannot become a transfer on its own
        int partId = json(created, "$[0].id");
        assertEquals("split_transfer", json(client.put("/api/cash-entries/" + partId, """
                {"date":"2026-09-12","kind":"TRANSFER","amount":1,"currency":"CHF"}"""), "$.code"));
        assertNotEquals(0, partId);
    }
}
