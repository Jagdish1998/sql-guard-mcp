package io.github.jagdish1998.sqlguard.guard;

import io.github.jagdish1998.sqlguard.config.SqlGuardProperties;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.statement.select.Limit;
import net.sf.jsqlparser.statement.select.Select;
import org.springframework.stereotype.Component;

/**
 * Caps how many rows a query can return.
 *
 * <p>An over-broad query is clamped rather than refused. A model that asks for a whole
 * table usually wants to look at the table, and answering "here are the first 200 rows,
 * and there are more" moves the conversation forward where a refusal just costs a turn.
 * The response records that clamping happened so the model never mistakes a truncated
 * result for a complete one.
 *
 * <p>The limit is applied to the parse tree, not appended as text. Appending {@code LIMIT}
 * to a statement that already ends in {@code LIMIT 5000}, or to one wrapped in
 * parentheses, produces either a syntax error or a query that ignores the cap.
 */
@Component
public class RowLimitRewriter {

    private final SqlGuardProperties properties;

    public RowLimitRewriter(SqlGuardProperties properties) {
        this.properties = properties;
    }

    /**
     * Rewrites the statement so it cannot return more than the configured maximum.
     *
     * <p>Mutates the tree held by {@code query} and returns the resulting SQL.
     */
    public Rewrite apply(ValidatedQuery query) {
        int maxRows = this.properties.getMaxRows();
        Select select = query.select();
        Limit existing = select.getLimit();

        Long requested = readRowCount(existing);
        boolean clamped;

        if (requested == null) {
            // No limit at all, or one this rewriter cannot reason about (a parameter or an
            // expression). Either way the safe move is to impose our own.
            select.setLimit(limitOf(maxRows));
            clamped = true;
        }
        else if (requested > maxRows) {
            select.setLimit(limitOf(maxRows));
            clamped = true;
        }
        else {
            clamped = false;
        }

        return new Rewrite(select.toString(), maxRows, clamped, requested);
    }

    private Long readRowCount(Limit limit) {
        if (limit == null || limit.isLimitAll() || limit.isLimitNull()) {
            return null;
        }
        if (limit.getRowCount() instanceof LongValue longValue) {
            return longValue.getValue();
        }
        return null;
    }

    private Limit limitOf(int rows) {
        Limit limit = new Limit();
        limit.setRowCount(new LongValue(rows));
        return limit;
    }

    /**
     * @param sql             the statement to execute
     * @param appliedMaxRows  the ceiling in force
     * @param limitWasApplied whether this rewriter changed or added a limit
     * @param requestedLimit  the caller's own limit, or {@code null} if there was not a
     *                        literal one to read
     */
    public record Rewrite(String sql, int appliedMaxRows, boolean limitWasApplied, Long requestedLimit) {
    }
}
