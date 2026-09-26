package me.luucka.finance;

import static me.luucka.finance.support.ApiClient.json;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.LocalDate;
import java.util.List;

import me.luucka.finance.recurring.RecurringEntryScheduler;
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
class RecurringEntriesIT {

    @Autowired
    MockMvc mvc;

    @Autowired
    TestUsers testUsers;

    @Autowired
    RecurringEntryScheduler scheduler;

    private final LocalDate today = LocalDate.now();

    private ApiClient loggedIn() throws Exception {
        AppUser user = testUsers.create("recurring", Role.USER);
        ApiClient client = new ApiClient(mvc);
        assertEquals(200, client.login(user.getUsername(), TestUsers.PASSWORD));
        return client;
    }

    private static int categoryId(ApiClient client, String kind) throws Exception {
        List<Integer> ids = json(client.get("/api/categories"), "$[?(@.kind == '" + kind + "')].id");
        return ids.getFirst();
    }

    private static MvcResult createRule(ApiClient client, int categoryId, String kind, String frequency,
                                        LocalDate start, LocalDate end) throws Exception {
        return client.post("/api/recurring-entries", """
                {"kind":"%s","categoryId":%d,"amount":100,"currency":"CHF","description":"rule",
                 "frequency":"%s","startDate":"%s","endDate":%s}""".formatted(kind, categoryId, frequency, start,
                end == null ? "null" : "\"" + end + "\""));
    }

    private static List<String> entryDates(ApiClient client) throws Exception {
        return json(client.get("/api/cash-entries?size=200"), "$.content[?(@.recurringEntryId != null)].date");
    }

    @Test
    void creatingARuleWithAPastStartCreatesTheEntriesAlreadyDue() throws Exception {
        ApiClient client = loggedIn();
        MvcResult rule = createRule(client, categoryId(client, "EXPENSE"), "EXPENSE", "DAILY", today.minusDays(4), null);
        assertEquals(201, rule.getResponse().getStatus());
        assertEquals(today.toString(), json(rule, "$.lastGenerated"));
        assertEquals(today.plusDays(1).toString(), json(rule, "$.nextDate"));
        assertEquals(5, entryDates(client).size());

        // Running the scheduler again creates nothing new
        scheduler.generateAll();
        assertEquals(5, entryDates(client).size());
    }

    @Test
    void monthlyRuleRespectsTheEndDate() throws Exception {
        ApiClient client = loggedIn();
        MvcResult rule = createRule(client, categoryId(client, "INCOME"), "INCOME", "MONTHLY",
                today.minusMonths(3), today.minusMonths(2));
        assertEquals(List.of(today.minusMonths(2).toString(), today.minusMonths(3).toString()), entryDates(client));
        assertNull(json(rule, "$.nextDate"));
    }

    @Test
    void futureRuleCreatesNothingYet() throws Exception {
        ApiClient client = loggedIn();
        MvcResult rule = createRule(client, categoryId(client, "EXPENSE"), "EXPENSE", "YEARLY", today.plusDays(10), null);
        assertEquals(today.plusDays(10).toString(), json(rule, "$.nextDate"));
        assertEquals(List.of(), entryDates(client));
    }

    @Test
    void deletedEntriesAreNotRecreatedAndDeletingTheRuleKeepsItsEntries() throws Exception {
        ApiClient client = loggedIn();
        int category = categoryId(client, "EXPENSE");
        int ruleId = json(createRule(client, category, "EXPENSE", "DAILY", today.minusDays(2), null), "$.id");
        List<Integer> entryIds = json(client.get("/api/cash-entries?size=200"), "$.content[*].id");
        assertEquals(204, client.delete("/api/cash-entries/" + entryIds.getFirst()).getResponse().getStatus());
        scheduler.generateAll();
        assertEquals(2, entryDates(client).size());

        // The category cannot go while a rule uses it
        assertEquals(409, client.delete("/api/categories/" + category).getResponse().getStatus());

        assertEquals(204, client.delete("/api/recurring-entries/" + ruleId).getResponse().getStatus());
        List<Object> links = json(client.get("/api/cash-entries?size=200"), "$.content[*].recurringEntryId");
        assertEquals(2, links.size());
        links.forEach(link -> assertNull(link));
    }

    @Test
    void resumingAPausedRuleSkipsWhatFellDueWhilePaused() throws Exception {
        ApiClient client = loggedIn();
        int category = categoryId(client, "EXPENSE");
        String body = """
                {"kind":"EXPENSE","categoryId":%d,"amount":100,"currency":"CHF","frequency":"DAILY",
                 "startDate":"%s","active":%s}""";
        MvcResult paused = client.post("/api/recurring-entries", body.formatted(category, today.minusDays(5), false));
        assertEquals(List.of(), entryDates(client));
        assertNull(json(paused, "$.nextDate"));

        int id = json(paused, "$.id");
        MvcResult resumed = client.put("/api/recurring-entries/" + id, body.formatted(category, today.minusDays(5), true));
        assertEquals(List.of(today.toString()), entryDates(client));
        assertEquals(today.plusDays(1).toString(), json(resumed, "$.nextDate"));
    }

    @Test
    void invalidRulesAreRejected() throws Exception {
        ApiClient client = loggedIn();
        int expense = categoryId(client, "EXPENSE");
        assertEquals(400, createRule(client, expense, "INCOME", "MONTHLY", today, null).getResponse().getStatus());
        assertEquals(400, createRule(client, expense, "EXPENSE", "MONTHLY", today, today.minusDays(1))
                .getResponse().getStatus());
        assertEquals(400, createRule(client, expense, "EXPENSE", "HOURLY", today, null).getResponse().getStatus());
    }

    @Test
    void rulesArePrivate() throws Exception {
        ApiClient owner = loggedIn();
        int ruleId = json(createRule(owner, categoryId(owner, "EXPENSE"), "EXPENSE", "MONTHLY", today.plusDays(1), null),
                "$.id");
        ApiClient other = loggedIn();
        assertEquals(List.of(), json(other.get("/api/recurring-entries"), "$"));
        assertEquals(404, other.delete("/api/recurring-entries/" + ruleId).getResponse().getStatus());
    }
}
