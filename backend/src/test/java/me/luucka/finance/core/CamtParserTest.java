package me.luucka.finance.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import me.luucka.finance.core.camt.CamtParser;
import me.luucka.finance.core.camt.CamtParser.Entry;
import me.luucka.finance.core.camt.CamtParser.Statement;
import org.junit.jupiter.api.Test;

public class CamtParserTest {

    /** A camt.053 like the Swiss banks export (version 04), with the cases that matter. */
    public static final String STATEMENT = """
            <?xml version="1.0" encoding="UTF-8"?>
            <Document xmlns="urn:iso:std:iso:20022:tech:xsd:camt.053.001.04">
              <BkToCstmrStmt>
                <GrpHdr><MsgId>MSG-1</MsgId><CreDtTm>2026-10-01T06:00:00</CreDtTm></GrpHdr>
                <Stmt>
                  <Id>STMT-1</Id>
                  <Acct><Id><IBAN>CH93 0076 2011 6238 5295 7</IBAN></Id><Ccy>CHF</Ccy></Acct>
                  <Bal><Tp><CdOrPrtry><Cd>OPBD</Cd></CdOrPrtry></Tp><Amt Ccy="CHF">1000.00</Amt>
                    <CdtDbtInd>CRDT</CdtDbtInd><Dt><Dt>2026-09-01</Dt></Dt></Bal>
                  <Bal><Tp><CdOrPrtry><Cd>CLBD</Cd></CdOrPrtry></Tp><Amt Ccy="CHF">6120.35</Amt>
                    <CdtDbtInd>CRDT</CdtDbtInd><Dt><Dt>2026-09-30</Dt></Dt></Bal>
                  <Ntry>
                    <Amt Ccy="CHF">6000.00</Amt><CdtDbtInd>CRDT</CdtDbtInd><Sts>BOOK</Sts>
                    <BookgDt><Dt>2026-09-25</Dt></BookgDt><ValDt><Dt>2026-09-25</Dt></ValDt>
                    <AddtlNtryInf>Gutschrift</AddtlNtryInf>
                    <NtryDtls><TxDtls>
                      <RltdPties><Dbtr><Nm>ACME AG</Nm></Dbtr></RltdPties>
                      <RmtInf><Ustrd>Lohn September</Ustrd></RmtInf>
                    </TxDtls></NtryDtls>
                  </Ntry>
                  <Ntry>
                    <Amt Ccy="CHF">45.20</Amt><CdtDbtInd>DBIT</CdtDbtInd><Sts>BOOK</Sts>
                    <BookgDt><Dt>2026-09-03</Dt></BookgDt>
                    <AddtlNtryInf>Zahlung Debitkarte 02.09.2026 MIGROS ZUERICH</AddtlNtryInf>
                  </Ntry>
                  <Ntry>
                    <Amt Ccy="CHF">834.45</Amt><CdtDbtInd>DBIT</CdtDbtInd><Sts>BOOK</Sts>
                    <BookgDt><Dt>2026-09-28</Dt></BookgDt>
                    <AddtlNtryInf>Sammelauftrag</AddtlNtryInf>
                    <NtryDtls>
                      <TxDtls><AmtDtls><TxAmt><Amt Ccy="CHF">520.00</Amt></TxAmt></AmtDtls>
                        <RltdPties><Cdtr><Nm>Helsana</Nm></Cdtr></RltdPties>
                        <RmtInf><Ustrd>Praemie Oktober</Ustrd></RmtInf></TxDtls>
                      <TxDtls><AmtDtls><TxAmt><Amt Ccy="CHF">314.45</Amt></TxAmt></AmtDtls>
                        <RltdPties><Cdtr><Nm>EWZ</Nm></Cdtr></RltdPties></TxDtls>
                    </NtryDtls>
                  </Ntry>
                  <Ntry>
                    <Amt Ccy="CHF">99.00</Amt><CdtDbtInd>DBIT</CdtDbtInd><Sts>PDNG</Sts>
                    <BookgDt><Dt>2026-09-30</Dt></BookgDt><AddtlNtryInf>Vormerkung</AddtlNtryInf>
                  </Ntry>
                  <Ntry>
                    <Amt Ccy="CHF">1.00</Amt><CdtDbtInd>DBIT</CdtDbtInd><Sts>INFO</Sts>
                    <BookgDt><Dt>2026-09-30</Dt></BookgDt>
                  </Ntry>
                </Stmt>
              </BkToCstmrStmt>
            </Document>
            """;

    private static List<Statement> parse(String xml) {
        return CamtParser.parse(xml.getBytes(StandardCharsets.UTF_8), 5000, 500);
    }

    @Test
    void readsAccountClosingBalanceAndMovements() {
        List<Statement> statements = parse(STATEMENT);
        assertEquals(1, statements.size());
        Statement statement = statements.getFirst();
        assertEquals("CH9300762011623852957", statement.iban());
        assertEquals("CHF", statement.currency());
        assertEquals(new CamtParser.Balance(LocalDate.of(2026, 9, 30), new BigDecimal("6120.35"), "CHF"),
                statement.closing());

        List<Entry> entries = statement.entries();
        // The information-only booking is left out; the collective one is split in two
        assertEquals(5, entries.size());
        assertEquals(new Entry(LocalDate.of(2026, 9, 25), EntryKind.INCOME, new BigDecimal("6000.00"), "CHF",
                "ACME AG · Lohn September", true), entries.get(0));
        assertEquals(new Entry(LocalDate.of(2026, 9, 3), EntryKind.EXPENSE, new BigDecimal("45.20"), "CHF",
                "Zahlung Debitkarte 02.09.2026 MIGROS ZUERICH", true), entries.get(1));
        assertEquals("Helsana · Praemie Oktober", entries.get(2).description());
        assertEquals(new BigDecimal("520.00"), entries.get(2).amount());
        assertEquals("EWZ", entries.get(3).description());
        assertEquals(new BigDecimal("314.45"), entries.get(3).amount());
        assertFalse(entries.get(4).booked());
    }

    @Test
    void aCollectiveBookingStaysWholeWhenItsTransactionsDoNotAddUp() {
        String xml = STATEMENT.replace("<Amt Ccy=\"CHF\">314.45</Amt>", "<Amt Ccy=\"CHF\">300.00</Amt>");
        List<Entry> entries = parse(xml).getFirst().entries();
        assertEquals(4, entries.size());
        assertEquals(new BigDecimal("834.45"), entries.get(2).amount());
        assertEquals("Sammelauftrag", entries.get(2).description());
    }

    @Test
    void readsNewerVersionsAndTheOtherReports() {
        // camt.054 version 08: parties under Pty, date and time, status as a code
        String xml = """
                <Document xmlns="urn:iso:std:iso:20022:tech:xsd:camt.054.001.08">
                  <BkToCstmrDbtCdtNtfctn><Ntfctn>
                    <Acct><Id><Othr><Id>12345</Id></Othr></Id></Acct>
                    <Ntry><Amt Ccy="EUR">12.5</Amt><CdtDbtInd>DBIT</CdtDbtInd><Sts><Cd>BOOK</Cd></Sts>
                      <BookgDt><DtTm>2026-09-05T10:15:00+02:00</DtTm></BookgDt>
                      <NtryDtls><TxDtls><RltdPties><Cdtr><Pty><Nm>Café   du\tLac</Nm></Pty></Cdtr></RltdPties>
                      </TxDtls></NtryDtls></Ntry>
                  </Ntfctn></BkToCstmrDbtCdtNtfctn>
                </Document>
                """;
        Statement statement = parse(xml).getFirst();
        assertNull(statement.iban());
        assertNull(statement.closing());
        assertEquals(new Entry(LocalDate.of(2026, 9, 5), EntryKind.EXPENSE, new BigDecimal("12.5"), "EUR",
                "Café du Lac", true), statement.entries().getFirst());

        String report = STATEMENT.replace("BkToCstmrStmt", "BkToCstmrAcctRpt").replace("<Stmt>", "<Rpt>")
                .replace("</Stmt>", "</Rpt>");
        assertEquals(5, parse(report).getFirst().entries().size());
    }

    @Test
    void unreadableValuesAreLeftForTheReview() {
        String xml = STATEMENT.replace("<Amt Ccy=\"CHF\">45.20</Amt><CdtDbtInd>DBIT</CdtDbtInd>",
                        "<Amt Ccy=\"CHF\">-4x</Amt>")
                .replace("<BookgDt><Dt>2026-09-03</Dt></BookgDt>", "");
        Entry entry = parse(xml).getFirst().entries().get(1);
        assertNull(entry.amount());
        assertNull(entry.kind());
        assertNull(entry.date());
    }

    @Test
    void refusesWhatIsNotAStatement() {
        assertEquals("camt_invalid", code("<Document><unclosed></Document>"));
        assertEquals("camt_unsupported", code("<html><body/></html>"));
        assertEquals("camt_unsupported", code("""
                <Document xmlns="urn:iso:std:iso:20022:tech:xsd:pain.001.001.09"><CstmrCdtTrfInitn/></Document>"""));
        assertEquals("camt_empty", code("""
                <Document><BkToCstmrStmt><Stmt><Acct/></Stmt></BkToCstmrStmt></Document>"""));
        assertEquals("camt_too_many_rows", assertThrows(CamtParser.CamtException.class,
                () -> CamtParser.parse(STATEMENT.getBytes(StandardCharsets.UTF_8), 3, 500)).code());
    }

    @Test
    void neverReadsADtdOrExternalEntities() {
        // XXE and entity expansion ("billion laughs") are refused before anything is resolved
        assertEquals("camt_invalid", code("""
                <?xml version="1.0"?>
                <!DOCTYPE Document [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                <Document><BkToCstmrStmt><Stmt><Ntry><AddtlNtryInf>&xxe;</AddtlNtryInf></Ntry></Stmt></BkToCstmrStmt></Document>"""));
        assertEquals("camt_invalid", code("""
                <?xml version="1.0"?>
                <!DOCTYPE lolz [<!ENTITY lol "lol"><!ENTITY lol2 "&lol;&lol;&lol;&lol;">]>
                <Document>&lol2;</Document>"""));
    }

    @Test
    void cutsLongDescriptionsAndDropsControlCharacters() {
        String xml = STATEMENT.replace("Zahlung Debitkarte 02.09.2026 MIGROS ZUERICH", "A\u202Eb\tc\n " + "x".repeat(600));
        String description = parse(xml).getFirst().entries().get(1).description();
        assertEquals(500, description.length());
        assertTrue(description.startsWith("A b c xxx"));
    }

    @Test
    void tellsXmlFromCsv() {
        assertTrue(CamtParser.looksLikeXml("\uFEFF  \n<?xml version=\"1.0\"?><a/>".getBytes(StandardCharsets.UTF_8)));
        assertFalse(CamtParser.looksLikeXml("data;importo\n2026-01-01;5".getBytes(StandardCharsets.UTF_8)));
        assertFalse(CamtParser.looksLikeXml(new byte[0]));
    }

    @Test
    void ibansAreNormalizedAndChecked() {
        assertEquals(Optional.of("CH9300762011623852957"), Iban.normalize(" ch93 0076 2011 6238 5295 7 "));
        assertEquals(Optional.of("DE89370400440532013000"), Iban.normalize("DE89 3704 0044 0532 0130 00"));
        assertEquals(Optional.empty(), Iban.normalize("CH9300762011623852958"));
        assertEquals(Optional.empty(), Iban.normalize("not an iban"));
        assertEquals(Optional.empty(), Iban.normalize(null));
    }

    private static String code(String xml) {
        return assertThrows(CamtParser.CamtException.class, () -> parse(xml)).code();
    }
}
