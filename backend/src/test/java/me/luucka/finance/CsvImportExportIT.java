package me.luucka.finance;

import static me.luucka.finance.support.ApiClient.json;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
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

/** CSV export and the two-step import, including hostile files and requests. */
@IntegrationTest
class CsvImportExportIT {

    private static final String PREVIEW = "/api/cash-entries/import/preview";
    private static final String IMPORT = "/api/cash-entries/import";

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

    private static int entryCount(ApiClient client) throws Exception {
        return json(client.get("/api/cash-entries?size=200"), "$.totalElements");
    }

    private static MvcResult upload(ApiClient client, String csv) throws Exception {
        return client.upload(PREVIEW, "movimenti.csv", csv.getBytes(StandardCharsets.UTF_8));
    }

    /** Sends every valid, non-duplicate row of a preview back, as the "import all" mode does. */
    private static MvcResult importValid(ApiClient client, MvcResult preview) throws Exception {
        List<Map<String, Object>> rows = json(preview, "$.rows[?(@.errors.length() == 0 && @.duplicate == false)]");
        StringBuilder body = new StringBuilder("{\"entries\":[");
        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> row = rows.get(i);
            body.append(i == 0 ? "" : ",").append("""
                    {"date":"%s","kind":"%s","categoryId":%s,"amount":%s,"currency":"%s","description":%s}"""
                    .formatted(row.get("date"), row.get("kind"), row.get("categoryId"), row.get("amount"),
                            row.get("currency"), row.get("description") == null ? "null"
                                    : "\"" + row.get("description").toString().replace("\"", "\\\"") + "\""));
        }
        return client.post(IMPORT, body.append("]}").toString());
    }

    @Test
    void exportNeutralizesFormulasAndImportsBackIntoAnotherAccount() throws Exception {
        ApiClient alice = login(testUsers.create("csv-alice", Role.USER));
        long groceries = category(alice, "Spesa alimentare");
        long salary = category(alice, "Stipendio");
        alice.post("/api/cash-entries", """
                {"date":"2026-08-01","kind":"EXPENSE","categoryId":%d,"amount":12.5,"currency":"CHF",
                 "description":"=HYPERLINK(\\"http://evil\\",\\"clic\\")"}""".formatted(groceries));
        alice.post("/api/cash-entries", """
                {"date":"2026-08-02","kind":"EXPENSE","categoryId":%d,"amount":3.2,"currency":"EUR",
                 "description":"Caffè; bar \\"centrale\\""}""".formatted(groceries));
        alice.post("/api/cash-entries", """
                {"date":"2026-08-25","kind":"INCOME","categoryId":%d,"amount":6000,"currency":"CHF"}"""
                .formatted(salary));

        MvcResult export = alice.get("/api/cash-entries/export");
        assertEquals(200, export.getResponse().getStatus());
        assertEquals("text/csv;charset=UTF-8", export.getResponse().getContentType());
        assertTrue(export.getResponse().getHeader("Content-Disposition").matches(
                "attachment; filename=\"movimenti-\\d{4}-\\d{2}-\\d{2}\\.csv\""));
        assertEquals("no-store", export.getResponse().getHeader("Cache-Control"));
        byte[] bytes = export.getResponse().getContentAsByteArray();
        String csv = new String(bytes, StandardCharsets.UTF_8);
        assertTrue(csv.startsWith("\uFEFFdata;tipo;categoria;importo;valuta;descrizione;da;verso;etichette\r\n"), csv);
        assertTrue(csv.contains("2026-08-01;Uscita;Spesa alimentare;12.5;CHF;\"'=HYPERLINK(\"\"http://evil\"\",\"\"clic\"\")\""), csv);
        assertTrue(csv.contains("2026-08-02;Uscita;Spesa alimentare;3.2;EUR;\"Caffè; bar \"\"centrale\"\"\""), csv);
        assertTrue(csv.contains("2026-08-25;Entrata;Stipendio;6000;CHF;;;;\r\n"), csv);
        // Filters apply to the export too
        String income = alice.get("/api/cash-entries/export?kind=INCOME").getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertEquals(2, income.lines().count());

        // Another user imports the file: categories matched by name among their own
        ApiClient bob = login(testUsers.create("csv-bob", Role.USER));
        MvcResult preview = bob.upload(PREVIEW, "movimenti.csv", bytes);
        assertEquals(200, preview.getResponse().getStatus());
        assertEquals(";", json(preview, "$.delimiter"));
        assertEquals(Integer.valueOf(3), json(preview, "$.valid"));
        assertEquals(Integer.valueOf(0), json(preview, "$.duplicates"));
        List<Number> categoryIds = json(preview, "$.rows[*].categoryId");
        assertTrue(categoryIds.stream().allMatch(id -> id.longValue() != groceries && id.longValue() != salary));
        // The preview writes nothing
        assertEquals(0, entryCount(bob));

        MvcResult imported = importValid(bob, preview);
        assertEquals(201, imported.getResponse().getStatus());
        assertEquals(Integer.valueOf(3), json(imported, "$.imported"));
        List<String> descriptions = json(bob.get("/api/cash-entries"), "$.content[*].description");
        assertTrue(descriptions.contains("=HYPERLINK(\"http://evil\",\"clic\")"), descriptions.toString());

        // Importing the same file again: every row is a duplicate
        MvcResult again = bob.upload(PREVIEW, "movimenti.csv", bytes);
        assertEquals(Integer.valueOf(3), json(again, "$.duplicates"));

        // Alice's export does not contain Bob's rows and vice versa
        assertEquals(4, bob.get("/api/cash-entries/export").getResponse()
                .getContentAsString(StandardCharsets.UTF_8).lines().count());
    }

    @Test
    void exportFollowsTheInterfaceLanguageAndFrenchFilesImport() throws Exception {
        ApiClient client = login(testUsers.create("csv-fr", Role.USER));
        long groceries = category(client, "Spesa alimentare");
        client.post("/api/cash-entries", """
                {"date":"2026-08-01","kind":"EXPENSE","categoryId":%d,"amount":12.5,"currency":"CHF",
                 "description":"Migros"}""".formatted(groceries));
        assertEquals(200, client.put("/api/account/settings", "{\"language\":\"FR\"}").getResponse().getStatus());

        MvcResult export = client.get("/api/cash-entries/export");
        assertTrue(export.getResponse().getHeader("Content-Disposition").matches(
                "attachment; filename=\"operations-\\d{4}-\\d{2}-\\d{2}\\.csv\""));
        String csv = export.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(csv.startsWith("\uFEFFdate;type;catégorie;montant;monnaie;description;de;vers;étiquettes\r\n"), csv);
        assertTrue(csv.contains("2026-08-01;Dépense;Spesa alimentare;12.5;CHF;Migros;;;\r\n"), csv);
        assertEquals(Integer.valueOf(1), json(upload(client, csv), "$.duplicates"));

        MvcResult preview = upload(client, """
                Date;Type;Catégorie;Montant;Monnaie;Libellé
                02.08.2026;Dépense;Spesa alimentare;7,30;CHF;Coop
                03.08.2026;Virement;;500;CHF;Épargne
                """);
        assertEquals(200, preview.getResponse().getStatus());
        assertEquals(Integer.valueOf(2), json(preview, "$.valid"));
        assertEquals(List.of("EXPENSE", "TRANSFER"), json(preview, "$.rows[*].kind"));
    }

    @Test
    void previewReportsEveryRowProblem() throws Exception {
        ApiClient client = login(testUsers.create("csv-rows", Role.USER));
        String csv = """
                Date,Amount,Category,Currency,Description,Balance
                01.08.2026,"-1'234,50",Casa,chf,Affitto,999
                2026-08-02,2500,Stipendio,,,
                31.02.2026,10,Casa,CHF,,
                2026-08-03,0,Casa,CHF,,
                2026-08-04,12,Sconosciuta,CHF,,
                2026-08-05,12,Casa,XYZ,,
                2026-08-06,12,,CHF,,
                2026-08-07,twelve,Casa,CHF,,
                ,,,,,
                """;
        MvcResult preview = upload(client, csv);
        assertEquals(200, preview.getResponse().getStatus());
        assertEquals(",", json(preview, "$.delimiter"));
        assertEquals(List.of("Balance"), json(preview, "$.ignoredColumns"));
        assertEquals(Integer.valueOf(8), json(preview, "$.total"));
        assertEquals(Integer.valueOf(2), json(preview, "$.valid"));

        // No type column: negative amount = expense, category decides otherwise
        assertEquals("EXPENSE", json(preview, "$.rows[0].kind"));
        assertEquals(1234.5, ((Number) json(preview, "$.rows[0].amount")).doubleValue());
        assertEquals("CHF", json(preview, "$.rows[0].currency"));
        assertEquals("INCOME", json(preview, "$.rows[1].kind"));
        assertEquals("CHF", json(preview, "$.rows[1].currency"));
        assertEquals(List.of("invalid_date"), json(preview, "$.rows[2].errors"));
        assertEquals(List.of("zero_amount"), json(preview, "$.rows[3].errors"));
        assertEquals(List.of("unknown_category"), json(preview, "$.rows[4].errors"));
        assertEquals(List.of("invalid_currency"), json(preview, "$.rows[5].errors"));
        assertEquals(List.of("missing_category"), json(preview, "$.rows[6].errors"));
        assertEquals(List.of("invalid_amount"), json(preview, "$.rows[7].errors"));
        assertEquals(Integer.valueOf(9), json(preview, "$.rows[7].line"));
        assertEquals("twelve", json(preview, "$.rows[7].raw.amount"));
    }

    @Test
    void unusableFilesAreRejected() throws Exception {
        ApiClient client = login(testUsers.create("csv-files", Role.USER));
        assertEquals("csv_empty", json(upload(client, ""), "$.code"));
        assertEquals("csv_empty", json(upload(client, "data;importo\n"), "$.code"));
        MvcResult missing = upload(client, "data;categoria\n2026-08-01;Casa\n");
        assertEquals("csv_missing_columns", json(missing, "$.code"));
        assertEquals(List.of("amount"), json(missing, "$.columns"));
        assertEquals("csv_duplicate_column", json(upload(client, "data;importo;amount\n"), "$.code"));
        MvcResult malformed = upload(client, "data;importo\n2026-08-01;\"12\n");
        assertEquals("csv_malformed", json(malformed, "$.code"));
        assertEquals(Integer.valueOf(2), json(malformed, "$.line"));
        assertEquals("csv_binary", json(client.upload(PREVIEW, "x.xlsx",
                new byte[] {'P', 'K', 3, 4, 0, 0, 1, 2}), "$.code"));
        assertEquals("csv_too_many_rows", json(upload(client,
                "data;importo\n" + "2026-08-01;1\n".repeat(5001)), "$.code"));
        assertEquals("csv_too_many_columns", json(upload(client, "a;".repeat(40) + "\n"), "$.code"));
        MvcResult huge = client.upload(PREVIEW, "big.csv", new byte[2 * 1024 * 1024 + 1]);
        assertEquals(413, huge.getResponse().getStatus());
        assertEquals("csv_too_large", json(huge, "$.code"));
        assertEquals(0, entryCount(client));
    }

    @Test
    void importIsAtomicAndLimitedToOwnCategories() throws Exception {
        ApiClient mallory = login(testUsers.create("csv-mallory", Role.USER));
        ApiClient victim = login(testUsers.create("csv-victim", Role.USER));
        long own = category(mallory, "Casa");
        long ownIncome = category(mallory, "Stipendio");
        long foreign = category(victim, "Casa");
        String row = """
                {"date":"2026-08-01","kind":"EXPENSE","categoryId":%d,"amount":10,"currency":"CHF"}""";

        // A category of another user in the second row: nothing is imported, the row is named
        MvcResult stolen = mallory.post(IMPORT, "{\"entries\":[" + row.formatted(own) + "," + row.formatted(foreign) + "]}");
        assertEquals(400, stolen.getResponse().getStatus());
        assertEquals("import_unknown_category", json(stolen, "$.code"));
        assertEquals(Integer.valueOf(1), json(stolen, "$.row"));
        assertEquals(0, entryCount(mallory));
        assertEquals(0, entryCount(victim));

        MvcResult mismatch = mallory.post(IMPORT, "{\"entries\":[" + row.formatted(ownIncome) + "]}");
        assertEquals("category_kind_mismatch", json(mismatch, "$.code"));

        // Same validation as single entries, reported per row
        MvcResult invalid = mallory.post(IMPORT, """
                {"entries":[{"date":"+999999-01-01","kind":"EXPENSE","categoryId":%d,"amount":-5,"currency":"QQQ",
                 "description":"%s"}]}""".formatted(own, "x".repeat(501)));
        assertEquals("validation_failed", json(invalid, "$.code"));
        Map<String, String> errors = json(invalid, "$.errors");
        assertTrue(errors.keySet().containsAll(List.of("entries[0].amount", "entries[0].currency",
                "entries[0].description")), errors.toString());
        assertEquals(400, mallory.post(IMPORT, "{\"entries\":[]}").getResponse().getStatus());
        assertEquals(400, mallory.post(IMPORT, "{\"entries\":[" + (row.formatted(own) + ",").repeat(5000)
                + row.formatted(own) + "]}").getResponse().getStatus());
        assertEquals(0, entryCount(mallory));

        assertEquals(201, mallory.post(IMPORT, "{\"entries\":[" + row.formatted(own) + "]}").getResponse().getStatus());
        assertEquals(1, entryCount(mallory));
    }

    @Test
    void csvEndpointsNeedASessionAndTheCsrfToken() throws Exception {
        ApiClient anonymous = new ApiClient(mvc);
        anonymous.refreshCsrf();
        assertEquals(401, anonymous.get("/api/cash-entries/export").getResponse().getStatus());
        assertEquals(401, anonymous.upload(PREVIEW, "a.csv", "data;importo\n".getBytes()).getResponse().getStatus());
        assertEquals(401, anonymous.post(IMPORT, "{\"entries\":[]}").getResponse().getStatus());

        ApiClient client = login(testUsers.create("csv-csrf", Role.USER));
        assertEquals(403, client.uploadWithoutCsrf(PREVIEW, "a.csv",
                "data;importo\n2026-08-01;1\n".getBytes()).getResponse().getStatus());
        assertEquals(403, client.postWithoutCsrf(IMPORT, "{\"entries\":[]}").getResponse().getStatus());
        assertFalse(client.upload(PREVIEW, "a.csv", "data;importo;categoria\n2026-08-01;-1;Casa\n".getBytes())
                .getResponse().getContentAsString().isEmpty());
    }
}
