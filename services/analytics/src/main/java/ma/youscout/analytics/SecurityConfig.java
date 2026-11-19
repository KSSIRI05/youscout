package ma.youscout.analytics;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

/** Les statistiques sont réservées aux administrateurs ; toute route non déclarée est refusée. */
@Configuration(proxyBeanMethods = false)
class SecurityConfig {
    @Bean SecurityFilterChain security(HttpSecurity http) throws Exception {
        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(new RealmRoles());
        return http.csrf(c -> c.disable()).sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a -> a
                .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                .requestMatchers("/v1/analytics/**").hasRole("admin")
                .anyRequest().denyAll())
            .exceptionHandling(e -> e.authenticationEntryPoint((request, response, error) -> ApiSecurityErrors.unauthorized(response, error))
                .accessDeniedHandler((request, response, error) -> ApiSecurityErrors.forbidden(response)))
            .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(converter))
                .authenticationEntryPoint((request, response, error) -> ApiSecurityErrors.unauthorized(response, error))
                .accessDeniedHandler((request, response, error) -> ApiSecurityErrors.forbidden(response)))
            .build();
    }
}
