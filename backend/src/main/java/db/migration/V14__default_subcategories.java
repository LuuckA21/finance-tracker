package db.migration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import me.luucka.finance.core.EntryKind;
import me.luucka.finance.core.category.DefaultCategories;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * Gives the users who already exist the default detail categories under their default macros, named
 * in their language. Their entries stay where they are, on the macro.
 */
public class V14__default_subcategories extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws SQLException {
        Connection connection = context.getConnection();
        Map<Long, String> languages = new HashMap<>();
        try (PreparedStatement users = connection.prepareStatement("SELECT id, language FROM app_user");
             ResultSet rows = users.executeQuery()) {
            while (rows.next()) {
                languages.put(rows.getLong("id"), rows.getString("language"));
            }
        }
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO category (user_id, name, kind, color, parent_id) VALUES (?, ?, ?, ?, ?)")) {
            for (Map.Entry<Long, String> user : languages.entrySet()) {
                for (DefaultCategories.MissingDetail detail : missing(connection, user.getKey(), user.getValue())) {
                    insert.setLong(1, user.getKey());
                    insert.setString(2, detail.name());
                    insert.setString(3, detail.kind().name());
                    insert.setString(4, detail.color());
                    insert.setLong(5, detail.parentId());
                    insert.addBatch();
                }
            }
            insert.executeBatch();
        }
    }

    private static List<DefaultCategories.MissingDetail> missing(Connection connection, long userId, String language)
            throws SQLException {
        List<DefaultCategories.ExistingMacro> macros = new ArrayList<>();
        Map<Long, Set<String>> details = new HashMap<>();
        try (PreparedStatement categories = connection.prepareStatement(
                "SELECT id, name, kind, color, parent_id FROM category WHERE user_id = ?")) {
            categories.setLong(1, userId);
            try (ResultSet rows = categories.executeQuery()) {
                while (rows.next()) {
                    long parentId = rows.getLong("parent_id");
                    if (rows.wasNull()) {
                        macros.add(new DefaultCategories.ExistingMacro(rows.getLong("id"),
                                EntryKind.valueOf(rows.getString("kind")), rows.getString("name"),
                                rows.getString("color")));
                    } else {
                        details.computeIfAbsent(parentId, id -> new HashSet<>()).add(rows.getString("name"));
                    }
                }
            }
        }
        return DefaultCategories.missingDetails(language, macros, details);
    }
}
