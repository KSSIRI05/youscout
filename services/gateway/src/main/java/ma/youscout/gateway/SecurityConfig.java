package ma.youscout.gateway;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
@Configuration
class SecurityConfig {
    @Bean SecurityWebFilterChain security(ServerHttpSecurity http) {
        return http.csrf(ServerHttpSecurity.CsrfSpec::disable)
            .cors(Customizer.withDefaults())
            .authorizeExchange(a -> a.pathMatchers("/actuator/health", "/actuator/health/**").permitAll().anyExchange().authenticated())
            .oauth2ResourceServer(o -> o.jwt(Customizer.withDefaults())).build();
    }
    @Bean UrlBasedCorsConfigurationSource corsConfigurationSource() {
        var cors = new CorsConfiguration();
        cors.setAllowedOrigins(List.of("http://localhost:7357", "http://127.0.0.1:7357"));
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Request-Id"));
        cors.setExposedHeaders(List.of("X-Request-Id"));
        cors.setMaxAge(600L);
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/v1/**", cors);
        return source;
    }
}
