package com.facturaflow.invoicing.infrastructure.adapter.in.messaging;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Lo que el consumidor sabe de sus últimos mensajes, para el health informativo y las métricas (ADR 0014).
 * Cuenta cada resultado en {@code invoicing.sale_confirmed.processed{outcome=...}}.
 */
final class ConsumerState {

    /** Resultado de un mensaje, como etiqueta de la métrica. */
    enum Result {
        ISSUED, REJECTED, DUPLICATE, RETRIED, DEAD_LETTERED, REQUEUED;

        String tag() {
            return name().toLowerCase().replace('_', '-');
        }
    }

    record Snapshot(Instant lastProcessedAt, Result lastResult, Instant lastFailureAt, String lastFailure) {
    }

    private final MeterRegistry registry;
    private final Clock clock;
    private final AtomicReference<Snapshot> current = new AtomicReference<>(new Snapshot(null, null, null, null));

    ConsumerState(MeterRegistry registry, Clock clock) {
        this.registry = registry;
        this.clock = clock;
        for (Result result : Result.values()) {
            counter(result);   // las series existen desde el arranque, en 0
        }
    }

    void record(Result result, String failure) {
        counter(result).increment();
        Instant now = clock.instant();
        current.updateAndGet(previous -> failure == null
                ? new Snapshot(now, result, previous.lastFailureAt(), previous.lastFailure())
                : new Snapshot(now, result, now, failure));
    }

    private Counter counter(Result result) {
        return Counter.builder("invoicing.sale_confirmed.processed")
                .description("Mensajes SaleConfirmed procesados, por resultado")
                .tag("outcome", result.tag())
                .register(registry);
    }

    /** Detalles para el health; omite lo que aún no ocurrió. */
    Map<String, Object> details() {
        Snapshot snapshot = current.get();
        Map<String, Object> details = new LinkedHashMap<>();
        if (snapshot.lastProcessedAt() != null) {
            details.put("lastProcessedAt", snapshot.lastProcessedAt().toString());
            details.put("lastResult", snapshot.lastResult().tag());
        }
        if (snapshot.lastFailureAt() != null) {
            details.put("lastFailureAt", snapshot.lastFailureAt().toString());
            details.put("lastFailure", snapshot.lastFailure());
        }
        return details;
    }
}
