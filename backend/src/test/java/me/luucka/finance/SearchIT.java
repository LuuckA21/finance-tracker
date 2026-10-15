package me.luucka.finance;

import static me.luucka.finance.support.ApiClient.body;
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
class SearchIT {

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

    private static long id(MvcResult created) throws Exception {
        assertEquals(201, created.getResponse().getStatus(), body(created));
        return ((Number) json(created, "$.id")).longValue();
    }

    private static long entry(ApiClient client, String date, long categoryId, String amount, String description)
            throws Exception {
        return id(client.post("/api/cash-entries", """
                {"date":"%s","kind":"EXPENSE","categoryId":%d,"amount":%s,"currency":"CHF","description":"%s",
                 "tags":["Coop Punkte"]}""".formatted(date, categoryId, amount, description)));
    }

    private static MvcResult search(ApiClient client, String text) throws Exception {
        // MockMvc encodes the query itself
        return client.get("/api/search?q=" + text);
    }

    @Test
    void findsTheUsersRecordsOfEveryKind() throws Exception {
        ApiClient client = login(testUsers.create("search", Role.USER));
        long shop = category(client, "Supermercato");
        long older = entry(client, "2026-08-02", shop, "12.40", "Coop Pronto");
        long newer = entry(client, "2026-09-15", shop, "45.20", "COOP City Zürich");
        entry(client, "2026-09-16", shop, "8", "Migros");
        long account = id(client.post("/api/positions", """
                {"name":"Conto risparmio","assetClass":"CASH","currency":"CHF","iban":"CH9300762011623852957"}"""));
        long etf = id(client.post("/api/positions", """
                {"name":"Azioni mondo","symbol":"VWRL","assetClass":"ETF","currency":"CHF"}"""));
        long rule = id(client.post("/api/recurring-entries", """
                {"kind":"EXPENSE","categoryId":%d,"amount":30,"currency":"CHF","description":"Abbonamento Coop",
                 "frequency":"MONTHLY","startDate":"2099-01-01"}""".formatted(shop)));
        long goal = id(client.post("/api/goals", """
                {"name":"Vacanze in Coop","kind":"BALANCE","targetAmount":1000,"currency":"CHF","targetDate":null,
                 "positionIds":[%d]}""".formatted(account)));

        MvcResult coop = search(client, "  coop ");
        assertEquals(200, coop.getResponse().getStatus(), body(coop));
        // Entries newest first, with the whole entry to open it
        assertEquals(List.of((int) newer, (int) older), json(coop, "$.entries[*].id"));
        assertEquals(2, (int) json(coop, "$.entriesTotal"));
        assertEquals(List.of("Coop Punkte"), json(coop, "$.entries[0].tags"));
        assertEquals(List.of("Coop Punkte"), json(coop, "$.tags[*].name"));
        assertEquals(List.of((int) rule), json(coop, "$.recurring[*].id"));
        assertEquals(List.of((int) goal), json(coop, "$.goals[*].id"));
        assertEquals(List.of(), json(coop, "$.positions"));

        // A detail category carries its macro's name; a macro has none
        MvcResult supermarket = search(client, "SUPERMERC");
        assertEquals(List.of((int) shop), json(supermarket, "$.categories[*].id"));
        assertEquals(List.of("Spesa alimentare"), json(supermarket, "$.categories[*].parentName"));
        assertEquals(List.of(), json(supermarket, "$.entries"));
        assertEquals(List.of((int) category(client, "Spesa alimentare")),
                json(search(client, "spesa alim"), "$.categories[?(@.parentName == null)].id"));

        // Positions by name, ticker or part of the IBAN with or without spaces
        assertEquals(List.of((int) etf), json(search(client, "vwrl"), "$.positions[*].id"));
        assertEquals(List.of((int) account), json(search(client, "0076 2011"), "$.positions[*].id"));
        assertEquals(List.of((int) account), json(search(client, "risparmio"), "$.positions[*].id"));

        // An amount finds the entries of that amount, however it is written
        assertEquals(List.of((int) newer), json(search(client, "45,20"), "$.entries[*].id"));
        assertEquals(List.of((int) newer), json(search(client, "45.2"), "$.entries[*].id"));
    }

    @Test
    void anotherUsersRecordsAreNeverFound() throws Exception {
        ApiClient other = login(testUsers.create("searchother", Role.USER));
        entry(other, "2026-09-01", category(other, "Supermercato"), "10", "Segreto condiviso");
        id(other.post("/api/positions", """
                {"name":"Segreto conto","assetClass":"CASH","currency":"CHF"}"""));

        ApiClient client = login(testUsers.create("searchmine", Role.USER));
        MvcResult result = search(client, "segreto");
        assertEquals(0, (int) json(result, "$.entriesTotal"));
        assertEquals(List.of(), json(result, "$.positions"));
        assertEquals(List.of(), json(result, "$.tags"));
    }

    @Test
    void tooShortTooLongAndWildcards() throws Exception {
        ApiClient client = login(testUsers.create("searchedge", Role.USER));
        entry(client, "2026-09-01", category(client, "Supermercato"), "10", "Sconto 50_percento");

        assertEquals(0, (int) json(search(client, "s"), "$.entriesTotal"));
        assertEquals(List.of(), json(search(client, "s"), "$.categories"));
        MvcResult tooLong = search(client, "x".repeat(101));
        assertEquals(400, tooLong.getResponse().getStatus());
        assertEquals("search_too_long", json(tooLong, "$.code"));
        // % and _ are matched as they are, not as wildcards
        assertEquals(0, (int) json(search(client, "%%"), "$.entriesTotal"));
        assertEquals(1, (int) json(search(client, "50_p"), "$.entriesTotal"));
        assertEquals(0, (int) json(search(client, "5__p"), "$.entriesTotal"));
        assertEquals(401, new ApiClient(mvc).get("/api/search?q=sconto").getResponse().getStatus());
    }
}
