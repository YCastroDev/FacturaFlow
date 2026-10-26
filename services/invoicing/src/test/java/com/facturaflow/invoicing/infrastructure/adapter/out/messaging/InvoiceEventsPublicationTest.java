package com.facturaflow.invoicing.infrastructure.adapter.out.messaging;

import static com.facturaflow.invoicing.domain.model.InvoiceFixtures.electronicDocument;
import static org.assertj.core.api.Assertions.assertThat;

import com.facturaflow.invoicing.EventContracts;
import com.facturaflow.invoicing.TestcontainersConfiguration;
import com.facturaflow.invoicing.application.port.in.PublishPendingEventsResult;
import com.facturaflow.invoicing.application.port.in.PublishPendingEventsUseCase;
import com.facturaflow.invoicing.application.port.out.InvoiceRepository;
import com.facturaflow.invoicing.application.port.out.RequestHash;
import com.facturaflow.invoicing.domain.model.Invoice;
import com.facturaflow.invoicing.domain.model.InvoiceFixtures;
import com.facturaflow.invoicing.domain.model.InvoiceId;
import com.facturaflow.invoicing.domain.model.PaymentMethod;
import com.facturaflow.invoicing.domain.model.PaymentTerms;
import com.facturaflow.invoicing.domain.model.ReferenceCode;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Factura emitida → outbox → relay → {@code invoicing.events} en RabbitMQ real, con el usuario real de invoicing
 * (ADR 0015). El relay programado está apagado: cada ciclo se invoca a mano. Una cola de prueba (con el prefijo de
 * invoicing, que es lo que su usuario puede declarar) hace de consumidor.
 */
@SpringBootTest(properties = "invoicing.outbox.relay.enabled=false")
@Import(TestcontainersConfiguration.class)
class InvoiceEventsPublicationTest {

    private static final String TEST_QUEUE = "invoicing.test-invoice-events";

    @Autowired
    private InvoiceRepository invoices;

    @Autowired
    private PublishPendingEventsUseCase relay;

    @Autowired
    private RabbitTemplate rabbit;

    @Autowired
    private AmqpAdmin admin;

    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    void setUp() {
        jdbc.sql("TRUNCATE TABLE invoices CASCADE").update();
        jdbc.sql("TRUNCATE TABLE outbox_events").update();
        admin.declareQueue(new Queue(TEST_QUEUE, true, false, false));
        admin.declareBinding(new Binding(TEST_QUEUE, Binding.DestinationType.QUEUE, "invoicing.events", "invoice.#", null));
        admin.purgeQueue(TEST_QUEUE, false);
    }

    @Test
    @DisplayName("una factura emitida publica InvoiceIssued persistente en invoicing.events, que cumple su contrato")
    void issuedInvoiceIsPublished() {
        Invoice invoice = invoices.saveNew(Invoice.create(InvoiceId.generate(), new ReferenceCode("VENTA-PUB-1"),
                InvoiceFixtures.customer(), List.of(InvoiceFixtures.line("1", "100000.00", "0", "19")),
                PaymentMethod.CASH, PaymentTerms.cash(), InvoiceFixtures.ISSUE_DATE, null), new RequestHash("c".repeat(64)));
        invoice.markAsIssued(electronicDocument("119000.00"));
        invoices.save(invoice);

        PublishPendingEventsResult result = relay.publishPending();

        assertThat(result.failed()).isFalse();
        assertThat(result.published()).isEqualTo(1);
        Message message = rabbit.receive(TEST_QUEUE, 5_000);
        assertThat(message).isNotNull();
        assertThat(message.getMessageProperties().getReceivedRoutingKey()).isEqualTo("invoice.issued.v1");
        assertThat(message.getMessageProperties().getReceivedDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
        assertThat(message.getMessageProperties().getAppId()).isEqualTo("invoicing");
        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        assertThat(EventContracts.invoiceEventViolations(body)).isEmpty();
        assertThat(body).contains("\"referenceCode\":\"VENTA-PUB-1\"");
        assertThat(jdbc.sql("SELECT count(*) FROM outbox_events WHERE published_at IS NULL").query(Long.class).single())
                .isZero();
    }
}
