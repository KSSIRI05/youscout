package ma.youscout.video;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.http.HttpMethod;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;

/** Valide le JWT indépendamment de la Gateway et refuse les routes non déclarées. */
@Configuration(proxyBeanMethods = false)
class SecurityConfig {
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(new RealmRoles());
        // API Bearer sans cookies de session : CSRF ne protège pas cette authentification.
        return http.csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(access -> access
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/v1/videos/session").hasAnyRole("user", "admin")
                        .requestMatchers(HttpMethod.GET, "/v1/videos/{id:[0-9a-fA-F-]+}").hasAnyRole("user", "admin")
                        .requestMatchers(HttpMethod.POST, "/v1/videos/uploads").hasAnyRole("user", "admin")
                        .requestMatchers(HttpMethod.POST, "/v1/videos/*/complete").hasAnyRole("user", "admin")
                        .anyRequest().denyAll())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, error) -> ApiSecurityErrors.unauthorized(response, error))
                        .accessDeniedHandler((request, response, error) -> ApiSecurityErrors.forbidden(response)))
                .oauth2ResourceServer(resource -> resource
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(converter))
                        .authenticationEntryPoint((request, response, error) -> ApiSecurityErrors.unauthorized(response, error))
                        .accessDeniedHandler((request, response, error) -> ApiSecurityErrors.forbidden(response)))
                .build();
    }
}
