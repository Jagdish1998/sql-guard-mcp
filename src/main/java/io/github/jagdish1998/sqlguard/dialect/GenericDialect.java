package io.github.jagdish1998.sqlguard.dialect;

import javax.sql.DataSource;

/**
 * Fallback for any database without a dedicated implementation, including the H2 instance
 * the tests run against.
 *
 * <p>It reports no cost estimate rather than inventing one. That is a deliberate
 * limitation and it is visible in the tool response, so a caller can tell the difference
 * between "this query is cheap" and "nobody checked". The remaining guardrails, row
 * limits, the statement timeout and the read-only connection, all still apply.
 */
public class GenericDialect implements SqlDialect {

    private final String name;

    public GenericDialect(String name) {
        this.name = (name != null && !name.isBlank()) ? name : "Unknown";
    }

    @Override
    public String name() {
        return this.name;
    }

    @Override
    public CostEstimate estimate(DataSource dataSource, String sql) {
        return CostEstimate.unavailable(
                "cost estimation is only implemented for PostgreSQL; row limit and query timeout still apply");
    }

    @Override
    public String readOnlyTransactionStatement() {
        // Left to JDBC's Connection#setReadOnly, which the executor always sets.
        return null;
    }
}
