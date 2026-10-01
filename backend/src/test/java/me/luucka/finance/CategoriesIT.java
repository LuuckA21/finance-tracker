package me.luucka.finance;

import static me.luucka.finance.support.ApiClient.json;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import me.luucka.finance.support.ApiClient;
import me.luucka.finance.support.IntegrationTest;
import me.luucka.finance.support.TestUsers;
import me.luucka.finance.user.AppUser;
import me.luucka.finance.user.Role;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@IntegrationTest
class CategoriesIT {

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

    private static MvcResult create(ApiClient client, String name, String kind, Long parentId) throws Exception {
        return client.post("/api/categories", """
                {"name":"%s","kind":"%s","color":"#123456","parentId":%s}""".formatted(name, kind, parentId));
    }

    private static MvcResult update(ApiClient client, long id, String name, Long parentId) throws Exception {
        return client.put("/api/categories/" + id, """
                {"name":"%s","color":"#654321","parentId":%s}""".formatted(name, parentId));
    }

    private static String code(MvcResult result) throws Exception {
        return json(result, "$.code");
    }

    @Test
    void newUsersStartWithMacrosAndDetails() throws Exception {
        ApiClient client = login(testUsers.create("cattree", Role.USER));
        long housing = category(client, "Casa");
        MvcResult list = client.get("/api/categories");
        List<Integer> macros = json(list, "$[?(@.parentId == null)].id");
        assertEquals(15, macros.size());
        List<String> details = json(list, "$[?(@.parentId == " + housing + ")].name");
        assertEquals(List.of("Affitto", "Arredamento", "Energia", "Manutenzione"), details.stream().sorted().toList());
        List<String> colors = json(list, "$[?(@.parentId == " + housing + ")].color");
        assertEquals("#2563eb", colors.getFirst());
    }

    @Test
    void detailsGoUnderOwnMacrosOfTheSameKind() throws Exception {
        ApiClient client = login(testUsers.create("catparent", Role.USER));
        long housing = category(client, "Casa");
        long rent = category(client, "Affitto");
        long salary = category(client, "Stipendio");

        MvcResult garage = create(client, "Garage", "EXPENSE", housing);
        assertEquals(201, garage.getResponse().getStatus());
        assertEquals(housing, ((Number) json(garage, "$.parentId")).longValue());

        // Not under a detail, a category of the other kind, or someone else's
        assertEquals("category_parent_invalid", code(create(client, "Box", "EXPENSE", rent)));
        assertEquals("category_parent_invalid", code(create(client, "Box", "EXPENSE", salary)));
        ApiClient other = login(testUsers.create("catparent2", Role.USER));
        MvcResult foreign = create(other, "Box", "EXPENSE", housing);
        assertEquals(400, foreign.getResponse().getStatus());
        assertEquals("category_parent_invalid", code(foreign));
    }

    @Test
    void anAccountHasAtMostFiveHundredCategories() throws Exception {
        AppUser user = testUsers.create("catcap", Role.USER);
        ApiClient client = login(user);
        int existing = ((List<?>) json(client.get("/api/categories"), "$")).size();
        jdbc.update("""
                insert into category (user_id, name, kind, color)
                select ?, 'Limite ' || g, 'EXPENSE', '#123456' from generate_series(1, ?) g""",
                user.getId(), 500 - existing);
        MvcResult refused = create(client, "Una di troppo", "EXPENSE", null);
        assertEquals(400, refused.getResponse().getStatus());
        assertEquals("too_many_categories", code(refused));
    }

    @Test
    void namesAreUniqueAmongMacrosAndUnderEachMacro() throws Exception {
        ApiClient client = login(testUsers.create("catnames", Role.USER));
        long housing = category(client, "Casa");
        long transport = category(client, "Trasporti");

        assertEquals(409, create(client, "affitto", "EXPENSE", housing).getResponse().getStatus());
        assertEquals(409, create(client, "CASA", "EXPENSE", null).getResponse().getStatus());
        // "Auto" is a detail of Assicurazioni: fine under Trasporti and as a macro
        assertEquals(201, create(client, "Auto", "EXPENSE", transport).getResponse().getStatus());
        assertEquals(201, create(client, "Affitto", "EXPENSE", null).getResponse().getStatus());
        // The same name as a macro under another kind is fine too
        assertEquals(201, create(client, "Casa", "INCOME", null).getResponse().getStatus());
    }

    @Test
    void categoriesMoveBetweenLevels() throws Exception {
        ApiClient client = login(testUsers.create("catmove", Role.USER));
        long housing = category(client, "Casa");
        long leisure = category(client, "Svago");
        long sport = category(client, "Sport");

        // A detail becomes a macro, then goes under another macro
        MvcResult promoted = update(client, sport, "Sport", null);
        assertEquals(200, promoted.getResponse().getStatus());
        assertNull(json(promoted, "$.parentId"));
        assertEquals(200, update(client, sport, "Sport", housing).getResponse().getStatus());
        // A macro with details cannot become a detail, nor go under itself
        assertEquals("category_has_details", code(update(client, leisure, "Svago", housing)));
        assertEquals("category_parent_invalid", code(update(client, housing, "Casa", housing)));
        // Name clash at the destination
        long cinema = ((Number) json(create(client, "Energia", "EXPENSE", leisure), "$.id")).longValue();
        assertEquals("category_exists", code(update(client, cinema, "Energia", housing)));
        assertEquals(201, create(client, "Manutenzione", "EXPENSE", null).getResponse().getStatus());
        assertEquals("category_exists", code(update(client, category(client, "Arredamento"), "Manutenzione", null)));
        // Renaming only a detail's case is fine
        assertEquals(200, update(client, category(client, "Affitto"), "AFFITTO", housing).getResponse().getStatus());
    }

    @Test
    void aMacroWithDetailsCannotBeDeleted() throws Exception {
        ApiClient client = login(testUsers.create("catdelete", Role.USER));
        long taxes = category(client, "Tasse");
        MvcResult refused = client.delete("/api/categories/" + taxes);
        assertEquals(409, refused.getResponse().getStatus());
        assertEquals("category_has_details", code(refused));

        assertEquals(204, client.delete("/api/categories/" + category(client, "Imposte sul reddito")).getResponse()
                .getStatus());
        assertEquals(204, client.delete("/api/categories/" + category(client, "Tasse e canoni")).getResponse()
                .getStatus());
        assertEquals(204, client.delete("/api/categories/" + taxes).getResponse().getStatus());
    }

    @Test
    void aMacroAndItsDetailsNeverBothHaveABudget() throws Exception {
        ApiClient client = login(testUsers.create("catbudget", Role.USER));
        long housing = category(client, "Casa");
        long leisure = category(client, "Svago");
        long rent = category(client, "Affitto");
        long sport = category(client, "Sport");
        assertEquals(200, client.put("/api/budgets/" + housing, "{\"amount\":2000,\"currency\":\"CHF\"}")
                .getResponse().getStatus());

        MvcResult detail = client.put("/api/budgets/" + rent, "{\"amount\":1500,\"currency\":\"CHF\"}");
        assertEquals(409, detail.getResponse().getStatus());
        assertEquals("budget_conflict", code(detail));

        assertEquals(200, client.put("/api/budgets/" + sport, "{\"amount\":100,\"currency\":\"CHF\"}")
                .getResponse().getStatus());
        MvcResult macro = client.put("/api/budgets/" + leisure, "{\"amount\":300,\"currency\":\"CHF\"}");
        assertEquals("budget_conflict", code(macro));
        // Moving a detail with a budget under a macro with one
        assertEquals("budget_conflict", code(update(client, sport, "Sport", housing)));
    }

    private static void entry(ApiClient client, String date, long categoryId, int amount, String tags)
            throws Exception {
        assertEquals(201, client.post("/api/cash-entries", """
                {"date":"%s","kind":"EXPENSE","categoryId":%d,"amount":%d,"currency":"CHF","tags":%s}"""
                .formatted(date, categoryId, amount, tags)).getResponse().getStatus());
    }

    private static double number(MvcResult result, String path) throws Exception {
        return ((Number) json(result, path)).doubleValue();
    }

    @Test
    void analysesRollDetailsUpToTheirMacro() throws Exception {
        ApiClient client = login(testUsers.create("catrollup", Role.USER));
        long housing = category(client, "Casa");
        long rent = category(client, "Affitto");
        long energy = category(client, "Energia");
        long leisure = category(client, "Svago");
        long sport = category(client, "Sport");
        entry(client, "2025-08-01", rent, 1800, "[\"Casa\"]");
        entry(client, "2025-08-05", energy, 120, "[\"Casa\"]");
        entry(client, "2025-08-06", housing, 80, "[]");
        entry(client, "2025-08-10", leisure, 50, "[]");
        entry(client, "2025-08-11", sport, 70, "[]");

        // Cash flow: one row per macro, its details inside (the macro's own entries under its id)
        MvcResult year = client.get("/api/dashboard/cashflow?year=2025");
        assertEquals(housing, ((Number) json(year, "$.categories[0].categoryId")).longValue());
        assertEquals(2000.0, number(year, "$.categories[0].amount"));
        List<Number> details = json(year, "$.categories[0].details[*].amount");
        assertEquals(List.of(1800.0, 120.0, 80.0), details.stream().map(Number::doubleValue).toList());
        assertEquals(housing, ((Number) json(year, "$.categories[0].details[2].categoryId")).longValue());
        assertEquals(120.0, number(year, "$.categories[1].amount"));
        // The tag matrix goes by macro too
        assertEquals(housing, ((Number) json(year, "$.tagMatrices[0].rows[0].categoryId")).longValue());
        assertEquals(2000.0, number(year, "$.tagMatrices[0].rows[0].total"));
        // A tag's totals go by macro too, matching the table
        MvcResult tags = client.get("/api/tags");
        List<Number> tagCategories = json(tags, "$.tags[0].categories[*].categoryId");
        assertEquals(List.of(housing), tagCategories.stream().map(Number::longValue).toList());
        assertEquals(1920.0, number(tags, "$.tags[0].categories[0].amount"));

        // Filtering on a macro takes in its details; on a detail, only that detail
        assertEquals(3, ((List<?>) json(client.get("/api/cash-entries?categoryId=" + housing), "$.content")).size());
        assertEquals(1, ((List<?>) json(client.get("/api/cash-entries?categoryId=" + rent), "$.content")).size());

        // Budgets: a macro's covers its details; a detail's only itself, the rest goes by macro
        client.put("/api/budgets/" + housing, "{\"amount\":2500,\"currency\":\"CHF\"}");
        client.put("/api/budgets/" + sport, "{\"amount\":100,\"currency\":\"CHF\"}");
        MvcResult status = client.get("/api/budgets/status?month=2025-08");
        List<Number> housingSpent = json(status, "$.categories[?(@.categoryId == " + housing + ")].spent");
        assertEquals(2000.0, housingSpent.getFirst().doubleValue());
        assertEquals("Svago › Sport", ((List<?>) json(status, "$.categories[?(@.categoryId == " + sport + ")].name"))
                .getFirst());
        assertEquals(leisure, ((Number) json(status, "$.others[0].categoryId")).longValue());
        assertEquals(50.0, number(status, "$.others[0].spent"));

        // Annual report: by macro with details
        MvcResult report = client.get("/api/reports/annual?year=2025");
        List<Number> macros = json(report, "$.categories[*].categoryId");
        assertEquals(List.of(housing, leisure), macros.stream().map(Number::longValue).toList());
        assertEquals(3, ((List<?>) json(report, "$.categories[0].details")).size());
    }

    @Test
    void theDatabaseKeepsTheTwoLevelsToo() throws Exception {
        AppUser user = testUsers.create("catdb", Role.USER);
        ApiClient client = login(user);
        long housing = category(client, "Casa");
        long rent = category(client, "Affitto");
        long salary = category(client, "Stipendio");
        String insert = "insert into category (user_id, name, kind, color, parent_id) values (?, ?, ?, '#000000', ?)";
        // Under a detail, under a macro of the other kind or of another user
        assertThrows(DataAccessException.class, () -> jdbc.update(insert, user.getId(), "X", "EXPENSE", rent));
        assertThrows(DataAccessException.class, () -> jdbc.update(insert, user.getId(), "X", "EXPENSE", salary));
        AppUser other = testUsers.create("catdb2", Role.USER);
        assertThrows(DataAccessException.class, () -> jdbc.update(insert, other.getId(), "X", "EXPENSE", housing));
        // A macro with details stays a macro of its kind
        assertThrows(DataAccessException.class, () -> jdbc.update(
                "update category set parent_id = ? where id = ?", category(client, "Svago"), housing));
        assertThrows(DataAccessException.class, () -> jdbc.update(
                "update category set kind = 'INCOME' where id = ?", housing));
        assertEquals(1, jdbc.update(insert, user.getId(), "Garage", "EXPENSE", housing));
    }

    @Test
    void deletingAUserTakesTheirDetailsAndEntriesWithIt() throws Exception {
        AppUser admin = testUsers.create("catadmin", Role.ADMIN);
        AppUser user = testUsers.create("catgone", Role.USER);
        ApiClient client = login(user);
        entry(client, "2025-08-01", category(client, "Affitto"), 1800, "[\"Casa\"]");
        client.put("/api/budgets/" + category(client, "Affitto"), "{\"amount\":2000,\"currency\":\"CHF\"}");
        assertEquals(204, login(admin).delete("/api/admin/users/" + user.getId()).getResponse().getStatus());
        assertEquals(0, jdbc.queryForObject("select count(*) from category where user_id = ?", Integer.class,
                user.getId()));
    }
}
