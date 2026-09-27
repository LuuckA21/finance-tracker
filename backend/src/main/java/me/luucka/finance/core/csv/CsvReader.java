package me.luucka.finance.core.csv;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Strict, bounded CSV reader (RFC 4180): quoted fields, doubled quotes inside quotes, CRLF or LF
 * line ends, delimiter detected from the header line ({@code ;}, {@code ,} or tab).
 * <p>
 * Every limit is checked while reading, so an oversized or hostile file fails fast instead of
 * exhausting memory: number of records, fields per record and characters per field.
 */
public final class CsvReader {

    /** Why a file was rejected; {@code line} is 1-based, 0 when not tied to a line. */
    public static final class CsvException extends RuntimeException {

        private final String code;
        private final int line;

        CsvException(String code, int line, String message) {
            super(message);
            this.code = code;
            this.line = line;
        }

        public String code() {
            return code;
        }

        public int line() {
            return line;
        }
    }

    /** A parsed record and the line it starts on (1-based, header included). */
    public record Row(int line, List<String> fields) {
    }

    public record Limits(int maxRecords, int maxFields, int maxFieldLength) {
    }

    private static final Charset WINDOWS_1252 = Charset.forName("windows-1252");
    private static final char[] DELIMITERS = {';', ',', '\t'};

    private CsvReader() {
    }

    /**
     * Decodes as UTF-8 (BOM removed), falling back to Windows-1252 as written by older Excel.
     *
     * @throws CsvException {@code csv_binary} when the content is not text
     */
    public static String decode(byte[] content) {
        int offset = content.length >= 3 && (content[0] & 0xFF) == 0xEF && (content[1] & 0xFF) == 0xBB
                && (content[2] & 0xFF) == 0xBF ? 3 : 0;
        for (int i = offset; i < content.length; i++) {
            if (content[i] == 0) {
                throw new CsvException("csv_binary", 0, "The file is not a text file");
            }
        }
        ByteBuffer bytes = ByteBuffer.wrap(content, offset, content.length - offset);
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(bytes)
                    .toString();
        } catch (CharacterCodingException e) {
            return new String(content, offset, content.length - offset, WINDOWS_1252);
        }
    }

    /** The delimiter occurring most often outside quotes in the first line; {@code ,} if none. */
    public static char detectDelimiter(String text) {
        int[] counts = new int[DELIMITERS.length];
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"') {
                quoted = !quoted;
            } else if (!quoted && (c == '\n' || c == '\r')) {
                break;
            } else if (!quoted) {
                for (int d = 0; d < DELIMITERS.length; d++) {
                    if (c == DELIMITERS[d]) {
                        counts[d]++;
                    }
                }
            }
        }
        int best = 1;
        for (int d = 0; d < DELIMITERS.length; d++) {
            if (counts[d] > counts[best]) {
                best = d;
            }
        }
        return DELIMITERS[best];
    }

    /**
     * Parses every record; blank lines are skipped.
     *
     * @throws CsvException {@code csv_too_many_rows}, {@code csv_too_many_columns},
     *                      {@code csv_field_too_long} or {@code csv_malformed}
     */
    public static List<Row> parse(String text, char delimiter, Limits limits) {
        List<Row> rows = new ArrayList<>();
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        boolean fieldWasQuoted = false;
        int line = 1;
        int recordLine = 1;
        int length = text.length();

        for (int i = 0; i < length; i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < length && text.charAt(i + 1) == '"') {
                        append(field, '"', limits, recordLine);
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    if (c == '\n') {
                        line++;
                    }
                    append(field, c, limits, recordLine);
                }
            } else if (c == '"') {
                if (field.length() > 0 || fieldWasQuoted) {
                    throw new CsvException("csv_malformed", line, "Unexpected quote on line " + line);
                }
                quoted = true;
                fieldWasQuoted = true;
            } else if (c == delimiter) {
                addField(fields, field, limits, recordLine);
                fieldWasQuoted = false;
            } else if (c == '\r' || c == '\n') {
                if (c == '\r' && i + 1 < length && text.charAt(i + 1) == '\n') {
                    i++;
                }
                endRecord(rows, fields, field, fieldWasQuoted, limits, recordLine);
                fieldWasQuoted = false;
                line++;
                recordLine = line;
            } else {
                if (fieldWasQuoted) {
                    throw new CsvException("csv_malformed", line, "Text after a closing quote on line " + line);
                }
                append(field, c, limits, recordLine);
            }
        }
        if (quoted) {
            throw new CsvException("csv_malformed", recordLine, "Unterminated quote starting on line " + recordLine);
        }
        endRecord(rows, fields, field, fieldWasQuoted, limits, recordLine);
        return rows;
    }

    private static void append(StringBuilder field, char c, Limits limits, int line) {
        if (field.length() >= limits.maxFieldLength()) {
            throw new CsvException("csv_field_too_long", line,
                    "A value on line " + line + " is longer than " + limits.maxFieldLength() + " characters");
        }
        field.append(c);
    }

    private static void addField(List<String> fields, StringBuilder field, Limits limits, int line) {
        if (fields.size() >= limits.maxFields()) {
            throw new CsvException("csv_too_many_columns", line,
                    "Line " + line + " has more than " + limits.maxFields() + " columns");
        }
        fields.add(field.toString());
        field.setLength(0);
    }

    private static void endRecord(List<Row> rows, List<String> fields, StringBuilder field, boolean wasQuoted,
                                  Limits limits, int line) {
        if (fields.isEmpty() && field.length() == 0 && !wasQuoted) {
            return; // blank line
        }
        addField(fields, field, limits, line);
        if (rows.size() >= limits.maxRecords()) {
            throw new CsvException("csv_too_many_rows", line,
                    "The file has more than " + (limits.maxRecords() - 1) + " rows");
        }
        rows.add(new Row(line, List.copyOf(fields)));
        fields.clear();
    }
}
