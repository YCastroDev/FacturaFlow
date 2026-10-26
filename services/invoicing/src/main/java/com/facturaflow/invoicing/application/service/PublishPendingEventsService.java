package com.facturaflow.invoicing.application.service;

import com.facturaflow.invoicing.application.port.in.PublishPendingEventsResult;
import com.facturaflow.invoicing.application.port.in.PublishPendingEventsUseCase;
import com.facturaflow.invoicing.application.port.out.EventPublisher;
import com.facturaflow.invoicing.application.port.out.OutboxBatch;
import com.facturaflow.invoicing.application.port.out.OutboxMessage;
import com.facturaflow.invoicing.application.port.out.OutboxRepository;
import com.facturaflow.invoicing.application.port.out.PublicationResult;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Relay del outbox (ADR 0015): toma un lote de eventos pendientes y lo publica. Garantías:
 * <ul>
 *   <li>un evento se marca como publicado SOLO si el broker lo confirmó; lo demás queda pendiente y se
 *       reintenta en el ciclo siguiente (entrega "al menos una vez");</li>
 *   <li>una excepción inesperada del publicador no se propaga: cuenta como falla de todo el lote, que
 *       queda pendiente con su intento registrado;</li>
 *   <li>un id "confirmado" que no pertenece al lote se ignora (nunca se marca un evento que no se envió), y
 *       si quedan eventos sin confirmar el resultado siempre lleva un motivo.</li>
 * </ul>
 * Sin transacción aquí: la abre el repositorio, que es quien sabe bloquear el lote (regla de ArchUnit).
 */
public final class PublishPendingEventsService implements PublishPendingEventsUseCase {

    private final OutboxRepository outbox;
    private final EventPublisher publisher;
    private final int batchSize;

    public PublishPendingEventsService(OutboxRepository outbox, EventPublisher publisher, int batchSize) {
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        if (batchSize < 1) {
            throw new IllegalArgumentException("El lote del outbox debe ser de al menos 1 evento: " + batchSize);
        }
        this.batchSize = batchSize;
    }

    @Override
    public PublishPendingEventsResult publishPending() {
        OutboxBatch batch = outbox.publishPending(batchSize, this::publishSafely);
        return new PublishPendingEventsResult(batch.messages().size(), batch.result().confirmed().size(),
                batch.result().failure());
    }

    private PublicationResult publishSafely(List<OutboxMessage> messages) {
        PublicationResult result;
        try {
            result = publisher.publish(messages);
        } catch (RuntimeException e) {
            return PublicationResult.partial(Set.of(), "Falla inesperada al publicar: " + e.getClass().getSimpleName());
        }
        Set<UUID> sent = messages.stream().map(OutboxMessage::eventId).collect(Collectors.toSet());
        Set<UUID> confirmed = result.confirmed().stream().filter(sent::contains).collect(Collectors.toSet());
        if (confirmed.size() == messages.size()) {
            return PublicationResult.allConfirmed(confirmed);
        }
        return PublicationResult.partial(confirmed, result.failure().orElse(
                "El broker no confirmó " + (messages.size() - confirmed.size()) + " evento(s)"));
    }
}
