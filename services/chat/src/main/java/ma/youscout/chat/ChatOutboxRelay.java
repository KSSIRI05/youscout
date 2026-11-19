package ma.youscout.chat;

import static org.springframework.data.mongodb.core.query.Criteria.where;
import static org.springframework.data.mongodb.core.query.Query.query;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Publie MessageAccepted au moins une fois. Un événement est réservé 60 s avant l'appel Kafka : une autre
 * instance ne le reprend qu'après expiration ; un échec après l'accusé Kafka republie le même eventId.
 */
@Component
@ConditionalOnProperty(name = "chat.outbox.enabled", havingValue = "true")
class ChatOutboxRelay implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(ChatOutboxRelay.class);
    private final MongoTemplate mongo;
    private final KafkaProducer<String, String> producer;
    private final String topic;

    ChatOutboxRelay(MongoTemplate mongo, @Value("${chat.outbox.bootstrap-servers}") String bootstrap,
                    @Value("${chat.outbox.topic}") String topic) {
        this.mongo = mongo;
        this.topic = topic;
        this.producer = new KafkaProducer<>(Map.ofEntries(
            Map.entry(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap),
            Map.entry(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class),
            Map.entry(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class),
            Map.entry(ProducerConfig.CLIENT_ID_CONFIG, "youscout-chat-outbox"),
            Map.entry(ProducerConfig.ACKS_CONFIG, "all"),
            Map.entry(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true),
            Map.entry(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 1500),
            Map.entry(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 4000),
            Map.entry(ProducerConfig.MAX_BLOCK_MS_CONFIG, 2000),
            Map.entry(ProducerConfig.LINGER_MS_CONFIG, 0)));
    }

    @Scheduled(fixedDelayString = "${chat.outbox.poll-ms:1000}", initialDelayString = "3000")
    void poll() {
        try {
            for (int count = 0; count < 20 && publishNext(); count++) { /* lot borné */ }
        } catch (RuntimeException unavailable) {
            log.warn("Chat outbox unavailable; pending events will be retried.");
        }
    }

    boolean publishNext() {
        Date now = new Date();
        Criteria free = new Criteria().orOperator(where("lockedUntil").is(null), where("lockedUntil").lt(now));
        var next = query(new Criteria().andOperator(where("publishedAt").is(null), where("nextAttemptAt").lte(now), free))
            .with(Sort.by("nextAttemptAt", "createdAt"));
        Document event = mongo.findAndModify(next, new Update().set("lockedUntil", Date.from(Instant.now().plusSeconds(60))),
            FindAndModifyOptions.options().returnNew(true), Document.class, ChatStore.OUTBOX);
        if (event == null) return false;
        try {
            producer.send(new ProducerRecord<>(topic, event.getString("key"), event.get("payload", Document.class).toJson()))
                .get(5, TimeUnit.SECONDS);
        } catch (Exception unavailable) {
            if (unavailable instanceof InterruptedException) Thread.currentThread().interrupt();
            int attempts = ((Number) event.get("attempts")).intValue();
            mongo.updateFirst(query(where("_id").is(event.getString("_id"))), new Update().inc("attempts", 1)
                .set("nextAttemptAt", Date.from(Instant.now().plusSeconds(Math.min(300, 1L << Math.min(8, attempts)))))
                .unset("lockedUntil"), ChatStore.OUTBOX);
            log.warn("Kafka unavailable; chat event {} retained for retry.", event.getString("_id"));
            return false;
        }
        mongo.updateFirst(query(where("_id").is(event.getString("_id"))),
            new Update().set("publishedAt", new Date()).unset("lockedUntil"), ChatStore.OUTBOX);
        return true;
    }

    @Override public void close() { producer.close(Duration.ofSeconds(3)); }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @ConditionalOnProperty(name = "chat.outbox.enabled", havingValue = "true")
    static class Scheduling {}
}
