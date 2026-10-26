package com.facturaflow.sales.infrastructure.adapter.out.messaging;

import com.facturaflow.sales.application.port.out.EventPublisher;
import com.facturaflow.sales.application.port.out.OutboxMessage;
import com.facturaflow.sales.application.port.out.PublicationResult;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

/**
 * Publica en RabbitMQ con publisher confirms (ADR 0013). Cada mensaje sale:
 * <ul>
 *   <li>persistente ({@code delivery_mode=2}): una cola durable lo guarda en disco y sobrevive a un
 *       reinicio del broker;</li>
 *   <li>con {@code message_id} = {@code eventId} (los consumidores descartan duplicados con él),
 *       {@code type}, {@code app_id}, {@code timestamp}, {@code content_type} y la cabecera
 *       {@code eventVersion}, para enrutar o filtrar sin abrir el cuerpo.</li>
 * </ul>
 * Se envía todo el lote y luego se espera la confirmación de cada mensaje con un único plazo
 * ({@code confirm-timeout}). Solo un ack cuenta como confirmado; un nack, un plazo vencido o un canal que
 * se cierra (p. ej. el exchange no existe) dejan el evento pendiente.
 *
 * <p>Sin {@code mandatory}: si ninguna cola está enlazada (hoy, sin consumidores) el broker descarta el
 * mensaje y aun así lo confirma. Es lo correcto para un evento: el productor no sabe ni le importa quién
 * escucha (coreografía).
 */
final class RabbitEventPublisher implements EventPublisher {

    static final String EVENT_VERSION_HEADER = "eventVersion";
    static final String APP_ID = "sales";

    private final RabbitTemplate template;
    private final String exchange;
    private final Duration confirmTimeout;

    RabbitEventPublisher(RabbitTemplate template, MessagingProperties properties) {
        this.template = template;
        this.exchange = properties.exchange();
        this.confirmTimeout = properties.confirmTimeout();
    }

    @Override
    public PublicationResult publish(List<OutboxMessage> messages) {
        List<Sent> sent = new ArrayList<>();
        String failure = null;
        for (OutboxMessage message : messages) {
            CorrelationData correlation = new CorrelationData(message.eventId().toString());
            try {
                template.send(exchange, message.routingKey(), toAmqp(message), correlation);
            } catch (AmqpException e) {
                // Broker inalcanzable, credenciales rechazadas…: se deja de enviar; lo ya enviado se espera.
                failure = "No se pudo enviar a RabbitMQ: " + e.getClass().getSimpleName();
                break;
            }
            sent.add(new Sent(message.eventId(), correlation));
        }

        Set<UUID> confirmed = new LinkedHashSet<>();
        long deadline = System.nanoTime() + confirmTimeout.toNanos();
        for (Sent message : sent) {
            String problem = awaitConfirm(message.correlation(), deadline);
            if (problem == null) {
                confirmed.add(message.eventId());
            } else if (failure == null) {
                failure = problem;
            }
        }
        return failure == null ? PublicationResult.allConfirmed(confirmed)
                : PublicationResult.partial(confirmed, failure);
    }

    /** {@code null} si el broker hizo ack; si no, el motivo. */
    private static String awaitConfirm(CorrelationData correlation, long deadline) {
        try {
            CorrelationData.Confirm confirm = correlation.getFuture()
                    .get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            return confirm.ack() ? null : "RabbitMQ rechazó el mensaje (nack): " + confirm.reason();
        } catch (TimeoutException e) {
            return "RabbitMQ no confirmó a tiempo";
        } catch (ExecutionException e) {
            return "Falla esperando la confirmación: " + e.getCause().getClass().getSimpleName();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Interrumpido esperando la confirmación";
        }
    }

    private static Message toAmqp(OutboxMessage message) {
        return MessageBuilder.withBody(message.payload().getBytes(StandardCharsets.UTF_8))
                .setMessageId(message.eventId().toString())
                .setType(message.eventType())
                .setHeader(EVENT_VERSION_HEADER, message.eventVersion())
                .setAppId(APP_ID)
                .setTimestamp(Date.from(message.occurredAt()))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setContentEncoding(StandardCharsets.UTF_8.name())
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .build();
    }

    private record Sent(UUID eventId, CorrelationData correlation) {
    }
}
