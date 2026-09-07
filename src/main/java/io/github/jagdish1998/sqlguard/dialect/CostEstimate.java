package io.github.jagdish1998.sqlguard.dialect;

/**
 * What the query planner thinks a statement will cost, before it runs.
 *
 * @param available    whether the database gave usable numbers. A dialect that cannot
 *                     produce an estimate reports {@code false} rather than zero, because
 *                     zero would read as "free" and wave the query through.
 * @param estimatedRows planner row estimate, or -1 when unavailable
 * @param totalCost     planner cost in its own arbitrary units, or -1 when unavailable
 * @param planSummary   the top plan node, useful for explaining a refusal
 */
public record CostEstimate(boolean available, long estimatedRows, double totalCost, String planSummary) {

    public static CostEstimate unavailable(String reason) {
        return new CostEstimate(false, -1L, -1d, reason);
    }

    public static CostEstimate of(long estimatedRows, double totalCost, String planSummary) {
        return new CostEstimate(true, estimatedRows, totalCost, planSummary);
    }
}
