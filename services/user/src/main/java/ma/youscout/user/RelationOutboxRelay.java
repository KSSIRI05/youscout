package ma.youscout.user;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Publishes committed outbox rows at least once; consumers deduplicate by eventId. */
@Component
@ConditionalOnProperty(name = "youscout.user-outbox.enabled", havingValue = "true")
class RelationOutboxRelay {
    private static final Logger log = LoggerFactory.getLogger(RelationOutboxRelay.class);
    private final RelationOutboxRepository outbox;
    private final RelationKafkaPublisher publisher;
    private final TransactionTemplate transactions;

    RelationOutboxRelay(RelationOutboxRepository outbox, RelationKafkaPublisher publisher,
                        PlatformTransactionManager manager) {
        this.outbox = outbox;
        this.publisher = publisher;
        this.transactions = new TransactionTemplate(manager);
    }

    @Scheduled(fixedDelayString = "${youscout.user-outbox.poll-ms:1000}",
               initialDelayString = "${youscout.user-outbox.initial-delay-ms:3000}")
    void poll() {
        try {
            for (int count = 0; count < 10 && publishNext(); count++) { /* bounded batch */ }
        } catch (RuntimeException unavailable) {
            log.warn("User relationship outbox unavailable; pending events will be retried.");
        }
    }

    boolean publishNext() {
        return Boolean.TRUE.equals(transactions.execute(status -> {
            var next = outbox.lockNext();
            if (next.isEmpty()) return false;
            var event = next.get();
            try {
                publisher.publish(event.key(), event.payload());
            } catch (Exception unavailable) {
                if (unavailable instanceof InterruptedException) Thread.currentThread().interrupt();
                outbox.retry(event.id());
                log.warn("Kafka unavailable; user event {} retained for retry.", event.id());
                return false;
            }
            outbox.published(event.id());
            return true;
        }));
    }
}
