package com.facturaflow.invoicing.infrastructure.adapter.in.outbox;

import com.facturaflow.invoicing.application.port.in.GetOutboxStatusUseCase;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.time.Clock;
import java.time.Duration;
import java.util.function.ToDoubleFunction;

/**
 * Métricas del outbox (ADR 0015), calculadas al leerlas con una consulta sobre el índice parcial de
 * pendientes (barata aunque el histórico crezca):
 * <ul>
 *   <li>{@code invoicing.outbox.pending}: eventos aún no confirmados por RabbitMQ;</li>
 *   <li>{@code invoicing.outbox.oldest.pending.age}: segundos que lleva esperando el más antiguo (0 si no hay).
 *       Es la que se vigilaría en producción: un número de pendientes alto puede ser un pico normal; uno
 *       que envejece significa que nada se está publicando.</li>
 * </ul>
 * Si la base no responde, el valor es NaN (Micrometer lo informa como ausente), sin romper el endpoint.
 */
final class OutboxMetrics implements MeterBinder {

    private final GetOutboxStatusUseCase outbox;
    private final Clock clock;

    OutboxMetrics(GetOutboxStatusUseCase outbox, Clock clock) {
        this.outbox = outbox;
        this.clock = clock;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("invoicing.outbox.pending", outbox, safely(status -> status.status().pending()))
                .description("Eventos del outbox aún no confirmados por RabbitMQ")
                .baseUnit("events")
                .register(registry);
        Gauge.builder("invoicing.outbox.oldest.pending.age", outbox, safely(status -> status.status().oldestPendingSince()
                        .map(since -> Math.max(0, Duration.between(since, clock.instant()).toMillis()) / 1000.0)
                        .orElse(0.0)))
                .description("Antigüedad del evento pendiente más antiguo del outbox")
                .baseUnit("seconds")
                .register(registry);
    }

    private static ToDoubleFunction<GetOutboxStatusUseCase> safely(ToDoubleFunction<GetOutboxStatusUseCase> value) {
        return useCase -> {
            try {
                return value.applyAsDouble(useCase);
            } catch (RuntimeException e) {
                return Double.NaN;
            }
        };
    }
}
