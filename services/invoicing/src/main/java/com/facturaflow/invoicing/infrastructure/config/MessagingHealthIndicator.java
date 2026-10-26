package com.facturaflow.invoicing.infrastructure.config;

import java.util.List;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Componente {@code messaging} de {@code /actuator/health} (ADR 0014 y 0015): INFORMATIVO, como el de Factus (ADR
 * 0010). Siempre UP, con una entrada de detalles por parte: {@code outbox} (eventos de facturación pendientes y
 * relay) y {@code consumer} (SaleConfirmed). RabbitMQ caído no significa que invoicing esté mal (su API atiende,
 * los eventos esperan en el outbox y el consumidor se reconecta), así que nunca baja el health ni entra en
 * liveness/readiness. Reemplaza al indicador {@code rabbit} de Spring Boot
 * ({@code management.health.rabbit.enabled: false}), que sí lo bajaría.
 */
@Component("messagingHealthIndicator")
class MessagingHealthIndicator implements HealthIndicator {

    private final List<MessagingHealthPart> parts;

    MessagingHealthIndicator(List<MessagingHealthPart> parts) {
        this.parts = parts;
    }

    @Override
    public Health health() {
        Health.Builder health = Health.up();
        parts.forEach(part -> health.withDetail(part.name(), part.details()));
        return health.build();
    }
}
