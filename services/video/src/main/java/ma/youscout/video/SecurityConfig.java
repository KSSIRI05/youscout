package ma.youscout.video;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.http.HttpMethod;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;

/** Valide le JWT indépendamment de la Gateway et refuse les routes non déclarées. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
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
                        .requestMatchers(HttpMethod.GET, "/v1/videos/admin/{id:[0-9a-fA-F-]+}", "/v1/videos/admin/{id:[0-9a-fA-F-]+}/playback").hasRole("admin")
                        .requestMatchers(HttpMethod.POST, "/v1/videos/admin/{id:[0-9a-fA-F-]+}/takedown", "/v1/videos/admin/{id:[0-9a-fA-F-]+}/restore").hasRole("admin")
                        .requestMatchers(HttpMethod.GET, "/v1/videos/session").hasAnyRole("user", "admin")
                        .requestMatchers(HttpMethod.GET, "/v1/videos", "/v1/videos/{id:[0-9a-fA-F-]+}", "/v1/videos/{id:[0-9a-fA-F-]+}/playback", "/v1/videos/{id:[0-9a-fA-F-]+}/public-playback", "/v1/videos/{id:[0-9a-fA-F-]+}/publication").hasAnyRole("user", "admin")
                        .requestMatchers(HttpMethod.POST, "/v1/videos/uploads").hasAnyRole("user", "admin")
                        .requestMatchers(HttpMethod.POST, "/v1/videos/*/complete").hasAnyRole("user", "admin")
                        .requestMatchers(HttpMethod.POST, "/v1/videos/{id:[0-9a-fA-F-]+}/publish").hasAnyRole("user", "admin")
                        .requestMatchers(HttpMethod.DELETE, "/v1/videos/{id:[0-9a-fA-F-]+}").hasAnyRole("user", "admin")
                        // U22 : effacement demandé par User pour un compte fermé.
                        .requestMatchers(HttpMethod.DELETE, "/internal/accounts/*").hasRole("account-closure")
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
