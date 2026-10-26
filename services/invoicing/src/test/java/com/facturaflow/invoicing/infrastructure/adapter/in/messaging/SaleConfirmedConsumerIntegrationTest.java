package com.facturaflow.invoicing.infrastructure.adapter.in.messaging;

import static com.facturaflow.invoicing.infrastructure.adapter.in.messaging.SaleConfirmedEvents.modified;
import static com.facturaflow.invoicing.infrastructure.adapter.in.messaging.SaleConfirmedEvents.payload;
import static com.facturaflow.invoicing.infrastructure.adapter.in.messaging.SaleConfirmedEvents.sale;
import static com.facturaflow.invoicing.infrastructure.adapter.in.messaging.SaleConfirmedEvents.withSalesTotal;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.facturaflow.invoicing.EventContracts;
import com.facturaflow.invoicing.TestcontainersConfiguration;
import com.facturaflow.invoicing.application.port.out.ElectronicInvoiceProvider;
import com.facturaflow.invoicing.application.port.out.InvoiceRepository;
import com.facturaflow.invoicing.domain.model.Invoice;
import com.facturaflow.invoicing.domain.model.Money;
import com.facturaflow.invoicing.domain.model.ReferenceCode;
import com.jayway.jsonpath.JsonPath;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.rabbitmq.RabbitMQContainer;

/**
 * El consumidor de punta a punta (ADR 0014): RabbitMQ y PostgreSQL reales (Testcontainers) y el proveedor falso,
 * observado con un spy para contar las emisiones. RabbitMQ tiene los usuarios reales (script de init): invoicing
 * consume con el suyo y los eventos se publican con el de sales. Esperas cortas (200/400/600 ms) en lugar de 10 s/1 min/5 min.
 * Cada evento de prueba cumple el contrato de docs/events/ (lo comprueba {@code SaleConfirmedContractTest} y,
 * aquí, cada publicación válida).
 */
@SpringBootTest(properties = {"invoicing.messaging.retry-delays=200ms,400ms,600ms",
        "invoicing.fake.temporary-failures=1"})
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class SaleConfirmedConsumerIntegrationTest {

    private static final String QUEUE = "invoicing.sale-confirmed";
    private static final String DLQ = "invoicing.sale-confirmed.dlq";
    private static final Duration WAIT = Duration.ofSeconds(15);

    /** Como invoicing: lee sus colas (la DLQ). */
    @Autowired
    private RabbitTemplate rabbit;

    @Autowired
    private RabbitMQContainer rabbitContainer;

    /** Como sales: publica en sales.events. */
    private RabbitTemplate sales;

    @Autowired
    private AmqpAdmin admin;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private InvoiceRepository invoices;

    @Autowired
    private MeterRegistry meters;

    @MockitoSpyBean
    private ElectronicInvoiceProvider provider;

    @BeforeEach
    void setUp() {
        jdbc.sql("TRUNCATE TABLE invoices CASCADE").update();
        for (String queue : new String[] {QUEUE, DLQ, QUEUE + ".wait-200ms", QUEUE + ".wait-400ms",
                QUEUE + ".wait-600ms"}) {
            admin.purgeQueue(queue, false);
        }
        clearInvocations(provider);
        if (sales == null) {
            sales = TestcontainersConfiguration.salesTemplate(rabbitContainer);
        }
    }

    /** Publica como lo hace sales: en sales.events, routing key de la v1, message_id = eventId, persistente. */
    private void publish(String event) {
        sales.send(TestcontainersConfiguration.SALES_EVENTS, "sale.confirmed.v1",
                MessageBuilder.withBody(event.getBytes(StandardCharsets.UTF_8))
                        .setMessageId(JsonPath.read(event, "$.eventId"))
                        .setType("SaleConfirmed")
                        .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                        .build());
    }

    private void publishValid(String event) {
        assertThat(EventContracts.saleConfirmedV1Violations(event)).as("el evento de prueba cumple el contrato").isEmpty();
        publish(event);
    }

    private Optional<String> status(String referenceCode) {
        return jdbc.sql("SELECT status FROM invoices WHERE reference_code = :reference")
                .param("reference", referenceCode).query(String.class).optional();
    }

    private void awaitStatus(String referenceCode, String expected) {
        await().atMost(WAIT).untilAsserted(() -> assertThat(status(referenceCode)).contains(expected));
    }

    private double processed(String outcome) {
        return meters.get("invoicing.sale_confirmed.processed").tag("outcome", outcome).counter().count();
    }

    private Message deadLetter() {
        return await().atMost(WAIT).until(() -> rabbit.receive(DLQ, 200), message -> message != null);
    }

    private long invoiceCount() {
        return jdbc.sql("SELECT count(*) FROM invoices").query(Long.class).single();
    }

    @Test
    @DisplayName("una venta confirmada crea la factura y la emite, sin tocar la API de invoicing")
    void saleBecomesIssuedInvoice() {
        publishValid(sale("VENTA-EVT-1"));

        awaitStatus("VENTA-EVT-1", "ISSUED");
        verify(provider, times(1)).issue(any(Invoice.class));
        assertThat(rabbit.receive(DLQ, 200)).isNull();
    }

    @Test
    @DisplayName("un mensaje duplicado deja una sola factura y una sola emisión")
    void duplicateMessageIssuesOnce() {
        String event = sale("VENTA-EVT-DUP");
        double duplicatesBefore = processed("duplicate");

        publishValid(event);
        publishValid(event);

        await().atMost(WAIT).until(() -> processed("duplicate") > duplicatesBefore);
        assertThat(status("VENTA-EVT-DUP")).contains("ISSUED");
        assertThat(invoiceCount()).isEqualTo(1);
        verify(provider, times(1)).issue(any(Invoice.class));
    }

    @Test
    @DisplayName("una falla técnica temporal se reintenta desde la cola de espera y la factura termina emitida")
    void temporaryFailureIsRetriedThenIssued() {
        double retriedBefore = processed("retried");

        publishValid(sale("VENTA-EVT-TEMP", "SIMULAR_FALLA_TEMPORAL"));

        awaitStatus("VENTA-EVT-TEMP", "ISSUED");
        assertThat(processed("retried")).isEqualTo(retriedBefore + 1);
        verify(provider, times(2)).issue(any(Invoice.class));
        assertThat(rabbit.receive(DLQ, 200)).isNull();
    }

    @Test
    @DisplayName("una falla técnica permanente agota los reintentos y termina en la DLQ, con el motivo y sin x-attempt")
    void permanentFailureEndsInDeadLetterQueue() {
        String event = sale("VENTA-EVT-CAIDA", "SIMULAR_CAIDA");

        publishValid(event);

        Message dead = deadLetter();
        MessageProperties properties = dead.getMessageProperties();
        assertThat(properties.getMessageId()).isEqualTo(JsonPath.read(event, "$.eventId"));
        assertThat((String) properties.getHeader("x-failure-category")).isEqualTo("technical");
        assertThat((String) properties.getHeader("x-failure-reason")).startsWith("Proveedor de facturación no disponible");
        assertThat((Integer) properties.getHeader("x-attempts-made")).isEqualTo(4);
        assertThat(properties.getHeaders()).doesNotContainKey("x-attempt");
        assertThat(new String(dead.getBody(), StandardCharsets.UTF_8)).isEqualTo(event);
        assertThat(status("VENTA-EVT-CAIDA")).as("la factura queda PENDING").contains("PENDING");
        verify(provider, times(4)).issue(any(Invoice.class));
    }

    @Test
    @DisplayName("un mensaje inválido va directo a la DLQ, sin reintentos ni factura")
    void invalidMessageGoesStraightToDeadLetterQueue() {
        String event = modified(sale("VENTA-EVT-ROTA"), json -> payload(json).put("paymentMethod", "BITCOIN"));
        assertThat(EventContracts.saleConfirmedV1Violations(event)).as("incumple el contrato").isNotEmpty();
        double retriedBefore = processed("retried");

        publish(event);

        Message dead = deadLetter();
        assertThat((String) dead.getMessageProperties().getHeader("x-failure-category")).isEqualTo("invalid");
        assertThat((String) dead.getMessageProperties().getHeader("x-failure-reason")).contains("BITCOIN");
        assertThat((Integer) dead.getMessageProperties().getHeader("x-attempts-made")).isEqualTo(1);
        assertThat(processed("retried")).isEqualTo(retriedBefore);
        assertThat(invoiceCount()).isZero();
        verify(provider, times(0)).issue(any(Invoice.class));
    }

    @Test
    @DisplayName("un rechazo de la DIAN deja la factura REJECTED: ack, sin reintentos ni DLQ")
    void rejectionIsAcknowledged() {
        double retriedBefore = processed("retried");

        publishValid(sale("VENTA-EVT-RECHAZO", "SIMULAR_RECHAZO"));

        awaitStatus("VENTA-EVT-RECHAZO", "REJECTED");
        verify(provider, times(1)).issue(any(Invoice.class));
        assertThat(processed("retried")).isEqualTo(retriedBefore);
        assertThat(rabbit.receive(DLQ, 500)).isNull();
    }

    @Test
    @DisplayName("totales distintos: WARN con ambos valores y la factura usa los de invoicing")
    void differentTotalsAreLoggedAndInvoicingWins(CapturedOutput output) {
        publishValid(withSalesTotal(sale("VENTA-EVT-TOTAL"), "34966.00"));

        awaitStatus("VENTA-EVT-TOTAL", "ISSUED");
        Invoice invoice = invoices.findByReferenceCode(new ReferenceCode("VENTA-EVT-TOTAL")).orElseThrow();
        assertThat(invoice.total()).isEqualTo(Money.of("34965.00"));
        assertThat(output).contains("Totales distintos entre sales e invoicing para la venta VENTA-EVT-TOTAL",
                "total=34966.00", "total=34965.00");
    }
}
