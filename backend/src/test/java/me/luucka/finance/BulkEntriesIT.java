package me.luucka.finance;

import static me.luucka.finance.support.ApiClient.body;
import static me.luucka.finance.support.ApiClient.json;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.stream.Collectors;

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
class BulkEntriesIT {

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

    private static long entry(ApiClient client, String kind, Long categoryId, String description, String tags)
            throws Exception {
        MvcResult created = client.post("/api/cash-entries", """
                {"date":"2026-09-10","kind":"%s","categoryId":%s,"amount":10,"currency":"CHF",
                 "description":"%s","tags":%s}""".formatted(kind, categoryId, description, tags));
        assertEquals(201, created.getResponse().getStatus(), body(created));
        return ((Number) json(created, "$.id")).longValue();
    }

    private static String ids(long... ids) {
        return "[" + java.util.Arrays.stream(ids).mapToObj(Long::toString).collect(Collectors.joining(",")) + "]";
    }

    private static MvcResult bulk(ApiClient client, String json) throws Exception {
        return client.post("/api/cash-entries/bulk", json);
    }

    @Test
    void aCategoryGoesToTheEntriesOfItsKindAndTagsToAll() throws Exception {
        ApiClient client = login(testUsers.create("bulk", Role.USER));
        long shop = category(client, "Supermercato");
        long a = entry(client, "EXPENSE", shop, "Coop", "[\"Vecchia\"]");
        long b = entry(client, "EXPENSE", shop, "Migros", "[\"Vecchia\",\"Casa\"]");
        long salary = entry(client, "INCOME", category(client, "Stipendio"), "Lohn", "[]");
        long transfer = entry(client, "TRANSFER", null, "Risparmio", "[]");
        long restaurants = category(client, "Ristoranti");

        MvcResult result = bulk(client, """
                {"ids":%s,"action":"UPDATE","categoryId":%d,"addTags":["Vacanze 2026"],"removeTags":["vecchia"]}"""
                .formatted(ids(a, b, salary, transfer), restaurants));
        assertEquals(200, result.getResponse().getStatus(), body(result));
        assertEquals(4, (int) json(result, "$.updated"));
        assertEquals(2, (int) json(result, "$.skipped"));

        MvcResult list = client.get("/api/cash-entries?size=50");
        assertEquals(List.of((int) restaurants), json(list, "$.content[?(@.id == " + a + ")].categoryId"));
        assertEquals(List.of((int) restaurants), json(list, "$.content[?(@.id == " + b + ")].categoryId"));
        // The income keeps its category, the transfer has none; both got the tag
        assertEquals(List.of(List.of("Vacanze 2026")), json(list, "$.content[?(@.id == " + salary + ")].tags"));
        assertEquals(List.of(List.of("Vacanze 2026")), json(list, "$.content[?(@.id == " + transfer + ")].tags"));
        assertEquals(List.of(List.of("Casa", "Vacanze 2026")),
                json(list, "$.content[?(@.id == " + b + ")].tags"));
    }

    @Test
    void entriesAreDeletedTogether() throws Exception {
        ApiClient client = login(testUsers.create("bulkdel", Role.USER));
        long shop = category(client, "Supermercato");
        long a = entry(client, "EXPENSE", shop, "Uno", "[]");
        long b = entry(client, "EXPENSE", shop, "Due", "[]");
        long kept = entry(client, "EXPENSE", shop, "Tre", "[]");

        MvcResult result = bulk(client, "{\"ids\":%s,\"action\":\"DELETE\"}".formatted(ids(a, b)));
        assertEquals(2, (int) json(result, "$.updated"));
        assertEquals(List.of((int) kept), json(client.get("/api/cash-entries"), "$.content[*].id"));
    }

    @Test
    void allOrNothing() throws Exception {
        ApiClient other = login(testUsers.create("bulkother", Role.USER));
        long foreign = entry(other, "EXPENSE", category(other, "Supermercato"), "Suo", "[]");
        ApiClient client = login(testUsers.create("bulkmine", Role.USER));
        long shop = category(client, "Supermercato");
        long mine = entry(client, "EXPENSE", shop, "Mio", "[]");

        // Another user's entry among them: nothing happens to either
        assertEquals(404, bulk(client, "{\"ids\":%s,\"action\":\"DELETE\"}".formatted(ids(mine, foreign)))
                .getResponse().getStatus());
        assertEquals(1, (int) json(client.get("/api/cash-entries"), "$.totalElements"));
        assertEquals(1, (int) json(other.get("/api/cash-entries"), "$.totalElements"));
        // Nor another user's category
        assertEquals(404, bulk(client, "{\"ids\":%s,\"action\":\"UPDATE\",\"categoryId\":%d}"
                .formatted(ids(mine), category(other, "Ristoranti"))).getResponse().getStatus());

        assertEquals("bulk_nothing", json(bulk(client, "{\"ids\":%s,\"action\":\"UPDATE\"}".formatted(ids(mine))),
                "$.code"));
        assertEquals(400, bulk(client, "{\"ids\":[],\"action\":\"DELETE\"}").getResponse().getStatus());

        // Too many tags on one entry rolls everything back, the new tags too
        long full = entry(client, "EXPENSE", shop, "Pieno",
                "[\"t1\",\"t2\",\"t3\",\"t4\",\"t5\",\"t6\",\"t7\",\"t8\",\"t9\"]");
        MvcResult tooMany = bulk(client, "{\"ids\":%s,\"action\":\"UPDATE\",\"addTags\":[\"x1\",\"x2\"]}"
                .formatted(ids(mine, full)));
        assertEquals("invalid_tags", json(tooMany, "$.code"));
        assertEquals(List.of(List.of()), json(client.get("/api/cash-entries"), "$.content[?(@.id == " + mine + ")].tags"));
        assertEquals(List.of(), json(client.get("/api/tags"), "$.tags[?(@.name == 'x1')]"));
    }

    @Test
    void theIdsOfTheFilteredEntries() throws Exception {
        ApiClient client = login(testUsers.create("bulkids", Role.USER));
        long shop = category(client, "Supermercato");
        long a = entry(client, "EXPENSE", shop, "Uno", "[]");
        long b = entry(client, "EXPENSE", shop, "Due", "[]");
        entry(client, "INCOME", category(client, "Stipendio"), "Lohn", "[]");

        MvcResult result = client.get("/api/cash-entries/ids?kind=EXPENSE");
        assertEquals(2, (int) json(result, "$.total"));
        List<Number> found = json(result, "$.ids");
        assertEquals(List.of(b, a), found.stream().map(Number::longValue).toList());
    }
}
