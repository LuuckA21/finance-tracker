package me.luucka.finance;

import static me.luucka.finance.support.ApiClient.json;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
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

@IntegrationTest
class MfaFlowIT {

    @Autowired
    MockMvc mvc;

    @Autowired
    TestUsers testUsers;

    @Test
    void enrolLoginWithTotpAndRecoveryCode() throws Exception {
        AppUser user = testUsers.create("mfa", Role.USER);
        ApiClient client = new ApiClient(mvc);
        assertEquals(200, client.login(user.getUsername(), TestUsers.PASSWORD));

        // Enrol
        MvcResult setup = client.post("/api/account/mfa/setup", null);
        assertEquals(200, setup.getResponse().getStatus());
        String secret = json(setup, "$.secret");
        long step = Totp.stepAt(Instant.now());

        MvcResult badEnable = client.post("/api/account/mfa/enable", "{\"code\":\"000000\"}");
        assertEquals(400, badEnable.getResponse().getStatus());

        MvcResult enabled = client.post("/api/account/mfa/enable",
                "{\"code\":\"%s\"}".formatted(Totp.codeAt(secret, step)));
        assertEquals(200, enabled.getResponse().getStatus());
        List<String> recoveryCodes = json(enabled, "$.recoveryCodes");
        assertEquals(10, recoveryCodes.size());
        assertEquals(Boolean.TRUE, json(client.get("/api/auth/me"), "$.mfaEnabled"));
        client.post("/api/auth/logout", null);

        // Password alone is not enough
        MvcResult passwordStep = loginPasswordStep(client, user);
        assertEquals(Boolean.TRUE, json(passwordStep, "$.mfaRequired"));
        assertEquals(401, client.get("/api/auth/me").getResponse().getStatus());
        assertEquals(401, client.loginMfa("123456"));

        // The code used during enrolment cannot be replayed; the next time step is accepted
        assertEquals(401, client.loginMfa(Totp.codeAt(secret, step)));
        assertEquals(204, client.loginMfa(Totp.codeAt(secret, step + 1)));
        assertEquals(200, client.get("/api/auth/me").getResponse().getStatus());
        client.post("/api/auth/logout", null);

        // Recovery code works exactly once
        loginPasswordStep(client, user);
        assertEquals(204, client.loginMfa(recoveryCodes.getFirst().toLowerCase()));
        assertEquals(Integer.valueOf(9), json(client.get("/api/auth/me"), "$.recoveryCodesRemaining"));
        client.post("/api/auth/logout", null);

        loginPasswordStep(client, user);
        assertEquals(401, client.loginMfa(recoveryCodes.getFirst()));
    }

    @Test
    void secondFactorStepWithoutPasswordStepIsRejected() throws Exception {
        ApiClient client = new ApiClient(mvc);
        client.refreshCsrf();
        MvcResult result = client.post("/api/auth/login/mfa", "{\"code\":\"123456\"}");
        assertEquals(401, result.getResponse().getStatus());
        assertEquals("mfa_expired", json(result, "$.code"));
    }

    private static MvcResult loginPasswordStep(ApiClient client, AppUser user) throws Exception {
        client.refreshCsrf();
        MvcResult result = client.post("/api/auth/login",
                "{\"username\":\"%s\",\"password\":\"%s\"}".formatted(user.getUsername(), TestUsers.PASSWORD));
        assertEquals(200, result.getResponse().getStatus());
        client.refreshCsrf();
        return result;
    }
}
