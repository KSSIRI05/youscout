package ma.youscout.mediaworker;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "worker.ingestion.enabled=false")
@Testcontainers
class ExecutionStoreTest {
    @Container static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17.11-alpine");
    @Autowired ExecutionStore store;
    @Autowired JobStore jobs;
    @Autowired JdbcTemplate jdbc;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl); registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }
    @BeforeEach void clear() { jdbc.update("DELETE FROM processing_jobs"); }
    VideoUploaded enqueue() {
        UUID video = UUID.randomUUID();
        var event = new EventDecoder().decode(video.toString(), EventFixture.json(EventFixture.event(UUID.randomUUID(), video)));
        jobs.accept(event, "test", 0, 0); return event;
    }
    @Test void successAndOutboxCommitTogetherAndReplayCannotOverwrite() {
        enqueue(); var job = store.claim().orElseThrow();
        assertThat(store.finish(job, new ExecutionStore.Output(2000, 320, 240), null)).isTrue();
        assertThat(store.finish(job, null, new ProcessingFailure("INVALID_MEDIA", false))).isFalse();
        assertThat(jdbc.queryForObject("SELECT status FROM processing_jobs", String.class)).isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM worker_outbox", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT output_video_key FROM processing_jobs", String.class)).isEqualTo(job.prefix() + "video.mp4");
    }
    @Test void outboxFailureRollsBackTerminalState() {
        enqueue(); var job = store.claim().orElseThrow();
        jdbc.execute("ALTER TABLE worker_outbox ADD CONSTRAINT test_reject CHECK (false)");
        try { assertThatThrownBy(() -> store.finish(job, new ExecutionStore.Output(2000, 320, 240), null))
                .isInstanceOf(org.springframework.dao.DataAccessException.class); }
        finally { jdbc.execute("ALTER TABLE worker_outbox DROP CONSTRAINT test_reject"); }
        assertThat(jdbc.queryForObject("SELECT status FROM processing_jobs", String.class)).isEqualTo("PROCESSING");
        assertThat(store.finish(job, new ExecutionStore.Output(2000, 320, 240), null)).isTrue();
    }
    @Test void expiredLeaseGetsNewFencingTokenAndOldAttemptCannotComplete() {
        enqueue(); var old = store.claim().orElseThrow();
        jdbc.update("UPDATE processing_jobs SET lease_until = clock_timestamp() - interval '1 second'");
        var next = store.claim().orElseThrow();
        assertThat(next.attemptId()).isNotEqualTo(old.attemptId()); assertThat(next.attempts()).isEqualTo(2);
        assertThat(store.finish(old, new ExecutionStore.Output(2000, 320, 240), null)).isFalse();
        assertThat(store.finish(next, new ExecutionStore.Output(2000, 320, 240), null)).isTrue();
    }
    @Test void transientFailuresBackOffThenBecomeOneTerminalFailure() {
        var original = enqueue();
        for (int n = 1; n <= 3; n++) {
            var job = store.claim().orElseThrow(); assertThat(job.attempts()).isEqualTo(n);
            store.finish(job, null, new ProcessingFailure("STORAGE_UNAVAILABLE", true));
            if (n < 3) {
                assertThat(store.claim()).isEmpty();
                assertThat(jdbc.queryForObject("SELECT count(*) FROM worker_outbox", Integer.class)).isZero();
                jdbc.update("UPDATE processing_jobs SET available_at = clock_timestamp() - interval '1 second'");
            }
        }
        jobs.accept(original, "test", 0, 1);
        assertThat(jdbc.queryForObject("SELECT status FROM processing_jobs", String.class)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM worker_outbox", Integer.class)).isEqualTo(1);
    }
    @Test void abandonedFinalAttemptCanBeRecoveredAsFailed() {
        enqueue(); store.claim();
        jdbc.update("UPDATE processing_jobs SET attempts = 3, lease_until = clock_timestamp() - interval '1 second'");
        var job = store.claim().orElseThrow(); assertThat(job.attempts()).isEqualTo(4);
        store.finish(job, null, new ProcessingFailure("RETRY_EXHAUSTED", false));
        assertThat(jdbc.queryForObject("SELECT status FROM processing_jobs", String.class)).isEqualTo("FAILED");
    }
    @Test void concurrentClaimsHaveOnlyOneWinner() {
        enqueue(); var futures = java.util.stream.IntStream.range(0, 5).mapToObj(n -> CompletableFuture.supplyAsync(store::claim)).toList();
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
        assertThat(futures.stream().filter(f -> f.join().isPresent()).count()).isEqualTo(1);
    }
}
