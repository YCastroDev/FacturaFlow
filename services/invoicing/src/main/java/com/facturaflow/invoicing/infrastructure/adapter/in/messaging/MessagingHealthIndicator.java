package com.facturaflow.invoicing.infrastructure.adapter.in.messaging;

import org.springframework.amqp.core.MessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

/**
 * Componente {@code messaging} de {@code /actuator/health} (ADR 0014): INFORMATIVO, como el de Factus (ADR 0010).
 * Siempre UP, con la cola, si el listener corre y el último resultado en los detalles. RabbitMQ caído no significa
 * que invoicing esté mal (su API sigue atendiendo y el listener se reconecta solo), así que nunca baja el health
 * ni entra en liveness/readiness. Reemplaza al indicador {@code rabbit} de Spring Boot
 * ({@code management.health.rabbit.enabled: false}), que sí lo bajaría.
 */
final class MessagingHealthIndicator implements HealthIndicator {

    private final RabbitListenerEndpointRegistry listeners;
    private final MessagingProperties properties;
    private final ConsumerState state;

    MessagingHealthIndicator(RabbitListenerEndpointRegistry listeners, MessagingProperties properties,
                             ConsumerState state) {
        this.listeners = listeners;
        this.properties = properties;
        this.state = state;
    }

    @Override
    public Health health() {
        MessageListenerContainer container = listeners.getListenerContainer(SaleConfirmedListener.LISTENER_ID);
        return Health.up()
                .withDetail("queue", properties.queue())
                .withDetail("listener", container != null && container.isRunning() ? "RUNNING" : "STOPPED")
                .withDetails(state.details())
                .build();
    }
}
