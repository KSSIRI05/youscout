package ma.youscout.video;

import java.util.UUID;

/** Retourne seulement après l'accusé de réception Kafka ; jamais depuis la transaction HTTP. */
interface EventPublisher {
    void publish(UUID videoId, String payload) throws Exception;
}
