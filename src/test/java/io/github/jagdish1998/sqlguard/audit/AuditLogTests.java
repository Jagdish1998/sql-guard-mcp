package io.github.jagdish1998.sqlguard.audit;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import io.github.jagdish1998.sqlguard.config.SqlGuardProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AuditLogTests {

    private AuditLog logWithCapacity(int capacity) {
        SqlGuardProperties properties = new SqlGuardProperties();
        properties.getAudit().setCapacity(capacity);
        return new AuditLog(properties);
    }

    private AuditEvent event(String sql) {
        return new AuditEvent(Instant.now(), "run_query", sql, AuditEvent.Outcome.ALLOWED, null,
                Set.of("orders"), 1, List.of(), 3L);
    }

    @Test
    void returnsNewestFirst() {
        AuditLog log = logWithCapacity(10);
        log.record(event("SELECT 1"));
        log.record(event("SELECT 2"));

        assertThat(log.recent(10)).extracting(AuditEvent::sql).containsExactly("SELECT 2", "SELECT 1");
    }

    @Test
    @DisplayName("the buffer is bounded, so a long session cannot exhaust memory")
    void discardsOldEntriesBeyondCapacity() {
        AuditLog log = logWithCapacity(3);
        IntStream.rangeClosed(1, 10).forEach(i -> log.record(event("SELECT " + i)));

        assertThat(log.size()).isEqualTo(3);
        assertThat(log.recent(100)).extracting(AuditEvent::sql)
                .containsExactly("SELECT 10", "SELECT 9", "SELECT 8");
    }

    @Test
    void neverReturnsMoreThanAsked() {
        AuditLog log = logWithCapacity(50);
        IntStream.rangeClosed(1, 20).forEach(i -> log.record(event("SELECT " + i)));

        assertThat(log.recent(5)).hasSize(5);
    }

    @Test
    @DisplayName("a very long statement is truncated before it is stored")
    void truncatesOversizedStatements() {
        AuditLog log = logWithCapacity(5);
        log.record(event("SELECT " + "x".repeat(5_000)));

        String stored = log.recent(1).get(0).sql();

        assertThat(stored).hasSizeLessThan(2_200).endsWith("...[truncated]");
    }

    @Test
    @DisplayName("concurrent tool calls do not lose entries")
    void isSafeUnderConcurrency() throws Exception {
        // An audit log that drops records under load is worse than no audit log, because it
        // is trusted. 8 threads writing 250 entries each into a buffer of 4000.
        AuditLog log = logWithCapacity(4_000);
        int threads = 8;
        int perThread = 250;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startLine = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(threads);

        try {
            for (int t = 0; t < threads; t++) {
                pool.submit(() -> {
                    try {
                        startLine.await();
                        for (int i = 0; i < perThread; i++) {
                            log.record(event("SELECT " + i));
                        }
                    }
                    catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    }
                    finally {
                        finished.countDown();
                    }
                });
            }

            startLine.countDown();
            assertThat(finished.await(30, TimeUnit.SECONDS)).isTrue();
        }
        finally {
            pool.shutdownNow();
        }

        assertThat(log.size()).isEqualTo(threads * perThread);
    }
}
