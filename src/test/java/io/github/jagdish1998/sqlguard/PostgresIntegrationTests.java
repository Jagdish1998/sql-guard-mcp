package io.github.jagdish1998.sqlguard;

import java.sql.SQLException;

import io.github.jagdish1998.sqlguard.db.QueryExecutor;
import io.github.jagdish1998.sqlguard.dialect.PostgresDialect;
import io.github.jagdish1998.sqlguard.dialect.SqlDialect;
import io.github.jagdish1998.sqlguard.guard.PolicyViolationException;
import io.github.jagdish1998.sqlguard.guard.ViolationReason;
import io.github.jagdish1998.sqlguard.mcp.SqlGuardTools;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * The parts that only a real PostgreSQL server can prove.
 *
 * <p>Skipped unless {@code SQLGUARD_TEST_PG_URL} is set, so the suite still runs on a
 * machine with no database and no Docker. CI sets it against a Postgres service container,
 * which is what makes the green badge mean something: the H2 tests cover the logic, and this
 * covers the two claims that depend on the engine itself.
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "SQLGUARD_TEST_PG_URL", matches = ".+")
@TestPropertySource(properties = {
        "spring.datasource.url=${SQLGUARD_TEST_PG_URL}",
        "spring.datasource.username=${SQLGUARD_TEST_PG_USER:postgres}",
        "spring.datasource.password=${SQLGUARD_TEST_PG_PASSWORD:postgres}",
        "spring.datasource.driver-class-name=org.postgresql.Driver",
        "spring.datasource.hikari.read-only=false",
        "spring.sql.init.mode=always",
        "sqlguard.max-rows=5"
})
class PostgresIntegrationTests {

    @Autowired
    private SqlGuardTools tools;

    @Autowired
    private SqlDialect dialect;

    @Autowired
    private QueryExecutor executor;

    @Test
    void detectsPostgresAndSelectsTheRightDialect() {
        assertThat(this.dialect).isInstanceOf(PostgresDialect.class);
        assertThat(this.dialect.name()).isEqualTo("PostgreSQL");
    }

    @Test
    @DisplayName("the planner gives a real estimate before the query runs")
    void producesACostEstimate() {
        SqlGuardTools.ExplainResponse response = this.tools.explainQuery("SELECT * FROM orders");

        assertThat(response.estimateAvailable()).isTrue();
        assertThat(response.estimatedRows()).isGreaterThanOrEqualTo(0L);
        assertThat(response.estimatedCost()).isGreaterThanOrEqualTo(0d);
        assertThat(response.planSummary()).isNotBlank();
        assertThat(response.withinLimits()).isTrue();
    }

    @Test
    void runsQueriesAgainstPostgres() throws Exception {
        SqlGuardTools.RunQueryResponse response = this.tools.runQuery(
                "SELECT id, status FROM orders ORDER BY id");

        assertThat(response.rowCount()).isEqualTo(5);
        assertThat(response.plannerRowEstimate()).isNotNull();
    }

    @Test
    @DisplayName("the read-only transaction blocks a write even when the validator is bypassed")
    void readOnlyTransactionIsTheRealBackstop() throws Exception {
        // This is the test that justifies the layering. It hands a DELETE straight to the
        // executor, skipping the parser entirely, to show that static analysis is not the
        // only thing standing between a model and the data. PostgreSQL refuses it because
        // the transaction was marked read-only.
        assertThatExceptionOfType(SQLException.class)
                .isThrownBy(() -> this.executor.execute("DELETE FROM orders", 10))
                .withMessageContaining("read-only");

        // And nothing was lost.
        assertThat(countOrders()).isEqualTo(10);
    }

    @Test
    @DisplayName("system catalogs stay hidden on a database that really has them")
    void refusesRealSystemCatalogReads() {
        assertThatExceptionOfType(PolicyViolationException.class)
                .isThrownBy(() -> this.tools.runQuery("SELECT usename FROM pg_catalog.pg_user"))
                .satisfies(refusal ->
                        assertThat(refusal.reason()).isEqualTo(ViolationReason.SYSTEM_CATALOG));
    }

    @Test
    void listsTablesFromTheRealSchema() throws Exception {
        SqlGuardTools.ListTablesResponse response = this.tools.listTables();

        assertThat(response.dialect()).isEqualTo("PostgreSQL");
        assertThat(response.tables())
                .extracting(table -> table.name().toLowerCase())
                .contains("customers", "orders");
    }

    private long countOrders() throws Exception {
        SqlGuardTools.RunQueryResponse response = this.tools.runQuery("SELECT count(*) AS n FROM orders");
        Object value = response.rows().get(0).values().iterator().next();
        return Long.parseLong(String.valueOf(value));
    }
}
