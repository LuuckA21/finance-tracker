package me.luucka.finance;

import static me.luucka.finance.support.ApiClient.json;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
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
class TagsIT {

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

    private static MvcResult entry(ApiClient client, String date, String kind, Long categoryId, int amount,
                                   String description, String tags) throws Exception {
        return client.post("/api/cash-entries", """
                {"date":"%s","kind":"%s","categoryId":%s,"amount":%d,"currency":"CHF","description":"%s","tags":%s}"""
                .formatted(date, kind, categoryId, amount, description, tags));
    }

    private static long tagId(ApiClient client, String name) throws Exception {
        List<Integer> ids = json(client.get("/api/tags"), "$.tags[?(@.name == '" + name + "')].id");
        return ids.getFirst();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Double> doubles(Object map) {
        Map<String, Double> result = new HashMap<>();
        ((Map<String, Object>) map).forEach((k, v) -> result.put(k, ((Number) v).doubleValue()));
        return result;
    }

    @Test
    @SuppressWarnings("unchecked")
    void entriesCarryTagsThatFilterAndAddUp() throws Exception {
        ApiClient client = login(testUsers.create("tags", Role.USER));
        long restaurants = category(client, "Ristoranti");
        long travel = category(client, "Viaggi");
        long salary = category(client, "Stipendio");

        MvcResult first = entry(client, "2026-07-10", "EXPENSE", travel, 800, "Traghetto",
                "[\" Vacanze  Sardegna \", \"Famiglia\"]");
        assertEquals(201, first.getResponse().getStatus());
        assertEquals(List.of("Famiglia", "Vacanze Sardegna"), json(first, "$.tags"));
        // Same tag whatever the case: no second "vacanze sardegna"
        entry(client, "2026-07-12", "EXPENSE", restaurants, 120, "Cena", "[\"vacanze sardegna\"]");
        entry(client, "2026-07-20", "INCOME", salary, 6000, "Stipendio", "[]");
        entry(client, "2026-07-15", "EXPENSE", restaurants, 40, "Pizza", "null");

        Map<String, Object> tags = json(client.get("/api/tags"), "$");
        assertEquals("CHF", tags.get("baseCurrency"));
        List<Map<String, Object>> rows = json(client.get("/api/tags"), "$.tags");
        assertEquals(List.of("Famiglia", "Vacanze Sardegna"), rows.stream().map(r -> r.get("name")).toList());
        Map<String, Object> holidays = rows.get(1);
        assertEquals(2, holidays.get("entryCount"));
        assertEquals(920.0, ((Number) holidays.get("expense")).doubleValue());
        assertEquals(0.0, ((Number) holidays.get("income")).doubleValue());
        assertEquals("2026-07-10", holidays.get("firstDate"));
        assertEquals("2026-07-12", holidays.get("lastDate"));
        // Split by category, largest first
        List<Map<String, Object>> byCategory = (List<Map<String, Object>>) holidays.get("categories");
        assertEquals(List.of((int) travel, (int) restaurants), byCategory.stream().map(c -> c.get("categoryId")).toList());
        assertEquals(800.0, ((Number) byCategory.get(0).get("amount")).doubleValue());
        assertEquals("EXPENSE", byCategory.get(1).get("kind"));

        long holidaysId = tagId(client, "Vacanze Sardegna");
        List<String> filtered = json(client.get("/api/cash-entries?tagId=" + holidaysId), "$.content[*].description");
        assertEquals(List.of("Cena", "Traghetto"), filtered);

        // Editing an entry replaces its tags
        long pizza = ((Number) json(client.get("/api/cash-entries?q=Pizza"), "$.content[0].id")).longValue();
        MvcResult edited = client.put("/api/cash-entries/" + pizza, """
                {"date":"2026-07-15","kind":"EXPENSE","categoryId":%d,"amount":40,"currency":"CHF",
                 "description":"Pizza","tags":["Vacanze Sardegna","Amici"]}""".formatted(restaurants));
        assertEquals(200, edited.getResponse().getStatus());
        assertEquals(List.of("Amici", "Vacanze Sardegna"), json(edited, "$.tags"));
        assertEquals(3, ((List<?>) json(client.get("/api/cash-entries?tagId=" + holidaysId), "$.content")).size());

        // Renaming: a clash with another tag is refused, whatever the case
        long friends = tagId(client, "Amici");
        assertEquals(409, client.put("/api/tags/" + friends, "{\"name\":\"famiglia\"}").getResponse().getStatus());
        MvcResult renamed = client.put("/api/tags/" + friends, "{\"name\":\"Amici di Lugano\"}");
        assertEquals(200, renamed.getResponse().getStatus());
        assertEquals(List.of("Amici di Lugano", "Vacanze Sardegna"),
                json(client.get("/api/cash-entries?q=Pizza"), "$.content[0].tags"));

        // Deleting a tag keeps its entries
        assertEquals(204, client.delete("/api/tags/" + holidaysId).getResponse().getStatus());
        assertEquals(4, ((Number) json(client.get("/api/cash-entries"), "$.totalElements")).intValue());
        assertEquals(List.of("Famiglia"), json(client.get("/api/cash-entries?q=Traghetto"), "$.content[0].tags"));
    }

    @Test
    void theYearsCashFlowSplitsByTag() throws Exception {
        ApiClient client = login(testUsers.create("tags-cashflow", Role.USER));
        long restaurants = category(client, "Ristoranti");
        long travel = category(client, "Viaggi");
        long salary = category(client, "Stipendio");
        entry(client, "2026-07-10", "EXPENSE", travel, 800, "Traghetto", "[\"Vacanze\",\"Famiglia\"]");
        entry(client, "2026-07-12", "EXPENSE", restaurants, 120, "Cena", "[\"Vacanze\"]");
        entry(client, "2026-07-15", "EXPENSE", restaurants, 80, "Pizza", "[]");
        entry(client, "2026-07-25", "INCOME", salary, 6000, "Stipendio", "[\"Famiglia\"]");
        // Another year and transfers stay out
        entry(client, "2025-12-30", "EXPENSE", restaurants, 500, "Capodanno", "[\"Vacanze\"]");
        String account = json(client.post("/api/positions", """
                {"name":"Conto","assetClass":"CASH","currency":"CHF"}"""), "$.id").toString();
        client.post("/api/cash-entries", """
                {"date":"2026-07-26","kind":"TRANSFER","amount":1000,"currency":"CHF","fromPositionId":%s,
                 "tags":["Vacanze"]}""".formatted(account));

        MvcResult year = client.get("/api/dashboard/cashflow?year=2026");
        List<Map<String, Object>> rows = json(year, "$.tags");
        // Largest first; an entry with two tags counts for both, so shares go past 100% together
        assertEquals(List.of("Famiglia/INCOME", "Vacanze/EXPENSE", "Famiglia/EXPENSE"),
                rows.stream().map(r -> r.get("name") + "/" + r.get("kind")).toList());
        assertEquals(6000.0, ((Number) rows.get(0).get("amount")).doubleValue());
        assertEquals(100.0, ((Number) rows.get(0).get("share")).doubleValue());
        assertEquals(920.0, ((Number) rows.get(1).get("amount")).doubleValue());
        assertEquals(92.0, ((Number) rows.get(1).get("share")).doubleValue());
        assertEquals(2, rows.get(1).get("entryCount"));
        assertEquals(80.0, ((Number) rows.get(2).get("share")).doubleValue());
        assertEquals((int) tagId(client, "Vacanze"), rows.get(1).get("tagId"));

        // Categories × tags: every expense category, the ferry in both columns, the pizza untagged
        long holidays = tagId(client, "Vacanze");
        long family = tagId(client, "Famiglia");
        assertEquals(List.of("EXPENSE", "INCOME"), json(year, "$.tagMatrices[*].kind"));
        assertEquals(List.of((int) holidays, (int) family), json(year, "$.tagMatrices[0].tagIds"));
        List<Map<String, Object>> matrix = json(year, "$.tagMatrices[0].rows");
        assertEquals(List.of((int) travel, (int) restaurants), matrix.stream().map(r -> r.get("categoryId")).toList());
        assertEquals(Map.of(String.valueOf(holidays), 800.0, String.valueOf(family), 800.0),
                doubles(matrix.get(0).get("tags")));
        assertEquals(Map.of(String.valueOf(holidays), 120.0), doubles(matrix.get(1).get("tags")));
        assertEquals(80.0, ((Number) matrix.get(1).get("untagged")).doubleValue());
        assertEquals(200.0, ((Number) matrix.get(1).get("total")).doubleValue());
        assertEquals(80.0, ((Number) json(year, "$.tagMatrices[0].untagged")).doubleValue());

        assertEquals(List.of(), json(client.get("/api/dashboard/cashflow?year=2024"), "$.tags"));
    }

    @Test
    void badTagsAreRefused() throws Exception {
        ApiClient client = login(testUsers.create("tags-bad", Role.USER));
        long food = category(client, "Spesa alimentare");
        assertEquals(400, entry(client, "2026-07-01", "EXPENSE", food, 10, "Virgola", "[\"a,b\"]")
                .getResponse().getStatus());
        assertEquals("invalid_tags", json(entry(client, "2026-07-01", "EXPENSE", food, 10, "Lungo",
                "[\"" + "x".repeat(41) + "\"]"), "$.code"));
        StringBuilder eleven = new StringBuilder("[");
        for (int i = 1; i <= 11; i++) {
            eleven.append(i == 1 ? "" : ",").append("\"t").append(i).append('"');
        }
        assertEquals(400, entry(client, "2026-07-01", "EXPENSE", food, 10, "Troppi", eleven.append(']').toString())
                .getResponse().getStatus());
        // Nothing was saved, not even the valid tags of a refused entry
        assertEquals(List.of(), json(client.get("/api/tags"), "$.tags"));
    }

    @Test
    void tagsTravelThroughCsvExportAndImport() throws Exception {
        ApiClient client = login(testUsers.create("tags-csv", Role.USER));
        long food = category(client, "Spesa alimentare");
        entry(client, "2026-08-01", "EXPENSE", food, 45, "Migros", "[\"Casa\",\"=SUM(A1)\"]");

        String csv = client.get("/api/cash-entries/export").getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(csv.contains(";etichette\r\n"), csv);
        // Tag names are user text: neutralized like descriptions
        assertTrue(csv.contains("2026-08-01;Uscita;Spesa alimentare;45;CHF;Migros;;;'=SUM(A1), Casa\r\n"), csv);

        ApiClient other = login(testUsers.create("tags-csv-2", Role.USER));
        MvcResult preview = other.upload("/api/cash-entries/import/preview", "a.csv", """
                data;importo;categoria;etichette
                01.08.2026;-45;Spesa alimentare;Casa, Vacanze
                02.08.2026;-10;Spesa alimentare;ok, a-very-long-tag-name-that-goes-well-past-forty
                """.getBytes(StandardCharsets.UTF_8));
        assertEquals(List.of("Casa", "Vacanze"), json(preview, "$.rows[0].tags"));
        assertEquals(List.of("invalid_tags"), json(preview, "$.rows[1].errors"));

        MvcResult imported = other.post("/api/cash-entries/import", """
                {"entries":[{"date":"2026-08-01","kind":"EXPENSE","categoryId":%d,"amount":45,"currency":"CHF",
                  "tags":["Casa","Vacanze"]}]}""".formatted(category(other, "Spesa alimentare")));
        assertEquals(201, imported.getResponse().getStatus());
        assertEquals(List.of("Casa", "Vacanze"), json(other.get("/api/cash-entries"), "$.content[0].tags"));
    }

    @Test
    void tagsStayPrivate() throws Exception {
        ApiClient alice = login(testUsers.create("tags-alice", Role.USER));
        ApiClient bob = login(testUsers.create("tags-bob", Role.USER));
        entry(alice, "2026-07-01", "EXPENSE", category(alice, "Svago"), 50, "Cinema", "[\"Segreto\"]");
        entry(bob, "2026-07-01", "EXPENSE", category(bob, "Svago"), 20, "Bowling", "[\"Segreto\"]");
        long aliceTag = tagId(alice, "Segreto");
        long bobTag = tagId(bob, "Segreto");
        assertTrue(aliceTag != bobTag, "same name, separate tags");

        // Bob filtering by Alice's tag sees nothing of hers; he cannot rename or delete it
        assertEquals(List.of(), json(bob.get("/api/cash-entries?tagId=" + aliceTag), "$.content"));
        assertEquals(404, bob.put("/api/tags/" + aliceTag, "{\"name\":\"Mio\"}").getResponse().getStatus());
        assertEquals(404, bob.delete("/api/tags/" + aliceTag).getResponse().getStatus());
        assertEquals(List.of("Segreto"), json(alice.get("/api/cash-entries"), "$.content[0].tags"));
        assertEquals(1, ((List<?>) json(bob.get("/api/tags"), "$.tags")).size());
    }
}
