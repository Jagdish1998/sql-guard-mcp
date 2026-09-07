package io.github.jagdish1998.sqlguard.guard;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import io.github.jagdish1998.sqlguard.config.SqlGuardProperties;
import org.springframework.stereotype.Component;

/**
 * Masks sensitive columns on the way out.
 *
 * <p>Redaction is decided from the column label the driver reports, not from the text of
 * the query. That matters: a model can write {@code SELECT email AS contact} or
 * {@code SELECT * FROM users}, and in both cases the label arriving from the result-set
 * metadata is what gets tested. Inspecting the query text instead would be trivially
 * bypassed by an alias.
 *
 * <p>What this does not do is detect sensitive data by looking at values. A free-text
 * {@code notes} column containing an email address is not caught, and pretending
 * otherwise would be worse than being clear about it. This is a guardrail against
 * casual exposure of columns that are obviously sensitive, not a DLP engine.
 */
@Component
public class PiiRedactor {

    private final boolean enabled;

    private final List<CompiledRule> rules;

    public PiiRedactor(SqlGuardProperties properties) {
        this.enabled = properties.getRedaction().isEnabled();
        this.rules = compile(properties.getRedaction().getRules());
    }

    private static List<CompiledRule> compile(List<SqlGuardProperties.Redaction.Rule> configured) {
        List<CompiledRule> compiled = new ArrayList<>();
        for (SqlGuardProperties.Redaction.Rule rule : configured) {
            if (rule.getPattern() == null || rule.getPattern().isBlank()) {
                continue;
            }
            try {
                compiled.add(new CompiledRule(Pattern.compile(rule.getPattern()), rule.getStrategy()));
            }
            catch (PatternSyntaxException ex) {
                // Fail fast at startup. A redaction rule that silently did not compile
                // would look like protection while providing none.
                throw new IllegalStateException(
                        "Invalid sqlguard redaction pattern: " + rule.getPattern(), ex);
            }
        }
        return List.copyOf(compiled);
    }

    /**
     * Finds the strategy for a column, or {@code null} when the column is not sensitive.
     */
    public MaskStrategy strategyFor(String columnLabel) {
        if (!this.enabled || columnLabel == null) {
            return null;
        }
        for (CompiledRule rule : this.rules) {
            if (rule.pattern().matcher(columnLabel).matches()) {
                return rule.strategy();
            }
        }
        return null;
    }

    /**
     * Masks a single row in place-safe fashion, returning a new map.
     *
     * @param row       column label to raw value
     * @param redacted  collects the labels that were masked, for the audit record
     */
    public Map<String, Object> redactRow(Map<String, Object> row, Set<String> redacted) {
        if (!this.enabled) {
            return row;
        }
        Map<String, Object> masked = new LinkedHashMap<>(row.size());
        row.forEach((label, value) -> {
            MaskStrategy strategy = strategyFor(label);
            if (strategy == null || value == null) {
                masked.put(label, value);
                return;
            }
            String raw = String.valueOf(value);
            if (raw.isEmpty()) {
                masked.put(label, value);
                return;
            }
            masked.put(label, strategy.apply(raw));
            redacted.add(label);
        });
        return masked;
    }

    /** Which of the given labels would be masked. Used to warn before a query runs. */
    public Set<String> sensitiveColumns(List<String> columnLabels) {
        Set<String> sensitive = new LinkedHashSet<>();
        for (String label : columnLabels) {
            if (strategyFor(label) != null) {
                sensitive.add(label);
            }
        }
        return sensitive;
    }

    public boolean isEnabled() {
        return this.enabled;
    }

    private record CompiledRule(Pattern pattern, MaskStrategy strategy) {
    }
}
