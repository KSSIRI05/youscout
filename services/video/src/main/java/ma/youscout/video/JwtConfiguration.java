package ma.youscout.video;

import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.util.StringUtils;

/** Un JWT valide doit aussi identifier son propriétaire : aucun sub fourni par le client HTTP. */
@Configuration(proxyBeanMethods = false)
class JwtConfiguration {
    @Bean
    JwtDecoder videoJwtDecoder(
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuer,
            @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}") String jwks,
            @Value("${spring.security.oauth2.resourceserver.jwt.audiences}") String audience) {
        var decoder = NimbusJwtDecoder.withJwkSetUri(jwks).jwsAlgorithm(SignatureAlgorithm.RS256).build();
        // Le validateur standard contrôle issuer, expiration et not-before (tolérance usuelle de 60 s).
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<Jwt>(
                JwtValidators.createDefaultWithIssuer(issuer),
                new JwtClaimValidator<List<String>>(JwtClaimNames.AUD,
                        audiences -> audiences != null && audiences.contains(audience)),
                new JwtClaimValidator<String>(JwtClaimNames.SUB, StringUtils::hasText),
                new JwtClaimValidator<Instant>(JwtClaimNames.EXP, expiry -> expiry != null)));
        return decoder;
    }
}
