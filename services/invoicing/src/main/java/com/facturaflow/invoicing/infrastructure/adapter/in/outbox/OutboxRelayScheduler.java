package com.facturaflow.invoicing.infrastructure.adapter.in.outbox;

import com.facturaflow.invoicing.application.port.in.PublishPendingEventsResult;
import com.facturaflow.invoicing.application.port.in.PublishPendingEventsUseCase;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Relay del outbox (ADR 0015): cada {@code invoicing.outbox.poll-interval} publica un lote de eventos
 * pendientes. {@code fixedDelay}: el siguiente ciclo empieza cuando termina el anterior, nunca se solapan
 * en una instancia (entre instancias los separa {@code SKIP LOCKED}).
 *
 * <p>Con RabbitMQ caído, las facturas se siguen emitiendo y sus eventos esperan en el outbox; el log solo
 * registra el cambio de estado (WARN al empezar a fallar, INFO al recuperarse), no una línea por ciclo.
 */
final class OutboxRelayScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(OutboxRelayScheduler.class);

    private final PublishPendingEventsUseCase useCase;
    private final RelayState state;
    private final Clock clock;

    OutboxRelayScheduler(PublishPendingEventsUseCase useCase, RelayState state, Clock clock) {
        this.useCase = useCase;
        this.state = state;
        this.clock = clock;
    }

    @Scheduled(initialDelayString = "${invoicing.outbox.poll-interval}", fixedDelayString = "${invoicing.outbox.poll-interval}")
    void relay() {
        PublishPendingEventsResult result;
        try {
            result = useCase.publishPending();
        } catch (RuntimeException e) {
            // P. ej. la base no responde: nada se marcó; el ciclo siguiente lo reintenta.
            failed(0, "No se pudo leer el outbox: " + e.getClass().getSimpleName());
            return;
        }
        if (result.failed()) {
            failed(result.published(), result.failure().orElseThrow());
            return;
        }
        RelayState.Snapshot previous = state.recordSuccess(clock.instant(), result.published());
        if (previous.status() == RelayState.Status.FAILING) {
            LOG.info("Outbox: publicación restablecida tras {} ciclo(s) fallido(s); {} evento(s) publicado(s)",
                    previous.consecutiveFailures(), result.published());
        } else if (result.taken() > 0) {
            LOG.debug("Outbox: {} evento(s) publicado(s)", result.published());
        }
    }

    private void failed(int published, String failure) {
        RelayState.Snapshot previous = state.recordFailure(clock.instant(), published, failure);
        if (previous.status() != RelayState.Status.FAILING) {
            LOG.warn("Outbox: no se pudieron publicar eventos ({}). Quedan pendientes y se reintentan cada ciclo.",
                    failure);
        } else {
            LOG.debug("Outbox: sigue fallando la publicación ({})", failure);
        }
    }
}
