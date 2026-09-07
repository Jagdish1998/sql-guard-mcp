package io.github.jagdish1998.sqlguard.audit;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import io.github.jagdish1998.sqlguard.config.SqlGuardProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * A bounded, in-memory record of recent decisions, mirrored to SLF4J.
 *
 * <p>It deliberately does not write to the database being queried. That database is
 * read-only by design, so the server could not insert there even if it wanted to, and an
 * audit trail living inside the system under audit is not much of a trail. The in-memory
 * ring answers "what just happened" during a session; the SLF4J mirror is what a real
 * deployment ships to somewhere durable.
 *
 * <p>Every method is synchronised on the deque. Tool calls arrive on request threads, and
 * an audit log that drops entries under concurrency is worse than none because it is
 * trusted.
 */
@Component
public class AuditLog {

    private static final Logger log = LoggerFactory.getLogger(AuditLog.class);

    /** Long statements are truncated before storage so one query cannot exhaust the buffer. */
    private static final int MAX_SQL_LENGTH = 2_000;

    private final Deque<AuditEvent> events = new ArrayDeque<>();

    private final int capacity;

    public AuditLog(SqlGuardProperties properties) {
        this.capacity = Math.max(1, properties.getAudit().getCapacity());
    }

    public void record(AuditEvent event) {
        AuditEvent stored = truncate(event);
        synchronized (this.events) {
            this.events.addFirst(stored);
            while (this.events.size() > this.capacity) {
                this.events.removeLast();
            }
        }

        if (stored.outcome() == AuditEvent.Outcome.REFUSED) {
            log.warn("sqlguard refused tool={} reason={} tables={} sql={}",
                    stored.tool(), stored.violationReason(), stored.tables(), stored.sql());
        }
        else {
            log.info("sqlguard allowed tool={} rows={} redacted={} durationMs={} sql={}",
                    stored.tool(), stored.rowsReturned(), stored.redactedColumns(),
                    stored.durationMillis(), stored.sql());
        }
    }

    /** Most recent first. */
    public List<AuditEvent> recent(int limit) {
        int bounded = Math.max(1, Math.min(limit, this.capacity));
        synchronized (this.events) {
            List<AuditEvent> snapshot = new ArrayList<>(bounded);
            for (AuditEvent event : this.events) {
                if (snapshot.size() >= bounded) {
                    break;
                }
                snapshot.add(event);
            }
            return List.copyOf(snapshot);
        }
    }

    public int size() {
        synchronized (this.events) {
            return this.events.size();
        }
    }

    private AuditEvent truncate(AuditEvent event) {
        String sql = event.sql();
        if (sql == null || sql.length() <= MAX_SQL_LENGTH) {
            return event;
        }
        return new AuditEvent(event.at(), event.tool(), sql.substring(0, MAX_SQL_LENGTH) + "...[truncated]",
                event.outcome(), event.violationReason(), event.tables(), event.rowsReturned(),
                event.redactedColumns(), event.durationMillis());
    }
}
