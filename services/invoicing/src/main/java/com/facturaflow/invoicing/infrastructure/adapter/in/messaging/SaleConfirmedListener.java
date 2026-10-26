package com.facturaflow.invoicing.infrastructure.adapter.in.messaging;

import com.facturaflow.invoicing.domain.model.InvoiceStatus;
import com.rabbitmq.client.Channel;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageBuilderSupport;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.annotation.RabbitListener;

/**
 * Consumidor de {@code invoicing.sale-confirmed} (ADR 0014), con ack MANUAL y solo después de procesar:
 * <ul>
 *   <li>{@link SaleConfirmedOutcome.Completed} (emitida, rechazada o duplicada): ack;</li>
 *   <li>{@link SaleConfirmedOutcome.RetryLater}: republica en la cola de espera del intento (10 s, 1 min, 5 min)
 *       con la cabecera {@value #ATTEMPT_HEADER}; agotados los intentos, a la DLQ; luego ack;</li>
 *   <li>{@link SaleConfirmedOutcome.Invalid}: a la DLQ sin reintentos; luego ack;</li>
 *   <li>si no se pudo republicar (el broker falló justo ahí) o algo inesperado: nack CON requeue, nada se pierde.</li>
 * </ul>
 * La republicación es explícita (no el dead-lettering nativo) porque el broker no sabe elegir la espera según el
 * intento ni guardar el motivo. Los mensajes de la DLQ llevan {@value #FAILURE_CATEGORY_HEADER},
 * {@value #FAILURE_REASON_HEADER}, {@value #ATTEMPTS_MADE_HEADER} y {@value #FAILED_AT_HEADER}, y NO llevan
 * {@value #ATTEMPT_HEADER}: al reprocesarlos (scripts/rabbitmq-dlq.sh) empiezan con todos sus intentos.
 */
final class SaleConfirmedListener {

    static final String LISTENER_ID = "saleConfirmed";
    /** Reintentos ya hechos (ausente = primera entrega). */
    static final String ATTEMPT_HEADER = "x-attempt";
    static final String FAILURE_CATEGORY_HEADER = "x-failure-category";
    static final String FAILURE_REASON_HEADER = "x-failure-reason";
    static final String ATTEMPTS_MADE_HEADER = "x-attempts-made";
    static final String FAILED_AT_HEADER = "x-failed-at";
    static final String TECHNICAL = "technical";
    static final String INVALID = "invalid";
    /** Cabeceras que pone RabbitMQ al hacer dead-lettering (TTL vencido); no se copian a la republicación. */
    private static final List<String> BROKER_HEADERS = List.of("x-death", "x-first-death-exchange",
            "x-first-death-queue", "x-first-death-reason", "x-last-death-exchange", "x-last-death-queue",
            "x-last-death-reason");

    private static final Logger LOG = LoggerFactory.getLogger(SaleConfirmedListener.class);

    private final SaleConfirmedProcessor processor;
    private final ConfirmedRepublisher republisher;
    private final MessagingProperties properties;
    private final ConsumerState state;
    private final Clock clock;

    SaleConfirmedListener(SaleConfirmedProcessor processor, ConfirmedRepublisher republisher,
                          MessagingProperties properties, ConsumerState state, Clock clock) {
        this.processor = processor;
        this.republisher = republisher;
        this.properties = properties;
        this.state = state;
        this.clock = clock;
    }

    @RabbitListener(id = LISTENER_ID, queues = "${invoicing.messaging.queue}", ackMode = "MANUAL",
            concurrency = "${invoicing.messaging.concurrency}")
    void onMessage(Message message, Channel channel) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        String eventId = message.getMessageProperties().getMessageId();
        int attempt = attemptOf(message);
        try {
            switch (processor.process(message.getBody())) {
                case SaleConfirmedOutcome.Completed done -> completed(done, eventId);
                case SaleConfirmedOutcome.RetryLater retry -> retryOrDeadLetter(message, retry, attempt, eventId);
                case SaleConfirmedOutcome.Invalid invalid -> {
                    LOG.error("Mensaje inválido (evento {}, venta {}): {}. Va a la DLQ sin reintentos.", eventId,
                            invalid.referenceCode(), invalid.reason());
                    deadLetter(message, INVALID, invalid.reason(), attempt + 1);
                }
            }
            channel.basicAck(deliveryTag, false);
        } catch (RuntimeException e) {
            // No se pudo republicar (o algo inesperado): el original vuelve a la cola, nada se pierde.
            LOG.warn("No se pudo completar el mensaje {} ({}); vuelve a la cola.", eventId, e.getMessage());
            state.record(ConsumerState.Result.REQUEUED, e.getMessage());
            channel.basicNack(deliveryTag, false, true);
        }
    }

    private void completed(SaleConfirmedOutcome.Completed done, String eventId) {
        ConsumerState.Result result = done.duplicate() ? ConsumerState.Result.DUPLICATE
                : done.status() == InvoiceStatus.ISSUED ? ConsumerState.Result.ISSUED : ConsumerState.Result.REJECTED;
        if (done.duplicate()) {
            LOG.info("Evento {} repetido para la venta {}: la factura {} ya existía ({}); no se vuelve a emitir.",
                    eventId, done.referenceCode(), done.invoiceId(), done.status());
        } else {
            LOG.info("Venta {} procesada desde el evento {}: factura {} {}.", done.referenceCode(), eventId,
                    done.invoiceId(), done.status());
        }
        state.record(result, null);
    }

    private void retryOrDeadLetter(Message message, SaleConfirmedOutcome.RetryLater retry, int attempt,
                                   String eventId) {
        List<Duration> delays = properties.retryDelays();
        if (attempt >= delays.size()) {
            LOG.error("Venta {} (evento {}): falla técnica tras {} intento(s): {}. Va a la DLQ.", retry.referenceCode(),
                    eventId, attempt + 1, retry.reason());
            deadLetter(message, TECHNICAL, retry.reason(), attempt + 1);
            return;
        }
        Duration delay = delays.get(attempt);
        String waitQueue = properties.waitQueue(delay);
        republisher.publish(properties.retryExchange(), waitQueue,
                copy(message, Map.of(ATTEMPT_HEADER, attempt + 1), true));
        LOG.warn("Venta {} (evento {}): {}. Reintento {}/{} en {}.", retry.referenceCode(), eventId, retry.reason(),
                attempt + 1, delays.size(), MessagingProperties.label(delay));
        state.record(ConsumerState.Result.RETRIED, retry.reason());
    }

    private void deadLetter(Message message, String category, String reason, int attemptsMade) {
        Message dead = copy(message, Map.of(
                FAILURE_CATEGORY_HEADER, category,
                FAILURE_REASON_HEADER, SaleConfirmedProcessor.shorten(reason),
                ATTEMPTS_MADE_HEADER, attemptsMade,
                FAILED_AT_HEADER, clock.instant().toString()), false);
        republisher.publish(properties.deadLetterExchange(), properties.deadLetterQueue(), dead);
        state.record(ConsumerState.Result.DEAD_LETTERED, reason);
    }

    /**
     * Copia persistente del mensaje, con sus propiedades (message_id = eventId, type…) y cabeceras, sin las que
     * puso el broker, más las nuevas. {@code keepAttempt=false} quita {@value #ATTEMPT_HEADER} (mensaje para la DLQ).
     */
    private static Message copy(Message message, Map<String, Object> headers, boolean keepAttempt) {
        MessageProperties original = message.getMessageProperties();
        MessageBuilderSupport<Message> builder = MessageBuilder.withBody(message.getBody())
                .setMessageId(original.getMessageId())
                .setType(original.getType())
                .setAppId(original.getAppId())
                .setTimestamp(original.getTimestamp())
                .setContentType(original.getContentType())
                .setContentEncoding(original.getContentEncoding())
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        original.getHeaders().forEach((name, value) -> {
            if (!BROKER_HEADERS.contains(name) && (keepAttempt || !ATTEMPT_HEADER.equals(name))) {
                builder.setHeader(name, value);
            }
        });
        headers.forEach(builder::setHeader);
        return builder.build();
    }

    private static int attemptOf(Message message) {
        Object value = message.getMessageProperties().getHeader(ATTEMPT_HEADER);
        return value instanceof Number number ? number.intValue() : 0;
    }
}
