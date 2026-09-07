package io.github.jagdish1998.sqlguard.guard;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import io.github.jagdish1998.sqlguard.config.SqlGuardProperties;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.ParenthesedStatement;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.delete.ParenthesedDelete;
import net.sf.jsqlparser.statement.insert.ParenthesedInsert;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.statement.select.WithItem;
import net.sf.jsqlparser.statement.update.ParenthesedUpdate;
import net.sf.jsqlparser.util.TablesNamesFinder;
import org.springframework.stereotype.Component;

/**
 * Decides whether a statement is genuinely read-only.
 *
 * <p>The important design choice here is that the SQL is parsed with a real grammar
 * rather than pattern-matched. Keyword blocklists on raw text lose to comment injection
 * ({@code DEL/**&#47;ETE}), to case and whitespace games, and to string literals that
 * happen to contain a keyword. Once the statement is a parse tree, "is this a SELECT"
 * stops being a guess.
 *
 * <p>Checks that do run over text run over {@link Statement#toString()}, the statement
 * re-serialised from the tree. Comments are gone by then and layout is normalised, so
 * the text being scanned is the text the database will actually execute.
 *
 * <p>None of this replaces a read-only database user and a read-only transaction. It is
 * the layer that gives the model a useful error instead of a driver stack trace, and that
 * blocks the reads a read-only session would otherwise happily perform.
 */
@Component
public class ReadOnlySqlValidator {

    /** Schemas that expose database internals. Reachable for a normal user, and not useful to a model. */
    private static final Set<String> SYSTEM_SCHEMAS = Set.of(
            "pg_catalog", "information_schema", "pg_toast", "pg_temp", "sys", "mysql", "performance_schema");

    /**
     * Row-locking syntax, matched against the normalised statement.
     *
     * <p>This duplicates the {@link Select#getForClause()} check on purpose. The two
     * layers disagree only if the parser models a locking clause somewhere unexpected,
     * and in that case the conservative answer is still to refuse.
     */
    private static final Pattern LOCKING_CLAUSE = Pattern.compile(
            "\\bFOR\\s+(UPDATE|SHARE|NO\\s+KEY\\s+UPDATE|KEY\\s+SHARE)\\b", Pattern.CASE_INSENSITIVE);

    /** Single-quoted literals, so a literal mentioning a blocked function is not a violation. */
    private static final Pattern STRING_LITERAL = Pattern.compile("'(?:[^']|'')*'");

    private final SqlGuardProperties properties;

    public ReadOnlySqlValidator(SqlGuardProperties properties) {
        this.properties = properties;
    }

    /**
     * @throws PolicyViolationException if the statement is anything other than a plain read
     */
    public ValidatedQuery validate(String rawSql) {
        if (rawSql == null || rawSql.isBlank()) {
            throw new PolicyViolationException(ViolationReason.EMPTY_STATEMENT);
        }

        Statements statements;
        try {
            statements = CCJSqlParserUtil.parseStatements(rawSql);
        }
        catch (JSQLParserException ex) {
            throw new PolicyViolationException(ViolationReason.PARSE_FAILED, rootMessage(ex));
        }

        if (statements.size() != 1) {
            throw new PolicyViolationException(ViolationReason.MULTIPLE_STATEMENTS,
                    "found " + statements.size() + " statements");
        }

        Statement statement = statements.get(0);
        if (!(statement instanceof Select select)) {
            throw new PolicyViolationException(ViolationReason.NOT_READ_ONLY,
                    "statement type was " + statement.getClass().getSimpleName());
        }

        inspectSelect(select);

        String normalised = statement.toString();
        String scannable = STRING_LITERAL.matcher(normalised).replaceAll("''");

        if (LOCKING_CLAUSE.matcher(scannable).find()) {
            throw new PolicyViolationException(ViolationReason.LOCKING_CLAUSE);
        }
        rejectBlockedFunctions(scannable);

        Set<String> tables = collectTables(statement);
        inspectTables(tables);

        return new ValidatedQuery(select, normalised, tables);
    }

    /**
     * Walks the select tree looking for writes hiding inside a read.
     *
     * <p>The top-level statement being a SELECT is not sufficient. PostgreSQL allows a
     * writing statement inside a common table expression, so
     * {@code WITH gone AS (DELETE FROM users RETURNING *) SELECT * FROM gone} parses as a
     * Select while deleting every row. That is the case this walk exists for.
     */
    private void inspectSelect(Select select) {
        if (select.getForClause() != null) {
            throw new PolicyViolationException(ViolationReason.LOCKING_CLAUSE,
                    "FOR clause: " + select.getForClause());
        }

        List<WithItem<?>> withItems = select.getWithItemsList();
        if (withItems != null) {
            for (WithItem<?> withItem : withItems) {
                inspectWithItem(withItem);
            }
        }

        if (select instanceof PlainSelect plainSelect) {
            boolean intoTables = plainSelect.getIntoTables() != null && !plainSelect.getIntoTables().isEmpty();
            if (intoTables || plainSelect.getIntoTempTable() != null) {
                throw new PolicyViolationException(ViolationReason.SELECT_INTO);
            }
        }
        else if (select instanceof SetOperationList setOperations) {
            if (setOperations.getSelects() != null) {
                setOperations.getSelects().forEach(this::inspectSelect);
            }
        }
        else if (select instanceof ParenthesedSelect parenthesed) {
            if (parenthesed.getSelect() != null) {
                inspectSelect(parenthesed.getSelect());
            }
        }
    }

    /**
     * Checks one common table expression.
     *
     * <p>The type is read from {@link WithItem#getParenthesedStatement()} rather than by
     * probing {@code getInsert()}, {@code getUpdate()} and {@code getDelete()}. Those
     * accessors cast without checking, so asking a SELECT-bearing item whether it is an
     * INSERT throws {@link ClassCastException} instead of answering no.
     *
     * <p>Anything that is not a select is refused, which also means a statement type
     * JSqlParser adds later is refused by default rather than admitted by omission.
     */
    private void inspectWithItem(WithItem<?> withItem) {
        ParenthesedStatement inner = withItem.getParenthesedStatement();
        if (inner == null) {
            return;
        }
        if (inner instanceof ParenthesedSelect nested) {
            inspectSelect(nested);
            return;
        }
        throw new PolicyViolationException(ViolationReason.DATA_MODIFYING_CTE,
                "CTE '" + withItem.getUnquotedAliasName() + "' performs " + describe(inner));
    }

    private String describe(ParenthesedStatement statement) {
        if (statement instanceof ParenthesedInsert) {
            return "an INSERT";
        }
        if (statement instanceof ParenthesedUpdate) {
            return "an UPDATE";
        }
        if (statement instanceof ParenthesedDelete) {
            return "a DELETE";
        }
        return "a write (" + statement.getClass().getSimpleName() + ")";
    }

    private void rejectBlockedFunctions(String scannableSql) {
        for (String function : this.properties.getBlockedFunctions()) {
            if (function == null || function.isBlank()) {
                continue;
            }
            // Match a call, not a mention: the name has to be followed by an opening paren.
            Pattern call = Pattern.compile("\\b" + Pattern.quote(function.trim()) + "\\s*\\(",
                    Pattern.CASE_INSENSITIVE);
            if (call.matcher(scannableSql).find()) {
                throw new PolicyViolationException(ViolationReason.BLOCKED_FUNCTION,
                        "function '" + function.trim() + "' is not permitted");
            }
        }
    }

    private Set<String> collectTables(Statement statement) {
        try {
            Set<String> found = new TablesNamesFinder<>().getTables(statement);
            return (found != null) ? new LinkedHashSet<>(found) : Set.of();
        }
        catch (RuntimeException ex) {
            // A statement shape the finder cannot walk is a statement we cannot vet.
            throw new PolicyViolationException(ViolationReason.PARSE_FAILED,
                    "table references could not be resolved: " + rootMessage(ex));
        }
    }

    private void inspectTables(Set<String> tables) {
        List<String> blockedTables = this.properties.getBlockedTables();
        List<String> allowedSchemas = this.properties.getAllowedSchemas();

        for (String reference : tables) {
            String cleaned = reference.replace("\"", "").replace("`", "").trim();
            if (cleaned.isEmpty()) {
                continue;
            }

            int lastDot = cleaned.lastIndexOf('.');
            String schema = (lastDot > 0) ? cleaned.substring(0, lastDot) : null;
            String table = (lastDot > 0) ? cleaned.substring(lastDot + 1) : cleaned;
            String lowerTable = table.toLowerCase(Locale.ROOT);

            if (schema != null && SYSTEM_SCHEMAS.contains(schema.toLowerCase(Locale.ROOT))) {
                throw new PolicyViolationException(ViolationReason.SYSTEM_CATALOG, "schema '" + schema + "'");
            }
            if (lowerTable.startsWith("pg_")) {
                throw new PolicyViolationException(ViolationReason.SYSTEM_CATALOG, "table '" + table + "'");
            }

            boolean blocked = blockedTables.stream()
                    .filter(candidate -> candidate != null && !candidate.isBlank())
                    .anyMatch(candidate -> matchesTable(candidate.trim(), schema, table));
            if (blocked) {
                throw new PolicyViolationException(ViolationReason.BLOCKED_TABLE, "table '" + cleaned + "'");
            }

            // An unqualified name resolves through the connection's search_path, which the
            // database user controls. Only qualified names can be checked against the list.
            if (!allowedSchemas.isEmpty() && schema != null) {
                boolean permitted = allowedSchemas.stream()
                        .filter(candidate -> candidate != null && !candidate.isBlank())
                        .anyMatch(candidate -> candidate.trim().equalsIgnoreCase(schema));
                if (!permitted) {
                    throw new PolicyViolationException(ViolationReason.SCHEMA_NOT_ALLOWED,
                            "schema '" + schema + "' is not in " + allowedSchemas);
                }
            }
        }
    }

    /** A blocked entry may be bare ({@code secrets}) or qualified ({@code private.secrets}). */
    private boolean matchesTable(String blocked, String schema, String table) {
        if (blocked.contains(".")) {
            String qualified = (schema != null) ? schema + "." + table : table;
            return blocked.equalsIgnoreCase(qualified);
        }
        return blocked.equalsIgnoreCase(table);
    }

    private String rootMessage(Throwable throwable) {
        Throwable cursor = throwable;
        while (cursor.getCause() != null && cursor.getCause() != cursor) {
            cursor = cursor.getCause();
        }
        String message = cursor.getMessage();
        if (message == null || message.isBlank()) {
            return cursor.getClass().getSimpleName();
        }
        // Parser messages carry long grammar dumps that are noise to a model.
        String firstLine = message.lines().findFirst().orElse(message);
        return (firstLine.length() > 300) ? firstLine.substring(0, 300) + "..." : firstLine;
    }
}
