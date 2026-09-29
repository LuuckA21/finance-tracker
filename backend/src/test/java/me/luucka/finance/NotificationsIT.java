package me.luucka.finance;

import static me.luucka.finance.support.ApiClient.json;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetup;
import jakarta.mail.internet.MimeMessage;
import me.luucka.finance.notification.NotificationService;
import me.luucka.finance.support.ApiClient;
import me.luucka.finance.support.IntegrationTest;
import me.luucka.finance.support.TestUsers;
import me.luucka.finance.user.AppUser;
import me.luucka.finance.user.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** Notifications sent through a real SMTP conversation with an in-process server (GreenMail). */
@IntegrationTest
@TestPropertySource(properties = {
        "app.mail.host=127.0.0.1",
        "app.mail.port=" + NotificationsIT.SMTP_PORT,
        "app.mail.security=NONE",
        "app.mail.from=Finanze <finanze@example.test>",
        "app.mail.app-url=https://finanze.example.test/",
        // Checked by the test itself, not by the clock
        "app.mail.cron=-"
})
class NotificationsIT {

    static final int SMTP_PORT = 18025;

    @RegisterExtension
    static final GreenMailExtension SMTP = new GreenMailExtension(new ServerSetup(SMTP_PORT, "127.0.0.1", "smtp"))
            .withPerMethodLifecycle(false);

    /** The code stands on its own line (user names in the greeting may contain digits too). */
    private static final Pattern CODE = Pattern.compile("(?m)^\\s+(\\d{6})\\s*$");

    @Autowired
    MockMvc mvc;

    @Autowired
    TestUsers testUsers;

    @Autowired
    NotificationService notifications;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void clearMailboxes() throws Exception {
        SMTP.purgeEmailFromAllMailboxes();
    }

    private ApiClient login(AppUser user) throws Exception {
        ApiClient client = new ApiClient(mvc);
        assertEquals(200, client.login(user.getUsername(), TestUsers.PASSWORD));
        return client;
    }

    private static int category(ApiClient client, String name) throws Exception {
        List<Integer> ids = json(client.get("/api/categories"), "$[?(@.name == '" + name + "')].id");
        return ids.getFirst();
    }

    private static void expense(ApiClient client, LocalDate date, int category, int amount) throws Exception {
        assertEquals(201, client.post("/api/cash-entries", """
                {"date":"%s","kind":"EXPENSE","categoryId":%d,"amount":%d,"currency":"CHF"}"""
                .formatted(date, category, amount)).getResponse().getStatus());
    }

    private static MimeMessage[] received() {
        return SMTP.getReceivedMessages();
    }

    /** Confirms {@code address} for the user through the code sent to it. */
    private static void confirm(ApiClient client, String address) throws Exception {
        assertEquals(200, client.post("/api/account/notifications/email", "{\"email\":\"" + address + "\"}")
                .getResponse().getStatus());
        MimeMessage[] mails = received();
        Matcher m = CODE.matcher(GreenMailUtil.getBody(mails[mails.length - 1]));
        assertTrue(m.find());
        assertEquals(200, client.post("/api/account/notifications/email/confirm", "{\"code\":\"" + m.group(1) + "\"}")
                .getResponse().getStatus());
    }

    @Test
    void anAddressIsConfirmedWithTheCodeSentToIt() throws Exception {
        ApiClient client = login(testUsers.create("mail-confirm", Role.USER));
        assertEquals(true, json(client.get("/api/account/notifications"), "$.mailEnabled"));
        assertNull(json(client.get("/api/account/notifications"), "$.email"));

        assertEquals("invalid_email", json(client.post("/api/account/notifications/email",
                "{\"email\":\"Luca <luca@example.test>\"}"), "$.code"));
        assertEquals("invalid_email", json(client.post("/api/account/notifications/email",
                "{\"email\":\"not-an-address\"}"), "$.code"));

        MvcResult asked = client.post("/api/account/notifications/email", "{\"email\":\"Luca@Example.TEST\"}");
        assertEquals(200, asked.getResponse().getStatus());
        assertEquals("Luca@example.test", json(asked, "$.pendingEmail"));
        MimeMessage mail = received()[0];
        assertEquals("Luca@example.test", mail.getAllRecipients()[0].toString());
        assertEquals("Finanze: codice di conferma", mail.getSubject());
        assertTrue(mail.getFrom()[0].toString().contains("finanze@example.test"));
        // UTF-8 end to end: accented letters survive the SMTP round trip
        assertTrue(((String) mail.getContent()).contains("questo indirizzo email è:"), (String) mail.getContent());
        Matcher code = CODE.matcher(GreenMailUtil.getBody(mail));
        assertTrue(code.find());

        // Asking again at once is refused; wrong codes count
        assertEquals(429, client.post("/api/account/notifications/email", "{\"email\":\"luca@example.test\"}")
                .getResponse().getStatus());
        String wrong = code.group(1).equals("000000") ? "111111" : "000000";
        assertEquals("invalid_code", json(client.post("/api/account/notifications/email/confirm",
                "{\"code\":\"" + wrong + "\"}"), "$.code"));
        MvcResult confirmed = client.post("/api/account/notifications/email/confirm",
                "{\"code\":\"" + code.group(1) + "\"}");
        assertEquals(200, confirmed.getResponse().getStatus());
        assertEquals("Luca@example.test", json(confirmed, "$.email"));
        assertNull(json(confirmed, "$.pendingEmail"));

        // A test email to the confirmed address, at most once a minute
        assertEquals(204, client.post("/api/account/notifications/test", "{}").getResponse().getStatus());
        assertEquals("Finanze: email di prova", received()[1].getSubject());
        assertEquals(429, client.post("/api/account/notifications/test", "{}").getResponse().getStatus());

        assertNull(json(client.delete("/api/account/notifications/email"), "$.email"));
        assertEquals("no_email", json(client.post("/api/account/notifications/test", "{}"), "$.code"));
    }

    @Test
    void tooManyWrongCodesEndTheConfirmation() throws Exception {
        ApiClient client = login(testUsers.create("mail-attempts", Role.USER));
        client.post("/api/account/notifications/email", "{\"email\":\"someone@example.test\"}");
        Matcher code = CODE.matcher(GreenMailUtil.getBody(received()[0]));
        assertTrue(code.find());
        String right = code.group(1);
        String wrong = right.equals("000000") ? "111111" : "000000";
        for (int i = 0; i < 5; i++) {
            assertEquals("invalid_code", json(client.post("/api/account/notifications/email/confirm",
                    "{\"code\":\"" + wrong + "\"}"), "$.code"));
        }
        // Even the right code no longer works
        assertEquals("code_expired", json(client.post("/api/account/notifications/email/confirm",
                "{\"code\":\"" + right + "\"}"), "$.code"));
        assertNull(json(client.get("/api/account/notifications"), "$.pendingEmail"));
    }

    @Test
    void alertsGoOutOnceAndOnlyForWhatHappensAfterSwitchingOn() throws Exception {
        AppUser user = testUsers.create("mail-alerts", Role.USER);
        ApiClient client = login(user);
        LocalDate today = LocalDate.now();
        int food = category(client, "Spesa alimentare");
        int restaurants = category(client, "Ristoranti");
        client.put("/api/budgets/" + food, "{\"amount\":100,\"currency\":\"CHF\"}");
        client.put("/api/budgets/" + restaurants, "{\"amount\":200,\"currency\":\"CHF\"}");
        // Already over before the address is confirmed: never sent
        expense(client, today, food, 150);

        confirm(client, "alerts@example.test");
        SMTP.purgeEmailFromAllMailboxes();
        notifications.run(user.getId());
        assertEquals(0, received().length);

        // Restaurants reach 85 %: one email; checking again sends nothing more
        expense(client, today, restaurants, 170);
        notifications.run(user.getId());
        assertEquals(1, received().length);
        MimeMessage alert = received()[0];
        assertEquals("Finanze: nuovi avvisi", alert.getSubject());
        String body = GreenMailUtil.getBody(alert);
        assertTrue(body.contains("Ristoranti: 85"), body);
        assertTrue(!body.contains("Spesa alimentare"), body);
        assertTrue(body.contains("https://finanze.example.test"), body);
        notifications.run(user.getId());
        assertEquals(1, received().length);

        // Then over the limit: a second email for the same category
        expense(client, today, restaurants, 40);
        notifications.run(user.getId());
        assertEquals(2, received().length);
        assertTrue(GreenMailUtil.getBody(received()[1]).contains("Ristoranti: superato"));

        // A goal reached
        int bank = json(client.post("/api/positions", """
                {"name":"Conto risparmio","assetClass":"CASH","currency":"CHF"}"""), "$.id");
        client.post("/api/positions/" + bank + "/snapshots", """
                {"date":"%s","quantity":5000,"unitPrice":1}""".formatted(today));
        client.post("/api/goals", """
                {"name":"Fondo emergenza","kind":"BALANCE","targetAmount":4000,"currency":"CHF","positionIds":[%d]}"""
                .formatted(bank));
        notifications.run(user.getId());
        assertEquals(3, received().length);
        assertTrue(GreenMailUtil.getBody(received()[2]).contains("Fondo emergenza: raggiunto"));

        // Goal alerts switched off: nothing for a second goal
        client.put("/api/account/notifications", "{\"goalAlerts\":false}");
        client.post("/api/goals", """
                {"name":"Vacanze","kind":"BALANCE","targetAmount":1000,"currency":"CHF","positionIds":[%d]}"""
                .formatted(bank));
        notifications.run(user.getId());
        assertEquals(3, received().length);
    }

    @Test
    void theSummaryOfLastMonthGoesOutOnce() throws Exception {
        AppUser user = testUsers.create("mail-monthly", Role.USER);
        ApiClient client = login(user);
        YearMonth last = YearMonth.now().minusMonths(1);
        int food = category(client, "Spesa alimentare");
        client.post("/api/cash-entries", """
                {"date":"%s","kind":"INCOME","categoryId":%d,"amount":6000,"currency":"CHF"}"""
                .formatted(last.atDay(25), category(client, "Stipendio")));
        expense(client, last.atDay(10), food, 700);
        expense(client, last.atDay(12), category(client, "Casa"), 1800);
        client.put("/api/budgets/" + food, "{\"amount\":600,\"currency\":\"CHF\"}");

        confirm(client, "monthly@example.test");
        // Confirming does not send last month's summary; forget that to see it
        jdbc.update("delete from notification_sent where user_id = ? and alert_key like 'monthly:%'", user.getId());
        SMTP.purgeEmailFromAllMailboxes();
        notifications.run(user.getId());

        MimeMessage[] mails = received();
        MimeMessage summary = mails[mails.length - 1];
        assertTrue(summary.getSubject().startsWith("Finanze: riepilogo di "), summary.getSubject());
        String body = GreenMailUtil.getBody(summary);
        assertTrue(body.contains("Entrate: CHF"), body);
        assertTrue(body.contains("Casa: CHF"), body);
        assertTrue(body.contains("Budget superati"), body);
        int count = received().length;
        notifications.run(user.getId());
        assertEquals(count, received().length);
    }

    @Test
    void settingsAreEachUsersOwn() throws Exception {
        ApiClient alice = login(testUsers.create("mail-alice", Role.USER));
        ApiClient bob = login(testUsers.create("mail-bob", Role.USER));
        confirm(alice, "alice@example.test");
        assertNull(json(bob.get("/api/account/notifications"), "$.email"));
        assertEquals(401, new ApiClient(mvc).get("/api/account/notifications").getResponse().getStatus());
    }
}
