package com.facturaflow.sales.infrastructure.adapter.in.messaging;

import com.facturaflow.sales.infrastructure.config.MessagingHealthPart;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.amqp.core.MessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;

/** Parte {@code consumer} del componente {@code messaging}: la cola, si el listener corre y el último resultado. */
final class ConsumerHealthPart implements MessagingHealthPart {

    private final RabbitListenerEndpointRegistry listeners;
    private final ConsumerProperties properties;
    private final ConsumerState state;

    ConsumerHealthPart(RabbitListenerEndpointRegistry listeners, ConsumerProperties properties, ConsumerState state) {
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
        MessageListenerContainer container = listeners.getListenerContainer(InvoiceResultListener.LISTENER_ID);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("queue", properties.queue());
        details.put("listener", container != null && container.isRunning() ? "RUNNING" : "STOPPED");
        details.putAll(state.details());
        return details;
    }
}
