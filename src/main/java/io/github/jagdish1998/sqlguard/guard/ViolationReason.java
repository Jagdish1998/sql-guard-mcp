package io.github.jagdish1998.sqlguard.guard;

/**
 * Why a query was refused.
 *
 * <p>Each reason carries guidance written for the model rather than for a human operator.
 * A tool that only says "denied" invites the model to retry the same query in a slightly
 * different shape; a tool that explains the rule lets it correct course on the next turn.
 */
public enum ViolationReason {

    EMPTY_STATEMENT("The query was empty. Send a single SELECT statement."),

    PARSE_FAILED("The query could not be parsed as SQL. Check the syntax and send one complete SELECT statement."),

    MULTIPLE_STATEMENTS("Only one statement per call is allowed. Remove the semicolon-separated extras and send a single SELECT."),

    NOT_READ_ONLY("Only SELECT statements are allowed. This server cannot run INSERT, UPDATE, DELETE, DDL, or any other write."),

    DATA_MODIFYING_CTE("A WITH clause contained a writing statement. Common table expressions must themselves be SELECT only."),

    SELECT_INTO("SELECT ... INTO creates a table, which is a write. Use a plain SELECT instead."),

    LOCKING_CLAUSE("Row locking clauses such as FOR UPDATE and FOR SHARE are not allowed on a read-only connection."),

    BLOCKED_FUNCTION("The query referenced a function that is not permitted, such as a filesystem, sleep, or outbound-connection helper."),

    BLOCKED_TABLE("The query referenced a table that this server is configured to keep hidden."),

    SCHEMA_NOT_ALLOWED("The query referenced a schema outside the configured allow-list."),

    SYSTEM_CATALOG("Direct reads of database system catalogs are not allowed. Use the list_tables and describe_table tools for schema information."),

    COST_LIMIT_EXCEEDED("The query planner estimated this query is too expensive to run. Add a WHERE clause, join fewer tables, or narrow the time range.");

    private final String guidance;

    ViolationReason(String guidance) {
        this.guidance = guidance;
    }

    /** Advice the model can act on, returned alongside the refusal. */
    public String guidance() {
        return this.guidance;
    }
}
