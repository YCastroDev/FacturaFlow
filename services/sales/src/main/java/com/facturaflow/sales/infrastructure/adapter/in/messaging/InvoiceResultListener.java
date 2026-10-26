package com.facturaflow.sales.infrastructure.adapter.in.messaging;

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
 * Consumidor de {@code sales.invoice-results} (ADR 0015), con ack MANUAL y solo después de procesar o de republicar
 * con confirmación. Mismo mecanismo que el consumidor de invoicing (ADR 0014):
 * <ul>
 *   <li>{@link InvoiceResultOutcome.Completed} (aplicado, duplicado o ignorado): ack;</li>
 *   <li>{@link InvoiceResultOutcome.RetryLater}: republica en la espera del intento con {@value #ATTEMPT_HEADER};
 *       agotados los intentos, a la DLQ;</li>
 *   <li>{@link InvoiceResultOutcome.Invalid}: a la DLQ sin reintentos;</li>
 *   <li>si no se pudo republicar: nack CON requeue, nada se pierde.</li>
 * </ul>
 * Los mensajes de la DLQ llevan {@value #FAILURE_CATEGORY_HEADER}, {@value #FAILURE_REASON_HEADER},
 * {@value #ATTEMPTS_MADE_HEADER} y {@value #FAILED_AT_HEADER}, y no {@value #ATTEMPT_HEADER}.
 */
final class InvoiceResultListener {

    static final String LISTENER_ID = "invoiceResults";
    static final String ATTEMPT_HEADER = "x-attempt";
    static final String FAILURE_CATEGORY_HEADER = "x-failure-category";
    static final String FAILURE_REASON_HEADER = "x-failure-reason";
    static final String ATTEMPTS_MADE_HEADER = "x-attempts-made";
    static final String FAILED_AT_HEADER = "x-failed-at";
    static final String TECHNICAL = "technical";
    static final String INVALID = "invalid";
    private static final List<String> BROKER_HEADERS = List.of("x-death", "x-first-death-exchange",
            "x-first-death-queue", "x-first-death-reason", "x-last-death-exchange", "x-last-death-queue",
            "x-last-death-reason");

    private static final Logger LOG = LoggerFactory.getLogger(InvoiceResultListener.class);

    private final InvoiceResultProcessor processor;
    private final ConfirmedRepublisher republisher;
    private final ConsumerProperties properties;
    private final ConsumerState state;
    private final Clock clock;

    InvoiceResultListener(InvoiceResultProcessor processor, ConfirmedRepublisher republisher,
                          ConsumerProperties properties, ConsumerState state, Clock clock) {
        this.processor = processor;
        this.republisher = republisher;
        this.properties = properties;
        this.state = state;
        this.clock = clock;
    }

    @RabbitListener(id = LISTENER_ID, queues = "${sales.invoice-results.queue}", ackMode = "MANUAL",
            concurrency = "${sales.invoice-results.concurrency}")
    void onMessage(Message message, Channel channel) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        String eventId = message.getMessageProperties().getMessageId();
        int attempt = attemptOf(message);
        try {
            switch (processor.process(message.getBody())) {
                case InvoiceResultOutcome.Completed done -> {
                    if (done.result() == ConsumerState.Result.IGNORED) {
                        LOG.info("Evento {} ignorado (venta {}): {}", eventId, done.referenceCode(), done.detail());
                    } else {
                        LOG.info("Venta {}: resultado de facturación {} desde el evento {} ({}).", done.referenceCode(),
                                done.result().tag(), eventId, done.detail());
                    }
                    state.record(done.result(), null);
                }
                case InvoiceResultOutcome.RetryLater retry -> retryOrDeadLetter(message, retry, attempt, eventId);
                case InvoiceResultOutcome.Invalid invalid -> {
                    LOG.error("Mensaje inválido (evento {}, venta {}): {}. Va a la DLQ sin reintentos.", eventId,
                            invalid.referenceCode(), invalid.reason());
                    deadLetter(message, INVALID, invalid.reason(), attempt + 1);
                }
            }
            channel.basicAck(deliveryTag, false);
        } catch (RuntimeException e) {
            LOG.warn("No se pudo completar el mensaje {} ({}); vuelve a la cola.", eventId, e.getMessage());
            state.record(ConsumerState.Result.REQUEUED, e.getMessage());
            channel.basicNack(deliveryTag, false, true);
        }
    }

    private void retryOrDeadLetter(Message message, InvoiceResultOutcome.RetryLater retry, int attempt,
                                   String eventId) {
        List<Duration> delays = properties.retryDelays();
        if (attempt >= delays.size()) {
            LOG.error("Venta {} (evento {}): falla técnica tras {} intento(s): {}. Va a la DLQ.", retry.referenceCode(),
                    eventId, attempt + 1, retry.reason());
            deadLetter(message, TECHNICAL, retry.reason(), attempt + 1);
            return;
        }
        Duration delay = delays.get(attempt);
        republisher.publish(properties.retryExchange(), properties.waitQueue(delay),
                copy(message, Map.of(ATTEMPT_HEADER, attempt + 1), true));
        LOG.warn("Venta {} (evento {}): {}. Reintento {}/{} en {}.", retry.referenceCode(), eventId, retry.reason(),
                attempt + 1, delays.size(), ConsumerProperties.label(delay));
        state.record(ConsumerState.Result.RETRIED, retry.reason());
    }

    private void deadLetter(Message message, String category, String reason, int attemptsMade) {
        Message dead = copy(message, Map.of(
                FAILURE_CATEGORY_HEADER, category,
                FAILURE_REASON_HEADER, InvoiceResultProcessor.shorten(reason),
                ATTEMPTS_MADE_HEADER, attemptsMade,
                FAILED_AT_HEADER, clock.instant().toString()), false);
        republisher.publish(properties.deadLetterExchange(), properties.deadLetterQueue(), dead);
        state.record(ConsumerState.Result.DEAD_LETTERED, reason);
    }

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
