package io.github.jagdish1998.sqlguard.guard;

/**
 * Thrown when a query breaks policy.
 *
 * <p>This extends {@link RuntimeException} on purpose. Spring AI converts an unchecked
 * exception from a tool method into an error result that is handed back to the model,
 * which is exactly what should happen here: the model asked for something it is not
 * allowed to have, and it should be told why so it can ask a better question. A checked
 * exception would fail the call without the model ever learning the reason.
 */
public class PolicyViolationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final ViolationReason reason;

    private final String detail;

    public PolicyViolationException(ViolationReason reason) {
        this(reason, null);
    }

    public PolicyViolationException(ViolationReason reason, String detail) {
        super(buildMessage(reason, detail));
        this.reason = reason;
        this.detail = detail;
    }

    private static String buildMessage(ViolationReason reason, String detail) {
        StringBuilder message = new StringBuilder("Query refused [").append(reason.name()).append("]. ");
        message.append(reason.guidance());
        if (detail != null && !detail.isBlank()) {
            message.append(" Detail: ").append(detail);
        }
        return message.toString();
    }

    public ViolationReason reason() {
        return this.reason;
    }

    public String detail() {
        return this.detail;
    }
}
