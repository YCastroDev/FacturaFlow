package com.facturaflow.invoicing.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/** Copia deliberada en los cuatro servicios (ADR 0018): si cambia aquí, cambia en todos. */
class KeycloakJwtAuthoritiesConverterTest {

    private final KeycloakJwtAuthoritiesConverter converter = new KeycloakJwtAuthoritiesConverter();

    private static Jwt jwt(Map<String, Object> claims) {
        Jwt.Builder builder = Jwt.withTokenValue("t").header("alg", "RS256").subject("u")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60));
        claims.forEach(builder::claim);
        return builder.build();
    }

    private List<String> authorities(Map<String, Object> claims) {
        Collection<GrantedAuthority> authorities = converter.convert(jwt(claims));
        return authorities.stream().map(GrantedAuthority::getAuthority).toList();
    }

    @Test
    @DisplayName("un usuario del realm: sus roles como ROLE_ y sus scopes como SCOPE_")
    void userToken() {
        assertThat(authorities(Map.of("scope", "openid profile",
                "realm_access", Map.of("roles", List.of("vendedor", "default-roles-facturaflow")))))
                .containsExactlyInAnyOrder("SCOPE_openid", "SCOPE_profile", "ROLE_vendedor",
                        "ROLE_default-roles-facturaflow");
    }

    @Test
    @DisplayName("sales-service (client credentials): solo SCOPE_catalog.read, ningún rol de persona")
    void serviceToken() {
        assertThat(authorities(Map.of("scope", "catalog.read"))).containsExactly("SCOPE_catalog.read");
    }

    @Test
    @DisplayName("un realm_access con otra forma no da roles ni falla")
    void malformedRealmAccess() {
        assertThat(authorities(Map.of("realm_access", "admin"))).isEmpty();
        assertThat(authorities(Map.of("realm_access", Map.of("roles", "admin")))).isEmpty();
        assertThat(authorities(Map.of("realm_access", Map.of("roles", List.of(1, " ", "admin")))))
                .containsExactly("ROLE_admin");
    }

    @Test
    @DisplayName("un rol de cliente (resource_access) no es un rol de realm: no da autoridad")
    void clientRolesAreIgnored() {
        assertThat(authorities(Map.of("resource_access", Map.of("account", Map.of("roles", List.of("admin"))))))
                .isEmpty();
    }
}
