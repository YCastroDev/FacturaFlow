package com.facturaflow.catalog.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.facturaflow.catalog.InfraImages;
import com.facturaflow.catalog.RealKeycloak;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Catalog con un Keycloak REAL y el realm del repositorio (ADR 0018): los usuarios de demostración y
 * {@code sales-service} piden sus tokens de verdad y catalog los valida contra el JWKS real. Es la mitad "catalog" del
 * flujo sales → catalog con client credentials (la otra mitad, en {@code SalesKeycloakIntegrationTest} de sales): el
 * token de sales-service lee y consulta por lote, pero no escribe.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(CatalogKeycloakIntegrationTest.PostgresOnly.class)
class CatalogKeycloakIntegrationTest {

    /** Solo PostgreSQL: sin el Keycloak falso de {@code TestcontainersConfiguration}. */
    @TestConfiguration(proxyBeanMethods = false)
    static class PostgresOnly {

        @Bean
        @ServiceConnection
        PostgreSQLContainer postgresContainer() {
            return new PostgreSQLContainer(InfraImages.POSTGRES);
        }
    }

    private static final String PRODUCT = """
            { "code": "KC-%s", "name": "Producto de prueba", "unitPrice": 1000.00, "unit": "UNIT", "taxes": [] }""";

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void realKeycloak(DynamicPropertyRegistry registry) {
        RealKeycloak.start();
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", RealKeycloak::issuer);
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", RealKeycloak::jwkSetUri);
    }

    private RestTestClient client() {
        return RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    private int get(String path, String token) {
        return client().get().uri(path).headers(h -> h.setBearerAuth(token))
                .exchange().expectBody(String.class).returnResult().getStatus().value();
    }

    private int post(String path, String body, String token) {
        return client().post().uri(path).contentType(MediaType.APPLICATION_JSON).body(body)
                .headers(h -> h.setBearerAuth(token))
                .exchange().expectBody(String.class).returnResult().getStatus().value();
    }

    @Test
    @DisplayName("admin crea un producto; vendedor lo consulta pero no puede crear otro; contador no lee catalog")
    void humanRoles() {
        assertThat(post("/api/v1/products", PRODUCT.formatted("ADMIN"), RealKeycloak.userToken("admin.demo")))
                .isEqualTo(201);
        assertThat(get("/api/v1/products/by-code/KC-ADMIN", RealKeycloak.userToken("vendedor.demo"))).isEqualTo(200);
        assertThat(post("/api/v1/products", PRODUCT.formatted("VEND"), RealKeycloak.userToken("vendedor.demo")))
                .isEqualTo(403);
        assertThat(get("/api/v1/products", RealKeycloak.userToken("contador.demo"))).isEqualTo(403);
    }

    @Test
    @DisplayName("sales-service (client credentials, scope catalog.read): consulta y hace el lookup, pero no escribe")
    void salesServiceReadsOnly() {
        String salesService = RealKeycloak.salesServiceToken();

        assertThat(get("/api/v1/products", salesService)).isEqualTo(200);
        assertThat(get("/api/v1/customers/by-document/NIT/890903938", salesService)).as("no existe: 404, no 403")
                .isEqualTo(404);
        assertThat(post("/api/v1/products/lookup", "{ \"codes\": [ \"KC-NADA\" ] }", salesService)).isEqualTo(200);
        assertThat(post("/api/v1/products", PRODUCT.formatted("SVC"), salesService)).isEqualTo(403);
    }

    @Test
    @DisplayName("un token del realm sin la audiencia facturaflow-api (pedido con admin-cli) es 401")
    void tokenWithoutApiAudience() {
        assertThat(get("/api/v1/products", RealKeycloak.userToken("admin-cli", "admin.demo"))).isEqualTo(401);
    }
}
