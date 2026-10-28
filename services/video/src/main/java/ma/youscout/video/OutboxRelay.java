package ma.youscout.video;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Livraison au moins une fois : un crash après l'ACK peut republier le même eventId. */
@Component
@ConditionalOnProperty(name = "youscout.outbox.enabled", havingValue = "true")
class OutboxRelay {
    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private final OutboxRepository outbox;
    private final EventPublisher publisher;
    private final TransactionTemplate transactions;
    OutboxRelay(OutboxRepository outbox, EventPublisher publisher, PlatformTransactionManager manager) {
        this.outbox = outbox; this.publisher = publisher; this.transactions = new TransactionTemplate(manager);
    }

    @Scheduled(fixedDelayString = "${youscout.outbox.poll-ms:1000}",
               initialDelayString = "${youscout.outbox.initial-delay-ms:5000}")
    void poll() {
        // Une transaction par message, dix au maximum ; une panne interrompt ce lot.
        try {
            for (int n = 0; n < 10 && publishNext(); n++) { /* bounded batch */ }
        } catch (RuntimeException error) {
            // Ne journalise ni le payload, ni les messages du SDK/driver.
            log.warn("Outbox transaction unavailable; pending events will be retried.");
        }
    }

    boolean publishNext() {
        return Boolean.TRUE.equals(transactions.execute(status -> {
            var next = outbox.lockNext();
            if (next.isEmpty()) return false;
            var event = next.get();
            try {
                publisher.publish(event.aggregateId(), event.payload());
            } catch (Exception error) {
                if (error instanceof InterruptedException) Thread.currentThread().interrupt();
                outbox.retry(event);
                log.warn("Kafka unavailable; outbox event {} retained for retry.", event.id());
                return false;
            }
            // Une erreur DB ici annule la transaction : l'événement sera rejoué avec le même ID.
            outbox.published(event.id());
            return true;
        }));
    }
}
