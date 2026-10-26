package com.facturaflow.sales.infrastructure.adapter.in.rest;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.facturaflow.sales.EventContracts;
import com.facturaflow.sales.TestcontainersConfiguration;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.jayway.jsonpath.JsonPath;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * Punta a punta: HTTP → controlador → caso de uso → adaptador de catalog (contra WireMock, que responde
 * como catalog) → JPA → PostgreSQL (Testcontainers), y el evento: outbox → relay → RabbitMQ
 * (Testcontainers). Sin @Transactional: cada operación confirma.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "sales.outbox.poll-interval=200ms")
@AutoConfigureRestTestClient
@Import(TestcontainersConfiguration.class)
class SaleApiEndToEndTest {

    static final WireMockServer CATALOG = new WireMockServer(wireMockConfig().dynamicPort());

    static {
        CATALOG.start();
    }

    @DynamicPropertySource
    static void catalogUrl(DynamicPropertyRegistry registry) {
        registry.add("sales.catalog.base-url", CATALOG::baseUrl);
    }

    @AfterAll
    static void stop() {
        CATALOG.stop();
    }

    private static final String CUSTOMER = """
            { "id": "c0000000-0000-4000-8000-000000000001", "documentType": "NIT", "documentNumber": "890903938",
              "checkDigit": 8, "personType": "LEGAL_ENTITY", "companyName": "Cliente Ejemplo S.A.S.",
              "taxScheme": "IVA", "fiscalResponsibilities": [ "LARGE_TAXPAYER" ], "municipalityCode": "05001",
              "active": true }""";
    private static final String PRODUCTS = """
            { "items": [
              { "id": "9f000000-0000-4000-8000-0000000000c1", "code": "CAFE-500", "name": "Café molido 500 g",
                "unitPrice": 18500.00, "unit": "UNIT", "ivaExcluded": false,
                "taxes": [ { "type": "IVA", "rate": 5 } ], "active": true } ] }""";
    private static final String SALE = """
            { "referenceCode": "%s", "customer": { "documentType": "NIT", "documentNumber": "890903938" },
              "lines": [ { "productCode": "cafe-500", "quantity": 2, "discountRate": 10 } ],
              "paymentMethod": "CASH", "paymentTerms": { "type": "CASH" } }""";

    @Autowired
    private RestTestClient client;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private CircuitBreakerRegistry circuitBreakers;

    @Autowired
    private RabbitTemplate rabbit;

    @Autowired
    private AmqpAdmin rabbitAdmin;

    @Autowired
    private TopicExchange salesEvents;

    @BeforeEach
    void setUp() {
        jdbc.sql("TRUNCATE sale_line_taxes, sale_lines, sales, outbox_events").update();
        CATALOG.resetAll();
        circuitBreakers.circuitBreaker("catalog").reset();
    }

    private void catalogUp() {
        CATALOG.stubFor(get("/api/v1/customers/by-document/NIT/890903938").willReturn(okJson(CUSTOMER)));
        CATALOG.stubFor(post("/api/v1/products/lookup").willReturn(okJson(PRODUCTS)));
    }

    private EntityExchangeResult<String> createSale(String reference) {
        return client.post().uri("/api/v1/sales").contentType(MediaType.APPLICATION_JSON).body(SALE.formatted(reference))
                .exchange().expectBody(String.class).returnResult();
    }

    private EntityExchangeResult<String> fetch(String uri) {
        return client.get().uri(uri).exchange().expectBody(String.class).returnResult();
    }

    private long salesInDatabase() {
        return jdbc.sql("SELECT count(*) FROM sales").query(Long.class).single();
    }

    @Test
    @DisplayName("una venta se confirma con los datos de catalog, se guarda y se consulta; el reintento no llama a catalog")
    void fullFlow() {
        catalogUp();

        EntityExchangeResult<String> created = createSale("VENTA-E2E-1");

        assertThat(created.getStatus().value()).as(created.getResponseBody()).isEqualTo(201);
        String body = created.getResponseBody();
        String id = JsonPath.read(body, "$.id");
        assertThat((String) JsonPath.read(body, "$.lines[0].code")).isEqualTo("CAFE-500");
        assertThat((String) JsonPath.read(body, "$.customer.companyName")).isEqualTo("Cliente Ejemplo S.A.S.");
        // 2 x 18.500 = 37.000 - 10 % = 33.300 + IVA 5 % (1.665) = 34.965
        assertThat((Double) JsonPath.read(body, "$.totals.total")).isEqualTo(34965.00);
        assertThat(CATALOG.getAllServeEvents()).as("2 llamadas: cliente y lote de productos").hasSize(2);

        CATALOG.resetAll();   // catalog "caído": sin stubs, todo 404
        EntityExchangeResult<String> replay = createSale("VENTA-E2E-1");
        assertThat(replay.getStatus().value()).isEqualTo(200);
        assertThat((String) JsonPath.read(replay.getResponseBody(), "$.id")).isEqualTo(id);
        assertThat(CATALOG.getAllServeEvents()).isEmpty();

        assertThat(fetch("/api/v1/sales/" + id).getStatus().value()).isEqualTo(200);
        assertThat((String) JsonPath.read(fetch("/api/v1/sales/by-reference/VENTA-E2E-1").getResponseBody(), "$.id"))
                .isEqualTo(id);
        assertThat((Integer) JsonPath.read(fetch("/api/v1/sales").getResponseBody(), "$.totalItems")).isEqualTo(1);
    }

    @Test
    @DisplayName("un producto que catalog no tiene es 422 y no se guarda nada")
    void unknownProduct() {
        CATALOG.stubFor(get("/api/v1/customers/by-document/NIT/890903938").willReturn(okJson(CUSTOMER)));
        CATALOG.stubFor(post("/api/v1/products/lookup").willReturn(okJson("{ \"items\": [] }")));

        EntityExchangeResult<String> result = createSale("VENTA-E2E-2");

        assertThat(result.getStatus().value()).isEqualTo(422);
        assertThat((List<String>) JsonPath.read(result.getResponseBody(), "$.missingProducts")).containsExactly("cafe-500");
        assertThat(salesInDatabase()).isZero();
    }

    @Test
    @DisplayName("catalog caído: 503 y nada guardado; tras pocas ventas el circuito abre, el 503 es inmediato y el health sigue UP")
    void catalogDown() {
        CATALOG.stubFor(get("/api/v1/customers/by-document/NIT/890903938").willReturn(aResponse().withStatus(503)));

        EntityExchangeResult<String> first = createSale("VENTA-E2E-3");
        assertThat(first.getStatus().value()).isEqualTo(503);
        assertThat((String) JsonPath.read(first.getResponseBody(), "$.reason")).isEqualTo("failure");
        assertThat(first.getResponseHeaders().getFirst("Retry-After")).isEqualTo("5");

        createSale("VENTA-E2E-4");
        createSale("VENTA-E2E-5");   // 2 intentos por venta: el circuito abre en esta (5.ª llamada)
        CATALOG.resetRequests();
        long started = System.nanoTime();
        EntityExchangeResult<String> open = createSale("VENTA-E2E-6");

        assertThat(System.nanoTime() - started).isLessThan(500_000_000L);
        assertThat(open.getStatus().value()).isEqualTo(503);
        assertThat((String) JsonPath.read(open.getResponseBody(), "$.reason")).isEqualTo("circuit-open");
        assertThat(CATALOG.getAllServeEvents()).isEmpty();
        assertThat(salesInDatabase()).isZero();

        assertThat((String) JsonPath.read(fetch("/actuator/health").getResponseBody(), "$.status")).isEqualTo("UP");
        assertThat((String) JsonPath.read(fetch("/actuator/health/readiness").getResponseBody(), "$.status")).isEqualTo("UP");
        String dependencies = fetch("/actuator/health/dependencies").getResponseBody();
        assertThat((String) JsonPath.read(dependencies, "$.components.catalog.details.circuitBreaker")).isEqualTo("OPEN");
        assertThat((String) JsonPath.read(fetch("/actuator/circuitbreakers").getResponseBody(),
                "$.circuitBreakers.catalog.state")).isEqualTo("OPEN");
    }

    @Test
    @DisplayName("una venta creada por la API publica SaleConfirmed en RabbitMQ, que cumple el contrato, y el outbox queda vacío")
    void saleConfirmedReachesRabbitMq() {
        String queue = "test.e2e-sales-events";
        rabbitAdmin.declareQueue(new Queue(queue, true, false, false));
        rabbitAdmin.declareBinding(BindingBuilder.bind(new Queue(queue)).to(salesEvents).with("sale.confirmed.*"));
        rabbitAdmin.purgeQueue(queue, false);
        catalogUp();

        String saleId = JsonPath.read(createSale("VENTA-E2E-EVT").getResponseBody(), "$.id");

        // El relay corre en segundo plano (cada 200 ms): se espera el mensaje de ESTA venta.
        String body = await().atMost(Duration.ofSeconds(10)).until(() -> {
            Message message = rabbit.receive(queue, 500);
            return message == null ? null : new String(message.getBody(), StandardCharsets.UTF_8);
        }, received -> received != null && saleId.equals(JsonPath.read(received, "$.payload.saleId")));
        assertThat(EventContracts.saleConfirmedV1Violations(body)).isEmpty();
        assertThat((String) JsonPath.read(body, "$.payload.referenceCode")).isEqualTo("VENTA-E2E-EVT");
        assertThat((String) JsonPath.read(body, "$.payload.customer.companyName")).isEqualTo("Cliente Ejemplo S.A.S.");
        assertThat((String) JsonPath.read(body, "$.payload.lines[0].name")).isEqualTo("Café molido 500 g");
        assertThat((Double) JsonPath.read(body, "$.payload.totals.total")).as("informativo").isEqualTo(34965.00);

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(jdbc.sql(
                "SELECT count(*) FROM outbox_events WHERE published_at IS NULL").query(Long.class).single()).isZero());
        String dependencies = fetch("/actuator/health/dependencies").getResponseBody();
        assertThat((String) JsonPath.read(dependencies, "$.components.messaging.status")).isEqualTo("UP");
        assertThat((String) JsonPath.read(dependencies, "$.components.messaging.details.relay")).isEqualTo("OK");
        assertThat((Integer) JsonPath.read(dependencies, "$.components.messaging.details.pendingEvents")).isZero();
        assertThat((Double) JsonPath.read(fetch("/actuator/metrics/sales.outbox.pending").getResponseBody(),
                "$.measurements[0].value")).isZero();
    }

    @Test
    @DisplayName("Swagger publica los ejemplos de contado y crédito con datos sembrados en catalog")
    void swaggerExamples() {
        String docs = fetch("/v3/api-docs").getResponseBody();

        assertThat(docs).contains("890903938", "1020304050", "CAFE-500", "CERVEZA-330", "\"CREDIT\"", "seed-catalog.sh");
    }
}
