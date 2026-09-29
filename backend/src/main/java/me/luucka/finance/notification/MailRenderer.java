package me.luucka.finance.notification;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import me.luucka.finance.notification.MailContent.Bar;
import me.luucka.finance.notification.MailContent.Block;
import me.luucka.finance.notification.MailContent.Code;
import me.luucka.finance.notification.MailContent.Note;
import me.luucka.finance.notification.MailContent.Paragraph;
import me.luucka.finance.notification.MailContent.Row;
import me.luucka.finance.notification.MailContent.Section;
import me.luucka.finance.notification.MailContent.Stat;
import me.luucka.finance.notification.MailContent.Stats;
import me.luucka.finance.notification.MailContent.Tone;
import org.springframework.web.util.HtmlUtils;

/**
 * Renders a {@link MailContent} as plain text and as HTML.
 * <p>
 * The HTML is built for email clients: tables and inline styles only, no images, no scripts, nothing
 * loaded from the network (no tracking), a dark palette for clients that honour
 * {@code prefers-color-scheme}, and every piece of text escaped, since category and goal names are
 * user input.
 */
public final class MailRenderer {

    private static final Pattern HEX_COLOR = Pattern.compile("#[0-9a-fA-F]{6}");
    private static final String FONT = "-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif";

    // The app's light palette (frontend/src/index.css)
    private static final String PAGE = "#f5f5f3";
    private static final String SURFACE = "#ffffff";
    private static final String LINE = "#e6e5e1";
    private static final String TRACK = "#f0efec";
    private static final String INK = "#0b0b0b";
    private static final String INK_2 = "#52514e";
    private static final String MUTED = "#6f6d68";
    private static final String ACCENT = "#2a78d6";
    private static final Map<Tone, String> TONES = Map.of(
            Tone.NEUTRAL, INK, Tone.GOOD, "#006300", Tone.WARN, "#b45309", Tone.BAD, "#b42323");

    /** Dark palette, applied by clients that support the media query (Apple Mail, Outlook apps, ...). */
    private static final String DARK_CSS = """
            @media (prefers-color-scheme: dark) {
              .ft-page { background: #0d0d0d !important; }
              .ft-card { background: #1a1a19 !important; border-color: #2c2c2a !important; }
              .ft-ink { color: #ffffff !important; }
              .ft-ink2 { color: #c3c2b7 !important; }
              .ft-muted { color: #9a988f !important; }
              .ft-line { border-color: #2c2c2a !important; }
              .ft-track { background: #2c2c2a !important; }
              .ft-good { color: #3fbf3f !important; }
              .ft-warn { color: #f3c860 !important; }
              .ft-bad { color: #ef7070 !important; }
            }""";

    private MailRenderer() {
    }

    // ------------------------------------------------------------------ text

    public static String text(MailContent mail, String appUrl) {
        StringBuilder out = new StringBuilder();
        out.append(mail.greeting()).append("\n\n");
        for (Block block : mail.blocks()) {
            switch (block) {
                case Paragraph p -> out.append(p.text()).append("\n\n");
                case Code c -> out.append("    ").append(c.code()).append("\n\n");
                case Stats s -> {
                    for (Stat stat : s.stats()) {
                        out.append(stat.label()).append(": ").append(stat.value());
                        if (stat.sub() != null) {
                            out.append(" (").append(stat.sub()).append(')');
                        }
                        out.append('\n');
                    }
                    out.append('\n');
                }
                case Section s -> {
                    out.append(s.heading()).append('\n');
                    for (Row row : s.rows()) {
                        out.append("- ").append(row.label()).append(": ");
                        if (row.detail() != null) {
                            out.append(row.detail()).append(", ");
                        }
                        out.append(row.value()).append('\n');
                    }
                    out.append('\n');
                }
                case Note n -> out.append(n.text()).append("\n\n");
            }
        }
        out.append("--\n");
        if (link(appUrl) != null) {
            out.append(MailTexts.text(mail.language(), "footer.link", Map.of("url", appUrl))).append('\n');
        }
        out.append(MailTexts.text(mail.language(), "footer.settings")).append('\n');
        return out.toString();
    }

    // ------------------------------------------------------------------ HTML

    public static String html(MailContent mail, String appUrl) {
        String lang = mail.language().locale().getLanguage();
        StringBuilder body = new StringBuilder();
        body.append(paragraph(mail.greeting(), INK, "ft-ink"));
        for (Block block : mail.blocks()) {
            switch (block) {
                case Paragraph p -> body.append(paragraph(p.text(), INK, "ft-ink"));
                case Code c -> body.append("<div class=\"ft-track ft-ink\" style=\"margin:8px 0 20px;padding:16px;")
                        .append("background:").append(TRACK).append(";border-radius:10px;text-align:center;")
                        .append("font-family:'SF Mono',Menlo,Consolas,monospace;font-size:30px;font-weight:700;")
                        .append("letter-spacing:8px;color:").append(INK).append(";\">").append(esc(c.code()))
                        .append("</div>");
                case Stats s -> body.append(stats(s));
                case Section s -> body.append(section(s));
                case Note n -> body.append("<p class=\"ft-muted\" style=\"margin:16px 0 0;font-size:12px;")
                        .append("line-height:18px;color:").append(MUTED).append(";\">").append(esc(n.text()))
                        .append("</p>");
            }
        }
        String link = link(appUrl);
        if (link != null) {
            body.append("<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" style=\"margin:24px 0 4px;\">")
                    .append("<tr><td style=\"border-radius:8px;background:").append(ACCENT).append(";\">")
                    .append("<a href=\"").append(esc(link)).append("\" style=\"display:inline-block;padding:10px 18px;")
                    .append("font-family:").append(FONT).append(";font-size:14px;font-weight:600;color:#ffffff;")
                    .append("text-decoration:none;border-radius:8px;\">")
                    .append(esc(MailTexts.text(mail.language(), "footer.button"))).append("</a></td></tr></table>");
        }

        return """
                <!doctype html>
                <html lang="%s">
                <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <meta name="color-scheme" content="light dark">
                <meta name="supported-color-schemes" content="light dark">
                <title>%s</title>
                <style>
                %s
                </style>
                </head>
                <body class="ft-page" style="margin:0;padding:0;background:%s;">
                <div style="display:none;max-height:0;overflow:hidden;opacity:0;">%s</div>
                <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" class="ft-page" style="background:%s;">
                <tr><td align="center" style="padding:24px 12px;">
                <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="max-width:560px;">
                <tr><td style="padding:0 4px 14px;font-family:%s;">
                <span style="display:inline-block;padding:5px 11px;border-radius:8px;background:%s;color:#ffffff;font-size:14px;font-weight:700;">%s</span>
                </td></tr>
                <tr><td class="ft-card" style="padding:24px;background:%s;border:1px solid %s;border-radius:14px;font-family:%s;">
                %s
                </td></tr>
                <tr><td class="ft-muted" style="padding:14px 4px 0;font-family:%s;font-size:12px;line-height:18px;color:%s;">%s</td></tr>
                </table>
                </td></tr>
                </table>
                </body>
                </html>
                """.formatted(lang, esc(mail.subject()), DARK_CSS, PAGE, esc(mail.preheader() == null ? "" : mail.preheader()),
                PAGE, FONT, ACCENT, esc(MailTexts.text(mail.language(), "app")), SURFACE, LINE, FONT, body, FONT, MUTED,
                esc(MailTexts.text(mail.language(), "footer.settings")));
    }

    private static String paragraph(String text, String color, String cssClass) {
        return "<p class=\"" + cssClass + "\" style=\"margin:0 0 14px;font-size:15px;line-height:22px;color:" + color
                + ";\">" + esc(text) + "</p>";
    }

    /** Key figures as boxes that wrap onto new lines on narrow screens. */
    private static String stats(Stats stats) {
        StringBuilder out = new StringBuilder("<div style=\"margin:4px 0 8px;font-size:0;\">");
        for (Stat stat : stats.stats()) {
            out.append("<div class=\"ft-line\" style=\"display:inline-block;vertical-align:top;width:48%;min-width:190px;")
                    .append("box-sizing:border-box;margin:0 1% 10px 0;padding:12px 14px;border:1px solid ").append(LINE)
                    .append(";border-radius:10px;\">")
                    .append("<div class=\"ft-ink2\" style=\"font-size:12px;line-height:16px;color:").append(INK_2)
                    .append(";\">").append(esc(stat.label())).append("</div>")
                    .append("<div class=\"").append(toneClass(stat.tone())).append("\" style=\"margin-top:4px;font-size:20px;")
                    .append("line-height:26px;font-weight:700;white-space:nowrap;color:").append(TONES.get(stat.tone()))
                    .append(";\">").append(esc(stat.value())).append("</div>");
            if (stat.sub() != null) {
                out.append("<div class=\"ft-muted\" style=\"margin-top:2px;font-size:12px;line-height:16px;color:")
                        .append(MUTED).append(";\">").append(esc(stat.sub())).append("</div>");
            }
            out.append("</div>");
        }
        return out.append("</div>").toString();
    }

    private static String section(Section section) {
        StringBuilder out = new StringBuilder();
        out.append("<h2 class=\"ft-ink\" style=\"margin:22px 0 6px;font-size:14px;line-height:20px;font-weight:700;color:")
                .append(INK).append(";\">").append(esc(section.heading())).append("</h2>")
                .append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\">");
        for (Row row : section.rows()) {
            out.append("<tr><td class=\"ft-line\" style=\"padding:9px 0;border-top:1px solid ").append(LINE).append(";\">")
                    .append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\"><tr>")
                    .append("<td class=\"ft-ink\" style=\"font-size:14px;line-height:20px;color:").append(INK).append(";\">")
                    .append(esc(row.label()));
            if (row.detail() != null) {
                out.append("<br><span class=\"").append(toneClass(row.tone())).append("\" style=\"font-size:12px;color:")
                        .append(row.tone() == Tone.NEUTRAL ? MUTED : TONES.get(row.tone())).append(";\">")
                        .append(esc(row.detail())).append("</span>");
            }
            out.append("</td><td align=\"right\" class=\"ft-ink\" style=\"padding-left:12px;font-size:14px;line-height:20px;")
                    .append("white-space:nowrap;vertical-align:top;color:").append(INK).append(";\">")
                    .append(esc(row.value())).append("</td></tr></table>");
            if (row.bar() != null) {
                out.append(bar(row.bar(), row.tone()));
            }
            out.append("</td></tr>");
        }
        return out.append("</table>").toString();
    }

    /** A track with a filled part; nested tables because email clients ignore most other layouts. */
    private static String bar(Bar bar, Tone tone) {
        long filled = Math.round(Math.clamp(bar.percent(), 0, 100));
        String color = bar.color() != null && HEX_COLOR.matcher(bar.color()).matches()
                ? bar.color() : tone == Tone.NEUTRAL ? ACCENT : TONES.get(tone);
        StringBuilder out = new StringBuilder("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" ")
                .append("class=\"ft-track\" style=\"margin-top:6px;background:").append(TRACK)
                .append(";border-radius:4px;\"><tr>");
        if (filled > 0) {
            out.append("<td width=\"").append(filled).append("%\" style=\"height:6px;line-height:6px;font-size:0;background:")
                    .append(color).append(";border-radius:4px;\">&nbsp;</td>");
        }
        if (filled < 100) {
            out.append("<td style=\"height:6px;line-height:6px;font-size:0;\">&nbsp;</td>");
        }
        return out.append("</tr></table>").toString();
    }

    private static String toneClass(Tone tone) {
        return switch (tone) {
            case NEUTRAL -> "ft-ink";
            case GOOD -> "ft-good";
            case WARN -> "ft-warn";
            case BAD -> "ft-bad";
        };
    }

    /** The app address when it is an http(s) URL, otherwise null (never turned into a link). */
    private static String link(String appUrl) {
        if (appUrl == null || appUrl.isBlank()) {
            return null;
        }
        String lower = appUrl.toLowerCase(Locale.ROOT);
        return lower.startsWith("https://") || lower.startsWith("http://") ? appUrl : null;
    }

    private static String esc(String text) {
        return HtmlUtils.htmlEscape(text, "UTF-8");
    }
}
