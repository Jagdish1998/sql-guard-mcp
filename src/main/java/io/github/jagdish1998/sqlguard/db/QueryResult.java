package io.github.jagdish1998.sqlguard.db;

import java.util.List;
import java.util.Map;

/**
 * The outcome of a permitted query.
 *
 * <p>The metadata fields exist so the model is never misled by its own results. A
 * truncated result set that looks complete leads to confidently wrong answers ("this
 * customer has 200 orders"), and a masked column that is not flagged leads the model to
 * report {@code ***} as though it were the stored value.
 *
 * @param columns          column labels in result order
 * @param rows             row maps, already redacted
 * @param rowCount         rows actually returned
 * @param truncated        whether more rows existed beyond the applied limit
 * @param appliedLimit     the row ceiling in force for this call
 * @param redactedColumns  columns that were masked
 * @param elapsedMillis    server-side execution time
 */
public record QueryResult(
        List<String> columns,
        List<Map<String, Object>> rows,
        int rowCount,
        boolean truncated,
        int appliedLimit,
        List<String> redactedColumns,
        long elapsedMillis) {
}
