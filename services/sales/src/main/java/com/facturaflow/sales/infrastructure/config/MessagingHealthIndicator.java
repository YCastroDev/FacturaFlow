package com.facturaflow.sales.infrastructure.config;

import java.util.List;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Componente {@code messaging} de {@code /actuator/health} (ADR 0013 y 0015): INFORMATIVO, como el de catalog. Siempre
 * UP, con una entrada de detalles por parte: {@code outbox} (SaleConfirmed pendientes y relay) y {@code consumer}
 * (resultados de facturación). RabbitMQ caído no significa que sales esté mal (vende, guarda sus eventos y el
 * consumidor se reconecta), así que nunca baja el health ni entra en liveness/readiness. Reemplaza al indicador
 * {@code rabbit} de Spring Boot ({@code management.health.rabbit.enabled: false}), que sí lo bajaría.
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
