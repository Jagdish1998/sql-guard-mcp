package io.github.jagdish1998.sqlguard.guard;

import io.github.jagdish1998.sqlguard.config.SqlGuardProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RowLimitRewriterTests {

    private SqlGuardProperties properties;

    private ReadOnlySqlValidator validator;

    private RowLimitRewriter rewriter;

    @BeforeEach
    void setUp() {
        this.properties = new SqlGuardProperties();
        this.properties.setMaxRows(100);
        this.validator = new ReadOnlySqlValidator(this.properties);
        this.rewriter = new RowLimitRewriter(this.properties);
    }

    private RowLimitRewriter.Rewrite rewrite(String sql) {
        return this.rewriter.apply(this.validator.validate(sql));
    }

    @Test
    void addsALimitWhenThereIsNone() {
        RowLimitRewriter.Rewrite result = rewrite("SELECT * FROM orders");

        assertThat(result.sql()).containsIgnoringCase("LIMIT 100");
        assertThat(result.limitWasApplied()).isTrue();
        assertThat(result.requestedLimit()).isNull();
    }

    @Test
    void lowersALimitThatIsTooHigh() {
        RowLimitRewriter.Rewrite result = rewrite("SELECT * FROM orders LIMIT 5000");

        assertThat(result.sql()).containsIgnoringCase("LIMIT 100");
        assertThat(result.sql()).doesNotContain("5000");
        assertThat(result.limitWasApplied()).isTrue();
        assertThat(result.requestedLimit()).isEqualTo(5000L);
    }

    @Test
    @DisplayName("a caller asking for fewer rows than the cap keeps their own limit")
    void leavesASmallerLimitAlone() {
        RowLimitRewriter.Rewrite result = rewrite("SELECT * FROM orders LIMIT 10");

        assertThat(result.sql()).containsIgnoringCase("LIMIT 10");
        assertThat(result.limitWasApplied()).isFalse();
        assertThat(result.requestedLimit()).isEqualTo(10L);
    }

    @Test
    @DisplayName("rewriting the tree, not appending text, keeps OFFSET intact")
    void preservesOffset() {
        // Appending " LIMIT 100" to this string would have produced two LIMIT clauses.
        RowLimitRewriter.Rewrite result = rewrite("SELECT * FROM orders LIMIT 5000 OFFSET 20");

        assertThat(result.sql()).containsIgnoringCase("LIMIT 100");
        assertThat(result.sql()).containsIgnoringCase("OFFSET 20");
        assertThat(result.sql().toUpperCase()).satisfies(sql ->
                assertThat(sql.split("LIMIT", -1).length - 1).isEqualTo(1));
    }

    @Test
    void limitsAUnion() {
        RowLimitRewriter.Rewrite result = rewrite("SELECT id FROM orders UNION SELECT id FROM archived_orders");

        assertThat(result.sql()).containsIgnoringCase("LIMIT 100");
        assertThat(result.limitWasApplied()).isTrue();
    }

    @Test
    void limitsAStatementWithACommonTableExpression() {
        RowLimitRewriter.Rewrite result = rewrite("WITH recent AS (SELECT * FROM orders) SELECT * FROM recent");

        assertThat(result.sql()).containsIgnoringCase("LIMIT 100");
        assertThat(result.limitWasApplied()).isTrue();
    }

    @Test
    @DisplayName("a limit the rewriter cannot read is replaced rather than trusted")
    void overridesANonLiteralLimit() {
        // "LIMIT ALL" means no limit. Leaving it alone because it parsed would defeat the cap.
        RowLimitRewriter.Rewrite result = rewrite("SELECT * FROM orders LIMIT ALL");

        assertThat(result.sql()).containsIgnoringCase("LIMIT 100");
        assertThat(result.limitWasApplied()).isTrue();
    }

    @Test
    void reportsTheCeilingInForce() {
        assertThat(rewrite("SELECT * FROM orders").appliedMaxRows()).isEqualTo(100);
    }
}
