package ma.youscout.gateway;
import java.util.List;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
/** Définit les accès autorisés ; toute route non déclarée est refusée. */
@Configuration
class SecurityConfig {
    @Bean SecurityWebFilterChain security(ServerHttpSecurity http) {
        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(new RealmRoles());
        // API avec jeton Bearer : pas de formulaire ni de session applicative à protéger par CSRF.
        return http.csrf(ServerHttpSecurity.CsrfSpec::disable)
            .cors(Customizer.withDefaults())
            .authorizeExchange(a -> a
                .pathMatchers("/actuator/health", "/actuator/health/**").permitAll()
                // La règle admin doit précéder la règle générale /v1/skills/** : le premier match gagne.
                .pathMatchers("/v1/skills/admin", "/v1/skills/admin/**").hasRole("admin")
                .pathMatchers("/v1/skills", "/v1/skills/**", "/v1/hashtags", "/v1/hashtags/**").hasAnyRole("user", "admin")
                .pathMatchers("/v1/users/admin/**").hasRole("admin")
                .pathMatchers("/v1/users/me").hasAnyRole("user", "admin")
                .pathMatchers("/v1/users/session").authenticated()
                .pathMatchers(org.springframework.http.HttpMethod.GET, "/v1/videos/session").hasAnyRole("user", "admin")
                .pathMatchers(org.springframework.http.HttpMethod.POST, "/v1/videos/uploads").hasAnyRole("user", "admin")
                .pathMatchers(org.springframework.http.HttpMethod.POST, "/v1/videos/*/complete").hasAnyRole("user", "admin")
                .anyExchange().denyAll())
            .exceptionHandling(e -> e.authenticationEntryPoint(ApiSecurityErrors::unauthorized)
                .accessDeniedHandler((exchange, error) -> ApiSecurityErrors.forbidden(exchange)))
            .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(new ReactiveJwtAuthenticationConverterAdapter(converter)))
                .authenticationEntryPoint(ApiSecurityErrors::unauthorized)
                .accessDeniedHandler((exchange, error) -> ApiSecurityErrors.forbidden(exchange))).build();
    }
    // Autorise uniquement les origines de la démo Flutter web locale, pas toutes les origines.
    @Bean UrlBasedCorsConfigurationSource corsConfigurationSource() {
        var cors = new CorsConfiguration();
        cors.setAllowedOrigins(List.of("http://localhost:7357", "http://127.0.0.1:7357"));
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Request-Id", "Idempotency-Key"));
        cors.setExposedHeaders(List.of("X-Request-Id"));
        cors.setMaxAge(600L);
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/v1/**", cors);
        return source;
    }
}
