package com.facturaflow.sales.infrastructure.adapter.out.persistence;

import com.facturaflow.sales.application.port.out.OutboxBatch;
import com.facturaflow.sales.application.port.out.OutboxMessage;
import com.facturaflow.sales.application.port.out.OutboxRepository;
import com.facturaflow.sales.application.port.out.OutboxStatus;
import com.facturaflow.sales.application.port.out.PublicationResult;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Adaptador de salida del outbox (ADR 0013). Los eventos entran por {@link JpaSaleRepositoryAdapter#saveNew};
 * aquí se publican y se consultan.
 *
 * <p>{@link #publishPending} mantiene la transacción (y el bloqueo de las filas del lote) mientras el
 * publicador envía y espera las confirmaciones del broker. Es la excepción documentada a "nada externo
 * dentro de una transacción" (ADR 0013, decisión del relay): es un proceso de fondo, fuera de las
 * peticiones de los usuarios; lo acotan el tamaño del lote y el timeout de confirmación, y solo bloquea
 * filas del outbox (nunca ventas), así que no compite con la creación de ventas.
 */
@Component
class JpaOutboxAdapter implements OutboxRepository {

    /** {@code last_error} es para diagnosticar, no un log: se recorta. */
    static final int MAX_ERROR_LENGTH = 500;

    private final OutboxEventJpaRepository jpaRepository;
    private final Clock clock;

    JpaOutboxAdapter(OutboxEventJpaRepository jpaRepository, Clock clock) {
        this.jpaRepository = jpaRepository;
        this.clock = clock;
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
