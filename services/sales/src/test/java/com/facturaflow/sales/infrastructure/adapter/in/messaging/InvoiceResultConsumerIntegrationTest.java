package com.facturaflow.sales.infrastructure.adapter.in.messaging;

import static com.facturaflow.sales.domain.model.SaleFixtures.CREATED_AT;
import static com.facturaflow.sales.domain.model.SaleFixtures.SALE_DATE;
import static com.facturaflow.sales.domain.model.SaleFixtures.customer;
import static com.facturaflow.sales.domain.model.SaleFixtures.line;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.facturaflow.sales.EventContracts;
import com.facturaflow.sales.TestcontainersConfiguration;
import com.facturaflow.sales.application.port.out.RequestHash;
import com.facturaflow.sales.application.port.out.SaleRepository;
import com.facturaflow.sales.domain.model.PaymentMethod;
import com.facturaflow.sales.domain.model.PaymentTerms;
import com.facturaflow.sales.domain.model.ReferenceCode;
import com.facturaflow.sales.domain.model.Sale;
import com.facturaflow.sales.domain.model.SaleId;
import com.jayway.jsonpath.JsonPath;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
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
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.rabbitmq.RabbitMQContainer;

/**
 * El consumidor de resultados de punta a punta (ADR 0015): RabbitMQ y PostgreSQL reales, con los usuarios reales
 * (sales consume con el suyo; los eventos se publican con el de invoicing en invoicing.events). Esperas cortas.
 * Cada evento válido se comprueba antes contra su esquema de docs/events/.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "sales.invoice-results.retry-delays=200ms,400ms,600ms")
@AutoConfigureRestTestClient
@Import(TestcontainersConfiguration.class)
class InvoiceResultConsumerIntegrationTest {

    private static final String DLQ = "sales.invoice-results.dlq";
    private static final Duration WAIT = Duration.ofSeconds(15);

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
    private MeterRegistry meters;

    @Autowired
    private RestTestClient client;

    private RabbitTemplate invoicing;

    @BeforeEach
    void setUp() {
        jdbc.sql("TRUNCATE sale_line_taxes, sale_lines, sales, outbox_events").update();
        admin.purgeQueue(DLQ, false);
        if (invoicing == null) {
            invoicing = TestcontainersConfiguration.invoicingTemplate(rabbitContainer);
        }
    }

    private Sale confirmedSale(String reference) {
        return sales.saveNew(Sale.confirm(SaleId.generate(), new ReferenceCode(reference), SALE_DATE, CREATED_AT,
                customer(), List.of(line("1", "1000", "0", "19")), PaymentMethod.CASH, PaymentTerms.cash(), null),
                new RequestHash("a".repeat(64)));
    }

    /** Publica como invoicing, con la routing key de su tipo. */
    private void publish(String event) {
        String type = JsonPath.read(event, "$.eventType");
        String routingKey = switch (type) {
            case "InvoiceIssued" -> "invoice.issued.v1";
            case "InvoiceRejected" -> "invoice.rejected.v1";
            default -> "invoice-request.rejected.v1";
        };
        invoicing.send(TestcontainersConfiguration.INVOICING_EVENTS, routingKey,
                MessageBuilder.withBody(event.getBytes(StandardCharsets.UTF_8))
                        .setMessageId(JsonPath.read(event, "$.eventId")).setType(type)
                        .setContentType(MessageProperties.CONTENT_TYPE_JSON).build());
    }

    private void publishValid(String event) {
        assertThat(EventContracts.invoiceResultViolations(event)).as("cumple su contrato").isEmpty();
        publish(event);
    }

    private String status(String reference) {
        return jdbc.sql("SELECT status FROM sales WHERE reference_code = :r").param("r", reference)
                .query(String.class).single();
    }

    private void awaitStatus(String reference, String expected) {
        await().atMost(WAIT).untilAsserted(() -> assertThat(status(reference)).isEqualTo(expected));
    }

    private double processed(String outcome) {
        return meters.get("sales.invoice_results.processed").tag("outcome", outcome).counter().count();
    }

    private void awaitProcessed(String outcome, double before) {
        await().atMost(WAIT).until(() -> processed(outcome) > before);
    }

    private String fetch(Sale sale) {
        return client.get().uri("/api/v1/sales/{id}", sale.id().value()).exchange().expectBody(String.class)
                .returnResult().getResponseBody();
    }

    @Test
    @DisplayName("InvoiceIssued deja la venta INVOICED y la API muestra el número y el CUFE")
    void issuedInvoicesTheSale() {
        Sale sale = confirmedSale("VENTA-R-1");
        UUID invoiceId = UUID.randomUUID();

        publishValid(InvoiceResultEvents.issued("VENTA-R-1", invoiceId, "SETP990000001"));

        awaitStatus("VENTA-R-1", "INVOICED");
        String body = fetch(sale);
        assertThat((String) JsonPath.read(body, "$.status")).isEqualTo("INVOICED");
        assertThat((String) JsonPath.read(body, "$.invoice.number")).isEqualTo("SETP990000001");
        assertThat((String) JsonPath.read(body, "$.invoice.cufe")).isEqualTo(InvoiceResultEvents.CUFE);
        assertThat((String) JsonPath.read(body, "$.invoice.invoiceId")).isEqualTo(invoiceId.toString());
        assertThat((Object) JsonPath.read(body, "$.invoiceFailure")).isNull();
    }

    @Test
    @DisplayName("InvoiceRejected deja la venta INVOICE_FAILED y la API muestra el motivo")
    void rejectedFailsTheSale() {
        Sale sale = confirmedSale("VENTA-R-2");

        publishValid(InvoiceResultEvents.rejected("VENTA-R-2", UUID.randomUUID(), "La DIAN no validó el documento"));

        awaitStatus("VENTA-R-2", "INVOICE_FAILED");
        String body = fetch(sale);
        assertThat((String) JsonPath.read(body, "$.invoiceFailure.reason")).isEqualTo("La DIAN no validó el documento");
        assertThat((Object) JsonPath.read(body, "$.invoice")).isNull();
    }

    @Test
    @DisplayName("InvoiceRequestRejected (p. ej. el vencimiento) deja la venta INVOICE_FAILED con el motivo")
    void requestRejectedFailsTheSale() {
        Sale sale = confirmedSale("VENTA-R-3");

        publishValid(InvoiceResultEvents.requestRejected("VENTA-R-3", sale.id().value(),
                "La fecha de vencimiento debe ser posterior a la fecha de emisión"));

        awaitStatus("VENTA-R-3", "INVOICE_FAILED");
        assertThat((String) JsonPath.read(fetch(sale), "$.invoiceFailure.reason")).contains("vencimiento");
    }

    @Test
    @DisplayName("un evento repetido no cambia nada ni falla")
    void duplicateIsANoOp() {
        confirmedSale("VENTA-R-4");
        String event = InvoiceResultEvents.issued("VENTA-R-4", UUID.randomUUID(), "SETP1");
        double duplicatesBefore = processed("duplicate");

        publishValid(event);
        publishValid(event);

        awaitProcessed("duplicate", duplicatesBefore);
        assertThat(status("VENTA-R-4")).isEqualTo("INVOICED");
        assertThat(jdbc.sql("SELECT version FROM sales WHERE reference_code = 'VENTA-R-4'").query(Long.class).single())
                .as("una sola actualización").isEqualTo(1L);
    }

    @Test
    @DisplayName("orden inesperado: fallo y luego éxito → INVOICED; éxito y luego fallo → sigue INVOICED")
    void unexpectedOrder() {
        confirmedSale("VENTA-R-5");
        confirmedSale("VENTA-R-6");
        double ignoredBefore = processed("ignored");

        publishValid(InvoiceResultEvents.requestRejected("VENTA-R-5", null, "Falló antes"));
        publishValid(InvoiceResultEvents.issued("VENTA-R-5", UUID.randomUUID(), "SETP5"));
        publishValid(InvoiceResultEvents.issued("VENTA-R-6", UUID.randomUUID(), "SETP6"));
        publishValid(InvoiceResultEvents.rejected("VENTA-R-6", UUID.randomUUID(), "Llega tarde"));

        awaitStatus("VENTA-R-5", "INVOICED");
        awaitProcessed("ignored", ignoredBefore);
        assertThat(status("VENTA-R-6")).isEqualTo("INVOICED");
    }

    @Test
    @DisplayName("la factura de otra venta (API REST) o de otro cliente se confirma e ignora, sin DLQ")
    void foreignInvoicesAreIgnored() {
        confirmedSale("VENTA-R-7");
        double ignoredBefore = processed("ignored");

        publishValid(InvoiceResultEvents.issued("FACTURA-REST-1", UUID.randomUUID(), "SETP7"));
        publishValid(InvoiceResultEvents.issued("VENTA-R-7", UUID.randomUUID(), "SETP8", "CITIZENSHIP_CARD", "1020304050"));

        await().atMost(WAIT).until(() -> processed("ignored") >= ignoredBefore + 2);
        assertThat(status("VENTA-R-7")).isEqualTo("CONFIRMED");
        assertThat(rabbit.receive(DLQ, 300)).isNull();
    }

    @Test
    @DisplayName("un mensaje inválido va directo a la DLQ sin reintentos y la venta no cambia")
    void invalidMessageGoesToDeadLetterQueue() {
        confirmedSale("VENTA-R-9");
        String event = InvoiceResultEvents.issued("VENTA-R-9", UUID.randomUUID(), "SETP9", "LIBRETA_MILITAR", "123");
        assertThat(EventContracts.invoiceResultViolations(event)).as("incumple el contrato").isNotEmpty();
        double retriedBefore = processed("retried");

        publish(event);

        Message dead = await().atMost(WAIT).until(() -> rabbit.receive(DLQ, 200), message -> message != null);
        assertThat((String) dead.getMessageProperties().getHeader("x-failure-category")).isEqualTo("invalid");
        assertThat((Integer) dead.getMessageProperties().getHeader("x-attempts-made")).isEqualTo(1);
        assertThat(processed("retried")).isEqualTo(retriedBefore);
        assertThat(status("VENTA-R-9")).isEqualTo("CONFIRMED");
    }
}
