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

/** Without SMTP settings the notifications say so instead of failing. */
@IntegrationTest
class NotificationsDisabledIT {

    @Autowired
    MockMvc mvc;

    @Autowired
    TestUsers testUsers;

    @Test
    void withoutAMailServerNothingCanBeSent() throws Exception {
        AppUser user = testUsers.create("mail-off", Role.USER);
        ApiClient client = new ApiClient(mvc);
        assertEquals(200, client.login(user.getUsername(), TestUsers.PASSWORD));

        assertEquals(false, json(client.get("/api/account/notifications"), "$.mailEnabled"));
        MvcResult asked = client.post("/api/account/notifications/email", "{\"email\":\"a@example.test\"}");
        assertEquals(409, asked.getResponse().getStatus());
        assertEquals("mail_disabled", json(asked, "$.code"));
        // The preferences can still be kept
        assertEquals(false, json(client.put("/api/account/notifications", "{\"monthlySummary\":false}"),
                "$.monthlySummary"));
    }
}
