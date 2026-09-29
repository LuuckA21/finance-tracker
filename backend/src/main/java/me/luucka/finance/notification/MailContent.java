package me.luucka.finance.notification;

import java.util.List;

import me.luucka.finance.user.Language;

/**
 * What an email says, independent of its format: {@link MailRenderer} turns it into the plain-text
 * and the HTML version of the same message, so the two never drift apart.
 *
 * @param preheader the line shown next to the subject in the inbox (HTML only)
 */
public record MailContent(Language language, String subject, String preheader, String greeting, List<Block> blocks) {

    public sealed interface Block permits Paragraph, Code, Stats, Section, Note {
    }

    public enum Tone { NEUTRAL, GOOD, WARN, BAD }

    public record Paragraph(String text) implements Block {
    }

    /** A code the reader types somewhere else, shown large. */
    public record Code(String code) implements Block {
    }

    /** Key figures side by side; {@code sub} is optional. */
    public record Stats(List<Stat> stats) implements Block {
    }

    public record Stat(String label, String value, String sub, Tone tone) {
    }

    public record Section(String heading, List<Row> rows) implements Block {
    }

    /**
     * One line of a section: "label: detail, value" in text. {@code detail} and {@code bar} are optional.
     */
    public record Row(String label, String value, String detail, Tone tone, Bar bar) {
    }

    /**
     * A horizontal bar under a row.
     *
     * @param percent filled share, clamped to 0-100 when drawn
     * @param color   CSS colour {@code #rrggbb}; null takes the colour of the row's tone
     */
    public record Bar(double percent, String color) {
    }

    /** Small print. */
    public record Note(String text) implements Block {
    }
}
