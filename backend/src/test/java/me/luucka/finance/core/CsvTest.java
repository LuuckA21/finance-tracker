package me.luucka.finance.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import me.luucka.finance.core.csv.CsvReader;
import me.luucka.finance.core.csv.CsvReader.CsvException;
import me.luucka.finance.core.csv.CsvWriter;
import me.luucka.finance.core.csv.EntryCsvFormat;
import me.luucka.finance.core.csv.EntryCsvFormat.Column;
import org.junit.jupiter.api.Test;

class CsvTest {

    private static final CsvReader.Limits LIMITS = new CsvReader.Limits(10, 5, 20);

    private static List<List<String>> parse(String text, char delimiter) {
        return CsvReader.parse(text, delimiter, LIMITS).stream().map(CsvReader.Row::fields).toList();
    }

    @Test
    void readsQuotedFieldsEscapedQuotesAndLineBreaks() {
        String text = "a;b;c\r\n\"x;y\";\"say \"\"hi\"\"\";\"two\nlines\"\n\n1;;3";
        assertEquals(List.of(
                List.of("a", "b", "c"),
                List.of("x;y", "say \"hi\"", "two\nlines"),
                List.of("1", "", "3")), parse(text, ';'));
        // Line numbers follow the file, blank lines and quoted line breaks included
        assertEquals(5, CsvReader.parse(text, ';', LIMITS).getLast().line());
    }

    @Test
    void detectsTheDelimiterFromTheHeader() {
        assertEquals(';', CsvReader.detectDelimiter("data;importo;\"a,b\"\n1,2,3,4,5"));
        assertEquals(',', CsvReader.detectDelimiter("date,amount\n1;2"));
        assertEquals('\t', CsvReader.detectDelimiter("date\tamount"));
        assertEquals(',', CsvReader.detectDelimiter("date"));
    }

    @Test
    void rejectsMalformedAndOversizedInput() {
        assertEquals("csv_malformed", assertThrows(CsvException.class, () -> parse("\"open;x", ';')).code());
        assertEquals("csv_malformed", assertThrows(CsvException.class, () -> parse("a\"b;c", ';')).code());
        assertEquals("csv_malformed", assertThrows(CsvException.class, () -> parse("\"a\"b;c", ';')).code());
        assertEquals("csv_field_too_long",
                assertThrows(CsvException.class, () -> parse("x".repeat(21), ';')).code());
        assertEquals("csv_too_many_columns",
                assertThrows(CsvException.class, () -> parse("1;2;3;4;5;6", ';')).code());
        assertEquals("csv_too_many_rows",
                assertThrows(CsvException.class, () -> parse("a\n".repeat(11), ';')).code());
        CsvException error = assertThrows(CsvException.class, () -> parse("a;b\nc;\"d", ';'));
        assertEquals(2, error.line());
    }

    @Test
    void decodesUtf8WithBomAndFallsBackToWindows1252() {
        byte[] utf8 = ("\uFEFFcaffè;€").getBytes(StandardCharsets.UTF_8);
        assertEquals("caffè;€", CsvReader.decode(utf8));
        byte[] ansi = "caffè;€".getBytes(Charset.forName("windows-1252"));
        assertEquals("caffè;€", CsvReader.decode(ansi));
        assertEquals("csv_binary", assertThrows(CsvException.class,
                () -> CsvReader.decode(new byte[] {'P', 'K', 3, 4, 0, 0})).code());
    }

    @Test
    void neutralizesFormulasAndQuotesWhenNeeded() {
        CsvWriter csv = new CsvWriter(';');
        csv.textRow(List.of("=HYPERLINK(\"http://x\")", "+1", "-2", "@SUM(A1)", "normal", "a;b", "say \"hi\""));
        csv.row(List.of("-12.50", "=raw"), new boolean[] {false, false});
        List<String> lines = csv.toString().lines().toList();
        assertEquals("\"'=HYPERLINK(\"\"http://x\"\")\";'+1;'-2;'@SUM(A1);normal;\"a;b\";\"say \"\"hi\"\"\"",
                lines.get(0));
        // Values produced by the application (amounts) are written as they are
        assertEquals("-12.50;=raw", lines.get(1));
        assertTrue(csv.toString().endsWith("\r\n"));
        assertEquals("=SUM(A1)", CsvWriter.unneutralize("'=SUM(A1)"));
        assertEquals("'quoted", CsvWriter.unneutralize("'quoted"));
    }

    @Test
    void readsAmountsInCommonFormats() {
        assertEquals(new BigDecimal("1234.50"), EntryCsvFormat.amount("1234.50").orElseThrow());
        assertEquals(new BigDecimal("1234.50"), EntryCsvFormat.amount("1234,50").orElseThrow());
        assertEquals(new BigDecimal("1234.50"), EntryCsvFormat.amount("1'234.50").orElseThrow());
        assertEquals(new BigDecimal("1234.50"), EntryCsvFormat.amount("1.234,50").orElseThrow());
        assertEquals(new BigDecimal("1234.50"), EntryCsvFormat.amount("1,234.50").orElseThrow());
        assertEquals(new BigDecimal("-12.30"), EntryCsvFormat.amount("-12.30").orElseThrow());
        assertEquals(new BigDecimal("-12.30"), EntryCsvFormat.amount("12.30-").orElseThrow());
        assertEquals(new BigDecimal("15"), EntryCsvFormat.amount(" +15 ").orElseThrow());
        for (String bad : List.of("", "abc", "12.3.4", "1,2,3", "12.34567", "1e5", "--1", "12 CHF",
                "1234567890123456", "0x10", "=1+1")) {
            assertEquals(Optional.empty(), EntryCsvFormat.amount(bad), bad);
        }
    }

    @Test
    void readsDatesStrictly() {
        assertEquals(LocalDate.of(2026, 8, 1), EntryCsvFormat.date("2026-08-01").orElseThrow());
        assertEquals(LocalDate.of(2026, 8, 1), EntryCsvFormat.date("01.08.2026").orElseThrow());
        assertEquals(LocalDate.of(2026, 8, 1), EntryCsvFormat.date("1.8.2026").orElseThrow());
        assertEquals(LocalDate.of(2026, 8, 1), EntryCsvFormat.date("01/08/2026").orElseThrow());
        for (String bad : List.of("2026-02-30", "31.04.2026", "2026/08/01", "08-01-2026", "1850-01-01",
                "01.08.26", "yesterday", "")) {
            assertEquals(Optional.empty(), EntryCsvFormat.date(bad), bad);
        }
    }

    @Test
    void matchesHeadersKindsAndCleansText() {
        assertEquals(Optional.of(Column.CURRENCY), EntryCsvFormat.column(" Währung "));
        assertEquals(Optional.of(Column.DESCRIPTION), EntryCsvFormat.column("Descrizione"));
        assertTrue(EntryCsvFormat.column("saldo").isEmpty());
        assertEquals(EntryKind.EXPENSE, EntryCsvFormat.kind("Uscita").orElseThrow());
        assertEquals(EntryKind.INCOME, EntryCsvFormat.kind("income").orElseThrow());
        assertEquals(EntryKind.TRANSFER, EntryCsvFormat.kind("Trasferimento").orElseThrow());
        assertEquals(EntryKind.TRANSFER, EntryCsvFormat.kind("Umbuchung").orElseThrow());
        assertTrue(EntryCsvFormat.kind("rimborso").isEmpty());
        assertEquals(Optional.of(Column.TO), EntryCsvFormat.column("Verso"));
        assertEquals(Optional.of(Column.FROM), EntryCsvFormat.column("from"));
        // Control and bidi-override characters become spaces; the export prefix is removed
        assertEquals("a b c", EntryCsvFormat.text(" a‮b\tc "));
        assertEquals("=1+1", EntryCsvFormat.text("'=1+1"));
    }
}
