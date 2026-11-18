package ma.youscout.chat;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Diffuse les trames à toutes les instances Chat par Redis Pub/Sub ; chacune les remet à ses connexions.
 * Si Redis est indisponible, la trame n'est remise qu'aux connexions locales : le message reste enregistré
 * et les autres clients le récupèrent par l'historique à leur reconnexion.
 */
@Component
class ChatRelay {
    static final String CHANNEL = "youscout.chat.v1";
    private static final Logger log = LoggerFactory.getLogger(ChatRelay.class);

    private final StringRedisTemplate redis;
    private final ChatSessions sessions;
    private final JsonMapper json;

    ChatRelay(StringRedisTemplate redis, ChatSessions sessions, JsonMapper json) {
        this.redis = redis;
        this.sessions = sessions;
        this.json = json;
    }

    void publish(Collection<String> subjects, String skipConnection, Map<String, Object> frame) {
        var envelope = new LinkedHashMap<String, Object>();
        envelope.put("to", List.copyOf(subjects));
        envelope.put("skip", skipConnection);
        envelope.put("frame", frame);
        try {
            redis.convertAndSend(CHANNEL, json.writeValueAsString(envelope));
        } catch (RuntimeException unavailable) {
            // Le contenu des messages privés n'est jamais journalisé.
            log.warn("Relais Redis indisponible ({}); livraison limitée aux connexions locales.", unavailable.getClass().getSimpleName());
            sessions.deliver(subjects, skipConnection, json.writeValueAsString(frame));
        }
    }

    void receive(String raw) {
        Map<?, ?> envelope = json.readValue(raw, Map.class);
        if (!(envelope.get("to") instanceof List<?> to) || !(envelope.get("frame") instanceof Map<?, ?> frame)) return;
        String skip = envelope.get("skip") instanceof String value ? value : "";
        sessions.deliver(to.stream().filter(String.class::isInstance).map(String.class::cast).toList(), skip, json.writeValueAsString(frame));
    }

    @Configuration(proxyBeanMethods = false)
    static class Subscription {
        @Bean RedisMessageListenerContainer chatRelayListener(RedisConnectionFactory redis, ChatRelay relay) {
            var container = new RedisMessageListenerContainer();
            container.setConnectionFactory(redis);
            container.addMessageListener((message, pattern) -> {
                try {
                    relay.receive(new String(message.getBody(), StandardCharsets.UTF_8));
                } catch (RuntimeException invalid) {
                    log.warn("Trame de relais ignorée ({}).", invalid.getClass().getSimpleName());
                }
            }, new ChannelTopic(CHANNEL));
            return container;
        }
    }
}
