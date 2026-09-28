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
import me.luucka.finance.core.csv.CsvReader;
import me.luucka.finance.core.csv.CsvWriter;
import me.luucka.finance.core.csv.EntryCsvFormat;
import me.luucka.finance.core.csv.EntryCsvFormat.Column;
import me.luucka.finance.position.AssetPosition;
import me.luucka.finance.position.AssetPositionRepository;
import me.luucka.finance.user.AppUser;
import me.luucka.finance.user.AppUserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CSV export of income/expense entries and the read-only first step of an import.
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
    public record PreviewRow(int line, Map<String, String> raw, LocalDate date, EntryKind kind, Long categoryId,
                             BigDecimal amount, String currency, String description, Long fromPositionId,
                             Long toPositionId, boolean duplicate, List<String> errors) {
    }

    public record Preview(String delimiter, List<String> ignoredColumns, int total, int valid, int duplicates,
                          int invalid, List<PreviewRow> rows) {
    }

    private final CashEntryService entries;
    private final CashEntryRepository repository;
    private final CategoryService categories;
    private final AssetPositionRepository positions;
    private final AppUserRepository users;

    public CashEntryCsvService(CashEntryService entries, CashEntryRepository repository,
                               CategoryService categories, AssetPositionRepository positions,
                               AppUserRepository users) {
        this.entries = entries;
        this.repository = repository;
        this.categories = categories;
        this.positions = positions;
        this.users = users;
    }

    // ------------------------------------------------------------------ export

    /** UTF-8 with BOM (so Excel reads accents), {@code ;} delimiter, ISO dates, dot decimals. */
    @Transactional(readOnly = true)
    public byte[] export(long userId, CashEntryService.Filter filter) {
        Locale language = user(userId).getLanguage().locale();
        Map<Long, String> names = categories.owned(userId).stream()
                .collect(Collectors.toMap(Category::getId, Category::getName));
        Map<Long, String> positionNames = positions.findByUserIdOrderByArchivedAscNameAsc(userId).stream()
                .collect(Collectors.toMap(AssetPosition::getId, AssetPosition::getName));
        // Category, description and position names are user text; the rest is produced here
        boolean[] text = {false, false, true, false, false, true, true, true};
        CsvWriter csv = new CsvWriter(EXPORT_DELIMITER);
        csv.textRow(EntryCsvFormat.headers(language));
        for (CashEntry entry : entries.all(userId, filter)) {
            csv.row(Arrays.asList(
                    entry.getDate().toString(),
                    EntryCsvFormat.kindLabel(entry.getKind(), language),
                    entry.getCategoryId() == null ? "" : names.getOrDefault(entry.getCategoryId(), ""),
                    entry.getAmount().stripTrailingZeros().toPlainString(),
                    entry.getCurrency(),
                    entry.getDescription(),
                    entry.getFromPositionId() == null ? "" : positionNames.getOrDefault(entry.getFromPositionId(), ""),
                    entry.getToPositionId() == null ? "" : positionNames.getOrDefault(entry.getToPositionId(), "")),
                    text);
        }
        return (BOM + csv).getBytes(StandardCharsets.UTF_8);
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
     * Reads a CSV and reports every row with its problems; writes nothing.
     *
     * @throws ApiException 400 when the file itself cannot be used (not text, malformed, too many
     *                      rows or columns, required columns missing)
     */
    @Transactional(readOnly = true)
    public Preview preview(long userId, byte[] content) {
        if (content.length == 0) {
            throw ApiException.badRequest("csv_empty", "The file is empty");
        }
        if (content.length > MAX_BYTES) {
            throw tooLarge();
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
        // Folded once: names are compared ignoring case and accents
        Map<String, List<Category>> byName = categories.owned(userId).stream()
                .collect(Collectors.groupingBy(c -> EntryCsvFormat.fold(c.getName())));
        // Active positions first, so a name shared with an archived one picks the active one
        Map<String, List<AssetPosition>> positionsByName = positions.findByUserIdOrderByArchivedAscNameAsc(userId)
                .stream().collect(Collectors.groupingBy(p -> EntryCsvFormat.fold(p.getName())));
        List<PreviewRow> rows = new ArrayList<>();
        for (CsvReader.Row record : records.subList(1, records.size())) {
            if (record.fields().stream().allMatch(String::isBlank)) {
                continue;
            }
            rows.add(readRow(record, columns, byName, positionsByName, baseCurrency));
        }
        if (rows.isEmpty()) {
            throw ApiException.badRequest("csv_empty", "The file has no rows below the header");
        }
        rows = markDuplicates(userId, rows);

        int valid = (int) rows.stream().filter(r -> r.errors().isEmpty()).count();
        int duplicates = (int) rows.stream().filter(PreviewRow::duplicate).count();
        return new Preview(delimiter == '\t' ? "tab" : String.valueOf(delimiter), ignored, rows.size(), valid,
                duplicates, rows.size() - valid, rows);
    }

    public static ApiException tooLarge() {
        return new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "csv_too_large",
                "The file is larger than " + MAX_BYTES / 1024 / 1024 + " MB");
    }

    private static PreviewRow readRow(CsvReader.Row record, Map<Column, Integer> columns,
                                      Map<String, List<Category>> byName,
                                      Map<String, List<AssetPosition>> positionsByName, String baseCurrency) {
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
        String folded = EntryCsvFormat.fold(categoryText);
        List<Category> named = folded.isEmpty() ? List.of() : byName.getOrDefault(folded, List.of());
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
            errors.add("missing_category");
        } else if (kind != null) {
            EntryKind rowKind = kind;
            Optional<Category> match = named.stream().filter(c -> c.getKind() == rowKind).findFirst();
            if (match.isPresent()) {
                categoryId = match.get().getId();
            } else {
                errors.add(named.isEmpty() ? "unknown_category" : "category_kind_mismatch");
            }
        } else if (named.isEmpty()) {
            errors.add("unknown_category");
        }

        return new PreviewRow(record.line(), raw, date, kind, categoryId, amount, currency,
                description.isEmpty() ? null : description, fromPositionId, toPositionId, false, errors);
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
                    true, row.errors()) : row;
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
