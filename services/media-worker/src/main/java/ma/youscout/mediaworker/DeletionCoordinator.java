package ma.youscout.mediaworker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Deletes the S3 objects of deleted videos; deletions recorded while processing is disabled wait in the table. */
@Component
@ConditionalOnProperty(name = "worker.processing.enabled", havingValue = "true")
class DeletionCoordinator {
    private static final Logger log = LoggerFactory.getLogger(DeletionCoordinator.class);
    private final DeletionStore store;
    private final MediaStorage storage;
    DeletionCoordinator(DeletionStore store, MediaStorage storage) { this.store = store; this.storage = storage; }

    @Scheduled(fixedDelay = 2000, initialDelay = 5000)
    void poll() {
        try { while (runOne()) { /* drain due cleanups */ } }
        catch (RuntimeException unavailable) { log.warn("Deletion state unavailable; pending cleanups will be retried."); }
    }

    boolean runOne() {
        var claimed = store.claim();
        if (claimed.isEmpty()) return false;
        var cleanup = claimed.get();
        try {
            storage.delete(cleanup.videoId(), cleanup.keys());
            store.complete(cleanup.videoId());
        } catch (ProcessingFailure failure) {
            log.warn("S3 cleanup failed ({}); it will be retried.", failure.code);
            store.retryLater(cleanup.videoId());
        }
        return true;
    }
}
