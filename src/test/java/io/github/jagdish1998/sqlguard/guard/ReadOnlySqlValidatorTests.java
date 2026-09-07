package io.github.jagdish1998.sqlguard.guard;

import io.github.jagdish1998.sqlguard.config.SqlGuardProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * The security tests.
 *
 * <p>Each case here is a way somebody could try to turn a read-only tool into a writable
 * one. They are written as the attack rather than as the method under test, because
 * "rejects a DELETE dressed up as a CTE" is the property worth keeping, and it should stay
 * green even if the implementation is rewritten.
 */
class ReadOnlySqlValidatorTests {

    private ReadOnlySqlValidator validator;

    private SqlGuardProperties properties;

    @BeforeEach
    void setUp() {
        this.properties = new SqlGuardProperties();
        this.validator = new ReadOnlySqlValidator(this.properties);
    }

    private PolicyViolationException refusalOf(String sql) {
        return assertThatExceptionOfType(PolicyViolationException.class)
                .isThrownBy(() -> this.validator.validate(sql))
                .actual();
    }

    @Nested
    @DisplayName("permits ordinary reads")
    class Allows {

        @ParameterizedTest
        @ValueSource(strings = {
                "SELECT * FROM orders",
                "SELECT id, total FROM orders WHERE total > 100 ORDER BY total DESC",
                "SELECT o.id, c.name FROM orders o JOIN customers c ON c.id = o.customer_id",
                "SELECT count(*) AS n FROM orders GROUP BY status HAVING count(*) > 2",
                "SELECT * FROM orders UNION SELECT * FROM archived_orders",
                "WITH recent AS (SELECT * FROM orders LIMIT 10) SELECT * FROM recent",
                "SELECT * FROM orders WHERE customer_id IN (SELECT id FROM customers)",
                "SELECT 1",
                "SELECT * FROM public.orders"
        })
        void acceptsReadOnlyStatements(String sql) {
            assertThatCode(() -> ReadOnlySqlValidatorTests.this.validator.validate(sql))
                    .doesNotThrowAnyException();
        }

        @Test
        void reportsTheTablesItFound() {
            ValidatedQuery validated = ReadOnlySqlValidatorTests.this.validator
                    .validate("SELECT o.id FROM orders o JOIN customers c ON c.id = o.customer_id");

            assertThat(validated.tables())
                    .extracting(String::toLowerCase)
                    .contains("orders", "customers");
        }

        @Test
        @DisplayName("a trailing semicolon is one statement, not two")
        void toleratesATrailingSemicolon() {
            assertThatCode(() -> ReadOnlySqlValidatorTests.this.validator.validate("SELECT * FROM orders;"))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("refuses writes")
    class RefusesWrites {

        @ParameterizedTest
        @ValueSource(strings = {
                "INSERT INTO orders (id) VALUES (1)",
                "UPDATE orders SET total = 0",
                "DELETE FROM orders",
                "DROP TABLE orders",
                "TRUNCATE TABLE orders",
                "ALTER TABLE orders ADD COLUMN x int",
                "CREATE TABLE t (id int)",
                "CREATE OR REPLACE FUNCTION f() RETURNS void AS $$ BEGIN END $$ LANGUAGE plpgsql",
                "MERGE INTO orders USING staging ON orders.id = staging.id WHEN MATCHED THEN UPDATE SET total = 1"
        })
        void rejectsEveryWritingStatement(String sql) {
            PolicyViolationException refusal = refusalOf(sql);
            assertThat(refusal.reason()).isEqualTo(ViolationReason.NOT_READ_ONLY);
        }

        @Test
        @DisplayName("a DELETE hidden in a CTE is still a DELETE")
        void rejectsDataModifyingCommonTableExpressions() {
            // The reason this validator parses instead of matching keywords. PostgreSQL runs
            // the DELETE here, and the statement is a SELECT as far as its top level goes.
            PolicyViolationException refusal = refusalOf(
                    "WITH gone AS (DELETE FROM orders RETURNING *) SELECT * FROM gone");

            assertThat(refusal.reason()).isEqualTo(ViolationReason.DATA_MODIFYING_CTE);
            assertThat(refusal.getMessage()).contains("DELETE");
        }

        @Test
        void rejectsInsertingCommonTableExpressions() {
            PolicyViolationException refusal = refusalOf(
                    "WITH added AS (INSERT INTO audit (msg) VALUES ('x') RETURNING *) SELECT * FROM added");

            assertThat(refusal.reason()).isEqualTo(ViolationReason.DATA_MODIFYING_CTE);
        }

        @Test
        void rejectsUpdatingCommonTableExpressions() {
            PolicyViolationException refusal = refusalOf(
                    "WITH bumped AS (UPDATE orders SET total = total + 1 RETURNING *) SELECT * FROM bumped");

            assertThat(refusal.reason()).isEqualTo(ViolationReason.DATA_MODIFYING_CTE);
        }

        @Test
        @DisplayName("SELECT INTO creates a table, so it is a write")
        void rejectsSelectInto() {
            PolicyViolationException refusal = refusalOf("SELECT * INTO copy_of_orders FROM orders");

            assertThat(refusal.reason()).isEqualTo(ViolationReason.SELECT_INTO);
        }

        @ParameterizedTest
        @DisplayName("statements the parser cannot read are refused, not waved through")
        @ValueSource(strings = {
                "GRANT ALL ON orders TO PUBLIC",
                "REVOKE SELECT ON orders FROM analyst",
                "VACUUM FULL orders",
                "COPY orders TO '/tmp/out.csv'"
        })
        void failsClosedOnUnsupportedGrammar(String sql) {
            // JSqlParser has no grammar for some of these, so they surface as PARSE_FAILED
            // rather than NOT_READ_ONLY. The reason differs; the refusal does not. That is
            // the property that matters: unparseable input is denied by default, so a gap in
            // the grammar can never become a way in.
            PolicyViolationException refusal = refusalOf(sql);

            assertThat(refusal.reason())
                    .isIn(ViolationReason.PARSE_FAILED, ViolationReason.NOT_READ_ONLY);
        }
    }

    @Nested
    @DisplayName("refuses statement stacking")
    class RefusesStacking {

        @Test
        void rejectsASecondStatementAfterASelect() {
            PolicyViolationException refusal = refusalOf("SELECT 1; DROP TABLE orders");

            assertThat(refusal.reason()).isEqualTo(ViolationReason.MULTIPLE_STATEMENTS);
        }

        @Test
        @DisplayName("a comment cannot hide a second statement")
        void rejectsStatementsSmuggledPastAComment() {
            PolicyViolationException refusal = refusalOf("SELECT 1 -- harmless\n; DELETE FROM orders");

            assertThat(refusal.reason()).isEqualTo(ViolationReason.MULTIPLE_STATEMENTS);
        }
    }

    @Nested
    @DisplayName("refuses locking and privileged functions")
    class RefusesEscapeHatches {

        @ParameterizedTest
        @ValueSource(strings = {
                "SELECT * FROM orders FOR UPDATE",
                "SELECT * FROM orders FOR SHARE",
                "SELECT * FROM orders FOR NO KEY UPDATE",
                "SELECT * FROM orders FOR KEY SHARE"
        })
        void rejectsRowLocking(String sql) {
            assertThat(refusalOf(sql).reason()).isEqualTo(ViolationReason.LOCKING_CLAUSE);
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "SELECT pg_sleep(10)",
                "SELECT pg_read_file('/etc/passwd')",
                "SELECT lo_import('/etc/shadow')",
                "SELECT dblink('host=evil.example.com', 'SELECT 1')",
                "SELECT pg_terminate_backend(1)"
        })
        void rejectsBlockedFunctions(String sql) {
            assertThat(refusalOf(sql).reason()).isEqualTo(ViolationReason.BLOCKED_FUNCTION);
        }

        @Test
        @DisplayName("a comment inside a function name does not get past the scan")
        void rejectsCommentObfuscatedFunctionCalls() {
            // Scanning happens after the statement is re-serialised from the parse tree, so
            // the comment is gone by the time the pattern runs. A raw-text blocklist would
            // have missed this.
            assertThat(refusalOf("SELECT pg_sleep/**/(10)").reason())
                    .isEqualTo(ViolationReason.BLOCKED_FUNCTION);
        }

        @Test
        @DisplayName("a blocked name inside a string literal is not a call")
        void allowsBlockedNamesAppearingInLiterals() {
            // Refusing this would be a false positive: the text is data, not a function call.
            assertThatCode(() -> ReadOnlySqlValidatorTests.this.validator
                    .validate("SELECT * FROM logs WHERE message = 'pg_sleep(1) was called'"))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("hides database internals")
    class HidesInternals {

        @ParameterizedTest
        @ValueSource(strings = {
                "SELECT * FROM pg_catalog.pg_user",
                "SELECT * FROM information_schema.tables",
                "SELECT * FROM pg_shadow",
                "SELECT * FROM pg_stat_activity"
        })
        void rejectsSystemCatalogReads(String sql) {
            assertThat(refusalOf(sql).reason()).isEqualTo(ViolationReason.SYSTEM_CATALOG);
        }
    }

    @Nested
    @DisplayName("applies configured policy")
    class AppliesConfiguredPolicy {

        @Test
        void rejectsExplicitlyBlockedTables() {
            ReadOnlySqlValidatorTests.this.properties.setBlockedTables(java.util.List.of("salaries"));

            assertThat(refusalOf("SELECT * FROM salaries").reason())
                    .isEqualTo(ViolationReason.BLOCKED_TABLE);
        }

        @Test
        void matchesBlockedTablesRegardlessOfCase() {
            ReadOnlySqlValidatorTests.this.properties.setBlockedTables(java.util.List.of("Salaries"));

            assertThat(refusalOf("SELECT * FROM SALARIES").reason())
                    .isEqualTo(ViolationReason.BLOCKED_TABLE);
        }

        @Test
        void rejectsSchemasOutsideTheAllowList() {
            ReadOnlySqlValidatorTests.this.properties.setAllowedSchemas(java.util.List.of("public"));

            assertThat(refusalOf("SELECT * FROM private.secrets").reason())
                    .isEqualTo(ViolationReason.SCHEMA_NOT_ALLOWED);
        }

        @Test
        @DisplayName("an empty allow-list defers to the database user's own grants")
        void permitsAnySchemaWhenTheAllowListIsEmpty() {
            ReadOnlySqlValidatorTests.this.properties.setAllowedSchemas(java.util.List.of());

            assertThatCode(() -> ReadOnlySqlValidatorTests.this.validator
                    .validate("SELECT * FROM reporting.daily_totals"))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("handles malformed input")
    class HandlesMalformedInput {

        @Test
        void rejectsEmptyInput() {
            assertThat(refusalOf("   ").reason()).isEqualTo(ViolationReason.EMPTY_STATEMENT);
        }

        @Test
        void rejectsNullInput() {
            assertThat(refusalOf(null).reason()).isEqualTo(ViolationReason.EMPTY_STATEMENT);
        }

        @Test
        void rejectsUnparseableInput() {
            assertThat(refusalOf("SELECT FROM WHERE ORDER").reason())
                    .isEqualTo(ViolationReason.PARSE_FAILED);
        }

        @Test
        @DisplayName("a refusal explains itself well enough for a model to retry")
        void explainsWhatToDoInstead() {
            PolicyViolationException refusal = refusalOf("DELETE FROM orders");

            assertThat(refusal.getMessage())
                    .contains("NOT_READ_ONLY")
                    .contains("Only SELECT statements are allowed");
        }
    }
}
