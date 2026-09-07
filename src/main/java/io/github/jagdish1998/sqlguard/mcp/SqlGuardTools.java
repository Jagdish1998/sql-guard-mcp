package io.github.jagdish1998.sqlguard.mcp;

import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.sql.DataSource;

import io.github.jagdish1998.sqlguard.audit.AuditEvent;
import io.github.jagdish1998.sqlguard.audit.AuditLog;
import io.github.jagdish1998.sqlguard.config.SqlGuardProperties;
import io.github.jagdish1998.sqlguard.db.QueryExecutor;
import io.github.jagdish1998.sqlguard.db.QueryResult;
import io.github.jagdish1998.sqlguard.db.SchemaInspector;
import io.github.jagdish1998.sqlguard.dialect.CostEstimate;
import io.github.jagdish1998.sqlguard.dialect.SqlDialect;
import io.github.jagdish1998.sqlguard.guard.PolicyViolationException;
import io.github.jagdish1998.sqlguard.guard.ReadOnlySqlValidator;
import io.github.jagdish1998.sqlguard.guard.RowLimitRewriter;
import io.github.jagdish1998.sqlguard.guard.ValidatedQuery;
import io.github.jagdish1998.sqlguard.guard.ViolationReason;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * The tools an MCP client sees.
 *
 * <p>Descriptions here are part of the interface, not documentation. They are the only
 * thing the model reads before deciding which tool to call and what to pass, so each one
 * states the constraint it enforces. Saying "SELECT only, capped at a few hundred rows"
 * in the description means the model writes a compliant query on the first attempt
 * instead of discovering the rules through a sequence of refusals.
 *
 * <p>Every tool is marked {@code readOnlyHint = true}. Clients use that hint to decide
 * whether a call needs human approval, and it is accurate here: nothing in this server
 * can modify the database.
 */
@Component
public class SqlGuardTools {

    private final ReadOnlySqlValidator validator;

    private final RowLimitRewriter rewriter;

    private final QueryExecutor executor;

    private final SchemaInspector schemaInspector;

    private final SqlDialect dialect;

    private final DataSource dataSource;

    private final SqlGuardProperties properties;

    private final AuditLog auditLog;

    public SqlGuardTools(ReadOnlySqlValidator validator, RowLimitRewriter rewriter, QueryExecutor executor,
            SchemaInspector schemaInspector, SqlDialect dialect, DataSource dataSource,
            SqlGuardProperties properties, AuditLog auditLog) {
        this.validator = validator;
        this.rewriter = rewriter;
        this.executor = executor;
        this.schemaInspector = schemaInspector;
        this.dialect = dialect;
        this.dataSource = dataSource;
        this.properties = properties;
        this.auditLog = auditLog;
    }

    @McpTool(
            name = "list_tables",
            description = """
                    List every table and view this server is permitted to read, with its schema and type.
                    Start here when you do not already know the shape of the database. Tables outside the
                    configured allow-list are not returned and cannot be queried.""",
            annotations = @McpTool.McpAnnotations(
                    title = "List readable tables",
                    readOnlyHint = true,
                    destructiveHint = false,
                    idempotentHint = true,
                    openWorldHint = false))
    public ListTablesResponse listTables() throws SQLException {
        long startedAt = System.nanoTime();
        List<SchemaInspector.TableInfo> tables = this.schemaInspector.listTables();
        record(startedAt, "list_tables", "<metadata>", AuditEvent.Outcome.ALLOWED, null, Set.of(), tables.size());
        return new ListTablesResponse(this.dialect.name(), tables.size(), tables);
    }

    @McpTool(
            name = "describe_table",
            description = """
                    Show the columns, types, nullability and primary key of one table. Columns whose values
                    will arrive masked are flagged as redacted, so prefer a different column when you need
                    the real value. Accepts either 'table' or 'schema.table'.""",
            annotations = @McpTool.McpAnnotations(
                    title = "Describe a table",
                    readOnlyHint = true,
                    destructiveHint = false,
                    idempotentHint = true,
                    openWorldHint = false))
    public SchemaInspector.TableDetail describeTable(
            @McpToolParam(description = "Table name, optionally schema-qualified, e.g. 'orders' or 'public.orders'",
                    required = true) String table) throws SQLException {
        long startedAt = System.nanoTime();
        SchemaInspector.TableDetail detail = this.schemaInspector.describeTable(table);
        record(startedAt, "describe_table", "<metadata: " + table + ">", AuditEvent.Outcome.ALLOWED, null,
                Set.of(String.valueOf(table)), detail.columns().size());
        return detail;
    }

    @McpTool(
            name = "explain_query",
            description = """
                    Check a SELECT against policy and ask the query planner what it would cost, without
                    running it and without returning any data. Use this when a query might be expensive,
                    or to find out why run_query refused something.""",
            annotations = @McpTool.McpAnnotations(
                    title = "Explain a query",
                    readOnlyHint = true,
                    destructiveHint = false,
                    idempotentHint = true,
                    openWorldHint = false))
    public ExplainResponse explainQuery(
            @McpToolParam(description = "A single SELECT statement", required = true) String sql) {
        long startedAt = System.nanoTime();
        ValidatedQuery validated;
        try {
            validated = this.validator.validate(sql);
        }
        catch (PolicyViolationException ex) {
            record(startedAt, "explain_query", sql, AuditEvent.Outcome.REFUSED, ex.reason().name(), Set.of(), -1);
            throw ex;
        }

        RowLimitRewriter.Rewrite rewrite = this.rewriter.apply(validated);
        CostEstimate estimate = this.dialect.estimate(this.dataSource, rewrite.sql());
        boolean withinLimits = isWithinCostLimits(estimate);

        record(startedAt, "explain_query", sql, AuditEvent.Outcome.ALLOWED, null, validated.tables(), -1);

        return new ExplainResponse(rewrite.sql(), validated.tables(), estimate.available(),
                estimate.estimatedRows(), estimate.totalCost(), estimate.planSummary(), withinLimits,
                this.properties.getMaxEstimatedRows(), this.properties.getMaxEstimatedCost());
    }

    @McpTool(
            name = "run_query",
            description = """
                    Run one read-only SELECT and return the rows.

                    Rules enforced by the server, not suggestions: exactly one statement, SELECT only, no
                    writes anywhere including inside a WITH clause, no row locking, and no reads of database
                    system catalogs. Results are capped at a few hundred rows; if 'truncated' comes back
                    true, add a WHERE clause or an ORDER BY with your own smaller LIMIT rather than asking
                    for everything. Columns holding credentials or personal data arrive masked, and the
                    masked ones are listed in 'redactedColumns'.

                    A refusal explains which rule was broken. Read it and adjust the query; sending the
                    same shape again will be refused again.""",
            annotations = @McpTool.McpAnnotations(
                    title = "Run a read-only query",
                    readOnlyHint = true,
                    destructiveHint = false,
                    idempotentHint = true,
                    openWorldHint = false))
    public RunQueryResponse runQuery(
            @McpToolParam(description = "A single SELECT statement", required = true) String sql)
            throws SQLException {
        long startedAt = System.nanoTime();

        ValidatedQuery validated;
        try {
            validated = this.validator.validate(sql);
        }
        catch (PolicyViolationException ex) {
            record(startedAt, "run_query", sql, AuditEvent.Outcome.REFUSED, ex.reason().name(), Set.of(), -1);
            throw ex;
        }

        RowLimitRewriter.Rewrite rewrite = this.rewriter.apply(validated);

        // Cost is checked before execution, so an expensive query is refused rather than
        // merely timed out. A timeout still burns the work; this does not.
        CostEstimate estimate = this.dialect.estimate(this.dataSource, rewrite.sql());
        if (!isWithinCostLimits(estimate)) {
            PolicyViolationException violation = new PolicyViolationException(
                    ViolationReason.COST_LIMIT_EXCEEDED,
                    "planner estimated rows=%d cost=%.0f (%s); limits are rows=%d cost=%.0f".formatted(
                            estimate.estimatedRows(), estimate.totalCost(), estimate.planSummary(),
                            this.properties.getMaxEstimatedRows(), this.properties.getMaxEstimatedCost()));
            record(startedAt, "run_query", sql, AuditEvent.Outcome.REFUSED,
                    ViolationReason.COST_LIMIT_EXCEEDED.name(), validated.tables(), -1);
            throw violation;
        }

        QueryResult result;
        try {
            result = this.executor.execute(rewrite.sql(), rewrite.appliedMaxRows());
        }
        catch (SQLException ex) {
            record(startedAt, "run_query", sql, AuditEvent.Outcome.REFUSED, "SQL_ERROR", validated.tables(), -1);
            throw ex;
        }

        recordWithRedactions(startedAt, "run_query", sql, AuditEvent.Outcome.ALLOWED, null, validated.tables(),
                result.rowCount(), result.redactedColumns());

        return new RunQueryResponse(rewrite.sql(), result.columns(), result.rows(), result.rowCount(),
                result.truncated(), result.appliedLimit(), result.redactedColumns(), result.elapsedMillis(),
                estimate.available() ? estimate.estimatedRows() : null);
    }

    @McpTool(
            name = "recent_activity",
            description = """
                    Return the most recent decisions this server made, allowed and refused alike. Useful for
                    reviewing what has been asked of the database in this session, and for seeing which
                    rules refusals hit.""",
            annotations = @McpTool.McpAnnotations(
                    title = "Recent audited activity",
                    readOnlyHint = true,
                    destructiveHint = false,
                    idempotentHint = true,
                    openWorldHint = false))
    public List<AuditEvent> recentActivity(
            @McpToolParam(description = "How many entries to return, newest first. Defaults to 20.",
                    required = false) Integer limit) {
        return this.auditLog.recent((limit != null && limit > 0) ? limit : 20);
    }

    /**
     * An unavailable estimate is not treated as a violation. On a database without cost
     * estimation the row cap and timeout are still in force, so refusing every query would
     * make the server useless rather than safer.
     */
    private boolean isWithinCostLimits(CostEstimate estimate) {
        if (!estimate.available()) {
            return true;
        }
        long maxRows = this.properties.getMaxEstimatedRows();
        double maxCost = this.properties.getMaxEstimatedCost();
        if (maxRows >= 0 && estimate.estimatedRows() > maxRows) {
            return false;
        }
        return !(maxCost >= 0 && estimate.totalCost() > maxCost);
    }

    private void record(long startedAt, String tool, String sql, AuditEvent.Outcome outcome, String reason,
            Set<String> tables, int rows) {
        recordWithRedactions(startedAt, tool, sql, outcome, reason, tables, rows, List.of());
    }

    private void recordWithRedactions(long startedAt, String tool, String sql, AuditEvent.Outcome outcome,
            String reason, Set<String> tables, int rows, List<String> redactedColumns) {
        long durationMillis = (System.nanoTime() - startedAt) / 1_000_000L;
        this.auditLog.record(new AuditEvent(Instant.now(), tool, sql, outcome, reason,
                Set.copyOf(tables), rows, new ArrayList<>(redactedColumns), durationMillis));
    }

    /**
     * @param dialect the database behind this server
     */
    public record ListTablesResponse(String dialect, int tableCount, List<SchemaInspector.TableInfo> tables) {
    }

    /**
     * @param withinLimits    whether run_query would accept this statement on cost grounds
     * @param estimateAvailable false when the database cannot produce a plan estimate, in
     *                          which case the row and cost figures are meaningless
     */
    public record ExplainResponse(
            String effectiveSql,
            Set<String> tables,
            boolean estimateAvailable,
            long estimatedRows,
            double estimatedCost,
            String planSummary,
            boolean withinLimits,
            long maxEstimatedRows,
            double maxEstimatedCost) {
    }

    /**
     * @param effectiveSql the statement as actually executed, after the row limit was applied
     * @param truncated    true when the result hit the ceiling, meaning more rows may exist
     * @param plannerRowEstimate the planner's guess, or {@code null} when unavailable
     */
    public record RunQueryResponse(
            String effectiveSql,
            List<String> columns,
            List<Map<String, Object>> rows,
            int rowCount,
            boolean truncated,
            int appliedLimit,
            List<String> redactedColumns,
            long elapsedMillis,
            Long plannerRowEstimate) {
    }
}
