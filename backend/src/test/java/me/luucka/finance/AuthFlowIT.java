package me.luucka.finance;

import static me.luucka.finance.support.ApiClient.json;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
class AuthFlowIT {

    @Autowired
    MockMvc mvc;

    @Autowired
    TestUsers testUsers;

    @Test
    void unauthenticatedRequestsGet401() throws Exception {
        ApiClient anonymous = new ApiClient(mvc);
        assertEquals(401, anonymous.get("/api/auth/me").getResponse().getStatus());
        assertEquals(401, anonymous.get("/api/cash-entries").getResponse().getStatus());
        assertEquals(401, anonymous.get("/api/dashboard/net-worth").getResponse().getStatus());
    }

    @Test
    void sessionCookieHasProductionAttributes() throws Exception {
        String cookie = new ApiClient(mvc).get("/api/auth/csrf").getResponse().getHeader("Set-Cookie");
        assertTrue(cookie.startsWith(ApiClient.SESSION_COOKIE + "="), cookie);
        assertTrue(cookie.contains("Secure"), cookie);
        assertTrue(cookie.contains("HttpOnly"), cookie);
        assertTrue(cookie.contains("SameSite=Strict"), cookie);
    }

    @Test
    void loginRequiresCsrfToken() throws Exception {
        AppUser user = testUsers.create("csrf", Role.USER);
        ApiClient client = new ApiClient(mvc);
        MvcResult result = client.postWithoutCsrf("/api/auth/login",
                "{\"username\":\"%s\",\"password\":\"%s\"}".formatted(user.getUsername(), TestUsers.PASSWORD));
        assertEquals(403, result.getResponse().getStatus());
    }

    @Test
    void successfulLoginRotatesSessionAndCsrfToken() throws Exception {
        AppUser user = testUsers.create("login", Role.USER);
        ApiClient client = new ApiClient(mvc);
        client.refreshCsrf();
        String before = client.sessionId();

        assertEquals(200, client.login(user.getUsername(), TestUsers.PASSWORD));
        assertNotEquals(before, client.sessionId(), "session id must change on login");

        MvcResult me = client.get("/api/auth/me");
        assertEquals(200, me.getResponse().getStatus());
        assertEquals(user.getUsername(), json(me, "$.username"));
        assertEquals("CHF", json(me, "$.baseCurrency"));
    }

    @Test
    void usernameIsCaseInsensitive() throws Exception {
        AppUser user = testUsers.create("case", Role.USER);
        ApiClient client = new ApiClient(mvc);
        assertEquals(200, client.login(user.getUsername().toUpperCase(), TestUsers.PASSWORD));
    }

    @Test
    void failedLoginsGiveGenericErrorAndLockTheAccount() throws Exception {
        AppUser user = testUsers.create("lock", Role.USER);
        ApiClient client = new ApiClient(mvc);

        client.refreshCsrf();
        MvcResult invalid = client.post("/api/auth/login", "{}");
        assertEquals(400, invalid.getResponse().getStatus()); // validation: missing fields

        for (int i = 0; i < 5; i++) {
            assertEquals(401, client.login(user.getUsername(), "wrong-password-" + i));
        }
        // Correct password is now refused while the account is locked, with the same generic error
        assertEquals(401, client.login(user.getUsername(), TestUsers.PASSWORD));
        assertEquals(401, client.login("no-such-user", TestUsers.PASSWORD));
    }

    @Test
    void temporaryPasswordMustBeChangedFirst() throws Exception {
        AppUser user = testUsers.create("temp", Role.USER, true);
        ApiClient client = new ApiClient(mvc);
        assertEquals(200, client.login(user.getUsername(), TestUsers.PASSWORD));

        MvcResult blocked = client.get("/api/categories");
        assertEquals(403, blocked.getResponse().getStatus());
        assertEquals("password_change_required", json(blocked, "$.code"));
        assertEquals(Boolean.TRUE, json(client.get("/api/auth/me"), "$.passwordChangeRequired"));

        MvcResult weak = client.put("/api/account/password",
                "{\"currentPassword\":\"%s\",\"newPassword\":\"short\"}".formatted(TestUsers.PASSWORD));
        assertEquals(400, weak.getResponse().getStatus());
        assertEquals("weak_password", json(weak, "$.code"));

        MvcResult wrongCurrent = client.put("/api/account/password",
                "{\"currentPassword\":\"nope\",\"newPassword\":\"A-much-better-passphrase\"}");
        assertEquals(400, wrongCurrent.getResponse().getStatus());

        MvcResult ok = client.put("/api/account/password",
                "{\"currentPassword\":\"%s\",\"newPassword\":\"A-much-better-passphrase\"}".formatted(TestUsers.PASSWORD));
        assertEquals(200, ok.getResponse().getStatus());
        assertEquals(Boolean.FALSE, json(ok, "$.passwordChangeRequired"));
        assertEquals(200, client.get("/api/categories").getResponse().getStatus());
    }

    @Test
    void changingPasswordRevokesOtherSessions() throws Exception {
        AppUser user = testUsers.create("revoke", Role.USER);
        ApiClient laptop = new ApiClient(mvc);
        ApiClient phone = new ApiClient(mvc);
        assertEquals(200, laptop.login(user.getUsername(), TestUsers.PASSWORD));
        assertEquals(200, phone.login(user.getUsername(), TestUsers.PASSWORD));

        MvcResult changed = laptop.put("/api/account/password",
                "{\"currentPassword\":\"%s\",\"newPassword\":\"Another-long-passphrase\"}".formatted(TestUsers.PASSWORD));
        assertEquals(200, changed.getResponse().getStatus());

        assertEquals(200, laptop.get("/api/auth/me").getResponse().getStatus());
        assertEquals(401, phone.get("/api/auth/me").getResponse().getStatus());
    }

    @Test
    void logoutEndsTheSession() throws Exception {
        AppUser user = testUsers.create("logout", Role.USER);
        ApiClient client = new ApiClient(mvc);
        assertEquals(200, client.login(user.getUsername(), TestUsers.PASSWORD));
        assertEquals(204, client.post("/api/auth/logout", null).getResponse().getStatus());
        assertEquals(401, client.get("/api/auth/me").getResponse().getStatus());
    }

    @Test
    void adminApiIsForAdminsOnly() throws Exception {
        AppUser user = testUsers.create("plain", Role.USER);
        ApiClient client = new ApiClient(mvc);
        assertEquals(200, client.login(user.getUsername(), TestUsers.PASSWORD));
        assertEquals(403, client.get("/api/admin/users").getResponse().getStatus());
    }

    @Test
    void disablingAUserKillsSessionsAndBlocksLogin() throws Exception {
        AppUser admin = testUsers.create("admin", Role.ADMIN);
        AppUser user = testUsers.create("victim", Role.USER);
        ApiClient adminClient = new ApiClient(mvc);
        ApiClient userClient = new ApiClient(mvc);
        assertEquals(200, adminClient.login(admin.getUsername(), TestUsers.PASSWORD));
        assertEquals(200, userClient.login(user.getUsername(), TestUsers.PASSWORD));

        MvcResult disabled = adminClient.patch("/api/admin/users/" + user.getId(), "{\"enabled\":false}");
        assertEquals(200, disabled.getResponse().getStatus());

        assertEquals(401, userClient.get("/api/auth/me").getResponse().getStatus());
        assertEquals(401, userClient.login(user.getUsername(), TestUsers.PASSWORD));
    }

    @Test
    void adminCreatesUserWithTemporaryPassword() throws Exception {
        AppUser admin = testUsers.create("admin", Role.ADMIN);
        ApiClient adminClient = new ApiClient(mvc);
        assertEquals(200, adminClient.login(admin.getUsername(), TestUsers.PASSWORD));

        String username = "family-" + System.nanoTime() % 100_000;
        MvcResult created = adminClient.post("/api/admin/users",
                "{\"username\":\"%s\",\"role\":\"USER\"}".formatted(username));
        assertEquals(201, created.getResponse().getStatus());
        String temporary = json(created, "$.temporaryPassword");

        ApiClient newUser = new ApiClient(mvc);
        assertEquals(200, newUser.login(username, temporary));
        assertEquals(Boolean.TRUE, json(newUser.get("/api/auth/me"), "$.passwordChangeRequired"));

        MvcResult selfDemote = adminClient.patch("/api/admin/users/" + admin.getId(), "{\"role\":\"USER\"}");
        assertEquals(400, selfDemote.getResponse().getStatus());
    }

    @Test
    void newUserGetsLanguageAndStartingCategoriesInIt() throws Exception {
        AppUser admin = testUsers.create("admin", Role.ADMIN);
        ApiClient adminClient = new ApiClient(mvc);
        assertEquals(200, adminClient.login(admin.getUsername(), TestUsers.PASSWORD));

        String username = "english-" + System.nanoTime() % 100_000;
        MvcResult created = adminClient.post("/api/admin/users",
                "{\"username\":\"%s\",\"role\":\"USER\",\"language\":\"EN\"}".formatted(username));
        assertEquals(201, created.getResponse().getStatus());

        String temporary = json(created, "$.temporaryPassword");
        ApiClient newUser = new ApiClient(mvc);
        assertEquals(200, newUser.login(username, temporary));
        assertEquals("EN", json(newUser.get("/api/auth/me"), "$.language"));
        assertEquals(200, newUser.put("/api/account/password",
                "{\"currentPassword\":\"%s\",\"newPassword\":\"%s\"}".formatted(temporary, TestUsers.PASSWORD))
                .getResponse().getStatus());
        newUser.refreshCsrf();
        List<String> names = json(newUser.get("/api/categories"), "$[*].name");
        assertTrue(names.contains("Salary") && names.contains("Groceries"), names.toString());
        assertTrue(!names.contains("Stipendio"), names.toString());
    }
}
