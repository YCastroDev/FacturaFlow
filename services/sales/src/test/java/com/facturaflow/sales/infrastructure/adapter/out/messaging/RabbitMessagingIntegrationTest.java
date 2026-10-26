package com.facturaflow.sales.infrastructure.adapter.out.messaging;

import static com.facturaflow.sales.domain.model.SaleFixtures.CREATED_AT;
import static com.facturaflow.sales.domain.model.SaleFixtures.SALE_DATE;
import static com.facturaflow.sales.domain.model.SaleFixtures.customer;
import static com.facturaflow.sales.domain.model.SaleFixtures.line;
import static org.assertj.core.api.Assertions.assertThat;

import com.facturaflow.sales.TestcontainersConfiguration;
import com.facturaflow.sales.application.port.in.PublishPendingEventsResult;
import com.facturaflow.sales.application.port.in.PublishPendingEventsUseCase;
import com.facturaflow.sales.application.port.out.RequestHash;
import com.facturaflow.sales.application.port.out.SaleRepository;
import com.facturaflow.sales.domain.model.PaymentMethod;
import com.facturaflow.sales.domain.model.PaymentTerms;
import com.facturaflow.sales.domain.model.ReferenceCode;
import com.facturaflow.sales.domain.model.Sale;
import com.facturaflow.sales.domain.model.SaleId;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Outbox → relay → RabbitMQ real (Testcontainers), ADR 0013. El relay programado está apagado: cada ciclo
 * se invoca a mano para controlar cuándo publica. Una cola de prueba enlazada a {@code sales.events} hace
 * de consumidor.
 */
@SpringBootTest(properties = "sales.outbox.relay.enabled=false")
@Import(TestcontainersConfiguration.class)
class RabbitMessagingIntegrationTest {

    private static final String TEST_QUEUE = "sales.test-events";
    private static final RequestHash HASH = new RequestHash("b".repeat(64));

    @Autowired
    private SaleRepository sales;

    @Autowired
    private PublishPendingEventsUseCase relay;

    @Autowired
    private RabbitTemplate rabbit;

    @Autowired
    private AmqpAdmin admin;

    @Autowired
    private TopicExchange exchange;

    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    void setUp() {
        jdbc.sql("TRUNCATE sale_line_taxes, sale_lines, sales, outbox_events").update();
        admin.declareExchange(exchange);
        bindTestQueue();
        admin.purgeQueue(TEST_QUEUE, false);
    }

    private void bindTestQueue() {
        // Durable: RabbitMQ 4.3 ya no admite colas transitorias no exclusivas (transient_nonexcl_queues).
        Queue queue = new Queue(TEST_QUEUE, true, false, false);
        admin.declareQueue(queue);
        Binding binding = BindingBuilder.bind(queue).to(exchange).with("sale.#");
        admin.declareBinding(binding);
    }

    private Sale confirmedSale(String reference) {
        Sale sale = Sale.confirm(SaleId.generate(), new ReferenceCode(reference), SALE_DATE, CREATED_AT, customer(),
                List.of(line("2", "18500", "10", "5")), PaymentMethod.CASH, PaymentTerms.cash(), null);
        return sales.saveNew(sale, HASH);
    }

    private UUID outboxIdOf(Sale sale) {
        return jdbc.sql("SELECT id FROM outbox_events WHERE aggregate_id = :id").param("id", sale.id().value())
                .query(UUID.class).single();
    }

    private Integer attemptsOf(UUID eventId) {
        return jdbc.sql("SELECT attempts FROM outbox_events WHERE id = :id AND published_at IS NULL")
                .param("id", eventId).query(Integer.class).optional().orElse(null);
    }

    @Test
    @DisplayName("el evento llega al exchange como mensaje persistente, con message_id = eventId, y queda publicado")
    void publishesPersistentMessageWithConfirm() {
        Sale sale = confirmedSale("VENTA-RMQ-1");
        UUID eventId = outboxIdOf(sale);

        PublishPendingEventsResult result = relay.publishPending();

        assertThat(result.failed()).isFalse();
        assertThat(result.published()).isEqualTo(1);
        Message message = rabbit.receive(TEST_QUEUE, 5_000);
        assertThat(message).isNotNull();
        MessageProperties properties = message.getMessageProperties();
        assertThat(properties.getMessageId()).isEqualTo(eventId.toString());
        assertThat(properties.getReceivedDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
        assertThat(properties.getContentType()).isEqualTo(MessageProperties.CONTENT_TYPE_JSON);
        assertThat(properties.getType()).isEqualTo("SaleConfirmed");
        assertThat(properties.getAppId()).isEqualTo("sales");
        assertThat(properties.getReceivedRoutingKey()).isEqualTo("sale.confirmed.v1");
        assertThat((Integer) properties.getHeader("eventVersion")).isEqualTo(1);
        assertThat(new String(message.getBody(), StandardCharsets.UTF_8))
                .contains("\"eventId\":\"" + eventId + "\"", "\"referenceCode\":\"VENTA-RMQ-1\"");
        assertThat(attemptsOf(eventId)).as("ya no está pendiente").isNull();
        assertThat(rabbit.receive(TEST_QUEUE, 200)).as("un solo mensaje").isNull();
    }

    @Test
    @DisplayName("si RabbitMQ rechaza la publicación, el evento queda pendiente y se publica cuando el broker vuelve")
    void failedPublicationStaysPendingAndIsRetried() {
        Sale sale = confirmedSale("VENTA-RMQ-2");
        UUID eventId = outboxIdOf(sale);
        // El broker no puede aceptar el mensaje: publicar en un exchange inexistente cierra el canal (404).
        admin.deleteExchange(exchange.getName());

        PublishPendingEventsResult failed = relay.publishPending();

        assertThat(failed.failed()).isTrue();
        assertThat(failed.published()).isZero();
        assertThat(attemptsOf(eventId)).as("pendiente, con un intento").isEqualTo(1);

        admin.declareExchange(exchange);
        bindTestQueue();
        PublishPendingEventsResult retried = relay.publishPending();

        assertThat(retried.failed()).isFalse();
        assertThat(retried.published()).isEqualTo(1);
        Message message = rabbit.receive(TEST_QUEUE, 5_000);
        assertThat(message).isNotNull();
        assertThat(message.getMessageProperties().getMessageId()).as("el mismo evento").isEqualTo(eventId.toString());
        assertThat(attemptsOf(eventId)).isNull();
    }
}
