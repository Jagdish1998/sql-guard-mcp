package io.github.jagdish1998.sqlguard.audit;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * One decision, recorded whether it was allowed or refused.
 *
 * <p>Refusals are the entries worth keeping. A run of {@code NOT_READ_ONLY} and
 * {@code BLOCKED_TABLE} events in a short window is the signal that something is probing
 * the tool, and that is invisible if only successful queries are logged.
 *
 * @param at               when the call was decided
 * @param tool             which MCP tool was invoked
 * @param sql              the statement as submitted, truncated for storage
 * @param outcome          allowed or refused
 * @param violationReason  the refusal reason, or {@code null} when allowed
 * @param tables           tables the statement touched
 * @param rowsReturned     rows handed back, or -1 when the query did not run
 * @param redactedColumns  columns that were masked on the way out
 * @param durationMillis   wall-clock time spent on the call
 */
public record AuditEvent(
        Instant at,
        String tool,
        String sql,
        Outcome outcome,
        String violationReason,
        Set<String> tables,
        int rowsReturned,
        List<String> redactedColumns,
        long durationMillis) {

    public enum Outcome {
        ALLOWED,
        REFUSED
    }
}
