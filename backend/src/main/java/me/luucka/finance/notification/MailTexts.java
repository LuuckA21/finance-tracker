package me.luucka.finance.notification;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.Currency;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import me.luucka.finance.user.Language;

/**
 * Email texts in the user's language ({@code mail/texts_xx.properties}) and the formats used in them:
 * Swiss number formats, like the web interface.
 */
public final class MailTexts {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\w+)}");
    private static final Map<Language, Properties> TEXTS = new EnumMap<>(Language.class);

    static {
        for (Language language : Language.values()) {
            TEXTS.put(language, load(language));
        }
    }

    private MailTexts() {
    }

    /** The text of {@code key} with its {@code {name}} placeholders replaced; {@code {app}} is always known. */
    public static String text(Language language, String key, Map<String, String> values) {
        String template = TEXTS.get(language).getProperty(key);
        if (template == null) {
            throw new IllegalArgumentException("Missing mail text " + key + " for " + language);
        }
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String name = m.group(1);
            String value = "app".equals(name) ? TEXTS.get(language).getProperty("app") : values.get(name);
            m.appendReplacement(out, Matcher.quoteReplacement(value == null ? m.group() : value));
        }
        m.appendTail(out);
        return out.toString();
    }

    public static String text(Language language, String key) {
        return text(language, key, Map.of());
    }

    /** Keys of one language's texts, for the consistency test. */
    static Collection<String> keys(Language language) {
        return TEXTS.get(language).stringPropertyNames();
    }

    public static String money(BigDecimal amount, String currency, Language language) {
        NumberFormat format = NumberFormat.getCurrencyInstance(locale(language));
        try {
            format.setCurrency(Currency.getInstance(currency));
        } catch (IllegalArgumentException e) {
            // Not an ISO code Java knows: show the code in front of the number
            NumberFormat number = NumberFormat.getNumberInstance(locale(language));
            number.setMinimumFractionDigits(2);
            number.setMaximumFractionDigits(2);
            return currency + " " + number.format(amount);
        }
        format.setMinimumFractionDigits(2);
        format.setMaximumFractionDigits(2);
        return format.format(amount);
    }

    /** Money with an explicit sign: +CHF 1’200.00, −CHF 80.00. */
    public static String signedMoney(BigDecimal amount, String currency, Language language) {
        String sign = amount.signum() > 0 ? "+" : amount.signum() < 0 ? "−" : "";
        return sign + money(amount.abs(), currency, language);
    }

    public static String percent(BigDecimal value, Language language) {
        NumberFormat format = NumberFormat.getNumberInstance(locale(language));
        format.setMaximumFractionDigits(0);
        return format.format(value.setScale(0, RoundingMode.HALF_EVEN)) + "\u00a0%";
    }

    /** "agosto 2026", "August 2026", ... */
    public static String month(YearMonth month, Language language) {
        return DateTimeFormatter.ofPattern("LLLL yyyy", locale(language)).format(month);
    }

    private static Locale locale(Language language) {
        return Locale.of(language.locale().getLanguage(), "CH");
    }

    private static Properties load(Language language) {
        String path = "/mail/texts_" + language.name().toLowerCase(Locale.ROOT) + ".properties";
        try (InputStream in = MailTexts.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("Missing " + path);
            }
            Properties properties = new Properties();
            properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            return properties;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
