package com.facturaflow.sales.infrastructure.adapter.in.messaging;

import static com.facturaflow.sales.TracingTestConfiguration.WAIT;
import static com.facturaflow.sales.TracingTestConfiguration.awaitSpan;
import static com.facturaflow.sales.TracingTestConfiguration.newTraceParent;
import static com.facturaflow.sales.TracingTestConfiguration.spanId;
import static com.facturaflow.sales.TracingTestConfiguration.traceId;
import static com.facturaflow.sales.domain.model.SaleFixtures.CREATED_AT;
import static com.facturaflow.sales.domain.model.SaleFixtures.SALE_DATE;
import static com.facturaflow.sales.domain.model.SaleFixtures.SOLD_BY;
import static com.facturaflow.sales.domain.model.SaleFixtures.customer;
import static com.facturaflow.sales.domain.model.SaleFixtures.line;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.facturaflow.sales.TestcontainersConfiguration;
import com.facturaflow.sales.TracingTestConfiguration;
import com.facturaflow.sales.application.port.out.RequestHash;
import com.facturaflow.sales.application.port.out.SaleRepository;
import com.facturaflow.sales.domain.model.PaymentMethod;
import com.facturaflow.sales.domain.model.PaymentTerms;
import com.facturaflow.sales.domain.model.ReferenceCode;
import com.facturaflow.sales.domain.model.Sale;
import com.facturaflow.sales.domain.model.SaleId;
import com.jayway.jsonpath.JsonPath;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.rabbitmq.RabbitMQContainer;

/**
 * El consumidor de resultados continúa la traza del productor (ADR 0019, D2: span HIJO, no un link): el mensaje llega
 * con el {@code traceparent} que puso invoicing y el span de consumo cuelga de él. Las republicaciones (DLQ) conservan
 * la traza. SDK real de OpenTelemetry con un exportador en memoria, sin el colector.
 */
@SpringBootTest(properties = {"sales.invoice-results.retry-delays=200ms,400ms,600ms",
        "management.opentelemetry.tracing.export.schedule-delay=50ms"})
@AutoConfigureTracing
@Import({TestcontainersConfiguration.class, TracingTestConfiguration.class})
class InvoiceResultTracingTest {

    private static final String DLQ = "sales.invoice-results.dlq";

    @Autowired
    private SaleRepository sales;

    @Autowired
    private RabbitTemplate rabbit;

    @Autowired
    private RabbitMQContainer rabbitContainer;

    @Autowired
    private AmqpAdmin admin;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private InMemorySpanExporter spans;

    private RabbitTemplate invoicing;

    @BeforeEach
    void setUp() {
        jdbc.sql("TRUNCATE sale_line_taxes, sale_lines, sales, outbox_events").update();
        admin.purgeQueue(DLQ, false);
        if (invoicing == null) {
            invoicing = TestcontainersConfiguration.invoicingTemplate(rabbitContainer);
        }
        spans.reset();
    }

    private void confirmedSale(String reference) {
        sales.saveNew(Sale.confirm(SaleId.generate(), new ReferenceCode(reference), SALE_DATE, CREATED_AT,
                customer(), List.of(line("1", "1000", "0", "19")), PaymentMethod.CASH, PaymentTerms.cash(), null,
                SOLD_BY), new RequestHash("a".repeat(64)));
    }

    /** Publica como invoicing, con el traceparent que habría escrito su RabbitTemplate instrumentado. */
    private void publish(String event, String traceParent) {
        invoicing.send(TestcontainersConfiguration.INVOICING_EVENTS, "invoice.issued.v1",
                MessageBuilder.withBody(event.getBytes(StandardCharsets.UTF_8))
                        .setMessageId(JsonPath.read(event, "$.eventId")).setType("InvoiceIssued")
                        .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                        .setHeader("traceparent", traceParent).build());
    }

    @Test
    @DisplayName("el consumo de InvoiceIssued es un span hijo del span productor de invoicing, en la misma traza")
    void consumerSpanIsAChildOfTheProducerSpan() {
        confirmedSale("VENTA-TR-1");
        String producer = newTraceParent();

        publish(InvoiceResultEvents.issued("VENTA-TR-1", UUID.randomUUID(), "SETP1"), producer);

        SpanData consumer = awaitSpan(spans, span -> span.getKind() == SpanKind.CONSUMER
                && span.getTraceId().equals(traceId(producer)));
        assertThat(consumer.getParentSpanId()).as("hijo del span productor (no un link)").isEqualTo(spanId(producer));
        assertThat(consumer.getLinks()).isEmpty();
        await().atMost(WAIT).untilAsserted(() -> assertThat(jdbc.sql(
                "SELECT status FROM sales WHERE reference_code = 'VENTA-TR-1'").query(String.class).single())
                .isEqualTo("INVOICED"));
    }

    @Test
    @DisplayName("un mensaje inválido llega a la DLQ con la misma traza (traceparent del span de consumo)")
    void deadLetterKeepsTheTrace() {
        confirmedSale("VENTA-TR-2");
        String producer = newTraceParent();

        publish(InvoiceResultEvents.issued("VENTA-TR-2", UUID.randomUUID(), "SETP2", "LIBRETA_MILITAR", "123"),
                producer);

        Message dead = await().atMost(WAIT).until(() -> rabbit.receive(DLQ, 200), message -> message != null);
        String header = dead.getMessageProperties().getHeader("traceparent");
        assertThat(traceId(header)).isEqualTo(traceId(producer));
        assertThat(spanId(header)).as("el span que republicó, no el del productor").isNotEqualTo(spanId(producer));
    }
}
