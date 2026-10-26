package com.facturaflow.sales.infrastructure.adapter.out.persistence;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/** Repositorio Spring Data del outbox, interno al adaptador. */
interface OutboxEventJpaRepository extends JpaRepository<OutboxEventEntity, UUID> {

    /**
     * Bloquea el lote de pendientes más antiguos. {@code SKIP LOCKED} salta las filas que otra transacción
     * (otro publicador) ya bloqueó, en vez de esperarla: cada publicador toma un lote distinto. Debe
     * ejecutarse dentro de una transacción, que mantiene el bloqueo hasta marcar el resultado.
     */
    @Query(value = """
            SELECT * FROM outbox_events
            WHERE published_at IS NULL
            ORDER BY occurred_at, id
            LIMIT :limit
            FOR UPDATE SKIP LOCKED""", nativeQuery = true)
    List<OutboxEventEntity> lockPendingBatch(int limit);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE OutboxEventEntity e SET e.publishedAt = :publishedAt WHERE e.id IN :ids")
    int markPublished(Collection<UUID> ids, Instant publishedAt);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE OutboxEventEntity e SET e.attempts = e.attempts + 1, e.lastError = :error WHERE e.id IN :ids")
    int markFailed(Collection<UUID> ids, String error);

    long countByPublishedAtIsNull();

    @Query("SELECT min(e.occurredAt) FROM OutboxEventEntity e WHERE e.publishedAt IS NULL")
    Instant findOldestPendingOccurredAt();
}
