package me.luucka.finance;

import static me.luucka.finance.support.ApiClient.json;
import static org.junit.jupiter.api.Assertions.assertEquals;

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
class AccountSettingsIT {

    @Autowired
    MockMvc mvc;

    @Autowired
    TestUsers testUsers;

    private ApiClient loggedIn() throws Exception {
        AppUser user = testUsers.create("settings", Role.USER);
        ApiClient client = new ApiClient(mvc);
        assertEquals(200, client.login(user.getUsername(), TestUsers.PASSWORD));
        return client;
    }

    @Test
    void newUsersGetItalianAndSystemTheme() throws Exception {
        MvcResult me = loggedIn().get("/api/auth/me");
        assertEquals("IT", json(me, "$.language"));
        assertEquals("SYSTEM", json(me, "$.theme"));
    }

    @Test
    void settingsAreUpdatedIndependently() throws Exception {
        ApiClient client = loggedIn();

        MvcResult language = client.put("/api/account/settings", "{\"language\":\"EN\"}");
        assertEquals(200, language.getResponse().getStatus());
        assertEquals("EN", json(language, "$.language"));
        assertEquals("SYSTEM", json(language, "$.theme"));
        assertEquals("CHF", json(language, "$.baseCurrency"));

        client.put("/api/account/settings", "{\"theme\":\"DARK\"}");
        client.put("/api/account/settings", "{\"baseCurrency\":\"eur\"}");

        MvcResult me = client.get("/api/auth/me");
        assertEquals("EN", json(me, "$.language"));
        assertEquals("DARK", json(me, "$.theme"));
        assertEquals("EUR", json(me, "$.baseCurrency"));
    }

    @Test
    void unknownValuesAreRejected() throws Exception {
        ApiClient client = loggedIn();
        assertEquals(400, client.put("/api/account/settings", "{\"language\":\"FR\"}").getResponse().getStatus());
        assertEquals(400, client.put("/api/account/settings", "{\"theme\":\"BLUE\"}").getResponse().getStatus());
        assertEquals(400, client.put("/api/account/settings", "{\"baseCurrency\":\"XXXX\"}").getResponse().getStatus());
        assertEquals("IT", json(client.get("/api/auth/me"), "$.language"));
    }
}
