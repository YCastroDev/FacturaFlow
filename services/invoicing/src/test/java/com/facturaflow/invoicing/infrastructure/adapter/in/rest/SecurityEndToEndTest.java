package com.facturaflow.invoicing.infrastructure.adapter.in.rest;

import static org.assertj.core.api.Assertions.assertThat;

import com.facturaflow.invoicing.FakeKeycloak;
import com.facturaflow.invoicing.TestcontainersConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * Lo público y lo reservado de invoicing con la aplicación completa (ADR 0018): el health (healthcheck de compose y
 * prueba de humo) y Swagger sin token; el resto de Actuator solo para admin. Usa su propio cliente, sin el token
 * por defecto de los demás tests: cada petición lleva el suyo, o ninguno.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class SecurityEndToEndTest {

    @LocalServerPort
    private int port;

    private RestTestClient.ResponseSpec get(String uri, String token) {
        RestTestClient client = RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
        return client.get().uri(uri)
                .headers(headers -> {
                    if (token != null) {
                        headers.setBearerAuth(token);
                    }
                })
                .exchange();
    }

    @Test
    @DisplayName("el health y sus grupos son públicos")
    void healthIsPublic() {
        get("/actuator/health", null).expectStatus().isOk();
        get("/actuator/health/dependencies", null).expectStatus().isOk();
    }

    @Test
    @DisplayName("el resto de Actuator: sin token 401, contador o vendedor 403, admin 200")
    void actuatorIsAdminOnly() {
        get("/actuator/metrics", null).expectStatus().isUnauthorized();
        get("/actuator/metrics", FakeKeycloak.userToken("contador")).expectStatus().isForbidden();
        get("/actuator/circuitbreakers", FakeKeycloak.userToken("vendedor")).expectStatus().isForbidden();
        get("/actuator/metrics", FakeKeycloak.userToken("admin")).expectStatus().isOk();
    }

    @Test
    @DisplayName("Swagger UI y /v3/api-docs son públicos y el documento trae el esquema OAuth2 (PKCE contra el realm)")
    void swaggerIsPublic() {
        get("/swagger-ui/index.html", null).expectStatus().isOk();
        String apiDocs = get("/v3/api-docs", null).expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();

        assertThat(apiDocs).contains("\"keycloak\"", "\"oauth2\"",
                "\"authorizationUrl\":\"http://localhost:8180/realms/facturaflow/protocol/openid-connect/auth\"",
                "\"tokenUrl\":\"http://localhost:8180/realms/facturaflow/protocol/openid-connect/token\"");
    }

    @Test
    @DisplayName("una ruta que no existe: 401 sin token y 403 con token (denegada por defecto, sin revelar si existe)")
    void unknownRoutesAreDenied() {
        get("/api/v1/nada", null).expectStatus().isEqualTo(HttpStatus.UNAUTHORIZED);
        get("/api/v1/nada", FakeKeycloak.userToken("admin")).expectStatus().isForbidden();
        get("/actuator/env", FakeKeycloak.userToken("contador")).expectStatus().isForbidden();
    }
}
