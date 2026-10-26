package com.facturaflow.invoicing.application.port.out;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Estado del outbox para el health informativo y las métricas (ADR 0015).
 *
 * @param pending             eventos aún no confirmados por el broker
 * @param oldestPendingSince  {@code occurredAt} del pendiente más antiguo; vacío si no hay pendientes
 */
public record OutboxStatus(long pending, Optional<Instant> oldestPendingSince) {

    public OutboxStatus {
        Objects.requireNonNull(oldestPendingSince, "oldestPendingSince");
    }
}
