package io.github.jagdish1998.sqlguard.guard;

import java.util.Set;

import net.sf.jsqlparser.statement.select.Select;

/**
 * A query that has passed every static check.
 *
 * @param select     the parsed statement, so later stages rewrite the tree instead of
 *                   editing text
 * @param normalised the statement re-serialised from the parse tree, with comments and
 *                   original whitespace discarded
 * @param tables     every table or table-like source the statement reads
 */
public record ValidatedQuery(Select select, String normalised, Set<String> tables) {
}
