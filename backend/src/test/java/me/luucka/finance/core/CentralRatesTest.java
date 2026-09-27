package me.luucka.finance.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import me.luucka.finance.core.fx.CentralRates;
import me.luucka.finance.core.fx.EcbXmlParser;
import me.luucka.finance.core.fx.FxTable;
import me.luucka.finance.core.fx.FxTable.Source;
import org.junit.jupiter.api.Test;

class CentralRatesTest {

    private static final LocalDate D1 = LocalDate.of(2026, 9, 24);
    private static final LocalDate D2 = LocalDate.of(2026, 9, 25);

    /** 1 EUR = rate X, as published by the ECB. */
    private static final CentralRates ECB = CentralRates.builder("EUR")
            .put("CHF", D1, new BigDecimal("0.9300"))
            .put("USD", D1, new BigDecimal("1.1650"))
            .put("CHF", D2, new BigDecimal("0.9321"))
            .put("USD", D2, new BigDecimal("1.1702"))
            .build();

    private static void assertRate(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual.setScale(10, java.math.RoundingMode.HALF_EVEN)),
                () -> "expected " + expected + " but was " + actual);
    }

    @Test
    void convertsFromAndToTheAnchor() {
        assertRate("0.9321", ECB.onOrBefore("EUR", "CHF", D2).orElseThrow().rate());
        // 1 CHF = 1 / 0.9321 EUR
        assertRate("1.0728462611", ECB.onOrBefore("CHF", "EUR", D2).orElseThrow().rate());
    }

    @Test
    void derivesCrossRatesThroughTheAnchor() {
        // 1 USD = 0.9321 / 1.1702 CHF
        CentralRates.Quote quote = ECB.onOrBefore("USD", "CHF", D2).orElseThrow();
        assertRate("0.7965305076", quote.rate());
        assertEquals(D2, quote.date());
    }

    @Test
    void usesTheLatestPublicationOnOrBeforeTheDate() {
        assertRate("0.9300", ECB.onOrBefore("EUR", "CHF", D1).orElseThrow().rate());
        // Weekend: Friday's rate
        assertEquals(D2, ECB.onOrBefore("EUR", "CHF", D2.plusDays(2)).orElseThrow().date());
        assertTrue(ECB.onOrBefore("EUR", "CHF", D1.minusDays(1)).isEmpty());
        assertRate("0.9300", ECB.earliest("EUR", "CHF").orElseThrow().rate());
    }

    @Test
    void unknownCurrenciesAndEmptyRatesCannotConvert() {
        assertTrue(ECB.onOrBefore("XAU", "CHF", D2).isEmpty());
        assertTrue(CentralRates.EMPTY.onOrBefore("EUR", "CHF", D2).isEmpty());
        assertTrue(CentralRates.EMPTY.onOrBefore("CHF", "EUR", D2).isEmpty());
        assertEquals(Set.of("CHF", "EUR", "USD"), ECB.currencies());
        assertEquals(D2, ECB.latestDate().orElseThrow());
        assertTrue(CentralRates.EMPTY.latestDate().isEmpty());
    }

    @Test
    void manualRatesTakePriorityOverCentralRates() {
        FxTable fx = new FxTable("CHF", ECB).put("USD", D1, new BigDecimal("0.80"));

        // A manual USD rate exists on or before the date: it wins over the ECB
        FxTable.Quote usd = fx.quote("USD", D2).orElseThrow();
        assertEquals(Source.MANUAL, usd.source());
        assertEquals(new BigDecimal("0.80"), usd.rate());
        // No manual EUR rate: ECB
        FxTable.Quote eur = fx.quote("EUR", D2).orElseThrow();
        assertEquals(Source.CENTRAL, eur.source());
        assertRate("0.9321", eur.rate());
        assertEquals(Source.BASE, fx.quote("CHF", D2).orElseThrow().source());
    }

    @Test
    void centralRatesCoverDatesBeforeTheFirstManualRate() {
        FxTable fx = new FxTable("CHF", ECB).put("USD", D2, new BigDecimal("0.80"));

        FxTable.Quote before = fx.quote("USD", D1).orElseThrow();
        assertEquals(Source.CENTRAL, before.source());
        assertRate("0.7982832618", before.rate());
        // Before any rate at all: earliest manual first, then earliest central
        assertEquals(Source.MANUAL, fx.quote("USD", LocalDate.of(2000, 1, 1)).orElseThrow().source());
        assertEquals(Source.CENTRAL, fx.quote("EUR", LocalDate.of(2000, 1, 1)).orElseThrow().source());
    }

    @Test
    void baseCurrencyEuroUsesTheEcbRatesDirectly() {
        FxTable fx = new FxTable("EUR", ECB);
        assertRate("0.8545547770", fx.rate("USD", D2).orElseThrow());
        assertTrue(fx.rate("GBP", D2).isEmpty());
    }

    @Test
    void parsesTheEcbFeed() throws Exception {
        List<EcbXmlParser.Rate> rates;
        try (InputStream in = getClass().getResourceAsStream("/ecb/eurofxref-hist-sample.xml")) {
            rates = EcbXmlParser.parse(in);
        }
        assertEquals(8, rates.size());
        assertEquals(new EcbXmlParser.Rate(D2, "USD", new BigDecimal("1.1702")), rates.getFirst());
        assertEquals(new EcbXmlParser.Rate(D1, "CHF", new BigDecimal("0.9300")), rates.getLast());
    }

    private static List<EcbXmlParser.Rate> parse(String xml) {
        return EcbXmlParser.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void rejectsExternalEntities() {
        String xxe = """
                <?xml version="1.0"?>
                <!DOCTYPE r [<!ENTITY x SYSTEM "file:///etc/passwd">]>
                <Cube><Cube time="2026-09-25"><Cube currency="&x;" rate="1"/></Cube></Cube>""";
        assertThrows(IllegalArgumentException.class, () -> parse(xxe));
    }

    @Test
    void rejectsMalformedFeeds() {
        String dated = "<Cube><Cube time=\"2026-09-25\">%s</Cube></Cube>";
        assertThrows(IllegalArgumentException.class, () -> parse(dated.formatted("<Cube currency=\"usd\" rate=\"1\"/>")));
        assertThrows(IllegalArgumentException.class, () -> parse(dated.formatted("<Cube currency=\"USD\" rate=\"-1\"/>")));
        assertThrows(IllegalArgumentException.class, () -> parse(dated.formatted("<Cube currency=\"USD\" rate=\"abc\"/>")));
        assertThrows(IllegalArgumentException.class, () -> parse(dated.formatted("<Cube currency=\"USD\"/>")));
        assertThrows(IllegalArgumentException.class, () -> parse(dated.formatted("")));
        assertThrows(IllegalArgumentException.class,
                () -> parse("<Cube><Cube time=\"1990-01-01\"><Cube currency=\"USD\" rate=\"1\"/></Cube></Cube>"));
        assertThrows(IllegalArgumentException.class, () -> parse("<Cube><Cube currency=\"USD\" rate=\"1\"/></Cube>"));
        assertThrows(IllegalArgumentException.class, () -> parse("<html>Service unavailable"));
    }
}
