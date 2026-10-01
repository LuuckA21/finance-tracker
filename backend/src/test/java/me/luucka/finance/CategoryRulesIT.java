package me.luucka.finance;

import static me.luucka.finance.support.ApiClient.json;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.charset.StandardCharsets;
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
class CategoryRulesIT {

    private static final String PREVIEW = "/api/cash-entries/import/preview";

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

    private static MvcResult rule(ApiClient client, String pattern, long categoryId) throws Exception {
        return client.post("/api/category-rules", """
                {"pattern":"%s","categoryId":%d}""".formatted(pattern, categoryId));
    }

    private static MvcResult upload(ApiClient client, String csv) throws Exception {
        return client.upload(PREVIEW, "movimenti.csv", csv.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void rulesAreTheUsersOwnAndChecked() throws Exception {
        ApiClient client = login(testUsers.create("rules", Role.USER));
        long groceries = category(client, "Supermercato");
        MvcResult created = rule(client, "  Migros   Zürich ", groceries);
        assertEquals(201, created.getResponse().getStatus());
        assertEquals("Migros Zürich", json(created, "$.pattern"));
        int id = json(created, "$.id");

        // The same text, case, accents and punctuation aside, is the same rule
        MvcResult duplicate = rule(client, "migros-zurich", category(client, "Ristoranti"));
        assertEquals(409, duplicate.getResponse().getStatus());
        assertEquals("rule_exists", json(duplicate, "$.code"));
        assertEquals("rule_pattern_too_short", json(rule(client, " * a ", groceries), "$.code"));

        assertEquals(200, client.put("/api/category-rules/" + id, """
                {"pattern":"Migros","categoryId":%d}""".formatted(groceries)).getResponse().getStatus());
        assertEquals(List.of("Migros"), json(client.get("/api/category-rules"), "$[*].pattern"));

        // Another user sees nothing, cannot change it, nor point a rule at someone else's category
        ApiClient other = login(testUsers.create("rules-other", Role.USER));
        assertEquals(List.of(), json(other.get("/api/category-rules"), "$"));
        assertEquals(404, other.put("/api/category-rules/" + id, """
                {"pattern":"x y","categoryId":%d}""".formatted(category(other, "Svago"))).getResponse().getStatus());
        assertEquals(404, other.delete("/api/category-rules/" + id).getResponse().getStatus());
        assertEquals(404, rule(other, "Coop", groceries).getResponse().getStatus());

        // Deleting the category takes its rules with it
        long custom = ((Number) json(client.post("/api/categories", """
                {"name":"Box","kind":"EXPENSE","color":"#123456","parentId":null}"""), "$.id")).longValue();
        rule(client, "Parking Garage", custom);
        assertEquals(204, client.delete("/api/categories/" + custom).getResponse().getStatus());
        assertEquals(List.of("Migros"), json(client.get("/api/category-rules"), "$[*].pattern"));

        assertEquals(204, client.delete("/api/category-rules/" + id).getResponse().getStatus());
        assertEquals(List.of(), json(client.get("/api/category-rules"), "$"));
    }

    @Test
    void suggestionsComeFromTheRulesThenFromThePast() throws Exception {
        ApiClient client = login(testUsers.create("rules-suggest", Role.USER));
        long groceries = category(client, "Supermercato");
        long dinners = category(client, "Cene");
        rule(client, "migros", groceries);
        client.post("/api/cash-entries", """
                {"date":"2026-08-01","kind":"EXPENSE","categoryId":%d,"amount":80,"currency":"CHF",
                 "description":"Pizzeria Da Mario 01.08"}""".formatted(dinners));

        MvcResult fromRule = client.get("/api/category-rules/suggest?description=MIGROS Bern&kind=EXPENSE");
        assertEquals(groceries, ((Number) json(fromRule, "$.categoryId")).longValue());
        assertEquals("migros", json(fromRule, "$.pattern"));

        MvcResult fromPast = client.get("/api/category-rules/suggest?description=pizzeria da mario 15.09");
        assertEquals(dinners, ((Number) json(fromPast, "$.categoryId")).longValue());
        assertNull(json(fromPast, "$.ruleId"));

        assertEquals(204, client.get("/api/category-rules/suggest?description=Migros&kind=INCOME").getResponse().getStatus());
        assertEquals(204, client.get("/api/category-rules/suggest?description=nuovo").getResponse().getStatus());
    }

    @Test
    void importedRowsWithoutAUsableCategoryGetTheRuleAndThePastProposesOne() throws Exception {
        ApiClient client = login(testUsers.create("rules-import", Role.USER));
        long groceries = category(client, "Supermercato");
        long dinners = category(client, "Cene");
        long salary = category(client, "Stipendio");
        rule(client, "migros", groceries);
        rule(client, "ACME SA", salary);
        client.post("/api/cash-entries", """
                {"date":"2026-07-01","kind":"EXPENSE","categoryId":%d,"amount":80,"currency":"CHF",
                 "description":"Pizzeria Da Mario"}""".formatted(dinners));

        MvcResult preview = upload(client, """
                data;tipo;categoria;importo;descrizione
                2026-08-01;Uscita;;45;MIGROS ZURICH 1234
                2026-08-02;Uscita;Sconosciuta;12;Migros Bern
                2026-08-03;Uscita;Ristoranti;30;Migros Restaurant
                2026-08-04;;;6000;Stipendio ACME SA agosto
                2026-08-05;Uscita;;70;Pizzeria da Mario
                2026-08-06;Uscita;;9;Edicola
                """);
        assertEquals(Integer.valueOf(4), json(preview, "$.valid"));
        List<Object> ids = json(preview, "$.rows[*].categoryId");
        List<Object> sources = json(preview, "$.rows[*].categorySource");
        // No category, or one the user does not have: the rule
        assertEquals(groceries, ((Number) ids.get(0)).longValue());
        assertEquals("RULE", sources.get(0));
        assertEquals("migros", json(preview, "$.rows[0].rulePattern"));
        assertEquals(groceries, ((Number) ids.get(1)).longValue());
        assertEquals(List.of(), json(preview, "$.rows[1].errors"));
        // The file's own category wins
        assertEquals(category(client, "Ristoranti"), ((Number) ids.get(2)).longValue());
        assertEquals("FILE", sources.get(2));
        // A rule also gives the type when the file has none
        assertEquals("INCOME", json(preview, "$.rows[3].kind"));
        assertEquals(salary, ((Number) ids.get(3)).longValue());
        // The past only proposes: the row still needs a category
        assertNull(ids.get(4));
        assertEquals(dinners, ((Number) json(preview, "$.rows[4].suggestedCategoryId")).longValue());
        assertEquals(List.of("missing_category"), json(preview, "$.rows[4].errors"));
        assertNull(json(preview, "$.rows[5].suggestedCategoryId"));
    }
}
