package com.facturaflow.invoicing.infrastructure.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Fila de {@code outbox_events} (ADR 0015). Se inserta junto con el cambio de estado de la factura (o sola, si
 * es una petición rechazada) y solo cambian su estado de
 * publicación y sus intentos. Sin {@code @Version}: la exclusión entre publicadores la da el bloqueo de
 * fila ({@code FOR UPDATE SKIP LOCKED}), no el bloqueo optimista.
 */
@Entity
@Table(name = "outbox_events")
class OutboxEventEntity {

    @Id
    UUID id;

    @Column(name = "aggregate_type", nullable = false, updatable = false)
    String aggregateType;

    /** Nulo en una petición rechazada (no hay factura). */
    @Column(name = "aggregate_id", updatable = false)
    UUID aggregateId;

    @Column(name = "event_type", nullable = false, updatable = false)
    String eventType;

    @Column(name = "event_version", nullable = false, updatable = false)
    int eventVersion;

    @Column(name = "routing_key", nullable = false, updatable = false)
    String routingKey;

    @Column(nullable = false, updatable = false)
    String payload;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    Instant occurredAt;

    @Column(name = "published_at")
    Instant publishedAt;

    @Column(nullable = false)
    int attempts;

    @Column(name = "last_error")
    String lastError;

    protected OutboxEventEntity() {
    }

    OutboxEventEntity(UUID id, String aggregateType, UUID aggregateId, String eventType, int eventVersion,
                      String routingKey, String payload, Instant occurredAt) {
        this.id = id;
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.eventVersion = eventVersion;
        this.routingKey = routingKey;
        this.payload = payload;
        this.occurredAt = occurredAt;
    }
}
