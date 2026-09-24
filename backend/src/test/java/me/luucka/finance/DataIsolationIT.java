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

/**
 * Every resource is scoped to its owner: another user (even an admin) gets 404 and never sees it.
 */
@IntegrationTest
class DataIsolationIT {

    @Autowired
    MockMvc mvc;

    @Autowired
    TestUsers testUsers;

    @Test
    void usersCannotAccessEachOthersData() throws Exception {
        AppUser alice = testUsers.create("alice", Role.USER);
        AppUser mallory = testUsers.create("mallory", Role.ADMIN);
        ApiClient a = new ApiClient(mvc);
        ApiClient m = new ApiClient(mvc);
        assertEquals(200, a.login(alice.getUsername(), TestUsers.PASSWORD));
        assertEquals(200, m.login(mallory.getUsername(), TestUsers.PASSWORD));

        // Alice's data
        List<Integer> expenseCategories = json(a.get("/api/categories"), "$[?(@.kind == 'EXPENSE')].id");
        int categoryId = expenseCategories.getFirst();
        MvcResult entry = a.post("/api/cash-entries", """
                {"date":"2026-01-10","kind":"EXPENSE","categoryId":%d,"amount":42.5,"currency":"CHF"}
                """.formatted(categoryId));
        assertEquals(201, entry.getResponse().getStatus());
        int entryId = json(entry, "$.id");

        MvcResult position = a.post("/api/positions", """
                {"name":"Conto","assetClass":"CASH","currency":"CHF"}""");
        assertEquals(201, position.getResponse().getStatus());
        int positionId = json(position, "$.id");
        MvcResult snapshot = a.post("/api/positions/" + positionId + "/snapshots", """
                {"date":"2026-01-31","quantity":1000,"unitPrice":1}""");
        assertEquals(200, snapshot.getResponse().getStatus());
        int snapshotId = json(snapshot, "$.id");

        MvcResult rate = a.post("/api/fx-rates", """
                {"currency":"EUR","date":"2026-01-01","rate":0.95}""");
        int rateId = json(rate, "$.id");

        // Mallory sees nothing and can touch nothing
        assertEquals(Integer.valueOf(0), json(m.get("/api/cash-entries"), "$.totalElements"));
        assertEquals(404, m.put("/api/cash-entries/" + entryId, """
                {"date":"2026-01-10","kind":"EXPENSE","categoryId":%d,"amount":1,"currency":"CHF"}
                """.formatted(categoryId)).getResponse().getStatus());
        assertEquals(404, m.delete("/api/cash-entries/" + entryId).getResponse().getStatus());
        assertEquals(404, m.post("/api/cash-entries", """
                {"date":"2026-01-10","kind":"EXPENSE","categoryId":%d,"amount":1,"currency":"CHF"}
                """.formatted(categoryId)).getResponse().getStatus());
        assertEquals(404, m.delete("/api/categories/" + categoryId).getResponse().getStatus());

        assertEquals(404, m.get("/api/positions/" + positionId).getResponse().getStatus());
        assertEquals(404, m.get("/api/positions/" + positionId + "/snapshots").getResponse().getStatus());
        assertEquals(404, m.delete("/api/positions/" + positionId + "/snapshots/" + snapshotId)
                .getResponse().getStatus());
        assertEquals(404, m.post("/api/positions/snapshots/bulk", """
                {"date":"2026-02-28","items":[{"positionId":%d,"quantity":0,"unitPrice":1}]}
                """.formatted(positionId)).getResponse().getStatus());
        assertEquals(404, m.delete("/api/fx-rates/" + rateId).getResponse().getStatus());

        List<?> mallorysPositions = json(m.get("/api/positions"), "$");
        assertEquals(0, mallorysPositions.size());
        assertEquals(Double.valueOf(0.0), ((Number) json(m.get("/api/dashboard/net-worth/detail"), "$.total"))
                .doubleValue());

        // Alice's data is untouched
        assertEquals(Integer.valueOf(1), json(a.get("/api/cash-entries"), "$.totalElements"));
        assertEquals(200, a.get("/api/positions/" + positionId).getResponse().getStatus());
    }

    @Test
    void entryCategoryMustMatchKind() throws Exception {
        AppUser bob = testUsers.create("bob", Role.USER);
        ApiClient b = new ApiClient(mvc);
        assertEquals(200, b.login(bob.getUsername(), TestUsers.PASSWORD));
        List<Integer> income = json(b.get("/api/categories"), "$[?(@.kind == 'INCOME')].id");
        MvcResult result = b.post("/api/cash-entries", """
                {"date":"2026-01-10","kind":"EXPENSE","categoryId":%d,"amount":10,"currency":"CHF"}
                """.formatted(income.getFirst()));
        assertEquals(400, result.getResponse().getStatus());
        assertEquals("category_kind_mismatch", json(result, "$.code"));

        MvcResult badCurrency = b.post("/api/cash-entries", """
                {"date":"2026-01-10","kind":"INCOME","categoryId":%d,"amount":10,"currency":"XXQ"}
                """.formatted(income.getFirst()));
        assertEquals(400, badCurrency.getResponse().getStatus());
        assertEquals("validation_failed", json(badCurrency, "$.code"));
    }
}
