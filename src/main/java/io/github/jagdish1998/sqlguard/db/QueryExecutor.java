package io.github.jagdish1998.sqlguard.db;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.sql.DataSource;

import io.github.jagdish1998.sqlguard.config.SqlGuardProperties;
import io.github.jagdish1998.sqlguard.dialect.SqlDialect;
import io.github.jagdish1998.sqlguard.guard.PiiRedactor;
import org.springframework.stereotype.Component;

/**
 * Runs a vetted statement inside a session that cannot write.
 *
 * <p>This is the last and strongest guardrail. The validator's static analysis is where
 * useful error messages come from, but it is still analysis, and a parser can be wrong
 * about a dialect corner. A transaction the engine has marked read-only cannot be
 * argued with, so the order of defences matters: parse to explain, then execute under
 * constraints that hold regardless.
 *
 * <p>The connection is never committed. There is nothing to commit, and rolling back
 * makes that explicit rather than incidental.
 */
@Component
public class QueryExecutor {

    private final DataSource dataSource;

    private final SqlDialect dialect;

    private final SqlGuardProperties properties;

    private final PiiRedactor redactor;

    public QueryExecutor(DataSource dataSource, SqlDialect dialect, SqlGuardProperties properties,
            PiiRedactor redactor) {
        this.dataSource = dataSource;
        this.dialect = dialect;
        this.properties = properties;
        this.redactor = redactor;
    }

    /**
     * @param sql        a statement the validator has already approved and the rewriter has limited
     * @param maxRows    the ceiling in force, used both as a driver hint and to detect truncation
     * @throws SQLException if the database refuses or the statement times out
     */
    public QueryResult execute(String sql, int maxRows) throws SQLException {
        long startedAt = System.nanoTime();

        try (Connection connection = this.dataSource.getConnection()) {
            // setReadOnly must come first: JDBC forbids calling it once a transaction is
            // under way, and disabling auto-commit is what starts one.
            connection.setReadOnly(true);
            // A transaction is needed for SET TRANSACTION READ ONLY to mean anything.
            connection.setAutoCommit(false);

            String readOnlyStatement = this.dialect.readOnlyTransactionStatement();
            if (readOnlyStatement != null) {
                try (Statement setup = connection.createStatement()) {
                    setup.execute(readOnlyStatement);
                }
            }

            try (Statement statement = connection.createStatement()) {
                // Two independent brakes. setMaxRows caps what the driver will materialise
                // even if the LIMIT rewrite somehow missed, and the timeout bounds a query
                // that returns few rows but reads many.
                statement.setMaxRows(maxRows);
                statement.setQueryTimeout(timeoutSeconds());
                statement.setFetchSize(Math.min(maxRows, 500));

                try (ResultSet resultSet = statement.executeQuery(sql)) {
                    QueryResult result = read(resultSet, maxRows, startedAt);
                    connection.rollback();
                    return result;
                }
            }
            catch (SQLException ex) {
                safeRollback(connection);
                throw ex;
            }
        }
    }

    private QueryResult read(ResultSet resultSet, int maxRows, long startedAt) throws SQLException {
        ResultSetMetaData metaData = resultSet.getMetaData();
        int columnCount = metaData.getColumnCount();
        List<String> columns = uniqueLabels(metaData, columnCount);

        List<Map<String, Object>> rows = new ArrayList<>();
        Set<String> redacted = new LinkedHashSet<>();

        while (resultSet.next() && rows.size() < maxRows) {
            Map<String, Object> row = new LinkedHashMap<>(columnCount);
            for (int i = 1; i <= columnCount; i++) {
                row.put(columns.get(i - 1), normalise(resultSet.getObject(i)));
            }
            rows.add(this.redactor.redactRow(row, redacted));
        }

        // Honest reporting: hitting the ceiling means more rows may exist, and we did not
        // pay to find out. Claiming certainty either way would be a guess.
        boolean hitCeiling = rows.size() >= maxRows;
        long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000L;

        return new QueryResult(columns, rows, rows.size(), hitCeiling, maxRows,
                List.copyOf(redacted), elapsedMillis);
    }

    /**
     * {@code SELECT id, id} yields two columns with the same label, and a map keyed by
     * label would silently drop one. Suffixing keeps every column visible.
     */
    private List<String> uniqueLabels(ResultSetMetaData metaData, int columnCount) throws SQLException {
        List<String> labels = new ArrayList<>(columnCount);
        Set<String> seen = new LinkedHashSet<>();
        for (int i = 1; i <= columnCount; i++) {
            String label = metaData.getColumnLabel(i);
            if (label == null || label.isBlank()) {
                label = "column_" + i;
            }
            String candidate = label;
            int suffix = 2;
            while (!seen.add(candidate)) {
                candidate = label + "_" + suffix++;
            }
            labels.add(candidate);
        }
        return labels;
    }

    /**
     * Converts driver-specific types into something that serialises predictably as JSON.
     *
     * <p>Left as-is for the types Jackson already handles. Everything exotic becomes its
     * string form, which is lossy but never breaks the tool response.
     */
    private Object normalise(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number || value instanceof String || value instanceof Boolean) {
            return value;
        }
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toInstant().toString();
        }
        if (value instanceof java.sql.Date date) {
            return date.toLocalDate().toString();
        }
        if (value instanceof java.sql.Time time) {
            return time.toLocalTime().toString();
        }
        if (value instanceof byte[] bytes) {
            return "<" + bytes.length + " bytes>";
        }
        return String.valueOf(value);
    }

    private int timeoutSeconds() {
        long seconds = this.properties.getQueryTimeout().toSeconds();
        // JDBC takes whole seconds, and 0 means "no timeout", which is the one value we
        // must never pass. Round a sub-second setting up rather than down.
        return (seconds <= 0) ? 1 : (int) Math.min(seconds, Integer.MAX_VALUE);
    }

    private void safeRollback(Connection connection) {
        try {
            connection.rollback();
        }
        catch (SQLException ignored) {
            // The original failure is the one worth reporting.
        }
    }
}
