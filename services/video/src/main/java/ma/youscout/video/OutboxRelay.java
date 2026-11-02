package ma.youscout.video;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Delivers outbox events at least once; retries always reuse the persisted eventId. */
@Component
@ConditionalOnProperty(name = "youscout.outbox.enabled", havingValue = "true")
class OutboxRelay {
    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private final OutboxRepository outbox;
    private final EventPublisher publisher;
    private final TransactionTemplate transactions;

    OutboxRelay(OutboxRepository outbox, EventPublisher publisher,
                @Qualifier("mongoTransactionManager") PlatformTransactionManager manager) {
        this.outbox = outbox;
        this.publisher = publisher;
        this.transactions = new TransactionTemplate(manager);
    }

    @Scheduled(fixedDelayString = "${youscout.outbox.poll-ms:1000}",
            initialDelayString = "${youscout.outbox.initial-delay-ms:5000}")
    void poll() {
        try {
            for (int count = 0; count < 10 && publishNext(); count++) { /* bounded batch */ }
        } catch (RuntimeException unavailable) {
            log.warn("Outbox transaction unavailable; pending events will be retried.");
        }
    }

    boolean publishNext() {
        // Commit the claim before calling Kafka so other relays respect the lease.
        OutboxRepository.Event event = transactions.execute(status -> outbox.lockNext().orElse(null));
        if (event == null) return false;
        try {
            publisher.publish(event.aggregateId(), event.eventType(), event.payload());
        } catch (Exception unavailable) {
            if (unavailable instanceof InterruptedException) Thread.currentThread().interrupt();
            transactions.executeWithoutResult(status -> outbox.retry(event));
            log.warn("Kafka unavailable; outbox event {} retained for retry.", event.id());
            return false;
        }
        // A failure after Kafka ACK lets the lease expire and republishes the same eventId.
        transactions.executeWithoutResult(status -> outbox.published(event.id()));
        return true;
    }
}
