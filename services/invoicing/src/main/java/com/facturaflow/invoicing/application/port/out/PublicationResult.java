package com.facturaflow.invoicing.application.port.out;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Lo que el broker CONFIRMÓ de un lote (ADR 0015). Solo los ids de {@code confirmed} se marcan como
 * publicados; los demás siguen pendientes y se reintentan en el siguiente ciclo.
 *
 * @param confirmed ids de los eventos que el broker confirmó (publisher confirm con ack)
 * @param failure   descripción breve y sin datos sensibles de por qué el resto no se confirmó; vacía si
 *                  se confirmó todo
 */
public record PublicationResult(Set<UUID> confirmed, Optional<String> failure) {

    public PublicationResult {
        confirmed = Set.copyOf(Objects.requireNonNull(confirmed, "confirmed"));
        Objects.requireNonNull(failure, "failure");
    }

    public static PublicationResult allConfirmed(Set<UUID> confirmed) {
        return new PublicationResult(confirmed, Optional.empty());
    }

    public static PublicationResult partial(Set<UUID> confirmed, String failure) {
        return new PublicationResult(confirmed, Optional.of(failure));
    }
}
