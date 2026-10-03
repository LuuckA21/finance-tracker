package me.luucka.finance;

import static me.luucka.finance.support.ApiClient.body;
import static me.luucka.finance.support.ApiClient.json;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import com.jayway.jsonpath.JsonPath;
import me.luucka.finance.core.security.Totp;
import me.luucka.finance.support.ApiClient;
import me.luucka.finance.support.IntegrationTest;
import me.luucka.finance.support.TestUsers;
import me.luucka.finance.user.AppUser;
import me.luucka.finance.user.Role;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@IntegrationTest
class AccountDataIT {

    /** Every table holding a user's rows directly, with its user column. */
    private static final Map<String, String> USER_TABLES = Map.ofEntries(
            Map.entry("category", "user_id"), Map.entry("cash_entry", "user_id"), Map.entry("tag", "user_id"),
            Map.entry("category_rule", "user_id"), Map.entry("recurring_entry", "user_id"),
            Map.entry("asset_position", "user_id"), Map.entry("position_snapshot", "user_id"),
            Map.entry("exchange_rate", "user_id"), Map.entry("budget", "user_id"),
            Map.entry("savings_goal", "user_id"), Map.entry("forecast_scenario", "user_id"),
            Map.entry("notification_settings", "user_id"), Map.entry("notification_sent", "user_id"),
            Map.entry("recovery_code", "user_id"), Map.entry("login_event", "user_id"),
            Map.entry("passkey", "user_id"), Map.entry("app_user", "id"));

    @Autowired
    MockMvc mvc;

    @Autowired
    TestUsers testUsers;

    @Autowired
    JdbcTemplate jdbc;

    private ApiClient login(AppUser user) throws Exception {
        ApiClient client = new ApiClient(mvc);
        assertEquals(200, client.login(user.getUsername(), TestUsers.PASSWORD));
        return client;
    }

    private static long category(ApiClient client, String name) throws Exception {
        List<Integer> ids = json(client.get("/api/categories"), "$[?(@.name == '" + name + "')].id");
        return ids.getFirst();
    }

    private static long id(MvcResult result) throws Exception {
        assertTrue(result.getResponse().getStatus() < 300, body(result));
        return ((Number) json(result, "$.id")).longValue();
    }

    /** Some of everything: entries with tags, a transfer, positions and records, rules, plans. */
    private static void fill(ApiClient client, String description) throws Exception {
        long rent = category(client, "Affitto");
        id(client.post("/api/cash-entries", """
                {"date":"2025-08-01","kind":"EXPENSE","categoryId":%d,"amount":1800.5,"currency":"CHF",
                 "description":"%s","tags":["Vacanze"]}""".formatted(rent, description)));
        long bank = id(client.post("/api/positions", """
                {"name":"Conto","assetClass":"CASH","currency":"CHF"}"""));
        long pillar = id(client.post("/api/positions", """
                {"name":"Pilastro 3a","assetClass":"PENSION","currency":"CHF"}"""));
        assertEquals(200, client.post("/api/positions/%d/snapshots".formatted(bank), """
                {"date":"2025-08-31","quantity":1000,"unitPrice":1,"note":""}""").getResponse().getStatus());
        id(client.post("/api/cash-entries", """
                {"date":"2025-08-02","kind":"TRANSFER","amount":500,"currency":"CHF",
                 "fromPositionId":%d,"toPositionId":%d}""".formatted(bank, pillar)));
        id(client.post("/api/recurring-entries", """
                {"kind":"EXPENSE","categoryId":%d,"amount":100,"currency":"CHF","description":"Palestra",
                 "frequency":"MONTHLY","startDate":"2030-01-01"}""".formatted(rent)));
        id(client.post("/api/category-rules", """
                {"pattern":"migros","categoryId":%d}""".formatted(rent)));
        assertEquals(200, client.put("/api/budgets/" + rent, """
                {"amount":2000,"currency":"CHF","period":"QUARTERLY"}""").getResponse().getStatus());
        id(client.post("/api/goals", """
                {"name":"Pensione","kind":"YEARLY","targetAmount":7258,"currency":"CHF","targetDate":null,
                 "positionIds":[%d]}""".formatted(pillar)));
        id(client.post("/api/forecasts", """
                {"name":"Piano","year":2027,"incomeGrowth":2.5,"expenseGrowth":0,"excludedTagIds":[],
                 "excludedCategoryIds":[%d],"items":[{"description":"Auto nuova","kind":"EXPENSE",
                 "categoryId":null,"amount":30000,"schedule":"ONCE","startMonth":6,"endMonth":null}]}"""
                .formatted(rent)));
    }

    private static String enableMfa(ApiClient client) throws Exception {
        String secret = json(client.post("/api/account/mfa/setup", null), "$.secret");
        MvcResult enabled = client.post("/api/account/mfa/enable", "{\"password\":\"%s\",\"code\":\"%s\"}"
                .formatted(TestUsers.PASSWORD, Totp.codeAt(secret, Totp.stepAt(Instant.now()))));
        assertEquals(200, enabled.getResponse().getStatus(), body(enabled));
        return secret;
    }

    private static Map<String, byte[]> unzip(byte[] zip) throws Exception {
        Map<String, byte[]> files = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry entry; (entry = in.getNextEntry()) != null; ) {
                files.put(entry.getName(), in.readAllBytes());
            }
        }
        return files;
    }

    private int rows(String table, String column, long userId) {
        return jdbc.queryForObject("select count(*) from " + table + " where " + column + " = ?", Integer.class,
                userId);
    }

    @Test
    void theExportHoldsAllOfTheUsersDataAndNoSecrets() throws Exception {
        AppUser user = testUsers.create("export", Role.USER);
        ApiClient client = login(user);
        fill(client, "Affitto agosto");
        String secret = enableMfa(client);
        AppUser other = testUsers.create("exportother", Role.USER);
        fill(login(other), "Non mio");

        MvcResult result = client.get("/api/account/export");
        assertEquals(200, result.getResponse().getStatus());
        assertEquals("application/zip", result.getResponse().getContentType());
        assertTrue(result.getResponse().getHeader("Content-Disposition")
                .contains("finanze-" + user.getUsername() + "-" + LocalDate.now() + ".zip"));
        assertTrue(result.getResponse().getHeader("Cache-Control").contains("no-store"));

        Map<String, byte[]> files = unzip(result.getResponse().getContentAsByteArray());
        assertEquals(List.of("data.json", "movimenti-" + LocalDate.now() + ".csv"), List.copyOf(files.keySet()));
        String data = new String(files.get("data.json"), StandardCharsets.UTF_8);
        String csv = new String(files.get("movimenti-" + LocalDate.now() + ".csv"), StandardCharsets.UTF_8);

        assertEquals("finanze-export", JsonPath.read(data, "$.format"));
        assertEquals(1, (int) JsonPath.read(data, "$.formatVersion"));
        assertEquals(user.getUsername(), JsonPath.read(data, "$.account.username"));
        assertEquals(Boolean.TRUE, JsonPath.read(data, "$.account.twoFactorEnabled"));

        // Entries keep their amounts, dates, tags and positions, linked by id
        List<Map<String, Object>> entries = JsonPath.read(data, "$.entries");
        assertEquals(2, entries.size());
        assertEquals("2025-08-01", JsonPath.read(data, "$.entries[0].date"));
        assertEquals(1800.5, ((Number) JsonPath.read(data, "$.entries[0].amount")).doubleValue());
        assertEquals("Affitto agosto", JsonPath.read(data, "$.entries[0].description"));
        List<Integer> tagIds = JsonPath.read(data, "$.entries[0].tagIds");
        assertEquals(List.of("Vacanze"), JsonPath.read(data, "$.tags[?(@.id == " + tagIds.getFirst() + ")].name"));
        Integer rent = JsonPath.read(data, "$.entries[0].categoryId");
        assertEquals(List.of("Affitto"), JsonPath.read(data, "$.categories[?(@.id == " + rent + ")].name"));
        assertEquals("TRANSFER", JsonPath.read(data, "$.entries[1].kind"));
        Integer pillar = JsonPath.read(data, "$.entries[1].toPositionId");
        assertEquals(List.of("Pilastro 3a"), JsonPath.read(data, "$.positions[?(@.id == " + pillar + ")].name"));

        assertEquals(1000, ((Number) JsonPath.read(data, "$.positionRecords[0].quantity")).intValue());
        assertEquals("Palestra", JsonPath.read(data, "$.recurring[0].description"));
        assertEquals("migros", JsonPath.read(data, "$.categoryRules[0].pattern"));
        assertEquals("QUARTERLY", JsonPath.read(data, "$.budgets[0].period"));
        assertEquals(List.of(pillar), JsonPath.read(data, "$.goals[0].positionIds"));
        assertEquals(2.5, ((Number) JsonPath.read(data, "$.forecastScenarios[0].incomeGrowth")).doubleValue());
        assertEquals(List.of(rent), JsonPath.read(data, "$.forecastScenarios[0].excludedCategoryIds"));
        assertEquals("Auto nuova", JsonPath.read(data, "$.forecastScenarios[0].items[0].description"));
        assertFalse(((List<?>) JsonPath.read(data, "$.logins")).isEmpty());
        assertEquals(List.of(), JsonPath.read(data, "$.passkeys"));

        // The CSV is the one the import reads
        assertTrue(csv.contains("Affitto agosto"), csv);

        // Nothing of the other user, no secrets
        assertFalse(data.contains("Non mio") || csv.contains("Non mio"));
        String hash = jdbc.queryForObject("select password_hash from app_user where id = ?", String.class,
                user.getId());
        assertFalse(data.contains(hash));
        assertFalse(data.contains(secret));
        assertFalse(data.contains("totp_secret") || data.contains("passwordHash") || data.contains("codeHash"));
    }

    @Test
    void theExportNeedsASession() throws Exception {
        assertEquals(401, new ApiClient(mvc).get("/api/account/export").getResponse().getStatus());
    }

    @Test
    void deletingTheAccountRemovesAllOfItsDataAndEndsEverySession() throws Exception {
        AppUser user = testUsers.create("leaving", Role.USER);
        ApiClient client = login(user);
        fill(client, "Da cancellare");
        ApiClient phone = login(user);
        AppUser other = testUsers.create("staying", Role.USER);
        ApiClient otherClient = login(other);
        fill(otherClient, "Resta");

        MvcResult wrong = client.post("/api/account/delete", "{\"password\":\"Wrong-Password-123\"}");
        assertEquals(400, wrong.getResponse().getStatus());
        assertEquals("invalid_current_password", json(wrong, "$.code"));
        assertEquals(1, rows("app_user", "id", user.getId()));
        assertEquals(400, client.post("/api/account/delete", "{}").getResponse().getStatus());

        MvcResult deleted = client.post("/api/account/delete",
                "{\"password\":\"%s\"}".formatted(TestUsers.PASSWORD));
        assertEquals(204, deleted.getResponse().getStatus(), body(deleted));

        USER_TABLES.forEach((table, column) -> assertEquals(0, rows(table, column, user.getId()), table));
        assertEquals(401, client.get("/api/auth/me").getResponse().getStatus());
        assertEquals(401, phone.get("/api/auth/me").getResponse().getStatus());
        assertEquals(401, new ApiClient(mvc).login(user.getUsername(), TestUsers.PASSWORD));

        // The other user keeps everything
        assertEquals(200, otherClient.get("/api/auth/me").getResponse().getStatus());
        assertEquals(2, rows("cash_entry", "user_id", other.getId()));
    }

    @Test
    void withTwoFactorDeletingNeedsTheCode() throws Exception {
        AppUser user = testUsers.create("leavingmfa", Role.USER);
        ApiClient client = login(user);
        String secret = enableMfa(client);
        String password = TestUsers.PASSWORD;

        MvcResult noCode = client.post("/api/account/delete", "{\"password\":\"%s\"}".formatted(password));
        assertEquals("invalid_mfa_code", json(noCode, "$.code"));
        MvcResult wrongCode = client.post("/api/account/delete",
                "{\"password\":\"%s\",\"code\":\"000000\"}".formatted(password));
        assertEquals("invalid_mfa_code", json(wrongCode, "$.code"));
        assertEquals(1, rows("app_user", "id", user.getId()));

        String code = Totp.codeAt(secret, Totp.stepAt(Instant.now()) + 1);
        assertEquals(204, client.post("/api/account/delete",
                "{\"password\":\"%s\",\"code\":\"%s\"}".formatted(password, code)).getResponse().getStatus());
        assertEquals(0, rows("app_user", "id", user.getId()));
    }

    @Test
    void theLastAdministratorCannotLeave() throws Exception {
        AppUser admin = testUsers.create("lastadmin", Role.ADMIN);
        ApiClient client = login(admin);
        List<Long> others = jdbc.queryForList(
                "select id from app_user where role = 'ADMIN' and enabled and id <> ?", Long.class, admin.getId());
        jdbc.update("update app_user set enabled = false where id = any (?)", (Object) others.toArray(Long[]::new));
        try {
            MvcResult refused = client.post("/api/account/delete",
                    "{\"password\":\"%s\"}".formatted(TestUsers.PASSWORD));
            assertEquals(400, refused.getResponse().getStatus());
            assertEquals("last_admin", json(refused, "$.code"));
            assertEquals(1, rows("app_user", "id", admin.getId()));
        } finally {
            jdbc.update("update app_user set enabled = true where id = any (?)", (Object) others.toArray(Long[]::new));
        }
        // With another administrator around it can
        assertEquals(204, client.post("/api/account/delete",
                "{\"password\":\"%s\"}".formatted(TestUsers.PASSWORD)).getResponse().getStatus());
    }
}
