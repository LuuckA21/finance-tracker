package me.luucka.finance;

import static me.luucka.finance.support.ApiClient.json;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetup;
import jakarta.mail.BodyPart;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
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

    /** The plain-text version of a message. */
    private static String text(MimeMessage mail) throws Exception {
        return part(mail, "text/plain");
    }

    private static String html(MimeMessage mail) throws Exception {
        return part(mail, "text/html");
    }

    private static String part(Part part, String type) throws Exception {
        if (part.isMimeType(type)) {
            return (String) part.getContent();
        }
        if (part.getContent() instanceof Multipart multipart) {
            for (int i = 0; i < multipart.getCount(); i++) {
                BodyPart child = multipart.getBodyPart(i);
                String found = part(child, type);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static MimeMessage[] received() {
        return SMTP.getReceivedMessages();
    }

    /** Confirms {@code address} for the user through the code sent to it. */
    private static void confirm(ApiClient client, String address) throws Exception {
        assertEquals(200, client.post("/api/account/notifications/email", "{\"email\":\"" + address + "\"}")
                .getResponse().getStatus());
        MimeMessage[] mails = received();
        Matcher m = CODE.matcher(text(mails[mails.length - 1]));
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
        // Both versions, UTF-8 end to end: accented letters survive the SMTP round trip
        assertTrue(text(mail).contains("questo indirizzo email è:"), text(mail));
        assertTrue(html(mail).contains("questo indirizzo email è:"), html(mail));
        Matcher code = CODE.matcher(text(mail));
        assertTrue(code.find());
        assertTrue(html(mail).contains(">" + code.group(1) + "</div>"), html(mail));

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
        Matcher code = CODE.matcher(text(received()[0]));
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
    void wrongCodesSentAllAtOnceStillCountOneByOne() throws Exception {
        ApiClient client = login(testUsers.create("mail-race", Role.USER));
        client.post("/api/account/notifications/email", "{\"email\":\"race@example.test\"}");
        Matcher code = CODE.matcher(text(received()[0]));
        assertTrue(code.find());
        String right = code.group(1);
        String wrong = right.equals("000000") ? "111111" : "000000";
        // Many guesses in parallel: without locking they would all read "0 attempts so far"
        int guesses = 24;
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(guesses)) {
            List<java.util.concurrent.Future<Integer>> results = new java.util.ArrayList<>();
            for (int i = 0; i < guesses; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return client.post("/api/account/notifications/email/confirm", "{\"code\":\"" + wrong + "\"}")
                            .getResponse().getStatus();
                }));
            }
            start.countDown();
            for (var result : results) {
                assertEquals(400, result.get());
            }
        }
        // Five wrong codes end the confirmation: the right one no longer works
        MvcResult late = client.post("/api/account/notifications/email/confirm", "{\"code\":\"" + right + "\"}");
        assertEquals(400, late.getResponse().getStatus());
        assertNull(json(client.get("/api/account/notifications"), "$.email"));
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
        String body = text(alert);
        assertTrue(body.contains("Ristoranti: 85"), body);
        assertTrue(!body.contains("Spesa alimentare"), body);
        assertTrue(body.contains("https://finanze.example.test"), body);
        // The HTML version: the category escaped in a row with its bar, and the button to the app
        String page = html(alert);
        assertTrue(page.contains(">Ristoranti<br>"), page);
        assertTrue(page.contains("<td width=\"85%\""), page);
        assertTrue(page.contains("href=\"https://finanze.example.test\""), page);
        notifications.run(user.getId());
        assertEquals(1, received().length);

        // Then over the limit: a second email for the same category
        expense(client, today, restaurants, 40);
        notifications.run(user.getId());
        assertEquals(2, received().length);
        assertTrue(text(received()[1]).contains("Ristoranti: superato"));

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
        assertTrue(text(received()[2]).contains("Fondo emergenza: raggiunto"));

        // Goal alerts switched off: nothing for a second goal
        client.put("/api/account/notifications", "{\"goalAlerts\":false}");
        client.post("/api/goals", """
                {"name":"Vacanze","kind":"BALANCE","targetAmount":1000,"currency":"CHF","positionIds":[%d]}"""
                .formatted(bank));
        notifications.run(user.getId());
        assertEquals(3, received().length);
    }

    @Test
    void aYearlyBudgetAlertsOncePerYear() throws Exception {
        AppUser user = testUsers.create("mail-yearly", Role.USER);
        ApiClient client = login(user);
        LocalDate today = LocalDate.now();
        int health = category(client, "Cassa malati");
        client.put("/api/budgets/" + health, """
                {"amount":4000,"currency":"CHF","period":"YEARLY"}""");
        confirm(client, "yearly@example.test");
        SMTP.purgeEmailFromAllMailboxes();

        // 85 % of the year: one email naming the year; the same alert never again this year
        expense(client, today, health, 3400);
        notifications.run(user.getId());
        assertEquals(1, received().length);
        String body = text(received()[0]);
        assertTrue(body.contains("Assicurazioni › Cassa malati · anno " + today.getYear() + ": 85"), body);
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM notification_sent WHERE user_id = ? AND alert_key = ?",
                Integer.class, user.getId(),
                "budget:" + health + ":" + today.getYear() + ":80"));
        notifications.run(user.getId());
        assertEquals(1, received().length);
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
        expense(client, last.atDay(14), category(client, "Energia"), 200);
        client.put("/api/budgets/" + food, "{\"amount\":600,\"currency\":\"CHF\"}");
        client.put("/api/budgets/" + category(client, "Energia"), "{\"amount\":500,\"currency\":\"CHF\"}");

        confirm(client, "monthly@example.test");
        // Confirming does not send last month's summary; forget that to see it
        jdbc.update("delete from notification_sent where user_id = ? and alert_key like 'monthly:%'", user.getId());
        SMTP.purgeEmailFromAllMailboxes();
        notifications.run(user.getId());

        MimeMessage[] mails = received();
        MimeMessage summary = mails[mails.length - 1];
        assertTrue(summary.getSubject().startsWith("Finanze: riepilogo di "), summary.getSubject());
        String body = text(summary);
        assertTrue(body.contains("Entrate: CHF"), body);
        // Largest spending by macro, a detail with its own budget included in it
        assertTrue(Pattern.compile("Casa: CHF.?2.?000").matcher(body).find(), body);
        assertFalse(body.contains("Energia"), body);
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
