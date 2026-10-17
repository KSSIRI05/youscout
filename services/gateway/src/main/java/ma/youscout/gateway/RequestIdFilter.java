package ma.youscout.gateway;
import java.util.UUID;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class RequestIdFilter implements WebFilter {
    @Override public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        var incoming = exchange.getRequest().getHeaders().getFirst("X-Request-Id");
        var id = incoming != null && incoming.matches("[A-Za-z0-9-]{1,64}") ? incoming : UUID.randomUUID().toString();
        exchange.getResponse().getHeaders().set("X-Request-Id", id);
        return chain.filter(exchange.mutate().request(r -> r.headers(h -> h.set("X-Request-Id", id))).build());
    }
}
