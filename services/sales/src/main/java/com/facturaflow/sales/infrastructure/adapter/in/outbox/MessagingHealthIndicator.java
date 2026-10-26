package com.facturaflow.sales.infrastructure.adapter.in.outbox;

import com.facturaflow.sales.application.port.in.GetOutboxStatusUseCase;
import com.facturaflow.sales.application.port.out.OutboxStatus;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

/**
 * Componente {@code messaging} de {@code /actuator/health} (ADR 0013): INFORMATIVO, como el de catalog y el
 * de Factus. Siempre UP, con los pendientes del outbox y el estado del relay en los detalles. RabbitMQ
 * caído no significa que sales esté mal (vende y guarda los eventos para después), así que nunca baja el
 * health agregado ni entra en liveness/readiness, que reiniciarían la instancia sin arreglar nada.
 *
 * <p>Reemplaza al indicador {@code rabbit} que Spring Boot registra solo al ver un ConnectionFactory, que
 * se desactiva con {@code management.health.rabbit.enabled: false}: ese SÍ bajaría el health a DOWN.
 */
final class MessagingHealthIndicator implements HealthIndicator {

    private final GetOutboxStatusUseCase outbox;
    private final RelayState state;

    MessagingHealthIndicator(GetOutboxStatusUseCase outbox, RelayState state) {
        this.outbox = outbox;
        this.state = state;
    }

    @Override
    public Health health() {
        Health.Builder health = Health.up().withDetails(state.details());
        try {
            OutboxStatus status = outbox.status();
            health.withDetail("pendingEvents", status.pending());
            status.oldestPendingSince().ifPresent(since -> health.withDetail("oldestPendingSince", since.toString()));
        } catch (RuntimeException e) {
            // La base tiene su propio indicador (db); aquí solo se informa.
            health.withDetail("pendingEvents", "desconocido (" + e.getClass().getSimpleName() + ")");
        }
        return health.build();
    }
}
