package com.facturaflow.sales.infrastructure.adapter.in.outbox;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Lo que el relay sabe de sus últimos ciclos, para el health informativo y para registrar en el log solo
 * los CAMBIOS de estado (sano → fallando → sano) y no una línea por ciclo mientras RabbitMQ está caído.
 * Seguro entre hilos: cada actualización reemplaza una instantánea inmutable.
 */
final class RelayState {

    /** Resultado del último ciclo. {@code IDLE}: aún no corre ninguno. */
    enum Status { IDLE, OK, FAILING }

    record Snapshot(Status status, Instant lastPublishedAt, Instant lastFailureAt, String lastFailure,
                    long consecutiveFailures) {
    }

    private final AtomicReference<Snapshot> current =
            new AtomicReference<>(new Snapshot(Status.IDLE, null, null, null, 0));

    /** Registra un ciclo sin falla; devuelve el estado anterior. */
    Snapshot recordSuccess(Instant now, int published) {
        return current.getAndUpdate(previous -> new Snapshot(Status.OK,
                published > 0 ? now : previous.lastPublishedAt(), previous.lastFailureAt(), previous.lastFailure(), 0));
    }

    /** Registra un ciclo con falla (aunque haya publicado parte del lote); devuelve el estado anterior. */
    Snapshot recordFailure(Instant now, int published, String failure) {
        return current.getAndUpdate(previous -> new Snapshot(Status.FAILING,
                published > 0 ? now : previous.lastPublishedAt(), now, failure, previous.consecutiveFailures() + 1));
    }

    Snapshot snapshot() {
        return current.get();
    }

    /** Detalles para el health; omite lo que aún no ocurrió. */
    Map<String, Object> details() {
        Snapshot snapshot = current.get();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("relay", snapshot.status().name());
        if (snapshot.lastPublishedAt() != null) {
            details.put("lastPublishedAt", snapshot.lastPublishedAt().toString());
        }
        if (snapshot.lastFailureAt() != null) {
            details.put("lastFailureAt", snapshot.lastFailureAt().toString());
            details.put("lastFailure", snapshot.lastFailure());
        }
        details.put("consecutiveFailures", snapshot.consecutiveFailures());
        return details;
    }
}
