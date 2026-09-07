package io.github.jagdish1998.sqlguard.dialect;

import javax.sql.DataSource;

/**
 * The database-specific corners of the server.
 *
 * <p>Almost everything here is portable SQL, but two things are not: asking the planner
 * what a query will cost, and telling a session to refuse writes. Both are isolated behind
 * this interface, which is also what lets the test suite run against H2 with no Docker
 * daemon while production runs against PostgreSQL.
 */
public interface SqlDialect {

    /** Name reported through the tools, so a caller knows what they are talking to. */
    String name();

    /**
     * Asks the planner for an estimate without executing the statement.
     *
     * <p>Implementations must not run the query. On PostgreSQL that means {@code EXPLAIN}
     * without {@code ANALYZE}.
     */
    CostEstimate estimate(DataSource dataSource, String sql);

    /**
     * SQL that makes the current transaction refuse writes, or {@code null} when the
     * database has no such statement.
     *
     * <p>This is the check that actually holds. Everything the validator does is static
     * analysis and could in principle be wrong; a transaction the engine itself marked
     * read-only cannot be talked into writing.
     */
    String readOnlyTransactionStatement();
}
