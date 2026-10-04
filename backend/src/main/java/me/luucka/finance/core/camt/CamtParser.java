package me.luucka.finance.core.camt;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import me.luucka.finance.core.EntryKind;
import me.luucka.finance.core.Iban;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

/**
 * Bank statements in the ISO 20022 cash management format the Swiss banks export: camt.053
 * (account statement), and camt.052 (intraday report) and camt.054 (debit/credit notification),
 * which share its layout. Any version: elements are matched by name, not by namespace.
 * <p>
 * Every booking becomes an income (credit) or an expense (debit). A collective booking whose
 * transactions carry their own amounts (a batch of card payments, of transfers) is split into them
 * when they add up to the booking. The description is the counterparty and the payment's message,
 * else the bank's text. The XML is read without DTDs or external entities.
 */
public final class CamtParser {

    /** A booked balance of the account, signed (negative when overdrawn). */
    public record Balance(LocalDate date, BigDecimal amount, String currency) {
    }

    /**
     * One movement. {@code kind} is null when the file does not say credit or debit, {@code date}
     * and {@code amount} when unreadable; {@code booked} is false for pending bookings.
     */
    public record Entry(LocalDate date, EntryKind kind, BigDecimal amount, String currency, String description,
                        boolean booked) {
    }

    /** One account in the file: its IBAN (when it has one), currency, closing balance and movements. */
    public record Statement(String iban, String currency, Balance closing, List<Entry> entries) {
    }

    /** Why a file cannot be read as a bank statement. */
    public static final class CamtException extends RuntimeException {

        private final String code;

        CamtException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    private static final Pattern CONTROL = Pattern.compile("[\\p{Cc}\\p{Cf}]");
    private static final Pattern SPACES = Pattern.compile("\\s+");

    private CamtParser() {
    }

    /** Whether the content is XML rather than CSV: its first character (after a BOM) is {@code <}. */
    public static boolean looksLikeXml(byte[] content) {
        int i = content.length >= 3 && (content[0] & 0xFF) == 0xEF && (content[1] & 0xFF) == 0xBB
                && (content[2] & 0xFF) == 0xBF ? 3 : 0;
        while (i < content.length && Character.isWhitespace(content[i])) {
            i++;
        }
        return i < content.length && content[i] == '<';
    }

    /**
     * @param maxEntries most movements accepted in all
     * @param maxDescription longest description kept (longer ones are cut)
     * @throws CamtException {@code camt_invalid} for malformed XML or a DTD, {@code camt_unsupported}
     *                       for another kind of document, {@code camt_empty} without movements,
     *                       {@code camt_too_many_rows} beyond {@code maxEntries}
     */
    public static List<Statement> parse(byte[] content, int maxEntries, int maxDescription) {
        Element root = read(content).getDocumentElement();
        if (!"Document".equals(root.getLocalName())) {
            throw new CamtException("camt_unsupported", "Not an ISO 20022 document");
        }
        Element message = firstChild(root);
        String reportName = message == null ? "" : switch (message.getLocalName()) {
            case "BkToCstmrStmt" -> "Stmt";
            case "BkToCstmrAcctRpt" -> "Rpt";
            case "BkToCstmrDbtCdtNtfctn" -> "Ntfctn";
            default -> "";
        };
        if (reportName.isEmpty()) {
            throw new CamtException("camt_unsupported", "Not a bank statement (camt.052, camt.053 or camt.054)");
        }
        List<Statement> statements = new ArrayList<>();
        int count = 0;
        for (Element report : children(message, reportName)) {
            Statement statement = statement(report, maxDescription);
            count += statement.entries().size();
            if (count > maxEntries) {
                throw new CamtException("camt_too_many_rows", "More than " + maxEntries + " movements");
            }
            statements.add(statement);
        }
        if (count == 0) {
            throw new CamtException("camt_empty", "The statement has no movements");
        }
        return statements;
    }

    private static Statement statement(Element report, int maxDescription) {
        Element account = child(report, "Acct");
        String iban = Iban.normalize(text(account, "Id", "IBAN")).orElse(null);
        String currency = upper(text(account, "Ccy"));
        Balance closing = null;
        for (Element balance : children(report, "Bal")) {
            if ("CLBD".equals(text(balance, "Tp", "CdOrPrtry", "Cd"))) {
                closing = balance(balance);
            }
        }
        List<Entry> entries = new ArrayList<>();
        for (Element entry : children(report, "Ntry")) {
            String status = Optional.ofNullable(text(entry, "Sts", "Cd")).orElse(text(entry, "Sts"));
            // Information only: not a movement of the account
            if ("INFO".equals(status)) {
                continue;
            }
            entries.addAll(entries(entry, status == null || status.equals("BOOK"), maxDescription));
        }
        return new Statement(iban, currency, closing, entries);
    }

    private static Balance balance(Element balance) {
        BigDecimal amount = amount(child(balance, "Amt"));
        LocalDate date = date(child(balance, "Dt"));
        if (amount == null || date == null) {
            return null;
        }
        return new Balance(date, "DBIT".equals(text(balance, "CdtDbtInd")) ? amount.negate() : amount,
                upper(attribute(child(balance, "Amt"), "Ccy")));
    }

    /** The movements of a booking: the booking itself, or its transactions when they add up to it. */
    private static List<Entry> entries(Element entry, boolean booked, int maxDescription) {
        Element amountElement = child(entry, "Amt");
        BigDecimal amount = amount(amountElement);
        String currency = upper(attribute(amountElement, "Ccy"));
        EntryKind kind = kind(text(entry, "CdtDbtInd"));
        LocalDate date = date(child(entry, "BookgDt"));
        if (date == null) {
            date = date(child(entry, "ValDt"));
        }
        String bookingText = text(entry, "AddtlNtryInf");

        List<Element> transactions = new ArrayList<>();
        for (Element details : children(entry, "NtryDtls")) {
            transactions.addAll(children(details, "TxDtls"));
        }
        if (transactions.size() > 1 && amount != null && kind != null) {
            List<Entry> split = split(transactions, date, kind, amount, currency, booked, bookingText, maxDescription);
            if (!split.isEmpty()) {
                return split;
            }
        }
        String description = transactions.size() == 1 || (bookingText == null && !transactions.isEmpty())
                ? description(transactions.getFirst(), kind, bookingText)
                : bookingText;
        return List.of(new Entry(date, kind, amount, currency, clean(description, maxDescription), booked));
    }

    /** One movement per transaction, or none when their amounts do not make up the booking. */
    private static List<Entry> split(List<Element> transactions, LocalDate date, EntryKind kind, BigDecimal amount,
                                     String currency, boolean booked, String bookingText, int maxDescription) {
        List<Entry> result = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (Element tx : transactions) {
            Element txAmount = Optional.ofNullable(child(tx, "Amt")).orElse(child(tx, "AmtDtls", "TxAmt", "Amt"));
            BigDecimal value = amount(txAmount);
            if (value == null || currency == null || !currency.equals(upper(attribute(txAmount, "Ccy")))) {
                return List.of();
            }
            EntryKind txKind = Optional.ofNullable(kind(text(tx, "CdtDbtInd"))).orElse(kind);
            total = txKind == kind ? total.add(value) : total.subtract(value);
            result.add(new Entry(date, txKind, value, currency,
                    clean(description(tx, txKind, bookingText), maxDescription), booked));
        }
        return total.compareTo(amount) == 0 ? result : List.of();
    }

    /**
     * The counterparty (who was paid, or who paid) and the payment's message; else the
     * transaction's or the booking's own text.
     */
    private static String description(Element tx, EntryKind kind, String bookingText) {
        String partyRole = kind == EntryKind.INCOME ? "Dbtr" : "Cdtr";
        String party = Optional.ofNullable(text(tx, "RltdPties", partyRole, "Nm"))
                .orElse(text(tx, "RltdPties", partyRole, "Pty", "Nm"));
        List<String> lines = new ArrayList<>();
        Element remittance = child(tx, "RmtInf");
        if (remittance != null) {
            for (Element line : children(remittance, "Ustrd")) {
                lines.add(line.getTextContent());
            }
        }
        String message = String.join(" ", lines).strip();
        String text = party == null ? message
                : message.isEmpty() || message.equalsIgnoreCase(party) ? party
                : party + " · " + message;
        if (text.isBlank()) {
            text = Optional.ofNullable(text(tx, "AddtlTxInf")).orElse(bookingText);
        }
        return text;
    }

    // ------------------------------------------------------------------ values

    private static EntryKind kind(String indicator) {
        return "CRDT".equals(indicator) ? EntryKind.INCOME : "DBIT".equals(indicator) ? EntryKind.EXPENSE : null;
    }

    private static BigDecimal amount(Element element) {
        if (element == null) {
            return null;
        }
        try {
            BigDecimal value = new BigDecimal(element.getTextContent().strip());
            return value.signum() > 0 && value.scale() <= 4 && value.precision() - value.scale() <= 15 ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** {@code <Dt>} or {@code <DtTm>} inside a date element: the day. */
    private static LocalDate date(Element element) {
        String value = Optional.ofNullable(text(element, "Dt")).orElse(text(element, "DtTm"));
        if (value == null || value.length() < 10) {
            return null;
        }
        try {
            return LocalDate.parse(value.substring(0, 10));
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** Single-line text without control characters, cut to {@code max}; null when empty. */
    private static String clean(String value, int max) {
        if (value == null) {
            return null;
        }
        String text = SPACES.matcher(CONTROL.matcher(value).replaceAll(" ")).replaceAll(" ").strip();
        if (text.length() > max) {
            text = text.substring(0, max).strip();
        }
        return text.isEmpty() ? null : text;
    }

    private static String upper(String value) {
        return value == null ? null : value.toUpperCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------ XML

    private static Document read(byte[] content) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            // No DTD at all: no entity expansion, no external files or URLs
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setErrorHandler(SILENT);
            return builder.parse(new ByteArrayInputStream(content));
        } catch (SAXException | IOException e) {
            throw new CamtException("camt_invalid", "The file is not valid XML");
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Parse errors become the exception above instead of lines on the console. */
    private static final ErrorHandler SILENT = new ErrorHandler() {
        @Override
        public void warning(SAXParseException e) {
        }

        @Override
        public void error(SAXParseException e) throws SAXException {
            throw e;
        }

        @Override
        public void fatalError(SAXParseException e) throws SAXException {
            throw e;
        }
    };

    private static Element firstChild(Element parent) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e) {
                return e;
            }
        }
        return null;
    }

    private static List<Element> children(Element parent, String name) {
        List<Element> result = new ArrayList<>();
        if (parent == null) {
            return result;
        }
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && name.equals(e.getLocalName())) {
                result.add(e);
            }
        }
        return result;
    }

    /** The first element down the path of child names, or null. */
    private static Element child(Element parent, String... path) {
        Element current = parent;
        for (String name : path) {
            if (current == null) {
                return null;
            }
            List<Element> found = children(current, name);
            current = found.isEmpty() ? null : found.getFirst();
        }
        return current;
    }

    /** The trimmed text at the path, or null when missing or blank. */
    private static String text(Element parent, String... path) {
        Element element = child(parent, path);
        if (element == null) {
            return null;
        }
        String value = element.getTextContent().strip();
        return value.isEmpty() ? null : value;
    }

    private static String attribute(Element element, String name) {
        if (element == null || !element.hasAttribute(name)) {
            return null;
        }
        return element.getAttribute(name).strip();
    }
}
