package com.facturaflow.invoicing.infrastructure.adapter.in.messaging;

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
 * Topología del consumidor (ADR 0014), declarada por invoicing (la cola es del consumidor). {@code RabbitAdmin}
 * la crea en cada conexión: si RabbitMQ estaba caído al arrancar, se crea al volver.
 * <pre>
 * sales.events ──sale.confirmed.v1──▶ invoicing.sale-confirmed ──(consumidor)
 *                                        ▲              │ falla técnica: republica con x-attempt
 *     TTL vencido ── invoicing.requeue ──┘              ▼
 *                     invoicing.sale-confirmed.wait-10s | wait-1m | wait-5m  ◀── invoicing.retry
 *                                                       │ intentos agotados o mensaje inválido
 *                                                       ▼
 *                     invoicing.sale-confirmed.dlq  ◀── invoicing.dlx
 * </pre>
 * Todo durable (RabbitMQ 4.3 ya no admite colas transitorias no exclusivas). Las colas de espera no tienen
 * consumidores: el mensaje espera su TTL y el broker lo devuelve por {@code invoicing.requeue} a la cola
 * principal, y SOLO a ella (no a otros consumidores de sales.events).
 *
 * <p>El regreso usa un exchange propio y no el exchange por defecto ({@code ""}): declarar una cola con un
 * dead-letter exchange exige permiso de escritura sobre él, y escribir en {@code amq.default} permitiría a
 * invoicing publicar en CUALQUIER cola del vhost. Con {@code invoicing.requeue} le basta su permiso sobre
 * {@code invoicing.*} (ADR 0014).
 */
final class SaleConfirmedTopology {

    private SaleConfirmedTopology() {
    }

    static Declarables declarables(MessagingProperties properties) {
        List<Declarable> declarables = new ArrayList<>();
        // Red de seguridad: un reject sin requeue (no previsto) también termina en la DLQ, no se pierde.
        Queue main = new Queue(properties.queue(), true, false, false, Map.of(
                "x-dead-letter-exchange", properties.deadLetterExchange(),
                "x-dead-letter-routing-key", properties.deadLetterQueue()));
        declarables.add(main);
        // El exchange de sales NO se declara aquí (invoicing no tiene permiso configure sobre él): lo declaran
        // sales y rabbitmq-init. Solo se enlaza la cola.
        declarables.add(new Binding(properties.queue(), Binding.DestinationType.QUEUE, properties.sourceExchange(),
                properties.routingKey(), null));

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
