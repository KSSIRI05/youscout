package ma.youscout.gateway;

import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** Produit des erreurs de sécurité JSON sans exposer les détails internes du jeton. */
final class ApiSecurityErrors {
    private ApiSecurityErrors() {}
    // 401 : authentification absente ou invalide ; WWW-Authenticate indique le schéma Bearer.
    static Mono<Void> unauthorized(ServerWebExchange exchange, AuthenticationException error) {
        exchange.getResponse().getHeaders().set(HttpHeaders.WWW_AUTHENTICATE,
            error instanceof OAuth2AuthenticationException ? "Bearer error=\"invalid_token\"" : "Bearer");
        return write(exchange, HttpStatus.UNAUTHORIZED, "Authentification requise", "Jeton absent, invalide ou expiré.");
    }
    static Mono<Void> forbidden(ServerWebExchange exchange) {
        return write(exchange, HttpStatus.FORBIDDEN, "Accès interdit", "Droits insuffisants pour cette ressource.");
    }
    private static Mono<Void> write(ServerWebExchange exchange, HttpStatus status, String title, String detail) {
        var response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        byte[] body = ("{\"type\":\"about:blank\",\"status\":" + status.value()
            + ",\"title\":\"" + title + "\",\"detail\":\"" + detail + "\"}").getBytes(StandardCharsets.UTF_8);
        return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
    }
}
