package me.luucka.finance.account;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import me.luucka.finance.cashflow.CashEntryCsvService;
import me.luucka.finance.cashflow.CashEntryService;
import me.luucka.finance.common.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Everything stored for one user, as a ZIP to keep or to take elsewhere: {@code data.json} with
 * the account, its settings and every record (linked by id), and the entries as CSV in the import
 * format. Secrets are left out: password and recovery code hashes, the 2FA secret, the passkeys'
 * keys and pending email codes.
 */
@Service
public class AccountExportService {

    /** Name and version of the {@code data.json} layout, so a reader can tell what it is reading. */
    static final String FORMAT = "finanze-export";
    static final int FORMAT_VERSION = 1;

    public record Export(String fileName, byte[] zip) {
    }

    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    private final CashEntryCsvService csv;
    private final Clock clock;

    public AccountExportService(JdbcTemplate jdbc, JsonMapper json, CashEntryCsvService csv, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.csv = csv;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Export export(long userId) {
        String username = jdbc.query("select username from app_user where id = ?",
                rs -> rs.next() ? rs.getString(1) : null, userId);
        if (username == null) {
            throw ApiException.notFound("User");
        }
        LocalDate today = LocalDate.now(clock);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            add(zip, "data.json", json.writerWithDefaultPrettyPrinter().writeValueAsBytes(data(userId)));
            add(zip, csv.exportFileName(userId, today),
                    csv.export(userId, new CashEntryService.Filter(null, null, null, null, null, null)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        // Usernames are lowercase letters, digits, '.', '_' and '-': safe in a file name
        return new Export("finanze-" + username + "-" + today + ".zip", bytes.toByteArray());
    }

    private ObjectNode data(long userId) {
        ObjectNode root = json.createObjectNode();
        root.put("format", FORMAT);
        root.put("formatVersion", FORMAT_VERSION);
        root.put("exportedAt", clock.instant().toString());
        root.set("account", object(userId, """
                select username, role, base_currency as "baseCurrency", language, theme,
                       totp_enabled as "twoFactorEnabled", created_at as "createdAt",
                       last_login_at as "lastLoginAt"
                from app_user where id = ?"""));
        root.set("notifications", object(userId, """
                select email, budget_alerts as "budgetAlerts", goal_alerts as "goalAlerts",
                       monthly_summary as "monthlySummary"
                from notification_settings where user_id = ?"""));
        root.set("categories", list(userId, """
                select id, parent_id as "parentId", name, kind, color, created_at as "createdAt"
                from category where user_id = ?"""));
        root.set("tags", list(userId, """
                select id, name, created_at as "createdAt" from tag where user_id = ?"""));
        root.set("categoryRules", list(userId, """
                select id, pattern, category_id as "categoryId", created_at as "createdAt"
                from category_rule where user_id = ?"""));
        root.set("entries", list(userId, """
                select id, entry_date as "date", kind, category_id as "categoryId",
                       trim_scale(amount) as amount, currency, description,
                       from_position_id as "fromPositionId", to_position_id as "toPositionId",
                       array(select tag_id from cash_entry_tag t where t.entry_id = e.id order by tag_id) as "tagIds",
                       recurring_entry_id as "recurringId", split_group as "splitGroup",
                       created_at as "createdAt", updated_at as "updatedAt"
                from cash_entry e where user_id = ?"""));
        root.set("recurring", list(userId, """
                select id, kind, category_id as "categoryId", trim_scale(amount) as amount, currency, description,
                       from_position_id as "fromPositionId", to_position_id as "toPositionId",
                       array(select tag_id from recurring_entry_tag t where t.rule_id = r.id order by tag_id)
                           as "tagIds",
                       frequency, start_date as "startDate", end_date as "endDate",
                       last_generated as "lastGenerated", active, created_at as "createdAt"
                from recurring_entry r where user_id = ?"""));
        root.set("positions", list(userId, """
                select id, name, symbol, iban, asset_class as "assetClass", currency, notes, archived,
                       created_at as "createdAt"
                from asset_position where user_id = ?"""));
        root.set("positionRecords", list(userId, """
                select id, position_id as "positionId", snapshot_date as "date", trim_scale(quantity) as quantity,
                       trim_scale(unit_price) as "unitPrice", note
                from position_snapshot where user_id = ?"""));
        root.set("exchangeRates", list(userId, """
                select id, base_currency as "baseCurrency", currency, rate_date as "date", trim_scale(rate) as rate
                from exchange_rate where user_id = ?"""));
        root.set("budgets", list(userId, """
                select id, category_id as "categoryId", period, trim_scale(amount) as amount, currency
                from budget where user_id = ?"""));
        root.set("goals", list(userId, """
                select id, name, kind, trim_scale(target_amount) as "targetAmount", currency,
                       target_date as "targetDate",
                       array(select position_id from savings_goal_position p where p.goal_id = g.id
                             order by position_id) as "positionIds"
                from savings_goal g where user_id = ?"""));
        root.set("forecastScenarios", list(userId, """
                select id, name, forecast_year as "year", trim_scale(income_growth) as "incomeGrowth",
                       trim_scale(expense_growth) as "expenseGrowth",
                       array(select tag_id from forecast_excluded_tag t where t.scenario_id = s.id order by tag_id)
                           as "excludedTagIds",
                       array(select category_id from forecast_excluded_category c where c.scenario_id = s.id
                             order by category_id) as "excludedCategoryIds",
                       (select coalesce(json_agg(json_build_object(
                                   'description', i.description, 'kind', i.kind, 'categoryId', i.category_id,
                                   'amount', trim_scale(i.amount), 'schedule', i.schedule,
                                   'startMonth', i.start_month, 'endMonth', i.end_month)
                               order by i.sort_order), '[]')
                        from forecast_item i where i.scenario_id = s.id) as items
                from forecast_scenario s where user_id = ?"""));
        root.set("passkeys", list(userId, """
                select id, name, backed_up as "synced", created_at as "createdAt", last_used_at as "lastUsedAt"
                from passkey where user_id = ?"""));
        root.set("logins", list(userId, """
                select id, created_at as "at", ip_address as "ipAddress", user_agent as "userAgent", success, reason
                from login_event where user_id = ?"""));
        return root;
    }

    /** The rows of {@code select} (which must have an {@code id} column) as a JSON array, by id. */
    private JsonNode list(long userId, String select) {
        return json.readTree(jdbc.queryForObject(
                "select coalesce(json_agg(x order by x.id), '[]')::text from (" + select + ") x",
                String.class, userId));
    }

    /** The single row of {@code select} as a JSON object, or null when there is none. */
    private JsonNode object(long userId, String select) {
        String row = jdbc.query("select row_to_json(x)::text from (" + select + ") x",
                rs -> rs.next() ? rs.getString(1) : null, userId);
        return row == null ? json.nullNode() : json.readTree(row);
    }

    private static void add(ZipOutputStream zip, String name, byte[] content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content);
        zip.closeEntry();
    }
}
