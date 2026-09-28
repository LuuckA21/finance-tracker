package me.luucka.finance;

import static me.luucka.finance.support.ApiClient.json;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
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

/** Transfers between own accounts: validation, ownership, dashboards, recurring rules and CSV. */
@IntegrationTest
class TransfersIT {

    @Autowired
    MockMvc mvc;

    @Autowired
    TestUsers testUsers;

    private ApiClient login(AppUser user) throws Exception {
        ApiClient client = new ApiClient(mvc);
        assertEquals(200, client.login(user.getUsername(), TestUsers.PASSWORD));
        return client;
    }

    private static int position(ApiClient client, String name, String assetClass) throws Exception {
        return json(client.post("/api/positions", """
                {"name":"%s","assetClass":"%s","currency":"CHF"}""".formatted(name, assetClass)), "$.id");
    }

    private static long category(ApiClient client, String name) throws Exception {
        List<Integer> ids = json(client.get("/api/categories"), "$[?(@.name == '" + name + "')].id");
        return ids.getFirst();
    }

    private static MvcResult transfer(ApiClient client, String date, int amount, Object from, Object to)
            throws Exception {
        return client.post("/api/cash-entries", """
                {"date":"%s","kind":"TRANSFER","amount":%d,"currency":"CHF","description":"Risparmio",
                 "fromPositionId":%s,"toPositionId":%s}""".formatted(date, amount, from, to));
    }

    @Test
    void transfersAreKeptOutOfIncomeAndExpenses() throws Exception {
        ApiClient client = login(testUsers.create("transfer", Role.USER));
        int bank = position(client, "Conto UBS", "CASH");
        int pillar = position(client, "Viac 3a", "PENSION");
        client.post("/api/cash-entries", """
                {"date":"2026-01-25","kind":"INCOME","categoryId":%d,"amount":6000,"currency":"CHF"}"""
                .formatted(category(client, "Stipendio")));
        client.post("/api/cash-entries", """
                {"date":"2026-01-05","kind":"EXPENSE","categoryId":%d,"amount":1800,"currency":"CHF"}"""
                .formatted(category(client, "Casa")));

        MvcResult created = transfer(client, "2026-01-28", 1000, bank, pillar);
        assertEquals(201, created.getResponse().getStatus());
        assertEquals("TRANSFER", json(created, "$.kind"));
        assertNull(json(created, "$.categoryId"));
        assertEquals(Integer.valueOf(bank), json(created, "$.fromPositionId"));
        assertEquals(Integer.valueOf(pillar), json(created, "$.toPositionId"));
        // Both positions are optional; a category sent with a transfer is ignored
        assertEquals(201, transfer(client, "2026-01-29", 500, "null", "null").getResponse().getStatus());
        MvcResult withCategory = client.post("/api/cash-entries", """
                {"date":"2026-02-02","kind":"TRANSFER","categoryId":%d,"amount":200,"currency":"CHF"}"""
                .formatted(category(client, "Casa")));
        assertNull(json(withCategory, "$.categoryId"));

        MvcResult year = client.get("/api/dashboard/cashflow?year=2026");
        assertEquals(6000.0, ((Number) json(year, "$.totals.income")).doubleValue());
        assertEquals(1800.0, ((Number) json(year, "$.totals.expense")).doubleValue());
        assertEquals(70.0, ((Number) json(year, "$.months[0].totals.savingsRate")).doubleValue());
        assertEquals(1500.0, ((Number) json(year, "$.months[0].totals.transferred")).doubleValue());
        assertEquals(1700.0, ((Number) json(year, "$.totals.transferred")).doubleValue());
        List<Map<String, Object>> transfers = json(year, "$.transfers");
        assertEquals("PENSION", transfers.getFirst().get("destination"));
        assertEquals(1000.0, ((Number) transfers.getFirst().get("amount")).doubleValue());
        assertNull(transfers.get(1).get("destination"));
        List<String> categoryKinds = json(year, "$.categories[*].kind");
        assertTrue(categoryKinds.stream().noneMatch("TRANSFER"::equals));
        assertEquals(1700.0, ((Number) json(client.get("/api/dashboard/cashflow/years"),
                "$.years[0].totals.transferred")).doubleValue());

        // Filter by kind
        assertEquals(Integer.valueOf(3), json(client.get("/api/cash-entries?kind=TRANSFER"), "$.totalElements"));

        // Deleting a position keeps the transfer and clears the link
        assertEquals(204, client.delete("/api/positions/" + pillar).getResponse().getStatus());
        MvcResult list = client.get("/api/cash-entries?kind=TRANSFER");
        List<Object> targets = json(list, "$.content[?(@.fromPositionId == " + bank + ")].toPositionId");
        assertEquals(1, targets.size());
        assertNull(targets.getFirst());
    }

    @Test
    void transfersAreValidatedAndLimitedToOwnPositions() throws Exception {
        ApiClient alice = login(testUsers.create("transfer-alice", Role.USER));
        ApiClient bob = login(testUsers.create("transfer-bob", Role.USER));
        int own = position(alice, "Conto", "CASH");
        int foreign = position(bob, "Conto di Bob", "CASH");

        assertEquals("transfer_same_position", json(transfer(alice, "2026-03-01", 10, own, own), "$.code"));
        MvcResult stolen = transfer(alice, "2026-03-01", 10, own, foreign);
        assertEquals(404, stolen.getResponse().getStatus());
        assertEquals(404, transfer(alice, "2026-03-01", 10, foreign, "null").getResponse().getStatus());

        // Income and expenses still need a category and never carry positions
        MvcResult noCategory = alice.post("/api/cash-entries", """
                {"date":"2026-03-01","kind":"EXPENSE","amount":10,"currency":"CHF"}""");
        assertEquals("category_required", json(noCategory, "$.code"));
        MvcResult expense = alice.post("/api/cash-entries", """
                {"date":"2026-03-01","kind":"EXPENSE","categoryId":%d,"amount":10,"currency":"CHF",
                 "fromPositionId":%d}""".formatted(category(alice, "Casa"), own));
        assertNull(json(expense, "$.fromPositionId"));

        // No categories of kind "transfer"
        MvcResult category = alice.post("/api/categories", """
                {"name":"Giroconti","kind":"TRANSFER","color":"#123456"}""");
        assertEquals("transfer_category", json(category, "$.code"));

        // Updating a transfer to point at someone else's position fails the same way
        int id = json(transfer(alice, "2026-03-02", 10, own, "null"), "$.id");
        assertEquals(404, alice.put("/api/cash-entries/" + id, """
                {"date":"2026-03-02","kind":"TRANSFER","amount":10,"currency":"CHF","toPositionId":%d}"""
                .formatted(foreign)).getResponse().getStatus());
        assertEquals(Integer.valueOf(0), json(bob.get("/api/cash-entries"), "$.totalElements"));
    }

    @Test
    void recurringRulesCanCreateTransfers() throws Exception {
        ApiClient client = login(testUsers.create("transfer-rule", Role.USER));
        int bank = position(client, "Conto", "CASH");
        int pillar = position(client, "3a", "PENSION");
        LocalDate start = LocalDate.now().minusMonths(2).withDayOfMonth(1);
        MvcResult rule = client.post("/api/recurring-entries", """
                {"kind":"TRANSFER","amount":500,"currency":"CHF","frequency":"MONTHLY","startDate":"%s",
                 "fromPositionId":%d,"toPositionId":%d}""".formatted(start, bank, pillar));
        assertEquals(201, rule.getResponse().getStatus());
        assertNull(json(rule, "$.categoryId"));
        assertEquals(Integer.valueOf(pillar), json(rule, "$.toPositionId"));
        MvcResult entries = client.get("/api/cash-entries?kind=TRANSFER");
        assertEquals(Integer.valueOf(3), json(entries, "$.totalElements"));
        List<Integer> destinations = json(entries, "$.content[*].toPositionId");
        assertTrue(destinations.stream().allMatch(d -> d == pillar));

        assertEquals("transfer_same_position", json(client.post("/api/recurring-entries", """
                {"kind":"TRANSFER","amount":5,"currency":"CHF","frequency":"MONTHLY","startDate":"%s",
                 "fromPositionId":%d,"toPositionId":%d}""".formatted(start, bank, bank)), "$.code"));
    }

    @Test
    void csvCarriesTransfersAndPositionNames() throws Exception {
        ApiClient alice = login(testUsers.create("transfer-csv", Role.USER));
        int bank = position(alice, "Conto UBS", "CASH");
        int pillar = position(alice, "Viac 3a", "PENSION");
        transfer(alice, "2026-04-28", 700, bank, pillar);

        String csv = alice.get("/api/cash-entries/export").getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(csv.contains("2026-04-28;Trasferimento;;700;CHF;Risparmio;Conto UBS;Viac 3a\r\n"), csv);

        // Another user with a position of the same name gets it matched; an unknown name is reported
        ApiClient bob = login(testUsers.create("transfer-csv-bob", Role.USER));
        int bobPillar = position(bob, "viac 3A", "PENSION");
        String file = csv + "2026-04-29;Transfer;;50;CHF;;Conto UBS;Sconosciuto\r\n";
        MvcResult preview = bob.upload("/api/cash-entries/import/preview", "t.csv", file.getBytes(StandardCharsets.UTF_8));
        // Row 1: "Conto UBS" is not Bob's, "Viac 3a" matches "viac 3A" ignoring case
        assertEquals("TRANSFER", json(preview, "$.rows[0].kind"));
        assertNull(json(preview, "$.rows[0].categoryId"));
        assertNull(json(preview, "$.rows[0].fromPositionId"));
        assertEquals(Integer.valueOf(bobPillar), json(preview, "$.rows[0].toPositionId"));
        assertEquals(List.of("unknown_position"), json(preview, "$.rows[0].errors"));
        assertEquals("TRANSFER", json(preview, "$.rows[1].kind"));
        assertNull(json(preview, "$.rows[1].toPositionId"));
        assertEquals(List.of("unknown_position"), json(preview, "$.rows[1].errors"));

        // Confirmed rows are validated like single transfers: only own positions
        assertEquals("import_unknown_position", json(bob.post("/api/cash-entries/import", """
                {"entries":[{"date":"2026-04-28","kind":"TRANSFER","amount":700,"currency":"CHF",
                 "fromPositionId":%d,"toPositionId":%d}]}""".formatted(bank, bobPillar)), "$.code"));
        assertEquals(201, bob.post("/api/cash-entries/import", """
                {"entries":[{"date":"2026-04-28","kind":"TRANSFER","amount":700,"currency":"CHF",
                 "toPositionId":%d}]}""".formatted(bobPillar)).getResponse().getStatus());
    }
}
