package io.github.jagdish1998.sqlguard.db;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import javax.sql.DataSource;

import io.github.jagdish1998.sqlguard.config.SqlGuardProperties;
import io.github.jagdish1998.sqlguard.guard.PiiRedactor;
import org.springframework.stereotype.Component;

/**
 * Answers schema questions through JDBC metadata rather than hand-written catalog queries.
 *
 * <p>The validator blocks the model from reading {@code pg_catalog} and
 * {@code information_schema} directly, which would leave it unable to discover anything.
 * These tools are the sanctioned replacement: the same information, filtered by the same
 * allow-list, with no arbitrary SQL involved. Using {@link DatabaseMetaData} also means the
 * driver handles quoting and dialect differences.
 *
 * <p>Column listings flag which fields would be masked. Telling the model up front that
 * {@code email} comes back redacted saves it from building a query whose central column
 * arrives as {@code ***}.
 */
@Component
public class SchemaInspector {

    private final DataSource dataSource;

    private final SqlGuardProperties properties;

    private final PiiRedactor redactor;

    public SchemaInspector(DataSource dataSource, SqlGuardProperties properties, PiiRedactor redactor) {
        this.dataSource = dataSource;
        this.properties = properties;
        this.redactor = redactor;
    }

    public List<TableInfo> listTables() throws SQLException {
        List<TableInfo> tables = new ArrayList<>();
        try (Connection connection = this.dataSource.getConnection()) {
            connection.setReadOnly(true);
            DatabaseMetaData metaData = connection.getMetaData();
            try (ResultSet resultSet = metaData.getTables(null, null, "%",
                    new String[] { "TABLE", "VIEW", "MATERIALIZED VIEW" })) {
                while (resultSet.next()) {
                    String schema = resultSet.getString("TABLE_SCHEM");
                    String name = resultSet.getString("TABLE_NAME");
                    String type = resultSet.getString("TABLE_TYPE");
                    if (isHidden(schema, name)) {
                        continue;
                    }
                    tables.add(new TableInfo(schema, name, type, resultSet.getString("REMARKS")));
                }
            }
        }
        tables.sort((left, right) -> {
            int bySchema = String.valueOf(left.schema()).compareToIgnoreCase(String.valueOf(right.schema()));
            return (bySchema != 0) ? bySchema : left.name().compareToIgnoreCase(right.name());
        });
        return tables;
    }

    /**
     * @param table either {@code name} or {@code schema.name}
     * @throws IllegalArgumentException when the table is unknown or deliberately hidden,
     *                                  which keeps a blocked table indistinguishable from
     *                                  one that does not exist
     */
    public TableDetail describeTable(String table) throws SQLException {
        if (table == null || table.isBlank()) {
            throw new IllegalArgumentException("Table name is required.");
        }
        String cleaned = table.replace("\"", "").replace("`", "").trim();
        int lastDot = cleaned.lastIndexOf('.');
        String schema = (lastDot > 0) ? cleaned.substring(0, lastDot) : null;
        String name = (lastDot > 0) ? cleaned.substring(lastDot + 1) : cleaned;

        if (isHidden(schema, name)) {
            throw new IllegalArgumentException("Unknown table: " + cleaned);
        }

        List<ColumnInfo> columns = new ArrayList<>();
        Set<String> primaryKeys = new LinkedHashSet<>();
        String resolvedSchema = schema;

        try (Connection connection = this.dataSource.getConnection()) {
            connection.setReadOnly(true);
            DatabaseMetaData metaData = connection.getMetaData();

            try (ResultSet resultSet = metaData.getColumns(null, schema, name, "%")) {
                while (resultSet.next()) {
                    String owningSchema = resultSet.getString("TABLE_SCHEM");
                    if (isHidden(owningSchema, name)) {
                        continue;
                    }
                    if (resolvedSchema == null) {
                        resolvedSchema = owningSchema;
                    }
                    String columnName = resultSet.getString("COLUMN_NAME");
                    columns.add(new ColumnInfo(
                            columnName,
                            resultSet.getString("TYPE_NAME"),
                            "YES".equalsIgnoreCase(resultSet.getString("IS_NULLABLE")),
                            resultSet.getString("COLUMN_DEF"),
                            this.redactor.strategyFor(columnName) != null,
                            resultSet.getString("REMARKS")));
                }
            }

            if (columns.isEmpty()) {
                throw new IllegalArgumentException(
                        "Unknown table: " + cleaned + ". Call list_tables to see what is available.");
            }

            try (ResultSet resultSet = metaData.getPrimaryKeys(null, resolvedSchema, name)) {
                while (resultSet.next()) {
                    primaryKeys.add(resultSet.getString("COLUMN_NAME"));
                }
            }
        }

        return new TableDetail(resolvedSchema, name, columns, List.copyOf(primaryKeys));
    }

    /**
     * A table is hidden if it sits outside the allowed schemas, is explicitly blocked, or
     * belongs to the database's own internals.
     */
    private boolean isHidden(String schema, String name) {
        if (name == null) {
            return true;
        }
        String lowerName = name.toLowerCase(Locale.ROOT);
        String lowerSchema = (schema != null) ? schema.toLowerCase(Locale.ROOT) : null;

        if (lowerName.startsWith("pg_")) {
            return true;
        }
        if (lowerSchema != null
                && (lowerSchema.startsWith("pg_") || lowerSchema.equals("information_schema"))) {
            return true;
        }

        List<String> allowedSchemas = this.properties.getAllowedSchemas();
        if (!allowedSchemas.isEmpty() && lowerSchema != null) {
            boolean permitted = allowedSchemas.stream()
                    .filter(candidate -> candidate != null && !candidate.isBlank())
                    .anyMatch(candidate -> candidate.trim().equalsIgnoreCase(lowerSchema));
            if (!permitted) {
                return true;
            }
        }

        return this.properties.getBlockedTables().stream()
                .filter(candidate -> candidate != null && !candidate.isBlank())
                .anyMatch(candidate -> {
                    String blocked = candidate.trim();
                    if (blocked.contains(".")) {
                        String qualified = (schema != null) ? schema + "." + name : name;
                        return blocked.equalsIgnoreCase(qualified);
                    }
                    return blocked.equalsIgnoreCase(name);
                });
    }

    public record TableInfo(String schema, String name, String type, String comment) {
    }

    public record ColumnInfo(String name, String type, boolean nullable, String defaultValue,
            boolean redacted, String comment) {
    }

    public record TableDetail(String schema, String name, List<ColumnInfo> columns, List<String> primaryKeys) {
    }
}
