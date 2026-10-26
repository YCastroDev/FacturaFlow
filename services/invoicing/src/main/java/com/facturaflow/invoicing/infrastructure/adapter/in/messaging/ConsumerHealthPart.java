package com.facturaflow.invoicing.infrastructure.adapter.in.messaging;

import com.facturaflow.invoicing.infrastructure.config.MessagingHealthPart;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.amqp.core.MessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;

/**
 * Parte {@code consumer} del componente informativo {@code messaging} (ADR 0014): la cola, si el listener corre
 * y el último resultado. Nunca baja el health: el listener se reconecta solo cuando RabbitMQ vuelve.
 */
final class ConsumerHealthPart implements MessagingHealthPart {

    private final RabbitListenerEndpointRegistry listeners;
    private final MessagingProperties properties;
    private final ConsumerState state;

    ConsumerHealthPart(RabbitListenerEndpointRegistry listeners, MessagingProperties properties, ConsumerState state) {
        this.listeners = listeners;
        this.properties = properties;
        this.state = state;
    }

    @Override
    public String name() {
        return "consumer";
    }

    @Override
    public Map<String, Object> details() {
        MessageListenerContainer container = listeners.getListenerContainer(SaleConfirmedListener.LISTENER_ID);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("queue", properties.queue());
        details.put("listener", container != null && container.isRunning() ? "RUNNING" : "STOPPED");
        details.putAll(state.details());
        return details;
    }
}
