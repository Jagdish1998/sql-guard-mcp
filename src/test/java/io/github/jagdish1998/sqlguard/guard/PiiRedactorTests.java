package io.github.jagdish1998.sqlguard.guard;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.jagdish1998.sqlguard.config.SqlGuardProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class PiiRedactorTests {

    private SqlGuardProperties properties;

    private PiiRedactor redactor;

    @BeforeEach
    void setUp() {
        this.properties = new SqlGuardProperties();
        this.redactor = new PiiRedactor(this.properties);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "password", "PASSWORD", "user_password", "passwd", "api_key", "apikey",
            "access_token", "private_key", "ssn", "social_security_number", "aadhaar",
            "email", "e_mail", "EMail", "user_email", "phone", "mobile_number",
            "card_number", "cvv", "iban", "date_of_birth"
    })
    void masksObviouslySensitiveColumns(String column) {
        assertThat(this.redactor.strategyFor(column)).isNotNull();
    }

    @ParameterizedTest
    @ValueSource(strings = { "id", "order_total", "status", "created_at", "product_name", "quantity" })
    void leavesOrdinaryColumnsAlone(String column) {
        assertThat(this.redactor.strategyFor(column)).isNull();
    }

    @ParameterizedTest
    @CsvSource({
            "email,     alice@example.com, a***@example.com",
            "password,  hunter2,           ***",
            "phone,     +919876543210,     ***3210",
            "cvv,       123,               ***"
    })
    void appliesTheStrategyMatchingTheColumn(String column, String raw, String expected) {
        MaskStrategy strategy = this.redactor.strategyFor(column);

        assertThat(strategy).isNotNull();
        assertThat(strategy.apply(raw)).isEqualTo(expected);
    }

    @Test
    @DisplayName("masking is keyed on the returned label, so an alias does not bypass it")
    void masksByResultLabelNotQueryText() {
        // SELECT email AS contact would arrive with label "contact" and pass, which is a
        // real limitation. But SELECT password AS email must still mask, because the label
        // is what gets tested. This documents which direction the check runs.
        Set<String> redacted = new LinkedHashSet<>();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("email", "bob@example.com");

        Map<String, Object> masked = this.redactor.redactRow(row, redacted);

        assertThat(masked.get("email")).isEqualTo("b***@example.com");
        assertThat(redacted).containsExactly("email");
    }

    @Test
    void recordsWhichColumnsItMasked() {
        Set<String> redacted = new LinkedHashSet<>();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", 1);
        row.put("email", "carol@example.com");
        row.put("password", "secret");

        this.redactor.redactRow(row, redacted);

        assertThat(redacted).containsExactlyInAnyOrder("email", "password");
    }

    @Test
    void leavesNullsAsNulls() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("email", null);

        Map<String, Object> masked = this.redactor.redactRow(row, new LinkedHashSet<>());

        assertThat(masked.get("email")).isNull();
    }

    @Test
    void canBeTurnedOff() {
        this.properties.getRedaction().setEnabled(false);
        PiiRedactor disabled = new PiiRedactor(this.properties);

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("password", "hunter2");

        assertThat(disabled.redactRow(row, new LinkedHashSet<>()).get("password")).isEqualTo("hunter2");
        assertThat(disabled.strategyFor("password")).isNull();
    }

    @Test
    void listsWhichOfTheGivenColumnsAreSensitive() {
        assertThat(this.redactor.sensitiveColumns(List.of("id", "email", "status", "ssn")))
                .containsExactly("email", "ssn");
    }

    @Test
    @DisplayName("a bad regex fails at startup rather than silently protecting nothing")
    void refusesToStartWithAnInvalidPattern() {
        SqlGuardProperties broken = new SqlGuardProperties();
        broken.getRedaction().setRules(List.of(
                new SqlGuardProperties.Redaction.Rule("([unclosed", MaskStrategy.FULL)));

        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> new PiiRedactor(broken))
                .withMessageContaining("Invalid sqlguard redaction pattern");
    }

    @Test
    @DisplayName("the hash strategy is stable, so grouping still works")
    void hashesConsistently() {
        String first = MaskStrategy.HASH.apply("alice@example.com");
        String second = MaskStrategy.HASH.apply("alice@example.com");
        String other = MaskStrategy.HASH.apply("bob@example.com");

        assertThat(first).isEqualTo(second).startsWith("sha256:");
        assertThat(first).isNotEqualTo(other);
        assertThat(first).doesNotContain("alice");
    }

    @Test
    void masksShortValuesEntirelyRatherThanRevealingThem() {
        // "***" + last four of a three-character value would return the whole thing.
        assertThat(MaskStrategy.LAST_FOUR.apply("123")).isEqualTo("***");
        assertThat(MaskStrategy.EMAIL.apply("not-an-email")).isEqualTo("***");
        assertThat(MaskStrategy.EMAIL.apply("@example.com")).isEqualTo("***");
    }
}
