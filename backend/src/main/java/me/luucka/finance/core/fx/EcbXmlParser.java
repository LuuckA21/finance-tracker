package me.luucka.finance.core.fx;

import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * Parses the ECB euro foreign exchange reference rates feed
 * ({@code eurofxref-daily.xml}, {@code eurofxref-hist-90d.xml}, {@code eurofxref-hist.xml}):
 * <pre>{@code
 * <Cube>
 *   <Cube time="2026-09-25">
 *     <Cube currency="USD" rate="1.1702"/>
 *     ...
 * }</pre>
 * The reader refuses DTDs and external entities (no XXE), and any malformed rate rejects the
 * whole document so that a broken download never stores partial or wrong data.
 */
public final class EcbXmlParser {

    /** {@code 1 EUR = rate currency} on {@code date}. */
    public record Rate(LocalDate date, String currency, BigDecimal rate) {
    }

    private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");
    private static final LocalDate FIRST_PUBLICATION = LocalDate.of(1999, 1, 1);
    /** Column limits of the rate tables: NUMERIC(28, 12). */
    private static final int MAX_INTEGER_DIGITS = 16;
    private static final int MAX_FRACTION_DIGITS = 12;

    private EcbXmlParser() {
    }

    /**
     * @throws IllegalArgumentException when the document is not a valid ECB feed or has no rates
     */
    public static List<Rate> parse(InputStream in) {
        List<Rate> rates = new ArrayList<>();
        XMLStreamReader reader = null;
        try {
            reader = factory().createXMLStreamReader(in);
            LocalDate date = null;
            while (reader.hasNext()) {
                if (reader.next() != XMLStreamConstants.START_ELEMENT || !"Cube".equals(reader.getLocalName())) {
                    continue;
                }
                String time = reader.getAttributeValue(null, "time");
                String currency = reader.getAttributeValue(null, "currency");
                String rate = reader.getAttributeValue(null, "rate");
                if (time != null) {
                    date = parseDate(time);
                } else if (currency != null || rate != null) {
                    if (date == null) {
                        throw new IllegalArgumentException("ECB rate outside a dated Cube");
                    }
                    rates.add(new Rate(date, parseCurrency(currency), parseRate(rate)));
                }
            }
        } catch (XMLStreamException e) {
            throw new IllegalArgumentException("Malformed ECB feed: " + e.getMessage(), e);
        } finally {
            close(reader);
        }
        if (rates.isEmpty()) {
            throw new IllegalArgumentException("ECB feed contains no rates");
        }
        return rates;
    }

    private static XMLInputFactory factory() {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        return factory;
    }

    private static LocalDate parseDate(String value) {
        try {
            LocalDate date = LocalDate.parse(value);
            if (date.isBefore(FIRST_PUBLICATION) || date.isAfter(LocalDate.now().plusDays(7))) {
                throw new IllegalArgumentException("ECB date out of range: " + value);
            }
            return date;
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Invalid ECB date: " + value, e);
        }
    }

    private static String parseCurrency(String value) {
        if (value == null || !CURRENCY.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid ECB currency: " + value);
        }
        return value;
    }

    private static BigDecimal parseRate(String value) {
        BigDecimal rate;
        try {
            rate = new BigDecimal(value == null ? "" : value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid ECB rate: " + value, e);
        }
        if (rate.signum() <= 0 || rate.scale() > MAX_FRACTION_DIGITS
                || rate.precision() - rate.scale() > MAX_INTEGER_DIGITS) {
            throw new IllegalArgumentException("Invalid ECB rate: " + value);
        }
        return rate;
    }

    private static void close(XMLStreamReader reader) {
        if (reader != null) {
            try {
                reader.close();
            } catch (XMLStreamException ignored) {
                // Nothing left to release
            }
        }
    }
}
