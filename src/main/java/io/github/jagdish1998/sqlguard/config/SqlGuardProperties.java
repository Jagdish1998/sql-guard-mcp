package io.github.jagdish1998.sqlguard.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import io.github.jagdish1998.sqlguard.guard.MaskStrategy;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Every guardrail in this server is configuration, not code.
 *
 * <p>The defaults are deliberately strict. A tool that an LLM can call needs to be safe
 * when nobody has read the manual, so widening a limit is an explicit decision the
 * operator makes rather than something they forget to tighten.
 */
@ConfigurationProperties(prefix = "sqlguard")
public class SqlGuardProperties {

    /** Hard ceiling on rows returned to the model. Queries asking for more are rewritten, not rejected. */
    private int maxRows = 200;

    /** Server-side statement timeout. A model can always write an accidental cross join. */
    private Duration queryTimeout = Duration.ofSeconds(5);

    /** Reject a query whose plan estimates more rows than this. Set to -1 to disable. */
    private long maxEstimatedRows = 5_000_000L;

    /** Reject a query whose plan estimates a higher total cost than this. Set to -1 to disable. */
    private double maxEstimatedCost = 5_000_000d;

    /**
     * Schemas the model may read. Empty means "any schema the database user can reach",
     * which is only safe when that user is itself tightly scoped.
     */
    private List<String> allowedSchemas = new ArrayList<>(List.of("public"));

    /** Tables the model may never read, even when the database user can. Matched case-insensitively. */
    private List<String> blockedTables = new ArrayList<>();

    /**
     * Functions the model may never call. These are the usual escape hatches out of a
     * read-only session: filesystem reads, sleeps that pin a connection, and anything
     * that opens an outbound connection.
     */
    private List<String> blockedFunctions = new ArrayList<>(List.of(
            "pg_read_file",
            "pg_read_binary_file",
            "pg_ls_dir",
            "pg_stat_file",
            "pg_sleep",
            "pg_sleep_for",
            "pg_sleep_until",
            "pg_terminate_backend",
            "pg_cancel_backend",
            "pg_reload_conf",
            "pg_rotate_logfile",
            "lo_import",
            "lo_export",
            "dblink",
            "dblink_exec",
            "dblink_connect",
            "query_to_xml",
            "set_config",
            "pg_logical_emit_message",
            "txid_current",
            "copy_file",
            "system"));

    private final Redaction redaction = new Redaction();
    private final Audit audit = new Audit();

    public static class Redaction {

        /** Turn column-name based masking on or off wholesale. */
        private boolean enabled = true;

        /**
         * Ordered rules. The first pattern that matches a column name wins, so put the
         * specific patterns above the general ones.
         */
        private List<Rule> rules = new ArrayList<>(List.of(
                new Rule("(?i).*(password|passwd|pwd|secret|token|api_?key|private_?key).*", MaskStrategy.FULL),
                new Rule("(?i).*(ssn|social_security|aadhaar|pan_number|tax_id|national_id).*", MaskStrategy.FULL),
                new Rule("(?i).*(card_number|cardno|cvv|iban|account_number).*", MaskStrategy.LAST_FOUR),
                new Rule("(?i).*e[-_]?mail.*", MaskStrategy.EMAIL),
                new Rule("(?i).*(phone|mobile|msisdn|contact_number).*", MaskStrategy.LAST_FOUR),
                new Rule("(?i).*(dob|date_of_birth|birth_date)$", MaskStrategy.FULL)));

        public boolean isEnabled() {
            return this.enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public List<Rule> getRules() {
            return this.rules;
        }

        public void setRules(List<Rule> rules) {
            this.rules = rules;
        }

        public static class Rule {

            /** Java regex matched against the column label as the driver reports it. */
            private String pattern;

            private MaskStrategy strategy = MaskStrategy.FULL;

            public Rule() {
            }

            public Rule(String pattern, MaskStrategy strategy) {
                this.pattern = pattern;
                this.strategy = strategy;
            }

            public String getPattern() {
                return this.pattern;
            }

            public void setPattern(String pattern) {
                this.pattern = pattern;
            }

            public MaskStrategy getStrategy() {
                return this.strategy;
            }

            public void setStrategy(MaskStrategy strategy) {
                this.strategy = strategy;
            }
        }
    }

    public static class Audit {

        /**
         * How many recent decisions to keep in memory.
         *
         * <p>The audit trail deliberately does not live in the database being queried:
         * that database is read-only by design, and a log an attacker can reach through
         * the same tool is not an audit trail. Entries are also written to SLF4J so a
         * real deployment can ship them somewhere durable.
         */
        private int capacity = 500;

        public int getCapacity() {
            return this.capacity;
        }

        public void setCapacity(int capacity) {
            this.capacity = capacity;
        }
    }

    public int getMaxRows() {
        return this.maxRows;
    }

    public void setMaxRows(int maxRows) {
        this.maxRows = maxRows;
    }

    public Duration getQueryTimeout() {
        return this.queryTimeout;
    }

    public void setQueryTimeout(Duration queryTimeout) {
        this.queryTimeout = queryTimeout;
    }

    public long getMaxEstimatedRows() {
        return this.maxEstimatedRows;
    }

    public void setMaxEstimatedRows(long maxEstimatedRows) {
        this.maxEstimatedRows = maxEstimatedRows;
    }

    public double getMaxEstimatedCost() {
        return this.maxEstimatedCost;
    }

    public void setMaxEstimatedCost(double maxEstimatedCost) {
        this.maxEstimatedCost = maxEstimatedCost;
    }

    public List<String> getAllowedSchemas() {
        return this.allowedSchemas;
    }

    public void setAllowedSchemas(List<String> allowedSchemas) {
        this.allowedSchemas = allowedSchemas;
    }

    public List<String> getBlockedTables() {
        return this.blockedTables;
    }

    public void setBlockedTables(List<String> blockedTables) {
        this.blockedTables = blockedTables;
    }

    public List<String> getBlockedFunctions() {
        return this.blockedFunctions;
    }

    public void setBlockedFunctions(List<String> blockedFunctions) {
        this.blockedFunctions = blockedFunctions;
    }

    public Redaction getRedaction() {
        return this.redaction;
    }

    public Audit getAudit() {
        return this.audit;
    }
}
