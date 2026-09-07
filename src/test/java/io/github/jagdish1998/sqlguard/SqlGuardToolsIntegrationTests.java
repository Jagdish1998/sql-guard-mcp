package io.github.jagdish1998.sqlguard;

import java.util.List;
import java.util.Map;

import io.github.jagdish1998.sqlguard.audit.AuditEvent;
import io.github.jagdish1998.sqlguard.audit.AuditLog;
import io.github.jagdish1998.sqlguard.db.SchemaInspector;
import io.github.jagdish1998.sqlguard.guard.PolicyViolationException;
import io.github.jagdish1998.sqlguard.guard.ViolationReason;
import io.github.jagdish1998.sqlguard.mcp.SqlGuardTools;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * End-to-end tests through the tool methods an MCP client would call, against a real
 * database with real data.
 *
 * <p>Runs on H2 in PostgreSQL mode so the suite needs no Docker daemon. That trade is
 * visible in one place only, the {@code SqlDialect} boundary, and the missing piece
 * (PostgreSQL's {@code EXPLAIN}) is covered by the Postgres job in CI.
 */
@SpringBootTest
@ActiveProfiles("test")
class SqlGuardToolsIntegrationTests {

    @Autowired
    private SqlGuardTools tools;

    @Autowired
    private AuditLog auditLog;

    @Test
    void listsTablesAndViewsItIsAllowedToRead() throws Exception {
        SqlGuardTools.ListTablesResponse response = this.tools.listTables();

        assertThat(response.tables())
                .extracting(SchemaInspector.TableInfo::name)
                .extracting(String::toLowerCase)
                .contains("customers", "orders", "order_summary");
        assertThat(response.tableCount()).isEqualTo(response.tables().size());
    }

    @Test
    void neverListsDatabaseInternals() throws Exception {
        SqlGuardTools.ListTablesResponse response = this.tools.listTables();

        assertThat(response.tables())
                .extracting(table -> String.valueOf(table.schema()).toLowerCase())
                .doesNotContain("information_schema", "pg_catalog");
    }

    @Test
    @DisplayName("describe_table flags the columns that will come back masked")
    void describesColumnsAndMarksSensitiveOnes() throws Exception {
        SchemaInspector.TableDetail detail = this.tools.describeTable("customers");

        assertThat(detail.primaryKeys()).extracting(String::toLowerCase).containsExactly("id");

        Map<String, Boolean> redactedByColumn = detail.columns().stream()
                .collect(java.util.stream.Collectors.toMap(
                        column -> column.name().toLowerCase(), SchemaInspector.ColumnInfo::redacted));

        assertThat(redactedByColumn)
                .containsEntry("email", true)
                .containsEntry("phone", true)
                .containsEntry("password_hash", true)
                .containsEntry("name", false)
                .containsEntry("id", false);
    }

    @Test
    void reportsAnUnknownTableClearly() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> this.tools.describeTable("no_such_table"))
                .withMessageContaining("Unknown table");
    }

    @Test
    void runsAPlainSelectAndReturnsRows() throws Exception {
        SqlGuardTools.RunQueryResponse response = this.tools.runQuery(
                "SELECT id, status, total FROM orders WHERE status = 'PENDING' ORDER BY id");

        assertThat(response.rowCount()).isEqualTo(3);
        assertThat(response.columns()).extracting(String::toLowerCase)
                .containsExactly("id", "status", "total");
        assertThat(response.rows()).allSatisfy(row ->
                assertThat(String.valueOf(row.get(keyFor(row, "status")))).isEqualTo("PENDING"));
        assertThat(response.redactedColumns()).isEmpty();
    }

    @Test
    @DisplayName("sensitive values are masked in the rows themselves, not just flagged")
    void masksSensitiveValuesInResults() throws Exception {
        SqlGuardTools.RunQueryResponse response = this.tools.runQuery(
                "SELECT name, email, password_hash FROM customers ORDER BY id");

        assertThat(response.redactedColumns()).extracting(String::toLowerCase)
                .contains("email", "password_hash");

        Map<String, Object> first = response.rows().get(0);
        assertThat(String.valueOf(first.get(keyFor(first, "name")))).isEqualTo("Alice Kumar");
        assertThat(String.valueOf(first.get(keyFor(first, "email")))).isEqualTo("a***@example.com");
        assertThat(String.valueOf(first.get(keyFor(first, "password_hash")))).isEqualTo("***");
    }

    @Test
    @DisplayName("hitting the row cap is reported rather than hidden")
    void capsRowsAndSaysSo() throws Exception {
        // sqlguard.max-rows is 5 in the test profile, and there are 10 orders.
        SqlGuardTools.RunQueryResponse response = this.tools.runQuery("SELECT id FROM orders");

        assertThat(response.rowCount()).isEqualTo(5);
        assertThat(response.appliedLimit()).isEqualTo(5);
        assertThat(response.truncated()).isTrue();
        assertThat(response.effectiveSql()).containsIgnoringCase("LIMIT 5");
    }

    @Test
    void doesNotClaimTruncationWhenEverythingFitted() throws Exception {
        SqlGuardTools.RunQueryResponse response = this.tools.runQuery("SELECT id FROM orders WHERE id = 1");

        assertThat(response.rowCount()).isEqualTo(1);
        assertThat(response.truncated()).isFalse();
    }

    @Test
    @DisplayName("the database is genuinely unchanged after a refused write")
    void refusesWritesAndLeavesDataIntact() throws Exception {
        assertThatExceptionOfType(PolicyViolationException.class)
                .isThrownBy(() -> this.tools.runQuery("DELETE FROM orders"))
                .satisfies(refusal -> assertThat(refusal.reason()).isEqualTo(ViolationReason.NOT_READ_ONLY));

        // The point of the test: prove nothing happened, rather than trusting the refusal.
        SqlGuardTools.RunQueryResponse after = this.tools.runQuery("SELECT count(*) AS n FROM orders");
        Map<String, Object> row = after.rows().get(0);
        assertThat(Long.parseLong(String.valueOf(row.get(keyFor(row, "n"))))).isEqualTo(10L);
    }

    @Test
    void refusesADeleteHiddenInACommonTableExpression() throws Exception {
        assertThatExceptionOfType(PolicyViolationException.class)
                .isThrownBy(() -> this.tools.runQuery(
                        "WITH gone AS (DELETE FROM orders RETURNING *) SELECT * FROM gone"))
                .satisfies(refusal ->
                        assertThat(refusal.reason()).isEqualTo(ViolationReason.DATA_MODIFYING_CTE));

        SqlGuardTools.RunQueryResponse after = this.tools.runQuery("SELECT count(*) AS n FROM orders");
        Map<String, Object> row = after.rows().get(0);
        assertThat(Long.parseLong(String.valueOf(row.get(keyFor(row, "n"))))).isEqualTo(10L);
    }

    @Test
    void refusesSystemCatalogReads() {
        assertThatExceptionOfType(PolicyViolationException.class)
                .isThrownBy(() -> this.tools.runQuery("SELECT * FROM information_schema.tables"))
                .satisfies(refusal -> assertThat(refusal.reason()).isEqualTo(ViolationReason.SYSTEM_CATALOG));
    }

    @Test
    @DisplayName("explain_query vets a statement without returning data")
    void explainsWithoutRunning() {
        SqlGuardTools.ExplainResponse response = this.tools.explainQuery("SELECT * FROM orders");

        assertThat(response.effectiveSql()).containsIgnoringCase("LIMIT 5");
        assertThat(response.tables()).extracting(String::toLowerCase).contains("orders");
        // H2 goes through GenericDialect, which reports no estimate rather than a fake one.
        assertThat(response.estimateAvailable()).isFalse();
        assertThat(response.withinLimits()).isTrue();
    }

    @Test
    void explainRefusesTheSameThingsRunQueryDoes() {
        assertThatExceptionOfType(PolicyViolationException.class)
                .isThrownBy(() -> this.tools.explainQuery("UPDATE orders SET total = 0"))
                .satisfies(refusal -> assertThat(refusal.reason()).isEqualTo(ViolationReason.NOT_READ_ONLY));
    }

    @Test
    @DisplayName("refusals are audited, not just thrown")
    void recordsRefusalsInTheAuditTrail() {
        assertThatExceptionOfType(PolicyViolationException.class)
                .isThrownBy(() -> this.tools.runQuery("DROP TABLE customers"));

        List<AuditEvent> recent = this.auditLog.recent(10);

        assertThat(recent).anySatisfy(event -> {
            assertThat(event.outcome()).isEqualTo(AuditEvent.Outcome.REFUSED);
            assertThat(event.violationReason()).isEqualTo(ViolationReason.NOT_READ_ONLY.name());
            assertThat(event.sql()).contains("DROP TABLE");
            assertThat(event.tool()).isEqualTo("run_query");
        });
    }

    @Test
    void recordsSuccessfulQueriesWithRedactionDetail() throws Exception {
        this.tools.runQuery("SELECT email FROM customers WHERE id = 1");

        List<AuditEvent> recent = this.auditLog.recent(5);

        assertThat(recent).anySatisfy(event -> {
            assertThat(event.outcome()).isEqualTo(AuditEvent.Outcome.ALLOWED);
            assertThat(event.redactedColumns()).extracting(String::toLowerCase).contains("email");
            assertThat(event.rowsReturned()).isEqualTo(1);
        });
    }

    @Test
    void exposesRecentActivityAsATool() throws Exception {
        this.tools.runQuery("SELECT 1 AS one");

        assertThat(this.tools.recentActivity(5)).isNotEmpty();
        assertThat(this.tools.recentActivity(null)).isNotEmpty();
    }

    /**
     * H2 may report labels in a different case than the query used, so tests resolve the
     * actual key instead of assuming one.
     */
    private String keyFor(Map<String, Object> row, String column) {
        return row.keySet().stream()
                .filter(key -> key.equalsIgnoreCase(column))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no column '" + column + "' in " + row.keySet()));
    }
}
