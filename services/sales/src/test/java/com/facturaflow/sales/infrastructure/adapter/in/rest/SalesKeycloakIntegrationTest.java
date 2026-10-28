package com.facturaflow.sales.infrastructure.adapter.in.rest;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.facturaflow.sales.InfraImages;
import com.facturaflow.sales.RealKeycloak;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
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
 * Sales con un Keycloak REAL y el realm del repositorio (ADR 0018), de punta a punta: el vendedor pide su token, sales
 * lo valida contra el JWKS real y, para consultar catalog (WireMock), pide SU propio token a Keycloak con client
 * credentials ({@code sales-service} y el secreto inyectado al realm). Se comprueba lo que catalog recibe: un token de
 * sales-service con {@code catalog.read} y la audiencia de la API, el mismo en las dos llamadas de la venta (caché).
 * Lo que catalog hace con ese token lo prueba {@code CatalogKeycloakIntegrationTest} en catalog.
 *
 * <p>Sin RabbitMQ (no interviene): sales arranca sin broker (ADR 0013) y el evento queda en el outbox.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.rabbitmq.host=127.0.0.1", "spring.rabbitmq.port=1", "spring.rabbitmq.password=sin-broker",
        "sales.catalog.read-timeout=10s"})
@Import(SalesKeycloakIntegrationTest.PostgresOnly.class)
class SalesKeycloakIntegrationTest {

    /** Solo PostgreSQL: sin el Keycloak falso de {@code TestcontainersConfiguration}. */
    @TestConfiguration(proxyBeanMethods = false)
    static class PostgresOnly {

        @Bean
        @ServiceConnection
        PostgreSQLContainer postgresContainer() {
            return new PostgreSQLContainer(InfraImages.POSTGRES);
        }
    }

    private static final WireMockServer CATALOG = new WireMockServer(wireMockConfig().dynamicPort());

    static {
        CATALOG.start();
    }

    private static final String SALE = """
            { "referenceCode": "%s", "customer": { "documentType": "NIT", "documentNumber": "890903938" },
              "lines": [ { "productCode": "CAFE-500", "quantity": 1 } ],
              "paymentMethod": "CASH", "paymentTerms": { "type": "CASH" } }""";

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void realKeycloak(DynamicPropertyRegistry registry) {
        RealKeycloak.start();
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", RealKeycloak::issuer);
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", RealKeycloak::jwkSetUri);
        registry.add("sales.catalog.base-url", CATALOG::baseUrl);
        registry.add("sales.catalog.auth.token-uri", RealKeycloak::tokenUri);
        registry.add("sales.catalog.auth.client-secret", () -> RealKeycloak.SALES_SERVICE_SECRET);
    }

    @AfterAll
    static void stop() {
        CATALOG.stop();
    }

    @BeforeEach
    void catalogUp() {
        CATALOG.resetAll();
        CATALOG.stubFor(get("/api/v1/customers/by-document/NIT/890903938").willReturn(okJson("""
                { "id": "c0000000-0000-4000-8000-000000000001", "documentType": "NIT", "documentNumber": "890903938",
                  "checkDigit": 8, "personType": "LEGAL_ENTITY", "companyName": "Cliente Ejemplo S.A.S.",
                  "taxScheme": "IVA", "fiscalResponsibilities": [ "LARGE_TAXPAYER" ], "active": true }""")));
        CATALOG.stubFor(post("/api/v1/products/lookup").willReturn(okJson("""
                { "items": [
                  { "id": "9f000000-0000-4000-8000-0000000000c1", "code": "CAFE-500", "name": "Café molido 500 g",
                    "unitPrice": 18500.00, "unit": "UNIT", "ivaExcluded": false,
                    "taxes": [ { "type": "IVA", "rate": 5 } ], "active": true } ] }""")));
    }

    private int createSale(String referenceCode, String token) {
        return RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build()
                .post().uri("/api/v1/sales").contentType(MediaType.APPLICATION_JSON).body(SALE.formatted(referenceCode))
                .headers(h -> h.setBearerAuth(token))
                .exchange().expectBody(String.class).returnResult().getStatus().value();
    }

    private static String claims(String bearer) {
        String jwt = bearer.substring("Bearer ".length());
        return new String(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("el vendedor vende con su token real y sales consulta catalog con el token real de sales-service")
    void sellerSellsAndSalesCallsCatalogWithItsOwnIdentity() {
        assertThat(createSale("VENTA-KC-1", RealKeycloak.userToken("vendedor.demo"))).isEqualTo(201);

        List<String> authorizations = CATALOG.getAllServeEvents().stream()
                .map(event -> event.getRequest().getHeader("Authorization")).toList();
        assertThat(authorizations).as("cliente y lote de productos").hasSize(2).doesNotContainNull();
        String claims = claims(authorizations.getFirst());
        assertThat((String) JsonPath.read(claims, "$.azp")).isEqualTo("sales-service");
        assertThat(((String) JsonPath.read(claims, "$.scope")).split(" ")).contains("catalog.read");
        assertThat(claims).contains("facturaflow-api").doesNotContain("\"vendedor\"");
    }

    @Test
    @DisplayName("el token de sales-service se reutiliza: las dos llamadas de una venta y la venta siguiente llevan el mismo")
    void serviceTokenIsCached() {
        assertThat(createSale("VENTA-KC-2", RealKeycloak.userToken("vendedor.demo"))).isEqualTo(201);
        assertThat(createSale("VENTA-KC-3", RealKeycloak.userToken("admin.demo"))).isEqualTo(201);

        assertThat(CATALOG.getAllServeEvents()).hasSize(4)
                .extracting(event -> event.getRequest().getHeader("Authorization")).containsOnly(
                        CATALOG.getAllServeEvents().getFirst().getRequest().getHeader("Authorization"));
    }

    @Test
    @DisplayName("el contador no vende (403) y sin token es 401: catalog ni se consulta")
    void otherRolesCannotSell() {
        assertThat(createSale("VENTA-KC-4", RealKeycloak.userToken("contador.demo"))).isEqualTo(403);
        assertThat(CATALOG.getAllServeEvents()).isEmpty();
    }
}
