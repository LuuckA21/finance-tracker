package me.luucka.finance.core.csv;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import me.luucka.finance.core.EntryKind;

/**
 * Columns and value formats of the income/expense CSV, shared by export and import.
 * <p>
 * Import is lenient where spreadsheets and banks differ (Italian, English, German or French headers,
 * {@code ;} or {@code ,}, decimal comma, Swiss apostrophes, dd.MM.yyyy dates) and strict about
 * everything else: a value that cannot be read unambiguously is reported, never guessed.
 */
public final class EntryCsvFormat {

    /**
     * {@code CATEGORY}: the macro category, {@code SUBCATEGORY}: the detail under it, optional.
     * {@code FROM}/{@code TO}: position names of a transfer, both optional. {@code TAGS}: tag names
     * separated by commas, optional.
     */
    public enum Column { DATE, KIND, CATEGORY, SUBCATEGORY, AMOUNT, CURRENCY, DESCRIPTION, FROM, TO, TAGS }

    /** Export headers per interface language. */
    public static final Map<Locale, List<String>> HEADERS = Map.of(
            Locale.ITALIAN, List.of("data", "tipo", "categoria", "sottocategoria", "importo", "valuta", "descrizione",
                    "da", "verso", "etichette"),
            Locale.ENGLISH, List.of("date", "type", "category", "subcategory", "amount", "currency", "description",
                    "from", "to", "tags"),
            Locale.GERMAN, List.of("datum", "art", "kategorie", "unterkategorie", "betrag", "währung", "beschreibung",
                    "von", "nach", "tags"),
            Locale.FRENCH, List.of("date", "type", "catégorie", "sous-catégorie", "montant", "monnaie", "description",
                    "de", "vers", "étiquettes"));

    private static final Map<Column, List<String>> ALIASES = new EnumMap<>(Map.of(
            Column.DATE, List.of("data", "date", "datum", "giorno", "day"),
            Column.KIND, List.of("tipo", "type", "kind", "typ", "art"),
            Column.CATEGORY, List.of("categoria", "category", "kategorie", "categorie"),
            Column.SUBCATEGORY, List.of("sottocategoria", "subcategory", "sub-category", "unterkategorie",
                    "sous-categorie", "souscategorie", "dettaglio", "detail", "detail category"),
            Column.AMOUNT, List.of("importo", "amount", "betrag", "valore", "value", "somma", "montant"),
            Column.CURRENCY, List.of("valuta", "currency", "wahrung", "waehrung", "divisa", "monnaie", "devise"),
            Column.DESCRIPTION, List.of("descrizione", "description", "beschreibung", "note", "nota", "notes",
                    "causale", "memo", "buchungstext", "libelle"),
            Column.FROM, List.of("da", "from", "von", "de", "origine", "source"),
            Column.TO, List.of("verso", "a", "to", "nach", "vers", "destinazione", "destination"),
            Column.TAGS, List.of("etichette", "etichetta", "tag", "tags", "label", "labels", "etiquettes",
                    "etiquette", "schlagworter", "stichworter")));

    private static final Map<String, EntryKind> KINDS = Map.ofEntries(
            Map.entry("entrata", EntryKind.INCOME), Map.entry("entrate", EntryKind.INCOME),
            Map.entry("income", EntryKind.INCOME), Map.entry("einnahme", EntryKind.INCOME),
            Map.entry("einnahmen", EntryKind.INCOME), Map.entry("revenu", EntryKind.INCOME),
            Map.entry("revenus", EntryKind.INCOME),
            Map.entry("in", EntryKind.INCOME), Map.entry("+", EntryKind.INCOME),
            Map.entry("uscita", EntryKind.EXPENSE), Map.entry("uscite", EntryKind.EXPENSE),
            Map.entry("spesa", EntryKind.EXPENSE), Map.entry("expense", EntryKind.EXPENSE),
            Map.entry("expenses", EntryKind.EXPENSE), Map.entry("ausgabe", EntryKind.EXPENSE),
            Map.entry("ausgaben", EntryKind.EXPENSE), Map.entry("depense", EntryKind.EXPENSE),
            Map.entry("depenses", EntryKind.EXPENSE), Map.entry("out", EntryKind.EXPENSE),
            Map.entry("-", EntryKind.EXPENSE),
            Map.entry("trasferimento", EntryKind.TRANSFER), Map.entry("trasferimenti", EntryKind.TRANSFER),
            Map.entry("transfer", EntryKind.TRANSFER), Map.entry("umbuchung", EntryKind.TRANSFER),
            Map.entry("giroconto", EntryKind.TRANSFER), Map.entry("umbuchungen", EntryKind.TRANSFER),
            Map.entry("virement", EntryKind.TRANSFER), Map.entry("virements", EntryKind.TRANSFER),
            Map.entry("transfert", EntryKind.TRANSFER));

    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT),
            DateTimeFormatter.ofPattern("d.M.uuuu").withResolverStyle(ResolverStyle.STRICT),
            DateTimeFormatter.ofPattern("d/M/uuuu").withResolverStyle(ResolverStyle.STRICT));

    private static final Pattern AMOUNT_CHARS = Pattern.compile("[0-9.,]+");
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cc}\\p{Cf}]");
    private static final Pattern PATH = Pattern.compile("\\s*([^›>]*[^›>\\s])\\s*[›>]\\s*([^›>]*[^›>\\s])\\s*");

    public static final LocalDate MIN_DATE = LocalDate.of(1900, 1, 1);
    public static final LocalDate MAX_DATE = LocalDate.of(2199, 12, 31);
    public static final int MAX_DESCRIPTION = 500;

    private EntryCsvFormat() {
    }

    /** Headers of the export in the user's language (Italian for an unknown one). */
    public static List<String> headers(Locale language) {
        return HEADERS.getOrDefault(language, HEADERS.get(Locale.ITALIAN));
    }

    /** The column a header names, ignoring case, accents and surrounding spaces. */
    public static Optional<Column> column(String header) {
        String key = fold(header);
        for (Map.Entry<Column, List<String>> entry : ALIASES.entrySet()) {
            if (entry.getValue().contains(key)) {
                return Optional.of(entry.getKey());
            }
        }
        return Optional.empty();
    }

    /** Kind word in Italian, English, German or French, or a sign; empty if unknown. */
    public static Optional<EntryKind> kind(String value) {
        return Optional.ofNullable(KINDS.get(fold(value)));
    }

    /** Export label of a kind in the user's language. */
    public static String kindLabel(EntryKind kind, Locale language) {
        List<String> labels = switch (language.getLanguage()) {
            case "en" -> List.of("Income", "Expense", "Transfer");
            case "de" -> List.of("Einnahme", "Ausgabe", "Umbuchung");
            case "fr" -> List.of("Revenu", "Dépense", "Virement");
            default -> List.of("Entrata", "Uscita", "Trasferimento");
        };
        return switch (kind) {
            case INCOME -> labels.get(0);
            case EXPENSE -> labels.get(1);
            case TRANSFER -> labels.get(2);
        };
    }

    /**
     * ISO {@code 2026-08-01}, {@code 01.08.2026} or {@code 01/08/2026} (day first), 1900–2199.
     */
    public static Optional<LocalDate> date(String value) {
        String text = value.strip();
        for (DateTimeFormatter format : DATE_FORMATS) {
            try {
                LocalDate date = LocalDate.parse(text, format);
                return date.isBefore(MIN_DATE) || date.isAfter(MAX_DATE) ? Optional.empty() : Optional.of(date);
            } catch (DateTimeParseException ignored) {
                // try the next format
            }
        }
        return Optional.empty();
    }

    /**
     * A signed amount: {@code 1234.50}, {@code 1234,50}, {@code 1'234.50}, {@code 1.234,50},
     * {@code -12.30}, {@code 12.30-}. With both {@code .} and {@code ,} the last one is the
     * decimal separator; a single separator is always decimal. At most 15 integer and 4 decimal
     * digits.
     */
    public static Optional<BigDecimal> amount(String value) {
        String text = value.strip().replace("'", "").replace("’", "").replace(" ", "").replace("\u00a0", "");
        boolean negative = false;
        if (text.startsWith("-") || text.startsWith("+")) {
            negative = text.startsWith("-");
            text = text.substring(1);
        } else if (text.endsWith("-")) {
            negative = true;
            text = text.substring(0, text.length() - 1);
        }
        if (text.isEmpty() || !AMOUNT_CHARS.matcher(text).matches()) {
            return Optional.empty();
        }
        int decimal = Math.max(text.lastIndexOf('.'), text.lastIndexOf(','));
        String integer = decimal < 0 ? text : text.substring(0, decimal);
        String fraction = decimal < 0 ? "" : text.substring(decimal + 1);
        char decimalChar = decimal < 0 ? 0 : text.charAt(decimal);
        // Thousands separators: only the other character, never the decimal one twice
        if (integer.indexOf(decimalChar) >= 0 && decimalChar != 0) {
            return Optional.empty();
        }
        integer = integer.replace(".", "").replace(",", "");
        if (integer.isEmpty() || fraction.length() > 4 || integer.length() > 15 || !fraction.matches("[0-9]*")) {
            return Optional.empty();
        }
        BigDecimal amount = new BigDecimal(fraction.isEmpty() ? integer : integer + "." + fraction);
        return Optional.of(negative ? amount.negate() : amount);
    }

    /** Trimmed, single-line-safe text: control characters become spaces, export prefix removed. */
    public static String text(String value) {
        return CONTROL.matcher(CsvWriter.unneutralize(value.strip())).replaceAll(" ").strip();
    }

    /** Lower case without accents or surrounding spaces, for matching names and headers. */
    /** A category cell naming a macro and a detail: {@code Casa › Affitto} or {@code Casa > Affitto}. */
    public record CategoryPath(String macro, String detail) {
    }

    /** The macro and detail of a category cell with a path, empty for a plain name. */
    public static Optional<CategoryPath> categoryPath(String value) {
        Matcher m = PATH.matcher(value);
        return m.matches() ? Optional.of(new CategoryPath(m.group(1).strip(), m.group(2).strip())) : Optional.empty();
    }

    public static String fold(String value) {
        String decomposed = Normalizer.normalize(value.strip(), Normalizer.Form.NFD);
        return decomposed.replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
    }
}
