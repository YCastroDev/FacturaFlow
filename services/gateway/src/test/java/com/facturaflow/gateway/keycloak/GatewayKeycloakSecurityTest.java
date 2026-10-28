package com.facturaflow.gateway.keycloak;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.facturaflow.gateway.RealKeycloak;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * Punta a punta con un Keycloak REAL (ADR 0018): cada usuario de demostración pide su token al realm del repositorio
 * y entra por el gateway; las claves se bajan del JWKS real. Prueba lo que el Keycloak falso no puede: que los tokens
 * reales traen la audiencia {@code facturaflow-api} y {@code realm_access.roles} con la forma que espera el conversor,
 * y que un token del mismo realm pedido por OTRO cliente (sin la audiencia) se rechaza.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayKeycloakSecurityTest {

    private static final WireMockServer SERVICE = new WireMockServer(wireMockConfig().dynamicPort());

    static {
        SERVICE.start();
    }

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void realKeycloak(DynamicPropertyRegistry registry) {
        RealKeycloak.start();
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", RealKeycloak::issuer);
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", RealKeycloak::jwkSetUri);
        registry.add("GATEWAY_INVOICING_URI", SERVICE::baseUrl);
        registry.add("GATEWAY_CATALOG_URI", SERVICE::baseUrl);
        registry.add("GATEWAY_SALES_URI", SERVICE::baseUrl);
    }

    @AfterAll
    static void stop() {
        SERVICE.stop();
    }

    @BeforeEach
    void serviceAnswers200() {
        SERVICE.resetAll();
        SERVICE.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody("{}")));
    }

    private int status(String path, String token) {
        RestTestClient client = RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
        return client.get().uri(path)
                .headers(headers -> {
                    if (token != null) {
                        headers.setBearerAuth(token);
                    }
                })
                .exchange().expectBody(String.class).returnResult().getStatus().value();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"admin.demo", "vendedor.demo", "contador.demo"})
    @DisplayName("cada usuario de demostración entra por el gateway con su token real")
    void demoUsersPass(String username) {
        assertThat(status("/api/v1/sales", RealKeycloak.userToken(username))).isEqualTo(200);
        assertThat(SERVICE.getAllServeEvents()).hasSize(1);
    }

    @Test
    @DisplayName("sin token, 401; con el token de sales-service (sin roles de persona), 403")
    void withoutTokenOrMachineToken() {
        assertThat(status("/api/v1/sales", null)).isEqualTo(401);
        assertThat(status("/api/v1/products", RealKeycloak.salesServiceToken())).isEqualTo(403);
        assertThat(SERVICE.getAllServeEvents()).isEmpty();
    }

    @Test
    @DisplayName("un token del mismo realm y del mismo usuario pedido por otro cliente (admin-cli, sin la audiencia "
            + "facturaflow-api) es 401: lo rechaza la validación, no la autorización (eso daría 403)")
    void tokenWithoutApiAudienceIsRejected() {
        String adminCliToken = RealKeycloak.userToken("admin-cli", "admin.demo");

        assertThat(status("/api/v1/sales", adminCliToken)).isEqualTo(401);
        assertThat(SERVICE.getAllServeEvents()).isEmpty();
    }

    @Test
    @DisplayName("Actuator con token real: admin 200, vendedor 403")
    void actuatorWithRealTokens() {
        assertThat(status("/actuator/metrics", RealKeycloak.userToken("admin.demo"))).isEqualTo(200);
        assertThat(status("/actuator/metrics", RealKeycloak.userToken("vendedor.demo"))).isEqualTo(403);
    }
}
