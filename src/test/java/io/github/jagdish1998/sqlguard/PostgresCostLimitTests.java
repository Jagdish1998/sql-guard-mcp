package io.github.jagdish1998.sqlguard;

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
 * Cost-budget enforcement, which needs both a real planner and a deliberately tiny budget.
 *
 * <p>A separate class because the budget has to be set before the context starts, and the
 * point of the check is that an expensive query is refused rather than merely timed out. A
 * timeout still pays for the work up to the moment it fires.
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
        // A budget of one row makes any real table scan over budget.
        "sqlguard.max-estimated-rows=1"
})
class PostgresCostLimitTests {

    @Autowired
    private SqlGuardTools tools;

    @Test
    @DisplayName("run_query refuses a plan the planner says is over budget")
    void refusesOverBudgetQueries() {
        assertThatExceptionOfType(PolicyViolationException.class)
                .isThrownBy(() -> this.tools.runQuery("SELECT * FROM orders"))
                .satisfies(refusal -> {
                    assertThat(refusal.reason()).isEqualTo(ViolationReason.COST_LIMIT_EXCEEDED);
                    // The refusal has to be actionable, so it states the estimate and the limit.
                    assertThat(refusal.getMessage()).contains("planner estimated");
                });
    }

    @Test
    @DisplayName("explain_query reports the same verdict without throwing")
    void explainReportsTheBudgetVerdict() {
        SqlGuardTools.ExplainResponse response = this.tools.explainQuery("SELECT * FROM orders");

        assertThat(response.estimateAvailable()).isTrue();
        assertThat(response.withinLimits()).isFalse();
        assertThat(response.maxEstimatedRows()).isEqualTo(1L);
    }

    @Test
    @DisplayName("a query the planner considers cheap still runs")
    void allowsQueriesInsideTheBudget() throws Exception {
        SqlGuardTools.RunQueryResponse response = this.tools.runQuery("SELECT id FROM orders WHERE id = 1");

        assertThat(response.rowCount()).isEqualTo(1);
    }
}
