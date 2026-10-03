package me.luucka.finance.cashflow;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import me.luucka.finance.category.Category;
import me.luucka.finance.category.CategoryService;
import me.luucka.finance.common.ApiException;
import me.luucka.finance.core.Currencies;
import me.luucka.finance.core.EntryKind;
import me.luucka.finance.core.TagNames;
import me.luucka.finance.core.camt.CamtParser;
import me.luucka.finance.core.category.CategoryTree;
import me.luucka.finance.core.csv.CsvReader;
import me.luucka.finance.core.csv.CsvWriter;
import me.luucka.finance.core.csv.EntryCsvFormat;
import me.luucka.finance.core.csv.EntryCsvFormat.Column;
import me.luucka.finance.core.rules.CategoryRules;
import me.luucka.finance.position.AssetPosition;
import me.luucka.finance.position.AssetPositionRepository;
import me.luucka.finance.rule.CategoryRuleService;
import me.luucka.finance.tag.TagService;
import me.luucka.finance.user.AppUser;
import me.luucka.finance.user.AppUserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CSV export of income/expense entries and the read-only first step of an import, from a CSV or a
 * bank statement (camt.053 and the like).
 * <p>
 * The import preview never writes anything and keeps nothing on the server: it parses the file,
 * matches categories among the user's own, flags duplicates and returns the rows. The client then
 * sends the rows it wants back to {@link CashEntryService#importEntries}, which validates them again.
 */
@Service
public class CashEntryCsvService {

    /** Largest file accepted (also enforced by the multipart limit and Nginx). */
    public static final long MAX_BYTES = 2L * 1024 * 1024;
    private static final CsvReader.Limits LIMITS =
            new CsvReader.Limits(CashEntryService.MAX_IMPORT_ROWS + 1, 30, 1000);
    private static final char EXPORT_DELIMITER = ';';
    private static final String BOM = "\uFEFF";

    /** One data row: the text as written in the file and, where it could be read, the values. */
    /**
     * @param categorySource      where {@code categoryId} comes from: {@code FILE}, {@code RULE} (the rule
     *                            {@code rulePattern}), null without one
     * @param suggestedCategoryId without a category, the one most often given to the same description
     *                            in the past: a proposal the user confirms
     */
    public record PreviewRow(int line, Map<String, String> raw, LocalDate date, EntryKind kind, Long categoryId,
                             BigDecimal amount, String currency, String description, Long fromPositionId,
                             Long toPositionId, List<String> tags, boolean duplicate, List<String> errors,
                             String categorySource, String rulePattern, Long suggestedCategoryId) {
    }

    /** Problems a rule can solve: the file gives no category the user has. */
    private static final Set<String> UNCATEGORIZED = Set.of("missing_category", "unknown_category",
            "unknown_subcategory");

    /**
     * An account in a bank statement: its closing balance and the position with its IBAN, which
     * the client may update with that balance.
     */
    public record StatementInfo(String iban, String currency, LocalDate closingDate, BigDecimal closingBalance,
                                Long positionId, String positionName, String positionCurrency) {
    }

    /**
     * @param format     {@code CSV} or {@code CAMT} (a bank statement, camt.052/053/054)
     * @param delimiter  of a CSV, null for a statement
     * @param statements the accounts of a statement, empty for a CSV
     */
    public record Preview(String format, String delimiter, List<String> ignoredColumns, int total, int valid,
                          int duplicates, int invalid, List<PreviewRow> rows, List<StatementInfo> statements) {
    }

    private final CashEntryService entries;
    private final CashEntryRepository repository;
    private final CategoryService categories;
    private final AssetPositionRepository positions;
    private final AppUserRepository users;
    private final TagService tags;
    private final CategoryRuleService rules;

    public CashEntryCsvService(CashEntryService entries, CashEntryRepository repository,
                               CategoryService categories, AssetPositionRepository positions,
                               AppUserRepository users, TagService tags, CategoryRuleService rules) {
        this.entries = entries;
        this.repository = repository;
        this.categories = categories;
        this.positions = positions;
        this.users = users;
        this.tags = tags;
        this.rules = rules;
    }

    // ------------------------------------------------------------------ export

    /** UTF-8 with BOM (so Excel reads accents), {@code ;} delimiter, ISO dates, dot decimals. */
    @Transactional(readOnly = true)
    public byte[] export(long userId, CashEntryService.Filter filter) {
        Locale language = user(userId).getLanguage().locale();
        CategoryTree tree = categories.tree(userId);
        Map<Long, String> positionNames = positions.findByUserIdOrderByArchivedAscNameAsc(userId).stream()
                .collect(Collectors.toMap(AssetPosition::getId, AssetPosition::getName));
        Map<Long, String> tagNames = tags.names(userId);
        // Category, description, position and tag names are user text; the rest is produced here
        boolean[] text = {false, false, true, true, false, false, true, true, true, true};
        CsvWriter csv = new CsvWriter(EXPORT_DELIMITER);
        csv.textRow(EntryCsvFormat.headers(language));
        for (CashEntry entry : entries.all(userId, filter)) {
            csv.row(Arrays.asList(
                    entry.getDate().toString(),
                    EntryCsvFormat.kindLabel(entry.getKind(), language),
                    macroName(tree, entry.getCategoryId()),
                    detailName(tree, entry.getCategoryId()),
                    entry.getAmount().stripTrailingZeros().toPlainString(),
                    entry.getCurrency(),
                    entry.getDescription(),
                    entry.getFromPositionId() == null ? "" : positionNames.getOrDefault(entry.getFromPositionId(), ""),
                    entry.getToPositionId() == null ? "" : positionNames.getOrDefault(entry.getToPositionId(), ""),
                    String.join(", ", CashEntryService.tagNames(entry, tagNames))),
                    text);
        }
        return (BOM + csv).getBytes(StandardCharsets.UTF_8);
    }

    /** The macro of an entry's category: the category itself or its parent. */
    private static String macroName(CategoryTree tree, Long categoryId) {
        CategoryTree.Node macro = categoryId == null ? null : tree.macro(categoryId);
        return macro == null ? "" : macro.name();
    }

    /** The detail an entry's category is, empty for a macro. */
    private static String detailName(CategoryTree tree, Long categoryId) {
        CategoryTree.Node node = categoryId == null ? null : tree.node(categoryId);
        return node == null || node.isMacro() ? "" : node.name();
    }

    public String exportFileName(long userId, LocalDate today) {
        String name = switch (user(userId).getLanguage()) {
            case IT -> "movimenti-";
            case EN -> "entries-";
            case DE -> "buchungen-";
            case FR -> "operations-";
        };
        return name + today + ".csv";
    }

    // ------------------------------------------------------------------ import preview

    /**
     * Reads a CSV or a bank statement (XML) and reports every row with its problems; writes nothing.
     *
     * @throws ApiException 400 when the file itself cannot be used (not text, malformed, too many
     *                      rows or columns, required columns missing, not a statement)
     */
    @Transactional(readOnly = true)
    public Preview preview(long userId, byte[] content) {
        if (content.length == 0) {
            throw ApiException.badRequest("csv_empty", "The file is empty");
        }
        if (content.length > MAX_BYTES) {
            throw tooLarge();
        }
        if (CamtParser.looksLikeXml(content)) {
            return previewStatement(userId, content);
        }
        List<CsvReader.Row> records;
        char delimiter;
        try {
            String text = CsvReader.decode(content);
            delimiter = CsvReader.detectDelimiter(text);
            records = CsvReader.parse(text, delimiter, LIMITS);
        } catch (CsvReader.CsvException e) {
            throw ApiException.badRequest(e.code(), e.getMessage()).withProperty("line", e.line());
        }
        if (records.isEmpty()) {
            throw ApiException.badRequest("csv_empty", "The file is empty");
        }

        // Header first: a wrong header is the more useful message, even without data rows
        Map<Column, Integer> columns = new EnumMap<>(Column.class);
        List<String> ignored = new ArrayList<>();
        List<String> header = records.getFirst().fields();
        for (int i = 0; i < header.size(); i++) {
            Optional<Column> column = EntryCsvFormat.column(header.get(i));
            if (column.isEmpty()) {
                if (!header.get(i).isBlank()) {
                    ignored.add(EntryCsvFormat.text(header.get(i)));
                }
            } else if (columns.putIfAbsent(column.get(), i) != null) {
                throw ApiException.badRequest("csv_duplicate_column",
                        "The column \"" + EntryCsvFormat.text(header.get(i)) + "\" appears twice");
            }
        }
        List<String> missing = new ArrayList<>();
        for (Column required : List.of(Column.DATE, Column.AMOUNT)) {
            if (!columns.containsKey(required)) {
                missing.add(required.name().toLowerCase(Locale.ROOT));
            }
        }
        if (!missing.isEmpty()) {
            throw ApiException.badRequest("csv_missing_columns", "Missing columns: " + String.join(", ", missing))
                    .withProperty("columns", missing);
        }

        if (records.size() < 2) {
            throw ApiException.badRequest("csv_empty", "The file has no rows below the header");
        }

        String baseCurrency = user(userId).getBaseCurrency();
        CategoryNames byName = new CategoryNames(categories.owned(userId));
        CategoryRules matcher = rules.matcher(userId);
        // Active positions first, so a name shared with an archived one picks the active one
        Map<String, List<AssetPosition>> positionsByName = positions.findByUserIdOrderByArchivedAscNameAsc(userId)
                .stream().collect(Collectors.groupingBy(p -> EntryCsvFormat.fold(p.getName())));
        List<PreviewRow> rows = new ArrayList<>();
        for (CsvReader.Row record : records.subList(1, records.size())) {
            if (record.fields().stream().allMatch(String::isBlank)) {
                continue;
            }
            rows.add(readRow(record, columns, byName, positionsByName, baseCurrency, matcher));
        }
        if (rows.isEmpty()) {
            throw ApiException.badRequest("csv_empty", "The file has no rows below the header");
        }
        rows = markDuplicates(userId, rows);

        int valid = (int) rows.stream().filter(r -> r.errors().isEmpty()).count();
        int duplicates = (int) rows.stream().filter(PreviewRow::duplicate).count();
        return new Preview("CSV", delimiter == '\t' ? "tab" : String.valueOf(delimiter), ignored, rows.size(),
                valid, duplicates, rows.size() - valid, rows, List.of());
    }

    /**
     * A bank statement: credits are income, debits expenses, categorized by the user's rules (the
     * bank's side decides the type); pending bookings are shown but cannot be imported.
     */
    private Preview previewStatement(long userId, byte[] content) {
        List<CamtParser.Statement> statements;
        try {
            statements = CamtParser.parse(content, CashEntryService.MAX_IMPORT_ROWS, EntryCsvFormat.MAX_DESCRIPTION);
        } catch (CamtParser.CamtException e) {
            throw ApiException.badRequest(e.code(), e.getMessage());
        }
        String baseCurrency = user(userId).getBaseCurrency();
        CategoryRules matcher = rules.matcher(userId);
        List<PreviewRow> rows = new ArrayList<>();
        List<StatementInfo> accounts = new ArrayList<>();
        for (CamtParser.Statement statement : statements) {
            for (CamtParser.Entry entry : statement.entries()) {
                rows.add(statementRow(rows.size() + 1, entry, statement.currency(), baseCurrency, matcher));
            }
            AssetPosition position = statement.iban() == null ? null
                    : positions.findByUserIdAndIban(userId, statement.iban()).orElse(null);
            CamtParser.Balance closing = statement.closing();
            accounts.add(new StatementInfo(statement.iban(), statement.currency(),
                    closing == null ? null : closing.date(), closing == null ? null : closing.amount(),
                    position == null ? null : position.getId(), position == null ? null : position.getName(),
                    position == null ? null : position.getCurrency()));
        }
        rows = markDuplicates(userId, rows);
        int valid = (int) rows.stream().filter(r -> r.errors().isEmpty()).count();
        int duplicates = (int) rows.stream().filter(PreviewRow::duplicate).count();
        return new Preview("CAMT", null, List.of(), rows.size(), valid, duplicates, rows.size() - valid, rows,
                accounts);
    }

    private static PreviewRow statementRow(int line, CamtParser.Entry entry, String accountCurrency,
                                           String baseCurrency, CategoryRules matcher) {
        List<String> errors = new ArrayList<>();
        if (entry.date() == null) {
            errors.add("invalid_date");
        }
        if (entry.amount() == null) {
            errors.add("invalid_amount");
        }
        String currencyText = entry.currency() != null ? entry.currency()
                : accountCurrency != null ? accountCurrency : baseCurrency;
        String currency = Currencies.isValid(currencyText) ? Currencies.normalize(currencyText) : null;
        if (currency == null) {
            errors.add("invalid_currency");
        }
        if (!entry.booked()) {
            errors.add("camt_pending");
        }
        String description = entry.description() == null ? "" : entry.description();
        Long categoryId = null;
        String categorySource = null;
        String rulePattern = null;
        Long suggestedCategoryId = null;
        if (entry.kind() == null) {
            errors.add("invalid_kind");
            errors.add("missing_category");
        } else {
            Optional<CategoryRules.Suggestion> suggestion = matcher.suggest(description, entry.kind());
            if (suggestion.isPresent() && suggestion.get().fromRule()) {
                categoryId = suggestion.get().categoryId();
                categorySource = "RULE";
                rulePattern = suggestion.get().rule().pattern();
            } else {
                errors.add("missing_category");
                suggestedCategoryId = suggestion.map(CategoryRules.Suggestion::categoryId).orElse(null);
            }
        }
        // What the file says, for the review to show next to an unreadable value
        Map<String, String> raw = new LinkedHashMap<>();
        raw.put("date", entry.date() == null ? "" : entry.date().toString());
        raw.put("amount", entry.amount() == null ? ""
                : (entry.kind() == EntryKind.EXPENSE ? "-" : "") + entry.amount().toPlainString());
        raw.put("currency", currencyText == null ? "" : currencyText);
        raw.put("description", description);
        return new PreviewRow(line, raw, entry.date(), entry.kind(), categoryId, entry.amount(), currency,
                description.isEmpty() ? null : description, null, null, List.of(), false, errors, categorySource,
                rulePattern, suggestedCategoryId);
    }

    public static ApiException tooLarge() {
        return new ApiException(HttpStatus.CONTENT_TOO_LARGE, "csv_too_large",
                "The file is larger than " + MAX_BYTES / 1024 / 1024 + " MB");
    }

    private static PreviewRow readRow(CsvReader.Row record, Map<Column, Integer> columns,
                                      CategoryNames byName,
                                      Map<String, List<AssetPosition>> positionsByName, String baseCurrency,
                                      CategoryRules matcher) {
        Map<String, String> raw = new LinkedHashMap<>();
        for (Column column : Column.values()) {
            Integer index = columns.get(column);
            if (index != null) {
                raw.put(column.name().toLowerCase(Locale.ROOT),
                        index < record.fields().size() ? EntryCsvFormat.text(record.fields().get(index)) : "");
            }
        }
        List<String> errors = new ArrayList<>();

        LocalDate date = EntryCsvFormat.date(raw.get("date")).orElse(null);
        if (date == null) {
            errors.add("invalid_date");
        }

        BigDecimal signed = EntryCsvFormat.amount(raw.get("amount")).orElse(null);
        BigDecimal amount = signed == null ? null : signed.abs();
        if (signed == null) {
            errors.add("invalid_amount");
        } else if (signed.signum() == 0) {
            errors.add("zero_amount");
            amount = null;
        }

        String currencyText = raw.getOrDefault("currency", "");
        String currency = currencyText.isEmpty() ? baseCurrency
                : Currencies.isValid(currencyText) ? Currencies.normalize(currencyText) : null;
        if (currency == null) {
            errors.add("invalid_currency");
        }

        String description = raw.getOrDefault("description", "");
        if (description.length() > EntryCsvFormat.MAX_DESCRIPTION) {
            errors.add("description_too_long");
        }

        // Type: explicit column, else the category's type, else the sign of the amount
        EntryKind kind = null;
        String kindText = raw.getOrDefault("kind", "");
        if (!kindText.isEmpty()) {
            kind = EntryCsvFormat.kind(kindText).orElse(null);
            if (kind == null) {
                errors.add("invalid_kind");
            }
        }
        String categoryText = raw.getOrDefault("category", "");
        String subcategoryText = raw.getOrDefault("subcategory", "");
        List<Category> named = byName.find(categoryText, subcategoryText);
        if (kind == null && kindText.isEmpty()) {
            if (named.size() == 1) {
                kind = named.getFirst().getKind();
            } else if (signed != null && signed.signum() != 0) {
                kind = signed.signum() < 0 ? EntryKind.EXPENSE : EntryKind.INCOME;
            }
        }
        Long categoryId = null;
        Long fromPositionId = null;
        Long toPositionId = null;
        if (kind == EntryKind.TRANSFER) {
            // No category; the positions are optional but must be the user's when named
            fromPositionId = position(raw.getOrDefault("from", ""), positionsByName, errors);
            toPositionId = position(raw.getOrDefault("to", ""), positionsByName, errors);
            if (fromPositionId != null && fromPositionId.equals(toPositionId)) {
                errors.add("transfer_same_position");
                toPositionId = null;
            }
        } else if (categoryText.isEmpty()) {
            errors.add(subcategoryText.isEmpty() ? "missing_category" : "unknown_category");
        } else if (named.isEmpty()) {
            errors.add(byName.knownMacro(categoryText, subcategoryText) ? "unknown_subcategory" : "unknown_category");
        } else if (kind != null) {
            EntryKind rowKind = kind;
            List<Category> matches = named.stream().filter(c -> c.getKind() == rowKind).toList();
            if (matches.size() == 1) {
                categoryId = matches.getFirst().getId();
            } else {
                errors.add(matches.isEmpty() ? "category_kind_mismatch" : "ambiguous_category");
            }
        }

        // The file wins: a rule only categorizes a row the file gives no category the user has; the
        // past entries only propose one
        String categorySource = categoryId == null ? null : "FILE";
        String rulePattern = null;
        Long suggestedCategoryId = null;
        if (kind != EntryKind.TRANSFER && categoryId == null && !errors.contains("invalid_kind")
                && errors.stream().anyMatch(UNCATEGORIZED::contains)) {
            Optional<CategoryRules.Suggestion> suggestion = matcher.suggest(description, kind);
            if (suggestion.isPresent() && suggestion.get().fromRule()) {
                categoryId = suggestion.get().categoryId();
                kind = suggestion.get().kind();
                errors.removeAll(UNCATEGORIZED);
                categorySource = "RULE";
                rulePattern = suggestion.get().rule().pattern();
            } else if (suggestion.isPresent()) {
                suggestedCategoryId = suggestion.get().categoryId();
            }
        }

        List<String> tags = TagNames.parseCell(raw.getOrDefault("tags", "")).orElse(null);
        if (tags == null) {
            errors.add("invalid_tags");
        }

        return new PreviewRow(record.line(), raw, date, kind, categoryId, amount, currency,
                description.isEmpty() ? null : description, fromPositionId, toPositionId,
                tags == null ? List.of() : tags, false, errors, categorySource, rulePattern, suggestedCategoryId);
    }

    /**
     * The user's categories by name, ignoring case and accents. A row names a macro and, optionally,
     * a detail under it (in the subcategory column or as "Casa › Affitto"); a detail's name alone
     * also finds it when no macro has that name.
     */
    static final class CategoryNames {

        private final Map<String, List<Category>> macros;
        private final Map<String, List<Category>> details;

        CategoryNames(List<Category> categories) {
            macros = categories.stream().filter(Category::isMacro)
                    .collect(Collectors.groupingBy(c -> EntryCsvFormat.fold(c.getName())));
            details = categories.stream().filter(c -> !c.isMacro())
                    .collect(Collectors.groupingBy(c -> EntryCsvFormat.fold(c.getName())));
        }

        /** The categories a row may mean, of either kind: several only when its kind has to decide. */
        List<Category> find(String category, String subcategory) {
            if (subcategory.isEmpty()) {
                Optional<EntryCsvFormat.CategoryPath> path = EntryCsvFormat.categoryPath(category);
                if (path.isPresent()) {
                    return under(path.get().macro(), path.get().detail());
                }
                String name = EntryCsvFormat.fold(category);
                return name.isEmpty() ? List.of() : macros.getOrDefault(name, details.getOrDefault(name, List.of()));
            }
            return under(category, subcategory);
        }

        /** Whether the macro a row names exists, though the detail it names does not. */
        boolean knownMacro(String category, String subcategory) {
            String macro = subcategory.isEmpty()
                    ? EntryCsvFormat.categoryPath(category).map(EntryCsvFormat.CategoryPath::macro).orElse(null)
                    : category;
            return macro != null && macros.containsKey(EntryCsvFormat.fold(macro));
        }

        private List<Category> under(String macro, String detail) {
            List<Category> parents = macros.getOrDefault(EntryCsvFormat.fold(macro), List.of());
            return details.getOrDefault(EntryCsvFormat.fold(detail), List.of()).stream()
                    .filter(d -> parents.stream().anyMatch(p -> p.getId().equals(d.getParentId())))
                    .toList();
        }
    }

    private static Long position(String name, Map<String, List<AssetPosition>> positionsByName,
                                 List<String> errors) {
        if (name.isEmpty()) {
            return null;
        }
        List<AssetPosition> matches = positionsByName.getOrDefault(EntryCsvFormat.fold(name), List.of());
        if (matches.isEmpty()) {
            if (!errors.contains("unknown_position")) {
                errors.add("unknown_position");
            }
            return null;
        }
        return matches.getFirst().getId();
    }

    /** Flags rows equal to an entry the user already has (same date, type, amount, currency, text). */
    private List<PreviewRow> markDuplicates(long userId, List<PreviewRow> rows) {
        List<LocalDate> dates = rows.stream().map(PreviewRow::date).filter(d -> d != null).sorted().toList();
        if (dates.isEmpty()) {
            return rows;
        }
        Set<String> existing = new HashSet<>();
        for (CashEntry entry : repository.findByUserIdAndDateBetween(userId, dates.getFirst(), dates.getLast())) {
            existing.add(key(entry.getDate(), entry.getKind(), entry.getAmount(), entry.getCurrency(),
                    entry.getDescription()));
        }
        return rows.stream().map(row -> {
            boolean comparable = row.date() != null && row.kind() != null && row.amount() != null
                    && row.currency() != null;
            boolean duplicate = comparable && existing.contains(
                    key(row.date(), row.kind(), row.amount(), row.currency(), row.description()));
            return duplicate ? new PreviewRow(row.line(), row.raw(), row.date(), row.kind(), row.categoryId(),
                    row.amount(), row.currency(), row.description(), row.fromPositionId(), row.toPositionId(),
                    row.tags(), true, row.errors(), row.categorySource(), row.rulePattern(),
                    row.suggestedCategoryId()) : row;
        }).toList();
    }

    private static String key(LocalDate date, EntryKind kind, BigDecimal amount, String currency,
                              String description) {
        return date + "|" + kind + "|" + amount.stripTrailingZeros().toPlainString() + "|" + currency + "|"
                + EntryCsvFormat.fold(description == null ? "" : description);
    }

    private AppUser user(long userId) {
        return users.findById(userId).orElseThrow(() -> ApiException.notFound("User"));
    }
}
