package com.facturaflow.catalog.config;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;

/**
 * Autoridades de un JWT de Keycloak (ADR 0018): los roles de realm ({@code realm_access.roles}) como
 * {@code ROLE_<rol>} y los scopes ({@code scope}) como {@code SCOPE_<scope>}, el formato estándar de Spring
 * Security. Spring solo lee claims de primer nivel, y {@code realm_access.roles} está anidado.
 *
 * <p><b>Copia deliberada</b> en gateway, invoicing, catalog y sales (sin librería compartida, ADR 0001); si
 * cambia en un servicio, cambia en los cuatro.
 */
final class KeycloakJwtAuthoritiesConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

    static final String REALM_ACCESS_CLAIM = "realm_access";
    static final String ROLES = "roles";

    private final JwtGrantedAuthoritiesConverter scopes = new JwtGrantedAuthoritiesConverter();

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        List<GrantedAuthority> authorities = new ArrayList<>(scopes.convert(jwt));
        for (String role : realmRoles(jwt)) {
            authorities.add(new SimpleGrantedAuthority("ROLE_" + role));
        }
        return authorities;
    }

    /** Roles de realm; vacío si el claim falta o no tiene la forma esperada (un token de máquina no trae roles). */
    private static List<String> realmRoles(Jwt jwt) {
        if (!(jwt.getClaims().get(REALM_ACCESS_CLAIM) instanceof Map<?, ?> realmAccess)
                || !(realmAccess.get(ROLES) instanceof Collection<?> roles)) {
            return List.of();
        }
        return roles.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .filter(role -> !role.isBlank())
                .toList();
    }
}
