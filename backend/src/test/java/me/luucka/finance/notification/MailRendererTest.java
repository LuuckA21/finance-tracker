package me.luucka.finance.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import me.luucka.finance.notification.MailContent.Bar;
import me.luucka.finance.notification.MailContent.Code;
import me.luucka.finance.notification.MailContent.Paragraph;
import me.luucka.finance.notification.MailContent.Row;
import me.luucka.finance.notification.MailContent.Section;
import me.luucka.finance.notification.MailContent.Stat;
import me.luucka.finance.notification.MailContent.Stats;
import me.luucka.finance.notification.MailContent.Tone;
import me.luucka.finance.user.Language;
import org.junit.jupiter.api.Test;

class MailRendererTest {

    private static final MailContent MAIL = new MailContent(Language.IT, "Finanze: nuovi avvisi", "Casa: superato",
            "Ciao luca,", List.of(
                    new Paragraph("ecco cosa è cambiato:"),
                    new Stats(List.of(new Stat("Risparmio", "CHF 3’500.00", "58 % delle entrate", Tone.GOOD))),
                    new Section("Budget", List.of(
                            new Row("<b>Casa</b> & co", "CHF 210.00 su CHF 200.00", "superato (105 %)", Tone.BAD,
                                    new Bar(105, null)),
                            new Row("Viaggi", "CHF 80.00", null, Tone.NEUTRAL, new Bar(40, "red;background:url(x)")))),
                    new Code("123456")));

    @Test
    void theTextVersionReadsLikeALetter() {
        String text = MailRenderer.text(MAIL, "https://finanze.example.test");
        assertTrue(text.startsWith("Ciao luca,\n\necco cosa è cambiato:\n\n"), text);
        assertTrue(text.contains("Risparmio: CHF 3’500.00 (58 % delle entrate)\n"), text);
        assertTrue(text.contains("- <b>Casa</b> & co: superato (105 %), CHF 210.00 su CHF 200.00\n"), text);
        assertTrue(text.contains("- Viaggi: CHF 80.00\n"), text);
        assertTrue(text.contains("\n    123456\n"), text);
        assertTrue(text.contains("Apri l'app: https://finanze.example.test\n"), text);
    }

    @Test
    void userTextIsEscapedInTheHtml() {
        String html = MailRenderer.html(MAIL, "https://finanze.example.test");
        assertTrue(html.contains("&lt;b&gt;Casa&lt;/b&gt; &amp; co"), html);
        assertFalse(html.contains("<b>Casa"), html);
        // A colour that is not #rrggbb never reaches a style attribute
        assertFalse(html.contains("url(x)"), html);
    }

    @Test
    void barsAreClampedAndTheLinkIsAButton() {
        String html = MailRenderer.html(MAIL, "https://finanze.example.test");
        assertTrue(html.contains("<td width=\"100%\" style=\"height:6px"), html);
        assertTrue(html.contains("<td width=\"40%\""), html);
        assertTrue(html.contains("href=\"https://finanze.example.test\""), html);
        assertTrue(html.contains(">Apri l&#39;app</a>"), html);
        assertTrue(html.contains("<html lang=\"it\">"), html);
        assertTrue(html.contains("prefers-color-scheme: dark"), html);
        assertFalse(html.contains("<img"), html);
        assertFalse(html.contains("<script"), html);
    }

    @Test
    void anAddressThatIsNotHttpIsNeverLinked() {
        String html = MailRenderer.html(MAIL, "javascript:alert(1)");
        assertFalse(html.contains("javascript:"), html);
        assertFalse(MailRenderer.text(MAIL, "javascript:alert(1)").contains("javascript:"));
        assertEquals(-1, MailRenderer.html(MAIL, "").indexOf("href="));
    }
}
