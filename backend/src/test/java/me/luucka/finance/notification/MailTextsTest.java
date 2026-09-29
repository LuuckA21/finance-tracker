package me.luucka.finance.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import me.luucka.finance.user.Language;
import org.junit.jupiter.api.Test;

class MailTextsTest {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\w+)}");

    @Test
    void everyLanguageHasTheSameKeysAndPlaceholders() {
        Set<String> keys = new TreeSet<>(MailTexts.keys(Language.IT));
        for (Language language : Language.values()) {
            assertEquals(keys, new TreeSet<>(MailTexts.keys(language)), language.name());
            for (String key : keys) {
                assertEquals(placeholders(MailTexts.text(Language.IT, key)), placeholders(MailTexts.text(language, key)),
                        language + " " + key);
            }
        }
    }

    @Test
    void frenchPutsANonBreakingSpaceBeforeColons() {
        for (String key : MailTexts.keys(Language.FR)) {
            String text = MailTexts.text(Language.FR, key);
            assertFalse(text.contains(" :"), key);
        }
    }

    @Test
    void placeholdersAreFilledAndTheAppNameIsKnown() {
        assertEquals("Finanze: riepilogo di agosto 2026", MailTexts.text(Language.IT, "subject.monthly",
                Map.of("month", MailTexts.month(YearMonth.of(2026, 8), Language.IT))));
        String body = MailTexts.text(Language.IT, "code.body", Map.of("code", "123456"));
        assertTrue(body.contains("\n\n    123456\n\n"), body);
    }

    @Test
    void swissFormats() {
        assertEquals("CHF 1’234.50", MailTexts.money(new BigDecimal("1234.5"), "CHF", Language.IT).replace('\u00a0', ' '));
        assertEquals("−CHF 80.00", MailTexts.signedMoney(new BigDecimal("-80"), "CHF", Language.EN).replace('\u00a0', ' '));
        assertEquals("102\u00a0%", MailTexts.percent(new BigDecimal("101.6"), Language.DE));
        assertEquals("August 2026", MailTexts.month(YearMonth.of(2026, 8), Language.DE));
    }

    private static Set<String> placeholders(String text) {
        Set<String> names = new TreeSet<>();
        Matcher m = PLACEHOLDER.matcher(text);
        while (m.find()) {
            names.add(m.group(1));
        }
        return names;
    }
}
