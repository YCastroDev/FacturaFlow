package com.facturaflow.invoicing.infrastructure.adapter.out.persistence;

import com.facturaflow.invoicing.application.port.out.InvoiceEventStore;
import com.facturaflow.invoicing.application.port.out.OutboxBatch;
import com.facturaflow.invoicing.application.port.out.OutboxMessage;
import com.facturaflow.invoicing.application.port.out.OutboxRepository;
import com.facturaflow.invoicing.application.port.out.OutboxStatus;
import com.facturaflow.invoicing.application.port.out.PublicationResult;
import com.facturaflow.invoicing.domain.model.InvoiceEvent;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Adaptador de salida del outbox (ADR 0015; copia deliberada del de sales, ADR 0013). Los eventos de la factura
 * entran por {@link JpaInvoiceRepositoryAdapter#save}, con su cambio de estado; los de peticiones rechazadas, por
 * {@link #append}. Aquí además se publican y se consultan.
 *
 * <p>{@link #publishPending} mantiene la transacción (y el bloqueo de las filas del lote) mientras el publicador
 * envía y espera las confirmaciones del broker: la excepción documentada a "nada externo dentro de una transacción"
 * (ADR 0013, decisión 5). Es un proceso de fondo, acotado por el lote y el timeout de confirmación, y solo bloquea
 * filas del outbox (nunca facturas).
 */
@Component
class JpaOutboxAdapter implements OutboxRepository, InvoiceEventStore {

    /** {@code last_error} es para diagnosticar, no un log: se recorta. */
    static final int MAX_ERROR_LENGTH = 500;

    private final OutboxEventJpaRepository jpaRepository;
    private final EntityManager entityManager;
    private final InvoiceEventMessages messages;
    private final Clock clock;

    JpaOutboxAdapter(OutboxEventJpaRepository jpaRepository, EntityManager entityManager, InvoiceEventMessages messages,
                     Clock clock) {
        this.jpaRepository = jpaRepository;
        this.entityManager = entityManager;
        this.messages = messages;
        this.clock = clock;
    }

    /** Una petición rechazada: no hay factura, el evento es todo lo que se guarda (su propia transacción). */
    @Override
    @Transactional
    public void append(InvoiceEvent.InvoiceRequestRejected event) {
        OutboxMessage message = messages.toMessage(event, clock.instant());
        // persist (no save): el id ya viene asignado y save() haría un merge con SELECT previo.
        entityManager.persist(entity(message, InvoiceEventMessages.INVOICE_REQUEST, null));
        entityManager.flush();
    }

    /** La fila de un mensaje; {@code aggregateId} nulo si el evento no es de un agregado. */
    static OutboxEventEntity entity(OutboxMessage message, String aggregateType, UUID aggregateId) {
        return new OutboxEventEntity(message.eventId(), aggregateType, aggregateId, message.eventType(),
                message.eventVersion(), message.routingKey(), message.payload(), message.occurredAt());
    }

    @Override
    @Transactional
    public OutboxBatch publishPending(int batchSize, Function<List<OutboxMessage>, PublicationResult> publisher) {
        List<OutboxEventEntity> locked = jpaRepository.lockPendingBatch(batchSize);
        if (locked.isEmpty()) {
            return OutboxBatch.empty();
        }
        List<OutboxMessage> batch = locked.stream().map(JpaOutboxAdapter::toMessage).toList();
        PublicationResult result = publisher.apply(batch);

        Set<UUID> confirmed = result.confirmed();
        List<UUID> unconfirmed = batch.stream().map(OutboxMessage::eventId)
                .filter(id -> !confirmed.contains(id)).toList();
        if (!confirmed.isEmpty()) {
            jpaRepository.markPublished(confirmed, clock.instant());
        }
        if (!unconfirmed.isEmpty()) {
            jpaRepository.markFailed(unconfirmed, truncate(result.failure().orElse("Sin confirmación del broker")));
        }
        return new OutboxBatch(batch, result);
    }

    @Override
    @Transactional(readOnly = true)
    public OutboxStatus status() {
        return new OutboxStatus(jpaRepository.countByPublishedAtIsNull(),
                Optional.ofNullable(jpaRepository.findOldestPendingOccurredAt()));
    }

    private static OutboxMessage toMessage(OutboxEventEntity entity) {
        return new OutboxMessage(entity.id, entity.eventType, entity.eventVersion, entity.routingKey, entity.payload,
                entity.occurredAt);
    }

    private static String truncate(String error) {
        return error.length() <= MAX_ERROR_LENGTH ? error : error.substring(0, MAX_ERROR_LENGTH);
    }
}
