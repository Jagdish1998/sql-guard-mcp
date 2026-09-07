package io.github.jagdish1998.sqlguard.guard;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * How a redacted value is presented once masked.
 *
 * <p>Masking is not all-or-nothing because analysis usually needs shape, not content.
 * Knowing that two rows share a domain, or that a phone number ends in 4821, is often
 * the entire point of the query, while the full value is what must not leave the
 * database. Every strategy is one-way: nothing here can be reversed by the model.
 */
public enum MaskStrategy {

    /** Replace the value outright. Correct for passwords, tokens and government identifiers. */
    FULL {
        @Override
        public String apply(String value) {
            return "***";
        }
    },

    /** Keep the last four characters. Enough to reconcile against a record the user already holds. */
    LAST_FOUR {
        @Override
        public String apply(String value) {
            String digits = value.trim();
            if (digits.length() <= 4) {
                return "***";
            }
            return "***" + digits.substring(digits.length() - 4);
        }
    },

    /** Keep the domain and the first character of the local part, so grouping by domain still works. */
    EMAIL {
        @Override
        public String apply(String value) {
            int at = value.indexOf('@');
            if (at < 1) {
                return "***";
            }
            String local = value.substring(0, at);
            String domain = value.substring(at);
            return local.charAt(0) + "***" + domain;
        }
    },

    /**
     * A stable pseudonym. Equal inputs mask to equal outputs, so grouping and counting
     * still work while the value itself does not leave the database.
     *
     * <p>Truncated to 12 hex characters for readability. Note that an unsalted digest of a
     * low-cardinality column is open to a dictionary attack: hashing a value set as small
     * as a country or a status code tells an attacker nothing they could not brute force.
     * Use {@link #FULL} when the column's value space is small enough to enumerate.
     */
    HASH {
        @Override
        public String apply(String value) {
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                byte[] hashed = digest.digest(value.getBytes(StandardCharsets.UTF_8));
                StringBuilder hex = new StringBuilder(12);
                for (int i = 0; i < 6; i++) {
                    hex.append("%02x".formatted(hashed[i]));
                }
                return "sha256:" + hex;
            }
            catch (NoSuchAlgorithmException ex) {
                // SHA-256 is required of every Java platform, so this cannot happen.
                // Failing closed still beats leaking the value if it somehow does.
                return "***";
            }
        }
    };

    /**
     * @param value the non-null, non-empty raw value
     * @return the masked replacement
     */
    public abstract String apply(String value);
}
