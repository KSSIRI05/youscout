package ma.youscout.gateway;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;

/** Convertit les rôles du realm du JWT vérifié en droits Spring Security. */
class RealmRoles implements Converter<Jwt, Collection<GrantedAuthority>> {
    @Override public Collection<GrantedAuthority> convert(Jwt jwt) {
        // Les scopes restent des autorités SCOPE_ ; seuls user et admin deviennent des ROLE_.
        var authorities = new ArrayList<GrantedAuthority>(new JwtGrantedAuthoritiesConverter().convert(jwt));
        // Les rôles client et les en-têtes HTTP ne donnent aucun rôle applicatif ici.
        if (jwt.getClaims().get("realm_access") instanceof Map<?, ?> realm
                && realm.get("roles") instanceof Collection<?> roles) {
            for (Object role : roles) {
                if (role instanceof String name && Set.of("user", "admin").contains(name)) {
                    authorities.add(new SimpleGrantedAuthority("ROLE_" + name));
                }
            }
        }
        return authorities;
    }
}
