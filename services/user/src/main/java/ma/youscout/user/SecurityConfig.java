package ma.youscout.user;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

/** Définit les accès autorisés ; toute route non déclarée est refusée. */
@Configuration
class SecurityConfig {
    @Bean SecurityFilterChain security(HttpSecurity http) throws Exception {
        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(new RealmRoles());
        // Chaque requête valide son Bearer JWT ; aucune session HTTP ne mémorise la connexion.
        return http.csrf(c -> c.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a -> a
                .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                .requestMatchers("/v1/users/admin/**").hasRole("admin")
                .requestMatchers("/v1/users/me").hasAnyRole("user", "admin")
                .requestMatchers("/v1/users/session").authenticated()
                .anyRequest().denyAll())
            .exceptionHandling(e -> e.authenticationEntryPoint((request, response, error) -> ApiSecurityErrors.unauthorized(response, error))
                .accessDeniedHandler((request, response, error) -> ApiSecurityErrors.forbidden(response)))
            // Le service vérifie lui-même le JWT, même si la Gateway l’a déjà accepté.
            .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(converter))
                .authenticationEntryPoint((request, response, error) -> ApiSecurityErrors.unauthorized(response, error))
                .accessDeniedHandler((request, response, error) -> ApiSecurityErrors.forbidden(response)))
            .build();
    }
}
