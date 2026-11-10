package com.facturaflow.sales.infrastructure.adapter.in.rest;

import static com.facturaflow.sales.TracingTestConfiguration.awaitSpan;
import static com.facturaflow.sales.TracingTestConfiguration.newTraceParent;
import static com.facturaflow.sales.TracingTestConfiguration.spanId;
import static com.facturaflow.sales.TracingTestConfiguration.traceId;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.facturaflow.sales.TestcontainersConfiguration;
import com.facturaflow.sales.TracingTestConfiguration;
import com.facturaflow.sales.application.port.in.PublishPendingEventsUseCase;
import com.github.tomakehurst.wiremock.WireMockServer;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
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
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * Trazas de punta a punta en sales (ADR 0019), con el SDK real de OpenTelemetry y un exportador en memoria (sin el
 * colector): una venta creada con un {@code traceparent} continúa esa traza en las llamadas a catalog (WireMock),
 * en la fila del outbox (misma transacción) y, al publicar, en las cabeceras del mensaje de {@code SaleConfirmed}.
 * El relay está apagado: se invoca a mano, desde OTRO contexto (sin la traza de la petición), como el programado.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"sales.outbox.relay.enabled=false", "sales.catalog.read-timeout=10s",
                "management.opentelemetry.tracing.export.schedule-delay=50ms"})
@AutoConfigureRestTestClient
@AutoConfigureTracing
@Import({TestcontainersConfiguration.class, TracingTestConfiguration.class})
class SaleTracingEndToEndTest {

    private static final String TEST_QUEUE = "sales.test-tracing-events";
    /** traceparent W3C versión 00 (los flags de una traza nueva pueden ser 01 o 03: "random trace id", nivel 2). */
    private static final String W3C = "00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}";

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
    private PublishPendingEventsUseCase relay;

    @Autowired
    private RabbitTemplate rabbit;

    @Autowired
    private AmqpAdmin admin;

    @Autowired
    private TopicExchange salesEvents;

    @Autowired
    private InMemorySpanExporter spans;

    @BeforeEach
    void setUp() {
        jdbc.sql("TRUNCATE sale_line_taxes, sale_lines, sales, outbox_events").update();
        CATALOG.resetAll();
        CATALOG.stubFor(get("/api/v1/customers/by-document/NIT/890903938").willReturn(okJson(CUSTOMER)));
        CATALOG.stubFor(post("/api/v1/products/lookup").willReturn(okJson(PRODUCTS)));
        admin.declareExchange(salesEvents);
        Queue queue = new Queue(TEST_QUEUE, true, false, false);
        admin.declareQueue(queue);
        admin.declareBinding(BindingBuilder.bind(queue).to(salesEvents).with("sale.#"));
        admin.purgeQueue(TEST_QUEUE, false);
        spans.reset();
    }

    @Test
    @DisplayName("la venta continúa la traza del cliente en catalog, en el outbox y en las cabeceras de SaleConfirmed")
    void saleContinuesTheCallersTrace() {
        String incoming = newTraceParent();
        String traceId = traceId(incoming);

        client.post().uri("/api/v1/sales").contentType(MediaType.APPLICATION_JSON).header("traceparent", incoming)
                .body(SALE.formatted("VENTA-TRAZA-1")).exchange().expectStatus().isCreated();

        // HTTP sales → catalog: las dos llamadas llevan la MISMA traza (con su propio span cliente).
        String sameTrace = "00-" + traceId + "-[0-9a-f]{16}-[0-9a-f]{2}";
        CATALOG.verify(getRequestedFor(urlEqualTo("/api/v1/customers/by-document/NIT/890903938"))
                .withHeader("traceparent", matching(sameTrace)));
        CATALOG.verify(postRequestedFor(urlEqualTo("/api/v1/products/lookup"))
                .withHeader("traceparent", matching(sameTrace)));

        // El outbox guarda el contexto de la traza en la misma transacción que la venta.
        String stored = jdbc.sql("SELECT trace_parent FROM outbox_events").query(String.class).single();
        assertThat(traceId(stored)).as("la traza de la petición").isEqualTo(traceId);
        assertThat(spanId(stored)).as("un span de sales, no el del cliente").isNotEqualTo(spanId(incoming));

        // El relay (otro hilo, sin traza) reanuda el contexto guardado: span "outbox", hijo del que guardó el evento…
        relay.publishPending();
        SpanData outbox = awaitSpan(spans, span -> span.getName().equals("outbox SaleConfirmed"));
        assertThat(outbox.getTraceId()).isEqualTo(traceId);
        assertThat(outbox.getParentSpanId()).isEqualTo(spanId(stored));

        // …y el envío a RabbitMQ (span productor, hijo del anterior) escribe traceparent en las cabeceras.
        SpanData send = awaitSpan(spans, span -> span.getKind() == SpanKind.PRODUCER
                && span.getParentSpanId().equals(outbox.getSpanId()));
        Message message = rabbit.receive(TEST_QUEUE, 5_000);
        assertThat(message).isNotNull();
        String header = message.getMessageProperties().getHeader("traceparent");
        assertThat(traceId(header)).isEqualTo(traceId);
        assertThat(spanId(header)).as("el span de envío").isEqualTo(send.getSpanId());
    }

    @Test
    @DisplayName("una petición sin traceparent empieza una traza nueva, y también llega al outbox y al mensaje")
    void saleWithoutIncomingTraceStartsANewOne() {
        client.post().uri("/api/v1/sales").contentType(MediaType.APPLICATION_JSON)
                .body(SALE.formatted("VENTA-TRAZA-2")).exchange().expectStatus().isCreated();

        String stored = jdbc.sql("SELECT trace_parent FROM outbox_events").query(String.class).single();
        assertThat(stored).matches(W3C);

        relay.publishPending();
        Message message = rabbit.receive(TEST_QUEUE, 5_000);
        assertThat(message).isNotNull();
        assertThat(traceId(message.getMessageProperties().getHeader("traceparent"))).isEqualTo(traceId(stored));
    }

    @Test
    @DisplayName("un evento sin contexto guardado (anterior a V5) se publica igual, con una traza nueva")
    void eventWithoutStoredContextIsPublishedWithANewTrace() {
        client.post().uri("/api/v1/sales").contentType(MediaType.APPLICATION_JSON)
                .body(SALE.formatted("VENTA-TRAZA-3")).exchange().expectStatus().isCreated();
        String saleTrace = traceId(jdbc.sql("SELECT trace_parent FROM outbox_events").query(String.class).single());
        jdbc.sql("UPDATE outbox_events SET trace_parent = NULL, trace_state = NULL").update();

        relay.publishPending();

        Message message = rabbit.receive(TEST_QUEUE, 5_000);
        assertThat(message).isNotNull();
        String header = message.getMessageProperties().getHeader("traceparent");
        assertThat(header).matches(W3C);
        assertThat(traceId(header)).as("una traza nueva, no la de la venta").isNotEqualTo(saleTrace);
        // Esperar el span de envío asegura que el lote ya se exportó; los spans de otros tests (exportados tarde) no
        // cuentan: se mira solo la traza de esta venta.
        awaitSpan(spans, span -> span.getKind() == SpanKind.PRODUCER && span.getTraceId().equals(traceId(header)));
        assertThat(spans.getFinishedSpanItems()).noneMatch(span -> span.getName().equals("outbox SaleConfirmed")
                && span.getTraceId().equals(saleTrace));
    }
}
