package com.facturaflow.sales;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.UnaryOperator;

/**
 * Keycloak falso para los tests (ADR 0018): un WireMock que publica el JWKS de una clave RSA generada en cada
 * ejecución y un token endpoint de client credentials, y tokens firmados con esa clave con la forma de los del realm
 * ({@code iss}, {@code aud = facturaflow-api}, {@code realm_access.roles}, {@code scope}). Así los tests de integración
 * pasan por el validador REAL de Spring Boot (firma, emisor, audiencia y vigencia), sin un Keycloak de verdad.
 * El Keycloak real se prueba en {@code SalesKeycloakIntegrationTest}.
 */
public final class FakeKeycloak {

    /** El emisor por defecto de {@code application.yml} (el fijo del realm, ADR 0017). */
    public static final String ISSUER = "http://localhost:8180/realms/facturaflow";
    public static final String AUDIENCE = "facturaflow-api";
    public static final String TOKEN_PATH = "/realms/facturaflow/protocol/openid-connect/token";
    public static final String CERTS_PATH = "/realms/facturaflow/protocol/openid-connect/certs";
    /** El token de client credentials que entrega el token endpoint falso (sales-service con catalog.read). */
    public static final String SERVICE_TOKEN;

    private static final RSAKey KEY = generateKey();
    private static final RSAKey OTHER_KEY = generateKey();
    private static final WireMockServer SERVER = new WireMockServer(wireMockConfig().dynamicPort());

    static {
        SERVER.start();
        SERVER.stubFor(get(CERTS_PATH).willReturn(okJson(new JWKSet(KEY.toPublicJWK()).toString())));
        SERVICE_TOKEN = serviceToken("catalog.read");
        SERVER.stubFor(post(TOKEN_PATH).willReturn(okJson("""
                { "access_token": "%s", "token_type": "Bearer", "expires_in": 300 }""".formatted(SERVICE_TOKEN))));
    }

    private FakeKeycloak() {
    }

    public static String jwkSetUri() {
        return SERVER.baseUrl() + CERTS_PATH;
    }

    public static String tokenUri() {
        return SERVER.baseUrl() + TOKEN_PATH;
    }

    /**
     * Token de un usuario con esos roles de realm. Dura 1 h (no 5 min como los del realm): un contexto de Spring
     * cacheado lo reutiliza durante toda la ejecución.
     */
    public static String userToken(String... roles) {
        return sign(userClaims(roles).build(), KEY);
    }

    /** Token de usuario con los claims modificados (vencido, otro emisor, otra audiencia...). */
    public static String userToken(UnaryOperator<JWTClaimsSet.Builder> change, String... roles) {
        return sign(change.apply(userClaims(roles)).build(), KEY);
    }

    /** Token válido en todo, pero firmado con una clave que el realm no publica. */
    public static String tokenSignedWithUnknownKey(String... roles) {
        return sign(userClaims(roles).build(), OTHER_KEY);
    }

    /** Token de un cliente de servicio (client credentials): scopes y ningún rol de persona. */
    public static String serviceToken(String... scopes) {
        Instant now = Instant.now();
        return sign(new JWTClaimsSet.Builder()
                .issuer(ISSUER).audience(AUDIENCE).subject(UUID.randomUUID().toString())
                .issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(3600)))
                .claim("azp", "sales-service").claim("scope", String.join(" ", scopes))
                .build(), KEY);
    }

    private static JWTClaimsSet.Builder userClaims(String... roles) {
        Instant now = Instant.now();
        return new JWTClaimsSet.Builder()
                .issuer(ISSUER).audience(List.of(AUDIENCE, "account")).subject(UUID.randomUUID().toString())
                .issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(3600)))
                .claim("azp", "facturaflow-cli").claim("scope", "openid profile email")
                .claim("preferred_username", "usuario.prueba")
                .claim("realm_access", Map.of("roles", List.of(roles)));
    }

    private static String sign(JWTClaimsSet claims, RSAKey key) {
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),
                    claims);
            jwt.sign(new RSASSASigner(key));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    private static RSAKey generateKey() {
        try {
            return new RSAKeyGenerator(2048).keyID(UUID.randomUUID().toString()).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }
}
