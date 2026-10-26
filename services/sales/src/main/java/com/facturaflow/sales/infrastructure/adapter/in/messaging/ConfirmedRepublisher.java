package com.facturaflow.sales.infrastructure.adapter.in.messaging;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

/**
 * Publica una copia del mensaje (en una cola de espera o en la DLQ) y espera la confirmación del broker
 * (publisher confirm). El listener solo hace ack del original DESPUÉS: así el mensaje nunca se pierde. Si el
 * servicio se cae entre la confirmación y el ack, queda duplicado, y lo absorbe la idempotencia (ADR 0014 y 0015).
 */
final class ConfirmedRepublisher {

    /** No se pudo republicar con confirmación: el listener devuelve el original a la cola (nack con requeue). */
    static final class RepublishFailedException extends RuntimeException {

        RepublishFailedException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final RabbitTemplate template;
    private final Duration confirmTimeout;

    ConfirmedRepublisher(RabbitTemplate template, Duration confirmTimeout) {
        this.template = template;
        this.confirmTimeout = confirmTimeout;
    }

    void publish(String exchange, String routingKey, Message message) {
        CorrelationData correlation = new CorrelationData(message.getMessageProperties().getMessageId());
        try {
            template.send(exchange, routingKey, message, correlation);
            CorrelationData.Confirm confirm = correlation.getFuture()
                    .get(confirmTimeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!confirm.ack()) {
                throw new RepublishFailedException("RabbitMQ rechazó la republicación en " + exchange + " (nack): "
                        + confirm.reason(), null);
            }
        } catch (AmqpException | ExecutionException | TimeoutException e) {
            throw new RepublishFailedException("No se pudo republicar en " + exchange + ": "
                    + e.getClass().getSimpleName(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RepublishFailedException("Interrumpido republicando en " + exchange, e);
        }
    }
}
