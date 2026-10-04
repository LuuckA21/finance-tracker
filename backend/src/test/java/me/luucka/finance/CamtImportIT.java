package me.luucka.finance;

import static me.luucka.finance.support.ApiClient.body;
import static me.luucka.finance.support.ApiClient.json;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;

import me.luucka.finance.core.CamtParserTest;
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
class CamtImportIT {

    private static final String IBAN = "CH9300762011623852957";

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

    private static MvcResult position(ApiClient client, String name, String iban) throws Exception {
        return client.post("/api/positions", """
                {"name":"%s","assetClass":"CASH","currency":"CHF","iban":%s}"""
                .formatted(name, iban == null ? "null" : "\"" + iban + "\""));
    }

    private static MvcResult preview(ApiClient client, String xml) throws Exception {
        return client.upload("/api/cash-entries/import/preview", "estratto.xml", xml.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void positionsKeepAValidIbanOnce() throws Exception {
        ApiClient client = login(testUsers.create("ibanpos", Role.USER));
        MvcResult created = position(client, "Conto UBS", "ch93 0076 2011 6238 5295 7");
        assertEquals(201, created.getResponse().getStatus(), body(created));
        assertEquals(IBAN, json(created, "$.iban"));

        assertEquals("invalid_iban", json(position(client, "Sbagliato", "CH93 0076 2011 6238 5295 8"), "$.code"));
        MvcResult twice = position(client, "Doppione", IBAN);
        assertEquals(409, twice.getResponse().getStatus());
        assertEquals("iban_taken", json(twice, "$.code"));

        // Saving the same position again keeps it; clearing it frees the IBAN
        long id = ((Number) json(created, "$.id")).longValue();
        assertEquals(200, client.put("/api/positions/" + id, """
                {"name":"Conto UBS","assetClass":"CASH","currency":"CHF","iban":"%s"}""".formatted(IBAN))
                .getResponse().getStatus());
        assertEquals(200, client.put("/api/positions/" + id, """
                {"name":"Conto UBS","assetClass":"CASH","currency":"CHF","iban":""}""").getResponse().getStatus());
        assertEquals(201, position(client, "Ora libero", IBAN).getResponse().getStatus());
    }

    @Test
    void aStatementBecomesRowsToReviewWithRulesDuplicatesAndTheMatchingPosition() throws Exception {
        AppUser user = testUsers.create("camt", Role.USER);
        ApiClient client = login(user);
        long account = ((Number) json(position(client, "Conto UBS", IBAN), "$.id")).longValue();
        long health = category(client, "Cassa malati");
        assertEquals(201, client.post("/api/category-rules", """
                {"pattern":"helsana","categoryId":%d}""".formatted(health)).getResponse().getStatus());
        // Already entered by hand: the same card payment
        assertEquals(201, client.post("/api/cash-entries", """
                {"date":"2026-09-03","kind":"EXPENSE","categoryId":%d,"amount":45.20,"currency":"CHF",
                 "description":"Zahlung Debitkarte 02.09.2026 MIGROS ZUERICH"}""".formatted(category(client, "Supermercato")))
                .getResponse().getStatus());

        MvcResult result = preview(client, CamtParserTest.STATEMENT);
        assertEquals(200, result.getResponse().getStatus(), body(result));
        assertEquals("CAMT", json(result, "$.format"));
        assertEquals(5, (int) json(result, "$.total"));
        assertEquals(1, (int) json(result, "$.duplicates"));

        // Salary: income, no rule, waits for a category
        assertEquals("INCOME", json(result, "$.rows[0].kind"));
        assertEquals(6000.0, ((Number) json(result, "$.rows[0].amount")).doubleValue());
        assertEquals("ACME AG · Lohn September", json(result, "$.rows[0].description"));
        assertEquals(List.of("missing_category"), json(result, "$.rows[0].errors"));
        // The card payment is the one already there
        assertEquals(Boolean.TRUE, json(result, "$.rows[1].duplicate"));
        assertEquals("-45.20", json(result, "$.rows[1].raw.amount"));
        // Split from the collective booking, categorized by the rule
        assertEquals((int) health, (int) json(result, "$.rows[2].categoryId"));
        assertEquals("RULE", json(result, "$.rows[2].categorySource"));
        assertEquals(List.of(), json(result, "$.rows[2].errors"));
        // Not booked yet
        assertTrue(((List<?>) json(result, "$.rows[4].errors")).contains("camt_pending"));

        // The account is the position with its IBAN, with the closing balance to update it
        assertEquals(IBAN, json(result, "$.statements[0].iban"));
        assertEquals((int) account, (int) json(result, "$.statements[0].positionId"));
        assertEquals("Conto UBS", json(result, "$.statements[0].positionName"));
        assertEquals("2026-09-30", json(result, "$.statements[0].closingDate"));
        assertEquals(6120.35, ((Number) json(result, "$.statements[0].closingBalance")).doubleValue());

        // The rows go through the usual import
        long salary = category(client, "Stipendio");
        MvcResult imported = client.post("/api/cash-entries/import", """
                {"entries":[{"date":"2026-09-25","kind":"INCOME","categoryId":%d,"amount":6000,"currency":"CHF",
                  "description":"ACME AG · Lohn September","tags":[]}]}""".formatted(salary));
        assertEquals(201, imported.getResponse().getStatus(), body(imported));
        assertEquals(2, (int) json(preview(client, CamtParserTest.STATEMENT), "$.duplicates"));
    }

    @Test
    void anotherUsersPositionWithTheSameIbanIsNotMatched() throws Exception {
        ApiClient other = login(testUsers.create("camtother", Role.USER));
        assertEquals(201, position(other, "Suo conto", IBAN).getResponse().getStatus());
        ApiClient client = login(testUsers.create("camtmine", Role.USER));
        MvcResult result = preview(client, CamtParserTest.STATEMENT);
        assertEquals(200, result.getResponse().getStatus(), body(result));
        assertNull(json(result, "$.statements[0].positionId"));
    }

    @Test
    void filesThatAreNotStatementsAreRefused() throws Exception {
        ApiClient client = login(testUsers.create("camtbad", Role.USER));
        MvcResult xxe = preview(client, """
                <?xml version="1.0"?>
                <!DOCTYPE Document [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                <Document><BkToCstmrStmt><Stmt><Ntry><AddtlNtryInf>&xxe;</AddtlNtryInf></Ntry></Stmt></BkToCstmrStmt></Document>""");
        assertEquals(400, xxe.getResponse().getStatus());
        assertEquals("camt_invalid", json(xxe, "$.code"));
        assertEquals("camt_unsupported", json(preview(client, "<html><body>no</body></html>"), "$.code"));
        assertEquals("camt_empty", json(preview(client,
                "<Document><BkToCstmrStmt><Stmt/></BkToCstmrStmt></Document>"), "$.code"));
    }
}
