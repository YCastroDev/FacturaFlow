package com.facturaflow.invoicing.infrastructure.adapter.out.provider.factus;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.facturaflow.invoicing.TestcontainersConfiguration;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.jayway.jsonpath.JsonPath;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * La aplicación completa con {@code invoicing.provider=factus} (contra un WireMock que no tiene ningún
 * stub): con el circuito de Factus abierto, el health sigue UP, el componente informativo muestra el
 * estado, Actuator expone el circuito y sus métricas, y la API responde 503 al instante con el
 * Retry-After que falta (ADR 0010).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "invoicing.provider=factus",
        "invoicing.factus.client-id=id-de-prueba",
        "invoicing.factus.client-secret=secreto-actuator-que-no-debe-aparecer",
        "invoicing.factus.username=usuario@empresa.example",
        "invoicing.factus.password=clave-actuator-que-no-debe-aparecer",
        "invoicing.factus.credit-note-numbering-range-id=390"})
@AutoConfigureRestTestClient
@Import(TestcontainersConfiguration.class)
class FactusActuatorTest {

    static final WireMockServer FACTUS = new WireMockServer(wireMockConfig().dynamicPort());

    static {
        FACTUS.start();
    }

    @DynamicPropertySource
    static void factusUrl(DynamicPropertyRegistry registry) {
        registry.add("invoicing.factus.base-url", FACTUS::baseUrl);
    }

    @AfterAll
    static void stop() {
        FACTUS.stop();
    }

    @Autowired
    private RestTestClient client;

    @Autowired
    private FactusResilience resilience;

    @AfterEach
    void close() {
        resilience.circuitBreaker().transitionToClosedState();
    }

    private EntityExchangeResult<String> get(String uri) {
        return client.get().uri(uri).exchange().expectBody(String.class).returnResult();
    }

    @Test
    @DisplayName("con el circuito abierto /actuator/health sigue UP (y liveness y readiness también)")
    void healthStaysUpWithOpenCircuit() {
        resilience.circuitBreaker().transitionToOpenState();

        EntityExchangeResult<String> health = get("/actuator/health");

        assertThat(health.getStatus().value()).isEqualTo(200);
        assertThat((String) JsonPath.read(health.getResponseBody(), "$.status")).isEqualTo("UP");
        assertThat(health.getResponseBody()).doesNotContain("factus", "OPEN");   // sin detalles en el principal
        assertThat((String) JsonPath.read(get("/actuator/health/liveness").getResponseBody(), "$.status"))
                .isEqualTo("UP");
        assertThat((String) JsonPath.read(get("/actuator/health/readiness").getResponseBody(), "$.status"))
                .isEqualTo("UP");
    }

    @Test
    @DisplayName("/actuator/health/providers muestra el circuito OPEN como detalle informativo, sin nada sensible")
    void factusGroupShowsCircuitState() {
        resilience.circuitBreaker().transitionToOpenState();

        String body = get("/actuator/health/providers").getResponseBody();

        assertThat((String) JsonPath.read(body, "$.status")).isEqualTo("UP");
        assertThat((String) JsonPath.read(body, "$.components.factus.details.circuitBreaker")).isEqualTo("OPEN");
        assertThat((Integer) JsonPath.read(body, "$.components.factus.details.retryAfterSeconds")).isPositive();
        assertThat(body).doesNotContain("secreto-actuator", "clave-actuator", "usuario@empresa", "Bearer");
    }

    @Test
    @DisplayName("con el circuito abierto, emitir responde 503 inmediato con Retry-After y sin llamar a Factus")
    void issueIsRejectedImmediately() {
        String invoice = client.post().uri("/api/v1/invoices").contentType(MediaType.APPLICATION_JSON)
                .body("""
                        { "referenceCode": "VENTA-CIRCUITO-1",
                          "customer": { "documentType": "NIT", "documentNumber": "890903938", "checkDigit": 8,
                                        "personType": "LEGAL_ENTITY", "companyName": "Cliente Ejemplo S.A.S.",
                                        "taxScheme": "IVA", "fiscalResponsibilities": [ "LARGE_TAXPAYER" ] },
                          "lines": [ { "code": "SKU-1", "name": "Producto", "quantity": 1, "unitPrice": 1000,
                                       "unit": "UNIT", "taxes": [ { "type": "IVA", "rate": 19 } ] } ],
                          "paymentMethod": "CASH", "paymentTerms": { "type": "CASH" } }""")
                .exchange().expectBody(String.class).returnResult().getResponseBody();
        String id = JsonPath.read(invoice, "$.id");
        resilience.circuitBreaker().transitionToOpenState();

        EntityExchangeResult<String> issue = client.post().uri("/api/v1/invoices/{id}/issue", id).exchange()
                .expectBody(String.class).returnResult();

        // Inmediato = sin llamar a Factus (se comprueba abajo; medir el tiempo solo daría falsos fallos).
        assertThat(issue.getStatus().value()).isEqualTo(503);
        assertThat(issue.getResponseHeaders().getFirst("Retry-After")).matches("[1-9][0-9]*");
        assertThat((String) JsonPath.read(issue.getResponseBody(), "$.reason")).isEqualTo("circuit-open");
        assertThat((String) JsonPath.read(issue.getResponseBody(), "$.detail")).contains("La factura sigue PENDING");
        assertThat(FACTUS.getAllServeEvents()).isEmpty();
    }

    @Test
    @DisplayName("Actuator expone el circuito y las métricas de resiliencia (estado, llamadas, rechazos)")
    void exposesCircuitAndMetrics() {
        resilience.circuitBreaker().transitionToOpenState();

        String circuits = get("/actuator/circuitbreakers").getResponseBody();
        assertThat((String) JsonPath.read(circuits, "$.circuitBreakers.factus.state")).isEqualTo("OPEN");
        for (String metric : new String[] {"resilience4j.circuitbreaker.state", "resilience4j.circuitbreaker.calls",
                "resilience4j.circuitbreaker.not.permitted.calls", "resilience4j.retry.calls",
                "resilience4j.ratelimiter.available.permissions", FactusResilience.REJECTED_METRIC}) {
            assertThat(get("/actuator/metrics/" + metric).getStatus().value()).as(metric).isEqualTo(200);
        }
        assertThat(get("/actuator/retries").getResponseBody()).contains("factus");
        assertThat(get("/actuator/ratelimiters").getResponseBody()).contains("factus");
        assertThat(resilience.circuitBreaker().getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }
}
