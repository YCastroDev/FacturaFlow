package com.facturaflow.invoicing.infrastructure.adapter.in.messaging;

import static com.facturaflow.invoicing.TracingTestConfiguration.WAIT;
import static com.facturaflow.invoicing.TracingTestConfiguration.awaitSpan;
import static com.facturaflow.invoicing.TracingTestConfiguration.newTraceParent;
import static com.facturaflow.invoicing.TracingTestConfiguration.spanId;
import static com.facturaflow.invoicing.TracingTestConfiguration.traceId;
import static com.facturaflow.invoicing.infrastructure.adapter.in.messaging.SaleConfirmedEvents.modified;
import static com.facturaflow.invoicing.infrastructure.adapter.in.messaging.SaleConfirmedEvents.payload;
import static com.facturaflow.invoicing.infrastructure.adapter.in.messaging.SaleConfirmedEvents.sale;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.facturaflow.invoicing.TestcontainersConfiguration;
import com.facturaflow.invoicing.TracingTestConfiguration;
import com.facturaflow.invoicing.application.port.in.PublishPendingEventsUseCase;
import com.jayway.jsonpath.JsonPath;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.rabbitmq.RabbitMQContainer;

/**
 * Trazas a través de invoicing (ADR 0019), con el SDK real de OpenTelemetry y un exportador en memoria (sin el
 * colector). Un {@code SaleConfirmed} llega con el {@code traceparent} de sales; el consumo es un span HIJO del
 * productor (D2), la emisión (proveedor falso, D7) cuelga de él, el outbox guarda el contexto con el resultado y el
 * relay lo reanuda: el {@code InvoiceIssued} sale en la MISMA traza. Los reintentos (colas de espera) y la DLQ también
 * la conservan. El relay está apagado: se invoca a mano para mirar la fila antes de publicar.
 */
@SpringBootTest(properties = {"invoicing.messaging.retry-delays=200ms,400ms,600ms",
        "invoicing.fake.temporary-failures=1", "invoicing.outbox.relay.enabled=false",
        "management.opentelemetry.tracing.export.schedule-delay=50ms"})
@AutoConfigureTracing
@Import({TestcontainersConfiguration.class, TracingTestConfiguration.class})
class SaleConfirmedTracingTest {

    private static final String QUEUE = "invoicing.sale-confirmed";
    private static final String DLQ = "invoicing.sale-confirmed.dlq";
    /** Con el prefijo invoicing.: el usuario de invoicing solo puede declarar colas suyas. */
    private static final String RESULTS = "invoicing.test-tracing-results";

    @Autowired
    private RabbitTemplate rabbit;

    @Autowired
    private RabbitMQContainer rabbitContainer;

    @Autowired
    private AmqpAdmin admin;

    @Autowired
    private TopicExchange invoicingEventsExchange;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private PublishPendingEventsUseCase relay;

    @Autowired
    private InMemorySpanExporter spans;

    private RabbitTemplate sales;

    @BeforeEach
    void setUp() {
        jdbc.sql("TRUNCATE TABLE invoices CASCADE").update();
        jdbc.sql("TRUNCATE TABLE outbox_events").update();
        Queue results = new Queue(RESULTS, true, false, false);
        admin.declareQueue(results);
        admin.declareBinding(BindingBuilder.bind(results).to(invoicingEventsExchange).with("#"));
        for (String queue : new String[] {QUEUE, DLQ, RESULTS, QUEUE + ".wait-200ms", QUEUE + ".wait-400ms",
                QUEUE + ".wait-600ms"}) {
            admin.purgeQueue(queue, false);
        }
        if (sales == null) {
            sales = TestcontainersConfiguration.salesTemplate(rabbitContainer);
        }
        spans.reset();
    }

    /** Publica como sales, con el traceparent que habría escrito su relay. */
    private void publish(String event, String traceParent) {
        sales.send(TestcontainersConfiguration.SALES_EVENTS, "sale.confirmed.v1",
                MessageBuilder.withBody(event.getBytes(StandardCharsets.UTF_8))
                        .setMessageId(JsonPath.read(event, "$.eventId")).setType("SaleConfirmed")
                        .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                        .setHeader("traceparent", traceParent).build());
    }

    private void awaitStatus(String referenceCode, String expected) {
        await().atMost(WAIT).untilAsserted(() -> assertThat(jdbc.sql(
                "SELECT status FROM invoices WHERE reference_code = :r").param("r", referenceCode)
                .query(String.class).optional()).contains(expected));
    }

    private String storedTraceParent(String eventType) {
        return jdbc.sql("SELECT trace_parent FROM outbox_events WHERE event_type = :t").param("t", eventType)
                .query(String.class).single();
    }

    private List<SpanData> consumerSpans(String traceId) {
        return spans.getFinishedSpanItems().stream()
                .filter(span -> span.getKind() == SpanKind.CONSUMER && span.getTraceId().equals(traceId)).toList();
    }

    @Test
    @DisplayName("SaleConfirmed → consumo (hijo del productor) → emisión → outbox → InvoiceIssued, en UNA traza")
    void invoiceIssuedContinuesTheSaleTrace() {
        String producer = newTraceParent();
        String traceId = traceId(producer);

        publish(sale("VENTA-TRAZA-1"), producer);
        awaitStatus("VENTA-TRAZA-1", "ISSUED");

        SpanData consumer = awaitSpan(spans, span -> span.getKind() == SpanKind.CONSUMER
                && span.getTraceId().equals(traceId));
        assertThat(consumer.getParentSpanId()).as("hijo del span productor (no un link)").isEqualTo(spanId(producer));
        assertThat(consumer.getLinks()).isEmpty();
        SpanData issuance = awaitSpan(spans, span -> span.getName().equals("fake-provider issue invoice"));
        assertThat(issuance.getTraceId()).as("la emisión, dentro de la traza de la venta").isEqualTo(traceId);

        // El resultado se guarda con el contexto de la traza en curso (el consumo)…
        String stored = storedTraceParent("InvoiceIssued");
        assertThat(traceId(stored)).isEqualTo(traceId);

        // …y el relay lo reanuda: InvoiceIssued sale en la MISMA traza, con un span "outbox" hijo del que lo guardó.
        relay.publishPending();
        SpanData outbox = awaitSpan(spans, span -> span.getName().equals("outbox InvoiceIssued"));
        assertThat(outbox.getTraceId()).isEqualTo(traceId);
        assertThat(outbox.getParentSpanId()).isEqualTo(spanId(stored));
        Message result = rabbit.receive(RESULTS, 5_000);
        assertThat(result).isNotNull();
        assertThat(result.getMessageProperties().getType()).isEqualTo("InvoiceIssued");
        assertThat(traceId(result.getMessageProperties().getHeader("traceparent"))).isEqualTo(traceId);
    }

    @Test
    @DisplayName("una falla temporal pasa por la cola de espera y el reintento sigue en la misma traza")
    void retryThroughTheWaitQueueStaysInTheTrace() {
        String producer = newTraceParent();
        String traceId = traceId(producer);

        publish(sale("VENTA-TRAZA-2", "SIMULAR_FALLA_TEMPORAL"), producer);
        awaitStatus("VENTA-TRAZA-2", "ISSUED");

        // Dos consumos (el fallido y el reintento), los dos en la traza de la venta.
        await().atMost(WAIT).until(() -> consumerSpans(traceId).size() >= 2);
        SpanData first = consumerSpans(traceId).stream()
                .filter(span -> span.getParentSpanId().equals(spanId(producer))).findFirst().orElseThrow();
        // El reintento cuelga del envío a la cola de espera, que a su vez cuelga del primer consumo.
        SpanData republish = awaitSpan(spans, span -> span.getKind() == SpanKind.PRODUCER
                && span.getParentSpanId().equals(first.getSpanId()));
        assertThat(consumerSpans(traceId)).anyMatch(span -> span.getParentSpanId().equals(republish.getSpanId()));
        assertThat(traceId(storedTraceParent("InvoiceIssued"))).isEqualTo(traceId);
    }

    @Test
    @DisplayName("un mensaje que agota los reintentos llega a la DLQ con la traza de la venta")
    void deadLetterKeepsTheTrace() {
        String producer = newTraceParent();

        publish(sale("VENTA-TRAZA-3", "SIMULAR_CAIDA"), producer);

        Message dead = await().atMost(WAIT).until(() -> rabbit.receive(DLQ, 200), message -> message != null);
        String header = dead.getMessageProperties().getHeader("traceparent");
        assertThat(traceId(header)).isEqualTo(traceId(producer));
        assertThat((Integer) dead.getMessageProperties().getHeader("x-attempts-made")).isEqualTo(4);
    }

    @Test
    @DisplayName("un SaleConfirmed inválido registra InvoiceRequestRejected en la misma traza")
    void requestRejectedContinuesTheTrace() {
        String producer = newTraceParent();

        publish(modified(sale("VENTA-TRAZA-4"), json -> payload(json).put("paymentMethod", "BITCOIN")), producer);

        await().atMost(WAIT).until(() -> jdbc.sql("SELECT count(*) FROM outbox_events").query(Long.class).single() > 0);
        assertThat(traceId(storedTraceParent("InvoiceRequestRejected"))).isEqualTo(traceId(producer));
    }
}
