package com.facturaflow.sales.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.facturaflow.sales.application.port.in.PublishPendingEventsResult;
import com.facturaflow.sales.application.port.out.EventPublisher;
import com.facturaflow.sales.application.port.out.OutboxMessage;
import com.facturaflow.sales.application.port.out.PublicationResult;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** El relay del outbox sin Spring ni broker: outbox en memoria y un publicador controlable. */
class PublishPendingEventsServiceTest {

    private static final Instant T0 = Instant.parse("2026-10-23T15:00:00Z");

    /** Publicador falso: registra lo que recibe y responde lo que diga {@code behaviour}. */
    private static final class StubPublisher implements EventPublisher {

        final List<List<OutboxMessage>> calls = new ArrayList<>();
        Function<List<OutboxMessage>, PublicationResult> behaviour = StubPublisher::confirmAll;

        @Override
        public PublicationResult publish(List<OutboxMessage> messages) {
            calls.add(messages);
            return behaviour.apply(messages);
        }

        static PublicationResult confirmAll(List<OutboxMessage> messages) {
            return PublicationResult.allConfirmed(messages.stream().map(OutboxMessage::eventId).collect(Collectors.toSet()));
        }

        static PublicationResult brokerDown(List<OutboxMessage> messages) {
            return PublicationResult.partial(Set.of(), "No se pudo enviar a RabbitMQ: AmqpConnectException");
        }
    }

    private final InMemoryOutboxRepository outbox = new InMemoryOutboxRepository();
    private final StubPublisher publisher = new StubPublisher();
    private final PublishPendingEventsService service = new PublishPendingEventsService(outbox, publisher, 2);

    private OutboxMessage event(int minute) {
        OutboxMessage message = new OutboxMessage(UUID.randomUUID(), "SaleConfirmed", 1, "sale.confirmed.v1",
                "{\"n\":" + minute + "}", T0.plusSeconds(60L * minute));
        outbox.add(message);
        return message;
    }

    @Test
    @DisplayName("sin eventos pendientes no se llama al broker")
    void nothingPending() {
        PublishPendingEventsResult result = service.publishPending();

        assertThat(result.taken()).isZero();
        assertThat(result.failed()).isFalse();
        assertThat(publisher.calls).isEmpty();
    }

    @Test
    @DisplayName("toma un lote del tamaño configurado, del evento más antiguo al más nuevo, y marca lo confirmado")
    void publishesOldestFirstInBatches() {
        OutboxMessage third = event(3);
        OutboxMessage first = event(1);
        OutboxMessage second = event(2);

        PublishPendingEventsResult result = service.publishPending();

        assertThat(result.taken()).isEqualTo(2);
        assertThat(result.published()).isEqualTo(2);
        assertThat(publisher.calls.getFirst()).containsExactly(first, second);
        assertThat(outbox.row(first).published).isTrue();
        assertThat(outbox.row(third).published).isFalse();
        assertThat(outbox.status().pending()).isEqualTo(1);

        service.publishPending();
        assertThat(outbox.row(third).published).isTrue();
        assertThat(outbox.status().pending()).isZero();
    }

    @Test
    @DisplayName("con RabbitMQ caído nada se marca: los eventos quedan pendientes con su intento y se publican al volver")
    void brokerDownKeepsEventsPending() {
        OutboxMessage message = event(1);
        publisher.behaviour = StubPublisher::brokerDown;

        PublishPendingEventsResult down = service.publishPending();

        assertThat(down.failed()).isTrue();
        assertThat(down.published()).isZero();
        assertThat(outbox.row(message).published).isFalse();
        assertThat(outbox.row(message).attempts).isEqualTo(1);
        assertThat(outbox.row(message).lastError).contains("AmqpConnectException");

        publisher.behaviour = StubPublisher::confirmAll;
        PublishPendingEventsResult back = service.publishPending();

        assertThat(back.failed()).isFalse();
        assertThat(outbox.row(message).published).isTrue();
        assertThat(publisher.calls).as("el mismo evento, reenviado").allSatisfy(call -> assertThat(call).containsExactly(message));
    }

    @Test
    @DisplayName("si el broker confirma solo parte del lote, solo esa parte se marca y el resto se reintenta")
    void partialConfirmation() {
        OutboxMessage confirmed = event(1);
        OutboxMessage rejected = event(2);
        publisher.behaviour = messages -> PublicationResult.partial(Set.of(confirmed.eventId()),
                "RabbitMQ rechazó el mensaje (nack)");

        PublishPendingEventsResult result = service.publishPending();

        assertThat(result.published()).isEqualTo(1);
        assertThat(result.failure()).contains("RabbitMQ rechazó el mensaje (nack)");
        assertThat(outbox.row(confirmed).published).isTrue();
        assertThat(outbox.row(rejected).published).isFalse();
        assertThat(outbox.row(rejected).attempts).isEqualTo(1);
    }

    @Test
    @DisplayName("una excepción inesperada del publicador no se propaga: el lote queda pendiente con el motivo")
    void publisherExceptionIsContained() {
        OutboxMessage message = event(1);
        publisher.behaviour = messages -> {
            throw new IllegalStateException("boom");
        };

        PublishPendingEventsResult result = service.publishPending();

        assertThat(result.failed()).isTrue();
        assertThat(outbox.row(message).published).isFalse();
        assertThat(outbox.row(message).lastError).isEqualTo("Falla inesperada al publicar: IllegalStateException");
    }

    @Test
    @DisplayName("nunca se marca un evento que no estaba en el lote, y lo no confirmado siempre lleva un motivo")
    void ignoresForeignConfirmationsAndAlwaysExplains() {
        OutboxMessage message = event(1);
        publisher.behaviour = messages -> PublicationResult.allConfirmed(Set.of(UUID.randomUUID()));

        PublishPendingEventsResult result = service.publishPending();

        assertThat(result.published()).isZero();
        assertThat(result.failure()).contains("El broker no confirmó 1 evento(s)");
        assertThat(outbox.row(message).published).isFalse();
    }

    @Test
    @DisplayName("el lote es de al menos un evento")
    void batchSizeMustBePositive() {
        assertThatThrownBy(() -> new PublishPendingEventsService(outbox, publisher, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
