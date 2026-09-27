package me.luucka.finance.core.csv;

import java.util.Arrays;
import java.util.List;

/**
 * Writes CSV for spreadsheets: fields quoted when needed (RFC 4180), and text that a spreadsheet
 * would run as a formula ({@code = + - @}, tab, CR) prefixed with {@code '} (OWASP "CSV injection").
 */
public final class CsvWriter {

    private final StringBuilder out = new StringBuilder();
    private final char delimiter;

    public CsvWriter(char delimiter) {
        this.delimiter = delimiter;
    }

    /** A row where every value is user text, neutralised so it can never run as a formula. */
    public CsvWriter textRow(List<String> values) {
        boolean[] flags = new boolean[values.size()];
        Arrays.fill(flags, true);
        return row(values, flags);
    }

    /** A row where only the columns flagged in {@code textColumns} are user text. */
    public CsvWriter row(List<String> values, boolean[] textColumns) {
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                out.append(delimiter);
            }
            String value = values.get(i) == null ? "" : values.get(i);
            out.append(quote(textColumns[i] ? neutralize(value) : value));
        }
        out.append("\r\n");
        return this;
    }

    @Override
    public String toString() {
        return out.toString();
    }

    /** Prefixes values a spreadsheet would interpret as a formula. */
    public static String neutralize(String value) {
        if (value.isEmpty()) {
            return value;
        }
        char first = value.charAt(0);
        return first == '=' || first == '+' || first == '-' || first == '@' || first == '\t' || first == '\r'
                ? "'" + value : value;
    }

    /** Reverses {@link #neutralize(String)} for values read back from an exported file. */
    public static String unneutralize(String value) {
        if (value.length() >= 2 && value.charAt(0) == '\'') {
            char next = value.charAt(1);
            if (next == '=' || next == '+' || next == '-' || next == '@' || next == '\t' || next == '\r') {
                return value.substring(1);
            }
        }
        return value;
    }

    private String quote(String value) {
        boolean needsQuotes = value.indexOf(delimiter) >= 0 || value.indexOf('"') >= 0
                || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0
                || (!value.isEmpty() && (value.charAt(0) == ' ' || value.charAt(value.length() - 1) == ' '));
        return needsQuotes ? '"' + value.replace("\"", "\"\"") + '"' : value;
    }
}
