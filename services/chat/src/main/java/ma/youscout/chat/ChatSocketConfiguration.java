package ma.youscout.chat;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

/** Entrée WebSocket de Chat ; une requête sans en-tête Origin (application mobile) reste admise. */
@Configuration(proxyBeanMethods = false)
@EnableWebSocket
class ChatSocketConfiguration implements WebSocketConfigurer {
    private final ChatSocketHandler handler;
    private final String[] origins;

    ChatSocketConfiguration(ChatSocketHandler handler, @Value("${chat.allowed-origins}") String[] origins) {
        this.handler = handler;
        this.origins = origins;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/v1/chat/ws").setAllowedOrigins(origins);
    }

    // Une trame dépasse rarement un message de 2000 caractères ; le client envoie un ping toutes les 30 secondes.
    @Bean ServletServerContainerFactoryBean createWebSocketContainer() {
        var container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(16 * 1024);
        container.setMaxSessionIdleTimeout(120_000L);
        return container;
    }
}
