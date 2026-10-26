package com.facturaflow.sales.infrastructure.adapter.in.messaging;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;

/**
 * Topología del consumidor de resultados (ADR 0015), declarada por sales con su prefijo:
 * <pre>
 * invoicing.events ──invoice.issued.v1 | invoice.rejected.v1 | invoice-request.rejected.v1──▶ sales.invoice-results
 *     TTL vencido ── sales.requeue ──▶ (cola principal)          ◀── sales.retry ── wait-10s | wait-1m | wait-5m
 *     intentos agotados o mensaje inválido ──▶ sales.dlx ──▶ sales.invoice-results.dlq
 * </pre>
 * Igual que la de invoicing (ADR 0014): todo durable, TTL por cola, regreso por un exchange propio (no por
 * {@code amq.default}, que permitiría publicar en cualquier cola) y DLQ como red de seguridad de la cola principal.
 */
final class InvoiceResultTopology {

    private InvoiceResultTopology() {
    }

    static Declarables declarables(ConsumerProperties properties) {
        List<Declarable> declarables = new ArrayList<>();
        declarables.add(new Queue(properties.queue(), true, false, false, Map.of(
                "x-dead-letter-exchange", properties.deadLetterExchange(),
                "x-dead-letter-routing-key", properties.deadLetterQueue())));
        // El exchange de invoicing NO se declara aquí (sales no tiene permiso configure sobre él): solo se enlaza.
        properties.routingKeys().forEach(key -> declarables.add(new Binding(properties.queue(),
                Binding.DestinationType.QUEUE, properties.sourceExchange(), key, null)));

        DirectExchange requeue = new DirectExchange(properties.requeueExchange(), true, false);
        declarables.add(requeue);
        declarables.add(new Binding(properties.queue(), Binding.DestinationType.QUEUE, requeue.getName(),
                properties.queue(), null));

        DirectExchange retry = new DirectExchange(properties.retryExchange(), true, false);
        declarables.add(retry);
        for (Duration delay : properties.retryDelays()) {
            String name = properties.waitQueue(delay);
            declarables.add(new Queue(name, true, false, false, Map.of(
                    "x-message-ttl", delay.toMillis(),
                    "x-dead-letter-exchange", requeue.getName(),
                    "x-dead-letter-routing-key", properties.queue())));
            declarables.add(new Binding(name, Binding.DestinationType.QUEUE, retry.getName(), name, null));
        }

        DirectExchange deadLetters = new DirectExchange(properties.deadLetterExchange(), true, false);
        declarables.add(deadLetters);
        declarables.add(new Queue(properties.deadLetterQueue(), true, false, false));
        declarables.add(new Binding(properties.deadLetterQueue(), Binding.DestinationType.QUEUE, deadLetters.getName(),
                properties.deadLetterQueue(), null));
        return new Declarables(declarables);
    }
}
