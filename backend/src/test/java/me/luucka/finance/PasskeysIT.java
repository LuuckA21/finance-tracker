package me.luucka.finance;

import static me.luucka.finance.support.ApiClient.body;
import static me.luucka.finance.support.ApiClient.json;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;

import me.luucka.finance.core.security.Totp;
import me.luucka.finance.support.ApiClient;
import me.luucka.finance.support.IntegrationTest;
import me.luucka.finance.support.TestAuthenticator;
import me.luucka.finance.support.TestUsers;
import me.luucka.finance.user.AppUser;
import me.luucka.finance.user.Role;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@IntegrationTest
@TestPropertySource(properties = "app.public-url=https://finanze.example.test/")
class PasskeysIT {

    private static final String ORIGIN = "https://finanze.example.test";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestUsers testUsers;

    private ApiClient login(AppUser user) throws Exception {
        ApiClient client = new ApiClient(mvc);
        assertEquals(200, client.login(user.getUsername(), TestUsers.PASSWORD));
        return client;
    }

    /** Adds a passkey of {@code device} to the logged-in account. */
    private static MvcResult addPasskey(ApiClient client, TestAuthenticator device, String name) throws Exception {
        MvcResult options = client.post("/api/account/passkeys/options",
                "{\"password\":\"%s\"}".formatted(TestUsers.PASSWORD));
        assertEquals(200, options.getResponse().getStatus(), body(options));
        return client.post("/api/account/passkeys",
                "{\"name\":\"%s\",\"credential\":%s}".formatted(name, device.register(body(options))));
    }

    /** Signs in anonymously with {@code device}; returns the client and the status. */
    private ApiClient signIn(TestAuthenticator device, int expectedStatus) throws Exception {
        ApiClient client = new ApiClient(mvc);
        client.refreshCsrf();
        MvcResult options = client.post("/api/auth/passkey/options", null);
        assertEquals(200, options.getResponse().getStatus(), body(options));
        MvcResult result = client.post("/api/auth/passkey",
                "{\"credential\":%s}".formatted(device.signIn(body(options))));
        assertEquals(expectedStatus, result.getResponse().getStatus(), body(result));
        client.refreshCsrf();
        return client;
    }

    @Test
    void aPasskeySignsInWithoutPasswordOrCode() throws Exception {
        AppUser user = testUsers.create("passkey", Role.USER);
        ApiClient client = login(user);
        assertEquals(Boolean.TRUE, json(new ApiClient(mvc).get("/api/auth/config"), "$.passkeys"));

        // The options are for this site and this user, a discoverable credential, user verified
        MvcResult options = client.post("/api/account/passkeys/options",
                "{\"password\":\"%s\"}".formatted(TestUsers.PASSWORD));
        assertEquals("finanze.example.test", json(options, "$.rp.id"));
        assertEquals(user.getUsername(), json(options, "$.user.name"));
        assertEquals("required", json(options, "$.authenticatorSelection.userVerification"));
        assertEquals("required", json(options, "$.authenticatorSelection.residentKey"));

        TestAuthenticator phone = new TestAuthenticator(ORIGIN);
        MvcResult added = client.post("/api/account/passkeys",
                "{\"name\":\" iPhone \",\"credential\":%s}".formatted(phone.register(body(options))));
        assertEquals(201, added.getResponse().getStatus(), body(added));
        assertEquals("iPhone", json(added, "$.name"));
        // The challenge is used once: the same answer again is refused
        assertEquals("passkey_expired", json(client.post("/api/account/passkeys",
                "{\"name\":\"Copia\",\"credential\":%s}".formatted(phone.register(body(options)))), "$.code"));
        // Adding the same device again is refused too
        assertEquals("passkey_exists", json(addPasskey(client, phone, "Ancora"), "$.code"));

        ApiClient signedIn = signIn(phone, 204);
        assertEquals(user.getUsername(), json(signedIn.get("/api/auth/me"), "$.username"));
        List<String> reasons = json(signedIn.get("/api/account/logins"), "$[*].reason");
        assertEquals("PASSKEY", reasons.getFirst());
        assertTrue(body(signedIn.get("/api/account/passkeys")).contains("\"lastUsedAt\":\""));

        // Rename and remove; then the device opens nothing
        long id = ((Number) json(added, "$.id")).longValue();
        assertEquals("Telefono", json(signedIn.put("/api/account/passkeys/" + id, "{\"name\":\"Telefono\"}"), "$.name"));
        assertEquals(204, signedIn.delete("/api/account/passkeys/" + id).getResponse().getStatus());
        signIn(phone, 401);
    }

    @Test
    void wrongAnswersAndForeignDevicesAreRefused() throws Exception {
        AppUser user = testUsers.create("passkey-bad", Role.USER);
        ApiClient client = login(user);
        assertEquals("invalid_current_password", json(client.post("/api/account/passkeys/options",
                "{\"password\":\"wrong-password-123\"}"), "$.code"));

        // A device answering for another site is not accepted
        TestAuthenticator phishing = new TestAuthenticator("https://finanze.example.evil");
        assertEquals("passkey_invalid", json(addPasskey(client, phishing, "Falso"), "$.code"));

        TestAuthenticator phone = new TestAuthenticator(ORIGIN);
        assertEquals(201, addPasskey(client, phone, "Telefono").getResponse().getStatus());
        // A device never registered, and an answer without a sign-in started, open nothing
        signIn(new TestAuthenticator(ORIGIN), 401);
        ApiClient anonymous = new ApiClient(mvc);
        anonymous.refreshCsrf();
        MvcResult withoutOptions = anonymous.post("/api/auth/passkey",
                "{\"credential\":%s}".formatted(phone.signIn("{\"challenge\":\"AAAA\",\"rpId\":\"finanze.example.test\"}")));
        assertEquals("passkey_rejected", json(withoutOptions, "$.code"));
        assertEquals(401, anonymous.get("/api/auth/me").getResponse().getStatus());

        // Another user can neither see nor touch it
        ApiClient other = login(testUsers.create("passkey-other", Role.USER));
        assertEquals(List.of(), json(other.get("/api/account/passkeys"), "$"));
        long id = ((Number) json(client.get("/api/account/passkeys"), "$[0].id")).longValue();
        assertEquals(404, other.delete("/api/account/passkeys/" + id).getResponse().getStatus());
        assertEquals(404, other.put("/api/account/passkeys/" + id, "{\"name\":\"Mio\"}").getResponse().getStatus());
    }

    @Test
    void withTwoStepVerificationAddingAPasskeyNeedsTheCode() throws Exception {
        AppUser user = testUsers.create("passkey-mfa", Role.USER);
        ApiClient client = login(user);
        String secret = json(client.post("/api/account/mfa/setup", null), "$.secret");
        long step = Totp.stepAt(Instant.now());
        assertEquals(200, client.post("/api/account/mfa/enable", "{\"password\":\"%s\",\"code\":\"%s\"}"
                .formatted(TestUsers.PASSWORD, Totp.codeAt(secret, step))).getResponse().getStatus());

        assertEquals("invalid_mfa_code", json(client.post("/api/account/passkeys/options",
                "{\"password\":\"%s\"}".formatted(TestUsers.PASSWORD)), "$.code"));
        MvcResult options = client.post("/api/account/passkeys/options", "{\"password\":\"%s\",\"code\":\"%s\"}"
                .formatted(TestUsers.PASSWORD, Totp.codeAt(secret, step + 1)));
        assertEquals(200, options.getResponse().getStatus(), body(options));
        TestAuthenticator key = new TestAuthenticator(ORIGIN);
        assertEquals(201, client.post("/api/account/passkeys", "{\"name\":\"Chiavetta\",\"credential\":%s}"
                .formatted(key.register(body(options)))).getResponse().getStatus());

        // The passkey replaces both factors: no 2FA step after it
        ApiClient signedIn = signIn(key, 204);
        assertEquals(200, signedIn.get("/api/auth/me").getResponse().getStatus());

        // An administrator resetting the 2FA removes the passkeys too
        ApiClient admin = login(testUsers.create("passkey-admin", Role.ADMIN));
        assertEquals(200, admin.post("/api/admin/users/" + user.getId() + "/reset-mfa", null)
                .getResponse().getStatus());
        signIn(key, 401);
    }
}
