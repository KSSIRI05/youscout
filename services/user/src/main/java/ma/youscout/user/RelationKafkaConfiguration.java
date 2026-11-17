package ma.youscout.user;

import java.time.Duration;
import java.util.Map;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "youscout.user-outbox.enabled", havingValue = "true")
class RelationKafkaConfiguration {
    @Bean(destroyMethod = "close")
    RelationKafkaPublisher relationKafkaPublisher(
            @Value("${youscout.user-outbox.bootstrap-servers}") String bootstrap,
            @Value("${youscout.user-outbox.topic}") String topic) {
        var producer = new KafkaProducer<String, String>(Map.ofEntries(
            Map.entry(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap),
            Map.entry(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class),
            Map.entry(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class),
            Map.entry(ProducerConfig.CLIENT_ID_CONFIG, "youscout-user-relations-outbox"),
            Map.entry(ProducerConfig.ACKS_CONFIG, "all"),
            Map.entry(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true),
            Map.entry(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 1500),
            Map.entry(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 4000),
            Map.entry(ProducerConfig.MAX_BLOCK_MS_CONFIG, 2000),
            Map.entry(ProducerConfig.LINGER_MS_CONFIG, 0)));
        return new RelationKafkaPublisher(producer, topic);
    }
}
