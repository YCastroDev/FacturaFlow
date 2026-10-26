package com.facturaflow.invoicing.application.port.in;

import java.util.Objects;
import java.util.Optional;

/**
 * Resultado de un ciclo del relay.
 *
 * @param taken     eventos pendientes que se tomaron del outbox en este ciclo
 * @param published de ellos, los que el broker confirmó (y quedaron marcados)
 * @param failure   motivo por el que el resto quedó pendiente; vacío si no hubo falla
 */
public record PublishPendingEventsResult(int taken, int published, Optional<String> failure) {

    public PublishPendingEventsResult {
        Objects.requireNonNull(failure, "failure");
    }

    public boolean failed() {
        return failure.isPresent();
    }
}
