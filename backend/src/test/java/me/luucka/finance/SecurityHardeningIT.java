package me.luucka.finance;

import static me.luucka.finance.support.ApiClient.json;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import me.luucka.finance.core.security.Totp;
import me.luucka.finance.support.ApiClient;
import me.luucka.finance.support.IntegrationTest;
import me.luucka.finance.support.TestUsers;
import me.luucka.finance.user.AppUser;
import me.luucka.finance.user.Role;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** Brute force from inside a session, 2FA enrolment and input bounds. */
@IntegrationTest
class SecurityHardeningIT {

    @Autowired
    MockMvc mvc;

    @Autowired
    TestUsers testUsers;

    private ApiClient login(AppUser user) throws Exception {
        ApiClient client = new ApiClient(mvc);
        assertEquals(200, client.login(user.getUsername(), TestUsers.PASSWORD));
        return client;
    }

    private static int changePassword(ApiClient client, String current) throws Exception {
        return client.put("/api/account/password",
                "{\"currentPassword\":\"%s\",\"newPassword\":\"Brand-New-Secret-77\"}".formatted(current))
                .getResponse().getStatus();
    }

    /** Enrols 2FA and returns the secret; the next usable code is at step + 1. */
    private static String enrol(ApiClient client) throws Exception {
        String secret = json(client.post("/api/account/mfa/setup", null), "$.secret");
        String code = Totp.codeAt(secret, Totp.stepAt(Instant.now()));
        MvcResult enabled = client.post("/api/account/mfa/enable",
                "{\"password\":\"%s\",\"code\":\"%s\"}".formatted(TestUsers.PASSWORD, code));
        assertEquals(200, enabled.getResponse().getStatus());
        return secret;
    }

    @Test
    void guessingTheCurrentPasswordLocksTheAccountAndEndsTheSession() throws Exception {
        AppUser user = testUsers.create("reauth", Role.USER);
        ApiClient stolen = login(user);
        for (int i = 1; i <= 4; i++) {
            assertEquals(400, changePassword(stolen, "wrong-guess-" + i));
        }
        assertEquals(429, changePassword(stolen, "wrong-guess-5"));
        assertEquals(401, stolen.get("/api/auth/me").getResponse().getStatus());
        // The owner too must wait for the lock (or an admin) before signing in again
        assertEquals(401, new ApiClient(mvc).login(user.getUsername(), TestUsers.PASSWORD));
    }

    @Test
    void aCorrectPasswordResetsTheCounter() throws Exception {
        AppUser user = testUsers.create("reauth-ok", Role.USER);
        ApiClient client = login(user);
        for (int i = 1; i <= 3; i++) {
            assertEquals(400, changePassword(client, "wrong-guess-" + i));
        }
        assertEquals(200, changePassword(client, TestUsers.PASSWORD));
        client.refreshCsrf();
        for (int i = 1; i <= 4; i++) {
            assertEquals(400, client.put("/api/account/password",
                    "{\"currentPassword\":\"wrong-%d\",\"newPassword\":\"Another-Secret-88\"}".formatted(i))
                    .getResponse().getStatus());
        }
    }

    @Test
    void guessingTotpCodesForNewRecoveryCodesIsLimited() throws Exception {
        AppUser user = testUsers.create("reauth-totp", Role.USER);
        ApiClient client = login(user);
        enrol(client);
        for (int i = 1; i <= 4; i++) {
            assertEquals(400, client.post("/api/account/mfa/recovery-codes", "{\"code\":\"00000%d\"}".formatted(i))
                    .getResponse().getStatus());
        }
        assertEquals(429, client.post("/api/account/mfa/recovery-codes", "{\"code\":\"000005\"}")
                .getResponse().getStatus());
        assertEquals(401, client.get("/api/auth/me").getResponse().getStatus());
    }

    @Test
    void aRecoveryCodeIsNotUsedUpOnALockedAccount() throws Exception {
        AppUser user = testUsers.create("reauth-recovery", Role.USER);
        ApiClient owner = login(user);
        String secret = enrol(owner);
        List<String> codes = json(owner.post("/api/account/mfa/recovery-codes",
                "{\"code\":\"%s\"}".formatted(Totp.codeAt(secret, Totp.stepAt(Instant.now()) + 1))), "$.recoveryCodes");
        owner.post("/api/auth/logout", null);

        ApiClient pending = new ApiClient(mvc);
        assertEquals(200, pending.login(user.getUsername(), TestUsers.PASSWORD));
        ApiClient attacker = new ApiClient(mvc);
        for (int i = 0; i < 5; i++) {
            attacker.login(user.getUsername(), "not-the-password");
        }
        assertEquals(401, pending.loginMfa(codes.getFirst()));

        AppUser admin = testUsers.create("reauth-admin", Role.ADMIN);
        assertEquals(200, login(admin).post("/api/admin/users/" + user.getId() + "/unlock", null)
                .getResponse().getStatus());
        ApiClient again = new ApiClient(mvc);
        assertEquals(200, again.login(user.getUsername(), TestUsers.PASSWORD));
        assertEquals(204, again.loginMfa(codes.getFirst()));
    }

    @Test
    void recurringStartDateCannotReachTooFarBack() throws Exception {
        ApiClient client = login(testUsers.create("bounds", Role.USER));
        List<Integer> ids = json(client.get("/api/categories"), "$[?(@.kind == 'EXPENSE')].id");
        String body = """
                {"kind":"EXPENSE","categoryId":%d,"amount":1,"currency":"CHF","frequency":"DAILY","startDate":"%s"}""";
        MvcResult old = client.post("/api/recurring-entries", body.formatted(ids.getFirst(), "1900-01-01"));
        assertEquals("start_too_old", json(old, "$.code"));
        assertEquals(201, client.post("/api/recurring-entries",
                body.formatted(ids.getFirst(), LocalDate.now().minusYears(1))).getResponse().getStatus());
    }

    @Test
    void absurdDatesAreRejected() throws Exception {
        ApiClient client = login(testUsers.create("dates", Role.USER));
        List<Integer> ids = json(client.get("/api/categories"), "$[?(@.kind == 'EXPENSE')].id");
        MvcResult farFuture = client.post("/api/cash-entries", """
                {"date":"+999999-01-01","kind":"EXPENSE","categoryId":%d,"amount":1,"currency":"CHF"}"""
                .formatted(ids.getFirst()));
        assertEquals(400, farFuture.getResponse().getStatus());
        assertEquals(400, client.post("/api/fx-rates", "{\"currency\":\"EUR\",\"date\":\"1800-01-01\",\"rate\":1}")
                .getResponse().getStatus());
    }
}
