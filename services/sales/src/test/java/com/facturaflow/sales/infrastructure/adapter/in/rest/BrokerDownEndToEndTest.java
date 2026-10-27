package com.facturaflow.sales.infrastructure.adapter.in.rest;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.facturaflow.sales.InfraImages;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * RabbitMQ caído (ADR 0013): solo PostgreSQL real; el broker apunta a un puerto donde nadie escucha. La
 * venta se crea igual (201), su evento espera en el outbox y el health de sales sigue UP: el componente
 * {@code messaging} es informativo y el indicador {@code rabbit} de Spring Boot está desactivado.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.rabbitmq.host=127.0.0.1", "spring.rabbitmq.port=1", "spring.rabbitmq.password=sin-broker",
        "sales.outbox.poll-interval=200ms"})
@AutoConfigureRestTestClient
@Import(BrokerDownEndToEndTest.PostgresOnly.class)
class BrokerDownEndToEndTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class PostgresOnly {

        @Bean
        @ServiceConnection
        PostgreSQLContainer postgresContainer() {
            return new PostgreSQLContainer(InfraImages.POSTGRES);
        }
    }

    static final WireMockServer CATALOG = new WireMockServer(wireMockConfig().dynamicPort());

    static {
        CATALOG.start();
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

    @DynamicPropertySource
    static void catalogUrl(DynamicPropertyRegistry registry) {
        registry.add("sales.catalog.base-url", CATALOG::baseUrl);
    }

    @AfterAll
    static void stop() {
        CATALOG.stop();
    }

    @Autowired
    private RestTestClient client;

    @Autowired
    private JdbcClient jdbc;

    private EntityExchangeResult<String> fetch(String uri) {
        return client.get().uri(uri).exchange().expectBody(String.class).returnResult();
    }

    @Test
    @DisplayName("con RabbitMQ caído la venta se crea (201), su evento queda pendiente y sales sigue UP")
    void salesKeepWorkingWithoutBroker() {
        EntityExchangeResult<String> created = client.post().uri("/api/v1/sales").contentType(MediaType.APPLICATION_JSON)
                .body("""
                        { "referenceCode": "VENTA-SIN-BROKER", "customer": { "documentType": "NIT", "documentNumber": "890903938" },
                          "lines": [ { "productCode": "CAFE-500", "quantity": 1 } ],
                          "paymentMethod": "CASH", "paymentTerms": { "type": "CASH" } }""")
                .exchange().expectBody(String.class).returnResult();

        assertThat(created.getStatus().value()).as(created.getResponseBody()).isEqualTo(201);
        // El relay lo intenta cada 200 ms y falla: el evento sigue pendiente, con sus intentos.
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(jdbc.sql(
                "SELECT attempts FROM outbox_events WHERE published_at IS NULL").query(Integer.class).single())
                .isGreaterThanOrEqualTo(2));

        assertThat((String) JsonPath.read(fetch("/actuator/health").getResponseBody(), "$.status")).isEqualTo("UP");
        assertThat((String) JsonPath.read(fetch("/actuator/health/liveness").getResponseBody(), "$.status")).isEqualTo("UP");
        assertThat((String) JsonPath.read(fetch("/actuator/health/readiness").getResponseBody(), "$.status")).isEqualTo("UP");
        assertThat(fetch("/actuator/health/rabbit").getStatus().value())
                .as("el indicador de Spring Boot está desactivado").isEqualTo(404);
        String dependencies = fetch("/actuator/health/dependencies").getResponseBody();
        assertThat((String) JsonPath.read(dependencies, "$.components.messaging.status")).isEqualTo("UP");
        assertThat((String) JsonPath.read(dependencies, "$.components.messaging.details.outbox.relay")).isEqualTo("FAILING");
        assertThat((Integer) JsonPath.read(dependencies, "$.components.messaging.details.outbox.pendingEvents")).isEqualTo(1);
        assertThat((String) JsonPath.read(dependencies, "$.components.messaging.details.outbox.lastFailure"))
                .startsWith("No se pudo enviar a RabbitMQ");
        assertThat((Double) JsonPath.read(fetch("/actuator/metrics/sales.outbox.pending").getResponseBody(),
                "$.measurements[0].value")).isEqualTo(1.0);
        assertThat((Double) JsonPath.read(fetch("/actuator/metrics/sales.outbox.oldest.pending.age").getResponseBody(),
                "$.measurements[0].value")).isGreaterThan(0.0);
    }
}
