package io.github.jagdish1998.sqlguard.dialect;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import javax.sql.DataSource;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * PostgreSQL support, including a real cost estimate.
 *
 * <p>{@code EXPLAIN (FORMAT JSON)} is the interesting part. It gives the planner's own
 * numbers before a single row is read, which turns "this query might melt the database"
 * from a guess into a threshold check. The alternative approaches are all worse: a
 * statement timeout only helps after the damage has started, and counting rows afterwards
 * means the work already happened.
 */
public class PostgresDialect implements SqlDialect {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String name() {
        return "PostgreSQL";
    }

    @Override
    public CostEstimate estimate(DataSource dataSource, String sql) {
        // FORMAT JSON rather than text: parsing the tree output would mean writing a
        // parser for a format Postgres never promised to keep stable.
        String explainSql = "EXPLAIN (FORMAT JSON) " + sql;

        try (Connection connection = dataSource.getConnection()) {
            // EXPLAIN without ANALYZE does not execute the statement, but a read-only
            // transaction costs nothing and removes any doubt about that.
            connection.setReadOnly(true);
            try (Statement statement = connection.createStatement()) {
                try (ResultSet resultSet = statement.executeQuery(explainSql)) {
                    if (!resultSet.next()) {
                        return CostEstimate.unavailable("planner returned no rows");
                    }
                    String json = resultSet.getString(1);
                    return parse(json);
                }
            }
        }
        catch (SQLException ex) {
            // A query the planner rejects is a query we should not run. Reporting the
            // estimate as unavailable lets the caller decide, and the message reaching the
            // model is usually the actual syntax or permission problem.
            return CostEstimate.unavailable("EXPLAIN failed: " + ex.getMessage());
        }
    }

    private CostEstimate parse(String json) {
        try {
            JsonNode root = MAPPER.readTree(json);
            JsonNode plan = root.isArray() && !root.isEmpty() ? root.get(0).path("Plan") : root.path("Plan");
            if (plan.isMissingNode()) {
                return CostEstimate.unavailable("no Plan node in EXPLAIN output");
            }
            long rows = plan.path("Plan Rows").asLong(-1L);
            double cost = plan.path("Total Cost").asDouble(-1d);
            String nodeType = plan.path("Node Type").asString("unknown");
            String relation = plan.path("Relation Name").asString("");
            String summary = relation.isEmpty() ? nodeType : nodeType + " on " + relation;
            return CostEstimate.of(rows, cost, summary);
        }
        catch (Exception ex) {
            return CostEstimate.unavailable("could not read EXPLAIN output: " + ex.getMessage());
        }
    }

    @Override
    public String readOnlyTransactionStatement() {
        return "SET TRANSACTION READ ONLY";
    }
}
